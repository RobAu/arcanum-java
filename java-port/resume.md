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

## After that (toward "playable")
- ⚠️ **Data constraint:** sector **objects/scenery** live only in real map
  sectors' object lists — and no real maps ship in the base archives (see above).
  So object parsing can't be verified until module/campaign map data is located.
  Options: (a) find/point at the campaign map data (the `.sec` object lists +
  `map.prp`), then parse objects + add sprite offset/hotspot in `TigArt.draw`;
  (b) meanwhile keep improving what the templates *can* show — smooth
  tween-walk animation, sector-edge behaviour, roof/lighting passes.
- Multi-sector scrolling; real map loading (`map.prp` / `startloc.txt`).

## Build / run quick ref
- Build + test: `./gradlew compileJava test` (green, except the un-run CritterCheck)
- Launch: `./play.sh` (menu) or `./play.sh world`
- Data dir used this session:
  `/home/raudenaerde/arcanum-ce/arcanum-ce-c/out/build/linux-x64-debug`
