using System;
using System.Threading;
using System.Windows.Forms;

[assembly: System.Reflection.AssemblyTitle("Lumina")]
[assembly: System.Reflection.AssemblyDescription("Lumina - beat-reactive screen border")]
[assembly: System.Reflection.AssemblyProduct("Lumina")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]
[assembly: System.Reflection.AssemblyFileVersion("1.0.0.0")]

namespace Lumina
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            // manifest already asks for PerMonitorV2; this is a fallback
            try { Win32.SetProcessDpiAwarenessContext(new IntPtr(-4)); } catch (Exception) { }

            bool created;
            using (var mutex = new Mutex(true, "Local\\Lumina.Desktop.SingleInstance", out created))
            {
                if (!created)
                {
                    MessageBox.Show("Lumina is already running – look for it in the system tray.", "Lumina",
                        MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new TrayApp());
                GC.KeepAlive(mutex);
            }
        }
    }
}
