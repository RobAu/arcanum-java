package com.arcanum.ce.tools;

import com.arcanum.ce.GameData;
import com.arcanum.ce.game.Location;
import com.arcanum.ce.tig.TigFile;
import com.arcanum.ce.tig.mes.Mes;

/**
 * Headless diagnostic: report the campaign's <b>start map</b> — the scene a new
 * game opens into (the IFS Zephyr crash site).
 *
 * <p>Ports {@code map_list_info_load} ({@code map.c}): {@code Rules\MapList.mes}
 * holds one entry per map starting at number 5000, comma-separated as
 * {@code <name>, <x>, <y>[, Type: <TYPE>][, WorldMap: n][, Area: n]}. The entry
 * flagged {@code Type: START_MAP} is where a new game begins, and its {@code x,y}
 * is the start location.
 *
 * <p>From that this prints the sector that contains the start location:
 * sector = {@code SECTOR_MAKE(x >> 6, y >> 6)}, whose file is that decimal id +
 * {@code .sec} under {@code maps\<name>\}, and the sector-local spawn tile
 * {@code (x & 63, y & 63)}.
 *
 * <p>Requires the campaign module archive ({@code modules\Arcanum.dat}) to be
 * registered — {@link GameData} does that.
 *
 * <p>Run: {@code ./gradlew runTool -Ptool=tools.StartMap -Darcanum.data=<dir>}
 */
public final class StartMap {

    private static final int FIRST_ENTRY = 5000;

    private StartMap() {
    }

    public static void main(String[] args) {
        TigFile.init();
        if (GameData.discoverAndRegister() == null) {
            System.err.println("No game data. Pass -Darcanum.data=<dir>.");
            System.exit(1);
            return;
        }

        int handle = Mes.load("Rules\\MapList.mes");
        if (handle == Mes.INVALID_HANDLE) {
            System.err.println("Rules\\MapList.mes not found");
            System.exit(1);
            return;
        }

        System.out.printf("%-30s %8s %8s  %s%n", "map", "x", "y", "type/extras");
        System.out.println("-".repeat(72));
        String startName = null;
        long startX = 0;
        long startY = 0;
        int count = 0;
        for (int num = FIRST_ENTRY; ; num++) {
            String s = Mes.find(handle, num);
            if (s == null) {
                break;
            }
            count++;
            String[] f = s.split(",");
            if (f.length < 3) {
                continue;
            }
            String name = f[0].trim();
            long x = parse(f[1]);
            long y = parse(f[2]);
            StringBuilder extra = new StringBuilder();
            boolean isStart = false;
            for (int i = 3; i < f.length; i++) {
                String e = f[i].trim();
                extra.append(e).append(' ');
                if (e.replace(" ", "").equalsIgnoreCase("Type:START_MAP")) {
                    isStart = true;
                }
            }
            if (isStart) {
                startName = name;
                startX = x;
                startY = y;
            }
            System.out.printf("%-30s %8d %8d  %s%s%n", name, x, y, extra,
                    isStart ? "   <== START_MAP" : "");
        }
        System.out.println("-".repeat(72));
        System.out.println(count + " map entries");

        if (startName == null) {
            System.out.println("\nNo Type: START_MAP entry found.");
            return;
        }
        long sec = Location.sectorMake(startX >> 6, startY >> 6);
        String secPath = "maps\\" + startName + "\\" + sec + ".sec";
        System.out.println("\nSTART MAP : " + startName);
        System.out.println("start loc : x=" + startX + " y=" + startY);
        System.out.println("sector    : " + sec + "  -> " + secPath
                + (TigFile.exists(secPath, null) ? "  [present]" : "  [MISSING]"));
        System.out.println("spawn tile: (" + (startX & 63) + ", " + (startY & 63) + ")");
        System.out.println("map.prp   : " + ("maps\\" + startName + "\\map.prp")
                + (TigFile.exists("maps\\" + startName + "\\map.prp", null)
                        ? "  [present]" : "  [MISSING]"));
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
