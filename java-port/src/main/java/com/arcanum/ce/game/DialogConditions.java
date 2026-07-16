package com.arcanum.ce.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The dialog condition language — a port of {@code sub_4150D0}
 * ({@code dialog.c:1361}), the test that decides whether a PC response is offered.
 *
 * <p>A {@code .dlg} entry's fifth field is its condition string: {@code re62},
 * {@code ra 3}, {@code gf 15 1}, {@code $$500}, or several of those run together.
 * The engine ANDs them: the first one that fails hides the line.
 *
 * <h2>The grammar (there isn't much of one)</h2>
 * {@code sub_4150D0} is a hand-rolled scanner, not a parser:
 * <pre>
 * pch = a2;
 * while (*pch != '\0') {
 *     while (*pch != '\0' &amp;&amp; !SDL_isalpha(*pch) &amp;&amp; *pch != '$') pch++;
 *     if (*pch == '\0') break;
 *     code[0] = *pch++;
 *     if (*pch == '\0') break;
 *     code[1] = *pch++;
 *     value = atoi(pch);
 *     for (cond = 0; cond &lt; DIALOG_COND_COUNT; cond++)
 *         if (SDL_strcasecmp(off_5A06BC[cond], code) == 0) break;
 *     switch (cond) { ... }
 * }
 * return true;
 * </pre>
 * So: skip anything that is not a letter and not {@code '$'}; take the next
 * <b>two characters verbatim</b> as the code (the second is not checked at all);
 * {@code atoi} the rest for the value; look the code up case-insensitively. The
 * {@code '$'} escape hatch exists purely so {@code $$} (gold) can be a code.
 *
 * <p>Because the value is {@code atoi}'d from the position right after the code and
 * the scan then resumes from that same position, the digits are skipped on the next
 * pass as "not a letter" — which is why {@code re62ch10} tokenises cleanly without
 * any separator.
 *
 * <h2>The sign convention</h2>
 * Most cases are a signed threshold against a stat:
 * <pre>
 * if (value &lt; 0) { if (stat &gt; -value) return false; }   // value is a MAXIMUM
 * else           { if (stat &lt;  value) return false; }   // value is a MINIMUM
 * </pre>
 * {@code re62} means "reaction &ge; 62"; {@code re-61} means "reaction &le; 61".
 * Note this makes the two halves of such a pair exactly complementary, which is
 * how the data expresses "if he likes you / if he doesn't".
 *
 * <h2>The second value</h2>
 * Several codes take two numbers ({@code gf 15 1} = "global flag 15 is 1"). The
 * second is read by {@code sub_4167C0} (0x4167C0):
 * <pre>
 * while (SDL_isspace(*str)) str++;
 * while (SDL_isdigit(*str)) str++;
 * return atoi(str);
 * </pre>
 * <b>It only skips digits, never a sign.</b> So for {@code "tr -5 3"} the digit-skip
 * stops immediately on the {@code '-'} and the "second" value comes back as
 * {@code -5} — the same number as the first. That is a real quirk of the shipped
 * code, reproduced here rather than corrected; see {@link #secondValue}.
 *
 * <h2>An unknown code is false, not true</h2>
 * The {@code switch} ends with {@code default: return false;}, and {@code cond}
 * lands on {@code DIALOG_COND_COUNT} when the lookup misses. A code the table does
 * not contain therefore <b>hides the line</b>. That is the C's behaviour and is
 * kept; {@link Result#unknownCode} reports it so bad data is visible rather than
 * silently swallowed.
 *
 * <h2>What we can actually evaluate</h2>
 * Most codes read PC state — stats, skills, gold, quests, inventory, global flags —
 * and there is no PC object yet ({@link Player} is a sprite, not an
 * {@code OBJ_TYPE_PC}). Each code is therefore classified (see {@link Support}):
 *
 * <ul>
 *   <li><b>{@link Support#REAL}</b> — evaluated exactly as the C does.
 *       {@code re} (via {@link Reaction}, whose no-PC answer of 50 is the engine's
 *       own), {@code lf}/{@code lc} (the NPC's own {@code SAP_DIALOG}
 *       {@link Script} header, which we parse), {@code wa}/{@code wt}
 *       ({@code OBJ_F_NPC_FLAGS} on the NPC), and {@code me} (whose no-PC path is
 *       likewise defined).</li>
 *   <li><b>{@link Support#DEFERRED}</b> — needs state we do not have. These
 *       <b>pass</b>, preserving today's behaviour so nothing regresses, and are
 *       recorded in {@link Result#deferred} so a tool can count them. They are never
 *       silently pretended-to-be-evaluated.</li>
 * </ul>
 *
 * <p>This class is conditions only. Dialog <i>actions</i> ({@code sub_415BA0} /
 * {@code off_5A0750}) mutate game state and are a separate job.
 */
public final class DialogConditions {

    /** How faithfully a code can be answered right now. */
    public enum Support {
        /** Evaluated exactly as {@code sub_4150D0} does. */
        REAL,
        /** Needs state we do not have; passes, and is counted. */
        DEFERRED
    }

    /**
     * {@code DialogCondition} ({@code dialog.c:64}), in enum order. The text codes
     * are {@code off_5A06BC} ({@code dialog.c:275}) and line up index-for-index.
     */
    public enum Code {
        PS("ps", Support.DEFERRED, "basic_skill_level(pc, PERSUATION)"),
        CH("ch", Support.DEFERRED, "stat_level_get(pc, CHARISMA)"),
        PE("pe", Support.DEFERRED, "stat_level_get(pc, PERCEPTION)"),
        AL("al", Support.DEFERRED, "stat_level_get(pc, ALIGNMENT)"),
        MA("ma", Support.DEFERRED, "stat_level_get(pc, MAGICK_TECH_APTITUDE)"),
        TA("ta", Support.DEFERRED, "-stat_level_get(pc, MAGICK_TECH_APTITUDE)"),
        GV("gv", Support.DEFERRED, "script_global_var_get"),
        GF("gf", Support.DEFERRED, "script_global_flag_get"),
        QU("qu", Support.DEFERRED, "quest_state_get(pc, n)"),
        RE("re", Support.REAL, "reaction_get(npc, pc)"),
        GOLD("$$", Support.DEFERRED, "item_gold_get(pc) + followers"),
        IN("in", Support.DEFERRED, "item_find_by_name in pc/npc inventory"),
        HA("ha", Support.DEFERRED, "basic_skill_level(pc, HAGGLE)"),
        LF("lf", Support.REAL, "script_local_flag_get(npc, SAP_DIALOG, n)"),
        LC("lc", Support.REAL, "script_local_counter_get(npc, SAP_DIALOG, n)"),
        TR("tr", Support.DEFERRED, "basic/tech_skill_training_get(pc, n)"),
        SK("sk", Support.DEFERRED, "basic/tech_skill_level(pc, n)"),
        RU("ru", Support.DEFERRED, "rumor_known_get(pc, n)"),
        RQ("rq", Support.DEFERRED, "rumor_qstate_get(n)"),
        FO("fo", Support.DEFERRED, "critter_leader_get(npc) == pc"),
        LE("le", Support.DEFERRED, "-stat_level_get(pc, LEVEL)"),
        QB("qb", Support.DEFERRED, "quest_state_get(pc, n) <= m"),
        ME("me", Support.REAL, "reaction_met_before(npc, pc)"),
        NI("ni", Support.DEFERRED, "item_find_by_name, negated"),
        QA("qa", Support.DEFERRED, "quest_state_get(pc, n) >= m"),
        RA("ra", Support.DEFERRED, "stat_level_get(pc, RACE) + 1"),
        PA("pa", Support.DEFERRED, "a named, un-mind-controlled follower"),
        SS("ss", Support.DEFERRED, "script_story_state_get()"),
        WA("wa", Support.REAL, "OBJ_F_NPC_FLAGS & ONF_AI_WAIT_HERE"),
        WT("wt", Support.REAL, "OBJ_F_NPC_FLAGS & ONF_JILTED"),
        PV("pv", Support.DEFERRED, "script_pc_var_get(pc, n)"),
        PF("pf", Support.DEFERRED, "script_pc_flag_get(pc, n)"),
        NA("na", Support.DEFERRED, "stat_level_get(pc, ALIGNMENT), inverted signs"),
        AR("ar", Support.DEFERRED, "area_is_known(pc, n)"),
        RP("rp", Support.DEFERRED, "reputation_has(pc, n)"),
        IA("ia", Support.DEFERRED, "area_of_object(pc)"),
        SC("sc", Support.DEFERRED, "spell_college_level_get(pc, n)");

        /** The two-character code as it appears in {@code .dlg} data. */
        public final String text;
        public final Support support;
        /** What the C reads for this code — why it is REAL or DEFERRED. */
        public final String reads;

        Code(String text, Support support, String reads) {
            this.text = text;
            this.support = support;
            this.reads = reads;
        }

        /** {@code off_5A06BC} lookup: {@code SDL_strcasecmp}, or null when absent. */
        public static Code find(String code) {
            for (Code c : values()) {
                if (c.text.equalsIgnoreCase(code)) {
                    return c;
                }
            }
            return null;
        }
    }

    /** obj_flags.h: {@code ONF_AI_WAIT_HERE}. */
    private static final int ONF_AI_WAIT_HERE = 0x00000008;
    /** obj_flags.h: {@code ONF_JILTED}. */
    private static final int ONF_JILTED = 0x00000020;

    /**
     * One scanned condition. {@link #rest} is the text from just after the code to
     * the end of the string — the C's {@code pch} at the point the {@code switch}
     * runs, which is what {@code atoi} and {@code sub_4167C0} are both handed.
     */
    public static final class Token {
        /** The two characters taken verbatim, e.g. {@code "re"} or {@code "$$"}. */
        public final String code;
        /** {@code atoi(pch)} — the first value. */
        public final int value;
        /** The text following the code. */
        public final String rest;

        Token(String code, int value, String rest) {
            this.code = code;
            this.value = value;
            this.rest = rest;
        }

        /** {@code sub_4167C0(pch)} — the second value, digit-skip quirk included. */
        public int secondValue() {
            return DialogConditions.secondValue(rest);
        }

        /** How this reads back in the data, e.g. {@code "re-61"}. */
        @Override
        public String toString() {
            return code + value;
        }
    }

    /** The outcome of evaluating a whole condition string. */
    public static final class Result {
        /** Whether the response is offered. */
        public final boolean pass;
        /** The token that returned false, or null when {@link #pass}. */
        public final Token failed;
        /** True when {@link #failed} failed because its code is not in the table. */
        public final boolean unknownCode;
        /** Codes that could not really be evaluated and were passed instead. */
        public final List<Token> deferred;

        Result(boolean pass, Token failed, boolean unknownCode, List<Token> deferred) {
            this.pass = pass;
            this.failed = failed;
            this.unknownCode = unknownCode;
            this.deferred = deferred;
        }

        /** Why the line was hidden, for a diagnostic; null when {@link #pass}. */
        public String reason() {
            if (pass) {
                return null;
            }
            return unknownCode ? "unknown code '" + failed.code + "'"
                               : "condition " + failed;
        }
    }

    /**
     * What {@code sub_4150D0} is handed: {@code DialogState}'s {@code npc_obj} and
     * {@code pc_obj}, plus the {@link ProtoStore} our field reads go through
     * (most object fields are inherited, so an instance read alone is wrong).
     */
    public static final class Context {
        /** {@code DialogState.npc_obj} — the critter being talked to. */
        public final GameObject npc;
        /**
         * {@code DialogState.pc_obj} — null until a real {@code OBJ_TYPE_PC}
         * exists. Every DEFERRED code is deferred because of this.
         */
        public final GameObject pc;
        public final ProtoStore protos;

        public Context(GameObject npc, GameObject pc, ProtoStore protos) {
            this.npc = npc;
            this.pc = pc;
            this.protos = protos;
        }
    }

    private DialogConditions() {
    }

    /** {@code sub_4150D0} — true when the response should be offered. */
    public static boolean test(Context ctx, String conditions) {
        return evaluate(ctx, conditions).pass;
    }

    /** {@link #test}, but reporting which token failed and what was deferred. */
    public static Result evaluate(Context ctx, String conditions) {
        List<Token> deferred = new ArrayList<>();
        // if (a2 == NULL || a2[0] == '\0') return true;
        if (conditions == null || conditions.isEmpty()) {
            return new Result(true, null, false, deferred);
        }
        for (Token t : tokenize(conditions)) {
            Code code = Code.find(t.code);
            if (code == null) {
                // switch's `default: return false` -- cond == DIALOG_COND_COUNT.
                return new Result(false, t, true, deferred);
            }
            if (code.support == Support.DEFERRED) {
                deferred.add(t);
                continue;               // pass: today's behaviour, but counted
            }
            if (!real(ctx, code, t)) {
                return new Result(false, t, false, deferred);
            }
        }
        return new Result(true, null, false, deferred);
    }

    /** The cases we can answer exactly. Each mirrors its arm of the C's switch. */
    private static boolean real(Context ctx, Code code, Token t) {
        int value = t.value;
        switch (code) {
        case RE:
            // if (value < 0) { if (reaction_get(npc, pc) > -value) return false; }
            // else           { if (reaction_get(npc, pc) <  value) return false; }
            return threshold(Reaction.get(ctx.npc, ctx.pc, ctx.protos), value);

        case LF:
            // if (script_local_flag_get(npc, SAP_DIALOG, value)
            //         != sub_4167C0(pch)) return false;
            return bool(localFlag(ctx, value)) == t.secondValue();

        case LC:
            // if (script_local_counter_get(npc, SAP_DIALOG, value)
            //         != sub_4167C0(pch)) return false;
            return localCounter(ctx, value) == t.secondValue();

        case ME:
            // if (value == 0) { if ( reaction_met_before(npc, pc)) return false; }
            // else if (value == 1) { if (!reaction_met_before(npc, pc)) return false; }
            // (any other value: no test at all)
            if (value == 0) {
                return !metBefore(ctx);
            }
            if (value == 1) {
                return metBefore(ctx);
            }
            return true;

        case WA:
            return npcFlag(ctx, ONF_AI_WAIT_HERE, value);

        case WT:
            return npcFlag(ctx, ONF_JILTED, value);

        default:
            throw new IllegalStateException("code " + code + " is marked REAL but "
                    + "has no implementation");
        }
    }

    /**
     * The recurring shape:
     * <pre>
     * if (value &lt; 0) { if (stat &gt; -value) return false; }
     * else           { if (stat &lt;  value) return false; }
     * </pre>
     * A negative {@code value} is a maximum, a non-negative one a minimum.
     */
    static boolean threshold(int stat, int value) {
        return value < 0 ? stat <= -value : stat >= value;
    }

    /**
     * {@code reaction_met_before(npc_obj, pc_obj)} ({@code reaction.c}):
     * <pre>
     * if (npc_obj == pc_obj) return true;
     * if (obj_field_int32_get(pc_obj,  OBJ_F_TYPE) != OBJ_TYPE_PC)  return false;
     * if (obj_field_int32_get(npc_obj, OBJ_F_TYPE) != OBJ_TYPE_NPC) return false;
     * return sub_4C12F0(npc_obj, pc_obj, false, &amp;v1);
     * </pre>
     * With no PC object the second guard answers it: <b>false</b>, never met. Like
     * {@link Reaction}'s 50, that is the engine's own value for this input, not a
     * guess. The {@code sub_4C12F0} path (the NPC's stored PC-met list) is not
     * ported and is unreachable while {@code pc} is null.
     */
    private static boolean metBefore(Context ctx) {
        if (ctx.pc == null) {
            return false;               // npc != pc, and pc is not an OBJ_TYPE_PC
        }
        throw new UnsupportedOperationException(
                "reaction_met_before's sub_4C12F0 path is not ported");
    }

    /**
     * {@code script_local_flag_get(obj, index, flag)} ({@code script.c}):
     * <pre>
     * obj_arrayfield_script_get(obj, OBJ_F_SCRIPTS_IDX, index, &amp;scr);
     * return scr.num != 0 ? (scr.hdr.flags &amp; (1 &lt;&lt; flag)) != 0 : false;
     * </pre>
     * We parse {@code OBJ_F_SCRIPTS_IDX}, so this is exact. {@link GameObject#script}
     * already collapses "key absent" and "num == 0" to null, which is the same
     * {@code scr.num != 0} test.
     */
    private static boolean localFlag(Context ctx, int flag) {
        Script scr = dialogScript(ctx);
        return scr != null && (scr.flags & (1 << flag)) != 0;
    }

    /**
     * {@code script_local_counter_get(obj, index, counter)} ({@code script.c}):
     * <pre>
     * obj_arrayfield_script_get(obj, OBJ_F_SCRIPTS_IDX, index, &amp;scr);
     * return scr.num != 0 ? (scr.hdr.counters &gt;&gt; (8 * counter)) : 0;
     * </pre>
     * <b>Note the missing mask.</b> {@code counters} packs four bytes but the shift
     * is not followed by {@code &amp; 0xFF}, so counter 0 returns all four bytes and
     * counter 1 the upper three. That is what shipped; it is reproduced verbatim.
     * {@code counters} is {@code unsigned int}, hence {@code >>>}. The shift count is
     * unmasked in C too — on x86 a shift is taken mod 32, which is exactly what
     * Java's {@code >>>} does, so oversized counters agree with the retail build.
     */
    private static int localCounter(Context ctx, int counter) {
        Script scr = dialogScript(ctx);
        return scr != null ? scr.counters >>> (8 * counter) : 0;
    }

    private static Script dialogScript(Context ctx) {
        return ctx.npc == null ? null : ctx.npc.script(Sap.DIALOG, ctx.protos);
    }

    /**
     * The shared shape of {@code wa} and {@code wt}:
     * <pre>
     * if ((obj_field_int32_get(npc, OBJ_F_NPC_FLAGS) &amp; FLAG) != 0) {
     *     if (value == 0) return false;
     * } else {
     *     if (value == 1) return false;
     * }
     * </pre>
     * So {@code value 1} requires the flag set, {@code value 0} requires it clear,
     * and any other value tests nothing.
     */
    private static boolean npcFlag(Context ctx, int mask, int value) {
        if (ctx.npc == null) {
            return true;
        }
        boolean set = (ctx.npc.resolvedInt(ObjectFields.OBJ_F_NPC_FLAGS, ctx.protos)
                & mask) != 0;
        return set ? value != 0 : value != 1;
    }

    /** C bool -> int, for the {@code != sub_4167C0(pch)} comparisons. */
    private static int bool(boolean b) {
        return b ? 1 : 0;
    }

    // -- the scanner --------------------------------------------------------

    /**
     * The tokeniser from {@code sub_4150D0}'s loop. Exposed because it is the part
     * most worth testing on its own: it is shared verbatim with {@code sub_415BA0}
     * (actions), and every subtlety in this file lives here.
     */
    public static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        int p = 0;
        int n = s.length();
        while (p < n) {
            // while (*pch && !SDL_isalpha(*pch) && *pch != '$') pch++;
            while (p < n && !isAlpha(s.charAt(p)) && s.charAt(p) != '$') {
                p++;
            }
            if (p >= n) {
                break;
            }
            char c0 = s.charAt(p++);
            if (p >= n) {
                break;              // "if (*pch == '\0') break;" -- a lone trailing char
            }
            char c1 = s.charAt(p++);
            String rest = s.substring(p);
            out.add(new Token(new String(new char[] {c0, c1}), atoi(rest), rest));
        }
        return out;
    }

    /**
     * {@code sub_4167C0} — the second number in a two-value condition:
     * <pre>
     * while (SDL_isspace(*str)) str++;
     * while (SDL_isdigit(*str)) str++;
     * return atoi(str);
     * </pre>
     * The digit-skip is what steps over the first value. It does not skip a sign, so
     * a negative first value stops it dead and the first value is returned again —
     * see the class docs.
     */
    static int secondValue(String s) {
        if (s == null) {
            return 0;
        }
        int p = 0;
        int n = s.length();
        while (p < n && isSpace(s.charAt(p))) {
            p++;
        }
        while (p < n && isDigit(s.charAt(p))) {
            p++;
        }
        return atoi(s.substring(p));
    }

    /** C {@code atoi} — shared with {@link DialogFile}'s parser. */
    private static int atoi(String s) {
        return DialogFile.atoi(s);
    }

    // SDL's ctype macros are ASCII/C-locale; Character.isLetter etc. are Unicode-wide
    // and would classify high-byte characters in dialog text differently.
    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B
                || c == '\f' || c == '\r';
    }

    // -- reporting ----------------------------------------------------------

    /**
     * Tally deferred codes across many evaluations, so a tool can state exactly how
     * much of a file we are guessing at. Keyed by code text, in first-seen order.
     */
    public static final class DeferredTally {
        private final Map<String, Integer> counts = new LinkedHashMap<>();

        public void add(Result r) {
            for (Token t : r.deferred) {
                counts.merge(t.code.toLowerCase(), 1, Integer::sum);
            }
        }

        /** Code text -> how many times it was skipped. */
        public Map<String, Integer> counts() {
            return counts;
        }

        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        public boolean isEmpty() {
            return counts.isEmpty();
        }
    }
}
