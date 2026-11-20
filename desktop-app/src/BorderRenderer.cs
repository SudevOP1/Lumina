using System;

namespace Lumina
{
    /// <summary>
    /// Port of mobile-app BorderRenderer: the same envelope / beat pulse / travelling rings and the
    /// same brightness profile + orange-red ramp. On the phone it paints gradients on black; here it
    /// fills a lookup table (brightness by distance from the screen edge, in 1/4 px steps) as
    /// premultiplied BGRA, which <see cref="Overlay"/> maps onto click-through layered windows.
    /// Alpha = brightest channel, so it looks exactly like the phone over black and glows over
    /// whatever is on screen.
    /// </summary>
    public class BorderRenderer
    {
        public const int DEFAULT_RADIUS = 42; // slider units: 0..100 → 0..25% of the short side
        public const int STEPS = 4;           // LUT entries per pixel of distance

        public const int DEFAULT_DEPTH = 30;  // slider units: 50 = the phone's look, lower = shallower

        private volatile float radiusFrac = DEFAULT_RADIUS / 400f;
        private volatile float depthScale = DEFAULT_DEPTH / 50f;

        /// <param name="slider">5..100 → 0.1x..2x how far the glow and rings reach into the screen</param>
        public void SetDepth(int slider)
        {
            depthScale = Math.Max(5, Math.Min(100, slider)) / 50f;
        }

        public float DepthScale { get { return depthScale; } }

        private float env, pulse, time;
        private int lastBeat = -1;
        private readonly float[] rings = new float[6];
        private int ringCount;
        private long lastTicks;
        public bool Live;

        // per-LUT params
        private float thick, glowAmt, ringTravel, density;

        /// <param name="slider">0..100, maps to 0..25% of the monitor's short side</param>
        public void SetCornerRadius(int slider)
        {
            radiusFrac = Math.Max(0, Math.Min(100, slider)) / 400f;
        }

        public float RadiusFrac { get { return radiusFrac; } }

        public void ResetClock()
        {
            lastTicks = 0;
        }

        public void Update(AudioAnalyzer a)
        {
            long now = System.Diagnostics.Stopwatch.GetTimestamp();
            float dt = lastTicks == 0 ? 1f / 60f
                : Math.Min(0.1f, (float)(now - lastTicks) / System.Diagnostics.Stopwatch.Frequency);
            lastTicks = now;
            time += dt;

            Live = a != null && a.Capturing && Clock.Ms - a.DataMs < 1500;
            float target;
            if (Live)
            {
                float b = a.Bass;
                target = b * b;
                int bc = a.BeatCount;
                if (lastBeat != -1 && bc != lastBeat)
                {
                    pulse = 1f;
                    if (ringCount < rings.Length)
                        rings[ringCount++] = 0f;
                }
                lastBeat = bc;
            }
            else
            {
                target = 0.05f + 0.04f * (float)Math.Sin(time * 1.6f); // idle breathing
                if (a != null)
                    lastBeat = a.BeatCount;
            }

            float k = target > env ? 22f : 4.5f; // quick attack, smooth release
            env += (target - env) * Math.Min(1f, dt * k);
            pulse *= (float)Math.Exp(-dt * 5f);

            int w = 0;
            for (int i = 0; i < ringCount; i++)
            {
                float p = rings[i] + dt * 1.4f;
                if (p < 1f)
                    rings[w++] = p;
            }
            ringCount = w;
        }

        /// <summary>brightness (0..~1.2) at distance d px from the edge</summary>
        private float Intensity(float d)
        {
            float c = d / (thick * 0.45f);
            float core = (float)Math.Exp(-c * c);
            float glow = glowAmt * (float)Math.Exp(-d / (thick * 1.6f));
            float r = 0f;
            for (int i = 0; i < ringCount; i++)
            {
                float p = rings[i];
                float e = 1f - (1f - p) * (1f - p);
                float center = thick * 0.4f + e * ringTravel;
                float sigma = density * (14f + 40f * p);
                float x = (d - center) / sigma;
                float fade = (1f - p);
                r += 0.55f * fade * fade * (float)Math.Exp(-x * x);
            }
            return core + glow + r;
        }

        /// <summary>orange-red ramp (hottest part towards amber/white) as premultiplied BGRA</summary>
        private static uint Color(float i)
        {
            if (i <= 0.002f)
                return 0;
            float a = Math.Min(1f, i);
            float hot = Math.Max(0f, Math.Min(1f, (i - 0.75f) / 0.45f));
            int r = Math.Min(255, (int)(255 * Math.Min(1f, a * 1.15f)));
            int g = Math.Min(255, (int)(255 * (0.24f * a + 0.45f * hot))); // #FF3D00-ish → #FFA060 at the core
            int b = Math.Min(255, (int)(255 * (0.38f * hot * hot)));
            int alpha = Math.Max(r, Math.Max(g, b));
            if (alpha == 0)
                return 0;
            return ((uint)alpha << 24) | ((uint)r << 16) | ((uint)g << 8) | (uint)b;
        }

        /// <summary>
        /// Fill lut[k] = colour at distance k/STEPS px from the edge, for one monitor.
        /// The glow fades out smoothly before <paramref name="depth"/> px (the overlay strips' depth).
        /// </summary>
        /// <param name="dens">size scale (Android density equivalent), already multiplied by the depth scale</param>
        /// <param name="depth">px where the glow has faded to nothing</param>
        /// <param name="ds">depth scale (see SetDepth)</param>
        public void BuildLut(uint[] lut, float dens, float shortSide, float depth, float ds)
        {
            density = dens;
            thick = dens * (7f + 34f * env + 12f * pulse);
            glowAmt = 0.35f + 0.45f * env + 0.3f * pulse;
            ringTravel = shortSide * 0.15f * ds;
            float fadeStart = depth * 0.7f;
            for (int k = 0; k < lut.Length; k++)
            {
                float d = (float)k / STEPS;
                float v = Intensity(d);
                if (d > fadeStart)
                {
                    float f = Math.Max(0f, (depth - d) / Math.Max(1f, depth - fadeStart));
                    v *= f * f * (3f - 2f * f);
                }
                lut[k] = Color(v);
            }
        }
    }
}
