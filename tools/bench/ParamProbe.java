import dev.vexelray.surface.Field;
import dev.vexelray.surface.Surface;
import dev.vexelray.surface.SurfaceCompiler;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleFunction;

/**
 * R1 pressure test. For each node kind that carries a numeric, lower it at several values of that numeric and
 * ask two questions:
 *   E1  does the Lipschitz bound depend on the value?   (if yes, the value cannot become a runtime parameter)
 *   E2  does the emitted structure depend on the value? (if yes, a parameter costs more IR than a literal)
 */
public final class ParamProbe {

    static final Surface CHILD = new Surface.Sphere(0, 0, 0, 0.5);

    public static void main(String[] args) {
        Map<String, DoubleFunction<Surface>> kinds = new LinkedHashMap<>();
        kinds.put("Sphere(radius=v)",      v -> new Surface.Sphere(0, 0, 0, v));
        kinds.put("Box(hx=v)",            v -> new Surface.Box(0, 0, 0, v, 1, 1));
        kinds.put("Torus(major=v)",       v -> new Surface.Torus(0, 0, 0, v, 0.3));
        kinds.put("Translate(dx=v)",      v -> new Surface.Translate(v, 0, 0, CHILD));
        kinds.put("Scale(factor=v)",      v -> new Surface.Scale(v, CHILD));
        kinds.put("Rotate(angle=v)",      v -> new Surface.Rotate(0, 1, 0, v, new Surface.Box(0, 0, 0, 1, 1, 1)));
        kinds.put("Round(radius=v)",      v -> new Surface.Round(v, CHILD));
        kinds.put("Shell(thickness=v)",   v -> new Surface.Shell(v, CHILD));
        kinds.put("SmoothUnion(k=v)",     v -> Surface.smoothUnion(v, CHILD, new Surface.Sphere(1, 0, 0, 0.5)));
        kinds.put("Repeat(period=v)",     v -> Surface.Repeat.alongX(Surface.Repeat.Axis.every(v), CHILD));
        kinds.put("PolarRepeat(count=v)", v -> new Surface.PolarRepeat((int) Math.max(1, v), CHILD));
        kinds.put("Twist(rate=v)",        v -> new Surface.Twist(v, 2, CHILD));
        kinds.put("Bend(rate=v)",         v -> new Surface.Bend(v, 2, CHILD));

        double[] values = {0.5, 1.0, 2.0, 3.0};

        System.out.printf("%-24s %-34s %-28s %s%n", "node", "lipschitz at v=0.5/1/2/3", "spirv bytes", "verdict");
        System.out.println("-".repeat(115));

        for (Map.Entry<String, DoubleFunction<Surface>> e : kinds.entrySet()) {
            StringBuilder bounds = new StringBuilder();
            StringBuilder sizes = new StringBuilder();
            boolean boundVaries = false;
            boolean sizeVaries = false;
            double firstBound = Double.NaN;
            int firstSize = -1;

            for (double v : values) {
                double b;
                int size;
                try {
                    Surface s = e.getValue().apply(v);
                    Field f = SurfaceCompiler.compile(s);
                    b = f.lipschitz();
                    size = SdfComposer.fragmentSpirv(SdfScene.of(s)).length;
                } catch (Throwable t) {
                    b = Double.NaN;
                    size = -1;
                }
                if (Double.isNaN(firstBound)) firstBound = b;
                else if (Double.compare(firstBound, b) != 0) boundVaries = true;
                if (firstSize < 0) firstSize = size;
                else if (firstSize != size) sizeVaries = true;

                bounds.append(String.format("%.3f ", b));
                sizes.append(String.format("%,d ", size));
            }

            String verdict = boundVaries ? "BOUND DEPENDS ON VALUE"
                    : sizeVaries ? "structure varies (Fold)"
                    : "value-independent";
            System.out.printf("%-24s %-34s %-28s %s%n", e.getKey(), bounds.toString().trim(),
                    sizes.toString().trim(), verdict);
        }
    }
}
