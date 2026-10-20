package com.yuriaaaaa.primalfury;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Full-screen layout editor: drag a button to move it, pinch or use the slider to resize the selected one. */
public class ControlsEditorActivity extends Activity {
    private TouchConfig cfg;
    private Board board;
    private SeekBar size;
    private TextView sizeLabel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 28) {   // draw under the camera cutout like the game does
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        cfg = TouchConfig.load(this);
        FrameLayout root = new FrameLayout(this);
        board = new Board(this);
        root.addView(board);

        // tool panel in the middle of the screen, where no default button sits
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(220, 20, 24, 36));
        bg.setCornerRadius(Ui.dp(this, 14));
        panel.setBackground(bg);
        int p = Ui.dp(this, 12);
        panel.setPadding(p, p, p, p);
        TextView hint = Ui.text(this, "Drag a button to move it. Pinch it, or use the slider, to resize.", 13, Color.WHITE);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint);
        sizeLabel = Ui.text(this, "", 13, Ui.GOLD);
        sizeLabel.setGravity(Gravity.CENTER);
        panel.addView(sizeLabel, Ui.margins(0, Ui.dp(this, 6), 0, 0));
        size = new SeekBar(this);
        size.setMax(270);   // radius 0.030 .. 0.300 screen heights
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user && board.sel >= 0) { board.setRadius(board.sel, (30 + v) / 1000f); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        panel.addView(size, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 36)));
        LinearLayout row = new LinearLayout(this);
        Button cancel = Ui.button(this, "Cancel", Ui.GREY), reset = Ui.button(this, "Defaults", Ui.GREY), done = Ui.button(this, "Save", Ui.RED);
        cancel.setOnClickListener(v -> finish());
        reset.setOnClickListener(v -> { cfg.clearLayout(); board.sel = -1; syncPanel(); board.invalidate(); });
        done.setOnClickListener(v -> { cfg.save(this); finish(); });
        row.addView(cancel, Ui.weight(Ui.dp(this, 8)));
        row.addView(reset, Ui.weight(Ui.dp(this, 8)));
        row.addView(done, Ui.weight(0));
        panel.addView(row, Ui.margins(0, Ui.dp(this, 6), 0, 0));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(Ui.dp(this, 360), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(panel, plp);
        setContentView(root);
        syncPanel();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    void syncPanel() {
        int s = board.sel;
        size.setEnabled(s >= 0);
        if (s < 0) { sizeLabel.setText("Tap a button to select it"); return; }
        float r = board.radius(s);
        sizeLabel.setText(TouchConfig.LABELS[s] + " size: " + Math.round(r * 1000));
        size.setProgress(Math.round(r * 1000) - 30);
    }

    /** Draws the buttons where the game will and lets the player move them. */
    private class Board extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        int sel = -1;
        float insetL, insetR;
        private int drag = -1;
        private float grabDx, grabDy, pinch0, r0;

        Board(Context c) {
            super(c);
            setBackgroundColor(Color.rgb(28, 34, 46));
            ring.setStyle(Paint.Style.STROKE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setColor(Color.WHITE);
        }

        @Override
        public WindowInsets onApplyWindowInsets(WindowInsets in) {
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout cut = in.getDisplayCutout();
                insetL = cut != null ? cut.getSafeInsetLeft() : 0;
                insetR = cut != null ? cut.getSafeInsetRight() : 0;
                invalidate();
            }
            return super.onApplyWindowInsets(in);
        }

        float[][] defs() {
            float h = Math.max(getHeight(), 1);
            return TouchConfig.defaults(getWidth() / h, insetL / h, insetR / h);
        }

        float cx(int i) { return cfg.custom(i) ? cfg.fx[i] * getWidth() : defs()[i][0] * getHeight(); }
        float cy(int i) { return cfg.custom(i) ? cfg.fy[i] * getHeight() : defs()[i][1] * getHeight(); }
        /** Radius before the global size slider, in screen heights. */
        float radius(int i) { return cfg.custom(i) ? cfg.r[i] : defs()[i][2]; }

        void own(int i) {   // first edit of a default button: copy its default place into the config
            if (cfg.custom(i)) return;
            float[] d = defs()[i];
            cfg.fx[i] = d[0] * getHeight() / getWidth();
            cfg.fy[i] = d[1];
            cfg.r[i] = d[2];
        }

        void setRadius(int i, float r) {
            own(i);
            cfg.r[i] = Math.max(0.03f, Math.min(0.3f, r));
            sizeLabel.setText(TouchConfig.LABELS[i] + " size: " + Math.round(cfg.r[i] * 1000));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight();
            for (int i = 0; i < TouchConfig.N; i++) {
                float x = cx(i), y = cy(i), r = radius(i) * cfg.scale * h;
                int col = i < TouchConfig.COLORS.length ? TouchConfig.COLORS[i] : 0xFFD9D9D9;
                fill.setColor(col);
                fill.setAlpha(i == sel ? 140 : 60);
                c.drawCircle(x, y, r, fill);
                ring.setColor(i == sel ? Color.WHITE : col);
                ring.setStrokeWidth(i == sel ? r * 0.12f : r * 0.08f);
                c.drawCircle(x, y, r * 0.93f, ring);
                if (i == TouchConfig.DPAD) {   // the cross the game draws
                    fill.setColor(Color.WHITE);
                    fill.setAlpha(i == sel ? 200 : 120);
                    float a = r * 0.28f, l = r * 0.80f;
                    c.drawRect(x - a, y - l, x + a, y + l, fill);
                    c.drawRect(x - l, y - a, x + l, y + a, fill);
                    continue;
                }
                String s = TouchConfig.LABELS[i];
                label.setTextSize(r * (s.length() > 2 ? 0.55f : s.length() > 1 ? 0.75f : 0.9f));
                c.drawText(s, x, y - (label.descent() + label.ascent()) / 2, label);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    drag = hit(e.getX(), e.getY());
                    sel = drag;
                    if (drag >= 0) { grabDx = cx(drag) - e.getX(); grabDy = cy(drag) - e.getY(); }
                    syncPanel();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (sel >= 0 && e.getPointerCount() == 2) {
                        pinch0 = spread(e);
                        r0 = radius(sel);
                        drag = -1;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (sel >= 0 && e.getPointerCount() >= 2 && pinch0 > 0) {
                        setRadius(sel, r0 * spread(e) / pinch0);
                        syncPanel();
                    } else if (drag >= 0) {
                        own(drag);
                        float x = Math.max(0, Math.min(getWidth(), e.getX() + grabDx));
                        float y = Math.max(0, Math.min(getHeight(), e.getY() + grabDy));
                        cfg.fx[drag] = x / getWidth();
                        cfg.fy[drag] = y / getHeight();
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drag = -1;
                    pinch0 = 0;
                    return true;
                default:
                    return true;
            }
        }

        private float spread(MotionEvent e) {
            float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        private int hit(float x, float y) {
            int best = -1;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < TouchConfig.N; i++) {
                float r = Math.max(radius(i) * cfg.scale * getHeight() * 1.2f, Ui.dp(getContext(), 24));
                float dx = x - cx(i), dy = y - cy(i), d = dx * dx + dy * dy;
                if (d <= r * r && d < bestD) { best = i; bestD = d; }
            }
            return best;
        }
    }
}
