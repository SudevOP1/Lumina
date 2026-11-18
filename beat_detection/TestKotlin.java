import javax.tools.ToolProvider;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Launcher for the Kotlin beat detector test bench. From beat_detection/:
 *
 * java TestKotlin.java (uses Bench.AUDIO_FILE)
 * java TestKotlin.java "audio/After Hours - The Weeknd.mp3"
 *
 * Runs kotlin_test/AudioBeatDetector.kt over a song and shows the result in
 * Bench's viewer, with Android's AudioRecord/Log replaced by desktop stubs
 * (kotlin_test/stubs). Steps:
 * 1. downloads the Kotlin compiler, stdlib and coroutines into lib/kotlin
 * (first run only);
 * 2. compiles stubs + Bench helpers (javac), AudioBeatDetector.kt (kotlinc,
 * in-process, with System.currentTimeMillis() swapped for the audio clock),
 * then KotlinBench (javac) into out/kotlin_classes, only when a source
 * changed;
 * 3. runs KotlinBench with the given arguments.
 */
public class TestKotlin {
    static final String KOTLIN = "2.4.21";
    static final String COROUTINES = "1.11.0";
    static final String MAVEN = "https://repo1.maven.org/maven2/";
    static final File DEPS = new File("lib/kotlin");
    static final File OUT = new File("out/kotlin_classes");
    static final File STAMP = new File(OUT, ".built");
    static final File TARSOS = new File("lib/TarsosDSP-core.jar");

    /** Needed to run the compiled code (and on the compile classpath). */
    static final String[] RUNTIME = {
            "org.jetbrains.kotlin:kotlin-stdlib:" + KOTLIN,
            "org.jetbrains:annotations:13.0",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:" + COROUTINES,
            "org.jetbrains.kotlinx:kotlinx-coroutines-swing:" + COROUTINES, // Dispatchers.Main
    };
    /** The Kotlin compiler itself (plus RUNTIME). */
    static final String[] COMPILER = {
            "org.jetbrains.kotlin:kotlin-compiler-embeddable:" + KOTLIN,
            "org.jetbrains.kotlin:kotlin-build-tools-api:" + KOTLIN,
            "org.jetbrains.kotlin:kotlin-script-runtime:" + KOTLIN,
            "org.jetbrains.kotlin:kotlin-daemon-embeddable:" + KOTLIN,
            "org.jetbrains.kotlin:kotlin-reflect:1.6.10",
    };

    static final String KT = "kotlin_test/AudioBeatDetector.kt";
    static final String STUBS = "kotlin_test/stubs";
    static final String[] JAVA_HELPERS = { "custom_test/KickDetector.java", "bench/Bench.java" };
    static final String HARNESS = "kotlin_test/KotlinBench.java";
    static final String WALL_CLOCK = "System.currentTimeMillis()";
    static final String AUDIO_CLOCK = "android.media.AudioRecord.clockMillis()";

