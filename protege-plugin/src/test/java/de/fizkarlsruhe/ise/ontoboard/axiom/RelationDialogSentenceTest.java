package de.fizkarlsruhe.ise.ontoboard.axiom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * The Create relation dialog names the property it will use, and says before OK when the relation
 * does not fit it (1.135.1, Windows study rehearsal). Its sentence read "Every boiled egg is
 * related to some egg boiling" whatever property was picked, and the egg guide's slips went
 * through without a word.
 */
class RelationDialogSentenceTest {

    private static final String OBO = "http://purl.obolibrary.org/obo/";
    private static final String EGG = "https://example.org/eggboil/EGGBOIL_";

    private OWLOntology edit;
    private OWLDataFactory df;
    private OWLClass boiling;
    private OWLClass boiled;

    private void label(OWLOntologyManager manager, OWLOntology in, OWLEntity entity, String label) {
        manager.addAxiom(in, df.getOWLDeclarationAxiom(entity));
        manager.addAxiom(in, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), entity.getIRI(),
                df.getOWLLiteral(label)));
    }

    @BeforeEach
    void eggProject() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        df = manager.getOWLDataFactory();
        OWLOntology module = manager.createOntology(IRI.create("https://example.org/eggboil/imports/m.owl"));
        OWLClass process = df.getOWLClass(IRI.create(OBO + "COB_0000035"));
        OWLClass object = df.getOWLClass(IRI.create(OBO + "BFO_0000030"));
        OWLObjectProperty input = df.getOWLObjectProperty(IRI.create(OBO + "OBI_0000293"));
        OWLObjectProperty output = df.getOWLObjectProperty(IRI.create(OBO + "OBI_0000299"));
        label(manager, module, process, "completely executed planned process");
        label(manager, module, object, "object");
        label(manager, module, input, "has specified input");
        label(manager, module, output, "has specified output");
        manager.addAxiom(module, df.getOWLObjectPropertyDomainAxiom(input, process));
        manager.addAxiom(module, df.getOWLObjectPropertyDomainAxiom(output, process));
        edit = manager.createOntology(IRI.create("https://example.org/eggboil/eggboil.owl"));
        manager.applyChange(new AddImport(edit, df.getOWLImportsDeclaration(
                IRI.create("https://example.org/eggboil/imports/m.owl"))));
        boiling = df.getOWLClass(IRI.create(EGG + "0001000"));
        boiled = df.getOWLClass(IRI.create(EGG + "0001002"));
        label(manager, edit, boiling, "egg boiling");
        label(manager, edit, boiled, "boiled egg");
        manager.addAxiom(edit, df.getOWLSubClassOfAxiom(boiling, process));
        manager.addAxiom(edit, df.getOWLSubClassOfAxiom(boiled, object));
    }

    @Test
    void theSentenceNamesTheChosenPropertyAndFollowsIt() {
        RelationDialog.Form form = RelationDialog.build(edit, boiling, boiled);
        form.propertyBox.setSelectedItem("has specified output");
        assertTrue(form.sentence.getText().contains(
                "Every egg boiling <b>has specified output</b> some boiled egg."),
                form.sentence.getText());
        form.propertyBox.setSelectedItem("has specified input");
        assertTrue(form.sentence.getText().contains("<b>has specified input</b>"),
                form.sentence.getText());
    }

    @Test
    void aRelationThatDoesNotFitIsWarnedAboutBeforeOk() {
        RelationDialog.Form wrongWay = RelationDialog.build(edit, boiled, boiling);
        wrongWay.propertyBox.setSelectedItem("has specified output");
        assertTrue(wrongWay.warning.isVisible());
        assertTrue(wrongWay.warning.getText().contains("boiled egg is not under completely executed"
                + " planned process, the domain of has specified output."), wrongWay.warning.getText());
        assertTrue(wrongWay.warning.getText().contains("from egg boiling to boiled egg, it would fit"),
                wrongWay.warning.getText());

        RelationDialog.Form right = RelationDialog.build(edit, boiling, boiled);
        right.propertyBox.setSelectedItem("has specified output");
        assertFalse(right.warning.isVisible(), right.warning.getText());
    }

    /** A warning takes room below the fields, never from them: the ODK form's rows went to 8 px. */
    @Test
    void theFieldsKeepTheirHeightWithAWarning() {
        RelationDialog.Form form = RelationDialog.build(edit, boiled, boiling);
        form.propertyBox.setSelectedItem("has specified output");
        form.panel.setSize(form.panel.getPreferredSize());
        layOut(form.panel);
        assertTrue(form.propertyBox.getHeight() >= form.propertyBox.getPreferredSize().height,
                "the property list is " + form.propertyBox.getHeight() + " px");
        assertTrue(form.warning.getHeight() >= form.warning.getPreferredSize().height,
                "the warning is " + form.warning.getHeight() + " px");
    }

    private static void layOut(Component component) {
        if (component instanceof Container) {
            ((Container) component).doLayout();
            for (Component child : ((Container) component).getComponents()) {
                layOut(child);
            }
        }
    }
}
