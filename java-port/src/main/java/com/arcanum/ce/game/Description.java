package com.arcanum.ce.game;

import com.arcanum.ce.tig.mes.Mes;

/**
 * Player-facing description text, ported from {@code description.c}
 * ({@code description_init} / {@code description_mod_load} /
 * {@code description_get} / {@code key_description_get}).
 *
 * <p>These are the strings the game actually shows for an object — reached from
 * {@code OBJ_F_DESCRIPTION} (not {@code OBJ_F_NAME}; see {@link OName}) via
 * {@code object_examine}, which {@link ObjectName} ports.
 *
 * <pre>
 * const char* description_get(int num)
 * {
 *     if (num &lt; 0 || num &gt; description_max_num) return NULL;
 *     if (num &lt; 30000) mes_file = description_mes_file;   // mes\description.mes
 *     else {
 *         mes_file = gamedesc_mes_file;                    // mes\gamedesc.mes
 *         if (mes_file == MES_FILE_HANDLE_INVALID) return NULL;
 *     }
 *     mes_file_entry.num = num;
 *     if (!mes_search(mes_file, &amp;mes_file_entry)) return NULL;
 *     return mes_file_entry.str;
 * }
 * </pre>
 *
 * <p>Loaded once and cached ({@link #get}), like {@link ProtoStore} — the message
 * files are immutable game data.
 */
public final class Description {

    /** Numbers at or above this come from the module file, not the system one. */
    private static final int MODULE_NUM_BASE = 30000;

    private static Description cached;

    private final int descriptionMes;   // mes\description.mes  (system, required)
    private final int gamedescMes;      // mes\gamedesc.mes     (module, optional)
    private final int gamekeyMes;       // mes\gamekey.mes      (module, optional)
    private final int maxNum;

    private Description(int descriptionMes, int gamedescMes, int gamekeyMes, int maxNum) {
        this.descriptionMes = descriptionMes;
        this.gamedescMes = gamedescMes;
        this.gamekeyMes = gamekeyMes;
        this.maxNum = maxNum;
    }

    /**
     * The description tables, loading them on first call. Requires the repository
     * stack to be registered ({@code GameData.discoverAndRegister}). Returns null
     * if {@code mes\description.mes} is unavailable — the C's
     * {@code description_init} returns false there and the game refuses to start.
     */
    public static synchronized Description get() {
        if (cached == null) {
            cached = load();
        }
        return cached;
    }

    /** Drop the cache — for tests that re-register the repository stack. */
    public static synchronized void reset() {
        cached = null;
    }

    /**
     * {@code description_init} + {@code description_mod_load}.
     *
     * <p>{@code description_max_num} is the highest number in the system file, then
     * raised to the module file's highest if one loads — both taken as the last
     * entry of a num-sorted table, i.e. the max.
     */
    public static Description load() {
        int descriptionMes = Mes.load("mes\\description.mes");
        if (descriptionMes == Mes.INVALID_HANDLE) {
            return null;                       // description_init returns false
        }
        int maxNum = Mes.maxNum(descriptionMes);

        // description_mod_load: both module files are optional.
        int gamedescMes = Mes.load("mes\\gamedesc.mes");
        if (gamedescMes != Mes.INVALID_HANDLE) {
            int moduleMax = Mes.maxNum(gamedescMes);
            if (Mes.entriesCount(gamedescMes) != 0) {
                maxNum = moduleMax;            // note: the C overwrites, not max()
            }
        }
        int gamekeyMes = Mes.load("mes\\gamekey.mes");

        return new Description(descriptionMes, gamedescMes, gamekeyMes, maxNum);
    }

    /**
     * {@code description_get}: the text for a description number, or null if the
     * number is out of range or absent.
     */
    public String describe(int num) {
        if (num < 0 || num > maxNum) {
            return null;
        }
        int mes = num < MODULE_NUM_BASE ? descriptionMes : gamedescMes;
        if (mes == Mes.INVALID_HANDLE) {
            return null;
        }
        return Mes.find(mes, num);
    }

    /**
     * {@code key_description_get}: the name of a key, from {@code mes\gamekey.mes}.
     * Null if the module file did not load or the number is absent.
     */
    public String keyDescription(int num) {
        if (gamekeyMes == Mes.INVALID_HANDLE) {
            return null;
        }
        return Mes.find(gamekeyMes, num);
    }

    /** {@code description_max_num} — the highest number {@link #describe} accepts. */
    public int maxNum() {
        return maxNum;
    }

    /** Whether the optional module table ({@code mes\gamedesc.mes}) loaded. */
    public boolean hasModuleTable() {
        return gamedescMes != Mes.INVALID_HANDLE;
    }

    /** Whether the optional key table ({@code mes\gamekey.mes}) loaded. */
    public boolean hasKeyTable() {
        return gamekeyMes != Mes.INVALID_HANDLE;
    }
}
