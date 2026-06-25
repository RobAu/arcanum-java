package com.arcanum.ce.tig;

import java.io.File;

/**
 * Cross-platform path helpers. Maps {@code tig/compat.h}. The C engine stores
 * Windows-style ("dir\\file") paths internally; these convert to/from the host.
 */
public final class Compat {

    private Compat() {
    }

    /** compat_windows_path_to_native */
    public static String windowsPathToNative(String path) {
        if (path == null) {
            return null;
        }
        return path.replace('\\', File.separatorChar);
    }

    /** compat_append_path */
    public static String appendPath(String path, String comp) {
        if (path == null || path.isEmpty()) {
            return comp;
        }
        String sep = File.separator;
        return path.endsWith(sep) ? path + comp : path + sep + comp;
    }
}
