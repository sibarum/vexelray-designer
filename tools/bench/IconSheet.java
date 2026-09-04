package dev.vexelray.designer;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.gui.draw.Svg;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes every toolbar icon to one SVG sheet. A picture is authored once and consumed twice - by the canvas and
 * by the SVG writer - so this is the same drawing the toolbar shows, not a redrawing of it.
 */
public final class IconSheet {

    static final double CELL = 96, PAD = 14, LABEL = 18;

    public static void main(String[] args) throws Exception {
        Color ink = Color.rgb(0.90f, 0.92f, 0.96f);
        Color faint = Color.rgb(0.42f, 0.46f, 0.54f);
        Color surface = Color.rgb(0.13f, 0.15f, 0.19f);

        Item.Kind[] kinds = Item.Kind.values();
        int cols = 4;
        int rows = (kinds.length + cols - 1) / cols;
        double w = cols * CELL, h = rows * (CELL + LABEL);

        Sketch sheet = new Sketch();
        sheet.fill(0, 0, w, h, surface);
        for (int i = 0; i < kinds.length; i++) {
            double cx = (i % cols) * CELL, cy = (i / cols) * (CELL + LABEL);
            sheet.tag("cell").outline(cx + 2, cy + 2, CELL - 4, CELL - 4, 4, 1, Color.rgb(0.22f, 0.25f, 0.30f));
            Picture icon = Icons.of(kinds[i], CELL - 2 * PAD, CELL - 2 * PAD, ink, faint, surface);
            sheet.place(icon, cx + PAD, cy + PAD);
            sheet.tag("label").text(kinds[i].tag, cx + CELL / 2 - kinds[i].tag.length() * 3.2,
                    cy + CELL + 11, 11, faint);
        }
        String svg = Svg.document(sheet.picture(), w, h);
        Path out = Path.of(args.length > 0 ? args[0] : "icons.svg");
        Files.writeString(out, svg);
        System.out.println("wrote " + out.toAbsolutePath() + "  (" + svg.length() + " bytes, "
                + (int) w + "x" + (int) h + ")");
    }
}
