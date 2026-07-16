# Resume — Arcanum CE Java port (world view + player)

**Where we are:** menu → **New Game** opens on the **campaign's real start map —
the IFS Zephyr crash site** — rendering its `.sec` terrain **plus the sector's
object list** (317 objects: `Tailblade`/`Junk2`/`RopeSpools` wreckage, blood,
campfire, pines/ferns/bushes — all art resolves), with a controllable player that
**glides** (smooth tween-walk) and a camera that follows. Confirmed playable:
you can walk around the crash site.

> **Settled: the campaign maps are in `modules\Arcanum.dat`** (340 MB; 1680 `.sec`
> across 81 maps + its own `Rules\MapList.mes`). Earlier notes claiming "no
> campaign maps ship in the archives" were **wrong** — `GameData` simply never
> registered the module archive. It now registers it *first* (module wins). The
> obfuscated loose files under `modules\Arcanum\maps\` are a red herring; nothing
> needs decrypting. Start map comes from the `Type: START_MAP` entry in
> `MapList.mes` → `Arcanum1-024-fixed` @ world (92958, 82592) →
> `maps\Arcanum1-024-fixed\86570436012.sec`, spawn tile (30,32).

## Latest work (uncommitted → committed this session)
- **Object parsing** (`object-rendering-spec.md`): full `.sec` object list ported
  — `ObjectFields` (314-field metadata: wire types + dif-bitmap `cai/bit` +
  per-type word counts/ranges, mirroring `sub_40A8A0/40A400/40C030`), `GameObject`,
  `ObjReader` (`obj_read` inst+proto paths; note `num_fields` is **int16**),
  `SectorFile` extended through roofs/scripts/townmap/blocks to the trailing
  objlist. Verified byte-exact against **all 165 archived sectors with objects**
  (trailing-count oracle) + `SectorObjectListTest`.
- **Object rendering** (`MapWorldScreen`): objects + player drawn in one
  depth-sorted pass, anchored by art hotspot (`object_get_rect`:
  `tileScreen + offset + (40,20) − hotspot`); `TigArt.frameHotspot`/`frameCount`.
- **Smooth walk** (`Player`/`MapWorldScreen`): tile-to-tile tween + walk-frame
  cycling; steps gate on tween completion (replaced the frame cooldown).
- `SecDump` extended to dump + art-resolve the object layer (`-Darcanum.sec=`).

- **Campaign start map** (`GameData` module archive + `MapList` porting
  `map_list_info_load`; `MapWorldScreen` resolves START_MAP → sector + spawn
  tile, falls back to a terrain template). Tool: `tools.StartMap` lists the map
  list and reports the start sector; `SecDump` now reads via the registered
  repository (`-Darcanum.sec=` for paths with spaces).

### Next / follow-ups
- **NPCs (Virgil et al.) are missing**: living characters are loose `.mob` files
  (one serialized object each — same `obj_read` format we already have), not part
  of the sector's static object list. Loading `maps\<name>\*.mob` should populate
  them. Note the campaign map dirs on disk are the obfuscated-name files; the
  `.mob`s may live in the module archive too — check `Arcanum.dat` for `.mob`.
- **Multi-sector scrolling**: we render one 64×64 sector, so the map edge is a
  hard stop; the start map has many sectors.
- Object collision (block on WALL/scenery with the blocking flag) — crash-site
  scenery is non-blocking, so it's fully walkable today.
- Facades in this map's tile layer are drawn flat (no hotspot offset) — the big
  wreck/cliff art may sit slightly wrong; see `tile-rendering-spec.md`.
- `.dif` overlays, decode array/handle object fields (consumed, not stored),
  click-to-inspect interaction.

## Committed (on `java-port` branch)
- `447812cf` — real Arcanum font in the menu (`TigFontRenderer`)
- `dc1bb028` — **terrain rendering**: `.sec` parse → tile-id decode →
  `art\tile\*.art` → isometric draw. Classes: `ArtId` tile accessors,
  `TileNames`, `TileArtResolver`, `SectorFile`, `Location`, `MapWorldScreen`,
  `ScreenManager`; tools `MapLister`/`SecDump`; `TileRenderingTest`. Verified on
  plains + desert. See `tile-rendering-spec.md`.
- `fd6cfae9` — **clickable launcher**: `play.sh`, `install-desktop-entry.sh`,
  locally-generated gitignored icon. Installed to app menu + Desktop.
- `547ed9b3` — **controllable player**: `Player` (tile pos, 8-dir facing,
  STAND/WALK anim → critter `art_id`), `Location.locationAt()` inverse
  projection, `MapWorldScreen` rewrite (camera follows player; WASD/arrows
  8-dir walk; left-click walk-to; Esc/right-click back), `CritterCheck` tool.

  **Resolved blocker:** the placeholder DFMBNS dwarf only ships STAND/WALK
  anims for weapon codes B/C, not the unarmed A. `Player.WEAPON = 2` (code C,
  fullest anim set: a,b,d,f,u,v). Art naming =
  `{body}{gender}{armor}{shield}{weapon}{anim}` — see `NameResolver.critterPath`.
- `878b8bcb` — **tile-edge blending**: `TileNames.edgeIndex` (sub_4EB7D0) makes
  two-terrain "X to Y" sectors blend instead of falling back to base terrain;
  `TigArt.draw` mirrors flippable tiles (flip bit → non-flip art drawn FLIP_X,
  like tig_art_blit); `ArtId.tileFlippable`; `TileEdgeCheck` tool;
  `-Darcanum.spawn=x,y`. Every blend tile across the transition templates
  resolves to shipping art; desert→plains seam verified visually.
- `fd1364b9` — **facade resolution** (type 11 in the tile array — cliff/mountain
  faces): `ArtId.facadeNum`, `NameResolver` loads `facadename.mes` →
  `art\Facade\<name>.art`, `Mes.valuesInOrder`. Cliffs render at the mountain
  boundary; 509/1360 facades resolve with 0 missing.
- `a698b9d1` — **walkability/collision**: `TileNames` now parses the terrain flag
  chars in `tilename.mes` (`isBlocking`/`terrainFlags`), `ArtId.facadeWalkable`,
  new `Tile.isBlocking` (`tile_is_blocking`); `MapWorldScreen.step` refuses
  blocked/off-sector tiles. Deep water (`dwr`) and black mountain (`Blk`) block;
  grass/dirt/shallow water don't.

## Notes / gotchas discovered
- **0 real maps** (`maps\<name>\map.prp`) ship in the base `.dat` archives —
  only `terrain\<name>\*.sec` **templates** (used by `terrain_fill`). So the
  world view renders a template sector directly; real map loading will need the
  module/campaign data, not just the base archives.
- Mountain sectors sit on real **`Blk` (black) terrain** tiles under the cliff
  facades — the near-black mountain mass is correct data, not missing art.
- Facades are currently drawn flat like ground tiles (no sprite offset/hotspot,
  no roofs/lighting). Cliffs at boundaries look right; a full mountain interior
  will need facade draw offsets + roof/lighting passes for fidelity.

## Where the campaign map data lives (searched 2026-07-01)
Found the full retail install (Steam):
`/home/raudenaerde/.local/share/Steam/steamapps/common/Arcanum/Arcanum/`
- Its `.dat`s are the **same set** we already use (arcanum1-5, tig, ArcanumX*,
  ArcanumZHighRes*). Grepping archive entry paths: **47 `map.prp`** and 414
  `.sec`, but all under `terrain/<name>/…` (the templates, each *is* a mini-map
  with a `map.prp`) plus one `module template/maps/shopmap/`. **No campaign maps
  with objects live in the base archives.**
- The campaign proper is a **module**: `modules/Arcanum/maps/` holds ~80 entries
  with obfuscated names (e.g. `1 kry-zjvgdinzrgP`, `hgrlrH hcdqnynP`). NOTE
  (corrected 2026-07-16): those obfuscated entries are **files, not dirs** —
  loose files 16 B … 1.8 MB, high-entropy / encrypted headers (`file(1)` sees
  garbage; not the plaintext `.sec` layout). Only `ShopMap/` (and `Vormantown/`,
  `HighRes/Files/`) is a real dir with a plaintext `0.sec`.
- **Answered (2026-07-16) — how the game actually loads a sector** (`sector.c`
  `sector_load_game` @0x4D1A30): it reads two numeric-id-named files:
  `<sector_base_path>\<id>.sec` (base sector; falls back to `terrain_sector_path`
  → the terrain **template** sector, or `terrain_fill` if none) **overlaid with**
  `<sector_save_path>\<id>.dif` — a **difference file** (`DifferenceFileFlags`:
  DIF_HAVE_LIGHT_LIST / _TILE_LIST / …) that patches lights/tiles/roofs/objects
  onto the base. So campaign content = terrain-template sector + `.dif` diff +
  `.mob` objects, all keyed by decimal `SECTOR_MAKE(x,y)` id. The engine does
  **no** decrypt/xor step (grep of src is clean) — plaintext `.sec`/`.dif`.
- `.sec` on-disk order (`sector_load_editor`, reusable for the port's parser):
  light_list, tile_list, roof_list, `int placeholder` (0xAA0000–0xAA0004 = format
  version), then if !=0xAA0000: tile_scripts, [≥0002] sector_scripts,
  [≥0003] townmap_info + aptitude_adj + light_scheme + sound_list,
  [≥0004] block_list, then **objlist** (the objects). ShopMap/0.sec (16952 B) is
  a live example to test the parser against.
- **Still open:** the obfuscated encrypted `modules/Arcanum/maps/*` files — what
  they are (map index? worldmap/travel? per-map prp?) and their cipher. They are
  NOT the `.sec`/`.dif` the loader reads (those are numeric-named), so they are
  likely NOT needed to render a sector. Lowest-friction path to real objects:
  parse `ShopMap/0.sec`'s objlist first, no decryption required.

## After that (toward "playable")
- **Objects/scenery** (needs the campaign data above): parse the `.sec` object
  list (or `.mob`), then add sprite **offset/hotspot** anchoring in
  `TigArt.draw` (object_draw formula: screen = tileXY + (40,20) + objOffset −
  hotspot). This is the biggest "feels like a game" leap once data is sorted.
- Multi-sector scrolling; real map loading (`map.prp` has width/height +
  `base_terrain_type`; `startloc.txt` = start x y). Note sector filenames are the
  decimal `SECTOR_MAKE(x,y)` id (e.g. `67108864.sec` = sector (0,1)).
- Meanwhile (no new data needed): smooth tween-walk animation, roof/lighting.

## Build / run quick ref
- Build + test: `./gradlew compileJava test` (green, except the un-run CritterCheck)
- Launch: `./play.sh` (menu) or `./play.sh world`
- Data dir used this session:
  `/home/raudenaerde/arcanum-ce/arcanum-ce-c/out/build/linux-x64-debug`
