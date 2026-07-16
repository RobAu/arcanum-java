package com.arcanum.ce.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import com.arcanum.ce.GameData;
import com.arcanum.ce.game.Description;
import com.arcanum.ce.game.GameObject;
import com.arcanum.ce.game.Location;
import com.arcanum.ce.game.MapList;
import com.arcanum.ce.game.MapMobiles;
import com.arcanum.ce.game.NameResolver;
import com.arcanum.ce.game.OName;
import com.arcanum.ce.game.ObjectFields;
import com.arcanum.ce.game.ObjectName;
import com.arcanum.ce.game.ProtoStore;
import com.arcanum.ce.game.Sap;
import com.arcanum.ce.game.Script;
import com.arcanum.ce.game.ScriptName;
import com.arcanum.ce.tig.TigFile;

/**
 * Headless diagnostic: for the start map's rendered sector, resolve every
 * critter's name and its dialog script, exercising the whole identify chain that
 * {@code MapWorldScreen}'s click-to-identify uses —
 *
 * <pre>
 * OBJ_F_NAME  --o_name_get-->          oemes\oname.mes      (internal name)
 * OBJ_F_DESCRIPTION --description_get--> mes\description.mes (display name)
 * OBJ_F_SCRIPTS_IDX[SAP_DIALOG] --sa_get--> Script.num
 *                               --script_name_build_dlg_name--> dlg\NNNNNname.dlg
 * </pre>
 *
 * <p>Every field read goes through the prototype fallback ({@code obj_field_fetch}).
 *
 * <p>Run:
 * {@code ./gradlew runTool -Ptool=tools.NpcDump -Darcanum.data=<dir>}
 */
public final class NpcDump {

    /** Virgil's dialog — the acceptance target for the num → .dlg chain. */
    private static final int VIRGIL_DLG = 1324;

    private static final String[] TYPE_NAMES = {
        "WALL", "PORTAL", "CONTAINER", "SCENERY", "PROJECTILE", "WEAPON", "AMMO",
        "ARMOR", "GOLD", "FOOD", "SCROLL", "KEY", "KEY_RING", "WRITTEN", "GENERIC",
        "PC", "NPC", "TRAP",
    };

    private NpcDump() {
    }

