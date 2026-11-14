package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.ImportGuard;
import de.fizkarlsruhe.ise.ontoboard.odk.MakeRun;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkBuildSettings;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkContainer;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkEngine;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkImage;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkMigration;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The migration of a 1.135.x project with nothing standing in.
 *
 * <p><b>The recorded project</b> ({@code migration/eggboil-1.135.2}), on a machine that has ODK
 * v1.6.1's image: <em>Update project files</em> carries it over with ODK's own update in ODK's
 * container, and ODK's own {@code make test_fast} then passes in that container - and passes
 * run by OntoBoard itself, without one. ({@code test_fast} and not {@code test}: the project's
 * one fetched import names an address that serves nothing, so a test that fetches it cannot
 * pass anywhere.)
 *
 * <p><b>Projects of somebody's own</b>, with {@code -Dmigration.projects=<folder>}: each folder
 * in it that holds a project of the older layout is copied, built as 1.135.x built it, carried
 * over by OntoBoard itself, and built again as ODK builds it. What came of each is written to
 * {@code target/migration-live/<name>.md}. Nothing is asserted about whether a project's own
 * content passes its checks - only that the migration succeeds, leaves an ODK project, and
 * that a second update changes nothing. The folders given are only read.
 */
class MigrationLiveTest {

    private static final String OPERATION = "Update project files";

    private static String runtime;
    private static String image;
    private static OdkContainer.Host host;

