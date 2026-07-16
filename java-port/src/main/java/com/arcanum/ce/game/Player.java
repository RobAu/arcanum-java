package com.arcanum.ce.game;

import com.arcanum.ce.tig.art.ArtId;

/**
 * A controllable critter on the tile grid — the player avatar for the world
 * view. Holds a tile position, a facing rotation (0..7, matching the engine's
 * direction order in {@code location_in_dir}), and the current animation; the
 * sprite is a critter {@code art_id} built from those, drawn via the standard
 * {@link com.arcanum.ce.tig.TigArt} pipeline.
 *
 * <p>The position is a <em>world</em> tile, as {@code OBJ_F_LOCATION} is for
 * every other object — not a sector-local 0..63 tile. A map is 64×64-tile
 * sectors wide (the retail start map is nominally 2000×2000 of them), so these
 * are {@code long}s and routinely run into the tens of thousands: the campaign
 * start location is (92958, 82592). The sector a tile falls in is
 * {@code (x >> 6, y >> 6)} — {@link Location#sectorIdFromLoc}.
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

    private long x;                     // world tile X (not sector-local)
    private long y;                     // world tile Y
    private long prevX;                 // tile being walked away from (for tween)
    private long prevY;
    private double walkT = 1.0;         // 0..1 progress from prev tile to (x,y)
    private int rotation;
    private int anim = ANIM_STAND;

    public Player(long x, long y) {
        this.x = x;
        this.y = y;
        this.prevX = x;
        this.prevY = y;
    }

    /** World tile X. */
    public long x() {
        return x;
    }

    /** World tile Y. */
    public long y() {
        return y;
    }

    /** The sector the player currently stands in ({@code sector_id_from_loc}). */
    public long sectorId() {
        return Location.sectorIdFromLoc(loc());
    }

    /** Location of the player's current (destination) tile. */
    public long loc() {
        return Location.make(x, y);
    }

    /** Location of the tile the player is walking from (equals {@link #loc()} when idle). */
    public long prevLoc() {
        return Location.make(prevX, prevY);
    }

    /**
     * Begin a walk step into {@code (x, y)}: the sprite tweens from its current
     * tile to the new one. Logical position updates immediately; the visual
     * catch-up is driven by {@link #advanceWalk}.
     */
    public void setTile(long x, long y) {
        this.prevX = this.x;
        this.prevY = this.y;
        this.x = x;
        this.y = y;
        this.walkT = 0.0;
    }

    /** Advance the tween by {@code inc} (fraction of a tile); clamps at 1. */
    public void advanceWalk(double inc) {
        if (walkT < 1.0) {
            walkT = Math.min(1.0, walkT + inc);
        }
    }

    /** Tween progress 0..1 from {@link #prevLoc()} to {@link #loc()}. */
    public double walkT() {
        return walkT;
    }

    /** True while the sprite is still sliding between tiles. */
    public boolean isMoving() {
        return walkT < 1.0;
    }

    public void setRotation(int rotation) {
        this.rotation = rotation & 7;
    }

    public void setAnim(int anim) {
        this.anim = anim;
    }

    /** The critter {@code art_id} for the current facing + animation, frame 0. */
    public int artId() {
        return artId(0);
    }

    /** The critter {@code art_id} for the current facing + animation at {@code frame}. */
    public int artId(int frame) {
        return ArtId.critterIdCreate(GENDER, BODY_TYPE, ARMOR, SHIELD,
                frame, rotation, anim, WEAPON, PALETTE);
    }
}
