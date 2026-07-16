package com.arcanum.ce.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.arcanum.ce.GameData;
import com.arcanum.ce.tig.TigFile;

/**
 * Verifies prototype loading ({@link ProtoStore}) and the prototype field
 * fallback ({@link GameObject#resolved}) — the port of {@code proto_init}
 * ({@code proto.c}) and {@code obj_field_fetch} ({@code obj.c}).
 *
 * <p>The {@link ObjectID} checks are pure logic and always run. The rest needs
 * the real install (not shipped with the repo) and is skipped without it, like
 * {@code SectorObjectListTest} / {@code MapMobilesTest}.
 */
class ProtoStoreTest {

    private static final String INSTALL = System.getProperty("arcanum.data",
            System.getProperty("user.home")
            + "/.local/share/Steam/steamapps/common/Arcanum/Arcanum");

    /** The worked example from the .pro format analysis: 464 bytes, type FOOD. */
    private static final String FOOD_PRO = INSTALL + "/data/proto/010143 - Food.pro";
    private static final int FOOD_PROTO_NUMBER = 10143;

    /** 1426 loose .pro ship in data\proto; Arcanum5.dat adds a few more. */
    private static final int MIN_EXPECTED_PROTOS = 1426;

    private static final String START_MAP = "Arcanum1-024-fixed";

    private static boolean dataAvailable() {
        return Files.isDirectory(Paths.get(INSTALL, "data", "proto"));
    }

    @BeforeAll
    static void registerRepositories() {
        if (!dataAvailable()) {
            return;
        }
        // TigFile's repository stack is process-global; start from a clean one so
        // this doesn't depend on (or leak into) other tests.
        TigFile.exit();
        TigFile.init();
        System.setProperty("arcanum.data", INSTALL);
        GameData.discoverAndRegister();
        ProtoStore.reset();
    }

    @AfterAll
    static void clearRepositories() {
        TigFile.exit();
        ProtoStore.reset();
    }

    // -- ObjectID (pure logic, no install needed) -----------------------------

    /**
     * The decisive property: a serialized ObjectID's padding and the union bytes
     * past the active member hold uninitialized garbage. {@code 010143 - Food.pro}
     * really does carry padding_4 = 0x033C85E2 and 12 junk bytes after {@code d.a}.
     * Equality/hashing must look only at the active member, exactly as
     * {@code objid_is_equal} does — otherwise no prototype would ever be found.
     */
    @Test
    void objectIdIgnoresPaddingAndGarbagePastTheActiveMember() {
        ObjectID clean = ObjectID.ofA(FOOD_PROTO_NUMBER);
        // Same d.a, but with junk filling the rest of the union.
        ObjectID garbage = new ObjectID(ObjectID.TYPE_A,
                (0x033C85E2L << 32) | FOOD_PROTO_NUMBER, 0x1CC24AB8033C85DAL);

        assertEquals(FOOD_PROTO_NUMBER, garbage.a());
        assertEquals(clean, garbage);
        assertEquals(clean.hashCode(), garbage.hashCode());
        assertNotEquals(clean, ObjectID.ofA(FOOD_PROTO_NUMBER + 1));
        // Type is part of identity: same bits, different type => different id.
        assertNotEquals(clean, new ObjectID(ObjectID.TYPE_P, FOOD_PROTO_NUMBER, 0));
    }

