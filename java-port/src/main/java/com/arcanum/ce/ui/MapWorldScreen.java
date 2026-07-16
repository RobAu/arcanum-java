package com.arcanum.ce.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import com.arcanum.ce.game.Location;
import com.arcanum.ce.game.MapList;
import com.arcanum.ce.game.Player;
import com.arcanum.ce.game.SectorFile;
import com.arcanum.ce.game.Tile;
import com.arcanum.ce.game.TileNames;
import com.arcanum.ce.tig.TigArt;
import com.arcanum.ce.tig.TigFile;

/**
 * The in-game isometric world view: a real Arcanum {@code .sec} sector of
 * terrain with a player avatar you can walk around. Ports the terrain side of
 * {@code tile_draw_iso} ({@code tile.c}) plus a minimal player on top — load the
 * sector's 4096 tile {@code art_id}s ({@link SectorFile}), project each with the
 * engine's isometric transform ({@link Location}), and blit via {@link TigArt}.
 *
 * <p>On top of the terrain it draws the sector's object list ({@link SectorFile}
 * objects — scenery, walls, critters) depth-sorted with the player, each anchored
 * on its tile by the art hotspot ({@code object_get_rect}).
 *
 * <p>Controls: arrow keys / WASD walk (8 directions, camera follows); left-click
 * a tile to walk there; Escape or right-click returns to the menu. The sector
 * is set by {@code -Darcanum.sector=<repository\path.sec>} (default: a wooded
 * template full of trees, so New Game opens into a populated scene).
 */
public final class MapWorldScreen implements Screen {

    // Fallback if the campaign module isn't available: a terrain template that at
    // least ships an object list, so we open into scenery rather than bare ground.
    private static final String FALLBACK_SECTOR = "terrain\\broad leaf forest to plains\\0.sec";
    private static final int N = Location.TILES_PER_SECTOR_AXIS;   // 64
    private static final int OBJ_F_OFFSET_X = 3;   // OBJ_F_OFFSET_X (INT32)
    private static final int OBJ_F_OFFSET_Y = 4;   // OBJ_F_OFFSET_Y (INT32)
    // Tween speed: fraction of a tile per frame (~7 frames/tile ≈ 8 tiles/sec at 60fps).
    private static final double WALK_SPEED = 1.0 / 7.0;

    // Tile delta per facing direction (location_in_dir order, dir 0..7).
    private static final int[] DIR_DX = {-1, -1, -1, 0, 1, 1, 1, 0};
    private static final int[] DIR_DY = {-1, 0, 1, 1, 1, 0, -1, -1};

    private String sectorPath;       // resolved in create() when not overridden
    private SectorFile sector;
    // The map's mobile objects (NPCs/critters/ground items) that stand in the
    // rendered sector. Empty unless we opened the campaign start map.
    private java.util.List<com.arcanum.ce.game.GameObject> mobiles =
            java.util.Collections.emptyList();
    private TileNames tileNames;      // for walkability; null → nothing blocks
    private Player player;
    private int originX;
    private int originY;
    private int targetX = -1;        // click-to-move target tile, -1 = none
    private int targetY = -1;

    public MapWorldScreen() {
        this(System.getProperty("arcanum.sector"));   // null → campaign start map
    }

    public MapWorldScreen(String sectorPath) {
        this.sectorPath = sectorPath;
    }

    @Override
    public void create() {
        tileNames = TileNames.load();
        int spawnX = N / 2;
        int spawnY = N / 2;

        // A new game opens on the campaign's START_MAP at its start location —
        // the IFS Zephyr crash site (map_by_type(MAP_TYPE_START_MAP), map.c).
        // Needs the module archive; fall back to a template if it isn't there.
        if (sectorPath == null) {
            MapList maps = MapList.load();
            if (maps != null && TigFile.exists(maps.startSectorPath(), null)) {
                sectorPath = maps.startSectorPath();
                spawnX = maps.spawnTileX();
                spawnY = maps.spawnTileY();
                // The map's mobiles (Virgil & co.) live in one file covering the
                // whole map (map_load_mobile); keep only those standing in the
                // sector we render. The terrain-template fallback has no such
                // file, so this is start-map only.
                mobiles = loadMobiles(maps);
            } else {
                sectorPath = FALLBACK_SECTOR;
            }
        }
        sector = SectorFile.load(sectorPath);

        String spawn = System.getProperty("arcanum.spawn");   // "x,y" (debug/verify)
        if (spawn != null && spawn.matches("\\d+,\\d+")) {
            String[] xy = spawn.split(",");
            spawnX = Integer.parseInt(xy[0]);
            spawnY = Integer.parseInt(xy[1]);
        }
        player = new Player(spawnX, spawnY);
    }

