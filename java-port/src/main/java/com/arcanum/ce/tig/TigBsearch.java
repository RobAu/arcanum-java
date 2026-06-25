package com.arcanum.ce.tig;

import java.util.Comparator;
import java.util.List;

/**
 * Binary search helper. Maps {@code tig/bsearch.h}. The C API was an untyped
 * bsearch over a raw array; the port offers a generic, type-safe equivalent.
 */
public final class TigBsearch {

    private TigBsearch() {
    }

    /** Returns the index of {@code key} in the sorted {@code list}, or
     *  {@code -(insertionPoint) - 1} if absent (java.util.Collections semantics). */
    public static <T> int search(List<? extends T> list, T key, Comparator<? super T> cmp) {
        return java.util.Collections.binarySearch(list, key, cmp);
    }
}
