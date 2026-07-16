package com.arcanum.ce.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.arcanum.ce.tig.TigDebug;
import com.arcanum.ce.tig.TigFile;

/**
 * A whole map's world of terrain and objects: sectors loaded on demand and kept
 * in a bounded cache, addressed in <em>world</em> tile coordinates. This is the
 * port-shaped equivalent of the engine's sector cache ({@code sector_lock} /
 * {@code sector_cache_entries}, {@code sector.c:860}) plus the map bounds that
 * {@code map_open} installs.
 *
 * <h2>Addressing</h2>
 * A world tile {@code (x, y)} lives in sector {@code (x >> 6, y >> 6)} at
 * sector-local tile {@code (x & 63, y & 63)} — see {@link Location#sectorIdFromLoc}
 * and {@link Location#tileIndexInSector}. A sector's file is the <em>decimal
 * {@code SECTOR_MAKE(sx, sy)} id</em> plus {@code .sec}, inside a base directory
 * — the engine's {@code sector_base_path} ({@code sector.c:839}):
 *
 * <pre>
 * snprintf(path, sizeof(path), "%s\\%" PRIu64 ".sec", sector_base_path, id);
 * </pre>
 *
 * <p>That base directory is {@code maps\<name>} for a campaign map
 * ({@link #openMap}) and {@code terrain\<name>} for a terrain template
 * ({@link #openSectorDir}) — {@code terrain_sector_path} builds the latter with
 * the same {@code <id>.sec} naming, so one model serves both.
 *
 * <h2>Bounds</h2>
 * {@code map_open} (map.c:747) sets both limits from {@code map.prp}:
 * {@code location_limits_set(width, height)} in tiles and
 * {@code sector_limits_set(width >> 6, height >> 6)} in sectors. Outside them is
 * off-map — {@link #inBounds}. With no {@code map.prp} the map is treated as
 * unbounded, matching the engine's defaults ({@code location.c:74} /
 * {@code sector.c:226} start at the maximum limits).
 *
 * <h2>Missing sectors</h2>
 * The engine falls back to a terrain <em>template</em> sector
 * ({@code terrain_sector_path}) and then to {@code terrain_fill}. Neither is
 * ported — see {@link #tileAt} — so a missing sector reads as {@link #NO_TILE}
 * (drawn as nothing, and {@link #isWalkable} false) rather than as fabricated
 * ground. This matters: the retail start map's bounds are 2000×2000 sectors but
 * it ships only 621 {@code .sec} files, so most of the nominal map is holes that
 * the engine would fill from templates.
 *
 * <h2>Mobiles</h2>
 * A map's mobiles live in one file spanning every sector ({@link MapMobiles}),
 * so they are read once at {@link #openMap} and bucketed by sector id up front —
 * {@link #mobilesInSector} is then a map lookup, not a scan of all 7399.
 */
public final class WorldMap {

    /** Tile art id reported for a tile with no sector behind it (see {@link #tileAt}). */
    public static final int NO_TILE = 0;

    /**
     * How many sectors to hold. Each is ~16-96 KB of tiles plus its object list;
     * the visible range at any sane window size touches at most a handful, so
     * this leaves generous slack for walking around without thrashing. The C
     * sector cache is likewise bounded ({@code sector_cache_init}, minimum 8).
     */
    public static final int DEFAULT_CACHE_SIZE = 16;

    /** The engine's {@code sector_base_path}: the directory holding {@code <id>.sec}. */
    private final String sectorBasePath;
    private final String name;
    private final MapProperties properties;          // null when there is no map.prp
    private final Map<Long, List<GameObject>> mobilesBySector;

    /**
     * sector id -> sector, least-recently-used first. A miss is cached as an
     * empty {@link java.util.Optional} so a hole in the map is not re-read from
     * the repository on every single frame.
     */
    private final LinkedHashMap<Long, java.util.Optional<SectorFile>> cache;

    private int loads;                               // sectors read from the repository
    private int misses;                              // sector ids with no .sec file

