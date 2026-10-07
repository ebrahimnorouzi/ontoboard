package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Honouring the configuration, rather than only reading and writing it.
 *
 * <p>The defect these pin was not that a setting was unread - it was that one was <em>overridden
 * while claiming otherwise</em>. A build passed {@code -e ROBOT_JAVA_ARGS=-Xmx8G} into the
 * container, which wins over the environment, so a project declaring {@code -Xmx16G} got 8G in
 * silence; and the advice printed when ROBOT ran out of memory told the user to set
 * {@code ROBOT_JAVA_ARGS}, the very variable the plugin was overriding.
 */
class OdkBuildSettingsTest {

    /** A project laid out the way ODK lays one out. */
    private static File projectWith(File root, String yaml) throws Exception {
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(ontology.mkdirs());
        Files.write(new File(ontology, "mwo-odk.yaml").toPath(),
                yaml.getBytes(StandardCharsets.UTF_8));
        return ontology;
    }

    // ---------- the heap ----------

    /** The project's value reaches the build. */
    @Test
    void theProjectsHeapIsUsed(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "id: mwo\nrobot_java_args: \"-Xmx16G\"\n");

        assertEquals("-Xmx16G", OdkBuildSettings.robotJavaArgs(ontology));

        List<String> command = MakeRun.containerCommand("docker", ontology,
                "obolibrary/odkfull", "all");
        assertTrue(command.contains("ROBOT_JAVA_ARGS=-Xmx16G"), command.toString());
        assertTrue(command.contains("JAVA_OPTS=-Xmx16G"), command.toString());
        assertTrue(!command.contains("ROBOT_JAVA_ARGS=-Xmx8G"),
                "the hardcoded value must be gone: " + command);
    }

    /**
     * A project that says nothing behaves exactly as it did before.
     *
     * <p>The default is the value OntoBoard used unconditionally, so reading the file cannot
     * change an existing project's build.
     */
    @Test
    void theOldValueIsTheDefault(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "id: mwo\ntitle: No heap declared\n");

        assertEquals("-Xmx8G", OdkBuildSettings.robotJavaArgs(ontology));
        assertEquals("-Xmx8G", OdkBuildSettings.DEFAULT_ROBOT_JAVA_ARGS);
        assertTrue(MakeRun.containerCommand("docker", ontology, "obolibrary/odkfull", "all")
                .contains("ROBOT_JAVA_ARGS=-Xmx8G"));
    }

    /** A quoted value is unquoted, because that is what the file means. */
    @Test
    void aQuotedValueIsUnquoted(@TempDir File root) throws Exception {
        assertEquals("-Xmx8G", OdkBuildSettings.robotJavaArgs(
                projectWith(root, "robot_java_args: \"-Xmx8G\"\n")));
    }

    /** Several arguments survive as one value, since the container's shell splits them. */
    @Test
    void severalArgumentsSurvive(@TempDir File root) throws Exception {
        File ontology = projectWith(root,
                "robot_java_args: \"-Xmx12G -XX:+UseG1GC\"\n");

        assertEquals("-Xmx12G -XX:+UseG1GC", OdkBuildSettings.robotJavaArgs(ontology));
        assertTrue(MakeRun.containerCommand("docker", ontology, "obolibrary/odkfull", "all")
                .contains("ROBOT_JAVA_ARGS=-Xmx12G -XX:+UseG1GC"),
                "one argv element, so the space is not a second argument");
    }

    // ---------- the report ----------

    /** fail_on and use_labels are read, and normalised to what ROBOT expects. */
    @Test
    void theReportSettingsAreRead(@TempDir File root) throws Exception {
        File ontology = projectWith(root, String.join("\n",
                "id: mwo",
                "robot_report:",
                "  use_labels: TRUE",
                "  fail_on: ERROR",
                "  custom_profile: TRUE",
                ""));

        assertEquals("ERROR", OdkBuildSettings.reportFailOn(ontology));
        assertEquals(Boolean.TRUE, OdkBuildSettings.reportUsesLabels(ontology));
        assertEquals(Boolean.TRUE, OdkBuildSettings.reportHasCustomProfile(ontology));
    }

    /** YAML's booleans are not Java's: TRUE, yes and on all mean true. */
    @Test
    void yamlBooleansAreUnderstood(@TempDir File root) throws Exception {
        assertEquals(Boolean.TRUE, OdkBuildSettings.reportUsesLabels(
                projectWith(root, "robot_report:\n  use_labels: yes\n")));
        File second = new File(root, "second");
        assertEquals(Boolean.FALSE, OdkBuildSettings.reportUsesLabels(
                projectWith(second, "robot_report:\n  use_labels: FALSE\n")));
    }

    /** A key the project does not declare is null, not a guess. */
    @Test
    void anAbsentKeyIsNull(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "id: mwo\n");

        assertNull(OdkBuildSettings.reportFailOn(ontology));
        assertNull(OdkBuildSettings.reportUsesLabels(ontology));
        assertNull(OdkBuildSettings.reportHasCustomProfile(ontology));
    }

    // ---------- never failing a build over a config problem ----------

    /**
     * Anything unreadable yields the default rather than an exception.
     *
     * <p>A build that refused to start because a configuration file had a problem elsewhere in
     * it would be a worse tool than one that used its documented default.
     */
    @Test
    void anUnreadableProjectFallsBackRatherThanThrowing(@TempDir File root) throws Exception {
        File noProject = new File(root, "nothing-here");
        assertEquals("-Xmx8G", OdkBuildSettings.robotJavaArgs(noProject));
        assertEquals("-Xmx8G", OdkBuildSettings.robotJavaArgs(null));

        File noYaml = new File(new File(new File(root, "bare"), "src"), "ontology");
        assertTrue(noYaml.mkdirs());
        assertEquals("-Xmx8G", OdkBuildSettings.robotJavaArgs(noYaml));

        File broken = projectWith(new File(root, "broken"), "this: is: not: yaml\n");
        assertEquals("-Xmx8G", OdkBuildSettings.robotJavaArgs(broken));

        // A duplicate key makes the whole file unreadable by design, and a build still runs.
        File duplicated = projectWith(new File(root, "dup"),
                "robot_java_args: \"-Xmx1G\"\nrobot_java_args: \"-Xmx2G\"\n");
        assertEquals("-Xmx8G", OdkBuildSettings.robotJavaArgs(duplicated));
    }

    /** A structure where a scalar was expected is not used as one. */
    @Test
    void aStructureIsNotReadAsAScalar(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "robot_java_args:\n  - -Xmx8G\n");

        assertEquals("-Xmx8G", OdkBuildSettings.robotJavaArgs(ontology),
                "a list is not a value; the default stands");
    }

    /** Two configuration files are ambiguous, so neither is chosen. */
    @Test
    void twoConfigurationFilesAreAmbiguous(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "robot_java_args: \"-Xmx16G\"\n");
        Files.write(new File(ontology, "other-odk.yaml").toPath(),
                "robot_java_args: \"-Xmx2G\"\n".getBytes(StandardCharsets.UTF_8));

        assertEquals("-Xmx8G", OdkBuildSettings.robotJavaArgs(ontology),
                "picking one of two would be a guess about which the build uses");
        assertNull(OdkBuildSettings.yamlIn(ontology));
    }

    /** The configuration is found by suffix, not by composing it from the id. */
    @Test
    void theFileIsFoundBySuffix(@TempDir File root) throws Exception {
        File ontology = projectWith(root, "id: something-else\nrobot_java_args: \"-Xmx4G\"\n");

        assertEquals("-Xmx4G", OdkBuildSettings.robotJavaArgs(ontology),
                "the id and the filename disagree, and the file is still read");
    }

    // ---------- the advice that could not work ----------

    /**
     * The out-of-memory advice names the YAML key, not the environment variable.
     *
     * <p>It used to say "give it more with ROBOT_JAVA_ARGS". The plugin passes that variable
     * into the container with {@code -e}, which overrides whatever the user exported, so
     * following the advice changed nothing and read as ROBOT ignoring the setting.
     */
    @Test
    void theOutOfMemoryAdviceNamesSomethingThatWorks() {
        String advice = MakeRun.explain("all", new de.fizkarlsruhe.ise.ontoboard.proc
                .ProcessRunner.Outcome(1,
                java.util.Arrays.asList("Exception in thread \"main\" "
                        + "java.lang.OutOfMemoryError: Java heap space"), false));

        assertTrue(advice.contains("robot_java_args"), advice);
        assertTrue(advice.contains("no effect"),
                "it has to say that setting the variable does nothing: " + advice);
    }
}
