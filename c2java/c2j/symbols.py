"""Global symbol index built by scanning headers.

The emitter needs to know, when it sees a call `mes_load(...)` inside skill.c,
which Java class owns `mesLoad`. We resolve that by scanning every header once:

  * function prototypes   -> C name -> owning Java holder class
  * enum constants        -> C name -> owning enum holder class (for qualifying
                             bare references like SKILL_BOW -> Skill.SKILL_BOW)
  * object-like macros that are simple constants -> treated like enum constants

A regex scan (rather than a full clang pass over ~300 headers) keeps the index
build fast; it is heuristic but reliable for this codebase's straightforward
declaration style.
"""

from __future__ import annotations

import glob
import os
import re

from . import naming
from .config import REPO_ROOT

# ret  name ( params ) ;   -- a function prototype on one logical line.
_PROTO_RE = re.compile(
    r"(?:^|\n)[ \t]*"
    r"(?:[A-Za-z_][\w ]*?[ \t*]+)"   # return type (+ qualifiers / stars)
    r"([A-Za-z_]\w*)[ \t]*"          # function name  (group 1)
    r"\([^;{}]*\)[ \t]*;",           # ( ... ) ;
)

_ENUM_RE = re.compile(
    r"(?:typedef[ \t]+)?enum[ \t]+([A-Za-z_]\w*)?[ \t]*\{([^}]*)\}[ \t]*([A-Za-z_]\w*)?[ \t]*;",
    re.DOTALL,
)

_TYPE_DECL_RE = re.compile(
    r"\btypedef[ \t]+(?:struct|enum|union)?[^;{]*?([A-Za-z_]\w*)[ \t]*;"
)
_STRUCT_NAME_RE = re.compile(r"\b(?:struct|union|enum)[ \t]+([A-Za-z_]\w*)[ \t]*\{")

_KEYWORDS = {"if", "for", "while", "switch", "return", "sizeof", "do", "else"}

# Object-like macro:  #define NAME body    (NAME not immediately followed by '(')
_MACRO_RE = re.compile(r"(?m)^[ \t]*#[ \t]*define[ \t]+([A-Za-z_]\w*)(?![\w(])")


class SymbolIndex:
    def __init__(self) -> None:
        self.func_to_class: dict[str, str] = {}
        self.enum_const_to_class: dict[str, str] = {}
        self.macro_to_class: dict[str, str] = {}
        self.type_names: set[str] = set()

    # -- query ----------------------------------------------------------------
    def class_for_func(self, name: str) -> str | None:
        return self.func_to_class.get(name)

    def class_for_enum_const(self, name: str) -> str | None:
        return self.enum_const_to_class.get(name)

    def class_for_macro(self, name: str) -> str | None:
        return self.macro_to_class.get(name)

    # -- build ----------------------------------------------------------------
    def scan_repo(self) -> "SymbolIndex":
        headers = []
        headers += glob.glob(os.path.join(REPO_ROOT, "src", "**", "*.h"), recursive=True)
        for h in headers:
            try:
                with open(h, "r", errors="replace") as fh:
                    text = fh.read()
            except OSError:
                continue
            self._scan_text(text, h)
        return self

    def _holder(self, header_path: str, type_names_here: set[str]) -> str:
        holder = naming.file_holder_class(header_path)
        if holder in type_names_here:
            holder = holder + "Lib"
        return holder

    def _scan_text(self, text: str, header_path: str) -> None:
        # 1. collect type names declared in this header
        local_types: set[str] = set()
        for m in _STRUCT_NAME_RE.finditer(text):
            local_types.add(m.group(1))
        for m in _TYPE_DECL_RE.finditer(text):
            local_types.add(m.group(1))
        self.type_names |= local_types

        holder = self._holder(header_path, local_types)

        # 2. enum constants -> enum holder class
        for m in _ENUM_RE.finditer(text):
            enum_name = m.group(1) or m.group(3)
            body = m.group(2)
            if not enum_name:
                continue
            for const in _split_enum_consts(body):
                self.enum_const_to_class[const] = enum_name

        # 3. function prototypes -> holder class
        for m in _PROTO_RE.finditer(text):
            fname = m.group(1)
            if fname in _KEYWORDS:
                continue
            # don't overwrite an existing mapping with a later, weaker one
            self.func_to_class.setdefault(fname, holder)

        # 4. object-like macros -> holder class (header guards excluded heuristically)
        for m in _MACRO_RE.finditer(text):
            name = m.group(1)
            if name.endswith("_H_") or name.endswith("_H"):
                continue
            self.macro_to_class.setdefault(name, holder)


def _split_enum_consts(body: str) -> list[str]:
    out = []
    for part in body.split(","):
        part = part.strip()
        if not part:
            continue
        # NAME = value  ->  NAME
        name = re.split(r"[=\s]", part, 1)[0].strip()
        if re.fullmatch(r"[A-Za-z_]\w*", name):
            out.append(name)
    return out
