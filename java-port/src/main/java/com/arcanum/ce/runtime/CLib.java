package com.arcanum.ce.runtime;

/**
 * Java implementations of the C standard-library functions the transpiled code
 * relies on. The c2java transpiler maps bare libc calls (strcmp, snprintf, ...)
 * to {@code CLib.*} via a static import.
 *
 * These are pragmatic equivalents, not byte-exact reimplementations. Functions
 * that operate on raw pointers/buffers in C (memcpy on arbitrary memory) cannot
 * be expressed faithfully and throw or no-op with a clear marker; the transpiler
 * flags the corresponding call sites.
 */
public final class CLib {

    private CLib() {
    }

    // -- strings --------------------------------------------------------------
    public static int strcmp(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return Integer.signum(a.compareTo(b));
    }

    public static int stricmp(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return Integer.signum(a.compareToIgnoreCase(b));
    }

    public static int strcasecmp(String a, String b) {
        return stricmp(a, b);
    }

    public static int strncmp(String a, String b, int n) {
        a = clip(a, n);
        b = clip(b, n);
        return Integer.signum(a.compareTo(b));
    }

    public static int strlen(String s) {
        return s == null ? 0 : s.length();
    }

    public static boolean strchr(String s, int c) {
        return s != null && s.indexOf((char) c) >= 0;
    }

    public static boolean strstr(String haystack, String needle) {
        return haystack != null && needle != null && haystack.contains(needle);
    }

    public static String strdup(String s) {
        return s;
    }

    /** C strcpy returns the destination; in Java strings are values, so we
     *  return the source for the common {@code x = strcpy(x, y)} idiom. */
    public static String strcpy(String dst, String src) {
        return src;
    }

    public static String strcat(String a, String b) {
        return (a == null ? "" : a) + (b == null ? "" : b);
    }

    // -- formatting -----------------------------------------------------------
    public static String sprintf(String fmt, Object... args) {
        return cfmt(fmt, args);
    }

    public static String snprintf(String fmt, Object... args) {
        return cfmt(fmt, args);
    }

    public static int printf(String fmt, Object... args) {
        String s = cfmt(fmt, args);
        System.out.print(s);
        return s.length();
    }

    /** Translate the most common C printf conversions to Java's. */
    public static String cfmt(String fmt, Object... args) {
        if (fmt == null) {
            return "";
        }
        String j = fmt.replaceAll("%l?l([dux])", "%$1")  // %lld/%llu -> %d/%u
                      .replace("%u", "%d");
        try {
            return String.format(j, args);
        } catch (RuntimeException e) {
            return fmt; // formatting mismatch -- fall back to the raw format
        }
    }

    // -- numeric --------------------------------------------------------------
    public static int atoi(String s) {
        if (s == null) return 0;
        s = s.trim();
        int i = 0, n = s.length(), sign = 1;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            sign = s.charAt(i) == '-' ? -1 : 1;
            i++;
        }
        long v = 0;
        while (i < n && Character.isDigit(s.charAt(i))) {
            v = v * 10 + (s.charAt(i++) - '0');
        }
        return (int) (sign * v);
    }

    public static double atof(String s) {
        try {
            return s == null ? 0 : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int abs(int v) {
        return Math.abs(v);
    }

    public static long llabs(long v) {
        return Math.abs(v);
    }

    public static long labs(long v) {
        return Math.abs(v);
    }

    public static double sqrt(double v) {
        return Math.sqrt(v);
    }

    public static double pow(double a, double b) {
        return Math.pow(a, b);
    }

    public static double atan2(double y, double x) {
        return Math.atan2(y, x);
    }

    public static double floor(double v) {
        return Math.floor(v);
    }

    public static double ceil(double v) {
        return Math.ceil(v);
    }

    public static double fabs(double v) {
        return Math.abs(v);
    }

    public static long time(Object ignored) {
        return System.currentTimeMillis() / 1000L;
    }

    public static int min(int a, int b) {
        return Math.min(a, b);
    }

    public static int max(int a, int b) {
        return Math.max(a, b);
    }

    public static int tolower(int c) {
        return Character.toLowerCase(c);
    }

    public static int toupper(int c) {
        return Character.toUpperCase(c);
    }

    public static boolean isdigit(int c) {
        return c >= '0' && c <= '9';
    }

    public static boolean isalpha(int c) {
        return Character.isLetter(c);
    }

    public static boolean isspace(int c) {
        return Character.isWhitespace(c);
    }

    public static boolean isalnum(int c) {
        return Character.isLetterOrDigit(c);
    }

    private static final java.util.Random RNG = new java.util.Random();

    public static int rand() {
        return RNG.nextInt(0x7fff);
    }

    public static void srand(int seed) {
        RNG.setSeed(seed);
    }

    // -- helpers --------------------------------------------------------------
    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }
}
