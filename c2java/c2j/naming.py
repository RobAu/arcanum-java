"""C identifier -> Java identifier conventions.

Rules:
  * C functions are snake_case -> Java methods are camelCase.
  * A C file `foo_bar.c` contributes its free functions / file-scope globals to a
    Java holder class `FooBar`. If a type of the same name already exists in that
    translation unit, the holder is suffixed with `Lib` to avoid a clash
    (e.g. skill.c defines `enum Skill`, so its functions live in `SkillLib`).
  * Struct / enum / typedef names are already PascalCase in this codebase and are
    kept as-is for the Java type name.
  * Local variables, parameters and struct fields keep their original spelling.
    That is valid Java and keeps the generated code diff-able against the C.
"""

from __future__ import annotations

import os
import re

_RESERVED = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new",
    "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while", "true", "false", "null",
    "var", "record", "yield",
}


def snake_to_camel(name: str) -> str:
    parts = name.split("_")
    # Preserve a leading underscore group (rare) as nothing.
    head = parts[0]
    rest = "".join(p[:1].upper() + p[1:] for p in parts[1:] if p != "")
    out = head + rest
    return safe_ident(out)


def snake_to_pascal(name: str) -> str:
    parts = [p for p in name.split("_") if p != ""]
    return "".join(p[:1].upper() + p[1:] for p in parts)


def safe_ident(name: str) -> str:
    """Avoid Java reserved words for locals/params/fields."""
    if name in _RESERVED:
        return name + "_"
    return name


def file_holder_class(c_path: str) -> str:
    base = os.path.basename(c_path)
    base = re.sub(r"\.(c|h)$", "", base)
    return snake_to_pascal(base)


def method_name(c_func: str) -> str:
    return snake_to_camel(c_func)


def field_name(c_field: str) -> str:
    return safe_ident(c_field)
