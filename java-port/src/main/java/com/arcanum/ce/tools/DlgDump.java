package com.arcanum.ce.tools;

import com.arcanum.ce.GameData;
import com.arcanum.ce.game.DialogFile;
import com.arcanum.ce.tig.TigFile;

/**
 * Headless diagnostic: parse a {@code .dlg} conversation file and dump its lines,
 * to validate the format described in {@link DialogFile} against real game data.
 *
 * <p>Prints each entry as NPC/PC, its line number, where it jumps to, and the
 * text; then walks the tree from the first NPC line to show the shape of the
 * conversation.
 *
 * <p>Run: {@code ./gradlew runTool -Ptool=tools.DlgDump -Darcanum.data=<dir>
 * -Darcanum.dlg="dlg\01324Virgil.dlg"}
 */
public final class DlgDump {

    private static final String DEFAULT_DLG = "dlg\\01324Virgil.dlg";

    private DlgDump() {
    }

    public static void main(String[] args) {
        // -Darcanum.dlg preserves paths with spaces (runTool splits -Pargs).
        String path = System.getProperty("arcanum.dlg",
                args.length > 0 ? args[0] : DEFAULT_DLG);

        TigFile.init();
        if (GameData.discoverAndRegister() == null) {
            System.err.println("No game data. Pass -Darcanum.data=<dir>.");
            System.exit(1);
            return;
        }

        // -Darcanum.rawchars=N prints the head of the file verbatim, to check the
        // parse against the bytes rather than trusting it.
        int raw = Integer.getInteger("arcanum.rawchars", 0);
        if (raw > 0) {
            byte[] b = TigFile.readBytes(path);
            if (b != null) {
                String s = new String(b, com.arcanum.ce.tig.mes.MesFile.ENCODING);
                System.out.println("--- raw head ---");
                System.out.println(s.substring(0, Math.min(raw, s.length())));
                System.out.println("--- end raw ---\n");
            }
        }

        DialogFile dlg = DialogFile.load(path);
        if (dlg == null) {
            System.err.println("Not found / no entries: " + path);
            System.exit(1);
            return;
        }

        int npc = 0;
        int pc = 0;
        for (DialogFile.Entry e : dlg.entries()) {
            if (e.isPc()) {
                pc++;
            } else {
                npc++;
            }
        }
        System.out.println("file:    " + path);
        System.out.println("entries: " + dlg.entries().size()
                + "  (" + npc + " NPC lines, " + pc + " PC responses)");

        System.out.println("\nall lines:");
        for (DialogFile.Entry e : dlg.entries()) {
            System.out.printf("  %-3s %5d -> %-5d iq=%-3d %s%s%n",
                    e.isPc() ? "PC" : "NPC", e.num, e.responseVal, e.iq,
                    trim(e.text),
                    e.conditions.trim().isEmpty() ? "" : "   [if " + e.conditions.trim() + "]");
        }

        // Show the opening beat: the first NPC line and the responses under it.
        DialogFile.Entry first = null;
        for (DialogFile.Entry e : dlg.entries()) {
            if (!e.isPc()) {
                first = e;
                break;
            }
        }
        if (first != null) {
            System.out.println("\nopening beat:");
            System.out.println("  NPC " + first.num + ": " + trim(first.text));
            for (DialogFile.Entry r : dlg.responsesTo(first)) {
                System.out.printf("    -> [%d] %s  (goes to %d)%n",
                        r.num, trim(r.text), r.responseVal);
            }
        }
    }

    private static String trim(String s) {
        String t = s.replace('\n', ' ').trim();
        return t.length() > 90 ? t.substring(0, 87) + "..." : t;
    }
}
