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
 * Verifies {@link Description} ({@code description.c}) and {@link OName}
 * ({@code oname.c}) against the real message files, plus {@link ObjectName}'s
 * port of {@code object_examine}.
 *
 * <p>Needs the user's install; guarded like {@link MapMobilesTest}.
 *
 * <p>These pin a correction to the brief this work started from: {@code OBJ_F_NAME}
 * does <em>not</em> resolve through {@code description.mes}. {@code obj.c:1703}
 * sends it through {@code o_name_get} → {@code oemes\oname.mes} (an internal
 * name), while the player-facing string comes from {@code OBJ_F_DESCRIPTION} →
 * {@code description_get} → {@code mes\description.mes}.
 *
 * <p>Virgil is the witness, and the two fields are not even the same number: his
 * {@code OBJ_F_NAME} is 6409 ("6409 Virgil" in oname.mes, and <em>absent</em> from
 * description.mes) while his {@code OBJ_F_DESCRIPTION} is 31073 ("Virgil"). Had
 * {@code OBJ_F_NAME} been read as a description number, he would have had no name
 * at all.
 */
class DescriptionTest {

    private static final String INSTALL = System.getProperty("arcanum.data",
            System.getProperty("user.home")
            + "/.local/share/Steam/steamapps/common/Arcanum/Arcanum");

    /** Virgil's OBJ_F_NAME — an oname.mes number (tools.NpcDump). */
    private static final int VIRGIL_NAME = 6409;

    /**
     * Virgil's OBJ_F_DESCRIPTION — a description.mes number, and a different
     * number entirely from his OBJ_F_NAME. Being >= 30000 it also exercises
     * description_get's module-table branch (mes\gamedesc.mes).
     */
    private static final int VIRGIL_DESCRIPTION = 31073;

    /** Virgil's OBJ_F_CRITTER_DESCRIPTION_UNKNOWN — what a stranger sees. */
    private static final int VIRGIL_UNKNOWN = 17082;

    @BeforeAll
    static void registerData() {
        if (dataAvailable()) {
            TigFile.init();
            System.setProperty("arcanum.data", INSTALL);
            GameData.discoverAndRegister();
            Description.reset();
            OName.reset();
            ProtoStore.reset();
        }
    }

    private static boolean dataAvailable() {
        return new File(INSTALL, "tig.dat").isFile();
    }

    @Test
    void loadsDescriptionTableAndReturnsTextForAKnownNumber() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        Description d = Description.get();
        assertNotNull(d, "mes\\description.mes must load");

        // description_get(31073) -- the string object_examine shows for Virgil once
        // the PC has met him. num >= 30000, so this comes from the module table.
        assertEquals("Virgil", d.describe(VIRGIL_DESCRIPTION));
        assertTrue(d.hasModuleTable(), "mes\\gamedesc.mes must load for num >= 30000");
        // A system-table number (< 30000) reads from mes\description.mes instead.
        assertEquals("Human Villager", d.describe(VIRGIL_UNKNOWN));
        // description_max_num comes from the last (highest-numbered) entry.
        assertTrue(d.maxNum() > 30000,
                "gamedesc.mes should raise max num past 30000, got " + d.maxNum());
    }

    @Test
    void outOfRangeNumbersReturnNull() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        Description d = Description.get();

        // `if (num < 0 || num > description_max_num) return NULL;`
        assertNull(d.describe(-1));
        assertNull(d.describe(d.maxNum() + 1));
        assertNull(d.describe(Integer.MAX_VALUE));
    }

    @Test
    void objFNameResolvesThroughOnameNotDescription() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        OName o = OName.get();
        assertNotNull(o, "oemes\\oname.mes must load");

        // obj.c:1703 -- OBJ_F_NAME goes to o_name_get, which reads oname.mes.
        assertEquals("6409 Virgil", o.name(VIRGIL_NAME));

        // ...and the same number means nothing to description.mes. This is the
        // load-bearing assertion: reading OBJ_F_NAME as a description number --
        // as the brief specified -- yields NO NAME for Virgil at all.
        assertNull(Description.get().describe(VIRGIL_NAME));

        // The display name lives at a different number in a different table.
        assertEquals("Virgil", Description.get().describe(VIRGIL_DESCRIPTION));
        assertTrue(o.count() > 0);
    }

    @Test
    void examineResolvesVirgilThroughTheProtoFallback() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        MapList maps = MapList.load();
        Assumptions.assumeTrue(maps != null, "campaign module not available");

        ProtoStore protos = ProtoStore.get();
        ObjectName names = ObjectName.get(protos);
        assertNotNull(names);

        GameObject virgil = findVirgil(protos, maps);
        Assumptions.assumeTrue(virgil != null, "Virgil not found on the start map");

        // object_examine's NPC branch with pc_obj == NULL takes
        // critter_description_get's else branch: the stranger's view.
        assertEquals("Human Villager", names.name(virgil));
        // reaction_met_before -> OBJ_F_DESCRIPTION.
        assertEquals("Virgil", names.knownName(virgil));
        assertEquals("6409 Virgil", names.internalName(virgil));
        // The two fields carry unrelated numbers; both resolve through the proto.
        assertEquals(VIRGIL_NAME, names.nameNum(virgil));
        assertEquals(VIRGIL_DESCRIPTION, names.descriptionNum(virgil));
    }

    /** The start map's Virgil, identified by his SAP_DIALOG script number. */
    private static GameObject findVirgil(ProtoStore protos, MapList maps) {
        for (GameObject o : MapMobiles.load(maps.startMapName)) {
            Script s = o.script(Sap.DIALOG, protos);
            if (s != null && s.num == 1324) {
                return o;
            }
        }
        return null;
    }
}
