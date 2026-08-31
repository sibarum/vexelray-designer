import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.PushConstants;
import dev.vexelray.ir.Ir;
import dev.vexelray.surface.Surface;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/** Traces the push-constant block's struct: how many members does the emitted block actually have? */
public final class PushStruct {

    static final int OP_TYPE_STRUCT = 30, OP_TYPE_POINTER = 32, OP_VARIABLE = 59, OP_MEMBER_DECORATE = 72;
    static final int STORAGE_PUSH_CONSTANT = 9;

    public static void main(String[] args) {
        report("camera only", new Surface.Sphere(0, 0, 0, 1));

        PushConstants one = PushConstants.of("radius", Ir.F32);
        report("+ 1-member param block",
                Surface.Implicit.bounded(Ir.sub(Ir.length(Ir.POINT), one.read(0)), 1.0));

        PushConstants three = new PushConstants(java.util.List.of(
                new PushConstants.Member("a", Ir.F32),
                new PushConstants.Member("b", Ir.F32),
                new PushConstants.Member("c", Ir.F32)));
        report("+ 3-member param block",
                Surface.Implicit.bounded(
                        Ir.sub(Ir.length(Ir.POINT),
                                Ir.add(Ir.add(three.read(0), three.read(1)), three.read(2))), 1.0));
    }

    static void report(String name, Surface s) {
        int[] w = words(SdfComposer.fragmentSpirv(SdfScene.of(s)));
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
