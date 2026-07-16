# Spec: parsing & rendering `.sec` sector objects (scenery / NPCs / items)

> **Status: object parsing + rendering DONE.** New Game opens on the campaign's
> START_MAP — the IFS Zephyr crash site — with its 317 scenery objects drawn
> depth-sorted with the player. The terrain layer is in `tile-rendering-spec.md`.
> This spec is the full `.sec` object-list byte format reverse-engineered from the
> C engine, plus the on-screen anchoring formula.
>
> **Where the campaign maps live (settled 2026-07-16 — supersedes earlier notes):**
> `modules\Arcanum.dat` (340 MB) — a normal `.dat` holding **1680 `.sec` across 81
> maps** (`maps\<name>\<id>.sec` + `map.prp`), plus its own `Rules\MapList.mes`.
> Earlier sessions concluded "no campaign maps ship in the archives" **only because
> `GameData` never registered that module archive** — it now does, first, so module
> content wins (as when the engine mounts a module). The obfuscated loose files
> under `modules\Arcanum\maps\` were a red herring; nothing needs decrypting.
> **No decryption is needed:** the engine reads plaintext `.sec` (grep of
> `src/game` shows no xor/decrypt).
>
> Verify targets: the start sector `maps\Arcanum1-024-fixed\86570436012.sec`
> (317 objects, 18 distinct art ids, all resolve) and every archived terrain
> template with objects (165 sectors, all byte-exact). Note
> `modules\Arcanum\maps\ShopMap\0.sec` has an **empty** static object list — its
> mobiles are sibling `.mob` files — so it is a poor object fixture.
>
> **Correctness oracle:** the object list stores its element count as a trailing
> `int32` at end-of-file. A parser is byte-exact iff, after reading `cnt` objects,
> the stream position is exactly `fileLength - 4` and that trailing `int32 == cnt`.
>
> Source of truth (C, in `arcanum-ce/src/game/`):
> `sector.c` (`sector_load_game` @0x4D1A30, `sector_load_editor`),
> `sector_object_list.c` (`objlist_load`), `obj.c` (`obj_read`,
> `obj_inst_read_file`, `obj_proto_read_file`, `sub_40A8A0`/`sub_40A400`/
> `sub_40C030` field metadata, `obj_version_read_file`), `obj_private.c`
> (`obj_data_read_file_fast`, `bitset_read_file`), `sa.c` (`sa_read_no_dealloc`),
> `obj.h`/`obj_id.h`/`obj_private.h` (enums & structs), `object.c`
> (`object_get_rect` @~0x442xxx — the draw/anchor formula).

---

## 1. How the game loads a sector (`sector_load_game`)

A live sector is assembled from two numeric-`SECTOR_MAKE(x,y)`-id-named files:

- **`<sector_base_path>\<id>.sec`** — the base sector. If absent, falls back to
  `terrain_sector_path(id)` (the terrain *template* sector), and if that is also
  absent, `terrain_fill()` generates flat terrain.
- **`<sector_save_path>\<id>.dif`** — an optional *difference file*
  (`DifferenceFileFlags`: `DIF_HAVE_LIGHT_LIST` / `_TILE_LIST` / …) that patches
  lights/tiles/roofs/objects on top of the base. Campaign content lives here plus
  in loose `.mob` object files.

The port currently loads a single `.sec` and ignores `.dif`. That is enough to
render a self-contained sector such as `ShopMap/0.sec`.

## 2. `.sec` on-disk section order (little-endian throughout)

Read sequentially. Sizes in bytes. From `sector_load_editor`/`sector_load_game`.

| # | Section | Bytes |
|---|---------|-------|
| 1 | **Lights** | `int32 count`, then `count × 48` (each `LightSerializedData`, 0x30) |
| 2 | **Tiles** | `4096 × uint32` = 16384 (the 64×64 tile `art_id` grid) |
| 3 | **Roofs** | `int32 empty`; if `empty == 0`, `256 × uint32` = 1024; else nothing |
| 4 | **Placeholder** | `int32` version in `0xAA0000..0xAA0004` |
| 5a | Tile scripts *(if placeholder ≠ 0xAA0000)* | `int32 count`, then `count × 24` (`TileScriptListNodeSerializedData`: flags,id,`Script`,next) |
| 5b | Sector scripts *(≥ 0xAA0002)* | one `Script` = 12 (flags u32, counters u32, num i32) |
| 5c | Townmap block *(≥ 0xAA0003)* | `townmap_info` i32 + `aptitude_adj` i32 + `light_scheme` i32 + `SectorSoundList`(12) = 24 |
| 5d | Block list *(≥ 0xAA0004)* | `128 × uint32` = 512 (tile-blocking mask) |
| 6 | **Object list** | see §3 |

## 3. Object list (`objlist_load`)

The element count is at the **end** of the file:

```
seek(fileLength - 4); cnt = int32; seek(back to objlist start)
repeat cnt times: obj_read()
int32 trailing = int32   // == cnt; position now == fileLength - 4  ← the oracle
```

Each object's `OBJ_F_LOCATION` is stored **sector-relative** (tile within the
64×64 grid); the loader rebases it to world coordinates via the sector id. For a
single-sector view the raw tile index is what you render at.

## 4. One object (`obj_read` → `obj_inst_read_file`)

```
int32 version                 // must == OBJ_FILE_VERSION (119)
ObjectID prototype_oid        // 24 bytes; if .type == -1 (OID_TYPE_BLOCKED) → proto path (§4a)
ObjectID oid                  // 24 bytes
int32 objType                 // ObjectType (see below)
int16 num_fields              // 2 bytes! `num_fields` is int16_t in struct Object
                              //   (obj.c), written via sizeof → 2 bytes. Ignore value.
