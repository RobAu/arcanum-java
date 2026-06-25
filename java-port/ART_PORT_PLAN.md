# Plan: porting the ART decoder

> **Progress (Steps 0–3 done, Step 4 partial).**
> - ✅ Step 0: real loose `.ART` assets found at `/home/raudenaerde/arcanumweb` (8221 files, all types).
> - ✅ Step 1: `c2java/tools/art_decode.py` — `info`/`dump`/`verify`. **verify: 8221/8221 files, 44,749 frames, 0 failures**; PNG dump visually correct.
> - ✅ Step 2: synthetic license-safe fixture + goldens via `c2java/tools/make_art_fixture.py` → `java-port/src/test/resources/art/`.
> - ✅ Step 3: `com.arcanum.ce.tig.art.ArtFile` (Java decoder) + `ArtFileTest` (JUnit). Passes; also **byte-identical CRC to the Python reference on a real RLE game ART**.
> - ✅ Step 4 (rendering): `TigArt.load/decode/toPixmap/texture/draw` implemented — string-keyed `Texture` cache, `SpriteBatch` draw with top-left→libGDX coord conversion and horizontal mirroring. **A real scene renders on the GL backend** (`ArcanumGame`): isometric terrain tiles, a treasure chest, an animated dwarven critter (+ mirrored copy), and the targeting lens — all decoded live from the `.dat` archives. Captured headlessly via `-Darcanum.screenshot=<png>`. The scene also draws UI art by `tig_art_id_t` (lens + button) through the resolution chain below.
> - ✅ Step 4 (art-id resolution): `ArtId` (faithful `tig_art_id_t` bitfield encode/decode, per-type), `ArtPathResolver` (the pluggable game callback, cf. `name_resolve_path`), and `TigArt.buildPath`/`setFilePathResolver` + an `art_id`-based `TigArt.draw(batch, artId, x, y, h)`. System/MISC art (mouse/button/lens/…) resolves directly via the `tig_art_build_path` table; other types delegate to a registered resolver. Tested (`ArtIdTest`) and proven on screen.
>   - Remaining: port the game-layer resolver `name_resolve_path` (`name.c`, ~1500 lines, `.mes`-data-driven) so critter/item/tile/etc. ids resolve; then `TigArt.blit(TigArtBlitInfo)` is a thin shim over `draw(artId,…)` once the struct type is generated.
> - ⬜ Step 5: re-run c2java once `.dat`/asset path is wired so `tig_art_*` users shrink TODOs.
>
> **`.dat` archive reader — DONE (the asset-loading gate).**
> - Python ref `c2java/tools/dat_extract.py` (`list`/`cat`/`extract`/`verify`): **verified 14/14 real archives, ~109,000 files, 0 failures** (incl. 75k-file Arcanum.dat).
> - Java `com.arcanum.ce.tig.database.DatArchive` + `DatArchiveTest` (synthetic fixture via `make_dat_fixture.py`). Passes; **byte-identical CRC to Python on a real archive**.
> - Wired in: `TigDatabase` facade uses `DatArchive`; `TigFile.repositoryAdd` registers `.dat`s and `TigFile.readBytes`/`exists` search the loose+archive stack; `TigArt.load(path)` reads from it.
> - **Full chain proven in Java end-to-end:** `repositoryAdd(tig.dat)` → `TigArt.load("art\\button.ART")` → decoded sprite (3 frames, 69×25, RGBA).

Goal: decode Arcanum `.ART` sprite/animation files into libGDX textures, replacing
the `TigArt.blit` stub. Strategy is **prototype the binary format in Python first**
(fast iteration, dump PNGs to eyeball correctness), then port the validated logic
to Java once it's proven against real assets.

Source of truth: `first_party/tig/src/art.c` — `art_read_header` (≈L5958),
`tig_art_cache_entry_load` (≈L5541), the palette/frame/RLE loop (≈L5774–5910).

---

## The ART on-disk format (reverse-engineered from art.c)

All integers are **little-endian int32/uint32**. Only `bpp == 8`
(palette-indexed) files are supported by the engine.

### Header — 132 bytes (0x84)

| Off | Type | Field | Notes |
| --- | --- | --- | --- |
| 0x00 | u32 | `flags` | bit `0x01` set ⇒ 1 rotation, else 8 (`sub_51BE30`) |
| 0x04 | i32 | `fps` | animation speed |
| 0x08 | i32 | `bpp` | must be 8 |
| 0x0C | i32×4 | `palette_present[4]` | nonzero ⇒ that palette's 256 entries follow |
| 0x1C | i32 | `action_frame` | |
| 0x20 | i32 | `num_frames` | frames per rotation |
| 0x24 | i32×8 | (frames table ptrs) | **skipped** (fseek +32) |
| 0x44 | i32×8 | `data_size[8]` | read but not needed for decode |
| 0x64 | i32×8 | (pixels table ptrs) | **skipped** (fseek +32) |

`MAX_PALETTES = 4`, `MAX_ROTATIONS = 8`, `FrameData = 28 bytes (0x1C)`.

### Then, in order:

1. **Palettes** — for each of the 4 slots where `palette_present[i] != 0`:
   256 × u32 entries, 1024 bytes. Each entry is `0x00RRGGBB`
   (`tig_color_index_of`: R=`>>16&FF`, G=`>>8&FF`, B=`&FF`; top byte ignored).
   **Palette index 0 = the color key (transparent)** — `color_key =
   palette->colors[0]` (art.c:1191).

2. **Frame tables** — `num_rotations` blocks (1 or 8), each `num_frames` ×
   `FrameData`:

   | Off | Field |
   | --- | --- |
   | 0x00 | `width` (i32) |
   | 0x04 | `height` (i32) |
   | 0x08 | `data_size` (i32) |
   | 0x0C | `hot_x`, 0x10 `hot_y` (i32) |
   | 0x14 | `offset_x`, 0x18 `offset_y` (i32) |

