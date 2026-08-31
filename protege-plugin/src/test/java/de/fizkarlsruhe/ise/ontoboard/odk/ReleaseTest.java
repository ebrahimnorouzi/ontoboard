package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * What makes a published file a release rather than just a copy.
 *
 * <p>A release without a version IRI is a file with the same name as last month's. Somebody who
 * imported it has no way to say which one their results came from, no way to pin it, and no way to
 * tell whether a disagreement with a colleague is about method or about which Tuesday they
 * downloaded it.
 *
 * <p>Worse than absent is wrong: a version IRI that is reused, or a dated copy that gets
 * overwritten, means the IRI resolves to something different from what it named. That is a lie a
 * consumer cannot detect.
 */
class ReleaseTest {

    private static final IRI MWO = IRI.create("http://purl.obolibrary.org/obo/mwo.owl");

    private OWLOntologyManager manager;
    private OWLOntology ontology;

    @BeforeEach
    void anOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(MWO);
    }

    // ---------- the version IRI ----------

    /** The OBO pattern exactly, so the date can be read out of the IRI without fetching it. */
    @Test
    void theVersionIriFollowsTheOboReleasePattern() {
        assertEquals(
                IRI.create("http://purl.obolibrary.org/obo/releases/2026-08-30/mwo.owl"),
                Release.versionIri(MWO, "2026-08-30"));
    }

    @Test
    void aProjectUnderItsOwnPathKeepsThatPath() {
        assertEquals(
                IRI.create("https://w3id.org/mwo/releases/2026-08-30/mwo.owl"),
                Release.versionIri(IRI.create("https://w3id.org/mwo/mwo.owl"), "2026-08-30"));
    }

    /**
     * A release directory named "today" or "30/08/2026" is one nothing can sort and nothing can
     * parse, and the mistake is invisible until somebody tries to find the previous release.
     */
    @Test
    void aDateThatIsNotIsoIsRefusedWithTheReasonThatItIsSortedAndParsed() {
        for (String bad : new String[] {"today", "30/08/2026", "2026-8-3", "", null,
                "2026-08-30T12:00:00Z"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> Release.versionIri(MWO, bad), "accepted '" + bad + "'");
            assertTrue(refused.getMessage().contains("YYYY-MM-DD"), refused.getMessage());
        }
    }

    @Test
    void anOntologyWithNoIriCannotHaveAReleaseIriDerivedFromIt() {
        assertThrows(IllegalArgumentException.class,
                () -> Release.versionIri(null, "2026-08-30"));
    }

    // ---------- stamping ----------

    @Test
    void stampingSetsBothTheVersionIriAndTheVersionInfo() {
        manager.applyChanges(Release.stamp(ontology, "2026-08-30"));

        assertEquals(IRI.create("http://purl.obolibrary.org/obo/releases/2026-08-30/mwo.owl"),
                Release.versionIriOf(ontology));
        assertEquals("2026-08-30", Release.versionInfoOf(ontology));
    }

    /** The ontology stays itself; only its version changes. */
    @Test
    void stampingDoesNotChangeTheOntologysOwnIri() {
        manager.applyChanges(Release.stamp(ontology, "2026-08-30"));

        assertEquals(MWO, ontology.getOntologyID().getOntologyIRI().get());
    }

    /**
     * Two version numbers on one ontology is not a history, it is an ambiguity - consumers get
     * whichever their parser returned first.
     */
    @Test
    void stampingTwiceLeavesOneVersionInfoRatherThanTwo() {
        manager.applyChanges(Release.stamp(ontology, "2026-01-01"));
        manager.applyChanges(Release.stamp(ontology, "2026-08-30"));

        assertEquals("2026-08-30", Release.versionInfoOf(ontology));
        int versionInfos = 0;
        for (org.semanticweb.owlapi.model.OWLAnnotation annotation : ontology.getAnnotations()) {
            if (annotation.getProperty().getIRI().toString().endsWith("versionInfo")) {
                versionInfos++;
            }
        }
        assertEquals(1, versionInfos, "two versionInfo annotations: " + ontology.getAnnotations());
    }

    /** A hand-written "0.1.0" that no target ever changes is what the scaffold shipped with. */
    @Test
    void aHandWrittenVersionInfoIsReplacedRatherThanKeptAlongside() throws Exception {
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(ontology,
                manager.getOWLDataFactory().getOWLAnnotation(
                        manager.getOWLDataFactory().getOWLAnnotationProperty(
                                org.semanticweb.owlapi.vocab.OWLRDFVocabulary.OWL_VERSION_INFO
                                        .getIRI()),
                        manager.getOWLDataFactory().getOWLLiteral("0.1.0"))));

        manager.applyChanges(Release.stamp(ontology, "2026-08-30"));

        assertEquals("2026-08-30", Release.versionInfoOf(ontology));
    }

    @Test
    void anOntologyWithNoIriIsRefusedWithSomethingToDoAboutIt() throws Exception {
        OWLOntology anonymous = OWLManager.createOWLOntologyManager().createOntology();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Release.stamp(anonymous, "2026-08-30"));

        assertTrue(refused.getMessage().contains("ontology header"), refused.getMessage());
    }

    @Test
    void anUnstampedOntologyReportsNoVersion() {
        assertNull(Release.versionIriOf(ontology));
        assertNull(Release.versionInfoOf(ontology));
        assertNull(Release.versionIriOf(null));
        assertNull(Release.versionInfoOf(null));
    }

    // ---------- the dated copy ----------

    @Test
    void theDatedCopyGoesUnderReleasesByDate(@TempDir File project) {
        File release = Release.releaseFile(project, "mwo", "2026-08-30");

        assertEquals("mwo.owl", release.getName());
        assertEquals("2026-08-30", release.getParentFile().getName());
        assertEquals("releases", release.getParentFile().getParentFile().getName());
    }

    /**
     * Re-releasing on the same date is ordinary - a mistake found an hour later. It is also the one
     * case where the dated copy is not a new file, and its version IRI is already published.
     */
    @Test
    void reReleasingOnADateThatAlreadyExistsIsFlagged(@TempDir File project) throws Exception {
        File existing = Release.releaseFile(project, "mwo", "2026-08-30");
        existing.getParentFile().mkdirs();
        Files.write(existing.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));

        String warning = Release.wouldOverwrite(project, "mwo", "2026-08-30");

        assertNotNull(warning);
        assertTrue(warning.contains("already published"), warning);
    }

    @Test
    void aFirstReleaseOnADateIsNotFlagged(@TempDir File project) {
        assertNull(Release.wouldOverwrite(project, "mwo", "2026-08-30"));
    }

    /** Yesterday's release must survive today's, or its version IRI starts lying. */
    @Test
    void adifferentDateIsADifferentFileSoTheOldOneSurvives(@TempDir File project) {
        assertTrue(!Release.releaseFile(project, "mwo", "2026-01-01")
                .equals(Release.releaseFile(project, "mwo", "2026-08-30")));
    }

    // ---------- the ontology id ----------

    @Test
    void theOntologyIdComesFromTheEditFilesName() {
        assertEquals("mwo", Release.ontologyIdFrom(new File("/p/src/ontology/mwo-edit.owl")));
        assertEquals("mwo", Release.ontologyIdFrom(new File("/p/src/ontology/mwo-edit.obo")));
        assertEquals("mwo", Release.ontologyIdFrom(new File("/p/src/ontology/mwo.owl")));
        assertEquals("my-ontology",
                Release.ontologyIdFrom(new File("/p/src/ontology/my-ontology-edit.owl")));
    }

    @Test
    void anUnnamedFileStillYieldsSomethingUsable() {
        assertEquals("ontology", Release.ontologyIdFrom(null));
    }
}
