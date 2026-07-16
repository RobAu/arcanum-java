package com.arcanum.ce.ui;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import com.arcanum.ce.game.DialogFile;

/**
 * The conversation overlay: an NPC line and the player's selectable responses,
 * driven by a parsed {@link DialogFile}.
 *
 * <p>Flow (cf. {@code dialog.c}): an NPC line is shown, the consecutive PC
 * entries after it are its options, and picking one jumps to that option's
 * {@code responseVal} — which is the next NPC line. A jump of 0 ends the
 * conversation.
 *
 * <p>Responses are filtered by intelligence exactly as the engine does
 * ({@code dialog.c:1348}): {@code iq < 0} requires {@code intelligence <= -iq}
 * (the dumb lines), {@code iq >= 0} requires {@code intelligence >= iq}.
 *
 * <p><b>Approximation:</b> each entry also carries a {@code conditions} test
 * (e.g. {@code re62} = reaction, {@code gf2004} = global flag) and an
 * {@code actions} effect (set flags, advance quests). Evaluating either needs
 * the script VM, which is not ported — so conditions are <b>ignored</b> (every
 * IQ-eligible response is offered) and actions are <b>not applied</b>. Lines
 * gated on story state may therefore appear out of context.
 *
 * <p>Input: {@code 1..9} or click a response; {@code Esc} leaves.
 */
public final class DialogUi {

    /** Default PC intelligence until a real character sheet exists. */
    private static final int DEFAULT_INTELLIGENCE = 8;

    private static final float PANEL_HEIGHT_FRACTION = 0.42f;
    private static final int PAD = 18;
    private static final int LINE_GAP = 6;

    private static final Color PANEL_BG = new Color(0.05f, 0.04f, 0.03f, 0.92f);
    private static final Color PANEL_EDGE = new Color(0.45f, 0.36f, 0.22f, 1f);
    private static final Color NAME_COLOR = new Color(0.93f, 0.80f, 0.45f, 1f);
    private static final Color NPC_COLOR = new Color(0.90f, 0.88f, 0.82f, 1f);
    private static final Color OPTION_COLOR = new Color(0.62f, 0.74f, 0.86f, 1f);
    private static final Color OPTION_HOVER = new Color(1f, 1f, 1f, 1f);
    private static final Color HINT_COLOR = new Color(0.55f, 0.52f, 0.47f, 1f);

    private DialogFile dialog;
    private DialogFile.Entry current;               // the NPC line on screen
    private final List<DialogFile.Entry> options = new ArrayList<>();
    private String npcName = "";
    private int intelligence = DEFAULT_INTELLIGENCE;
    private boolean female;                         // PC gender (picks the NPC's female line)

    private Texture blank;                          // 1x1, tinted for panels
    private final GlyphLayout layout = new GlyphLayout();
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

    /** dialog.c:1348 — negative iq is a maximum, non-negative is a minimum. */
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

    /** Draw the overlay. Call inside an active batch, after the world. */
    public void render(SpriteBatch batch, BitmapFont font, int width, int height) {
        if (!isActive()) {
            return;
        }
        ensureBlank();
        optionRects.clear();

        int panelH = (int) (height * PANEL_HEIGHT_FRACTION);
        int panelY = 0;                                   // libGDX y-up: bottom strip
        fill(batch, 0, panelY, width, panelH, PANEL_BG);
        fill(batch, 0, panelY + panelH - 2, width, 2, PANEL_EDGE);

        float textW = width - 2f * PAD;
        float y = panelY + panelH - PAD;                  // draw downward from the top edge

        // Speaker.
        font.setColor(NAME_COLOR);
        layout.setText(font, npcName.isEmpty() ? "???" : npcName);
        font.draw(batch, layout, PAD, y);
        y -= layout.height + LINE_GAP * 2;

        // The NPC's line (wrapped).
        font.setColor(NPC_COLOR);
        layout.setText(font, clean(current.text(female)), NPC_COLOR, textW, -1, true);
        font.draw(batch, layout, PAD, y);
        y -= layout.height + LINE_GAP * 3;

        // Player responses.
        if (options.isEmpty()) {
            font.setColor(HINT_COLOR);
            font.draw(batch, "[no available response - Esc to leave]", PAD, y);
            return;
        }

        int hovered = hoveredOption();
        for (int i = 0; i < options.size(); i++) {
            DialogFile.Entry o = options.get(i);
            Color c = (i == hovered) ? OPTION_HOVER : OPTION_COLOR;
            String s = (i + 1) + ". " + clean(o.text);
            layout.setText(font, s, c, textW - PAD, -1, true);
            font.draw(batch, layout, PAD * 2f, y);

            // Record the row for hit-testing (convert y-up -> screen y-down).
            float top = height - y;
            optionRects.add(new float[] {PAD * 2f, top, textW - PAD, layout.height});

            y -= layout.height + LINE_GAP;
            if (y < panelY + PAD) {
                break;                                    // out of room; rest are clipped
            }
        }
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

    /** Dialog text carries hard line breaks and double spaces; normalise for wrapping. */
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
