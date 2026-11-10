package de.fizkarlsruhe.ise.ontoboard.axiom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Whether a relation fits its property's domain and range, said before OK (1.135.1, Windows study
 * rehearsal). The egg guide's slips - the output drawn from the egg to the boiling, a value's
 * relation to its quality drawn the wrong way - were accepted without a word and found only by a
 * build several tasks later.
 */
class PropertyFitTest {

    private static final String OBO = "http://purl.obolibrary.org/obo/";
    private static final String PMD = "https://w3id.org/pmd/co/";
    private static final String EGG = "https://example.org/eggboil/EGGBOIL_";

    private OWLOntology edit;
    private OWLDataFactory df;

    private OWLClass cls(String iri) {
        return df.getOWLClass(IRI.create(iri));
    }

    private OWLObjectProperty property(String iri) {
        return df.getOWLObjectProperty(IRI.create(iri));
    }

    private void label(OWLOntologyManager manager, OWLOntology in, OWLEntity entity, String label) {
        manager.addAxiom(in, df.getOWLDeclarationAxiom(entity));
        manager.addAxiom(in, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), entity.getIRI(),
                df.getOWLLiteral(label)));
    }

    /** The module the patterns bring, and the participant's own classes in the edit file. */
    @BeforeEach
    void eggProject() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        df = manager.getOWLDataFactory();
        OWLOntology module = manager.createOntology(IRI.create("https://example.org/eggboil/imports/pmdco.owl"));
        label(manager, module, cls(OBO + "COB_0000035"), "completely executed planned process");
        label(manager, module, cls(OBO + "BFO_0000030"), "object");
        label(manager, module, cls(OBO + "OBI_0001933"), "value specification");
        label(manager, module, cls(OBO + "OBI_0001931"), "scalar value specification");
        label(manager, module, cls(PMD + "PMD_0020133"), "mass");
        label(manager, module, property(OBO + "OBI_0000293"), "has specified input");
        label(manager, module, property(OBO + "OBI_0000299"), "has specified output");
        label(manager, module, property(OBO + "OBI_0001927"), "specifies value of");
        label(manager, module, property(PMD + "PMD_0000077"), "specified by value");
        manager.addAxiom(module, df.getOWLSubClassOfAxiom(cls(OBO + "OBI_0001931"), cls(OBO + "OBI_0001933")));
        manager.addAxiom(module, df.getOWLObjectPropertyDomainAxiom(property(OBO + "OBI_0000293"),
                cls(OBO + "COB_0000035")));
        manager.addAxiom(module, df.getOWLObjectPropertyDomainAxiom(property(OBO + "OBI_0000299"),
                cls(OBO + "COB_0000035")));
        manager.addAxiom(module, df.getOWLObjectPropertyDomainAxiom(property(OBO + "OBI_0001927"),
                cls(OBO + "OBI_0001933")));
        manager.addAxiom(module, df.getOWLObjectPropertyRangeAxiom(property(PMD + "PMD_0000077"),
                cls(OBO + "OBI_0001933")));

        edit = manager.createOntology(IRI.create("https://example.org/eggboil/eggboil.owl"));
        manager.applyChange(new AddImport(edit, df.getOWLImportsDeclaration(
                IRI.create("https://example.org/eggboil/imports/pmdco.owl"))));
        label(manager, edit, cls(EGG + "0001000"), "egg boiling");
        label(manager, edit, cls(EGG + "0001001"), "raw egg");
        label(manager, edit, cls(EGG + "0001002"), "boiled egg");
        label(manager, edit, cls(EGG + "0001005"), "egg mass value");
        manager.addAxiom(edit, df.getOWLSubClassOfAxiom(cls(EGG + "0001000"), cls(OBO + "COB_0000035")));
        manager.addAxiom(edit, df.getOWLSubClassOfAxiom(cls(EGG + "0001001"), cls(OBO + "BFO_0000030")));
        manager.addAxiom(edit, df.getOWLSubClassOfAxiom(cls(EGG + "0001002"), cls(OBO + "BFO_0000030")));
        manager.addAxiom(edit, df.getOWLSubClassOfAxiom(cls(EGG + "0001005"), cls(OBO + "OBI_0001931")));
    }

    @Test
    void theGuidesRelationsFit() {
        assertNull(PropertyFit.warningFor(edit, EdgeAxioms.Candidate.EXISTENTIAL,
                cls(EGG + "0001000"), property(OBO + "OBI_0000293"), cls(EGG + "0001001")));
        assertNull(PropertyFit.warningFor(edit, EdgeAxioms.Candidate.EXISTENTIAL,
                cls(EGG + "0001000"), property(OBO + "OBI_0000299"), cls(EGG + "0001002")));
        assertNull(PropertyFit.warningFor(edit, EdgeAxioms.Candidate.EXISTENTIAL,
                cls(EGG + "0001005"), property(OBO + "OBI_0001927"), cls(PMD + "PMD_0020133")));
    }

    /** T3 drawn the wrong way round: the output relation from the egg to the boiling. */
    @Test
    void anOutputDrawnFromTheEggSaysWhyAndWhichWayFits() {
        String warning = PropertyFit.warningFor(edit, EdgeAxioms.Candidate.EXISTENTIAL,
                cls(EGG + "0001002"), property(OBO + "OBI_0000299"), cls(EGG + "0001000"));
        assertNotNull(warning);
        assertTrue(warning.startsWith("boiled egg is not under completely executed planned process,"
                + " the domain of has specified output."), warning);
        assertTrue(warning.contains("Drawn the other way, from egg boiling to boiled egg, it would"
                + " fit."), warning);
    }

    /** T9: specified by value points at a value, so mass cannot be at its end. */
    @Test
    void aValuesRelationDrawnTheWrongWaySaysWhichWayFits() {
        String warning = PropertyFit.warningFor(edit, EdgeAxioms.Candidate.EXISTENTIAL,
                cls(EGG + "0001005"), property(PMD + "PMD_0000077"), cls(PMD + "PMD_0020133"));
        assertNotNull(warning);
        assertTrue(warning.startsWith("mass is not under value specification, the range of"
                + " specified by value."), warning);
        assertTrue(warning.contains("from mass to egg mass value, it would fit"), warning);
    }

    @Test
    void onlyTheExistentialReadingIsChecked() {
        assertNull(PropertyFit.warningFor(edit, EdgeAxioms.Candidate.SCOPED_RANGE,
                cls(EGG + "0001002"), property(OBO + "OBI_0000299"), cls(EGG + "0001000")));
        assertNull(PropertyFit.warningFor(edit, EdgeAxioms.Candidate.EXISTENTIAL,
                cls(EGG + "0001002"), null, cls(EGG + "0001000")));
    }

    /**
     * Relating from an imported term says so (1.135.1): relating from PMDco's mass wrote an axiom
     * about PMDco's term into the participant's file, and nothing said a term of theirs was not
     * the one being changed.
     */
    @Test
    void relatingFromAnImportedTermSaysSo() {
        String note = PropertyFit.importedNote(edit, cls(PMD + "PMD_0020133"));
        assertEquals("mass comes from an import: this saves a statement about it in your"
                + " ontology.", note);
        assertNull(PropertyFit.importedNote(edit, cls(EGG + "0001005")));
    }
}
