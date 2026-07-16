package com.arcanum.ce.game;

/**
 * {@code ScriptAttachmentPoint} ({@code script.h}) — the keys of an object's
 * {@code OBJ_F_SCRIPTS_IDX} sparse array.
 *
 * <p>These are <em>keys</em>, not element indices: the array is a
 * {@link SizeableArray}, so {@link #DIALOG} (9) is rarely the 9th element. See
 * {@link SizeableArray} for why that matters.
 */
public final class Sap {

    public static final int EXAMINE = 0;
    public static final int USE = 1;
    public static final int DESTROY = 2;
    public static final int UNLOCK = 3;
    public static final int GET = 4;
    public static final int DROP = 5;
    public static final int THROW = 6;
    public static final int HIT = 7;
    public static final int MISS = 8;
    public static final int DIALOG = 9;
    public static final int FIRST_HEARTBEAT = 10;
    public static final int CATCHING_THIEF_PC = 11;
    public static final int DYING = 12;
    public static final int ENTER_COMBAT = 13;
    public static final int EXIT_COMBAT = 14;
    public static final int START_COMBAT = 15;
    public static final int END_COMBAT = 16;
    public static final int BUY_OBJECT = 17;
    public static final int RESURRECT = 18;
    public static final int HEARTBEAT = 19;
    public static final int LEADER_KILLING = 20;
    public static final int INSERT_ITEM = 21;
    public static final int WILL_KOS = 22;
    public static final int TAKING_DAMAGE = 23;
    public static final int WIELD_ON = 24;
    public static final int WIELD_OFF = 25;
    public static final int CRITTER_HITS = 26;
    public static final int NEW_SECTOR = 27;
    public static final int REMOVE_ITEM = 28;
    public static final int LEADER_SLEEPING = 29;
    public static final int BUST = 30;
    public static final int DIALOG_OVERRIDE = 31;
    public static final int TRANSFER = 32;
    public static final int CAUGHT_THIEF = 33;
    public static final int CRITICAL_HIT = 34;
    public static final int CRITICAL_MISS = 35;

    /** Names in enum order, for diagnostics. */
    private static final String[] NAMES = {
        "EXAMINE", "USE", "DESTROY", "UNLOCK", "GET", "DROP", "THROW", "HIT",
        "MISS", "DIALOG", "FIRST_HEARTBEAT", "CATCHING_THIEF_PC", "DYING",
        "ENTER_COMBAT", "EXIT_COMBAT", "START_COMBAT", "END_COMBAT", "BUY_OBJECT",
        "RESURRECT", "HEARTBEAT", "LEADER_KILLING", "INSERT_ITEM", "WILL_KOS",
        "TAKING_DAMAGE", "WIELD_ON", "WIELD_OFF", "CRITTER_HITS", "NEW_SECTOR",
        "REMOVE_ITEM", "LEADER_SLEEPING", "BUST", "DIALOG_OVERRIDE", "TRANSFER",
        "CAUGHT_THIEF", "CRITICAL_HIT", "CRITICAL_MISS",
    };

    private Sap() {
    }

    /** The SAP_* name for a key, or {@code "SAP<n>"} if out of range. */
    public static String name(int sap) {
        return sap >= 0 && sap < NAMES.length ? NAMES[sap] : "SAP" + sap;
    }
}
