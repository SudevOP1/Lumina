using System;
using System.Threading;

namespace Lumina
{
    /// <summary>
    /// Audio input = WASAPI loopback of the selected app (or the whole system), into a ring buffer.
    /// Every 50 ms the latest 1024 samples go to <see cref="KickDetector"/>: the same ~20 snapshots/s
    /// of 1024 samples the Android Visualizer delivers, so the mobile tuning still holds.
    ///
    /// Watchdog (every 2 s): attach when the selected app starts, reattach when it exits / the
    /// capture fails / the default output device changes (system audio).
    /// </summary>
    public class AudioAnalyzer
    {
        private const int SNAP = 1024;
        private const int RING = 16384; // power of two

        public volatile float Bass; // 0..1, for border thickness
        public volatile int BeatCount, Restarts;
        public long LastDataMs, LastFrameMs; // read with Interlocked/Volatile semantics via properties
        public volatile string Error;
        /// <summary>human-readable capture state for the status line</summary>
        public volatile string State = "stopped";

        private readonly KickDetector kick = new KickDetector();
        private readonly float[] ring = new float[RING];
        private long written; // total samples ever written (guarded by ring)
        private volatile int sampleRate = 48000;

        private readonly object gate = new object();
        private volatile string source = ""; // exe name, "" = system audio
        private volatile bool sourceChanged;
        private volatile int sensitivity = 50;
        private volatile bool sensChanged = true;

        private Thread thread;
        private volatile bool stop;

        private volatile LoopbackCapture capture;
        private IntPtr attachedProcess = IntPtr.Zero;
        private string attachedDevice;

        public string Source { get { return source; } }

        /// <summary>true while a capture stream is open</summary>
        public bool Capturing { get { var c = capture; return c != null && c.Running; } }

        /// <summary>last capture error (also while it is being retried)</summary>
        public string CaptureError
        {
            get
            {
                var c = capture;
                if (c != null && c.Error != null)
                    return c.Error;
                return Error;
            }
        }

        public long DataMs { get { return Interlocked.Read(ref LastDataMs); } }
        public long FrameMs { get { return Interlocked.Read(ref LastFrameMs); } }

        public void Configure(int sens)
        {
            sensitivity = Math.Max(0, Math.Min(100, sens));
            sensChanged = true;
        }

        /// <param name="exe">e.g. "Spotify.exe", or "" / null for all system audio</param>
        public void SetSource(string exe)
        {
            exe = exe ?? "";
            if (exe == source)
                return;
            source = exe;
            sourceChanged = true;
        }

        public void Start()
        {
            lock (gate)
            {
                if (thread != null)
                    return;
                stop = false;
                sourceChanged = true;
                thread = new Thread(Run);
                thread.IsBackground = true;
                thread.Name = "Lumina-analysis";
                thread.SetApartmentState(ApartmentState.MTA);
                thread.Start();
            }
        }

        public void Stop()
        {
            Thread t;
            lock (gate)
            {
                t = thread;
                thread = null;
                stop = true;
            }
            if (t != null)
                t.Join(3000);
            Bass = 0;
            State = "stopped";
        }

        private void OnSamples(float[] mono, int count, int rate)
        {
            sampleRate = rate;
            lock (ring)
            {
                int start = (int)(written & (RING - 1));
                int first = Math.Min(count, RING - start);
                Array.Copy(mono, 0, ring, start, first);
                if (count > first)
                    Array.Copy(mono, first, ring, 0, Math.Min(count - first, RING));
                written += count;
            }
        }

        private void Run()
        {
            float[] snap = new float[SNAP];
            long lastWritten = 0;
            long nextTick = Clock.Ms;
            long lastWatch = 0;
            try
            {
                while (!stop)
                {
                    long now = Clock.Ms;
                    if (sourceChanged)
                    {
                        sourceChanged = false;
                        Detach();
                        Attach();
                        lastWatch = now;
                    }
                    else if (now - lastWatch >= 2000)
                    {
                        lastWatch = now;
                        Watchdog();
                    }
                    if (sensChanged)
                    {
                        sensChanged = false;
                        kick.SetSensitivity(sensitivity);
                    }

                    // wait for the next 50 ms tick
                    nextTick += 50;
                    long wait = nextTick - Clock.Ms;
                    if (wait > 0)
                        Thread.Sleep((int)wait);
                    else if (wait < -500)
                        nextTick = Clock.Ms; // fell far behind (sleep/hibernate)
                    now = Clock.Ms;

                    if (capture == null || !capture.Running)
                    {
                        Bass = 0;
                        continue;
                    }

                    bool fresh;
                    lock (ring)
                    {
                        fresh = written != lastWritten;
                        lastWritten = written;
                        if (fresh)
                        {
                            int end = (int)(written & (RING - 1));
                            for (int i = 0; i < SNAP; i++)
                                snap[i] = ring[(end - SNAP + i + RING) & (RING - 1)];
                        }
                    }
                    if (!fresh)
                        Array.Clear(snap, 0, SNAP); // nothing arrived: silence (loopback sends no packets then)

                    Interlocked.Exchange(ref LastFrameMs, now);
                    bool beat = kick.Process(snap, sampleRate, now);
                    Bass = kick.Bass;
                    if (!kick.HasSound)
                        continue; // silence / paused
                    Interlocked.Exchange(ref LastDataMs, now);
                    if (beat)
                        BeatCount++;
                }
            }
            catch (Exception e)
            {
                Error = e.Message;
            }
            finally
            {
                Detach();
            }
        }

        private void Attach()
        {
            Error = null;
            kick.ResetOnset();
            string exe = source;
            if (exe.Length == 0)
            {
                attachedDevice = AudioSessions.DefaultDeviceId();
                capture = LoopbackCapture.ForSystem(OnSamples);
                State = "system audio";
                return;
            }
            int pid = AudioSessions.FindRootPid(exe);
            if (pid == 0)
            {
                State = "waiting for " + exe + "…";
                return;
            }
            attachedProcess = Win32.OpenProcess(Win32.SYNCHRONIZE, false, pid);
            capture = LoopbackCapture.ForProcess(pid, OnSamples);
            State = exe + " (pid " + pid + ")";
        }

        private void Detach()
        {
            if (capture != null)
            {
                capture.Dispose();
                capture = null;
            }
            if (attachedProcess != IntPtr.Zero)
            {
                Win32.CloseHandle(attachedProcess);
                attachedProcess = IntPtr.Zero;
            }
            Bass = 0;
        }

        private void Watchdog()
        {
            if (capture == null)
            {
                Attach(); // app not running yet: retry
                return;
            }
            if (capture.Failed)
            {
                Error = capture.Error;
                Detach();
                Restarts++;
                Attach();
                return;
            }
            if (attachedProcess != IntPtr.Zero && Win32.WaitForSingleObject(attachedProcess, 0) == 0)
            {
                Detach(); // the app exited
                Attach();
                return;
            }
            if (source.Length == 0)
            {
                string id = AudioSessions.DefaultDeviceId();
                if (id != null && id != attachedDevice)
                {
                    Detach(); // output device switched (headphones etc.)
                    Restarts++;
                    Attach();
                }
            }
        }
    }
}
