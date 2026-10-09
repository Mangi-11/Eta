package io.github.mangi.eta.validation;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Synthetic screen: no accounts, network requests or user data. */
public class DisplayProbeActivity extends Activity {
    private SharedPreferences state;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        state = getSharedPreferences("display_probe", MODE_PRIVATE);
        state.edit().clear().putInt("display", getDisplay().getDisplayId()).putInt("taps", 0)
            .putInt("longPresses", 0).putString("text", "").apply();
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 24, 24, 24);
        content.setBackgroundColor(0xfff5f5f5);
        scroll.addView(content);
        TextView title = new TextView(this);
        title.setText("Eta display validation");
        title.setTextSize(22);
        content.addView(title);
        if (getIntent().getData() != null && "frames".equals(getIntent().getData().getHost())) {
            title.postOnAnimation(new Runnable() {
                private int frame;
                @Override public void run() {
                    if (isFinishing() || isDestroyed()) return;
                    title.setText("Eta frame validation: " + (++frame));
                    title.postOnAnimation(this);
                }
            });
        }
        EditText editor = new EditText(this);
        editor.setHint("Unicode input");
        content.addView(editor, new LinearLayout.LayoutParams(-1, 180));
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                state.edit().putString("text", s.toString()).apply();
            }
            public void afterTextChanged(Editable value) {}
        });
        Button button = new Button(this);
        button.setText("Counter: 0");
        if (this instanceof LandscapeProbeActivity) button.setBackgroundTintList(ColorStateList.valueOf(0xff23aa55));
        content.addView(button, new LinearLayout.LayoutParams(-1, 180));
        button.setOnClickListener(v -> {
            int taps = state.getInt("taps", 0) + 1;
            state.edit().putInt("taps", taps).apply();
            button.setText("Counter: " + taps);
        });
        button.setOnLongClickListener(v -> {
            state.edit().putInt("longPresses", state.getInt("longPresses", 0) + 1).apply();
            return true;
        });
        Button freeze = new Button(this);
        if (getIntent().getBooleanExtra("stall_validation", false)) {
            freeze.setText("Block UI for 15 seconds");
            content.addView(freeze, new LinearLayout.LayoutParams(-1, 180));
            freeze.setOnClickListener(v -> {
                state.edit().putBoolean("blocked", true).commit();
                android.os.SystemClock.sleep(15_000);
                state.edit().putBoolean("blocked", false).commit();
            });
        }
        Button rotate = new Button(this);
        if (this instanceof LandscapeProbeActivity) {
            rotate.setText("Switch orientation");
            content.addView(rotate, new LinearLayout.LayoutParams(-1, 180));
            rotate.setOnClickListener(v -> setRequestedOrientation(
                getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                    ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        }
        for (int i = 0; i < 60; i++) {
            TextView row = new TextView(this);
            row.setText("Validation row " + i);
            row.setTextSize(20);
            row.setPadding(12, 30, 12, 30);
            content.addView(row);
        }
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> state.edit().putInt("scrollY", y).apply());
        setContentView(scroll);
        content.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            bounds("editor", editor);
            bounds("button", button);
            bounds("title", title);
            if (freeze.getParent() != null) bounds("freeze", freeze);
            if (rotate.getParent() != null) bounds("rotate", rotate);
            Point size = new Point();
            getDisplay().getRealSize(size);
            state.edit().putInt("orientation", getResources().getConfiguration().orientation)
                .putInt("displayWidth", size.x).putInt("displayHeight", size.y)
                .putInt("layoutWidth", scroll.getWidth()).putInt("layoutHeight", scroll.getHeight()).apply();
        });
    }

    private void bounds(String name, View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        state.edit().putInt(name + "X", location[0] + view.getWidth() / 2)
            .putInt(name + "Y", location[1] + view.getHeight() / 2)
            .putInt(name + "Width", view.getWidth()).putInt(name + "Height", view.getHeight()).apply();
    }
}
