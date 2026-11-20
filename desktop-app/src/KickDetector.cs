using System;

namespace Lumina
{
    /// <summary>
    /// Kick-drum detector. Port of mobile-app/src/xyz/sudev/lumina/KickDetector.java with the
    /// same constants and logic. The only change is the input: float PCM (-1..1) snapshots instead
    /// of Android Visualizer unsigned 8-bit bytes. AudioAnalyzer feeds it 1024-sample snapshots
    /// ~20 times a second, matching the Android Visualizer, so the tuning carries over.
    ///
    /// Per snapshot:
    /// - Hann window + DFT of only the bins we need (30-100 Hz sub-bass, 150-700 Hz vocal band).
    /// - bass level = sub-bass vs a loudness memory that halves every ~30 s.
    /// - beat when the sub-bass rise (flux) > mean + k*std of recent flux, the level is above a
    ///   gate, sub-bass outweighs the vocal band (so singing can't fire it), refractory 180 ms.
    /// </summary>
    public class KickDetector
    {
        // ---- outputs, valid after each Process() call ----
        /// <summary>false if the snapshot was silence (peak &lt; SILENCE); nothing else is updated then</summary>
        public bool HasSound;
        /// <summary>0..1, for border thickness</summary>
        public float Bass;
        /// <summary>debug values of the last snapshot</summary>
        public float Sub, Vox, Low, Flux, Threshold, Level, Gate, SlowPeak;

        private const float SILENCE = 0.02f;
        private const int HIST = 32;
        private const long REFRACTORY_MS = 180; // allows kick patterns 200 ms apart

        private int sensitivity = 50;

        private float[] pcm;
        private float dc; // snapshot mean, removed before the DFT
        private int winN = -1;
        private float[] window, cosT, sinT;

        private readonly float[] fluxHist = new float[HIST];
        private int histIdx, histN;
        private float prevSub = -1f;
        private long lastProcMs, lastBeatMs = long.MinValue / 2;

        public void SetSensitivity(int sens)
        {
            sensitivity = Math.Max(0, Math.Min(100, sens));
        }

        /// <summary>Forget the previous snapshot (call after the audio source restarts).</summary>
        public void ResetOnset()
        {
            prevSub = -1f;
        }

        /// <param name="samples">mono PCM, -1..1</param>
        /// <param name="sampleRateHz">e.g. 48000, 0 → 44.1 kHz</param>
        /// <param name="nowMs">monotonic time of the snapshot</param>
        /// <returns>true if this snapshot is a kick</returns>
        public bool Process(float[] samples, int sampleRateHz, long nowMs)
        {
            float dt = lastProcMs == 0 ? 0.05f : Math.Max(0.01f, Math.Min(0.25f, (nowMs - lastProcMs) / 1000f));
            lastProcMs = nowMs;
            float sr = sampleRateHz > 0 ? sampleRateHz : 44100f;

            int n = samples.Length;
            if (pcm == null || pcm.Length != n)
                pcm = new float[n];
            float peak = 0;
            for (int i = 0; i < n; i++)
            {
                float v = samples[i];
                if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
                pcm[i] = v;
                peak = Math.Max(peak, Math.Abs(v));
            }
            if (peak < SILENCE)
            { // silence / paused
                HasSound = false;
                Bass = 0f;
                prevSub = -1f;
                return false;
            }
            HasSound = true;

            // remove the snapshot's mean: Hann leaks DC straight into the 47 Hz bin, and a
            // sub-40 Hz drone (cycle longer than the 21 ms snapshot) shows up as a DC offset
            // that changes every snapshot -> fake sub-bass rises on a steady bass note
            float sum = 0;
            for (int i = 0; i < n; i++)
                sum += pcm[i];
            dc = sum / n;
            Prepare(n);
            float binHz = sr / n;
            Sub = BandAvg(binHz, 30f, 100f);
            Vox = BandAvg(binHz, 150f, 700f);
            // bins just above sub-bass (~140-190 Hz at 1024/48k). A 1024-sample Hann
            // window smears a ~130 Hz vocal fundamental into the 47/94 Hz bins, so a
            // vocal note looks like a sub-bass rise; a real kick peaks below 100 Hz.
            Low = BandAvg(binHz, 145f, 230f);

            SlowPeak = Math.Max(Sub, SlowPeak * (float)Math.Exp(-dt * 0.0231f)); // halves every ~30 s
            Level = SlowPeak > 1e-6f ? Sub / SlowPeak : 0f;
            Bass = Clamp((Level - 0.3f) / 0.7f);

            return Detect(nowMs);
        }

