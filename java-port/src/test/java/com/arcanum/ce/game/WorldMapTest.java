package com.arcanum.ce.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.arcanum.ce.GameData;
import com.arcanum.ce.tig.TigFile;

/**
 * Verifies the multi-sector world model: {@link Location}'s world/sector/tile
 * math, {@link MapProperties} ({@code map.prp}), and {@link WorldMap}'s
 * on-demand sector cache.
 *
 * <p>The coordinate math is pure logic and always runs. The rest needs the real
 * campaign data (the user's install, not the repo) and is skipped without it,
 * like {@code MapMobilesTest} / {@code ProtoStoreTest}.
 */
class WorldMapTest {

    private static final String INSTALL = System.getProperty("arcanum.data",
            System.getProperty("user.home")
            + "/.local/share/Steam/steamapps/common/Arcanum/Arcanum");

    private static final String START_MAP = "Arcanum1-024-fixed";

    /** MapList START_MAP location for the retail campaign (see MapList). */
    private static final long START_X = 92958;
    private static final long START_Y = 82592;

    /** The start location's sector: SECTOR_MAKE(92958 >> 6, 82592 >> 6). */
    private static final long START_SECTOR = 86570436012L;
    private static final long START_SX = 1452;
    private static final long START_SY = 1290;

    private static boolean dataAvailable() {
        return Files.isDirectory(Paths.get(INSTALL, "modules"));
    }

    @BeforeAll
    static void registerRepositories() {
        if (!dataAvailable()) {
            return;
        }
        // TigFile's repository stack is process-global; start from a clean one so
        // this doesn't depend on (or leak into) other tests.
        TigFile.exit();
        TigFile.init();
        System.setProperty("arcanum.data", INSTALL);
        GameData.discoverAndRegister();
        ProtoStore.reset();
    }

    @AfterAll
    static void clearRepositories() {
        TigFile.exit();
        ProtoStore.reset();
    }

    // -- coordinate math (pure logic, no install needed) ----------------------

    /**
     * The round trip that the whole multi-sector world rests on: a world tile
     * decomposes into (sector, local tile) and back. Sector is {@code x >> 6},
     * local tile is {@code x & 63} per axis.
     */
    @Test
    void worldTileRoundTripsThroughSectorAndLocalTile() {
        long loc = Location.make(START_X, START_Y);
        assertEquals(START_X, Location.getX(loc));
        assertEquals(START_Y, Location.getY(loc));

        assertEquals(START_SECTOR, Location.sectorIdFromLoc(loc));
        assertEquals(START_SX, Location.sectorX(START_SECTOR));
        assertEquals(START_SY, Location.sectorY(START_SECTOR));
        assertEquals(START_SECTOR, Location.sectorMake(START_X >> 6, START_Y >> 6));

        // Local tile: (92958 & 63, 82592 & 63) = (30, 32) -- the spawn tile.
        assertEquals(30, START_X & 63);
        assertEquals(32, START_Y & 63);
        assertEquals(30 | (32 << 6), Location.tileIndexInSector(loc));

        // Sector origin + local tile reconstructs the world tile.
        long origin = Location.sectorOriginLoc(START_SECTOR);
        assertEquals(START_X, Location.getX(origin) + (START_X & 63));
        assertEquals(START_Y, Location.getY(origin) + (START_Y & 63));
    }

