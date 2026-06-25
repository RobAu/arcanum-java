# c2java — Arcanum CE C→Java conversion tool

`c2java` does the **mechanical heavy lifting** of porting the Arcanum Community
Edition C engine (`src/game`, `src/ui`, ~188k LOC) to Java targeting
[libGDX](https://libgdx.com/). It parses the *real* C AST with **libclang** and
emits readable Java that calls a hand-written TIG→libGDX runtime shim
(see [`../java-port`](../java-port)).

It is a **transpiler + scaffolding generator**, not a magic "press button, get a
finished game" tool. C has constructs (pointer arithmetic, unions, varargs, raw
memory) that have no faithful 1:1 Java form. For those, c2java emits clearly
marked `/* TODO C2J: … */` annotations and preserves the original C signature as
a reference comment, so a human finishes the last mile instead of starting from
scratch.

## Results on this repository

Running `python -m c2j all` over `src/game` + `src/ui`:

| Metric | Value |
| --- | --- |
| C files attempted | 140 |
| C files converted (no crash) | **140 (100%)** |
| Java files generated | ~707 |
| Java LOC generated | ~147,000 |
| Unresolved call names (whole codebase) | ~56 |

The ~56 unresolved calls are almost entirely `SDL_*` / `zlib` / a few libc
helpers and decompiler `sub_XXXXXX` stubs — each is given a `TODO` marker and is
resolved by adding one line to [`apimap.json`](apimap.json).

## How it works

```
 C source ──libclang──▶ AST ──┬─▶ types     → typemap.py   (C type → Java type)
                              ├─▶ structs   → Java classes
                              ├─▶ enums     → int-constant classes
                              ├─▶ #defines  → static finals / static methods
                              └─▶ functions → static methods (statements + exprs)
                                                   │
                    apimap.json + symbol index ────┘  (call resolution)
```

### Pipeline modules (`c2j/`)

| Module | Responsibility |
| --- | --- |
| `config.py`  | Repo layout, libclang path, clang include args, package names |
| `naming.py`  | `snake_case` → `camelCase`/`PascalCase`; holder-class naming |
| `typemap.py` | C `Type` → Java type (see table below) |
| `symbols.py` | Global index: C function / enum-const / macro → owning Java class |
| `apimap.py`  | Resolves TIG / libc calls to shim methods via `apimap.json` |
| `emit.py`    | The AST→Java emitter (the bulk of the logic) |
| `__main__.py`| CLI |

### Translation decisions

- **Module layout.** Each C module is converted *as a unit*: converting `foo.c`
  also owns `foo.h` (pulled in via `#include`), so the header's enums, structs
  and macros translate together with the `.c`'s functions. Free functions and
  file-scope globals go into a holder class named after the file (`skill.c` →
  `SkillLib`, suffixed `Lib` to avoid colliding with `enum Skill`). Each struct,
  enum and function-pointer typedef becomes its own top-level Java file.
- **Enums → int constants.** C enums are used as array indices and in
  arithmetic, so they are emitted as `public static final int` constants (not
  Java `enum`s). Bare references like `SKILL_BOW` are auto-qualified to
  `Skill.SKILL_BOW`.
- **Object-like macros → `static final` constants;** **function-like macros →
  `static` methods** (with boolean/int return inferred from the body), because
  the preprocessor expands them into unusable synthetic AST nodes — c2java
  rewrites both the definition and every call site.
- **Pointers.** `T*` (to a struct) → an object reference `T`; `T**` and `T*` (to
  a primitive) → arrays / out-parameters (flagged at call sites); `char*` →
  `String`; `void*` → `Object`; `NULL`/`(T*)0` → `null`.
- **Calls** are resolved in order: TIG shim (`apimap.json`) → libc (`CLib`) →
  another translated module (via the symbol index) → file-local. Calls through
  function pointers become `field.invoke(...)` against the generated
  `@FunctionalInterface`.

### Type mapping (excerpt)

| C | Java | | C | Java |
| --- | --- | --- | --- | --- |
| `char*` / `const char*` | `String` | | `int64_t`,`size_t`,`intptr_t` | `long` |
| `char` | `byte` | | `bool` | `boolean` |
| `uint8_t` | `int` (`uint8_t*`→`byte[]`) | | `enum E` | `int` |
| `int`,`int32_t`,`unsigned` | `int` | | `T*` (struct) | `T` |
| `tig_color_t`,`tig_art_id_t` | `int` | | `T[N]` | `T[]` (`new T[N]`) |

## Usage

```console
# one-time: Python venv with libclang bindings (already created as ../.c2j-venv)
python3 -m venv ../.c2j-venv && ../.c2j-venv/bin/pip install libclang

# from the repo root, with the package on PYTHONPATH:
PYTHONPATH=c2java ../.c2j-venv/bin/python -m c2j <command> [args] [--verbose]
```

| Command | Effect |
| --- | --- |
| `scan` | Build and summarise the global symbol index |
| `module skill quest …` | Convert named `src/game` modules (`.c`+`.h`) |
| `convert path/to/foo.c …` | Convert specific files |
| `all [src/game src/ui]` | Convert every module under the given roots |

Output is written to `../java-port/generated/java/` under
`com.arcanum.ce.generated.{game,ui}`.

## Extending it

- **Map a new engine/libc function:** add a prefix rule, an `exact` entry, or a
  `libc` name to [`apimap.json`](apimap.json) (no code change). Then implement
  the target in the shim / `CLib`.
- **Improve a type mapping:** edit `typemap.py`.
- **Handle a new C idiom:** add a case in `emit.py`'s `_expr` / `_stmt`.

## Known limitations (where the human takes over)

- Pointer arithmetic / aliasing, raw `memcpy` over structs, and unions are not
  semantically preserved — they are flagged.
- Cross-module references to *global variables* (as opposed to functions) are not
  yet auto-qualified.
- `sizeof`, `va_list`/varargs, `goto`, and computed gotos are stubbed with TODOs.
- Generated module "holder" classes only compile once their dependency modules
  are also ported; self-contained generated types (enums, structs,
  function-pointer interfaces) compile today against the shim.
