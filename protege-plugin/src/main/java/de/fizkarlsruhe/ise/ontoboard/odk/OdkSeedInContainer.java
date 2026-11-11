package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A new project, seeded by ODK itself: {@code odk.py seed} in ODK's container.
 *
 * <p>This is what New ODK project does when Docker or Podman runs and the pinned image is on the
 * machine. Nothing of the project is then OntoBoard's rendering: the files are the ones ODK
 * wrote. {@code OdkInstaller} writes the same files without a container, and
 * {@code SeedLiveParityTest} holds the two against each other.
 *
 * <p>The seed runs with {@code --skipgit}: ODK then writes the files and stops, instead of
 * making commits as "ODK" and building the first release - which belongs to the person, and to
 * Create, which shows what it commits.
 *
 * <p>The configuration is handed over in a folder of its own, mounted read-only, and the
 * project's parent folder is mounted to be written into. Nothing else of the machine is given
 * to the container.
 */
public final class OdkSeedInContainer {

    /** A seed renders templates and copies files; it does not download or reason. */
    private static final long TIMEOUT_MINUTES = 10;

    private static final String CONFIG = "seed-odk.yaml";
    private static final String SOURCE = "seed-source.owl";

    private OdkSeedInContainer() {
    }

    /**
     * The command that seeds a project.
     *
     * @param configDirectory  a folder holding the configuration, mounted at
     *                         {@code /seedconfig}, read-only
     * @param configName       the configuration's file name in it
     * @param parentDirectory  the folder the project's folder is made in, mounted at
     *                         {@code /work}
     * @param projectFolder    the project folder's name: one name, not a path
     * @param sourceNameOrNull the file name, in the config folder, of an ontology to start from
     */
    public static List<String> command(String runtime, String image, OdkContainer.Host host,
            File configDirectory, String configName, File parentDirectory, String projectFolder,
            String sourceNameOrNull) {
        if (projectFolder == null || projectFolder.isEmpty() || projectFolder.equals(".")
                || projectFolder.equals("..") || projectFolder.indexOf('/') >= 0
                || projectFolder.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("a project folder is one name, not '"
                    + projectFolder + "'");
        }
        if (runtime == null || image == null) {
            throw new IllegalArgumentException("no container runtime, or no ODK image, to seed "
                    + "with");
        }
        List<String> command = new ArrayList<String>();
        command.add(runtime);
        command.add("run");
        command.add("--rm");
        command.add("-v");
        command.add(forMount(configDirectory) + ":/seedconfig:ro");
        command.add("-v");
        command.add(forMount(parentDirectory) + ":/work");
        command.add("-w");
        command.add("/work");
        if (!host.windows && host.userId != null && host.groupId != null) {
            // ODK's entrypoint then writes the project as this user, not as root.
            command.add("-e");
            command.add("ODK_USER_ID=" + host.userId);
            command.add("-e");
            command.add("ODK_GROUP_ID=" + host.groupId);
        }
        command.add(image);
        command.addAll(Arrays.asList("odk.py", "seed", "--skipgit", "-C",
                "/seedconfig/" + configName, "-D", "/work/" + projectFolder));
        if (sourceNameOrNull != null) {
            command.add("-s");
            command.add("/seedconfig/" + sourceNameOrNull);
        }
        return command;
    }