    /**
     * The start map's mobile objects that stand in the sector we render. The
     * mobile file spans the whole map, so filter by sector id (an object belongs
     * here iff its location's sector is the rendered one). Objects with no
     * OBJ_F_LOCATION -- carried inventory -- have location 0 and fall out
     * naturally, since sector 0 is not the start sector.
     */
    private static java.util.List<com.arcanum.ce.game.GameObject> loadMobiles(MapList maps) {
        long sectorId = Location.sectorMake(maps.startX >> 6, maps.startY >> 6);
        java.util.List<com.arcanum.ce.game.GameObject> here = new java.util.ArrayList<>();
        for (com.arcanum.ce.game.GameObject o
                : com.arcanum.ce.game.MapMobiles.load(maps.startMapName)) {
            if (Location.sectorIdFromLoc(o.location()) == sectorId) {
                here.add(o);
            }
        }
        return here;
    }

    @Override
    public void render(SpriteBatch batch, BitmapFont font, int width, int height) {
        if (sector == null) {
            font.draw(batch, "Sector not found: " + sectorPath
                    + "   (Esc to go back)", 16, height - 16);
            handleBack();
            return;
        }

        update();

        // Camera follows the player, tracking the smooth walk tween between tiles:
        // interpolate the player's base screen position and keep it window-centred.
        double t = player.walkT();
        long fromLoc = player.prevLoc();
        long toLoc = player.loc();
        int pbx = (int) Math.round(lerp(Location.screenX(fromLoc, 0), Location.screenX(toLoc, 0), t));
        int pby = (int) Math.round(lerp(Location.screenY(fromLoc, 0), Location.screenY(toLoc, 0), t));
        originX = width / 2 - pbx;
        originY = height / 2 - pby;

        // Terrain, back-to-front (increasing X+Y) so taller tiles overlap right.
        for (int d = 0; d <= 2 * (N - 1); d++) {
            int xStart = Math.max(0, d - (N - 1));
            int xEnd = Math.min(N - 1, d);
            for (int x = xStart; x <= xEnd; x++) {
                int y = d - x;
                long loc = Location.make(x, y);
                TigArt.draw(batch, sector.tileAt(x, y),
                        Location.screenX(loc, originX),
                        Location.screenY(loc, originY), height);
            }
        }

        // Objects (scenery / walls / critters) and the player share one
        // back-to-front pass, sorted by tile depth so nearer sprites overlap
        // farther ones and the player is correctly occluded. Each is anchored on
        // its tile by the art frame's hotspot (object_get_rect).
        int frame = 0;
        if (player.isMoving()) {
            int frames = TigArt.frameCount(player.artId(0));
            frame = Math.min(frames - 1, (int) (t * frames));
        }

        java.util.List<Sprite> sprites = new java.util.ArrayList<>(
                sector.objects.size() + mobiles.size() + 1);
        addObjectSprites(sprites, sector.objects);   // static scenery / walls
        addObjectSprites(sprites, mobiles);          // NPCs / critters / ground items
        // The player draws after any object sharing its tile (order = 1).
        sprites.add(new Sprite(player.x() + player.y(), player.x(), 1,
                player.artId(frame), pbx + originX, pby + originY, 0, 0));
        sprites.sort(SPRITE_ORDER);
        for (Sprite s : sprites) {
            drawSprite(batch, s.artId, s.baseX, s.baseY, s.offX, s.offY, height);
        }

        font.setColor(Color.WHITE);
        font.draw(batch, sectorPath + "   tile (" + player.x() + ", " + player.y()
                + ")   [WASD/arrows or click: move, Esc: menu]", 12, height - 12);
    }

    private void update() {
        handleBack();
        if (Gdx.input == null) {
            return;
        }

        // Drive the walk tween; the next step can't begin until it completes.
        player.advanceWalk(WALK_SPEED);

        // A left-click (in the world) sets a walk-to target.
        if (Gdx.input.justTouched()
                && !Gdx.input.isButtonPressed(Input.Buttons.RIGHT)) {
            long t = Location.locationAt(Gdx.input.getX(), Gdx.input.getY(),
                    originX, originY);
            int tx = (int) Location.getX(t);
            int ty = (int) Location.getY(t);
            if (tx >= 0 && tx < N && ty >= 0 && ty < N) {
                targetX = tx;
                targetY = ty;
            }
        }

        if (player.isMoving()) {
            return;
        }

        // Keyboard intent overrides any click target.
        int dir = keyboardDir();
        if (dir >= 0) {
            targetX = -1;
            targetY = -1;
        } else if (targetX >= 0) {
            dir = dirToward(targetX, targetY);
            if (dir < 0) {                 // arrived
                targetX = -1;
                targetY = -1;
            }
        }

        if (dir < 0) {
            player.setAnim(Player.ANIM_STAND);
            return;
        }
        // A blocked click target is unreachable head-on: give up so we don't
        // spin in place pushing against the wall.
        if (!step(dir) && targetX >= 0) {
            targetX = -1;
            targetY = -1;
        }
    }

    /**
     * Try to move one tile in {@code dir}; always face that way. Returns false
     * (and stays put) if the target tile is off-sector or impassable terrain.
     */
    private boolean step(int dir) {
        player.setRotation(dir);
        int nx = player.x() + DIR_DX[dir];
        int ny = player.y() + DIR_DY[dir];
        if (nx >= 0 && nx < N && ny >= 0 && ny < N
                && !Tile.isBlocking(sector.tileAt(nx, ny), tileNames)) {
            player.setAnim(Player.ANIM_WALK);
            player.setTile(nx, ny);
            return true;
        }
        player.setAnim(Player.ANIM_STAND);
        return false;
    }

