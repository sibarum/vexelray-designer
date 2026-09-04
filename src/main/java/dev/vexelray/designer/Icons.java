package dev.vexelray.designer;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;

/**
 * The toolbar's icons, drawn rather than spelled.
 *
 * <p>The first toolbar used Unicode marks and every one drew as a box, because the atlas carries almost nothing
 * above {@code U+2000} (docs/icons.md). Drawing them removes the font from the question entirely: a
 * {@link Picture} is authored in the node's own pixel frame, and {@code gui-draw}'s four operations — a rounded
 * box fill, its outline, a line at any angle, and a run of glyphs — are exactly what these shapes need.
 *
 * <h2>What the alphabet can and cannot say</h2>
 * There is no filled polygon and no curve primitive, which is a statement about the engine's uber-shader rather
 * than about this module: the canvas can draw a rounded box and a line, so a picture costs the vertices its
 * marks need and nothing else. Two consequences shape the set below.
 *
 * <p><b>Curves are polylines.</b> A polyline is one line per segment with round caps, so the twist's spiral and
 * the bend's arc are real curves, sampled — not approximations of something the format cannot hold.
 *
 * <p><b>The boolean lens is approximated, and deliberately.</b> A union is exact: paint both discs and the
 * union region is literally what is painted. A difference is exact too, by painting the second disc in the
 * surface colour so it punches a hole. An <em>intersection</em> is neither, because its lens is bounded by two
 * arcs and nothing here can fill between them — so it is drawn as a stadium spanning the true lens, inside two
 * faint outlines. At the size a toolbar icon is actually seen, the stadium reads as the lens and the outlines
 * carry the meaning. Exactness there would need a clip the canvas does not have.
 *
 * <p>The boolean triad is the reason this set is worth drawing at all: <em>which region survives</em> is the
 * whole semantics of the operation, and three pictures say it where three symbols only name it.
 */
final class Icons {

    private Icons() {
    }

    /** How far the drawing stays inside its box, as a fraction of the smaller side. */
    private static final double INSET = 0.10;

    /** Segments per sampled curve. Enough that a 24px spiral has no visible corners. */
    private static final int CURVE_STEPS = 44;

