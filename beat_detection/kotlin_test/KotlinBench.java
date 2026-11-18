import android.media.AudioRecord;
import com.beatblink.AudioBeatDetector;

import javax.swing.*;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;

/**
 * Desktop test bench for kotlin_test/AudioBeatDetector.kt, in the same viewer
 * as Bench. The Android AudioRecord is stubbed (kotlin_test/stubs) to
 * "record" the song file offline, with the detector's clock running on audio
 * time, so the whole song is analyzed up front and can be browsed and played.
 *
 * Start it through the launcher, from beat_detection/:
 * java TestKotlin.java (uses Bench.AUDIO_FILE)
 * java TestKotlin.java "audio/song.mp3"
 */
public class KotlinBench {
    static final int SAMPLE_RATE = 44100; // AudioBeatDetector.SAMPLE_RATE

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : Bench.AUDIO_FILE;
        File src = new File(path);
        if (!src.isFile()) {
            System.err.println("Audio file not found: " + src.getAbsolutePath());
            System.exit(1);
        }
        File wav = src.getName().toLowerCase().endsWith(".wav") ? src : Bench.toWav(src);
        Bench.load(wav);
        analyze();

        Bench.detectorName = "AudioBeatDetector.kt";
        Bench.fluxTitle = "bass energy";
        Bench.fluxName = "level";
        Bench.thresholdName = "threshold (recent avg × " + ENERGY_THRESHOLD + ")";
        Bench.tunable = false;
        SwingUtilities.invokeLater(() -> new Bench.Viewer(wav).setVisible(true));
    }

    static double ENERGY_THRESHOLD;

    /**
     * Runs the detector over the whole song, filling Bench.frames (one per
     * 2048-sample buffer: the detector's level and its energy threshold) and
     * Bench.beats.
     */
    static void analyze() throws Exception {
        // Resample the mono source to the detector's rate.
        short[] pcm = new short[(int) (Bench.duration * SAMPLE_RATE)];
        for (int i = 0; i < pcm.length; i++) {
            float s = Math.max(-1f, Math.min(1f, Bench.sampleAt((double) i / SAMPLE_RATE)));
            pcm[i] = (short) Math.round(s * 32767f);
        }
        AudioRecord.setSource(pcm, SAMPLE_RATE);

        // The detector's own tuning constants, so the threshold line matches it.
        ENERGY_THRESHOLD = constant("ENERGY_THRESHOLD").doubleValue();
        int historySize = constant("ENERGY_HISTORY_SIZE").intValue();

        AudioBeatDetector detector = new AudioBeatDetector();
        Field lastBeat = AudioBeatDetector.class.getDeclaredField("lastBeatTime");
        lastBeat.setAccessible(true);

        Bench.frames.clear();
        Bench.beats.clear();
        ArrayDeque<Float> history = new ArrayDeque<>();
        long[] seenBeat = { 0 };
        CountDownLatch done = new CountDownLatch(1);

        AudioRecord.setHooks(() -> {
            double t = AudioRecord.positionSeconds();
            if (t == 0)
                return; // nothing processed yet
            try {
                long b = lastBeat.getLong(detector);
                boolean beat = b != seenBeat[0];
                if (beat) {
                    seenBeat[0] = b;
                    Bench.beats.add(b / 1000.0);
                }
                // LevelMonitor: level vs. ENERGY_THRESHOLD × mean of the last N levels
                float level = detector.getAudioLevel().getValue();
                history.addLast(level);
                if (history.size() > historySize)
                    history.removeFirst();
                boolean full = history.size() >= historySize;
                float avg = 0;
                for (float v : history)
                    avg += v;
                avg /= history.size();
                Bench.frames.add(new Bench.Frame(t, full, level, (float) (avg * ENERGY_THRESHOLD), beat, null));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }, done::countDown);

        detector.start();
        done.await();
        detector.stop();
        System.out.printf("%d beats in %.1f s, final bpm %d%n", Bench.beats.size(), Bench.duration,
                detector.getBpm().getValue());
    }

    static Number constant(String name) throws Exception {
        Field f = AudioBeatDetector.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Number) f.get(null);
    }
}
