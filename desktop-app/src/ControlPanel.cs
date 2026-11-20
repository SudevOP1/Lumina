using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace Lumina
{
    /// <summary>
    /// Tray popup with the mobile app's controls: status line, beat sensitivity, border radius,
    /// plus desktop-only: which app to listen to, enable, start with Windows, quit.
    /// Laid out in pixels scaled by the DPI of the monitor it opens on.
    /// </summary>
    public class ControlPanel : Form
    {
        private static readonly Color Bg = Color.FromArgb(24, 24, 24);
        private static readonly Color Fg = Color.White;
        private static readonly Color Dim = Color.FromArgb(170, 170, 170);
        private static readonly Color Accent = Color.FromArgb(255, 80, 20);

        private readonly TrayApp app;
        private readonly Timer statusTimer = new Timer();
        private float scale = -1;
        private Label status, sensLabel, radiusLabel, depthLabel;
        private Slider sens, radius, depth;
        private ComboBox source;
        private CheckBox enabled, autostart;
        private bool populating;

        public long HiddenAtMs;

        private class SourceItem
        {
            public string Exe, Label;
            public override string ToString() { return Label; }
        }

        public ControlPanel(TrayApp app)
        {
            this.app = app;
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            TopMost = true;
            StartPosition = FormStartPosition.Manual;
            AutoScaleMode = AutoScaleMode.None;
            BackColor = Bg;
            ForeColor = Fg;
            Text = "Lumina";
            KeyPreview = true;
            statusTimer.Interval = 250;
            statusTimer.Tick += delegate { UpdateStatus(); };
        }

        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.ExStyle |= Win32.WS_EX_TOOLWINDOW;
                cp.ClassStyle |= 0x20000; // CS_DROPSHADOW
                return cp;
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            int round = 2; // DWMWCP_ROUND (Windows 11)
            try { Win32.DwmSetWindowAttribute(Handle, 33, ref round, 4); } catch (Exception) { }
        }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Escape)
                HidePanel();
            base.OnKeyDown(e);
        }

        protected override void OnDeactivate(EventArgs e)
        {
            base.OnDeactivate(e);
            HidePanel();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            using (var pen = new Pen(Color.FromArgb(64, 64, 64)))
                e.Graphics.DrawRectangle(pen, 0, 0, Width - 1, Height - 1);
        }

        public void HidePanel()
        {
            if (!Visible)
                return;
            statusTimer.Stop();
            HiddenAtMs = Clock.Ms;
            Hide();
        }

        /// <summary>Open next to the tray, on the monitor under the cursor.</summary>
        public void ShowNear(Point cursor)
        {
            IntPtr mon = Win32.MonitorFromPoint(new Win32.POINT(cursor.X, cursor.Y), 2);
            int dpi = 96, dpiY;
            try { if (Win32.GetDpiForMonitor(mon, 0, out dpi, out dpiY) != 0) dpi = 96; } catch (Exception) { dpi = 96; }
            float s = dpi / 96f;
            if (s != scale)
                Build(s);

            Populate();
            sens.Value = app.Config.Sens;
            radius.Value = app.Config.Radius;
            depth.Value = app.Config.Depth;
            enabled.Checked = app.Config.Enabled;
            autostart.Checked = Settings.Autostart;
            UpdateLabels();
            UpdateStatus();

            Rectangle wa = Screen.FromPoint(cursor).WorkingArea;
            Rectangle sb = Screen.FromPoint(cursor).Bounds;
            int m = (int)(10 * scale);
            int x = Math.Max(wa.Left + m, Math.Min(cursor.X - Width / 2, wa.Right - Width - m));
            int y;
            if (wa.Top > sb.Top && cursor.Y < wa.Top) y = wa.Top + m;            // taskbar on top
            else if (wa.Left > sb.Left && cursor.X < wa.Left) { x = wa.Left + m; y = Math.Max(wa.Top + m, Math.Min(cursor.Y - Height / 2, wa.Bottom - Height - m)); }
            else if (wa.Right < sb.Right && cursor.X >= wa.Right) { x = wa.Right - Width - m; y = Math.Max(wa.Top + m, Math.Min(cursor.Y - Height / 2, wa.Bottom - Height - m)); }
            else y = wa.Bottom - Height - m;                                       // taskbar at bottom
            Location = new Point(x, y);

            Show();
            Activate();
            Win32.SetForegroundWindow(Handle);
            statusTimer.Start();
        }

        // ------------------------------------------------------------------ layout

        private Font F(float px, FontStyle style)
        {
            return new Font("Segoe UI", px * scale, style, GraphicsUnit.Pixel);
        }

        private int S(float v) { return (int)Math.Round(v * scale); }

        private void Build(float s)
        {
            scale = s;
            SuspendLayout();
            foreach (Control c in Controls) c.Dispose();
            Controls.Clear();

            int pad = S(20), w = S(340), inner = w - 2 * pad;
            int y = S(16);

            var title = new Label { Text = "Lumina", Font = F(26, FontStyle.Regular), ForeColor = Fg, AutoSize = false, TextAlign = ContentAlignment.MiddleCenter };
            title.SetBounds(pad, y, inner, S(36)); Controls.Add(title); y += S(36);

            var hint = new Label { Text = "desktop · play music and watch the edge", Font = F(12.5f, FontStyle.Regular), ForeColor = Dim, AutoSize = false, TextAlign = ContentAlignment.MiddleCenter };
            hint.SetBounds(pad, y, inner, S(20)); Controls.Add(hint); y += S(28);

            status = new Label { Font = new Font("Consolas", 11.5f * scale, FontStyle.Regular, GraphicsUnit.Pixel), ForeColor = Color.FromArgb(176, 255, 176), AutoSize = false, TextAlign = ContentAlignment.TopCenter, AutoEllipsis = true };
            status.SetBounds(pad, y, inner, S(36)); Controls.Add(status); y += S(46);

            sensLabel = MakeLabel(pad, ref y, inner);
            sens = new Slider(scale); sens.SetBounds(pad, y, inner, S(28)); Controls.Add(sens); y += S(36);
            sens.ValueChanged += delegate { UpdateLabels(); app.SetSensitivity(sens.Value); };

            radiusLabel = MakeLabel(pad, ref y, inner);
            radius = new Slider(scale); radius.SetBounds(pad, y, inner, S(28)); Controls.Add(radius); y += S(36);
            radius.ValueChanged += delegate { UpdateLabels(); app.SetRadius(radius.Value); };

            depthLabel = MakeLabel(pad, ref y, inner);
            depth = new Slider(scale); depth.Minimum = 5; depth.SetBounds(pad, y, inner, S(28)); Controls.Add(depth); y += S(40);
            depth.ValueChanged += delegate { UpdateLabels(); app.SetDepth(depth.Value); };

            var listenLabel = new Label { Text = "Listen to", Font = F(13, FontStyle.Regular), ForeColor = Color.FromArgb(220, 220, 220), AutoSize = false };
            listenLabel.SetBounds(pad, y, inner, S(20)); Controls.Add(listenLabel); y += S(24);

            source = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, FlatStyle = FlatStyle.Flat, BackColor = Color.FromArgb(44, 44, 44), ForeColor = Fg, Font = F(13, FontStyle.Regular), MaxDropDownItems = 12 };
            // themed DropDownList ignores BackColor; draw the items ourselves to stay dark
            source.DrawMode = DrawMode.OwnerDrawFixed;
            source.ItemHeight = S(24);
            source.DrawItem += delegate (object o, DrawItemEventArgs e)
            {
                bool edit = (e.State & DrawItemState.ComboBoxEdit) != 0;
                bool sel = (e.State & DrawItemState.Selected) != 0 && !edit;
                using (var bg = new SolidBrush(sel ? Color.FromArgb(200, 70, 20) : Color.FromArgb(44, 44, 44)))
                    e.Graphics.FillRectangle(bg, e.Bounds);
                if (e.Index < 0) return;
                var r = new Rectangle(e.Bounds.X + S(6), e.Bounds.Y, e.Bounds.Width - S(8), e.Bounds.Height);
                TextRenderer.DrawText(e.Graphics, source.Items[e.Index].ToString(), source.Font, r, Fg,
                    TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);
            };
            int refreshW = S(34);
            source.SetBounds(pad, y, inner - refreshW - S(8), S(30)); Controls.Add(source);
            source.SelectedIndexChanged += delegate
            {
                if (populating) return;
                var it = source.SelectedItem as SourceItem;
                if (it != null) app.SetSource(it.Exe);
            };
            var refresh = MakeButton("↻", new Font("Segoe UI Symbol", 16 * scale, FontStyle.Regular, GraphicsUnit.Pixel));
            refresh.SetBounds(pad + inner - refreshW, y - S(1), refreshW, source.Height + S(2));
            refresh.Click += delegate { Populate(); };
            var tip = new ToolTip(); tip.SetToolTip(refresh, "Refresh the list of apps playing audio");
            Controls.Add(refresh);
            y += source.Height + S(14);

            enabled = MakeCheck("Show border", pad, ref y, inner);
            enabled.CheckedChanged += delegate { if (!populating) app.SetEnabled(enabled.Checked); };
            autostart = MakeCheck("Start with Windows", pad, ref y, inner);
            autostart.CheckedChanged += delegate { if (!populating) Settings.Autostart = autostart.Checked; };
            y += S(6);

            var quit = MakeButton("Quit Lumina", F(13.5f, FontStyle.Regular));
            quit.SetBounds(pad, y, inner, S(40)); Controls.Add(quit); y += S(40);
            quit.Click += delegate { app.Quit(); };

            ClientSize = new Size(w, y + S(18));
            ResumeLayout(false);
        }

        private Label MakeLabel(int pad, ref int y, int inner)
        {
            var l = new Label { Font = F(13, FontStyle.Regular), ForeColor = Color.FromArgb(220, 220, 220), AutoSize = false, TextAlign = ContentAlignment.MiddleCenter };
            l.SetBounds(pad, y, inner, S(20));
            Controls.Add(l);
            y += S(22);
            return l;
        }

        private CheckBox MakeCheck(string text, int pad, ref int y, int inner)
        {
            var c = new CheckBox { Text = text, Font = F(13, FontStyle.Regular), ForeColor = Fg, FlatStyle = FlatStyle.Flat, AutoSize = false };
            c.FlatAppearance.BorderColor = Color.FromArgb(110, 110, 110);
            c.FlatAppearance.CheckedBackColor = Accent;
            c.SetBounds(pad, y, inner, S(26));
            Controls.Add(c);
            y += S(28);
            return c;
        }

        private Button MakeButton(string text, Font font)
        {
            var b = new Button { Text = text, Font = font, ForeColor = Fg, BackColor = Color.FromArgb(51, 51, 51), FlatStyle = FlatStyle.Flat, UseVisualStyleBackColor = false, TabStop = false };
            b.FlatAppearance.BorderColor = Color.FromArgb(110, 110, 110);
            b.FlatAppearance.MouseOverBackColor = Color.FromArgb(70, 70, 70);
            b.FlatAppearance.MouseDownBackColor = Color.FromArgb(90, 90, 90);
            return b;
        }

        // ------------------------------------------------------------------ state

        private void Populate()
        {
            populating = true;
            try
            {
                source.Items.Clear();
                var items = new List<SourceItem>();
                items.Add(new SourceItem { Exe = "", Label = "All system audio" });
                string cur = app.Config.SourceExe ?? "";
                bool found = cur.Length == 0;
                foreach (var a in AudioSessions.ListApps())
                {
                    items.Add(new SourceItem { Exe = a.Exe, Label = a.ToString() });
                    if (string.Equals(a.Exe, cur, StringComparison.OrdinalIgnoreCase))
                        found = true;
                }
                if (!found)
                    items.Add(new SourceItem { Exe = cur, Label = cur + "  (not playing)" });
                int sel = 0;
                for (int i = 0; i < items.Count; i++)
                {
                    source.Items.Add(items[i]);
                    if (string.Equals(items[i].Exe, cur, StringComparison.OrdinalIgnoreCase))
                        sel = i;
                }
                source.SelectedIndex = sel;
                enabled.Checked = app.Config.Enabled;
                autostart.Checked = Settings.Autostart;
            }
            finally
            {
                populating = false;
            }
        }

        private void UpdateLabels()
        {
            sensLabel.Text = "Beat sensitivity: " + sens.Value;
            radiusLabel.Text = "Border radius: " + radius.Value;
            depthLabel.Text = "Border depth: " + depth.Value;
        }

        private void UpdateStatus()
        {
            var a = app.Analyzer;
            if (!app.Config.Enabled)
            {
                status.Text = "border off";
                return;
            }
            string s = "source: " + a.State;
            if (a.Capturing)
                s += " | audio: " + (Clock.Ms - a.DataMs < 1500 ? "yes" : "silent – play music");
            string err = a.CaptureError;
            if (err != null)
                s += "\nerror: " + err;
            else
                s += string.Format("\nbass {0:0.00}  beats {1}  restarts {2}", a.Bass, a.BeatCount, a.Restarts);
            status.Text = s;
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
                statusTimer.Dispose();
            base.Dispose(disposing);
        }
    }

    /// <summary>Flat dark slider (0..100) in Lumina orange.</summary>
    public class Slider : Control
    {
        private int value;
        private readonly float scale;
        public int Minimum = 0, Maximum = 100;
        public event EventHandler ValueChanged;

        public Slider(float scale)
        {
            this.scale = scale;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint
                     | ControlStyles.ResizeRedraw | ControlStyles.Selectable, true);
            BackColor = Color.FromArgb(24, 24, 24);
            Cursor = Cursors.Hand;
        }

        public int Value
        {
            get { return value; }
            set
            {
                int v = Math.Max(Minimum, Math.Min(Maximum, value));
                if (v == this.value) return;
                this.value = v;
                Invalidate();
                if (ValueChanged != null) ValueChanged(this, EventArgs.Empty);
            }
        }

        private float Pad { get { return 9 * scale; } }

        private void SetFromX(int x)
        {
            float t = (x - Pad) / Math.Max(1f, Width - 2 * Pad);
            Value = (int)Math.Round(Math.Max(0, Math.Min(1, t)) * Maximum);
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            Focus();
            if (e.Button == MouseButtons.Left) { Capture = true; SetFromX(e.X); }
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            base.OnMouseMove(e);
            if (Capture && e.Button == MouseButtons.Left) SetFromX(e.X);
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            base.OnMouseUp(e);
            Capture = false;
        }

        protected override void OnMouseWheel(MouseEventArgs e)
        {
            base.OnMouseWheel(e);
            Value += e.Delta > 0 ? 1 : -1;
        }

        protected override bool IsInputKey(Keys k)
        {
            return k == Keys.Left || k == Keys.Right || base.IsInputKey(k);
        }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            base.OnKeyDown(e);
            if (e.KeyCode == Keys.Left || e.KeyCode == Keys.Down) Value -= 1;
            else if (e.KeyCode == Keys.Right || e.KeyCode == Keys.Up) Value += 1;
            else if (e.KeyCode == Keys.PageDown) Value -= 10;
            else if (e.KeyCode == Keys.PageUp) Value += 10;
        }

        protected override void OnGotFocus(EventArgs e) { base.OnGotFocus(e); Invalidate(); }
        protected override void OnLostFocus(EventArgs e) { base.OnLostFocus(e); Invalidate(); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            float pad = Pad, cy = Height / 2f, track = 4 * scale;
            float x0 = pad, x1 = Width - pad;
            float tx = x0 + (x1 - x0) * value / Math.Max(1, Maximum);
            using (var bg = new SolidBrush(Color.FromArgb(70, 70, 70)))
                g.FillRectangle(bg, x0, cy - track / 2, x1 - x0, track);
            using (var fill = new LinearGradientBrush(new PointF(x0 - 1, 0), new PointF(x1 + 1, 0),
                       Color.FromArgb(255, 61, 0), Color.FromArgb(255, 160, 96)))
                g.FillRectangle(fill, x0, cy - track / 2, Math.Max(0, tx - x0), track);
            float r = (Focused ? 8.5f : 7.5f) * scale;
            using (var thumb = new SolidBrush(Color.FromArgb(255, 120, 50)))
                g.FillEllipse(thumb, tx - r, cy - r, 2 * r, 2 * r);
            using (var ring = new Pen(Color.FromArgb(255, 200, 160), 1.2f * scale))
                g.DrawEllipse(ring, tx - r, cy - r, 2 * r, 2 * r);
        }
    }
}
