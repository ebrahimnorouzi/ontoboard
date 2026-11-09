package de.fizkarlsruhe.ise.ontoboard.axiom;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/** Extending a pattern means reusing its properties, so the dialog has to offer them. */
class RelationDialogPropertiesTest {

    @Test
    void importedPropertiesAreOfferedBesideTheOntologysOwnInLabelOrder() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLObjectProperty hasInput =
                factory.getOWLObjectProperty(IRI.create("http://example.org/pattern#hasInput"));
        OWLObjectProperty measures =
                factory.getOWLObjectProperty(IRI.create("http://example.org/mine#measures"));
        OWLOntology pattern = manager.createOntology(IRI.create("http://example.org/pattern"));
        manager.applyChange(new AddAxiom(pattern, factory.getOWLDeclarationAxiom(hasInput)));
        OWLOntology mine = manager.createOntology(IRI.create("http://example.org/mine"));
        manager.applyChange(new AddImport(mine, factory.getOWLImportsDeclaration(
                IRI.create("http://example.org/pattern"))));
        manager.applyChange(new AddAxiom(mine, factory.getOWLDeclarationAxiom(measures)));

        List<OWLObjectProperty> offered = new ArrayList<OWLObjectProperty>(
                RelationDialog.propertiesOffered(mine));

        assertEquals(Arrays.asList(hasInput, measures), offered);
    }
}
