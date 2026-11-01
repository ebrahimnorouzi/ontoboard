package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.axiom.EntityFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A new term named from what was typed.
 *
 * <p>A materials scientist typed "tensile test" and was refused: a space was not allowed. The
 * workaround, TensileTest, made a term with no label, under a namespace the ontology's own terms
 * did not use.
 */
class TermMinterNamingTest {

    @Test
    void wordsBecomeOneNameAndAReadyNameIsKept() {
        assertEquals("TensileTest", TermMinter.localNameFor("tensile test"));
        assertEquals("LaserPowderBedFusion", TermMinter.localNameFor(" laser-powder bed fusion "));
        assertEquals("TensileTest", TermMinter.localNameFor("TensileTest"));
        assertEquals("_3dPrinting", TermMinter.localNameFor("3d printing"));
        assertThrows(IllegalArgumentException.class, () -> TermMinter.localNameFor("  "));
        assertThrows(IllegalArgumentException.class, () -> TermMinter.localNameFor("#/"));
    }

    @Test
    void aNewTermGoesWhereTheOntologysOwnTermsAreAndIsLabelledAsTyped() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology o = manager.createOntology(IRI.create("https://example.org/materials.owl"));
        for (String name : new String[] {"Specimen", "Material", "Process"}) {
            manager.addAxiom(o, df.getOWLDeclarationAxiom(
                    df.getOWLClass(IRI.create("https://example.org/materials/" + name))));
        }
        TermMinter minter = TermMinter.forOntologyFile(null, "someone");

        IRI made = minter.mintFor(o, "tensile test");
        List<OWLOntologyChange> changes =
                minter.declare(o, made, EntityFactory.Kind.CLASS, "tensile test");

        assertEquals("https://example.org/materials/TensileTest", made.toString());
        boolean labelled = false;
        for (OWLOntologyChange change : changes) {
            labelled |= change instanceof AddAxiom
                    && change.getAxiom().toString().contains("\"tensile test\"");
        }
        assertTrue(labelled, changes.toString());
    }

    /** Numbered terms - PREFIX_0001001 - are a convention a typed name must not break. */
    @Test
    void anOntologyOfNumberedTermsGetsNamedOnesUnderItsOwnIri() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology o = manager.createOntology(IRI.create("https://example.org/m.owl"));
        for (int i = 1; i <= 3; i++) {
            manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(
                    IRI.create("http://purl.obolibrary.org/obo/M_000000" + i))));
        }

        assertEquals("https://example.org/m.owl#",
                TermMinter.namespaceForNewTerms(o));
    }
}
