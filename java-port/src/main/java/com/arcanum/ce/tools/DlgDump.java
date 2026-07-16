package com.arcanum.ce.tools;

import java.util.Map;

import com.arcanum.ce.GameData;
import com.arcanum.ce.game.DialogConditions;
import com.arcanum.ce.game.DialogFile;
import com.arcanum.ce.game.DialogOptions;
import com.arcanum.ce.game.Reaction;
import com.arcanum.ce.tig.TigFile;

/**
 * Headless diagnostic: parse a {@code .dlg} conversation file, dump its lines, and
 * run the engine's option filter over every NPC line — {@link DialogOptions}, the
 * port of {@code sub_414F50} — so the responses printed are the ones the game would
 * actually offer, with a reason beside each one it drops.
 *
 * <p>The filter needs a PC we do not have, so the inputs are supplied:
 * <ul>
 *   <li>{@code -Darcanum.iq=N} — {@code STAT_INTELLIGENCE} (default
 *       {@value #DEFAULT_IQ}, matching the UI's placeholder)</li>
 *   <li>{@code -Darcanum.female=true} — {@code STAT_GENDER} (default male)</li>
 * </ul>
 * Reaction is not settable: with no PC object, {@code reaction_get} returns
 * {@value com.arcanum.ce.game.Reaction#NEUTRAL} by its own second guard, and that is
 * the value the {@code re} conditions are tested against. See {@link Reaction}.
 *
 * <p>Conditions that need PC state cannot be evaluated and are <b>passed</b>; the
 * tally at the end says exactly which and how many, so the size of that gap is a
 * number rather than a feeling.
 *
 * <p>Run: {@code ./gradlew runTool -Ptool=tools.DlgDump -Darcanum.data=<dir>
 * -Darcanum.dlg="dlg\01324Virgil.dlg"}
 */
public final class DlgDump {

    private static final String DEFAULT_DLG = "dlg\\01324Virgil.dlg";

    /** DialogUi.DEFAULT_INTELLIGENCE — the placeholder PC's STAT_INTELLIGENCE. */
    private static final int DEFAULT_IQ = 8;

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

        if ("*".equals(path)) {
            censusAll();
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

        int iq = Integer.getInteger("arcanum.iq", DEFAULT_IQ);
        boolean female = Boolean.getBoolean("arcanum.female");
        int gender = female ? DialogOptions.GENDER_FEMALE : DialogOptions.GENDER_MALE;

        // No NPC object here: this tool is given a path, not a critter. The codes
        // that read the NPC (lf/lc/wa/wt) therefore see an absent object -- which is
        // the honest answer for "no NPC", not a stand-in for a real one.
        DialogConditions.Context ctx = new DialogConditions.Context(null, null, null);

        int npc = 0;
        int pc = 0;
        int gated = 0;
        for (DialogFile.Entry e : dlg.entries()) {
            if (e.isPc()) {
                pc++;
                if (!e.conditions.trim().isEmpty()) {
                    gated++;
                }
            } else {
                npc++;
            }
        }
        System.out.println("file:    " + path);
        System.out.println("entries: " + dlg.entries().size()
                + "  (" + npc + " NPC lines, " + pc + " PC responses, "
                + gated + " of them condition-gated)");
        System.out.println("PC:      intelligence " + iq
                + ", gender " + (female ? "female" : "male")
                + ", reaction " + Reaction.get(null, null, null)
                + " (reaction_get's no-PC path)");

        // What the file asks of us, independent of any particular PC: every code in
        // every condition string, whether or not the filter reaches it below.
        System.out.println("\ncondition codes used by this file:");
        Map<String, int[]> census = new java.util.TreeMap<>();   // code -> {count}
        for (DialogFile.Entry e : dlg.entries()) {
            if (!e.isPc()) {
                continue;       // an NPC line's field 5 is a speech id, not a condition
            }
            for (DialogConditions.Token t : DialogConditions.tokenize(e.conditions)) {
                census.computeIfAbsent(t.code.toLowerCase(), k -> new int[1])[0]++;
            }
        }
        if (census.isEmpty()) {
            System.out.println("  none");
        }
        for (Map.Entry<String, int[]> e : census.entrySet()) {
            DialogConditions.Code c = DialogConditions.Code.find(e.getKey());
            System.out.printf("  %-4s x%-4d %-9s %s%n", e.getKey(), e.getValue()[0],
                    c == null ? "UNKNOWN" : c.support, c == null ? "not in off_5A06BC" : c.reads);
        }

        System.out.println("\nall lines:");
        for (DialogFile.Entry e : dlg.entries()) {
            System.out.printf("  %-3s %5d -> %-5d iq=%-3d %s%s%n",
                    e.isPc() ? "PC" : "NPC", e.num, e.responseVal, e.iq,
                    trim(e.text),
                    e.conditions.trim().isEmpty() ? "" : "   [if " + e.conditions.trim() + "]");
        }

        // The point of the tool: every NPC line, with the filter applied.
        DialogConditions.DeferredTally tally = new DialogConditions.DeferredTally();
        int offeredTotal = 0;
        int droppedTotal = 0;

        System.out.println("\noffered options per NPC line (sub_414F50):");
        for (DialogFile.Entry line : dlg.entries()) {
            if (line.isPc()) {
                continue;
            }
            java.util.List<DialogOptions.Option> opts =
                    DialogOptions.evaluate(dlg, line, ctx, iq, gender);
            if (opts.isEmpty()) {
                continue;
            }
            System.out.println("\n  NPC " + line.num + ": " + trim(line.text));
            int shown = 0;
            for (DialogOptions.Option o : opts) {
                if (o.condition != null) {
                    tally.add(o.condition);
                }
                if (o.offered()) {
                    offeredTotal++;
                    System.out.printf("    %d. [%d] %s  (-> %d)%n",
                            ++shown, o.entry.num, trim(o.entry.text), o.entry.responseVal);
                } else {
                    droppedTotal++;
                    System.out.printf("       [%d] %-60s  DROPPED: %s%n",
                            o.entry.num, trim(o.entry.text), o.reason());
                }
            }
        }

        System.out.println("\ntotals: " + offeredTotal + " options offered, "
                + droppedTotal + " dropped");

        System.out.println("\ndeferred condition codes (passed, not evaluated -- "
                + "each needs PC state we do not have):");
        if (tally.isEmpty()) {
            System.out.println("  none -- every condition in this file was evaluated "
                    + "for real");
        } else {
            for (Map.Entry<String, Integer> e : tally.counts().entrySet()) {
                DialogConditions.Code c = DialogConditions.Code.find(e.getKey());
                System.out.printf("  %-4s x%-4d  needs %s%n",
                        e.getKey(), e.getValue(), c == null ? "?" : c.reads);
            }
            System.out.println("  " + tally.total() + " deferred check(s) across "
                    + tally.counts().size() + " code(s)");
        }

        System.out.println("\ncondition codes evaluated for real: "
                + realCodes());
    }

