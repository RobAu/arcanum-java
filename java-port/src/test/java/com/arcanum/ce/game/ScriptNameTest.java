package com.arcanum.ce.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.arcanum.ce.GameData;
import com.arcanum.ce.tig.TigFile;

/**
 * Verifies {@link ScriptName} — the port of {@code script_name.c}'s
 * {@code script_name_build_scr_name} / {@code script_name_build_dlg_name}.
 *
 * <p>The {@code atoi} rule is pure logic and always runs. The index itself needs
 * the real {@code scr\*.scr} listing from the user's install, so those checks
 * guard on the data dir like {@link MapMobilesTest}.
 */
class ScriptNameTest {

    private static final String INSTALL = System.getProperty("arcanum.data",
            System.getProperty("user.home")
            + "/.local/share/Steam/steamapps/common/Arcanum/Arcanum");

    /** Virgil's dialog script — the acceptance target for the whole chain. */
    private static final int VIRGIL = 1324;

    @BeforeAll
    static void registerData() {
        if (new File(INSTALL, "tig.dat").isFile()) {
            TigFile.init();
            System.setProperty("arcanum.data", INSTALL);
            GameData.discoverAndRegister();
            ScriptName.reset();
        }
    }

    private static boolean dataAvailable() {
        return new File(INSTALL, "tig.dat").isFile();
    }

    @Test
    void atoiTakesLeadingDigitsOnly() {
        // script_name_init keys the table on atoi(filename), and the shipped names
        // are %.5d-prefixed: "01324Virgil.scr" -> 1324.
        assertEquals(1324, ScriptName.atoi("01324Virgil.scr"));
        assertEquals(997, ScriptName.atoi("00997endslides.scr"));
        assertEquals(1, ScriptName.atoi("00001foo.scr"));
        // No leading digits -> 0, which script_name_build_scr_name rejects outright.
        assertEquals(0, ScriptName.atoi("Virgil.scr"));
        assertEquals(0, ScriptName.atoi(""));
        // Digits stop at the first non-digit; nothing later is consulted.
        assertEquals(12, ScriptName.atoi("12ab34.scr"));
    }

    @Test
    void buildsVirgilsDlgPathFromScriptNumber() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        ScriptName names = ScriptName.get();

        // The acceptance test: 1324 -> scr\01324Virgil.scr -> dlg\01324Virgil.dlg,
        // and the .dlg really is in the archives.
        String scr = names.buildScrName(VIRGIL);
        assertNotNull(scr, "script 1324 must be indexed from scr\\*.scr");
        assertEquals("scr\\01324Virgil.scr", scr);

        String dlg = names.buildDlgName(VIRGIL);
        assertEquals("dlg\\01324Virgil.dlg", dlg);
        assertTrue(TigFile.exists(dlg, null),
                "dlg\\01324Virgil.dlg must exist in the repository stack");
    }

    @Test
    void dlgNameOverwritesFirstAndLastThreeChars() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        ScriptName names = ScriptName.get();

        // script_name_build_dlg_name is a blind overwrite of buffer[0..2] and
        // buffer[len-3..len-1] -- not an extension swap. Same stem, both ends 'dlg'.
        String scr = names.buildScrName(VIRGIL);
        String dlg = names.buildDlgName(VIRGIL);
        assertEquals(scr.length(), dlg.length());
        assertTrue(dlg.startsWith("dlg\\"), dlg);
        assertTrue(dlg.endsWith(".dlg"), dlg);
        assertEquals(scr.substring(4, scr.length() - 4), dlg.substring(4, dlg.length() - 4));
    }

    @Test
    void numberZeroIsNeverAScript() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        ScriptName names = ScriptName.get();
        // `if (num != 0)` guards the whole of script_name_build_scr_name; a zero
        // num is the C's "no script attached" marker.
        assertNull(names.buildScrName(0));
        assertNull(names.buildDlgName(0));
    }

    @Test
    void unindexedNumbersResolveToNull() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        ScriptName names = ScriptName.get();
        // tig_idxtable_get fails -> build returns false. No such script ships.
        assertNull(names.buildScrName(999999));
        assertNull(names.buildDlgName(999999));
    }

    @Test
    void systemTableTakesEverythingAtOrAbove1000() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        ScriptName names = ScriptName.get();

        // The C's own noted quirk: script_name_init claims every num >= 1000 for
        // the system table, leaving script_name_mod_load only 997/998/999. The
        // shipped data has exactly those three below 1000, so the module table is
        // tiny and the system table holds the rest.
        assertTrue(names.systemCount() > 1000,
                "expected the bulk of scripts in the system table, got "
                + names.systemCount());
        assertTrue(names.modCount() <= 3,
                "the module table should only hold 997/998/999, got " + names.modCount());

        // Every indexed name must atoi back to the number it is filed under.
        assertEquals(VIRGIL, ScriptName.atoi(names.fileName(VIRGIL)));
    }
}
