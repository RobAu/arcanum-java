package com.arcanum.ce.tools;

import java.io.File;

import com.arcanum.ce.GameData;
import com.arcanum.ce.game.NameResolver;
import com.arcanum.ce.game.Player;
import com.arcanum.ce.tig.TigArt;
import com.arcanum.ce.tig.TigFile;
import com.arcanum.ce.tig.art.ArtId;
import com.arcanum.ce.tig.database.DatArchive;

/** Diagnostic: resolve the player critter art id (per anim/rotation) + check it exists. */
public final class CritterCheck {

    private CritterCheck() {
    }

    public static void main(String[] args) {
        TigFile.init();
        GameData.discoverAndRegister();
        NameResolver.install();

        Player p = new Player(0, 0);
        for (int anim : new int[] {Player.ANIM_STAND, Player.ANIM_WALK}) {
            for (int rot = 0; rot < 8; rot++) {
                p.setAnim(anim);
                p.setRotation(rot);
                int id = p.artId();
                String path = TigArt.buildPath(id);
                boolean exists = path != null && TigFile.exists(path, null);
                System.out.printf("anim=%d rot=%d  id=0x%08X  type=%d  %-28s %s%n",
                        anim, rot, id, ArtId.type(id), path, exists ? "OK" : "MISSING");
            }
        }

        // Which critter art actually ships? List a few candidate dirs/prefixes.
        String[] prefixes = {"art/critter/dfm/dfmbnsa", "art/critter/dfm/dfm",
                "art/critter/hmm/hmm", "art/critter/hmf/hmf"};
        File dir = new File(System.getProperty("arcanum.data", "."));
        File[] dats = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".dat"));
        if (dats == null) {
            return;
        }
        for (String prefix : prefixes) {
            java.util.TreeSet<String> hits = new java.util.TreeSet<>();
            for (File dat : dats) {
                try (DatArchive ar = new DatArchive(dat)) {
                    for (DatArchive.Entry e : ar.files()) {
                        if (e.path.startsWith(prefix)) {
                            hits.add(e.path);
                        }
                    }
                } catch (Exception ignored) {
                    // skip unreadable archive
                }
            }
            System.out.println("\n" + prefix + "* : " + hits.size() + " file(s)");
            hits.stream().limit(16).forEach(h -> System.out.println("  " + h));
        }
    }
}
