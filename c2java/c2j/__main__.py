"""Command line entry point for the c2java transpiler.

Usage:
    python -m c2j convert <file.c|file.h> [more files...]
    python -m c2j module  <name>          # convert src/game/<name>.h and .c
    python -m c2j scan                    # build & dump the symbol index summary

Options:
    --out DIR        output root (default: java-port/generated/java)
    --no-ref         do not emit original-C reference comments
    --verbose
"""

from __future__ import annotations

import os
import sys

import clang.cindex as CX

from . import config
from .apimap import ApiMap
from .emit import Emitter
from .symbols import SymbolIndex


def _init_clang() -> None:
    CX.Config.set_library_file(config.libclang_path())


def _parse(path: str):
    index = CX.Index.create()
    tu = index.parse(
        path,
        args=config.clang_args(),
        options=CX.TranslationUnit.PARSE_DETAILED_PROCESSING_RECORD,
    )
    return tu


def _package_dir(out_root: str, pkg: str) -> str:
    return os.path.join(out_root, *pkg.split("."))


def _write_outputs(c_path: str, files: dict, opt: config.Options) -> list[str]:
    pkg = config.package_for(c_path)
    out_dir = _package_dir(opt.out_root, pkg)
    os.makedirs(out_dir, exist_ok=True)
    written = []
    for cls, src in files.items():
        dst = os.path.join(out_dir, cls + ".java")
        with open(dst, "w") as fh:
            fh.write(src)
        written.append(dst)
    return written


def _owned_for(path: str) -> list[str]:
    """A .c owns its sibling .h (the header is pulled in via #include), so the
    module's macros / enums / structs translate together with its functions."""
    owned = [path]
    if path.endswith(".c"):
        h = path[:-2] + ".h"
        if os.path.exists(h):
            owned.append(h)
    return owned


def convert_file(path: str, symbols, apimap, opt) -> dict:
    tu = _parse(path)
    diags = [d for d in tu.diagnostics if d.severity >= 3]
    if opt.verbose and diags:
        print("  (%d parse error diagnostic(s), continuing best-effort)" % len(diags))
    owned = _owned_for(path)
    em = Emitter(tu, os.path.abspath(path), opt, symbols, apimap, owned_files=owned)
    files = em.run()
    written = _write_outputs(path, files, opt)
    return {
        "path": path,
        "classes": list(files.keys()),
        "written": written,
        "todos": em.todos,
        "unresolved": sorted(em.unresolved),
    }


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2

    cmd, rest = argv[0], argv[1:]
    opt = config.Options()
    files: list[str] = []
    for a in rest:
        if a == "--no-ref":
            opt.emit_reference_comments = False
        elif a == "--verbose":
            opt.verbose = True
        elif a.startswith("--out="):
            opt.out_root = a.split("=", 1)[1]
        else:
            files.append(a)

    _init_clang()

    if cmd == "scan":
        sym = SymbolIndex().scan_repo()
        print("symbol index: %d functions, %d enum constants, %d types"
              % (len(sym.func_to_class), len(sym.enum_const_to_class),
                 len(sym.type_names)))
        for f in list(sym.func_to_class)[:20]:
            print("  %s -> %s" % (f, sym.func_to_class[f]))
        return 0

    print("building symbol index ...")
    sym = SymbolIndex().scan_repo()
    print("  %d functions, %d enum constants indexed"
          % (len(sym.func_to_class), len(sym.enum_const_to_class)))
    api = ApiMap()

    targets: list[str] = []
    if cmd == "convert":
        targets = files
    elif cmd == "all":
        import glob
        roots = files or ["src/game", "src/ui"]
        for root in roots:
            targets += sorted(glob.glob(os.path.join(config.REPO_ROOT, root, "*.c")))
    elif cmd == "module":
        # Convert each module from its .c (the .h is owned via #include). If only
        # a header exists (header-only module), fall back to the .h.
        for name in files:
            c = os.path.join(config.REPO_ROOT, "src", "game", name + ".c")
            h = os.path.join(config.REPO_ROOT, "src", "game", name + ".h")
            if os.path.exists(c):
                targets.append(c)
            elif os.path.exists(h):
                targets.append(h)
    else:
        print("unknown command: %s" % cmd)
        print(__doc__)
        return 2

    if not targets:
        print("no input files")
        return 1

    total_todos = 0
    total_unresolved: set[str] = set()
    failed: list[str] = []
    for t in targets:
        if opt.verbose:
            print("converting %s ..." % t)
        try:
            r = convert_file(t, sym, api, opt)
        except Exception as e:  # noqa: BLE001 - keep going on a bad file
            failed.append(t)
            print("   !! FAILED %s: %s: %s"
                  % (os.path.relpath(t, config.REPO_ROOT), type(e).__name__, e))
            continue
        total_todos += r["todos"]
        total_unresolved |= set(r["unresolved"])
        if opt.verbose:
            for w in r["written"]:
                print("   -> %s" % os.path.relpath(w, config.REPO_ROOT))
        print("   %-28s classes: %2d | TODOs: %3d | unresolved: %d"
              % (os.path.basename(t), len(r["classes"]), r["todos"],
                 len(r["unresolved"])))

    print("\n=== summary ===")
    print("files attempted : %d" % len(targets))
    print("files converted : %d" % (len(targets) - len(failed)))
    print("files FAILED    : %d" % len(failed))
    print("TODO markers    : %d" % total_todos)
    print("unresolved calls: %d" % len(total_unresolved))
    if total_unresolved and opt.verbose:
        print("  " + ", ".join(sorted(total_unresolved)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
