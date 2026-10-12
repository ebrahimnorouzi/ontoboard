package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Stamping a release in whichever convention the project uses, and linking the one before it.
 *
 * <p>The dated half of this is pinned by {@link ReleaseTest} and must not move; what is new is
 * that a project publishing semantic versions gets the IRI shape it already publishes, and that
 * the release it follows is recorded so a consumer can walk the chain backwards.
 */
class ReleaseStampingTest {

    private static final IRI MWO = IRI.create("http://purls.helmholtz-metadaten.de/mwo");

    private OWLOntologyManager manager;
    private OWLOntology ontology;

    @BeforeEach
    void anOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(MWO);
    }

    private int annotationsNamed(String ending) {
        int found = 0;
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (annotation.getProperty().getIRI().toString().endsWith(ending)) {
                found++;
            }
        }
        return found;
    }

    // ---------- the semantic convention ----------

    /** The exact IRI MatWerk's published mwo.owl carries, built by the menu rather than by hand. */
    @Test
    void aSemanticReleaseIsStampedWithTheIriThatProjectAlreadyPublishes() {
        manager.applyChanges(
                Release.stamp(ontology, OntologyVersion.semantic("3.0.2"), null));

        assertEquals(IRI.create("http://purls.helmholtz-metadaten.de/mwo/3.0.2"),
                Release.versionIriOf(ontology));
        assertEquals("3.0.2", Release.versionInfoOf(ontology));
    }

    @Test
    void aDatedReleaseIsUnchangedByAnyOfThis() {
        manager.applyChanges(
                Release.stamp(ontology, OntologyVersion.dated("2026-10-12"), null));

        assertEquals(IRI.create(
                "http://purls.helmholtz-metadaten.de/mwo/releases/2026-10-12/mwo.owl"),
                Release.versionIriOf(ontology));
        assertEquals("2026-10-12", Release.versionInfoOf(ontology));
    }

    @Test
    void theOntologysOwnIriIsNotTouchedByEitherConvention() {
        manager.applyChanges(Release.stamp(ontology, OntologyVersion.of("3.0.2"), null));

        assertEquals(MWO, ontology.getOntologyID().getOntologyIRI().get());
    }

    // ---------- what it follows ----------

    /**
     * {@code owl:priorVersion} is what lets a consumer walk the chain backwards, and it is what
     * MatWerk's own custom Makefile adds
     * ({@code --link-annotation owl:priorVersion $(ONTBASE)/$(PRIOR_VERSION)}).
     */
    @Test
    void theReleaseItFollowsIsRecordedAsAnIri() {
        manager.applyChanges(Release.stamp(ontology, OntologyVersion.of("3.0.2"),
                OntologyVersion.of("3.0.1")));

        assertEquals(IRI.create("http://purls.helmholtz-metadaten.de/mwo/3.0.1"),
                Release.priorVersionOf(ontology));
    }

    @Test
    void aFirstReleaseFollowsNothing() {
        manager.applyChanges(Release.stamp(ontology, OntologyVersion.of("3.0.0"), null));

        assertNull(Release.priorVersionOf(ontology));
        assertEquals(0, annotationsNamed("priorVersion"));
    }

    /** One prior version, not a pile of them, for the same reason there is one versionInfo. */
    @Test
    void reStampingLeavesOnePriorVersionRatherThanTwo() {
        manager.applyChanges(Release.stamp(ontology, OntologyVersion.of("3.0.2"),
                OntologyVersion.of("3.0.1")));
        manager.applyChanges(Release.stamp(ontology, OntologyVersion.of("3.0.3"),
                OntologyVersion.of("3.0.2")));

        assertEquals(1, annotationsNamed("priorVersion"),
                "two priorVersion annotations: " + ontology.getAnnotations());
        assertEquals(IRI.create("http://purls.helmholtz-metadaten.de/mwo/3.0.2"),
                Release.priorVersionOf(ontology));
        assertEquals(1, annotationsNamed("versionInfo"));
    }

    /** A dated predecessor is linked in the dated shape, not the semantic one. */
    @Test
    void aDatedPredecessorIsLinkedInTheDatedShape() {
        manager.applyChanges(Release.stamp(ontology, OntologyVersion.of("2026-10-12"),
                OntologyVersion.of("2026-08-30")));

        assertEquals(IRI.create(
                "http://purls.helmholtz-metadaten.de/mwo/releases/2026-08-30/mwo.owl"),
                Release.priorVersionOf(ontology));
    }

    // ---------- reading the convention off the artefact ----------

    /**
     * The default the dialog offers. Read from the ontology rather than from a preference, so
     * that opening somebody else's project and releasing it continues their history instead of
     * starting a second one beside it.
     */
    @Test
    void theVersionAnOntologyDeclaresIsFoundFromItsVersionInfo() {
        manager.applyChange(new AddOntologyAnnotation(ontology,
                manager.getOWLDataFactory().getOWLAnnotation(
                        manager.getOWLDataFactory().getOWLAnnotationProperty(
                                IRI.create("http://www.w3.org/2002/07/owl#versionInfo")),
                        manager.getOWLDataFactory().getOWLLiteral("3.0.1"))));

        OntologyVersion found = Release.declaredVersionOf(ontology);

        assertEquals("3.0.1", found.getText());
        assertEquals(OntologyVersion.Scheme.SEMANTIC, found.getScheme());
        assertEquals("3.0.2", found.suggestNext("2026-10-12").getText());
    }

    @Test
    void anOntologyThatHasNeverBeenReleasedDeclaresNoVersion() {
        assertNull(Release.declaredVersionOf(ontology));
        assertNull(Release.declaredVersionOf(null));
    }

    /** Round trip: stamp it, then read back the scheme the next release should use. */
    @Test
    void theSchemeSurvivesAStampAndIsReadBack() {
        manager.applyChanges(Release.stamp(ontology, OntologyVersion.of("3.1.0"), null));

        OntologyVersion declared = Release.declaredVersionOf(ontology);

        assertEquals(OntologyVersion.Scheme.SEMANTIC, declared.getScheme());
        assertEquals("3.1.0", declared.getText());
    }

    @Test
    void aDatedStampReadsBackAsDated() {
        manager.applyChanges(Release.stamp(ontology, OntologyVersion.of("2026-08-30"), null));

        assertEquals(OntologyVersion.Scheme.DATE,
                Release.declaredVersionOf(ontology).getScheme());
    }

    // ---------- the release directory ----------

    @Test
    void aSemanticReleaseIsKeptInADirectoryNamedAfterIt() {
        java.io.File release = Release.releaseFile(new java.io.File("project"), "mwo", "3.0.2");

        assertEquals("mwo.owl", release.getName());
        assertEquals("3.0.2", release.getParentFile().getName());
        assertEquals("releases", release.getParentFile().getParentFile().getName());
    }

    @Test
    void aDirectoryNameThatSortsAsNeitherIsStillRefused() {
        IllegalArgumentException refused =
                org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                        () -> Release.releaseFile(new java.io.File("project"), "mwo", "final"));

        assertTrue(refused.getMessage().contains("sorted"), refused.getMessage());
    }
}
