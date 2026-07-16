package com.arcanum.ce.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins {@code sub_4150D0} ({@code dialog.c:1361}) — the dialog condition language.
 *
 * <p>The context here is the real one: no PC object, because none exists yet. That
 * is not a stub — {@code reaction_get}'s second guard makes 50 the engine's own
 * answer for a non-PC {@code pc_obj}, so these tests are exercising the same
 * arithmetic the game does.
 */
class DialogConditionsTest {

    /** DialogState with no pc_obj and no npc_obj — what the UI has today. */
    private static DialogConditions.Context ctx() {
        return new DialogConditions.Context(null, null, null);
    }

    // -- the tokeniser ------------------------------------------------------

    @Test
    void tokenisesASingleCodeAndValue() {
        List<DialogConditions.Token> t = DialogConditions.tokenize("re62");
        assertEquals(1, t.size());
        assertEquals("re", t.get(0).code);
        assertEquals(62, t.get(0).value);
    }

    @Test
    void tokenisesSeveralCodesFromOneString() {
        // The engine ANDs whatever it finds; commas and spaces are just skipped.
        List<DialogConditions.Token> t = DialogConditions.tokenize("lf15 1, gf1095 0, re62");
        assertEquals(3, t.size());
        assertEquals("lf", t.get(0).code);
        assertEquals(15, t.get(0).value);
        assertEquals(1, t.get(0).secondValue());
        assertEquals("gf", t.get(1).code);
        assertEquals(1095, t.get(1).value);
        assertEquals(0, t.get(1).secondValue());
        assertEquals("re", t.get(2).code);
        assertEquals(62, t.get(2).value);
    }

    @Test
    void needsNoSeparatorBetweenCodes() {
        // The value's digits are skipped on the next pass as "not a letter", so
        // codes butt straight up against the previous value.
        List<DialogConditions.Token> t = DialogConditions.tokenize("re62ch10");
        assertEquals(2, t.size());
        assertEquals("re", t.get(0).code);
        assertEquals(62, t.get(0).value);
        assertEquals("ch", t.get(1).code);
        assertEquals(10, t.get(1).value);
    }

    @Test
    void dollarStartsACodeSoGoldCanBeSpelled() {
        // The scan skips !isalpha && != '$'; '$' is whitelisted purely for "$$".
        List<DialogConditions.Token> t = DialogConditions.tokenize("$$500");
        assertEquals(1, t.size());
        assertEquals("$$", t.get(0).code);
        assertEquals(500, t.get(0).value);
        assertEquals(DialogConditions.Code.GOLD, DialogConditions.Code.find("$$"));
    }

    @Test
    void aSingleDollarSwallowsTheFirstDigitAsTheCode() {
        // The code is two characters, taken blind. "{$500}" -- which is what several
        // shipped .dlg files actually contain -- therefore reads as code "$5",
        // value atoi("00") == 0, and "$5" is not in off_5A06BC.
        List<DialogConditions.Token> t = DialogConditions.tokenize("$500");
        assertEquals(1, t.size());
        assertEquals("$5", t.get(0).code);
        assertEquals(0, t.get(0).value);
        assertNull(DialogConditions.Code.find("$5"));
    }

    @Test
    void codeLookupIsCaseInsensitive() {
        assertEquals(DialogConditions.Code.RE, DialogConditions.Code.find("RE"));
        assertEquals(DialogConditions.Code.RE, DialogConditions.Code.find("Re"));
    }

    @Test
    void tokeniserTakesTheSecondCharacterBlind() {
        // code[1] = *pch++ with no isalpha test: a digit is a perfectly good second
        // character, which is exactly how "$$" and the "$5" bug both work.
        List<DialogConditions.Token> t = DialogConditions.tokenize("a1b2");
        assertEquals(2, t.size());
        assertEquals("a1", t.get(0).code);
        assertEquals("b2", t.get(1).code);
    }

    @Test
    void aLoneTrailingCharacterIsDropped() {
        // "if (*pch == '\0') break;" between code[0] and code[1].
        List<DialogConditions.Token> t = DialogConditions.tokenize("re62c");
        assertEquals(1, t.size());
        assertEquals("re", t.get(0).code);
    }

    @Test
    void tokenisesNothingFromTextWithoutLettersOrDollar() {
        assertTrue(DialogConditions.tokenize("").isEmpty());
        assertTrue(DialogConditions.tokenize("   123, 456  ").isEmpty());
        assertTrue(DialogConditions.tokenize(null).isEmpty());
    }

