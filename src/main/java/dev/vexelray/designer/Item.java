package dev.vexelray.designer;

import dev.vexelray.surface.Surface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One node of the design: a primitive or a modifier, with a name, a visibility, and named numbers.
 *
 * <p><b>Deliberately not a {@link Surface}.</b> A {@code Surface} is a record tree with structural equality —
 * which is exactly right for a shader cache key and exactly wrong for a document. Two spheres of the same
 * radius are {@code equals}, and {@code TreeView} keys its rows in a {@code HashMap}, so a document made of
 * {@code Surface} records would collapse identical siblings into one row and lose track of which one the user
 * clicked. An {@code Item} is an ordinary object with identity equality, so two identical spheres are two
 * things. {@code Surface} is what a design <em>compiles to</em>, not what it is stored as.
 *
 * <p>That split buys the rest of the tool as well: a name, a visibility toggle, and (later) per-node parameter
 * identity all have somewhere to live, and the on-disk format is the designer's rather than the framework's.
 */
final class Item {

    /** What an item is. The glyph is its toolbar and tree icon, and every one is in the text atlas. */
    enum Kind {
        SPHERE("Sphere", "●", false),
        BOX("Box", "■", false),
        TORUS("Torus", "◆", false),
        CAPSULE("Capsule", "▮", false),

        UNION("Union", "∪", true),
        BLEND("Blend", "⊕", true),
        DIFFERENCE("Difference", "∖", true),
        INTERSECTION("Intersection", "∩", true),

        TWIST("Twist", "↻", true),
        BEND("Bend", "↝", true),
        ARRAY("Array", "▦", true),
        MIRROR("Mirror", "◧", true);

        final String label;
        final String glyph;
        final boolean group;

        Kind(String label, String glyph, boolean group) {
            this.label = label;
            this.glyph = glyph;
            this.group = group;
        }
    }

    final Kind kind;
    private String name;
    private boolean visible = true;
    private final Map<String, Double> params = new LinkedHashMap<>();
    private final List<Item> children = new ArrayList<>();
    private Item parent;

    Item(Kind kind) {
        this.kind = kind;
        this.name = kind.label;
        defaults(kind, params);
    }

    /** The starting numbers for a kind. Small and centred, so a newly added item is visible where you are. */
    private static void defaults(Kind kind, Map<String, Double> p) {
        switch (kind) {
            case SPHERE -> { xyz(p); p.put("radius", 0.6); }
            case BOX -> { xyz(p); p.put("hx", 0.5); p.put("hy", 0.5); p.put("hz", 0.5); }
            case TORUS -> { xyz(p); p.put("major", 0.7); p.put("minor", 0.22); }
            case CAPSULE -> { xyz(p); p.put("height", 0.8); p.put("radius", 0.3); }
            case BLEND -> p.put("sharpness", 4.0);
            case TWIST -> { p.put("rate", 0.8); p.put("radius", 2.0); }
            case BEND -> { p.put("rate", 0.4); p.put("extent", 2.0); }
            case ARRAY -> p.put("period", 2.0);
            default -> { }
        }
    }

    private static void xyz(Map<String, Double> p) {
        p.put("x", 0.0);
        p.put("y", 0.0);
        p.put("z", 0.0);
    }

    // --- model ---

    String name() {
        return name;
    }

    void name(String value) {
        this.name = value;
    }

    boolean visible() {
        return visible;
    }

    void visible(boolean value) {
        this.visible = value;
    }

    double get(String key) {
        return params.getOrDefault(key, 0.0);
    }

    void set(String key, double value) {
        if (params.containsKey(key)) {
            params.put(key, value);
        }
    }

    Map<String, Double> params() {
        return params;
    }

    /**
     * The closed range {@code key} is edited over, as {@code {min, max}}.
     *
     * <p>Here rather than in the properties panel because it is a fact about the parameter, not about the
     * widget showing it — the same argument R1.1 makes for putting a range on a framework {@code Param}. It
     * arrives early because a slider cannot exist without one, and the UI is the wrong place to invent it.
     *
     * <p>Keyed on the kind as well as the name, because {@code radius} means something different on a sphere,
     * a capsule and a twist.
     */
    double[] range(String key) {
        if (key.equals("x") || key.equals("y") || key.equals("z")) {
            return new double[]{-3, 3};
        }
        return switch (kind) {
            case SPHERE -> new double[]{0.05, 2};
            case BOX -> new double[]{0.05, 2};
            case TORUS -> key.equals("major") ? new double[]{0.1, 2} : new double[]{0.02, 1};
            case CAPSULE -> key.equals("height") ? new double[]{0.1, 3} : new double[]{0.05, 1.5};
            case BLEND -> new double[]{0.5, 20};
            case TWIST -> key.equals("rate") ? new double[]{-3, 3} : new double[]{0.2, 6};
            case BEND -> key.equals("rate") ? new double[]{-2, 2} : new double[]{0.2, 6};
            case ARRAY -> new double[]{0.3, 6};
            default -> new double[]{0, 1};
        };
    }

