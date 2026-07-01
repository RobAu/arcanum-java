package com.arcanum.ce.tig;

import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

import com.arcanum.ce.tig.art.ArtFile;
import com.arcanum.ce.tig.art.ArtId;
import com.arcanum.ce.tig.art.ArtPathResolver;

/**
 * Art/sprite assets. Maps {@code tig/art.h}.
 *
 * Decoding (.art bytes -&gt; indexed pixels) lives in
 * {@link com.arcanum.ce.tig.art.ArtFile}; this class adds the libGDX rendering
 * layer: decode a frame to a {@link Texture}, cache it, and draw it through a
 * {@link SpriteBatch}. ARTs are loaded from the repository stack via
 * {@link TigFile} (loose files or {@code .dat} archives).
 *
 * Coordinate note: TIG/Arcanum uses a top-left screen origin (y grows down);
 * libGDX uses bottom-left. {@link #draw} takes top-left coordinates and flips
 * into libGDX space so call sites read like the C engine.
 *
 * Not yet faithful: full {@code tig_art_id_t} -&gt; file path resolution
 * ({@code tig_art_build_path}) and the struct-based {@link #blit} entry point;
 * the rendering primitives they need are implemented here.
 */
public final class TigArt {

    private static final Map<String, Texture> TEXTURES = new HashMap<>();
    private static final Map<String, ArtFile> ARTS = new HashMap<>();

    /** Game-supplied resolver for non-system art (cf. name_resolve_path). */
    private static ArtPathResolver pathResolver;

    /** System/MISC art file table (tig_art_build_path, TIG_ART_SYSTEM_*). */
    private static final String[] SYSTEM_ART = {
        "art\\mouse.art",       // 0 MOUSE
        "art\\button.art",      // 1 BUTTON
        "art\\up.art",          // 2 UP
        "art\\down.art",        // 3 DOWN
        "art\\cancel.art",      // 4 CANCEL
        "art\\lens.art",        // 5 LENS
        "art\\x.art",           // 6 X
        "art\\plus.art",        // 7 PLUS
        "art\\minus.art",       // 8 MINUS
        "art\\blank.art",       // 9 BLANK
        "art\\morph15font.art", // 10 FONT
    };

    /** Simple width/height pair returned by convenience helpers. */
    public static final class ArtSize {
        public final int width;
        public final int height;

        public ArtSize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private TigArt() {
    }

    public static int init() {
        return 0;
    }

    public static void exit() {
        for (Texture t : TEXTURES.values()) {
            t.dispose();
        }
        TEXTURES.clear();
        ARTS.clear();
    }

    public static void ping() {
    }

    public static void flush() {
        exit();
    }

    // -- decode ---------------------------------------------------------------
    /** Decode raw .art bytes. */
    public static ArtFile decode(byte[] bytes) {
        return ArtFile.decode(bytes);
    }

    /** Load + decode an .art by repository path (cached). Null if not found. */
    public static ArtFile load(String path) {
        String key = path.replace('\\', '/').toLowerCase();
        ArtFile art = ARTS.get(key);
        if (art != null) {
            return art;
        }
        byte[] bytes = TigFile.readBytes(path);
        if (bytes == null) {
            return null;
        }
        art = ArtFile.decode(bytes);
        ARTS.put(key, art);
        return art;
    }

    public static boolean exists(String path) {
        return TigFile.exists(path, null);
    }

    // -- decode -> Pixmap / Texture ------------------------------------------
    /** Render one decoded frame to a {@link Pixmap} (RGBA8888, index 0 -&gt; alpha 0). */
    public static Pixmap toPixmap(ArtFile art, int rotation, int frame, int paletteSlot) {
        ArtFile.Frame fr = art.frames[rotation][frame];
        Pixmap pm = new Pixmap(fr.width, fr.height, Pixmap.Format.RGBA8888);
        byte[] rgba = art.frameToRgba(fr, paletteSlot);
        pm.getPixels().clear();
        pm.getPixels().put(rgba).flip();
        return pm;
    }

    /** A cached GPU texture for (art identity, rotation, frame, palette). */
    public static Texture texture(String cacheKey, ArtFile art, int rotation,
                                  int frame, int paletteSlot) {
        Texture t = TEXTURES.get(cacheKey);
        if (t != null) {
            return t;
        }
        Pixmap pm = toPixmap(art, rotation, frame, paletteSlot);
        try {
            t = new Texture(pm);
            TEXTURES.put(cacheKey, t);
            return t;
        } finally {
            pm.dispose();
        }
    }

