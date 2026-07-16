package com.arcanum.ce.game;

/**
 * How an NPC feels about the player, ported from {@code reaction.c}.
 *
 * <p>{@code reaction_get} (0x4C0CC0) is a one-line wrapper:
 * <pre>
 * int reaction_get(int64_t npc_obj, int64_t pc_obj)
 * {
 *     return sub_4C0D00(npc_obj, pc_obj, 0);
 * }
 * </pre>
 * and {@code sub_4C0D00} (0x4C0D00) opens with three guards before it computes
 * anything:
 * <pre>
 * if (ai_shitlist_has(npc_obj, pc_obj)) return 0;
 * if (obj_field_int32_get(pc_obj,  OBJ_F_TYPE) != OBJ_TYPE_PC)  return 50;
 * if (obj_field_int32_get(npc_obj, OBJ_F_TYPE) != OBJ_TYPE_NPC) return 50;
 * value = sub_4C1500(npc_obj, pc_obj, flags) + sub_4C1290(npc_obj, pc_obj);
 * value = effect_adjust_reaction(npc_obj, value);
 * if (value &lt; 50
 *     &amp;&amp; sub_459040(npc_obj, OSF_MIND_CONTROLLED, &amp;mind_controlled_by_obj)
 *     &amp;&amp; mind_controlled_by_obj == pc_obj) {
 *     value = 50;
 * }
 * return value;
 * </pre>
 *
 * <p><b>We have no PC object.</b> {@link Player} is a sprite on the tile grid, not
 * a serialized {@code OBJ_TYPE_PC}. That is not a gap we have to paper over: the
 * second guard is the engine's own answer for exactly this input. A {@code pc_obj}
 * that is not an {@code OBJ_TYPE_PC} yields <b>50</b> — neutral — and 50 is what
 * every {@code re} dialog condition is therefore tested against today. It is the C's
 * value, not a placeholder.
 *
 * <p>The first guard still runs ahead of it, and is answerable too:
 * {@code ai_shitlist_has} walks {@code OBJ_F_NPC_SHIT_LIST_IDX} looking for a
 * handle equal to {@code pc_obj}. Our {@code pc_obj} is {@code OBJ_HANDLE_NULL},
 * and a stored shitlist holds real object handles, never null — so it cannot match
 * and the guard is false. See {@link #shitlistHas}.
 *
 * <p>What is <b>not</b> ported is the real computation: {@code sub_4C1500}
 * (charisma/race/alignment/reputation scoring), {@code sub_4C1290} (the
 * per-NPC stored reaction delta), {@code effect_adjust_reaction} and the
 * mind-control clamp. All of them read PC stats that do not exist yet. Rather than
 * invent a number, {@link #get} throws once a real PC object is handed to it — the
 * exception is a marker for whoever adds character creation, not a runtime hazard
 * today (nothing can currently construct that input).
 */
public final class Reaction {

    /** {@code sub_4C0D00}'s answer when either party is the wrong object type. */
    public static final int NEUTRAL = 50;

    /** {@code sub_4C0D00}'s answer when the PC is on the NPC's shitlist. */
    public static final int SHITLIST = 0;

    private Reaction() {
    }

    /**
     * {@code reaction_get(npc_obj, pc_obj)} — {@code sub_4C0D00(npc, pc, 0)}.
     *
     * @param npc    the NPC being talked to
     * @param pc     the PC object, or null while none exists (the current state)
     * @param protos for resolving fields through the prototype
     * @return the reaction value, 0..100
     * @throws UnsupportedOperationException when a real PC object reaches the
     *         scoring path, which is not ported (see the class docs)
     */
    public static int get(GameObject npc, GameObject pc, ProtoStore protos) {
        if (shitlistHas(npc, pc)) {
            return SHITLIST;
        }
        // Make sure `pc_obj` is actually PC. Ours is null, so: not a PC -> 50.
        if (pc == null || pc.type != ObjectFields.OBJ_TYPE_PC) {
            return NEUTRAL;
        }
        // Make sure `npc_obj` is actually NPC.
        if (npc == null || npc.type != ObjectFields.OBJ_TYPE_NPC) {
            return NEUTRAL;
        }
        throw new UnsupportedOperationException(
                "reaction scoring (sub_4C1500 + sub_4C1290 + effect_adjust_reaction) "
                + "is not ported; it needs PC stats that do not exist yet");
    }

    /**
     * {@code ai_shitlist_has(npc_obj, shit_obj)} ({@code ai.c}) for the only input
     * we can produce: a null {@code shit_obj}.
     *
     * <pre>
     * if (npc_obj != OBJ_HANDLE_NULL
     *     &amp;&amp; obj_field_int32_get(npc_obj, OBJ_F_TYPE) == OBJ_TYPE_NPC) {
     *     cnt = obj_arrayfield_length_get(npc_obj, OBJ_F_NPC_SHIT_LIST_IDX);
     *     for (idx = 0; idx &lt; cnt; idx++) {
     *         obj_arrayfield_obj_get(npc_obj, OBJ_F_NPC_SHIT_LIST_IDX, idx, &amp;obj);
     *         if (obj == shit_obj) return true;
     *     }
     * }
     * return false;
     * </pre>
     *
     * <p>The loop compares stored handles against {@code shit_obj}. With
     * {@code shit_obj == OBJ_HANDLE_NULL} no stored handle can equal it, so the
     * result is false without reading the array — which is fortunate, because
     * {@code OBJ_F_NPC_SHIT_LIST_IDX} is a HANDLE_ARRAY and {@link ObjReader} does
     * not decode those.
     */
    static boolean shitlistHas(GameObject npc, GameObject pc) {
        if (pc == null) {
            return false;       // OBJ_HANDLE_NULL is never in a stored shitlist
        }
        throw new UnsupportedOperationException(
                "ai_shitlist_has needs OBJ_F_NPC_SHIT_LIST_IDX, a HANDLE_ARRAY that "
                + "ObjReader does not decode");
    }
}
