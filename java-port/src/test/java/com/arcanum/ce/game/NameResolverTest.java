package com.arcanum.ce.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.arcanum.ce.tig.art.ArtId;

/**
 * Verifies the pure (non-.mes) critter path building in {@link NameResolver}
 * against a known real art filename. Body=DWARF, gender=MALE, armor=BARBARIAN,
 * shield, no weapon, anim 3 (=='d') encodes the file
 * {@code art\critter\DFM\DFMBNSAd.art} -- the C engine emits uppercase codes
 * with a lowercase anim letter; archive lookup is case-insensitive so it
 * resolves to the real {@code dfmbnsad.art} asset in arcanum1.dat.
 */
class NameResolverTest {

    // Enum indices (TigArt*).
    private static final int GENDER_MALE = 1;
    private static final int BODY_DWARF = 1;
    private static final int ARMOR_BARBARIAN = 7;
    private static final int WEAPON_NONE = 0;

    @Test
    void buildsKnownCritterPath() {
        int aid = ArtId.critterIdCreate(GENDER_MALE, BODY_DWARF, ARMOR_BARBARIAN,
                /*shield*/ 1, /*frame*/ 0, /*rotation*/ 0, /*anim*/ 3,
                WEAPON_NONE, /*palette*/ 0);
        assertEquals(ArtId.TYPE_CRITTER, ArtId.type(aid));
        assertEquals("art\\critter\\DFM\\DFMBNSAd.art", NameResolver.critterPath(aid));
    }

    @Test
    void encodesWeaponAndAnimLetters() {
        // weapon SWORD (3 -> 'D'), anim 0 (-> 'a'), no shield (-> 'X'), human/female.
        int aid = ArtId.critterIdCreate(/*gender F*/ 0, /*HUMAN*/ 0, /*LEATHER*/ 2,
                /*shield*/ 0, 0, 0, /*anim*/ 0, /*SWORD*/ 3, 0);
        assertEquals("art\\critter\\HMF\\HMFLAXDa.art", NameResolver.critterPath(aid));
    }
}
