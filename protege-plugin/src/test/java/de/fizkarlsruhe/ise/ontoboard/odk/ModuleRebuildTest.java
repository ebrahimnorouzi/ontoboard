package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.reason.ProfileCheck;
import de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.util.SimpleIRIMapper;

/**
 * A module rebuilt from its terms declares what it uses, as its file does (1.135.1, Windows study
 * rehearsal).
 *
 * <p>An extraction declares the terms it cuts, not the annotation properties their annotations use
 * nor the ones Import terms' provenance stamp adds. The OWL API writer declares those when it saves,
 * so the file has them; matching the open copy to the extraction took them out of it. A tester took
 * one term out of IAO's module, and the next test build failed with 375 "Use of undeclared
 * annotation property".
 *
 * <p>Written for both OWL API versions OntoBoard meets: 4.5.9 here and in Prot&eacute;g&eacute;
 * 5.5.0, whose writer does not declare the date the stamp uses, and 4.5.29 in Prot&eacute;g&eacute;
 * 5.6.9, whose writer does.
 */
class ModuleRebuildTest {

    private static final String OBO = "http://purl.obolibrary.org/obo/";
    private static final IRI SOURCE = IRI.create(OBO + "iao.owl");
    private static final IRI MODULE_IRI = IRI.create("http://example.org/egg/imports/iao_import.owl");
    private static final IRI REPORT = IRI.create(OBO + "IAO_0000088");
    private static final IRI CASE_REPORT = IRI.create(OBO + "IAO_0000614");
    private static final String DATE = "2026-11-09";

    @TempDir
    File root;

    private OWLOntology upstream;
    private OWLOntology edit;
    private File moduleFile;

