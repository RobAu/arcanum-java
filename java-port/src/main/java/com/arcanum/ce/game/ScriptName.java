package com.arcanum.ce.game;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.arcanum.ce.tig.TigDebug;
import com.arcanum.ce.tig.TigFile;

/**
 * Script number → file path, ported from {@code script_name.c}
 * ({@code script_name_init} / {@code script_name_mod_load} /
 * {@code script_name_build_scr_name} / {@code script_name_build_dlg_name}).
 *
 * <p>The index is built by listing {@code scr\*.scr} and {@code atoi}-ing each
 * filename's leading digits — so {@code 01324Virgil.scr} registers under 1324:
 *
 * <pre>
 * tig_file_list_create(&amp;file_list, "scr\\*.scr");
 * for (index = 0; index &lt; file_list.count; index++) {
 *     num = atoi(file_list.entries[index].path);
 *     if (num &gt;= 1000) {                                  // script_name_init
 *         if (tig_idxtable_contains(&amp;script_name_system_idxtable, num))
 *             tig_debug_printf("Error! Multiple script files numbered %.5d\n", num);
 *         tig_idxtable_set(&amp;script_name_system_idxtable, num, ...path);
 *     }
 * }
 * ...
 *     if (num &gt;= 1 &amp;&amp; num &lt; 1000) { ... mod_idxtable ... }   // script_name_mod_load
 * </pre>
 *
 * <p>Two tables, one file listing: {@code >= 1000} is the <em>system</em> table
 * ({@code script_name_init}), {@code 1..999} the <em>module</em> table
 * ({@code script_name_mod_load}). The C's own comment flags this split as a
 * suspected bug (it leaves only the three 997/998/999 scripts in the module
 * table), but it is the shipped behaviour and the lookup honours the same split,
 * so the two cancel out. Ported as-is.
 *
 * <p>The dlg path is the scr path with its first and last three characters
 * overwritten — {@code scr\01324Virgil.scr} → {@code dlg\01324Virgil.dlg}. Note
 * this is a blind overwrite, not an extension swap: it neither checks that the
 * file exists nor that the name ends in {@code .scr}.
 *
 * <p>The listing is done once and cached ({@link #get}).
 */
public final class ScriptName {

    /** The repository directory scripts live in, and their extension. */
    public static final String SCR_DIR = "scr";
    public static final String SCR_EXT = ".scr";

    /** Script numbers at or above this go in the system table; 1..999 in the module one. */
    public static final int SYSTEM_NUM_BASE = 1000;

    private static ScriptName cached;

    private final Map<Integer, String> systemTable;   // num >= 1000
    private final Map<Integer, String> modTable;      // 1 <= num < 1000

    private ScriptName(Map<Integer, String> systemTable, Map<Integer, String> modTable) {
        this.systemTable = systemTable;
        this.modTable = modTable;
    }

    /** The script index, building it on first call. */
    public static synchronized ScriptName get() {
        if (cached == null) {
            cached = load();
        }
        return cached;
    }

    /** Drop the cache — for tests that re-register the repository stack. */
    public static synchronized void reset() {
        cached = null;
    }

    /** {@code script_name_init} + {@code script_name_mod_load}: one listing, two tables. */
    public static ScriptName load() {
        List<String> names = TigFile.list(SCR_DIR, SCR_EXT);
        Map<Integer, String> systemTable = new HashMap<>();
        Map<Integer, String> modTable = new HashMap<>();

        for (String name : names) {
            int num = atoi(name);
            Map<Integer, String> table;
            if (num >= SYSTEM_NUM_BASE) {
                table = systemTable;
            } else if (num >= 1) {
                table = modTable;
            } else {
                continue;                       // no leading digits, or 0: neither table
            }
            // tig_idxtable_contains -> "Error! Multiple script files numbered %.5d",
            // then tig_idxtable_set overwrites regardless. TigFile.list is sorted
            // case-insensitively as tig_file_list_create is, so last-wins matches.
            if (table.containsKey(num)) {
                TigDebug.println(String.format(
                        "Error! Multiple script files numbered %05d", num));
            }
            table.put(num, name);
        }
        TigDebug.println("script_name_init: indexed " + systemTable.size()
                + " system + " + modTable.size() + " module script(s) from "
                + names.size() + " scr\\*.scr file(s)");
        return new ScriptName(systemTable, modTable);
    }

    /**
     * {@code script_name_build_scr_name}: {@code scr\<filename>} for a script
     * number, or null if it is not indexed.
     *
     * <pre>
     * if (num != 0) {
     *     if (script_name_system_loaded &amp;&amp; num &gt;= 1000
     *         &amp;&amp; tig_idxtable_get(&amp;script_name_system_idxtable, num, path)) {
     *         snprintf(buffer, maxlen, "scr\\%s", path); return true;
     *     }
     *     if (script_name_mod_loaded &amp;&amp; num &gt;= 1 &amp;&amp; num &lt; 1000
     *         &amp;&amp; tig_idxtable_get(&amp;script_name_mod_idxtable, num, path)) {
     *         snprintf(buffer, maxlen, "scr\\%s", path); return true;
     *     }
     * }
     * return false;
     * </pre>
     */
    public String buildScrName(int num) {
        if (num == 0) {
            return null;
        }
        String file = num >= SYSTEM_NUM_BASE ? systemTable.get(num)
                : (num >= 1 ? modTable.get(num) : null);
        return file == null ? null : SCR_DIR + "\\" + file;
    }

    /**
     * {@code script_name_build_dlg_name}: the scr path with its leading and
     * trailing three characters overwritten with {@code dlg}. Null if the number
     * is not indexed.
     *
     * <pre>
     * if (!script_name_build_scr_name(num, buffer, maxlen)) return false;
     * len = strlen(buffer);
     * buffer[0] = 'd'; buffer[1] = 'l'; buffer[2] = 'g';
     * buffer[len - 3] = 'd'; buffer[len - 2] = 'l'; buffer[len - 1] = 'g';
     * </pre>
     *
     * <p>A blind overwrite: the C does not verify the extension, nor that the
     * resulting {@code .dlg} exists. Callers check separately
     * ({@code dialog_load} in {@code dialog_ui.c:150}).
     */
    public String buildDlgName(int num) {
        String scr = buildScrName(num);
        if (scr == null) {
            return null;
        }
        if (scr.length() < 6) {
            return scr;                         // the C would corrupt the buffer here
        }
        StringBuilder sb = new StringBuilder(scr);
        sb.replace(0, 3, "dlg");
        sb.replace(sb.length() - 3, sb.length(), "dlg");
        return sb.toString();
    }

    /** The bare {@code .scr} filename indexed under a number, or null. */
    public String fileName(int num) {
        String file = num >= SYSTEM_NUM_BASE ? systemTable.get(num)
                : (num >= 1 ? modTable.get(num) : null);
        return file;
    }

    /** Number of scripts in the system table ({@code num >= 1000}). */
    public int systemCount() {
        return systemTable.size();
    }

    /** Number of scripts in the module table ({@code 1 <= num < 1000}). */
    public int modCount() {
        return modTable.size();
    }

    /**
     * C {@code atoi} over a filename: the leading run of digits, 0 if none.
     *
     * <p>Faithful enough for this input — script filenames are
     * {@code %.5d}-prefixed ({@code 01324Virgil.scr}), so no sign, whitespace or
     * overflow handling is reachable.
     */
    static int atoi(String s) {
        int i = 0;
        int n = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            n = n * 10 + (s.charAt(i) - '0');
            i++;
        }
        return n;
    }
}
