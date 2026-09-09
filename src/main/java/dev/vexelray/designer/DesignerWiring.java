package dev.vexelray.designer;

import dev.vexelray.framework.api.FrameStage;
import dev.vexelray.framework.automation.Driver;
import dev.vexelray.framework.shell.AppInfo;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Wiring;
import dev.vexelray.gui.automation.Automation;
import dev.vexelray.gui.automation.AutomationServer;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Slider;
import dev.vexelray.gui.widget.Tooltip;
import dev.vexelray.gui.widget.TreeView;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.surface.Surface;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This application's own wiring: what it builds, and in which phase.
 *
 * <p>What is <em>not</em> here is the point. Opening an input backend and settling its coordinate space,
 * opening an input backend again for the second window, parsing the command line, deciding what
 * {@code --capture} means, running the loop, and closing the two {@code Gui}s and the device in the right
 * order — all of that was {@link DesignerApp}'s and is now the framework's. Two things the designer never had
 * arrive with it: one {@code Settings} store, and a main window that reopens where it was left.
 *
 * <p>Written by hand, deliberately, on the same terms as {@code calculator-vexel-demo}'s and
 * {@code text-editor-vexel-demo}'s: this is the file the annotation processor will be made to generate, so its
 * shape is being settled against real code first. Each method is one {@code Phase}, and the phase a component
 * belongs to is decided by what it needs.
 *
 * <p><b>This is the port that had two windows on one device.</b> The framework builds one window and one
 * {@code Gui}; the viewport is a second of both, on the same device and the same frame loop, and the residue
 * below is what that costs — a {@code Gui} of its own to dress, a second automation socket, and controls that
 * have to be looked up per command because a named window's OS window does not exist when the driver starts.
 */
final class DesignerWiring implements Wiring {

    /**
     * The facts the framework needs about this application.
     *
     * <p>No mark: the designer has never worn one, and the framework leaves every window under the OS default
     * rather than inventing one. No setting keys either — the two flags this application takes,
     * {@code --automation} and {@code --profile}, are the names {@code Launch} reserves.
     */
    private static final AppInfo INFO = new AppInfo(
            DesignerApp.APP_NAME, DesignerApp.TITLE, DesignerApp.W, DesignerApp.H);

    /** Toolbar buttons per row. Words need the width; glyphs would not have. */
    private static final int PER_ROW = 4;

    /** The viewport window's first-run size. Not remembered: it is not the main window. */
    private static final int VIEW_W = 900;
    private static final int VIEW_H = 640;

    private final Map<Gui, Tooltip> tips = new HashMap<>();
    private final List<Node> propertyRows = new ArrayList<>();

    private Design design;
    private Gui treeGui;
    private Gui viewGui;
    private Viewport viewport;
    private TreeView<Item> tree;
    private Node status;
    private Node properties;
    private Node viewStatus;

    /**
     * The viewport window's real controls, handed down by {@code WindowSpec.onControls} when the window opens.
     * {@link WindowControls#NONE} until then, because the driver starts before the frame loop creates it.
     */
    private volatile WindowControls viewControls = WindowControls.NONE;

    @Override
    public AppInfo info() {
        return INFO;
    }

    /**
     * The look: the framework's own, drawn in an OS frame.
     *
     * <p><b>The one application on this stack that does not draw its own frame</b>, which is worth stating
     * rather than leaving to a default, because the default is the other answer. {@code Appearance} takes
     * {@code CLIENT} as its default on a census — <i>"three of the four applications on this stack draw their
     * own frame"</i> — and this is the fourth. Saying so is also saying there is no framework title bar here,
     * and so no instrument strip: the screenshot that matters in this application is the viewport's, and it
     * comes off the driver rather than out of a caption.
     */
    @Override
    public void config(Shell shell) {
        shell.appearance(Appearance.DEFAULT.decorations(Decorations.SYSTEM));
    }

    /**
     * The design: a tree of items and their numbers, and nothing about how any of it is drawn.
     *
     * <p>Textbook {@code MODEL} — it needs no {@code Gui}, and the compile it feeds
     * ({@link Design#compile()} to a {@link Surface}) is a value too. The seed is not here, though it looks
     * like it belongs: see {@link #tree}.
     */
    @Override
    public void model(Shell shell) {
        design = new Design();
    }

