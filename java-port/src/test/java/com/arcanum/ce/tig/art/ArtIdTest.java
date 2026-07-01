package com.arcanum.ce.tig.art;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link ArtId} bitfield encode/decode against the layout in
 * {@code first_party/tig/src/art.c}.
 */
class ArtIdTest {

    @Test
    void miscIdRoundTrips() {
        int id = ArtId.miscIdCreate(5, 2);              // TIG_ART_SYSTEM_LENS, palette 2
        assertEquals(ArtId.TYPE_MISC, ArtId.type(id));
        assertEquals(5, ArtId.num(id));
        assertEquals(2, ArtId.palette(id));
        assertEquals(0, ArtId.rotation(id));            // MISC has no rotation
    }

    @Test
    void interfaceIdRoundTrips() {
        int id = ArtId.interfaceIdCreate(1234, 7, 1, 3);
        assertEquals(ArtId.TYPE_INTERFACE, ArtId.type(id));
        assertEquals(1234, ArtId.num(id));
        assertEquals(7, ArtId.frame(id));
        assertEquals(3, ArtId.palette(id));
        assertEquals(0, ArtId.rotation(id));            // INTERFACE has no rotation
    }

    @Test
    void typeIsTopNibble() {
        // A critter id (type 2) packs rotation/frame/palette in the generic slots.
        int id = (ArtId.TYPE_CRITTER << 28)
                | (3 << ArtId.ART_ID_ROTATION_SHIFT)
                | (9 << ArtId.ART_ID_FRAME_SHIFT)
                | (1 << ArtId.ART_ID_PALETTE_SHIFT);
        assertEquals(ArtId.TYPE_CRITTER, ArtId.type(id));
        assertEquals(3, ArtId.rotation(id));
        assertEquals(9, ArtId.frame(id));
        assertEquals(1, ArtId.palette(id));
        assertEquals(0, ArtId.num(id));                 // critter num is always 0
    }

    @Test
    void facadeIdDecodesNumFrameAndType() {
        // tig_art_facade_id_create layout: num split low(<<17,8 bits)+high(bit 27),
        // frame <<1 (10 bits), rotation always 0.
        int low = facadeId(17, 5);                       // num 17 (< 256), frame 5
        assertEquals(ArtId.TYPE_FACADE, ArtId.type(low));
        assertEquals(17, ArtId.facadeNum(low));
        assertEquals(5, ArtId.frame(low));
        assertEquals(0, ArtId.rotation(low));

        int high = facadeId(300, 0);                     // num 300 needs the high bit
        assertEquals(300, ArtId.facadeNum(high));
    }

    private static int facadeId(int num, int frame) {
        return (ArtId.TYPE_FACADE << 28)
                | ((num < 256 ? 0 : 1) << ArtId.FACADE_ID_NUM_HIGH_SHIFT)
                | ((num & 0xFF) << ArtId.FACADE_ID_NUM_LOW_SHIFT)
                | ((frame & (ArtId.FACADE_ID_MAX_FRAME - 1)) << ArtId.FACADE_ID_FRAME_SHIFT);
    }
}
