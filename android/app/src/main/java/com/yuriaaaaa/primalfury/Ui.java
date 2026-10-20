package com.yuriaaaaa.primalfury;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for the screens that are built in code. */
final class Ui {
    private Ui() {}

    static final int BG = Color.rgb(16, 8, 10);
    static final int GOLD = Color.rgb(240, 180, 60);
    static final int RED = Color.rgb(178, 24, 24);
    static final int GREY = Color.rgb(70, 80, 100);
    static final int DIM = Color.rgb(130, 138, 155);

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static TextView text(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    static TextView heading(Context c, String s) {
        TextView t = text(c, s, 17, GOLD);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static Button button(Context c, String s, int color) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(c, 10));
        b.setBackground(bg);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        return b;
    }

    /** On/off row: the label on the left and a pill that says ON or OFF, so the state can't hide off screen. */
    interface Toggle { void on(boolean v); }

    static LinearLayout toggleRow(Context c, String label, boolean initial, Toggle l) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 52));
        row.addView(text(c, label, 16, Color.WHITE), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button pill = button(c, "", GREY);
        final boolean[] on = {initial};
        Runnable paint = () -> {
            pill.setText(on[0] ? "ON" : "OFF");
            ((GradientDrawable) pill.getBackground()).setColor(on[0] ? Color.rgb(46, 160, 67) : GREY);
        };
        paint.run();
        pill.setOnClickListener(v -> { on[0] = !on[0]; paint.run(); l.on(on[0]); });
        row.addView(pill, new LinearLayout.LayoutParams(dp(c, 110), dp(c, 46)));
        row.setTag(new Object[] {on, paint});
        return row;
    }

    /** Sets a toggleRow's state without calling its listener. */
    static void setToggle(LinearLayout row, boolean v) {
        Object[] t = (Object[]) row.getTag();
        ((boolean[]) t[0])[0] = v;
        ((Runnable) t[1]).run();
    }

    /** Dark text box with a gold outline (the system default can come out white on white in dark mode). */
    static GradientDrawable fieldBackground(Context c) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(28, 32, 44));
        bg.setStroke(dp(c, 2), GOLD);
        bg.setCornerRadius(dp(c, 8));
        return bg;
    }

    static LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    static LinearLayout.LayoutParams weight(int marginRight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(0, 0, marginRight, 0);
        return p;
    }
}
