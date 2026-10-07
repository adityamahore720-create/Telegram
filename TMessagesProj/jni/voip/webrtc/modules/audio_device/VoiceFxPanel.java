package org.telegram.messenger;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/** NXC logo (floating) + sliders panel. ApplicationLoader.onCreate me: VoiceFxPanel.init(this); */
public class VoiceFxPanel {
    private static final String[] NAMES = {"Gain (dB)", "Loudness Trim", "Saturation Drive",
            "Compressor Threshold (dB)", "Presence EQ (dB)", "Bass EQ (dB)", "Treble EQ (dB)", "Limiter Ceiling (dB)"};
    private static final float[] MIN = {0f, 0f, 0f, -60f, 0f, 0f, 0f, -12f};
    private static final float[] MAX = {40f, 11f, 1f, 0f, 12f, 8f, 12f, 0f};
    private static final int STEPS = 1000;
    private static final String TAG = "nxc_fx_logo";
    private static SharedPreferences prefs;

    public static void init(Application app) {
        prefs = app.getSharedPreferences("nxc_voicefx", Context.MODE_PRIVATE);
        for (int i = 0; i < 8; i++) {
            VoiceFxBridge.cur[i] = prefs.getFloat("v" + i, VoiceFxBridge.DEF[i]);
        }
        VoiceFxBridge.apply();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) { attach(a); }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    private static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    private static void attach(final Activity act) {
        final ViewGroup root = act.findViewById(android.R.id.content);
        if (root == null || root.findViewWithTag(TAG) != null) return;
        final ImageView logo = new ImageView(act);
        logo.setTag(TAG);
        logo.setImageResource(R.drawable.nxc_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(act, 10));
        bg.setStroke(dp(act, 1), 0xFF00E5FF);
        logo.setBackground(bg);
        logo.setClipToOutline(true);
        int size = dp(act, 52);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(act, 8), dp(act, 90));
        logo.setLayoutParams(lp);
        logo.setElevation(dp(act, 6));

        logo.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy, sx, sy; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = v.getX() - e.getRawX(); dy = v.getY() - e.getRawY();
                        sx = e.getRawX(); sy = e.getRawY(); moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - sx) > 12 || Math.abs(e.getRawY() - sy) > 12) moved = true;
                        if (moved) { v.setX(e.getRawX() + dx); v.setY(e.getRawY() + dy); }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) showPanel(act);
                        return true;
                }
                return false;
            }
        });
        root.addView(logo);
    }

    private static void showPanel(final Activity act) {
        final Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(act, 20), dp(act, 18), dp(act, 20), dp(act, 18));
        GradientDrawable box = new GradientDrawable();
        box.setColor(0xFF121218);
        box.setCornerRadius(dp(act, 22));
        box.setStroke(dp(act, 3), 0xFF00E5FF);
        col.setBackground(box);

        TextView title = new TextView(act);
        title.setText("NXC FIGHTER ~ SYSTEM");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        col.addView(title);

        for (int i = 0; i < 8; i++) {
            final int idx = i;
            final TextView label = new TextView(act);
            label.setTextColor(Color.WHITE);
            label.setTextSize(15);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setPadding(0, dp(act, 12), 0, 0);
            col.addView(label);

            final TextView val = new TextView(act);
            val.setTextColor(0xFFBBBBBB);
            val.setTextSize(13);

            SeekBar sb = new SeekBar(act);
            sb.setMax(STEPS);
            sb.getProgressDrawable().setTint(0xFFFF9800);
            sb.getThumb().setTint(0xFF80CBC4);
            float cur = VoiceFxBridge.cur[idx];
            sb.setProgress(Math.round((cur - MIN[idx]) / (MAX[idx] - MIN[idx]) * STEPS));
            label.setText(NAMES[idx]);
            val.setText(String.format(Locale.US, "%.2f", cur));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    float v = MIN[idx] + (MAX[idx] - MIN[idx]) * p / (float) STEPS;
                    VoiceFxBridge.cur[idx] = v;
                    val.setText(String.format(Locale.US, "%.2f", v));
                    VoiceFxBridge.apply();
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) { save(); }
            });
            col.addView(sb);
            col.addView(val);
        }

        TextView close = new TextView(act);
        close.setText("\u26A0 CLOSE \u26A0");
        close.setTextColor(Color.WHITE);
        close.setTextSize(17);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setGravity(Gravity.CENTER);
        GradientDrawable cb = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A0000, 0xFF7A0000});
        cb.setCornerRadius(dp(act, 18));
        cb.setStroke(dp(act, 2), 0xFFFF3B30);
        close.setBackground(cb);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 54));
        clp.topMargin = dp(act, 20);
        close.setLayoutParams(clp);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); d.dismiss(); }
        });
        col.addView(close);

        ScrollView sv = new ScrollView(act);
        sv.addView(col);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (act.getResources().getDisplayMetrics().widthPixels * 0.92f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private static void save() {
        if (prefs == null) return;
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < 8; i++) ed.putFloat("v" + i, VoiceFxBridge.cur[i]);
        ed.apply();
    }
}
