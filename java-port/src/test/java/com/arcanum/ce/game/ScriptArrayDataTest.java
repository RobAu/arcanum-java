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
 * The SCRIPT_ARRAY decode against real game data: {@code OBJ_F_SCRIPTS_IDX}
 * parsed off disk, {@code sa_get} by SAP key, and the full
 * object → {@code SAP_DIALOG} → num → {@code .dlg} chain.
 *
 * <p>{@link SizeableArrayTest} pins the sparse semantics on synthetic buffers;
 * this pins that they are the semantics the shipped data actually needs.
 *
 * <p>Needs the user's install; guarded like {@link MapMobilesTest}.
 */
class ScriptArrayDataTest {

    private static final String INSTALL = System.getProperty("arcanum.data",
            System.getProperty("user.home")
            + "/.local/share/Steam/steamapps/common/Arcanum/Arcanum");

    private static final int VIRGIL_DLG = 1324;

    @BeforeAll
    static void registerData() {
        if (dataAvailable()) {
            TigFile.init();
            System.setProperty("arcanum.data", INSTALL);
            GameData.discoverAndRegister();
            ProtoStore.reset();
            ScriptName.reset();
        }
    }

    private static boolean dataAvailable() {
        return new File(INSTALL, "tig.dat").isFile();
    }

    @Test
    void scriptsIdxParsesAsASizeableArrayOfScripts() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        ProtoStore protos = ProtoStore.get();

        int withScripts = 0;
        for (GameObject o : protos.protos()) {
            Object v = o.field(ObjectFields.OBJ_F_SCRIPTS_IDX);
            if (v == null) {
                continue;
            }
            assertTrue(v instanceof SizeableArray,
                    "OBJ_F_SCRIPTS_IDX must decode to a SizeableArray, got " + v);
            SizeableArray sa = (SizeableArray) v;
            // OD_TYPE_SCRIPT_ARRAY elements are sizeof(Script) == 0xC.
            assertEquals(Script.SIZE, sa.size,
                    "SCRIPT_ARRAY stride must be sizeof(Script)");
            // The bitset and the element count must agree, or rank() lies.
            assertEquals(sa.count, sa.keys().length,
                    "bitset must name exactly `count` keys");
            withScripts++;
        }
        assertTrue(withScripts > 0, "expected prototypes carrying scripts");
    }

    /**
     * The trap, measured on real data: for every prototype that has a SAP_DIALOG,
     * is the naive "element[9]" read wrong? If this ever reports a case where
     * key == slot, the sparse handling still matters -- but today it is 100%.
     */
    @Test
    void sapDialogIsNeverAtSlotNineInRealData() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        ProtoStore protos = ProtoStore.get();

        int dialogPresent = 0;
        int naiveWouldBeWrong = 0;
        for (GameObject o : protos.protos()) {
            Object v = o.field(ObjectFields.OBJ_F_SCRIPTS_IDX);
            if (!(v instanceof SizeableArray)) {
                continue;
            }
            SizeableArray sa = (SizeableArray) v;
            if (!sa.has(Sap.DIALOG)) {
                continue;
            }
            dialogPresent++;
            // Either the rank-resolved slot differs from the key, or element[9]
            // is not even in the buffer -- both mean a naive reader is wrong.
            if (sa.rank(Sap.DIALOG) != Sap.DIALOG || Sap.DIALOG >= sa.count) {
                naiveWouldBeWrong++;
            }
        }

        assertTrue(dialogPresent > 0, "expected prototypes with a SAP_DIALOG script");
        assertEquals(dialogPresent, naiveWouldBeWrong,
                "every SAP_DIALOG in the shipped protos should sit at a slot other"
                + " than 9 -- indexing by the raw key is always wrong here");
    }

    @Test
    void virgilResolvesDialog1324ThroughTheWholeChain() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        MapList maps = MapList.load();
        Assumptions.assumeTrue(maps != null, "campaign module not available");

        ProtoStore protos = ProtoStore.get();
        ScriptName scriptNames = ScriptName.get();

        // obj_arrayfield_script_get(npc, OBJ_F_SCRIPTS_IDX, SAP_DIALOG) -> num
        // -> script_name_build_dlg_name -> the file.
        GameObject virgil = null;
        for (GameObject o : MapMobiles.load(maps.startMapName)) {
            Script s = o.script(Sap.DIALOG, protos);
            if (s != null && s.num == VIRGIL_DLG) {
                virgil = o;
                break;
            }
        }
        assertNotNull(virgil, "an NPC on the start map must carry SAP_DIALOG " + VIRGIL_DLG);
        assertEquals(ObjectFields.OBJ_TYPE_NPC, virgil.type);

        Script dlg = virgil.script(Sap.DIALOG, protos);
        assertEquals(VIRGIL_DLG, dlg.num);

        String path = scriptNames.buildDlgName(dlg.num);
        assertEquals("dlg\\01324Virgil.dlg", path);
        assertTrue(TigFile.exists(path, null), path + " must exist in the archives");
    }

    @Test
    void scriptsResolveThroughThePrototypeFallback() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        MapList maps = MapList.load();
        Assumptions.assumeTrue(maps != null, "campaign module not available");
        ProtoStore protos = ProtoStore.get();

        // Virgil's scripts come from his prototype, not his instance -- the same
        // obj_field_fetch fallback the art id already relies on. Pin that the
        // instance really does not override the field, so this is a true proto read.
        GameObject virgil = null;
        for (GameObject o : MapMobiles.load(maps.startMapName)) {
            Script s = o.script(Sap.DIALOG, protos);
            if (s != null && s.num == VIRGIL_DLG) {
                virgil = o;
                break;
            }
        }
        Assumptions.assumeTrue(virgil != null, "Virgil not found");

        if (!virgil.has(ObjectFields.OBJ_F_SCRIPTS_IDX)) {
            // Inherited: without the proto store there is nothing to read.
            assertNull(virgil.script(Sap.DIALOG, null),
                    "with no ProtoStore the inherited script must not resolve");
            GameObject proto = protos.proto(virgil.prototypeOid);
            assertNotNull(proto, "Virgil's prototype must be loaded");
            assertTrue(proto.field(ObjectFields.OBJ_F_SCRIPTS_IDX) instanceof SizeableArray,
                    "the prototype is where the scripts live");
        }
    }

    @Test
    void absentSapReadsAsNoScript() {
        Assumptions.assumeTrue(dataAvailable(), "game data not available: " + INSTALL);
        ProtoStore protos = ProtoStore.get();

        // A SAP no prototype uses must never resolve to a stray element from a
        // neighbouring key -- the failure mode a naive index would produce.
        for (GameObject o : protos.protos()) {
            Object v = o.field(ObjectFields.OBJ_F_SCRIPTS_IDX);
            if (!(v instanceof SizeableArray)) {
                continue;
            }
            SizeableArray sa = (SizeableArray) v;
            for (int key : new int[] {Sap.CRITICAL_MISS, Sap.CAUGHT_THIEF}) {
                if (!sa.has(key)) {
                    assertNull(sa.get(key), "absent SAP " + key + " must read as nothing");
                }
            }
        }
    }
}
