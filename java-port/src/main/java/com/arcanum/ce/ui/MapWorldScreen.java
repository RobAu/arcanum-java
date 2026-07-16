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
import com.arcanum.ce.game.TileNames;
import com.arcanum.ce.game.WorldMap;
import com.arcanum.ce.tig.TigArt;
import com.arcanum.ce.tig.TigFile;

/**
 * The in-game isometric world view: a real Arcanum map of terrain, spanning as
 * many {@code .sec} sectors as fit on screen, with a player avatar you can walk
 * around. Ports the terrain side of {@code tile_draw_iso} ({@code tile.c}) plus
 * a minimal player on top — project each tile with the engine's isometric
 * transform ({@link Location}) and blit via {@link TigArt}.
 *
 * <h2>The world is multi-sector</h2>
 * Everything here works in <em>world</em> tile coordinates, as
 * {@code OBJ_F_LOCATION} does — not the sector-local 0..63 the first draft used.
 * Sectors are loaded on demand and cached by {@link WorldMap}; the player spawns
 * at the campaign's real start location (world tile 92958, 82592 for the retail
 * start map) and can walk across sector boundaries, because every terrain,
 * collision, object and picking query goes through {@link WorldMap} in world
 * coordinates rather than indexing one 64×64 grid.
 *
 * <h2>What gets drawn</h2>
 * Not everything — that is the point. {@code gamelib_draw} ({@code gamelib.c:855})
 * derives the visible tile rect from the screen rect with
 * {@code location_screen_rect_to_loc_rect}, expanded by 256px per side
 * ({@code gamelib_iso_content_rect_ex}) so tall art anchored just off-screen
 * still draws. {@link Location#visibleLocRect} is that derivation; this screen
 * scans only the tiles it returns, clamped to the map's bounds, back-to-front by
 * {@code x + y}. Objects come from the sectors that rect overlaps.
 *
 * <p>On top of the terrain it draws each visible sector's object list
 * ({@link SectorFile} objects — scenery, walls, critters) plus the map's mobiles
 * ({@link WorldMap#mobilesInSector}), depth-sorted with the player, each anchored
 * on its tile by the art hotspot ({@code object_get_rect}).
 *
 * <p>Clicking an object identifies it: {@link #pick} finds what is under the
 * cursor and {@link #identify} resolves its name and dialog script, shown as
 * on-screen text. {@link #pick} approximates {@code target_pick_at_screen_xy}
 * rather than porting it; see its javadoc for exactly what is and is not
 * reproduced.
 *
 * <p>Controls: arrow keys / WASD walk (8 directions, camera follows); left-click
 * empty ground to walk there, or hold the left button to keep walking toward the
 * cursor; left-click an object to identify it instead of walking; hover any
 * object to see its name. Escape or right-click returns to the menu.
 *
 * <p>Dev hooks: {@code -Darcanum.sector=<repository\path.sec>} opens that
 * sector's directory as the world instead of the campaign map (for terrain-
 * template art checks); {@code -Darcanum.spawn=x,y} overrides the spawn tile.
 * Spawn is in <em>world</em> tiles — the same coordinates {@code MapList} and
 * {@code OBJ_F_LOCATION} use, so {@code -Darcanum.spawn=92990,82592} is a real
 * place. Values below one sector (both < 64) are read as sector-local offsets
 * from the default spawn's sector instead, which keeps the old single-sector
 * invocations meaningful; see {@link #resolveSpawn}.
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

    /** The map being explored — sectors on demand, world-tile addressing. */
    private WorldMap world;
    /** What the HUD calls this world (map name, or the pinned sector's path). */
    private String worldLabel;
    private TileNames tileNames;      // for walkability; null → nothing blocks
    // The object prototypes. Most objects don't store their own art id and
    // inherit it from their prototype (obj_field_fetch), so without these the
    // majority of the scene has nothing to draw.
    private com.arcanum.ce.game.ProtoStore protos;
    // Name + script tables for click-to-identify (description.c / oname.c /
    // script_name.c). Null if the data isn't there; identify then says so.
    private com.arcanum.ce.game.ObjectName names;
    private com.arcanum.ce.game.ScriptName scriptNames;
    private Player player;
    private int originX;
    private int originY;
    private long targetX = -1;       // click-to-move target world tile, -1 = none
    private long targetY = -1;
    /** The tile rect drawn last frame, for the HUD's culling readout. */
    private Location.LocRect visible;
    /** Terrain tiles actually drawn last frame (after culling + bounds). */
    private int drawnTiles;
    /** Last frame's draw list — what the user can actually see, so what they click. */
    private java.util.List<Sprite> drawnSprites = java.util.Collections.emptyList();
    /** The identify readout for the last picked object, top line first. */
    private final java.util.List<String> pickedLines = new java.util.ArrayList<>();
    /** True while the left button that went down on an object is still held. */
    private boolean clickedObject;
    /** The conversation overlay; active only while talking to someone. */
    private final DialogUi dialogUi = new DialogUi();
    /** Who we are talking to, so the bubble can float above them. */
    private com.arcanum.ce.game.GameObject speaker;

    /** dialog.c: conversations enter at line 1 unless a script says otherwise. */
    private static final int DIALOG_ENTRY_LINE = 1;
    /** -Darcanum.pick=x,y — force one pick at a screen point (headless/dev). */
    private int[] forcedPick;

    /** ObjectType names (obj.h), for the identify readout. */
    private static final String[] TYPE_NAMES = {
        "WALL", "PORTAL", "CONTAINER", "SCENERY", "PROJECTILE", "WEAPON", "AMMO",
        "ARMOR", "GOLD", "FOOD", "SCROLL", "KEY", "KEY_RING", "WRITTEN", "GENERIC",
        "PC", "NPC", "TRAP",
    };

    /** The {@code -Darcanum.sector} override, or null for the campaign start map. */
    private final String pinnedSectorPath;

    public MapWorldScreen() {
        this(System.getProperty("arcanum.sector"));   // null → campaign start map
    }

    public MapWorldScreen(String sectorPath) {
        this.pinnedSectorPath = sectorPath;
    }

    @Override
    public void create() {
        tileNames = TileNames.load();
        protos = com.arcanum.ce.game.ProtoStore.get();
        // The identify chain: OBJ_F_DESCRIPTION -> description.mes for the display
        // name, OBJ_F_SCRIPTS_IDX[SAP_DIALOG] -> num -> dlg\*.dlg for the dialog.
        names = com.arcanum.ce.game.ObjectName.get(protos);
        scriptNames = com.arcanum.ce.game.ScriptName.get();

        long spawnX;
        long spawnY;

        if (pinnedSectorPath != null) {
            // Dev override: treat the named sector's directory as the world, so
            // even a terrain template is explorable across its own sectors.
            openSectorDir(pinnedSectorPath);
            long id = sectorIdOf(pinnedSectorPath);
            spawnX = (Location.sectorX(id) << 6) + N / 2;
            spawnY = (Location.sectorY(id) << 6) + N / 2;
        } else {
            // A new game opens on the campaign's START_MAP at its start location —
            // the IFS Zephyr crash site (map_by_type(MAP_TYPE_START_MAP), map.c).
            // Needs the module archive; fall back to a template if it isn't there.
            MapList maps = MapList.load();
            if (maps != null && TigFile.exists(maps.startSectorPath(), null)) {
                // The whole map, not just the start sector: WorldMap pulls in
                // sectors as we walk and buckets the map's mobiles (Virgil & co.,
                // one file covering every sector -- map_load_mobile) by sector.
                world = WorldMap.openMap(maps.startMapName);
                worldLabel = maps.startMapName;
                spawnX = maps.startX;              // real world tiles: 92958, 82592
                spawnY = maps.startY;
            } else {
                openSectorDir(FALLBACK_SECTOR);
                spawnX = N / 2;                    // template sector 0 = tiles 0..63
                spawnY = N / 2;
            }
        }

        String forced = System.getProperty("arcanum.pick");   // "x,y" screen (debug/verify)
        if (forced != null && forced.matches("\\d+,\\d+")) {
            String[] xy = forced.split(",");
            forcedPick = new int[] {Integer.parseInt(xy[0]), Integer.parseInt(xy[1])};
        }

        long[] spawn = resolveSpawn(System.getProperty("arcanum.spawn"), spawnX, spawnY);
        player = new Player(spawn[0], spawn[1]);
        walkScript(System.getProperty("arcanum.walk"));
    }

    /**
     * {@code -Darcanum.walk=<dir>,<steps>} — take {@code steps} steps in facing
     * {@code dir} (0..7) before the first frame, so the walk logic can be driven
     * headlessly (cf. {@code -Darcanum.pick}). Each step goes through the real
     * {@link #step}, so this exercises exactly what the keyboard does — including
     * crossing a sector boundary, which is the thing worth proving.
     */
    private void walkScript(String spec) {
        if (spec == null || !spec.matches("\\d+,\\d+")) {
            return;
        }
        String[] parts = spec.split(",");
        int dir = Integer.parseInt(parts[0]) & 7;
        int steps = Integer.parseInt(parts[1]);
        int taken = 0;
        for (int i = 0; i < steps; i++) {
            if (!step(dir)) {
                break;                          // blocked: report where we stopped
            }
            player.advanceWalk(1.0);            // settle the tween; this is not animated
            taken++;
        }
        com.arcanum.ce.tig.TigDebug.println("arcanum.walk: dir " + dir + " x" + steps
                + " -> took " + taken + " step(s), now at (" + player.x() + ", "
                + player.y() + ") sector " + player.sectorId()
                + " (" + Location.sectorX(player.sectorId()) + ", "
                + Location.sectorY(player.sectorId()) + ")");
    }

    /** Open the directory containing {@code path} as the world (see WorldMap). */
    private void openSectorDir(String path) {
        int slash = path.lastIndexOf('\\');
        world = WorldMap.openSectorDir(slash < 0 ? "." : path.substring(0, slash));
        worldLabel = path;
    }

    /** The sector id a {@code <dir>\<id>.sec} path names; 0 if it isn't one. */
    private static long sectorIdOf(String path) {
        int slash = path.lastIndexOf('\\');
        String base = path.substring(slash + 1);
        if (base.toLowerCase().endsWith(".sec")) {
            base = base.substring(0, base.length() - 4);
        }
        try {
            return Long.parseLong(base);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Read {@code -Darcanum.spawn=x,y}. The world is in world tiles now, so that
     * is what this takes — {@code -Darcanum.spawn=92990,82592} is the tile east
     * of the campaign start.
     *
     * <p>For continuity with the single-sector era, a pair that could only be a
     * sector-local tile (both under 64) is instead applied as an offset inside
     * the default spawn's sector, so an old {@code -Darcanum.spawn=32,32} still
     * means "the middle of the sector I would have opened in". Anything larger is
     * unambiguous and taken literally.
     *
     * @return {@code {x, y}} world tiles
     */
    private static long[] resolveSpawn(String spawn, long defaultX, long defaultY) {
        if (spawn == null || !spawn.matches("\\d+,\\d+")) {
            return new long[] {defaultX, defaultY};
        }
        String[] xy = spawn.split(",");
        long x = Long.parseLong(xy[0]);
        long y = Long.parseLong(xy[1]);
        if (x < N && y < N) {
            return new long[] {(defaultX & ~63L) + x, (defaultY & ~63L) + y};
        }
        return new long[] {x, y};
    }

    @Override
    public void render(SpriteBatch batch, BitmapFont font, int width, int height) {
        if (world == null || world.sectorAt(player.x(), player.y()) == null) {
            font.draw(batch, "No sector at spawn (" + player.x() + ", " + player.y()
                    + ") in " + worldLabel + "   (Esc to go back)", 16, height - 16);
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

        // Which tiles can be seen. The world is far too big to scan blindly, so
        // this is the engine's own derivation: gamelib_draw (gamelib.c:855) turns
        // the screen rect -- expanded 256px per side, so tall art anchored just
        // off-screen still draws -- into a world-tile rect via
        // location_screen_rect_to_loc_rect, and draws that. Clamp it to the map,
        // as the C clamps to location_limit_x/y.
        visible = clampToMap(Location.visibleLocRect(width, height, originX, originY));

        // Terrain, back-to-front (increasing X+Y) so taller tiles overlap right.
        // Same order as before, now walking the visible rect instead of one
        // sector's 64x64: each diagonal d = x+y, x bounded by the rect.
        drawnTiles = 0;
        for (long d = visible.x1 + visible.y1; d <= visible.x2 + visible.y2; d++) {
            long xStart = Math.max(visible.x1, d - visible.y2);
            long xEnd = Math.min(visible.x2, d - visible.y1);
            for (long x = xStart; x <= xEnd; x++) {
                long y = d - x;
                int aid = world.tileAt(x, y);
                if (aid == WorldMap.NO_TILE) {
                    continue;          // hole in the map: draw nothing, never garbage
                }
                long loc = Location.make(x, y);
                TigArt.draw(batch, aid,
                        Location.screenX(loc, originX),
                        Location.screenY(loc, originY), height);
                drawnTiles++;
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

        java.util.List<Sprite> sprites = new java.util.ArrayList<>();
        // Every sector the visible rect touches contributes its static objects
        // and its share of the map's mobiles -- so scenery, walls and NPCs from
        // a neighbouring sector draw seamlessly alongside this one's.
        for (long sy = visible.y1 >> 6; sy <= visible.y2 >> 6; sy++) {
            for (long sx = visible.x1 >> 6; sx <= visible.x2 >> 6; sx++) {
                long id = Location.sectorMake(sx, sy);
                addObjectSprites(sprites, world.objectsInSector(id));  // scenery / walls
                addObjectSprites(sprites, world.mobilesInSector(id));  // NPCs / items
            }
        }
        // The player draws after any object sharing its tile (order = 1).
        sprites.add(new Sprite(player.x() + player.y(), player.x(), 1,
                player.artId(frame), pbx + originX, pby + originY, 0, 0, null));
        sprites.sort(SPRITE_ORDER);
        for (Sprite s : sprites) {
            drawSprite(batch, s.artId, s.baseX, s.baseY, s.offX, s.offY, height);
        }
        // Hand this frame's sprites to the next frame's input pass, so a click
        // hit-tests exactly the pixels the player was looking at when they clicked.
        drawnSprites = sprites;

        // Headless/dev shortcut: -Darcanum.pick=<screenX>,<screenY> runs one pick
        // at a fixed point, so the picker can be driven without a real mouse
        // (cf. -Darcanum.hover on the main menu).
        if (forcedPick != null && pickedLines.isEmpty()) {
            Sprite s = pick(forcedPick[0], forcedPick[1]);
            if (s != null) {
                identify(s.obj);
            } else {
                pickedLines.add("nothing picked at (" + forcedPick[0] + ", "
                        + forcedPick[1] + ")");
            }
        }

        // A conversation owns the screen: its bubble floats over the speaker and
        // the responses replace the identify readout.
        if (dialogUi.isActive() && speaker != null) {
            float[] anchor = speakerAnchor();
            dialogUi.render(batch, font, width, height, anchor[0], anchor[1]);
            return;
        }

        font.setColor(Color.WHITE);
        long sec = player.sectorId();
        font.draw(batch, worldLabel + "   tile (" + player.x() + ", " + player.y()
                + ")   sector " + sec + " (" + Location.sectorX(sec) + ", "
                + Location.sectorY(sec) + ")   [WASD/arrows, click or hold LMB: move,"
                + " click an object: identify/talk, Esc: menu]",
                12, height - 12);
        font.draw(batch, "drawn " + drawnTiles + " tiles of " + visible.tileCount()
                + " in view   sprites " + drawnSprites.size()
                + "   sectors cached " + world.cachedSectorCount()
                + " (loaded " + world.sectorLoads() + ", missing " + world.sectorMisses() + ")",
                12, height - 30);

        drawHover(batch, font, height);
        drawPicked(batch, font, height);
    }

    /** The name of whatever is under the cursor, drawn beside it. */
    private void drawHover(SpriteBatch batch, BitmapFont font, int height) {
        if (Gdx.input == null || names == null) {
            return;
        }
        Sprite s = pick(Gdx.input.getX(), Gdx.input.getY());
        if (s == null) {
            return;
        }
        String name = names.name(s.obj);
        if (name == null) {
            return;
        }
        font.setColor(Color.YELLOW);
        // Screen space is top-down; libGDX text draws bottom-up from its baseline.
        font.draw(batch, name, Gdx.input.getX() + 14,
                height - Gdx.input.getY() - 4);
    }

    /** The identify readout for the last clicked object. */
    private void drawPicked(SpriteBatch batch, BitmapFont font, int height) {
        if (pickedLines.isEmpty()) {
            return;
        }
        float y = 64 + (pickedLines.size() - 1) * 18;
        for (int i = 0; i < pickedLines.size(); i++) {
            font.setColor(i == 0 ? Color.YELLOW : Color.WHITE);
            font.draw(batch, pickedLines.get(i), 12, y);
            y -= 18;
        }
    }

    private void update() {
        if (Gdx.input == null) {
            handleBack();
            return;
        }

        // A conversation takes the input: no walking, no re-picking, and Esc
        // closes the conversation rather than leaving the map -- so this runs
        // *before* handleBack and swallows the frame.
        if (dialogUi.isActive()) {
            dialogUi.update();
            if (!dialogUi.isActive()) {
                speaker = null;
            }
            player.setAnim(Player.ANIM_STAND);
            targetX = -1;
            targetY = -1;
            return;
        }

        handleBack();

        // Drive the walk tween; the next step can't begin until it completes.
        player.advanceWalk(WALK_SPEED);

        // An initial left-press ON an object identifies it instead of walking --
        // the interaction wins over movement, and only on the press, so that
        // holding the button afterwards does not re-identify every frame.
        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)
                && !Gdx.input.isButtonPressed(Input.Buttons.RIGHT)) {
            Sprite hit = pick(Gdx.input.getX(), Gdx.input.getY());
            if (hit != null) {
                identify(hit.obj);
                clickedObject = true;
            } else {
                clickedObject = false;
                pickedLines.clear();
            }
        }
        if (!Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
            clickedObject = false;
        }

        // Left button sets a walk-to target, re-read every frame while it is held
        // so the player keeps following the cursor; releasing leaves the last
        // target set, so a single click still walks there and stops. A press that
        // landed on an object is an identify, not a walk, for as long as it is held.
        if (Gdx.input.isButtonPressed(Input.Buttons.LEFT)
                && !Gdx.input.isButtonPressed(Input.Buttons.RIGHT)
                && !clickedObject) {
            long t = Location.locationAt(Gdx.input.getX(), Gdx.input.getY(),
                    originX, originY);
            long tx = Location.getX(t);
            long ty = Location.getY(t);
            // Any in-bounds world tile is a legal target now -- including one in
            // a neighbouring sector.
            if (world.inBounds(tx, ty)) {
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
     * (and stays put) if the target tile is impassable.
     *
     * <p>The step is taken in world tiles and validated against the whole map, so
     * crossing x=63 into the next sector is an ordinary step: {@link
     * WorldMap#isWalkable} resolves the destination's own sector (loading it if
     * need be) rather than clamping at the sector edge. It refuses three things —
     * off-map ({@code location_limits} / {@code sector_limits}), a sector the map
     * does not ship (a hole; see {@link WorldMap#tileAt}), and blocking terrain
     * ({@code tile_is_blocking}).
     */
    private boolean step(int dir) {
        player.setRotation(dir);
        long nx = player.x() + DIR_DX[dir];
        long ny = player.y() + DIR_DY[dir];
        if (world.isWalkable(nx, ny, tileNames)) {
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
    private int dirToward(long tx, long ty) {
        int dx = Long.signum(tx - player.x());
        int dy = Long.signum(ty - player.y());
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
     * The visible rect clipped to the map's tile limits — {@code
     * location_screen_rect_to_loc_rect}'s clamp against {@code location_limit_x/y}
     * (location.c:458), which {@link Location#screenRectToLocRect} leaves to us
     * because it has no map to ask.
     */
    private Location.LocRect clampToMap(Location.LocRect r) {
        com.arcanum.ce.game.MapProperties prp = world.properties();
        long maxX = prp != null ? prp.widthTiles - 1 : Integer.MAX_VALUE;
        long maxY = prp != null ? prp.heightTiles - 1 : Integer.MAX_VALUE;
        return new Location.LocRect(
                Math.min(Math.max(r.x1, 0), maxX), Math.min(Math.max(r.y1, 0), maxY),
                Math.min(Math.max(r.x2, 0), maxX), Math.min(Math.max(r.y2, 0), maxY));
    }

    /**
     * Queue each object as a depth-sorted sprite anchored on its tile. Used for
     * both a sector's static object list and the map's mobiles -- they render
     * identically, they only differ in where they were read from.
     *
     * <p>Objects outside the visible rect are skipped: a sector is 64x64 but only
     * part of it may be on screen, and the rect already carries the engine's
     * 256px margin for art that overhangs into view.
     */
    private void addObjectSprites(java.util.List<Sprite> sprites,
                                  java.util.List<com.arcanum.ce.game.GameObject> objects) {
        for (com.arcanum.ce.game.GameObject o : objects) {
            // Art, location and offsets all go through the prototype fallback
            // (obj_field_fetch): most objects override none of them on the
            // instance and inherit them from their prototype.
            int aid = o.currentAid(protos);
            if (aid == 0) {
                continue;                       // no art on the instance nor its proto
            }
            // OBJ_F_LOCATION *is* the world location -- use it as such. (The
            // single-sector draft masked it to the low 6 bits per axis to fake a
            // sector-local tile; that folded every sector onto the same 64x64
            // patch, which is exactly the wall this screen no longer has.)
            long oloc = o.location(protos);
            long ox = Location.getX(oloc);
            long oy = Location.getY(oloc);
            if (!visible.contains(ox, oy)) {
                continue;
            }
            sprites.add(new Sprite(ox + oy, ox, 0, aid,
                    Location.screenX(oloc, originX), Location.screenY(oloc, originY),
                    o.resolvedInt(OBJ_F_OFFSET_X, protos),
                    o.resolvedInt(OBJ_F_OFFSET_Y, protos), o));
        }
    }

    private final int[] hot = new int[2];
    private final int[] wh = new int[2];

    /** Anchor an art on its tile: screen = tileScreen + offset + (40,20) − hotspot. */
    private void drawSprite(SpriteBatch batch, int artId, float baseX, float baseY,
                            int offX, int offY, int height) {
        TigArt.frameHotspot(artId, hot);
        TigArt.draw(batch, artId, baseX + offX + 40 - hot[0],
                baseY + offY + 20 - hot[1], height);
    }

    /**
     * The front-most object whose drawn pixels contain the cursor, or null for
     * empty ground.
     *
     * <h2>This approximates {@code target_pick_at_screen_xy} → {@code sub_4F28A0}
     * ({@code target.c}); it is not a port of it.</h2>
     *
     * <p>{@code sub_4F28A0} is inseparable from the party/targeting subsystem,
     * none of which is ported: it consults {@code player_get_local_pc_obj}, sets
     * and clears {@code OF_CLICK_THROUGH} across {@code object_list_all_followers}
     * and {@code object_list_party}, filters on the current {@code TGT_*} params
     * ({@code TGT_OBJ_NO_SELF}, {@code TGT_NON_PARTY_CRITTERS},
     * {@code TGT_OBJ_NO_ST_CRITTER_DEAD}), and falls back to
     * {@code object_list_location} and then to a bare location target. Porting
     * that means porting the party system first, so we hit-test the sprites this
     * screen already builds instead.
     *
     * <p>What <em>is</em> reproduced is the geometry of its inner test,
     * {@code sub_43D9F0} ({@code object.c:1792}), because that is the part that
     * decides what the cursor is over:
     * <ul>
     * <li><b>Front-most first.</b> {@code sub_43D9F0} walks sectors, rows and
     *     tiles in reverse ({@code for (row = num_rows - 1; row &gt;= 0; row--)}
     *     …) and returns the first hit; we walk the depth-sorted draw list
     *     backwards, which is the same near-to-far order.</li>
     * <li><b>The rect.</b> {@code object_get_rect(obj, 0, &rect)} gives
     *     {@code x = loc_x + offset_x + 40 - hot_x}, {@code y = loc_y + offset_y
     *     + 20 - hot_y}, sized by the frame — exactly what {@link #drawSprite}
     *     draws, so the pickable rect is the drawn rect by construction. The
     *     bounds test is half-open, as the C's is.</li>
     * <li><b>Alpha.</b> {@code !sub_502FD0(aid, test_x, test_y)} — a transparent
     *     pixel does not pick. See {@link TigArt#isPickableAt}: the C's threshold
     *     is palette index {@code < 2}, not just index 0.</li>
     * </ul>
     *
     * <p>Knowingly left out, beyond the party/TGT filtering: {@code roof_hit_test},
     * {@code object_type_visibility} and the {@code dword_5E2F88} flag filter, the
     * {@code OBJ_F_BLIT_SCALE}/{@code OF_SHRUNK} rescale of the test point (this
     * screen draws everything unscaled), the wall {@code OWAF_TRANS_*} exclusion,
     * and the {@code flags & 0x01} ±2px sloppy-hit fallback.
     */
    private Sprite pick(int screenX, int screenY) {
        // Reverse of the depth sort: nearest (last drawn, visually on top) first.
        for (int i = drawnSprites.size() - 1; i >= 0; i--) {
            Sprite s = drawnSprites.get(i);
            if (s.obj == null) {
                continue;                       // the player avatar is not a target
            }
            if (!TigArt.frameHotspot(s.artId, hot) || !TigArt.frameSize(s.artId, wh)) {
                continue;                       // unresolved art has no rect
            }
            // object_get_rect: the same anchor drawSprite uses.
            int rx = (int) (s.baseX + s.offX + 40 - hot[0]);
            int ry = (int) (s.baseY + s.offY + 20 - hot[1]);
            if (screenX < rx || screenY < ry
                    || screenX >= rx + wh[0] || screenY >= ry + wh[1]) {
                continue;
            }
            // A transparent pixel must not pick, so the cursor lands on the thing
            // it looks like it is on rather than its bounding box.
            if (TigArt.isPickableAt(s.artId, screenX - rx, screenY - ry)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Resolve what a picked object is and show it. A stepping stone: the dialog
     * number and {@code .dlg} path are surfaced as text because there is no dialog
     * UI yet ({@code .dlg} parsing and the dialog window are the next stage).
     */
    private void identify(com.arcanum.ce.game.GameObject o) {
        pickedLines.clear();
        if (names == null) {
            pickedLines.add("no name tables (mes\\description.mes missing)");
            return;
        }
        String display = names.name(o);
        String internal = names.internalName(o);
        pickedLines.add(display != null ? display : "<unnamed>");

        StringBuilder detail = new StringBuilder();
        detail.append(typeName(o.type));
        if (internal != null) {
            detail.append("   oname: ").append(internal);
        }
        detail.append("   name#").append(names.nameNum(o))
                .append("  desc#").append(names.descriptionNum(o));
        pickedLines.add(detail.toString());

        // OBJ_F_SCRIPTS_IDX is sparse: SAP_DIALOG is a key, not an index.
        com.arcanum.ce.game.Script dlg =
                o.script(com.arcanum.ce.game.Sap.DIALOG, protos);
        if (dlg == null) {
            pickedLines.add("no SAP_DIALOG script");
            return;
        }
        String path = scriptNames == null ? null : scriptNames.buildDlgName(dlg.num);
        String exists = path != null && TigFile.exists(path, null)
                ? " (exists)" : " (missing)";
        pickedLines.add("SAP_DIALOG num " + dlg.num + " -> "
                + (path == null ? "<not indexed>" : path + exists));

        // ...and if that .dlg parses, actually open the conversation.
        if (path == null) {
            return;
        }
        com.arcanum.ce.game.DialogFile file = com.arcanum.ce.game.DialogFile.load(path);
        if (file == null) {
            pickedLines.add("dialog file did not parse");
            return;
        }
        speaker = o;
        dialogUi.start(file, DIALOG_ENTRY_LINE, display);
        if (!dialogUi.isActive()) {
            pickedLines.add("dialog has no line " + DIALOG_ENTRY_LINE);
            speaker = null;
        }
    }

    /**
     * Where the speaker's text bubble should float: the tile centre horizontally,
     * and the top of their sprite vertically (tb.c places bubbles relative to the
     * object). Falls back to the tile anchor when the sprite is not in the last
     * draw list.
     */
    private float[] speakerAnchor() {
        // The speaker's OBJ_F_LOCATION is a world location; project it directly
        // (it used to be masked to a sector-local tile, which only happened to
        // land right because the speaker was always in the one rendered sector).
        long loc = speaker.location(protos);
        float x = Location.screenX(loc, originX) + 40;
        float y = Location.screenY(loc, originY) + 20;
        for (Sprite s : drawnSprites) {
            if (s.obj == speaker) {
                int[] hot = new int[2];
                TigArt.frameHotspot(s.artId, hot);
                // The sprite's drawn top edge (cf. drawSprite / object_get_rect).
                y = s.baseY + s.offY + 20 - hot[1];
                x = s.baseX + s.offX + 40 - hot[0]
                        + spriteWidth(s.artId) / 2f;
                break;
            }
        }
        return new float[] {x, y};
    }

    private static float spriteWidth(int artId) {
        String path = TigArt.buildPath(artId);
        if (path == null) {
            return 0;
        }
        com.arcanum.ce.tig.art.ArtFile art = TigArt.load(path);
        if (art == null) {
            return 0;
        }
        int rot = Math.min(com.arcanum.ce.tig.art.ArtId.rotation(artId),
                art.numRotations - 1);
        int frame = Math.min(com.arcanum.ce.tig.art.ArtId.frame(artId),
                art.numFrames - 1);
        return art.frames[rot][frame].width;
    }

    private static String typeName(int type) {
        return type >= 0 && type < TYPE_NAMES.length ? TYPE_NAMES[type] : "type" + type;
    }

    /** One depth-sortable sprite (an object or the player) queued for drawing. */
    private static final class Sprite {
        final long depth;   // world tile x+y (primary, back-to-front)
        final long tieX;    // world tile x   (secondary, matches terrain scan order)
        final int order;    // 0 = object, 1 = player (drawn last on a shared tile)
        final int artId;
        final float baseX;
        final float baseY;
        final int offX;
        final int offY;
        /** The object this sprite draws, or null for the player avatar. */
        final com.arcanum.ce.game.GameObject obj;

        Sprite(long depth, long tieX, int order, int artId,
               float baseX, float baseY, int offX, int offY,
               com.arcanum.ce.game.GameObject obj) {
            this.depth = depth;
            this.tieX = tieX;
            this.order = order;
            this.artId = artId;
            this.baseX = baseX;
            this.baseY = baseY;
            this.offX = offX;
            this.offY = offY;
            this.obj = obj;
        }
    }

    private static final java.util.Comparator<Sprite> SPRITE_ORDER =
            java.util.Comparator.comparingLong((Sprite s) -> s.depth)
                    .thenComparingLong(s -> s.tieX)
                    .thenComparingInt(s -> s.order);

    private void handleBack() {
        if (Gdx.input != null
                && (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)
                    || Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT))) {
            ScreenManager.pop();
        }
    }
}
