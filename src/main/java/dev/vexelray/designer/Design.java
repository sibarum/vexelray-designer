package dev.vexelray.designer;

import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.widget.Placement;
import dev.vexelray.surface.Surface;

import java.util.List;

/**
 * The document: a tree of {@link Item}s, an undo history, and one signal that says it changed.
 *
 * <p>Every mutation goes through {@link History#perform}, so undo and redo come from the framework rather than
 * from anything written here — and so does the clean flag that says whether the design has unsaved work. A
 * {@link Change} states itself as the thing that undoes it and returns its own reverse, which is why a move
 * only has to capture where the item was.
 */
final class Design {

    private final Item root = new Item(Item.Kind.UNION);
    private final History history = new History();
    private Runnable onChange = () -> { };
    private boolean quiet;

    Design() {
        root.name("Design");
    }

    Item root() {
        return root;
    }

    History history() {
        return history;
    }

    /** Told whenever the tree changed shape or numbers — the cue to recompile and to refresh the tree. */
    void onChange(Runnable listener) {
        this.onChange = listener;
    }

    void changed() {
        if (quiet) {
            return;
        }
        onChange.run();
    }

    /**
     * Run {@code edits} as one announcement. Without this, building a four-item design fires four recompiles —
     * the seed alone composed eight shaders before the first frame. It is the same coalescing a slider drag
     * will need, arriving early because the seed made the cost visible immediately.
     */
    void silently(Runnable edits) {
        quiet = true;
        try {
            edits.run();
        } finally {
            quiet = false;
        }
        changed();
    }

    // --- edits, all of them undoable ---

    /** Add {@code item} under {@code parent} (or the root), at the end. */
    void add(Item item, Item parent) {
        Item into = parent == null || !parent.canHoldChildren() ? root : parent;
        history.perform(placing(item, into, into.children().size()));
        changed();
    }

    void remove(Item item) {
        if (item == root || item.parent() == null) {
            return;
        }
        history.perform(placing(item, null, 0));
        changed();
    }

    /** Toggle visibility. Undoable, because hiding a branch is an edit like any other. */
    void visible(Item item, boolean visible) {
        history.perform(setting(item, visible));
        changed();
    }

    /**
     * Set a number and recompile. <b>Not recorded in the history</b>, and that is a prototype shortcut rather
     * than a design: a drag fires a change per pointer event, so recording each one would bury every structural
     * edit under a hundred slider steps. The right fix is {@link Change#coalesce}, which exists precisely so a
     * run of same-kind edits becomes one entry — this should become one history entry per drag, merged on the
     * way. Until then Ctrl+Z steps over parameter edits entirely.
     */
    void tweak(Item item, String key, double value) {
        item.set(key, value);
        changed();
    }

    void undo() {
        history.undo();
        changed();
    }

    void redo() {
        history.redo();
        changed();
    }

    /**
     * The change that puts {@code moved} where a drop asked for, or null to refuse it — {@code TreeView} calls
     * this once per frame while the pointer is over a row, so it decides what to draw as much as what to do.
     *
     * <p>Refusing is an ordinary answer: a group cannot be dropped inside itself, and a placement whose result
     * is where the item already is is not a move. Both reach the user as an indicator that does not appear,
     * before they let go, rather than as nothing happening after.
     */
    Change move(Item moved, Placement<Item> where, DropEffect effect) {
        Item subject = switch (effect) {
            case MOVE -> moved;
            case COPY -> moved.copy();
            default -> null;                       // LINK is not a thing this model has
        };
        if (subject == null) {
            return null;
        }
        Item parent;
        int index;
        if (where.isRoot()) {
            parent = root;
            index = root.children().size();
        } else {
            Item ref = where.reference();
            switch (where.relation()) {
                case INTO -> {
                    if (!ref.canHoldChildren()) {
                        return null;
                    }
                    parent = ref;
                    index = ref.children().size();
                }
                case BEFORE, AFTER -> {
                    parent = ref.parent() == null ? root : ref.parent();
                    index = parent.indexOf(ref) + (where.relation() == Placement.Relation.AFTER ? 1 : 0);
                }
                default -> {
                    return null;
                }
            }
        }
        if (subject.contains(parent)) {
            return null;                           // into itself, or into its own subtree
        }
        if (effect == DropEffect.MOVE && subject.parent() == parent) {
            int at = parent.indexOf(subject);
            if (at == index || at + 1 == index) {
                return null;                       // exactly where it already is
            }
        }
        return placing(subject, parent, index);
    }

    /** A change that puts {@code item} at {@code (parent, index)}; a null parent takes it out of the tree. */
    private Change placing(Item item, Item parent, int index) {
        return () -> {
            Item was = item.parent();
            int wasAt = was == null ? 0 : was.indexOf(item);
            if (parent == null) {
                if (was != null) {
                    was.remove(item);
                }
            } else {
                parent.add(item, index);
            }
            changed();
            return placing(item, was, wasAt);
        };
    }

    private Change setting(Item item, boolean visible) {
        return () -> {
            boolean was = item.visible();
            item.visible(visible);
            changed();
            return setting(item, was);
        };
    }

    // --- compilation ---

    /** The whole design as one surface, or null when there is nothing visible in it. */
    Surface compile() {
        return root.compile();
    }

    /** Depth-first, for the tree's own walk. */
    List<Item> roots() {
        return root.children();
    }
}
