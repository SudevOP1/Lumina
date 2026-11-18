import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Launcher for the beat detection test bench. From beat_detection/:
 *
 * java TestCustom.java (uses Bench.AUDIO_FILE)
 * java TestCustom.java "audio/After Hours - The Weeknd.mp3"
 *
 * Compiles the app's KickDetector plus bench/Bench.java into out/classes
 * (only when a source changed), then runs Bench with the given arguments.
 */
public class TestCustom {
    static final String[] SOURCES = {
            "custom_test/KickDetector.java",
            "bench/Bench.java",
    };
    static final File OUT = new File("out/classes");

    public static void main(String[] args) throws Exception {
        if (needsCompile()) {
            OUT.mkdirs();
            List<String> javacArgs = new ArrayList<>(List.of("-d", OUT.getPath(), "-encoding", "UTF-8"));
            javacArgs.addAll(List.of(SOURCES));
            int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, javacArgs.toArray(String[]::new));
            if (rc != 0) {
                System.err.println("Compilation failed.");
                System.exit(1);
            }
        }

        URLClassLoader loader = new URLClassLoader(new URL[] { OUT.toURI().toURL() }, TestCustom.class.getClassLoader());
        try {
            loader.loadClass("Bench").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    /**
     * True if any source is newer than its compiled output (or the output is
     * missing).
     */
    static boolean needsCompile() {
        File bench = new File(OUT, "Bench.class");
        File kick = new File(OUT, "xyz/sudev/lumina/KickDetector.class");
        if (!bench.isFile() || !kick.isFile())
            return true;
        long built = Math.min(bench.lastModified(), kick.lastModified());
        for (String s : SOURCES) {
            File f = new File(s);
            if (!f.isFile()) {
                System.err.println("Missing source: " + f.getAbsolutePath() + " (run from beat_detection/)");
                System.exit(1);
            }
            if (f.lastModified() > built)
                return true;
        }
        return false;
    }
}
