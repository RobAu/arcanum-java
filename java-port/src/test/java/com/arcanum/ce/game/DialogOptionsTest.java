package com.arcanum.ce.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins {@code sub_414F50} ({@code dialog.c:1309}) — the option filter: gender, then
 * intelligence, then conditions, capped at five.
 *
 * <p>The headline case is Virgil's opening, which is why the port exists: it offers
 * each line twice, once gated {@code re62} and once {@code re-61}, and without the
 * condition test both halves of every pair show up.
 */
class DialogOptionsTest {

    /** The head of dlg\01324Virgil.dlg, verbatim — the actual duplicated opening. */
    private static final String VIRGIL_OPENING =
            "{1}{I can't believe it...!}{}{}{1}{}{}\n"
            + "{2}{What are you going on about?}{}{5}{re62}{9}{}\n"
            + "{3}{Whut you say?}{}{-4}{re62}{9}{}\n"
            + "{4}{What are you going on about?}{}{5}{re-61}{9}{}\n"
            + "{5}{Whut you say?}{}{-4}{re-61}{9}{}\n"
            + "{6}{By the gods, man!}{}{5}{re62}{9}{}\n"
            + "{7}{By the gods, man!}{}{5}{re-61}{9}{}\n"
            + "{9}{[The man's jaw drops open.]}{}{}{2}{}{}\n";

    /** DialogState with no pc_obj — see DialogConditions/Reaction. */
    private static DialogConditions.Context ctx() {
        return new DialogConditions.Context(null, null, null);
    }

    private static List<Integer> nums(List<DialogFile.Entry> es) {
        List<Integer> out = new java.util.ArrayList<>();
        for (DialogFile.Entry e : es) {
            out.add(e.num);
        }
        return out;
    }

    // -- the bug this port fixes --------------------------------------------

    @Test
    void virgilsOpeningOffersEachLineOnce() {
        DialogFile d = DialogFile.parse("virgil.dlg", VIRGIL_OPENING);
        List<DialogFile.Entry> offered = DialogOptions.offered(
                d, d.find(1), ctx(), 8, DialogOptions.GENDER_MALE);

        // Reaction is 50, so the re62 half of each pair is dropped and the re-61
        // half survives. Lines 3 and 5 are the dumb variants (iq -4), out at iq 8.
        assertEquals(java.util.Arrays.asList(4, 7), nums(offered));

        // ...and specifically, no text appears twice.
        assertEquals(offered.size(),
                offered.stream().map(e -> e.text).distinct().count(),
                "each response should be offered exactly once");
    }

    @Test
    void withoutTheConditionTestTheOpeningDuplicates() {
        // The old behaviour, pinned so the regression is visible if conditions are
        // ever bypassed again: IQ alone lets both halves of each pair through.
        DialogFile d = DialogFile.parse("virgil.dlg", VIRGIL_OPENING);
        List<Integer> iqOnly = new java.util.ArrayList<>();
        for (DialogFile.Entry e : d.responsesTo(d.find(1))) {
            if (DialogOptions.passesIq(e.iq, 8)) {
                iqOnly.add(e.num);
            }
        }
        assertEquals(java.util.Arrays.asList(2, 4, 6, 7), iqOnly,
                "IQ alone keeps both the re62 and re-61 variants");
    }

    @Test
    void aDumbPcSeesTheDumbVariantsInstead() {
        DialogFile d = DialogFile.parse("virgil.dlg", VIRGIL_OPENING);
        // iq 4: the iq -4 lines pass (4 <= 4), the iq 5 lines do not.
        List<DialogFile.Entry> offered = DialogOptions.offered(
                d, d.find(1), ctx(), 4, DialogOptions.GENDER_MALE);
        assertEquals(java.util.Arrays.asList(5), nums(offered));
    }

    // -- the filter's parts -------------------------------------------------

