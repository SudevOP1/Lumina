package xyz.sudev.lumina;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;

/**
 * Single continuous orange-red edge glow. Brightness is a smooth 1-D function
 * of the distance
 * from the (rounded) screen edge, drawn exactly with gradients: one linear
 * gradient per straight
 * edge (clipped to mitred trapezoids) and one radial gradient per rounded
 * corner. No stacked
 * strokes, so there are no visible bands.
 */
public class BorderRenderer {
    private static final int N = 72; // gradient stops for straight edges
    private static final int M = 56; // gradient stops for corners

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final int[] edgeColors = new int[N + 1];
    private final float[] edgePos = new float[N + 1];
    private final int[] cornerColors = new int[M + 1];
    private final float[] cornerPos = new float[M + 1];
    private final Path top = new Path(), bottom = new Path(), left = new Path(), right = new Path();
    private int pw = -1, ph = -1;

    private float env, pulse, time;
    private int lastBeat = -1;
    private final float[] rings = new float[6];
    private int ringCount;
    private long lastNanos;
    public String message;

    // per-frame profile params
    private float thick, glowAmt, ringTravel;

    public BorderRenderer(float density) {
        this.density = density;
        text.setColor(0xCCFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(15 * density);
        for (int k = 0; k <= N; k++) {
            float f = (float) k / N;
            edgePos[k] = f * f;
        }
        for (int j = 0; j <= M; j++) {
            float g = (float) (M - j) / M;
            cornerPos[j] = 1f - g * g;
        }
    }

    public void resetClock() {
        lastNanos = 0;
    }

    public void update(AudioAnalyzer a) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1f / 60f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        time += dt;

        boolean live = a != null && SystemClock.uptimeMillis() - a.lastDataMs < 1500;
        float target;
        if (live) {
            target = a.bass * a.bass;
            int bc = a.beatCount;
            if (lastBeat != -1 && bc != lastBeat) {
                pulse = 1f;
                if (ringCount < rings.length)
                    rings[ringCount++] = 0f;
            }
            lastBeat = bc;
        } else {
            target = 0.05f + 0.04f * (float) Math.sin(time * 1.6f); // idle breathing
            if (a != null)
                lastBeat = a.beatCount;
        }

        float k = target > env ? 22f : 4.5f; // quick attack, smooth release
        env += (target - env) * Math.min(1f, dt * k);
        pulse *= (float) Math.exp(-dt * 5f);

        int w = 0;
        for (int i = 0; i < ringCount; i++) {
            float p = rings[i] + dt * 1.4f;
            if (p < 1f)
                rings[w++] = p;
        }
        ringCount = w;
    }

    /** brightness (0..~1.2) at distance d px from the edge */
    private float intensity(float d) {
        float c = d / (thick * 0.45f);
        float core = (float) Math.exp(-c * c);
        float glow = glowAmt * (float) Math.exp(-d / (thick * 1.6f));
        float r = 0f;
        for (int i = 0; i < ringCount; i++) {
            float p = rings[i];
            float e = 1f - (1f - p) * (1f - p);
            float center = thick * 0.4f + e * ringTravel;
            float sigma = density * (14f + 40f * p);
            float x = (d - center) / sigma;
            float fade = (1f - p);
            r += 0.55f * fade * fade * (float) Math.exp(-x * x);
        }
        return core + glow + r;
    }

    /** orange-red ramp; hottest part goes towards amber/white */
    private static int color(float i) {
        if (i <= 0.002f)
            return Color.BLACK;
        float a = Math.min(1f, i);
        float hot = Math.max(0f, Math.min(1f, (i - 0.75f) / 0.45f));
        int r = (int) (255 * Math.min(1f, a * 1.15f));
        int g = (int) (255 * (0.24f * a + 0.45f * hot)); // #FF3D00-ish → #FFA060 at the core
        int b = (int) (255 * (0.38f * hot * hot));
        return Color.rgb(Math.min(255, r), Math.min(255, g), Math.min(255, b));
    }

