package xyz.sudev.lumina;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.io.TarsosDSPAudioFormat;
import be.tarsos.dsp.onsets.OnsetHandler;
import be.tarsos.dsp.onsets.PercussionOnsetDetector;

/**
 * Audio input = Android Visualizer PCM snapshots of the global output mix
 * (session 0), ~20/s.
 * (A continuous stream would need MediaProjection, which Android 15+ kills on
 * screen lock.)
 *
 * Two detectors (switchable in the app):
 * - KICK (default): {@link KickDetector} (pure Java, also runs on desktop via
 * beat_detection/TestCustom.java).
 * - TARSOS: TarsosDSP's PercussionOnsetDetector as-is (broadband drum-hit
 * detector).
 *
 * Watchdog: if Android says music is playing but the Visualizer went quiet
 * (happens after
 * pause/resume when audio gets re-routed), recreate it.
 */
public class AudioAnalyzer {
    public static final int MODE_KICK = 0, MODE_TARSOS = 1;

    private final AudioManager audioManager;
    private Visualizer vis;

    public volatile float bass; // 0..1, for border thickness
    public volatile int beatCount, restarts;
    public volatile long lastDataMs, lastFrameMs;
    public volatile String error;

    private int sensitivity = 50, mode = MODE_KICK;

    private final KickDetector kick = new KickDetector();

    // tarsos detector state
    private PercussionOnsetDetector detector;
    private AudioEvent event;
    private float detectorRate;
    private int detectorSize;

    private long lastBeatMs, lastSoundMs, lastRestartMs, lastWatchMs;

    public AudioAnalyzer(Context ctx) {
        audioManager = (AudioManager) ctx.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    public synchronized void configure(int sens, int mode) {
        sensitivity = Math.max(0, Math.min(100, sens));
        this.mode = mode;
        kick.setSensitivity(sensitivity);
        detector = null;
    }

    public synchronized void start() {
        if (vis != null)
            return;
        try {
            vis = new Visualizer(0);
            vis.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.min(1024, range[1]));
            // AS_PLAYED: NORMALIZED rescales each snapshot on its own, which fakes hits in
            // quiet parts
            try {
                vis.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
            } catch (Throwable ignored) {
            }
            vis.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] w, int sr) {
                    onWave(w, sr);
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] f, int sr) {
                }
            }, Visualizer.getMaxCaptureRate(), true, false);
            vis.setEnabled(true);
            error = null;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            release();
        }
        detector = null;
        kick.resetOnset();
        lastRestartMs = SystemClock.uptimeMillis();
    }

    public synchronized void stop() {
        release();
    }

    private void release() {
        if (vis != null) {
            try {
                vis.setEnabled(false);
            } catch (Throwable ignored) {
            }
            try {
                vis.release();
            } catch (Throwable ignored) {
            }
            vis = null;
        }
    }

    /**
     * Call often (every frame is fine). Recreates the Visualizer if music plays but
     * nothing arrives.
     */
    public void watchdog() {
        long now = SystemClock.uptimeMillis();
        if (now - lastWatchMs < 500)
            return;
        lastWatchMs = now;
        if (audioManager == null)
            return;
        boolean playing;
        try {
            playing = audioManager.isMusicActive();
        } catch (Throwable t) {
            return;
        }
        if (!playing)
            return;
        boolean deaf = now - lastDataMs > 1200 || now - lastFrameMs > 1200;
        if (deaf && now - lastRestartMs > 2000) {
            synchronized (this) {
                release();
            }
            start();
            restarts++;
        }
    }

    private synchronized void onWave(byte[] w, int samplingRateMilliHz) {
        long now = SystemClock.uptimeMillis();
        lastFrameMs = now;

        boolean beat = kick.process(w, samplingRateMilliHz, now, mode == MODE_KICK);
        bass = kick.bass;
        if (!kick.hasSound)
            return; // silence / paused
        lastDataMs = now;

        if (mode == MODE_TARSOS) {
            float sr = samplingRateMilliHz > 0 ? samplingRateMilliHz / 1000f : 44100f;
            runTarsos(sr, w.length, now);
        } else if (beat) {
            lastBeatMs = now;
            beatCount++;
        }
        lastSoundMs = now;
    }

    private void runTarsos(float sr, int n, long now) {
        if (detector == null || detectorRate != sr || detectorSize != n || now - lastSoundMs > 400) {
            double sens = 20 + 0.6 * sensitivity; // Tarsos default 20
            double thrDb = 9 - 0.04 * sensitivity; // Tarsos default 8 dB
            detector = new PercussionOnsetDetector(sr, n, new OnsetHandler() {
                @Override
                public void handleOnset(double time, double salience) {
                    long t = SystemClock.uptimeMillis();
                    if (t - lastBeatMs > 200) {
                        lastBeatMs = t;
                        beatCount++;
                    }
                }
            }, sens, thrDb);
            event = new AudioEvent(new TarsosDSPAudioFormat(sr, 16, 1, true, false));
            detectorRate = sr;
            detectorSize = n;
        }
        event.setFloatBuffer(kick.pcm().clone());
        detector.process(event);
    }
}
