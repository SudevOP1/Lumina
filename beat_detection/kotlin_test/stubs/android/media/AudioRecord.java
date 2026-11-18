package android.media;

/**
 * Desktop stand-in for android.media.AudioRecord. Instead of the microphone it
 * "records" the 16-bit mono PCM set with {@link #setSource}, as fast as the
 * reader asks for it (offline analysis).
 *
 * Because that runs faster than real time, TestKotlin compiles
 * AudioBeatDetector.kt with System.currentTimeMillis() replaced by
 * {@link #clockMillis()}: the detector's clock is the audio clock, as it
 * would be live.
 */
public class AudioRecord {
    public static final int STATE_UNINITIALIZED = 0;
    public static final int STATE_INITIALIZED = 1;
    public static final int RECORDSTATE_STOPPED = 1;
    public static final int RECORDSTATE_RECORDING = 3;
    public static final int ERROR_INVALID_OPERATION = -3;

    private static short[] source = new short[0];
    private static int sourceRate = 44100;
    private static volatile int position; // next sample to hand out
    private static Runnable onRead = () -> {
    };
    private static Runnable onEnd = () -> {
    };

    private boolean recording;

    /** Audio the "mic" will hear: 16-bit mono at the given rate. */
    public static void setSource(short[] pcm, int rate) {
        source = pcm;
        sourceRate = rate;
        position = 0;
    }

    /**
     * beforeRead runs at the start of every read, i.e. after the reader has
     * processed the previous chunk; atEnd once the source runs out.
     */
    public static void setHooks(Runnable beforeRead, Runnable atEnd) {
        onRead = beforeRead;
        onEnd = atEnd;
    }

    /** Audio time handed out so far, in ms (the detector's clock). */
    public static long clockMillis() {
        return position * 1000L / sourceRate;
    }

    public static double positionSeconds() {
        return (double) position / sourceRate;
    }

    public static int getMinBufferSize(int sampleRate, int channelConfig, int audioFormat) {
        return 4096;
    }

    public AudioRecord(int audioSource, int sampleRate, int channelConfig, int audioFormat, int bufferSizeInBytes) {
        if (sampleRate != sourceRate)
            throw new IllegalArgumentException("Source is " + sourceRate + " Hz, detector asked for " + sampleRate);
    }

    public int getState() {
        return STATE_INITIALIZED;
    }

    public int getRecordingState() {
        return recording ? RECORDSTATE_RECORDING : RECORDSTATE_STOPPED;
    }

    public void startRecording() {
        recording = true;
    }

    /**
     * Little-endian 16-bit PCM, like ENCODING_PCM_16BIT. Returns an error code
     * once the song ends, which makes the detector's stream end too.
     */
    public int read(byte[] audioData, int offsetInBytes, int sizeInBytes) {
        if (!recording)
            return ERROR_INVALID_OPERATION;
        onRead.run();
        int samples = Math.min(sizeInBytes / 2, source.length - position);
        if (samples <= 0) {
            recording = false;
            onEnd.run();
            return ERROR_INVALID_OPERATION;
        }
        for (int i = 0; i < samples; i++) {
            short s = source[position + i];
            audioData[offsetInBytes + 2 * i] = (byte) s;
            audioData[offsetInBytes + 2 * i + 1] = (byte) (s >> 8);
        }
        position += samples;
        return samples * 2;
    }

    public void stop() {
        recording = false;
    }

    public void release() {
        recording = false;
    }
}