    /**
     * Seeds a project into a folder of its own.
     *
     * @param projectRoot a folder that does not exist yet, or holds nothing
     * @param sourceOrNull an ontology to start from instead of ODK's empty one
     * @return the files ODK wrote, sorted
     * @throws IllegalStateException when the folder holds something, or ODK's seed fails - with
     *                               what ODK said; nothing of a failed seed is left
     * @throws UncheckedIOException  when the configuration cannot be handed over
     */
    public static List<File> run(String runtime, String image, OdkContainer.Host host,
            String configText, File projectRoot, byte[] sourceOrNull,
            ProcessRunner.Runner runner) {
        File root = projectRoot.getAbsoluteFile();
        boolean existed = root.exists();
        if (existed) {
            String[] inside = root.list();
            if (inside == null || inside.length > 0) {
                throw new IllegalStateException(root + " is not an empty folder. A new ODK "
                        + "project needs a folder of its own.");
            }
        }
        File parent = root.getParentFile();
        if (parent == null) {
            throw new IllegalArgumentException(root + " has no folder to be made in");
        }
        Path configs = null;
        try {
            // Made here: a runtime asked to mount a folder that is not there makes it, as root.
            Files.createDirectories(parent.toPath());
            configs = Files.createTempDirectory("ontoboard-seed");
            Files.write(configs.resolve(CONFIG), configText.getBytes(StandardCharsets.UTF_8));
            if (sourceOrNull != null) {
                Files.write(configs.resolve(SOURCE), sourceOrNull);
            }
            List<String> command = command(runtime, image, host, configs.toFile(), CONFIG,
                    parent, root.getName(), sourceOrNull == null ? null : SOURCE);
            ProcessRunner.Outcome outcome = runner.run(parent, command, TIMEOUT_MINUTES, null);
            List<File> written = new ArrayList<File>();
            if (outcome.isSuccess()) {
                collect(root, written);
                Collections.sort(written);
            }
            if (written.isEmpty()) {
                takeBack(root, existed);
                throw new IllegalStateException("ODK's own seed (odk.py seed, in " + image
                        + ") " + (outcome.timedOut() ? "did not finish within " + TIMEOUT_MINUTES
                                + " minutes"
                                : outcome.isSuccess() ? "finished and wrote nothing"
                                        : "stopped: " + lastLines(outcome.getOutput()))
                        + ". The command was: " + String.join(" ", command));
            }
            return written;
        } catch (IOException failed) {
            takeBack(root, existed);
            throw new UncheckedIOException("Could not seed " + root + " through " + runtime
                    + ": " + failed.getMessage(), failed);
        } finally {
            if (configs != null) {
                delete(configs.toFile());
            }
        }
    }

    /** The end of what ODK printed: a Python error says what went wrong in its last lines. */
    private static String lastLines(List<String> output) {
        List<String> kept = new ArrayList<String>();
        for (int i = output.size() - 1; i >= 0 && kept.size() < 3; i--) {
            if (!output.get(i).trim().isEmpty()) {
                kept.add(0, output.get(i).trim());
            }
        }
        return kept.isEmpty() ? "it said nothing" : String.join(" / ", kept);
    }

    private static void collect(File folder, List<File> into) {
        File[] inside = folder.listFiles();
        if (inside == null) {
            return;
        }
        for (File file : inside) {
            if (file.isDirectory()) {
                collect(file, into);
            } else {
                into.add(file);
            }
        }
    }

    /** Removes what a failed seed left: the folder's contents, and the folder if it was new. */
    private static void takeBack(File root, boolean existed) {
        File[] inside = root.listFiles();
        if (inside != null) {
            for (File file : inside) {
                delete(file);
            }
        }
        if (!existed) {
            root.delete();
        }
    }

    /**
     * Removes a file, or a folder with what is in it. A link or a Windows junction is removed
     * as the link it is: what it points to is somebody's, and is not gone into.
     */
    private static void delete(File file) {
        Path path = file.toPath();
        try {
            BasicFileAttributes is = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            // A junction reports itself a directory and "other" at once.
            if (is.isDirectory() && !is.isOther() && !is.isSymbolicLink()) {
                File[] inside = file.listFiles();
                if (inside != null) {
                    for (File child : inside) {
                        delete(child);
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException leftBehind) {
            // Tidying up after a failure that is already being reported; that report stands.
        }
    }

    private static String forMount(File file) {
        return file.getAbsolutePath().replace('\\', '/');
    }
}
