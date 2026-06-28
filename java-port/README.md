# Arcanum CE — Java port (libGDX)

The Java target of the [`c2java`](../c2java) C→Java conversion of the Arcanum
Community Edition engine. It pairs a hand-written **TIG→libGDX runtime shim**
with the transpiler's generated game code.

> Status: **early scaffold.** The runtime shim + launcher compile and run against
> libGDX; the generated game code compiles incrementally as modules are ported.
> Many shim methods are documented skeletons that throw `UnsupportedOperationException`
> or no-op until implemented. You need to own the game (data assets) to play —
> see the top-level project README.

## Layout

```
java-port/
├── build.gradle            libGDX (LWJGL3) build; two source sets
├── src/main/java/com/arcanum/ce/
│   ├── DesktopLauncher.java   LWJGL3 entry point (-window, -geometry=WxH)
│   ├── ArcanumGame.java       ApplicationAdapter; drives Tig.ping()
│   ├── runtime/CLib.java      C stdlib helpers (strcmp, snprintf, math, …)
│   └── tig/*.java             the TIG→libGDX runtime shim (29 classes)
└── generated/java/com/arcanum/ce/generated/{game,ui}/
                            output of `python -m c2j all` (~707 files)
```

Two source roots, wired in `build.gradle`:

- `src/main/java` — hand-written. The **default build target**; always compiles.
- `generated/java` — a separate `generated` source set so a *partial* port never
  breaks `./gradlew build`. Compile it explicitly with
  `./gradlew compileGeneratedJava` as more modules land.

## Build & run

### Clickable launcher

```console
cd java-port
./install-desktop-entry.sh   # adds "Arcanum CE (Java)" to your app menu + Desktop
```

Installs a desktop entry that runs `play.sh` (auto-detects the game data and
launches to the menu). The icon is rendered locally from the game's own art and
is never committed. `./play.sh` runs it from a shell; `./play.sh world` boots
straight into the world view.

```console
./gradlew compileJava     # build the runtime + shim (downloads libGDX)
./gradlew run             # launch the app

# With game data, the app boots into the real main menu:
./gradlew run -Darcanum.data=/path/to/Arcanum
# Headless render of one frame to a PNG (then exit):
./gradlew run -Darcanum.data=/path/to/Arcanum -Darcanum.screenshot=out.png
```

With game data present the app reproduces the C boot sequence
(`src/main.c`): init TIG → register `.dat` archives → install the art-name
resolver → show the **interactive main menu** (`MainMenuBack.ART` + labels
from `mes\mainmenu.mes`). Hover highlights an option, left-click navigates
(Main → Single Player / Options / Credits), right-click or Esc goes back,
Exit Game quits. Without data it shows a placeholder.

**Single Player → New Game** opens the **isometric world view**
(`MapWorldScreen`): a real `.sec` sector decoded from the archives, its 4096
terrain tiles projected with the engine's isometric transform and drawn through
the `TigArt` pipeline (see `tile-rendering-spec.md`). Arrow keys / WASD or
left-drag scroll; Esc / right-click returns to the menu.

Headless capture extras: `-Darcanum.menu=options` starts on a submenu,
`-Darcanum.hover=N` force-highlights option N, `-Darcanum.screen=world` boots
straight into the world view, and `-Darcanum.sector=<repo\path.sec>` picks the
sector (default: a plains template). Diagnostics: `./gradlew runTool
-Ptool=tools.MapLister` lists terrain sectors; `-Ptool=tools.SecDump
-Pargs=<path>` dumps + resolves a sector's tiles.

## TIG → libGDX shim mapping

The C engine sits on "TIG" (`first_party/tig`). Each TIG header maps to a Java
class in `com.arcanum.ce.tig`; generated code calls these instead of SDL.

| C header (`tig/…`) | Java shim class | libGDX backing |
| --- | --- | --- |
| `core.h` | `Tig` | subsystem lifecycle facade |
| `video.h` (screen) | `TigVideo` | GL clear/fill, `ShapeRenderer` |
| `video.h` (surfaces) | `TigVideoBuffer` | `Pixmap` / `Texture` |
| `art.h` | `TigArt` | `Texture`/`TextureRegion` cache (ART decode TODO) |
| `font.h` | `TigFont` | `BitmapFont` + `GlyphLayout` (gdx-freetype) |
| `color.h` | `TigColor` | packed int ↔ `Color` |
| `rect.h` | `TigRect` | pure Java geometry |
| `palette.h` | `TigPalette` | `int[256]` |
| `draw.h` | `TigDraw` | line mode/style state |
| `mouse.h` | `TigMouse` | `Gdx.input` |
| `kb.h` | `TigKeyboard` | `Gdx.input` |
| `message.h` | `TigMessage` | event queue (enums + `ArrayDeque`) |
| `button.h` | `TigButton` | UI buttons (skeleton) |
| `window.h` | `TigWindow` | UI regions (skeleton) |
| `sound.h` | `TigSound` | `Sound` / `Music` |
| `movie.h` | `TigMovie` | placeholder (no Bink decoder) |
| `timer.h` | `TigTimer` | `System.nanoTime` |
| `memory.h` | `TigMemory` | no-op (JVM GC) |
| `file.h` | `TigFile` | `java.nio` loose-file VFS |
| `database.h` | `TigDatabase` | `.dat` archive (zlib parse TODO) |
| `file_cache.h` | `TigFileCache` | LRU `LinkedHashMap` |
| `find_file.h` | `TigFindFile` | directory enumeration |
| `bmp.h` | `TigBmp` | `Pixmap` |
| `guid.h` | `TigGuid` | `java.util.UUID` |
| `idxtable.h` | `TigIdxTable` | generic `HashMap` |
| `str_parse.h` | `TigStrParse` | data-file parsing (skeleton) |
| `debug.h` | `TigDebug` | `System.out` |
| `bsearch.h` | `TigBsearch` | `Collections.binarySearch` |
| `compat.h` | `Compat` | path conversion |

## Biggest remaining engine work (data formats & rendering)

1. **`.dat` archive reader** (`TigDatabase`) — zlib index + entries; the gate to
   loading any asset.
2. **ART decoder** (`TigArt`) — Arcanum's palettized sprite/animation format →
   libGDX textures.
3. **Window/blit compositor** (`TigWindow` + `TigVideoBuffer`).
4. Porting the game modules themselves (combat, dialog, anim, …) via `c2java`
   and finishing each holder's `TODO C2J` markers.
