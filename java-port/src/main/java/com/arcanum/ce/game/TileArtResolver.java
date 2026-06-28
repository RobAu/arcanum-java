package com.arcanum.ce.game;

import com.arcanum.ce.tig.art.ArtId;

/**
 * Resolves a {@code TIG_ART_TYPE_TILE} art id to its {@code art\tile\*.art}
 * path, ported from {@code a_name.c} ({@code a_name_tile_aid_to_fname} +
 * {@code build_tile_file_name}). See {@code tile-rendering-spec.md}.
 *
 * <p>A tile blends two terrain names ({@code num1}/{@code num2}); a blend index
 * picks a character from {@link #BLEND_CHARS} and a variation picks a letter.
 * Single-terrain tiles ({@code name1 == name2}) — the common ground case — use
 * the {@code <name>bse<c><v>.art} form. Edge-blend forms depend on the
 * not-yet-ported tile-edge tables and fall back to the base form.
 */
public final class TileArtResolver {

    // off_5BB4E4 (a_name.c): blend index -> filename character.
    private static final String BLEND_CHARS = "06b489237ea5dc10";

    private final TileNames names;

    public TileArtResolver(TileNames names) {
        this.names = names;
    }

    /** a_name_tile_aid_to_fname; null if the id isn't a tile or names are missing. */
    public String resolve(int aid) {
        if (ArtId.type(aid) != ArtId.TYPE_TILE) {
            return null;
        }
        int type = ArtId.tileType(aid);
        String name1 = names.nameOf(ArtId.tileNum1(aid), type, ArtId.tileFlippable1(aid));
        String name2 = names.nameOf(ArtId.tileNum2(aid), type, ArtId.tileFlippable2(aid));
        if (name1 == null || name2 == null) {
            return null;
        }
        return buildFileName(name1, name2, ArtId.tileBlend(aid), ArtId.tileVariation(aid));
    }

    /** build_tile_file_name (a_name.c:291). */
    private String buildFileName(String name1, String name2, int a3, int a4) {
        if (a4 >= 8) {
            a4 -= 8;
        }
        char v = (char) ('a' + a4);

        if (a3 == 15 || name1.equalsIgnoreCase(name2)) {
            return base(name1, a3, v);
        }
        if (a3 == 0) {
            return base(name2, 0, v);
        }
        if (names.edgeIndex(name1) < 0) {
            return base(name1, a3, v);
        }
        if (names.edgeIndex(name2) < 0) {
            return base(name2, 15 - a3, v);
        }
        if (names.edgeIndex(name1) < names.edgeIndex(name2)) {
            return "art\\tile\\" + name1 + name2 + BLEND_CHARS.charAt(a3) + v + ".art";
        }
        return "art\\tile\\" + name2 + name1 + BLEND_CHARS.charAt(15 - a3) + v + ".art";
    }

    private static String base(String name, int blend, char variation) {
        return "art\\tile\\" + name + "bse" + BLEND_CHARS.charAt(blend) + variation + ".art";
    }
}
