package com.arcanum.ce.tig;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Screen / video. Maps {@code tig/video.h} (the screen-level tig_video_* calls;
 * off-screen surfaces live in {@link TigVideoBuffer}). Backed by libGDX GL and a
 * {@link ShapeRenderer} for fills.
 */
public final class TigVideo {

    private static ShapeRenderer shapes;
    private static final Color SCRATCH = new Color();

    private TigVideo() {
    }

    public static int init() {
        shapes = new ShapeRenderer();
        return 0;
    }

    public static void exit() {
        if (shapes != null) {
            shapes.dispose();
            shapes = null;
        }
    }

    public static int getWidth() {
        return Gdx.graphics != null ? Gdx.graphics.getWidth() : 0;
    }

    public static int getHeight() {
        return Gdx.graphics != null ? Gdx.graphics.getHeight() : 0;
    }

    /** tig_video_fill */
    public static int fill(TigRect rect, int color) {
        if (shapes == null || rect == null) {
            return 1;
        }
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(TigColor.toGdx(color, SCRATCH));
        shapes.rect(rect.x, getHeight() - rect.y - rect.height, rect.width, rect.height);
        shapes.end();
        return 0;
    }

    /** tig_video_flip -- the LWJGL3 backend swaps buffers automatically each
     *  frame, so this is a no-op marker for now. */
    public static int flip() {
        return 0;
    }

    public static int clear(int color) {
        TigColor.toGdx(color, SCRATCH);
        Gdx.gl.glClearColor(SCRATCH.r, SCRATCH.g, SCRATCH.b, SCRATCH.a);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        return 0;
    }
}
