package com.arcanum.ce.game;

/**
 * Location / sector / tile coordinate math, ported from {@code location.c},
 * {@code sector.c}, and {@code tile.c}. See {@code tile-rendering-spec.md}.
 *
 * <p>A <em>location</em> packs a tile's world (x, y) into a 64-bit value; a
 * <em>sector</em> is a 64×64 block of tiles. The isometric projection maps a
 * location to screen pixels given a scroll origin (tiles are 80×40 diamonds).
 */
public final class Location {

    public static final int TILES_PER_SECTOR_AXIS = 64;
    public static final int TILE_WIDTH = 80;
    public static final int TILE_HEIGHT = 40;

    private Location() {
    }

    // LOCATION_MAKE / GET (location.h): loc = x | (y << 32).
    public static long make(long x, long y) {
        return (x & 0xFFFFFFFFL) | (y << 32);
    }

    public static long getX(long loc) {
        return loc & 0xFFFFFFFFL;
    }

    public static long getY(long loc) {
        return (loc >> 32) & 0xFFFFFFFFL;
    }

    // SECTOR_MAKE / X / Y (sector.h): sec = x | (y << 26).
    public static long sectorMake(long x, long y) {
        return (x & 0x3FFFFFFL) | (y << 26);
    }

    public static long sectorX(long sec) {
        return sec & 0x3FFFFFFL;
    }

    public static long sectorY(long sec) {
        return (sec >> 26) & 0x3FFFFFFL;
    }

    /** sector_id_from_loc: the sector containing a location (>> 6 per axis). */
    public static long sectorIdFromLoc(long loc) {
        return sectorMake(getX(loc) >> 6, getY(loc) >> 6);
    }

    /** location of a sector's (0,0) corner tile: sector_loc_from_id, << 6. */
    public static long sectorOriginLoc(long sec) {
        return make(sectorX(sec) << 6, sectorY(sec) << 6);
    }

    /** tile_id_from_loc → index into SectorTileList.art_ids[4096]. */
    public static int tileIndexInSector(long loc) {
        int tx = (int) (getX(loc) & 0x3F);
        int ty = (int) (getY(loc) & 0x3F);
        return tx | (ty << 6);          // TILE_MAKE
    }

    /** Screen X for a location (location_xy, ISOMETRIC): ox + 40*(Y - X - 1). */
    public static int screenX(long loc, int originX) {
        return originX + 40 * (int) (getY(loc) - getX(loc) - 1);
    }

    /** Screen Y for a location (location_xy, ISOMETRIC): oy + 20*(Y + X). */
    public static int screenY(long loc, int originY) {
        return originY + 20 * (int) (getY(loc) + getX(loc));
    }

    /**
     * Inverse of {@link #screenX}/{@link #screenY}: the tile location under a
     * screen pixel (location_at, ISOMETRIC), with floor division matching the C.
     */
    public static long locationAt(int sx, int sy, int originX, int originY) {
        int dy = sy - originY;
        int dx = (sx - originX) >> 1;
        int x = Math.floorDiv(dy - dx, 40);
        int y = Math.floorDiv(dy + dx, 40);
        return make(x, y);
    }
}
