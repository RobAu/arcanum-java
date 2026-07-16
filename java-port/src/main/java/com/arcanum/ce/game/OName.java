package com.arcanum.ce.game;

import com.arcanum.ce.tig.mes.Mes;

/**
 * Internal object names, ported from {@code oname.c} ({@code o_name_init} /
 * {@code o_name_mod_load} / {@code o_name_get}) over {@code oemes\oname.mes}.
 *
 * <p>This — not {@link Description} — is where {@code OBJ_F_NAME} resolves.
 * {@code obj.c:1703} is explicit:
 *
 * <pre>
 * if (fld == OBJ_F_NAME) {
 *     obj_field_fetch(object, OBJ_F_NAME, &amp;name_num);
 *     name_str = o_name_get(name_num);              // oemes\oname.mes
 *     *value_ptr = (char*)MALLOC(strlen(name_str) + 1);
 *     strcpy(*value_ptr, name_str);
 * }
 * </pre>
 *
 * <p>These names are the engine's internal/editor identifiers ("Virgil",
 * "Wooden Chest"), which is why they read as developer-facing rather than
 * player-facing. The string shown in-game comes from {@code object_examine} via
 * {@code OBJ_F_DESCRIPTION} — see {@link ObjectName}.
 *
 * <p>{@code o_name_mod_load} merges the optional module table
 * ({@code oemes\gameoname.mes}) over the system one; this reproduces that by
 * consulting the module table first.
 */
public final class OName {

    private static OName cached;

    private final int onameMes;       // oemes\oname.mes        (system, required)
    private final int gameOnameMes;   // oemes\gameoname.mes    (module, optional)
    private final int factionMes;     // oemes\faction.mes      (system, required)

    private OName(int onameMes, int gameOnameMes, int factionMes) {
        this.onameMes = onameMes;
        this.gameOnameMes = gameOnameMes;
        this.factionMes = factionMes;
    }

    /**
     * The object-name tables, loading them on first call. Null if
     * {@code oemes\oname.mes} is unavailable ({@code o_name_init} returns false).
     */
    public static synchronized OName get() {
        if (cached == null) {
            cached = load();
        }
        return cached;
    }

    /** Drop the cache — for tests that re-register the repository stack. */
    public static synchronized void reset() {
        cached = null;
    }

    /** {@code o_name_init} + {@code o_name_mod_load}. */
    public static OName load() {
        int onameMes = Mes.load("oemes\\oname.mes");
        if (onameMes == Mes.INVALID_HANDLE) {
            return null;
        }
        int factionMes = Mes.load("oemes\\faction.mes");
        // o_name_mod_load: mes_merge(o_name_oname_mes_file, gameoname) -- module
        // entries win over system ones, which `name` reproduces by looking here first.
        int gameOnameMes = Mes.load("oemes\\gameoname.mes");
        return new OName(onameMes, gameOnameMes, factionMes);
    }

    /**
     * {@code o_name_get}: the internal name for a name number.
     *
     * <p>The C calls {@code mes_get_msg}, which yields the {@code "Mes Error(n)"}
     * marker for an absent number rather than null; this returns null instead so
     * callers can tell "absent" from "a name that happens to look like that".
     */
    public String name(int num) {
        if (gameOnameMes != Mes.INVALID_HANDLE) {
            String s = Mes.find(gameOnameMes, num);
            if (s != null) {
                return s;
            }
        }
        return Mes.find(onameMes, num);
    }

    /** The faction name for a number ({@code oemes\faction.mes}), or null. */
    public String faction(int num) {
        return factionMes == Mes.INVALID_HANDLE ? null : Mes.find(factionMes, num);
    }

    /** {@code o_name_count} — number of entries in the system object-name table. */
    public int count() {
        return Mes.entriesCount(onameMes);
    }
}
