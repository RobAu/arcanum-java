package com.arcanum.ce.game;

import com.arcanum.ce.tig.mes.Mes;

/**
 * The campaign's map list, ported from {@code map_list_info_load} ({@code map.c}).
 *
 * <p>{@code Rules\MapList.mes} holds one entry per map from number 5000 up,
 * comma-separated as {@code <name>, <x>, <y>[, Type: <TYPE>][, WorldMap: n]
 * [, Area: n]}. The entry tagged {@code Type: START_MAP} is where a new game
 * begins (for the retail campaign: the IFS Zephyr crash site), and its
 * {@code x, y} is the start location in world tile coordinates.
 *
 * <p>This only surfaces the start map — enough to open a new game in the right
 * place. The list lives in the campaign module archive ({@code modules\Arcanum.dat}),
 * so {@link com.arcanum.ce.GameData} must have registered it.
 */
public final class MapList {

    private static final int FIRST_ENTRY = 5000;
    private static final String START_TYPE = "Type:START_MAP";

    /** Name of the {@code Type: START_MAP} map, or null if the list had none. */
    public final String startMapName;

    /** Start location in world tile coordinates. */
    public final long startX;
    public final long startY;

    private MapList(String startMapName, long startX, long startY) {
        this.startMapName = startMapName;
        this.startX = startX;
        this.startY = startY;
    }

    /** Load the list and pick out the start map; null if unavailable. */
    public static MapList load() {
        int handle = Mes.load("Rules\\MapList.mes");
        if (handle == Mes.INVALID_HANDLE) {
            return null;
        }
        try {
            for (int num = FIRST_ENTRY; ; num++) {
                String s = Mes.find(handle, num);
                if (s == null) {
                    break;
                }
                String[] f = s.split(",");
                if (f.length < 3) {
                    continue;
                }
                for (int i = 3; i < f.length; i++) {
                    if (f[i].replace(" ", "").equalsIgnoreCase(START_TYPE)) {
                        return new MapList(f[0].trim(), parse(f[1]), parse(f[2]));
                    }
                }
            }
        } finally {
            Mes.unload(handle);
        }
        return null;
    }

    /**
     * Repository path of the sector containing the start location:
     * {@code maps\<name>\<SECTOR_MAKE(x>>6, y>>6)>.sec}.
     */
    public String startSectorPath() {
        long sector = Location.sectorMake(startX >> 6, startY >> 6);
        return "maps\\" + startMapName + "\\" + sector + ".sec";
    }

    /** Sector-local spawn tile (0..63) for the start location. */
    public int spawnTileX() {
        return (int) (startX & 63);
    }

    public int spawnTileY() {
        return (int) (startY & 63);
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
