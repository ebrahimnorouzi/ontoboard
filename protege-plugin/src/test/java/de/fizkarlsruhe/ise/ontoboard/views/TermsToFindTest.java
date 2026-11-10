package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasSearch;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * What Find looks through when nothing on the board matches - every term of the ontology, imports
 * and all, with its label - is built again only when an edit can change it (1.135.1 review). Every
 * drawing of the board threw it away, so with a query in the Find box that the board did not match,
 * each edit anywhere in Protégé read the label of every term in the imports closure again, on the
 * event thread.
 */
class TermsToFindTest {

    private static final String EGG = "https://example.org/eggboil/";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private int builds;
    private final SchemaCanvasView.TermsToFind terms = new SchemaCanvasView.TermsToFind(
            ontology -> {
                builds++;
                return OntologyProjection.everyTermWorthShowing(ontology, Imports.INCLUDED);
            });
    private OWLOntology edit;
    private OWLClass process;
    private OWLClass boiling;
    private OWLClass report;

    private OWLClass named(String id) {
        return df.getOWLClass(IRI.create(EGG + id));
    }

    private OWLAxiom label(OWLClass cls, String text) {
        return df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), cls.getIRI(),
                df.getOWLLiteral(text, "en"));
    }

    @BeforeEach
    void eggBoiling() throws Exception {
        edit = manager.createOntology(IRI.create(EGG + "eggboil.owl"));
        process = named("EGGBOIL_0000001");
        boiling = named("EGGBOIL_0000002");
        report = named("EGGBOIL_0000003");
        manager.addAxiom(edit, df.getOWLDeclarationAxiom(process));
        manager.addAxiom(edit, df.getOWLDeclarationAxiom(boiling));
        manager.addAxiom(edit, df.getOWLDeclarationAxiom(report));
        manager.addAxiom(edit, label(process, "planned process"));
        manager.addAxiom(edit, label(boiling, "egg boiling"));
        manager.addAxiom(edit, label(report, "egg boiling report"));
    }

    /** An edit as the board hears of it: applied to the ontology, then reported. */
    private void edit(OWLOntologyChange... changes) {
        List<OWLOntologyChange> list = Arrays.asList(changes);
        manager.applyChanges(list);
        terms.changed(list, edit);
    }

    /** Whether Find, with nothing on the board matching, finds the query in the ontology. */
    private boolean finds(String query) {
        return !CanvasSearch.matches(query, terms.of(edit), 1).isEmpty();
    }

    @Test
    void editsThatChangeNoTermAndNoLabelBuildNothingAgain() throws Exception {
        assertFalse(finds("dataset"));
        assertEquals(1, builds);

        // Relating two terms, and taking it back.
        edit(new AddAxiom(edit, df.getOWLSubClassOfAxiom(boiling, process)));
        assertFalse(finds("dataset"));
        edit(new RemoveAxiom(edit, df.getOWLSubClassOfAxiom(boiling, process)));
        assertFalse(finds("dataset"));
        // A definition, which Find does not read.
        edit(new AddAxiom(edit, df.getOWLAnnotationAssertionAxiom(
                df.getOWLAnnotationProperty(IRI.create("http://purl.obolibrary.org/obo/IAO_0000115")),
                report.getIRI(), df.getOWLLiteral("A report about an egg boiling."))));
        assertFalse(finds("dataset"));
        // An ontology the edit file does not import.
        OWLOntology other = manager.createOntology(IRI.create("https://example.org/other.owl"));
        List<OWLOntologyChange> elsewhere = Arrays.<OWLOntologyChange>asList(new AddAxiom(other,
                df.getOWLDeclarationAxiom(named("dataset"))));
        manager.applyChanges(elsewhere);
        terms.changed(elsewhere, edit);
        assertFalse(finds("dataset"));

        assertEquals(1, builds, "edits that change no term and no label built the list again");
    }

    @Test
    void anEditThatChangesWhatFindFindsBuildsTheListAgain() throws Exception {
        assertFalse(finds("egg boiling dataset"));
        assertEquals(1, builds);

        OWLClass dataset = named("EGGBOIL_0000004");
        edit(new AddAxiom(edit, df.getOWLDeclarationAxiom(dataset)));
        assertTrue(finds("EGGBOIL_0000004"), "a new class");
        assertEquals(2, builds);

        edit(new AddAxiom(edit, label(dataset, "egg boiling dataset")));
        assertTrue(finds("egg boiling dataset"), "its label");
        assertEquals(3, builds);

        edit(new RemoveAxiom(edit, label(dataset, "egg boiling dataset")),
                new RemoveAxiom(edit, df.getOWLDeclarationAxiom(dataset)));
        assertFalse(finds("egg boiling dataset"), "taken back with Edit > Undo");
        assertFalse(finds("EGGBOIL_0000004"));
        assertEquals(4, builds);

        // A class an axiom names first, with no declaration of its own, and the axiom undone.
        OWLClass loose = named("EGGBOIL_0000005");
        edit(new AddAxiom(edit, df.getOWLSubClassOfAxiom(loose, process)));
        assertTrue(finds("EGGBOIL_0000005"));
        assertEquals(5, builds);
        edit(new RemoveAxiom(edit, df.getOWLSubClassOfAxiom(loose, process)));
        assertFalse(finds("EGGBOIL_0000005"));
        assertEquals(6, builds);

        // An import, and a term added to the module it imports.
        OWLOntology iao = manager.createOntology(IRI.create(EGG + "eggboil/imports/iao_import.owl"));
        OWLClass textual = df.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/IAO_0000300"));
        manager.addAxiom(iao, df.getOWLDeclarationAxiom(textual));
        manager.addAxiom(iao, label(textual, "textual entity"));
        edit(new AddImport(edit, df.getOWLImportsDeclaration(iao.getOntologyID()
                .getOntologyIRI().get())));
        assertTrue(finds("textual entity"), "brought in by an import");
        assertEquals(7, builds);
        OWLClass document = df.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/IAO_0000310"));
        List<OWLOntologyChange> rebuilt = Arrays.<OWLOntologyChange>asList(
                new AddAxiom(iao, df.getOWLDeclarationAxiom(document)),
                new AddAxiom(iao, label(document, "document")));
        manager.applyChanges(rebuilt);
        terms.changed(rebuilt, edit);
        assertTrue(finds("document"), "added to the imported module");
        assertEquals(8, builds);
    }

    @Test
    void anotherOntologyIsLookedThroughAfresh() throws Exception {
        terms.of(edit);
        OWLOntology other = manager.createOntology(IRI.create("https://example.org/other.owl"));
        terms.of(other);
        assertEquals(2, builds);
        terms.of(other);
        assertEquals(2, builds);
        terms.forget();
        terms.of(other);
        assertEquals(3, builds);
    }
}
