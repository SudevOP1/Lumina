import javax.swing.*;
import java.io.File;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * Desktop test bench for aubio's beat tracker (aubio_tempo, the one behind
 * the aubiotrack command), in the same viewer as Bench. Calls the real
 * libaubio (0.4.6 win64 build) through the Java FFM API; nothing is ported.
 *
 * Settings match aubiotrack's defaults: "default" method (= specflux onset
 * function), 1024-sample window, 512-sample hop, threshold 0.3, silence
 * -90 dB, at the file's own sample rate.
 *
 * The flux lane shows what the beat tracker is fed: the specflux onset
 * function after aubio's peak picker smoothing (7-frame filtfilt), against
 * the picker's adaptive threshold (median + mean × threshold). aubio only
 * exposes their difference, so a parallel pvoc → specdesc → peakpicker chain
 * (also libaubio) produces it and the two parts are recomputed here; the
 * result is checked against aubio's own value.
 *
 * Start it through the launcher, from beat_detection/:
 * java TestAubio.java (uses Bench.AUDIO_FILE)
 * java TestAubio.java "audio/song.mp3"
 */
public class AubioBench {
    static final String METHOD = "default"; // aubio_tempo: "default" = specflux
    static final int BUF_SIZE = 1024, HOP_SIZE = 512;
    static final float THRESHOLD = 0.3f, SILENCE = -90f;

    public static void main(String[] args) throws Throwable {
        String path = args.length > 0 ? args[0] : Bench.AUDIO_FILE;
        File src = new File(path);
        if (!src.isFile()) {
            System.err.println("Audio file not found: " + src.getAbsolutePath());
            System.exit(1);
        }
        File wav = src.getName().toLowerCase().endsWith(".wav") ? src : Bench.toWav(src);
        Bench.load(wav);
        analyze();

        Bench.detectorName = "aubio tempo";
        Bench.fluxTitle = "specflux (peak picker)";
        Bench.fluxName = "smoothed specflux";
        Bench.thresholdName = "threshold (median + mean × " + THRESHOLD + ")";
        Bench.tunable = false;
        SwingUtilities.invokeLater(() -> new Bench.Viewer(wav).setVisible(true));
    }

