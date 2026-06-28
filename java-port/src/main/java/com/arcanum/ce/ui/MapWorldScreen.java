package com.arcanum.ce.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import com.arcanum.ce.game.Location;
import com.arcanum.ce.game.SectorFile;
import com.arcanum.ce.tig.TigArt;

/**
 * The in-game isometric world view: renders one real Arcanum {@code .sec} sector
 * of terrain tiles. Ports the terrain side of {@code tile_draw_iso}
 * ({@code tile.c}) onto the libGDX backend — load the sector's 4096 tile
 * {@code art_id}s ({@link SectorFile}), project each to screen with the engine's
 * isometric transform ({@link Location}), and blit it via the {@link TigArt}
 * pipeline (id → {@code art\tile\*.art} via the tile name resolver).
 *
 * <p>Reached from the main menu's "New Game"; Escape (or right-click) returns.
 * Arrow keys / WASD or left-drag scroll the view. Which sector loads is set by
 * {@code -Darcanum.sector=<repository\path.sec>} (default: a plains template).
 */
public final class MapWorldScreen implements Screen {

    private static final String DEFAULT_SECTOR = "terrain\\plains\\0.sec";
    private static final int SCROLL_SPEED = 12;   // px per frame held

    private final String sectorPath;
    private SectorFile sector;
    private int originX;
    private int originY;
    private boolean originReady;
    private boolean dragging;
    private int dragX;
    private int dragY;

    public MapWorldScreen() {
        this(System.getProperty("arcanum.sector", DEFAULT_SECTOR));
    }

    public MapWorldScreen(String sectorPath) {
        this.sectorPath = sectorPath;
    }

    @Override
    public void create() {
        sector = SectorFile.load(sectorPath);
    }

    @Override
    public void render(SpriteBatch batch, BitmapFont font, int width, int height) {
        if (sector == null) {
            font.draw(batch, "Sector not found: " + sectorPath
                    + "   (Esc to go back)", 16, height - 16);
            handleBack();
            return;
        }

        if (!originReady) {
            centerOn(32, 32, width, height);   // middle of the 64x64 sector
            originReady = true;
        }
        handleInput(width, height);

        // Back-to-front: increasing (X + Y) draws far tiles first so any tile art
        // taller than the 40px diamond is overlapped by the tiles in front of it.
        int n = Location.TILES_PER_SECTOR_AXIS;
        for (int d = 0; d <= 2 * (n - 1); d++) {
            int xStart = Math.max(0, d - (n - 1));
            int xEnd = Math.min(n - 1, d);
            for (int x = xStart; x <= xEnd; x++) {
                int y = d - x;
                long loc = Location.make(x, y);
                int artId = sector.tileAt(x, y);
                TigArt.draw(batch, artId,
                        Location.screenX(loc, originX),
                        Location.screenY(loc, originY),
                        height);
            }
        }

        font.setColor(Color.WHITE);
        font.draw(batch, sectorPath + "   [arrows/drag: scroll, Esc: menu]",
                12, height - 12);
    }

    /** Put the screen pixel of tile (tx, ty) at the window centre. */
    private void centerOn(int tx, int ty, int width, int height) {
        long loc = Location.make(tx, ty);
        originX = width / 2 - (Location.screenX(loc, 0));
        originY = height / 2 - (Location.screenY(loc, 0));
    }

    private void handleInput(int width, int height) {
        if (Gdx.input == null) {
            return;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.LEFT) || Gdx.input.isKeyPressed(Input.Keys.A)) {
            originX += SCROLL_SPEED;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.RIGHT) || Gdx.input.isKeyPressed(Input.Keys.D)) {
            originX -= SCROLL_SPEED;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.UP) || Gdx.input.isKeyPressed(Input.Keys.W)) {
            originY += SCROLL_SPEED;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.DOWN) || Gdx.input.isKeyPressed(Input.Keys.S)) {
            originY -= SCROLL_SPEED;
        }

        // Left-drag panning.
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        int mx = Gdx.input.getX();
        int my = Gdx.input.getY();
        if (down && !dragging) {
            dragging = true;
            dragX = mx;
            dragY = my;
        } else if (down) {
            originX += mx - dragX;
            originY += my - dragY;   // screen Y is top-left origin, same as ours
            dragX = mx;
            dragY = my;
        } else {
            dragging = false;
        }

        handleBack();
    }

    private void handleBack() {
        if (Gdx.input == null) {
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)
                || Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT)) {
            ScreenManager.pop();
        }
    }
}
