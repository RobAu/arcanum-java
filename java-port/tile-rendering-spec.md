# Spec: rendering a real Arcanum map sector (terrain tiles)

> **Status: terrain rendering with seams DONE.** Menu "New Game" → `MapWorldScreen`
> loads a real `.sec` and renders its 4096 tiles isometrically from the `.dat`
> archives, including two-terrain edge blends in "X to Y" sectors (verified
> headlessly on plains/desert base sectors and the desert→plains seam).
> Implemented: `ArtId` tile accessors (+ `tileFlippable`), `TileNames`
> (tilename.mes, incl. `edgeIndex`/`sub_4EB7D0`), `TileArtResolver`
> (`a_name_tile_aid_to_fname`/`build_tile_file_name`, base **and** two-name blend
> branches), flippable-tile mirroring in `TigArt.draw` (`tig_art_blit`),
> `SectorFile` (.sec tile layer), `Location` (iso math), `MapWorldScreen`,
> `ScreenManager`. Tools: `tools.MapLister`, `tools.SecDump`, `tools.TileEdgeCheck`.
> Tests: `TileRenderingTest`. **Remaining: FACADE ids (type 11) in the tile array
> — cliff/mountain faces, currently unresolved; then objects / roofs / lighting /
> walls, then multi-sector scrolling.** Note: no `maps\*\map.prp` ship in the base
> archives — only `terrain\` templates, so real map loading needs module data.
>
> Goal: load a real `.sec` sector file from the `.dat` archives and render its
> terrain tiles in the correct isometric layout, reachable from the menu's
> "New Game". This is the "real game data" path (chosen over a synthetic iso
> demo). Reuses the proven `TigArt` decode/draw pipeline.
>
> Source of truth (C, in `arcanum-ce/src/game/` and `first_party/tig/src/`):
> `sector.c`, `sector_tile_list.c`, `sector_light_list.c`, `light.c`,
> `tile.c`, `location.c`, `map.c`, `a_name.c`, `art.c`.

---

## 1. Coordinate systems

All integer math, little-endian on disk.

| Macro | Definition | Notes |
| --- | --- | --- |
| `LOCATION_MAKE(x,y)` | `x \| (y << 32)` | a *location* = one tile, 64-bit (`location.h:40`) |
| `LOCATION_GET_X(l)` | `l & 0xFFFFFFFF` | tile X (world) |
| `LOCATION_GET_Y(l)` | `(l >> 32) & 0xFFFFFFFF` | tile Y (world) |
| `SECTOR_MAKE(x,y)` | `x \| (y << 26)` | a *sector* id, 64-bit (`sector.h`) |
| `SECTOR_X(a)` | `a & 0x3FFFFFF` | sector X (26 bits) |
| `SECTOR_Y(a)` | `(a >> 26) & 0x3FFFFFF` | sector Y |
| `TILE_MAKE(x,y)` | `x \| (y << 6)` | tile index *within* a sector (`tile.h:24`) |

A **sector is 64×64 tiles** = 4096 tiles.

```
sector_id_from_loc(loc)  =  SECTOR_MAKE(LOCATION_GET_X(loc) >> 6,
                                        LOCATION_GET_Y(loc) >> 6)   // sector.c
tile_index_in_sector(loc) = TILE_MAKE(LOCATION_GET_X(loc) & 0x3F,
                                       LOCATION_GET_Y(loc) & 0x3F)  // tile.c:134
   => index = tile_x + tile_y*64,  art_ids[index]                  // tile.c:159
