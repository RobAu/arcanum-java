package com.arcanum.ce;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

/**
 * Desktop entry point for the Arcanum CE Java port.
 *
 * Mirrors the role of {@code src/main.c} in the C engine: it parses a few
 * command-line switches (see the C engine's README -- {@code -window},
 * {@code -geometry=WxH}) and starts the application via the libGDX LWJGL3
 * backend.
 */
public final class DesktopLauncher {

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        int width = 800;   // C engine default
        int height = 600;
        boolean windowed = true;

        for (String arg : args) {
            if (arg.equals("-window")) {
                windowed = true;
            } else if (arg.startsWith("-geometry=")) {
                String geom = arg.substring("-geometry=".length());
                int x = geom.indexOf('x');
                if (x > 0) {
                    try {
                        width = Integer.parseInt(geom.substring(0, x));
                        height = Integer.parseInt(geom.substring(x + 1));
                    } catch (NumberFormatException ignored) {
                        // keep defaults
                    }
                }
            }
        }

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Arcanum: Of Steamworks and Magick Obscura (CE / Java)");
        config.setWindowedMode(width, height);
        config.useVsync(true);
        config.setForegroundFPS(60);

        new Lwjgl3Application(new ArcanumGame(), config);
    }
}
