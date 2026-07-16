package com.arcanum.ce.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.arcanum.ce.tig.TigFile;
import com.arcanum.ce.tig.mes.MesFile;

/**
 * A parsed Arcanum {@code .dlg} conversation file, ported from
 * {@code dialog_load_internal} / {@code dialog_parse_entry} / {@code dialog_parse_field}
 * ({@code dialog.c}).
 *
 * <p>Despite the binary look of the rest of the object data, {@code .dlg} is
 * <b>plain text</b> — the engine opens it {@code "rt"} and {@code dialog_file_fgetc}
 * is just a line-buffered {@code fgets}. Nothing is obfuscated.
 *
 * <p>Each entry is <b>seven</b> {@code {...}}-delimited fields, in order:
 * <ol>
 *   <li>{@code num} — the line number other lines jump to</li>
 *   <li>{@code text} — what is said</li>
 *   <li>gender field — dual-purpose (see below)</li>
 *   <li>{@code iq} — <b>blank/0 marks an NPC line; non-zero marks a PC response</b>
 *       and is that response's minimum IQ</li>
 *   <li>{@code conditions} — the "test" field (unevaluated here)</li>
 *   <li>{@code responseVal} — the line this jumps to</li>
 *   <li>{@code actions} — the "effect" field (unevaluated here)</li>
 * </ol>
 * Field 3 depends on the line kind: for a <b>PC</b> line it is a gender filter
 * ({@code -1} when blank); for an <b>NPC</b> line it is the female variant of the
 * text (the engine only warns when it is blank, so it is kept verbatim and the
 * caller decides whether to fall back to {@link Entry#text}).
 *
 * <p>Outside a field, {@code //} starts a line comment. Entries are sorted by
 * {@code num} (the engine qsorts them) so {@link #find} can binary-search.
 *
 * <p>Conditions/actions are captured as raw strings: evaluating them needs the
 * script VM, which is not ported.
 */
public final class DialogFile {

    private static final int FIELDS_PER_ENTRY = 7;

    /** One dialog line: either an NPC utterance or a selectable PC response. */
    public static final class Entry {
        public final int num;
        public final String text;
        /** NPC lines: the female variant, verbatim (may be blank). */
        public final String femaleText;
        /** PC lines: gender filter, -1 when blank. NPC lines: unused (-1). */
        public final int gender;
        /** 0 = NPC line; non-zero = PC response, and its minimum IQ. */
        public final int iq;
        public final String conditions;
        /** The dialog line this jumps to (0 = none/end). */
        public final int responseVal;
        public final String actions;

        Entry(int num, String text, String femaleText, int gender, int iq,
              String conditions, int responseVal, String actions) {
            this.num = num;
            this.text = text;
            this.femaleText = femaleText;
            this.gender = gender;
            this.iq = iq;
            this.conditions = conditions;
            this.responseVal = responseVal;
            this.actions = actions;
        }

        /** True when this is a selectable player response rather than an NPC line. */
        public boolean isPc() {
            return iq != 0;
        }

        /** The text to show for {@code female}, falling back to {@link #text}. */
        public String text(boolean female) {
            if (female && !isPc() && femaleText != null && !femaleText.trim().isEmpty()) {
                return femaleText;
            }
            return text;
        }

        @Override
        public String toString() {
            return String.format("%s %d -> %d  %s", isPc() ? "PC " : "NPC",
                    num, responseVal, text);
        }
    }

    public final String path;
    private final List<Entry> entries;

    private DialogFile(String path, List<Entry> entries) {
        this.path = path;
        this.entries = entries;
    }

    /** Load + parse a {@code .dlg} from the repository; null if absent or empty. */
    public static DialogFile load(String path) {
        byte[] bytes = TigFile.readBytes(path);
        if (bytes == null) {
            return null;
        }
        DialogFile d = parse(path, new String(bytes, MesFile.ENCODING));
        // dialog_load treats a file that yielded no entries as a failure.
        return d != null && !d.entries.isEmpty() ? d : null;
    }

    /** Parse {@code .dlg} text (see the class docs for the format). */
    public static DialogFile parse(String path, String text) {
        List<Entry> entries = new ArrayList<>();
        Cursor c = new Cursor(text);
        while (true) {
            String[] f = new String[FIELDS_PER_ENTRY];
            int got = 0;
            for (; got < FIELDS_PER_ENTRY; got++) {
                String field = c.nextField();
                if (field == null) {
                    break;
                }
                f[got] = field;
            }
            if (got < FIELDS_PER_ENTRY) {
                break;              // clean EOF, or a truncated trailing entry
            }

            int iq = atoi(f[3]);
            String genderField = f[2];
            entries.add(new Entry(
                    atoi(f[0]),
                    f[1],
                    iq != 0 ? "" : genderField,
                    iq != 0 ? (genderField.trim().isEmpty() ? -1 : atoi(genderField)) : -1,
                    iq,
                    f[4],
                    atoi(f[5]),
                    f[6]));
        }
        // dialog_load_internal qsorts by num; keep that so find() can bisect.
        entries.sort(Comparator.comparingInt(e -> e.num));
        return new DialogFile(path, entries);
    }

    /** dialog_search: the entry with this line number, or null. */
    public Entry find(int num) {
        int lo = 0;
        int hi = entries.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = Integer.compare(entries.get(mid).num, num);
            if (cmp == 0) {
                return entries.get(mid);
            }
            if (cmp < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return null;
    }

    /** All entries, ordered by line number. */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * The PC responses that follow an NPC line: the engine walks forward from the
     * NPC line, and the consecutive PC entries after it are its options.
     */
    public List<Entry> responsesTo(Entry npcLine) {
        List<Entry> out = new ArrayList<>();
        int i = entries.indexOf(npcLine);
        if (i < 0) {
            return out;
        }
        for (int j = i + 1; j < entries.size() && entries.get(j).isPc(); j++) {
            out.add(entries.get(j));
        }
        return out;
    }

    /**
     * C {@code atoi}: leading integer, 0 when absent/blank. Shared with
     * {@link DialogConditions}, which scans the same fields with the same libc.
     */
    static int atoi(String s) {
        if (s == null) {
            return 0;
        }
        int i = 0;
        int n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        int start = i;
        if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            i++;
        }
        while (i < n && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == start || (i == start + 1 && !Character.isDigit(s.charAt(start)))) {
            return 0;
        }
        try {
            return Integer.parseInt(s.substring(start, i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** dialog_parse_field: seek to '{', then take everything up to '}'. */
    private static final class Cursor {
        private final String s;
        private int p;

        Cursor(String s) {
            this.s = s;
        }

        /** Next {@code {...}} field, or null at end of input. */
        String nextField() {
            // Seek '{'. Outside a field, "//" runs to end of line.
            int prev = 0;
            while (p < s.length()) {
                char ch = s.charAt(p++);
                if (ch == '{') {
                    StringBuilder sb = new StringBuilder();
                    while (p < s.length()) {
                        char c = s.charAt(p++);
                        if (c == '}') {
                            return sb.toString();
                        }
                        sb.append(c);
                    }
                    return null;        // unterminated field at EOF
                }
                if (ch == '/' && prev == '/') {
                    while (p < s.length() && s.charAt(p) != '\n') {
                        p++;
                    }
                    prev = 0;
                } else {
                    prev = ch;
                }
            }
            return null;
        }
    }
}
