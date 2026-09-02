package dev.vexelray.designer;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Slider;
import dev.vexelray.gui.widget.Tooltip;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import dev.vexelray.gui.widget.TreeView;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.surface.Surface;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;

import java.util.List;

/**
 * The prototype: an object/modifier tree in one window, a ray-marched viewport in another.
 *
 * <p>Both windows share one device and one frame loop, which is what lets {@code GuiApp.viewport} mint a target
 * for a window that is not the main one, and why {@link Viewport#pump()} has exactly one home — the single
 * {@code beforeFrame} hook, on the thread that presents.
 *
 * <p>Run it: {@code mvn compile exec:exec}. Headless, for a look without a GPU window:
 * {@code mvn compile exec:exec "-Dapp.args=[dash][dash]capture"}.
 */
public final class DesignerApp {

    private static final String APP_NAME = "vexelray-designer";
    private static final int W = 380;
    private static final int H = 620;

    /** Toolbar buttons per row. Words need the width; glyphs would not have. */
    private static final int PER_ROW = 4;

    private final Design design = new Design();
    private final Gui treeGui = new Gui();
    private final Gui viewGui = new Gui();

    private final java.util.Map<Gui, Tooltip> tips = new java.util.HashMap<>();

    private Viewport viewport;
    private TreeView<Item> tree;
    private Node status;
    private Node properties;
    private final java.util.List<Node> propertyRows = new java.util.ArrayList<>();
    private Node viewStatus;

    public static void main(String[] args) throws Exception {
        boolean capture = List.of(args).contains("--capture");
        int maxFrames = capture ? 3 : 0;
        new DesignerApp().run(maxFrames);
    }

    private void run(int maxFrames) throws Exception {
        buildViewportWindow();
        buildTreeWindow();
        seed();

        try (Tactroller input = openInput();
             GuiApp app = new GuiApp(WindowConfig.of("VexelRay Designer", W, H))) {
            attachInput(input, app);
            viewport.attach(app);
            app.input(DesignerApp::attachWindowInput);

            // The viewport is a named window: one window however many times it is asked for, and the Gui
            // outlives every open/close cycle, so closing and reopening it returns it as it was left.
            // Opened under --capture too, because a viewport that is never laid out is never marched, and a
            // bounded run that skipped it would prove only that the program can start.
            app.window("viewport",
                    () -> WindowSpec.of(WindowConfig.of("Viewport", 900, 640), viewGui)).show();

            TactrollerInputBridge bridge =
                    input == null ? null : new TactrollerInputBridge(input, treeGui.bus());

            app.run(treeGui, maxFrames, () -> {
                pump(bridge);
                // The march does its GPU work here and nowhere else: this hook runs on the presenting thread,
                // and the device queue belongs to it.
                viewport.pump();
            });
        }
        treeGui.close();
        viewGui.close();
        System.out.println("clean shutdown");
    }

    // --- the viewport window ---

    private void buildViewportWindow() {
        viewStatus = viewGui.text("").textSize(Length.rem(0.8f))
                .textColor(viewGui.theme().color(Role.DIM));
        viewport = new Viewport(viewGui, s -> viewStatus.text(s));

        Node bar = viewGui.row().width(Length.FILL).gap(Length.rem(0.4f))
                .padding(Length.dp(8))
                .children(
                        button(viewGui, "Grid", "Show or hide the ground grid", () -> viewport.grid(!viewport.grid())),
                        button(viewGui, "Home", "Back to the starting view", () -> viewport.home()),
                        viewGui.box().width(Length.grow(1)),
                        viewStatus);

        viewGui.root()
                .background(viewGui.theme().color(Role.PAGE))
                .padding(Length.dp(8)).gap(Length.rem(0.4f))
                .children(bar, viewport.node());
    }

    // --- the tree window ---

