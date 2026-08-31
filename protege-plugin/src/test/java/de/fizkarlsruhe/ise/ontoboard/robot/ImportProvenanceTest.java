package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.base.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Which release of an upstream ontology an import module was cut from.
 *
 * <p>The question nobody can answer about an existing project. A module holding forty ChEBI terms
 * sits in {@code src/ontology/imports/} and says nothing about which ChEBI it came from, when, or
 * how - so nobody regenerates it, because regenerating means guessing, and nobody can tell whether
 * the definitions in it are current or four years stale. The module becomes a fork of the upstream
 * that nobody decided to make.
 */
class ImportProvenanceTest {

    private static final IRI CHEBI = IRI.create("http://purl.obolibrary.org/obo/chebi.owl");
    private static final IRI CHEBI_2024 =
            IRI.create("http://purl.obolibrary.org/obo/chebi/2024-01-01/chebi.owl");
    private static final IRI CHEBI_2026 =
            IRI.create("http://purl.obolibrary.org/obo/chebi/2026-07-01/chebi.owl");
    private static final IRI MODULE =
            IRI.create("http://purl.obolibrary.org/obo/mwo/imports/chebi_import.owl");

    private OWLOntologyManager manager;

    @BeforeEach
    void aManager() {
        manager = OWLManager.createOWLOntologyManager();
    }

    private OWLOntology ontology(IRI iri, IRI version) throws Exception {
        return manager.createOntology(new OWLOntologyID(Optional.of(iri),
                version == null ? Optional.<IRI>absent() : Optional.of(version)));
    }

    private OWLOntology moduleCutFrom(OWLOntology source, String on) throws Exception {
        OWLOntology module = ontology(MODULE, null);
        manager.applyChanges(ImportProvenance.stamp(module, source, on));
        return module;
    }

    // ---------- what is recorded ----------

    /** Both, and they are not interchangeable - see the class comment on ImportProvenance. */
    @Test
    void aModuleRecordsTheUpstreamAndTheRelease() throws Exception {
        OWLOntology module = moduleCutFrom(ontology(CHEBI, CHEBI_2024), "2024-01-15");

        assertEquals(CHEBI, ImportProvenance.sourceOf(module));
        assertEquals(CHEBI_2024, ImportProvenance.cutFromOf(module));
        assertEquals("2024-01-15", ImportProvenance.extractedOnOf(module));
    }

    /**
     * The ontology IRI alone answers "what do I reload"; the version IRI alone answers "which
     * release". Only having both answers "has it moved", which is the question.
     */
    @Test
    void theReleaseIsNotWrittenWhenTheUpstreamPublishesNone() throws Exception {
        OWLOntology module = moduleCutFrom(ontology(CHEBI, null), "2024-01-15");

        assertEquals(CHEBI, ImportProvenance.sourceOf(module));
        assertNull(ImportProvenance.cutFromOf(module),
                "writing the ontology IRI here would say 'this release' about something that "
                        + "does not identify a release");
    }

    /** A file path names a directory on one machine, which is worse than saying nothing. */
    @Test
    void anAnonymousSourceRecordsNothing() throws Exception {
        OWLOntology anonymous = manager.createOntology();
        OWLOntology module = ontology(MODULE, null);

        assertTrue(ImportProvenance.stamp(module, anonymous, "2024-01-15").isEmpty());
        assertTrue(ImportProvenance.stamp(null, anonymous, "2024-01-15").isEmpty());
        assertTrue(ImportProvenance.stamp(module, null, "2024-01-15").isEmpty());
    }

    // ---------- has upstream moved ----------

    @Test
    void upstreamHavingMovedIsNoticedAndNamed() throws Exception {
        OWLOntology module = moduleCutFrom(ontology(CHEBI, CHEBI_2024), "2024-01-15");
        // The old release goes; the new one is what is open now, under the same ontology IRI.
        manager.removeOntology(module.getOWLOntologyManager()
                .getOntology(new OWLOntologyID(Optional.of(CHEBI), Optional.of(CHEBI_2024))));
        ontology(CHEBI, CHEBI_2026);

        ImportProvenance.Report report = ImportProvenance.check(module, manager);

        assertEquals(ImportProvenance.Freshness.MOVED, report.getFreshness());
        assertEquals(CHEBI_2024, report.getCutFrom());
        assertEquals(CHEBI_2026, report.getUpstreamNow());
        assertTrue(report.explain().contains("2024-01-01"), report.explain());
        assertTrue(report.explain().contains("2026-07-01"), report.explain());
    }

