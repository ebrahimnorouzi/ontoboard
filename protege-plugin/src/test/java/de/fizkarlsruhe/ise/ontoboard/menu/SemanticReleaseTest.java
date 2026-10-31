package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion;
import de.fizkarlsruhe.ise.ontoboard.odk.Release;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Releasing a project that versions semantically, which is what MatWerk does.
 *
 * <p>Its {@code src/ontology/release.sh} sets {@code VERSION=3.0.2} and
 * {@code PRIOR_VERSION=3.0.1}; the published {@code mwo.owl} carries
 * {@code owl:versionIRI .../mwo/3.0.2}. Everything here is measured against those strings rather
 * than against a convention invented for the test.
 */
class SemanticReleaseTest {

    private static void aRelease(File projectRoot, String version, String id) throws Exception {
        File file = Release.releaseFile(projectRoot, id, version);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));
    }

    // ---------- finding the predecessor ----------

    @Test
    void theLastSemanticReleaseIsFound(@TempDir File project) throws Exception {
        aRelease(project, "3.0.0", "mwo");
        aRelease(project, "3.0.1", "mwo");

        assertEquals("3.0.1", ReleaseAction.previousRelease(project, "mwo", "3.0.2"));
    }

    /**
     * The ordering trap. As text, 3.9.0 sorts after 3.10.0, so a project on its tenth minor
     * release would diff against 3.9.0 and report everything added since then as new again -
     * and, with the check on, refuse the release over terms that never disappeared.
     */
    @Test
    void theTenthMinorReleaseFollowsTheNinthRatherThanPrecedingIt(@TempDir File project)
            throws Exception {
        aRelease(project, "3.9.0", "mwo");
        aRelease(project, "3.10.0", "mwo");

        assertEquals("3.10.0", ReleaseAction.previousRelease(project, "mwo", "3.11.0"));
    }

    /**
     * A project with both histories has two, and they are not two points on one line. Diffing
     * across them would compare things that were never consecutive.
     */
    @Test
    void aSemanticReleaseDoesNotFollowADatedOne(@TempDir File project) throws Exception {
        aRelease(project, "2026-01-01", "mwo");
        aRelease(project, "2026-06-15", "mwo");

        assertNull(ReleaseAction.previousRelease(project, "mwo", "3.0.0"));
    }

    @Test
    void aDatedReleaseDoesNotFollowASemanticOne(@TempDir File project) throws Exception {
        aRelease(project, "3.0.1", "mwo");

        assertNull(ReleaseAction.previousRelease(project, "mwo", "2026-10-12"));
    }

    @Test
    void eachConventionFindsItsOwnPredecessorInAProjectThatHasBoth(@TempDir File project)
            throws Exception {
        aRelease(project, "2026-01-01", "mwo");
        aRelease(project, "3.0.1", "mwo");

        assertEquals("3.0.1", ReleaseAction.previousRelease(project, "mwo", "3.0.2"));
        assertEquals("2026-01-01", ReleaseAction.previousRelease(project, "mwo", "2026-10-12"));
    }

    @Test
    void aPreReleaseComesBeforeTheReleaseItLeadsTo(@TempDir File project) throws Exception {
        aRelease(project, "3.1.0-rc1", "mwo");

        assertEquals("3.1.0-rc1", ReleaseAction.previousRelease(project, "mwo", "3.1.0"));
    }

    @Test
    void aVersionNothingCanParseHasNoPredecessor(@TempDir File project) throws Exception {
        aRelease(project, "3.0.1", "mwo");

        assertNull(ReleaseAction.previousRelease(project, "mwo", "final"));
    }

    /** Offered for comparison, newest first, in either convention. */
    @Test
    void semanticReleasesAreOfferedForComparisonNewestFirst(@TempDir File project)
            throws Exception {
        File ontology = new File(project, "src/ontology/mwo-edit.owl");
        ontology.getParentFile().mkdirs();
        Files.write(ontology.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));
        aRelease(project, "3.9.0", "mwo");
        aRelease(project, "3.10.0", "mwo");

        List<String> offered = CompareReleasesAction.releasesOf(ontology);

        assertEquals("3.10.0", offered.get(0),
                "listing only dated directories made a semantic project look unreleased");
        assertEquals(2, offered.size());
    }

    // ---------- the form ----------

    private static Map<String, String> typed(String scheme, String version) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("scheme", scheme);
        values.put("date", version);
        return values;
    }

    @Test
    void aVersionInTheChosenConventionIsAccepted() {
        assertTrue(ReleaseAction.versionProblems(
                typed(OntologyVersion.Scheme.SEMANTIC.getLabel(), "3.0.2")).isEmpty());
        assertTrue(ReleaseAction.versionProblems(
                typed(OntologyVersion.Scheme.DATE.getLabel(), "2026-10-12")).isEmpty());
    }

    /**
     * The cross-field rule. Dated and semantic publish under different IRIs, so a semantic
     * version typed under the dated convention would publish
     * {@code .../releases/3.0.2/mwo.owl} - a shape belonging to neither history, under an IRI
     * that cannot be taken back.
     */
    @Test
    void aVersionWrittenInTheOtherConventionIsRefusedWithBothNamed() {
        List<String> problems = ReleaseAction.versionProblems(
                typed(OntologyVersion.Scheme.DATE.getLabel(), "3.0.2"));

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("3.0.2"), problems.get(0));
        assertTrue(problems.get(0).contains("semantic version"), problems.get(0));
    }

    @Test
    void aDateTypedUnderTheSemanticConventionIsRefusedToo() {
        List<String> problems = ReleaseAction.versionProblems(
                typed(OntologyVersion.Scheme.SEMANTIC.getLabel(), "2026-10-12"));

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("a date"), problems.get(0));
    }

    @Test
    void aVersionThatIsNeitherShapeSaysSoAndOffersBothExamples() {
        List<String> problems = ReleaseAction.versionProblems(
                typed(OntologyVersion.Scheme.SEMANTIC.getLabel(), "final"));

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("2026-10-12"), problems.get(0));
        assertTrue(problems.get(0).contains("1.2.0"), problems.get(0));
    }

    @Test
    void anEmptyVersionIsRefusedRatherThanThrowing() {
        assertEquals(1, ReleaseAction.versionProblems(
                typed(OntologyVersion.Scheme.DATE.getLabel(), "")).size());
        assertEquals(1, ReleaseAction.versionProblems(
                typed(OntologyVersion.Scheme.DATE.getLabel(), null)).size());
    }

    // ---------- the project's own release script ----------

    private static File aScript(File ontologyDir, String body) throws Exception {
        ontologyDir.mkdirs();
        File script = new File(ontologyDir, "release.sh");
        Files.write(script.toPath(), body.getBytes("UTF-8"));
        return script;
    }

    /**
     * Two release paths with two versions is how a project publishes 3.0.2 twice or skips it,
     * and each path is internally consistent so neither notices.
     */
    @Test
    void aReleaseScriptNamingADifferentVersionIsReported(@TempDir File project) throws Exception {
        File ontologyDir = new File(project, "src/ontology");
        aScript(ontologyDir, "set -e\n\nVERSION=3.0.2\nPRIOR_VERSION=3.0.1\n");
        File editFile = new File(ontologyDir, "mwo-edit.owl");

        String warning =
                ReleaseAction.whatReleaseScriptSays(editFile, OntologyVersion.of("3.0.3"));

        assertTrue(warning != null && warning.contains("VERSION=3.0.2"), String.valueOf(warning));
        assertTrue(warning.contains("3.0.3"), warning);
    }

    @Test
    void aReleaseScriptThatAgreesSaysNothing(@TempDir File project) throws Exception {
        File ontologyDir = new File(project, "src/ontology");
        aScript(ontologyDir, "VERSION=3.0.2\nPRIOR_VERSION=3.0.1\n");

        assertNull(ReleaseAction.whatReleaseScriptSays(new File(ontologyDir, "mwo-edit.owl"),
                OntologyVersion.of("3.0.2")));
    }

    @Test
    void aProjectWithNoReleaseScriptSaysNothing(@TempDir File project) throws Exception {
        new File(project, "src/ontology").mkdirs();

        assertNull(ReleaseAction.whatReleaseScriptSays(
                new File(project, "src/ontology/mwo-edit.owl"), OntologyVersion.of("3.0.2")));
        assertNull(ReleaseAction.whatReleaseScriptSays(null, OntologyVersion.of("3.0.2")));
    }

    /** A script whose VERSION is itself a variable is not a disagreement about anything. */
    @Test
    void aScriptedVersionThatIsAVariableIsNotADisagreement(@TempDir File project)
            throws Exception {
        File ontologyDir = new File(project, "src/ontology");
        aScript(ontologyDir, "VERSION=$1\n");

        assertNull(ReleaseAction.whatReleaseScriptSays(new File(ontologyDir, "mwo-edit.owl"),
                OntologyVersion.of("3.0.2")));
    }

    @Test
    void aQuotedVersionIsReadWithoutItsQuotes(@TempDir File project) throws Exception {
        File ontologyDir = new File(project, "src/ontology");
        aScript(ontologyDir, "VERSION=\"3.0.2\"\n");

        assertNull(ReleaseAction.whatReleaseScriptSays(new File(ontologyDir, "mwo-edit.owl"),
                OntologyVersion.of("3.0.2")));
    }

    /** The last assignment wins, as it would when the script runs. */
    @Test
    void theLastAssignmentIsTheOneThatCounts(@TempDir File project) throws Exception {
        File ontologyDir = new File(project, "src/ontology");
        aScript(ontologyDir, "VERSION=3.0.1\nVERSION=3.0.2\n");

        assertNull(ReleaseAction.whatReleaseScriptSays(new File(ontologyDir, "mwo-edit.owl"),
                OntologyVersion.of("3.0.2")));
    }

    /** It reads the real MatWerk script if this machine has the project checked out. */
    @Test
    void theRealMatWerkScriptIsReadTheSameWay() throws Exception {
        File editFile = new File("C:/Users/eno/ontology-projects/mwo/src/ontology/mwo-edit.owl");
        if (!new File(editFile.getParentFile(), "release.sh").isFile()) {
            return;
        }
        String declared = new String(Files.readAllBytes(
                new File(editFile.getParentFile(), "release.sh").toPath()),
                Charset.forName("UTF-8"));
        assertTrue(declared.contains("VERSION="), "the script changed shape: " + declared);
        // Whatever it says, releasing exactly that version must raise no disagreement, and
        // releasing something else must.
        String version = null;
        for (String line : declared.split("\n")) {
            if (line.trim().startsWith("VERSION=")) {
                version = line.trim().substring("VERSION=".length()).trim();
            }
        }
        OntologyVersion scripted = OntologyVersion.parseOrNull(version);
        if (scripted == null) {
            return;
        }
        assertNull(ReleaseAction.whatReleaseScriptSays(editFile, scripted));
        assertTrue(ReleaseAction.whatReleaseScriptSays(editFile, scripted.nextMajor()) != null);
    }
}
