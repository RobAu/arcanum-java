package com.arcanum.ce.game;

import com.arcanum.ce.tig.TigArt;
import com.arcanum.ce.tig.art.ArtId;
import com.arcanum.ce.tig.art.ArtPathResolver;
import com.arcanum.ce.tig.mes.Mes;

/**
 * Game-layer {@code tig_art_id_t} → file path resolver.
 *
 * Ports {@code name_resolve_path} from {@code src/game/name.c}: it builds art
 * file paths from id bitfields, using a set of {@code .mes} name tables for the
 * data-driven types (scenery, interface, container, monster, unique NPC, eye
 * candy) and fixed code tables for critters. Register it once via
 * {@link #install()} so {@link TigArt#buildPath} resolves non-system art.
 *
 * Faithful for: critter, interface, scenery, container, monster, unique_npc,
 * eye_candy, and tile (base terrain; see {@link TileArtResolver}). The remaining
 * {@code a_name_*}-driven types (wall, portal, item, light, roof, facade) and
 * {@code name_normalize_aid}'s missing-art fallback remain TODO; those ids
 * resolve to null (the caller falls back).
 */
public final class NameResolver implements ArtPathResolver {

    // Fixed code tables (name.c).
    private static final char[] SHIELDING_CODES = {'X', 'S'};
    private static final char[] GENDER_CODES = {'F', 'M', 'X'};
    private static final String[] BODY_TYPE_STRS = {"HM", "DF", "GH", "HG", "EF"};
    private static final String[] ARMOR_TYPE_STRS =
            {"UW", "V1", "LA", "CM", "PM", "RB", "PC", "BN", "CD"};
    private static final char[] WEAPON_TYPE_CODES =
            {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'X', 'Y', 'N', 'Z'};
    private static final char[] EYE_CANDY_TYPE_CODES = {'F', 'B', 'U'};

    // Enum values referenced by the logic.
    private static final int ARMOR_PLATE = 4;
    private static final int ARMOR_PLATE_CLASSIC = 6;
    private static final int WEAPON_SWORD = 3;
    private static final int WEAPON_TWO_HANDED_SWORD = 7;
    private static final int ANIM_EXPLODE = 24;     // TIG_ART_ANIM_EXPLODE
    private static final int ARMOR_TYPE_COUNT = 9;

    private int sceneryMes = Mes.INVALID_HANDLE;
    private int interfaceMes = Mes.INVALID_HANDLE;
    private int containerMes = Mes.INVALID_HANDLE;
    private int monsterMes = Mes.INVALID_HANDLE;
    private int uniqueNpcMes = Mes.INVALID_HANDLE;
    private int eyeCandyMes = Mes.INVALID_HANDLE;
    private TileArtResolver tileResolver;   // null if tilename.mes is unavailable
    private boolean initialized;

    private NameResolver() {
    }

    /** name_init + register with TigArt; returns null if the name tables fail to load. */
    public static NameResolver install() {
        NameResolver r = new NameResolver();
        if (!r.init()) {
            return null;
        }
        TigArt.setFilePathResolver(r);
        return r;
    }

    private boolean init() {
        sceneryMes = Mes.load("art\\scenery\\scenery.mes");
        interfaceMes = Mes.load("art\\interface\\interface.mes");
        containerMes = Mes.load("art\\container\\container.mes");
        monsterMes = Mes.load("art\\monster\\monster.mes");
        uniqueNpcMes = Mes.load("art\\unique_npc\\unique_npc.mes");
        eyeCandyMes = Mes.load("art\\eye_candy\\eye_candy.mes");
        TileNames tileNames = TileNames.load();
        tileResolver = tileNames != null ? new TileArtResolver(tileNames) : null;
        // The C engine requires all to load; we accept whatever is present so the
        // resolver still works for the types whose tables are available.
        initialized = interfaceMes != Mes.INVALID_HANDLE
                || sceneryMes != Mes.INVALID_HANDLE;
        return initialized;
    }

