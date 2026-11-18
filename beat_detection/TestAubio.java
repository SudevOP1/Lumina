import javax.tools.ToolProvider;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Launcher for the aubio beat tracker test bench. From beat_detection/:
 *
 * java TestAubio.java (uses Bench.AUDIO_FILE)
 * java TestAubio.java "audio/After Hours - The Weeknd.mp3"
 *
 * Runs aubio's tempo tracker (aubio_tempo, as used by aubiotrack) over a song
 * and shows the result in Bench's viewer. Steps:
 * 1. downloads the official aubio 0.4.6 win64 build and keeps its
 * libaubio-5.dll in lib/aubio (first run only; checksum verified);
 * 2. compiles aubio_test/AubioBench.java plus Bench and KickDetector into
 * out/aubio_classes, only when a source changed;
 * 3. runs AubioBench in a child JVM with native access enabled (it calls
 * libaubio through the FFM API).
 *
 * Elsewhere than Windows, point AUBIO_LIB at an installed libaubio
 * (e.g. /usr/lib/x86_64-linux-gnu/libaubio.so.5).
 */
public class TestAubio {
    static final String ZIP_URL = "https://aubio.org/bin/0.4.6/aubio-0.4.6-win64.zip";
    static final String ZIP_SHA256 = "97d4fe0e49d66db12881b94c2d2dc1e5f7126ce8bbeac30b8664e9c641360106";
    static final File DEPS = new File("lib/aubio");
    static final File DLL = new File(DEPS, "libaubio-5.dll");
    static final File OUT = new File("out/aubio_classes");
    static final File STAMP = new File(OUT, ".built");
    static final String[] SOURCES = {
            "custom_test/KickDetector.java",
            "bench/Bench.java",
            "aubio_test/AubioBench.java",
    };

    public static void main(String[] args) throws Exception {
        File lib = library();
        if (needsCompile())
            compile();

        List<String> cmd = new ArrayList<>(List.of(
                new File(System.getProperty("java.home"), "bin/java").getPath(),
                "--enable-native-access=ALL-UNNAMED",
                "-Daubio.lib=" + lib.getAbsolutePath(),
                "-cp", OUT.getPath(), "AubioBench"));
        cmd.addAll(List.of(args));
        System.exit(new ProcessBuilder(cmd).inheritIO().start().waitFor());
    }

    // ---------------- build ----------------
    static void compile() throws Exception {
        System.out.println("Compiling...");
        OUT.mkdirs();
        STAMP.delete();
        List<String> a = new ArrayList<>(List.of("-d", OUT.getPath(), "-encoding", "UTF-8"));
        a.addAll(List.of(SOURCES));
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, a.toArray(String[]::new)) != 0) {
            System.err.println("Compilation failed.");
            System.exit(1);
        }
        Files.writeString(STAMP.toPath(), "");
    }

    /**
     * True if any source is newer than the last successful build (or there is
     * none).
     */
    static boolean needsCompile() {
        for (String s : SOURCES) {
            if (!new File(s).isFile()) {
                System.err.println("Missing source: " + new File(s).getAbsolutePath() + " (run from beat_detection/)");
                System.exit(1);
            }
        }
        if (!STAMP.isFile())
            return true;
        long built = STAMP.lastModified();
        for (String s : SOURCES)
            if (new File(s).lastModified() > built)
                return true;
        return false;
    }

    // ---------------- libaubio ----------------
    /** AUBIO_LIB if set, else lib/aubio/libaubio-5.dll, downloading it if missing. */
    static File library() throws Exception {
        String env = System.getenv("AUBIO_LIB");
        if (env != null && !env.isBlank()) {
            File f = new File(env);
            if (!f.isFile()) {
                System.err.println("AUBIO_LIB points to a missing file: " + f.getAbsolutePath());
                System.exit(1);
            }
            return f;
        }
        if (DLL.isFile())
            return DLL;
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            System.err.println("No prebuilt aubio for this OS: install libaubio and set AUBIO_LIB to it.");
            System.exit(1);
        }

        System.out.println("Downloading aubio 0.4.6 (win64) ...");
        DEPS.mkdirs();
        File zip = new File(DEPS, "aubio-0.4.6-win64.zip.part");
        try (InputStream in = URI.create(ZIP_URL).toURL().openStream()) {
            Files.copy(in, zip.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip.toPath())));
        if (!sha.equals(ZIP_SHA256)) {
            zip.delete();
            System.err.println("Checksum mismatch for " + ZIP_URL + " (got " + sha + ").");
            System.exit(1);
        }
        // keep only the DLLs from bin/
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null;) {
                String name = e.getName();
                if (!e.isDirectory() && name.contains("/bin/") && name.endsWith(".dll"))
                    Files.copy(z, new File(DEPS, name.substring(name.lastIndexOf('/') + 1)).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
            }
        }
        zip.delete();
        if (!DLL.isFile()) {
            System.err.println("libaubio-5.dll not found in " + ZIP_URL);
            System.exit(1);
        }
        return DLL;
    }
}
