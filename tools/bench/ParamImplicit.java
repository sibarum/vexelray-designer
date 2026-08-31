import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.PushConstants;
import dev.vexelray.ir.Ir;
import dev.vexelray.surface.Field;
import dev.vexelray.surface.Surface;
import dev.vexelray.surface.SurfaceCompiler;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;

/** Can a Surface numeric actually be a runtime push constant today? End to end. */
public final class ParamImplicit {

    public static void main(String[] args) {
        // A sphere written as an implicit whose RADIUS is a push constant rather than a literal.
        PushConstants block = PushConstants.of("radius", Ir.F32);
        Expr radius = block.read(0);
        Expr f = Ir.sub(Ir.length(Ir.POINT), radius);

        System.out.println("== 1. does it lower, and what bound does it get? ==");
        // length(p) - r is exactly 1-Lipschitz, and we can say so.
        Surface declared = Surface.Implicit.bounded(f, 1.0);
        Surface derived = new Surface.Implicit(f);
        for (var pair : new Object[][]{{"declared bound 1.0", declared}, {"derived (symbolic grad)", derived}}) {
            try {
                Field fld = SurfaceCompiler.compile((Surface) pair[1]);
                System.out.printf("   %-26s lipschitz=%.4f  marchable=%s%n",
                        pair[0], fld.lipschitz(), fld.isMarchable());
            } catch (Throwable t) {
                System.out.printf("   %-26s FAILED: %s%n", pair[0], msg(t));
            }
        }

        System.out.println();
        System.out.println("== 2. does the gradient survive a push-constant leaf? ==");
        try {
            // Derived mode runs Gradient over f. If PushConstantRead were unhandled this throws.
            Field fld = SurfaceCompiler.compile(derived);
            System.out.println("   Gradient handled the parameter leaf; normalised bound "
                    + fld.lipschitz());
        } catch (Throwable t) {
            System.out.println("   FAILED: " + msg(t));
        }

        System.out.println();
        System.out.println("== 3. does it survive a domain transform (Substitute)? ==");
        try {
            Surface moved = new Surface.Twist(0.6, 2,
                    new Surface.Translate(1, 0, 0, declared));
            Field fld = SurfaceCompiler.compile(moved);
            System.out.printf("   Twist(Translate(param sphere)) lipschitz=%.4f  marchable=%s%n",
                    fld.lipschitz(), fld.isMarchable());
        } catch (Throwable t) {
            System.out.println("   FAILED: " + msg(t));
        }

        System.out.println();
        System.out.println("== 4. can SdfComposer emit it? (camera already owns the push block) ==");
        try {
            byte[] spirv = SdfComposer.fragmentSpirv(SdfScene.of(declared));
            System.out.printf("   composed %,d bytes -- SdfComposer.CAMERA_BYTES = %d%n",
                    spirv.length, SdfComposer.CAMERA_BYTES);
            System.out.println("   NOTE: whether that module declares one push block or two is the question;");
            System.out.println("         Vulkan permits exactly one per stage.");
        } catch (Throwable t) {
            System.out.println("   FAILED: " + msg(t));
        }
    }

    static String msg(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
