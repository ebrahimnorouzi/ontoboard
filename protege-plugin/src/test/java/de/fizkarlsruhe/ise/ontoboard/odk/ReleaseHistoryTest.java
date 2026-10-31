package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class ReleaseHistoryTest {

    private static final String NS = "http://example.org/menu#";
    private static final OWLDataFactory DF = OWLManager.getOWLDataFactory();

    /**
     * Every release, newest first, with what each holds and what changed since the one before.
     *
     * <p>Asked for: "go through the evolution of the ontology... the statistics of what was
     * changed: added, deleted, obsoleted".
     */
    @Test
    void eachReleaseSaysWhatChangedSinceTheOneBefore(@TempDir File project) throws Exception {
        release(project, "0.1.0", declare("Pizza"), label("Pizza", "pizza"));
        release(project, "0.2.0", declare("Pizza"), label("Pizza", "a pizza"), declare("Base"),
                DF.getOWLDeclarationAxiom(DF.getOWLObjectProperty(IRI.create(NS + "hasBase"))));
        release(project, "0.10.0", declare("Pizza"), label("Pizza", "a pizza"), declare("Base"),
                DF.getOWLDeclarationAxiom(DF.getOWLObjectProperty(IRI.create(NS + "hasBase"))),
                DF.getOWLAnnotationAssertionAxiom(DF.getOWLDeprecated(), IRI.create(NS + "Base"),
                        DF.getOWLLiteral(true)),
                DF.getOWLDeclarationAxiom(DF.getOWLNamedIndividual(IRI.create(NS + "margherita"))));

        List<ReleaseHistory.Version> history = ReleaseHistory.of(project, "menu",
                Arrays.asList("0.10.0", "0.2.0", "0.1.0"), null, null, null);

        assertEquals("[0.10.0, 0.2.0, 0.1.0]", names(history), "newest first, by version");
        assertEquals("First release", history.get(2).describeChange());
        assertNull(history.get(2).getSincePrevious());
        assertEquals("+1 class, 1 relabelled, +1 property", history.get(1).describeChange());
        assertEquals("1 obsoleted, +1 individual", history.get(0).describeChange());
        assertEquals(2, history.get(0).getCounts().getClasses());
        assertEquals(1, history.get(0).getCounts().getObsolete());
        assertEquals(4, history.get(0).getCounts().getTerms());
    }

    /**
     * The working copy is compared as a release would be, its imports folded in.
     *
     * <p>A release is merged; the edit file is not. Compared as they are, every term an import
     * brings in would be reported as removed since the last release - the warning that matters
     * most, raised falsely.
     */
    @Test
    void theWorkingCopyIsComparedWithItsImportsIn(@TempDir File project) throws Exception {
        release(project, "1.0.0", declare("Pizza"), declare("Object"));

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology module = manager.createOntology(IRI.create("http://example.org/module.owl"));
        manager.addAxiom(module, declare("Object"));
        OWLOntology edit = manager.createOntology(IRI.create("http://example.org/menu.owl"));
        manager.addAxiom(edit, declare("Pizza"));
        manager.addAxiom(edit, declare("Topping"));
        manager.applyChange(new AddImport(edit,
                DF.getOWLImportsDeclaration(IRI.create("http://example.org/module.owl"))));

        List<ReleaseHistory.Version> history = ReleaseHistory.of(project, "menu",
                Arrays.asList("1.0.0"), edit, new File(project, "menu-edit.owl"), null);

        assertEquals("[Working copy, 1.0.0]", names(history));
        assertEquals("+1 class", history.get(0).describeChange(), "Object was not removed");
    }

    /**
     * A release is reasoned and the working copy is not, so the working copy is reasoned before
     * they are compared.
     *
     * <p>Seen on the pizza walkthrough: its working copy, identical to its 1.0.0 release, came
     * back "7 moved" - the parents ELK had added to the release, such as Diavola under
     * SpicyPizza, read as terms the user had moved.
     */
    @Test
    void whatTheReasonerAddedIsNotAChange(@TempDir File project) throws Exception {
        OWLAxiom spicyIsDefined = DF.getOWLEquivalentClassesAxiom(cls("Spicy"),
                DF.getOWLObjectIntersectionOf(cls("Pizza"), DF.getOWLObjectSomeValuesFrom(
                        DF.getOWLObjectProperty(IRI.create(NS + "hasTopping")), cls("Hot"))));
        OWLAxiom[] asserted = {declare("Pizza"), declare("Spicy"), declare("Hot"),
            declare("Diavola"), spicyIsDefined, DF.getOWLSubClassOfAxiom(cls("Diavola"),
                    cls("Pizza")),
            DF.getOWLSubClassOfAxiom(cls("Diavola"), DF.getOWLObjectSomeValuesFrom(
                    DF.getOWLObjectProperty(IRI.create(NS + "hasTopping")), cls("Hot")))};
        // Released as Release releases: the asserted axioms, reasoned with ELK through ROBOT.
        OWLOntologyManager releaser = OWLManager.createOWLOntologyManager();
        OWLOntology released = releaser.createOntology(IRI.create("http://example.org/menu.owl"));
        releaser.addAxioms(released, new java.util.HashSet<OWLAxiom>(Arrays.asList(asserted)));
        releaser.applyChanges(de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform.preview(released,
                de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform.Kind.REASON,
                de.fizkarlsruhe.ise.ontoboard.robot.Reasoners.DEFAULT.newFactory()).getChanges());
        org.junit.jupiter.api.Assertions.assertTrue(released.containsAxiom(
                DF.getOWLSubClassOfAxiom(cls("Diavola"), cls("Spicy"))), "the reasoner placed it");
        release(project, "1.0.0", released.getAxioms().toArray(new OWLAxiom[0]));

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology edit = manager.createOntology(IRI.create("http://example.org/menu.owl"));
        manager.addAxioms(edit, new java.util.HashSet<OWLAxiom>(Arrays.asList(asserted)));

        List<ReleaseHistory.Version> history = ReleaseHistory.of(project, "menu",
                Arrays.asList("1.0.0"), edit, new File(project, "menu-edit.owl"), null);

        assertEquals("No change to any term", history.get(0).describeChange());
        assertEquals(asserted.length, edit.getAxiomCount(),
                "the working copy itself was not touched - it was reasoned as a copy");
    }

    /** Any two versions can be compared, not only neighbours, and in either order of picking. */
    @Test
    void anyTwoVersionsCanBeCompared(@TempDir File project) throws Exception {
        release(project, "0.1.0", declare("Pizza"));
        release(project, "0.2.0", declare("Pizza"), declare("Base"));
        release(project, "0.3.0", declare("Pizza"), declare("Base"), declare("Topping"));
        List<ReleaseHistory.Version> history = ReleaseHistory.of(project, "menu",
                Arrays.asList("0.3.0", "0.2.0", "0.1.0"), null, null, null);

        ReleaseHistory.Version since = ReleaseHistory.compare(history.get(2), history.get(0),
                null);

        assertEquals("+2 classes", since.describeChange());
    }

    private static void release(File project, String version, OWLAxiom... axioms)
            throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/menu.owl"));
        manager.addAxioms(ontology, new java.util.HashSet<OWLAxiom>(Arrays.asList(axioms)));
        File file = Release.releaseFile(project, "menu", version);
        file.getParentFile().mkdirs();
        manager.saveOntology(ontology, IRI.create(file.toURI()));
    }

    private static OWLAxiom declare(String name) {
        return DF.getOWLDeclarationAxiom(cls(name));
    }

    private static OWLAxiom label(String name, String text) {
        return DF.getOWLAnnotationAssertionAxiom(DF.getRDFSLabel(), cls(name).getIRI(),
                DF.getOWLLiteral(text));
    }

    private static OWLClass cls(String name) {
        return DF.getOWLClass(IRI.create(NS + name));
    }

    private static String names(List<ReleaseHistory.Version> history) {
        StringBuilder names = new StringBuilder("[");
        for (ReleaseHistory.Version version : history) {
            names.append(names.length() == 1 ? "" : ", ").append(version.getName());
        }
        return names.append(']').toString();
    }
}
