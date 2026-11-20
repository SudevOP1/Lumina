using System;
using System.Runtime.InteropServices;
using System.Threading;

namespace Lumina
{
    /// <summary>
    /// WASAPI loopback capture on its own thread, delivering mono float samples.
    /// - ForProcess(pid): captures only that process tree (Windows 10 2004+), via the virtual
    ///   "VAD\Process_Loopback" device.
    /// - ForSystem(): captures everything on the default output device.
    /// </summary>
    public sealed class LoopbackCapture : IDisposable
    {
        /// <summary>(mono samples, count, sample rate)</summary>
        public delegate void SamplesHandler(float[] mono, int count, int sampleRate);

        public volatile bool Running;
        public volatile bool Failed;
        public volatile string Error;
        public readonly int Pid; // 0 = system audio

        private readonly SamplesHandler handler;
        private readonly Thread thread;
        private volatile bool stop;

        public static LoopbackCapture ForProcess(int pid, SamplesHandler handler)
        {
            return new LoopbackCapture(pid, handler);
        }

        public static LoopbackCapture ForSystem(SamplesHandler handler)
        {
            return new LoopbackCapture(0, handler);
        }

        private LoopbackCapture(int pid, SamplesHandler handler)
        {
            Pid = pid;
            this.handler = handler;
            thread = new Thread(Run);
            thread.IsBackground = true;
            thread.Name = "Lumina-capture";
            thread.SetApartmentState(ApartmentState.MTA);
            thread.Start();
        }

        public void Dispose()
        {
            stop = true;
            if (!thread.Join(2000))
            {
                try { thread.Abort(); } catch (Exception) { }
            }
        }

        // ------------------------------------------------------------------ activation

        [ComVisible(true), ClassInterface(ClassInterfaceType.None)]
        private class ActivationHandler : IActivateAudioInterfaceCompletionHandler, IAgileObject
        {
            public readonly ManualResetEvent Done = new ManualResetEvent(false);
            public int Result;
            public object Interface;

            public void ActivateCompleted(IActivateAudioInterfaceAsyncOperation op)
            {
                try
                {
                    op.GetActivateResult(out Result, out Interface);
                }
                catch (Exception e)
                {
                    Result = Marshal.GetHRForException(e);
                }
                Done.Set();
            }
        }

        private static IAudioClient ActivateProcessLoopback(int pid)
        {
            // AUDIOCLIENT_ACTIVATION_PARAMS { ActivationType = PROCESS_LOOPBACK (1),
            //   ProcessLoopbackParams { TargetProcessId, ProcessLoopbackMode = INCLUDE_TARGET_PROCESS_TREE (0) } }
            IntPtr acp = Marshal.AllocHGlobal(12);
            // PROPVARIANT { vt = VT_BLOB (65), blob { cbSize, pBlobData } }  (x64: 24 bytes)
            IntPtr pv = Marshal.AllocHGlobal(24);
            try
            {
                Marshal.WriteInt32(acp, 0, 1);
                Marshal.WriteInt32(acp, 4, pid);
                Marshal.WriteInt32(acp, 8, 0);
                for (int i = 0; i < 24; i += 4)
                    Marshal.WriteInt32(pv, i, 0);
                Marshal.WriteInt16(pv, 0, 65);
                Marshal.WriteInt32(pv, 8, 12);
                Marshal.WriteIntPtr(pv, 8 + IntPtr.Size, acp);

                var h = new ActivationHandler();
                IActivateAudioInterfaceAsyncOperation op;
                Win32.ActivateAudioInterfaceAsync(AudioConst.VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK,
                    AudioConst.IID_IAudioClient, pv, h, out op);
                if (!h.Done.WaitOne(5000))
                    throw new TimeoutException("audio activation timed out");
                GC.KeepAlive(op);
                if (h.Result < 0)
                    Marshal.ThrowExceptionForHR(h.Result);
                return (IAudioClient)h.Interface;
            }
            finally
            {
                Marshal.FreeHGlobal(pv);
                Marshal.FreeHGlobal(acp);
            }
        }

        private static IAudioClient ActivateDefaultDevice()
        {
            var en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
            try
            {
                IMMDevice dev;
                en.GetDefaultAudioEndpoint(AudioConst.eRender, AudioConst.eMultimedia, out dev);
                object o;
                Guid iid = AudioConst.IID_IAudioClient;
                dev.Activate(ref iid, AudioConst.CLSCTX_ALL, IntPtr.Zero, out o);
                Marshal.ReleaseComObject(dev);
                return (IAudioClient)o;
            }
            finally
            {
                Marshal.ReleaseComObject(en);
            }
        }

        // ------------------------------------------------------------------ capture loop

