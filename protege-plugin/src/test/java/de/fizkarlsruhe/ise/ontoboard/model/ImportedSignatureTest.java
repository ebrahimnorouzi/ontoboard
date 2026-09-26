package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * An OWL API caching defect, and the plugin's immunity to it.
 *
 * <p>In OWL API 4.5.29, asking an ontology for its signature <em>with</em> imports permanently changes
 * the answer it gives <em>without</em> them:
 *
 * <pre>
 *   getSignature(EXCLUDED)  -&gt; [edit#Mine]
 *   getSignature(INCLUDED)  -&gt; [edit#Mine, obo/BFO_0000002]
 *   getSignature(EXCLUDED)  -&gt; [edit#Mine, obo/BFO_0000002]      &lt;- wrong
 * </pre>
 *
 * <p>Two callers in this plugin ask opposite questions of the same ontology. The search box asks with
 * imports, because "that term is not on the board" is only useful if it is true of the ontology
 * somebody is working in. "Add all" asks without, because an ODK project importing BFO, ChEBI and the
 * relations ontology would otherwise put tens of thousands of terms on a board on one click. On the
 * first version of this work, one search made "Add all" do exactly that for the rest of the session -
 * and it was found by a test asserting the boring half of the pair, not by the feature's own tests.
 *
 * <p>So this file pins both halves: the library still behaves this way (delete these assertions when a
 * future OWL API fixes it, not before), and the plugin no longer asks the question that provokes it.
 */
class ImportedSignatureTest {

    private static final String LOCAL = "http://example.org/edit#Mine";
    private static final String IMPORTED = "http://purl.obolibrary.org/obo/BFO_0000002";

    private OWLOntology edit;

    @BeforeEach
    void anEditFileImportingAnother() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();

        IRI upstreamIri = IRI.create("http://example.org/imported");
        OWLOntology upstream = manager.createOntology(upstreamIri);
        manager.addAxiom(upstream, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(IMPORTED))));

        edit = manager.createOntology(IRI.create("http://example.org/edit"));
        manager.applyChange(new AddImport(edit, factory.getOWLImportsDeclaration(upstreamIri)));
        manager.addAxiom(edit, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(LOCAL))));
    }

    /**
     * The library's behaviour, recorded so the workaround is not removed as superstition.
     *
     * <p>If this test ever fails, OWL API has been fixed and {@code termIdentifiers} could go back to
     * one query. Until then its four queries are load-bearing.
     */
    @Test
    void owlApiStillPollutesTheExcludedSignature() {
        assertFalse(edit.getSignature(Imports.EXCLUDED).toString().contains(IMPORTED),
                "before any INCLUDED call, EXCLUDED is correct");

        edit.getSignature(Imports.INCLUDED);

        assertTrue(edit.getSignature(Imports.EXCLUDED).toString().contains(IMPORTED),
                "OWL API 4.5.29 pollutes the cached EXCLUDED signature - if this fails, the library "
                        + "has been fixed and OntologyProjection.termIdentifiers can be simplified");
    }

    /** The per-kind queries, which the plugin uses instead, are not affected. */
    @Test
    void thePerKindQueriesAreNotPolluted() {
        edit.getSignature(Imports.INCLUDED);

        assertEquals(1, edit.getClassesInSignature(Imports.EXCLUDED).size(),
                edit.getClassesInSignature(Imports.EXCLUDED).toString());
    }

    /**
     * The consequence that matters: "Add all" stays local however often the board is searched.
     *
     * <p>This is the assertion the original defect failed. It is written in the order that provokes it
     * - imports first, then local - because the other order passes either way.
     */
    @Test
    void addAllStaysLocalAfterASearchHasLookedIntoTheImports() {
        OntologyProjection.everyTermWorthShowing(edit, Imports.INCLUDED);

        Set<String> offered = OntologyProjection.everythingWorthShowing(edit);

        assertTrue(offered.contains(LOCAL), offered.toString());
        assertFalse(offered.contains(IMPORTED),
                "a search must not make Add all offer every imported term: " + offered);
    }

    /** And the same for telling local terms from imported ones, which decides how they are drawn. */
    @Test
    void anImportedTermIsStillMarkedImportedAfterASearch() {
        OntologyProjection.everyTermWorthShowing(edit, Imports.INCLUDED);

        Projection projection = OntologyProjection.project(edit,
                new java.util.HashSet<String>(java.util.Arrays.asList(LOCAL, IMPORTED)));

        for (CanvasNode node : projection.getNodes()) {
            assertEquals(IMPORTED.equals(node.getId()), node.isImported(),
                    node.getId() + " was marked wrongly after a search touched the imports");
        }
        assertEquals(2, projection.getNodes().size());
    }

    @Test
    void termIdentifiersAnswersBothQuestionsCorrectly() {
        assertTrue(OntologyProjection.termIdentifiers(edit, Imports.INCLUDED).contains(IMPORTED));
        assertFalse(OntologyProjection.termIdentifiers(edit, Imports.EXCLUDED).contains(IMPORTED));
        assertTrue(OntologyProjection.termIdentifiers(edit, Imports.EXCLUDED).contains(LOCAL));
        assertTrue(OntologyProjection.termIdentifiers(null, Imports.INCLUDED).isEmpty());
    }
}
