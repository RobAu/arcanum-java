package com.arcanum.ce.game;

import com.arcanum.ce.tig.art.ArtId;

/**
 * A controllable critter on the tile grid — the player avatar for the world
 * view. Holds a tile position, a facing rotation (0..7, matching the engine's
 * direction order in {@code location_in_dir}), and the current animation; the
 * sprite is a critter {@code art_id} built from those, drawn via the standard
 * {@link com.arcanum.ce.tig.TigArt} pipeline.
 *
 * <p>Appearance is a fixed placeholder (the demo dwarf) until character creation
 * exists. {@code STAND}/{@code WALK} are {@code TIG_ART_ANIM_*} values.
 */
public final class Player {

    public static final int ANIM_STAND = 0;     // TIG_ART_ANIM_STAND
    public static final int ANIM_WALK = 1;      // TIG_ART_ANIM_WALK

    // Placeholder appearance (the known-good demo critter): gender, body type,
    // armor, shield, weapon, palette — see ArtId.critterIdCreate / NameResolver.
    private static final int GENDER = 1;
    private static final int BODY_TYPE = 1;     // DF (dwarf)
    private static final int ARMOR = 7;
    private static final int SHIELD = 1;
    // Weapon C (art code) — the DFMBNS* critter only ships STAND/WALK anims for
    // weapon codes B/C, not the unarmed A; C has the fullest anim set (a,b,d,f,u,v).
    private static final int WEAPON = 2;
    private static final int PALETTE = 0;

    private int x;
    private int y;
    private int rotation;
    private int anim = ANIM_STAND;

    public Player(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    /** Location of the player's current tile. */
    public long loc() {
        return Location.make(x, y);
    }

    public void setTile(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void setRotation(int rotation) {
        this.rotation = rotation & 7;
    }

    public void setAnim(int anim) {
        this.anim = anim;
    }

    /** The critter {@code art_id} for the current facing + animation. */
    public int artId() {
        return ArtId.critterIdCreate(GENDER, BODY_TYPE, ARMOR, SHIELD,
                /* frame */ 0, rotation, anim, WEAPON, PALETTE);
    }
}