    @BeforeAll
    static void lookForTheImage() {
        runtime = MakeRun.containerRuntime(ProcessRunner.real());
        if (runtime != null) {
            image = OdkImage.pinned(OdkImage.probe(runtime, ProcessRunner.real()));
            host = OdkContainer.Host.detect(runtime, ProcessRunner.real());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void copy(File from, File into) throws IOException {
        final Path source = from.toPath();
        final Path target = into.toPath();
        try (Stream<Path> all = Files.walk(source)) {
            for (Path one : (Iterable<Path>) all::iterator) {
                String relative = source.relativize(one).toString();
                // A clone's history is not part of the project, and copying it is slow.
                if (relative.equals(".git") || relative.startsWith(".git" + File.separator)) {
                    continue;
                }
                Path there = target.resolve(relative);
                if (Files.isDirectory(one)) {
                    Files.createDirectories(there);
                } else {
                    Files.createDirectories(there.getParent());
                    Files.copy(one, there);
                }
            }
        }
    }

    private static final BooleanSupplier NEVER = new BooleanSupplier() {
        @Override
        public boolean getAsBoolean() {
            return false;
        }
    };

    /** Answers the question about other imports as a careful person would. */
    private static final class Careful implements OdkUpdateForm.Session {
        private final List<String> answered = new ArrayList<String>();

        @Override
        public boolean stopped() {
            return false;
        }

        @Override
        public boolean isOpen(File editFile) {
            return false;
        }

        /** Kept as a module of what is used; whole where nothing is used; out where unreadable. */
        @Override
        public List<ImportGuard.Decision> decide(List<ImportGuard.Finding> findings) {
            List<ImportGuard.Decision> decisions = new ArrayList<ImportGuard.Decision>();
            for (ImportGuard.Finding finding : findings) {
                ImportGuard.Decision decision = !finding.isReadable()
                        ? ImportGuard.Decision.REMOVE : finding.termsUsed() > 0
                        ? ImportGuard.Decision.MODULE : ImportGuard.Decision.WHOLE;
                decisions.add(decision);
                answered.add(finding.item().iri() + " -> " + decision
                        + (finding.isReadable() ? " (" + finding.termsUsed() + " terms used)"
                                : " (" + finding.whyUnreadable() + ")"));
            }
            return decisions;
        }

        @Override
        public String readAgain(File editFile, Map<String, File> imports) {
            return null;
        }

        @Override
        public void writtenAsItWas(File editFile) {
            // Nobody has it open.
        }
    }

    private static File editFileOf(File root) {
        File ontology = new File(root, "src/ontology");
        File config = OdkBuildSettings.yamlIn(ontology);
        String id = config.getName().substring(0, config.getName().length() - "-odk.yaml".length());
        return new File(ontology, id + "-edit.owl");
    }

    private static String tail(List<String> lines, int last) {
        return String.join("\n", lines.subList(Math.max(0, lines.size() - last), lines.size()));
    }

    // ------------------------------------------------------------------ the recorded project

    @Test
    void theRecordedProjectIsCarriedOverByOdksOwnUpdateAndItsTestPasses(@TempDir File temp)
            throws Exception {
        assumeTrue(image != null, "ODK v1.6.1's image is not on this machine");
        File module = new File(System.getProperty("basedir", System.getProperty("user.dir")));
        File root = new File(temp, "eggboil");
        copy(new File(module, "src/test/resources/odk/migration/eggboil-1.135.2"), root);

        long started = System.currentTimeMillis();
        OperationResult migrated = OdkUpdateForm.run(OPERATION, root, true,
                OdkUpdateForm.way(OdkRoute.Use.AUTOMATIC, runtime, image, host, null, null),
                ProcessRunner.real(), new Careful());
        long migrating = System.currentTimeMillis() - started;

        assertTrue(migrated.isSuccess(), migrated.getSummary() + "\n"
                + String.join("\n", migrated.getLog()));
        assertTrue(migrated.getSummary().contains("with ODK's own update, in its container"),
                migrated.getSummary());
        assertFalse(OdkMigration.isOntoBoards1x(root));
        File editFile = editFileOf(root);
        assertTrue(OdkRelease.isOdkProject(editFile));

        // ODK's own make, in ODK's container.
        started = System.currentTimeMillis();
        ProcessRunner.Outcome theirs = OdkRelease.inContainer(runtime, image, host,
                editFile.getParentFile(), ProcessRunner.real(), null)
                .run(Arrays.asList("make", "test_fast"), false);
        long odkTook = System.currentTimeMillis() - started;
        assertTrue(theirs.isSuccess(), "ODK's own make test_fast on the migrated project:\n"
                + tail(theirs.getOutput(), 30));

        // And the same by OntoBoard itself, without a container.
        started = System.currentTimeMillis();
        OdkEngine.Outcome ours = OdkEngine.run(editFile, "test_fast",
                Collections.<String, String>emptyMap(), NEVER);
        long ontoBoardTook = System.currentTimeMillis() - started;
        assertTrue(ours.isOk(), ours.getFailure() + "\n" + tail(ours.getTranscript(), 30));

        File report = new File("target/migration-live/eggboil-recorded.md");
        Files.createDirectories(report.getParentFile().toPath());
        Files.write(report.toPath(), ("# The recorded 1.135.2 project, carried over\n\n"
                + "- Update project files, ODK's own update in its container (" + image + "): "
                + migrating + " ms - " + migrated.getSummary() + "\n"
                + "- make test_fast in ODK's container: passed, " + odkTook + " ms\n"
                + "- test_fast by OntoBoard itself: passed, " + ontoBoardTook + " ms\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ somebody's own projects

    @Test
    void projectsOfSomebodysOwnAreCarriedOver(@TempDir File temp) throws Exception {
        String given = System.getProperty("migration.projects");
        assumeTrue(given != null && !given.trim().isEmpty(),
                "Carries real projects over only with -Dmigration.projects=<folder>.");
        File[] folders = new File(given.trim()).listFiles();
        assertTrue(folders != null && folders.length > 0, given + " holds no folders");
        Arrays.sort(folders);
        File reports = new File("target/migration-live");
        Files.createDirectories(reports.toPath());
        List<String> failed = new ArrayList<String>();
        int carried = 0;
        for (File original : folders) {
            if (!original.isDirectory() || !OdkMigration.isOntoBoards1x(original)) {
                continue;
            }
            carried++;
            String name = original.getName();
            StringBuilder said = new StringBuilder("# " + name + ", carried over\n\n");
            File root = new File(temp, name);
            copy(original, root);
            File editFile = editFileOf(root);

            // As 1.135.x built it - its own test, inside the JVM - it is not run any more:
            // the code that ran OntoBoard's own Makefile went with 1.153.0. What it said of
            // these projects is in the record of 2026-11-13 (the ledger, task O24).
            said.append("## Before\n\n- `test` as 1.135.x ran it, inside the JVM, is not run "
                    + "any more: the code that ran OntoBoard's own Makefile went with "
                    + "1.153.0.\n\n");

            OperationResult look = OdkUpdateForm.run(OPERATION, root, false,
                    OdkUpdateForm.way(OdkRoute.Use.NEVER_CONTAINER, null, null, null,
                            "A container is switched off", null), ProcessRunner.real(), null);
            said.append("## The look\n\n- ").append(look.getSummary()).append("\n");
            for (String line : look.getLog()) {
                if (!line.startsWith("|")) {
                    said.append("- ").append(line).append("\n");
                }
            }
            said.append("\n```yaml\n");
            for (String line : look.getLog()) {
                if (line.startsWith("| ")) {
                    said.append(line.substring(2)).append("\n");
                } else if (line.equals("|")) {
                    said.append("\n");
                }
            }
            said.append("```\n\n| File | State |\n|---|---|\n");
            for (List<String> row : look.getRows()) {
                if (!row.get(1).equals("as it is")) {
                    said.append("| ").append(row.get(0)).append(" | ").append(row.get(1))
                            .append(" |\n");
                }
            }

            Careful session = new Careful();
            long started = System.currentTimeMillis();
            OperationResult migrated = OdkUpdateForm.run(OPERATION, root, true,
                    OdkUpdateForm.way(OdkRoute.Use.NEVER_CONTAINER, null, null, null,
                            "A container is switched off", null), ProcessRunner.real(), session);
            said.append("\n## The migration (").append(System.currentTimeMillis() - started)
                    .append(" ms, by OntoBoard itself)\n\n- ").append(migrated.getStatus())
                    .append(": ").append(migrated.getSummary()).append("\n");
            for (String answer : session.answered) {
                said.append("- asked about ").append(answer).append("\n");
            }
            boolean odkProject = migrated.isSuccess() && !OdkMigration.isOntoBoards1x(root)
                    && OdkRelease.isOdkProject(editFile);
            said.append("- an ODK project afterwards: ").append(odkProject).append("\n");
            if (!odkProject) {
                failed.add(name + ": " + migrated.getSummary());
                for (String line : migrated.getLog()) {
                    said.append("  - ").append(line).append("\n");
                }
            } else {
                // The edit file as the first update left it, kept to hold a second one's
                // against, should that write it again.
                Files.copy(editFile.toPath(), new File(reports, name + "-edit-after-first.owl")
                        .toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                OperationResult second = OdkUpdateForm.run(OPERATION, root, true,
                        OdkUpdateForm.way(OdkRoute.Use.NEVER_CONTAINER, null, null, null,
                                "A container is switched off", null), ProcessRunner.real(),
                        new Careful());
                said.append("- a second update: ").append(second.getSummary()).append("\n");
                if (!second.getSummary().startsWith(OdkUpdateForm.ALREADY_UP_TO_DATE)) {
                    failed.add(name + ": a second update is not idle: " + second.getSummary());
                    for (File file : second.getFiles()) {
                        said.append("  - written again: ").append(file.getPath()).append("\n");
                    }
                    // Kept, to see what differs between the two.
                    File kept = new File(reports, name + "-second-update");
                    for (File file : second.getFiles()) {
                        Files.createDirectories(kept.toPath());
                        Files.copy(file.toPath(), new File(kept, file.getName()).toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }

                started = System.currentTimeMillis();
                OdkEngine.Outcome fast = OdkEngine.run(editFile, "test_fast",
                        Collections.<String, String>emptyMap(), NEVER);
                said.append("\n## After\n\n- ODK's `test_fast` by OntoBoard itself (")
                        .append(System.currentTimeMillis() - started).append(" ms): ")
                        .append(fast.isOk() ? "passed" : "FAILED: " + fast.getFailure())
                        .append("\n");
                if (!fast.isOk()) {
                    said.append("\n```\n").append(tail(fast.getTranscript(), 40))
                            .append("\n```\n");
                }
                if (image != null && Boolean.getBoolean("migration.container")) {
                    started = System.currentTimeMillis();
                    ProcessRunner.Outcome theirs = OdkRelease.inContainer(runtime, image, host,
                            editFile.getParentFile(), ProcessRunner.real(), null)
                            .run(Arrays.asList("make", "test_fast"), false);
                    said.append("- ODK's own `make test_fast` in its container (")
                            .append(System.currentTimeMillis() - started).append(" ms): ")
                            .append(theirs.isSuccess() ? "passed" : "FAILED").append("\n");
                    if (!theirs.isSuccess()) {
                        said.append("\n```\n").append(tail(theirs.getOutput(), 40))
                                .append("\n```\n");
                    }
                }
            }
            Files.write(new File(reports, name + ".md").toPath(),
                    said.toString().getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(carried > 0, given + " holds no project of the older layout");
        assertEquals(new ArrayList<String>(), failed);
    }
}
