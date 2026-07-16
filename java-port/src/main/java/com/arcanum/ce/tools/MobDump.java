package com.arcanum.ce.tools;

import java.util.Map;
import java.util.TreeMap;

import com.arcanum.ce.GameData;
import com.arcanum.ce.game.GameObject;
import com.arcanum.ce.game.Location;
import com.arcanum.ce.game.MapList;
import com.arcanum.ce.game.MapMobiles;
import com.arcanum.ce.game.NameResolver;
import com.arcanum.ce.tig.TigArt;
import com.arcanum.ce.tig.TigFile;

/**
 * Headless diagnostic: parse a map's mobile-object file (the NPCs, critters and
 * ground items — {@code map_load_mobile} in {@code map.c}) and dump what came
 * out, to validate {@link MapMobiles} against the real data.
 *
 * <p>Run:
 * {@code ./gradlew runTool -Ptool=tools.MobDump -Darcanum.data=<dir> -Darcanum.map=Arcanum1-024-fixed}
 */
public final class MobDump {

    private static final String[] TYPE_NAMES = {
        "WALL", "PORTAL", "CONTAINER", "SCENERY", "PROJECTILE", "WEAPON", "AMMO",
        "ARMOR", "GOLD", "FOOD", "SCROLL", "KEY", "KEY_RING", "WRITTEN", "GENERIC",
        "PC", "NPC", "TRAP",
    };

    /** OBJ_F_NAME (obj.h enum ordinal 22) — an INT32 name number. */
    private static final int OBJ_F_NAME = 22;

    private MobDump() {
    }

    public static void main(String[] args) {
        String mapName = System.getProperty("arcanum.map",
                args.length > 0 ? args[0] : "Arcanum1-024-fixed");

        TigFile.init();
        GameData.discoverAndRegister();
        NameResolver.install();

        String path = MapMobiles.path(mapName);
        System.out.println("map:        " + mapName);
        System.out.println("obfuscated: " + path);

        byte[] bytes = TigFile.readBytes(path);
        if (bytes == null) {
            System.err.println("mobile file not found in the repository stack: " + path);
            System.exit(1);
            return;
        }
        System.out.println("size:       " + bytes.length + " bytes");

        MapMobiles.Result r = MapMobiles.parse(bytes);
        System.out.println("mobiles:    " + r.objects.size());
        System.out.println("consumed exactly: " + (r.consumedExactly() ? "yes ✓"
                : "NO — " + r.bytesRemaining + " bytes remaining"
                  + (r.error != null ? " (" + r.error + ")" : "")));

        // The start sector: where a new game opens (MapList START_MAP).
        MapList maps = MapList.load();
        long startSector = maps != null
                ? Location.sectorMake(maps.startX >> 6, maps.startY >> 6)
                : 0;
        if (maps != null) {
            System.out.println("start map:  " + maps.startMapName + " at ("
                    + maps.startX + ", " + maps.startY + ")  sector " + startSector
                    + "  spawn tile (" + maps.spawnTileX() + ", " + maps.spawnTileY() + ")");
        }

        // Breakdown by ObjectType, overall and within the start sector.
        TreeMap<Integer, Integer> typeHist = new TreeMap<>();
        TreeMap<Integer, Integer> startHist = new TreeMap<>();
        int inStart = 0;
        int startDrawable = 0;
        int noArt = 0;
        for (GameObject o : r.objects) {
            typeHist.merge(o.type, 1, Integer::sum);
            if (o.currentAid() == 0) {
                noArt++;
            }
            if (Location.sectorIdFromLoc(o.location()) == startSector) {
                inStart++;
                startHist.merge(o.type, 1, Integer::sum);
                if (o.currentAid() != 0) {
                    startDrawable++;
                }
            }
        }
        System.out.println("\nby ObjectType (whole map):");
        printHist(typeHist);
        System.out.println("  (objects with no CURRENT_AID: " + noArt + ")");

        System.out.println("\nin start sector " + startSector + ": " + inStart);
        printHist(startHist);
        System.out.println("  drawable now (CURRENT_AID overridden on the instance): "
                + startDrawable);
        System.out.println("  inheriting CURRENT_AID from their prototype: "
                + (inStart - startDrawable) + " — obj_field_fetch (obj.c) falls back to"
                + " the proto for fields absent from the instance's dif bitmap;"
                + " proto lookup is not ported, so these do not draw yet.");

        // Do the start sector's mobiles actually have renderable art?
        System.out.println("\nstart-sector sample — CURRENT_AID -> art path:");
        int shown = 0;
        int missing = 0;
        int checked = 0;
        TreeMap<Integer, Integer> missingByArtType = new TreeMap<>();
        for (GameObject o : r.objects) {
            if (Location.sectorIdFromLoc(o.location()) != startSector) {
                continue;
            }
            int aid = o.currentAid();
            if (aid == 0) {
                continue;
            }
            checked++;
            String artPath = TigArt.buildPath(aid);
            boolean exists = artPath != null && TigFile.exists(artPath, null);
            if (!exists) {
                missing++;
                missingByArtType.merge(aid >>> 28, 1, Integer::sum);
            }
            if (shown++ < 24) {
                long loc = o.location();
                // OBJ_F_NAME is an INT32 name number into the description tables
                // (obj.c: object_fields[OBJ_F_NAME].type = OD_TYPE_INT32), not a
                // string — print the raw id; resolving it needs description.mes.
                System.out.printf("  %-9s tile(%2d,%2d) name#%-6s 0x%08X %-42s %s%n",
                        typeName(o.type),
                        (int) (Location.getX(loc) & 63), (int) (Location.getY(loc) & 63),
                        str(o.field(OBJ_F_NAME)),
                        aid, artPath, exists ? "OK" : "MISSING");
            }
        }
        System.out.println("  checked " + checked + " art id(s); "
                + (missing == 0 ? "all resolved + present ✓" : missing + " MISSING"));
        for (Map.Entry<Integer, Integer> e : missingByArtType.entrySet()) {
            System.out.println("    missing art type " + artTypeName(e.getKey())
                    + ": " + e.getValue());
        }
    }

    /** TigArtType (tig/art.h), the art_id's high nibble. */
    private static String artTypeName(int artType) {
        String[] names = {
            "TILE", "WALL", "CRITTER", "PORTAL", "SCENERY", "INTERFACE", "ITEM",
            "CONTAINER", "MISC", "LIGHT", "ROOF", "FACADE", "MONSTER",
            "UNIQUE_NPC", "EYE_CANDY",
        };
        return artType >= 0 && artType < names.length
                ? names[artType] + "(" + artType + ")" : "type" + artType;
    }

    private static void printHist(Map<Integer, Integer> hist) {
        for (Map.Entry<Integer, Integer> e : hist.entrySet()) {
            System.out.printf("  %-11s %d%n", typeName(e.getKey()), e.getValue());
        }
    }

    private static String typeName(int type) {
        return type >= 0 && type < TYPE_NAMES.length ? TYPE_NAMES[type] : "type" + type;
    }

    private static String str(Object v) {
        return v == null ? "-" : String.valueOf(v);
    }
}
