package by.radioegor146;

import by.radioegor146.helpers.ProcessHelper;
import by.radioegor146.helpers.ProcessHelper.ProcessResult;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end repro of the Kotlin coroutine failure seen on Android.
 *
 * The input jar is a prebuilt Kotlin object (Rep.kt in the same directory)
 * whose suspend functions park the coroutine on Dispatchers.IO, exactly like
 * the app's SetupGates.snapshot / CoverCache.load / GamesScreenKt.refreshGates.
 *
 * Only the app-side class is transpiled ("Rep*"); kotlin-stdlib and
 * kotlinx-coroutines stay as ordinary bytecode, which is the same shape as the
 * Android build. The transpiled program must print exactly what the plain JVM
 * run prints.
 */
public class KotlinCoroutineTest {

    private static Path jarOf(Class<?> clazz) {
        try {
            return Paths.get(clazz.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new RuntimeException("cannot locate jar for " + clazz.getName(), e);
        }
    }

    private static List<String> javaRun(Path dir, String classpath, String mainClass) {
        return Arrays.asList("java", "-Djava.library.path=.", "-cp", classpath, mainClass);
    }

    @Test
    public void suspendFunctionsSurviveTranspilation() throws Exception {
        Path input = Paths.get(Objects.requireNonNull(
                getClass().getResource("/coroutines/kotlin/input.jar")).toURI());
        Path stdlib = jarOf(kotlin.Unit.class);
        Path coroutines = jarOf(kotlinx.coroutines.BuildersKt.class);

        Path temp = Files.createTempDirectory("nob-kotlin-coro-");
        Path inputJar = temp.resolve("input.jar");
        Files.copy(input, inputJar, StandardCopyOption.REPLACE_EXISTING);

        String plainCp = inputJar + File.pathSeparator + stdlib + File.pathSeparator + coroutines;
        ProcessResult ideal = ProcessHelper.run(temp, 60_000, javaRun(temp, plainCp, "Rep"));
        ideal.check("Ideal run");
        System.out.println("--- plain JVM ---\n" + ideal.stdout + ideal.stderr);

        Path out = temp.resolve("out");
        Path cpp = out.resolve("cpp");
        Files.createDirectories(cpp);

        new NativeObfuscator().process(inputJar, out,
                Arrays.asList(stdlib, coroutines),
                Collections.emptyList(),
                Collections.singletonList("Rep*"),
                "native_library", null, Platform.HOTSPOT, true, true);

        ProcessHelper.run(cpp, 300_000, Arrays.asList("cmake", ".")).check("CMake prepare");
        ProcessHelper.run(cpp, 900_000, Arrays.asList("cmake", "--build", ".", "--config", "Release"))
                .check("CMake build");

        Path libDir = cpp.resolve("build").resolve("lib");
        try (Stream<Path> libs = Files.find(libDir, 1, (p, a) -> Files.isRegularFile(p))) {
            for (Path lib : libs.collect(Collectors.toList())) {
                Files.copy(lib, out.resolve(lib.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Path resultJar;
        try (Stream<Path> jars = Files.find(out, 1,
                (p, a) -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))) {
            resultJar = jars.findFirst().orElseThrow(() -> new IllegalStateException("no output jar in " + out));
        }

        String testCp = resultJar + File.pathSeparator + stdlib + File.pathSeparator + coroutines;
        ProcessResult test = ProcessHelper.run(out, 200_000, javaRun(out, testCp, "Rep"));
        System.out.println("--- transpiled ---\n" + test.stdout + test.stderr);
        test.check("Test run");

        assertEquals(ideal.stdout, test.stdout,
                "transpiled coroutine output diverged from the plain JVM run");
    }
}
