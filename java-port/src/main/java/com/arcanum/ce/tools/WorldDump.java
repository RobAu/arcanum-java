package com.arcanum.ce.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.arcanum.ce.GameData;
import com.arcanum.ce.game.GameObject;
import com.arcanum.ce.game.Location;
import com.arcanum.ce.game.MapList;
import com.arcanum.ce.game.MapMobiles;
import com.arcanum.ce.game.MapProperties;
import com.arcanum.ce.game.ProtoStore;
import com.arcanum.ce.game.SectorFile;
import com.arcanum.ce.game.WorldMap;
import com.arcanum.ce.tig.TigFile;

/**
 * Headless diagnostic: report a map's <em>world</em> shape — the thing that
 * proves it is bigger than the one sector the world screen used to render.
 *
 * <p>Dumps {@code map.prp} (base terrain, width/height in tiles and sectors),
 * how many {@code <id>.sec} files the map actually ships versus how many its
 * bounds allow, the start sector's eight neighbours and whether each exists,
 * and the map's mobiles bucketed per sector.
 *
 * <p>Run:
 * {@code ./gradlew runTool -Ptool=tools.WorldDump -Darcanum.data=<dir>}
 * (defaults to the campaign START_MAP; override with {@code -Darcanum.map=<name>}).
 */
public final class WorldDump {

    private WorldDump() {
    }

