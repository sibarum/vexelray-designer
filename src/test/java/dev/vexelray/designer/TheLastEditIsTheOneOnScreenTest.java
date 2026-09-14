package dev.vexelray.designer;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.surface.Surface;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two edits in quick succession, and which design is left on screen.
 *
 * <p><b>The defect this was written against, and which it saw.</b> {@link Viewport#show} used to hand-roll a
 * mailbox: an {@code AtomicInteger} revision, a submit to {@code Gui}'s worker pool, and a check that this
 * task was still the newest before it composed. That is {@code Backpressure.COALESCE_LATEST} written out by
 * hand, and a scene edit is exactly the payload class the policy is for — the next edit supersedes this one.
 *
 * <p>The check ran <em>before</em> the compose rather than around it, so an edit that passed it and then lost
 * its place kept composing on a pool thread beside the edit that replaced it. Both then wrote the scene, its
 * parameter block, the push-constant size and both SPIR-V modules as five separate {@code volatile} fields.
 * Measured on a 150-primitive design against a sphere:
 *
 * <pre>
 * reports, in the order they finished:
 *   6,188 B in 17 ms      the sphere — asked for last, finished first
 *   2,662,152 B in 171 ms the heavy design — superseded after 40 ms, composed anyway, and landed last
 * </pre>
 *
 * <p>So the viewport settled on a design the user had already replaced, and the frame pump could build a
 * pipeline from one compose's modules and another's push size. A slider emits an edit every few milliseconds
 * and a compose takes tens, so this is the ordinary case of dragging one, not a rare interleaving.
 *
 * <p><b>No device.</b> Composition is lowering and SPIR-V generation, which is where the cost is and none of
 * which touches the GPU — the same fact that makes it the thing worth moving off the frame thread.
 */
final class TheLastEditIsTheOneOnScreenTest {

    /** How long after the first edit the second one arrives. */
    private static final long GAP_MS = 40;

    /** How long to wait for composes to stop arriving before reading what is left. */
    private static final long QUIET_MS = 15_000;

    /**
     * Primitives in the slow scene: enough that lowering outlasts {@link #GAP_MS} by a wide margin, and not
     * so many that it is refused instead.
     *
     * <p>A smooth union's gradient normalisation compounds through nesting, so the lowered node count grows
     * far faster than the primitive count — about 6,000 nodes per sphere here, against a ceiling of a million.
     * 600 was the first guess and produced 3.6M, which the composer declines to lower at all; a refusal takes
     * no time and so overlaps nothing, which is why this number is written down with its reason.
     */
    private static final int HEAVY = 150;

    @Test
    void aComposeAlreadySupersededIsNotPutOnScreen() throws Exception {
        List<String> applied = twoEditsInQuickSuccession();

        assertEquals(1, applied.size(),
                "a compose that was already stale when it finished was applied anyway, so the viewport shows "
                        + "the superseded design for as long as it takes the newer one to arrive — a flash of "
                        + "the wrong picture. Applied, in order: " + applied);
    }

    @Test
    void theDesignLeftOnScreenIsTheOneAskedForLast() throws Exception {
        List<String> applied = twoEditsInQuickSuccession();

        int last = bytes(applied.get(applied.size() - 1));
        int smallest = applied.stream().mapToInt(TheLastEditIsTheOneOnScreenTest::bytes).min().orElseThrow();
        assertEquals(smallest, last,
                "the compose that landed last was not the edit asked for last, so the viewport is showing a "
                        + "design the user has already replaced. Applied, in order: " + applied);
    }

    /**
     * Ask for a slow design, then a trivial one while the first is still lowering, and return what each
     * <em>applied</em> compose reported, in order.
     *
     * <p>Calibrated first, against a different slow design so that no shader cache can answer for it: if the
     * slow one composes faster than the gap then the two never overlapped, the run asked nothing, and saying
     * so is the only honest outcome. An inconclusive run fails here rather than going green.
     */
    private static List<String> twoEditsInQuickSuccession() throws InterruptedException {
        int slowMillis = millis(composeOne(heavy(1)));
        assertTrue(slowMillis > GAP_MS,
                "inconclusive rather than passing: the slow design composed in " + slowMillis + "ms, inside "
                        + "the " + GAP_MS + "ms gap, so the two composes never overlapped. Raise HEAVY.");

        List<String> applied = new CopyOnWriteArrayList<>();
        try (Gui gui = new Gui()) {
            Viewport viewport = new Viewport(gui, gui.bus(), applied::add);
            try {
                viewport.show(heavy(2));
                Thread.sleep(GAP_MS);
                viewport.show(new Surface.Sphere(0, 0, 0, 1));
                awaitQuiet(applied);
            } finally {
                viewport.close();
            }
        }
        assertTrue(!applied.isEmpty(), "nothing composed at all within " + QUIET_MS + "ms");
        assertNothingRefused(applied);
        return new ArrayList<>(applied);
    }

    /** One design, composed alone, and what it reported. */
    private static String composeOne(Surface surface) throws InterruptedException {
        List<String> applied = new CopyOnWriteArrayList<>();
        try (Gui gui = new Gui()) {
            Viewport viewport = new Viewport(gui, gui.bus(), applied::add);
            try {
                viewport.show(surface);
                awaitQuiet(applied);
            } finally {
                viewport.close();
            }
        }
        assertEquals(1, applied.size(), "the calibration design did not compose exactly once: " + applied);
        assertNothingRefused(applied);
        return applied.get(0);
    }

    private static void assertNothingRefused(List<String> applied) {
        assertTrue(applied.stream().noneMatch(r -> r.startsWith("cannot compile")),
                "a design this test depends on did not lower, so nothing was being asked: " + applied);
    }

    /** A design expensive to lower: one smooth union over many primitives, placed differently per {@code seed}. */
    private static Surface heavy(int seed) {
        List<Surface> parts = new ArrayList<>(HEAVY);
        for (int i = 0; i < HEAVY; i++) {
            double a = i * 0.137 + seed;
            parts.add(new Surface.Sphere(Math.cos(a) * 2, Math.sin(a) * 2, i * 0.01 + seed, 0.35));
        }
        return new Surface.SmoothUnion(0.3, parts);
    }

    /** Wait until no new compose has been applied for a while, or until the budget is gone. */
    private static void awaitQuiet(List<String> applied) throws InterruptedException {
        long deadline = System.nanoTime() + QUIET_MS * 1_000_000L;
        int seen = -1;
        while (System.nanoTime() < deadline) {
            if (applied.size() == seen && seen > 0) {
                return;
            }
            seen = applied.size();
            Thread.sleep(250);
        }
    }

    /** The byte count out of {@code "12,345 B in 87 ms"}, grouping separators and locale left out of it. */
    private static int bytes(String report) {
        return number(report.substring(0, report.indexOf(" B")));
    }

    /** The milliseconds out of the same report. */
    private static int millis(String report) {
        return number(report.substring(report.indexOf(" in ") + 4, report.indexOf(" ms")));
    }

    private static int number(String text) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                digits.append(text.charAt(i));
            }
        }
        return digits.isEmpty() ? -1 : Integer.parseInt(digits.toString());
    }
}
