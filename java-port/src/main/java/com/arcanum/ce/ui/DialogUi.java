package com.arcanum.ce.ui;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import com.arcanum.ce.game.DialogFile;
import com.arcanum.ce.tig.font.TigFontRenderer;

/**
 * The conversation overlay, following how the engine actually presents dialog
 * ({@code dialog_ui.c} + {@code tb.c}):
 *
 * <ul>
 *   <li>The NPC's line is a <b>text bubble</b> floating above the speaker
 *       ({@code dialog_ui_npc_say} → {@code tb_add}). There is no bubble art —
 *       {@code tb_background_color} is the colour <i>key</i>, so a bubble is just
 *       floating text: font interface art <b>229</b>, {@code TIG_FONT_CENTERED |
 *       TIG_FONT_SHADOW}, coloured per {@code tb_colors} (white for
 *       {@code TB_TYPE_WHITE}), wrapped to {@code TEXT_BUBBLE_WIDTH} = 200px,
 *       placed at {@code TB_POS_TOP} relative to the object.</li>
 *   <li>The player's responses go to the interface bar
 *       ({@code intgame_dialog_set_option}). {@code intgame} is not ported, so
 *       they are listed in a panel along the bottom — <b>this part is our own
 *       layout, not the original's.</b></li>
 * </ul>
 *
 * <p>Flow: an NPC line is shown, the consecutive PC entries after it are its
 * options, and choosing one jumps to that option's {@code responseVal} (the next
 * NPC line). A jump of 0 ends the conversation. Responses are filtered by
 * intelligence exactly as {@code dialog.c:1348} does: {@code iq < 0} requires
 * {@code intelligence <= -iq} (the dumb lines), {@code iq >= 0} requires
 * {@code intelligence >= iq}.
 *
 * <p><b>Approximation:</b> each entry carries a {@code conditions} test
 * ({@code re62} = reaction, {@code gf2004} = global flag) and an {@code actions}
 * effect (set flags, advance quests). Both need the script VM, which is not
 * ported — so conditions are <b>ignored</b> (every IQ-eligible response is
 * offered) and actions are <b>not applied</b>. Story-gated lines can therefore
 * appear out of context.
 *
 * <p>Input: {@code 1..9} or click a response; {@code Esc} leaves.
 */
public final class DialogUi {

    /** tb.c: the text bubble font is interface art 229. */
    private static final int BUBBLE_FONT_ART_NUM = 229;
    /** tb.c: TEXT_BUBBLE_WIDTH. */
    private static final int BUBBLE_WIDTH = 200;
    /** Gap between the speaker's anchor and the bottom of the bubble. */
    private static final int BUBBLE_GAP = 24;
    /** TIG_FONT_SHADOW: a 1px black drop shadow. */
    private static final int SHADOW = 1;

    /** Default PC intelligence until a real character sheet exists. */
    private static final int DEFAULT_INTELLIGENCE = 8;

    private static final int PAD = 16;
    private static final int LINE_GAP = 5;

    /** tb_colors[TB_TYPE_WHITE]. */
    private static final Color BUBBLE_COLOR = new Color(1f, 1f, 1f, 1f);
    private static final Color SHADOW_COLOR = new Color(0f, 0f, 0f, 1f);
    private static final Color PANEL_BG = new Color(0.05f, 0.04f, 0.03f, 0.90f);
    private static final Color PANEL_EDGE = new Color(0.45f, 0.36f, 0.22f, 1f);
    private static final Color NAME_COLOR = new Color(0.93f, 0.80f, 0.45f, 1f);
    private static final Color OPTION_COLOR = new Color(0.62f, 0.74f, 0.86f, 1f);
    private static final Color OPTION_HOVER = new Color(1f, 1f, 1f, 1f);
    private static final Color HINT_COLOR = new Color(0.55f, 0.52f, 0.47f, 1f);

    private DialogFile dialog;
    private DialogFile.Entry current;               // the NPC line on screen
    private final List<DialogFile.Entry> options = new ArrayList<>();
    private String npcName = "";
    private int intelligence = DEFAULT_INTELLIGENCE;
    private boolean female;                         // PC gender: picks the NPC's female line