        private bool Detect(long nowMs)
        {
            Flux = prevSub < 0 ? 0f : Math.Max(0f, Sub - prevSub);
            prevSub = Sub;

            float mean = 0, var = 0;
            for (int i = 0; i < histN; i++)
                mean += fluxHist[i];
            if (histN > 0)
                mean /= histN;
            for (int i = 0; i < histN; i++)
            {
                float d = fluxHist[i] - mean;
                var += d * d;
            }
            float std = histN > 1 ? (float)Math.Sqrt(var / histN) : 0f;

            float k = 2.2f - 0.012f * sensitivity; // 50 → 1.6
            Gate = 0.65f - 0.003f * sensitivity; // 50 → 0.5
            // clean bass rise (sub-bass far above the 140-190 Hz and vocal bands): a
            // smaller jump is enough, since nothing but a kick/808 looks like that
            bool pureBass = Sub > 6f * Low && Sub > 4f * Vox;
            Threshold = Math.Max(mean + k * std, 0.18f * SlowPeak) * (pureBass ? 0.6f : 1f);
            bool beat = histN >= 8
                    && Flux > Threshold
                    && Level > Gate
                    && Sub > 1.2f * Vox // kick-heavy, not a voice
                    && Sub > Low // energy peaks below 100 Hz, not vocal leakage
                    && nowMs - lastBeatMs > REFRACTORY_MS;
            if (beat)
                lastBeatMs = nowMs;

            // clip outliers: one huge kick must not inflate mean+k*std and hide the
            // quieter kicks that follow it for the next ~1.6 s
            fluxHist[histIdx] = histN >= 8 ? Math.Min(Flux, 1.2f * Threshold) : Flux;
            histIdx = (histIdx + 1) % HIST;
            if (histN < HIST)
                histN++;
            return beat;
        }

        /// <summary>Hann window and DFT twiddle tables for size n.</summary>
        private void Prepare(int n)
        {
            if (winN == n)
                return;
            window = new float[n];
            cosT = new float[n];
            sinT = new float[n];
            for (int i = 0; i < n; i++)
            {
                window[i] = (float)(0.5 * (1 - Math.Cos(2 * Math.PI * i / (n - 1))));
                cosT[i] = (float)Math.Cos(2 * Math.PI * i / n);
                sinT[i] = (float)Math.Sin(2 * Math.PI * i / n);
            }
            winN = n;
        }

        /// <summary>|DFT| of the windowed snapshot at bin k.</summary>
        private float BinMag(int k)
        {
            int n = winN;
            float re = 0, im = 0;
            int idx = 0;
            for (int i = 0; i < n; i++)
            {
                float x = (pcm[i] - dc) * window[i];
                re += x * cosT[idx];
                im -= x * sinT[idx];
                idx += k;
                if (idx >= n)
                    idx -= n;
            }
            return (float)Math.Sqrt(re * re + im * im);
        }

        private float BandAvg(float binHz, float lo, float hi)
        {
            int bins = winN / 2;
            int a = Math.Max(1, (int)(lo / binHz));
            int b = Math.Min(bins - 1, Math.Max(a, (int)(hi / binHz)));
            float s = 0;
            for (int k = a; k <= b; k++)
                s += BinMag(k);
            return s / (b - a + 1);
        }

        private static float Clamp(float v)
        {
            return v < 0 ? 0 : (v > 1 ? 1 : v);
        }
    }
}
