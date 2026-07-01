package com.arcanum.ce.game;

import com.arcanum.ce.tig.art.ArtId;
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
 * <p>Each entry string carries a tile name plus flags/sound. The name is the
 * text before a {@code '/'} (or the first three characters when there is no
 * slash); after the slash, flag characters ({@code s/b/f/i/n/p}) set the
 * per-terrain flags used for e.g. walkability ({@link #isBlocking}).
 */
public final class TileNames {

    // Terrain flag bits (a_name.c). Only BLOCK is consumed so far.
    private static final int TF_BLOCK = 0x01;
    private static final int TF_FLYABLE = 0x04;

    private final String[] outdoorFlippable;
    private final String[] outdoorNonFlippable;
    private final String[] indoorFlippable;
    private final String[] indoorNonFlippable;
    private final int[] outdoorFlippableFlags;
    private final int[] outdoorNonFlippableFlags;
    private final int[] indoorFlippableFlags;
    private final int[] indoorNonFlippableFlags;

    private TileNames(String[] of, String[] onf, String[] inf, String[] innf,
                      int[] off, int[] onff, int[] iff, int[] inff) {
        this.outdoorFlippable = of;
        this.outdoorNonFlippable = onf;
        this.indoorFlippable = inf;
        this.indoorNonFlippable = innf;
        this.outdoorFlippableFlags = off;
        this.outdoorNonFlippableFlags = onff;
        this.indoorFlippableFlags = iff;
        this.indoorNonFlippableFlags = inff;
    }

    /** Build directly from the four name tables (tests / non-.mes callers). */
    public static TileNames of(String[] outdoorFlippable, String[] outdoorNonFlippable,
                               String[] indoorFlippable, String[] indoorNonFlippable) {
        return new TileNames(outdoorFlippable, outdoorNonFlippable,
                indoorFlippable, indoorNonFlippable,
                new int[outdoorFlippable.length], new int[outdoorNonFlippable.length],
                new int[indoorFlippable.length], new int[indoorNonFlippable.length]);
    }

    /** a_name_tile_init: loads {@code tilename.mes}; null if unavailable. */
    public static TileNames load() {
        int mes = Mes.load("art\\tile\\tilename.mes");
        if (mes == Mes.INVALID_HANDLE) {
            return null;
        }
        Table of = buildTable(mes, 0, 99);
        Table onf = buildTable(mes, 100, 199);
        Table inf = buildTable(mes, 200, 299);
        Table innf = buildTable(mes, 300, 399);
        return new TileNames(of.names, onf.names, inf.names, innf.names,
                of.flags, onf.flags, inf.flags, innf.flags);
    }

    private static final class Table {
        final String[] names;
        final int[] flags;

        Table(String[] names, int[] flags) {
            this.names = names;
            this.flags = flags;
        }
    }

    private static Table buildTable(int mes, int lo, int hi) {
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.List<Integer> flags = new java.util.ArrayList<>();
        for (int num = lo; num <= hi; num++) {
            String s = Mes.find(mes, num);
            if (s != null) {
                names.add(parseName(s));
                flags.add(parseFlags(s));
            }
        }
        int[] fl = new int[flags.size()];
        for (int i = 0; i < fl.length; i++) {
            fl[i] = flags.get(i);
        }
        return new Table(names.toArray(new String[0]), fl);
    }

    /** Name = text before '/', else the first three characters (load_tile_names). */
    private static String parseName(String entry) {
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            return entry.substring(0, slash);
        }
        return entry.length() >= 3 ? entry.substring(0, 3) : entry;
    }

    /** Flag chars after '/' (until a space): s/b/f/i/n/p (load_tile_names). */
    static int parseFlags(String entry) {
        int slash = entry.indexOf('/');
        if (slash < 0) {
            return 0;
        }
        int flags = 0;
        for (int i = slash + 1; i < entry.length() && entry.charAt(i) != ' '; i++) {
            switch (entry.charAt(i)) {
                case 'b': flags |= TF_BLOCK; break;
                case 'f': flags |= TF_BLOCK | TF_FLYABLE; break;
                default: break;   // s/i/n/p (sinkable/slippery/natural/soundproof)
            }
        }
        return flags;
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

    /**
     * a_name_tile_id_flags: the terrain flag byte for a TILE art id, selected by
     * its {@code num1}/{@code type}/{@code flippable1} (the base terrain).
     */
    public int terrainFlags(int aid) {
        int num = ArtId.tileNum1(aid);
        int[] table;
        if (ArtId.tileFlippable1(aid) != 0) {
            table = ArtId.tileType(aid) != 0 ? outdoorFlippableFlags : indoorFlippableFlags;
        } else {
            table = ArtId.tileType(aid) != 0 ? outdoorNonFlippableFlags : indoorNonFlippableFlags;
        }
        return num >= 0 && num < table.length ? table[num] : 0;
    }

    /** a_name_tile_is_blocking: the base terrain carries the BLOCK flag. */
    public boolean isBlocking(int aid) {
        return (terrainFlags(aid) & TF_BLOCK) != 0;
    }
}
