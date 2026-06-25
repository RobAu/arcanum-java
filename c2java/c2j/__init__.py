"""c2java -- a libclang-based C-to-Java conversion tool for Arcanum CE.

This package does the *mechanical heavy lifting* of porting the Arcanum
Community Edition C engine to Java (targeting libGDX): it parses the real C
AST via libclang and emits readable Java that calls a hand-written TIG->libGDX
runtime shim.

It is intentionally honest about its limits. Pointer arithmetic, unions,
function-like macros, varargs and a handful of other C idioms cannot be
translated 1:1 to Java; for those the emitter leaves clearly marked
`/* TODO C2J: ... */` annotations and preserves the original C as a reference
comment so a human can finish the job.

Modules:
    config   -- paths, include dirs, package names, libclang location
    naming   -- C identifier -> Java identifier conventions
    typemap  -- C type -> Java type mapping
    symbols  -- global index: C function/global -> owning Java class
    apimap   -- TIG / libc function -> Java shim call mapping
    emit     -- the AST -> Java emitter
    __main__ -- command line entry point
"""

__version__ = "0.1.0"