    public static void main(String[] args) {
        TigFile.init();
        GameData.discoverAndRegister();
        NameResolver.install();

        ProtoStore protos = ProtoStore.get();
        Description descriptions = Description.get();
        OName onames = OName.get();
        ScriptName scriptNames = ScriptName.get();
        ObjectName names = ObjectName.get(protos);

        System.out.println("protos:       " + protos.size() + " loaded");
        System.out.println("description:  " + (descriptions == null ? "MISSING mes\\description.mes"
                : "mes\\description.mes ok, max num " + descriptions.maxNum()
                  + ", gamedesc " + (descriptions.hasModuleTable() ? "yes" : "no")
                  + ", gamekey " + (descriptions.hasKeyTable() ? "yes" : "no")));
        System.out.println("oname:        " + (onames == null ? "MISSING oemes\\oname.mes"
                : "oemes\\oname.mes ok, " + onames.count() + " entries"));
        System.out.println("script index: " + scriptNames.systemCount() + " system (num>=1000) + "
                + scriptNames.modCount() + " module (1..999)");
        if (names == null) {
            System.err.println("cannot resolve names without mes\\description.mes");
            System.exit(1);
            return;
        }

        // The acceptance test for the num -> path chain, independent of any object.
        String virgilScr = scriptNames.buildScrName(VIRGIL_DLG);
        String virgilDlg = scriptNames.buildDlgName(VIRGIL_DLG);
        System.out.println("\nscript_name chain for num " + VIRGIL_DLG + ":");
        System.out.println("  scr: " + virgilScr
                + (virgilScr != null && TigFile.exists(virgilScr, null) ? "  [exists]" : "  [ABSENT]"));
        System.out.println("  dlg: " + virgilDlg
                + (virgilDlg != null && TigFile.exists(virgilDlg, null) ? "  [exists]" : "  [ABSENT]"));

        MapList maps = MapList.load();
        if (maps == null) {
            System.err.println("no MapList -- the campaign module is not available");
            System.exit(1);
            return;
        }
        long startSector = Location.sectorMake(maps.startX >> 6, maps.startY >> 6);
        System.out.println("\nstart map:    " + maps.startMapName + "  sector " + startSector);

        List<GameObject> critters = new ArrayList<>();
        for (GameObject o : MapMobiles.load(maps.startMapName)) {
            if (Location.sectorIdFromLoc(o.location(protos)) != startSector) {
                continue;
            }
            if (o.type == ObjectFields.OBJ_TYPE_NPC || o.type == ObjectFields.OBJ_TYPE_PC) {
                critters.add(o);
            }
        }
        System.out.println("critters in the rendered sector: " + critters.size());

        // "stranger" is object_examine with pc_obj == NULL (OBJ_F_CRITTER_DESCRIPTION_
        // UNKNOWN); "known" is the reaction_met_before branch (OBJ_F_DESCRIPTION).
        System.out.printf("%n  %-5s %-9s %-8s %-22s %-22s %-20s %-8s %s%n",
                "tile", "type", "name#", "oname (internal)", "display (stranger)",
                "display (known)", "dlg num", "dlg path");
        System.out.println("  " + "-".repeat(126));

        int withDisplayName = 0;
        int withInternalName = 0;
        int withDialog = 0;
        int dlgResolved = 0;
        int dlgExists = 0;
        boolean virgilFound = false;
        TreeMap<Integer, String> dialogsHere = new TreeMap<>();

        for (GameObject o : critters) {
            long loc = o.location(protos);
            int tx = (int) (Location.getX(loc) & 63);
            int ty = (int) (Location.getY(loc) & 63);

            int nameNum = names.nameNum(o);
            String internal = names.internalName(o);
            String display = names.name(o);
            if (display != null) {
                withDisplayName++;
            }
            if (internal != null) {
                withInternalName++;
            }

            Script dlg = o.script(Sap.DIALOG, protos);
            String dlgPath = null;
            boolean exists = false;
            if (dlg != null) {
                withDialog++;
                dlgPath = scriptNames.buildDlgName(dlg.num);
                if (dlgPath != null) {
                    dlgResolved++;
                    exists = TigFile.exists(dlgPath, null);
                    if (exists) {
                        dlgExists++;
                    }
                }
                dialogsHere.put(dlg.num, dlgPath == null ? "<unindexed>" : dlgPath);
                if (dlg.num == VIRGIL_DLG) {
                    virgilFound = true;
                }
            }

            System.out.printf("  %2d,%-2d %-9s %-8d %-22s %-22s %-20s %-8s %s%s%n",
                    tx, ty, typeName(o.type), nameNum,
                    trunc(internal, 22), trunc(display, 22),
                    trunc(names.knownName(o), 20),
                    dlg == null ? "-" : String.valueOf(dlg.num),
                    dlgPath == null ? "-" : dlgPath,
                    dlgPath == null ? "" : (exists ? "  [exists]" : "  [ABSENT]"));
        }

        System.out.println("\nsummary for the rendered start sector:");
        System.out.println("  critters:                       " + critters.size());
        System.out.println("  with a display name:            " + withDisplayName);
        System.out.println("  with an internal (oname) name:  " + withInternalName);
        System.out.println("  with a SAP_DIALOG script:       " + withDialog);
        System.out.println("  whose dlg path resolved:        " + dlgResolved);
        System.out.println("  whose .dlg file exists:         " + dlgExists);

        System.out.println("\n  distinct SAP_DIALOG scripts in this sector:");
        for (java.util.Map.Entry<Integer, String> e : dialogsHere.entrySet()) {
            System.out.println("    " + e.getKey() + " -> " + e.getValue());
        }

        System.out.println("\n  Virgil (dlg " + VIRGIL_DLG + ") among this sector's NPCs: "
                + (virgilFound ? "YES" : "NO"));

        // Which SAP keys the sector's critters actually carry -- evidence that the
        // array is sparse and that SAP_DIALOG is rarely at slot 9.
        System.out.println("\n  SAP keys present on this sector's critters:");
        TreeMap<Integer, Integer> sapHist = new TreeMap<>();
        for (GameObject o : critters) {
            for (int key : o.scriptKeys(protos)) {
                sapHist.merge(key, 1, Integer::sum);
            }
        }
        for (java.util.Map.Entry<Integer, Integer> e : sapHist.entrySet()) {
            System.out.printf("    %-2d %-18s on %d critter(s)%n",
                    e.getKey(), Sap.name(e.getKey()), e.getValue());
        }

        // Not in this sector? Then say where he is, rather than forcing a match.
        if (!virgilFound) {
            System.out.println("\n  searching the whole start map for a SAP_DIALOG of "
                    + VIRGIL_DLG + ":");
            int hits = 0;
            for (GameObject o : MapMobiles.load(maps.startMapName)) {
                Script s = o.script(Sap.DIALOG, protos);
                if (s == null || s.num != VIRGIL_DLG) {
                    continue;
                }
                hits++;
                long loc = o.location(protos);
                System.out.printf("    %s at world (%d,%d) sector %d  oname=%s  display=%s%n",
                        typeName(o.type), Location.getX(loc), Location.getY(loc),
                        Location.sectorIdFromLoc(loc), names.internalName(o), names.name(o));
            }
            System.out.println("    " + hits + " hit(s) on the whole map");
        }

        sparseArrayTrapReport(protos);
    }

