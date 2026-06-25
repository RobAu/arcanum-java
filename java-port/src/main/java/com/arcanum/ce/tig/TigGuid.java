package com.arcanum.ce.tig;

import java.util.UUID;

/**
 * GUID generation. Maps {@code tig/guid.h}. Backed by java.util.UUID.
 */
public final class TigGuid {

    public long hi;
    public long lo;

    /** tig_guid_create */
    public static void create(TigGuid guid) {
        UUID u = UUID.randomUUID();
        guid.hi = u.getMostSignificantBits();
        guid.lo = u.getLeastSignificantBits();
    }

    /** tig_guid_is_equal */
    public static boolean isEqual(TigGuid a, TigGuid b) {
        return a.hi == b.hi && a.lo == b.lo;
    }
}
