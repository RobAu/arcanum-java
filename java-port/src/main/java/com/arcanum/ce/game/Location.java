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

    /**
     * How far outside the visible content rect the engine still draws, per side
     * ({@code gamelib_init} / {@code gamelib_resize}, {@code gamelib.c:357}):
     *
     * <pre>
     * gamelib_iso_content_rect_ex.x = gamelib_iso_content_rect.x - 256;
     * gamelib_iso_content_rect_ex.y = gamelib_iso_content_rect.y - 256;
     * gamelib_iso_content_rect_ex.width  = gamelib_iso_content_rect.width + 512;
     * gamelib_iso_content_rect_ex.height = gamelib_iso_content_rect.height + 512;
     * </pre>
     *
     * <p>{@code gamelib_draw} feeds that expanded rect — not the content rect —
     * to {@link #screenRectToLocRect}, so the draw covers tiles whose own
     * anchor is off-screen but whose art (a tall facade, a tree, a wall)
     * reaches back into view. This is why the margin exists; it is not a guess.
     */
    public static final int ISO_CONTENT_MARGIN = 256;

    /** An inclusive world-tile bounding box — {@code LocRect} (location.h:8). */
    public static final class LocRect {
        public final long x1;
        public final long y1;
        public final long x2;
        public final long y2;

        public LocRect(long x1, long y1, long x2, long y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        public long width() {
            return x2 - x1 + 1;
        }

        public long height() {
            return y2 - y1 + 1;
        }

        /** Tiles enclosed — what a full scan of this rect would touch. */
        public long tileCount() {
            return width() * height();
        }

        public boolean contains(long x, long y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }

        @Override
        public String toString() {
            return "LocRect{x " + x1 + ".." + x2 + ", y " + y1 + ".." + y2
                    + " (" + width() + "x" + height() + " = " + tileCount() + " tiles)}";
        }
    }

    /**
     * {@code convert_screen_to_world_coords} (location.c:588), ISOMETRIC branch:
     *
     * <pre>
     * dx = (int)((sx - location_origin_x) / 2);
     * dy = (int)(sy - location_origin_y);
     * *wx = (dy - dx) / 40;
     * *wy = (dy + dx) / 40;
     * </pre>
     *
     * <p>Note this is <em>not</em> {@link #locationAt}: the C divides (truncating
     * toward zero) where {@code location_at} shifts and floor-corrects. Kept as
     * the C has it — the two agree over the positive tile space a real map uses.
     *
     * @return {@code {wx, wy}}
     */
    private static long[] screenToWorld(long sx, long sy, int originX, int originY) {
        long dx = (sx - originX) / 2;
        long dy = sy - originY;
        return new long[] {(dy - dx) / 40, (dy + dx) / 40};
    }

    /**
     * The world-tile rect covering a screen rect — {@code
     * location_screen_rect_to_loc_rect} (location.c:437). This is how the engine
     * turns "what is on screen" into "which tiles to draw"; {@code gamelib_draw}
     * calls it with {@code gamelib_iso_content_rect_ex} (see
     * {@link #ISO_CONTENT_MARGIN}) and hands the result to the tile and object
     * passes.
     *
     * <p>Each bound comes from the one corner that produces it, because the iso
     * axes run diagonally across the screen — X grows toward the bottom-left, Y
     * toward the bottom-right:
     *
     * <pre>
     * convert_screen_to_world_coords(x,       y,        &tmp, &y1);   // top-left     -> min y
     * convert_screen_to_world_coords(x+width, y,        &x1,  &tmp);  // top-right    -> min x
     * convert_screen_to_world_coords(x,       y+height, &x2,  &tmp);  // bottom-left  -> max x
     * convert_screen_to_world_coords(x+width, y+height, &tmp, &y2);   // bottom-right -> max y
     * </pre>
     *
     * <p>The C then clamps to {@code location_limit_x/y} and returns false on an
     * empty rect. Clamping is left to the caller here, which knows the map's
     * limits ({@link com.arcanum.ce.game.MapProperties}); this returns the raw
     * geometry.
     */
    public static LocRect screenRectToLocRect(int x, int y, int width, int height,
                                              int originX, int originY) {
        long y1 = screenToWorld(x, y, originX, originY)[1];
        long x1 = screenToWorld(x + width, y, originX, originY)[0];
        long x2 = screenToWorld(x, y + height, originX, originY)[0];
        long y2 = screenToWorld(x + width, y + height, originX, originY)[1];
        return new LocRect(x1, y1, x2, y2);
    }

    /**
     * The world-tile rect for a window of {@code width x height}, expanded by
     * {@link #ISO_CONTENT_MARGIN} on every side — the exact rect
     * {@code gamelib_draw} draws.
     */
    public static LocRect visibleLocRect(int width, int height, int originX, int originY) {
        int m = ISO_CONTENT_MARGIN;
        return screenRectToLocRect(-m, -m, width + 2 * m, height + 2 * m, originX, originY);
    }
}
