package com.arcanum.ce.game;

import com.arcanum.ce.tig.mes.Mes;

/**
 * The terrain-tile name tables, ported from {@code a_name.c}
 * ({@code count_tile_names} / {@code load_tile_names} / {@code sub_4EB0C0}).
 *
 * <p>Loaded from {@code art\tile\tilename.mes}, whose entry numbers select four
 * tables (see {@code tile-rendering-spec.md}):
 * <pre>
 *   0..99   outdoor flippable      200..299  indoor flippable
 *   100..199 outdoor non-flippable 300..399  indoor non-flippable
 * </pre>
 * Within each range the present entries are appended in number order, so a tile
 * id's {@code num} field indexes the k-th present entry (gaps are skipped).
 *
 * <p>Each entry string carries a tile name plus flags/sound; we only need the
 * name: the text before a {@code '/'}, or the first three characters when there
 * is no slash.
 */
public final class TileNames {

    private final String[] outdoorFlippable;
    private final String[] outdoorNonFlippable;
    private final String[] indoorFlippable;
    private final String[] indoorNonFlippable;

    private TileNames(String[] of, String[] onf, String[] inf, String[] innf) {
        this.outdoorFlippable = of;
        this.outdoorNonFlippable = onf;
        this.indoorFlippable = inf;
        this.indoorNonFlippable = innf;
    }

    /** Build directly from the four name tables (tests / non-.mes callers). */
    public static TileNames of(String[] outdoorFlippable, String[] outdoorNonFlippable,
                               String[] indoorFlippable, String[] indoorNonFlippable) {
        return new TileNames(outdoorFlippable, outdoorNonFlippable,
                indoorFlippable, indoorNonFlippable);
    }

    /** a_name_tile_init: loads {@code tilename.mes}; null if unavailable. */
    public static TileNames load() {
        int mes = Mes.load("art\\tile\\tilename.mes");
        if (mes == Mes.INVALID_HANDLE) {
            return null;
        }
        return new TileNames(
                buildTable(mes, 0, 99),
                buildTable(mes, 100, 199),
                buildTable(mes, 200, 299),
                buildTable(mes, 300, 399));
    }

    private static String[] buildTable(int mes, int lo, int hi) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int num = lo; num <= hi; num++) {
            String s = Mes.find(mes, num);
            if (s != null) {
                names.add(parseName(s));
            }
        }
        return names.toArray(new String[0]);
    }

    /** Name = text before '/', else the first three characters (load_tile_names). */
    private static String parseName(String entry) {
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            return entry.substring(0, slash);
        }
        return entry.length() >= 3 ? entry.substring(0, 3) : entry;
    }

    /**
     * sub_4EB0C0: the tile name for {@code (num, type, flippable)}, or null when
     * out of range. {@code type}: 0 = indoor, 1 = outdoor.
     */
    public String nameOf(int num, int type, int flippable) {
        String[] table;
        if (flippable != 0) {
            table = type != 0 ? outdoorFlippable : indoorFlippable;
        } else {
            table = type != 0 ? outdoorNonFlippable : indoorNonFlippable;
        }
        return num >= 0 && num < table.length ? table[num] : null;
    }

    /**
     * sub_4EB7D0: {@code name}'s index in the concatenated outdoor name tables
     * (flippable first, then non-flippable), or -1 if it is not an outdoor name.
     * This is the "is this a blendable edge name?" test {@code build_tile_file_name}
     * uses to order the two terrains in a seam filename. Despite the engine also
     * having a {@code load_tile_edges} adjacency table, that table is used only by
     * map generation — filename resolution needs only this lookup.
     */
    public int edgeIndex(String name) {
        for (int i = 0; i < outdoorFlippable.length; i++) {
            if (outdoorFlippable[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        for (int i = 0; i < outdoorNonFlippable.length; i++) {
            if (outdoorNonFlippable[i].equalsIgnoreCase(name)) {
                return outdoorFlippable.length + i;
            }
        }
        return -1;
    }
}