    // -- draw -----------------------------------------------------------------
    /**
     * Draw a frame of an ART (loaded by repository path) at top-left
     * (screenX, screenY) in a coordinate space whose origin is the top-left of
     * the window of height {@code screenHeight}. {@code flipX} mirrors
     * horizontally (used for mirrored critter rotations). No-op if not found.
     */
    public static void draw(SpriteBatch batch, String path, int rotation, int frame,
                            int paletteSlot, float screenX, float screenY,
                            int screenHeight, boolean flipX) {
        ArtFile art = load(path);
        if (art == null) {
            return;
        }
        draw(batch, path, art, rotation, frame, paletteSlot, screenX, screenY,
                screenHeight, flipX);
    }

    /** As above, for an already-decoded ArtFile (cacheKeyBase identifies it). */
    public static void draw(SpriteBatch batch, String cacheKeyBase, ArtFile art,
                            int rotation, int frame, int paletteSlot,
                            float screenX, float screenY, int screenHeight,
                            boolean flipX) {
        ArtFile.Frame fr = art.frames[rotation][frame];
        String key = cacheKeyBase + "#" + rotation + "/" + frame + "/" + paletteSlot;
        Texture tex = texture(key, art, rotation, frame, paletteSlot);
        TextureRegion region = new TextureRegion(tex);
        // The texture's first pixel row is the image top; SpriteBatch already
        // maps that to the top of the quad, so we only translate the top-left
        // (screenX, screenY) into libGDX's bottom-up space. flipX mirrors.
        float gdxY = screenHeight - screenY - fr.height;
        if (flipX) {
            region.flip(true, false);
        }
        batch.draw(region, screenX, gdxY);
    }

    /** tig_art_size: writes width/height of (rotation 0, frame 0). Returns 0 on ok. */
    public static int size(String path, int[] widthOut, int[] heightOut) {
        ArtFile art = load(path);
        if (art == null) {
            return 1;
        }
        ArtFile.Frame fr = art.frames[0][0];
        if (widthOut != null) widthOut[0] = fr.width;
        if (heightOut != null) heightOut[0] = fr.height;
        return 0;
    }

    // -- art_id -> path / draw ------------------------------------------------
    /** Register the game's resolver for non-system art (cf. name_resolve_path). */
    public static void setFilePathResolver(ArtPathResolver resolver) {
        pathResolver = resolver;
    }

    /**
     * tig_art_build_path: resolve a {@code tig_art_id_t} to a repository path.
     * System/MISC art is handled directly; other types delegate to the
     * registered {@link ArtPathResolver}. Returns null if unresolved.
     */
    public static String buildPath(int artId) {
        if (ArtId.type(artId) == ArtId.TYPE_MISC) {
            int n = ArtId.num(artId);
            if (n >= 0 && n < SYSTEM_ART.length) {
                return SYSTEM_ART[n];
            }
            return null;
        }
        return pathResolver != null ? pathResolver.resolve(artId) : null;
    }

    /**
     * Draw an art by {@code tig_art_id_t}: resolve its path and decode the
     * rotation/frame/palette from the id. No-op (returns non-zero) if unresolved.
     */
    public static int draw(SpriteBatch batch, int artId, float screenX,
                           float screenY, int screenHeight) {
        // tig_art_blit: a flippable tile with the flip flag is drawn mirrored,
        // using the art resolved with that flip bit cleared. The flippable tile's
        // art ships only in its non-flipped orientation, so the raw (un-remapped)
        // blend name is the one that exists; the mirror is applied at blit time.
        boolean flipX = false;
        if (ArtId.type(artId) == ArtId.TYPE_TILE && ArtId.tileFlippable(artId)
                && (ArtId.tileFlags(artId) & 1) != 0) {
            flipX = true;
            artId &= ~1;
        }
        String path = buildPath(artId);
        if (path == null) {
            return 1;
        }
        ArtFile art = load(path);
        if (art == null) {
            return 1;
        }
        int rot = Math.min(ArtId.rotation(artId), art.numRotations - 1);
        int frame = Math.min(ArtId.frame(artId), art.numFrames - 1);
        int pal = art.firstPaletteSlot();
        if (pal < 0) {
            pal = 0;
        }
        draw(batch, path, art, rot, frame, pal, screenX, screenY, screenHeight, flipX);
        return 0;
    }

    /** tig_art_blit -- struct-based entry point used by generated engine code.
     *  The {@code TigArtBlitInfo} struct type is generated from the game sources;
     *  once present, extract {@code art_id} + {@code dst_rect} and call
     *  {@link #draw(SpriteBatch, int, float, float, int)}. */
    public static int blit(Object blitInfo) {
        throw new UnsupportedOperationException(
                "TODO: extract art_id/dst_rect from TigArtBlitInfo, then "
                + "TigArt.draw(batch, artId, x, y, h)");
    }
}
