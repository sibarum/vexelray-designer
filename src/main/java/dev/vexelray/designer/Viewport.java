package dev.vexelray.designer;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.input.DragEvent;
import sibarum.tactroller.api.InputEvent;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.gui.plot.Camera;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.shader.Shadings;
import dev.vexelray.surface.Surface;
import dev.vexelray.technique.sdf.MarchSettings;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.SampledColorTarget;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The 3D view: the design compiled to a distance field, ray-marched into a target, shown as a box that samples,
 * with a ground grid drawn over it.
 *
 * <p>The shape of this is ported from {@code calculator-vexel-demo}'s {@code SdfViewport}, whose two structural
 * decisions are the ones worth keeping. <b>The march happens between frames</b>: {@code renderInto} allocates a
 * command pool, submits and waits, and a {@code VkQueue} is not thread-safe, so a drag handler running on a
 * worker must only move the camera and raise a flag — {@link #pump()} is called from the frame loop and is the
 * only place the GPU is touched. And <b>there are two kinds of dirty</b>: a scene change means new SPIR-V and a
 * new pipeline, while a camera change is six floats of push constant. Orbiting recompiles nothing.
 *
 * <h2>The grid is drawn, not marched</h2>
 * A {@code Picture} on the same node draws over the image and under the border, clipped to the box — so the
 * grid costs no shader and no recompile, and a toggle is one prop write.
 *
 * <p>It has to agree with the march about where the floor is, and agreeing is not automatic:
 * {@code Camera.project} is <b>orthographic</b>, while the march builds a <b>perspective</b> ray through a
 * focal length. Projecting the grid with {@code Camera.project} would draw a floor that slid against the
 * objects standing on it as the camera turned. So {@link #project} inverts {@code SdfComposer}'s own ray
 * construction instead — same eye, same yaw and pitch, same focal length, same aspect — which is what makes the
 * two line up by construction rather than by two derivations that happen to match.
 *
 * <p><b>Known limitation</b>: the grid is painted over the marched image and so is not occluded by geometry —
 * a line behind a sphere still draws in front of it. Fixing that needs the march's hit depth, which is the
 * pick pass (R6/P7). Until then the grid reads as an overlay, which for a prototype is honest enough.
 */
final class Viewport {

    /** How far the eye orbits from the origin, before zoom. */
    private static final double BASE_DISTANCE = 5.0;

    /** A drag across the whole box turns the picture this far. */
    private static final double DRAG_YAW = Math.PI;
    private static final double DRAG_PITCH = Math.PI / 2;

    /** One notch of zoom: a root of two, so two notches are a doubling. */
    private static final double ZOOM_STEP = Math.sqrt(2);
    private static final double MIN_DISTANCE = 1.2;
    private static final double MAX_DISTANCE = 40;

    private static final double FOCAL_LENGTH = 1.4;

    /** Re-mint the target once the box exceeds it by this much. */
    private static final float GROWTH = 1.35f;

    private static final int PUSH_BYTES = SdfComposer.CAMERA_BYTES;

    /** How far the ground grid runs, and how far apart its lines are. */
    private static final int GRID_HALF = 6;
    private static final double GRID_STEP = 1.0;

    private final Gui gui;
    private final Node canvas;
    private final Consumer<String> status;

    private volatile GuiApp app;
    private SampledColorTarget target;
    private GraphicsPipeline pipeline;

    private volatile byte[] vertexSpirv;
    private volatile byte[] fragmentSpirv;
    private volatile SdfScene.Rgb sky = new SdfScene.Rgb(0.09, 0.10, 0.13);

    private volatile boolean sceneDirty;
    private volatile boolean frameDirty;
    private volatile boolean gridOn = true;
    private volatile String report = "empty";

    /** Whether the first marched frame has been reported &mdash; a windowed frame cannot be photographed here. */
    private boolean announced;

    /** Latest-wins guard, so a burst of edits composes once rather than a dozen times. */
    private final AtomicInteger revision = new AtomicInteger();

    private Camera camera = Camera.DEFAULT;
    private double distance = BASE_DISTANCE;

    Viewport(Gui gui, Consumer<String> status) {
        this.gui = gui;
        this.status = status;
        this.canvas = gui.box()
                .width(Length.FILL).height(Length.grow(1))
                .background(Color.rgb(0.09f, 0.10f, 0.13f))
                .corner(Length.rem(0.4f))
                .clip(true);
        gui.onDrag(canvas, this::drag);
        // Turning is a displacement, so the pointer is held for the gesture and warped back each frame.
        gui.dragLocksPointer(canvas, true);
        gui.onResize(canvas, box -> {
            frameDirty = true;
            redrawGrid();
        });
        // The wheel arrives straight off the device bus rather than through a node handler: scroll dispatch
        // belongs to scrollable containers, and a viewport is emphatically not one. A marched zoom moves in
        // whole notches, so there is nothing to keep under the cursor between them.
        gui.bus().subscribe(InputTopics.INPUT, event -> {
            if (event instanceof InputEvent.Scrolled s && s.yOffset() != 0) {
                zoom(s.yOffset() > 0 ? 1 : -1);
            }
        });
    }

    Node node() {
        return canvas;
    }

    /** Remembered so {@link #pump()} can mint a target; the device is not public and this is how it is reached. */
    void attach(GuiApp app) {
        this.app = app;
    }

    // --- the scene ---

    /**
     * Show {@code surface}. Composed on a worker, because lowering and SPIR-V generation is where the cost is
     * and none of it touches the GPU. Null is an empty design and draws the sky.
     */
    void show(Surface surface) {
        int mine = revision.incrementAndGet();
        gui.async(() -> {
            if (revision.get() == mine) {
                showNow(surface);
            }
        });
    }

    private void showNow(Surface surface) {
        if (surface == null) {
            // Nothing to march. An empty tree is an ordinary state, not an error, so it gets a picture of
            // nothing rather than the last design left standing.
            surface = new Surface.Sphere(0, -1e6, 0, 1e-3);
            report = "empty";
        }
        try {
            long t0 = System.nanoTime();
            SdfScene scene = new SdfScene(surface, Shadings.defaultKeyLight(), MarchSettings.DEFAULT,
                    new SdfScene.Rgb(0.72, 0.74, 0.78), sky, FOCAL_LENGTH);
            // Both stages from one call: index 0 is the fullscreen vertex, index 1 the march.
            List<ComposedShader> composed = new SdfComposer().compose(scene);
            byte[] vs = composed.get(0).spirv();
            byte[] fs = composed.get(1).spirv();
            double ms = (System.nanoTime() - t0) / 1e6;
            this.fragmentSpirv = fs;
            this.vertexSpirv = vs;
            this.sceneDirty = true;
            this.frameDirty = true;
            report = String.format("%,d B in %.0f ms", fs.length, ms);
            System.out.println("composed: " + report);
            status.accept(report);
        } catch (Throwable t) {
            // A surface too large to lower is the six-operator ceiling (R2), and it is a thing a user will hit
            // by stacking modifiers. Say so and keep the last good picture rather than dying.
            String m = t.getMessage();
            status.accept("cannot compile: " + (m == null ? t.getClass().getSimpleName() : m));
        }
    }

    // --- the camera ---

    private void drag(DragEvent e) {
        if (e.phase() != DragEvent.Phase.MOVE) {
            return;                                   // letting go is not a movement
        }
        double dYaw = e.dx() / Math.max(1f, e.nodeW()) * DRAG_YAW;
        double dPitch = e.dy() / Math.max(1f, e.nodeH()) * DRAG_PITCH;
        if (dYaw != 0 || dPitch != 0) {
            turn(dYaw, dPitch);
        }
    }

    synchronized void turn(double dYaw, double dPitch) {
        camera = camera.turned(dYaw, dPitch);
        frameDirty = true;
        redrawGrid();
    }

    synchronized void zoom(int notches) {
        distance = Math.min(MAX_DISTANCE,
                Math.max(MIN_DISTANCE, distance * Math.pow(ZOOM_STEP, notches)));
        frameDirty = true;
        redrawGrid();
    }

    synchronized void home() {
        camera = Camera.DEFAULT;
        distance = BASE_DISTANCE;
        frameDirty = true;
        redrawGrid();
    }

    void grid(boolean on) {
        this.gridOn = on;
        redrawGrid();
    }

    boolean grid() {
        return gridOn;
    }

    /** The eye in world coordinates. Plot space is z-up and the marched world is y-up, so the two swap. */
    private synchronized double[] worldEye() {
        double[] at = camera.eye(distance);
        return new double[]{at[0], at[2], at[1]};
    }

    // --- the grid overlay ---

    private void redrawGrid() {
        Rect box = canvas.layout().content();
        int w = Math.round(box.w());
        int h = Math.round(box.h());
        if (w < 2 || h < 2) {
            return;
        }
        if (!gridOn) {
            canvas.picture(new Sketch().picture());
            return;
        }
        double yaw;
        double pitch;
        double[] eye;
        synchronized (this) {
            yaw = camera.yaw();
            pitch = camera.pitch();
            eye = worldEye();
        }
        double aspect = (double) w / h;
        Color line = Color.rgb(0.26f, 0.29f, 0.35f);
        Color axisX = Color.rgb(0.62f, 0.30f, 0.32f);
        Color axisZ = Color.rgb(0.30f, 0.48f, 0.62f);

        Sketch sketch = new Sketch().tag("grid");
        double span = GRID_HALF * GRID_STEP;
        for (int i = -GRID_HALF; i <= GRID_HALF; i++) {
            double c = i * GRID_STEP;
            boolean axis = i == 0;
            segment(sketch, eye, yaw, pitch, aspect, w, h, c, -span, c, span,
                    axis ? axisZ : line, axis ? 1.6 : 1.0);
            segment(sketch, eye, yaw, pitch, aspect, w, h, -span, c, span, c,
                    axis ? axisX : line, axis ? 1.6 : 1.0);
        }
        canvas.picture(sketch.picture());
    }

    /** One floor segment from {@code (x0,z0)} to {@code (x1,z1)}, near-clipped and drawn if any of it is in front. */
    private void segment(Sketch sketch, double[] eye, double yaw, double pitch, double aspect,
                         int w, int h, double x0, double z0, double x1, double z1,
                         Color color, double thickness) {
        double[] a = {x0, 0, z0};
        double[] b = {x1, 0, z1};
        double da = depth(eye, yaw, pitch, a);
        double db = depth(eye, yaw, pitch, b);
        final double near = 0.05;
        if (da < near && db < near) {
            return;                                   // entirely behind the eye
        }
        if (da < near || db < near) {
            // Clip to the near plane, or a point just behind the camera projects to a wild coordinate and the
            // line whips across the screen.
            double t = (near - da) / (db - da);
            double[] cut = {a[0] + (b[0] - a[0]) * t, 0, a[2] + (b[2] - a[2]) * t};
            if (da < near) {
                a = cut;
            } else {
                b = cut;
            }
        }
        double[] pa = project(eye, yaw, pitch, aspect, w, h, a);
        double[] pb = project(eye, yaw, pitch, aspect, w, h, b);
        sketch.line(pa[0], pa[1], pb[0], pb[1], thickness, color);
    }

    /**
     * How far in front of the eye a world point is, along the view axis — the denominator of {@link #project},
     * and what a near clip tests.
     */
    private static double depth(double[] eye, double yaw, double pitch, double[] p) {
        double dx = p[0] - eye[0];
        double dy = p[1] - eye[1];
        double dz = p[2] - eye[2];
        double pz = dx * Math.sin(yaw) + dz * Math.cos(yaw);
        return pz * Math.cos(pitch) - dy * Math.sin(pitch);
    }

    /**
     * A world point in the node's pixels, by inverting {@code SdfComposer.primaryRay}.
     *
     * <p>The march builds, for a pixel {@code (u,v)}: {@code sx = (2u-1)*aspect}, {@code sy = 1-2v}, pitches
     * {@code (sy, focal)} and then yaws the result. Going the other way is that composition run backwards on
     * {@code p - eye}, scaled so the forward component equals the focal length. Same numbers as the shader, so
     * the floor sits where the objects standing on it think it does.
     */
    private static double[] project(double[] eye, double yaw, double pitch, double aspect,
                                    int w, int h, double[] p) {
        double dx = p[0] - eye[0];
        double dy = p[1] - eye[1];
        double dz = p[2] - eye[2];

        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double sx = dx * cosYaw - dz * sinYaw;        // undo the yaw
        double pz = dx * sinYaw + dz * cosYaw;

        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        double sy = dy * cosPitch + pz * sinPitch;    // undo the pitch
        double forward = pz * cosPitch - dy * sinPitch;

        double k = FOCAL_LENGTH / forward;            // scale so the forward component is the focal length
        double u = (sx * k / aspect + 1) / 2;
        double v = (1 - sy * k) / 2;
        return new double[]{u * w, v * h};
    }

    // --- the frame ---

    /** March one frame if anything asked for one. <b>Render thread only.</b> */
    void pump() {
        GuiApp live = app;
        byte[] vs = vertexSpirv;
        byte[] fs = fragmentSpirv;
        if (live == null || vs == null || fs == null) {
            return;
        }
        Rect box = canvas.layout().content();
        int w = Math.round(box.w());
        int h = Math.round(box.h());
        if (w < 2 || h < 2) {
            return;                                   // not laid out yet; the next frame will find it
        }
        boolean fresh = ensureTarget(live, w, h);
        if (ensurePipeline(vs, fs) || fresh) {
            frameDirty = true;
        }
        if (!frameDirty || pipeline == null || target == null) {
            return;
        }
        frameDirty = false;
        if (!announced) {
            announced = true;
            System.out.println("marched " + target.width() + "x" + target.height() + " into a viewport");
        }
        SdfScene.Rgb bg = sky;
        target.renderInto(pipeline, 0L, 0L, 3, cameraBytes((double) w / h),
                (float) bg.r(), (float) bg.g(), (float) bg.b(), 1f);
    }

    /** @return whether a new target was minted, which means the node has to be pointed at it. */
    private boolean ensureTarget(GuiApp live, int w, int h) {
        if (target != null && w <= target.width() * GROWTH && h <= target.height() * GROWTH) {
            return false;
        }
        // The superseded target is deliberately not closed: the application minted it and closes every one it
        // minted at shutdown, and close() is not idempotent.
        target = live.viewport(w, h);
        canvas.image(target);
        return true;
    }

    private boolean ensurePipeline(byte[] vs, byte[] fs) {
        if (!sceneDirty && pipeline != null) {
            return false;
        }
        sceneDirty = false;
        if (pipeline != null) {
            // Safe here and nowhere else: renderInto waits for its own submission, and the presenter only
            // samples the image this pipeline drew into.
            pipeline.close();
        }
        pipeline = target.pipelineFor(vs, "main", fs, "main", PUSH_BYTES);
        return true;
    }

    private byte[] cameraBytes(double aspect) {
        double yaw;
        double pitch;
        double[] eye;
        synchronized (this) {
            yaw = camera.yaw();
            pitch = camera.pitch();
            eye = worldEye();
        }
        return SdfComposer.cameraBytes(eye[0], eye[1], eye[2], yaw, pitch, aspect);
    }
}
