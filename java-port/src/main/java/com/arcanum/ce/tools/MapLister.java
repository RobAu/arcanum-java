package com.arcanum.ce.tools;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

import com.arcanum.ce.tig.database.DatArchive;

/**
 * Headless diagnostic: enumerate the maps inside the game's {@code .dat} archives
 * and how many {@code .sec} sector files each one ships.
 *
 * <p>Maps live at {@code maps\<name>\} with a {@code map.prp} plus per-sector
 * {@code <id>.sec} files (see {@code tile-rendering-spec.md}). This scans every
 * archive in the data dir and tallies, per map, whether it has {@code map.prp},
 * {@code startloc.txt}, and the {@code .sec} count — so we can pick a small,
 * concrete map to target for the world renderer.
 *
 * <p>Run: {@code ./gradlew runTool -Ptool=tools.MapLister -Darcanum.data=<dir>}
 */
public final class MapLister {

    private static final class MapInfo {
        boolean prp;
        boolean startloc;
        int sectors;
    }

    private MapLister() {
    }

    public static void main(String[] args) throws Exception {
        File dir = dataDir(args);
        if (dir == null) {
            System.err.println("No game data dir. Pass -Darcanum.data=<dir> "
                    + "or ARCANUM_DATA, or a path arg.");
            System.exit(1);
            return;
        }

        Map<String, MapInfo> maps = new TreeMap<>();
        Map<String, Integer> terrain = new TreeMap<>();
        Map<String, Integer> topLevel = new TreeMap<>();
        int secAnywhere = 0;
        String sampleSec = null;
        File[] dats = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".dat"));
        if (dats == null || dats.length == 0) {
            System.err.println("No .dat archives in " + dir);
            System.exit(1);
            return;
        }

        for (File dat : dats) {
            try (DatArchive archive = new DatArchive(dat)) {
                for (DatArchive.Entry e : archive.files()) {
                    // Entry paths are normalized to lower-case, '/'-separated.
                    String p = e.path;
                    int firstSlash = p.indexOf('/');
                    topLevel.merge(firstSlash < 0 ? p : p.substring(0, firstSlash),
                            1, Integer::sum);
                    if (p.endsWith(".sec")) {
                        secAnywhere++;
                        if (sampleSec == null) {
                            sampleSec = dat.getName() + ":" + p;
                        }
                    }
                    if (p.startsWith("terrain/") && p.endsWith(".sec")) {
                        String tr = p.substring("terrain/".length());
                        int ts = tr.lastIndexOf('/');
                        if (ts > 0) {
                            terrain.merge(tr.substring(0, ts), 1, Integer::sum);
                        }
                    }
                    if (!p.startsWith("maps/")) {
                        continue;
                    }
                    String rest = p.substring("maps/".length());
                    int slash = rest.indexOf('/');
                    if (slash < 0) {
                        continue;
                    }
                    String name = rest.substring(0, slash);
                    String file = rest.substring(slash + 1);
                    MapInfo info = maps.computeIfAbsent(name, k -> new MapInfo());
                    if (file.equals("map.prp")) {
                        info.prp = true;
                    } else if (file.equals("startloc.txt")) {
                        info.startloc = true;
                    } else if (file.endsWith(".sec")) {
                        info.sectors++;
                    }
                }
            }
        }

        System.out.printf("%-28s %4s %4s %8s%n", "map", "prp", "loc", "sectors");
        System.out.println("-".repeat(48));
        int totalMaps = 0;
        for (Map.Entry<String, MapInfo> m : maps.entrySet()) {
            MapInfo i = m.getValue();
            System.out.printf("%-28s %4s %4s %8d%n",
                    m.getKey(), i.prp ? "y" : "-", i.startloc ? "y" : "-", i.sectors);
            totalMaps++;
        }
        System.out.println("-".repeat(48));
        System.out.println(totalMaps + " map(s) across " + dats.length + " archive(s)");

        System.out.println();
        System.out.println("terrain\\<name> sector templates (" + terrain.size() + " dirs):");
        terrain.forEach((k, v) -> System.out.printf("  %-44s %4d .sec%n", k, v));

        if (totalMaps == 0 && terrain.isEmpty()) {
            System.out.println();
            System.out.println("No maps/ or terrain/. Top-level dirs in archives:");
            topLevel.forEach((k, v) -> System.out.printf("  %-20s %8d%n", k, v));
            System.out.println(".sec files anywhere: " + secAnywhere
                    + (sampleSec != null ? "  (e.g. " + sampleSec + ")" : ""));
        }
    }

    private static File dataDir(String[] args) {
        if (args.length > 0) {
            File f = new File(args[0]);
            if (f.isDirectory()) {
                return f;
            }
        }
        String prop = System.getProperty("arcanum.data");
        if (prop != null && new File(prop).isDirectory()) {
            return new File(prop);
        }
        String env = System.getenv("ARCANUM_DATA");
        if (env != null && new File(env).isDirectory()) {
            return new File(env);
        }
        return null;
    }
}
