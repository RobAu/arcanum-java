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

    /**
     * List the names of files directly inside {@code dir} carrying {@code suffix},
     * across the whole repository stack. Ports the repository branch of
     * {@code tig_file_list_create} (tig/file.c) for the fixed-directory,
     * fixed-extension patterns the game actually uses -- e.g. proto.c's
     * {@code tig_file_list_create(&file_list, "proto\\*.pro")}.
     *
     * <p>Matches the C in the two ways that matter. First, dedup and order: the
     * result is a case-insensitively sorted set of bare filenames, exactly the
     * array {@code tig_file_list_add} builds (it binary-searches with
     * {@code SDL_strcasecmp}, returns early on a hit -- so the first repository
     * to supply a name wins -- and otherwise inserts in sorted position). Callers
     * that let later reads overwrite earlier ones therefore resolve collisions
     * the same way the C does.
     *
     * <p>Second, precedence: loose roots are walked before archives, matching
     * both {@link #readBytes} and the C, where
     * {@code tig_file_repository_add_native} pushes each new repository onto the
     * head of the list and {@code gamelib_load_data} adds the loose {@code data}
     * directory <em>after</em> the {@code arcanum*.dat} archives -- so loose
     * files deliberately win.
     *
     * <p>Returned names are bare filenames (no directory), as in
     * {@code TigFileInfo.path}; join them onto {@code dir} to read.
     *
     * @param dir    windows-style directory, no trailing separator (e.g. {@code "proto"})
     * @param suffix case-insensitive filename suffix (e.g. {@code ".pro"})
     */
    public static List<String> list(String dir, String suffix) {
        // A case-insensitively sorted, first-wins map keyed on the bare filename
        // *is* tig_file_list_add's entry array.
        java.util.Map<String, String> seen =
                new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String lowerSuffix = suffix.toLowerCase();

        for (File root : ROOTS) {
            File d = new File(root, Compat.windowsPathToNative(dir));
            File[] files = d.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(lowerSuffix)) {
                    seen.putIfAbsent(f.getName(), f.getName());
                }
            }
        }

        // Match on the archive's lowercase key (DatArchive.Entry.path), but return
        // the name in its real stored case (rawPath), as tig_file_list_create does
        // -- callers such as script_name_build_scr_name build paths out of it.
        // Keep only immediate children of `dir` (no nested subdirs).
        String prefix = dir.replace('\\', '/').toLowerCase() + "/";
        for (DatArchive a : ARCHIVES) {
            for (DatArchive.Entry e : a.files()) {
                String p = e.path;
                if (!p.startsWith(prefix) || !p.endsWith(lowerSuffix)) {
                    continue;
                }
                String name = e.rawPath.substring(prefix.length());
                if (name.indexOf('/') < 0) {
                    seen.putIfAbsent(name, name);
                }
            }
        }
        return new ArrayList<>(seen.values());
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
