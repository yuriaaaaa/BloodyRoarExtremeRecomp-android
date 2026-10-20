package com.yuriaaaaa.primalfury;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Process;
import android.system.Os;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.libsdl.app.SDLActivity;

/**
 * Runs the recompiled game in its own process (":game"), so quitting always starts clean. The settings reach the
 * native side as environment variables (android/native/app_android.cpp). A three-finger tap opens the settings.
 */
public class PrimalFuryActivity extends SDLActivity {
    static final String EXTRA_DIR = "dir";
    private static final int REQ_SETTINGS = 7;
    private static final long THREE_TAP_MS = 300;

    private long firstDown;
    private boolean settingsOpen;
    private AlertDialog exitDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String dir = getIntent().getStringExtra(EXTRA_DIR);
        if (dir == null) dir = Paths.gameDir(this);
        SharedPreferences p = Paths.prefs(this);
        try {
            Os.setenv("PRIMALFURY_DIR", dir, true);
            Os.setenv("PRIMALFURY_DATA", Paths.dataDir().getAbsolutePath(), true);
            Os.setenv("PRIMALFURY_CACHE", getCodeCacheDir().getAbsolutePath(), true);
            Os.setenv("PRIMALFURY_TOUCH", TouchConfig.file(this).getAbsolutePath(), true);
            Os.setenv("PRIMALFURY_SCALE", Integer.toString(p.getInt("scale", SettingsActivity.SCALE_DEFAULT)), true);
            Os.setenv("PRIMALFURY_ASPECT", p.getString("aspect", "4:3"), true);
            Os.setenv("PRIMALFURY_VSYNC", p.getBoolean("vsync", true) ? "1" : "0", true);
            Os.setenv("PRIMALFURY_FPS", Integer.toString(p.getInt("fps", 60)), true);
            Os.setenv("PRIMALFURY_DEBUG", p.getBoolean("debug", false) ? "1" : "0", true);
        } catch (Exception e) {
            Log.e("PrimalFury", "setenv failed", e);
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL3", "main"};
    }

    /** Three fingers landing together (within 300 ms of the first) open the settings; held fingers never do. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) firstDown = ev.getEventTime();
        if (a == MotionEvent.ACTION_POINTER_DOWN && ev.getPointerCount() == 3 && !settingsOpen
                && ev.getEventTime() - firstDown <= THREE_TAP_MS) {
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);   // let go of whatever the fingers were pressing in game
            cancel.recycle();
            settingsOpen = true;
            startActivityForResult(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_IN_GAME, true), REQ_SETTINGS);
            return true;
        }
        if (settingsOpen) return true;
        return super.dispatchTouchEvent(ev);
    }

    /** The phone's back button or gesture (not a controller's) asks before leaving the game. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int src = ev.getSource();
        boolean pad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK && !pad) {
            if (ev.getAction() == KeyEvent.ACTION_UP && !ev.isCanceled()) confirmExit();
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    @Override
    public void onBackPressed() {
        confirmExit();
    }

    private void confirmExit() {
        if (exitDialog != null && exitDialog.isShowing()) return;
        exitDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Exit the game?")
                .setMessage("Unsaved progress will be lost.")
                .setNegativeButton("Continue", (d, w) -> d.dismiss())
                .setPositiveButton("Exit", (d, w) -> quit())
                .create();
        exitDialog.show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SETTINGS) return;
        settingsOpen = false;
        if (res == SettingsActivity.RESULT_QUIT) quit();
    }

    private void quit() {
        startActivity(new Intent(this, LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Process.killProcess(Process.myPid());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) Process.killProcess(Process.myPid());   // the native game can't restart in the same process
    }
}