    /**
     * {@code -Darcanum.dlg=*} — tokenise every shipped {@code .dlg}'s conditions and
     * total the codes.
     *
     * <p>Worth having for one reason: {@code sub_4150D0}'s {@code switch} ends with
     * {@code default: return false}, so a code outside {@code off_5A06BC} <b>hides
     * the response</b>. Porting that faithfully is only safe if the shipped data
     * never produces one — this says whether it does, over the whole game, rather
     * than over the one file we happened to look at.
     */
    private static void censusAll() {
        java.util.List<String> files = TigFile.list("dlg", ".dlg");
        Map<String, int[]> census = new java.util.TreeMap<>();
        java.util.List<String> unknowns = new java.util.ArrayList<>();
        int parsed = 0;
        int gated = 0;

        for (String f : files) {
            DialogFile dlg = DialogFile.load("dlg\\" + f);
            if (dlg == null) {
                continue;
            }
            parsed++;
            for (DialogFile.Entry e : dlg.entries()) {
                if (!e.isPc() || e.conditions.trim().isEmpty()) {
                    continue;
                }
                gated++;
                for (DialogConditions.Token t : DialogConditions.tokenize(e.conditions)) {
                    census.computeIfAbsent(t.code.toLowerCase(), k -> new int[1])[0]++;
                    if (DialogConditions.Code.find(t.code) == null && unknowns.size() < 20) {
                        unknowns.add(f + " line " + e.num + ": '" + t.code
                                + "' in {" + e.conditions.trim() + "}");
                    }
                }
            }
        }

        System.out.println("scanned " + parsed + " of " + files.size()
                + " dlg files; " + gated + " condition-gated PC responses\n");
        int real = 0;
        int deferred = 0;
        int unknown = 0;
        for (Map.Entry<String, int[]> e : census.entrySet()) {
            DialogConditions.Code c = DialogConditions.Code.find(e.getKey());
            int n = e.getValue()[0];
            if (c == null) {
                unknown += n;
            } else if (c.support == DialogConditions.Support.REAL) {
                real += n;
            } else {
                deferred += n;
            }
            System.out.printf("  %-4s x%-6d %-9s %s%n", e.getKey(), n,
                    c == null ? "UNKNOWN" : c.support,
                    c == null ? "not in off_5A06BC -> sub_4150D0 returns false" : c.reads);
        }
        System.out.println("\n  real " + real + ", deferred " + deferred
                + ", unknown " + unknown);
        if (!unknowns.isEmpty()) {
            System.out.println("\nunknown-code sites (these HIDE their response, per "
                    + "the C's `default: return false`):");
            for (String u : unknowns) {
                System.out.println("  " + u);
            }
        }
    }

    private static String realCodes() {
        StringBuilder sb = new StringBuilder();
        for (DialogConditions.Code c : DialogConditions.Code.values()) {
            if (c.support == DialogConditions.Support.REAL) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(c.text);
            }
        }
        return sb.toString();
    }

    private static String trim(String s) {
        String t = s.replace('\n', ' ').trim();
        return t.length() > 90 ? t.substring(0, 87) + "..." : t;
    }
}