    @Test
    void capsAtFiveOptions() {
        StringBuilder sb = new StringBuilder("{1}{npc}{}{}{}{}{}\n");
        for (int i = 2; i <= 9; i++) {
            sb.append("{").append(i).append("}{option ").append(i)
              .append("}{}{5}{}{20}{}\n");
        }
        DialogFile d = DialogFile.parse("cap.dlg", sb.toString());
        assertEquals(8, d.responsesTo(d.find(1)).size(), "all eight are eligible");

        List<DialogFile.Entry> offered = DialogOptions.offered(
                d, d.find(1), ctx(), 8, DialogOptions.GENDER_MALE);
        assertEquals(DialogOptions.MAX_OPTIONS, offered.size());
        assertEquals(java.util.Arrays.asList(2, 3, 4, 5, 6), nums(offered),
                "the first five in file order; the loop stops there");
    }

    @Test
    void theCapCountsOnlyAcceptedOptions() {
        // cnt++ happens inside the condition test, so rejected lines do not use up
        // a slot -- five must still make it through past a wall of failures.
        StringBuilder sb = new StringBuilder("{1}{npc}{}{}{}{}{}\n");
        for (int i = 2; i <= 5; i++) {          // four that fail on reaction
            sb.append("{").append(i).append("}{no}{}{5}{re62}{20}{}\n");
        }
        for (int i = 6; i <= 12; i++) {         // seven that pass
            sb.append("{").append(i).append("}{yes}{}{5}{}{20}{}\n");
        }
        DialogFile d = DialogFile.parse("cap2.dlg", sb.toString());
        List<DialogFile.Entry> offered = DialogOptions.offered(
                d, d.find(1), ctx(), 8, DialogOptions.GENDER_MALE);
        assertEquals(java.util.Arrays.asList(6, 7, 8, 9, 10), nums(offered));
    }

    @Test
    void entriesPastTheCapAreNeverExamined() {
        // The cap is in the C's loop condition, not its body: once five are in, the
        // loop exits and the rest are not tested at all.
        StringBuilder sb = new StringBuilder("{1}{npc}{}{}{}{}{}\n");
        for (int i = 2; i <= 8; i++) {
            sb.append("{").append(i).append("}{option}{}{5}{}{20}{}\n");
        }
        DialogFile d = DialogFile.parse("cap3.dlg", sb.toString());
        List<DialogOptions.Option> opts =
                DialogOptions.evaluate(d, d.find(1), ctx(), 8, DialogOptions.GENDER_MALE);
        assertEquals(7, opts.size());
        assertEquals(DialogOptions.Reject.CAP, opts.get(5).reject);
        assertEquals(DialogOptions.Reject.CAP, opts.get(6).reject);
        assertTrue(opts.get(4).offered());
    }

    @Test
    void filtersOnGender() {
        // stat.h: GENDER_FEMALE = 0, GENDER_MALE = 1; a blank field is -1 = either.
        String text = "{1}{npc}{}{}{}{}{}\n"
                + "{2}{for anyone}{}{5}{}{20}{}\n"
                + "{3}{for women}{0}{5}{}{20}{}\n"
                + "{4}{for men}{1}{5}{}{20}{}\n";
        DialogFile d = DialogFile.parse("gender.dlg", text);

        assertEquals(java.util.Arrays.asList(2, 4),
                nums(DialogOptions.offered(d, d.find(1), ctx(), 8,
                        DialogOptions.GENDER_MALE)));
        assertEquals(java.util.Arrays.asList(2, 3),
                nums(DialogOptions.offered(d, d.find(1), ctx(), 8,
                        DialogOptions.GENDER_FEMALE)));
    }

    @Test
    void genderIsTestedBeforeIntelligence() {
        // The C nests iq inside the gender check, so a line failing both reports
        // gender. Pinning the order keeps the diagnostic honest.
        String text = "{1}{npc}{}{}{}{}{}\n"
                + "{2}{women only, and clever}{0}{18}{}{20}{}\n";
        DialogFile d = DialogFile.parse("order.dlg", text);
        List<DialogOptions.Option> opts =
                DialogOptions.evaluate(d, d.find(1), ctx(), 8, DialogOptions.GENDER_MALE);
        assertEquals(DialogOptions.Reject.GENDER, opts.get(0).reject);
    }