    List<Item> children() {
        return List.copyOf(children);
    }

    Item parent() {
        return parent;
    }

    boolean canHoldChildren() {
        return kind.group;
    }

    /** Adds at the end. An item is in one place, so it leaves wherever it was first. */
    void add(Item child) {
        add(child, children.size());
    }

    void add(Item child, int index) {
        if (child == this || child.contains(this)) {
            return;                                  // a group cannot be put inside itself
        }
        if (child.parent != null) {
            child.parent.children.remove(child);
        }
        child.parent = this;
        children.add(Math.min(Math.max(index, 0), children.size()), child);
    }

    void remove(Item child) {
        if (children.remove(child)) {
            child.parent = null;
        }
    }

    int indexOf(Item child) {
        return children.indexOf(child);
    }

    /** Whether {@code other} is this item or anywhere beneath it — the drop-into-itself check. */
    boolean contains(Item other) {
        for (Item c = other; c != null; c = c.parent) {
            if (c == this) {
                return true;
            }
        }
        return false;
    }

    /** A duplicate of this item and its whole subtree, as new identities. */
    Item copy() {
        Item c = new Item(kind);
        c.name = name;
        c.visible = visible;
        c.params.putAll(params);
        for (Item child : children) {
            c.add(child.copy());
        }
        return c;
    }

    // --- compilation ---

    /**
     * This item as a {@link Surface}, or null for nothing at all — an item that is hidden, or a group with no
     * visible children. Null is an ordinary answer here rather than a failure: an empty group is a normal state
     * of a tree being built, and the alternative is inventing a surface nobody asked for.
     */
    Surface compile() {
        if (!visible) {
            return null;
        }
        return kind.group ? compileGroup() : compilePrimitive();
    }

    private Surface compilePrimitive() {
        double x = get("x");
        double y = get("y");
        double z = get("z");
        return switch (kind) {
            case SPHERE -> new Surface.Sphere(x, y, z, positive(get("radius")));
            case BOX -> new Surface.Box(x, y, z,
                    positive(get("hx")), positive(get("hy")), positive(get("hz")));
            case TORUS -> new Surface.Torus(x, y, z,
                    positive(get("major")), positive(get("minor")));
            case CAPSULE -> {
                double h = positive(get("height")) / 2;
                yield new Surface.Capsule(x, y - h, z, x, y + h, z, positive(get("radius")));
            }
            default -> null;
        };
    }

    private Surface compileGroup() {
        List<Surface> parts = new ArrayList<>();
        for (Item child : children) {
            Surface s = child.compile();
            if (s != null) {
                parts.add(s);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        return switch (kind) {
            case UNION -> parts.size() == 1 ? parts.get(0) : new Surface.Union(parts);
            case BLEND -> parts.size() == 1
                    ? parts.get(0)
                    : new Surface.SmoothUnion(positive(get("sharpness")), parts);
            case INTERSECTION -> parts.size() == 1 ? parts.get(0) : new Surface.Intersection(parts);
            case DIFFERENCE -> {
                // The first child is the body; everything after it is carved out of it.
                if (parts.size() == 1) {
                    yield parts.get(0);
                }
                List<Surface> rest = parts.subList(1, parts.size());
                yield new Surface.Difference(parts.get(0),
                        rest.size() == 1 ? rest.get(0) : new Surface.Union(List.copyOf(rest)));
            }
            case TWIST -> new Surface.Twist(finite(get("rate")), positive(get("radius")), merge(parts));
            case BEND -> new Surface.Bend(finite(get("rate")), positive(get("extent")), merge(parts));
            case ARRAY -> Surface.Repeat.grid(positive(get("period")), merge(parts));
            case MIRROR -> new Surface.Mirror(true, false, false, merge(parts));
            default -> merge(parts);
        };
    }

    /** Several children under a one-child modifier are its union — the obvious reading, and the only one. */
    private static Surface merge(List<Surface> parts) {
        return parts.size() == 1 ? parts.get(0) : new Surface.Union(parts);
    }

    /** Surface's constructors reject zero and negatives; a slider dragged to the end must not throw. */
    private static double positive(double v) {
        return Double.isFinite(v) && v > 1e-4 ? v : 1e-4;
    }

    /**
     * Twist and Bend take any finite rate, zero included — a rate of zero is "no twist", which is a state worth
     * being able to reach with a slider. Only the non-finite case has to be caught.
     */
    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0;
    }

    @Override
    public String toString() {
        return name;
    }
}
