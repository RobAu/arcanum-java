package com.arcanum.ce.tools;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.arcanum.ce.GameData;
import com.arcanum.ce.game.TileArtResolver;
import com.arcanum.ce.game.TileNames;
import com.arcanum.ce.tig.TigFile;
import com.arcanum.ce.tig.database.DatArchive;

/**
 * Headless diagnostic: parse a {@code .sec} sector file and dump its terrain
 * tile layer, to validate the on-disk format described in
 * {@code tile-rendering-spec.md} before wiring the renderer.
 *
 * <p>Format: {@code int32 lightCount}, then {@code lightCount * 48} bytes of
 * light records (skipped), then {@code 4096 * uint32} tile {@code art_id}s.
 *
 * <p>Run:
 * {@code ./gradlew runTool -Ptool=tools.SecDump -Darcanum.data=<dir> -Pargs="terrain/plains/0.sec"}
 */
public final class SecDump {

    private static final int TILE_COUNT = 4096;        // 64x64
    private static final int LIGHT_RECORD = 48;        // LightSerializedData (0x30)

    private SecDump() {
    }

    public static void main(String[] args) throws Exception {
        // -Darcanum.sec preserves paths with spaces (runTool splits -Pargs on whitespace).
        String internal = System.getProperty("arcanum.sec",
                args.length > 0 ? args[0] : "terrain/plains/0.sec");
        File dir = new File(System.getProperty("arcanum.data", "."));

        // Register archives so Mes/TigArt resolution works, then build the tile
        // name resolver for the end-to-end check below.
        TigFile.init();
        GameData.discoverAndRegister();
        TileNames tileNames = TileNames.load();
        TileArtResolver resolver = tileNames != null ? new TileArtResolver(tileNames) : null;
        System.out.println("tilename.mes loaded: " + (tileNames != null));

        byte[] bytes = readFromArchives(dir, internal);
        if (bytes == null) {
            System.err.println("Not found in any archive: " + internal);
            System.exit(1);
            return;
        }
        System.out.println("file: " + internal + "  (" + bytes.length + " bytes)");

        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int lightCount = b.getInt();
        System.out.println("lights: " + lightCount + "  -> skip "
                + (lightCount * LIGHT_RECORD) + " bytes");
        b.position(b.position() + lightCount * LIGHT_RECORD);

        int[] tiles = new int[TILE_COUNT];
        for (int i = 0; i < TILE_COUNT; i++) {
            tiles[i] = b.getInt();
        }
        System.out.println("read " + TILE_COUNT + " tile art_ids; "
                + (b.remaining()) + " bytes remain after tile layer");

        // Distinct tile ids + decode a few.
        java.util.TreeMap<Integer, Integer> hist = new java.util.TreeMap<>();
        for (int id : tiles) {
            hist.merge(id, 1, Integer::sum);
        }
        System.out.println("distinct tile art_ids: " + hist.size());

        System.out.println("\nfirst row (y=0, x=0..7):");
        for (int x = 0; x < 8; x++) {
            dump(x + ", 0", tiles[x]);            // index = x + y*64
        }
        System.out.println("\nresolve each distinct tile id -> path (exists?):");
        int missing = 0;
        for (Integer id : hist.keySet()) {
            String path = resolver != null ? resolver.resolve(id) : null;
            boolean exists = path != null && TigFile.exists(path, null);
            if (!exists) {
                missing++;
            }
            System.out.printf("  0x%08X  x%-4d  %-28s %s%n",
                    id, hist.get(id), path, exists ? "OK" : "MISSING");
        }
        System.out.println(missing == 0
                ? "all tile art resolved + present ✓"
                : missing + " distinct tile id(s) unresolved/missing");

        // Object layer: parse the full sector and resolve each object's art so we
        // know the scenery/critters will actually render (object-rendering-spec.md).
        com.arcanum.ce.game.NameResolver.install();
        com.arcanum.ce.game.SectorFile sec = com.arcanum.ce.game.SectorFile.parse(bytes);
        if (sec == null) {
            System.out.println("\nobjects: sector parse returned null (alignment failed)");
            return;
        }
        System.out.println("\nobjects: " + sec.objects.size());
        java.util.TreeMap<Integer, Integer> aidHist = new java.util.TreeMap<>();
        java.util.TreeMap<Integer, Integer> typeHist = new java.util.TreeMap<>();
        for (com.arcanum.ce.game.GameObject o : sec.objects) {
            typeHist.merge(o.type, 1, Integer::sum);
            aidHist.merge(o.currentAid(), 1, Integer::sum);
        }
        System.out.println("  by ObjectType: " + typeHist);
        int objMissing = 0;
        int shown = 0;
        for (java.util.Map.Entry<Integer, Integer> e : aidHist.entrySet()) {
            int aid = e.getKey();
            String path = com.arcanum.ce.tig.TigArt.buildPath(aid);
            boolean exists = path != null && TigFile.exists(path, null);
            if (!exists) {
                objMissing++;
            }
            if (shown++ < 20) {
                System.out.printf("  0x%08X  x%-4d  %-40s %s%n",
                        aid, e.getValue(), path, exists ? "OK" : "MISSING");
            }
        }
        System.out.println("  distinct object art_ids: " + aidHist.size()
                + (objMissing == 0 ? "  all resolved + present ✓"
                                   : "  " + objMissing + " unresolved/missing"));
    }

    /** Decode a tile art_id per the bitfields in tile-rendering-spec.md. */
    private static void dump(String label, int id) {
        int type = id >>> 28;
        int num1 = (id >>> 22) & 63;
        int num2 = (id >>> 16) & 63;
        int tileType = (id >>> 8) & 1;          // 0=indoor, 1=outdoor
        int flip1 = (id >>> 7) & 1;
        int flip2 = (id >>> 6) & 1;
        int flags = id & 0xF;
        int blend = (id >>> 12) & 0xF;          // v1 (pre-remap)
        int variation = (id >>> 9) & 7;         // v2 (pre-remap)
        System.out.printf(
                "  [%s] id=0x%08X type=%d num1=%d num2=%d tt=%d f1=%d f2=%d "
                + "blend=%d var=%d flags=%d%n",
                label, id, type, num1, num2, tileType, flip1, flip2,
                blend, variation, flags);
    }

    private static byte[] readFromArchives(File dir, String internal) throws Exception {
        File[] dats = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".dat"));
        if (dats == null) {
            return null;
        }
        for (File dat : dats) {
            try (DatArchive ar = new DatArchive(dat)) {
                if (ar.contains(internal)) {
                    return ar.read(internal);
                }
            }
        }
        return null;
    }
}
