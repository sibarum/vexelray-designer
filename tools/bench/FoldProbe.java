import dev.vexelray.surface.Surface;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;

/**
 * What Fold is actually buying, and therefore what a runtime parameter gives up. A parameter cannot be folded
 * -- the compiler does not know its value -- so a node whose literal form folds away pays full price once
 * parameterised. These are the identity/axis-aligned cases where that gap is widest.
 */
public final class FoldProbe {

    static final Surface BOX = new Surface.Box(0, 0, 0, 1, 0.6, 0.4);

    public static void main(String[] args) {
        System.out.printf("%-46s %10s%n", "surface", "spirv");
        System.out.println("-".repeat(58));

        row("Box alone (the floor)", BOX);
        System.out.println();

        row("Translate(0,0,0)      -- identity, folds", new Surface.Translate(0, 0, 0, BOX));
        row("Translate(0.7,0,0)    -- one axis", new Surface.Translate(0.7, 0, 0, BOX));
        row("Translate(0.7,0.3,0.2) -- generic", new Surface.Translate(0.7, 0.3, 0.2, BOX));
        System.out.println();

        row("Scale(1.0)            -- identity, folds", new Surface.Scale(1.0, BOX));
        row("Scale(1.7)            -- generic", new Surface.Scale(1.7, BOX));
        System.out.println();

        row("Rotate(Y, 0)          -- identity", new Surface.Rotate(0, 1, 0, 0, BOX));
        row("Rotate(Y, pi/2)       -- axis-aligned", new Surface.Rotate(0, 1, 0, Math.PI / 2, BOX));
        row("Rotate(Y, 0.7)        -- generic angle", new Surface.Rotate(0, 1, 0, 0.7, BOX));
        row("Rotate(1,1,1, 0.7)    -- generic axis+angle", new Surface.Rotate(1, 1, 1, 0.7, BOX));
        System.out.println();

        row("Twist(rate=0)         -- identity", new Surface.Twist(1e-12, 2, BOX));
        row("Twist(rate=0.6)       -- generic", new Surface.Twist(0.6, 2, BOX));
    }

    static void row(String name, Surface s) {
        try {
            System.out.printf("%-46s %,10d%n", name, SdfComposer.fragmentSpirv(SdfScene.of(s)).length);
        } catch (Throwable t) {
            System.out.printf("%-46s %10s%n", name, "--");
        }
    }
}
