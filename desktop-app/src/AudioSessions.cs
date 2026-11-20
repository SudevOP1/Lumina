using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace Lumina
{
    /// <summary>An app that has (or recently had) an audio session on some output device.</summary>
    public class AudioApp
    {
        public string Exe;      // "Spotify.exe"
        public string Name;     // "Spotify"
        public bool Playing;    // session state is Active right now

        public override string ToString()
        {
            return Name + "  (" + Exe + ")" + (Playing ? "  ♪" : "");
        }
    }

    /// <summary>
    /// Lists the apps that have audio sessions, and finds the process to capture for an exe name.
    /// Browsers and Spotify play audio from child processes, so we capture the top process with
    /// the same exe name and its whole tree (PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE).
    /// </summary>
    public static class AudioSessions
    {
        private class ProcInfo { public int Parent; public string Exe; }

        private static Dictionary<int, ProcInfo> Snapshot()
        {
            var map = new Dictionary<int, ProcInfo>();
            IntPtr snap = Win32.CreateToolhelp32Snapshot(Win32.TH32CS_SNAPPROCESS, 0);
            if (snap == IntPtr.Zero || snap == new IntPtr(-1))
                return map;
            try
            {
                var e = new Win32.PROCESSENTRY32();
                e.dwSize = Marshal.SizeOf(typeof(Win32.PROCESSENTRY32));
                if (Win32.Process32FirstW(snap, ref e))
                {
                    do
                    {
                        var p = new ProcInfo();
                        p.Parent = e.th32ParentProcessID;
                        p.Exe = e.szExeFile;
                        map[e.th32ProcessID] = p;
                    } while (Win32.Process32NextW(snap, ref e));
                }
            }
            finally
            {
                Win32.CloseHandle(snap);
            }
            return map;
        }

        /// <summary>Walk up while the parent has the same exe name.</summary>
        private static int Root(Dictionary<int, ProcInfo> map, int pid)
        {
            for (int guard = 0; guard < 64; guard++)
            {
                ProcInfo me, parent;
                if (!map.TryGetValue(pid, out me))
                    return pid;
                if (me.Parent == pid || !map.TryGetValue(me.Parent, out parent))
                    return pid;
                if (!string.Equals(parent.Exe, me.Exe, StringComparison.OrdinalIgnoreCase))
                    return pid;
                pid = me.Parent;
            }
            return pid;
        }

        /// <summary>(pid, active) for every non-system audio session on every active output device.</summary>
        private static List<KeyValuePair<int, bool>> SessionPids()
        {
            var result = new List<KeyValuePair<int, bool>>();
            IMMDeviceEnumerator en = null;
            try
            {
                en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
                IMMDeviceCollection devices;
                en.EnumAudioEndpoints(AudioConst.eRender, AudioConst.DEVICE_STATE_ACTIVE, out devices);
                int count;
                devices.GetCount(out count);
                for (int d = 0; d < count; d++)
                {
                    try
                    {
                        IMMDevice dev;
                        devices.Item(d, out dev);
                        object o;
                        Guid iid = AudioConst.IID_IAudioSessionManager2;
                        dev.Activate(ref iid, AudioConst.CLSCTX_ALL, IntPtr.Zero, out o);
                        var mgr = (IAudioSessionManager2)o;
                        IAudioSessionEnumerator sessions;
                        mgr.GetSessionEnumerator(out sessions);
                        int n;
                        sessions.GetCount(out n);
                        for (int i = 0; i < n; i++)
                        {
                            object so;
                            sessions.GetSession(i, out so);
                            var ctl = so as IAudioSessionControl2;
                            if (ctl == null)
                                continue;
                            if (ctl.IsSystemSoundsSession() == 0)
                                continue; // S_OK means it IS the system sounds session
                            int pid, state;
                            if (ctl.GetProcessId(out pid) < 0 || pid == 0)
                                continue;
                            ctl.GetState(out state);
                            result.Add(new KeyValuePair<int, bool>(pid, state == AudioConst.AudioSessionStateActive));
                            Marshal.ReleaseComObject(so);
                        }
                        Marshal.ReleaseComObject(sessions);
                        Marshal.ReleaseComObject(mgr);
                        Marshal.ReleaseComObject(dev);
                    }
                    catch (Exception) { }
                }
                Marshal.ReleaseComObject(devices);
            }
            catch (Exception) { }
            finally
            {
                if (en != null)
                    Marshal.ReleaseComObject(en);
            }
            return result;
        }

        private static string FriendlyName(int pid, string exe)
        {
            try
            {
                using (var p = Process.GetProcessById(pid))
                {
                    string desc = p.MainModule.FileVersionInfo.FileDescription;
                    if (!string.IsNullOrEmpty(desc) && desc.Trim().Length > 0)
                        return desc.Trim();
                }
            }
            catch (Exception) { }
            return exe.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? exe.Substring(0, exe.Length - 4) : exe;
        }

        /// <summary>Apps with audio sessions, playing ones first, one entry per exe.</summary>
        public static List<AudioApp> ListApps()
        {
            var map = Snapshot();
            int self = Process.GetCurrentProcess().Id;
            var byExe = new Dictionary<string, AudioApp>(StringComparer.OrdinalIgnoreCase);
            var order = new List<AudioApp>();
            foreach (var s in SessionPids())
            {
                ProcInfo info;
                if (s.Key == self || !map.TryGetValue(s.Key, out info))
                    continue;
                AudioApp app;
                if (!byExe.TryGetValue(info.Exe, out app))
                {
                    app = new AudioApp();
                    app.Exe = info.Exe;
                    app.Name = FriendlyName(Root(map, s.Key), info.Exe);
                    byExe[info.Exe] = app;
                    order.Add(app);
                }
                app.Playing |= s.Value;
            }
            order.Sort(delegate (AudioApp a, AudioApp b)
            {
                if (a.Playing != b.Playing)
                    return a.Playing ? -1 : 1;
                return string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase);
            });
            return order;
        }

        /// <summary>
        /// The process to capture for an exe name: the root of a process tree that currently has
        /// an (active, preferably) audio session, else the first root process with that name.
        /// Returns 0 if the app isn't running.
        /// </summary>
        public static int FindRootPid(string exe)
        {
            var map = Snapshot();
            int best = 0;
            bool bestActive = false;
            foreach (var s in SessionPids())
            {
                ProcInfo info;
                if (!map.TryGetValue(s.Key, out info) || !string.Equals(info.Exe, exe, StringComparison.OrdinalIgnoreCase))
                    continue;
                if (best == 0 || (s.Value && !bestActive))
                {
                    best = Root(map, s.Key);
                    bestActive = s.Value;
                }
            }
            if (best != 0)
                return best;
            foreach (var kv in map)
            {
                if (string.Equals(kv.Value.Exe, exe, StringComparison.OrdinalIgnoreCase))
                    return Root(map, kv.Key);
            }
            return 0;
        }

        /// <summary>Id of the default output device, or null.</summary>
        public static string DefaultDeviceId()
        {
            IMMDeviceEnumerator en = null;
            try
            {
                en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
                IMMDevice dev;
                en.GetDefaultAudioEndpoint(AudioConst.eRender, AudioConst.eMultimedia, out dev);
                string id;
                dev.GetId(out id);
                Marshal.ReleaseComObject(dev);
                return id;
            }
            catch (Exception)
            {
                return null;
            }
            finally
            {
                if (en != null)
                    Marshal.ReleaseComObject(en);
            }
        }
    }
}
