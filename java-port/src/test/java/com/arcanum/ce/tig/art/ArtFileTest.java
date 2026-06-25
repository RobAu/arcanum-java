package com.arcanum.ce.tig.art;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;

/**
 * Verifies the Java {@link ArtFile} decoder against goldens produced by the
 * validated Python reference (c2java/tools/art_decode.py) on a synthetic,
 * license-safe fixture (c2java/tools/make_art_fixture.py).
 *
 * Goldens (synthetic.golden):
 *   rot0 frame0 4x4 rgba_crc32=6F8800BB   (raw frame)
 *   rot0 frame1 4x4 rgba_crc32=938E82F2   (RLE color-run frame)
 */
class ArtFileTest {

    private static byte[] readResource(String name) throws Exception {
        try (InputStream in = ArtFileTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "missing test resource: " + name);
            return in.readAllBytes();
        }
    }

    private static String crc32(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data);
        return String.format("%08X", c.getValue());
    }

    @Test
    void decodesSyntheticFixtureMatchingPythonGoldens() throws Exception {
        byte[] bytes = readResource("/art/synthetic.art");
        ArtFile art = ArtFile.decode(bytes);

        assertEquals(1, art.numRotations);
        assertEquals(2, art.numFrames);
        assertEquals(8, art.fps);
        assertEquals(0, art.firstPaletteSlot());

        ArtFile.Frame raw = art.frames[0][0];
        assertEquals(4, raw.width);
        assertEquals(4, raw.height);
        assertEquals(16, raw.indices.length);
        assertEquals("6F8800BB", crc32(art.frameToRgba(raw, 0)),
                "raw frame RGBA must match the Python reference golden");

        ArtFile.Frame rle = art.frames[0][1];
        assertEquals(16, rle.indices.length);
        assertEquals("938E82F2", crc32(art.frameToRgba(rle, 0)),
                "RLE frame RGBA must match the Python reference golden");
    }

    @Test
    void colorKeyIndexZeroIsTransparent() throws Exception {
        ArtFile art = ArtFile.decode(readResource("/art/synthetic.art"));
        byte[] rgba = art.frameToRgba(art.frames[0][0], 0);
        // Centre pixel (1,1) in the 4x4 raw frame is index 0 -> alpha 0.
        int centre = (1 * 4 + 1) * 4;
        assertEquals(0, rgba[centre + 3] & 0xFF, "color-key pixel must be transparent");
    }
}
