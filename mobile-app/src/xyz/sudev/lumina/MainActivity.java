package xyz.sudev.lumina;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private AudioAnalyzer analyzer;
    private BorderRenderer renderer;
    private TextView status;
    private Button permBtn;
    private final Handler handler = new Handler();
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (hasMic())
                analyzer.watchdog();
            updateStatus();
            handler.postDelayed(this, 250);
        }
    };

    class Preview extends View {
        Preview(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            renderer.update(analyzer);
            renderer.draw(c, getWidth(), getHeight());
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float d = getResources().getDisplayMetrics().density;
        analyzer = new AudioAnalyzer(this);
        applyConfig();
        renderer = new BorderRenderer(d);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        Preview preview = new Preview(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (36 * d);
        panel.setPadding(pad, 0, pad, (int) (64 * d));

        TextView title = new TextView(this);
        title.setText("Lumina");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);

        TextView hint = new TextView(this);
        hint.setText("v2 · play music and watch the edge");
        hint.setTextColor(0xAAFFFFFF);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, (int) (6 * d), 0, (int) (14 * d));
        panel.addView(hint);

        status = new TextView(this);
        status.setTextColor(0xFFB0FFB0);
        status.setTextSize(12);
        status.setTypeface(android.graphics.Typeface.MONOSPACE);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, (int) (14 * d));
        panel.addView(status);

        final TextView sensLabel = new TextView(this);
        sensLabel.setTextColor(0xCCFFFFFF);
        sensLabel.setTextSize(13);
        sensLabel.setGravity(Gravity.CENTER);
        panel.addView(sensLabel);
        SeekBar sens = new SeekBar(this);
        int sv = getSharedPreferences("beat", MODE_PRIVATE).getInt("sens", 50);
        sens.setMax(100);
        sens.setProgress(sv);
        sensLabel.setText("Beat sensitivity: " + sv);
        sens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                sensLabel.setText("Beat sensitivity: " + p);
                getSharedPreferences("beat", MODE_PRIVATE).edit().putInt("sens", p).apply();
                applyConfig();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        panel.addView(sens, new LinearLayout.LayoutParams(-1, (int) (40 * d)));

        final TextView radiusLabel = new TextView(this);
        radiusLabel.setTextColor(0xCCFFFFFF);
        radiusLabel.setTextSize(13);
        radiusLabel.setGravity(Gravity.CENTER);
        panel.addView(radiusLabel);
        SeekBar radius = new SeekBar(this);
        int rv = getSharedPreferences("beat", MODE_PRIVATE).getInt("radius", BorderRenderer.DEFAULT_RADIUS);
        radius.setMax(100);
        radius.setProgress(rv);
        radiusLabel.setText("Border radius: " + rv);
        renderer.setCornerRadius(rv);
        radius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                radiusLabel.setText("Border radius: " + p);
                renderer.setCornerRadius(p);
                getSharedPreferences("beat", MODE_PRIVATE).edit().putInt("radius", p).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        panel.addView(radius, new LinearLayout.LayoutParams(-1, (int) (40 * d)));

        final Button modeBtn = makeButton(modeLabel(), d);
        modeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int m = 1 - getSharedPreferences("beat", MODE_PRIVATE).getInt("mode", 0);
                getSharedPreferences("beat", MODE_PRIVATE).edit().putInt("mode", m).apply();
                applyConfig();
                modeBtn.setText(modeLabel());
            }
        });
        panel.addView(modeBtn);

        permBtn = makeButton("Allow microphone (needed to hear music)", d);
        permBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, 1);
            }
        });
        panel.addView(permBtn);

        Button setBtn = makeButton("Set as wallpaper", d);
        setBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPicker();
            }
        });
        panel.addView(setBtn);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(panel, lp);
        setContentView(root);
    }

    private Button makeButton(String label, float d) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setStroke((int) (1 * d), 0x66FFFFFF);
        bg.setCornerRadius(24 * d);
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (48 * d));
        lp.topMargin = (int) (10 * d);
        btn.setLayoutParams(lp);
        return btn;
    }

    private String modeLabel() {
        return getSharedPreferences("beat", MODE_PRIVATE).getInt("mode", 0) == 0
                ? "Detector: Kick (tap to switch)"
                : "Detector: TarsosDSP percussion (tap to switch)";
    }

    private void applyConfig() {
        android.content.SharedPreferences p = getSharedPreferences("beat", MODE_PRIVATE);
        analyzer.configure(p.getInt("sens", 50), p.getInt("mode", 0));
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void openPicker() {
        try {
            Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(this, BeatWallpaperService.class));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (Throwable t2) {
                Toast.makeText(this, "Open Settings → Wallpaper → Live wallpapers → Lumina", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void updateStatus() {
        boolean mic = hasMic();
        permBtn.setVisibility(mic ? View.GONE : View.VISIBLE);
        StringBuilder s = new StringBuilder();
        s.append("mic: ").append(mic ? "granted" : "NOT granted");
        if (analyzer.error != null) {
            s.append("\nvisualizer error: ").append(analyzer.error);
        } else if (mic) {
            long now = SystemClock.uptimeMillis();
            boolean frames = now - analyzer.lastFrameMs < 1500;
            boolean sound = now - analyzer.lastDataMs < 1500;
            s.append(" | fft: ").append(frames ? "OK" : "none");
            s.append(" | audio: ").append(sound ? "yes" : "silent – play music");
            s.append(String.format("\nbass %.2f  beats %d  restarts %d",
                    analyzer.bass, analyzer.beatCount, analyzer.restarts));
        }
        status.setText(s);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        if (hasMic())
            analyzer.start();
        renderer.resetClock();
        handler.post(statusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusTick);
        analyzer.stop();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (hasMic())
            analyzer.start();
        else
            Toast.makeText(this, "Without mic permission Android won't share the audio levels", Toast.LENGTH_LONG)
                    .show();
    }
}
