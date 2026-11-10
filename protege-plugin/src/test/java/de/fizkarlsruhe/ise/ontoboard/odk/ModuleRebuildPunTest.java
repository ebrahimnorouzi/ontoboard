package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.reason.ProfileCheck;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A rebuilt module declares what a pun is, as its file does (1.135.1 review).
 *
 * <p>Any IRI used as two kinds of entity was left undeclared, legal puns too. The OWL API writer
 * declares a legal pun - a class that is also an individual - both ways, so matching the open copy
 * to the rebuilt module took both declarations out of it, and the DL check then reported "Use of
 * undeclared class". Only what OWL 2 DL does not allow one IRI to be at once is left out: an object
 * property and a data property, either and an annotation property, a class and a datatype.
 */
class ModuleRebuildPunTest {

    private static final String X = "http://example.org/x#";

    @TempDir
    File where;

    private OWLOntologyManager manager;
    private OWLDataFactory df;
    private OWLOntology module;
    private OWLClass d;
    private OWLClass a;

    @BeforeEach
    void aModuleWithTwoDeclaredClasses() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        df = manager.getOWLDataFactory();
        module = manager.createOntology(IRI.create("http://example.org/egg/imports/x_import.owl"));
        d = df.getOWLClass(IRI.create(X + "D"));
        a = df.getOWLClass(IRI.create(X + "A"));
        manager.addAxiom(module, df.getOWLDeclarationAxiom(d));
        manager.addAxiom(module, df.getOWLDeclarationAxiom(a));
    }

    /** The module as its file has it: written by the OWL API, read back by another manager. */
    private OWLOntology itsFile() throws Exception {
        File file = new File(where, "x_import.owl");
        manager.saveOntology(module, IRI.create(file.toURI()));
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(file);
    }

    private static List<String> dlViolations(OWLOntology ontology) {
        List<String> found = new ArrayList<String>();
        for (ProfileCheck.Violation violation
                : ProfileCheck.violations(ontology, ProfileCheck.Target.DL)) {
            found.add(violation.getMessage());
        }
        return found;
    }

    private Set<String> declaredBy(List<OWLOntologyChange> changes) {
        Set<String> declared = new TreeSet<String>();
        for (OWLOntologyChange change : changes) {
            declared.add(change.getAxiom().toString());
        }
        return declared;
    }

    /** The reviewer's probe: C a class and an individual, declared neither way. */
    @Test
    void aClassThatIsAlsoAnIndividualIsDeclaredAsBoth() throws Exception {
        IRI c = IRI.create(X + "C");
        manager.addAxiom(module, df.getOWLSubClassOfAxiom(df.getOWLClass(c), d));
        manager.addAxiom(module, df.getOWLClassAssertionAxiom(a, df.getOWLNamedIndividual(c)));

        List<OWLOntologyChange> declarations = ModuleRebuild.declarationsMissingFrom(module);

        assertEquals(new TreeSet<String>(java.util.Arrays.asList(
                df.getOWLDeclarationAxiom(df.getOWLClass(c)).toString(),
                df.getOWLDeclarationAxiom(df.getOWLNamedIndividual(c)).toString())),
                declaredBy(declarations));
        manager.applyChanges(declarations);
        OWLOntology open = itsFile();
        List<OWLOntologyChange> match = ModuleRebuild.changesToMatch(open, module);
        assertEquals(Collections.emptyList(), match, "the open copy is what its file says");
        open.getOWLOntologyManager().applyChanges(match);
        assertEquals(Collections.emptyList(), dlViolations(open), "the next build's DL check");
    }

    /** A pun whose class is declared, as an extraction declares the terms it cuts. */
    @Test
    void aDeclaredClassUsedAsAnIndividualIsDeclaredAsOneToo() throws Exception {
        OWLClass c = df.getOWLClass(IRI.create(X + "C"));
        manager.addAxiom(module, df.getOWLDeclarationAxiom(c));
        manager.addAxiom(module, df.getOWLSubClassOfAxiom(c, d));
        manager.addAxiom(module, df.getOWLClassAssertionAxiom(a,
                df.getOWLNamedIndividual(c.getIRI())));

        manager.applyChanges(ModuleRebuild.declarationsMissingFrom(module));

        assertTrue(module.containsAxiom(df.getOWLDeclarationAxiom(
                df.getOWLNamedIndividual(c.getIRI()))), module.getAxioms().toString());
        OWLOntology open = itsFile();
        assertEquals(Collections.emptyList(), ModuleRebuild.changesToMatch(open, module),
                "the open copy is what its file says");
    }

    /**
     * What one IRI cannot be at once is still declared neither way: no declaration makes it legal,
     * and the file the writer makes of it reads back other axioms than were written.
     */
    @Test
    void anIllegalPunIsStillDeclaredNeitherWay() {
        IRI p = IRI.create(X + "p");
        IRI r = IRI.create(X + "r");
        IRI s = IRI.create(X + "s");
        IRI t = IRI.create(X + "T");
        IRI q = IRI.create(X + "q");
        // p an object property and a data property; r an object property and an annotation
        // property; s a data property and an annotation property; T a class and a datatype.
        manager.addAxiom(module, df.getOWLObjectPropertyDomainAxiom(df.getOWLObjectProperty(p), d));
        manager.addAxiom(module, df.getOWLDataPropertyDomainAxiom(df.getOWLDataProperty(p), d));
        manager.addAxiom(module, df.getOWLObjectPropertyDomainAxiom(df.getOWLObjectProperty(r), d));
        manager.addAxiom(module, df.getOWLAnnotationAssertionAxiom(df.getOWLAnnotationProperty(r),
                d.getIRI(), df.getOWLLiteral("r")));
        manager.addAxiom(module, df.getOWLDataPropertyDomainAxiom(df.getOWLDataProperty(s), d));
        manager.addAxiom(module, df.getOWLAnnotationAssertionAxiom(df.getOWLAnnotationProperty(s),
                d.getIRI(), df.getOWLLiteral("s")));
        manager.addAxiom(module, df.getOWLSubClassOfAxiom(df.getOWLClass(t), d));
        manager.addAxiom(module, df.getOWLDataPropertyRangeAxiom(df.getOWLDataProperty(q),
                df.getOWLDatatype(t)));

        Set<String> declared = declaredBy(ModuleRebuild.declarationsMissingFrom(module));

        assertEquals(Collections.singleton(
                df.getOWLDeclarationAxiom(df.getOWLDataProperty(q)).toString()), declared);
        for (IRI pun : java.util.Arrays.asList(p, r, s, t)) {
            assertFalse(declared.toString().contains(pun.toString()), declared.toString());
        }
    }
}
