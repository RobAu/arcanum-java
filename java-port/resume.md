# Resume — Arcanum CE Java port (world view + player)

**Where we are:** menu → **New Game** opens an isometric world view that renders
real `.sec` terrain from the `.dat` archives, with a controllable player critter
drawn on top. Verified: the dwarf sprite renders standing on plains terrain and
the camera follows it.

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
- The campaign proper is a **module**: `modules/Arcanum/maps/` has **83 map dirs
  with obfuscated names** (e.g. `1 kry-zjvgdinzrgP`, `hgrlrH hcdqnynP` — looks
  like a per-char cipher). On disk those dirs hold mostly **`.mob` files** (loose
  object/mobile overrides) and only **1 loose `.sec`** (`ShopMap/0.sec`, 16952 B).
- **Open question (next session, was mid-search when stopped):** where do the
  campaign **sector `.sec`s** actually come from? Candidates to check:
  (1) list one campaign map dir's full file breakdown
  (`find modules/Arcanum/maps -type f | sed 's/.*\.//' | sort | uniq -c`);
  (2) is there a module-level archive or a `data/` map store;
  (3) are `.mob` files the object data keyed to template terrain (i.e. the game
  lays objects from `.mob` onto a `terrain_fill` base)?  Decoding the map-name
  cipher may help match dirs to known locations (Shrouded Hills, Tarant…).
- Larger `.sec` = more content: base tile-only sector ≈ `4 + 4096*4 = 16388 B`;
  ShopMap 0.sec is 16952 B (≈ few objects). Use size to spot object-rich sectors.

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
