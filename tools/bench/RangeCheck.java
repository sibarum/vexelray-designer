package dev.vexelray.designer;

/** Every default must sit inside its own slider's range, and every range must be non-degenerate. */
public final class RangeCheck {
    public static void main(String[] args) {
        int bad = 0;
        for (Item.Kind kind : Item.Kind.values()) {
            Item item = new Item(kind);
            for (var e : item.params().entrySet()) {
                double[] r = item.range(e.getKey());
                double v = e.getValue();
                boolean ok = r[1] > r[0] && v >= r[0] && v <= r[1];
                if (!ok) {
                    bad++;
                    System.out.printf("  BAD  %-12s %-10s default=%.3f range=[%.3f, %.3f]%n",
                            kind, e.getKey(), v, r[0], r[1]);
                }
            }
        }
        // A group with no children compiles to null, and a primitive must always compile.
        for (Item.Kind kind : Item.Kind.values()) {
            Item item = new Item(kind);
            Object s = item.compile();
            if (!kind.group && s == null) {
                bad++;
                System.out.println("  BAD  " + kind + " primitive compiled to null");
            }
        }
        // Sliders reach the ends of their ranges; the compile must survive both.
        for (Item.Kind kind : Item.Kind.values()) {
            Item item = new Item(kind);
            for (int end = 0; end < 2; end++) {
                for (var key : item.params().keySet().toArray(new String[0])) {
                    item.set(key, item.range(key)[end]);
                }
                try {
                    Item probe = new Item(Item.Kind.SPHERE);
                    if (kind.group) {
                        item.add(probe);
                    }
                    item.compile();
                } catch (Throwable t) {
                    bad++;
                    System.out.printf("  BAD  %-12s at range end %d threw %s%n", kind, end, t);
                }
            }
        }
        System.out.println(bad == 0 ? "PASS - every default in range, every extreme compiles"
                : bad + " problems");
    }
}
