package com.arcanum.ce.tig;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Filesystem enumeration. Maps {@code tig/find_file.h}
 * (tig_find_first_file / tig_find_next_file / tig_find_close). Loose-file only;
 * archive enumeration is handled by {@link TigDatabase}.
 */
public final class TigFindFile {

    /** Maps the C {@code TigFindFileData} handle. */
    public static final class Data {
        final Deque<File> pending = new ArrayDeque<>();
        public String name;
        public boolean isDirectory;
        public long size;
    }

    private TigFindFile() {
    }

    public static boolean findFirstFile(String pattern, Data out) {
        File dir = new File(pattern).getParentFile();
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            out.pending.add(f);
        }
        return findNextFile(out);
    }

    public static boolean findNextFile(Data data) {
        File f = data.pending.pollFirst();
        if (f == null) {
            return false;
        }
        data.name = f.getName();
        data.isDirectory = f.isDirectory();
        data.size = f.length();
        return true;
    }

    public static boolean findClose(Data data) {
        data.pending.clear();
        return true;
    }
}
