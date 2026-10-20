package com.yuriaaaaa.primalfury;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Locale;

/**
 * The touch controls the game draws, as the player set them up. Saved to files/touch.cfg, which the game reads
 * at start and again whenever the file changes (android/native/touch.cpp parses the same format).
 */
final class TouchConfig {
    static final String[] NAMES = {"A", "B", "X", "Y", "WHITE", "BLACK", "LT", "RT", "BACK", "START", "DPAD"};
    static final String[] LABELS = {"A", "B", "X", "Y", "W", "Bk", "LT", "RT", "Back", "Start", "D-pad"};
    static final int[] COLORS = {0xFF59D959, 0xFFF25959, 0xFF5999FF, 0xFFFFD940, 0xFFF0F0F0, 0xFF3C3C3C};
    static final int N = NAMES.length;
    static final int DPAD = N - 1;

    float opacity = 0.6f, scale = 1.0f;
    boolean show = true;
    /** Display: fill the screen instead of keeping the game's aspect (kept here so the game picks it up on resume). */
    boolean stretch;
    boolean padHide = true;
    /** Per button: centre as fractions of screen width and height, radius in screen heights; NaN = default. */
    final float[] fx = new float[N], fy = new float[N], r = new float[N];

    TouchConfig() {
        clearLayout();
    }

    void clearLayout() {
        for (int i = 0; i < N; i++) fx[i] = fy[i] = r[i] = Float.NaN;
    }

    boolean custom(int i) {
        return !Float.isNaN(fx[i]);
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), "touch.cfg");
    }

    /** Default layout, the same as Layout() in android/native/touch.cpp: {x, y, r} in screen heights. */
    static float[][] defaults(float aspect, float insetL, float insetR) {
        float L = Math.max(insetL, 0.03f), R = aspect - Math.max(insetR, 0.03f), C = aspect * 0.5f;
        return new float[][] {
            {R - 0.25f, 0.82f, 0.085f}, {R - 0.10f, 0.67f, 0.085f}, {R - 0.40f, 0.67f, 0.085f}, {R - 0.25f, 0.52f, 0.085f},
            {L + 0.10f, 0.32f, 0.065f}, {R - 0.10f, 0.32f, 0.065f}, {L + 0.10f, 0.13f, 0.065f}, {R - 0.10f, 0.13f, 0.065f},
            {C - 0.12f, 0.10f, 0.050f}, {C + 0.12f, 0.10f, 0.050f}, {L + 0.24f, 0.70f, 0.180f},
        };
    }

    static TouchConfig load(Context c) {
        TouchConfig t = new TouchConfig();
        File f = file(c);
        if (!f.isFile()) return t;
        try (BufferedReader in = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                try {
                    switch (p[0]) {
                        case "opacity": t.opacity = Float.parseFloat(p[1]); break;
                        case "scale": t.scale = Float.parseFloat(p[1]); break;
                        case "show": t.show = !"0".equals(p[1]); break;
                        case "stretch": t.stretch = "1".equals(p[1]); break;
                        case "padhide": t.padHide = !"0".equals(p[1]); break;
                        case "btn":
                            for (int i = 0; i < N; i++) {
                                if (!NAMES[i].equals(p[1])) continue;
                                t.fx[i] = Float.parseFloat(p[2]);
                                t.fy[i] = Float.parseFloat(p[3]);
                                t.r[i] = Float.parseFloat(p[4]);
                            }
                            break;
                        default: break;
                    }
                } catch (RuntimeException ignored) {
                    // skip a damaged line
                }
            }
        } catch (Exception ignored) {
            // unreadable file: defaults
        }
        return t;
    }

    void save(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "opacity %.3f\nscale %.3f\nshow %d\nstretch %d\npadhide %d\n",
                opacity, scale, show ? 1 : 0, stretch ? 1 : 0, padHide ? 1 : 0));
        for (int i = 0; i < N; i++)
            if (custom(i)) sb.append(String.format(Locale.US, "btn %s %.5f %.5f %.5f\n", NAMES[i], fx[i], fy[i], r[i]));
        File f = file(c), tmp = new File(f.getPath() + ".tmp");
        try (FileWriter w = new FileWriter(tmp)) {
            w.write(sb.toString());
        } catch (Exception e) {
            return;
        }
        tmp.renameTo(f);
    }
}
