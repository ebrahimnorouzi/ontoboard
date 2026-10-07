package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * The settings in {@code <id>-odk.yaml} that change how OntoBoard behaves.
 *
 * <p>Reading and writing the configuration is not the same as honouring it, and until now
 * OntoBoard did the first two and not the third: the file was shown, edited and preserved
 * faithfully while five scalars drove anything at all, and those only through the regenerator,
 * which refuses a real ODK repository before reading them. Everything else was text the plugin
 * carried around.
 *
 * <p><b>The one that was actively wrong.</b> A build ran with a hardcoded
 * {@code ROBOT_JAVA_ARGS=-Xmx8G}, passed to the container with {@code -e}, which wins over
 * anything in the environment. So a project declaring {@code robot_java_args: "-Xmx16G"} got 8G
 * and no indication, and the advice this plugin printed when ROBOT ran out of memory - "give it
 * more with ROBOT_JAVA_ARGS" - could not work, because the plugin overrode exactly the variable
 * it was telling the user to set.
 *
 * <p>Separate from {@link OdkProjectSettings}, which reads the five keys the scaffold's
 * regenerator needs and throws on a repository it cannot regenerate - which is every genuine ODK
 * repository. These settings have to be readable from any project, so every one of them is
 * optional and a missing or unreadable file yields the documented default rather than an error:
 * a build must not fail because a configuration key is absent.
 *
 * <p>Read through {@link OdkYaml}, so quoting, comments and nesting are handled by the code that
 * is already tested for them rather than by a second regex.
 */
public final class OdkBuildSettings {

    /**
     * What a build gets when the project says nothing.
     *
     * <p>The value OntoBoard used unconditionally before it read the file, kept as the default
     * so a project without the key behaves exactly as it did.
     */
    public static final String DEFAULT_ROBOT_JAVA_ARGS = "-Xmx8G";

    private OdkBuildSettings() {
    }

    /**
     * The JVM arguments the project wants ROBOT run with, or the default.
     *
     * @param ontologyDirectory the project's {@code src/ontology}
     */
    public static String robotJavaArgs(File ontologyDirectory) {
        String declared = scalarAt(ontologyDirectory, "robot_java_args");
        return declared == null || declared.trim().isEmpty()
                ? DEFAULT_ROBOT_JAVA_ARGS : declared.trim();
    }

    /** Whether the project asked for labels in the ROBOT report, or null when it did not say. */
    public static Boolean reportUsesLabels(File ontologyDirectory) {
        return flagAt(ontologyDirectory, "robot_report.use_labels");
    }

    /**
     * The severity the project fails its report on - {@code ERROR}, {@code WARN}, {@code INFO} -
     * or null when it does not say.
     */
    public static String reportFailOn(File ontologyDirectory) {
        String declared = scalarAt(ontologyDirectory, "robot_report.fail_on");
        return declared == null || declared.trim().isEmpty()
                ? null : declared.trim().toUpperCase(Locale.ROOT);
    }

    /** Whether the project declares it ships its own report profile. */
    public static Boolean reportHasCustomProfile(File ontologyDirectory) {
        return flagAt(ontologyDirectory, "robot_report.custom_profile");
    }

    /**
     * One scalar, by path, or null.
     *
     * <p>Every failure is a null: no project, no file, unreadable YAML, a duplicate key, the
     * path naming a structure. A build that refused to start because a configuration file had a
     * problem somewhere else in it would be a worse tool than one that used its default.
     */
    static String scalarAt(File ontologyDirectory, String path) {
        if (ontologyDirectory == null || !ontologyDirectory.isDirectory()) {
            return null;
        }
        File yaml = yamlIn(ontologyDirectory);
        if (yaml == null) {
            return null;
        }
        try {
            String text = new String(Files.readAllBytes(yaml.toPath()), StandardCharsets.UTF_8);
            for (OdkYaml.Entry entry : OdkYaml.entriesIn(text)) {
                if (path.equals(entry.getPath())
                        && entry.getEditable() == OdkYaml.Editable.YES) {
                    return entry.getValue();
                }
            }
            return null;
        } catch (IOException unreadable) {
            return null;
        } catch (RuntimeException notYaml) {
            // Includes OdkYaml.UnreadableException: a duplicate key, or not a mapping at all.
            return null;
        }
    }

    /** YAML's booleans, which are not Java's: TRUE, True, true, yes, on. */
    private static Boolean flagAt(File ontologyDirectory, String path) {
        String declared = scalarAt(ontologyDirectory, path);
        if (declared == null) {
            return null;
        }
        String value = declared.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "yes".equals(value) || "on".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value) || "no".equals(value) || "off".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * The project's {@code *-odk.yaml}, or null.
     *
     * <p>By suffix rather than by composing {@code <id>-odk.yaml}, because the id comes from the
     * same file and a project whose id and filename disagree would otherwise read as having no
     * configuration at all. More than one is ambiguous, so neither is chosen.
     */
    static File yamlIn(File ontologyDirectory) {
        File[] found = ontologyDirectory.listFiles();
        if (found == null) {
            return null;
        }
        File only = null;
        for (File candidate : found) {
            if (candidate.isFile() && candidate.getName().endsWith("-odk.yaml")) {
                if (only != null) {
                    return null;
                }
                only = candidate;
            }
        }
        return only;
    }
}
