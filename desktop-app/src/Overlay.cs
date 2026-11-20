using System;
using System.Collections.Generic;
using System.Threading;
using System.Windows.Forms;

namespace Lumina
{
    /// <summary>
    /// Draws the border on every monitor, above all windows and the taskbar.
    /// Each monitor gets 4 click-through, topmost, layered strip windows (top / bottom / left /
    /// right) so the middle of the screen is never touched. A dedicated render thread owns the
    /// windows: update renderer → LUT per monitor → map precomputed distance fields through it →
    /// UpdateLayeredWindow → DwmFlush (vsync). ~60 fps with live audio, ~20 fps idle.
    /// </summary>
    public class Overlay
    {
        private readonly BorderRenderer renderer;
        private readonly AudioAnalyzer analyzer;
        private Thread thread;
        private volatile bool stop;
        private volatile bool enabled = true;

        public Overlay(BorderRenderer renderer, AudioAnalyzer analyzer)
        {
            this.renderer = renderer;
            this.analyzer = analyzer;
        }

        public bool Enabled
        {
            get { return enabled; }
            set { enabled = value; }
        }

        public void Start()
        {
            stop = false;
            thread = new Thread(Run);
            thread.IsBackground = true;
            thread.Name = "Lumina-render";
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
        }

        public void Stop()
        {
            stop = true;
            if (thread != null)
                thread.Join(2000);
            thread = null;
        }

        // ------------------------------------------------------------------ render thread

        private void Run()
        {
            var monitors = new List<MonitorOverlay>();
            string layout = null;
            long lastLayoutCheck = 0, lastTopmost = 0;
            bool shown = false;
            try
            {
                while (!stop)
                {
                    PumpMessages();
                    long now = Clock.Ms;

                    if (!enabled)
                    {
                        if (shown)
                        {
                            foreach (var m in monitors) m.Hide();
                            shown = false;
                        }
                        Thread.Sleep(100);
                        continue;
                    }

                    if (layout == null || now - lastLayoutCheck > 2000)
                    {
                        lastLayoutCheck = now;
                        var rects = Monitors();
                        string key = string.Join(";", rects.ConvertAll(delegate (Win32.RECT r)
                        { return r.Left + "," + r.Top + "," + r.Right + "," + r.Bottom; }).ToArray());
                        if (key != layout)
                        {
                            layout = key;
                            foreach (var m in monitors) m.Dispose();
                            monitors.Clear();
                            foreach (var r in rects)
                                monitors.Add(new MonitorOverlay(r));
                            shown = false;
                        }
                    }

                    renderer.Update(analyzer);
                    foreach (var m in monitors)
                        m.Render(renderer);
                    if (!shown)
                    {
                        foreach (var m in monitors) m.Show();
                        shown = true;
                        lastTopmost = 0;
                    }
                    if (now - lastTopmost > 1000)
                    {
                        lastTopmost = now;
                        foreach (var m in monitors) m.BringToTop();
                    }

                    if (renderer.Live)
                    {
                        if (Win32.DwmFlush() < 0)
                            Thread.Sleep(15);
                    }
                    else
                    {
                        Thread.Sleep(45);
                    }
                }
            }
            finally
            {
                foreach (var m in monitors) m.Dispose();
            }
        }

        private static void PumpMessages()
        {
            Win32.MSG msg;
            while (Win32.PeekMessage(out msg, IntPtr.Zero, 0, 0, 1))
            {
                Win32.TranslateMessage(ref msg);
                Win32.DispatchMessage(ref msg);
            }
        }

        private static List<Win32.RECT> Monitors()
        {
            var list = new List<Win32.RECT>();
            Win32.EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, delegate (IntPtr h, IntPtr dc, ref Win32.RECT r, IntPtr data)
            {
                var info = new Win32.MONITORINFO();
                info.cbSize = System.Runtime.InteropServices.Marshal.SizeOf(typeof(Win32.MONITORINFO));
                if (Win32.GetMonitorInfo(h, ref info))
                    list.Add(info.rcMonitor);
                else
                    list.Add(r);
                return true;
            }, IntPtr.Zero);
            return list;
        }

