package com.arcanum.ce.ui;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import com.arcanum.ce.tig.TigArt;
import com.arcanum.ce.tig.art.ArtId;
import com.arcanum.ce.tig.mes.Mes;

/**
 * The main menu, ported from {@code src/ui/mainmenu_ui.c} (the
 * {@code MM_WINDOW_MAINMENU} layout).
 *
 * Renders the real menu background (interface art 329 = {@code MainMenuBack.ART})
 * and the menu option labels from {@code mes\mainmenu.mes} at the button
 * positions defined in {@code mainmenu_ui_mainmenu_no_multiplayer_buttons}. The
 * button art and click handling are not wired yet; this establishes the real
 * boot screen in place of the demo scene.
 */
public final class MainMenuScreen implements Screen {

    private static final int BACKGROUND_ART_NUM = 329;      // MainMenuBack.ART
    private static final int BUTTON_X = 410;
    private static final int[] BUTTON_Y = {143, 193, 243, 293};
    // mes\mainmenu.mes label ids for the no-multiplayer layout.
    private static final int[] LABEL_IDS = {10, 12, 13, 14}; // SP, Options, Credits, Exit

    private int mainmenuMes = Mes.INVALID_HANDLE;
    private int backgroundArtId;
    private boolean ready;

    @Override
    public void create() {
        backgroundArtId = ArtId.interfaceIdCreate(BACKGROUND_ART_NUM, 0, 0, 0);
        mainmenuMes = Mes.load("mes\\mainmenu.mes");
        // Ready if the background resolves (the menu can render even without labels).
        ready = TigArt.load(TigArt.buildPath(backgroundArtId)) != null;
    }

    @Override
    public void render(SpriteBatch batch, BitmapFont font, int width, int height) {
        if (!ready) {
            font.draw(batch, "Main menu unavailable (game data not found).", 16, height - 16);
            return;
        }

        // Full-screen background (MainMenuBack.ART is 800x600). Centre it if the
        // window is larger.
        int[] bw = new int[1];
        int[] bh = new int[1];
        TigArt.size(TigArt.buildPath(backgroundArtId), bw, bh);
        int bx = Math.max(0, (width - bw[0]) / 2);
        int by = Math.max(0, (height - bh[0]) / 2);
        TigArt.draw(batch, backgroundArtId, bx, by, height);

        // Menu option labels at the C button positions (relative to the bg origin).
        for (int i = 0; i < BUTTON_Y.length; i++) {
            String label = mainmenuMes != Mes.INVALID_HANDLE
                    ? Mes.getMsg(mainmenuMes, LABEL_IDS[i]) : "?";
            float lx = bx + BUTTON_X;
            float ly = height - (by + BUTTON_Y[i]) - 4; // top-left -> libGDX baseline
            font.draw(batch, label, lx, ly);
        }
    }
}
