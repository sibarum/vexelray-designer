package dev.vexelray.designer;

import dev.vexelray.text.AtlasData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Every character the UI displays must exist in the text atlas, because a missing one draws as a box and
 * nothing anywhere says so.
 *
 * <p>This exists because the first toolbar was built entirely of glyphs the atlas does not carry — the boolean
 * operators, the geometric shapes, the arrows, all twelve of them — chosen by reading the {@code charset} in
 * {@code vexelray-text/pom.xml}. That charset is a <em>request</em>: {@code msdf-atlas-gen} rasterises the
 * intersection of what is asked for and what the font has, and NotoSans-Regular has none of those blocks. Above
 * {@code U+2000} the built atlas holds only General Punctuation, currency, Letterlike, {@code U+2212},
 * {@code U+25CC} and {@code U+FFFD}.
 *
 * <p>So the pom cannot be the source of truth and reasoning about Unicode blocks cannot either. Only the built
 * atlas can, which is what this reads. Run it after touching any user-visible string.
 */
public final class GlyphCheck {

    private static final String ATLAS = "/dev/vexelray/text/atlas/primary.json";

    public static void main(String[] args) {
        AtlasData atlas = AtlasData.loadFromResource(ATLAS);

        // Everything the application actually shows. Kept explicit rather than scraped from source: a scraper
        // over string literals would miss composed text and flag javadoc, and this list is short.
        Map<String, String> shown = new LinkedHashMap<>();
        for (Item.Kind kind : Item.Kind.values()) {
            shown.put("Kind." + kind + ".label", kind.label);
            shown.put("Kind." + kind + ".tag", kind.tag);
        }
        for (Item.Kind kind : Item.Kind.values()) {
            Item item = new Item(kind);
            item.params().keySet().forEach(k -> shown.put("param " + kind + "." + k, k));
        }
        shown.put("viewport.grid", "Grid");
        shown.put("viewport.home", "Home");
        shown.put("viewport.grid.tip", "Show or hide the ground grid");
        shown.put("viewport.home.tip", "Back to the starting view");
        shown.put("menu.delete", "Delete");
        shown.put("menu.duplicate", "Duplicate");
        shown.put("status.empty", "empty");
        shown.put("status.refusal", "cannot compile: lowered surface exceeds nodes");
        shown.put("readout", "-0.00 123456789");
        shown.put("root.name", "Design");

        TreeSet<Integer> missing = new TreeSet<>();
        int failures = 0;
        for (Map.Entry<String, String> e : shown.entrySet()) {
            for (int cp : e.getValue().codePoints().toArray()) {
                if (Character.isWhitespace(cp)) {
                    continue;                       // a space is legitimately blank
                }
                if (atlas.glyph(cp) == null) {
                    missing.add(cp);
                    System.out.printf("  MISSING U+%04X '%c'  in %s%n", cp, cp, e.getKey());
                    failures++;
                }
            }
        }
        System.out.printf("%d strings, %d distinct missing codepoints%n", shown.size(), missing.size());
        System.out.println(failures == 0
                ? "PASS - every displayed character is in the atlas"
                : "FAIL - " + failures + " uses of " + missing.size() + " absent codepoints draw as boxes");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