    private void buildTreeWindow() {
        status = treeGui.text("").textSize(Length.rem(0.8f))
                .textColor(treeGui.theme().color(Role.DIM));

        tree = new TreeView<>(treeGui, new ItemSource());
        tree.reorderable(design::move)
            .checkable(new TreeView.Checkable<Item>() {
                @Override
                public TreeView.Check state(Item item) {
                    return item.visible() ? TreeView.Check.ON : TreeView.Check.OFF;
                }

                @Override
                public void toggled(Item item) {
                    design.visible(item, !item.visible());
                }
            })
            .onSelect(item -> {
                status.text(item == null ? "" : describe(item));
                showProperties(item);
            })
            .onContextMenu((item, menu) -> {
                menu.item("", "Delete", true, () -> design.remove(item));
                menu.item("", "Duplicate", true, () -> design.add(item.copy(), item.parent()));
            });

        design.onChange(() -> {
            tree.refresh();
            recompile();
        });

        viewGui.root();   // touch, so the tree window's theme and the view's stay in step

        // The properties panel: rebuilt on every selection, empty when nothing is selected.
        properties = treeGui.column().width(Length.FILL).gap(Length.rem(0.25f))
                .padding(Length.dp(8))
                .background(treeGui.theme().color(Role.WELL))
                .corner(Length.rem(0.4f));

        treeGui.root()
                .background(treeGui.theme().color(Role.PAGE))
                .padding(Length.dp(8)).gap(Length.rem(0.4f))
                .children(toolbar(), tree.scroller(), properties, status);

        treeGui.shortcut(Key.Z, design::undo,
                Modifier.CONTROL);
        treeGui.shortcut(Key.Y, design::redo,
                Modifier.CONTROL);
    }

    /**
     * The toolbar: named buttons, four to a row — primitives, then booleans, then deformers, which is the order
     * they are reached for.
     *
     * <p>Four rather than eight because these are words. See {@link Item.Kind} for why they are words: the
     * glyphs this originally used are absent from the atlas and drew as boxes, every one of them.
     */
    private Node toolbar() {
        Node rows = treeGui.column().width(Length.FILL).gap(Length.rem(0.3f));
        Node row = null;
        int n = 0;
        for (Item.Kind kind : Item.Kind.values()) {
            if (n % PER_ROW == 0) {
                row = treeGui.row().width(Length.FILL).gap(Length.rem(0.3f));
                rows.append(row);
            }
            row.append(button(treeGui, kind.tag, kind.label, () -> addKind(kind))
                    .width(Length.grow(1)));
            n++;
        }
        // A last row with fewer buttons would stretch them; a spacer keeps every button one quarter wide.
        for (int pad = n % PER_ROW; pad > 0 && pad < PER_ROW; pad++) {
            row.append(treeGui.box().width(Length.grow(1)));
        }
        return rows;
    }

    /**
     * A new item goes inside the selection when the selection can hold children, and beside it otherwise —
     * which is what makes "select the Blend, press Sphere" mean the obvious thing.
     */
    private void addKind(Item.Kind kind) {
        Item selected = tree.selected();
        Item parent = selected == null ? design.root()
                : selected.canHoldChildren() ? selected : selected.parent();
        design.add(new Item(kind), parent);
    }

    private Node button(Gui gui, String face, String tip, Runnable action) {
        Node b = gui.box()
                .padding(Length.dp(6))
                .corner(Length.rem(0.35f))
                .background(gui.theme().color(Role.PANEL))
                .border(Length.rem(0.08f), gui.theme().color(Role.LINE))
                .lit(true)
                .elevation(Length.rem(0.2f))
                .children(gui.text(face).textSize(Length.rem(0.75f))
                        .textColor(gui.theme().color(Role.INK)));
        gui.onClick(b, action);
        gui.onState(b, s -> b.background(gui.theme().color(Role.PANEL, s)));
        tips.computeIfAbsent(gui, Tooltip::new).attach(b, tip);
        return b;
    }

    // --- wiring ---

    /** Compile the design and hand it to the viewport. Composition itself happens on a worker. */
    private void recompile() {
        Surface s = design.compile();
        viewport.show(s);
    }

    /**
     * Rebuild the properties panel for {@code item} — one slider per number, labelled, with the live value
     * beside it.
     *
     * <p>Rebuilt rather than reused: the parameters differ per kind, and a handle's registrations belong to the
     * item they were built for. {@code Node.children} appends rather than replaces, so the previous rows are
     * removed explicitly.
     *
     * <p>Each drag writes through {@link Design#tweak} and recompiles. That is the whole point of having this
     * here: the compose is coalesced latest-wins on a worker, but the pipeline is still rebuilt per settled
     * value on the frame thread, so this is where R1's cost is felt rather than argued about.
     */
    private void showProperties(Item item) {
        for (Node row : propertyRows) {
            row.remove();
        }
        propertyRows.clear();
        if (item == null) {
            return;
        }
        Node heading = treeGui.text(item.name())
                .textSize(Length.rem(0.85f))
                .textColor(treeGui.theme().color(Role.INK));
        properties.append(heading);
        propertyRows.add(heading);

        item.params().forEach((key, value) -> {
            double[] range = item.range(key);
            Node readout = treeGui.text(format(value)).textSize(Length.rem(0.75f))
                    .textColor(treeGui.theme().color(Role.DIM))
                    .width(Length.em(2.6f));
            Slider slider = new Slider(treeGui,
                    (float) ((value - range[0]) / (range[1] - range[0])));
            slider.onChange(t -> {
                double v = range[0] + t * (range[1] - range[0]);
                readout.text(format(v));
                design.tweak(item, key, v);
            });
            Node row = treeGui.row().width(Length.FILL).gap(Length.rem(0.4f))
                    .children(
                            treeGui.text(key).textSize(Length.rem(0.75f))
                                    .textColor(treeGui.theme().color(Role.DIM))
                                    .width(Length.em(3.4f)),
                            slider.node().width(Length.grow(1)),
                            readout);
            properties.append(row);
            propertyRows.add(row);
        });
    }

