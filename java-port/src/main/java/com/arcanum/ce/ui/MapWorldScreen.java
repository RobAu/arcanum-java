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
 * <p>Clicking an object identifies it: {@link #pick} finds what is under the
 * cursor and {@link #identify} resolves its name and dialog script, shown as
 * on-screen text. This is stage one of click-to-interact — there is no dialog UI
 * yet, so the {@code .dlg} the object <em>would</em> open is printed instead.
 * {@link #pick} approximates {@code target_pick_at_screen_xy} rather than porting
 * it; see its javadoc for exactly what is and is not reproduced.
 *
 * <p>Controls: arrow keys / WASD walk (8 directions, camera follows); left-click
 * empty ground to walk there, or hold the left button to keep walking toward the
 * cursor; left-click an object to identify it instead of walking; hover any
 * object to see its name. Escape or right-click returns to the menu. The sector
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
    private int targetX = -1;        // click-to-move target tile, -1 = none
    private int targetY = -1;
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

    public MapWorldScreen() {
        this(System.getProperty("arcanum.sector"));   // null → campaign start map
    }

    public MapWorldScreen(String sectorPath) {
        this.sectorPath = sectorPath;
    }

    @Override
    public void create() {
        tileNames = TileNames.load();
        protos = com.arcanum.ce.game.ProtoStore.get();
        // The identify chain: OBJ_F_DESCRIPTION -> description.mes for the display
        // name, OBJ_F_SCRIPTS_IDX[SAP_DIALOG] -> num -> dlg\*.dlg for the dialog.
        names = com.arcanum.ce.game.ObjectName.get(protos);
        scriptNames = com.arcanum.ce.game.ScriptName.get();
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
                mobiles = loadMobiles(maps, protos);
            } else {
                sectorPath = FALLBACK_SECTOR;
            }
        }
        sector = SectorFile.load(sectorPath);

        String forced = System.getProperty("arcanum.pick");   // "x,y" screen (debug/verify)
        if (forced != null && forced.matches("\\d+,\\d+")) {
            String[] xy = forced.split(",");
            forcedPick = new int[] {Integer.parseInt(xy[0]), Integer.parseInt(xy[1])};
        }

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
     * OBJ_F_LOCATION -- carried inventory -- resolve to location 0 and fall out
     * naturally, since sector 0 is not the start sector.
     */
    private static java.util.List<com.arcanum.ce.game.GameObject> loadMobiles(
            MapList maps, com.arcanum.ce.game.ProtoStore protos) {
        long sectorId = Location.sectorMake(maps.startX >> 6, maps.startY >> 6);
        java.util.List<com.arcanum.ce.game.GameObject> here = new java.util.ArrayList<>();
        for (com.arcanum.ce.game.GameObject o
                : com.arcanum.ce.game.MapMobiles.load(maps.startMapName)) {
            if (Location.sectorIdFromLoc(o.location(protos)) == sectorId) {
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
        font.draw(batch, sectorPath + "   tile (" + player.x() + ", " + player.y()
                + ")   [WASD/arrows, click or hold LMB: move, click an object:"
                + " identify/talk, Esc: menu]",
                12, height - 12);

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
            // Art, location and offsets all go through the prototype fallback
            // (obj_field_fetch): most objects override none of them on the
            // instance and inherit them from their prototype.
            int aid = o.currentAid(protos);
            if (aid == 0) {
                continue;                       // no art on the instance nor its proto
            }
            // OBJ_F_LOCATION holds a full world location; the sector-local tile
            // (0..63) is its low 6 bits per axis (cf. Location.tileIndexInSector).
            long oloc = o.location(protos);
            int ox = (int) (Location.getX(oloc) & (N - 1));
            int oy = (int) (Location.getY(oloc) & (N - 1));
            long tl = Location.make(ox, oy);
            sprites.add(new Sprite(ox + oy, ox, 0, aid,
                    Location.screenX(tl, originX), Location.screenY(tl, originY),
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
        long loc = speaker.location();
        int sx = (int) (Location.getX(loc) & (N - 1));
        int sy = (int) (Location.getY(loc) & (N - 1));
        long tl = Location.make(sx, sy);
        float x = Location.screenX(tl, originX) + 40;
        float y = Location.screenY(tl, originY) + 20;
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
        final int depth;    // tile x+y (primary, back-to-front)
        final int tieX;     // tile x   (secondary, matches terrain scan order)
        final int order;    // 0 = object, 1 = player (drawn last on a shared tile)
        final int artId;
        final float baseX;
        final float baseY;
        final int offX;
        final int offY;
        /** The object this sprite draws, or null for the player avatar. */
        final com.arcanum.ce.game.GameObject obj;

        Sprite(int depth, int tieX, int order, int artId,
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
