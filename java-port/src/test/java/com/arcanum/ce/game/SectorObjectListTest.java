package com.arcanum.ce.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.arcanum.ce.tig.database.DatArchive;

/**
 * Verifies the sector object-list parse ({@link SectorFile} / {@link ObjReader})
 * against real Arcanum sectors. The correctness oracle (from
 * {@code objlist_load} in {@code sector_object_list.c}): after reading the
 * trailing object count's worth of objects the buffer position lands exactly on
 * the trailing {@code int32}, and that value equals the count.
 * {@link SectorFile#parse} enforces this internally and returns an empty list if
 * it is violated, so {@code objects.size() == trailingCount} is the check.
 *
 * <p>These files live in the user's Steam install, not the repo, so if they are
 * absent the disk-backed tests are skipped (the pure-logic checks still run).
 */
class SectorObjectListTest {

    private static final String INSTALL = System.getProperty("arcanum.data",
            System.getProperty("user.home")
            + "/.local/share/Steam/steamapps/common/Arcanum/Arcanum");

    private static final String SHOP_MAP = System.getProperty("arcanum.shopmap",
            INSTALL + "/modules/Arcanum/maps/ShopMap/0.sec");

    @Test
    void wordCountsAndMetadataAreConsistent() {
        assertEquals(ObjectFields.OD_INT32, ObjectFields.TYPE[ObjectFields.OBJ_F_CURRENT_AID]);
        assertEquals(ObjectFields.OD_INT64, ObjectFields.TYPE[ObjectFields.OBJ_F_LOCATION]);
        // Every enumerated field's change-array index must fit inside its type's
        // field_48 word count.
        for (int t = 0; t <= ObjectFields.OBJ_TYPE_TRAP; t++) {
            int words = ObjectFields.wordCount(t);
            for (int[] r : ObjectFields.rangesForType(t)) {
                for (int f = r[0] + 1; f < r[1]; f++) {
                    assertTrue(ObjectFields.cai[f] < words,
                            "cai out of range for type " + t + " field " + f);
                }
            }
        }
    }

    @Test
    void parsesRealShopMapSector() throws Exception {
        Path p = Paths.get(SHOP_MAP);
        Assumptions.assumeTrue(Files.isReadable(p), "ShopMap/0.sec not available: " + p);

        byte[] bytes = Files.readAllBytes(p);
        int trailingCount = trailingCount(bytes);
        System.out.println("ShopMap/0.sec: " + bytes.length + " bytes, trailing count="
                + trailingCount);

        SectorFile sec = SectorFile.parse(bytes);
        // Oracle: parsed count == trailing count (SectorFile enforces the
        // end-position equality internally). ShopMap/0.sec's static object list
        // is legitimately empty — its mobile objects live in sibling .mob files.
        assertEquals(trailingCount, sec.objects.size(),
                "parsed object count must match the trailing count");
    }

    @Test
    void parsesTerrainSectorWithObjects() throws Exception {
        // Pick any archived sector that actually carries a non-zero object list
        // and assert the oracle end-to-end, surfacing a few decoded objects.
        File dir = new File(INSTALL);
        File[] dats = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".dat"));
        Assumptions.assumeTrue(dats != null && dats.length > 0,
                "no Arcanum .dat archives at " + INSTALL);

        for (File dat : dats) {
            try (DatArchive ar = new DatArchive(dat)) {
                for (DatArchive.Entry e : ar.files()) {
                    if (!e.path.toLowerCase().endsWith(".sec")) {
                        continue;
                    }
                    byte[] bytes = ar.read(e.path);
                    if (bytes == null || bytes.length < 4) {
                        continue;
                    }
                    int cnt = trailingCount(bytes);
                    if (cnt <= 0) {
                        continue;
                    }
                    SectorFile sec = SectorFile.parse(bytes);
                    assertEquals(cnt, sec.objects.size(),
                            "oracle failed for " + e.path + " in " + dat.getName());
                    System.out.println(e.path + " (" + dat.getName() + "): "
                            + sec.objects.size() + " objects; sample:");
                    int shown = 0;
                    for (GameObject o : sec.objects) {
                        long loc = o.location();
                        System.out.printf("  type=%-2d loc=(%d,%d) tileIdx=%d aid=0x%08X%s%n",
                                o.type, Location.getX(loc), Location.getY(loc),
                                Location.tileIndexInSector(loc), o.currentAid(),
                                o.isProto ? " [proto]" : "");
                        if (++shown >= 8) {
                            break;
                        }
                    }
                    return;     // one sector is enough to prove the parse
                }
            }
        }
        Assumptions.abort("no archived sector with a non-empty object list found");
    }

    private static int trailingCount(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(bytes.length - 4);
    }
}