        // ------------------------------------------------------------------ one monitor

        private sealed class MonitorOverlay : IDisposable
        {
            private readonly int X, Y, W, H, Short;
            private readonly float density;
            private readonly Strip[] strips = new Strip[4];
            private uint[] lut;
            private float builtRadiusFrac = -1, builtDepthScale = -1;
            private int depth, radius;
            private float glowDepth;

            public MonitorOverlay(Win32.RECT r)
            {
                X = r.Left; Y = r.Top; W = r.Right - r.Left; H = r.Bottom - r.Top;
                Short = Math.Max(1, Math.Min(W, H));
                // phone: ~1080 px short side at density 2.75; a desktop is viewed from further
                // away, so use a slightly finer scale than a straight proportional mapping
                density = Short / 500f;
            }

            private void Build(float radiusFrac, float depthScale)
            {
                foreach (var s in strips)
                    if (s != null) s.Dispose();
                builtRadiusFrac = radiusFrac;
                builtDepthScale = depthScale;
                radius = (int)Math.Max(0, Math.Min(Math.Round(Short * radiusFrac), Short / 2 - 1));
                glowDepth = Math.Max(4f, Short * 0.2f * depthScale);
                // strips must be at least as deep as the corner radius, else part of the
                // rounded-corner glow would fall outside them
                depth = Math.Min(Short / 2, Math.Max((int)Math.Ceiling(glowDepth), radius) + 2);
                lut = new uint[depth * BorderRenderer.STEPS + 1];
                int side = Math.Max(1, H - 2 * depth);
                strips[0] = new Strip(this, 0, 0, W, depth);
                strips[1] = new Strip(this, 0, H - depth, W, depth);
                strips[2] = new Strip(this, 0, depth, depth, side);
                strips[3] = new Strip(this, W - depth, depth, depth, side);
            }

            /// <summary>distance from the rounded screen edge, in LUT steps, for monitor pixel (mx, my)</summary>
            private ushort Dist(int mx, int my)
            {
                float cx = mx + 0.5f, cy = my + 0.5f;
                float d;
                float R = radius;
                bool inCornerX = cx < R || cx > W - R;
                bool inCornerY = cy < R || cy > H - R;
                if (R > 0 && inCornerX && inCornerY)
                {
                    float ox = cx < R ? R : W - R;
                    float oy = cy < R ? R : H - R;
                    float dx = cx - ox, dy = cy - oy;
                    d = R - (float)Math.Sqrt(dx * dx + dy * dy);
                }
                else
                {
                    d = Math.Min(Math.Min(cx, cy), Math.Min(W - cx, H - cy)) - 0.5f;
                }
                if (d < 0) d = 0;
                int idx = (int)(d * BorderRenderer.STEPS + 0.5f);
                return (ushort)Math.Min(lut.Length - 1, idx);
            }

            public void Render(BorderRenderer renderer)
            {
                float rf = renderer.RadiusFrac, ds = renderer.DepthScale;
                if (rf != builtRadiusFrac || ds != builtDepthScale)
                {
                    bool wasVisible = strips[0] != null && strips[0].Visible;
                    Build(rf, ds);
                    renderer.BuildLut(lut, density * ds, Short, glowDepth, ds);
                    foreach (var s in strips) s.Draw(lut);
                    if (wasVisible)
                        foreach (var s in strips) s.Show();
                    return;
                }
                renderer.BuildLut(lut, density * ds, Short, glowDepth, ds);
                foreach (var s in strips)
                    s.Draw(lut);
            }

            public void Show() { foreach (var s in strips) if (s != null) s.Show(); }
            public void Hide() { foreach (var s in strips) if (s != null) s.Hide(); }
            public void BringToTop() { foreach (var s in strips) if (s != null) s.BringToTop(); }

            public void Dispose()
            {
                foreach (var s in strips)
                    if (s != null) s.Dispose();
            }

            // -------------------------------------------------------------- one strip window

