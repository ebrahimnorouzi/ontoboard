package de.fizkarlsruhe.ise.ontoboard.axiom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;

/**
 * The three edges that need no property, and the question of which one a pair of terms can mean.
 *
 * <p>Deciding that from the ends rather than asking is the whole design. Only one of the three is
 * ever legal for a given pair, so a menu offering all three would be offering two ways to make an
 * error - and letting somebody choose "subclass of" between an individual and a class is exactly
 * the confusion between {@code rdf:type} and {@code rdfs:subClassOf} that a diagram is supposed to
 * dispel.
 */
class HierarchyAxiomsTest {

    private static final String NS = "http://example.org/o#";

    private final OWLDataFactory factory = OWLManager.getOWLDataFactory();

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private OWLNamedIndividual individual(String name) {
        return factory.getOWLNamedIndividual(IRI.create(NS + name));
    }

    private OWLObjectProperty objectProperty(String name) {
        return factory.getOWLObjectProperty(IRI.create(NS + name));
    }

    private OWLDataProperty dataProperty(String name) {
        return factory.getOWLDataProperty(IRI.create(NS + name));
    }

    // ---------- which link a pair can mean ----------

    @Test
    void twoClassesCanOnlyMeanSubclass() {
        assertEquals(Arrays.asList(HierarchyAxioms.Kind.SUBCLASS_OF),
                HierarchyAxioms.applicableTo(cls("Dog"), cls("Mammal")));
    }

    @Test
    void anIndividualAndAClassCanOnlyMeanType() {
        assertEquals(Arrays.asList(HierarchyAxioms.Kind.TYPE),
                HierarchyAxioms.applicableTo(individual("rex"), cls("Dog")));
    }

    @Test
    void twoObjectPropertiesCanOnlyMeanSubProperty() {
        assertEquals(Arrays.asList(HierarchyAxioms.Kind.SUB_PROPERTY_OF),
                HierarchyAxioms.applicableTo(objectProperty("hasMother"),
                        objectProperty("hasParent")));
    }

    @Test
    void twoDataPropertiesCanOnlyMeanSubProperty() {
        assertEquals(Arrays.asList(HierarchyAxioms.Kind.SUB_PROPERTY_OF),
                HierarchyAxioms.applicableTo(dataProperty("birthYear"), dataProperty("year")));
    }

    /** The direction matters and is not symmetric: a class is not an instance of an individual. */
    @Test
    void aClassPointingAtAnIndividualMeansNothing() {
        assertTrue(HierarchyAxioms.applicableTo(cls("Dog"), individual("rex")).isEmpty());
    }

    @Test
    void aClassAndAPropertyHaveNoHierarchyRelationship() {
        assertTrue(HierarchyAxioms.applicableTo(cls("Dog"), objectProperty("hasParent"))
                .isEmpty());
    }

    /**
     * One relates things to things and the other things to values, so they cannot share a
     * hierarchy - and OWL 2 DL forbids it outright.
     */
    @Test
    void anObjectPropertyAndADataPropertyCannotShareAHierarchy() {
        assertTrue(HierarchyAxioms.applicableTo(objectProperty("hasParent"),
                dataProperty("birthYear")).isEmpty());
        assertTrue(HierarchyAxioms.applicableTo(dataProperty("birthYear"),
                objectProperty("hasParent")).isEmpty());
    }

    @Test
    void aTermIsNotItsOwnParent() {
        assertTrue(HierarchyAxioms.applicableTo(cls("Dog"), cls("Dog")).isEmpty());
        assertTrue(HierarchyAxioms.applicableTo(objectProperty("p"), objectProperty("p"))
                .isEmpty());
    }

    @Test
    void nothingToLinkIsNotALink() {
        assertTrue(HierarchyAxioms.applicableTo(null, cls("Dog")).isEmpty());
        assertTrue(HierarchyAxioms.applicableTo(cls("Dog"), null).isEmpty());
    }

    // ---------- the axioms ----------

