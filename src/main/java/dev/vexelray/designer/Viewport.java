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
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.shader.Shadings;
import dev.vexelray.surface.ParamBlock;
import dev.vexelray.surface.ParamId;
import dev.vexelray.surface.Scalar;
import dev.vexelray.surface.Surface;
import dev.vexelray.technique.sdf.MarchSettings;
import dev.vexelray.technique.sdf.ParamBacking;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.SampledColorTarget;
import dev.vexelray.vulkan.present.StorageBuffer;

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

    /**
     * The binding the generated fragment declares for the parameter buffer, at descriptor set 0.
     *
     * <p>The composer names it once ({@code SdfComposer.PARAM_BUFFER}) and the host has to agree; a mismatch
     * is not a compile error, it is a shader reading an unbound descriptor.
     */
    private static final int PARAM_BINDING = 0;

    /** Re-mint the target once the box exceeds it by this much. */
    private static final float GROWTH = 1.35f;

    /**
     * The scene being marched, and the values its parameters currently hold.
     *
     * <p>Both are needed on the render thread and neither is derivable there: the push-constant block is the
     * camera, the lens and one float per parameter, so its <em>size</em> is a property of the scene and its
     * contents are a property of what the sliders were last left at. Written by the compose worker before
     * {@code sceneDirty} is set and read by the frame pump afterwards, which is the same handoff the SPIR-V
     * uses.
     */
    private volatile SdfScene scene;
    private volatile ParamBlock params;
    private volatile int pushBytes = SdfComposer.CAMERA_BYTES;

    /**
     * Which road this device wants the parameters to take, and which one the shader on screen actually took.
     *
     * <p>Two fields because the first is not known until a target exists — the device arrives with it — while
     * a compose can happen before that. So the first compose assumes Vulkan's guaranteed floor, and the frame
     * pump notices when the real number turns out to be larger and asks for one more compose. The second
     * field is what the live pipeline was built for, and is what the frame must be pushed and bound for: they
     * differ for exactly one frame, and pushing the wrong one would be pushing a block the shader does not
     * have.
     */
    private volatile ParamBacking backing = ParamBacking.DEFAULT;
    private volatile ParamBacking composedBacking = ParamBacking.DEFAULT;

    /**
     * The parameters, when they travel by buffer rather than by push constant.
     *
     * <p>Null while the design is small enough for push constants, which is the common case and the faster
     * one — a push constant lands in a register and a buffer load is memory, read inside a march loop. Minted
     * when a design outgrows the block, and sized generously so that adding a slider does not mint another.
     */
    private StorageBuffer paramBuffer;

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
    private volatile Surface.Rgb sky = new Surface.Rgb(0.09, 0.10, 0.13);

    private volatile boolean sceneDirty;
    private volatile boolean frameDirty;
    private volatile boolean gridOn = true;
    private volatile String report = "empty";

    /** Whether the first marched frame has been reported &mdash; a windowed frame cannot be photographed here. */
    private boolean announced;

    /** Latest-wins guard, so a burst of edits composes once rather than a dozen times. */
    private final AtomicInteger revision = new AtomicInteger();

    /**
     * The machine-readable state of the view: an invisible node whose <b>name is the phase</b>, as
     * {@code view <n> <phase>}.
     *
     * <p>This exists because {@code settle} cannot see either half of getting a picture here. A scene change is
     * lowered and composed on a worker, and the march runs from the frame loop because
     * {@code SampledColorTarget.renderInto} submits to a {@code VkQueue} — and neither is a mutation the loop is
     * owed, so nothing reports it owed. {@code settle} is exact about the frame loop and blind to work in
     * flight, and says so.
     *
     * <p><b>The number is the load-bearing part.</b> Waiting for {@code marched} alone is satisfied the instant
     * it is asked, because what this node said before the request is that the <em>previous</em> scene marched —
     * so a driver would photograph the old picture under the new heading and be told it succeeded. A driver
     * reads the revision, acts, then waits for the next one. That is the trap
     * {@code calculator-vexel-demo/docs/driving-the-preview.md} records getting wrong three times running while
     * every number on the status line was correct.
     */
    private final Node state;

    /** What has already been announced, so a camera-only frame does not re-announce the same march. */
    private volatile String announcedState = "";

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
        // Zero-height and hidden: the semantic snapshot describes hidden subtrees rather than skipping them, so
        // its name is published and legible while nothing about the window changes.
        this.state = gui.text("view 0 idle").visible(false).height(Length.ZERO);
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

    /** The readiness node, for the host to landmark. Hidden, zero-height, and in the tree on purpose. */
    Node stateNode() {
        return state;
    }

    /**
     * Announce a phase against the current revision. Safe from any thread: a {@link Node} setter posts a
     * mutation and the GUI thread applies it, which is the ordinary way to write to one.
     */
    private void publish(String phase) {
        String next = "view " + revision.get() + " " + phase;
        if (!next.equals(announcedState)) {
            announcedState = next;
            state.text(next);
        }
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
        publish("requested");
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
            SdfScene next = new SdfScene(surface, Shadings.defaultKeyLight(), MarchSettings.DEFAULT,
                    new Surface.Rgb(0.72, 0.74, 0.78), sky, FOCAL_LENGTH, ClipDepth.DEFAULT.near());
            // Composed against what this device reports, not against the spec floor: the floor holds 25
            // parameters and the machine this was written on holds 57, and a design that fits the faster road
            // should take it. Which road is a fact about the machine — the design is the same either way, and
            // opens on a smaller device by the other one.
            ParamBacking road = this.backing;
            List<ComposedShader> composed = new SdfComposer(road).compose(next);
            byte[] vs = composed.get(0).spirv();
            byte[] fs = composed.get(1).spirv();
            double ms = (System.nanoTime() - t0) / 1e6;
            // Values cross the recompile by identity: a parameter that survived the edit keeps what it was
            // holding even if it moved slot, and one the edit removed simply drops (R4's rule, and the reason
            // ParamBlock publishes a writer rather than an offset).
            ParamBlock carried = SdfComposer.paramBlock(next, road);
            ParamBlock previous = this.params;
            if (previous != null) {
                carried.carryFrom(previous);
            }
            this.scene = next;
            this.params = carried;
            this.composedBacking = road;
            this.pushBytes = SdfComposer.pushBytes(next, road);
            this.fragmentSpirv = fs;
            this.vertexSpirv = vs;
            this.sceneDirty = true;
            this.frameDirty = true;
            report = String.format("%,d B in %.0f ms", fs.length, ms);
            publish("composed " + fs.length);
            System.out.println("composed: " + report);
            status.accept(report);
        } catch (Throwable t) {
            // A surface too large to lower is the six-operator ceiling (R2), and it is a thing a user will hit
            // by stacking modifiers. Say so and keep the last good picture rather than dying.
            String m = t.getMessage();
            status.accept("cannot compile: " + (m == null ? t.getClass().getSimpleName() : m));
            publish("refused");
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
        Surface.Rgb bg = sky;
        // The values, where this shader looks for them. Safe here and nowhere else: renderInto waits on its
        // own fence before returning, so the previous march has finished reading the buffer by the time the
        // next one rewrites it. The day that wait goes, this needs N-buffering behind ParamBlock — which is
        // the third reason that class publishes a writer rather than an offset.
        long paramSet = 0L;
        ParamBlock values = params;
        if (paramBuffer != null && values != null) {
            paramBuffer.update(values.floats(), values.size());
            paramSet = paramBuffer.descriptorSet();
        }
        target.renderInto(pipeline, 0L, paramSet, 3, pushConstantBytes((double) w / h),
                (float) bg.r(), (float) bg.g(), (float) bg.b(), 1f);
        // Announced only after the submission returns. renderInto waits for its own work, so by here the image
        // really is in SHADER_READ_ONLY and the next presented frame samples it. Announcing before the call
        // would tell a driver the picture was on the glass while the march had not run — the exact lie this
        // node exists to prevent, and one that a photograph taken on the strength of it would not reveal.
        publish("marched " + target.width() + "x" + target.height());
        if (!announced) {
            announced = true;
            System.out.println("marched " + target.width() + "x" + target.height() + " into a viewport");
        }
    }

    /**
     * Release the pipeline this viewport built. <b>Render thread only</b>, and after the loop has stopped.
     *
     * <p>The probe's resource ledger is what asked for this: a run reported {@code GraphicsPipeline opened 3,
     * closed 2, LIVE 1}, because {@link #ensurePipeline} closes the pipeline it <em>replaces</em> and nothing
     * closed the last one. The target is deliberately not closed here — {@code GuiApp} minted every one and
     * closes them all, and {@code SampledColorTarget.close()} is not idempotent.
     */
    void close() {
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
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
        askTheDevice();
        return true;
    }

    /**
     * Read what this device reports for {@code maxPushConstantsSize}, once a target has brought one.
     *
     * <p>The first compose happens before any of this — it may well precede the first frame — so it assumes
     * Vulkan's guaranteed floor, which is never wrong and is often modest: the floor holds 25 parameters and
     * the machine this was written on holds 57. When the real number turns out to be larger, one more compose
     * is asked for, and every one after that takes the faster road while it fits.
     */
    private void askTheDevice() {
        ParamBacking asked = ParamBacking.on(target.device().maxPushConstantBytes());
        if (asked.equals(backing)) {
            return;
        }
        backing = asked;
        status.accept(String.format("%d push-constant bytes here: %d parameters before the buffer",
                asked.maxPushConstantBytes(), asked.pushConstantCapacity()));
        if (scene != null) {
            show(scene.surface());              // recompose on the road this device actually wants
        }
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
        pipeline = target.pipelineFor(vs, "main", fs, "main", pushBytes, ensureParamBuffer());
        return true;
    }

    /**
     * The descriptor set layouts this scene's pipeline needs: the parameter buffer's, or none.
     *
     * <p>Minted only when a design outgrows the push-constant block, and sized generously — growing past the
     * capacity means a new buffer <em>and</em> a new pipeline, since the pipeline was built against this
     * layout, so it is worth not doing that per slider.
     */
    private long[] ensureParamBuffer() {
        ParamBlock values = params;
        int needed = values == null ? 0 : values.size();
        if (!composedBacking.usesBuffer(needed)) {
            return new long[0];                 // the push road: no descriptor, nothing to bind
        }
        if (paramBuffer == null || paramBuffer.capacityFloats() < needed) {
            StorageBuffer superseded = paramBuffer;
            paramBuffer = new StorageBuffer(target.device(), Math.max(256, needed * 2), PARAM_BINDING);
            if (superseded != null) {
                // Safe for the same reason the pipeline's replacement is: renderInto waited for the last
                // submission that could have been reading it.
                superseded.close();
            }
        }
        return new long[]{paramBuffer.descriptorSetLayout()};
    }

    /**
     * Set a parameter and ask for a frame — the whole of what a slider has to do.
     *
     * <p>No compose, no pipeline, no SPIR-V: only {@code frameDirty}, exactly as turning the camera does. That
     * is the point of the stage this method belongs to, and the reason it is this short.
     */
    void setParam(ParamId id, double value) {
        ParamBlock block = params;
        if (block == null || !block.holds(id)) {
            return;                                   // the edit that removed it got here first
        }
        block.write(id, value);
        frameDirty = true;
    }

    /** The parameters of the surface on screen, in slot order — what a panel builds sliders from. */
    List<Scalar.Param> parameters() {
        ParamBlock block = params;
        return block == null ? List.of() : block.params();
    }

    private byte[] pushConstantBytes(double aspect) {
        double yaw;
        double pitch;
        double[] eye;
        synchronized (this) {
            yaw = camera.yaw();
            pitch = camera.pitch();
            eye = worldEye();
        }
        return SdfComposer.pushConstantBytes(scene, eye[0], eye[1], eye[2], yaw, pitch, aspect, params);
    }
}
