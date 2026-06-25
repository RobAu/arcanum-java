"""TIG / libc function -> Java shim call resolution.

Loads apimap.json and answers: "for C function `f`, what Java call should the
emitter produce?" Returns a `Resolution(cls, method, kind)` where:

  kind == "shim"   -> cls.method(...)   (a TIG runtime shim, e.g. TigVideo.flip)
  kind == "libc"   -> CLib.method(...)  (a C standard library helper)
  kind == "module" -> cls.method(...)   (another translated module)
  kind == "local"  -> method(...)       (same module, unqualified)
  None             -> unresolved        (emitter leaves a TODO + best effort)
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass

from . import naming
from .config import C2JAVA_DIR

_APIMAP_PATH = os.path.join(C2JAVA_DIR, "apimap.json")


@dataclass
class Resolution:
    cls: str | None
    method: str
    kind: str  # "shim" | "libc" | "module" | "local"


class ApiMap:
    def __init__(self) -> None:
        with open(_APIMAP_PATH) as fh:
            data = json.load(fh)
        self._exact = data.get("exact", {})
        # longest prefix first so e.g. tig_video_buffer_ beats tig_video_
        self._prefixes = sorted(
            data.get("prefixes", []), key=lambda r: len(r[0]), reverse=True
        )
        self._libc = set(data.get("libc", []))

    def resolve(self, cfunc: str, symbols=None, current_class: str | None = None):
        """Return a Resolution, or None if the function is unknown."""
        if cfunc in self._exact:
            cls, method = self._exact[cfunc]
            return Resolution(cls, method, "shim")

        for prefix, cls, strip in self._prefixes:
            if cfunc.startswith(prefix):
                remainder = cfunc[len(strip):] if cfunc.startswith(strip) else cfunc
                method = naming.snake_to_camel(remainder) or "invoke"
                return Resolution(cls, method, "shim")

        if cfunc in self._libc:
            return Resolution("CLib", naming.snake_to_camel(cfunc), "libc")

        if symbols is not None:
            owner = symbols.class_for_func(cfunc)
            if owner is not None:
                method = naming.method_name(cfunc)
                if owner == current_class:
                    return Resolution(None, method, "local")
                return Resolution(owner, method, "module")

        return None
