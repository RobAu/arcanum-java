package com.arcanum.ce.game;

import com.arcanum.ce.tig.art.ArtId;

/**
 * Terrain-tile queries, ported from {@code tile.c}. Currently just walkability
 * ({@code tile_is_blocking}): a FACADE (cliff/mountain face) blocks unless its
 * walkable bit is set; a base terrain tile blocks when its {@code b} flag is set
 * in {@code tilename.mes} (water, etc.). Other art types don't block terrain.
 */
public final class Tile {

    private Tile() {
    }

    /** tile_is_blocking: is the tile art at a location impassable on foot? */
    public static boolean isBlocking(int aid, TileNames names) {
        if (ArtId.type(aid) == ArtId.TYPE_FACADE) {
            return !ArtId.facadeWalkable(aid);
        }
        if (names != null && ArtId.type(aid) == ArtId.TYPE_TILE) {
            return names.isBlocking(aid);
        }
        return false;
    }
}
