package xyz.sudev.lumina;

/**
 * Kick-drum detector for Android Visualizer waveform snapshots (8-bit unsigned PCM, ~20/s).
 *
 * Pure Java (no Android imports, Java 8 syntax, no lambdas) so the exact same file runs in the
 * app (fed by AudioAnalyzer) and on the desktop (fed by beat_detection/Run.java).
 *
 * Per snapshot:
 *  - Hann window + DFT of only the bins we need (30–100 Hz sub-bass, 150–700 Hz vocal band).
 *  - bass level = sub-bass vs a loudness memory that halves every ~30 s.
 *  - beat when the sub-bass rise (flux) > mean + k·std of recent flux, the level is above a
 *    gate, sub-bass outweighs the vocal band (so singing can't fire it), refractory 180 ms.
 */
public class KickDetector {
    // ---- outputs, valid after each process() call ----
    /** false if the snapshot was silence (peak < SILENCE); nothing else is updated then */
    public boolean hasSound;
    /** 0..1, for border thickness */
    public float bass;
    /** debug values of the last snapshot */
    public float sub, vox, low, flux, threshold, level, gate, slowPeak;

    private static final float SILENCE = 0.02f;
    private static final int HIST = 32;
    private static final long REFRACTORY_MS = 180; // allows kick patterns 200 ms apart

    private int sensitivity = 50;

    private float[] pcm;
    private float dc; // snapshot mean, removed before the DFT
    private int winN = -1;
    private float[] window, cosT, sinT;

    private final float[] fluxHist = new float[HIST];
    private int histIdx, histN;
    private float prevSub = -1f;
    private long lastProcMs, lastBeatMs = Long.MIN_VALUE / 2;

    public void setSensitivity(int sens) { sensitivity = Math.max(0, Math.min(100, sens)); }

    /** Forget the previous snapshot (call after the audio source restarts). */
    public void resetOnset() { prevSub = -1f; }

    /** Last snapshot converted to -1..1 floats (shared buffer; copy if you keep it). */
    public float[] pcm() { return pcm; }

    /**
     * @param wave                 Visualizer waveform bytes (unsigned 8-bit, 128 = zero)
     * @param samplingRateMilliHz  as given by Visualizer (e.g. 48000000), 0 → 44.1 kHz
     * @param nowMs                monotonic time of the snapshot
     * @param detectBeats          false = only update bass/spectrum (other detector in use)
     * @return true if this snapshot is a kick
     */
    public boolean process(byte[] wave, int samplingRateMilliHz, long nowMs, boolean detectBeats) {
        float dt = lastProcMs == 0 ? 0.05f : Math.max(0.01f, Math.min(0.25f, (nowMs - lastProcMs) / 1000f));
        lastProcMs = nowMs;
        float sr = samplingRateMilliHz > 0 ? samplingRateMilliHz / 1000f : 44100f;

        int n = wave.length;
        if (pcm == null || pcm.length != n) pcm = new float[n];
        float peak = 0;
        for (int i = 0; i < n; i++) {
            float v = ((wave[i] & 0xFF) - 128) / 128f;
            pcm[i] = v;
            peak = Math.max(peak, Math.abs(v));
        }
        if (peak < SILENCE) {                     // silence / paused
            hasSound = false;
            bass = 0f;
            prevSub = -1f;
            return false;
        }
        hasSound = true;

        // remove the snapshot's mean: Hann leaks DC straight into the 47 Hz bin, and a
        // sub-40 Hz drone (cycle longer than the 21 ms snapshot) shows up as a DC offset
        // that changes every snapshot -> fake sub-bass rises on a steady bass note
        float sum = 0;
        for (int i = 0; i < n; i++)
            sum += pcm[i];
        dc = sum / n;
        prepare(n);
        float binHz = sr / n;
        sub = bandAvg(binHz, 30f, 100f);
        vox = bandAvg(binHz, 150f, 700f);
        // bins just above sub-bass (~140-190 Hz at 1024/48k). A 1024-sample Hann
        // window smears a ~130 Hz vocal fundamental into the 47/94 Hz bins, so a
        // vocal note looks like a sub-bass rise; a real kick peaks below 100 Hz.
        low = bandAvg(binHz, 145f, 230f);

        slowPeak = Math.max(sub, slowPeak * (float) Math.exp(-dt * 0.0231f));   // halves every ~30 s
        level = slowPeak > 1e-6f ? sub / slowPeak : 0f;
        bass = clamp((level - 0.3f) / 0.7f);

        return detectBeats && detect(nowMs);
    }

    private boolean detect(long nowMs) {
        flux = prevSub < 0 ? 0f : Math.max(0f, sub - prevSub);
        prevSub = sub;

        float mean = 0, var = 0;
        for (int i = 0; i < histN; i++) mean += fluxHist[i];
        if (histN > 0) mean /= histN;
        for (int i = 0; i < histN; i++) { float d = fluxHist[i] - mean; var += d * d; }
        float std = histN > 1 ? (float) Math.sqrt(var / histN) : 0f;

        float k = 2.2f - 0.012f * sensitivity;          // 50 → 1.6
        gate = 0.65f - 0.003f * sensitivity;            // 50 → 0.5
        // clean bass rise (sub-bass far above the 140-190 Hz and vocal bands): a
        // smaller jump is enough, since nothing but a kick/808 looks like that
        boolean pureBass = sub > 6f * low && sub > 4f * vox;
        threshold = Math.max(mean + k * std, 0.18f * slowPeak) * (pureBass ? 0.6f : 1f);
        boolean beat = histN >= 8
                && flux > threshold
                && level > gate
                && sub > 1.2f * vox                      // kick-heavy, not a voice
                && sub > low                             // energy peaks below 100 Hz, not vocal leakage
                && nowMs - lastBeatMs > REFRACTORY_MS;
        if (beat) lastBeatMs = nowMs;

        // clip outliers: one huge kick must not inflate mean+k*std and hide the
        // quieter kicks that follow it for the next ~1.6 s
        fluxHist[histIdx] = histN >= 8 ? Math.min(flux, 1.2f * threshold) : flux;
        histIdx = (histIdx + 1) % HIST;
        if (histN < HIST) histN++;
        return beat;
    }

    /** Hann window (same formula as TarsosDSP HannWindow) and DFT twiddle tables for size n. */
    private void prepare(int n) {
        if (winN == n) return;
        window = new float[n];
        cosT = new float[n];
        sinT = new float[n];
        for (int i = 0; i < n; i++) {
            window[i] = (float) (0.5 * (1 - Math.cos(2 * Math.PI * i / (n - 1))));
            cosT[i] = (float) Math.cos(2 * Math.PI * i / n);
            sinT[i] = (float) Math.sin(2 * Math.PI * i / n);
        }
        winN = n;
    }

    /** |DFT| of the windowed snapshot at bin k (same magnitude as TarsosDSP FFT.modulus). */
    private float binMag(int k) {
        int n = winN;
        float re = 0, im = 0;
        int idx = 0;
        for (int i = 0; i < n; i++) {
            float x = (pcm[i] - dc) * window[i];
            re += x * cosT[idx];
            im -= x * sinT[idx];
            idx += k;
            if (idx >= n) idx -= n;
        }
        return (float) Math.sqrt(re * re + im * im);
    }

    private float bandAvg(float binHz, float lo, float hi) {
        int bins = winN / 2;
        int a = Math.max(1, (int) (lo / binHz));
        int b = Math.min(bins - 1, Math.max(a, (int) (hi / binHz)));
        float s = 0;
        for (int k = a; k <= b; k++) s += binMag(k);
        return s / (b - a + 1);
    }

    private static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
