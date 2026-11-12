package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final java.util.concurrent.atomic.AtomicInteger HOLDING_ASKED =
            new java.util.concurrent.atomic.AtomicInteger();

    /** How many times {@link #holding} started git. For tests. */
    static int holdingAsked() {
        return HOLDING_ASKED.get();
    }

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
        List<String> tags = releasesOrNull(projectRoot, runner);
        return tags == null ? new ArrayList<String>() : tags;
    }

    /**
     * {@link #releases}, or null when git could not be asked or did not answer - which an empty
     * list does not tell apart from a repository without release tags.
     */
    static List<String> releasesOrNull(File projectRoot, ProcessRunner.Runner runner) {
        List<String> tags = new ArrayList<String>();
        try {
            ProcessRunner.Outcome listed = runner.run(projectRoot,
                    Arrays.asList("git", "tag", "--list", PREFIX + "*"), 1, null);
            if (!listed.isSuccess()) {
                return null;
            }
            for (String line : listed.getOutput()) {
                if (versionOf(line.trim()) != null) {
                    tags.add(line.trim());
                }
            }
        } catch (IOException noGit) {
            return null;
        } catch (RuntimeException noGit) {
            return null;
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
            // To a file, not through a pipe read as text: the bytes are the release's. "./"
            // makes git read the path from the folder it is run in: a project may be a folder
            // of a larger repository, and a bare path would be taken from that one's top.
            out = File.createTempFile("ontoboard-tag", ".bin");
            Process git = new ProcessBuilder("git", "show", tag + ":./" + path)
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

    /**
     * Which of these release tags hold a file, each with the file as git names it - its blob's
     * id, which changes when the file does and never otherwise. Empty where there is no
     * repository, or no git.
     *
     * <p>One git process for all of them ({@code git cat-file --batch-check}), where asking tag by
     * tag would start one each: a project with seventy releases would start seventy.
     *
     * @param path from the project's root, with {@code /}
     * @return tag to blob id, in the order the tags were given
     */
    public static Map<String, String> holding(File projectRoot, List<String> tags, String path) {
        Map<String, String> held = heldOrNull(projectRoot, tags, path);
        return held == null ? new LinkedHashMap<String, String>() : held;
    }

    /**
     * {@link #holding}, or null when git could not be asked or did not answer every question -
     * which an empty map does not tell apart from tags that hold no such file.
     */
    static Map<String, String> heldOrNull(File projectRoot, List<String> tags, String path) {
        Map<String, String> held = new LinkedHashMap<String, String>();
        List<String> asked = new ArrayList<String>();
        for (String tag : tags) {
            if (versionOf(tag) != null) {
                asked.add(tag);
            }
        }
        if (asked.isEmpty() || path.startsWith("-") || path.contains(":")
                || path.contains("\n") || path.contains("\r")) {
            return held;
        }
        StringBuilder questions = new StringBuilder();
        for (String tag : asked) {
            // refs/tags/, so a branch of the same name is not what is read; "./" for a project in
            // a folder of a larger repository, as in fileAt.
            questions.append("refs/tags/").append(tag).append(":./").append(path).append('\n');
        }
        File out = null;
        try {
            out = File.createTempFile("ontoboard-tags", ".txt");
            HOLDING_ASKED.incrementAndGet();
            Process git = new ProcessBuilder("git", "cat-file", "--batch-check")
                    .directory(projectRoot)
                    .redirectOutput(out)
                    .redirectError(new File(out.getParentFile(), out.getName() + ".err"))
                    .start();
            try (OutputStream questionsTo = git.getOutputStream()) {
                questionsTo.write(questions.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!git.waitFor(60, TimeUnit.SECONDS)) {
                git.destroyForcibly();
                return null;
            }
            if (git.exitValue() != 0) {
                return null;
            }
            // One answer a line, in the order asked: "<id> blob <size>", or "<asked> missing".
            List<String> answers = Files.readAllLines(out.toPath(), StandardCharsets.UTF_8);
            if (answers.size() < asked.size()) {
                return null;
            }
            for (int at = 0; at < asked.size(); at++) {
                String[] words = answers.get(at).trim().split(" ");
                if (words.length == 3 && "blob".equals(words[1])) {
                    held.put(asked.get(at), words[0]);
                }
            }
            return held;
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