    /** Facing direction from held movement keys, or -1 if none. */
    private int keyboardDir() {
        int sx = 0;
        int sy = 0;       // screen-space intent (top-left origin)
        if (down(Input.Keys.W, Input.Keys.UP)) {
            sy -= 1;
        }
        if (down(Input.Keys.S, Input.Keys.DOWN)) {
            sy += 1;
        }
        if (down(Input.Keys.A, Input.Keys.LEFT)) {
            sx -= 1;
        }
        if (down(Input.Keys.D, Input.Keys.RIGHT)) {
            sx += 1;
        }
        if (sx == 0 && sy == 0) {
            return -1;
        }
        // Screen compass (clockwise from up) maps directly to dir 0..7.
        switch (sy * 3 + sx) {       // unique key per (sx,sy) in {-1,0,1}
            case -3: return 0;       // ( 0,-1) up
            case -2: return 1;       // ( 1,-1) up-right
            case  1: return 2;       // ( 1, 0) right
            case  4: return 3;       // ( 1, 1) down-right
            case  3: return 4;       // ( 0, 1) down
            case  2: return 5;       // (-1, 1) down-left
            case -1: return 6;       // (-1, 0) left
            case -4: return 7;       // (-1,-1) up-left
            default: return -1;
        }
    }

    /** Greedy 8-dir step toward (tx, ty), or -1 if already there. */
    private int dirToward(int tx, int ty) {
        int dx = Integer.signum(tx - player.x());
        int dy = Integer.signum(ty - player.y());
        if (dx == 0 && dy == 0) {
            return -1;
        }
        for (int d = 0; d < 8; d++) {
            if (DIR_DX[d] == dx && DIR_DY[d] == dy) {
                return d;
            }
        }
        return -1;
    }

    private static boolean down(int k1, int k2) {
        return Gdx.input.isKeyPressed(k1) || Gdx.input.isKeyPressed(k2);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * Queue each object as a depth-sorted sprite anchored on its tile. Used for
     * both the sector's static object list and the map's mobiles -- they render
     * identically, they only differ in where they were read from.
     */
    private void addObjectSprites(java.util.List<Sprite> sprites,
                                  java.util.List<com.arcanum.ce.game.GameObject> objects) {
        for (com.arcanum.ce.game.GameObject o : objects) {
            int aid = o.currentAid();
            if (aid == 0) {
                continue;                       // no drawable art (mobiles without a set AID)
            }
            // OBJ_F_LOCATION holds a full world location; the sector-local tile
            // (0..63) is its low 6 bits per axis (cf. Location.tileIndexInSector).
            long oloc = o.location();
            int ox = (int) (Location.getX(oloc) & (N - 1));
            int oy = (int) (Location.getY(oloc) & (N - 1));
            long tl = Location.make(ox, oy);
            sprites.add(new Sprite(ox + oy, ox, 0, aid,
                    Location.screenX(tl, originX), Location.screenY(tl, originY),
                    objInt(o, OBJ_F_OFFSET_X), objInt(o, OBJ_F_OFFSET_Y)));
        }
    }

    /** Read an INT32 object field by ordinal, or 0 if absent. */
    private static int objInt(com.arcanum.ce.game.GameObject o, int ordinal) {
        Object v = o.field(ordinal);
        return v instanceof Integer ? (Integer) v : 0;
    }

    private final int[] hot = new int[2];

    /** Anchor an art on its tile: screen = tileScreen + offset + (40,20) − hotspot. */
    private void drawSprite(SpriteBatch batch, int artId, float baseX, float baseY,
                            int offX, int offY, int height) {
        TigArt.frameHotspot(artId, hot);
        TigArt.draw(batch, artId, baseX + offX + 40 - hot[0],
                baseY + offY + 20 - hot[1], height);
    }

    /** One depth-sortable sprite (an object or the player) queued for drawing. */
    private static final class Sprite {
        final int depth;    // tile x+y (primary, back-to-front)
        final int tieX;     // tile x   (secondary, matches terrain scan order)
        final int order;    // 0 = object, 1 = player (drawn last on a shared tile)
        final int artId;
        final float baseX;
        final float baseY;
        final int offX;
        final int offY;

        Sprite(int depth, int tieX, int order, int artId,
               float baseX, float baseY, int offX, int offY) {
            this.depth = depth;
            this.tieX = tieX;
            this.order = order;
            this.artId = artId;
            this.baseX = baseX;
            this.baseY = baseY;
            this.offX = offX;
            this.offY = offY;
        }
    }

    private static final java.util.Comparator<Sprite> SPRITE_ORDER =
            java.util.Comparator.comparingInt((Sprite s) -> s.depth)
                    .thenComparingInt(s -> s.tieX)
                    .thenComparingInt(s -> s.order);

    private void handleBack() {
        if (Gdx.input != null
                && (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)
                    || Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT))) {
            ScreenManager.pop();
        }
    }
}
