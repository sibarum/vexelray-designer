import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.PushConstants;
import dev.vexelray.ir.Ir;
import dev.vexelray.surface.Scalar;
import dev.vexelray.surface.Surface;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Traces the push-constant block's struct: how many members does the emitted block actually have?
 *
 * <p>Written to find a bug and kept as its regression test. Before P0a it reported <b>6 for every scene</b> —
 * the camera's six — however many parameters a surface asked for, because an {@code Implicit} reading a
 * push-constant block the composer never issued lowered to a read of the <em>emitted</em> block at the same
 * member index. Member 0 of somebody's parameter block silently became {@code camX}, and nothing anywhere
 * complained: the module was valid SPIR-V and passed {@code spirv-val}.
 *
 * <p>What it should now print: {@code 7} for a scene of literals (the camera plus the lens), {@code 7 + n} for
 * a scene with {@code n} parameters, and a named refusal for the foreign block.
 */
public final class PushStruct {

    static final int OP_TYPE_STRUCT = 30, OP_TYPE_POINTER = 32, OP_VARIABLE = 59, OP_MEMBER_DECORATE = 72;
    static final int STORAGE_PUSH_CONSTANT = 9;

    public static void main(String[] args) {
        report("camera and lens only", new Surface.Sphere(0, 0, 0, 1));

        report("+ 1 parameter", new Surface.Sphere(Scalar.of(0), Scalar.of(0), Scalar.of(0),
                Scalar.Param.over(0.25, 2)));

        report("+ 3 parameters", new Surface.Sphere(Scalar.of(0), Scalar.Param.over(0, 3),
                Scalar.Param.over(-2, 2), Scalar.Param.over(0.25, 2)));

        report("+ 25 parameters (the cap)", spheres(SdfComposer.MAX_PUSH_CONSTANT_PARAMS));
        report("+ 26 parameters (over it)", spheres(SdfComposer.MAX_PUSH_CONSTANT_PARAMS + 1));

        // The bug this file was written to find: a block the composer never issued. It is now refused by name
        // rather than resolving to the camera.
        PushConstants foreign = PushConstants.of("radius", Ir.F32);
        report("+ a foreign block",
                Surface.Implicit.bounded(Ir.sub(Ir.length(Ir.POINT), foreign.read(0)), 1.0));
    }

    /** A union of {@code n} spheres, each with a driven radius — one parameter apiece. */
    static Surface spheres(int n) {
        List<Surface> of = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            of.add(new Surface.Sphere(Scalar.of(i * 3), Scalar.of(0), Scalar.of(0),
                    Scalar.Param.over(0.25, 2)));
        }
        return new Surface.Union(of);
    }

    static void report(String name, Surface s) {
        byte[] spirv;
        try {
            spirv = SdfComposer.fragmentSpirv(SdfScene.of(s));
        } catch (IllegalArgumentException refused) {
            System.out.printf("%-26s refused: %s%n", name, refused.getMessage());
            return;
        }
        int[] w = words(spirv);
        Map<Integer, Integer> structMembers = new HashMap<>();   // struct id -> member count
        Map<Integer, Integer> pointerToType = new HashMap<>();   // pointer id -> pointee id
        Integer pushStructId = null;
        int maxMemberIdx = -1;

        int i = 5;
        while (i < w.length) {
            int count = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (count == 0) break;
            switch (op) {
                case OP_TYPE_STRUCT -> structMembers.put(w[i + 1], count - 2);
                case OP_TYPE_POINTER -> pointerToType.put(w[i + 1], w[i + 3]);
                case OP_VARIABLE -> {
                    if (count >= 4 && w[i + 3] == STORAGE_PUSH_CONSTANT) {
                        pushStructId = pointerToType.get(w[i + 1]);
                    }
                }
                default -> { }
            }
            i += count;
        }
        // second pass: member decorations on the push struct tell us its real member count
        i = 5;
        while (i < w.length) {
            int count = w[i] >>> 16, op = w[i] & 0xFFFF;
            if (count == 0) break;
            if (op == OP_MEMBER_DECORATE && pushStructId != null && w[i + 1] == pushStructId) {
                maxMemberIdx = Math.max(maxMemberIdx, w[i + 2]);
            }
            i += count;
        }
        System.out.printf("%-26s push struct members=%s  highest decorated member index=%d%n",
                name, pushStructId == null ? "none" : String.valueOf(structMembers.get(pushStructId)),
                maxMemberIdx);
    }

    static int[] words(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int[] w = new int[b.length / 4];
        for (int i = 0; i < w.length; i++) w[i] = bb.getInt(i * 4);
        return w;
    }
}
