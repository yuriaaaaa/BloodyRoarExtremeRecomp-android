package com.yuriaaaaa.primalfury;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/** Checks file access and the game folder, then starts the game. */
public class LauncherActivity extends Activity {
    private TextView status;
    private EditText dirField;
    private Button access, play;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, pad, pad, pad);

        ImageView cover = new ImageView(this);
        cover.setImageResource(R.drawable.cover);
        cover.setAdjustViewBounds(true);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(cover, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 24), 0, 0, 0);
        scroll.addView(col);
        root.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));

        TextView title = Ui.text(this, "Primal Fury", 26, Ui.GOLD);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        col.addView(Ui.text(this, "Bloody Roar Extreme, native Android build " + version(), 13, Ui.DIM));

        col.addView(Ui.text(this, "Game folder", 15, Color.WHITE), Ui.margins(0, Ui.dp(this, 18), 0, Ui.dp(this, 4)));
        dirField = new EditText(this);
        dirField.setTextColor(Color.WHITE);
        dirField.setHintTextColor(Ui.DIM);
        dirField.setBackground(Ui.fieldBackground(this));
        dirField.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        dirField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        dirField.setSingleLine(true);
        dirField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(dirField);

        status = Ui.text(this, "", 14, Color.rgb(215, 205, 205));
        col.addView(status, Ui.margins(0, Ui.dp(this, 10), 0, Ui.dp(this, 10)));

        access = Ui.button(this, "Allow file access", Ui.GREY);
        access.setOnClickListener(v -> requestAccess());
        col.addView(access, Ui.margins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6)));

        play = Ui.button(this, "Play", Ui.RED);
        play.setOnClickListener(v -> start());
        col.addView(play, Ui.margins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6)));

        Button settings = Ui.button(this, "Settings", Ui.GREY);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        col.addView(settings, Ui.margins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6)));

        col.addView(Ui.text(this, "Put your own copy of the USA Bloody Roar Extreme disc in this folder: the disc image "
                + "(.iso), or default.xbe with the disc's folders (fpack, cmn, game...). Saves go to Internal storage/"
                + "PrimalFury/hdd. In game, a three-finger tap opens the settings.", 12, Ui.DIM),
                Ui.margins(0, Ui.dp(this, 14), 0, 0));

        root.setFitsSystemWindows(true);
        setContentView(root);
        dirField.setText(Paths.gameDir(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[] {android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        refresh();
    }

    private void refresh() {
        boolean ok = hasAccess();
        access.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (!ok) {
            status.setText("The game needs \"All files access\" to read your game files and write saves.");
            play.setEnabled(false);
            play.setAlpha(0.5f);
            return;
        }
        Paths.dataDir().mkdirs();
        String cur = dirField.getText().toString().trim();
        if (!Paths.looksLikeGame(new File(cur))) {
            String found = Paths.gameDir(this);
            if (Paths.looksLikeGame(new File(found))) dirField.setText(found);
        }
        File d = new File(dirField.getText().toString().trim());
        File iso = Paths.iso(d);
        boolean ready = Paths.looksLikeGame(d);
        if (Paths.extracted(d)) status.setText("✓ default.xbe\n✓ fpack/\nReady.");
        else if (iso != null) status.setText("✓ " + iso.getName() + "\nReady.");
        else status.setText("✗ No disc image (.iso) or default.xbe with fpack/ in this folder.");
        play.setEnabled(ready);
        play.setAlpha(ready ? 1f : 0.5f);
    }

    private void start() {
        String dir = dirField.getText().toString().trim();
        Paths.prefs(this).edit().putString("dir", dir).apply();
        Intent i = new Intent(this, PrimalFuryActivity.class);
        i.putExtra(PrimalFuryActivity.EXTRA_DIR, dir);
        startActivity(i);
        finish();
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
