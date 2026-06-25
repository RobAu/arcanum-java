package com.arcanum.ce.tig.mes;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A parsed Arcanum {@code .mes} message file.
 *
 * Ported from {@code src/game/mes.c} (parse_entry / parse_field). The format is
 * a sequence of entries, each two brace-delimited fields: {@code {number}{text}}.
 * Everything outside braces (whitespace, {@code //} comments) is ignored; text
 * between {@code &#123;} and the next {@code &#125;} is kept verbatim, so entries
 * may span multiple lines. Entries are keyed by their integer number.
 *
 * Text is decoded as Windows-1252 (the game's encoding).
 */
public final class MesFile {

    /** The game stores .mes text in Windows-1252. */
    public static final Charset ENCODING = Charset.forName("windows-1252");

    private final Map<Integer, String> entries;

    private MesFile(Map<Integer, String> entries) {
        this.entries = entries;
    }

    public static MesFile parse(byte[] data) {
        return parse(new String(data, ENCODING));
    }

    public static MesFile parse(String text) {
        // Sorted map mirrors the C engine's qsort-by-number (ordered iteration).
        Map<Integer, String> map = new TreeMap<>();
        int i = 0;
        while (true) {
            // Field = text between the next '{' and the following '}'.
            int numOpen = text.indexOf('{', i);
            if (numOpen < 0) {
                break;
            }
            int numClose = text.indexOf('}', numOpen + 1);
            if (numClose < 0) {
                break;
            }
            int textOpen = text.indexOf('{', numClose + 1);
            if (textOpen < 0) {
                break;                        // trailing number with no text field
            }
            int textClose = text.indexOf('}', textOpen + 1);
            if (textClose < 0) {
                break;
            }
            int num = parseLeadingInt(text.substring(numOpen + 1, numClose));
            String value = text.substring(textOpen + 1, textClose);
            map.putIfAbsent(num, value);      // first definition wins (cf. dedup)
            i = textClose + 1;
        }
        return new MesFile(map);
    }

    private static int parseLeadingInt(String f) {
        int i = 0;
        int n = f.length();
        boolean neg = false;
        while (i < n && Character.isWhitespace(f.charAt(i))) {
            i++;
        }
        if (i < n && (f.charAt(i) == '-' || f.charAt(i) == '+')) {
            neg = f.charAt(i) == '-';
            i++;
        }
        long v = 0;
        boolean any = false;
        while (i < n && f.charAt(i) >= '0' && f.charAt(i) <= '9') {
            v = v * 10 + (f.charAt(i++) - '0');
            any = true;
        }
        if (!any) {
            return 0;
        }
        return (int) (neg ? -v : v);
    }

    // -- query (mirrors mes.h) -----------------------------------------------
    /** mes_get_msg: the text for a number, or a "Mes Error" marker if absent. */
    public String getMsg(int num) {
        String s = entries.get(num);
        return s != null ? s : "Mes Error(" + num + ")";
    }

    /** Like {@link #getMsg} but returns null when the number is absent. */
    public String find(int num) {
        return entries.get(num);
    }

    public boolean contains(int num) {
        return entries.containsKey(num);
    }

    public int count() {
        return entries.size();
    }

    /** Ordered (by number) view of all entries. */
    public Map<Integer, String> entries() {
        return new HashMap<>(entries);
    }
}
