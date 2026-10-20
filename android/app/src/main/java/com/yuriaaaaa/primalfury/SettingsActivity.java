package com.yuriaaaaa.primalfury;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Settings, opened from the launcher or in game with a three-finger tap. Touch control and stretch changes show as
 * soon as the game resumes; the rest apply the next time the game starts.
 */
public class SettingsActivity extends Activity {
    static final String EXTRA_IN_GAME = "in_game";
    static final int RESULT_QUIT = RESULT_FIRST_USER;
    static final int SCALE_DEFAULT = 2;
    static final int[] SCALE = {1, 2, 3, 4};
    static final String[] SCALE_LABEL = {"1x (480p)", "2x (960p)", "3x", "4x"};
    static final int[] FPS = {30, 60, 0};
    static final String[] FPS_LABEL = {"30", "60", "Unlimited"};
    static final String[] ASPECT = {"4:3", "16:9"};

    private TouchConfig cfg;
    private boolean inGame;
    private LinearLayout show, padHide;
    private SeekBar size, opacity;
    private TextView sizeVal, opacityVal;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        inGame = getIntent().getBooleanExtra(EXTRA_IN_GAME, false);
        cfg = TouchConfig.load(this);
        SharedPreferences prefs = Paths.prefs(this);
        String later = inGame ? "Applies the next time the game starts." : "Applies when the game starts.";

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad * 2, pad, pad * 2, pad);
        scroll.addView(col);

        TextView title = Ui.heading(this, inGame ? "Paused: settings" : "Settings");
        title.setTextSize(24);
        col.addView(title);

        if (inGame) {
            LinearLayout row = new LinearLayout(this);
            Button resume = Ui.button(this, "Resume game", Ui.RED);
            resume.setOnClickListener(v -> finish());
            Button quit = Ui.button(this, "Quit to launcher", Ui.GREY);
            quit.setOnClickListener(v -> { setResult(RESULT_QUIT); finish(); });
            row.addView(resume, Ui.weight(Ui.dp(this, 10)));
            row.addView(quit, Ui.weight(0));
            col.addView(row, Ui.margins(0, Ui.dp(this, 12), 0, 0));
        }

        // Graphics
        col.addView(Ui.heading(this, "Graphics"), Ui.margins(0, Ui.dp(this, 22), 0, Ui.dp(this, 4)));
        col.addView(Ui.text(this, "Internal resolution (higher is sharper, lower runs faster)", 14, Color.WHITE));
        RadioGroup scale = group();
        int curScale = prefs.getInt("scale", SCALE_DEFAULT);
        for (int i = 0; i < SCALE.length; i++) {
            RadioButton rb = radio(SCALE_LABEL[i], 100 + i);
            scale.addView(rb);
            if (SCALE[i] == curScale) rb.setChecked(true);
        }
        scale.setOnCheckedChangeListener((g, id) -> prefs.edit().putInt("scale", SCALE[id - 100]).apply());
        col.addView(scale);
        col.addView(note(later));

        col.addView(Ui.text(this, "Frame rate cap", 14, Color.WHITE), Ui.margins(0, Ui.dp(this, 12), 0, 0));
        RadioGroup fps = group();
        int curFps = prefs.getInt("fps", 60);
        for (int i = 0; i < FPS.length; i++) {
            RadioButton rb = radio(FPS_LABEL[i], 300 + i);
            fps.addView(rb);
            if (FPS[i] == curFps) rb.setChecked(true);
        }
        fps.setOnCheckedChangeListener((g, id) -> prefs.edit().putInt("fps", FPS[id - 300]).apply());
        col.addView(fps);
        col.addView(note("The game runs at 60. " + later));
        col.addView(Ui.toggleRow(this, "VSync", prefs.getBoolean("vsync", true),
                on -> prefs.edit().putBoolean("vsync", on).apply()), Ui.margins(0, Ui.dp(this, 6), 0, 0));
        col.addView(note("Off can lower input lag on some phones but may tear. " + later));

        // Display
        col.addView(Ui.heading(this, "Display"), Ui.margins(0, Ui.dp(this, 22), 0, Ui.dp(this, 4)));
        col.addView(Ui.text(this, "Aspect ratio", 14, Color.WHITE));
        RadioGroup aspect = group();
        String curAspect = prefs.getString("aspect", "4:3");
        for (int i = 0; i < ASPECT.length; i++) {
            RadioButton rb = radio(ASPECT[i], 200 + i);
            aspect.addView(rb);
            if (ASPECT[i].equals(curAspect)) rb.setChecked(true);
        }
        aspect.setOnCheckedChangeListener((g, id) -> prefs.edit().putString("aspect", ASPECT[id - 200]).apply());
        col.addView(aspect);
        col.addView(note("4:3 is the game's own shape. 16:9 tells the game it is on a widescreen TV. " + later));
        LinearLayout stretch = Ui.toggleRow(this, "Stretch to fill", cfg.stretch, on -> { cfg.stretch = on; save(); });
        col.addView(stretch, Ui.margins(0, Ui.dp(this, 6), 0, 0));
        col.addView(note("Stretches the picture over the whole screen instead of showing black bars. "
                + (inGame ? "Applies when you resume." : "")));

        // Touch controls
        col.addView(Ui.heading(this, "Touch controls"), Ui.margins(0, Ui.dp(this, 22), 0, Ui.dp(this, 4)));
        show = Ui.toggleRow(this, "Show touch controls", cfg.show, on -> { cfg.show = on; save(); });
        col.addView(show);
        padHide = Ui.toggleRow(this, "Hide them when a controller is connected", cfg.padHide, on -> { cfg.padHide = on; save(); });
        col.addView(padHide, Ui.margins(0, Ui.dp(this, 6), 0, 0));
        col.addView(note("With a controller connected the touch controls step aside. A three-finger tap still opens "
                + "these settings."));

        sizeVal = Ui.text(this, "", 14, Color.WHITE);
        col.addView(sizeVal, Ui.margins(0, Ui.dp(this, 8), 0, 0));
        size = slider(40, 250, Math.round(cfg.scale * 100), v -> { cfg.scale = v / 100f; label(); save(); });
        col.addView(size);

        opacityVal = Ui.text(this, "", 14, Color.WHITE);
        col.addView(opacityVal, Ui.margins(0, Ui.dp(this, 8), 0, 0));
        opacity = slider(10, 100, Math.round(cfg.opacity * 100), v -> { cfg.opacity = v / 100f; label(); save(); });
        col.addView(opacity);
        label();

        LinearLayout row = new LinearLayout(this);
        Button edit = Ui.button(this, "Move and resize buttons", Ui.RED);
        edit.setOnClickListener(v -> startActivity(new Intent(this, ControlsEditorActivity.class)));
        Button reset = Ui.button(this, "Reset controls", Ui.GREY);
        reset.setOnClickListener(v -> resetControls());
        row.addView(edit, Ui.weight(Ui.dp(this, 10)));
        row.addView(reset, Ui.weight(0));
        col.addView(row, Ui.margins(0, Ui.dp(this, 12), 0, 0));
        col.addView(note("A punches, B kicks, X is beast, Y is the rave; White and Black are the extra Xbox buttons. "
                + "Slide a thumb across A/B/X/Y to chain them, and around the D-pad for diagonals."));

        // Debug
        col.addView(Ui.heading(this, "Debug"), Ui.margins(0, Ui.dp(this, 22), 0, Ui.dp(this, 4)));
        col.addView(Ui.toggleRow(this, "Debug logging", prefs.getBoolean("debug", false),
                on -> prefs.edit().putBoolean("debug", on).apply()));
        col.addView(note("Writes primal_fury_NNN.log for each run to Internal storage/PrimalFury/logs. " + later));

        scroll.setFitsSystemWindows(true);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg = TouchConfig.load(this);   // the editor may have changed the layout
    }

    private void save() {
        cfg.save(this);
    }

    private void resetControls() {
        TouchConfig old = cfg;   // the display switch is not a control
        cfg = new TouchConfig();
        cfg.stretch = old.stretch;
        save();
        Ui.setToggle(padHide, true);
        Ui.setToggle(show, true);
        size.setProgress(100 - 40);
        opacity.setProgress(60 - 10);
        label();
    }

    private void label() {
        sizeVal.setText("Button size: " + Math.round(cfg.scale * 100) + "%");
        opacityVal.setText("Opacity: " + Math.round(cfg.opacity * 100) + "%");
    }

    private TextView note(String s) {
        TextView t = Ui.text(this, s, 12, Ui.DIM);
        t.setPadding(0, Ui.dp(this, 4), 0, 0);
        return t;
    }

    private RadioGroup group() {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        return g;
    }

    private RadioButton radio(String s, int id) {
        RadioButton r = new RadioButton(this);
        r.setId(id);
        r.setText(s);
        r.setTextColor(Color.WHITE);
        r.setTextSize(15);
        r.setPadding(0, 0, Ui.dp(this, 14), 0);
        return r;
    }

    interface IntChange { void on(int v); }

    private SeekBar slider(int min, int max, int value, IntChange l) {
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        s.setProgress(Math.max(0, Math.min(max - min, value - min)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) { if (user) l.on(p + min); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        s.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40)));
        return s;
    }
}