    private TigFontRenderer font;                   // real Arcanum face; null -> BitmapFont
    private boolean fontLoadAttempted;
    private Texture blank;                          // 1x1, tinted for panels
    // Screen-space rows of the drawn options, for click hit-testing.
    private final List<float[]> optionRects = new ArrayList<>();

    /** Begin a conversation at {@code lineNum}; no-op if that line is missing. */
    public void start(DialogFile dialog, int lineNum, String npcName) {
        this.dialog = dialog;
        this.npcName = npcName != null ? npcName : "";
        goTo(lineNum);
    }

    public boolean isActive() {
        return dialog != null && current != null;
    }

    public void close() {
        dialog = null;
        current = null;
        options.clear();
    }

    /** PC intelligence used to filter responses (dialog.c:1348). */
    public void setIntelligence(int intelligence) {
        this.intelligence = intelligence;
        refreshOptions();
    }

    /** PC gender; selects the NPC line's female variant when set. */
    public void setFemale(boolean female) {
        this.female = female;
    }

    private void goTo(int lineNum) {
        if (dialog == null || lineNum == 0) {
            close();
            return;
        }
        DialogFile.Entry e = dialog.find(lineNum);
        // Jumping to a PC line would be malformed data; the engine warns about it.
        if (e == null || e.isPc()) {
            close();
            return;
        }
        current = e;
        refreshOptions();
    }

    private void refreshOptions() {
        options.clear();
        if (dialog == null || current == null) {
            return;
        }
        for (DialogFile.Entry r : dialog.responsesTo(current)) {
            if (passesIq(r.iq)) {
                options.add(r);
            }
        }
    }

    /** dialog.c:1348 -- negative iq is a maximum, non-negative is a minimum. */
    private boolean passesIq(int iq) {
        return (iq < 0 && intelligence <= -iq) || (iq >= 0 && intelligence >= iq);
    }

