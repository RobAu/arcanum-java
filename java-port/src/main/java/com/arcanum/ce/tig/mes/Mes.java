package com.arcanum.ce.tig.mes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.arcanum.ce.tig.TigFile;

/**
 * Handle-based facade over {@link MesFile}, mirroring {@code src/game/mes.h}
 * ({@code mes_load} / {@code mes_get_msg} / {@code mes_unload} ...).
 *
 * The C API hands out small integer handles into a table of loaded files and
 * ref-counts repeated loads of the same path; this reproduces that so the rest
 * of the port (and eventually the transpiled game code) can call it the same way.
 * Files are read through the {@link TigFile} repository stack (loose or .dat).
 */
public final class Mes {

    public static final int INVALID_HANDLE = -1;

    private static final List<MesFile> FILES = new ArrayList<>();
    private static final List<String> PATHS = new ArrayList<>();
    private static final Map<Integer, Integer> REFCOUNTS = new HashMap<>();
    private static final Map<String, Integer> BY_PATH = new HashMap<>();

    private Mes() {
    }

    /** mes_load: returns a handle, or {@link #INVALID_HANDLE} on failure. */
    public static int load(String path) {
        String key = path.replace('\\', '/').toLowerCase();
        Integer existing = BY_PATH.get(key);
        if (existing != null) {
            REFCOUNTS.merge(existing, 1, Integer::sum);
            return existing;
        }
        byte[] bytes = TigFile.readBytes(path);
        if (bytes == null) {
            return INVALID_HANDLE;
        }
        MesFile mf = MesFile.parse(bytes);
        if (mf.count() == 0) {
            return INVALID_HANDLE;
        }
        int handle = FILES.size();
        FILES.add(mf);
        PATHS.add(path);
        BY_PATH.put(key, handle);
        REFCOUNTS.put(handle, 1);
        return handle;
    }

    /** mes_unload: decrements the refcount (entries are kept for handle stability). */
    public static boolean unload(int handle) {
        Integer rc = REFCOUNTS.get(handle);
        if (rc == null) {
            return false;
        }
        REFCOUNTS.put(handle, Math.max(0, rc - 1));
        return true;
    }

    private static MesFile file(int handle) {
        if (handle < 0 || handle >= FILES.size()) {
            return null;
        }
        return FILES.get(handle);
    }

    /** mes_get_msg: text for a number ("Mes Error(n)" if absent). */
    public static String getMsg(int handle, int num) {
        MesFile mf = file(handle);
        return mf != null ? mf.getMsg(num) : "Mes Error(" + num + ")";
    }

    /** Null-returning lookup (no error marker). */
    public static String find(int handle, int num) {
        MesFile mf = file(handle);
        return mf != null ? mf.find(num) : null;
    }

    public static int entriesCount(int handle) {
        MesFile mf = file(handle);
        return mf != null ? mf.count() : 0;
    }

    /** Entry texts in ascending message-number order, or null if the handle is bad. */
    public static java.util.List<String> valuesInOrder(int handle) {
        MesFile mf = file(handle);
        return mf != null ? mf.valuesInNumberOrder() : null;
    }
}