    @Test
    void conditionsAreTestedAfterIntelligence() {
        String text = "{1}{npc}{}{}{}{}{}\n"
                + "{2}{clever and liked}{}{18}{re62}{20}{}\n";
        DialogFile d = DialogFile.parse("order2.dlg", text);
        List<DialogOptions.Option> opts =
                DialogOptions.evaluate(d, d.find(1), ctx(), 8, DialogOptions.GENDER_MALE);
        assertEquals(DialogOptions.Reject.IQ, opts.get(0).reject);
    }

    @Test
    void responsesStopAtTheNextNpcLine() {
        // "if (entry->iq == 0) return cnt;"
        String text = "{1}{npc one}{}{}{}{}{}\n"
                + "{2}{mine}{}{5}{}{20}{}\n"
                + "{3}{npc two}{}{}{}{}{}\n"
                + "{4}{not mine}{}{5}{}{20}{}\n";
        DialogFile d = DialogFile.parse("stop.dlg", text);
        assertEquals(java.util.Arrays.asList(2),
                nums(DialogOptions.offered(d, d.find(1), ctx(), 8,
                        DialogOptions.GENDER_MALE)));
        assertEquals(java.util.Arrays.asList(4),
                nums(DialogOptions.offered(d, d.find(3), ctx(), 8,
                        DialogOptions.GENDER_MALE)));
    }

    @Test
    void aBlankConditionOffersTheLine() {
        String text = "{1}{npc}{}{}{}{}{}\n{2}{always}{}{5}{}{20}{}\n";
        DialogFile d = DialogFile.parse("blank.dlg", text);
        assertEquals(1, DialogOptions.offered(d, d.find(1), ctx(), 8,
                DialogOptions.GENDER_MALE).size());
    }

    @Test
    void reportsWhyEachDroppedLineWasDropped() {
        DialogFile d = DialogFile.parse("virgil.dlg", VIRGIL_OPENING);
        List<DialogOptions.Option> opts =
                DialogOptions.evaluate(d, d.find(1), ctx(), 8, DialogOptions.GENDER_MALE);
        assertEquals("condition re62", opts.get(0).reason());     // line 2
        assertEquals("iq -4", opts.get(1).reason());              // line 3
        assertEquals(null, opts.get(2).reason());                 // line 4, offered
    }

    // -- the codes that read the NPC ----------------------------------------
    //
    // These are the conditions we can answer exactly, because the NPC object is one
    // we actually parse. Build a real one rather than mocking the read.

    /** An NPC with the given OBJ_F_NPC_FLAGS and SAP_DIALOG script header. */
    private static GameObject npc(int npcFlags, Script dialogScript) {
        // A non-blocked prototype_oid: this is an instance, so has()/resolved() take
        // the instance path and never need a ProtoStore.
        GameObject o = new GameObject(ObjectFields.OBJ_TYPE_NPC,
                ObjectID.ofA(1), ObjectID.ofA(2));
        o.put(ObjectFields.OBJ_F_NPC_FLAGS, npcFlags);
        if (dialogScript != null) {
            o.put(ObjectFields.OBJ_F_SCRIPTS_IDX, scriptArray(dialogScript));
        }
        return o;
    }

    /** A SizeableArray holding one Script at key SAP_DIALOG, as the data has it. */
    private static SizeableArray scriptArray(Script s) {
        int key = Sap.DIALOG;
        int bcnt = key / 32 + 1;
        int[] words = new int[bcnt];
        words[key / 32] |= 1 << (key % 32);

        ByteBuffer b = ByteBuffer
                .allocate(SizeableArray.HEADER_SIZE + Script.SIZE + 4 + 4 * bcnt)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(Script.SIZE);      // element size
        b.putInt(1);                // count
        b.putInt(0);                // bitset_id: a runtime slot, ignored on read
        b.putInt(s.flags).putInt(s.counters).putInt(s.num);
        b.putInt(bcnt);
        for (int w : words) {
            b.putInt(w);
        }
        return SizeableArray.read(b.flip().slice().order(ByteOrder.LITTLE_ENDIAN));
    }

