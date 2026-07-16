package com.arcanum.ce.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which PC responses an NPC line actually offers — a port of {@code sub_414F50}
 * ({@code dialog.c:1309}), the engine's option filter:
 *
 * <pre>
 * gender = stat_level_get(a1-&gt;pc_obj, STAT_GENDER);
 * intelligence = stat_level_get(a1-&gt;pc_obj, STAT_INTELLIGENCE);
 * if (intelligence &gt; LOW_INTELLIGENCE &amp;&amp; critter_is_dumb(a1-&gt;pc_obj)) intelligence = 1;
 * ...
 * for (idx = (int)(entry - dialog-&gt;entries) + 1;
 *      idx &lt; dialog-&gt;entries_length &amp;&amp; cnt &lt; 5;
 *      idx++) {
 *     entry = &amp;(dialog-&gt;entries[idx]);
 *     if (entry-&gt;iq == 0) return cnt;
 *     if (entry-&gt;data.gender == -1 || entry-&gt;data.gender == gender) {
 *         if ((entry-&gt;iq &lt; 0 &amp;&amp; intelligence &lt;= -entry-&gt;iq)
 *             || (entry-&gt;iq &gt;= 0 &amp;&amp; intelligence &gt;= entry-&gt;iq)) {
 *             if (entry-&gt;conditions == NULL || sub_4150D0(a1, entry-&gt;conditions)) {
 *                 a2[cnt++] = entry-&gt;num;
 *             }
 *         }
 *     }
 * }
 * return cnt;
 * </pre>
 *
 * Four things happen there, and all four matter:
 * <ol>
 *   <li><b>{@code iq == 0} ends the run.</b> A zero IQ marks an NPC line, so the
 *       responses to a line are exactly the consecutive PC entries after it —
 *       {@link DialogFile#responsesTo}.</li>
 *   <li><b>Gender.</b> {@code -1} (a blank field) means "either"; otherwise it must
 *       equal the PC's, with {@code GENDER_FEMALE = 0, GENDER_MALE = 1}
 *       ({@code stat.h:41}).</li>
 *   <li><b>Intelligence.</b> A negative {@code iq} is a <i>maximum</i> — those are
 *       the dumb-PC lines; a non-negative one is a minimum.</li>
 *   <li><b>{@link DialogConditions}</b>, the condition string.</li>
 * </ol>
 * and the loop stops at <b>{@value #MAX_OPTIONS}</b> accepted options, because the
 * interface bar has five slots. Entries past that are never examined at all.
 *
 * <p>Evaluating produces a verdict per response ({@link Option}) rather than just the
 * survivors, so a diagnostic can say <i>why</i> a line was dropped. {@link #offered}
 * is the plain list the UI wants.
 *
 * <p><b>Not ported:</b> the {@code critter_is_dumb} clamp above the loop, which drops
 * intelligence to 1 for a PC with a low-IQ background. It reads PC stats that do not
 * exist yet; the caller supplies intelligence directly.
 */
public final class DialogOptions {

    /** The engine's option cap — {@code cnt < 5} in {@code sub_414F50}'s loop. */
    public static final int MAX_OPTIONS = 5;

    /** {@code stat.h:41} — {@code GENDER_FEMALE}. */
    public static final int GENDER_FEMALE = 0;
    /** {@code stat.h:41} — {@code GENDER_MALE}. */
    public static final int GENDER_MALE = 1;

    /** {@code entry->data.gender} when the field is blank: "either gender". */
    public static final int GENDER_ANY = -1;

    /** Why a response was not offered, in the order {@code sub_414F50} tests them. */
    public enum Reject {
        /** Offered. */
        NONE,
        /** {@code entry->data.gender} is set and is not the PC's. */
        GENDER,
        /** The {@code iq} threshold. */
        IQ,
        /** {@code sub_4150D0} returned false. */
        CONDITION,
        /** Five options were already accepted, so the loop never reached this. */
        CAP
    }

    /** One response and what the filter decided about it. */
    public static final class Option {
        public final DialogFile.Entry entry;
        public final Reject reject;
        /** The condition verdict, or null when the filter stopped before it. */
        public final DialogConditions.Result condition;

        Option(DialogFile.Entry entry, Reject reject, DialogConditions.Result condition) {
            this.entry = entry;
            this.reject = reject;
            this.condition = condition;
        }

        public boolean offered() {
            return reject == Reject.NONE;
        }

        /** Short human-readable cause, or null when offered. */
        public String reason() {
            switch (reject) {
            case NONE:
                return null;
            case GENDER:
                return "gender " + entry.gender;
            case IQ:
                return "iq " + entry.iq;
            case CONDITION:
                return condition.reason();
            case CAP:
                return "over the " + MAX_OPTIONS + "-option cap";
            default:
                throw new IllegalStateException(String.valueOf(reject));
            }
        }
    }

    private DialogOptions() {
    }

    /**
     * {@code sub_414F50} for one NPC line, with a verdict per response.
     *
     * @param dlg          the file {@code npcLine} came from
     * @param npcLine      the NPC line whose responses to filter
     * @param ctx          the condition context (NPC object, PC object, protos)
     * @param intelligence the PC's {@code STAT_INTELLIGENCE}
     * @param gender       the PC's {@code STAT_GENDER} ({@link #GENDER_FEMALE} /
     *                     {@link #GENDER_MALE})
     */
    public static List<Option> evaluate(DialogFile dlg, DialogFile.Entry npcLine,
                                        DialogConditions.Context ctx,
                                        int intelligence, int gender) {
        List<Option> out = new ArrayList<>();
        if (dlg == null || npcLine == null) {
            return out;
        }
        int cnt = 0;
        for (DialogFile.Entry e : dlg.responsesTo(npcLine)) {
            if (cnt >= MAX_OPTIONS) {
                // The C's loop condition, not a body test: these are never looked at.
                out.add(new Option(e, Reject.CAP, null));
                continue;
            }
            if (!(e.gender == GENDER_ANY || e.gender == gender)) {
                out.add(new Option(e, Reject.GENDER, null));
                continue;
            }
            if (!passesIq(e.iq, intelligence)) {
                out.add(new Option(e, Reject.IQ, null));
                continue;
            }
            DialogConditions.Result r = DialogConditions.evaluate(ctx, e.conditions);
            if (!r.pass) {
                out.add(new Option(e, Reject.CONDITION, r));
                continue;
            }
            out.add(new Option(e, Reject.NONE, r));
            cnt++;
        }
        return out;
    }

    /** The responses actually offered — {@link #evaluate}, survivors only. */
    public static List<DialogFile.Entry> offered(DialogFile dlg, DialogFile.Entry npcLine,
                                                 DialogConditions.Context ctx,
                                                 int intelligence, int gender) {
        List<DialogFile.Entry> out = new ArrayList<>();
        for (Option o : evaluate(dlg, npcLine, ctx, intelligence, gender)) {
            if (o.offered()) {
                out.add(o.entry);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * {@code (iq < 0 && intelligence <= -iq) || (iq >= 0 && intelligence >= iq)} —
     * a negative threshold is a maximum, a non-negative one a minimum.
     */
    public static boolean passesIq(int iq, int intelligence) {
        return (iq < 0 && intelligence <= -iq) || (iq >= 0 && intelligence >= iq);
    }
}