    // -- atoi / sub_4167C0 edges --------------------------------------------

    @Test
    void valueIsAtoiOfWhateverFollowsTheCode() {
        assertEquals(0, DialogConditions.tokenize("re").get(0).value, "no digits -> 0");
        assertEquals(0, DialogConditions.tokenize("re foo").get(0).value, "not a number -> 0");
        assertEquals(62, DialogConditions.tokenize("re 62").get(0).value, "leading space ok");
        assertEquals(-61, DialogConditions.tokenize("re-61").get(0).value);
        assertEquals(-61, DialogConditions.tokenize("re -61").get(0).value);
        assertEquals(62, DialogConditions.tokenize("re62abc").get(0).value, "stops at non-digit");
    }

    @Test
    void secondValueSkipsSpacesThenTheFirstValuesDigits() {
        // sub_4167C0: isspace-skip, isdigit-skip, atoi.
        assertEquals(1, DialogConditions.tokenize("lf15 1").get(0).secondValue());
        assertEquals(0, DialogConditions.tokenize("gf1095 0").get(0).secondValue());
        assertEquals(-3, DialogConditions.tokenize("sk 5 -3").get(0).secondValue());
        assertEquals(0, DialogConditions.tokenize("lf15").get(0).secondValue(),
                "no second number -> 0");
    }

    @Test
    void secondValueRepeatsTheFirstWhenTheFirstIsNegative() {
        // The quirk: sub_4167C0's digit-skip does not skip a sign, so it stops dead
        // on '-' and atoi's the first value all over again. This is what shipped.
        DialogConditions.Token t = DialogConditions.tokenize("tr -5 3").get(0);
        assertEquals(-5, t.value);
        assertEquals(-5, t.secondValue(), "sub_4167C0 never skips the '-'");
    }

    // -- the sign convention ------------------------------------------------

    @Test
    void aNonNegativeValueIsAMinimum() {
        // if (stat < value) return false;
        assertTrue(DialogConditions.threshold(62, 62), "equal passes");
        assertTrue(DialogConditions.threshold(63, 62));
        assertFalse(DialogConditions.threshold(61, 62));
    }

    @Test
    void aNegativeValueIsAMaximum() {
        // if (stat > -value) return false;
        assertTrue(DialogConditions.threshold(61, -61), "equal passes");
        assertTrue(DialogConditions.threshold(60, -61));
        assertFalse(DialogConditions.threshold(62, -61));
    }

    @Test
    void zeroIsAMinimumNotAMaximum() {
        // value < 0 is false for 0, so it takes the "stat >= value" arm.
        assertTrue(DialogConditions.threshold(0, 0));
        assertTrue(DialogConditions.threshold(100, 0));
    }

    // -- evaluation ---------------------------------------------------------

    @Test
    void nullOrEmptyConditionsPass() {
        // "if (a2 == NULL || a2[0] == '\0') return true;"
        assertTrue(DialogConditions.test(ctx(), null));
        assertTrue(DialogConditions.test(ctx(), ""));
    }

    @Test
    void aConditionOfOnlyPunctuationPasses() {
        // Nothing tokenises, so the loop falls off the end -> true.
        assertTrue(DialogConditions.test(ctx(), "   "));
    }

    @Test
    void virgilsReactionPairIsMutuallyExclusiveAtFifty() {
        // The bug this whole port exists for. Virgil's opening offers each line
        // twice: once gated re62, once re-61. With no PC object reaction_get
        // returns 50, so exactly one of the pair survives -- never both, never
        // neither, whatever the reaction turns out to be.
        assertEquals(50, Reaction.get(null, null, null));

        boolean liked = DialogConditions.test(ctx(), "re62");
        boolean disliked = DialogConditions.test(ctx(), "re-61");

        assertFalse(liked, "reaction 50 is below the re62 floor");
        assertTrue(disliked, "reaction 50 is within the re-61 ceiling");
        assertTrue(liked ^ disliked, "the pair must be exactly complementary");
    }

    @Test
    void theReactionPairIsComplementaryAtEveryReaction() {
        // re62 / re-61 partition the range: the thresholds abut with no gap or
        // overlap, which is why the data can use them as if/else.
        for (int reaction = 0; reaction <= 100; reaction++) {
            boolean liked = DialogConditions.threshold(reaction, 62);
            boolean disliked = DialogConditions.threshold(reaction, -61);
            assertTrue(liked ^ disliked, "reaction " + reaction + " matched both or neither");
        }
    }