    /**
     * The case the single-sector renderer got wrong: a tile one step past the
     * sector's east edge belongs to the NEXT sector at local x 0 -- it is not
     * "x = 63 clamped", and it is not the same sector's tile 0 either. Masking
     * a world location to {@code & 63} (as the old object draw did) folds these
     * onto the same patch, which is exactly the bug.
     */
    @Test
    void neighbouringTileResolvesToTheNeighbouringSector() {
        // Last tile of the start sector on the x axis, then one step east.
        long lastX = (START_SX << 6) + 63;              // 92991
        long firstXNext = lastX + 1;                    // 92992

        assertEquals(START_SECTOR, Location.sectorIdFromLoc(Location.make(lastX, START_Y)));
        assertEquals(63, lastX & 63);

        long east = Location.sectorIdFromLoc(Location.make(firstXNext, START_Y));
        assertEquals(Location.sectorMake(START_SX + 1, START_SY), east);
        assertNotSame(START_SECTOR, east);
        assertEquals(START_SX + 1, Location.sectorX(east));
        assertEquals(START_SY, Location.sectorY(east));
        assertEquals(0, firstXNext & 63);               // local x wraps to 0

        // ...and the same one step south, on the y axis.
        long lastY = (START_SY << 6) + 63;              // 82623
        long south = Location.sectorIdFromLoc(Location.make(START_X, lastY + 1));
        assertEquals(Location.sectorMake(START_SX, START_SY + 1), south);
        assertEquals(0, (lastY + 1) & 63);

        // The diagonal corner sector, where four sectors meet.
        long southEast = Location.sectorIdFromLoc(Location.make(firstXNext, lastY + 1));
        assertEquals(Location.sectorMake(START_SX + 1, START_SY + 1), southEast);

        // Distinct tiles in distinct sectors must not collide -- but their
        // sector-LOCAL indexes do, which is why local indexing cannot address
        // a world.
        assertEquals(Location.tileIndexInSector(Location.make(firstXNext, START_Y)),
                Location.tileIndexInSector(Location.make(START_SX << 6, START_Y)));
    }

    /** SECTOR_MAKE/X/Y round trip across the boundary values it packs at (sec = x | y << 26). */
    @Test
    void sectorMakeRoundTrips() {
        long[][] cases = {{0, 0}, {1, 0}, {0, 1}, {START_SX, START_SY},
                          {1999, 1999}, {0x3FFFFFFL, 0}};
        for (long[] c : cases) {
            long sec = Location.sectorMake(c[0], c[1]);
            assertEquals(c[0], Location.sectorX(sec), "sector x of " + c[0] + "," + c[1]);
            assertEquals(c[1], Location.sectorY(sec), "sector y of " + c[0] + "," + c[1]);
        }
    }

    /**
     * The camera origin that centres {@code (x, y)} in a {@code w x h} window —
     * exactly what {@code MapWorldScreen.render} computes. Tests must use a
     * realistic one: at origin (0,0) the visible tiles run negative, and a
     * negative tile is not a location at all — {@code LOCATION_MAKE} packs x into
     * the low 32 bits, so {@code location_x} reads -10 back as 4294967286, and
     * the C's {@code location_at} refuses to build such a location
     * ({@code if (new_x < 0) return false;}, location.c:169).
     *
     * @return {@code {originX, originY}}
     */
    private static int[] cameraOrigin(long x, long y, int w, int h) {
        long loc = Location.make(x, y);
        return new int[] {w / 2 - Location.screenX(loc, 0), h / 2 - Location.screenY(loc, 0)};
    }

    /**
     * {@code location_screen_rect_to_loc_rect}: each bound comes from the corner
     * that produces it, because the iso axes run diagonally. X grows toward the
     * bottom-left of the screen, Y toward the bottom-right. The decisive property
     * for culling: every tile the screen shows is inside the rect.
     */
    @Test
    void visibleRectCoversTheScreenAndNothingMore() {
        int w = 800;
        int h = 600;
        int[] o = cameraOrigin(START_X, START_Y, w, h);
        Location.LocRect r = Location.screenRectToLocRect(0, 0, w, h, o[0], o[1]);

        assertTrue(r.x1 <= r.x2, "x1 must not exceed x2: " + r);
        assertTrue(r.y1 <= r.y2, "y1 must not exceed y2: " + r);

        // The camera is centred on the player, so the player's tile is in view.
        assertTrue(r.contains(START_X, START_Y), START_X + "," + START_Y + " in " + r);

        // Every screen corner's tile must be inside the rect -- that is the
        // property that makes it safe to cull against.
        int[][] corners = {{0, 0}, {w - 1, 0}, {0, h - 1}, {w - 1, h - 1}, {w / 2, h / 2}};
        for (int[] c : corners) {
            long loc = Location.locationAt(c[0], c[1], o[0], o[1]);
            assertTrue(r.contains(Location.getX(loc), Location.getY(loc)),
                    "screen corner (" + c[0] + "," + c[1] + ") -> tile ("
                    + Location.getX(loc) + "," + Location.getY(loc)
                    + ") must be inside " + r);
        }

        // Sweep the whole screen: no visible tile may fall outside the rect.
        for (int sy = 0; sy < h; sy += 7) {
            for (int sx = 0; sx < w; sx += 7) {
                long loc = Location.locationAt(sx, sy, o[0], o[1]);
                assertTrue(r.contains(Location.getX(loc), Location.getY(loc)),
                        "pixel (" + sx + "," + sy + ") -> tile ("
                        + Location.getX(loc) + "," + Location.getY(loc)
                        + ") must be inside " + r);
            }
        }

        // The margin expands the rect on every side (gamelib_iso_content_rect_ex).
        Location.LocRect m = Location.visibleLocRect(w, h, o[0], o[1]);
        assertTrue(m.x1 < r.x1 && m.y1 < r.y1 && m.x2 > r.x2 && m.y2 > r.y2,
                "the 256px margin must widen the rect: " + m + " vs " + r);
    }

