package com.arcanum.ce;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.arcanum.ce.tig.TigDebug;
import com.arcanum.ce.tig.TigFile;

/**
 * Locates the Arcanum game data directory and registers its {@code .dat}
 * archives with {@link TigFile}'s repository stack.
 *
 * Search order: the {@code arcanum.data} system property, the {@code ARCANUM_DATA}
 * environment variable, then a few well-known relative locations. The game data
 * is not shipped (you must own the game), so this fails gracefully and the app
 * falls back to a placeholder when nothing is found.
 */
public final class GameData {

    /** Load order roughly matches the C engine (tig first, then arcanum1..5). */
    /** The campaign module, under {@code modules\} -- holds the real maps. */
    public static final String MODULE_ARCHIVE = "Arcanum.dat";

    private static final String[] ARCHIVE_ORDER = {
        "tig.dat",
        "arcanum1.dat", "arcanum2.dat", "arcanum3.dat", "Arcanum4.dat", "Arcanum5.dat",
        "ArcanumXAnims1Base.dat", "ArcanumXAnims2Dupes.dat", "ArcanumXAnims3UAP.dat",
        "ArcanumXAmbient.dat",
    };

    private GameData() {
    }

    /** @return the data directory used, or null if none was found. */
    public static File discoverAndRegister() {
        File dir = locate();
        if (dir == null) {
            TigDebug.println("game data not found (set -Darcanum.data=<dir> or "
                    + "ARCANUM_DATA); running with placeholder.");
            return null;
        }
        int n = 0;
        // The campaign module archive first, so its content wins over the base
        // archives (as when the engine mounts a module). This is where the real
        // campaign maps live -- `maps\<name>\<id>.sec` plus its own
        // `Rules\MapList.mes` naming the START_MAP. Without it only the
        // `terrain\` templates are visible.
        File modules = new File(dir, "modules");
        if (modules.isDirectory()) {
            File module = caseInsensitive(modules, MODULE_ARCHIVE);
            if (module != null && TigFile.repositoryAdd(module.getPath())) {
                n++;
                TigDebug.println("registered module archive " + module.getName());
            }
        }
        for (String name : ARCHIVE_ORDER) {
            File dat = caseInsensitive(dir, name);
            if (dat != null && TigFile.repositoryAdd(dat.getPath())) {
                n++;
            }
        }
        // Also register the directory itself for loose files.
        TigFile.repositoryAdd(dir.getPath());
        TigDebug.println("registered " + n + " archive(s) from " + dir);
        return n > 0 ? dir : null;
    }

    private static File locate() {
        List<String> candidates = new ArrayList<>();
        String prop = System.getProperty("arcanum.data");
        if (prop != null) {
            candidates.add(prop);
        }
        String env = System.getenv("ARCANUM_DATA");
        if (env != null) {
            candidates.add(env);
        }
        candidates.add(".");
        candidates.add("..");
        candidates.add("../arcanum-ce-c/out/build/linux-x64-debug");
        for (String c : candidates) {
            File dir = new File(c);
            if (caseInsensitive(dir, "tig.dat") != null) {
                return dir;
            }
        }
        return null;
    }

    private static File caseInsensitive(File dir, String name) {
        File exact = new File(dir, name);
        if (exact.isFile()) {
            return exact;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().equalsIgnoreCase(name)) {
                    return f;
                }
            }
        }
        return null;
    }
}
