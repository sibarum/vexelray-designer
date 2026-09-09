package dev.vexelray.designer;

import dev.vexelray.framework.shell.VexelApplication;

/**
 * The prototype: an object/modifier tree in one window, a ray-marched viewport in another.
 *
 * <p>Both windows share one device and one frame loop, which is what lets {@code GuiApp.viewport} mint a target
 * for a window that is not the main one, and why {@link Viewport#pump()} has exactly one home — one frame hook,
 * on the thread that presents.
 *
 * <p>What this class used to be is the point of {@link DesignerWiring}: 558 lines of application edge, of which
 * the frame loop, the input backends, the command line, the two {@code Gui}s' shutdown order and the choice of
 * what {@code --capture} meant were all the same code every application on this stack was writing for itself.
 * What is left here is the entry point and the names a driver addresses.
 *
 * <p>Run it: {@code mvn compile exec:exec}. A bounded run, for a look that exits on its own:
 * {@code mvn compile exec:exec -Dapp.args=3} — a frame count is the framework's, and it replaces the
 * {@code --capture} this application used to parse into exactly that.
 */
public final class DesignerApp {

    /** The settings directory, {@code $HOME/.vexelray-designer/} — stable across releases. */
    static final String APP_NAME = "vexelray-designer";

    static final String TITLE = "VexelRay Designer";
    static final int W = 380;
    static final int H = 620;

    /**
     * The landmarks this application publishes — its contract with an automation driver.
     *
     * <p>Constants rather than literals at the call site, because a landmark is the durable half of the
     * automation surface: a ref is a node id minted per run, so a script that clicks ref 41 cannot be replayed
     * tomorrow, while a landmark can also be <em>navigated</em> to, so a concealed target is revealed rather
     * than refused. Naming them here makes renaming one visibly a breaking change.
     *
     * <p>They stay on this class rather than moving into the wiring for the same reason: they are the
     * application's published surface, and a driver's author should find them where the application is named.
     */
    static final String TOOLBAR = "toolbar";
    static final String TREE = "tree";
    static final String PROPERTIES = "properties";
    static final String STATUS = "status";
    static final String VIEW = "view";
    static final String VIEW_STATUS = "view.status";
    static final String VIEW_STATE = "view.state";

    private DesignerApp() {
    }

    public static void main(String[] args) {
        VexelApplication.run(new DesignerWiring(), args);
    }
}