    @Test
    void conditionsAreAndedAndTheFirstFailureWins() {
        // re62 fails at 50; the ra that follows it must never be reached.
        DialogConditions.Result r = DialogConditions.evaluate(ctx(), "re62, ra 3");
        assertFalse(r.pass);
        assertEquals("re", r.failed.code);
        assertTrue(r.deferred.isEmpty(), "evaluation stops at the first failure");
    }

    @Test
    void anUnknownCodeHidesTheResponse() {
        // The switch's `default: return false`. Faithful, and the reason the
        // shipped "{$500}" lines never appear in the real game either.
        DialogConditions.Result r = DialogConditions.evaluate(ctx(), "$500");
        assertFalse(r.pass);
        assertTrue(r.unknownCode);
        assertEquals("$5", r.failed.code);
        assertNotNull(r.reason());
    }

    // -- honest deferral ----------------------------------------------------

    @Test
    void codesNeedingAPcAreDeferredNotFaked() {
        // They pass (today's behaviour, so nothing regresses) but are recorded --
        // never silently pretended-to-be-evaluated.
        DialogConditions.Result r = DialogConditions.evaluate(ctx(), "ra 3");
        assertTrue(r.pass);
        assertEquals(1, r.deferred.size());
        assertEquals("ra", r.deferred.get(0).code);
    }

    @Test
    void deferredCodesAreTalliedAcrossEvaluations() {
        DialogConditions.DeferredTally tally = new DialogConditions.DeferredTally();
        tally.add(DialogConditions.evaluate(ctx(), "ra 3"));
        tally.add(DialogConditions.evaluate(ctx(), "ra 4, ss 2"));
        assertEquals(3, tally.total());
        assertEquals(2, (int) tally.counts().get("ra"));
        assertEquals(1, (int) tally.counts().get("ss"));
    }

    @Test
    void everyCodeInTheTableIsClassified() {
        // off_5A06BC has 37 entries (DIALOG_COND_COUNT); each must be reachable by
        // its text and say whether we really evaluate it.
        assertEquals(37, DialogConditions.Code.values().length);
        for (DialogConditions.Code c : DialogConditions.Code.values()) {
            assertEquals(c, DialogConditions.Code.find(c.text), c.text);
            assertNotNull(c.support);
            assertFalse(c.reads.isEmpty());
        }
    }

    @Test
    void everyCodeMarkedRealActuallyEvaluates() {
        // A REAL code that fell through to the switch's "no implementation" throw
        // would be a lie in the docs; walk them all.
        for (DialogConditions.Code c : DialogConditions.Code.values()) {
            if (c.support != DialogConditions.Support.REAL) {
                continue;
            }
            DialogConditions.Result r = DialogConditions.evaluate(ctx(), c.text + "1");
            assertTrue(r.deferred.isEmpty(), c.text + " should not defer");
        }
    }

    @Test
    void theCodeTableIsInTheCsEnumOrder() {
        // off_5A06BC is indexed by DialogCondition, so the order is load-bearing:
        // the C finds a code by scanning this array and switches on the index.
        String[] expected = {
            "ps", "ch", "pe", "al", "ma", "ta", "gv", "gf", "qu", "re", "$$", "in",
            "ha", "lf", "lc", "tr", "sk", "ru", "rq", "fo", "le", "qb", "me", "ni",
            "qa", "ra", "pa", "ss", "wa", "wt", "pv", "pf", "na", "ar", "rp", "ia",
            "sc",
        };
        DialogConditions.Code[] actual = DialogConditions.Code.values();
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i].text, "index " + i);
        }
    }

    // -- the NPC-side codes we can really answer ----------------------------

    @Test
    void metBeforeIsFalseWithNoPcObject() {
        // reaction_met_before's own second guard: pc_obj is not an OBJ_TYPE_PC.
        assertTrue(DialogConditions.test(ctx(), "me0"), "me0 = 'never met' -> passes");
        assertFalse(DialogConditions.test(ctx(), "me1"), "me1 = 'have met' -> fails");
    }

    @Test
    void meIgnoresValuesOtherThanZeroAndOne() {
        // "if (value == 0) ... else if (value == 1) ..." -- no else, so no test.
        assertTrue(DialogConditions.test(ctx(), "me2"));
    }
}
