package com.arcanum.ce.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import com.arcanum.ce.game.Location;
import com.arcanum.ce.game.Player;
import com.arcanum.ce.game.SectorFile;
import com.arcanum.ce.tig.TigArt;

/**
 * The in-game isometric world view: a real Arcanum {@code .sec} sector of
 * terrain with a player avatar you can walk around. Ports the terrain side of
 * {@code tile_draw_iso} ({@code tile.c}) plus a minimal player on top — load the
 * sector's 4096 tile {@code art_id}s ({@link SectorFile}), project each with the
 * engine's isometric transform ({@link Location}), and blit via {@link TigArt}.
 *
 * <p>Controls: arrow keys / WASD walk (8 directions, camera follows); left-click
 * a tile to walk there; Escape or right-click returns to the menu. The sector
 * is set by {@code -Darcanum.sector=<repository\path.sec>} (default: plains).
 */
public final class MapWorldScreen implements Screen {

    private static final String DEFAULT_SECTOR = "terrain\\plains\\0.sec";
    private static final int N = Location.TILES_PER_SECTOR_AXIS;   // 64
    private static final int STEP_COOLDOWN_FRAMES = 7;             // ~8 tiles/sec

    // Tile delta per facing direction (location_in_dir order, dir 0..7).
    private static final int[] DIR_DX = {-1, -1, -1, 0, 1, 1, 1, 0};
    private static final int[] DIR_DY = {-1, 0, 1, 1, 1, 0, -1, -1};

    private final String sectorPath;
    private SectorFile sector;
    private Player player;
    private int originX;
    private int originY;
    private int cooldown;
    private int targetX = -1;        // click-to-move target tile, -1 = none
    private int targetY = -1;

    public MapWorldScreen() {
        this(System.getProperty("arcanum.sector", DEFAULT_SECTOR));
    }

    public MapWorldScreen(String sectorPath) {
        this.sectorPath = sectorPath;
    }

    @Override
    public void create() {
        sector = SectorFile.load(sectorPath);
        player = new Player(N / 2, N / 2);
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
        // Camera follows the player: keep the player's tile at the window centre.
        originX = width / 2 - Location.screenX(player.loc(), 0);
        originY = height / 2 - Location.screenY(player.loc(), 0);

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

        // Player on top of the terrain at its tile.
        TigArt.draw(batch, player.artId(),
                Location.screenX(player.loc(), originX),
                Location.screenY(player.loc(), originY), height);

        font.setColor(Color.WHITE);
        font.draw(batch, sectorPath + "   tile (" + player.x() + ", " + player.y()
                + ")   [WASD/arrows or click: move, Esc: menu]", 12, height - 12);
    }

    private void update() {
        handleBack();
        if (Gdx.input == null) {
            return;
        }

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

        if (cooldown > 0) {
            cooldown--;
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
        step(dir);
    }

    /** Move one tile in {@code dir} if it stays in the sector; face that way. */
    private void step(int dir) {
        player.setRotation(dir);
        player.setAnim(Player.ANIM_WALK);
        int nx = player.x() + DIR_DX[dir];
        int ny = player.y() + DIR_DY[dir];
        if (nx >= 0 && nx < N && ny >= 0 && ny < N) {
            player.setTile(nx, ny);
            cooldown = STEP_COOLDOWN_FRAMES;
        }
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

    private void handleBack() {
        if (Gdx.input != null
                && (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)
                    || Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT))) {
            ScreenManager.pop();
        }
    }
}
