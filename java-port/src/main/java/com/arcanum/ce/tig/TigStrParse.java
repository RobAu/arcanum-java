package com.arcanum.ce.tig;

/**
 * String/value parsing for the engine's text data files (.mes, rules tables).
 * Maps {@code tig/str_parse.h}. The C API advanced a {@code char**} cursor; the
 * Java port will replace that idiom with a small cursor object (TODO). Skeleton.
 */
public final class TigStrParse {

    private TigStrParse() {
    }

    public static int init() {
        return 0;
    }

    public static void exit() {
    }

    private static int separator = ',';

    public static void setSeparator(int sep) {
        separator = sep;
    }

    public static int getSeparator() {
        return separator;
    }
}