    /**
     * The rect the world screen actually culls against, at the real start
     * location: it must span more than one sector's worth of the map -- that is
     * why a single 64x64 grid could never address it -- yet stay far below the
     * 4096 tiles the old code drew blindly.
     */
    @Test
    void visibleRectIsSmallerThanASectorButSpansSectorBoundaries() {
        int[] o = cameraOrigin(START_X, START_Y, 800, 600);
        Location.LocRect r = Location.visibleLocRect(800, 600, o[0], o[1]);

        assertTrue(r.tileCount() < SectorFile.TILE_COUNT,
                "culling must draw fewer tiles than a whole sector's 4096, got " + r);
        assertTrue(r.contains(START_X, START_Y));

        // Standing on the sector's east edge, the rect reaches into the next
        // sector -- the case the single-sector renderer could not draw.
        long edgeX = (START_SX << 6) + 63;
        int[] eo = cameraOrigin(edgeX, START_Y, 800, 600);
        Location.LocRect er = Location.visibleLocRect(800, 600, eo[0], eo[1]);
        assertTrue(er.x2 > edgeX, "the view must reach past the sector edge: " + er);
        assertEquals(Location.sectorMake(START_SX + 1, START_SY),
                Location.sectorIdFromLoc(Location.make(er.x2, START_Y)),
                "the rect's east edge must land in the neighbouring sector");
    }

    /** The visible rect must track the camera: scrolling the origin shifts it. */
    @Test
    void visibleRectFollowsTheOrigin() {
        int[] o = cameraOrigin(START_X, START_Y, 800, 600);
        Location.LocRect a = Location.visibleLocRect(800, 600, o[0], o[1]);
        // Move the origin one tile's worth down-right in screen space; both world
        // axes advance (screenY = oy + 20*(y+x), so -40px on the origin = +1 on x and y).
        Location.LocRect b = Location.visibleLocRect(800, 600, o[0], o[1] - 40);
        assertEquals(a.x1 + 1, b.x1);
        assertEquals(a.y1 + 1, b.y1);
        assertEquals(a.tileCount(), b.tileCount());
    }

    // -- map.prp (needs the install) ------------------------------------------

    /**
     * {@code map.prp} is a raw 24-byte struct: {@code int base_terrain_type; int
     * padding_4; int64_t width; int64_t height;} -- and width/height are in
     * TILES. {@code map_open} converts with {@code >> 6} wherever it wants
     * sectors (map.c:704, map.c:748).
     */
    @Test
    void parsesTheStartMapProperties() {
        Assumptions.assumeTrue(dataAvailable(), "campaign data not available: " + INSTALL);

        MapProperties prp = MapProperties.load(START_MAP);
        assertNotNull(prp, "start map must ship " + MapProperties.path(START_MAP));

        // The retail start map: a 128000x128000 tile world = 2000x2000 sectors.
        assertEquals(128000, prp.widthTiles);
        assertEquals(128000, prp.heightTiles);
        assertEquals(2000, prp.widthSectors());
        assertEquals(2000, prp.heightSectors());
        assertEquals(prp.widthTiles >> 6, prp.widthSectors());
        assertEquals(prp.heightTiles >> 6, prp.heightSectors());
        assertEquals(2, prp.baseTerrainType);

        // The start location is inside its own map, in both tile and sector space.
        assertTrue(prp.containsTile(START_X, START_Y));
        assertTrue(prp.containsSector(START_SX, START_SY));

        // ...and the bounds actually bound.
        assertFalse(prp.containsTile(-1, START_Y));
        assertFalse(prp.containsTile(START_X, prp.heightTiles));
        assertFalse(prp.containsSector(prp.widthSectors(), START_SY));
    }

