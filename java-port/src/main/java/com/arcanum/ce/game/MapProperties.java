package com.arcanum.ce.game;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.arcanum.ce.tig.TigFile;

/**
 * A map's {@code map.prp} properties file, ported from {@code map_open}
 * ({@code map.c:621}) and the {@code MapProperties} struct ({@code map.c:82}).
 *
 * <p>The file is a single raw 24-byte struct read with one {@code fread}:
 *
 * <pre>
 * typedef struct MapProperties {
 *     /* 0000 *&#47; int base_terrain_type;
 *     /* 0004 *&#47; int padding_4;
 *     /* 0008 *&#47; int64_t width;
 *     /* 0010 *&#47; int64_t height;
 * } MapProperties;
 * static_assert(sizeof(MapProperties) == 0x18, "wrong size");
 * </pre>
 *
 * <p><b>width/height are in TILES, not sectors.</b> {@code map_open} converts to
 * sectors where it needs them, always by {@code >> 6}:
 *
 * <pre>
 * location_limits_set(map_properties.width, map_properties.height);        // map.c:747, tiles
 * sector_limits_set(map_properties.width &gt;&gt; 6, map_properties.height &gt;&gt; 6); // map.c:748, sectors
 * new_map_info.width = map_properties.width &gt;&gt; 6;                          // map.c:704, sectors
 * </pre>
 *
 * <p>So {@link #widthTiles} mirrors the raw field and {@link #widthSectors} is
 * the {@code >> 6} the engine applies — a tile outside {@code [0, widthTiles)} ×
 * {@code [0, heightTiles)} is off-map ({@link #containsTile}).
 */
public final class MapProperties {

    /** sizeof(MapProperties) — the exact fread size (static_assert 0x18). */
    public static final int SIZE = 0x18;

    /** {@code base_terrain_type} — the terrain a missing sector is filled from. */
    public final int baseTerrainType;

    /** {@code padding_4} — kept because it is on disk; the engine never reads it. */
    public final int padding4;

    /** {@code width} — map width in TILES (see the class javadoc). */
    public final long widthTiles;

    /** {@code height} — map height in TILES. */
    public final long heightTiles;

    private MapProperties(int baseTerrainType, int padding4, long widthTiles, long heightTiles) {
        this.baseTerrainType = baseTerrainType;
        this.padding4 = padding4;
        this.widthTiles = widthTiles;
        this.heightTiles = heightTiles;
    }

    /** Repository path of a map's properties file: {@code maps\<name>\map.prp}. */
    public static String path(String mapName) {
        return "maps\\" + mapName + "\\map.prp";
    }

    /**
     * Load {@code maps\<mapName>\map.prp}, or null if it is absent or short.
     *
     * <p>The C treats both as a hard failure ({@code map_open} returns false);
     * here it is graceful so callers can fall back to a terrain template that
     * ships no properties file.
     */
    public static MapProperties load(String mapName) {
        return parse(TigFile.readBytes(path(mapName)));
    }

    /**
     * Parse a {@code map.prp} byte image; null if it is not exactly readable as
     * the struct. The C's {@code fread(&map_properties, sizeof(map_properties),
     * 1, stream) != 1} accepts a longer file but never a shorter one.
     */
    public static MapProperties parse(byte[] bytes) {
        if (bytes == null || bytes.length < SIZE) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int baseTerrainType = b.getInt();
        int padding4 = b.getInt();
        long width = b.getLong();
        long height = b.getLong();
        return new MapProperties(baseTerrainType, padding4, width, height);
    }

    /** {@code map_properties.width >> 6} — map width in sectors (map.c:748). */
    public long widthSectors() {
        return widthTiles >> 6;
    }

    /** {@code map_properties.height >> 6} — map height in sectors. */
    public long heightSectors() {
        return heightTiles >> 6;
    }

    /**
     * Is a world tile inside the map? Mirrors {@code location_limits_set(width,
     * height)} — the limits are the tile extents, so valid x is {@code [0, width)}.
     */
    public boolean containsTile(long x, long y) {
        return x >= 0 && y >= 0 && x < widthTiles && y < heightTiles;
    }

    /**
     * Is a sector inside the map? Mirrors {@code sector_limits_set(width >> 6,
     * height >> 6)} — the limits are sector extents.
     */
    public boolean containsSector(long sx, long sy) {
        return sx >= 0 && sy >= 0 && sx < widthSectors() && sy < heightSectors();
    }

    @Override
    public String toString() {
        return "MapProperties{baseTerrainType=" + baseTerrainType
                + ", width=" + widthTiles + " tiles (" + widthSectors() + " sectors)"
                + ", height=" + heightTiles + " tiles (" + heightSectors() + " sectors)}";
    }
}