```

So the world tile at `(wx, wy)` lives in sector `(wx>>6, wy>>6)` at array index
`(wx & 63) + (wy & 63)*64`.

## 2. Isometric projection (`location.c:135 location_xy`, ISOMETRIC view)

```
sx = origin_x + 40 * (Y - X - 1)
sy = origin_y + 20 * (Y + X)
```
where `X = LOCATION_GET_X(loc)`, `Y = LOCATION_GET_Y(loc)`. Tiles are **80×40 px**
diamonds (`tile.c:315`). `origin_x/origin_y` is the scroll origin
(`location_origin_set`, centered via `location_center_get`). The inverse is
`location_at` (`location.c:147`): `dy=sy-oy; dx=(sx-ox)>>1; X=(dy-dx)/40; Y=(dy+dx)/40`.

A tile sprite is blitted with its **top-left** at `(sx, sy)` (after our usual
top-left→libGDX flip in `TigArt.draw`). The diamond for world tile `(X,Y)`
therefore occupies screen rows around `sy`.

## 3. `.sec` file format

Path: `<base_path>\<id>.sec`, `id` = the decimal `uint64` sector id
(`sector.c:1467`, `SDL_ulltoa(id, …, 10)`). `base_path` = `maps\<mapname>`
(`map.c`, `sector_map_name_set`). Read order in `sector_load_game`
(`sector.c:1453`):

1. **Light list** — `sector_light_list_load` (`sector_light_list.c`):
   - `int32 count`
   - `count` × **`LightSerializedData` = 48 bytes (0x30)** each (`light.c:30`,
     `static_assert == 0x30`). Layout: `i64 obj; i64 loc; i32 offset_x; i32
     offset_y; u32 flags; u32 art_id; u8 r; u8 b; u8 g; (pad 1); u32 tint_color;
     i32 palette; i32 pad;`
   - → **to skip lights: read `count` (i32), seek forward `count*48` bytes.**
2. **Tile list** — `sector_tile_list_load` (`sector_tile_list.c:64` →
   `_internal`): **4096 × `uint32` `tig_art_id_t`**, read raw. `art_ids[4096]`.
3. Roof list, then `int32 placeholder` ∈ `[0xAA0000, 0xAA0004]`, then objects —
   **not needed for terrain**; we stop after the tile list.

(The optional per-sector `.dif` "difference" file under the save path overrides
tiles; ignored for read-only rendering. Sectors that don't exist on disk are
procedurally `terrain_fill`-ed — out of scope; we only render existing `.sec`.)

## 4. Tile `art_id` → file path (`a_name.c`, art type `TILE`)

A tile `art_id` (type `TIG_ART_TYPE_TILE = 0`) encodes a blend of **two** terrain
names. Bit layout (`art.c`):

| Field | Expr | Const |
| --- | --- | --- |
| type | `id>>28` | `ART_ID_TYPE_SHIFT=28` |
| num1 | `(id>>22) & 63` | `TILE_ID_NUM1_SHIFT=22`, `TILE_ID_MAX_NUM=64` |
| num2 | `(id>>16) & 63` | `TILE_ID_NUM2_SHIFT=16` |
| tile type | `(id>>8) & 1` | `TILE_ID_TYPE_SHIFT=8`, `TILE_ID_MAX_TYPE=2` (0=indoor,1=outdoor) |
| flippable1 | `(id>>7) & 1` | `TILE_ID_FLIPPABLE1_SHIFT=7` |
| flippable2 | `(id>>6) & 1` | `TILE_ID_FLIPPABLE2_SHIFT=6` |
| flags | `id & 0xF` | `tig_art_id_flags_get` (TILE/WALL/PORTAL/ROOF only) |

Two blend/variation indices (`a_name.c:276-277`):
```
v1 = sub_503700(id):  a = (id>>12) & 0xF;  if (flags&1) a = REMAP_8C0[a];  return a;
v2 = sub_5037B0(id):  b = (id>>9)  & 7;
                      if (REMAP_8C0[v1] == REMAP_880[v1] && (flags&1)) b += 8;
                      return b;
```
Remap tables (`art.c:181,201`):
```
REMAP_880 = {0,1,8,3,4,5,6,7,8,3,10,11,6,7,14,15}   // dword_5BE880
REMAP_8C0 = {0,1,2,9,4,5,12,13,2,9,10,11,12,13,14,15} // dword_5BE8C0
```

**Name lookup** `sub_4EB0C0(num, tileType, flippable)` (`a_name.c:357`) picks a
name string from one of four tables by `(flippable?, tileType?)`:
`outdoor_flippable | outdoor_non_flippable | indoor_flippable |
indoor_non_flippable`, index `num` (bounds-checked).

**Filename** `build_tile_file_name(name1, name2, a3=v1, a4=v2)` (`a_name.c:291`):
```
static charset = "06b489237ea5dc10"            // off_5BB4E4, indexed by blend
if (a4 >= 8) a4 -= 8;                            // variation letter
if (a3 == 15 || strcasecmp(name1,name2)==0)      // single terrain
    -> "art\tile\%sbse%c%c.art", name1, charset[a3], 'a'+a4
else if (a3 == 0)
    -> "art\tile\%sbse%c%c.art", name2, charset[0], 'a'+a4
else if (!index_of(name1))                       // name1 not an "edge" name
    -> "art\tile\%sbse%c%c.art", name1, charset[a3], 'a'+a4
else if (!index_of(name2))
    -> "art\tile\%sbse%c%c.art", name2, charset[15-a3], 'a'+a4
else if (idx(name1) < idx(name2))
    -> "art\tile\%s%s%c%c.art", name1, name2, charset[a3],    'a'+a4
