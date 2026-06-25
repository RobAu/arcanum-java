"""C type -> Java type mapping (operates on libclang `Type` objects).

Design choices, with rationale:

  * `char*` / `const char*`        -> String          (overwhelmingly text here)
  * `char**`                       -> String[]
  * `char`                         -> byte            (C char is an 8-bit int)
  * `uint8_t` / `unsigned char`    -> int   (element: byte[])
  * `int8_t`                       -> byte
  * `int16_t`/`short`              -> short ; `uint16_t` -> int
  * `int`/`int32_t`/`unsigned`     -> int   (unsigned-ness noted, not enforced)
  * `int64_t`/`uint64_t`/`long long` -> long
  * `size_t`/`intptr_t`            -> long
  * `bool`                         -> boolean
  * `float`/`double`               -> float/double
  * enums                          -> int   (we emit enum constants as int finals,
                                             matching C's int-ish enum semantics)
  * `T*` where T is a struct       -> T      (object reference)
  * `T**`                          -> T[]    (out-parameter; flagged at call sites)
  * `T[N]`                         -> T[]
  * function pointers              -> a generated @FunctionalInterface name, else Object
  * `void*`                        -> Object
  * `va_list` / varargs            -> Object... (flagged)

This is deliberately a *best effort*. Unsigned arithmetic and pointer identity
are not preserved; those are called out at the relevant sites by the emitter.
"""

from __future__ import annotations

import clang.cindex as CX

TK = CX.TypeKind

# Typedef / spelling level overrides (checked before structural analysis).
_SPELLING_OVERRIDES = {
    "char *": "String",
    "const char *": "String",
    "char **": "String[]",
    "const char **": "String[]",
    "tig_color_t": "int",
    "tig_art_id_t": "int",
    "tig_button_handle_t": "int",
    "tig_window_handle_t": "int",
    "tig_sound_handle_t": "int",
    "tig_font_handle_t": "long",
    "tig_timestamp_t": "long",
    "tig_duration_t": "long",
    "size_t": "long",
    "ssize_t": "long",
    "intptr_t": "long",
    "uintptr_t": "long",
    "ptrdiff_t": "long",
    "int64_t": "long",
    "uint64_t": "long",
    "int32_t": "int",
    "uint32_t": "int",
    "int16_t": "short",
    "uint16_t": "int",
    "int8_t": "byte",
    "uint8_t": "int",
    "va_list": "Object[]",
    "bool": "boolean",
    "_Bool": "boolean",
}

_BUILTIN = {
    TK.VOID: "void",
    TK.BOOL: "boolean",
    TK.CHAR_U: "byte",
    TK.UCHAR: "int",
    TK.CHAR_S: "byte",
    TK.SCHAR: "byte",
    TK.CHAR16: "char",
    TK.WCHAR: "char",
    TK.USHORT: "int",
    TK.SHORT: "short",
    TK.UINT: "int",
    TK.INT: "int",
    TK.ULONG: "long",
    TK.LONG: "long",
    TK.ULONGLONG: "long",
    TK.LONGLONG: "long",
    TK.FLOAT: "float",
    TK.DOUBLE: "double",
    TK.LONGDOUBLE: "double",
}


def _strip(spelling: str) -> str:
    return spelling.replace("const ", "").replace("volatile ", "").strip()


def java_type(t: "CX.Type", record_names: set[str] | None = None) -> str:
    """Map a clang Type to a Java type string."""
    record_names = record_names or set()

    spell = _strip(t.spelling)
    if spell in _SPELLING_OVERRIDES:
        return _SPELLING_OVERRIDES[spell]

    # A typedef to a function pointer -> the generated @FunctionalInterface name.
    if t.kind == TK.TYPEDEF:
        u = t.get_canonical()
        if (u.kind == TK.POINTER
                and u.get_pointee().get_canonical().kind
                in (TK.FUNCTIONPROTO, TK.FUNCTIONNOPROTO)):
            decl = t.get_declaration()
            if decl and decl.spelling:
                return decl.spelling

    canon = t.get_canonical()
    kind = canon.kind

    if kind in _BUILTIN:
        return _BUILTIN[kind]

    if kind == TK.ENUM:
        # We emit enum constants as int finals; the type is therefore int.
        return "int"

    if kind == TK.TYPEDEF:
        return java_type(canon.get_canonical(), record_names)

    if kind == TK.ELABORATED:
        return java_type(canon.get_named_type(), record_names)

    if kind == TK.RECORD:
        decl = canon.get_declaration()
        name = decl.spelling or _record_typedef_name(t)
        return name or "Object"

    if kind in (TK.CONSTANTARRAY, TK.INCOMPLETEARRAY, TK.VARIABLEARRAY):
        elem = canon.element_type
        return java_type(elem, record_names) + "[]"

    if kind == TK.POINTER:
        pointee = canon.get_pointee()
        pk = pointee.get_canonical().kind
        # char* handled by spelling override above; re-check raw pointee spelling.
        ps = _strip(pointee.spelling)
        if ps in ("char", "const char"):
            return "String"
        if pk == TK.VOID:
            return "Object"
        if pk == TK.FUNCTIONPROTO or pk == TK.FUNCTIONNOPROTO:
            return "Object /* fn ptr: %s */" % t.spelling
        if pk == TK.POINTER:
            # T** -> T[] (out-param / array of pointers)
            inner = pointee.get_canonical().get_pointee()
            return java_type(inner, record_names) + "[]"
        if pk == TK.RECORD:
            decl = pointee.get_canonical().get_declaration()
            return (decl.spelling or "Object")
        # pointer to primitive -> array (often an out-param or buffer)
        base = java_type(pointee, record_names)
        return base + "[]"

    if kind in (TK.FUNCTIONPROTO, TK.FUNCTIONNOPROTO):
        return "Object /* fn */"

    # Fallback: try a light textual cleanup.
    return _fallback(spell)


def _record_typedef_name(t: "CX.Type") -> str:
    decl = t.get_declaration()
    if decl and decl.spelling:
        return decl.spelling
    return ""


def _fallback(spell: str) -> str:
    s = spell.replace("struct ", "").replace("enum ", "").replace("union ", "")
    s = s.replace("unsigned ", "").replace("signed ", "").strip()
    table = {
        "int": "int", "long": "long", "short": "short", "char": "byte",
        "float": "float", "double": "double", "void": "void", "bool": "boolean",
    }
    if s in table:
        return table[s]
    if s.endswith("*"):
        return "Object"
    # Unknown typedef/record we couldn't resolve -> keep name, strip pointer star.
    return s.split()[0] if s else "Object"


def default_value(java_t: str) -> str:
    if java_t in ("int", "short", "byte", "long"):
        return "0"
    if java_t in ("float", "double"):
        return "0"
    if java_t == "boolean":
        return "false"
    if java_t == "char":
        return "'\\0'"
    if java_t == "void":
        return ""
    return "null"