    @Test
    void subclassBuildsTheOrdinaryParentLink() {
        OWLAxiom axiom = HierarchyAxioms.build(factory, HierarchyAxioms.Kind.SUBCLASS_OF,
                cls("Dog"), cls("Mammal"));

        assertEquals(factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Mammal")), axiom);
    }

    /**
     * The direction trap. The edge runs from the individual to its class and
     * {@code ClassAssertion} takes the class first, so getting it backwards produces a valid
     * axiom saying something quite different - which nothing downstream would catch.
     */
    @Test
    void typeAssertsTheIndividualIsInTheClassAndNotTheOtherWayRound() {
        OWLAxiom axiom = HierarchyAxioms.build(factory, HierarchyAxioms.Kind.TYPE,
                individual("rex"), cls("Dog"));

        assertEquals(factory.getOWLClassAssertionAxiom(cls("Dog"), individual("rex")), axiom);
    }

    @Test
    void subPropertyPicksTheObjectPropertyForm() {
        OWLAxiom axiom = HierarchyAxioms.build(factory, HierarchyAxioms.Kind.SUB_PROPERTY_OF,
                objectProperty("hasMother"), objectProperty("hasParent"));

        assertEquals(factory.getOWLSubObjectPropertyOfAxiom(objectProperty("hasMother"),
                objectProperty("hasParent")), axiom);
    }

    @Test
    void subPropertyPicksTheDataPropertyForm() {
        OWLAxiom axiom = HierarchyAxioms.build(factory, HierarchyAxioms.Kind.SUB_PROPERTY_OF,
                dataProperty("birthYear"), dataProperty("year"));

        assertEquals(factory.getOWLSubDataPropertyOfAxiom(dataProperty("birthYear"),
                dataProperty("year")), axiom);
    }

    /** Every kind the enum offers must actually build, or the menu offers a dead option. */
    @Test
    void everyKindBuildsForSomePair() {
        assertEquals(HierarchyAxioms.Kind.values().length, 3);
        for (HierarchyAxioms.Kind kind : HierarchyAxioms.Kind.values()) {
            boolean built = false;
            for (List<Object> pair : Arrays.<List<Object>>asList(
                    Arrays.<Object>asList(cls("A"), cls("B")),
                    Arrays.<Object>asList(individual("a"), cls("B")),
                    Arrays.<Object>asList(objectProperty("p"), objectProperty("q")))) {
                if (HierarchyAxioms.applicableTo(
                        (org.semanticweb.owlapi.model.OWLEntity) pair.get(0),
                        (org.semanticweb.owlapi.model.OWLEntity) pair.get(1)).contains(kind)) {
                    built = HierarchyAxioms.build(factory, kind,
                            (org.semanticweb.owlapi.model.OWLEntity) pair.get(0),
                            (org.semanticweb.owlapi.model.OWLEntity) pair.get(1)) != null;
                }
            }
            assertTrue(built, kind + " has no pair it can be built for");
        }
    }

    @Test
    void buildingSomethingTheTermsCannotMeanIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> HierarchyAxioms.build(factory, HierarchyAxioms.Kind.SUBCLASS_OF,
                        individual("rex"), cls("Dog")));
    }

    // ---------- why not, when not ----------

    /** "Nothing happened" is the worst possible answer to a deliberate gesture. */
    @Test
    void aClassPointedAtAnIndividualSaysToStartAtTheIndividual() {
        String reason = HierarchyAxioms.whyNot(cls("Dog"), individual("rex"));

        assertTrue(reason.contains("start the link at the individual"), reason);
    }

    @Test
    void mixingPropertyKindsExplainsWhyTheyCannotShareAHierarchy() {
        String reason = HierarchyAxioms.whyNot(objectProperty("hasParent"),
                dataProperty("birthYear"));

        assertTrue(reason.contains("relates things to things"), reason);
    }

    @Test
    void anUnrelatedPairIsPointedAtTheRelationDialogInstead() {
        String reason = HierarchyAxioms.whyNot(cls("Dog"), objectProperty("hasParent"));

        assertTrue(reason.contains("a class"), reason);
        assertTrue(reason.contains("an object property"), reason);
        assertTrue(reason.contains("Create relation"),
                "a user who cannot make a hierarchy link should be told what would work: "
                        + reason);
    }

    @Test
    void aTermPointedAtItselfSaysSo() {
        assertTrue(HierarchyAxioms.whyNot(cls("Dog"), cls("Dog")).contains("its own parent"));
    }

    @Test
    void thereIsNoReasonWhenTheLinkIsLegal() {
        assertNull(HierarchyAxioms.whyNot(cls("Dog"), cls("Mammal")));
        assertNull(HierarchyAxioms.whyNot(individual("rex"), cls("Dog")));
    }

    // ---------- what the user reads ----------

    @Test
    void everyKindExplainsItselfAndShowsItsLogic() {
        for (HierarchyAxioms.Kind kind : HierarchyAxioms.Kind.values()) {
            assertFalse(kind.getDisplayName().trim().isEmpty(), kind.name());
            assertFalse(kind.getDlNotation().trim().isEmpty(), kind.name());
            assertTrue(kind.getExplanation().length() > 60,
                    kind + " needs an explanation a user can act on: " + kind.getExplanation());
            assertFalse(kind.getExplanation().toLowerCase()
                            .startsWith(kind.getDisplayName().toLowerCase()),
                    kind + " restates its own name instead of explaining");
        }
    }

    /** The two the diagram exists to distinguish must not read alike. */
    @Test
    void subclassAndTypeAreDescribedAsDifferentThings() {
        assertFalse(HierarchyAxioms.Kind.SUBCLASS_OF.getExplanation()
                .equals(HierarchyAxioms.Kind.TYPE.getExplanation()));
        assertTrue(HierarchyAxioms.Kind.TYPE.getExplanation().contains("instance"),
                HierarchyAxioms.Kind.TYPE.getExplanation());
        assertTrue(HierarchyAxioms.Kind.SUBCLASS_OF.getExplanation().contains("kind of"),
                HierarchyAxioms.Kind.SUBCLASS_OF.getExplanation());
    }
}
