package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A project's releases, read from its git tags.
 *
 * <p>ODK keeps the files of a release out of git until they are committed for it, and a release
 * is then a commit with a tag {@code v<version>}. For a project that keeps no {@code releases/}
 * folder - an ODK repository OntoBoard did not make - the tags are the only record there is:
 * which versions exist, and what a released file held.
 *
 * <p>Read-only. Where there is no repository, or no git, there are no releases.
 */
public final class GitTags {

    /** The letter a release's tag starts with. */
    private static final String PREFIX = "v";

    private GitTags() {
    }

    /** The tag of a version: {@code v1.0.0}. */
    public static String tagFor(String version) {
        return PREFIX + version;
    }

    /** The version a tag names, or null when the tag is not a release's. */
    public static String versionOf(String tag) {
        if (tag == null || !tag.startsWith(PREFIX)) {
            return null;
        }
        OntologyVersion version = OntologyVersion.parseOrNull(tag.substring(PREFIX.length()));
        return version == null ? null : version.getText();
    }

    /**
     * The release tags of a project, newest version first.
     *
     * <p>By version, not by name and not by when the tag was made: 1.10.0 is after 1.2.0, and a
     * tag put on an old commit later is still the old release.
     */
    public static List<String> releases(File projectRoot, ProcessRunner.Runner runner) {
        List<String> tags = new ArrayList<String>();
        try {
            ProcessRunner.Outcome listed = runner.run(projectRoot,
                    Arrays.asList("git", "tag", "--list", PREFIX + "*"), 1, null);
            if (!listed.isSuccess()) {
                return tags;
            }
            for (String line : listed.getOutput()) {
                if (versionOf(line.trim()) != null) {
                    tags.add(line.trim());
                }
            }
        } catch (IOException noGit) {
            return tags;
        } catch (RuntimeException noGit) {
            return tags;
        }
        Collections.sort(tags, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return OntologyVersion.of(versionOf(b)).compareTo(OntologyVersion.of(versionOf(a)));
            }
        });
        return tags;
    }

    /**
     * A file as a release has it, byte for byte, or null when that release has no such file -
     * or there is no such tag, no repository, no git.
     *
     * @param path from the project's root, with {@code /}
     */
    public static byte[] fileAt(File projectRoot, String tag, String path) {
        if (versionOf(tag) == null || path.startsWith("-") || path.contains(":")) {
            return null;
        }
        File out = null;
        try {
            // To a file, not through a pipe read as text: the bytes are the release's.
            out = File.createTempFile("ontoboard-tag", ".bin");
            Process git = new ProcessBuilder("git", "show", tag + ":" + path)
                    .directory(projectRoot)
                    .redirectOutput(out)
                    .redirectError(ProcessBuilder.Redirect.appendTo(out.getAbsoluteFile()
                            .getParentFile().toPath().resolve(out.getName() + ".err").toFile()))
                    .start();
            git.getOutputStream().close();
            if (!git.waitFor(60, TimeUnit.SECONDS)) {
                git.destroyForcibly();
                return null;
            }
            return git.exitValue() == 0 ? Files.readAllBytes(out.toPath()) : null;
        } catch (IOException noGit) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (out != null) {
                out.delete();
                new File(out.getParentFile(), out.getName() + ".err").delete();
            }
        }
    }
}
