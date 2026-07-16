package com.arcanum.ce.tools;

import java.util.TreeMap;

import com.arcanum.ce.GameData;
import com.arcanum.ce.game.DialogFile;
import com.arcanum.ce.game.NameResolver;
import com.arcanum.ce.tig.TigArt;
import com.arcanum.ce.tig.TigFile;
import com.arcanum.ce.tig.art.ArtFile;
import com.arcanum.ce.tig.art.ArtId;

/**
 * Headless diagnostic: check a TIG bitmap font's glyph coverage against real
 * text, to find characters that would silently disappear.
 *
 * <p>{@code tig_font_glyph_data} (font.c:542) maps a character to a frame with
 * {@code (unsigned char)ch - 31} and gives up when that frame is out of range —
 * so any character past the font's frame count is dropped rather than drawn.
 * {@code .dlg} text is windows-1252, so a stray accented or curly character can
 * silently vanish mid-sentence.
 *
 * <p>Run: {@code ./gradlew runTool -Ptool=tools.FontCheck -Darcanum.data=<dir>
 * [-Darcanum.font=229] [-Darcanum.dlg="dlg\01324Virgil.dlg"]}
 */
public final class FontCheck {

    private static final int FIRST_GLYPH_CHAR = 31;   // font.c: frame = ch - 31

    private FontCheck() {
    }

    public static void main(String[] args) {
        TigFile.init();
        if (GameData.discoverAndRegister() == null) {
            System.err.println("No game data. Pass -Darcanum.data=<dir>.");
            System.exit(1);
            return;
        }
        NameResolver.install();

        int fontNum = Integer.getInteger("arcanum.font", 229);
        String dlgPath = System.getProperty("arcanum.dlg", "dlg\\01324Virgil.dlg");

        int aid = ArtId.interfaceIdCreate(fontNum, 0, 0, 0);
        String path = TigArt.buildPath(aid);
        ArtFile font = path != null ? TigArt.load(path) : null;
        System.out.println("font art num : " + fontNum);
        System.out.println("resolves to  : " + path);
        if (font == null) {
            System.out.println("NOT LOADABLE — every glyph would be skipped.");
            return;
        }
        System.out.println("frames       : " + font.numFrames
                + "   (chars " + FIRST_GLYPH_CHAR + ".."
                + (FIRST_GLYPH_CHAR + font.numFrames - 1) + ")");
        System.out.println("line height  : " + font.frames[0][0].height);

        // Which of the printable windows-1252 range does this font actually cover?
        int firstMissing = -1;
        for (int ch = 32; ch <= 255; ch++) {
            int frame = ch - FIRST_GLYPH_CHAR;
            if (frame < 0 || frame >= font.numFrames) {
                firstMissing = ch;
                break;
            }
        }
        System.out.println("first char with no glyph: "
                + (firstMissing < 0 ? "none (covers 32..255)" : firstMissing
                        + " ('" + (char) firstMissing + "')"));

        // Now scan real dialog text for characters this font cannot draw.
        DialogFile dlg = DialogFile.load(dlgPath);
        if (dlg == null) {
            System.out.println("\n(no dialog at " + dlgPath + " to scan)");
            return;
        }
        TreeMap<Integer, Integer> dropped = new TreeMap<>();
        TreeMap<Integer, Integer> used = new TreeMap<>();
        int chars = 0;
        for (DialogFile.Entry e : dlg.entries()) {
            for (String s : new String[] {e.text, e.femaleText}) {
                if (s == null) {
                    continue;
                }
                for (int i = 0; i < s.length(); i++) {
                    int ch = s.charAt(i) & 0xFF;
                    chars++;
                    used.merge(ch, 1, Integer::sum);
                    int frame = ch - FIRST_GLYPH_CHAR;
                    if (frame < 0 || frame >= font.numFrames) {
                        dropped.merge(ch, 1, Integer::sum);
                    }
                }
            }
        }
        System.out.println("\nscanned " + dlgPath + ": " + chars + " chars, "
                + used.size() + " distinct");
        if (dropped.isEmpty()) {
            System.out.println("no characters would be dropped by this font ✓");
        } else {
            System.out.println("CHARACTERS WITH NO GLYPH (silently skipped):");
            dropped.forEach((ch, n) -> System.out.printf("  %3d 0x%02X '%s'  x%d%n",
                    ch, ch,
                    ch >= 32 && ch < 127 ? String.valueOf((char) ch.intValue()) : "?",
                    n));
        }
    }
}
