package com.arcanum.ce.tig;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.arcanum.ce.tig.database.DatArchive;

/**
 * Virtual file system. Maps {@code tig/file.h} (plus database.h / file_cache.h).
 *
 * The C engine reads from a stack of repositories: loose files on disk and the
 * compressed {@code .dat} archives. Both are supported here: {@link #repositoryAdd}
 * registers either a loose-file directory or a {@code .dat} archive (via
 * {@link DatArchive}), and {@link #readBytes} / {@link #exists} search the stack
 * (loose files first, then archives in registration order). A {@code TigFile*}
 * maps to {@link Handle}.
 */
public final class TigFile {

    /** Open file handle (maps C {@code TigFile*}). */
    public static final class Handle {
        RandomAccessFile raf;
        String path;
    }

    private static final List<File> ROOTS = new ArrayList<>();
    private static final List<DatArchive> ARCHIVES = new ArrayList<>();

    private TigFile() {
    }

    public static int init() {
        return 0;
    }

    public static void exit() {
        for (DatArchive a : ARCHIVES) {
            try {
                a.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        ARCHIVES.clear();
        ROOTS.clear();
    }

    /** tig_file_repository_add -- register a loose-file directory or a .dat archive. */
    public static boolean repositoryAdd(String path) {
        File f = new File(path);
        if (!f.exists()) {
            return false;
        }
        if (f.isFile() && f.getName().toLowerCase().endsWith(".dat")) {
            try {
                ARCHIVES.add(new DatArchive(f));
                return true;
            } catch (IOException e) {
                TigDebug.println("repository_add: bad .dat " + path + " (" + e + ")");
                return false;
            }
        }
        ROOTS.add(f);
        return true;
    }

    /**
     * Read a named asset from the repository stack: loose files first, then each
     * registered {@code .dat} archive (inflating if needed). Returns null if not
     * found. This is the primary entry point the asset loaders (e.g.
     * {@link TigArt}) build on.
     */
    public static byte[] readBytes(String path) {
        File loose = resolve(path);
        if (loose != null) {
            try {
                return Files.readAllBytes(loose.toPath());
            } catch (IOException ignored) {
                // fall through to archives
            }
        }
        for (DatArchive a : ARCHIVES) {
            DatArchive.Entry e = a.get(path);
            if (e != null && !e.isDirectory()) {
                try {
                    return a.read(e);
                } catch (IOException ignored) {
                    // try next archive
                }
            }
        }
        return null;
    }

    /** tig_file_exists -- searches loose roots and archives. */
    public static boolean exists(String fileName, Object infoOut) {
        if (resolve(fileName) != null) {
            return true;
        }
        for (DatArchive a : ARCHIVES) {
            DatArchive.Entry e = a.get(fileName);
            if (e != null && !e.isDirectory()) {
                return true;
            }
        }
        return false;
    }

    /** tig_file_fopen (loose files only; archive entries are read via readBytes). */
    public static Handle fopen(String path, String mode) {
        File f = resolve(path);
        if (f == null && mode != null && mode.contains("w")) {
            f = new File(ROOTS.isEmpty() ? "." : ROOTS.get(0).getPath(), path);
        }
        if (f == null) {
            return null;
        }
        try {
            Handle h = new Handle();
            h.raf = new RandomAccessFile(f, mode != null && mode.contains("w")
                    ? "rw" : "r");
            h.path = f.getPath();
            return h;
        } catch (IOException e) {
            return null;
        }
    }

    public static int fclose(Handle stream) {
        if (stream != null && stream.raf != null) {
            try {
                stream.raf.close();
            } catch (IOException ignored) {
                return -1;
            }
        }
        return 0;
    }

    public static int filelength(Handle stream) {
        try {
            return stream == null ? -1 : (int) stream.raf.length();
        } catch (IOException e) {
            return -1;
        }
    }

    private static File resolve(String path) {
        File direct = new File(path);
        if (direct.isAbsolute() && direct.exists()) {
            return direct;
        }
        for (File root : ROOTS) {
            if (root.isDirectory()) {
                File candidate = new File(root, Compat.windowsPathToNative(path));
                if (candidate.exists()) {
                    return candidate;
                }
            }
        }
        return direct.exists() ? direct : null;
    }
}
