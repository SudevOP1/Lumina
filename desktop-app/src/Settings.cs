using System;
using System.Collections.Generic;
using System.IO;
using Microsoft.Win32;

namespace Lumina
{
    /// <summary>%APPDATA%\Lumina\settings.ini (key=value). Same keys/defaults as the mobile prefs.</summary>
    public class Settings
    {
        public int Sens = 50;
        public int Radius = BorderRenderer.DEFAULT_RADIUS;
        public int Depth = BorderRenderer.DEFAULT_DEPTH;
        public string SourceExe = ""; // "" = system audio
        public bool Enabled = true;
        public bool FirstRun;

        private static string Dir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Lumina"); }
        }

        private static string FilePath { get { return Path.Combine(Dir, "settings.ini"); } }

        public static Settings Load()
        {
            var s = new Settings();
            try
            {
                if (!File.Exists(FilePath))
                {
                    s.FirstRun = true;
                    return s;
                }
                var kv = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                foreach (string line in File.ReadAllLines(FilePath))
                {
                    int eq = line.IndexOf('=');
                    if (eq > 0)
                        kv[line.Substring(0, eq).Trim()] = line.Substring(eq + 1).Trim();
                }
                string v;
                int i;
                if (kv.TryGetValue("sens", out v) && int.TryParse(v, out i)) s.Sens = Clamp(i);
                if (kv.TryGetValue("radius", out v) && int.TryParse(v, out i)) s.Radius = Clamp(i);
                if (kv.TryGetValue("depth", out v) && int.TryParse(v, out i)) s.Depth = Math.Max(5, Clamp(i));
                if (kv.TryGetValue("source", out v)) s.SourceExe = v;
                if (kv.TryGetValue("enabled", out v)) s.Enabled = v != "0";
            }
            catch (Exception) { }
            return s;
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(Dir);
                File.WriteAllLines(FilePath, new string[] {
                    "sens=" + Sens,
                    "radius=" + Radius,
                    "depth=" + Depth,
                    "source=" + SourceExe,
                    "enabled=" + (Enabled ? 1 : 0),
                });
            }
            catch (Exception) { }
        }

        private static int Clamp(int v) { return Math.Max(0, Math.Min(100, v)); }

        // ---------------------------------------------------------------- start with Windows

        private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

        public static bool Autostart
        {
            get
            {
                try
                {
                    using (var k = Registry.CurrentUser.OpenSubKey(RunKey))
                        return k != null && k.GetValue("Lumina") != null;
                }
                catch (Exception) { return false; }
            }
            set
            {
                try
                {
                    using (var k = Registry.CurrentUser.CreateSubKey(RunKey))
                    {
                        if (value)
                            k.SetValue("Lumina", "\"" + System.Windows.Forms.Application.ExecutablePath + "\"");
                        else if (k.GetValue("Lumina") != null)
                            k.DeleteValue("Lumina");
                    }
                }
                catch (Exception) { }
            }
        }
    }
}
