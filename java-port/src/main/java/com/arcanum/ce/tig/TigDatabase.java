package com.arcanum.ce.tig;

import java.io.File;
import java.io.IOException;

import com.arcanum.ce.tig.database.DatArchive;

/**
 * Compressed {@code .dat} archive access. Maps {@code tig/database.h}.
 *
 * The actual archive parsing/inflation lives in
 * {@link com.arcanum.ce.tig.database.DatArchive} (ported & tested against the
 * Python reference). This class is the thin TIG-facing facade the generated code
 * calls; a {@code TigDatabase*} handle is a {@link DatArchive}.
 */
public final class TigDatabase {

    private TigDatabase() {
    }

    /** tig_database_open */
    public static DatArchive open(String path) {
        try {
            return new DatArchive(new File(path));
        } catch (IOException e) {
            TigDebug.println("tig_database_open failed: " + path + " (" + e + ")");
            return null;
        }
    }

    /** tig_database_close */
    public static boolean close(DatArchive database) {
        if (database == null) {
            return true;
        }
        try {
            database.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** tig_database_get_entry: returns the entry, or null if absent. */
    public static DatArchive.Entry getEntry(DatArchive database, String path) {
        return database == null ? null : database.get(path);
    }

    /** Convenience: read & inflate a whole entry. */
    public static byte[] readAll(DatArchive database, String path) {
        try {
            return database == null ? null : database.read(path);
        } catch (IOException e) {
            return null;
        }
    }
}