int32 field_48[wordCount(objType)]   // "which fields are overridden" bitmap
// then, for each field in objType's enumeration order, if its dif-bit is set:
//   read one value of that field's wire type (§5)
```

`ObjectID` = 24 bytes: `int16 type, int16 pad, int32 pad, union{…}` (16-byte
union). `OID_TYPE_BLOCKED = -1`, `OID_TYPE_NULL = 0`.

`ObjectType`: WALL=0, PORTAL=1, CONTAINER=2, SCENERY=3, PROJECTILE=4, WEAPON=5,
AMMO=6, ARMOR=7, GOLD=8, FOOD=9, SCROLL=10, KEY=11, KEY_RING=12, WRITTEN=13,
GENERIC=14, PC=15, NPC=16, TRAP=17 (MONSTER=18, UNIQUE_NPC=19 possible).

### 4a. Prototype path (`obj_proto_read_file`) — rare in sectors
When `prototype_oid.type == -1`: read `oid`(24), `objType`(i32), `field_4C`
(= `wordCount` × i32), then read **every** field of the type's enumeration
unconditionally (protos are not dif-gated). Sector objects are normally instances,
so the oracle will flag it if a proto ever appears.

## 5. Field wire types (`obj_data_read_file_fast`)

Per `ObjDataType`:

- **INT32 (3)** — 4 bytes (no presence byte).
- **INT64 (4)** — 1 presence byte; if ≠0, 8 bytes.
- **STRING (11)** — 1 presence byte; if ≠0: `int32 size`, then `size+1` bytes.
- **HANDLE (12)** — 1 presence byte; if ≠0, 24 bytes (an `ObjectID`).
- **Arrays** — INT32_ARRAY(5) / INT64_ARRAY(6) / UINT32_ARRAY(7) / UINT64_ARRAY(8)
  / SCRIPT_ARRAY(9) / QUEST_ARRAY(10) / HANDLE_ARRAY(13): 1 presence byte; if ≠0
  a **SizeableArray** (below).
- **PTR (14) / PTR_ARRAY (15)** — never serialized (transient fields only, never
  enumerated). An error if hit.

**SizeableArray** (`sa_read_no_dealloc`): `int32 size` (element bytes),
`int32 count`, `int32 bitset_id` (value ignored), then `size × count` data bytes,
then a **bitset** (`bitset_read_file`): `int32 bcnt`, then `bcnt × 4` bytes.

## 6. Field metadata (`sub_40A8A0` + `sub_40A400` + `sub_40C030`)

There are 314 "normal" fields, ordinals `OBJ_F_BEGIN=0 … OBJ_F_TOTAL_NORMAL=313`.
Fields are grouped into 21 sections delimited by `*_BEGIN` (`OD_TYPE_BEGIN`) and
`*_END` (`OD_TYPE_END`) markers, which are not read as values. Each real field
has an `ObjDataType` and a dif-bit address `(change_array_idx, bit)` into
`field_48`.

**Section (begin, end) ordinals:** COMMON 0–37, WALL 38–44, PORTAL 45–54,
CONTAINER 55–67, SCENERY 68–75, PROJECTILE 76–85, ITEM 86–110, WEAPON 111–139,
AMMO 140–148, ARMOR 149–162, GOLD 163–170, FOOD 171–177, SCROLL 178–184,
KEY 185–191, KEY_RING 192–199, WRITTEN 200–209, GENERIC 210–216, CRITTER 217–251,
PC 252–278, NPC 279–305, TRAP 306–312. Useful named ordinals:
`CURRENT_AID = 1` (INT32), `LOCATION = 2` (INT64).

**Dif-bit layout** (`sub_40A400`): walking fields `0..313` in order, tracking the
current section base:

- On a `*_BEGIN` at `fld`: `base = 0` for COMMON; `base = CAI[36]+1` for the
  group {WALL,PORTAL,CONTAINER,SCENERY,PROJECTILE,ITEM,CRITTER,TRAP}; `CAI[109]+1`
  for {WEAPON…GENERIC}; `CAI[250]+1` for {PC,NPC}. (`CAI[36]`/`CAI[109]`/`CAI[250]`
  are the last COMMON/ITEM/CRITTER fields, already computed by then.)
- For a normal field: `idx = fld - sectionBegin - 1`;
  `change_array_idx = idx/32 + base`; `bit = idx % 32`.

Because the group sections all share one base, only one section is ever live per
object, so `field_48` stays compact. `wordCount(objType) = 1 + max(CAI[fld])`
over the fields that type enumerates.

**Enumeration order per type** (`object_inst_enumerate_overridden_fields`): always
COMMON first, then — item types → ITEM then the specific item section; critter
types (PC/NPC) → CRITTER then PC/NPC; everything else → just its own section.
Within a `[begin,end)` range iterate `fld = begin+1 … end-1`; read the field iff
`(field_48[CAI[fld]] & (1 << BIT[fld])) != 0`.

## 7. Drawing an object (`object_get_rect`)

Objects anchor to their tile via the art frame's **hotspot**, not its top-left:

```
tileScreen = iso projection of the object's tile (same transform as terrain)
base_x = tileScreen.x + OFFSET_X + 40      // +40,+20 = tile centre (tiles are 80×40)
base_y = tileScreen.y + OFFSET_Y + 20
draw_x = base_x - hot_x                     // hot_x/hot_y from tig_art_frame_data
draw_y = base_y - hot_y
```

`CURRENT_AID` gives the sprite `art_id` (→ path/rotation/frame/palette via the
normal `TigArt` pipeline). `OFFSET_X/Y` default 0. The java-port exposes the
hotspot through `TigArt.frameHotspot(artId, int[2])` and `ArtFile.Frame.hotX/hotY`.

**Draw order:** terrain first (all tiles, back-to-front by `x+y`), then objects +
the player merged into one depth-sorted pass by tile `x+y` (ties: `y`), so nearer
objects overlap farther ones and the player is correctly occluded.

## 8. Java classes (this port)

- `ObjectFields` — static field metadata: `TYPE[314]`, computed `CAI[]`/`BIT[]`,
  `wordCount(objType)`, per-type enumeration ranges.
- `GameObject` — a parsed object (`type`, `location`, `currentAid`, generic field
  access).
- `ObjReader` — `obj_read` (instance path; proto path detected).
- `SectorFile` — extended to parse §2 through the object list, exposing
  `List<GameObject> objects` alongside the existing tile grid.
- `MapWorldScreen` — renders the objects per §7 (integration step).

Verification: a JUnit test / `tools` main parses `ShopMap/0.sec` and asserts the
§0 oracle, then prints object count + a sample of `(type, tile, aid)`.
