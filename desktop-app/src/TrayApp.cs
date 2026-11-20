using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace Lumina
{
    /// <summary>Tray icon + menu; owns settings, the analyzer, the renderer and the overlay.</summary>
    public class TrayApp : ApplicationContext
    {
        public readonly Settings Config;
        public readonly AudioAnalyzer Analyzer = new AudioAnalyzer();
        public readonly BorderRenderer Renderer = new BorderRenderer();
        private readonly Overlay overlay;
        private readonly NotifyIcon tray;
        private readonly ControlPanel panel;
        private readonly ToolStripMenuItem enabledItem;
        private readonly Timer saveTimer = new Timer();

        public TrayApp()
        {
            Config = Settings.Load();
            Analyzer.Configure(Config.Sens);
            Analyzer.SetSource(Config.SourceExe);
            Renderer.SetCornerRadius(Config.Radius);
            Renderer.SetDepth(Config.Depth);
            overlay = new Overlay(Renderer, Analyzer);
            overlay.Enabled = Config.Enabled;
            overlay.Start();
            if (Config.Enabled)
                Analyzer.Start();

            panel = new ControlPanel(this);

            var menu = new ContextMenuStrip();
            menu.Items.Add("Open controls", null, delegate { ShowPanel(); });
            enabledItem = new ToolStripMenuItem("Show border", null, delegate { SetEnabled(!Config.Enabled); });
            enabledItem.Checked = Config.Enabled;
            menu.Items.Add(enabledItem);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Quit", null, delegate { Quit(); });

            tray = new NotifyIcon();
            tray.Icon = MakeIcon(32);
            tray.Text = "Lumina";
            tray.ContextMenuStrip = menu;
            tray.MouseClick += delegate (object s, MouseEventArgs e)
            {
                if (e.Button != MouseButtons.Left)
                    return;
                if (panel.Visible)
                    panel.HidePanel();
                else if (Clock.Ms - panel.HiddenAtMs > 300) // this click is what just closed it
                    ShowPanel();
            };
            tray.Visible = true;

            // slider drags fire many changes; write the file at most every 500 ms
            saveTimer.Interval = 500;
            saveTimer.Tick += delegate { saveTimer.Stop(); Config.Save(); };

            if (Config.FirstRun)
            {
                Config.Save();
                tray.ShowBalloonTip(6000, "Lumina is running",
                    "Click the tray icon to pick the app to listen to.", ToolTipIcon.Info);
            }
        }

        private void ShowPanel()
        {
            panel.ShowNear(Cursor.Position);
        }

        private void SaveSoon()
        {
            saveTimer.Stop();
            saveTimer.Start();
        }

        public void SetSensitivity(int v)
        {
            Config.Sens = v;
            Analyzer.Configure(v);
            SaveSoon();
        }

        public void SetRadius(int v)
        {
            Config.Radius = v;
            Renderer.SetCornerRadius(v);
            SaveSoon();
        }

        public void SetDepth(int v)
        {
            Config.Depth = v;
            Renderer.SetDepth(v);
            SaveSoon();
        }

        public void SetSource(string exe)
        {
            Config.SourceExe = exe ?? "";
            Analyzer.SetSource(Config.SourceExe);
            SaveSoon();
        }

        public void SetEnabled(bool on)
        {
            if (on == Config.Enabled)
                return;
            Config.Enabled = on;
            enabledItem.Checked = on;
            overlay.Enabled = on;
            if (on)
                Analyzer.Start();
            else
                Analyzer.Stop();
            SaveSoon();
        }

        public void Quit()
        {
            saveTimer.Stop();
            Config.Save();
            panel.HidePanel();
            tray.Visible = false;
            overlay.Stop();
            Analyzer.Stop();
            tray.Dispose();
            panel.Dispose();
            ExitThread();
        }

        /// <summary>Orange glowing rounded-square ring, like the border itself.</summary>
        public static Icon MakeIcon(int size)
        {
            using (var bmp = new Bitmap(size, size, System.Drawing.Imaging.PixelFormat.Format32bppArgb))
            {
                using (var g = Graphics.FromImage(bmp))
                {
                    g.SmoothingMode = SmoothingMode.AntiAlias;
                    g.Clear(Color.Transparent);
                    float s = size / 32f;
                    var r = new RectangleF(3 * s, 3 * s, 26 * s, 26 * s);
                    using (var path = RoundRect(r, 8 * s))
                    {
                        using (var glow = new Pen(Color.FromArgb(110, 255, 61, 0), 6 * s))
                            g.DrawPath(glow, path);
                        using (var core = new Pen(Color.FromArgb(255, 255, 120, 50), 3 * s))
                            g.DrawPath(core, path);
                        using (var hot = new Pen(Color.FromArgb(255, 255, 200, 150), 1.2f * s))
                            g.DrawPath(hot, path);
                    }
                }
                IntPtr h = bmp.GetHicon();
                return (Icon)Icon.FromHandle(h).Clone();
            }
        }

        private static GraphicsPath RoundRect(RectangleF r, float rad)
        {
            var p = new GraphicsPath();
            float d = rad * 2;
            p.AddArc(r.Left, r.Top, d, d, 180, 90);
            p.AddArc(r.Right - d, r.Top, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            p.AddArc(r.Left, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }
    }
}
