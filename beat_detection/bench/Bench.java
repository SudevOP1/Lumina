import xyz.sudev.lumina.KickDetector;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Desktop test bench for the beat detector. Runs the app's own KickDetector
 * (custom_test/KickDetector.java) on a WAV file, fed exactly like the
 * phone feeds it:
 * Visualizer-style snapshots, 1024 samples of 8-bit audio at the 48 kHz mixer
 * rate, every 50 ms,
 * with the audio between snapshots never seen.
 *
 * Start it through the launcher, from beat_detection/:
 * java TestCustom.java (uses AUDIO_FILE below)
 * java TestCustom.java "audio/song.mp3" (any file ffmpeg can read)
 *
 * Window: Space = play/pause · left-click = seek · right-drag = pan ·
 * wheel = zoom · ←/→ = seek ±5 s · ↑/↓ = sensitivity ±5 (see README.md)
 */
public class Bench {
    // ---------------- settings ----------------
    static final String AUDIO_FILE = "audio/test.mp3"; // any format; non-WAV is converted with ffmpeg
    static int SENSITIVITY = 0; // app slider, 0..100
    static final float VOLUME = 1.0f; // phone media volume (AS_PLAYED scaling sees it)
    static final int MIXER_RATE = 48000; // Android mixer output rate
    static final int CAPTURE_SIZE = 1024; // Visualizer capture size
    static final int SNAPSHOT_MS = 50; // Visualizer max capture rate ≈ 20/s

    // ---------------- viewer setup (KotlinBench overrides these) ----------------
    static String detectorName = "KickDetector";
    static String fluxTitle = "sub-bass flux", fluxName = "flux", thresholdName = "threshold";
    static boolean tunable = true; // ↑/↓ change SENSITIVITY and re-run analyze()

    // ---------------- analysis results ----------------
    static float[] mono; // source audio, mono -1..1
    static float srcRate;
    static double duration;
    static final String[] BAND_NAMES = { "bass", "mids", "treble" };
    static final double BASS_HZ = 250, TREBLE_HZ = 4000; // band edges
    static float[][] bandEnv; // [band][block]: peak |x| per ENV_BLOCK samples, ~0..1
    static final int ENV_BLOCK = 256;

    record Frame(double t, boolean sound, float flux, float threshold, boolean beat, String miss) {
    }

