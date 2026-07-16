package com.arcanum.ce.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Verifies the map mobile-object load ({@link MapMobiles}) — the port of the
 * game branch of {@code map_load_mobile} ({@code map.c}).
 *
 * <p>The naming check is pure logic and always runs. The parse check needs the
 * real campaign data (the user's install, not the repo) and is skipped without
 * it, like {@code SectorObjectListTest}.
 */
class MapMobilesTest {

    private static final String INSTALL = System.getProperty("arcanum.data",
            System.getProperty("user.home")
            + "/.local/share/Steam/steamapps/common/Arcanum/Arcanum");

    private static final String START_MAP = "Arcanum1-024-fixed";

    /** MapList START_MAP location for the retail campaign (see MapList). */
    private static final long START_X = 92958;
    private static final long START_Y = 82592;

    @Test
    void obfuscatesTheStartMapName() {
        // ROT13-with-a-bug, then reversed. 'A'-'Z'+1 == -24 (not -26), so a
        // letter wrapping past 'Z'/'z' shifts by -11 overall: 'A'->'N' (no wrap),
        // but 'n'->'a'+... -> the intermediate here is "Ngpncjz1-024-svmrq".
        assertEquals("qrmvs-420-1zjcnpgN", MapMobiles.obfuscateName(START_MAP));
        assertEquals("maps\\qrmvs-420-1zjcnpgN", MapMobiles.path(START_MAP));
    }

    @Test
    void obfuscationQuirksMatchTheC() {
        // Non-wrapping letters are plain ROT13 (reversed): "A" -> "N".
        assertEquals("N", MapMobiles.obfuscateName("A"));
        assertEquals("n", MapMobiles.obfuscateName("a"));
        // Wrapping letters shift by -11, not -13: 'N'(78)+13=91 > 'Z' -> 91-24=67='C'
        // (plain ROT13 would give 'A'). Likewise 'n' -> 'c'.
        assertEquals("C", MapMobiles.obfuscateName("N"));
        assertEquals("c", MapMobiles.obfuscateName("n"));
        // 'Z'(90)+13=103 > 'Z' -> 103-24=79='O' (plain ROT13 would give 'M').
        assertEquals("O", MapMobiles.obfuscateName("Z"));
        assertEquals("o", MapMobiles.obfuscateName("z"));
        // 'm'(109)+13=122='z' exactly -- no wrap, so this one is plain ROT13.
        assertEquals("z", MapMobiles.obfuscateName("m"));
        // Non-letters pass through untouched, and the whole string is reversed.
        assertEquals("4-320-1", MapMobiles.obfuscateName("1-023-4"));
        // The intermediate ROT13 pass, made visible by reversing a palindrome-free
        // name: obfuscate is its own composition of rot+reverse, not an involution.
        assertEquals("Ngpncjz1-024-svmrq",
                new StringBuilder(MapMobiles.obfuscateName(START_MAP)).reverse().toString());
    }

    @Test
    void parsesTheStartMapMobileFile() throws Exception {
        Path p = Paths.get(INSTALL, "modules", "Arcanum", "maps",
                MapMobiles.obfuscateName(START_MAP));
        Assumptions.assumeTrue(Files.isReadable(p), "start map mobile file not available: " + p);

        byte[] bytes = Files.readAllBytes(p);
        MapMobiles.Result r = MapMobiles.parse(bytes);

        // The oracle from map_load_mobile: obj_read runs until EOF and the feof
        // check must pass -- i.e. the object stream consumes the file exactly.
        assertTrue(r.consumedExactly(),
                "object stream must consume the file exactly, but stopped with "
                + r.bytesRemaining + " bytes left after " + r.objects.size()
                + " objects: " + r.error);
        assertTrue(r.objects.size() > 1000,
                "expected the start map to carry a lot of mobiles, got " + r.objects.size());

        // Mobiles standing in the start sector: this is what the world view draws.
        long startSector = Location.sectorMake(START_X >> 6, START_Y >> 6);
        int inSector = 0;
        int npcs = 0;
        for (GameObject o : r.objects) {
            if (Location.sectorIdFromLoc(o.location()) == startSector) {
                inSector++;
                if (o.type == ObjectFields.OBJ_TYPE_NPC || o.type == ObjectFields.OBJ_TYPE_PC) {
                    npcs++;
                }
            }
        }
        System.out.println(p.getFileName() + ": " + bytes.length + " bytes, "
                + r.objects.size() + " mobiles, " + inSector + " in start sector "
                + startSector + " (" + npcs + " critters)");
        assertTrue(inSector > 0, "start sector must be populated");
        assertTrue(npcs > 0, "start sector must have critters (Virgil & co.)");
    }
}
