package com.arcanum.ce.tig;

import java.util.HashMap;
import java.util.Map;

/**
 * Integer-keyed table. Maps {@code tig/idxtable.h}. The C version stored
 * fixed-size values keyed by int; here it is a {@code Map<Integer,Object>}.
 *
 * @param <V> value type (the C API was untyped void*; the port can specialise)
 */
public final class TigIdxTable<V> {

    private final Map<Integer, V> map = new HashMap<>();

    public void set(int key, V value) {
        map.put(key, value);
    }

    public V get(int key) {
        return map.get(key);
    }

    public boolean contains(int key) {
        return map.containsKey(key);
    }

    public void remove(int key) {
        map.remove(key);
    }

    public int count() {
        return map.size();
    }
}