    /**
     * The second {@code Gui}, and the only reason this phase has a body at all.
     *
     * <p>The viewport lives in its own window, so it has its own tree, so it has its own {@code Gui} — and a
     * {@code Gui} the framework did not build is a {@code Gui} the application's look has not reached.
     * {@code applyTo} is that seam; without it this window would draw in whatever {@code new Gui()} defaults
     * to and disagree with the one beside it about the theme and about how far the zoom goes.
     *
     * <p>Registered with the disposer here so it closes in reverse order, after the device that renders into
     * it — which is what the hand-written version's two {@code close()} calls after the try-with-resources
     * were arranging by hand.
     */
    @Override
    public void gui(Shell shell) {
        treeGui = shell.gui();
        viewGui = shell.disposer().register(new Gui());
        shell.appearance().applyTo(viewGui);
    }

    /**
     * Both windows' trees, and then the seed.
     *
     * <p>No window is needed for either, which is what {@code Phase.TREE} is: the viewport's canvas is a box
     * that will sample a target, and it lays out against nothing until there is one.
     *
     * <p><b>The seed is announced rather than constructed</b>, which is why it is here and not in
     * {@link #model}. {@link Design#silently} coalesces the four edits into one {@code onChange} — the seed
     * alone composed eight shaders before that existed — and that one announcement is what refreshes the tree
     * and compiles the first {@code Surface}. Both listeners have to exist before it fires, so the seed goes
     * after the thing it wakes up, not beside the model it edits.
     */
    @Override
    public void tree(Shell shell) {
        buildViewportWindow();
        buildTreeWindow();
        seed();
    }

    /**
     * The viewport's device.
     *
     * <p>{@code Viewport} mints its own render target and submits to a {@code VkQueue}, so it needs the
     * {@code GuiApp} itself rather than anything reached through it — and that does not exist until
     * {@code WINDOW}. Nothing is drawn here; the march happens in the frame hook below.
     */
    @Override
    public void window(Shell shell) {
        viewport.attach(shell.app());
    }

    /**
     * What needed the window: the second window itself, the march, and the drivers.
     *
     * <p>Ordering that is not obvious and is the framework's: the per-window input factory is installed by the
     * framework's own {@code ATTACH} block, which runs before this method — and it has to, because a named
     * window opened before the factory is installed is a window nobody can click.
     */
    @Override
    public void attach(Shell shell) {
        // The viewport is a named window: one window however many times it is asked for, and the Gui outlives
        // every open/close cycle, so closing and reopening it returns it as it was left. Opened under a
        // fixed-frame run too, because a viewport that is never laid out is never marched, and a bounded run
        // that skipped it would prove only that the program can start.
        //
        // onControls, not WindowControls.of(nativeWindow). A native window cannot photograph itself -- the
        // pixels come from the GUI's per-window render bundle, which only the host owns -- so controls minted
        // from the handle get a working minimize and close and a screenshot that silently does nothing. The
        // framework hands the real set down here for exactly that reason, and it is worth the indirection:
        // `shot` on the viewport driver is the only way to see what was marched.
        AppWindow view = shell.app().window("viewport",
                () -> WindowSpec.of(WindowConfig.of("Viewport", VIEW_W, VIEW_H), viewGui)
                        .onControls(c -> viewControls = c));
        view.show();

        // The march does its GPU work in this stage and nowhere else: a frame hook runs on the presenting
        // thread, and the device queue belongs to it. APP rather than SETTLE because the picture is the
        // application's own per-frame work, and it runs after the framework's input pump, which is the order
        // the hand-written frame lambda had.
        shell.hooks().add(FrameStage.APP, viewport::pump);
        // Registered after the GuiApp, so it closes before it: the loop has stopped by then, so this is still
        // the render thread and nothing else holds the pipeline. Without it the probe's ledger reports one
        // GraphicsPipeline live at exit.
        shell.disposer().register(viewport::close);

        drivers(shell);
    }

    /**
     * Two automation drivers, each bound to its own {@code Gui} tree.
     *
     * <p>One would not do: {@code tree} on the first would not list the viewport and {@code shot} on it would
     * photograph the wrong window. The viewport's is the one that matters — a marched picture cannot be checked
     * by reading numbers off a status line.
     *
     * <p>The framework's {@code Driver} binds the socket for the {@code Gui} the framework built, off unless
     * {@code --automation} asks for it, and it is the only thing here that knows how that setting is spelled or
     * resolved. The second socket is placed at the port it actually bound plus one, so this application still
     * does not parse the flag — which is the whole of what {@code Driver} absorbed. What it could not absorb is
     * that there are two.
     */
    private void drivers(Shell shell) {
        Driver treeDriver = shell.disposer().register(Driver.open(shell));
        if (!treeDriver.bound()) {
            return;
        }
        System.out.println("automation: tree is the socket above");
        try {
            AutomationServer viewDriver = AutomationServer.start(
                    new Automation(viewGui, delegatingViewControls()), treeDriver.port() + 1);
            shell.disposer().register(viewDriver::close);
            System.out.println("automation: viewport listening on 127.0.0.1:" + viewDriver.port());
        } catch (IOException | RuntimeException e) {
            // Same trade the framework's Driver makes: an application that will not start because a debugging
            // port was busy is a worse outcome than one whose second window cannot be driven.
            System.out.println("automation: viewport socket unavailable (" + e + "); tree only");
        }
    }

