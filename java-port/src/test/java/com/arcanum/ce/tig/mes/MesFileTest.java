package com.arcanum.ce.tig.mes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link MesFile} parser against the format in
 * {@code src/game/mes.c} (parse_entry / parse_field).
 */
class MesFileTest {

    // Realistic .mes content: comments outside braces, multiple entries per
    // line, and a multi-line body. (Real files never put braces in comments,
    // since parse_field has no comment awareness -- it would desync pairing.)
    private static final String SAMPLE =
            "// a comment line, skipped because it is outside braces\n"
            + "{0}{lagicon}\n"
            + "{1}{first} {2}{second}\n"          // two entries on one line
            + "// another plain comment line\n"
            + "{10}{a multi-line\nmessage body}\n";

    @Test
    void parsesNumbersAndText() {
        MesFile m = MesFile.parse(SAMPLE);
        assertEquals("lagicon", m.find(0));
        assertEquals("first", m.find(1));
        assertEquals("second", m.find(2));
    }

    @Test
    void keepsMultiLineTextVerbatim() {
        MesFile m = MesFile.parse(SAMPLE);
        assertEquals("a multi-line\nmessage body", m.find(10));
    }

    @Test
    void missingNumberReportsErrorMarker() {
        MesFile m = MesFile.parse(SAMPLE);
        assertNull(m.find(999));
        assertEquals("Mes Error(999)", m.getMsg(999));
        assertFalse(m.contains(999));
    }

    @Test
    void countsAllEntries() {
        MesFile m = MesFile.parse(SAMPLE);
        assertEquals(4, m.count());
        assertTrue(m.contains(0));
        assertTrue(m.contains(10));
    }
}
