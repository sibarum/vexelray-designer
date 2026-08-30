import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.MathFn;
import dev.vexelray.ir.Ir;
import dev.vexelray.surface.Surface;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;

import java.util.ArrayList;
import java.util.List;

/** Times SdfComposer.fragmentSpirv -- the CPU half of "a scene change is a new shader". */
public final class ComposeBench {

    record Case(String name, Surface surface) {}

    public static void main(String[] args) {
        List<Case> cases = new ArrayList<>();

        cases.add(new Case("1 sphere", new Surface.Sphere(0, 0, 0, 1)));

        Surface[] eight = new Surface[8];
        for (int i = 0; i < 8; i++) eight[i] = new Surface.Sphere(i * 0.4 - 1.4, 0, 0, 0.5);
        cases.add(new Case("smoothUnion of 8 spheres", Surface.smoothUnion(8, eight)));

        Surface unit = new Surface.Sphere(0, 0, 0, 0.4);
        cases.add(new Case("Repeat.grid (2 axes)", Surface.Repeat.grid(1.5, unit)));
        cases.add(new Case("Repeat 3 axes", new Surface.Repeat(
                Surface.Repeat.Axis.every(1.5), Surface.Repeat.Axis.every(1.5),
                Surface.Repeat.Axis.every(1.5), unit)));

        cases.add(new Case("Twist(Repeat.grid(smoothUnion 8))",
                new Surface.Twist(0.6, 4, Surface.Repeat.grid(1.5, Surface.smoothUnion(8, eight)))));

        cases.add(new Case("PolarRepeat 12 of Twist", new Surface.PolarRepeat(12,
                new Surface.Twist(0.6, 4, new Surface.Box(0, 0, 0, 0.2, 1, 0.2)))));

        // The "math expression" case: a gyroid. Each partial is cos(a)cos(b) - sin(c)sin(a), bounded by 2,
        // so |grad f| <= 2*sqrt(3). Derived-vs-declared is exactly the choice the docs call out, so time both.
        Expr p = Ir.POINT;
        Expr gyroid = Ir.add(Ir.add(
                Ir.mul(sin(Ir.x(p)), cos(Ir.y(p))),
                Ir.mul(sin(Ir.y(p)), cos(Ir.z(p)))),
                Ir.mul(sin(Ir.z(p)), cos(Ir.x(p))));
        cases.add(new Case("Implicit gyroid (bound declared)",
                Surface.Implicit.bounded(gyroid, 2 * Math.sqrt(3))));
        cases.add(new Case("Implicit gyroid (bound derived)", new Surface.Implicit(gyroid)));

        // Nesting, the measured hazard: derived-bound implicit inside repetition.
        cases.add(new Case("Repeat.grid(Implicit gyroid derived)",
                Surface.Repeat.grid(6.3, new Surface.Implicit(gyroid))));

        System.out.printf("%-42s %10s %12s%n", "scene", "spirv", "compose");
        System.out.println("-".repeat(66));
        for (Case c : cases) {
            SdfScene scene = SdfScene.of(c.surface());
            String size;
            String time;
            try {
                for (int i = 0; i < 3; i++) SdfComposer.fragmentSpirv(scene);   // warm
                long best = Long.MAX_VALUE;
                int bytes = 0;
                for (int i = 0; i < 7; i++) {
                    long t0 = System.nanoTime();
                    byte[] spirv = SdfComposer.fragmentSpirv(scene);
                    long dt = System.nanoTime() - t0;
                    if (dt < best) best = dt;
                    bytes = spirv.length;
                }
                size = String.format("%,d B", bytes);
                time = String.format("%.1f ms", best / 1e6);
            } catch (Throwable t) {
                String m = t.getMessage();
                size = "--";
                time = "REJECTED: " + (m == null ? t.getClass().getSimpleName()
                        : m.length() > 70 ? m.substring(0, 70) : m);
            }
            System.out.printf("%-42s %10s %12s%n", c.name(), size, time);
        }
    }

    static Expr sin(Expr e) { return Ir.call(MathFn.SIN, Ir.F32, e); }
    static Expr cos(Expr e) { return Ir.call(MathFn.COS, Ir.F32, e); }
}