3. **Pixel data** — `num_rotations` blocks, each frame's pixels concatenated.
   Per frame, decode into a `width*height` index buffer:
   - if `data_size == width*height` → **raw**, copy `data_size` bytes.
   - else if `data_size > 0` → **RLE** until `cnt == data_size`:
     - read `value` (u8); `len = value & 0x7F`
     - if `value & 0x80` → **literal**: copy next `len` bytes; `cnt += 1 + len`
     - else → **run**: read 1 byte `color`, write `color` × `len`; `cnt += 2`
     - advance output by `len` either way
   - `data_size == 0` → empty frame.

4. **Mirroring** (decode-time optimization, *skip in the prototype*): for
   critter/monster/unique-NPC art with 8 rotations, rotations 1–3 are freed and
   aliased to mirrors of 5–7 (art.c:5890+). Decode all 8 stored rotations as-is;
   apply mirroring only if/when matching engine output.

---

## Step 0 — get sample `.ART` bytes to test against  *(dependency)*

The decoder needs real input. `.art` files live inside the `.dat` archives
(`tig.dat`, `arcanum*.dat`) under `art\...`. Two options, in order of preference:

- **Quick unblock:** write a ~60-line Python `.dat` extractor (the `.dat` format
  is in `first_party/tig/src/database.c` — a zlib-deflated archive with a
  trailing index). Pull out a handful of `.art` files (e.g. an interface button,
  a tile, a critter) into `c2java/sampledata/`.
- Or, if the user already has loose extracted art, point the prototype at it.

This same extractor is step 0 of the sibling `TigDatabase` task, so it's not
throwaway work. *(Confirm with the user which game data path is available.)*

## Step 1 — Python reference decoder + validator  `c2java/tools/art_decode.py`

Pure-stdlib (`struct`, `zlib`, and `Pillow` only for PNG dump). Deliver:

```
art_decode.py info   <file.art>          # print header, palettes, per-frame dims
art_decode.py dump   <file.art> <outdir> # write PNG per (rotation,frame), RGBA,
                                         #   index 0 -> transparent
art_decode.py verify <dir>               # batch-decode, assert no size mismatch
```

- Implements the header/palette/frame/RLE spec above verbatim.
- Output a `DecodedArt` dataclass: `flags, fps, num_frames, num_rotations`,
  `palettes[4][256]`, `frames[rot][frame] = (w,h,hot,offset, index_bytes)`.
- **Acceptance:** PNGs visually correct for a button, a tile, and a multi-frame
  critter rotation; RLE round-trips (decoded length == `w*h`); no exceptions over
  a batch of ≥100 arts. This is the gate before any Java is written.

## Step 2 — golden test fixtures

From the validated Python decoder, emit small **golden files** for a few arts:
the raw decoded RGBA bytes (or a CRC per frame) into `java-port/src/test/resources/art/`.
These let the Java port be verified byte-for-byte against the Python reference
without shipping game assets.

## Step 3 — Java port: `ArtFile` decoder

New class `com.arcanum.ce.tig.art.ArtFile` (keep decode separate from the
`TigArt` cache/blit facade):

- `static ArtFile read(java.nio.ByteBuffer buf)` — `ByteBuffer.order(LITTLE_ENDIAN)`,
  mirrors the Python logic 1:1 (header → palettes → frames → RLE).
- `Frame { int w,h,hotX,hotY,offX,offY; byte[] indices; }`,
  `int[] palettes[4][256]` (packed `0xAARRGGBB`, with index 0 → alpha 0).
- `Pixmap toPixmap(int rotation, int frame, int paletteIndex)` — fill an
  `RGBA8888` `Pixmap`: `argb = (idx==0) ? 0 : palette[idx]`.
- **Test** `ArtFileTest` (JUnit) decodes the Step-2 fixtures and asserts equality
  with the goldens. Wire JUnit into `build.gradle` (`testImplementation`).

## Step 4 — hook into `TigArt`

- `TigArt`: load `.art` via `TigFile`/`TigDatabase` → `ArtFile` → cache a
  `Texture` per `(artId-num, rotation, frame, palette)`; use `tig_art_id_*`
  bitfields (already sketched in `TigArt`) to pick rotation/frame/palette.
- Implement `TigArt.blit(TigArtBlitInfo)` against the active `SpriteBatch`
  (`ArcanumGame` already owns one): draw the `TextureRegion` at the dest rect,
  honoring color-key transparency (free via the alpha-0 index-0 mapping).
- Honor mirroring (Step 0.4) for critters when matching engine visuals.

## Step 5 — c2java integration

- Add an `apimap.json` mapping so transpiled calls to `tig_art_*` already route
  to `TigArt` (the prefix rule exists); fill in the remaining methods
  (`tig_art_size`, `tig_art_frame_data`, `tig_art_anim_data`) the generated game
  code calls. Re-run `python -m c2j module <users-of-art>` to confirm fewer TODOs.

---

## Sequencing & effort (rough)

| Step | What | Risk |
| --- | --- | --- |
| 0 | sample data via minimal `.dat` extractor | low–med (format known) |
| 1 | Python decoder + PNG dump (**the core**) | low — spec fully recovered |
| 2 | golden fixtures | low |
| 3 | Java `ArtFile` + JUnit vs goldens | low |
| 4 | `TigArt` cache + `blit` rendering | **med** — libGDX texture/batch wiring |
| 5 | re-run transpiler, shrink TODOs | low |

**Recommended first action:** confirm the game-data path, then build
`art_decode.py` (Steps 0–1) and dump PNGs — that proves the whole format before
committing to Java.