    public static void main(String[] args) throws Exception {
        List<File> runtime = fetch(RUNTIME);
        if (!TARSOS.isFile()) {
            System.err.println("Missing " + TARSOS.getAbsolutePath() + " (run from beat_detection/)");
            System.exit(1);
        }
        runtime.add(TARSOS);

        if (needsCompile()) {
            List<File> compiler = fetch(COMPILER);
            compiler.addAll(runtime);
            compile(runtime, compiler);
        }

        List<URL> urls = new ArrayList<>();
        urls.add(OUT.toURI().toURL());
        for (File f : runtime)
            urls.add(f.toURI().toURL());
        URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), TestKotlin.class.getClassLoader());
        try {
            loader.loadClass("KotlinBench").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    // ---------------- build ----------------
    static void compile(List<File> runtime, List<File> compiler) throws Exception {
        System.out.println("Compiling...");
        OUT.mkdirs();
        STAMP.delete();
        String cp = OUT.getPath() + File.pathSeparator + join(runtime);

        // 1. Android stubs + Bench helpers (the .kt needs the stubs on its classpath)
        List<String> sources = javaFiles(new File(STUBS));
        sources.addAll(List.of(JAVA_HELPERS));
        javac(cp, sources);

        // 2. AudioBeatDetector.kt, on the audio clock (see stubs/android/media/AudioRecord),
        // with the compiler loaded in its own class loader
        String kt = Files.readString(new File(KT).toPath());
        if (!kt.contains(WALL_CLOCK))
            System.err.println("Note: " + KT + " no longer calls " + WALL_CLOCK + "; beat timing may be off.");
        File ktOut = new File("out/kotlin_src/" + new File(KT).getName());
        ktOut.getParentFile().mkdirs();
        Files.writeString(ktOut.toPath(), kt.replace(WALL_CLOCK, AUDIO_CLOCK));

        List<URL> urls = new ArrayList<>();
        for (File f : compiler)
            urls.add(f.toURI().toURL());
        try (URLClassLoader kc = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Object k2 = kc.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler").getDeclaredConstructor()
                    .newInstance();
            String[] kargs = { "-d", OUT.getPath(), "-cp", cp, "-no-stdlib", "-no-reflect",
                    "-jvm-target", "17", "-jdk-home", System.getProperty("java.home"), ktOut.getPath() };
            Object exit = k2.getClass().getMethod("exec", PrintStream.class, String[].class)
                    .invoke(k2, System.err, (Object) kargs);
            if (!"OK".equals(exit.toString())) {
                System.err.println("Kotlin compilation failed (" + exit + ").");
                System.exit(1);
            }
        }

        // 3. Swing harness (uses the compiled Kotlin class)
        javac(cp, List.of(HARNESS));
        Files.writeString(STAMP.toPath(), KOTLIN);
    }

    static void javac(String cp, List<String> sources) {
        List<String> a = new ArrayList<>(List.of("-d", OUT.getPath(), "-cp", cp, "-encoding", "UTF-8"));
        a.addAll(sources);
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, a.toArray(String[]::new)) != 0) {
            System.err.println("Java compilation failed.");
            System.exit(1);
        }
    }

    /**
     * True if any source is newer than the last successful build (or there is
     * none).
     */
    static boolean needsCompile() throws Exception {
        List<String> sources = javaFiles(new File(STUBS));
        sources.addAll(List.of(JAVA_HELPERS));
        sources.add(KT);
        sources.add(HARNESS);
        for (String s : sources) {
            if (!new File(s).isFile()) {
                System.err.println("Missing source: " + new File(s).getAbsolutePath() + " (run from beat_detection/)");
                System.exit(1);
            }
        }
        if (!STAMP.isFile() || !KOTLIN.equals(Files.readString(STAMP.toPath())))
            return true;
        long built = STAMP.lastModified();
        for (String s : sources)
            if (new File(s).lastModified() > built)
                return true;
        return false;
    }

    static List<String> javaFiles(File dir) throws Exception {
        try (Stream<java.nio.file.Path> walk = Files.walk(dir.toPath())) {
            return new ArrayList<>(walk.map(Object::toString).filter(p -> p.endsWith(".java")).toList());
        }
    }

    // ---------------- dependencies ----------------
    /** Maven coordinates → jars in lib/kotlin, downloading any that are missing. */
    static List<File> fetch(String[] coords) throws Exception {
        List<File> jars = new ArrayList<>();
        for (String c : coords) {
            String[] p = c.split(":");
            String name = p[1] + "-" + p[2] + ".jar";
            File jar = new File(DEPS, name);
            if (!jar.isFile()) {
                String url = MAVEN + p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + name;
                System.out.println("Downloading " + name + " ...");
                DEPS.mkdirs();
                File tmp = new File(DEPS, name + ".part");
                try (InputStream in = URI.create(url).toURL().openStream()) {
                    Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            jars.add(jar);
        }
        return jars;
    }

    static String join(List<File> files) {
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            if (sb.length() > 0)
                sb.append(File.pathSeparator);
            sb.append(f.getPath());
        }
        return sb.toString();
    }
}
