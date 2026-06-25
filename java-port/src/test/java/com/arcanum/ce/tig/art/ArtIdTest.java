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
}
