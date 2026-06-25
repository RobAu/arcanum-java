package com.arcanum.ce.tig;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;

/**
 * BMP load/save. Maps {@code tig/bmp.h}. Backed by libGDX {@link Pixmap}.
 */
public final class TigBmp {

    public int width;
    public int height;
    public Pixmap pixmap;

    /** tig_bmp_create -- load the BMP named by this struct's path. */
    public static int create(TigBmp bmp, String path) {
        if (Gdx.files == null) {
            return 1;
        }
        Pixmap pm = new Pixmap(Gdx.files.internal(path));
        bmp.pixmap = pm;
        bmp.width = pm.getWidth();
        bmp.height = pm.getHeight();
        return 0;
    }

    public static int destroy(TigBmp bmp) {
        if (bmp.pixmap != null) {
            bmp.pixmap.dispose();
            bmp.pixmap = null;
        }
        return 0;
    }
}
