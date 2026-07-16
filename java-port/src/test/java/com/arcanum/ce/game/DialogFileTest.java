package com.arcanum.ce.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@code .dlg} format against the shapes seen in real game data
 * (dialog.c: dialog_parse_entry / dialog_parse_field).
 */
class DialogFileTest {

    // The head of dlg\01324Virgil.dlg, verbatim (7 brace fields per entry).
    private static final String VIRGIL_HEAD =
            "{1}{I can't believe it...!  Do you have any idea what all of this means?}"
            + "{She can't believe it...!  Female variant.}{}{1}{}{gf2004 1, qu1010 2}\n"
            + "{2}{What are you going on about?}{}{5}{re62}{9}{}\n"
            + "{3}{Whut you say?}{}{-4}{re62}{9}{}\n"
            + "{9}{[The man's jaw drops open.]  You speak!}{}{}{2}{}{}\n";

    @Test
    void parsesNpcAndPcLinesWithTheSevenFields() {
        DialogFile d = DialogFile.parse("test.dlg", VIRGIL_HEAD);
        assertEquals(4, d.entries().size());

        DialogFile.Entry npc = d.find(1);
        assertNotNull(npc);
        assertFalse(npc.isPc(), "blank iq marks an NPC line");
        assertEquals(0, npc.iq);
        assertEquals(0, npc.responseVal, "NPC lines do not jump; their PC options do");
        assertEquals("1", npc.conditions);
        assertEquals("gf2004 1, qu1010 2", npc.actions);
        assertTrue(npc.text.startsWith("I can't believe it"));

        DialogFile.Entry pc = d.find(2);
        assertNotNull(pc);
        assertTrue(pc.isPc(), "non-zero iq marks a PC response");
        assertEquals(5, pc.iq);
        assertEquals("re62", pc.conditions);
        assertEquals(9, pc.responseVal);
    }

    @Test
    void npcLineKeepsItsFemaleVariantAndPcLineKeepsItsGender() {
        DialogFile d = DialogFile.parse("test.dlg", VIRGIL_HEAD);
        // Field 3 is dual-purpose: female text on NPC lines, gender on PC lines.
        DialogFile.Entry npc = d.find(1);
        assertTrue(npc.femaleText.startsWith("She can't believe it"));
        assertTrue(npc.text(true).startsWith("She can't"), "female PC sees the female line");
        assertTrue(npc.text(false).startsWith("I can't"));

        DialogFile.Entry pc = d.find(2);
        assertEquals(-1, pc.gender, "blank gender means any");
    }

    @Test
    void npcLineFallsBackToMaleTextWhenFemaleVariantIsBlank() {
        DialogFile d = DialogFile.parse("t.dlg", "{1}{Only male text.}{}{}{}{}{}\n");
        DialogFile.Entry npc = d.find(1);
        assertEquals("Only male text.", npc.text(true));
    }

    @Test
    void responsesToAnNpcLineAreTheConsecutivePcEntriesAfterIt() {
        DialogFile d = DialogFile.parse("test.dlg", VIRGIL_HEAD);
        List<DialogFile.Entry> opts = d.responsesTo(d.find(1));
        assertEquals(2, opts.size());
        assertEquals(2, opts.get(0).num);
        assertEquals(3, opts.get(1).num);
        // Line 9 is an NPC line, so it must not be swept in as an option.
        assertTrue(opts.stream().allMatch(DialogFile.Entry::isPc));
    }

    @Test
    void skipsSlashSlashCommentsBetweenFields() {
        String src = "// leading comment\n"
                + "{1}{Hello.}{}{}{}{}{}\n"
                + "// another comment {not a field}\n"
                + "{2}{Reply.}{}{5}{}{1}{}\n";
        DialogFile d = DialogFile.parse("t.dlg", src);
        assertEquals(2, d.entries().size(), "comment braces must not be parsed as fields");
        assertEquals("Hello.", d.find(1).text);
        assertEquals("Reply.", d.find(2).text);
    }

    @Test
    void entriesAreSortedByNumSoFindCanBisect() {
        DialogFile d = DialogFile.parse("t.dlg",
                "{50}{Later.}{}{}{}{}{}\n{2}{Earlier.}{}{}{}{}{}\n");
        assertEquals(2, d.entries().get(0).num);
        assertEquals(50, d.entries().get(1).num);
        assertNotNull(d.find(50));
        assertNotNull(d.find(2));
    }

    @Test
    void truncatedTrailingEntryIsDropped() {
        DialogFile d = DialogFile.parse("t.dlg", "{1}{Fine.}{}{}{}{}{}\n{2}{Truncated}{}{5}");
        assertEquals(1, d.entries().size());
    }
}