    /**
     * "Moved" is not "wrong". The terms taken may not have changed at all, and telling somebody
     * their project is stale every time upstream publishes teaches them to ignore the message.
     */
    @Test
    void havingMovedIsNotReportedAsAnError() throws Exception {
        OWLOntology module = moduleCutFrom(ontology(CHEBI, CHEBI_2024), "2024-01-15");
        manager.removeOntology(manager.getOntology(
                new OWLOntologyID(Optional.of(CHEBI), Optional.of(CHEBI_2024))));
        ontology(CHEBI, CHEBI_2026);

        String explanation = ImportProvenance.check(module, manager).explain();

        assertFalse(explanation.toLowerCase().contains("stale"), explanation);
        assertFalse(explanation.toLowerCase().contains("out of date"), explanation);
        assertTrue(explanation.contains("re-extract"), explanation);
    }

    @Test
    void theSameReleaseIsCurrent() throws Exception {
        OWLOntology module = moduleCutFrom(ontology(CHEBI, CHEBI_2024), "2024-01-15");

        ImportProvenance.Report report = ImportProvenance.check(module, manager);

        assertEquals(ImportProvenance.Freshness.CURRENT, report.getFreshness());
        assertFalse(report.isWorthWarningAbout());
    }

    /**
     * Downloading ChEBI to read one line of its header is not a thing to do behind somebody's
     * back, so the answer is "not checked" and it says so rather than pretending.
     */
    @Test
    void nothingIsFetchedToFindOut() throws Exception {
        OWLOntology module = moduleCutFrom(ontology(CHEBI, CHEBI_2024), "2024-01-15");
        manager.removeOntology(manager.getOntology(
                new OWLOntologyID(Optional.of(CHEBI), Optional.of(CHEBI_2024))));

        ImportProvenance.Report report = ImportProvenance.check(module, manager);

        assertEquals(ImportProvenance.Freshness.UNCHECKED, report.getFreshness());
        assertTrue(report.explain().contains("Open " + CHEBI), report.explain());
        assertFalse(report.isWorthWarningAbout(),
                "not having looked is not a finding to warn about");
    }

    /**
     * A module mistakenly given its source's IRI would otherwise find itself, compare its own
     * version to its own version, and report current forever.
     */
    @Test
    void aModuleIsNotItsOwnUpstream() throws Exception {
        OWLOntology source = ontology(CHEBI, CHEBI_2024);
        OWLOntology module = ontology(IRI.create("http://example.org/module"), null);
        manager.applyChanges(ImportProvenance.stamp(module, source, "2024-01-15"));
        manager.removeOntology(source);
        // The mistake TermExtract.run guards against for MIREOT: the module carrying the source's
        // identity. Here it is deliberate, to check the freshness answer does not go blind.
        manager.applyChange(new org.semanticweb.owlapi.model.SetOntologyID(module,
                new OWLOntologyID(Optional.of(CHEBI), Optional.of(CHEBI_2026))));

        assertEquals(ImportProvenance.Freshness.UNCHECKED,
                ImportProvenance.check(module, manager).getFreshness());
    }

    // ---------- the modules that predate all of this ----------

    /**
     * Most modules in real projects record nothing, and the advice for those has to be something
     * a person can act on rather than a complaint.
     */
    @Test
    void anUnrecordedModuleSaysWhatToDoAboutIt() throws Exception {
        ImportProvenance.Report report = ImportProvenance.check(ontology(MODULE, null), manager);

        assertEquals(ImportProvenance.Freshness.UNRECORDED, report.getFreshness());
        assertTrue(report.isWorthWarningAbout());
        assertTrue(report.explain().contains("re-extract"), report.explain());
        assertNull(report.getSource());
    }

    @Test
    void nothingToCheckIsNotAnAnswer() {
        assertNull(ImportProvenance.sourceOf(null));
        assertNull(ImportProvenance.cutFromOf(null));
        assertEquals("", ImportProvenance.extractedOnOf(null));
        assertEquals(ImportProvenance.Freshness.UNRECORDED,
                ImportProvenance.check(null, null).getFreshness());
    }

    @Test
    void everyFreshnessExplainsItself() throws Exception {
        OWLOntology module = moduleCutFrom(ontology(CHEBI, CHEBI_2024), "2024-01-15");
        for (ImportProvenance.Freshness freshness : ImportProvenance.Freshness.values()) {
            assertNotNull(freshness.name());
        }
        String explanation = ImportProvenance.check(module, manager).explain();
        assertTrue(explanation.length() > 40, explanation);
        assertTrue(explanation.contains("2024-01-15"), "the extraction date is part of the answer");
    }
}