            private sealed class StripWindow : NativeWindow
            {
                protected override void WndProc(ref Message m)
                {
                    if (m.Msg == Win32.WM_NCHITTEST)
                    {
                        m.Result = new IntPtr(Win32.HTTRANSPARENT);
                        return;
                    }
                    base.WndProc(ref m);
                }
            }

            private sealed class Strip : IDisposable
            {
                private readonly StripWindow window = new StripWindow();
                private readonly int sx, sy, w, h; // screen position / size
                private readonly ushort[] dist;
                private readonly IntPtr memDC, bitmap, oldBitmap, bits;
                public bool Visible;

                public Strip(MonitorOverlay mon, int lx, int ly, int w, int h)
                {
                    this.w = w;
                    this.h = h;
                    sx = mon.X + lx;
                    sy = mon.Y + ly;

                    dist = new ushort[w * h];
                    for (int y = 0; y < h; y++)
                        for (int x = 0; x < w; x++)
                            dist[y * w + x] = mon.Dist(lx + x, ly + y);

                    var cp = new CreateParams();
                    cp.Caption = "Lumina border";
                    cp.Style = Win32.WS_POPUP;
                    cp.ExStyle = Win32.WS_EX_LAYERED | Win32.WS_EX_TRANSPARENT | Win32.WS_EX_TOOLWINDOW
                               | Win32.WS_EX_TOPMOST | Win32.WS_EX_NOACTIVATE;
                    cp.X = sx; cp.Y = sy; cp.Width = w; cp.Height = h;
                    window.CreateHandle(cp);

                    var bmi = new Win32.BITMAPINFOHEADER();
                    bmi.biSize = System.Runtime.InteropServices.Marshal.SizeOf(typeof(Win32.BITMAPINFOHEADER));
                    bmi.biWidth = w;
                    bmi.biHeight = -h; // top-down
                    bmi.biPlanes = 1;
                    bmi.biBitCount = 32;
                    memDC = Win32.CreateCompatibleDC(IntPtr.Zero);
                    IntPtr b;
                    bitmap = Win32.CreateDIBSection(memDC, ref bmi, 0, out b, IntPtr.Zero, 0);
                    bits = b;
                    oldBitmap = Win32.SelectObject(memDC, bitmap);
                }

                public unsafe void Draw(uint[] lut)
                {
                    if (bits == IntPtr.Zero)
                        return;
                    int n = w * h;
                    uint* p = (uint*)bits;
                    fixed (ushort* dp = dist)
                    fixed (uint* lp = lut)
                    {
                        for (int i = 0; i < n; i++)
                            p[i] = lp[dp[i]];
                    }
                    var dst = new Win32.POINT(sx, sy);
                    var size = new Win32.SIZE(w, h);
                    var src = new Win32.POINT(0, 0);
                    var blend = new Win32.BLENDFUNCTION();
                    blend.BlendOp = 0;            // AC_SRC_OVER
                    blend.SourceConstantAlpha = 255;
                    blend.AlphaFormat = 1;        // AC_SRC_ALPHA (premultiplied)
                    Win32.UpdateLayeredWindow(window.Handle, IntPtr.Zero, ref dst, ref size, memDC, ref src, 0, ref blend, Win32.ULW_ALPHA);
                }

                public void Show()
                {
                    Win32.ShowWindow(window.Handle, Win32.SW_SHOWNOACTIVATE);
                    Visible = true;
                }

                public void Hide()
                {
                    Win32.ShowWindow(window.Handle, Win32.SW_HIDE);
                    Visible = false;
                }

                public void BringToTop()
                {
                    Win32.SetWindowPos(window.Handle, Win32.HWND_TOPMOST, 0, 0, 0, 0,
                        Win32.SWP_NOMOVE | Win32.SWP_NOSIZE | Win32.SWP_NOACTIVATE | Win32.SWP_NOOWNERZORDER);
                }

                public void Dispose()
                {
                    if (window.Handle != IntPtr.Zero)
                        window.DestroyHandle();
                    if (memDC != IntPtr.Zero)
                    {
                        Win32.SelectObject(memDC, oldBitmap);
                        Win32.DeleteObject(bitmap);
                        Win32.DeleteDC(memDC);
                    }
                }
            }
        }
    }
}
