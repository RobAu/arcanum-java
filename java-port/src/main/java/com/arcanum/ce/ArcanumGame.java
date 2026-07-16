package com.arcanum.ce;

import java.io.File;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.ScreenUtils;

import com.arcanum.ce.game.NameResolver;
import com.arcanum.ce.tig.Tig;
import com.arcanum.ce.tig.TigArt;
import com.arcanum.ce.ui.MainMenuScreen;
import com.arcanum.ce.ui.MapWorldScreen;
import com.arcanum.ce.ui.Screen;
import com.arcanum.ce.ui.ScreenManager;

/**
 * Root libGDX application.
 *
 * Reproduces the shape of the C engine's boot sequence ({@code src/main.c}):
 * initialise the TIG runtime, discover the game-data archives, install the
 * art-name resolver, then enter the main menu screen. Each frame pings the
 * runtime and renders the active {@link Screen}. If no game data is found it
 * shows a placeholder instead of the menu.
 *
 * Headless capture: {@code -Darcanum.screenshot=<png>} renders one frame, saves
 * it, and exits.
 */
public final class ArcanumGame extends ApplicationAdapter {

    private SpriteBatch batch;
    private BitmapFont font;
    private boolean haveData;
    private int frames;

    @Override
    public void create() {
        Tig.init();
        TigArt.init();
        batch = new SpriteBatch();
        font = new BitmapFont();

        haveData = GameData.discoverAndRegister() != null;
        if (haveData) {
            NameResolver.install();                       // art_id -> path
            ScreenManager.push(new MainMenuScreen());     // the real boot screen
            // Headless/dev shortcut: jump straight into the world view.
            if ("world".equals(System.getProperty("arcanum.screen"))) {
                ScreenManager.push(new MapWorldScreen());
            }
        }
    }

    /**
     * Keep one batch unit equal to one logical window pixel.
     *
     * <p>A {@link SpriteBatch} takes its projection from the window size when it
     * is constructed and never updates it. Screens lay out (and hit-test the
     * mouse) against the <em>current</em> {@code Gdx.graphics} size, so once the
     * window is resized the two disagree: the picture keeps being drawn in the
     * old space and stretched to fit, while {@link com.badlogic.gdx.Input#getX}
     * reports the new space — the cursor and what it points at drift apart.
     *
     * <p>Re-projecting to the new logical size fixes both: drawing matches the
     * window, and input coordinates (which are in logical pixels, y down) line up
     * with the top-left-origin convention the screens already use. The GL
     * viewport takes back-buffer pixels, which differ from logical ones on HiDPI.
     */
    @Override
    public void resize(int width, int height) {
        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(),
                Gdx.graphics.getBackBufferHeight());
        batch.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, width, height));
    }

    @Override
    public void render() {
        Tig.ping();
        Gdx.gl.glClearColor(0.05f, 0.04f, 0.07f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        int h = Gdx.graphics.getHeight();
        int w = Gdx.graphics.getWidth();

        batch.begin();
        Screen screen = ScreenManager.current();
        if (screen != null) {
            screen.render(batch, font, w, h);
        } else {
            font.draw(batch, "Arcanum CE (Java) -- boot OK (no game data; "
                    + "set -Darcanum.data=<dir>)", 16, h - 12);
        }
        batch.end();

        frames++;
        maybeScreenshot();
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
        ScreenManager.disposeAll();
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