    /**
     * How often the sparse-array trap actually bites: compare {@code sa_get}'s
     * rank-resolved SAP_DIALOG against naively reading element {@code [SAP_DIALOG]}
     * of the packed buffer, over every object on the map.
     */
    private static void sparseArrayTrapReport(ProtoStore protos) {
        int withScripts = 0;
        int dialogPresent = 0;
        int keyEqualsSlot = 0;
        int keyDiffersFromSlot = 0;
        int naiveOutOfBounds = 0;

        for (GameObject o : protos.protos()) {
            Object v = o.field(ObjectFields.OBJ_F_SCRIPTS_IDX);
            if (!(v instanceof com.arcanum.ce.game.SizeableArray)) {
                continue;
            }
            com.arcanum.ce.game.SizeableArray sa = (com.arcanum.ce.game.SizeableArray) v;
            withScripts++;
            if (!sa.has(Sap.DIALOG)) {
                continue;
            }
            dialogPresent++;
            int slot = sa.rank(Sap.DIALOG);
            if (slot == Sap.DIALOG) {
                keyEqualsSlot++;
            } else {
                keyDiffersFromSlot++;
            }
            if (Sap.DIALOG >= sa.count) {
                naiveOutOfBounds++;
            }
        }

        System.out.println("\nsparse-array trap, over all " + protos.size()
                + " prototypes (OBJ_F_SCRIPTS_IDX):");
        System.out.println("  protos carrying a SCRIPTS_IDX array: " + withScripts);
        System.out.println("  of those, with SAP_DIALOG present:   " + dialogPresent);
        System.out.println("    where rank(9) == 9 (naive would work):     " + keyEqualsSlot);
        System.out.println("    where rank(9) != 9 (naive reads a DIFFERENT"
                + " script): " + keyDiffersFromSlot);
        System.out.println("    where element[9] is out of bounds entirely: "
                + naiveOutOfBounds + "  (count <= 9)");
    }

    private static String typeName(int type) {
        return type >= 0 && type < TYPE_NAMES.length ? TYPE_NAMES[type] : "type" + type;
    }

    private static String trunc(String s, int max) {
        if (s == null) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