    @Test
    void objectIdReadsTheCLayout() {
        // type(2) padding_2(2) padding_4(4) union(16) == 24 bytes.
        ByteBuffer b = ByteBuffer.allocate(ObjectID.SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(ObjectID.TYPE_A);
        b.putShort((short) 0x1234);          // padding_2 (garbage)
        b.putInt(0x033C85E2);                // padding_4 (garbage)
        b.putLong(FOOD_PROTO_NUMBER);        // d.a in the union's low int
        b.putLong(-1L);                      // rest of the union (garbage)
        b.flip();

        ObjectID oid = ObjectID.read(b);
        assertEquals(0, b.remaining(), "must consume exactly 24 bytes");
        assertEquals(ObjectID.TYPE_A, oid.type);
        assertEquals(FOOD_PROTO_NUMBER, oid.a());
        assertEquals(ObjectID.ofA(FOOD_PROTO_NUMBER), oid);
    }

    /**
     * {@code objid_is_equal}'s switch has no case for BLOCKED/HANDLE, so it
     * returns false even for identical ids. {@link ObjectID#equals} must not
     * copy that quirk (a non-reflexive equals would break HashMap).
     */
    @Test
    void isEqualCMatchesTheCQuirkButEqualsStaysReflexive() {
        ObjectID blocked = new ObjectID(ObjectID.TYPE_BLOCKED, 0, 0);
        assertFalse(blocked.isEqualC(blocked), "the C returns false for BLOCKED");
        assertEquals(blocked, blocked, "equals must stay reflexive");

        ObjectID a = ObjectID.ofA(7);
        assertTrue(a.isEqualC(ObjectID.ofA(7)));
        assertFalse(a.isEqualC(ObjectID.ofA(8)));
        // OID_TYPE_NULL compares equal regardless of the union.
        assertTrue(new ObjectID(ObjectID.TYPE_NULL, 1, 2)
                .isEqualC(new ObjectID(ObjectID.TYPE_NULL, 3, 4)));
    }

    // -- .pro parsing ---------------------------------------------------------

    /**
     * A real prototype file parses through {@code obj_read}'s prototype branch:
     * its prototype_oid is BLOCKED (it inherits from nothing) and its own oid is
     * OID_TYPE_A carrying the proto number from the filename ({@code %06d - %s.pro}).
     */
    @Test
    void foodProtoParsesWithAnOidTypeA() throws Exception {
        Path p = Paths.get(FOOD_PRO);
        Assumptions.assumeTrue(Files.isReadable(p), "010143 - Food.pro not available: " + p);

        byte[] bytes = Files.readAllBytes(p);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        GameObject food = ObjReader.read(b);

        assertEquals(0, b.remaining(), "a .pro holds exactly one object");
        assertTrue(food.isProto, "prototype_oid must be OID_TYPE_BLOCKED");
        assertTrue(food.prototypeOid.isBlocked());
        assertEquals(ObjectFields.OBJ_TYPE_FOOD, food.type);
        assertEquals(ObjectID.TYPE_A, food.oid.type);
        assertEquals(FOOD_PROTO_NUMBER, food.oid.a());
        // Protos serialize every field, so the art id is always present.
        assertNotEquals(0, food.currentAid(), "a prototype carries its own art id");
    }

    // -- ProtoStore -----------------------------------------------------------

    @Test
    void loadsEveryPrototype() {
        Assumptions.assumeTrue(dataAvailable(), "install not available: " + INSTALL);

        ProtoStore protos = ProtoStore.get();
        assertTrue(protos.size() >= MIN_EXPECTED_PROTOS,
                "expected at least " + MIN_EXPECTED_PROTOS + " prototypes, got "
                        + protos.size());
        assertEquals(List.of(), protos.failures(), "no proto file should fail to parse");

        GameObject food = protos.byNumber(FOOD_PROTO_NUMBER);
        assertNotNull(food, "proto " + FOOD_PROTO_NUMBER + " must be indexed by its oid");
        assertEquals(ObjectFields.OBJ_TYPE_FOOD, food.type);
        assertSameProto(food, protos.proto(ObjectID.ofA(FOOD_PROTO_NUMBER)));
    }

    private static void assertSameProto(GameObject expected, GameObject actual) {
        assertTrue(expected == actual, "lookup by oid must find the same instance");
    }

    /** An unknown prototype id resolves to nothing rather than throwing. */
    @Test
    void unknownProtoResolvesToNull() {
        Assumptions.assumeTrue(dataAvailable(), "install not available: " + INSTALL);
        assertNull(ProtoStore.get().proto(ObjectID.ofA(-12345)));
        assertNull(ProtoStore.get().proto(null));
    }

    // -- the inheritance rule (obj_field_fetch) -------------------------------

    /**
     * The point of the whole exercise: an instance that does not override
     * CURRENT_AID must take it from its prototype. Most of the start map's
     * objects are exactly that case.
     */
    @Test
    void instanceWithoutCurrentAidInheritsItFromItsProto() {
        Assumptions.assumeTrue(dataAvailable(), "install not available: " + INSTALL);

        ProtoStore protos = ProtoStore.get();
        List<GameObject> mobiles = MapMobiles.load(START_MAP);
        Assumptions.assumeFalse(mobiles.isEmpty(), "start map mobiles not available");

        GameObject inheriting = null;
        for (GameObject o : mobiles) {
            if (!o.has(ObjectFields.OBJ_F_CURRENT_AID) && !o.isProto
                    && protos.proto(o.prototypeOid) != null) {
                inheriting = o;
                break;
            }
        }
        assertNotNull(inheriting, "expected some instance to inherit its art id");

        // Raw instance-only access still reports nothing (existing callers rely
        // on field()/currentAid() meaning "what the instance itself stores").
        assertEquals(0, inheriting.currentAid());
        assertNull(inheriting.field(ObjectFields.OBJ_F_CURRENT_AID));

        // Resolved access falls back to the prototype.
        int resolved = inheriting.currentAid(protos);
        assertNotEquals(0, resolved, "must inherit an art id from the prototype");
        assertEquals(protos.proto(inheriting.prototypeOid).currentAid(), resolved);

        // With no store there is nothing to fall back to.
        assertEquals(0, inheriting.currentAid(null));
    }

    /** An instance that DOES override a field keeps its own value, not the proto's. */
    @Test
    void instanceOverrideWinsOverTheProto() {
        Assumptions.assumeTrue(dataAvailable(), "install not available: " + INSTALL);

        ProtoStore protos = ProtoStore.get();
        List<GameObject> mobiles = MapMobiles.load(START_MAP);
        Assumptions.assumeFalse(mobiles.isEmpty(), "start map mobiles not available");

        GameObject overriding = null;
        for (GameObject o : mobiles) {
            if (o.has(ObjectFields.OBJ_F_CURRENT_AID)
                    && protos.proto(o.prototypeOid) != null) {
                overriding = o;
                break;
            }
        }
        assertNotNull(overriding, "expected some instance to override its art id");
        assertEquals(overriding.currentAid(), overriding.currentAid(protos),
                "an overridden field must come from the instance");
    }

    /** A prototype reads its own data even for fields it would otherwise inherit. */
    @Test
    void prototypeResolvesAgainstItself() {
        Assumptions.assumeTrue(dataAvailable(), "install not available: " + INSTALL);

        GameObject food = ProtoStore.get().byNumber(FOOD_PROTO_NUMBER);
        assertNotNull(food);
        // isProto short-circuits the fallback (prototype_oid == BLOCKED in the C).
        assertEquals(food.currentAid(), food.currentAid(ProtoStore.get()));
        assertEquals(food.currentAid(), food.currentAid(null));
    }

    /**
     * The headline regression guard: in the sector a new game opens into, art
     * resolution goes from a minority of objects to all of them once prototypes
     * are consulted.
     */
    @Test
    void protoResolutionMakesTheStartSectorDrawable() {
        Assumptions.assumeTrue(dataAvailable(), "install not available: " + INSTALL);

        MapList maps = MapList.load();
        Assumptions.assumeTrue(maps != null, "MapList (module archive) not available");

        ProtoStore protos = ProtoStore.get();
        List<GameObject> mobiles = MapMobiles.load(maps.startMapName);
        Assumptions.assumeFalse(mobiles.isEmpty(), "start map mobiles not available");

        long startSector = Location.sectorMake(maps.startX >> 6, maps.startY >> 6);
        int inSector = 0;
        int before = 0;
        int after = 0;
        for (GameObject o : mobiles) {
            if (Location.sectorIdFromLoc(o.location(protos)) != startSector) {
                continue;
            }
            inSector++;
            if (o.currentAid() != 0) {
                before++;
            }
            if (o.currentAid(protos) != 0) {
                after++;
            }
        }
        assertEquals(58, inSector, "the start sector's mobile count must not drift");
        assertTrue(after > before, "prototypes must add drawable objects");
        assertEquals(inSector, after,
                "every start-sector mobile should resolve an art id via its proto");
    }
}
