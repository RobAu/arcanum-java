package com.arcanum.ce;

import java.io.File;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.ScreenUtils;

import com.arcanum.ce.tig.Tig;
import com.arcanum.ce.tig.TigArt;
import com.arcanum.ce.tig.art.ArtId;

/**
 * Root libGDX application.
 *
 * Boots the TIG runtime, discovers the game data archives, and renders a small
 * scene built from real Arcanum sprites (a ground tile, a container, and an
 * animated critter) decoded straight from the {@code .dat} files via the ported
 * ART decoder. If no game data is found it falls back to a placeholder message.
 *
 * Headless capture: pass {@code -Darcanum.screenshot=<path.png>} to render one
 * frame, save it, and exit -- used to verify the renderer without a human at the
 * window.
 */
public final class ArcanumGame extends ApplicationAdapter {

    private SpriteBatch batch;
    private BitmapFont font;
    private boolean haveData;
    private int frames;
    private float animTimer;
    private int critterFrame;

    // Sprites for the demo scene (repository paths; safe if absent).
    private static final String TILE = "art\\tile\\bg1bg21a.art";
    private static final String CHEST = "art\\container\\blackchest1.art";
    private static final String CRITTER = "art\\critter\\dfm\\dfmbnsad.art";

    @Override
    public void create() {
        Tig.init();
        TigArt.init();
        batch = new SpriteBatch();
        font = new BitmapFont();
        haveData = GameData.discoverAndRegister() != null;
    }

    @Override
    public void render() {
        Tig.ping();
        Gdx.gl.glClearColor(0.05f, 0.04f, 0.07f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        int h = Gdx.graphics.getHeight();
        int w = Gdx.graphics.getWidth();

        // Advance the critter's walk animation (~10 fps).
        animTimer += Gdx.graphics.getDeltaTime();
        if (animTimer > 0.1f) {
            animTimer = 0f;
            critterFrame++;
        }

        batch.begin();
        if (haveData) {
            drawScene(w, h);
        }
        font.draw(batch, haveData
                ? "Arcanum CE (Java) -- real sprites decoded from .dat archives"
                : "Arcanum CE (Java) -- boot OK (no game data; set -Darcanum.data=<dir>)",
                16, h - 12);
        batch.end();

        frames++;
        maybeScreenshot();
    }

    private void drawScene(int w, int h) {
        // Tile the ground across the bottom with a real terrain tile.
        TigArt.ArtSize ts = sizeOf(TILE);
        if (ts != null) {
            for (int y = h - ts.height * 3; y < h; y += ts.height) {
                for (int x = 0; x < w; x += ts.width) {
                    TigArt.draw(batch, TILE, 0, 0, 0, x, y, h, false);
                }
            }
        }

        // A container sitting on the ground.
        TigArt.draw(batch, CHEST, 0, 0, 0, 120, h - 170, h, false);

        // The animated critter, mid-screen, plus a mirrored copy facing the other way.
        var critter = TigArt.load(CRITTER);
        if (critter != null) {
            int nf = critter.numFrames;
            int rot = 0;                          // facing
            int frame = nf > 0 ? critterFrame % nf : 0;
            TigArt.draw(batch, CRITTER, rot, frame, 0, 260, h - 200, h, false);
            TigArt.draw(batch, CRITTER, rot, frame, 0, 340, h - 200, h, true);
        }

        // Interface elements drawn by tig_art_id_t (system art -> path -> render),
        // proving the full art-id resolution chain: lens + button reticles.
        int lensId = ArtId.miscIdCreate(5 /*TIG_ART_SYSTEM_LENS*/, 0);
        int buttonId = ArtId.miscIdCreate(1 /*TIG_ART_SYSTEM_BUTTON*/, 0);
        TigArt.draw(batch, lensId, w - 80, 40, h);
        TigArt.draw(batch, buttonId, w - 160, 44, h);
    }

    private TigArt.ArtSize sizeOf(String path) {
        int[] wd = new int[1];
        int[] ht = new int[1];
        return TigArt.size(path, wd, ht) == 0 ? new TigArt.ArtSize(wd[0], ht[0]) : null;
    }

    private void maybeScreenshot() {
        String path = System.getProperty("arcanum.screenshot");
        if (path == null || frames < 2) {       // let one full frame settle
            return;
        }
        int bw = Gdx.graphics.getBackBufferWidth();
        int bh = Gdx.graphics.getBackBufferHeight();
        Pixmap pm = ScreenUtils.getFrameBufferPixmap(0, 0, bw, bh);
        flipVertically(pm, bw, bh);   // framebuffer is bottom-up; make PNG match screen
        PixmapIO.writePNG(new com.badlogic.gdx.files.FileHandle(new File(path)), pm);
        pm.dispose();
        Gdx.app.log("Arcanum", "screenshot written: " + path);
        Gdx.app.exit();
    }

    /** Flip an RGBA pixmap top-to-bottom in place (framebuffer -> image order). */
    private static void flipVertically(Pixmap pm, int w, int h) {
        java.nio.ByteBuffer pixels = pm.getPixels();
        int stride = w * 4;
        byte[] row = new byte[stride];
        byte[] other = new byte[stride];
        for (int y = 0; y < h / 2; y++) {
            int top = y * stride;
            int bot = (h - 1 - y) * stride;
            pixels.position(top);
            pixels.get(row);
            pixels.position(bot);
            pixels.get(other);
            pixels.position(top);
            pixels.put(other);
            pixels.position(bot);
            pixels.put(row);
        }
        pixels.position(0);
    }

    @Override
    public void dispose() {
        if (batch != null) {
            batch.dispose();
        }
        if (font != null) {
            font.dispose();
        }
        TigArt.exit();
        Tig.exit();
    }
}
