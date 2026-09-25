package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The project the wizard generates must actually build.
 *
 * <p>{@link OdkScaffoldTest} asserts what the generated files <em>say</em>. This runs them. The
 * difference is not academic: three defects in the generated build survived a 30-assertion test
 * class and were found the first time anyone executed it.
 *
 * <ul>
 *   <li>A comment in {@code catalog-v001.xml} contained {@code --}, which XML forbids inside a
 *       comment. Every string assertion about the catalog passed and {@code make} died on the first
 *       robot call, because robot parses the catalog before doing anything else.
 *   <li>No robot invocation passed the catalog at all, so the entries {@code Import terms...} writes
 *       to make imports resolve offline were ignored by the build that needed them.
 *   <li>{@code prepare_release} ran a weaker gate than {@code make test}, so a release could be cut
 *       while a SPARQL check was failing.
 * </ul>
 *
 * <p>Skipped, not failed, where Docker or {@code obolibrary/odkfull} is absent - the generated
 * project targets ODK's own image, and a laptop without Docker should still be able to run the
 * suite. The receipt in {@code tools/smoke-receipt/} records where it did run.
 */
class OdkBuildTest {

    private static final String IMAGE = "obolibrary/odkfull";

    /** A full reason-report-verify cycle in a cold container; a hung docker must not hang the suite. */
    private static final int TIMEOUT_SECONDS = 600;

    private static final String LICENSE = "https://creativecommons.org/publicdomain/zero/1.0/";

    /**
     * {@code make test} passes on a project nobody has edited.
     *
     * <p>A scaffold that generates a project failing its own CI on the first commit would be worse
     * than one that generated nothing: the new maintainer's first experience is a red build they did
     * not cause. This asserts the whole gate - reason, report against the project's own 32-rule
     * profile with {@code --fail-on ERROR}, the scaffolded SPARQL check, and the OWL 2 DL profile
     * validation.
     *
     * <p>Each assertion names the output it expects rather than trusting the exit code, because a
     * target silently dropped from the {@code test} chain would still exit 0. The profile validation
     * was missing from this scaffold entirely until 1.46.0 while real ODK had always had it, so an
     * ontology could drift out of OWL 2 DL with CI staying green - and a passing build was exactly
     * what that looked like.
     */
    @Test
    void afreshProjectPassesItsOwnTestGate(@TempDir File dir) throws Exception {
        assumeTrue(imageIsAvailable(), IMAGE + " is not available, so the generated build cannot run");

        File project = scaffold(dir);
        String output = make(project, "test");

        assertTrue(output.contains("No violations found."),
                "robot report must find nothing wrong with a freshly generated project: " + output);
        assertTrue(output.contains("PASS Rule"),
                "the scaffolded SPARQL check must run and pass: " + output);
        assertTrue(output.contains("validate-profile"),
                "make test must validate the OWL 2 DL profile, as real ODK's test target does - "
                        + "otherwise an ontology can leave DL with CI green: " + output);
        // robot validate-profile writes its verdict to the output file, not to stdout - the recipe
        // only cats it when the command fails. So the file is where the answer is, and asserting on
        // stdout would have passed whether the ontology was in profile or not.
        File verdict = new File(new File(new File(project, "src"), "ontology"),
                "validate-profile.txt");
        assertTrue(verdict.isFile(), "validate-profile wrote no report: " + output);
        String text = new String(java.nio.file.Files.readAllBytes(verdict.toPath()),
                java.nio.charset.Charset.forName("UTF-8"));
        assertTrue(text.contains("in profile"),
                "a freshly generated project must be in OWL 2 DL: " + text);
    }

    /**
     * {@code make prepare_release} produces a dated, version-stamped release.
     *
     * <p>And runs the full gate first: the dependency is on {@code test}, so a failing SPARQL check
     * stops the release rather than being skipped by it.
     */
    @Test
    void prepareReleaseWritesADatedVersionStampedRelease(@TempDir File dir) throws Exception {
        assumeTrue(imageIsAvailable(), IMAGE + " is not available, so the generated build cannot run");

        File project = scaffold(dir);
        String output = make(project, "prepare_release");

        assertTrue(output.contains("PASS Rule"),
                "prepare_release must run the SPARQL check, not skip it: " + output);

        File releases = new File(project, "releases");
        List<File> released = new ArrayList<File>();
        collect(releases, released);
        assertTrue(!released.isEmpty(), "no release was written under " + releases);

        File release = released.get(0);
        String owl = new String(Files.readAllBytes(release.toPath()), StandardCharsets.UTF_8);
        assertTrue(owl.contains("versionIRI"),
                "a release with no owl:versionIRI cannot be cited or pinned: " + release);
        assertTrue(release.getParentFile().getName().matches("\\d{4}-\\d{2}-\\d{2}"),
                "the release directory must be dated, or each release overwrites the last: "
                        + release.getParentFile().getName());
        assertTrue(!owl.contains(".owl/releases/"),
                "the version IRI must be built from the base IRI's stem, not from a base ending in "
                        + ".owl, or one release carries two spellings of its own name");
    }

    // ===================================================================================== running

    private static File scaffold(File dir) {
        OdkProjectConfig config = new OdkProjectConfig("demo", "Demo Ontology",
                "A scaffolded project used to check that the generated build actually runs.",
                "http://example.org/demo.owl", LICENSE, dir);
        OdkScaffold.create(config);
        return config.getProjectRoot();
    }

    /** Runs one make target in the ODK image, from the directory the Makefile lives in. */
    private static String make(File project, String target) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add("docker");
        command.add("run");
        command.add("--rm");
        command.add("-v");
        command.add(project.getAbsolutePath() + ":/work");
        command.add("-w");
        command.add("/work/src/ontology");
        command.add(IMAGE);
        command.add("make");
        command.add(target);
        return run(command);
    }

    private static void collect(File root, List<File> into) {
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, into);
            } else {
                into.add(child);
            }
        }
    }

    private static boolean imageIsAvailable() {
        try {
            List<String> command = new ArrayList<String>();
            command.add("docker");
            command.add("image");
            command.add("inspect");
            command.add(IMAGE);
            run(command);
            return true;
        } catch (Exception notThere) {
            return false;
        }
    }

    /** Runs a command, returning its combined output and failing loudly on a non-zero exit. */
    private static String run(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        InputStream out = process.getInputStream();
        byte[] chunk = new byte[8192];
        for (int read = out.read(chunk); read >= 0; read = out.read(chunk)) {
            captured.write(chunk, 0, read);
        }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("timed out after " + TIMEOUT_SECONDS + "s: " + command);
        }
        String output = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            // make's own output is the diagnosis - which robot call failed, and why.
            throw new IOException("the generated build failed (" + command.get(command.size() - 1)
                    + ", exit " + process.exitValue() + "):\n" + output);
        }
        return output;
    }
}
