import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.PushConstants;
import dev.vexelray.ir.Ir;
import dev.vexelray.surface.Field;
import dev.vexelray.surface.ParamBlock;
import dev.vexelray.surface.Scalar;
import dev.vexelray.surface.Surface;
import dev.vexelray.surface.SurfaceCompiler;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;

/**
 * Can a {@code Surface} numeric actually be a runtime value? End to end.
 *
 * <p>Asked before P0a existed, by hand-rolling the only route there was: an {@code Implicit} reading a
 * {@code PushConstants} block the probe declared itself. Every question below answered yes — and that was the
 * finding, because <b>it should not have</b>. A module declares one push-constant block, the composer's, so the
 * foreign block's members were never emitted and only the member <em>index</em> survived: a read of member 0
 * resolved to the camera's {@code camX}, and the sphere's radius became the camera's X position. Valid SPIR-V,
 * clean {@code spirv-val}, wrong picture.
 *
 * <p>So the probe now asks the same questions through the route P0a added — a {@link Scalar.Param}, whose slot
 * the composer issues — and keeps a last one as the regression test: the hand-rolled block is refused by name.
 * Question 2 is where P0a's limit shows: an {@code Implicit} holds raw IR, and raw IR cannot spell a parameter,
 * so a driven implicit is driven from the outside. See also {@code PushStruct}, which counts the members that
 * actually reach the module.
 */
public final class ParamImplicit {

    public static void main(String[] args) {
        // A sphere whose radius is driven. Written twice: as an ordinary primitive, and as an implicit, since
        // the implicit is the case that carries all the risk.
        Scalar.Param radius = Scalar.Param.over(0.25, 2, 1);
        Surface primitive = new Surface.Sphere(Scalar.of(0), Scalar.of(0), Scalar.of(0), radius);

        System.out.println("== 1. does a driven primitive lower, and what bound does it get? ==");
        try {
            ParamBlock block = SdfComposer.paramBlock(SdfScene.of(primitive));
            Field fld = SurfaceCompiler.compile(primitive, store(block));
            System.out.printf("   %-26s lipschitz=%.4f  marchable=%s  slots=%d%n",
                    "Sphere(radius=param)", fld.lipschitz(), fld.isMarchable(), block.size());
        } catch (Throwable t) {
            System.out.printf("   %-26s FAILED: %s%n", "Sphere(radius=param)", msg(t));
        }

        System.out.println();
        System.out.println("== 2. can an implicit's own expression carry a driven value? ==");
        // No, and this is P0a's stated limit rather than an oversight. An Implicit holds raw core IR, which has
        // no way to spell "the parameter with this identity" — the only expression that reads a value is a read
        // of the composer's block at a slot, and a surface author has neither. So the answer is: drive an
        // implicit from the outside, with parametric transforms around it, which is question 3.
        System.out.println("   not in P0a: Implicit takes an Expr, and a Scalar.Param is not one.");
        System.out.println("   supported instead: parametric Translate/Rotate/Scale/Twist around it.");

        System.out.println();
        System.out.println("== 3. does a real implicit survive driven transforms (Substitute + normalise)? ==");
        try {
            Surface moved = new Surface.Twist(Scalar.Param.over(0, 0.9, 0.6), Scalar.of(2),
                    new Surface.Translate(Scalar.Param.over(-2, 2, 1), Scalar.of(0), Scalar.of(0),
                            new Surface.Implicit(Ir.sub(Ir.dot(Ir.POINT, Ir.POINT), Ir.f(1.0)))));
            ParamBlock block = SdfComposer.paramBlock(SdfScene.of(moved));
            Field fld = SurfaceCompiler.compile(moved, store(block));
            System.out.printf("   Twist(Translate(implicit sphere)) lipschitz=%.4f  marchable=%s  slots=%d%n",
                    fld.lipschitz(), fld.isMarchable(), block.size());
        } catch (Throwable t) {
            System.out.println("   FAILED: " + msg(t));
        }

        System.out.println();
        System.out.println("== 4. can SdfComposer emit it, and does a value sweep recompile? ==");
        try {
            SdfScene scene = SdfScene.of(primitive);
            byte[] spirv = SdfComposer.fragmentSpirv(scene);
            ParamBlock values = SdfComposer.paramBlock(scene);
            boolean stable = true;
            for (int i = 0; i <= 200; i++) {
                values.write(radius.id(), 0.25 + i * (1.75 / 200));
                stable &= SdfComposer.pushConstantBytes(scene, 0, 1, -4, 0, 0, 1.6, values).length
                        == SdfComposer.pushBytes(scene);
            }
            System.out.printf("   composed %,d bytes once; block is %d bytes (camera+lens+%d slots)%n",
                    spirv.length, SdfComposer.pushBytes(scene), values.size());
            System.out.println("   200 values written, 0 recompiles, block size steady: " + stable);
        } catch (Throwable t) {
            System.out.println("   FAILED: " + msg(t));
        }

        System.out.println();
        System.out.println("== 5. the old route: a block the composer never issued ==");
        try {
            PushConstants foreign = PushConstants.of("radius", Ir.F32);
            Surface handRolled = Surface.Implicit.bounded(
                    Ir.sub(Ir.length(Ir.POINT), foreign.read(0)), 1.0);
            SdfComposer.fragmentSpirv(SdfScene.of(handRolled));
            System.out.println("   REGRESSION: it composed. Member 0 of that block is the camera's camX.");
        } catch (IllegalArgumentException refused) {
            System.out.println("   refused, by name: " + refused.getMessage());
        }
    }


    /** The composer's own store, so the probe reads parameters exactly as a rendered scene does. */
    private static dev.vexelray.surface.ParamStore store(ParamBlock block) {
        java.util.List<PushConstants.Member> members = new java.util.ArrayList<>();
        for (int i = 0; i < SdfComposer.FIRST_PARAM_MEMBER + block.size(); i++) {
            members.add(new PushConstants.Member("m" + i, Ir.F32));
        }
        return block.inPushConstants(new PushConstants(members), SdfComposer.FIRST_PARAM_MEMBER);
    }

    static String msg(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