    /** A short or absent map.prp is graceful, not an exception. */
    @Test
    void missingOrShortPropertiesAreNull() {
        assertNull(MapProperties.parse(null));
        assertNull(MapProperties.parse(new byte[0]));
        assertNull(MapProperties.parse(new byte[MapProperties.SIZE - 1]));
        assertNotNull(MapProperties.parse(new byte[MapProperties.SIZE]));
    }

    // -- the sector cache (needs the install) ---------------------------------

    /** The cache must hand back the sector that actually owns a world coord. */
    @Test
    void cacheResolvesWorldCoordsToTheRightSector() {
        Assumptions.assumeTrue(dataAvailable(), "campaign data not available: " + INSTALL);

        WorldMap world = WorldMap.openMap(START_MAP);

        // The start tile resolves to the start sector's file.
        assertEquals("maps\\" + START_MAP + "\\" + START_SECTOR + ".sec",
                world.sectorPath(START_SECTOR));
        SectorFile start = world.sectorAt(START_X, START_Y);
        assertNotNull(start, "start sector must load");
        assertSame(start, world.sector(START_SECTOR),
                "sectorAt(world) and sector(id) must agree on the start sector");

        // The known-good count from SecDump: the start sector's object list.
        assertEquals(317, start.objects.size());

        // A tile in the sector EAST of the start is a different sector object,
        // and its terrain comes from that sector's own local tile 0.
        long eastX = (START_SX + 1) << 6;
        SectorFile east = world.sectorAt(eastX, START_Y);
        assertNotNull(east, "the sector east of the start must load");
        assertNotSame(start, east, "the east neighbour must be a different sector");
        assertEquals(east.tileAt(0, (int) (START_Y & 63)), world.tileAt(eastX, START_Y));
        assertEquals(start.tileAt(63, (int) (START_Y & 63)), world.tileAt(eastX - 1, START_Y));

        // Repeated lookups are cached, not re-read.
        assertSame(start, world.sectorAt(START_X, START_Y));
        assertSame(east, world.sectorAt(eastX, START_Y));
        assertEquals(2, world.sectorLoads(), "each sector must be read exactly once");
    }

    /**
     * The wall must be gone: the tile across the sector boundary has to be
     * walkable terrain from the NEIGHBOURING sector, which is what the world
     * screen's step logic consults instead of clamping at local 63.
     */
    @Test
    void walkingCrossesTheSectorBoundary() {
        Assumptions.assumeTrue(dataAvailable(), "campaign data not available: " + INSTALL);

        WorldMap world = WorldMap.openMap(START_MAP);
        TileNames names = TileNames.load();

        long lastX = (START_SX << 6) + 63;              // 92991, last tile of start sector
        long firstXNext = lastX + 1;                    // 92992, first of the east sector

        // Both sides of the boundary are real, in-bounds ground.
        assertTrue(world.inBounds(lastX, START_Y));
        assertTrue(world.inBounds(firstXNext, START_Y));
        assertNotNull(world.sectorAt(firstXNext, START_Y),
                "the east neighbour ships a .sec, so stepping east must resolve terrain");
        assertNotEqualsTile(world.tileAt(firstXNext, START_Y));

        // The step the old code could not take: onto local x 0 of the next sector.
        assertTrue(world.isWalkable(firstXNext, START_Y, names),
                "the first tile of the east neighbour must be walkable");
        assertEquals(Location.sectorMake(START_SX + 1, START_SY),
                Location.sectorIdFromLoc(Location.make(firstXNext, START_Y)));
    }