    private WorldMap(String sectorBasePath, String name, MapProperties properties,
                     Map<Long, List<GameObject>> mobilesBySector, int cacheSize) {
        this.sectorBasePath = sectorBasePath;
        this.name = name;
        this.properties = properties;
        this.mobilesBySector = mobilesBySector;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(
                    Map.Entry<Long, java.util.Optional<SectorFile>> eldest) {
                return size() > cacheSize;
            }
        };
    }

    /** Open a campaign map under {@code maps\<mapName>}: properties + mobiles. */
    public static WorldMap openMap(String mapName) {
        return openMap(mapName, DEFAULT_CACHE_SIZE);
    }

    /** Open a campaign map with an explicit cache bound (for tests / tools). */
    public static WorldMap openMap(String mapName, int cacheSize) {
        MapProperties prp = MapProperties.load(mapName);
        if (prp == null) {
            TigDebug.println("WorldMap: no " + MapProperties.path(mapName)
                    + " -- treating " + mapName + " as unbounded");
        }
        return new WorldMap("maps\\" + mapName, mapName, prp,
                bucketMobiles(mapName), cacheSize);
    }

    /**
     * Open a bare directory of {@code <id>.sec} files — a terrain template such
     * as {@code terrain\broad leaf forest to plains}. No {@code map.prp} and no
     * mobile file, so: unbounded, terrain and static objects only.
     */
    public static WorldMap openSectorDir(String dir) {
        return new WorldMap(dir, dir, null, Collections.emptyMap(), DEFAULT_CACHE_SIZE);
    }

    /**
     * Read the map's mobiles once and bucket them by the sector they stand in.
     * Objects with no {@code OBJ_F_LOCATION} — carried inventory — resolve to
     * location 0; they belong to no sector and are dropped here rather than
     * being filed under sector 0.
     */
    private static Map<Long, List<GameObject>> bucketMobiles(String mapName) {
        List<GameObject> all = MapMobiles.load(mapName);
        if (all.isEmpty()) {
            return Collections.emptyMap();
        }
        ProtoStore protos = ProtoStore.get();
        Map<Long, List<GameObject>> byId = new HashMap<>();
        for (GameObject o : all) {
            long loc = o.location(protos);
            if (loc == 0) {
                continue;
            }
            byId.computeIfAbsent(Location.sectorIdFromLoc(loc), k -> new ArrayList<>()).add(o);
        }
        return byId;
    }

    /** The map name, or the sector directory in {@link #openSectorDir} mode. */
    public String name() {
        return name;
    }

    /** The engine's {@code sector_base_path} for this world. */
    public String sectorBasePath() {
        return sectorBasePath;
    }

    /** The map's {@code map.prp}, or null if it has none. */
    public MapProperties properties() {
        return properties;
    }

    /** Repository path of a sector: {@code <base>\<id>.sec} (sector.c:839). */
    public String sectorPath(long sectorId) {
        return sectorBasePath + "\\" + sectorId + ".sec";
    }

    /**
     * Is a world tile inside the map? Mirrors the tile-space
     * {@code location_limits_set(width, height)} of {@code map_open}. A world
     * with no {@code map.prp} is unbounded except for the non-negative quadrant
     * (a location's x/y are unsigned 32-bit halves; negatives are not tiles).
     */
    public boolean inBounds(long x, long y) {
        if (properties == null) {
            return x >= 0 && y >= 0;
        }
        return properties.containsTile(x, y);
    }

    /** Is a sector inside {@code sector_limits} ({@code width >> 6})? */
    public boolean sectorInBounds(long sectorId) {
        if (properties == null) {
            return true;
        }
        return properties.containsSector(Location.sectorX(sectorId),
                Location.sectorY(sectorId));
    }

    /**
     * The sector containing a world tile, loading it if needed; null when the
     * tile is off-map or the map ships no such {@code .sec}.
     */
    public SectorFile sectorAt(long x, long y) {
        if (!inBounds(x, y)) {
            return null;
        }
        return sector(Location.sectorMake(x >> 6, y >> 6));
    }

    /**
     * A sector by id, from the cache or read on demand. Null if the map has no
     * such file — <em>no</em> terrain-template / {@code terrain_fill} fallback
     * is applied (see the class javadoc). The absence is cached, so a hole costs
     * one lookup per frame, not one repository probe.
     */
    public SectorFile sector(long sectorId) {
        java.util.Optional<SectorFile> hit = cache.get(sectorId);
        if (hit != null) {
            return hit.orElse(null);
        }
        SectorFile sec = SectorFile.load(sectorPath(sectorId));
        if (sec == null) {
            misses++;
            TigDebug.println("WorldMap: no sector " + sectorPath(sectorId)
                    + " (sx=" + Location.sectorX(sectorId)
                    + ", sy=" + Location.sectorY(sectorId) + ") -- rendering it empty");
        } else {
            loads++;
        }
        cache.put(sectorId, java.util.Optional.ofNullable(sec));
        return sec;
    }

    /**
     * The terrain art id at a <em>world</em> tile, or {@link #NO_TILE} when the
     * tile is off-map or its sector is missing.
     *
     * <p>The engine never returns "no tile": {@code sector_load_game}
     * ({@code sector.c:1464}) substitutes a terrain-template sector
     * ({@code terrain_sector_path}) and, failing that, {@code terrain_fill}s the
     * sector. Neither is ported — {@code terrain_sector_path} needs terrain.c's
     * template lookup ({@code sub_4E87F0} / {@code sub_4E8DC0} and the terrain
     * heightmap) and {@code terrain_fill} needs the tile-blend id math of
     * {@code sub_4D7480} ({@code tile.c:232}). Rather than invent terrain, a hole
     * renders as nothing and is not walkable ({@link #isWalkable}), which fences
     * the player into the sectors the map actually ships.
     *
     * <p>(Note {@code terrain_fill} does <em>not</em> derive its tile from
     * {@code map.prp}'s {@code base_terrain_type}: it hardcodes
     * {@code tig_art_tile_id_create(7, 7, 15, 0, 0, 0, 0, 0)}, terrain.c:421.
     * {@code base_terrain_type} feeds {@code terrain_map_new} instead.)
     */
    public int tileAt(long x, long y) {
        SectorFile sec = sectorAt(x, y);
        return sec == null ? NO_TILE : sec.tileAt((int) (x & 63), (int) (y & 63));
    }

    /**
     * Can the player stand on this world tile? Off-map, a missing sector, and
     * blocking terrain ({@code tile_is_blocking}) all say no.
     */
    public boolean isWalkable(long x, long y, TileNames names) {
        SectorFile sec = sectorAt(x, y);
        if (sec == null) {
            return false;
        }
        return !Tile.isBlocking(sec.tileAt((int) (x & 63), (int) (y & 63)), names);
    }

    /** The static objects of a sector (its {@code .sec} object list); never null. */
    public List<GameObject> objectsInSector(long sectorId) {
        SectorFile sec = sector(sectorId);
        return sec == null ? Collections.emptyList() : sec.objects;
    }

    /** The map's mobiles standing in a sector; never null. Pre-bucketed at open. */
    public List<GameObject> mobilesInSector(long sectorId) {
        return mobilesBySector.getOrDefault(sectorId, Collections.emptyList());
    }

    /** How many sectors the map's mobiles occupy — a lower bound on its size. */
    public int mobileSectorCount() {
        return mobilesBySector.size();
    }

    /** Total mobiles bucketed (those with a location). */
    public int mobileCount() {
        int n = 0;
        for (List<GameObject> l : mobilesBySector.values()) {
            n += l.size();
        }
        return n;
    }

    /** Sectors currently held (loaded + known-missing), for diagnostics. */
    public int cachedSectorCount() {
        return cache.size();
    }

    /** Sectors successfully read from the repository so far. */
    public int sectorLoads() {
        return loads;
    }

    /** Sector ids that turned out to have no {@code .sec} file. */
    public int sectorMisses() {
        return misses;
    }

    /** How many {@code <id>.sec} files this world ships (a directory listing). */
    public int sectorFileCount() {
        return TigFile.list(sectorBasePath, ".sec").size();
    }

    @Override
    public String toString() {
        return "WorldMap{" + name
                + ", " + (properties == null ? "unbounded (no map.prp)"
                        : properties.widthSectors() + "x" + properties.heightSectors()
                          + " sectors / " + properties.widthTiles + "x"
                          + properties.heightTiles + " tiles")
                + ", mobiles=" + mobileCount() + " in " + mobileSectorCount() + " sectors"
                + ", cached=" + cache.size() + " (loads=" + loads + ", misses=" + misses + ")}";
    }
}
