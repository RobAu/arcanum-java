package com.arcanum.ce.ui;

import java.util.ArrayDeque;
import java.util.Deque;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import com.arcanum.ce.tig.TigArt;
import com.arcanum.ce.tig.art.ArtId;
import com.arcanum.ce.tig.font.TigFontRenderer;
import com.arcanum.ce.tig.mes.Mes;

/**
 * The interactive main menu, ported from {@code src/ui/mainmenu_ui.c}.
 *
 * Reproduces the menu graph (main → single player / options / credits → back)
 * with the real backgrounds (interface art) and labels from
 * {@code mes\mainmenu.mes} at the button positions from the C button tables.
 * Mouse hover highlights an option; left-click activates it; right-click or
 * Escape goes back. Activating "Exit Game" quits. Sub-screens deeper than these
 * menus (new character, load game, the iso view) are not built yet.
 *
 * The C engine renders clickable button *art* with the Morph font; here the
 * options are drawn as text hit-rects. Button-art rendering is a later refinement.
 */
public final class MainMenuScreen implements Screen {

    /** MM_WINDOW_* subset we implement (cf. mainmenu_ui.h). */
    private enum Window { MAINMENU, SINGLE_PLAYER, OPTIONS, CREDITS }

    /** One menu option: label + what activating it does. */
    private static final class Item {
        final int labelId;
        final int y;
        final Action action;
        final Window target;     // for NAVIGATE

        Item(int labelId, int y, Action action, Window target) {
            this.labelId = labelId;
            this.y = y;
            this.action = action;
            this.target = target;
        }
    }

    private enum Action { NAVIGATE, NEW_GAME, BACK, QUIT, UNIMPLEMENTED }

    private static final int BUTTON_X = 410;
    private static final int ITEM_W = 200;
    private static final int ITEM_H = 34;

    private static final Color NORMAL = new Color(0.85f, 0.78f, 0.55f, 1f);
    private static final Color HOVER = new Color(1f, 0.95f, 0.6f, 1f);
    private static final Color DIM = new Color(0.55f, 0.5f, 0.4f, 1f);

    private static final int MENU_FONT_ART_NUM = 27;   // morph15font.art

    private int mainmenuMes = Mes.INVALID_HANDLE;
    private TigFontRenderer menuFont;
    private final Deque<Window> backStack = new ArrayDeque<>();
    private Window current = Window.MAINMENU;
    private boolean ready;
    private int forcedHover = -1;   // -Darcanum.hover for headless capture