    private void buildPaths(int w, int h) {
        float H = Math.min(w, h) / 2f, e = 3f;
        top.reset();
        top.moveTo(0, 0);
        top.lineTo(w, 0);
        top.lineTo(w, e);
        top.lineTo(w - H, H + e);
        top.lineTo(H, H + e);
        top.lineTo(0, e);
        top.close();
        bottom.reset();
        bottom.moveTo(0, h);
        bottom.lineTo(w, h);
        bottom.lineTo(w, h - e);
        bottom.lineTo(w - H, h - H - e);
        bottom.lineTo(H, h - H - e);
        bottom.lineTo(0, h - e);
        bottom.close();
        left.reset();
        left.moveTo(0, 0);
        left.lineTo(e, 0);
        left.lineTo(H + e, H);
        left.lineTo(H + e, h - H);
        left.lineTo(e, h);
        left.lineTo(0, h);
        left.close();
        right.reset();
        right.moveTo(w, 0);
        right.lineTo(w - e, 0);
        right.lineTo(w - H - e, H);
        right.lineTo(w - H - e, h - H);
        right.lineTo(w - e, h);
        right.lineTo(w, h);
        right.close();
        pw = w;
        ph = h;
    }

    public void draw(Canvas c, int w, int h) {
        c.drawColor(Color.BLACK);
        if (w <= 0 || h <= 0)
            return;
        if (w != pw || h != ph)
            buildPaths(w, h);

        float R = Math.round(w * 0.105f);
        thick = density * (7f + 34f * env + 12f * pulse);
        glowAmt = 0.35f + 0.45f * env + 0.3f * pulse;
        ringTravel = w * 0.22f;
        float dMax = Math.min(w, h) * 0.42f;

        for (int k = 0; k <= N; k++)
            edgeColors[k] = color(intensity(edgePos[k] * dMax));
        edgeColors[N] = Color.BLACK;
        for (int j = 0; j <= M; j++)
            cornerColors[j] = color(intensity(R * (1f - cornerPos[j])));

        LinearGradient lin = new LinearGradient(0, 0, 0, dMax, edgeColors, edgePos, Shader.TileMode.CLAMP);
        RadialGradient rad = new RadialGradient(0, 0, R, cornerColors, cornerPos, Shader.TileMode.CLAMP);
        paint.setShader(lin);

        // top
        matrix.reset();
        lin.setLocalMatrix(matrix);
        c.save();
        c.clipPath(top);
        c.clipRect(R, 0, w - R, h);
        c.drawRect(0, 0, w, h, paint);
        c.restore();
        // bottom
        matrix.setScale(1, -1);
        matrix.postTranslate(0, h);
        lin.setLocalMatrix(matrix);
        c.save();
        c.clipPath(bottom);
        c.clipRect(R, 0, w - R, h);
        c.drawRect(0, 0, w, h, paint);
        c.restore();
        // left
        matrix.setRotate(-90);
        lin.setLocalMatrix(matrix);
        c.save();
        c.clipPath(left);
        c.clipRect(0, R, w, h - R);
        c.drawRect(0, 0, w, h, paint);
        c.restore();
        // right
        matrix.setRotate(-90);
        matrix.postScale(-1, 1);
        matrix.postTranslate(w, 0);
        lin.setLocalMatrix(matrix);
        c.save();
        c.clipPath(right);
        c.clipRect(0, R, w, h - R);
        c.drawRect(0, 0, w, h, paint);
        c.restore();

        // rounded corners
        paint.setShader(rad);
        float o = 1.5f;
        corner(c, rad, R, R, 0, 0, R + o, R + o);
        corner(c, rad, w - R, R, w - R - o, 0, w, R + o);
        corner(c, rad, R, h - R, 0, h - R - o, R + o, h);
        corner(c, rad, w - R, h - R, w - R - o, h - R - o, w, h);
        paint.setShader(null);

        if (message != null)
            c.drawText(message, w / 2f, h / 2f, text);
    }

    private void corner(Canvas c, RadialGradient rad, float cx, float cy, float l, float t, float r, float b) {
        matrix.setTranslate(cx, cy);
        rad.setLocalMatrix(matrix);
        c.save();
        c.clipRect(l, t, r, b);
        c.drawRect(l, t, r, b, paint);
        c.restore();
    }
}
