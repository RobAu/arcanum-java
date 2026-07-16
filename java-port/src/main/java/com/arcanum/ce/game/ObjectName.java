package com.arcanum.ce.game;

/**
 * An object's display name — the port of {@code object_examine}
 * ({@code object.c:3934}), which is what the game shows the player when they
 * examine or roll over an object.
 *
 * <pre>
 * void object_examine(int64_t obj, int64_t pc_obj, char* buffer)
 * {
 *     type = obj_field_int32_get(obj, OBJ_F_TYPE);
 *     if (type == OBJ_TYPE_KEY) {                       // gamekey.mes
 *         name = key_description_get(obj_field_int32_get(obj, OBJ_F_KEY_KEY_ID));
 *         ... return;
 *     }
 *     if (obj_type_is_item(type) &amp;&amp; !object_editor &amp;&amp; !item_is_identified(obj)) {
 *         name = description_get(obj_field_int32_get(obj, OBJ_F_ITEM_DESCRIPTION_UNKNOWN));
 *         ... return;
 *     }
 *     if (type == OBJ_TYPE_PC) {                        // a stored string
 *         obj_field_string_get(obj, OBJ_F_PC_PLAYER_NAME, &amp;player_name);
 *         ... return;
 *     }
 *     if (type == OBJ_TYPE_NPC) {                       // known vs unknown
 *         name = description_get(critter_description_get(obj, pc_obj));
 *         ... return;
 *     }
 *     name = description_get(obj_field_int32_get(obj, OBJ_F_DESCRIPTION));
 * }
 * </pre>
 *
 * <p><b>The display name comes from {@code OBJ_F_DESCRIPTION}, not
 * {@code OBJ_F_NAME}.</b> {@code OBJ_F_NAME} is an internal name number resolved
 * through {@code oemes\oname.mes} ({@link OName}, {@code obj.c:1703}); the
 * player-facing string is a description number resolved through
 * {@code mes\description.mes} ({@link Description}). Both are exposed here.
 *
 * <p>Every field read goes through the prototype fallback
 * ({@code obj_field_fetch}) — most instances override neither their description
 * nor their name.
 *
 * <h2>Approximations</h2>
 * <ul>
 * <li><b>Item identification.</b> {@code item_is_identified} needs
 *     {@code OBJ_F_ITEM_FLAGS}/magic state that is not ported; we take the
 *     identified branch (the plain {@code OBJ_F_DESCRIPTION}), which is what the
 *     C also does under {@code object_editor}. {@link #unknownDescription} exposes
 *     the unidentified string separately.</li>
 * <li><b>NPC acquaintance.</b> {@code critter_description_get(a, b)} returns the
 *     known description iff {@code a == b || reaction_met_before(a, b)}; with no
 *     PC object and no reaction system, {@code pc_obj} is null, which takes the C's
 *     own {@code else} branch — {@code OBJ_F_CRITTER_DESCRIPTION_UNKNOWN}, the
 *     "Man"/"Woman" placeholder a stranger sees. {@link #name} therefore reports
 *     the stranger's view by default, faithfully; {@link #knownName} gives the
 *     met-before string.</li>
 * </ul>
 */
public final class ObjectName {

    private final Description descriptions;
    private final OName onames;
    private final ProtoStore protos;

    public ObjectName(Description descriptions, OName onames, ProtoStore protos) {
        this.descriptions = descriptions;
        this.onames = onames;
        this.protos = protos;
    }

    /** Build from the cached tables; null if {@code mes\description.mes} is missing. */
    public static ObjectName get(ProtoStore protos) {
        Description d = Description.get();
        if (d == null) {
            return null;
        }
        return new ObjectName(d, OName.get(), protos);
    }

    /**
     * {@code object_examine(obj, OBJ_HANDLE_NULL, buffer)} — the name a stranger
     * sees. Null if no name resolves.
     */
    public String name(GameObject o) {
        if (o.type == ObjectFields.OBJ_TYPE_KEY) {
            return descriptions.keyDescription(
                    o.resolvedInt(ObjectFields.OBJ_F_KEY_KEY_ID, protos));
        }
        if (o.type == ObjectFields.OBJ_TYPE_PC) {
            Object s = o.resolved(ObjectFields.OBJ_F_PC_PLAYER_NAME, protos);
            return s instanceof String ? (String) s : null;
        }
        if (o.type == ObjectFields.OBJ_TYPE_NPC) {
            // critter_description_get(a, b) with b == OBJ_HANDLE_NULL: neither
            // a == b nor reaction_met_before holds, so the C takes the unknown branch.
            return descriptions.describe(
                    o.resolvedInt(ObjectFields.OBJ_F_CRITTER_DESCRIPTION_UNKNOWN, protos));
        }
        return descriptions.describe(descriptionNum(o));
    }

    /**
     * The name once the PC has met this critter — {@code critter_description_get}'s
     * {@code reaction_met_before} branch, i.e. plain {@code OBJ_F_DESCRIPTION}. For
     * non-critters this is the same as {@link #name}.
     */
    public String knownName(GameObject o) {
        if (o.type == ObjectFields.OBJ_TYPE_NPC) {
            return descriptions.describe(descriptionNum(o));
        }
        return name(o);
    }

    /** The unidentified-item string ({@code OBJ_F_ITEM_DESCRIPTION_UNKNOWN}), or null. */
    public String unknownDescription(GameObject o) {
        return descriptions.describe(
                o.resolvedInt(ObjectFields.OBJ_F_ITEM_DESCRIPTION_UNKNOWN, protos));
    }

    /**
     * The internal object name ({@code OBJ_F_NAME} through {@code o_name_get} —
     * {@code oemes\oname.mes}), or null. Editor-facing, not what the player sees.
     */
    public String internalName(GameObject o) {
        if (onames == null) {
            return null;
        }
        return onames.name(nameNum(o));
    }

    /** The raw {@code OBJ_F_NAME} number, prototype-resolved. */
    public int nameNum(GameObject o) {
        return o.resolvedInt(ObjectFields.OBJ_F_NAME, protos);
    }

    /** The raw {@code OBJ_F_DESCRIPTION} number, prototype-resolved. */
    public int descriptionNum(GameObject o) {
        return o.resolvedInt(ObjectFields.OBJ_F_DESCRIPTION, protos);
    }
}