    @Override
    public void create() {
        mainmenuMes = Mes.load("mes\\mainmenu.mes");
        menuFont = TigFontRenderer.load(MENU_FONT_ART_NUM);
        ready = TigArt.load(TigArt.buildPath(backgroundIdOf(Window.MAINMENU))) != null;

        String startMenu = System.getProperty("arcanum.menu");
        if (startMenu != null) {
            try {
                current = Window.valueOf(startMenu.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // keep MAINMENU
            }
        }
        String hover = System.getProperty("arcanum.hover");
        if (hover != null) {
            try {
                forcedHover = Integer.parseInt(hover);
            } catch (NumberFormatException ignored) {
                // none
            }
        }
    }

    // -- menu model (background art num + items) ------------------------------
    private static int backgroundArtNum(Window w) {
        switch (w) {
            case SINGLE_PLAYER: return 331;
            case OPTIONS: return 556;
            case CREDITS: return 2;     // black.art
            case MAINMENU:
            default: return 329;        // MainMenuBack.ART
        }
    }

    private static int backgroundIdOf(Window w) {
        return ArtId.interfaceIdCreate(backgroundArtNum(w), 0, 0, 0);
    }

    private static Item[] itemsOf(Window w) {
        switch (w) {
            case SINGLE_PLAYER:
                return new Item[] {
                    new Item(50, 143, Action.NEW_GAME, null),       // New Game
                    new Item(51, 193, Action.UNIMPLEMENTED, null),  // Load Game
                    new Item(52, 243, Action.UNIMPLEMENTED, null),  // Last Save
                    new Item(53, 293, Action.UNIMPLEMENTED, null),  // View Intro
                    new Item(54, 343, Action.BACK, null),           // Cancel
                };
            case OPTIONS:
                return new Item[] {
                    new Item(72, 343, Action.BACK, null),           // Continue (back)
                };
            case CREDITS:
                return new Item[] {
                    new Item(54, 343, Action.BACK, null),           // Cancel
                };
            case MAINMENU:
            default:
                return new Item[] {
                    new Item(10, 143, Action.NAVIGATE, Window.SINGLE_PLAYER),
                    new Item(12, 193, Action.NAVIGATE, Window.OPTIONS),
                    new Item(13, 243, Action.NAVIGATE, Window.CREDITS),
                    new Item(14, 293, Action.QUIT, null),
                };
        }
    }

    // -- frame ----------------------------------------------------------------
    @Override
    public void render(SpriteBatch batch, BitmapFont font, int width, int height) {
        if (!ready) {
            font.draw(batch, "Main menu unavailable (game data not found).", 16, height - 16);
            return;
        }

        int bgId = backgroundIdOf(current);
        int[] bw = new int[1];
        int[] bh = new int[1];
        TigArt.size(TigArt.buildPath(bgId), bw, bh);
        int bx = Math.max(0, (width - bw[0]) / 2);
        int by = Math.max(0, (height - bh[0]) / 2);
        TigArt.draw(batch, bgId, bx, by, height);

        Item[] items = itemsOf(current);
        int hovered = hitTest(items, bx, by, height);
        update(items, hovered);     // input handled after we know the hovered item

        for (int i = 0; i < items.length; i++) {
            Item it = items[i];
            String label = mainmenuMes != Mes.INVALID_HANDLE
                    ? Mes.getMsg(mainmenuMes, it.labelId) : "?";
            Color color = it.action == Action.UNIMPLEMENTED ? DIM
                    : (i == hovered ? HOVER : NORMAL);
            float lx = bx + BUTTON_X;
            if (menuFont != null) {
                menuFont.draw(batch, label, lx, by + it.y, height, color);
            } else {
                float ly = height - (by + it.y) - 4;   // top-left -> baseline
                font.setColor(color);
                font.draw(batch, label, lx, ly);
                font.setColor(Color.WHITE);
            }
        }
    }

    /** @return index of the option under the mouse, or -1. */
    private int hitTest(Item[] items, int bx, int by, int height) {
        if (forcedHover >= 0) {
            return forcedHover < items.length ? forcedHover : -1;
        }
        if (Gdx.input == null) {
            return -1;
        }
        int mx = Gdx.input.getX();
        int my = Gdx.input.getY();        // top-left origin, matches our layout
        for (int i = 0; i < items.length; i++) {
            int x0 = bx + BUTTON_X;
            int y0 = by + items[i].y - 4;
            if (mx >= x0 && mx <= x0 + ITEM_W && my >= y0 && my <= y0 + ITEM_H) {
                return i;
            }
        }
        return -1;
    }

    private void update(Item[] items, int hovered) {
        if (Gdx.input == null) {
            return;
        }
        boolean back = Gdx.input.isButtonJustPressed(Input.Buttons.RIGHT)
                || Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE);
        if (back) {
            goBack();
            return;
        }
        if (hovered >= 0 && Gdx.input.justTouched()) {
            activate(items[hovered]);
        }
    }

    private void activate(Item it) {
        switch (it.action) {
            case NAVIGATE:
                backStack.push(current);
                current = it.target;
                break;
            case NEW_GAME:
                // No character creation yet: drop straight into the world view.
                ScreenManager.push(new MapWorldScreen());
                break;
            case BACK:
                goBack();
                break;
            case QUIT:
                Gdx.app.exit();
                break;
            case UNIMPLEMENTED:
            default:
                // No screen for this yet (new char / load / iso view).
                break;
        }
    }

    private void goBack() {
        if (!backStack.isEmpty()) {
            current = backStack.pop();
        } else {
            current = Window.MAINMENU;
        }
    }
}
