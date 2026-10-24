package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * What two curators disagreed about, from the three versions git keeps of a conflicted file.
 *
 * <p>The shapes here are the ones that actually happen on an ODK project: two people retitle the
 * same class, one obsoletes what the other is still editing, both move a term under different
 * parents. Each is a clean merge as far as any line-based tool is concerned, because the two
 * edits are in different places in the file - and each is a decision somebody has to make.
 */
class ConflictReportTest {

    private static final String NS = "http://example.org/o#";

    private OWLOntologyManager manager;
    private OWLDataFactory factory;

    @BeforeEach
    void fresh() {
        manager = OWLManager.createOWLOntologyManager();
        factory = manager.getOWLDataFactory();
    }

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    /** An ontology with these classes declared, each carrying the given label. */
    private OWLOntology ontology(String name, String... labelled) throws Exception {
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/" + name));
        for (int at = 0; at < labelled.length; at += 2) {
            OWLClass owlClass = cls(labelled[at]);
            manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(owlClass));
            manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                    factory.getRDFSLabel(), owlClass.getIRI(),
                    factory.getOWLLiteral(labelled[at + 1])));
        }
        return ontology;
    }

    private void obsolete(OWLOntology ontology, String name) {
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(IRI.create(
                        "http://www.w3.org/2002/07/owl#deprecated")),
                cls(name).getIRI(), factory.getOWLLiteral(true)));
    }

    /** Two people retitle the same class. A line tool merges this cleanly and loses one of them. */
    @Test
    void twoSidesRelabellingOneTermIsACollision() throws Exception {
        OWLOntology ancestor = ontology("base", "Material", "Material");
        OWLOntology ours = ontology("ours", "Material", "Material Entity");
        OWLOntology theirs = ontology("theirs", "Material", "Material Thing");

        ConflictReport report = ConflictReport.between(ancestor, ours, theirs);

        assertFalse(report.isClean(), report.summary());
        assertEquals(1, report.getCollisions().size());
        ConflictReport.Collision one = report.getCollisions().get(0);
        assertEquals(ReleaseDiff.Change.RELABELLED, one.getOurs());
        assertEquals(ReleaseDiff.Change.RELABELLED, one.getTheirs());
        assertTrue(one.describe("yours", "theirs").contains("Material Entity"),
                one.describe("yours", "theirs"));
        assertTrue(one.describe("yours", "theirs").contains("Material Thing"),
                one.describe("yours", "theirs"));
    }

    /** One side retires a term the other is still editing - the worst one to merge by accident. */
    @Test
    void obsoletingWhatSomebodyElseEditedIsACollision() throws Exception {
        OWLOntology ancestor = ontology("base", "Sample", "Sample");
        OWLOntology ours = ontology("ours", "Sample", "Sample Preparation");
        OWLOntology theirs = ontology("theirs", "Sample", "Sample");
        obsolete(theirs, "Sample");

        ConflictReport report = ConflictReport.between(ancestor, ours, theirs);

        assertEquals(1, report.getCollisions().size(), report.summary());
        ConflictReport.Collision one = report.getCollisions().get(0);
        assertEquals(ReleaseDiff.Change.RELABELLED, one.getOurs());
        assertEquals(ReleaseDiff.Change.OBSOLETED, one.getTheirs());
        assertTrue(one.describe("you", "they").contains("obsoleted"),
                one.describe("you", "they"));
    }

    /**
     * The same change on both sides is agreement, not conflict.
     *
     * <p>Reporting it as a conflict would train a reader to skim the report, and the next one
     * they skim will be real.
     */
    @Test
    void theSameChangeOnBothSidesIsAgreement() throws Exception {
        OWLOntology ancestor = ontology("base", "Material", "Material");
        OWLOntology ours = ontology("ours", "Material", "Material Entity");
        OWLOntology theirs = ontology("theirs", "Material", "Material Entity");

        ConflictReport report = ConflictReport.between(ancestor, ours, theirs);

        assertTrue(report.isClean(), report.summary());
        assertEquals(1, report.getAgreements().size());
        assertTrue(report.getAgreements().get(0).isAgreement());
        assertTrue(report.getAgreements().get(0).describe("you", "they").contains("the same way"),
                report.getAgreements().get(0).describe("you", "they"));
    }

    /** A term only one side touched is not a collision: a merge takes it, and should. */
    @Test
    void aChangeOnOneSideOnlyIsNotACollision() throws Exception {
        OWLOntology ancestor = ontology("base", "A", "A", "B", "B");
        OWLOntology ours = ontology("ours", "A", "A renamed", "B", "B");
        OWLOntology theirs = ontology("theirs", "A", "A", "B", "B renamed");

        ConflictReport report = ConflictReport.between(ancestor, ours, theirs);

        assertTrue(report.isClean(), report.summary());
        assertTrue(report.getAgreements().isEmpty(), report.getAgreements().toString());
    }

    /** Two people adding the same term with different labels still disagree. */
    @Test
    void twoSidesAddingOneTermDifferentlyIsACollision() throws Exception {
        OWLOntology ancestor = ontology("base");
        OWLOntology ours = ontology("ours", "New", "Specimen");
        OWLOntology theirs = ontology("theirs", "New", "Sample");

        ConflictReport report = ConflictReport.between(ancestor, ours, theirs);

        assertEquals(1, report.getCollisions().size(), report.summary());
        assertEquals(ReleaseDiff.Change.ADDED, report.getCollisions().get(0).getOurs());
    }

    /** Nothing changed on either side is clean, and the summary says so without a number dump. */
    @Test
    void anUnchangedPairIsClean() throws Exception {
        OWLOntology ancestor = ontology("base", "A", "A");
        OWLOntology ours = ontology("ours", "A", "A");
        OWLOntology theirs = ontology("theirs", "A", "A");

        ConflictReport report = ConflictReport.between(ancestor, ours, theirs);

        assertTrue(report.isClean());
        assertTrue(report.getCollisions().isEmpty());
    }

    /** Three versions are needed; two is a different question and must not silently answer it. */
    @Test
    void itRefusesToWorkWithoutAllThreeVersions() throws Exception {
        OWLOntology one = ontology("base", "A", "A");

        assertThrows(IllegalArgumentException.class,
                () -> ConflictReport.between(null, one, one));
        assertThrows(IllegalArgumentException.class,
                () -> ConflictReport.between(one, null, one));
        assertThrows(IllegalArgumentException.class,
                () -> ConflictReport.between(one, one, null));
    }

    /**
     * TWO INDEPENDENT ADDITIONS OF ONE TERM ARE NEVER AGREEMENT, even with the same label.
     *
     * <p>{@link ReleaseDiff} records an ADDED change with an empty before and the term's
     * {@code rdfs:label} as after, and nothing else. So two people who each introduced the same
     * IRI with the same label looked agreed, while their definitions, parents and restrictions
     * went uncompared - and a term two people created separately, each believing they were
     * first, is exactly the one somebody should look at.
     */
    @Test
    void twoSidesAddingOneTermIsACollisionEvenWithTheSameLabel() throws Exception {
        OWLOntology ancestor = ontology("base");
        OWLOntology ours = ontology("ours", "New", "Specimen");
        manager.addAxiom(ours, factory.getOWLSubClassOfAxiom(cls("New"), cls("Sample")));
        OWLOntology theirs = ontology("theirs", "New", "Specimen");

        ConflictReport report = ConflictReport.between(ancestor, ours, theirs);

        assertEquals(1, report.getCollisions().size(),
                "the same label does not make two separate additions the same term: "
                        + report.summary());
        assertTrue(report.getAgreements().isEmpty(), report.getAgreements().toString());
    }

    /**
     * A SECOND CHANGE TO ONE TERM CANNOT HIDE A DISAGREEMENT ABOUT IT.
     *
     * <p>Both sides relabel Material differently AND move it under the same new parent. An
     * earlier version kept only the last change the diff reported per term, so each side reduced
     * to one identical MOVED entry, the pair compared equal, and the report announced agreement -
     * with the label disagreement absent from it altogether. Agreement is now decided on
     * everything each side did to the term.
     */
    @Test
    void aSecondChangeToOneTermDoesNotMaskADisagreement() throws Exception {
        OWLOntology ancestor = ontology("base", "Material", "Material");
        manager.addAxiom(ancestor, factory.getOWLDeclarationAxiom(cls("Entity")));

        OWLOntology ours = ontology("ours", "Material", "Material Entity");
        manager.addAxiom(ours, factory.getOWLDeclarationAxiom(cls("Entity")));
        manager.addAxiom(ours, factory.getOWLSubClassOfAxiom(cls("Material"), cls("Entity")));

        OWLOntology theirs = ontology("theirs", "Material", "Material Thing");
        manager.addAxiom(theirs, factory.getOWLDeclarationAxiom(cls("Entity")));
        manager.addAxiom(theirs, factory.getOWLSubClassOfAxiom(cls("Material"), cls("Entity")));

        ConflictReport report = ConflictReport.between(ancestor, ours, theirs);

        assertFalse(report.isClean(),
                "both sides relabelled Material differently; the shared move does not settle it: "
                        + report.summary());
        assertEquals(1, report.getCollisions().size(), report.getCollisions().toString());
    }

    /** One agreement reads as one, not as "1 were". */
    @Test
    void oneAgreementIsCountedInTheSingular() throws Exception {
        OWLOntology ancestor = ontology("base", "Material", "Material");
        OWLOntology ours = ontology("ours", "Material", "Material Entity");
        OWLOntology theirs = ontology("theirs", "Material", "Material Entity");

        String summary = ConflictReport.between(ancestor, ours, theirs).summary();

        assertTrue(summary.contains("1 was changed the same way"), summary);
    }

    /** A term reported twice by the diff is one collision, not two. */
    @Test
    void oneTermIsOneCollisionHoweverManyWaysItChanged() throws Exception {
        OWLOntology ancestor = ontology("base", "A", "A");
        OWLOntology ours = ontology("ours", "A", "A one");
        manager.addAxiom(ours, factory.getOWLSubClassOfAxiom(cls("A"), cls("Parent")));
        OWLOntology theirs = ontology("theirs", "A", "A two");

        ConflictReport report = ConflictReport.between(ancestor, ours, theirs);

        List<ConflictReport.Collision> found = report.getCollisions();
        assertEquals(1, found.size(), "one term, one decision: " + found);
    }
}
