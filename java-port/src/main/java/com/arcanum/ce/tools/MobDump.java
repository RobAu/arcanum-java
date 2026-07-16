package com.arcanum.ce.tools;

import java.util.Map;
import java.util.TreeMap;

import com.arcanum.ce.GameData;
import com.arcanum.ce.game.GameObject;
import com.arcanum.ce.game.Location;
import com.arcanum.ce.game.MapList;
import com.arcanum.ce.game.MapMobiles;
import com.arcanum.ce.game.NameResolver;
import com.arcanum.ce.game.ObjectFields;
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

        // The prototypes most objects inherit their art from (obj_field_fetch).
        com.arcanum.ce.game.ProtoStore protos = com.arcanum.ce.game.ProtoStore.get();
        System.out.println("protos:     " + protos.size() + " loaded from "
                + protos.filesFound() + " proto\\*.pro file(s), "
                + protos.failures().size() + " failed");

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

        // Breakdown by ObjectType, overall and within the start sector, plus the
        // drawable split before vs after prototype resolution.
        TreeMap<Integer, Integer> typeHist = new TreeMap<>();
        TreeMap<Integer, Integer> startHist = new TreeMap<>();
        int inStart = 0;
        int startDrawableBefore = 0;
        int startDrawableAfter = 0;
        int noArtBefore = 0;
        int noArtAfter = 0;
        // Why does an object still have no art id after resolution?
        int missingProto = 0;       // its prototype_oid isn't in the store
        int protoNoAid = 0;         // the proto loaded, but carries no CURRENT_AID
        for (GameObject o : r.objects) {
            typeHist.merge(o.type, 1, Integer::sum);
            boolean before = o.currentAid() != 0;
            boolean after = o.currentAid(protos) != 0;
            if (!before) {
                noArtBefore++;
            }
            if (!after) {
                noArtAfter++;
                if (!o.isProto && protos.proto(o.prototypeOid) == null) {
                    missingProto++;
                } else {
                    protoNoAid++;
                }
            }
            if (Location.sectorIdFromLoc(o.location(protos)) == startSector) {
                inStart++;
                startHist.merge(o.type, 1, Integer::sum);
                if (before) {
                    startDrawableBefore++;
                }
                if (after) {
                    startDrawableAfter++;
                }
            }
        }
        System.out.println("\nby ObjectType (whole map):");
        printHist(typeHist);
        System.out.println("  objects with no instance CURRENT_AID:  " + noArtBefore);
        System.out.println("  still with no CURRENT_AID after proto: " + noArtAfter
                + "  (proto not found: " + missingProto
                + ", proto has no CURRENT_AID: " + protoNoAid + ")");

        System.out.println("\nin start sector " + startSector + ": " + inStart);
        printHist(startHist);
        System.out.println("  drawable BEFORE proto resolution (CURRENT_AID overridden"
                + " on the instance): " + startDrawableBefore);
        System.out.println("  drawable AFTER proto resolution (obj_field_fetch falls"
                + " back to the prototype):  " + startDrawableAfter);
        System.out.println("  still not drawable: " + (inStart - startDrawableAfter));

        // Do the start sector's mobiles actually have renderable art, once their
        // CURRENT_AID is resolved through the prototype? "inherited" marks the
        // ones that had no instance art id and only draw thanks to the proto.
        System.out.println("\nstart-sector sample — resolved CURRENT_AID -> art path:");
        int shown = 0;
        int missing = 0;
        int checked = 0;
        int unresolvedPath = 0;
        TreeMap<Integer, Integer> missingByArtType = new TreeMap<>();
        for (GameObject o : r.objects) {
            if (Location.sectorIdFromLoc(o.location(protos)) != startSector) {
                continue;
            }
            int aid = o.currentAid(protos);
            if (aid == 0) {
                continue;
            }
            checked++;
            String artPath = TigArt.buildPath(aid);
            boolean exists = artPath != null && TigFile.exists(artPath, null);
            if (!exists) {
                missing++;
                missingByArtType.merge(aid >>> 28, 1, Integer::sum);
                if (artPath == null) {
                    unresolvedPath++;
                }
            }
            if (shown++ < 24) {
                long loc = o.location(protos);
                // OBJ_F_NAME is an INT32 name number into the description tables
                // (obj.c: object_fields[OBJ_F_NAME].type = OD_TYPE_INT32), not a
                // string — print the raw id; resolving it needs description.mes.
                System.out.printf("  %-9s tile(%2d,%2d) name#%-6s 0x%08X %-11s %-38s %s%n",
                        typeName(o.type),
                        (int) (Location.getX(loc) & 63), (int) (Location.getY(loc) & 63),
                        str(o.resolved(OBJ_F_NAME, protos)),
                        aid, o.has(ObjectFields.OBJ_F_CURRENT_AID) ? "(instance)" : "(inherited)",
                        artPath == null ? "<unresolved>" : artPath,
                        exists ? "OK" : "MISSING");
            }
        }
        System.out.println("  checked " + checked + " art id(s); "
                + (missing == 0 ? "all resolved + present ✓"
                    : missing + " MISSING (" + unresolvedPath
                      + " with no path from NameResolver, "
                      + (missing - unresolvedPath) + " path built but file absent)"));
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
