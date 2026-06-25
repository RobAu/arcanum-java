package com.arcanum.ce.tig;

import com.badlogic.gdx.graphics.Pixmap;

/**
 * Off-screen surface. Maps the {@code TigVideoBuffer} object and the
 * tig_video_buffer_* calls in {@code tig/video.h}. Backed by a libGDX
 * {@link Pixmap} (CPU-side) and, when uploaded, a Texture. Skeleton only.
 */
public final class TigVideoBuffer {

    public int width;
    public int height;
    Pixmap pixmap;

    public TigVideoBuffer(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** tig_video_buffer_create */
    public static int create(Object createInfo, TigVideoBuffer[] out) {
        throw new UnsupportedOperationException(
                "TODO: TigVideoBuffer.create from TigVideoBufferCreateInfo");
    }

    public int destroy() {
        if (pixmap != null) {
            pixmap.dispose();
            pixmap = null;
        }
        return 0;
    }

    public int fill(TigRect rect, int color) {
        return 0;
    }
}