        private void Run()
        {
            IAudioClient client = null;
            IAudioCaptureClient capture = null;
            IntPtr format = IntPtr.Zero;
            bool ownFormat = false;
            AutoResetEvent ready = null;
            try
            {
                int flags = AudioConst.AUDCLNT_STREAMFLAGS_LOOPBACK;
                if (Pid != 0)
                {
                    client = ActivateProcessLoopback(Pid);
                    // process loopback has no mix format; ask for 48 kHz stereo 16-bit
                    format = Marshal.AllocHGlobal(18);
                    ownFormat = true;
                    Marshal.WriteInt16(format, 0, 1);          // WAVE_FORMAT_PCM
                    Marshal.WriteInt16(format, 2, 2);          // channels
                    Marshal.WriteInt32(format, 4, 48000);      // rate
                    Marshal.WriteInt32(format, 8, 48000 * 4);  // bytes/s
                    Marshal.WriteInt16(format, 12, 4);         // block align
                    Marshal.WriteInt16(format, 14, 16);        // bits
                    Marshal.WriteInt16(format, 16, 0);         // cbSize
                    flags |= AudioConst.AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AudioConst.AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM;
                    client.Initialize(AudioConst.AUDCLNT_SHAREMODE_SHARED, flags, 200000, 0, format, IntPtr.Zero);
                    ready = new AutoResetEvent(false);
                    client.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle());
                }
                else
                {
                    client = ActivateDefaultDevice();
                    client.GetMixFormat(out format);
                    client.Initialize(AudioConst.AUDCLNT_SHAREMODE_SHARED, flags, 1000000, 0, format, IntPtr.Zero);
                }

                int tag = Marshal.ReadInt16(format, 0) & 0xFFFF;
                int channels = Marshal.ReadInt16(format, 2);
                int rate = Marshal.ReadInt32(format, 4);
                int blockAlign = Marshal.ReadInt16(format, 12);
                int bits = Marshal.ReadInt16(format, 14);
                bool isFloat = tag == 3 || (tag == 0xFFFE && Marshal.ReadInt32(format, 24) == 3);
                if (!isFloat && bits != 16 && bits != 24 && bits != 32)
                    throw new NotSupportedException("unsupported audio format (" + bits + " bit)");

                object svc;
                Guid iid = AudioConst.IID_IAudioCaptureClient;
                client.GetService(ref iid, out svc);
                capture = (IAudioCaptureClient)svc;
                client.Start();
                Running = true;
                Error = null;

                float[] mono = new float[4096];
                while (!stop)
                {
                    if (ready != null)
                        ready.WaitOne(100);
                    else
                        Thread.Sleep(10);

                    int packet;
                    int hr = capture.GetNextPacketSize(out packet);
                    if (hr < 0)
                        Marshal.ThrowExceptionForHR(hr);
                    while (packet > 0 && !stop)
                    {
                        IntPtr data;
                        int frames, bufFlags;
                        long devPos, qpc;
                        hr = capture.GetBuffer(out data, out frames, out bufFlags, out devPos, out qpc);
                        if (hr < 0)
                            Marshal.ThrowExceptionForHR(hr);
                        if (frames > mono.Length)
                            mono = new float[frames * 2];
                        if ((bufFlags & AudioConst.AUDCLNT_BUFFERFLAGS_SILENT) != 0 || data == IntPtr.Zero)
                            Array.Clear(mono, 0, frames);
                        else
                            Downmix(data, frames, channels, blockAlign, bits, isFloat, mono);
                        capture.ReleaseBuffer(frames);
                        if (frames > 0)
                            handler(mono, frames, rate);
                        hr = capture.GetNextPacketSize(out packet);
                        if (hr < 0)
                            Marshal.ThrowExceptionForHR(hr);
                    }
                }
                client.Stop();
            }
            catch (ThreadAbortException)
            {
            }
            catch (Exception e)
            {
                Error = e.Message.Trim();
                Failed = true;
            }
            finally
            {
                Running = false;
                if (capture != null) try { Marshal.ReleaseComObject(capture); } catch (Exception) { }
                if (client != null) try { Marshal.ReleaseComObject(client); } catch (Exception) { }
                if (format != IntPtr.Zero)
                {
                    if (ownFormat) Marshal.FreeHGlobal(format);
                    else Marshal.FreeCoTaskMem(format);
                }
                if (ready != null)
                    ready.Dispose();
            }
        }

        private static unsafe void Downmix(IntPtr data, int frames, int channels, int blockAlign, int bits, bool isFloat, float[] mono)
        {
            byte* p = (byte*)data;
            float inv = 1f / channels;
            for (int f = 0; f < frames; f++)
            {
                byte* frame = p + f * blockAlign;
                float sum = 0;
                for (int c = 0; c < channels; c++)
                {
                    if (isFloat)
                        sum += ((float*)frame)[c];
                    else if (bits == 16)
                        sum += ((short*)frame)[c] / 32768f;
                    else if (bits == 32)
                        sum += ((int*)frame)[c] / 2147483648f;
                    else // 24-bit packed
                    {
                        byte* s = frame + c * 3;
                        int v = (s[0] << 8) | (s[1] << 16) | (s[2] << 24);
                        sum += v / 2147483648f;
                    }
                }
                mono[f] = sum * inv;
            }
        }
    }
}
