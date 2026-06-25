"""AST -> Java emitter.

Walks the libclang cursor tree of one C translation unit and produces Java
source. Structure (top-level declarations) is emitted from the structured AST;
expressions are emitted structurally for the common kinds and fall back to a
transformed token stream for the long tail. Anything that cannot be expressed
faithfully in Java is annotated with `/* TODO C2J: ... */` and counted so the
run summary is honest about coverage.

One C file produces several Java files (one per public type + one "holder" class
for the file's free functions and globals), returned as {ClassName: source}.
"""

from __future__ import annotations

import clang.cindex as CX

from . import naming, typemap
from .apimap import ApiMap
from .config import Options
from .symbols import SymbolIndex

CK = CX.CursorKind
TK = CX.TypeKind

# Operators that exist identically in C and Java.
_PASS_BINOPS = {
    "+", "-", "*", "/", "%", "==", "!=", "<", ">", "<=", ">=", "&&", "||",
    "&", "|", "^", "<<", ">>", "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=",
    "^=", "<<=", ">>=",
}
_PASS_UNOPS = {"!", "~", "-", "+", "++", "--"}


class Emitter:
    def __init__(self, tu, c_path: str, options: Options,
                 symbols: SymbolIndex, apimap: ApiMap, owned_files=None):
        self.tu = tu
        self.c_path = c_path
        self.opt = options
        self.sym = symbols
        self.api = apimap
        # Files whose declarations this run emits. When converting foo.c we also
        # own foo.h (pulled in via #include) so header macros/types/enums are
        # translated together with the .c's functions into one module.
        import os as _os
        self.owned = {_os.path.abspath(p) for p in (owned_files or [c_path])}
        self._text_cache: dict = {}
        self.record_names: set[str] = set()
        self.local_funcs: set[str] = set()
        self.local_macros: set[str] = set()
        self.func_macros: dict = {}
        self._macro_consts: list = []
        self.todos = 0
        self.unresolved: set[str] = set()
        with open(c_path, "r", errors="replace") as fh:
            self._src_text = fh.read()
        self._src_lines = self._src_text.split("\n")

    # -- helpers --------------------------------------------------------------
    def _in_file(self, cursor) -> bool:
        import os as _os
        f = cursor.location.file
        return f is not None and _os.path.abspath(f.name) in self.owned

    def _owns(self, extent) -> bool:
        import os as _os
        f = extent.start.file
        return f is not None and _os.path.abspath(f.name) in self.owned

    def _text_of(self, filename: str) -> str:
        if filename not in self._text_cache:
            try:
                with open(filename, "r", errors="replace") as fh:
                    self._text_cache[filename] = fh.read()
            except OSError:
                self._text_cache[filename] = ""
        return self._text_cache[filename]

    def _named(self, cursor) -> bool:
        """True for a real, named (non-anonymous) tag the emitter can name a file
        after. Anonymous structs/enums (e.g. inline unions) are skipped."""
        import re as _re
        if cursor.is_anonymous():
            return False
        return bool(_re.fullmatch(r"[A-Za-z_]\w*", cursor.spelling or ""))

    def _todo(self, msg: str) -> str:
        self.todos += 1
        return "/* TODO C2J: %s */" % msg

    def _jtype(self, t) -> str:
        return typemap.java_type(t, self.record_names)

    # -- top level ------------------------------------------------------------
    def run(self) -> dict[str, str]:
        children = [c for c in self.tu.cursor.get_children() if self._in_file(c)]

        enums, structs, fnptr_typedefs, funcs, globals_, macros = \
            [], [], [], [], [], []

        for c in children:
            if c.kind == CK.ENUM_DECL and self._named(c):
                enums.append(c)
                self.record_names.add(c.spelling)
            elif c.kind in (CK.STRUCT_DECL, CK.UNION_DECL) and self._named(c):
                if any(f.kind == CK.FIELD_DECL for f in c.get_children()):
                    structs.append(c)
                    self.record_names.add(c.spelling)
            elif c.kind == CK.TYPEDEF_DECL:
                self.record_names.add(c.spelling)
                if self._is_fnptr_typedef(c):
                    fnptr_typedefs.append(c)
            elif c.kind == CK.FUNCTION_DECL and c.is_definition():
                funcs.append(c)
                self.local_funcs.add(c.spelling)
            elif c.kind == CK.VAR_DECL:
                globals_.append(c)
            elif c.kind == CK.MACRO_DEFINITION:
                macros.append(c)

        out: dict[str, str] = {}
        for e in enums:
            out[e.spelling] = self._emit_enum(e)
        for s in structs:
            out[s.spelling] = self._emit_struct(s)
        for t in fnptr_typedefs:
            out[t.spelling] = self._emit_fnptr(t)

        # Index this file's object-like macros so references resolve to them
        # (they become static final constants in the holder class), and its
        # function-like macros (which become static methods).
        self._macro_consts = self._object_macros(macros)
        self.local_macros = {n for n, _, _ in self._macro_consts}
        self.func_macros = self._func_macros(macros)

        holder = naming.file_holder_class(self.c_path)
        if holder in self.sym.type_names or holder in out:
            holder += "Lib"
        if funcs or globals_ or self._macro_consts:
            out[holder] = self._emit_holder(holder, funcs, globals_)
        return out

    # -- enums ----------------------------------------------------------------
    def _emit_enum(self, cur) -> str:
        lines = [self._file_header(), ""]
        lines.append("/** Generated from C enum %s. Emitted as int constants to "
                     "preserve C enum arithmetic/indexing semantics. */"
                     % cur.spelling)
        lines.append("public final class %s {" % cur.spelling)
        lines.append("    private %s() {}" % cur.spelling)
        for c in cur.get_children():
            if c.kind == CK.ENUM_CONSTANT_DECL:
                lines.append("    public static final int %s = %d;"
                             % (c.spelling, c.enum_value))
        lines.append("}")
        return "\n".join(lines) + "\n"

    # -- structs --------------------------------------------------------------
    def _emit_struct(self, cur) -> str:
        lines = [self._file_header(), ""]
        kind = "union" if cur.kind == CK.UNION_DECL else "struct"
        note = " (C union -- fields overlap in C; here they are independent)" \
            if kind == "union" else ""
        lines.append("/** Generated from C %s %s.%s */" % (kind, cur.spelling, note))
        lines.append("public class %s {" % cur.spelling)
        for f in cur.get_children():
            if f.kind != CK.FIELD_DECL:
                continue
            jt = self._jtype(f.type)
            init = ""
            if f.type.get_canonical().kind == TK.CONSTANTARRAY:
                n = f.type.get_canonical().element_count
                base = jt[:-2]
                if base not in ("String",) and "/*" not in base:
                    init = " = new %s[%d]" % (base, n)
            lines.append("    public %s %s%s;" % (jt, naming.field_name(f.spelling), init))
        lines.append("")
        lines.append("    public %s() {}" % cur.spelling)
        lines.append("}")
        return "\n".join(lines) + "\n"

    # -- function-pointer typedefs -------------------------------------------
    def _is_fnptr_typedef(self, cur) -> bool:
        u = cur.underlying_typedef_type.get_canonical()
        return (u.kind == TK.POINTER
                and u.get_pointee().get_canonical().kind in
                (TK.FUNCTIONPROTO, TK.FUNCTIONNOPROTO))

    def _emit_fnptr(self, cur) -> str:
        proto = cur.underlying_typedef_type.get_canonical().get_pointee().get_canonical()
        ret = self._jtype(proto.get_result())
        args = []
        for i, a in enumerate(proto.argument_types()):
            args.append("%s a%d" % (self._jtype(a), i))
        lines = [self._file_header(), ""]
        lines.append("/** Generated from C function-pointer typedef %s. */"
                     % cur.spelling)
        lines.append("@FunctionalInterface")
        lines.append("public interface %s {" % cur.spelling)
        lines.append("    %s invoke(%s);" % (ret, ", ".join(args)))
        lines.append("}")
        return "\n".join(lines) + "\n"

    # -- holder (functions + globals + macros) --------------------------------
    def _object_macros(self, macros) -> list:
        """Object-like macros in this file -> (name, javaType, javaBody).

        Function-like macros are skipped (the preprocessor already inlined them
        into the AST at every use site). Bare flag macros with no body are
        skipped too (they only matter to #ifdef)."""
        out = []
        for m in macros:
            toks = list(m.get_tokens())
            if not toks:
                continue
            name = toks[0].spelling
            if name.endswith("_H_") or name.endswith("_H"):
                continue
            # function-like: '(' immediately adjacent to the name
            if (len(toks) >= 2 and toks[1].spelling == "("
                    and toks[1].extent.start.offset == toks[0].extent.end.offset):
                continue
            body_toks = toks[1:]
            if not body_toks:
                continue
            body = self._transform_tokens(body_toks)
            out.append((name, self._macro_type(body_toks), body))
        return out

    def _macro_type(self, body_toks) -> str:
        first = body_toks[0].spelling
        if first[:1] == '"':
            return "String"
        text = "".join(t.spelling for t in body_toks)
        import re as _re
        if _re.search(r"\d\.\d", text) or text.rstrip("fF")[-1:] == ".":
            return "double"
        if _re.search(r"\d[lL]", text):
            return "long"
        return "int"

    def _func_macros(self, macros) -> dict:
        """Function-like macros -> {cName: (javaName, [params], bodyTokens)}.

        These are translated into static methods so that call sites (which the
        preprocessor expanded into unusable synthetic AST nodes) can be rewritten
        as ordinary Java calls. See _macro_call."""
        out = {}
        for m in macros:
            toks = list(m.get_tokens())
            if len(toks) < 2:
                continue
            name = toks[0].spelling
            if name.endswith("_H_") or name.endswith("_H"):
                continue
            if not (toks[1].spelling == "("
                    and toks[1].extent.start.offset == toks[0].extent.end.offset):
                continue  # object-like, handled elsewhere
            params, i, depth = [], 2, 1
            while i < len(toks) and depth > 0:
                sp = toks[i].spelling
                if sp == "(":
                    depth += 1
                elif sp == ")":
                    depth -= 1
                elif depth == 1 and sp != ",":
                    params.append(naming.field_name(sp))
                i += 1
            body_toks = toks[i:]
            if not body_toks:
                continue
            out[name] = (naming.snake_to_camel(name), params, body_toks)
        return out

    def _emit_func_macro(self, name) -> str:
        import re as _re
        camel, params, body_toks = self.func_macros[name]
        body = self._transform_tokens(body_toks)
        text = " ".join(t.spelling for t in body_toks)
        is_bool = bool(_re.search(r"&&|\|\||==|!=|>=|<=|<|>|(?<![&|=!<>])!(?!=)",
                                  text))
        ret = "boolean" if is_bool else "int"
        plist = ", ".join("int %s" % p for p in params)
        return ("    // C macro: %s(%s)\n"
                "    public static %s %s(%s) { return %s; }"
                % (name, ", ".join(params), ret, camel, plist, body))

    def _emit_holder(self, name, funcs, globals_) -> str:
        lines = [self._file_header(), ""]
        lines.append("/** Generated from %s -- free functions and file-scope "
                     "state. */" % self.c_path.split("/")[-1])
        lines.append("public final class %s {" % name)
        lines.append("    private %s() {}" % name)
        lines.append("")

        for cname, jt, body in self._macro_consts:
            lines.append("    public static final %s %s = %s;"
                         % (jt, cname, body))
        if self._macro_consts:
            lines.append("")

        for mname in self.func_macros:
            lines.append(self._emit_func_macro(mname))
        if self.func_macros:
            lines.append("")

        for g in globals_:
            lines.append("    " + self._emit_global(g))
        if globals_:
            lines.append("")

        for fn in funcs:
            lines.append(self._emit_function(fn))
            lines.append("")
        lines.append("}")
        return "\n".join(lines) + "\n"

    def _emit_global(self, cur) -> str:
        jt = self._jtype(cur.type)
        init = self._var_init(cur, jt)
        return "static %s %s%s;" % (jt, naming.field_name(cur.spelling), init)

    def _emit_function(self, cur) -> str:
        ret = self._jtype(cur.result_type)
        params = []
        for a in cur.get_arguments():
            params.append("%s %s" % (self._jtype(a.type), naming.field_name(a.spelling)))
        sig_line = self._first_line(cur)
        body = None
        for c in cur.get_children():
            if c.kind == CK.COMPOUND_STMT:
                body = c
        head = "    // C: %s\n" % sig_line if self.opt.emit_reference_comments else ""
        head += "    public static %s %s(%s) " % (ret, naming.method_name(cur.spelling),
                                                  ", ".join(params))
        if body is None:
            return head + "{ " + self._todo("missing body") + " }"
        return head + self._stmt(body, 1)

    # -- statements -----------------------------------------------------------
    def _stmt(self, cur, depth: int) -> str:
        ind = "    " * depth
        k = cur.kind
        if k == CK.COMPOUND_STMT:
            inner = []
            for c in cur.get_children():
                inner.append(self._stmt(c, depth + 1))
            body = "\n".join(inner)
            return "{\n" + body + ("\n" if body else "") + ind + "}"
        if k == CK.DECL_STMT:
            return ind + self._decl_stmt(cur)
        if k == CK.IF_STMT:
            return ind + self._if_stmt(cur, depth)
        if k == CK.FOR_STMT:
            return ind + self._for_stmt(cur, depth)
        if k == CK.WHILE_STMT:
            ch = list(cur.get_children())
            return ind + "while (%s) %s" % (self._expr(ch[0]),
                                             self._block(ch[1], depth))
        if k == CK.DO_STMT:
            ch = list(cur.get_children())
            return ind + "do %s while (%s);" % (self._block(ch[0], depth),
                                                 self._expr(ch[1]))
        if k == CK.SWITCH_STMT:
            return ind + self._switch_stmt(cur, depth)
        if k == CK.RETURN_STMT:
            ch = list(cur.get_children())
            if ch:
                return ind + "return %s;" % self._expr(ch[0])
            return ind + "return;"
        if k == CK.BREAK_STMT:
            return ind + "break;"
        if k == CK.CONTINUE_STMT:
            return ind + "continue;"
        if k == CK.NULL_STMT:
            return ind + ";"
        if k in (CK.GOTO_STMT, CK.INDIRECT_GOTO_STMT):
            return ind + self._todo("goto -> restructure manually") + " ;"
        if k == CK.LABEL_STMT:
            return ind + "// label: %s" % cur.spelling + self._label_body(cur, depth)
        if k == CK.CASE_STMT or k == CK.DEFAULT_STMT:
            # handled inside _switch_stmt; if reached standalone, flatten
            return ind + self._case_like(cur, depth)
        # expression statement
        text = self._expr(cur)
        if text.startswith("(void)") or text.startswith("// (void)"):
            return ind + "// " + text
        return ind + text + ";"

    def _block(self, cur, depth: int) -> str:
        """Render a statement that should appear as a braced block after a header."""
        if cur.kind == CK.COMPOUND_STMT:
            return self._stmt(cur, depth)
        return "{\n" + self._stmt(cur, depth + 1) + "\n" + "    " * depth + "}"

    def _decl_stmt(self, cur) -> str:
        parts = []
        for v in cur.get_children():
            if v.kind != CK.VAR_DECL:
                continue
            jt = self._jtype(v.type)
            init = self._var_init(v, jt)
            parts.append("%s %s%s;" % (jt, naming.field_name(v.spelling), init))
        return " ".join(parts) if parts else ";"

    def _var_init(self, v, jt: str) -> str:
        canon = v.type.get_canonical()
        src = self._slice(v.extent) or ""
        # An '=' that is not part of ==, <=, >=, !=, +=, ... marks an initializer.
        import re as _re
        has_init = bool(_re.search(r"(?<![=!<>+\-*/%&|^])=(?!=)", src))
        if has_init:
            init_expr = None
            for c in v.get_children():
                if c.kind not in (CK.TYPE_REF, CK.TEMPLATE_REF, CK.NAMESPACE_REF):
                    init_expr = c
            if init_expr is not None:
                return " = %s" % self._expr(init_expr)
        if canon.kind == TK.CONSTANTARRAY:
            base = jt[:-2]
            n = canon.element_count
            if "/*" not in base:
                return " = new %s[%d]" % (base, n)
        return ""

    def _if_stmt(self, cur, depth: int) -> str:
        ch = list(cur.get_children())
        cond = self._expr(ch[0])
        then = self._block(ch[1], depth)
        out = "if (%s) %s" % (cond, then)
        if len(ch) >= 3:
            els = ch[2]
            if els.kind == CK.IF_STMT:
                out += " else " + self._if_stmt(els, depth)
            else:
                out += " else " + self._block(els, depth)
        return out

    def _for_stmt(self, cur, depth: int) -> str:
        ch = list(cur.get_children())
        # Common case: init, cond, inc, body all present.
        if len(ch) == 4:
            init = self._for_clause(ch[0])
            cond = self._expr(ch[1])
            inc = self._expr(ch[2])
            return "for (%s; %s; %s) %s" % (init, cond, inc,
                                            self._block(ch[3], depth))
        # Otherwise reconstruct the header from tokens (slots are ambiguous).
        body = ch[-1]
        header = self._for_header_tokens(cur, body)
        return "for (%s) %s" % (header, self._block(body, depth))

    def _for_clause(self, cur) -> str:
        if cur.kind == CK.DECL_STMT:
            return self._decl_stmt(cur).rstrip(";")
        return self._expr(cur)

    def _for_header_tokens(self, cur, body) -> str:
        toks = list(cur.get_tokens())
        body_start = body.extent.start.offset
        kept = [t for t in toks if t.extent.start.offset < body_start]
        # drop leading 'for' and outer parens
        s = self._join_tokens(kept)
        s = s.strip()
        if s.startswith("for"):
            s = s[3:].strip()
        if s.startswith("("):
            s = s[1:]
        s = s.rstrip()
        if s.endswith(")"):
            s = s[:-1]
        return self._transform_text(s)

    def _switch_stmt(self, cur, depth: int) -> str:
        ch = list(cur.get_children())
        cond = self._expr(ch[0])
        body = ch[1]
        ind = "    " * depth
        out = ["switch (%s) {" % cond]
        out.append(self._switch_body(body, depth + 1))
        out.append(ind + "}")
        return "\n".join(out)

    def _switch_body(self, comp, depth: int) -> str:
        """Flatten a switch compound, handling clang's nested case chains."""
        lines: list[str] = []
        for c in comp.get_children():
            self._emit_case_chain(c, depth, lines)
        return "\n".join(lines)

    def _emit_case_chain(self, cur, depth: int, lines: list[str]) -> None:
        ind = "    " * depth
        if cur.kind == CK.CASE_STMT:
            ch = list(cur.get_children())
            lines.append(ind + "case %s:" % self._expr(ch[0]))
            for sub in ch[1:]:
                self._emit_case_chain(sub, depth, lines)
        elif cur.kind == CK.DEFAULT_STMT:
            lines.append(ind + "default:")
            for sub in cur.get_children():
                self._emit_case_chain(sub, depth, lines)
        else:
            lines.append(self._stmt(cur, depth + 1))

    def _case_like(self, cur, depth: int) -> str:
        lines: list[str] = []
        self._emit_case_chain(cur, depth, lines)
        return "\n".join(l.strip() if i == 0 else l
                         for i, l in enumerate(lines))

    def _label_body(self, cur, depth: int) -> str:
        ch = list(cur.get_children())
        if ch:
            return "\n" + self._stmt(ch[0], depth)
        return ""

    # -- expressions ----------------------------------------------------------
    def _expr(self, cur) -> str:
        # A node whose source extent is exactly one identifier that names a macro
        # is a macro expansion; emit the macro reference rather than the
        # (often multi-line, unusable) expanded token stream.
        mu = self._macro_use(cur)
        if mu is not None:
            return mu
        mc = self._macro_call(cur)
        if mc is not None:
            return mc
        k = cur.kind
        if k == CK.INTEGER_LITERAL or k == CK.FLOATING_LITERAL \
                or k == CK.IMAGINARY_LITERAL:
            return self._literal(cur)
        if k == CK.STRING_LITERAL or k == CK.CHARACTER_LITERAL:
            toks = list(cur.get_tokens())
            return toks[0].spelling if toks else cur.spelling
        if k == CK.DECL_REF_EXPR:
            return self._declref(cur)
        if k == CK.MEMBER_REF_EXPR:
            ch = list(cur.get_children())
            if ch:
                return "%s.%s" % (self._expr(ch[0]), cur.spelling)
            return cur.spelling
        if k == CK.ARRAY_SUBSCRIPT_EXPR:
            ch = list(cur.get_children())
            return "%s[%s]" % (self._expr(ch[0]), self._expr(ch[1]))
        if k == CK.CALL_EXPR:
            return self._call(cur)
        if k == CK.PAREN_EXPR:
            ch = list(cur.get_children())
            return "(%s)" % self._expr(ch[0]) if ch else "()"
        if k == CK.UNARY_OPERATOR:
            return self._unary(cur)
        if k == CK.BINARY_OPERATOR or k == CK.COMPOUND_ASSIGNMENT_OPERATOR:
            return self._binary(cur)
        if k == CK.CSTYLE_CAST_EXPR:
            return self._cast(cur)
        if k == CK.CONDITIONAL_OPERATOR:
            ch = list(cur.get_children())
            return "%s ? %s : %s" % (self._expr(ch[0]), self._expr(ch[1]),
                                     self._expr(ch[2]))
        if k == CK.INIT_LIST_EXPR:
            items = ", ".join(self._expr(c) for c in cur.get_children())
            return "{ %s }" % items
        if k == CK.CXX_UNARY_EXPR:
            return self._todo("sizeof/alignof") + " 0"
        if k == CK.UNEXPOSED_EXPR:
            ch = list(cur.get_children())
            if len(ch) == 1:
                return self._expr(ch[0])
        # fallback: transformed tokens
        return self._tokens_expr(cur)

    def _literal(self, cur) -> str:
        toks = list(cur.get_tokens())
        if not toks:
            return "0"
        return self._num(toks[0].spelling)

    def _num(self, s: str) -> str:
        # Strip C unsigned suffixes that Java lacks; keep L/f/d.
        if s[:1] in "\"'":
            return s
        if s.lower().startswith("0x") or s.lower().startswith("0b"):
            t = s.rstrip("uU")
            return t
        t = s
        while t and t[-1] in "uU":
            t = t[:-1]
        return t if t else s

    def _declref(self, cur) -> str:
        name = cur.spelling
        enum_cls = self.sym.class_for_enum_const(name)
        if enum_cls is not None:
            return "%s.%s" % (enum_cls, name)
        return naming.safe_ident(name)

    def _unary(self, cur) -> str:
        ch = list(cur.get_children())
        operand = ch[0]
        toks = list(cur.get_tokens())
        op = toks[0].spelling if toks else "?"
        if op == "&":
            return self._expr(operand)  # address-of: out-param handled at call site
        if op == "*":
            return self._expr(operand)  # deref: pointers are object refs in Java
        if op in _PASS_UNOPS:
            # distinguish prefix vs postfix for ++/--
            if op in ("++", "--"):
                first_off = toks[0].extent.start.offset
                operand_off = operand.extent.start.offset
                if first_off < operand_off:
                    return op + self._expr(operand)
                return self._expr(operand) + op
            return op + self._expr(operand)
        return self._tokens_expr(cur)

    def _binary(self, cur) -> str:
        ch = list(cur.get_children())
        if len(ch) != 2:
            return self._tokens_expr(cur)
        op = self._binop_spelling(cur, ch[0])
        if op in _PASS_BINOPS:
            return "%s %s %s" % (self._expr(ch[0]), op, self._expr(ch[1]))
        return self._tokens_expr(cur)

    def _binop_spelling(self, cur, lhs) -> str:
        all_toks = list(cur.get_tokens())
        lhs_toks = list(lhs.get_tokens())
        idx = len(lhs_toks)
        if 0 <= idx < len(all_toks):
            return all_toks[idx].spelling
        return "?"

    def _cast(self, cur) -> str:
        jt = self._jtype(cur.type)
        ch = list(cur.get_children())
        operand = ch[-1] if ch else None
        inner = self._expr(operand) if operand is not None else ""
        # NULL and (T*)0 -> null (pointers are object references in Java).
        if cur.type.get_canonical().kind == TK.POINTER and inner.strip() == "0":
            return "null"
        if jt == "void":
            return "(void)" + inner  # marked as comment by _stmt
        if "/*" in jt:  # un-mappable cast target
            return inner
        return "(%s) %s" % (jt, inner)

    def _call(self, cur) -> str:
        ch = list(cur.get_children())
        if not ch:
            return self._tokens_expr(cur)
        callee = self._unwrap(ch[0])
        args = ch[1:]
        arg_strs = [self._expr(a) for a in args]
        flagged = any(self._is_addr_of(a) for a in args)
        suffix = (" " + self._todo("out-param (&) -> use holder/return value")) \
            if flagged else ""

        # Call through a function pointer (field, array slot, or local fn-ptr var):
        # generated fn-ptr typedefs are @FunctionalInterface with invoke().
        if not self._is_named_function_ref(callee):
            return "%s.invoke(%s)%s" % (self._expr(callee), ", ".join(arg_strs),
                                        suffix)

        fname = callee.spelling
        if fname in self.local_funcs:
            return "%s(%s)%s" % (naming.method_name(fname), ", ".join(arg_strs),
                                 suffix)

        holder = naming.file_holder_class(self.c_path)
        if holder in self.sym.type_names:
            holder += "Lib"
        res = self.api.resolve(fname, self.sym, holder)
        if res is None:
            self.unresolved.add(fname)
            call = "%s(%s)" % (naming.method_name(fname), ", ".join(arg_strs))
            return call + " " + self._todo("unresolved call %s" % fname) + suffix
        target = (res.cls + "." + res.method) if res.cls else res.method
        return "%s(%s)%s" % (target, ", ".join(arg_strs), suffix)

    def _unwrap(self, cur):
        c = cur
        while c.kind in (CK.UNEXPOSED_EXPR, CK.PAREN_EXPR):
            ch = list(c.get_children())
            if len(ch) != 1:
                break
            c = ch[0]
        return c

    def _is_named_function_ref(self, callee) -> bool:
        if callee.kind != CK.DECL_REF_EXPR:
            return False
        ref = callee.referenced
        return ref is not None and ref.kind == CK.FUNCTION_DECL

    def _callee_name(self, cur):
        if cur.kind == CK.DECL_REF_EXPR:
            return cur.spelling
        for c in cur.walk_preorder():
            if c.kind == CK.DECL_REF_EXPR:
                return c.spelling
        toks = list(cur.get_tokens())
        return toks[0].spelling if toks else None

    def _is_addr_of(self, cur) -> bool:
        c = cur
        while c.kind in (CK.UNEXPOSED_EXPR, CK.PAREN_EXPR):
            ch = list(c.get_children())
            if not ch:
                break
            c = ch[0]
        if c.kind == CK.UNARY_OPERATOR:
            toks = list(c.get_tokens())
            return bool(toks) and toks[0].spelling == "&"
        return False

    # -- token fallback -------------------------------------------------------
    def _tokens_expr(self, cur) -> str:
        toks = list(cur.get_tokens())
        return self._transform_tokens(toks)

    def _transform_tokens(self, toks) -> str:
        out = []
        for t in toks:
            s = t.spelling
            if s == "->":
                s = "."
            elif s == "NULL":
                s = "null"
            elif t.kind == CX.TokenKind.LITERAL:
                s = self._num(s)
            elif t.kind == CX.TokenKind.IDENTIFIER:
                ec = self.sym.class_for_enum_const(s)
                if ec is not None:
                    s = "%s.%s" % (ec, s)
            out.append(s)
        return self._join_tokens_str(out)

    def _transform_text(self, text: str) -> str:
        return text.replace("->", ".").replace("NULL", "null")

    def _join_tokens(self, toks) -> str:
        return self._join_tokens_str([t.spelling for t in toks])

    def _join_tokens_str(self, parts) -> str:
        out = ""
        no_space_before = {")", "]", ",", ";", ".", "(", "["}
        no_space_after = {"(", "[", ".", "!", "~"}
        for i, p in enumerate(parts):
            if i == 0:
                out = p
                continue
            prev = parts[i - 1]
            if p in no_space_before or prev in no_space_after:
                out += p
            else:
                out += " " + p
        return out

    # -- macro / source helpers -----------------------------------------------
    def _slice(self, extent) -> str | None:
        if extent.start.file is None:
            return None
        text = self._text_of(extent.start.file.name)
        a = extent.start.offset
        b = extent.end.offset
        if 0 <= a <= b <= len(text):
            return text[a:b]
        return None

    def _macro_use(self, cur):
        import re as _re
        ext = cur.extent
        if not self._owns(ext):
            return None
        s = self._slice(ext)
        if s is None:
            return None
        s = s.strip()
        if not _re.fullmatch(r"[A-Za-z_]\w*", s):
            return None
        if s in self.local_macros:
            return s
        cls = self.sym.class_for_macro(s)
        if cls is not None:
            return s if cls == self._holder_name() else "%s.%s" % (cls, s)
        return None

    def _macro_call(self, cur):
        import re as _re
        ext = cur.extent
        if not self._owns(ext):
            return None
        s = self._slice(ext)
        if s is None:
            return None
        s = s.strip()
        m = _re.fullmatch(r"([A-Za-z_]\w*)\s*\((.*)\)", s, _re.DOTALL)
        if not m or m.group(1) not in self.func_macros:
            return None
        camel = self.func_macros[m.group(1)][0]
        return "%s(%s)" % (camel, self._transform_text(m.group(2)))

    def _holder_name(self) -> str:
        h = naming.file_holder_class(self.c_path)
        if h in self.sym.type_names:
            h += "Lib"
        return h

    # -- misc -----------------------------------------------------------------
    def _first_line(self, cur) -> str:
        line = cur.extent.start.line - 1
        if 0 <= line < len(self._src_lines):
            return self._src_lines[line].strip()
        return cur.spelling

    def _file_header(self) -> str:
        from . import config
        pkg = config.package_for(self.c_path)
        lines = [
            "// Generated by c2java from %s" % self.c_path.split("/")[-1],
            "// Do not edit by hand; re-run the transpiler instead.",
            "package %s;" % pkg,
            "",
            "import %s.*;" % config.SHIM_PACKAGE,
            "import %s.*;" % config.RUNTIME_PACKAGE,
        ]
        # ui code references game code; pull the game package in too.
        if pkg == config.GENERATED_UI_PACKAGE:
            lines.append("import %s.*;" % config.GENERATED_GAME_PACKAGE)
        lines.append("import static %s.CLib.*;" % config.RUNTIME_PACKAGE)
        return "\n".join(lines)
