package com.arcanum.ce.tools;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.arcanum.ce.GameData;
import com.arcanum.ce.game.GameObject;
import com.arcanum.ce.game.ObjectFields;
import com.arcanum.ce.game.ProtoStore;
import com.arcanum.ce.tig.TigArt;
import com.arcanum.ce.tig.TigFile;
import com.arcanum.ce.tig.database.DatArchive;

/**
 * Headless diagnostic for the object prototypes ({@code proto\*.pro}, loaded by
 * {@code proto_init} in {@code proto.c}): how many loaded, what failed, and a
 * sample of {@code protoOid -> type / CURRENT_AID / art path}.
 *
 * <p>Also verifies (via {@code --shadow}, on by default) that registering the
 * install's loose {@code data\} root — which {@link GameData} must do for
 * {@code proto\*.pro} to resolve at all — does not shadow any file already
 * supplied by a {@code .dat} archive. Loose roots are searched ahead of
 * archives, so a name present in both would silently change which bytes the
 * engine reads.
 *
 * <p>Run:
 * {@code ./gradlew runTool -Ptool=tools.ProtoDump -Darcanum.data=<dir>}
 */
public final class ProtoDump {

    private static final String[] TYPE_NAMES = {
        "WALL", "PORTAL", "CONTAINER", "SCENERY", "PROJECTILE", "WEAPON", "AMMO",
        "ARMOR", "GOLD", "FOOD", "SCROLL", "KEY", "KEY_RING", "WRITTEN", "GENERIC",
        "PC", "NPC", "TRAP", "MONSTER", "UNIQUE_NPC",
    };

    private ProtoDump() {
    }

    public static void main(String[] args) {
        TigFile.init();
        File dataDir = GameData.discoverAndRegister();
        // Non-system art ids resolve to a path through the game's resolver
        // (name_resolve_path); without it TigArt.buildPath returns null.
        com.arcanum.ce.game.NameResolver.install();

        ProtoStore protos = ProtoStore.get();
        System.out.println("proto files found: " + protos.filesFound());
        System.out.println("prototypes loaded: " + protos.size());
        System.out.println("failed:            " + protos.failures().size());
        for (String f : protos.failures()) {
            System.out.println("  ! " + f);
        }
        System.out.println("re-registered oids (last wins, per obj_pool_perm_oid_set): "
                + protos.overridden().size());
        for (String o : protos.overridden()) {
            System.out.println("  ~ " + o);
        }

        // Breakdown by ObjectType, and how many carry their own art.
        TreeMap<Integer, Integer> hist = new TreeMap<>();
        int withAid = 0;
        for (GameObject p : protos.protos()) {
            hist.merge(p.type, 1, Integer::sum);
            if (p.currentAid() != 0) {
                withAid++;
            }
        }
        System.out.println("\nby ObjectType:");
        for (Map.Entry<Integer, Integer> e : hist.entrySet()) {
            System.out.printf("  %-11s %d%n", typeName(e.getKey()), e.getValue());
        }
        System.out.println("  prototypes carrying a CURRENT_AID: " + withAid
                + " of " + protos.size());

        System.out.println("\nsample protoOid -> type / CURRENT_AID / art path:");
        int shown = 0;
        for (GameObject p : protos.protos()) {
            if (shown++ >= 20) {
                break;
            }
            int aid = p.currentAid();
            String artPath = aid != 0 ? TigArt.buildPath(aid) : null;
            boolean exists = artPath != null && TigFile.exists(artPath, null);
            System.out.printf("  %-10s %-11s 0x%08X %-40s %s%n",
                    p.oid, typeName(p.type), aid, artPath == null ? "-" : artPath,
                    aid == 0 ? "(no art)" : (exists ? "OK" : "MISSING"));
        }

        // The named example from the .pro format work: 010143 - Food.pro.
        GameObject food = protos.byNumber(10143);
        System.out.println("\nproto 10143 (010143 - Food.pro): "
                + (food == null ? "NOT FOUND" : food.toString()));

        if (dataDir != null) {
            checkShadowing(dataDir);
        }
    }

    /**
     * Does the loose {@code data\} root hide anything an archive provides?
     * Walks every file under {@code <data>/data} and asks each registered
     * archive whether it carries the same repository path.
     */
    private static void checkShadowing(File dataDir) {
        File data = new File(dataDir, "data");
        if (!data.isDirectory()) {
            return;
        }
        System.out.println("\nshadow check — files under " + data
                + " that also exist in a .dat archive:");

        // Every archive TigFile would search: the base .dat files AND the
        // campaign module archive (a data\ file shadowing the module would be a
        // real ordering bug -- the C adds the module after data, so the module
        // wins there, whereas TigFile searches all roots before all archives).
        List<File> archiveFiles = new ArrayList<>();
        File[] base = dataDir.listFiles(f -> f.getName().toLowerCase().endsWith(".dat"));
        if (base != null) {
            java.util.Collections.addAll(archiveFiles, base);
        }
        File modules = new File(dataDir, "modules");
        File[] mods = modules.listFiles(f -> f.getName().toLowerCase().endsWith(".dat"));
        if (mods != null) {
            java.util.Collections.addAll(archiveFiles, mods);
        }

        List<DatArchive> archives = new ArrayList<>();
        Map<DatArchive, String> names = new java.util.HashMap<>();
        for (File f : archiveFiles) {
            try {
                DatArchive a = new DatArchive(f);
                archives.add(a);
                names.put(a, f.getName());
            } catch (Exception e) {
                System.out.println("  (skipped unreadable archive " + f.getName() + ")");
            }
        }

        int files = 0;
        int shadowed = 0;
        int protoFiles = 0;
        TreeMap<String, Integer> byArchive = new TreeMap<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(data.toPath())) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                files++;
                String rel = data.toPath().relativize(p).toString()
                        .replace(File.separatorChar, '/').toLowerCase();
                if (rel.startsWith("proto/")) {
                    protoFiles++;
                }
                for (DatArchive a : archives) {
                    if (a.contains(rel)) {
                        shadowed++;
                        byArchive.merge(names.get(a), 1, Integer::sum);
                        if (!rel.startsWith("proto/")) {
                            System.out.println("  SHADOWS " + names.get(a) + ": " + rel);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  walk failed: " + e);
        }
        System.out.println("  " + files + " loose file(s) under data\\ ("
                + protoFiles + " under proto\\); " + shadowed
                + " also exist in an archive (per-archive: " + byArchive + ")");
        // Loose-wins is the engine's intent (gamelib_load_data adds `data` after
        // the archives, and repository_add prepends). The only thing that would
        // be wrong is shadowing the campaign module.
        boolean shadowsModule = byArchive.keySet().stream()
                .anyMatch(n -> n.equalsIgnoreCase(GameData.MODULE_ARCHIVE));
        System.out.println("  shadows the module archive ("
                + GameData.MODULE_ARCHIVE + ")? " + (shadowsModule ? "YES — BUG" : "no ✓"));
        System.out.println("  (shadowing base archives is correct: the C registers"
                + " `data` AFTER arcanum*.dat and repository_add prepends, so loose"
                + " files are searched first by design.)");

        for (DatArchive a : archives) {
            try {
                a.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private static String typeName(int type) {
        return type >= 0 && type < TYPE_NAMES.length ? TYPE_NAMES[type] : "type" + type;
    }
}
