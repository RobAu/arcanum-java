package com.arcanum.ce.tools;

import java.util.TreeMap;

import com.arcanum.ce.GameData;
import com.arcanum.ce.game.NameResolver;
import com.arcanum.ce.game.SectorFile;
import com.arcanum.ce.tig.TigArt;
import com.arcanum.ce.tig.TigFile;
import com.arcanum.ce.tig.art.ArtId;

/**
 * Diagnostic: scan a {@code .sec} for two-terrain <em>blend</em> tiles (num1 !=
 * num2) and report whether their resolved {@code art\tile\*.art} paths exist —
 * i.e. whether tile-edge seam resolution ({@link
 * com.arcanum.ce.game.TileNames#edgeIndex}) is correct.
 *
 * <p>Run: {@code ./gradlew runTool -Ptool=tools.TileEdgeCheck
 * -Darcanum.data=<dir> [-Darcanum.sector=<repo\path.sec>]}
 */
public final class TileEdgeCheck {

    private TileEdgeCheck() {
    }

    public static void main(String[] args) {
        TigFile.init();
        GameData.discoverAndRegister();
        NameResolver.install();

        String path = System.getProperty("arcanum.sector",
                "terrain\\desert to plains\\1.sec");
        SectorFile sec = SectorFile.load(path);
        if (sec == null) {
            System.err.println("sector not found: " + path);
            return;
        }
        System.out.println("sector: " + path);

        String probe = System.getProperty("arcanum.probe");   // "x,y"
        if (probe != null && probe.matches("\\d+,\\d+")) {
            String[] xy = probe.split(",");
            int px = Integer.parseInt(xy[0]);
            int py = Integer.parseInt(xy[1]);
            int aid = sec.tileAt(px, py);
            com.arcanum.ce.game.TileNames tn = com.arcanum.ce.game.TileNames.load();
            System.out.printf("probe (%d,%d): aid=0x%08X type=%d blocking=%b -> %s%n",
                    px, py, aid, ArtId.type(aid),
                    com.arcanum.ce.game.Tile.isBlocking(aid, tn), TigArt.buildPath(aid));
        }

        int base = 0;
        int blend = 0;
        int blendOk = 0;
        int blendMissing = 0;
        int missFlip = 0;
        int okFlip = 0;
        int dumped = 0;
        TreeMap<String, Integer> missingExamples = new TreeMap<>();
        TreeMap<String, Integer> okExamples = new TreeMap<>();
        int nonTile = 0;
        int facadeOk = 0;
        int facadeMissing = 0;
        long sumX = 0;
        long sumY = 0;
        for (int i = 0; i < SectorFile.TILE_COUNT; i++) {
            int aid = sec.tileArtIds[i];
            if (ArtId.type(aid) != ArtId.TYPE_TILE) {
                nonTile++;   // e.g. FACADE cliff faces
                if (ArtId.type(aid) == ArtId.TYPE_FACADE) {
                    String fp = TigArt.buildPath(aid);
                    if (fp != null && TigFile.exists(fp, null)) {
                        facadeOk++;
                    } else {
                        facadeMissing++;
                    }
                }
                continue;
            }
            if (ArtId.tileNum1(aid) == ArtId.tileNum2(aid)) {
                base++;
                continue;
            }
            sumX += i & 0x3F;          // tile x within sector
            sumY += (i >> 6) & 0x3F;   // tile y within sector
            blend++;
            boolean flip = ArtId.tileFlippable(aid) && (ArtId.tileFlags(aid) & 1) != 0;
            int drawAid = flip ? (aid & ~1) : aid;   // tig_art_blit flip normalization
            String p = TigArt.buildPath(drawAid);
            boolean exists = p != null && TigFile.exists(p, null);
            if (exists) {
                blendOk++;
                if (flip) {
                    okFlip++;
                }
                okExamples.merge(p, 1, Integer::sum);
            } else {
                blendMissing++;
                if (flip) {
                    missFlip++;
                }
                missingExamples.merge(String.valueOf(p), 1, Integer::sum);
                if (dumped++ < 8) {
                    System.out.printf("  MISS aid=0x%08X flip=%b blend=%d var=%d -> %s%n",
                            aid, flip, ArtId.tileBlend(aid), ArtId.tileVariation(aid), p);
                }
            }
        }
        System.out.printf("flip: %d/%d OK are flip, %d/%d MISSING are flip%n",
                okFlip, blendOk, missFlip, blendMissing);

        System.out.printf("tiles: %d base, %d blend, %d non-tile (facade/etc)  "
                + "(blend art: %d OK, %d MISSING; facade art: %d OK, %d MISSING)%n",
                base, blend, nonTile, blendOk, blendMissing, facadeOk, facadeMissing);
        if (blend > 0) {
            System.out.printf("blend centroid tile: (%d, %d)%n", sumX / blend, sumY / blend);
        }
        System.out.println("-- sample resolved blend art (OK) --");
        okExamples.entrySet().stream().limit(12)
                .forEach(e -> System.out.printf("  x%-4d %s%n", e.getValue(), e.getKey()));
        if (blendMissing > 0) {
            System.out.println("-- sample MISSING blend art --");
            missingExamples.entrySet().stream().limit(12)
                    .forEach(e -> System.out.printf("  x%-4d %s%n", e.getValue(), e.getKey()));
        }
    }
}
