import dev.vexelray.surface.Surface;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;

/** How a designer's stack grows as operators are piled on -- one modifier at a time. */
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
            System.out.printf("%-34s %12s %10.1f ms%n", name,
                    String.format("%,d B", spirv.length), dt / 1e6);
        } catch (Throwable t) {
            String m = t.getMessage();
            System.out.printf("%-34s %12s  %s%n", name, "--",
                    (m == null ? t.getClass().getSimpleName() : m));
        }
    }
}
