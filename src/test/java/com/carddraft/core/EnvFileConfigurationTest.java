package com.carddraft.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code .env} file a stranger copies next to the checkout has to configure the service.
 *
 * <p>This is the whole contract, and it is not visible from a unit test that sets properties
 * itself: every {@code @SpringBootTest} in this repository passes {@code card.db.*} directly, so a
 * service that ignored its own {@code .env} entirely would still pass the entire suite. The only
 * honest way to test it is to start the service the way a person does — from a directory
 * containing nothing but a {@code .env} — which is what
 * {@link EnvFileConfigurationProbe} is asked to do from a child process.
 *
 * <p>A shell export taking precedence over the file is asserted as well, because that precedence is
 * the reason {@code .env} and the environment can share one variable name without either one
 * surprising anybody: an operator who exports a value for one run must win over the file's.
 */
class EnvFileConfigurationTest {

    private static final String FILE_URL = "jdbc:postgresql://127.0.0.1:15999/from-env-file";
    private static final String FILE_USER = "card-from-env-file";

    @TempDir
    Path workingDirectory;

    @TempDir
    Path argumentDirectory;

    @Test
    void anEnvFileBesideTheApplicationConfiguresTheDatabase() throws Exception {
        writeEnvFile(FILE_URL, FILE_USER);

        String output = runProbe(Map.of());

        assertThat(output)
                .as("the settings a process started in a directory containing this .env would bind")
                .contains("SETTINGS url=" + FILE_URL)
                .contains("SETTINGS username=" + FILE_USER)
                .contains("SETTINGS poolSize=7");
    }

    @Test
    void anExportedValueOverridesTheFile() throws Exception {
        writeEnvFile(FILE_URL, FILE_USER);
        String exported = "jdbc:postgresql://127.0.0.1:15998/from-shell";

        String output = runProbe(Map.of("CARD_DB_URL", exported));

        assertThat(output)
                .as("an operator who exports a value for one run means it, whatever the file says")
                .contains("SETTINGS url=" + exported)
                .doesNotContain(FILE_URL);
    }

    private void writeEnvFile(String url, String user) throws IOException {
        Files.writeString(workingDirectory.resolve(".env"), """
                CARD_DB_URL=%s
                CARD_DB_USER=%s
                CARD_DB_POOL_SIZE=7
                """.formatted(url, user), StandardCharsets.UTF_8);
    }

    private String runProbe(Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(javaExecutable(),
                "@" + argumentFile().toAbsolutePath());
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);

        Map<String, String> childEnvironment = builder.environment();
        childEnvironment.keySet().removeIf(EnvFileConfigurationTest::isInheritedConfiguration);
        childEnvironment.putAll(environment);

        Process probe = builder.start();
        String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(probe.waitFor(120, TimeUnit.SECONDS))
                .as("the probe finished, reporting:%n%s", output)
                .isTrue();
        assertThat(probe.exitValue())
                .as("the probe exited cleanly, reporting:%n%s", output)
                .isZero();
        return output;
    }

    /**
     * The probe's own command line, in a file.
     *
     * <p>This project's runtime classpath runs to several thousand characters, which is past what
     * Windows will accept on a command line — a child process fails to start at all. An argument
     * file carries it instead, and lives outside the working directory so that what the probe finds
     * beside itself is the {@code .env} and nothing else.
     */
    private Path argumentFile() throws IOException {
        Path arguments = argumentDirectory.resolve("probe.args");
        Files.writeString(arguments, """
                -cp "%s"
                %s
                """.formatted(classpathForArgumentFile(), EnvFileConfigurationProbe.class.getName()),
                StandardCharsets.UTF_8);
        return arguments;
    }

    /** Forward slashes throughout: a backslash is an escape character inside an argument file. */
    private String classpathForArgumentFile() {
        return System.getProperty("java.class.path").replace('\\', '/');
    }

    /**
     * Variables that would let this machine's configuration answer instead of the file's.
     *
     * <p>Every one of them has the same failure shape: the assertion passes or fails according to
     * how the developer happened to launch the suite, which is exactly the class of green that
     * hides the bug under test.
     */
    private static boolean isInheritedConfiguration(String name) {
        return name.startsWith("CARD_")
                || name.startsWith("SPRING_")
                || name.equals("JAVA_TOOL_OPTIONS")
                || name.equals("_JAVA_OPTIONS")
                || name.equals("JDK_JAVA_OPTIONS");
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}