    @Override
    public String resolve(int aid) {
        if (!initialized) {
            return null;
        }
        switch (ArtId.type(aid)) {
            case ArtId.TYPE_TILE:
                return tileResolver != null ? tileResolver.resolve(aid) : null;
            case ArtId.TYPE_CRITTER:
                return critterPath(aid);
            case ArtId.TYPE_SCENERY: {
                int num = 1000 * ArtId.sceneryType(aid) + ArtId.num(aid);
                String s = Mes.find(sceneryMes, num);
                return s != null ? "art\\scenery\\" + s : null;
            }
            case ArtId.TYPE_INTERFACE: {
                String s = Mes.find(interfaceMes, ArtId.num(aid));
                return s != null ? "art\\interface\\" + s : null;
            }
            case ArtId.TYPE_CONTAINER: {
                int num = 1000 * ArtId.containerType(aid) + ArtId.num(aid);
                String s = Mes.find(containerMes, num);
                return s != null ? "art\\container\\" + s : null;
            }
            case ArtId.TYPE_MONSTER:
                return resolveMonster(aid);
            case ArtId.TYPE_UNIQUE_NPC:
                return resolveUniqueNpc(aid);
            case ArtId.TYPE_EYE_CANDY: {
                String s = Mes.find(eyeCandyMes, ArtId.num(aid));
                if (s == null) {
                    return null;
                }
                return "art\\eye_candy\\" + s + "_"
                        + EYE_CANDY_TYPE_CODES[ArtId.eyeCandyType(aid)] + ".art";
            }
            default:
                return null;   // tile/wall/portal/item/light/roof/facade: TODO
        }
    }

    /** Critter path building (pure; no .mes data needed). Null if armor invalid. */
    public static String critterPath(int aid) {
        int armor = ArtId.critterArmor(aid);
        if (armor >= ARMOR_TYPE_COUNT) {
            return null;
        }
        int bodyType = ArtId.critterBodyType(aid);
        char genderCode;
        if (armor == ARMOR_PLATE || armor == ARMOR_PLATE_CLASSIC) {
            genderCode = GENDER_CODES[2];
        } else {
            genderCode = GENDER_CODES[ArtId.critterGender(aid)];
        }
        int anim = ArtId.anim(aid);
        String bodyTypeStr = BODY_TYPE_STRS[bodyType];
        String armorStr;
        if (anim == ANIM_EXPLODE) {
            armorStr = "XX";
            genderCode = GENDER_CODES[2];
        } else {
            armorStr = ARMOR_TYPE_STRS[armor];
        }
        int shield = ArtId.critterShield(aid);
        char shieldCode = SHIELDING_CODES[shield];
        int weapon = ArtId.critterWeapon(aid);
        char weaponCode = (weapon == WEAPON_TWO_HANDED_SWORD && shield == 1)
                ? WEAPON_TYPE_CODES[WEAPON_SWORD] : WEAPON_TYPE_CODES[weapon];
        return String.format("art\\critter\\%s%c\\%s%c%s%c%c%c.art",
                bodyTypeStr, genderCode, bodyTypeStr, genderCode, armorStr,
                shieldCode, weaponCode, (char) ('a' + anim));
    }

    private String resolveMonster(int aid) {
        String name = Mes.find(monsterMes, ArtId.monsterSpecie(aid));
        if (name == null) {
            return null;
        }
        int anim = ArtId.anim(aid);
        int armor = ArtId.critterArmor(aid);
        String armorStr = anim != ANIM_EXPLODE ? ARMOR_TYPE_STRS[armor] : "XX";
        int shield = ArtId.critterShield(aid);
        char shieldCode = SHIELDING_CODES[shield];
        int weapon = ArtId.critterWeapon(aid);
        char weaponCode = (weapon == WEAPON_TWO_HANDED_SWORD && shield == 1)
                ? WEAPON_TYPE_CODES[WEAPON_SWORD] : WEAPON_TYPE_CODES[weapon];
        return String.format("art\\monster\\%s\\%s%s%c%c%c.art",
                name, name, armorStr, shieldCode, weaponCode, (char) ('a' + anim));
    }

    private String resolveUniqueNpc(int aid) {
        String name = Mes.find(uniqueNpcMes, ArtId.num(aid));
        if (name == null) {
            return null;
        }
        int anim = ArtId.anim(aid);
        int shield = ArtId.critterShield(aid);
        char shieldCode = SHIELDING_CODES[shield];
        int weapon = ArtId.critterWeapon(aid);
        char weaponCode = (weapon == WEAPON_TWO_HANDED_SWORD && shield == 1)
                ? WEAPON_TYPE_CODES[WEAPON_SWORD] : WEAPON_TYPE_CODES[weapon];
        return String.format("art\\unique_npc\\%s\\%s%c%c%c.art",
                name, name, shieldCode, weaponCode, (char) ('a' + anim));
    }
}