    /**
     * Controls that read {@link #viewControls} at command time rather than capturing it.
     *
     * <p>The driver is constructed before the frame loop runs, so the window it photographs does not exist
     * yet; and a named window closed and reopened is handed a fresh set. Both are reasons to look the current
     * one up per command instead of holding one.
     */
    private WindowControls delegatingViewControls() {
        return new WindowControls() {
            @Override
            public void capture(String path) {
                viewControls.capture(path);
            }

            @Override
            public void minimize() {
                viewControls.minimize();
            }

            @Override
            public void toggleMaximize() {
                viewControls.toggleMaximize();
            }

            @Override
            public boolean maximized() {
                return viewControls.maximized();
            }

            @Override
            public void close() {
                viewControls.close();
            }
        };
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
                .children(bar, viewport.node(), viewport.stateNode());

        // The canvas has to be named. It is a bare box with no role and no text, so it appears in no `tree`
        // listing and matches no `find` -- the subject of the whole window would be the one node in it that
        // nothing could address. Drag targets go here.
        viewGui.landmark(DesignerApp.VIEW, viewport.node());
        viewGui.landmark(DesignerApp.VIEW_STATUS, viewStatus);
        viewGui.landmark(DesignerApp.VIEW_STATE, viewport.stateNode());
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

        // The properties panel: rebuilt on every selection, empty when nothing is selected.
        properties = treeGui.column().width(Length.FILL).gap(Length.rem(0.25f))
                .padding(Length.dp(8))
                .background(treeGui.theme().color(Role.WELL))
                .corner(Length.rem(0.4f));

        Node bar = toolbar();
        treeGui.root()
                .background(treeGui.theme().color(Role.PAGE))
                .padding(Length.dp(8)).gap(Length.rem(0.4f))
                .children(bar, tree.scroller(), properties, status);

        treeGui.landmark(DesignerApp.TOOLBAR, bar);
        treeGui.landmark(DesignerApp.TREE, tree.scroller());
        treeGui.landmark(DesignerApp.PROPERTIES, properties);
        treeGui.landmark(DesignerApp.STATUS, status);

        treeGui.shortcut(Key.Z, design::undo, Modifier.CONTROL);
        treeGui.shortcut(Key.Y, design::redo, Modifier.CONTROL);
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
            row.append(button(treeGui, kind, kind.tag, kind.label, () -> addKind(kind))
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
        return button(gui, null, face, tip, action);
    }

    /**
     * A toolbar button: an optional drawn icon over its name.
     *
     * <p>The icon is a {@link dev.vexelray.gui.draw.Picture} on a fixed-size box, rebuilt from {@code onResizeUi}
     * rather than authored once. A picture is in pixels — the renderer resolves no units of its own — so the only
     * way it follows the UI zoom is to be redrawn for the box that was actually measured. {@code onResizeUi} is
     * the right lane because the rebuild is a dozen marks and lands in the same frame as the layout it reacts to;
     * a frame of lag here would show as an icon briefly the wrong size while a window is dragged.
     */
    private Node button(Gui gui, Item.Kind kind, String face, String tip, Runnable action) {
        Node b = gui.column()
                .padding(Length.dp(6))
                .gap(Length.rem(0.15f))
                .corner(Length.rem(0.35f))
                .background(gui.theme().color(Role.PANEL))
                .border(Length.rem(0.08f), gui.theme().color(Role.LINE))
                .lit(true)
                .elevation(Length.rem(0.2f));
        if (kind != null) {
            Node art = gui.box().width(Length.rem(1.5f)).height(Length.rem(1.5f));
            gui.onResizeUi(art, layout -> {
                float w = layout.content().w();
                float h = layout.content().h();
                if (w > 1 && h > 1) {
                    art.picture(Icons.of(kind, w, h,
                            gui.theme().color(Role.INK),
                            gui.theme().color(Role.FAINT),
                            gui.theme().color(Role.PANEL)));
                }
            });
            b.append(art);
        }
        b.append(gui.text(face).textSize(Length.rem(0.7f))
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
            // A group is a group because of what it is, not because of what happens to be in it -- so an
            // emptied Blend still takes a drop, and the last item dragged out of one can be put back.
            return item.canHoldChildren();
        }
    }
}
