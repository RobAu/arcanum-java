package com.arcanum.ce.tig;

/**
 * Rectangle geometry. Maps the C header {@code tig/rect.h} (struct TigRect and
 * the tig_rect_* helpers). Pure Java -- no libGDX dependency.
 */
public final class TigRect {

    public int x;
    public int y;
    public int width;
    public int height;

    public TigRect() {
    }

    public TigRect(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** tig_rect_intersection: writes the overlap of a and b into r.
     *  Returns 0 (TIG_OK) on intersection, non-zero when disjoint. */
    public static int intersection(TigRect a, TigRect b, TigRect r) {
        int x0 = Math.max(a.x, b.x);
        int y0 = Math.max(a.y, b.y);
        int x1 = Math.min(a.x + a.width, b.x + b.width);
        int y1 = Math.min(a.y + a.height, b.y + b.height);
        if (x1 <= x0 || y1 <= y0) {
            return 4; // TIG_ERR_NO_INTERSECTION
        }
        r.x = x0;
        r.y = y0;
        r.width = x1 - x0;
        r.height = y1 - y0;
        return 0;
    }

    /** tig_rect_union: writes the bounding box of a and b into r. */
    public static int union(TigRect a, TigRect b, TigRect r) {
        int x0 = Math.min(a.x, b.x);
        int y0 = Math.min(a.y, b.y);
        int x1 = Math.max(a.x + a.width, b.x + b.width);
        int y1 = Math.max(a.y + a.height, b.y + b.height);
        r.x = x0;
        r.y = y0;
        r.width = x1 - x0;
        r.height = y1 - y0;
        return 0;
    }

    public boolean contains(int px, int py) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }

    @Override
    public String toString() {
        return "TigRect{" + x + "," + y + " " + width + "x" + height + "}";
    }
}