    static List<Frame> frames = new ArrayList<>();
    static List<Double> beats = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : AUDIO_FILE;
        File src = new File(path);
        if (!src.isFile()) {
            System.err.println("Audio file not found: " + src.getAbsolutePath());
            System.err.println("Put a song at beat_detection/" + AUDIO_FILE + " (or pass a path as argument).");
            System.exit(1);
        }
        File f = src.getName().toLowerCase().endsWith(".wav") ? src : toWav(src);
        load(f);
        analyze();
        SwingUtilities.invokeLater(() -> new Viewer(f).setVisible(true));
    }

    // ---------------- audio loading ----------------
    /**
     * mp3/m4a/flac/... → 16-bit WAV in out/converted/ via ffmpeg (cached by
     * modification time).
     */
    static File toWav(File src) throws Exception {
        String base = src.getName().replaceFirst("\\.[^.]+$", "");
        File dst = new File("out/converted/" + base + ".wav");
        if (dst.isFile() && dst.lastModified() >= src.lastModified())
            return dst;
        dst.getParentFile().mkdirs();
        String ffmpeg = findWingetFfmpeg();
        Process p = new ProcessBuilder(ffmpeg, "-y", "-loglevel", "error", "-i", src.getPath(),
                "-vn", "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2", dst.getPath())
                .inheritIO().start();
        if (p.waitFor() != 0 || !dst.isFile()) {
            System.err.println("ffmpeg failed. Install it with: winget install Gyan.FFmpeg  (or use a .wav)");
            System.exit(1);
        }
        return dst;
    }

    /**
     * winget's ffmpeg install dir (works in terminals opened before the install),
     * else PATH.
     */
    static String findWingetFfmpeg() {
        File pkgs = new File(System.getenv("LOCALAPPDATA") + "/Microsoft/WinGet/Packages");
        File[] dirs = pkgs.listFiles((d, name) -> name.startsWith("Gyan.FFmpeg"));
        if (dirs != null) {
            for (File d : dirs) {
                File[] builds = d.listFiles(File::isDirectory);
                if (builds == null)
                    continue;
                for (File b : builds) {
                    File exe = new File(b, "bin/ffmpeg.exe");
                    if (exe.isFile())
                        return exe.getPath();
                }
            }
        }
        return "ffmpeg";
    }

    static void load(File f) throws Exception {
        AudioInputStream in = AudioSystem.getAudioInputStream(f);
        AudioFormat src = in.getFormat();
        AudioFormat pcm16 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16,
                src.getChannels(), src.getChannels() * 2, src.getSampleRate(), false);
        if (!src.matches(pcm16))
            in = AudioSystem.getAudioInputStream(pcm16, in);
        byte[] data = in.readAllBytes();
        int ch = pcm16.getChannels();
        int n = data.length / (2 * ch);
        mono = new float[n];
        for (int i = 0; i < n; i++) {
            float s = 0;
            for (int c = 0; c < ch; c++) {
                int o = (i * ch + c) * 2;
                s += (short) ((data[o] & 0xFF) | (data[o + 1] << 8)) / 32768f;
            }
            mono[i] = s / ch;
        }
        srcRate = pcm16.getSampleRate();
        duration = n / srcRate;

        // bass / mids / treble via 4th-order (Linkwitz-Riley) crossovers
        float r = srcRate;
        float[] bass = biquad(biquad(mono, r, BASS_HZ, true), r, BASS_HZ, true);
        float[] mids = biquad(biquad(biquad(biquad(mono, r, BASS_HZ, false), r, BASS_HZ, false),
                r, TREBLE_HZ, true), r, TREBLE_HZ, true);
        float[] treble = biquad(biquad(mono, r, TREBLE_HZ, false), r, TREBLE_HZ, false);
        bandEnv = new float[][] { envelope(bass), envelope(mids), envelope(treble) };
    }

    /** RBJ-cookbook Butterworth (Q = 0.707) low- or high-pass. */
    static float[] biquad(float[] x, float rate, double f0, boolean lowPass) {
        double w = 2 * Math.PI * f0 / rate, cos = Math.cos(w), alpha = Math.sin(w) / (2 * Math.sqrt(0.5));
        double b0 = lowPass ? (1 - cos) / 2 : (1 + cos) / 2, b1 = lowPass ? 1 - cos : -(1 + cos), b2 = b0;
        double a0 = 1 + alpha, a1 = -2 * cos / a0, a2 = (1 - alpha) / a0;
        b0 /= a0;
        b1 /= a0;
        b2 /= a0;
        float[] y = new float[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x[i];
            y2 = y1;
            y1 = v;
            y[i] = (float) v;
        }
        return y;
    }

    /**
     * Peak |x| per ENV_BLOCK samples, scaled so the band's 99th-percentile block
     * is 1 (each band fills its lane regardless of its loudness).
     */
    static float[] envelope(float[] x) {
        int blocks = (x.length + ENV_BLOCK - 1) / ENV_BLOCK;
        float[] env = new float[blocks];
        for (int b = 0; b < blocks; b++) {
            float hi = 0;
            for (int i = b * ENV_BLOCK; i < Math.min(x.length, (b + 1) * ENV_BLOCK); i++)
                hi = Math.max(hi, Math.abs(x[i]));
            env[b] = hi;
        }
        float[] sorted = env.clone();
        java.util.Arrays.sort(sorted);
        float ref = blocks == 0 ? 1 : Math.max(1e-6f, sorted[(int) (0.99 * (blocks - 1))]);
        for (int b = 0; b < blocks; b++)
            env[b] = Math.min(1f, env[b] / ref);
        return env;
    }

    /**
     * Sample of the source at time t (linear interpolation = resample to the mixer
     * rate).
     */
    static float sampleAt(double t) {
        double x = t * srcRate;
        int i = (int) Math.floor(x);
        if (i < 0 || i + 1 >= mono.length)
            return 0f;
        float fr = (float) (x - i);
        return mono[i] * (1 - fr) + mono[i + 1] * fr;
    }

    // ---------------- detection ----------------
    static void analyze() {
        frames.clear();
        beats.clear();
        KickDetector kick = new KickDetector();
        kick.setSensitivity(SENSITIVITY);
        byte[] snap = new byte[CAPTURE_SIZE];
        double capDur = (double) CAPTURE_SIZE / MIXER_RATE;
        long lastBeatMs = Long.MIN_VALUE / 2;
        int seen = 0;

        for (long ms = (long) Math.ceil(capDur * 1000); ms <= duration * 1000; ms += SNAPSHOT_MS) {
            double end = ms / 1000.0;
            for (int i = 0; i < CAPTURE_SIZE; i++) {
                double t = end - capDur + (double) i / MIXER_RATE;
                // Visualizer: top 8 bits of the 16-bit mix, offset to unsigned
                int s16 = Math.round(Math.max(-1f, Math.min(1f, sampleAt(t) * VOLUME)) * 32767f);
                snap[i] = (byte) ((s16 >> 8) + 128);
            }
            boolean beat = kick.process(snap, MIXER_RATE * 1000, ms, true);

            String miss = null;
            if (kick.hasSound) {
                seen++;
                if (beat) {
                    lastBeatMs = ms;
                    beats.add(end);
                } else if (seen > 9 && kick.flux > kick.threshold) {
                    // onset was strong enough but another check vetoed it
                    if (kick.level <= kick.gate)
                        miss = "level";
                    else if (kick.sub <= 1.2f * kick.vox || kick.sub <= kick.low)
                        miss = "vocal";
                    else if (ms - lastBeatMs <= 180)
                        miss = "refractory";
                }
            } else {
                seen = 0;
            }
            frames.add(new Frame(end, kick.hasSound, kick.flux, kick.threshold, beat, miss));
        }
    }

    // ---------------- viewer ----------------
    static class Viewer extends JFrame {
        static final Color BG = new Color(0x0E0E10), GRID = new Color(0x26262B), TEXT = new Color(0xC8C8CC),
                BEAT = new Color(0xFF5A1F), FLUX = new Color(0xFF8A3D), THR = new Color(0xE8E8E8),
                MISS_LEVEL = new Color(0x3DD6F5), MISS_VOCAL = new Color(0xB48CFF), MISS_REF = new Color(0x888888);
        static final Color[] BAND_COLORS = { new Color(0xE0603A), new Color(0x58C08A), new Color(0x5E9BEA) };

        Clip clip;
        double viewStart = 0, viewLen = 10; // seconds
        double cursor = 0;
        boolean follow = true; // view tracks the playhead while playing
        float playVolume = 1.0f; // playback-only volume (slider), 0..1
        boolean dragging;
        double lastFlashBeat = -1;
        long flashAt;
        final Canvas2 canvas = new Canvas2();
        final File file;

        Viewer(File file) {
            super(detectorName + " — " + file.getName());
            this.file = file;
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(1400, 820);
            setLocationRelativeTo(null);
            add(canvas);
            viewLen = Math.min(viewLen, duration);
            try {
                clip = AudioSystem.getClip();
                clip.open(AudioSystem.getAudioInputStream(file));
            } catch (Exception e) {
                System.err.println("Playback unavailable: " + e.getMessage());
                clip = null;
            }
            add(volumeBar(), BorderLayout.SOUTH);
            applyPlaybackGain();

            canvas.setFocusable(true);
            canvas.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_SPACE -> togglePlay();
                        case KeyEvent.VK_LEFT -> seek(cursor - 5);
                        case KeyEvent.VK_RIGHT -> seek(cursor + 5);
                        case KeyEvent.VK_UP -> setSens(SENSITIVITY + 5);
                        case KeyEvent.VK_DOWN -> setSens(SENSITIVITY - 5);
                        default -> {
                        }
                    }
                }
            });
            MouseAdapter mouse = new MouseAdapter() {
                int dragX;
                double dragViewStart;

                @Override
                public void mousePressed(MouseEvent e) {
                    canvas.requestFocusInWindow();
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        if (canvas.seekable(e.getX(), e.getY()))
                            seek(viewStart + (e.getX() - Canvas2.LEFT) / canvas.pxPerSec());
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        dragX = e.getX();
                        dragViewStart = viewStart;
                        follow = false;
                        dragging = true;
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e))
                        dragging = false;
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    canvas.setCursor(Cursor.getPredefinedCursor(canvas.seekable(e.getX(), e.getY())
                            ? Cursor.HAND_CURSOR
                            : Cursor.DEFAULT_CURSOR));
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (!SwingUtilities.isRightMouseButton(e))
                        return;
                    viewStart = dragViewStart - (e.getX() - dragX) / canvas.pxPerSec();
                    clampView();
                    canvas.repaint();
                }
            };
            canvas.addMouseListener(mouse);
            canvas.addMouseMotionListener(mouse);
            canvas.addMouseWheelListener(e -> {
                double anchor = viewStart + (e.getX() - Canvas2.LEFT) / canvas.pxPerSec();
                double factor = Math.pow(1.2, e.getPreciseWheelRotation());
                double newLen = Math.max(1, Math.min(duration, viewLen * factor));
                viewStart = anchor - (anchor - viewStart) * newLen / viewLen;
                viewLen = newLen;
                clampView();
                canvas.repaint();
            });
            new Timer(16, e -> tick()).start();
        }

        void togglePlay() {
            if (clip == null)
                return;
            if (clip.isRunning())
                clip.stop();
            else {
                if (cursor >= duration - 0.05)
                    seek(0);
                clip.start();
            }
        }

        void seek(double t) {
            cursor = Math.max(0, Math.min(duration, t));
            if (clip != null)
                clip.setMicrosecondPosition((long) (cursor * 1e6));
            lastFlashBeat = cursor;
            // keep the playhead on screen
            if (cursor < viewStart || cursor > viewStart + viewLen) {
                viewStart = cursor - viewLen * 0.25;
                clampView();
            }
            follow = true;
            canvas.repaint();
        }

        void setSens(int s) {
            if (!tunable)
                return;
            SENSITIVITY = Math.max(0, Math.min(100, s));
            analyze();
            canvas.repaint();
        }

        /** Playback volume slider, 0..100 %. Only changes what you hear, not detection. */
        JPanel volumeBar() {
            JSlider slider = new JSlider(0, 100, Math.round(playVolume * 100));
            JLabel label = new JLabel();
            label.setForeground(TEXT);
            label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            label.setText(String.format("volume %3d%%", slider.getValue()));
            slider.setBackground(BG);
            slider.setFocusable(false); // keep ←/→ for seeking on the canvas
            slider.addChangeListener(e -> {
                playVolume = slider.getValue() / 100f;
                label.setText(String.format("volume %3d%%", slider.getValue()));
                applyPlaybackGain();
            });
            JPanel bar = new JPanel(new BorderLayout(10, 0));
            bar.setBackground(BG);
            bar.setBorder(BorderFactory.createEmptyBorder(4, Canvas2.LEFT, 8, Canvas2.RIGHT));
            bar.add(label, BorderLayout.WEST);
            bar.add(slider, BorderLayout.CENTER);
            return bar;
        }

        /** Playback loudness follows playVolume (linear amplitude → dB on the clip's master gain). */
        void applyPlaybackGain() {
            if (clip == null || !clip.isControlSupported(FloatControl.Type.MASTER_GAIN))
                return;
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float db = playVolume <= 0 ? gain.getMinimum() : (float) (20 * Math.log10(playVolume));
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
        }

        void clampView() {
            viewStart = Math.max(0, Math.min(duration - viewLen, viewStart));
        }

        void tick() {
            if (clip == null || !clip.isRunning())
                return;
            cursor = clip.getMicrosecondPosition() / 1e6;
            // flash on beats the playhead just passed
            for (double b : beats) {
                if (b > lastFlashBeat && b <= cursor)
                    flashAt = System.currentTimeMillis();
            }
            lastFlashBeat = cursor;
            // follow playhead, unless the view was dragged away from it
            // (resumes once the playhead comes back into view, or on seek)
            if (!follow && !dragging && cursor >= viewStart && cursor <= viewStart + viewLen * 0.75)
                follow = true;
            if (follow && (cursor < viewStart || cursor > viewStart + viewLen * 0.75)) {
                viewStart = cursor - viewLen * 0.25;
                clampView();
            }
            canvas.repaint();
        }

        class Canvas2 extends JComponent {
            static final int LEFT = 70, RIGHT = 20, TOP = 56, BOTTOM = 34;

            double pxPerSec() {
                return (getWidth() - LEFT - RIGHT) / viewLen;
            }

            /** Inside the plot (band lanes + flux lane), where a left click seeks. */
            boolean seekable(int px, int py) {
                return px >= LEFT && px <= getWidth() - RIGHT && py >= TOP && py <= getHeight() - BOTTOM;
            }

            int x(double t) {
                return (int) Math.round(LEFT + (t - viewStart) * pxPerSec());
            }

            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g.setColor(BG);
                g.fillRect(0, 0, w, h);

                int plotW = w - LEFT - RIGHT, plotH = h - TOP - BOTTOM, gap = 6, bigGap = 14;
                int bandH = (int) ((plotH - 2 * gap - bigGap) * 0.55 / 3);
                Rectangle[] bands = new Rectangle[3];
                for (int i = 0; i < 3; i++)
                    bands[i] = new Rectangle(LEFT, TOP + i * (bandH + gap), plotW, bandH);
                int fluxY = TOP + 3 * bandH + 2 * gap + bigGap;
                Rectangle flux = new Rectangle(LEFT, fluxY, plotW, TOP + plotH - fluxY);

                header(g, w);
                for (int i = 0; i < 3; i++)
                    drawBand(g, bands[i], i);
                drawFlux(g, flux);
                int bottom = flux.y + flux.height;
                drawBeats(g, bands[0].y, bottom);
                drawTimeAxis(g, bottom);

                // playhead
                int cx = x(cursor);
                if (cx >= LEFT && cx <= w - RIGHT) {
                    g.setColor(Color.WHITE);
                    g.drawLine(cx, bands[0].y - 4, cx, bottom + 4);
                }
            }

            void header(Graphics2D g, int w) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
                g.setColor(TEXT);
                g.drawString(file.getName(), LEFT, 24);
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                g.drawString((tunable ? "sensitivity " + SENSITIVITY + "   " : "")
                        + String.format("beats %d   %6.2f / %.2f s", beats.size(), cursor, duration), LEFT, 42);

                // beat flash, like the border pulse
                float a = Math.max(0, 1 - (System.currentTimeMillis() - flashAt) / 250f);
                int bx = w - RIGHT - 56, by = 10;
                g.setColor(new Color(0x2A, 0x14, 0x0C));
                g.fillRoundRect(bx, by, 56, 36, 10, 10);
                if (a > 0) {
                    g.setColor(new Color(BEAT.getRed(), BEAT.getGreen(), BEAT.getBlue(), (int) (255 * a)));
                    g.fillRoundRect(bx, by, 56, 36, 10, 10);
                }
            }

            void lane(Graphics2D g, Rectangle r, String title) {
                g.setColor(GRID);
                g.drawRect(r.x, r.y, r.width, r.height);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g.setColor(TEXT);
                Graphics2D gr = (Graphics2D) g.create();
                gr.rotate(-Math.PI / 2, 14, r.y + r.height / 2.0);
                FontMetrics fm = gr.getFontMetrics();
                gr.drawString(title, 14 - fm.stringWidth(title) / 2, r.y + r.height / 2 + 4);
                gr.dispose();
            }

            void legend(Graphics2D g, Rectangle r, Object... items) {
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                int lx = r.x + 8;
                for (int i = 0; i < items.length; i += 2) {
                    g.setColor((Color) items[i]);
                    g.fillRect(lx, r.y + 7, 10, 3);
                    g.setColor(TEXT);
                    g.drawString((String) items[i + 1], lx + 14, r.y + 13);
                    lx += 24 + g.getFontMetrics().stringWidth((String) items[i + 1]);
                }
            }

            /** One frequency band as a mirrored peak envelope. */
            void drawBand(Graphics2D g, Rectangle r, int band) {
                lane(g, r, BAND_NAMES[band]);
                float[] env = bandEnv[band];
                double mid = r.y + r.height / 2.0, amp = r.height / 2.0 - 2;
                g.setColor(BAND_COLORS[band]);
                double blocksPerSec = srcRate / ENV_BLOCK;
                for (int px = 0; px < r.width; px++) {
                    double t0 = viewStart + px / pxPerSec(), t1 = viewStart + (px + 1) / pxPerSec();
                    int b0 = (int) (t0 * blocksPerSec), b1 = Math.max(b0 + 1, (int) (t1 * blocksPerSec));
                    float hi = 0;
                    for (int b = Math.max(0, b0); b < Math.min(env.length, b1); b++)
                        hi = Math.max(hi, env[b]);
                    g.drawLine(r.x + px, (int) (mid - hi * amp), r.x + px, (int) (mid + hi * amp));
                }
            }

            void drawFlux(Graphics2D g, Rectangle r) {
                lane(g, r, fluxTitle);
                float max = 1e-6f;
                for (Frame fr : frames) {
                    if (fr.t < viewStart || fr.t > viewStart + viewLen)
                        continue;
                    max = Math.max(max, Math.max(fr.flux, fr.threshold));
                }
                final float scale = (r.height - 20) / max;
                series(g, r, FLUX, false, fr -> fr.flux * scale);
                series(g, r, THR, true, fr -> fr.threshold * scale);
                // vetoed onsets
                for (Frame fr : frames) {
                    if (fr.miss == null || fr.t < viewStart || fr.t > viewStart + viewLen)
                        continue;
                    g.setColor(switch (fr.miss) {
                        case "level" -> MISS_LEVEL;
                        case "vocal" -> MISS_VOCAL;
                        default -> MISS_REF;
                    });
                    int px = x(fr.t), py = (int) (r.y + r.height - fr.flux * scale);
                    g.drawOval(px - 4, py - 4, 8, 8);
                }
                if (tunable)
                    legend(g, r, FLUX, fluxName, THR, thresholdName, MISS_LEVEL, "vetoed: level",
                            MISS_VOCAL, "vetoed: vocal", MISS_REF, "vetoed: refractory");
                else
                    legend(g, r, FLUX, fluxName, THR, thresholdName);
            }

            interface Val {
                float of(Frame f);
            }

            void series(Graphics2D g, Rectangle r, Color c, boolean dashed, Val v) {
                Path2D p = new Path2D.Float();
                boolean open = false;
                for (Frame fr : frames) {
                    if (fr.t < viewStart - 0.1 || fr.t > viewStart + viewLen + 0.1)
                        continue;
                    if (!fr.sound) {
                        open = false;
                        continue;
                    }
                    double px = x(fr.t), py = r.y + r.height - v.of(fr);
                    if (open)
                        p.lineTo(px, py);
                    else
                        p.moveTo(px, py);
                    open = true;
                }
                g.setColor(c);
                g.setStroke(dashed
                        ? new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[] { 5, 4 },
                                0)
                        : new BasicStroke(1.6f));
                Shape old = g.getClip();
                g.clip(r);
                g.draw(p);
                g.setClip(old);
                g.setStroke(new BasicStroke());
            }

            void drawBeats(Graphics2D g, int y0, int y1) {
                g.setColor(new Color(BEAT.getRed(), BEAT.getGreen(), BEAT.getBlue(), 150));
                g.setStroke(new BasicStroke(2f));
                for (double b : beats) {
                    if (b < viewStart || b > viewStart + viewLen)
                        continue;
                    int px = x(b);
                    g.drawLine(px, y0, px, y1);
                }
                g.setStroke(new BasicStroke());
            }

            void drawTimeAxis(Graphics2D g, int y) {
                double[] steps = { 0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60 };
                double step = steps[steps.length - 1];
                for (double s : steps)
                    if (s * pxPerSec() >= 70) {
                        step = s;
                        break;
                    }
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                for (double t = Math.ceil(viewStart / step) * step; t <= viewStart + viewLen; t += step) {
                    int px = x(t);
                    g.setColor(GRID);
                    g.drawLine(px, y, px, y + 5);
                    g.setColor(TEXT);
                    String s = step < 1 ? String.format("%.2f", t) : String.format("%.0f", t);
                    g.drawString(s, px - g.getFontMetrics().stringWidth(s) / 2, y + 18);
                }
            }
        }
    }
}