    /** Handle input; returns true while the conversation still has the focus. */
    public boolean update() {
        if (!isActive() || Gdx.input == null) {
            return isActive();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            close();
            return false;
        }
        for (int i = 0; i < options.size() && i < 9; i++) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_1 + i)) {
                choose(i);
                return isActive();
            }
        }
        if (Gdx.input.justTouched()) {
            int hovered = hoveredOption();
            if (hovered >= 0) {
                choose(hovered);
            }
        }
        return isActive();
    }

    private void choose(int index) {
        if (index < 0 || index >= options.size()) {
            return;
        }
        // Note: the option's `actions` (flags/quests) are not applied -- no script VM.
        goTo(options.get(index).responseVal);
    }

    /** Index of the option row under the cursor, or -1. */
    private int hoveredOption() {
        if (Gdx.input == null) {
            return -1;
        }
        float mx = Gdx.input.getX();
        float my = Gdx.input.getY();
        for (int i = 0; i < optionRects.size(); i++) {
            float[] r = optionRects.get(i);   // x, yTop, w, h in screen coords
            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Draw the overlay inside an active batch, after the world.
     *
     * @param speakerX screen x of the speaking NPC (its tile anchor)
     * @param speakerY screen y of the speaking NPC, top-left origin; the bubble
     *                 floats above this
     */
    public void render(SpriteBatch batch, BitmapFont fallback, int width, int height,
                       float speakerX, float speakerY) {
        if (!isActive()) {
            return;
        }
        ensureFont();
        ensureBlank();
        optionRects.clear();

        drawBubble(batch, fallback, width, height, speakerX, speakerY);
        drawOptions(batch, fallback, width, height);
    }

    /** The NPC's line: centred, shadowed, wrapped to 200px, floating above them. */
    private void drawBubble(SpriteBatch batch, BitmapFont fallback, int width, int height,
                            float speakerX, float speakerY) {
        List<String> lines = wrap(clean(current.text(female)), BUBBLE_WIDTH, fallback);
        int lh = lineHeight(fallback);
        float top = speakerY - BUBBLE_GAP - lines.size() * lh;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            float w = measure(line, fallback);
            float x = speakerX - w / 2f;                    // TIG_FONT_CENTERED
            float y = top + i * lh;
            // Keep the bubble on screen rather than letting it slide off the edge.
            x = Math.max(2, Math.min(x, width - w - 2));
            y = Math.max(2, y);
            drawText(batch, fallback, line, x + SHADOW, y + SHADOW, height, SHADOW_COLOR);
            drawText(batch, fallback, line, x, y, height, BUBBLE_COLOR);
        }
    }

    /**
     * The player's responses. The original puts these in the interface bar
     * (intgame_dialog_set_option); intgame is not ported, so this panel is ours.
     */
    private void drawOptions(SpriteBatch batch, BitmapFont fallback, int width, int height) {
        int lh = lineHeight(fallback);
        int rows = Math.max(1, options.size());
        int panelH = PAD * 3 + lh + rows * (lh + LINE_GAP);
        panelH = Math.min(panelH, (int) (height * 0.45f));

        fill(batch, 0, 0, width, panelH, PANEL_BG);
        fill(batch, 0, panelH - 2, width, 2, PANEL_EDGE);

        // Draw downward from the panel's top edge (screen coords are top-left).
        float y = height - panelH + PAD;

        drawText(batch, fallback, npcName.isEmpty() ? "???" : npcName,
                PAD, y, height, NAME_COLOR);
        y += lh + LINE_GAP;

        if (options.isEmpty()) {
            drawText(batch, fallback, "[no available response - Esc to leave]",
                    PAD, y, height, HINT_COLOR);
            return;
        }

        int hovered = hoveredOption();
        for (int i = 0; i < options.size(); i++) {
            String s = (i + 1) + ". " + clean(options.get(i).text);
            // One row per response; long lines are clipped rather than wrapped so
            // the numbering stays scannable.
            s = fit(s, width - PAD * 3, fallback);
            Color c = (i == hovered) ? OPTION_HOVER : OPTION_COLOR;
            drawText(batch, fallback, s, PAD * 2f, y, height, c);
            optionRects.add(new float[] {PAD * 2f, y, width - PAD * 3f, lh});
            y += lh + LINE_GAP;
            if (y > height - PAD) {
                break;
            }
        }
    }

    // -- font plumbing: prefer the real Arcanum face, fall back to the BitmapFont --

    private void ensureFont() {
        if (!fontLoadAttempted) {
            fontLoadAttempted = true;
            font = TigFontRenderer.load(BUBBLE_FONT_ART_NUM);
        }
    }

    private int lineHeight(BitmapFont fallback) {
        return font != null ? font.lineHeight() : (int) fallback.getLineHeight();
    }

    private float measure(String s, BitmapFont fallback) {
        if (font != null) {
            return font.measureWidth(s);
        }
        com.badlogic.gdx.graphics.g2d.GlyphLayout gl =
                new com.badlogic.gdx.graphics.g2d.GlyphLayout(fallback, s);
        return gl.width;
    }

    /** Draw one line with its top-left at (x, yTop) in top-left-origin space. */
    private void drawText(SpriteBatch batch, BitmapFont fallback, String s,
                          float x, float yTop, int height, Color tint) {
        if (font != null) {
            font.draw(batch, s, x, yTop, height, tint);
            return;
        }
        fallback.setColor(tint);
        fallback.draw(batch, s, x, height - yTop - fallback.getLineHeight() * 0.25f);
        fallback.setColor(Color.WHITE);
    }

    /** Greedy word wrap to {@code maxWidth} px. */
    private List<String> wrap(String s, int maxWidth, BitmapFont fallback) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (measure(candidate, fallback) <= maxWidth || line.length() == 0) {
                line.setLength(0);
                line.append(candidate);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    /** Truncate with an ellipsis to fit {@code maxWidth} px. */
    private String fit(String s, float maxWidth, BitmapFont fallback) {
        if (measure(s, fallback) <= maxWidth) {
            return s;
        }
        String tail = "...";
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (measure(s.substring(0, mid) + tail, fallback) <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return s.substring(0, lo) + tail;
    }

    private void ensureBlank() {
        if (blank != null) {
            return;
        }
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        blank = new Texture(pm);
        pm.dispose();
    }

    private void fill(SpriteBatch batch, float x, float y, float w, float h, Color c) {
        batch.setColor(c);
        batch.draw(blank, x, y, w, h);
        batch.setColor(Color.WHITE);
    }

    /** Dialog text carries hard breaks and double spaces; normalise for wrapping. */
    private static String clean(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    public void dispose() {
        if (blank != null) {
            blank.dispose();
            blank = null;
        }
    }
}
