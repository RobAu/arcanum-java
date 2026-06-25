package com.arcanum.ce.tig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU file cache. Maps {@code tig/file_cache.h}. Caches recently read file
 * blobs. Backed by a size-bounded {@link LinkedHashMap}.
 */
public final class TigFileCache {

    private final int capacity;
    private final Map<String, byte[]> entries;

    public TigFileCache(int capacity, int maxSize) {
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > TigFileCache.this.capacity;
            }
        };
    }

    public byte[] acquire(String path) {
        return entries.get(path);
    }

    public void put(String path, byte[] data) {
        entries.put(path, data);
    }

    public void flush() {
        entries.clear();
    }
}
