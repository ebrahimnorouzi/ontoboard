package de.fizkarlsruhe.ise.ontoboard.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Picking the one file in a repository that a person is meant to edit.
 *
 * <p>The stake is higher than "find an ontology". An ODK repository holds {@code mwo-edit.owl},
 * which is the source of truth, and beside it {@code mwo.owl}, which the build overwrites; under
 * {@code imports/} a dozen extracted modules; under {@code releases/} every version ever
 * published. Opening any of those instead hands somebody a file whose edits will be destroyed the
 * next time {@code make} runs, with nothing at the time to suggest anything is wrong.
 */
class RepoLayoutTest {

    /** An ODK repository, with all the files that make choosing hard. */
    private static File anOdkRepository(File root) throws IOException {
        write(root, "src/ontology/mwo-edit.owl");
        write(root, "src/ontology/mwo.owl");
        write(root, "src/ontology/mwo-idranges.owl");
        write(root, "src/ontology/imports/iao_import.owl");
        write(root, "src/ontology/imports/chebi_import.owl");
        write(root, "releases/2026-01-01/mwo.owl");
        write(root, "mwo.owl");
        write(root, ".git/config");
        return root;
    }

    private static File write(File root, String path) throws IOException {
        File file = new File(root, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));
        return file;
    }

    // ---------- the ODK layout ----------

    /** The one that matters: the edit file, not the release artefact beside it. */
    @Test
    void theOdkEditFileIsChosenOverEverythingElse(@TempDir File root) throws Exception {
        anOdkRepository(root);

        File chosen = RepoLayout.ontologyIn(root, null);

        assertEquals("src/ontology/mwo-edit.owl", RepoLayout.relative(root, chosen));
    }

    /**
     * Opening one of these means editing something the build regenerates. They are offered rather
     * than hidden - a repository with nothing else must still open - but never chosen over a file
     * somebody actually edits.
     */
    @Test
    void generatedAndImportedFilesAreNeverChosenOverTheEditFile(@TempDir File root)
            throws Exception {
        anOdkRepository(root);

        List<File> candidates = RepoLayout.candidatesIn(root);
        String best = RepoLayout.relative(root, candidates.get(0));

        assertEquals("src/ontology/mwo-edit.owl", best);
        for (File candidate : candidates) {
            String path = RepoLayout.relative(root, candidate);
            assertFalse(path.startsWith(".git/"), "git internals are not ontologies: " + path);
        }
    }

    /**
     * They were skipped outright, and that was wrong. Many small vocabularies publish through
     * GitHub Pages and keep their only OWL file in docs/; skipping it meant cloning such a
     * repository and being told it contains no ontology, about a file sitting right there.
     */
    @Test
    void aRepositoryWhoseOnlyOntologyIsInDocsStillOpensIt(@TempDir File root) throws Exception {
        write(root, "docs/vocabulary.ttl");
        write(root, "README.md");

        File chosen = RepoLayout.ontologyIn(root, null);

        assertNotNull(chosen, "the ontology in docs/ was not found");
        assertEquals("docs/vocabulary.ttl", RepoLayout.relative(root, chosen));
    }

    @Test
    void aRepositoryWhoseOnlyOntologyIsAnImportModuleStillOpensIt(@TempDir File root)
            throws Exception {
        write(root, "src/ontology/imports/iao_import.owl");

        assertEquals("src/ontology/imports/iao_import.owl",
                RepoLayout.relative(root, RepoLayout.ontologyIn(root, null)));
    }

    /** But a real file always wins over one in a generated directory. */
    @Test
    void aFileOutsideTheGeneratedDirectoriesBeatsOneInside(@TempDir File root) throws Exception {
        write(root, "releases/2026-01-01/thing.owl");
        write(root, "thing.owl");

        assertEquals("thing.owl", RepoLayout.relative(root, RepoLayout.ontologyIn(root, null)));
    }

    /** Choosing build output is worth saying out loud, since editing it loses the work. */
    @Test
    void choosingAGeneratedFileIsFlaggedAsProbablyBuildOutput(@TempDir File root)
            throws Exception {
        write(root, "releases/2026-01-01/thing.owl");
        write(root, "imports/other.owl");
        File chosen = RepoLayout.ontologyIn(root, null);

        String explanation = RepoLayout.explain(root, chosen, RepoLayout.candidatesIn(root));

        assertTrue(explanation.contains("build output"), explanation);
        assertTrue(explanation.contains("losing the changes"), explanation);
    }

    /**
     * It is an ontology, it sits in src/ontology beside the edit file, and nobody edits it by
     * hand - so on a repository with an unusually named edit file it would otherwise win.
     */
    @Test
    void theIdRangesFileIsRankedLast(@TempDir File root) throws Exception {
        write(root, "src/ontology/mwo-idranges.owl");
        write(root, "src/ontology/something.owl");

        assertEquals("src/ontology/something.owl",
                RepoLayout.relative(root, RepoLayout.ontologyIn(root, null)));
    }

    @Test
    void aRepositoryWithOnlyAnIdRangesFileStillOpensIt(@TempDir File root) throws Exception {
        write(root, "src/ontology/mwo-idranges.owl");

        assertNotNull(RepoLayout.ontologyIn(root, null));
    }

    // ---------- what the user asked for wins ----------

    /** A link to a particular file is a statement about which file, not a hint. */
    @Test
    void aPathFromTheLinkBeatsTheConvention(@TempDir File root) throws Exception {
        anOdkRepository(root);

        File chosen = RepoLayout.ontologyIn(root, "src/ontology/imports/chebi_import.owl");

        assertEquals("src/ontology/imports/chebi_import.owl",
                RepoLayout.relative(root, chosen));
    }

    @Test
    void aDirectoryFromTheLinkNarrowsTheSearch(@TempDir File root) throws Exception {
        anOdkRepository(root);
        write(root, "other/other.owl");

        File chosen = RepoLayout.ontologyIn(root, "other");

        assertEquals("other/other.owl", RepoLayout.relative(root, chosen));
    }

    /**
     * A link to a file that has since moved, or to the README. The repository is still what they
     * asked to open, so falling back beats refusing.
     */
    @Test
    void aPathThatIsNoLongerThereFallsBackToTheConvention(@TempDir File root) throws Exception {
        anOdkRepository(root);

        File chosen = RepoLayout.ontologyIn(root, "src/ontology/moved-away.owl");

        assertEquals("src/ontology/mwo-edit.owl", RepoLayout.relative(root, chosen));
    }

    @Test
    void aLinkToTheReadmeFallsBackToTheConvention(@TempDir File root) throws Exception {
        anOdkRepository(root);
        write(root, "README.md");

        assertEquals("src/ontology/mwo-edit.owl",
                RepoLayout.relative(root, RepoLayout.ontologyIn(root, "README.md")));
    }

    // ---------- repositories that are not ODK ----------

    @Test
    void aFlatRepositoryWithOneOntologyOpensIt(@TempDir File root) throws Exception {
        write(root, "vocabulary.ttl");

        assertEquals("vocabulary.ttl",
                RepoLayout.relative(root, RepoLayout.ontologyIn(root, null)));
    }

    @Test
    void theShallowestFileWinsWhenNothingElseDistinguishesThem(@TempDir File root)
            throws Exception {
        write(root, "deep/deeper/deepest/a.owl");
        write(root, "b.owl");

        assertEquals("b.owl", RepoLayout.relative(root, RepoLayout.ontologyIn(root, null)));
    }

    @Test
    void anEditFileOutsideSrcOntologyIsStillPreferred(@TempDir File root) throws Exception {
        write(root, "ontology/thing-edit.owl");
        write(root, "a.owl");

        assertEquals("ontology/thing-edit.owl",
                RepoLayout.relative(root, RepoLayout.ontologyIn(root, null)));
    }

    @Test
    void obiFormatsAreRecognisedToo(@TempDir File root) throws Exception {
        write(root, "src/ontology/thing-edit.obo");

        assertNotNull(RepoLayout.ontologyIn(root, null));
    }

    @Test
    void aRepositoryWithNoOntologyGivesNothingRatherThanSomethingWrong(@TempDir File root)
            throws Exception {
        write(root, "README.md");
        write(root, "src/code.py");

        assertNull(RepoLayout.ontologyIn(root, null));
        assertTrue(RepoLayout.candidatesIn(root).isEmpty());
    }

    @Test
    void somewhereThatIsNotADirectoryGivesNothing() {
        assertNull(RepoLayout.ontologyIn(null, null));
        assertNull(RepoLayout.ontologyIn(new File("definitely-not-here"), null));
    }

    // ---------- saying which and why ----------

    /**
     * A tool that opens one of a dozen ontologies without saying which leaves somebody editing the
     * release artefact and losing the work at the next build.
     */
    @Test
    void theExplanationSaysItIsTheEditFileAndWhatTheOthersAre(@TempDir File root)
            throws Exception {
        anOdkRepository(root);
        File chosen = RepoLayout.ontologyIn(root, null);

        String explanation = RepoLayout.explain(root, chosen, RepoLayout.candidatesIn(root));

        assertTrue(explanation.contains("src/ontology/mwo-edit.owl"), explanation);
        assertTrue(explanation.toLowerCase().contains("edit file"), explanation);
        assertTrue(explanation.contains("generated"), explanation);
    }

    @Test
    void aRepositoryThatIsNotOdkIsFlaggedAsAGuess(@TempDir File root) throws Exception {
        write(root, "a.owl");
        write(root, "b.owl");
        File chosen = RepoLayout.ontologyIn(root, null);

        String explanation = RepoLayout.explain(root, chosen, RepoLayout.candidatesIn(root));

        assertTrue(explanation.contains("check it is the file you meant"), explanation);
    }

    @Test
    void oneOntologyIsSaidToBeTheOnlyOne(@TempDir File root) throws Exception {
        write(root, "a.owl");
        File chosen = RepoLayout.ontologyIn(root, null);

        assertTrue(RepoLayout.explain(root, chosen, RepoLayout.candidatesIn(root))
                .contains("the only ontology"));
    }

    @Test
    void findingNothingIsSaidPlainly(@TempDir File root) {
        assertTrue(RepoLayout.explain(root, null, java.util.Collections.<File>emptyList())
                .contains("No ontology file"));
    }
}