    private static DialogConditions.Context ctx(GameObject npc) {
        return new DialogConditions.Context(npc, null, null);
    }

    @Test
    void waReadsTheNpcsWaitHereFlag() {
        // ONF_AI_WAIT_HERE = 0x8. wa1 requires it set, wa0 requires it clear.
        GameObject waiting = npc(0x8, null);
        GameObject roaming = npc(0x0, null);
        assertTrue(DialogConditions.test(ctx(waiting), "wa1"));
        assertFalse(DialogConditions.test(ctx(waiting), "wa0"));
        assertFalse(DialogConditions.test(ctx(roaming), "wa1"));
        assertTrue(DialogConditions.test(ctx(roaming), "wa0"));
    }

    @Test
    void wtReadsTheNpcsJiltedFlag() {
        // ONF_JILTED = 0x20 -- a different bit, so wa must not answer it.
        GameObject jilted = npc(0x20, null);
        assertTrue(DialogConditions.test(ctx(jilted), "wt1"));
        assertFalse(DialogConditions.test(ctx(jilted), "wt0"));
        assertFalse(DialogConditions.test(ctx(jilted), "wa1"),
                "JILTED must not read as AI_WAIT_HERE");
    }

    @Test
    void waAndWtIgnoreValuesOtherThanZeroAndOne() {
        // "if (flag set) { if (value == 0) return false; } else { if (value == 1)
        // return false; }" -- value 2 fails neither arm.
        assertTrue(DialogConditions.test(ctx(npc(0x8, null)), "wa2"));
        assertTrue(DialogConditions.test(ctx(npc(0x0, null)), "wa2"));
    }

    @Test
    void lfReadsTheNpcsDialogScriptFlags() {
        // script_local_flag_get: (scr.hdr.flags & (1 << flag)) != 0, from the
        // SAP_DIALOG slot of OBJ_F_SCRIPTS_IDX.
        GameObject o = npc(0, new Script(0b1010, 0, 1324));
        assertTrue(DialogConditions.test(ctx(o), "lf1 1"), "bit 1 is set");
        assertTrue(DialogConditions.test(ctx(o), "lf3 1"), "bit 3 is set");
        assertFalse(DialogConditions.test(ctx(o), "lf0 1"), "bit 0 is clear");
        assertTrue(DialogConditions.test(ctx(o), "lf0 0"), "...so lf0 0 passes");
    }

    @Test
    void lfIsFalseWhenTheNpcHasNoDialogScript() {
        // scr.num != 0 ? ... : false -- and GameObject.script() folds "no key" and
        // "num == 0" into the same null.
        GameObject o = npc(0, null);
        assertFalse(DialogConditions.test(ctx(o), "lf1 1"));
        assertTrue(DialogConditions.test(ctx(o), "lf1 0"));
    }

    @Test
    void lcReadsTheNpcsDialogScriptCounters() {
        // script_local_counter_get: scr.hdr.counters >> (8 * counter), with NO mask
        // -- so counter 0 is all four bytes, not just the low one. Verbatim quirk.
        GameObject o = npc(0, new Script(0, 0x01020304, 1324));
        assertTrue(DialogConditions.test(ctx(o), "lc0 " + 0x01020304),
                "counter 0 returns the whole word, unmasked");
        assertTrue(DialogConditions.test(ctx(o), "lc1 " + 0x010203),
                "counter 1 shifts by 8 and keeps the upper three bytes");
        assertFalse(DialogConditions.test(ctx(o), "lc0 4"),
                "a masked read would have said 4; the C does not mask");
    }

    @Test
    void lcIsZeroWhenTheNpcHasNoDialogScript() {
        assertTrue(DialogConditions.test(ctx(npc(0, null)), "lc0 0"));
        assertFalse(DialogConditions.test(ctx(npc(0, null)), "lc0 1"));
    }
}