    public static void main(String[] args) {
        TigFile.init();
        if (GameData.discoverAndRegister() == null) {
            System.err.println("no game data (set -Darcanum.data=<dir>)");
            System.exit(1);
            return;
        }

        MapList maps = MapList.load();
        if (maps == null) {
            System.err.println("no Rules\\MapList.mes (is modules\\Arcanum.dat registered?)");
            System.exit(1);
            return;
        }
        String mapName = System.getProperty("arcanum.map",
                args.length > 0 ? args[0] : maps.startMapName);
        System.out.println("map: " + mapName
                + (mapName.equals(maps.startMapName) ? "   (START_MAP)" : ""));
        System.out.println("start location: world tile (" + maps.startX + ", "
                + maps.startY + ")");

        // --- map.prp ------------------------------------------------------
        MapProperties prp = MapProperties.load(mapName);
        System.out.println();
        System.out.println("--- map.prp (" + MapProperties.path(mapName) + ") ---");
        if (prp == null) {
            System.out.println("  ABSENT or short -- map has no properties file");
        } else {
            System.out.println("  base_terrain_type : " + prp.baseTerrainType);
            System.out.println("  padding_4         : " + prp.padding4);
            System.out.println("  width             : " + prp.widthTiles + " tiles -> "
                    + prp.widthSectors() + " sectors  (width >> 6)");
            System.out.println("  height            : " + prp.heightTiles + " tiles -> "
                    + prp.heightSectors() + " sectors  (height >> 6)");
            System.out.println("  sector rect       : " + prp.widthSectors() + " x "
                    + prp.heightSectors() + " = "
                    + (prp.widthSectors() * prp.heightSectors()) + " sectors in bounds");
            System.out.println("  start tile in bounds: "
                    + prp.containsTile(maps.startX, maps.startY));
        }

        // --- which sectors ship -------------------------------------------
        long startSec = Location.sectorMake(maps.startX >> 6, maps.startY >> 6);
        List<String> secFiles = TigFile.list("maps\\" + mapName, ".sec");
        System.out.println();
        System.out.println("--- sector files (maps\\" + mapName + "\\*.sec) ---");
        System.out.println("  files shipped     : " + secFiles.size());

        // Parse each name back to a sector id -> (sx, sy) so we can see the shape.
        long minSx = Long.MAX_VALUE;
        long maxSx = Long.MIN_VALUE;
        long minSy = Long.MAX_VALUE;
        long maxSy = Long.MIN_VALUE;
        int unparsed = 0;
        for (String f : secFiles) {
            String base = f.substring(0, f.length() - 4);
            long id;
            try {
                id = Long.parseLong(base);
            } catch (NumberFormatException e) {
                unparsed++;
                continue;
            }
            long sx = Location.sectorX(id);
            long sy = Location.sectorY(id);
            minSx = Math.min(minSx, sx);
            maxSx = Math.max(maxSx, sx);
            minSy = Math.min(minSy, sy);
            maxSy = Math.max(maxSy, sy);
        }
        if (unparsed > 0) {
            System.out.println("  (" + unparsed + " file name(s) not a decimal sector id)");
        }
        if (maxSx >= minSx) {
            System.out.println("  sector x range    : " + minSx + " .. " + maxSx
                    + "  (" + (maxSx - minSx + 1) + " wide)");
            System.out.println("  sector y range    : " + minSy + " .. " + maxSy
                    + "  (" + (maxSy - minSy + 1) + " tall)");
            System.out.println("  world tile range  : x " + (minSx << 6) + " .. "
                    + (((maxSx + 1) << 6) - 1) + ",  y " + (minSy << 6) + " .. "
                    + (((maxSy + 1) << 6) - 1));
        }
        System.out.println("  start sector      : " + startSec + ".sec  = SECTOR_MAKE("
                + Location.sectorX(startSec) + ", " + Location.sectorY(startSec) + ")"
                + "   exists=" + TigFile.exists("maps\\" + mapName + "\\" + startSec + ".sec", null));

        // --- the start sector's neighbours --------------------------------
        System.out.println();
        System.out.println("--- neighbours of the start sector ---");
        long ssx = Location.sectorX(startSec);
        long ssy = Location.sectorY(startSec);
        String[] labels = {"NW", "N ", "NE", "W ", "  ", "E ", "SW", "S ", "SE"};
        int i = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++, i++) {
                long nsx = ssx + dx;
                long nsy = ssy + dy;
                long id = Location.sectorMake(nsx, nsy);
                String path = "maps\\" + mapName + "\\" + id + ".sec";
                boolean exists = TigFile.exists(path, null);
                boolean inBounds = prp == null || prp.containsSector(nsx, nsy);
                System.out.println("  " + labels[i] + " (" + nsx + ", " + nsy + ")"
                        + "  id=" + id
                        + "  exists=" + exists
                        + "  inBounds=" + inBounds
                        + (dx == 0 && dy == 0 ? "   <-- start sector" : ""));
            }
        }

        // --- the shipped region around the start sector -------------------
        // The map's *bounds* (map.prp) are huge and mostly empty: the engine
        // fills the holes from terrain templates, which is not ported. So what
        // is actually walkable is the contiguous run of shipped .sec files --
        // print it, since that is the map the player really gets.
        int r = Integer.getInteger("arcanum.radius", 8);
        System.out.println();
        System.out.println("--- shipped sectors within " + r
                + " of the start sector ('#' = .sec exists, '.' = hole, 'S' = start) ---");
        int present = 0;
        for (long dy = -r; dy <= r; dy++) {
            StringBuilder row = new StringBuilder("  ");
            for (long dx = -r; dx <= r; dx++) {
                long id = Location.sectorMake(ssx + dx, ssy + dy);
                boolean exists = TigFile.exists("maps\\" + mapName + "\\" + id + ".sec", null);
                if (exists) {
                    present++;
                }
                row.append(dx == 0 && dy == 0 ? 'S' : exists ? '#' : '.');
            }
            System.out.println(row);
        }
        int span = 2 * r + 1;
        System.out.println("  " + present + " of " + (span * span)
                + " sectors in this " + span + "x" + span + " window ship a .sec file");

        // --- objects in the start sector ----------------------------------
        SectorFile start = SectorFile.load("maps\\" + mapName + "\\" + startSec + ".sec");
        System.out.println();
        System.out.println("--- start sector contents ---");
        System.out.println("  static objects    : "
                + (start == null ? "<sector did not load>" : start.objects.size()));

        // --- mobiles bucketed per sector ----------------------------------
        ProtoStore protos = ProtoStore.get();
        List<GameObject> all = MapMobiles.load(mapName);
        Map<Long, Integer> perSector = new TreeMap<>();
        int noLocation = 0;
        for (GameObject o : all) {
            long loc = o.location(protos);
            if (loc == 0) {
                noLocation++;          // carried inventory: no OBJ_F_LOCATION
                continue;
            }
            perSector.merge(Location.sectorIdFromLoc(loc), 1, Integer::sum);
        }
        System.out.println();
        System.out.println("--- mobiles (maps\\" + MapMobiles.obfuscateName(mapName) + ") ---");
        System.out.println("  total objects     : " + all.size());
        System.out.println("  no OBJ_F_LOCATION : " + noLocation + " (carried inventory)");
        System.out.println("  distinct sectors  : " + perSector.size());
        System.out.println("  in start sector   : " + perSector.getOrDefault(startSec, 0));
        System.out.println();
        System.out.println("  sector id        (sx, sy)      mobiles  .sec?");
        List<Map.Entry<Long, Integer>> rows = new ArrayList<>(perSector.entrySet());
        rows.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int shown = 0;
        for (Map.Entry<Long, Integer> e : rows) {
            long id = e.getKey();
            boolean exists = TigFile.exists("maps\\" + mapName + "\\" + id + ".sec", null);
            System.out.printf("  %-16d (%d, %d)%s%7d  %s%s%n",
                    id, Location.sectorX(id), Location.sectorY(id),
                    "     ", e.getValue(), exists ? "yes" : "NO",
                    id == startSec ? "   <-- start sector" : "");
            if (++shown >= 40) {
                System.out.println("  ... (" + (rows.size() - shown) + " more)");
                break;
            }
        }

        // --- the cache -----------------------------------------------------
        WorldMap world = WorldMap.openMap(mapName);
        System.out.println();
        System.out.println("--- WorldMap ---");
        System.out.println("  " + world);
        System.out.println("  tileAt(start)     : art_id "
                + world.tileAt(maps.startX, maps.startY));
        System.out.println("  inBounds(start)   : " + world.inBounds(maps.startX, maps.startY));
        long eastX = ((ssx + 1) << 6);      // first tile of the sector to the east
        System.out.println("  tileAt(east nbr " + eastX + ", " + maps.startY + "): art_id "
                + world.tileAt(eastX, maps.startY) + "   sector "
                + Location.sectorIdFromLoc(Location.make(eastX, maps.startY)));
        System.out.println("  mobiles in start sector (bucketed): "
                + world.mobilesInSector(startSec).size());
        System.out.println("  cached sectors    : " + world.cachedSectorCount());
    }
}