    /**
     * Runs aubio_tempo over the whole song hop by hop, filling Bench.beats with
     * the beats it reports and Bench.frames with the peak picker's view.
     */
    static void analyze() throws Throwable {
        Bench.frames.clear();
        Bench.beats.clear();
        int rate = Math.round(Bench.srcRate);
        float[] mono = Bench.mono;
        int hops = (mono.length + HOP_SIZE - 1) / HOP_SIZE; // last hop zero-padded, like aubio_source

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tempo = (MemorySegment) Aubio.newTempo.invoke(arena.allocateFrom(METHOD), BUF_SIZE,
                    HOP_SIZE, rate);
            if (tempo.equals(MemorySegment.NULL))
                throw new IllegalStateException("new_aubio_tempo failed");
            Aubio.tempoSetThreshold.invoke(tempo, THRESHOLD);
            Aubio.tempoSetSilence.invoke(tempo, SILENCE);

            // parallel chain, configured like aubio_tempo's internals (see tempo.c)
            MemorySegment pv = (MemorySegment) Aubio.newPvoc.invoke(BUF_SIZE, HOP_SIZE);
            MemorySegment od = (MemorySegment) Aubio.newSpecdesc.invoke(arena.allocateFrom("specflux"), BUF_SIZE);
            MemorySegment pp = (MemorySegment) Aubio.newPeakpicker.invoke();
            Aubio.peakpickerSetThreshold.invoke(pp, THRESHOLD);

            MemorySegment in = (MemorySegment) Aubio.newFvec.invoke(HOP_SIZE);
            MemorySegment out = (MemorySegment) Aubio.newFvec.invoke(2);
            MemorySegment grain = (MemorySegment) Aubio.newCvec.invoke(BUF_SIZE);
            MemorySegment of = (MemorySegment) Aubio.newFvec.invoke(1);
            MemorySegment onset = (MemorySegment) Aubio.newFvec.invoke(1);
            MemorySegment inData = Aubio.data(in, HOP_SIZE), outData = Aubio.data(out, 2),
                    ofData = Aubio.data(of, 1);
            MemorySegment thrData = Aubio.data((MemorySegment) Aubio.peakpickerGetThresholded.invoke(pp), 1);

            Picker picker = new Picker();
            double maxDiff = 0, maxFlux = 1e-9;
            for (int h = 0; h < hops; h++) {
                for (int i = 0; i < HOP_SIZE; i++) {
                    int s = h * HOP_SIZE + i;
                    inData.setAtIndex(JAVA_FLOAT, i, s < mono.length ? mono[s] : 0f);
                }

                Aubio.tempoDo.invoke(tempo, in, out);
                boolean beat = outData.getAtIndex(JAVA_FLOAT, 0) != 0;
                if (beat)
                    Bench.beats.add((double) (float) Aubio.tempoGetLastS.invoke(tempo));

                Aubio.pvocDo.invoke(pv, in, grain);
                Aubio.specdescDo.invoke(od, grain, of);
                Aubio.peakpickerDo.invoke(pp, of, onset);
                picker.push(ofData.getAtIndex(JAVA_FLOAT, 0));
                float aubioThresholded = thrData.getAtIndex(JAVA_FLOAT, 0);
                maxDiff = Math.max(maxDiff, Math.abs(aubioThresholded - (picker.flux - picker.threshold)));
                maxFlux = Math.max(maxFlux, Math.abs(picker.flux));

                boolean sound = (int) Aubio.silenceDetection.invoke(in, SILENCE) == 0;
                // picker value h is the smoothed onset function of frame h-1, whose
                // analysis window ends at sample h × hop
                double t = (double) h * HOP_SIZE / rate;
                Bench.frames.add(new Bench.Frame(t, sound, picker.flux, picker.threshold, beat, null));
            }

            System.out.printf("%d beats in %.1f s, final bpm %.1f (confidence %.2f)%n", Bench.beats.size(),
                    Bench.duration, (float) Aubio.tempoGetBpm.invoke(tempo),
                    (float) Aubio.tempoGetConfidence.invoke(tempo));
            if (maxDiff > 1e-4 * maxFlux)
                System.err.printf("Note: flux lane differs from aubio's peak picker by up to %.3g (max %.3g).%n",
                        maxDiff, maxFlux);

            Aubio.delFvec.invoke(onset);
            Aubio.delFvec.invoke(of);
            Aubio.delCvec.invoke(grain);
            Aubio.delFvec.invoke(out);
            Aubio.delFvec.invoke(in);
            Aubio.delPeakpicker.invoke(pp);
            Aubio.delSpecdesc.invoke(od);
            Aubio.delPvoc.invoke(pv);
            Aubio.delTempo.invoke(tempo);
            Aubio.cleanup.invoke();
        }
    }

    /**
     * aubio_peakpicker_do's threshold maths (onset/peakpicker.c), keeping the two
     * parts aubio subtracts: the filtfilt-smoothed onset value and the adaptive
     * threshold. Same float/double precision as the C code.
     */
    static class Picker {
        static final int WIN_PRE = 1, WIN_POST = 5, N = WIN_PRE + WIN_POST + 1;
        // biquad from new_aubio_peakpicker (as shipped in 0.4.6)
        static final double B0 = 0.15998789, B1 = 0.31997577, B2 = 0.15998789, A1 = 0.23484048, A2 = 0;
        final float[] keep = new float[N];
        float flux, threshold;

        void push(float v) {
            System.arraycopy(keep, 1, keep, 0, N - 1);
            keep[N - 1] = v;
            float[] proc = keep.clone();
            filter(proc);
            float[] tmp = new float[N];
            for (int j = 0; j < N; j++)
                tmp[N - j - 1] = proc[j];
            filter(tmp);
            for (int j = 0; j < N; j++)
                proc[j] = tmp[N - j - 1];

            float mean = 0;
            for (float p : proc)
                mean += p;
            mean /= N;
            float[] sorted = proc.clone();
            java.util.Arrays.sort(sorted);
            float median = sorted[(N - 1) / 2];

            flux = proc[WIN_POST];
            threshold = median + mean * THRESHOLD;
        }

        /** aubio_filter_do from a reset state (filtfilt resets after each pass). */
        static void filter(float[] x) {
            double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
            for (int j = 0; j < x.length; j++) {
                double x0 = x[j];
                double y0 = B0 * x0 + B1 * x1 - A1 * y1 + B2 * x2 - A2 * y2;
                x[j] = (float) y0;
                x2 = x1;
                x1 = x0;
                y2 = y1;
                y1 = y0;
            }
        }
    }

    /** libaubio bindings (uint_t = 32-bit, smpl_t = float). */
    static class Aubio {
        static final Linker LINKER = Linker.nativeLinker();
        static final SymbolLookup LIB = SymbolLookup.libraryLookup(
                new File(System.getProperty("aubio.lib", "lib/aubio/libaubio-5.dll")).toPath(), Arena.global());

        static MethodHandle fn(String name, MemoryLayout ret, MemoryLayout... args) {
            MemorySegment sym = LIB.find(name).orElseThrow(() -> new UnsatisfiedLinkError("libaubio: no " + name));
            return LINKER.downcallHandle(sym, ret == null ? FunctionDescriptor.ofVoid(args)
                    : FunctionDescriptor.of(ret, args));
        }

        static final MethodHandle newFvec = fn("new_fvec", ADDRESS, JAVA_INT),
                delFvec = fn("del_fvec", null, ADDRESS),
                newCvec = fn("new_cvec", ADDRESS, JAVA_INT),
                delCvec = fn("del_cvec", null, ADDRESS),
                newTempo = fn("new_aubio_tempo", ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT),
                tempoDo = fn("aubio_tempo_do", null, ADDRESS, ADDRESS, ADDRESS),
                tempoGetLastS = fn("aubio_tempo_get_last_s", JAVA_FLOAT, ADDRESS),
                tempoGetBpm = fn("aubio_tempo_get_bpm", JAVA_FLOAT, ADDRESS),
                tempoGetConfidence = fn("aubio_tempo_get_confidence", JAVA_FLOAT, ADDRESS),
                tempoSetThreshold = fn("aubio_tempo_set_threshold", JAVA_INT, ADDRESS, JAVA_FLOAT),
                tempoSetSilence = fn("aubio_tempo_set_silence", JAVA_INT, ADDRESS, JAVA_FLOAT),
                delTempo = fn("del_aubio_tempo", null, ADDRESS),
                newPvoc = fn("new_aubio_pvoc", ADDRESS, JAVA_INT, JAVA_INT),
                pvocDo = fn("aubio_pvoc_do", null, ADDRESS, ADDRESS, ADDRESS),
                delPvoc = fn("del_aubio_pvoc", null, ADDRESS),
                newSpecdesc = fn("new_aubio_specdesc", ADDRESS, ADDRESS, JAVA_INT),
                specdescDo = fn("aubio_specdesc_do", null, ADDRESS, ADDRESS, ADDRESS),
                delSpecdesc = fn("del_aubio_specdesc", null, ADDRESS),
                newPeakpicker = fn("new_aubio_peakpicker", ADDRESS),
                peakpickerSetThreshold = fn("aubio_peakpicker_set_threshold", JAVA_INT, ADDRESS, JAVA_FLOAT),
                peakpickerDo = fn("aubio_peakpicker_do", null, ADDRESS, ADDRESS, ADDRESS),
                peakpickerGetThresholded = fn("aubio_peakpicker_get_thresholded_input", ADDRESS, ADDRESS),
                delPeakpicker = fn("del_aubio_peakpicker", null, ADDRESS),
                silenceDetection = fn("aubio_silence_detection", JAVA_INT, ADDRESS, JAVA_FLOAT),
                cleanup = fn("aubio_cleanup", null);

        /** fvec_t { uint_t length; smpl_t *data; } → its data, as n floats. */
        static MemorySegment data(MemorySegment fvec, int n) {
            return fvec.reinterpret(16).get(ADDRESS, 8).reinterpret(n * 4L);
        }
    }
}
