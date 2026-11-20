package xyz.sudev.lumina;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;

/**
 * Single continuous orange-red edge glow. Brightness is a smooth 1-D function
 * of an inset depth whose contours are rounded rects that all keep the chosen
 * corner radius (so beat rings stay rounded as they travel inwards). Drawn with
 * one linear gradient: straight edges directly, corners as thin diagonal strips
 * each holding a shifted copy of the gradient. No stacked strokes, so there are
 * no visible bands.
 */
public class BorderRenderer {
    private static final int N = 72; // gradient stops for straight edges
    private static final float SQRT2 = 1.41421356f;

    private final float density;
    // no AA: rotated rects tile edge-to-edge without seams
    private final Paint paint = new Paint(Paint.DITHER_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final int[] edgeColors = new int[N + 1];
    private final float[] edgePos = new float[N + 1];
    private final float[] mv = new float[9];
    private float[] stripQ = new float[0], stripF = new float[0];
    private float stripR = -1;

    private float env, pulse, time;
    private int lastBeat = -1;
    private final float[] rings = new float[6];
    private int ringCount;
    private long lastNanos;
    public String message;

    // per-frame profile params
    private float thick, glowAmt, ringTravel;

    /** corner radius as a fraction of screen width (0.105 matches most phones) */
    private float radiusFrac = DEFAULT_RADIUS / 400f;
    public static final int DEFAULT_RADIUS = 42; // slider units: 0..100 → 0..25% of width

    /** @param slider 0..100, maps to 0..25% of the screen width */
    public void setCornerRadius(int slider) {
        radiusFrac = Math.max(0, Math.min(100, slider)) / 400f;
    }

    public BorderRenderer(float density) {
        this.density = density;
        text.setColor(0xCCFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(15 * density);
        for (int k = 0; k <= N; k++) {
            float f = (float) k / N;
            edgePos[k] = f * f;
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

    /**
     * Splits the corner band into strips of (nearly) constant offset f, chosen so f changes by at
     * most ~1.5px between neighbours. v = (x - y) / 2 across the band; strips stored as q = sqrt2*v.
     */
    private void buildStrips(float R) {
        float f0 = R / SQRT2 - R, fe = -R / 2f; // f at band centre / band edge
        int n = Math.max(1, (int) Math.ceil((f0 - fe) / 1.5f));
        if (stripQ.length != n + 1) {
            stripQ = new float[n + 1];
            stripF = new float[n];
        }
        float half = R * R / 2f;
        for (int k = 0; k <= n; k++) {
            float a = f0 - (f0 - fe) * k / n + R;
            stripQ[k] = SQRT2 * (float) Math.sqrt(Math.max(0f, half - a * a));
        }
        stripQ[n] = R / SQRT2;
        for (int k = 0; k < n; k++)
            stripF[k] = f0 - (f0 - fe) * (k + 0.5f) / n;
        stripR = R;
    }

    public void draw(Canvas c, int w, int h) {
        c.drawColor(Color.BLACK);
        if (w <= 0 || h <= 0)
            return;

        // keep the radius below half the short side
        float R = Math.max(1f, Math.min(Math.round(w * radiusFrac), Math.min(w, h) / 2f - 1f));
        if (R != stripR)
            buildStrips(R);
        thick = density * (7f + 34f * env + 12f * pulse);
        glowAmt = 0.35f + 0.45f * env + 0.3f * pulse;
        ringTravel = w * 0.22f;
        float dMax = Math.min(w, h) * 0.42f;

        for (int k = 0; k <= N; k++)
            edgeColors[k] = color(intensity(edgePos[k] * dMax));
        edgeColors[N] = Color.BLACK;

        LinearGradient lin = new LinearGradient(0, 0, 0, dMax, edgeColors, edgePos, Shader.TileMode.CLAMP);
        paint.setShader(lin);

        // each quadrant is drawn as the top-left one, mirrored into place
        int qx = w / 2, qy = h / 2;
        float big = w + h;
        quadrant(c, lin, R, qx, qy, big);
        c.save();
        c.translate(w, 0);
        c.scale(-1, 1);
        quadrant(c, lin, R, w - qx, qy, big);
        c.restore();
        c.save();
        c.translate(0, h);
        c.scale(1, -1);
        quadrant(c, lin, R, qx, h - qy, big);
        c.restore();
        c.save();
        c.translate(w, h);
        c.scale(-1, -1);
        quadrant(c, lin, R, w - qx, h - qy, big);
        c.restore();
        paint.setShader(null);

        if (message != null)
            c.drawText(message, w / 2f, h / 2f, text);
    }

    /**
     * Top-left quadrant [0,qw]x[0,qh]. Drawn in a canvas rotated 45 degrees, where s runs along the
     * diagonal and q = (y - x) / sqrt2 across it, so every region is an axis-aligned rect.
     * Pseudo-distance d: top edge d = y, left edge d = x, corner band (|x - y| < R)
     * d = (x + y) / 2 + f(v), f(v) = sqrt(R^2/2 - v^2) - R. Every contour d = const is then a
     * rounded rect inset by d with the same corner radius R.
     */
    private void quadrant(Canvas c, LinearGradient lin, float R, float qw, float qh, float big) {
        final float k = 1f / SQRT2, ov = 2f * k, qb = R * k;
        c.save();
        c.clipRect(0, 0, qw, qh);
        c.rotate(45);
        // top edge: q <= -qb (slight overlap into the band to avoid seams)
        setMatrix(k, k, -k, k);
        lin.setLocalMatrix(matrix);
        c.drawRect(-big, -big, big, -qb + ov, paint);
        // left edge: q >= qb
        setMatrix(k, k, k, -k);
        lin.setLocalMatrix(matrix);
        c.drawRect(-big, qb - ov, big, big, paint);
        // corner band: gradient along s, shifted by f per strip
        setMatrix(0, SQRT2, 1, 0);
        lin.setLocalMatrix(matrix);
        for (int i = 0; i < stripF.length; i++) {
            c.save();
            c.translate(-SQRT2 * stripF[i], 0);
            c.drawRect(-big, stripQ[i], big, stripQ[i + 1], paint);
            c.drawRect(-big, -stripQ[i + 1], big, -stripQ[i], paint);
            c.restore();
        }
        c.restore();
    }

    /** gradient space (x_g, y_g) -> local (a*x_g + b*y_g, c*x_g + d*y_g) */
    private void setMatrix(float a, float b, float cc, float d) {
        mv[0] = a; mv[1] = b; mv[2] = 0;
        mv[3] = cc; mv[4] = d; mv[5] = 0;
        mv[6] = 0; mv[7] = 0; mv[8] = 1;
        matrix.setValues(mv);
    }
}
