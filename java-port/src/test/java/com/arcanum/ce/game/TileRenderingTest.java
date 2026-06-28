package com.arcanum.ce.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

import com.arcanum.ce.tig.art.ArtId;

/**
 * Tests the pure terrain-rendering logic ported in tile-rendering-spec.md:
 * tile-id decoding ({@link ArtId}), tile-name → file path
 * ({@link TileArtResolver}), the {@code .sec} tile-layer parse
 * ({@link SectorFile}), and the isometric projection ({@link Location}).
 *
 * <p>The reference ids are real {@code terrain\plains\0.sec} tiles whose paths
 * were confirmed to exist in the game archives (e.g. 0x010401C0 →
 * {@code art\tile\dg1bse0a.art}).
 */
class TileRenderingTest {

    @Test
    void decodesRealPlainsTileIds() {
        int a = 0x010401C0;     // plains, variation 0
        assertEquals(ArtId.TYPE_TILE, ArtId.type(a));
        assertEquals(4, ArtId.tileNum1(a));
        assertEquals(4, ArtId.tileNum2(a));
        assertEquals(1, ArtId.tileType(a));         // outdoor
        assertEquals(1, ArtId.tileFlippable1(a));
        assertEquals(1, ArtId.tileFlippable2(a));
        assertEquals(0, ArtId.tileBlend(a));
        assertEquals(0, ArtId.tileVariation(a));
        assertEquals(0, ArtId.tileFlags(a));

        int h = 0x01040FC0;     // same tile, variation 7
        assertEquals(7, ArtId.tileVariation(h));
        assertEquals(4, ArtId.tileNum1(h));
    }

    @Test
    void resolvesBaseTileToFilePath() {
        // Outdoor-flippable name table: index 4 = "dg1" (the plains ground tile).
        String[] outdoorFlippable = {"aa", "bb", "cc", "dd", "dg1"};
        TileNames names = TileNames.of(outdoorFlippable, new String[0],
                new String[0], new String[0]);
        TileArtResolver resolver = new TileArtResolver(names);

        // variation 0..7 -> letters a..h, blend 0 -> charset[0]='0'.
        assertEquals("art\\tile\\dg1bse0a.art", resolver.resolve(0x010401C0));
        assertEquals("art\\tile\\dg1bse0d.art", resolver.resolve(0x010407C0));
        assertEquals("art\\tile\\dg1bse0h.art", resolver.resolve(0x01040FC0));
    }

    @Test
    void unresolvableWhenNameMissing() {
        TileNames empty = TileNames.of(new String[0], new String[0],
                new String[0], new String[0]);
        assertNull(new TileArtResolver(empty).resolve(0x010401C0));
        // Non-tile ids are not this resolver's concern.
        assertNull(new TileArtResolver(empty).resolve(ArtId.miscIdCreate(10, 0)));
    }

    @Test
    void parsesSectorTileLayer() {
        // A minimal .sec image: 0 lights, then 4096 sequential tile ids.
        ByteBuffer b = ByteBuffer.allocate(4 + SectorFile.TILE_COUNT * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0);                                 // light count
        for (int i = 0; i < SectorFile.TILE_COUNT; i++) {
            b.putInt(0x01000000 | i);
        }
        SectorFile sec = SectorFile.parse(b.array());

        assertEquals(0x01000000, sec.tileAt(0, 0));
        assertEquals(0x01000001, sec.tileAt(1, 0));  // index 1 = x=1,y=0
        assertEquals(0x01000040, sec.tileAt(0, 1));  // index 64 = x=0,y=1
        assertEquals(0x01000FFF, sec.tileAt(63, 63));
    }

    @Test
    void parsesSectorWithLights() {
        // 2 lights (48 bytes each) precede the tile layer and must be skipped.
        int lights = 2;
        ByteBuffer b = ByteBuffer.allocate(4 + lights * 48 + SectorFile.TILE_COUNT * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(lights);
        b.position(b.position() + lights * 48);
        for (int i = 0; i < SectorFile.TILE_COUNT; i++) {
            b.putInt(i);
        }
        SectorFile sec = SectorFile.parse(b.array());
        assertEquals(0, sec.tileAt(0, 0));
        assertEquals(4095, sec.tileAt(63, 63));
    }

    @Test
    void truncatedSectorParsesToNull() {
        assertNull(SectorFile.parse(new byte[] {1, 2}));            // < 4 bytes
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0);                                                 // claims 0 lights
        b.putInt(123);                                               // only 1 tile
        assertNull(SectorFile.parse(b.array()));                     // not 4096 tiles
    }

    @Test
    void isometricProjectionMatchesEngine() {
        // location_xy (ISOMETRIC): sx = ox + 40*(Y-X-1), sy = oy + 20*(Y+X).
        long loc = Location.make(3, 5);
        assertEquals(1000 + 40 * (5 - 3 - 1), Location.screenX(loc, 1000));
        assertEquals(2000 + 20 * (5 + 3), Location.screenY(loc, 2000));

        // sector / tile index math.
        long world = Location.make(70, 130);         // tile (70,130)
        assertEquals(Location.sectorMake(1, 2), Location.sectorIdFromLoc(world));
        assertEquals((70 & 63) | ((130 & 63) << 6), Location.tileIndexInSector(world));
    }
}
