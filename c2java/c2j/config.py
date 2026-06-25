"""Configuration: repository layout, include paths, package names, libclang."""

from __future__ import annotations

import os
from dataclasses import dataclass, field

# Repository root = two levels up from this file (c2java/c2j/config.py -> repo).
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
C2JAVA_DIR = os.path.join(REPO_ROOT, "c2java")
STUBS_DIR = os.path.join(C2JAVA_DIR, "stubs")

# Where generated Java lands. Must match the libGDX project's `generated/java`
# source root and package layout (see java-port/).
JAVA_PORT_DIR = os.path.join(REPO_ROOT, "java-port")
GENERATED_ROOT = os.path.join(JAVA_PORT_DIR, "generated", "java")

BASE_PACKAGE = "com.arcanum.ce"
SHIM_PACKAGE = f"{BASE_PACKAGE}.tig"
GENERATED_GAME_PACKAGE = f"{BASE_PACKAGE}.generated.game"
GENERATED_UI_PACKAGE = f"{BASE_PACKAGE}.generated.ui"
RUNTIME_PACKAGE = f"{BASE_PACKAGE}.runtime"  # CLib + pointer helpers

# libclang shared library. Override with C2J_LIBCLANG env var if needed.
DEFAULT_LIBCLANG = "/lib/x86_64-linux-gnu/libclang-18.so.18"


def libclang_path() -> str:
    return os.environ.get("C2J_LIBCLANG", DEFAULT_LIBCLANG)


def clang_args() -> list[str]:
    """Compiler arguments mirroring the project's CMake include layout."""
    inc = [
        STUBS_DIR,  # SDL3 stub first so it wins over the empty submodule dir
        os.path.join(REPO_ROOT, "src"),
        os.path.join(REPO_ROOT, "first_party", "tig", "include"),
        os.path.join(REPO_ROOT, "first_party", "mss_compat", "include"),
        os.path.join(REPO_ROOT, "first_party", "bink_compat", "include"),
        os.path.join(REPO_ROOT, "third_party"),
    ]
    args = ["-std=c11", "-DC2J_TRANSPILE=1"]
    for d in inc:
        args += ["-I", d]
    return args


def package_for(c_path: str) -> str:
    """Pick the generated package based on the C file's directory."""
    norm = c_path.replace("\\", "/")
    if "/src/ui/" in norm or norm.endswith("/ui"):
        return GENERATED_UI_PACKAGE
    return GENERATED_GAME_PACKAGE


@dataclass
class Options:
    """Per-run options."""

    out_root: str = GENERATED_ROOT
    emit_reference_comments: bool = True  # keep original C as // comments
    verbose: bool = False
    stats: dict = field(default_factory=dict)
