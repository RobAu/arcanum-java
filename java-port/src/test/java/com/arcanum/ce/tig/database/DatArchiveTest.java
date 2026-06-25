package com.arcanum.ce.tig.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Verifies the Java {@link DatArchive} reader against a synthetic, license-safe
 * archive built by the validated Python reference
 * (c2java/tools/make_dat_fixture.py / dat_extract.py). Exercises both a PLAIN
 * entry and a zlib-COMPRESSED entry, plus path normalization.
 */
class DatArchiveTest {

    private static File copyResourceToTemp(String name) throws Exception {
        Path tmp = Files.createTempFile("synthetic", ".dat");
        try (InputStream in = DatArchiveTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "missing test resource: " + name);
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        tmp.toFile().deleteOnExit();
        return tmp.toFile();
    }

    @Test
    void readsPlainAndCompressedEntries() throws Exception {
        File dat = copyResourceToTemp("/dat/synthetic.dat");
        try (DatArchive a = new DatArchive(dat)) {
            // 2 files (the directory entry is excluded by files()).
            assertEquals(2, a.files().size());

            byte[] hello = a.read("data/hello.txt");
            assertArrayEquals("hello arcanum\n".getBytes("US-ASCII"), hello);

            DatArchive.Entry big = a.get("data/big.txt");
            assertNotNull(big);
            assertTrue(big.isCompressed(), "big.txt should be zlib-compressed");
            byte[] bigData = a.read(big);
            assertEquals(720, bigData.length);   // golden from make_dat_fixture.py
            assertEquals(
                    ("the quick brown fox jumps over the lazy dog. ").repeat(16),
                    new String(bigData, "US-ASCII"));
        }
    }

    @Test
    void normalizesWindowsPathsAndCase() throws Exception {
        File dat = copyResourceToTemp("/dat/synthetic.dat");
        try (DatArchive a = new DatArchive(dat)) {
            // backslashes + uppercase resolve to the normalized entry.
            assertTrue(a.contains("DATA\\HELLO.TXT"));
            assertNotNull(a.get("data/hello.txt"));
        }
    }
}