    /** A missing sector is a hole: no tile, not walkable -- never fabricated ground. */
    @Test
    void missingSectorsReadAsHolesAndBlockWalking() {
        Assumptions.assumeTrue(dataAvailable(), "campaign data not available: " + INSTALL);

        WorldMap world = WorldMap.openMap(START_MAP);
        TileNames names = TileNames.load();

        // In bounds (the map is nominally 2000x2000 sectors) but the map ships
        // only 621 .sec files, so a far corner is a hole the engine would fill
        // from a terrain template. We render nothing there rather than guess.
        long holeX = 100 * 64;
        long holeY = 100 * 64;
        assertTrue(world.inBounds(holeX, holeY), "the hole must be inside the map bounds");
        assertNull(world.sectorAt(holeX, holeY), "sector (100,100) must not ship");
        assertEquals(WorldMap.NO_TILE, world.tileAt(holeX, holeY));
        assertFalse(world.isWalkable(holeX, holeY, names),
                "a hole must not be walkable -- there is no ground there");
        assertTrue(world.objectsInSector(Location.sectorMake(100, 100)).isEmpty());

        // Off-map is likewise blocked (location_limits / sector_limits).
        assertFalse(world.inBounds(-1, START_Y));
        assertFalse(world.isWalkable(-1, START_Y, names));
        assertFalse(world.inBounds(128000, START_Y));
        assertFalse(world.isWalkable(128000, START_Y, names));

        // The absence is cached: a hole costs one lookup, not a repository probe
        // per frame.
        int misses = world.sectorMisses();
        world.tileAt(holeX, holeY);
        world.tileAt(holeX, holeY);
        assertEquals(misses, world.sectorMisses(), "a known-missing sector must not re-probe");
    }

    /** The cache is bounded and evicts least-recently-used, as sector.c's is. */
    @Test
    void cacheIsBoundedAndEvictsLeastRecentlyUsed() {
        Assumptions.assumeTrue(dataAvailable(), "campaign data not available: " + INSTALL);

        WorldMap world = WorldMap.openMap(START_MAP, 4);
        // Walk a row of sectors through a 4-entry cache.
        for (long dx = 0; dx < 6; dx++) {
            world.sector(Location.sectorMake(START_SX + dx, START_SY));
        }
        assertEquals(4, world.cachedSectorCount(), "cache must not exceed its bound");

        // The start sector was evicted; re-reading it is a fresh load.
        int loads = world.sectorLoads();
        assertNotNull(world.sector(START_SECTOR));
        assertTrue(world.sectorLoads() > loads, "an evicted sector must be re-read");
    }

    /**
     * The map's mobiles are bucketed by sector once at open, not rescanned:
     * 7399 objects spread over the map, 58 of them in the start sector.
     */
    @Test
    void mobilesAreBucketedPerSector() {
        Assumptions.assumeTrue(dataAvailable(), "campaign data not available: " + INSTALL);

        WorldMap world = WorldMap.openMap(START_MAP);
        assertEquals(7399, world.mobileCount(),
                "every mobile with a location must be bucketed");
        assertEquals(58, world.mobilesInSector(START_SECTOR).size(),
                "the start sector's mobiles (the known-good count)");
        assertTrue(world.mobileSectorCount() > 1,
                "the map's mobiles must span more than one sector, got "
                + world.mobileSectorCount());

        // Every bucketed object really does stand in the sector it is filed under.
        ProtoStore protos = ProtoStore.get();
        for (GameObject o : world.mobilesInSector(START_SECTOR)) {
            assertEquals(START_SECTOR, Location.sectorIdFromLoc(o.location(protos)));
        }
        // A sector with no mobiles is empty, not null.
        assertTrue(world.mobilesInSector(Location.sectorMake(100, 100)).isEmpty());
    }

    /** The start map really is bigger than one sector -- the point of all this. */
    @Test
    void theStartMapShipsManySectors() {
        Assumptions.assumeTrue(dataAvailable(), "campaign data not available: " + INSTALL);

        WorldMap world = WorldMap.openMap(START_MAP);
        int files = world.sectorFileCount();
        assertTrue(files > 100,
                "the start map must ship many sectors, got " + files);

        // All eight neighbours of the start sector exist, so the player can walk
        // off the start sector in any direction.
        for (long dy = -1; dy <= 1; dy++) {
            for (long dx = -1; dx <= 1; dx++) {
                long id = Location.sectorMake(START_SX + dx, START_SY + dy);
                assertNotNull(world.sector(id),
                        "neighbour (" + (START_SX + dx) + ", " + (START_SY + dy)
                        + ") must ship a .sec");
            }
        }
    }

    private static void assertNotEqualsTile(int aid) {
        assertTrue(aid != WorldMap.NO_TILE, "expected real terrain, got NO_TILE");
    }
}