    private static String format(double v) {
        return String.format("%.2f", v);
    }

    private String describe(Item item) {
        StringBuilder sb = new StringBuilder(item.name());
        item.params().forEach((k, v) -> sb.append("  ").append(k).append('=')
                .append(String.format("%.2f", v)));
        return sb.toString();
    }

    /** A starting design, so the first frame shows something rather than an empty sky. */
    private void seed() {
        design.silently(this::seedNow);
    }

    private void seedNow() {
        Item blend = new Item(Item.Kind.BLEND);
        design.add(blend, design.root());
        Item a = new Item(Item.Kind.SPHERE);
        a.set("x", -0.5);
        design.add(a, blend);
        Item b = new Item(Item.Kind.BOX);
        b.set("x", 0.5);
        design.add(b, blend);
        Item t = new Item(Item.Kind.TORUS);
        t.set("y", 0.9);
        design.add(t, design.root());
    }

    /** The tree's view of the model. Identity comes free: an {@link Item} is an object, not a record. */
    private final class ItemSource implements TreeView.Source<Item> {
        @Override
        public List<Item> roots() {
            return design.roots();
        }

        @Override
        public String label(Item item) {
            return item.name();
        }

        @Override
        public boolean hasChildren(Item item) {
            return !item.children().isEmpty();
        }

        @Override
        public List<Item> children(Item item) {
            return item.children();
        }

        @Override
        public boolean acceptsChildren(Item item) {
            // A group is a group because of what it is, not because of what happens to be in it — so an
            // emptied Blend still takes a drop, and the last item dragged out of one can be put back.
            return item.canHoldChildren();
        }
    }

    // --- input plumbing ---

    /**
     * Drain the device into the bus. A backend that fails mid-drain drops the events it could not read rather
     * than the frame: an input hiccup must not take the window down with it.
     */
    private static void pump(TactrollerInputBridge bridge) {
        if (bridge == null) {
            return;
        }
        try {
            bridge.pump();
        } catch (BackendException e) {
            // best effort — a transient read failure costs those events and nothing else
        }
    }

    private static Tactroller openInput() {
        try {
            Tactroller t = Tactroller.open();
            System.out.println("input: " + t.backendName());
            return t;
        } catch (BackendException e) {
            System.out.println("input unavailable (" + e.getMessage() + "); running without pointer input");
            return null;
        }
    }

    private void attachInput(Tactroller input, GuiApp app) {
        if (input == null) {
            return;
        }
        try {
            input.attach(NativeWindow.ofHwnd(app.windowHandle()));
            input.setCoordinateSpace(CoordinateSpace.CLIENT);
        } catch (BackendException e) {
            System.out.println("input attach failed (" + e.getMessage() + "); pointer input disabled");
        }
    }

    /** Every window the framework opens from here on gets an input backend of its own. */
    private static dev.vexelray.gui.core.app.WindowInput attachWindowInput(
            dev.vexelray.os.NativeWindow window, Gui windowGui) {
        try {
            Tactroller opened = Tactroller.open();
            opened.attach(NativeWindow.ofHwnd(window.osHandle()));
            opened.setCoordinateSpace(CoordinateSpace.CLIENT);
            TactrollerInputBridge bridge = new TactrollerInputBridge(opened, windowGui.bus());
            return new dev.vexelray.gui.core.app.WindowInput() {
                @Override
                public void pump() {
                    DesignerApp.pump(bridge);
                }

                @Override
                public void close() {
                    try {
                        opened.close();
                    } catch (Exception e) {
                        // closing an input backend is best effort
                    }
                }
            };
        } catch (BackendException e) {
            return null;
        }
    }
}