else
    -> "art\tile\%s%s%c%c.art", name2, name1, charset[15-a3], 'a'+a4
```
`index_of(name)` = `sub_4EB7D0` — looks the name up in the **tile-edges** table
(`load_tile_edges`, from the same `.mes`); "not found" ⇒ treat as a base/ground
name. For the common ground tiles `name1==name2`, so we hit the first branch and
only need the name tables, not edges. (Edge/blend tiles need `load_tile_edges`;
can be a follow-up — render base tiles first, add blends after.)

### Tile name tables — `tilename.mes` (`a_name.c:425 count_tile_names`, `:435 load_tile_names`)

Load `art\tile\tilename.mes`. Entry-number ranges define the four tables:

| Range | Table |
| --- | --- |
| 0–99 | outdoor flippable |
| 100–199 | outdoor non-flippable |
| 200–299 | indoor flippable |
| 300–399 | indoor non-flippable |

Within each range entries are appended in order (index resets to 0 at 100/200/300).
Each entry string is parsed for the **name** + flags + sound:
- If it contains `'/'`: name = text before `'/'`; after it, flag chars
  (`s/b/f/i/n/p` → sinkable/block/flyable/slippery/natural/soundproof) until a
  space, then `atoi(rest)` = sound type.
- Else: name = **first 3 chars** (`str[3]='\0'`), sound = 0.

We only need the **name** strings (3-letter tile codes like `grs`, `dirt` etc.).
`mes_entries_count_in_range(mes, lo, hi)` counts entries with `lo ≤ num ≤ hi`.

## 5. Map layout (`map.c:579 map_open`)

`maps\<mapname>\` contains:
- `map.prp` — `MapProperties` (binary; has `width`, `height`,
  `base_terrain_type`; `location_limits_set(width,height)`,
  `sector_limits_set(width>>6, height>>6)`).
- `startloc.txt` (optional) — start `x y`.
- `<id>.sec` per sector, `<id>.dif` per modified sector (save dir).

Start view: `location_origin_set(location_center_get())`. For our viewer we can
pick the start location (or map center), find its sector, and render that sector
(plus neighbours later).

---

## 6. Implementation plan (Java, `com.arcanum.ce`)

1. **`ArtId` tile accessors** (`tig/art/ArtId.java`): `tileType`, `tileNum1`,
   `tileNum2`, `tileFlippable1`, `tileFlippable2`, `tileFlags`, `tileBlend()`
   (=v1), `tileVariation()` (=v2), with the two remap tables. Unit-test against
   known ids.
2. **`TileNames`** (`game/`): load `art\tile\tilename.mes` via `Mes`, build the
   four name tables (needs `Mes.entriesCountInRange` / iteration — verify the
   Java `Mes` API; add if missing). Implement `nameOf(num,type,flippable)`.
3. **`TileArtResolver`** (`game/`): `art_id → "art\tile\…art"` via
   `build_tile_file_name` (base-tile branches first; edges optional). Register
   for `TYPE_TILE` in `NameResolver`/`TigArt.buildPath`.
4. **`SectorFile`** (`game/`): parse a `.sec` `byte[]` (from `TigFile`/dat):
   skip light list (`count` then `count*48`), read `4096` tile `art_id`s.
5. **`Location`** (`game/`): the `LOCATION_*`/`SECTOR_*`/`TILE_*` math +
   `locationXy(loc, originX, originY)` iso projection and inverse.
6. **`MapWorldScreen`** (`ui/`): a `Screen` that, given a map dir + a sector,
   draws each of the 4096 tiles at its iso `(sx,sy)` with `TigArt.draw(artId,…)`.
   Iterate in draw order (back-to-front: increasing `X+Y`). Arrow/drag scroll
   adjusts `origin_x/origin_y`. Reachable from `MainMenuScreen` "New Game"
   (replace the `UNIMPLEMENTED` action), with Esc → back to menu.
7. **Verify** headlessly with `-Darcanum.screenshot=` against a real map; iterate
   on tile correctness. Add JUnit tests for the pure pieces (ArtId tile decode,
   `build_tile_file_name`, `.sec` parse against a synthetic fixture).

### Open items / follow-ups
- Tile-edge blending (`load_tile_edges` + `sub_4EB7D0` + the two-name branches)
  — needed for seams between terrain types; base tiles render without it.
- Objects, roofs, lighting, walls — later passes.
- Procedural `terrain_fill` for missing sectors — out of scope.
- Pick a concrete test map: enumerate `maps\*\map.prp` in the archives.