    /**
     * An IAO in small: information content entity, document under it, report and case report
     * textual entity under document, each with a label, a definition and an editor note.
     */
    @BeforeEach
    void project() throws Exception {
        OWLOntologyManager upper = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = upper.getOWLDataFactory();
        upstream = upper.createOntology(SOURCE);
        OWLAnnotationProperty definition = df.getOWLAnnotationProperty(IRI.create(OBO + "IAO_0000115"));
        OWLAnnotationProperty editorNote = df.getOWLAnnotationProperty(IRI.create(OBO + "IAO_0000116"));
        for (OWLAnnotationProperty property : Arrays.asList(definition, editorNote)) {
            upper.addAxiom(upstream, df.getOWLDeclarationAxiom(property));
            upper.addAxiom(upstream, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    property.getIRI(), df.getOWLLiteral(property == definition
                            ? "definition" : "editor note")));
        }
        String[][] classes = {
            {"IAO_0000030", null, "information content entity"},
            {"IAO_0000310", "IAO_0000030", "document"},
            {"IAO_0000088", "IAO_0000310", "report"},
            {"IAO_0000614", "IAO_0000310", "case report textual entity"},
        };
        for (String[] row : classes) {
            OWLClass cls = df.getOWLClass(IRI.create(OBO + row[0]));
            upper.addAxiom(upstream, df.getOWLDeclarationAxiom(cls));
            if (row[1] != null) {
                upper.addAxiom(upstream, df.getOWLSubClassOfAxiom(cls,
                        df.getOWLClass(IRI.create(OBO + row[1]))));
            }
            upper.addAxiom(upstream, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    cls.getIRI(), df.getOWLLiteral(row[2])));
            upper.addAxiom(upstream, df.getOWLAnnotationAssertionAxiom(definition, cls.getIRI(),
                    df.getOWLLiteral("A " + row[2] + " is what it is.")));
            upper.addAxiom(upstream, df.getOWLAnnotationAssertionAxiom(editorNote, cls.getIRI(),
                    df.getOWLLiteral("Kept short for the test.")));
        }

        // Import terms, two terms: the cut, its stamp, and the file the writer makes of it.
        File imports = new File(root, "src/ontology/imports");
        assertTrue(imports.mkdirs());
        moduleFile = new File(imports, "iao_import.owl");
        TermExtract.Result cut = TermExtract.run(upstream, Arrays.asList(REPORT, CASE_REPORT),
                TermExtract.Method.BOT, MODULE_IRI);
        OWLOntology module = cut.getModule();
        module.getOWLOntologyManager().applyChanges(ImportProvenance.stamp(module, upstream, DATE));
        module.getOWLOntologyManager().saveOntology(module, IRI.create(moduleFile.toURI()));

        // Protégé's copy: the edit file, which declares what it uses, importing the module file.
        OWLOntologyManager protege = OWLManager.createOWLOntologyManager();
        protege.getIRIMappers().add(new SimpleIRIMapper(MODULE_IRI, IRI.create(moduleFile)));
        OWLDataFactory pdf = protege.getOWLDataFactory();
        edit = protege.createOntology(IRI.create("http://example.org/egg"));
        protege.applyChange(new AddImport(edit, pdf.getOWLImportsDeclaration(MODULE_IRI)));
        OntologySource.load(protege, MODULE_IRI);
        OWLClass eggReport = pdf.getOWLClass(IRI.create("http://example.org/egg#EGG_0001003"));
        protege.addAxiom(edit, pdf.getOWLDeclarationAxiom(eggReport));
        protege.addAxiom(edit, pdf.getOWLSubClassOfAxiom(eggReport, pdf.getOWLClass(REPORT)));
    }

    private OWLOntology open() {
        return ModuleRebuild.openFrom(edit, moduleFile);
    }

    /** Every entity a module uses, in its axioms and in its own annotations, that it does not declare. */
    private static Set<String> undeclared(OWLOntology module) {
        Set<OWLEntity> used = new java.util.LinkedHashSet<OWLEntity>(module.getSignature());
        for (OWLAnnotation annotation : module.getAnnotations()) {
            used.addAll(annotation.getSignature());
        }
        Set<String> missing = new TreeSet<String>();
        for (OWLEntity entity : used) {
            if (!entity.isBuiltIn() && !module.isDeclared(entity)) {
                missing.add(entity.getIRI().toString());
            }
        }
        return missing;
    }

    /** The annotation properties a module declares. */
    private static Set<String> declaredAnnotationProperties(OWLOntology module) {
        Set<String> declared = new TreeSet<String>();
        for (OWLAnnotationProperty property : module.getAnnotationPropertiesInSignature()) {
            if (!property.isBuiltIn() && module.isDeclared(property)) {
                declared.add(property.getIRI().toString());
            }
        }
        return declared;
    }

    /** The module as Imported terms' "Take out" and "Rebuild from the source" cut it again. */
    private static List<OWLOntologyChange> rebuild(OWLOntology open, OWLOntology upstream,
            List<IRI> terms) {
        TermExtract.Result rebuilt = ModuleRebuild.extract(upstream, terms, TermExtract.Method.BOT,
                MODULE_IRI, DATE);
        return ModuleRebuild.changesToMatch(open, rebuilt.getModule());
    }

    private static List<String> dlViolations(OWLOntology ontology) {
        List<String> found = new ArrayList<String>();
        for (ProfileCheck.Violation violation : ProfileCheck.violations(ontology, ProfileCheck.Target.DL)) {
            found.add(violation.getMessage());
        }
        return found;
    }

    @Test
    void anExtractedModuleDeclaresEverythingItUses() {
        TermExtract.Result rebuilt = ModuleRebuild.extract(upstream,
                Collections.singletonList(REPORT), TermExtract.Method.BOT, MODULE_IRI, DATE);
        assertEquals(Collections.emptySet(), undeclared(rebuilt.getModule()),
                "used in the module and not declared in it");
    }

    @Test
    void takingATermOutKeepsTheDeclarationsItsFileMakes() throws Exception {
        OWLOntology open = open();
        Set<String> declaredBefore = declaredAnnotationProperties(open);
        assertTrue(declaredBefore.contains(OBO + "IAO_0000115"), "the file declares the definition");
        assertTrue(declaredBefore.containsAll(Arrays.asList(OBO + "IAO_0000116",
                "http://purl.org/dc/terms/source", "http://purl.org/dc/terms/created")),
                "the editor note and the stamp's source and date: " + declaredBefore);
        assertEquals(Collections.emptyList(), dlViolations(edit), "in OWL 2 DL before the take-out");

        open.getOWLOntologyManager().applyChanges(rebuild(open, upstream,
                Collections.singletonList(REPORT)));

        assertFalse(open.containsClassInSignature(CASE_REPORT), "the term taken out is gone");
        assertEquals(declaredBefore, declaredAnnotationProperties(open),
                "the annotation properties are still declared");
        assertEquals(Collections.emptySet(), undeclared(open), "everything it uses is declared");
        assertEquals(Collections.emptyList(), dlViolations(edit), "the next build's DL check");

        // What Protégé has open is what its file says, read back by another manager.
        open.getOWLOntologyManager().saveOntology(open, IRI.create(moduleFile.toURI()));
        OWLOntologyManager reader = OWLManager.createOWLOntologyManager();
        OWLOntology fromFile = reader.loadOntologyFromOntologyDocument(moduleFile);
        assertEquals(new TreeSet<String>(strings(fromFile.getAxioms())),
                new TreeSet<String>(strings(open.getAxioms())), "the open copy is its file");
    }

    @Test
    void rebuildingFromTheSourceGivesBackDeclarationsACopyLost() {
        OWLOntology open = open();
        // As the released 1.135.1 left a module after Take out: its annotation properties no
        // longer declared in the open copy.
        List<OWLOntologyChange> lost = new ArrayList<OWLOntologyChange>();
        for (OWLAxiom declaration : open.getAxioms(AxiomType.DECLARATION)) {
            if (((org.semanticweb.owlapi.model.OWLDeclarationAxiom) declaration).getEntity()
                    .isOWLAnnotationProperty()) {
                lost.add(new RemoveAxiom(open, declaration));
            }
        }
        open.getOWLOntologyManager().applyChanges(lost);
        assertFalse(dlViolations(edit).isEmpty(), "broken as the tester's was");

        List<OWLOntologyChange> repair = rebuild(open, upstream, Arrays.asList(REPORT, CASE_REPORT));
        assertFalse(repair.isEmpty(), "Rebuild from the source must not say it is already up to date");
        open.getOWLOntologyManager().applyChanges(repair);

        assertEquals(Collections.emptySet(), undeclared(open), "everything it uses is declared again");
        assertEquals(Collections.emptyList(), dlViolations(edit), "the next build's DL check");
    }

    private static List<String> strings(Set<OWLAxiom> axioms) {
        List<String> out = new ArrayList<String>();
        for (OWLAxiom axiom : axioms) {
            out.add(axiom.toString());
        }
        return out;
    }
}
