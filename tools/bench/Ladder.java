import dev.vexelray.surface.Field;
import dev.vexelray.surface.Surface;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;

/**
 * How a designer's stack grows as operators are piled on -- one modifier at a time.
 *
 * <p>Three numbers per rung, and P1 is the reason there are three. <b>Bytes</b> is what the driver compiles.
 * <b>nodes</b> is what the compiler emitted, counting a shared function once because that is how many times
 * it is written. <b>evals</b> is the same program with every call charged the cost of its callee -- what the
 * GPU actually runs per query.
 *
 * <p>Reading them together is the point. P1 moved cost from the first two to the third deliberately: a
 * three-axis repeat emits one copy of its child and evaluates it eight times, so a stage that reported only
 * size would be claiming a saving it did not make. Before P1 the rungs read 15 KB, 191 KB, 497 KB, 1.3 MB,
 * 4.1 MB, and then three refusals -- the sixth operator did not compile.
 */
public final class Ladder {
    public static void main(String[] args) {
        Surface[] eight = new Surface[8];
        for (int i = 0; i < 8; i++) eight[i] = new Surface.Sphere(i * 0.4 - 1.4, 0, 0, 0.5);

        Surface base = Surface.smoothUnion(8, eight);
        Surface r2 = Surface.Repeat.grid(1.5, base);
        Surface r3 = new Surface.Repeat(Surface.Repeat.Axis.every(1.5),
                Surface.Repeat.Axis.every(1.5), Surface.Repeat.Axis.every(1.5), base);

        report("smoothUnion(8)", base);
        report("+ Repeat 2 axes", r2);
        report("+ Repeat 3 axes", r3);
        report("+ Twist", new Surface.Twist(0.6, 4, r3));
        report("+ Twist + Bend", new Surface.Bend(0.3, 4, new Surface.Twist(0.6, 4, r3)));
        report("+ Twist + Bend + PolarRepeat 6",
                new Surface.PolarRepeat(6, new Surface.Bend(0.3, 4, new Surface.Twist(0.6, 4, r3))));
        report("+ ... + Mirror",
                new Surface.Mirror(true, false, true,
                        new Surface.PolarRepeat(6, new Surface.Bend(0.3, 4, new Surface.Twist(0.6, 4, r3)))));
        report("+ ... + Repeat 2 again",
                Surface.Repeat.grid(20, new Surface.Mirror(true, false, true,
                        new Surface.PolarRepeat(6, new Surface.Bend(0.3, 4, new Surface.Twist(0.6, 4, r3))))));
    }

    static void report(String name, Surface s) {
        SdfScene scene = SdfScene.of(s);
        try {
            long t0 = System.nanoTime();
            byte[] spirv = SdfComposer.fragmentSpirv(scene);
            long dt = System.nanoTime() - t0;
            Field field = SdfComposer.field(scene);
            System.out.printf("%-34s %12s %10.1f ms   %,9d nodes  %,12d evals  %3d fn%n", name,
                    String.format("%,d B", spirv.length), dt / 1e6,
                    field.nodes(), field.evaluations(), field.helpers().size());
        } catch (Throwable t) {
            String m = t.getMessage();
            System.out.printf("%-34s %12s  %s%n", name, "--",
                    (m == null ? t.getClass().getSimpleName() : m));
        }
    }
}