    /**
     * An icon for {@code kind}, drawn to fill a {@code w} by {@code h} box.
     *
     * @param ink     the mark's own colour
     * @param faint   for construction lines and the parts of a boolean that are not kept
     * @param surface what the icon is drawn on, used to punch the hole in a difference
     */
    static Picture of(Item.Kind kind, double w, double h, Color ink, Color faint, Color surface) {
        Sketch s = new Sketch().tag(kind.name().toLowerCase());
        double side = Math.min(w, h);
        double pad = side * INSET;
        double x0 = (w - side) / 2 + pad;
        double y0 = (h - side) / 2 + pad;
        double d = side - 2 * pad;                  // the square the icon is composed in
        Geometry g = new Geometry(x0, y0, d);
        switch (kind) {
            case SPHERE -> s.circle(g.x(0.5), g.y(0.5), g.u(0.34), ink);
            case BOX -> s.outline(g.x(0.16), g.y(0.16), g.u(0.68), g.u(0.68), g.u(0.02), g.u(0.09), ink);
            case TORUS -> s.ring(g.x(0.5), g.y(0.5), g.u(0.32), g.u(0.13), ink);
            case CAPSULE -> s.fill(g.x(0.33), g.y(0.12), g.u(0.34), g.u(0.76), g.u(0.17), ink);

            case UNION -> {
                // Exact: the union of two discs is what painting two discs leaves behind.
                s.circle(g.x(0.38), g.y(0.5), g.u(0.30), ink);
                s.circle(g.x(0.62), g.y(0.5), g.u(0.30), ink);
            }
            case BLEND -> {
                // The same two discs, pulled together and bridged, so the pair reads as one melted body.
                s.fill(g.x(0.30), g.y(0.34), g.u(0.40), g.u(0.32), g.u(0.16), ink);
                s.circle(g.x(0.34), g.y(0.5), g.u(0.26), ink);
                s.circle(g.x(0.66), g.y(0.5), g.u(0.26), ink);
            }
            case DIFFERENCE -> {
                // Exact, given a known backdrop: the second disc is painted in the surface colour, which is a
                // hole rather than a drawing of one.
                s.circle(g.x(0.40), g.y(0.5), g.u(0.32), ink);
                s.circle(g.x(0.66), g.y(0.5), g.u(0.30), surface);
                s.ring(g.x(0.66), g.y(0.5), g.u(0.30), g.u(0.045), faint);
            }
            case INTERSECTION -> {
                // Approximated: see the class note. The stadium spans the true lens of the two outlined discs.
                s.ring(g.x(0.38), g.y(0.5), g.u(0.30), g.u(0.045), faint);
                s.ring(g.x(0.62), g.y(0.5), g.u(0.30), g.u(0.045), faint);
                double halfW = g.u(0.30) - g.u(0.12);                 // r - half the centre separation
                double halfH = Math.sqrt(Math.max(0, sq(g.u(0.30)) - sq(g.u(0.12))));
                s.fill(g.x(0.5) - halfW, g.y(0.5) - halfH, halfW * 2, halfH * 2, halfW, ink);
            }

            case TWIST -> {
                // An Archimedean spiral, sampled. A polyline is one line per segment, so this is a real curve.
                double[] xs = new double[CURVE_STEPS];
                double[] ys = new double[CURVE_STEPS];
                for (int i = 0; i < CURVE_STEPS; i++) {
                    double t = i / (double) (CURVE_STEPS - 1);
                    double angle = t * Math.PI * 3.4;
                    double r = g.u(0.06 + 0.30 * t);
                    xs[i] = g.x(0.5) + r * Math.cos(angle);
                    ys[i] = g.y(0.5) + r * Math.sin(angle);
                }
                s.polyline(xs, ys, g.u(0.085), ink);
            }
            case BEND -> {
                // A bar bent about its middle: the arc, plus the straight it departs from.
                double[] xs = new double[CURVE_STEPS];
                double[] ys = new double[CURVE_STEPS];
                for (int i = 0; i < CURVE_STEPS; i++) {
                    double t = i / (double) (CURVE_STEPS - 1);
                    double angle = Math.PI * (0.25 + 0.5 * t);
                    xs[i] = g.x(0.5) + g.u(0.42) * Math.cos(angle);
                    ys[i] = g.y(0.88) - g.u(0.42) * Math.sin(angle);
                }
                s.line(g.x(0.12), g.y(0.88), g.x(0.88), g.y(0.88), g.u(0.05), faint);
                s.polyline(xs, ys, g.u(0.09), ink);
            }
            case ARRAY -> {
                // Four cells on a lattice, the far one faded: a repeat runs on past what is drawn.
                double cell = g.u(0.30);
                s.fill(g.x(0.10), g.y(0.10), cell, cell, g.u(0.04), ink);
                s.fill(g.x(0.56), g.y(0.10), cell, cell, g.u(0.04), ink);
                s.fill(g.x(0.10), g.y(0.56), cell, cell, g.u(0.04), ink);
                s.fill(g.x(0.56), g.y(0.56), cell, cell, g.u(0.04), faint);
            }
            case MIRROR -> {
                // A solid half and its reflection, across the axis the fold happens on.
                s.fill(g.x(0.08), g.y(0.24), g.u(0.32), g.u(0.52), g.u(0.05), ink);
                s.outline(g.x(0.60), g.y(0.24), g.u(0.32), g.u(0.52), g.u(0.05), g.u(0.07), faint);
                for (double t = 0.08; t < 0.95; t += 0.22) {
                    s.line(g.x(0.5), g.y(t), g.x(0.5), g.y(Math.min(0.94, t + 0.11)), g.u(0.05), faint);
                }
            }
        }
        return s.picture();
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Maps the unit square onto the icon's box, so every icon is authored in 0..1 and scales with the UI. */
    private record Geometry(double x0, double y0, double d) {
        double x(double t) {
            return x0 + t * d;
        }

        double y(double t) {
            return y0 + t * d;
        }

        double u(double t) {
            return t * d;
        }
    }
}
