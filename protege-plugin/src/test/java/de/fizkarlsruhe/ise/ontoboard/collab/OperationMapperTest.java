package de.fizkarlsruhe.ise.ontoboard.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import java.util.LinkedHashMap;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;

/**
 * The mapping between OWL axioms and the web application's operation vocabulary.
 *
 * <p>Worth testing exhaustively because every failure mode here is quiet. A mapping that produces
 * the wrong axiom form does not throw - it writes a different ontology than the user drew, and
 * the difference (an existential restriction where a universal one belongs) is invisible until a
 * reasoner disagrees. A mapping that produces nothing does not throw either; the edit simply
 * never reaches the other person.
 *
 * <p>So the two properties asserted hardest are: <em>symmetry</em>, that what goes out comes back
 * as the same axiom, and <em>honesty</em>, that anything unmappable says so with a reason naming
 * what did not travel.
 */
class OperationMapperTest {

    private static final String NS = "http://example.org/o#";
    private static final String USER = "alice";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void freshOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        factory = manager.getOWLDataFactory();
    }

    // ---------- fixture helpers ----------

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private OWLNamedIndividual individual(String name) {
        return factory.getOWLNamedIndividual(IRI.create(NS + name));
    }

    private OWLObjectProperty property(String name) {
        return factory.getOWLObjectProperty(IRI.create(NS + name));
    }

    /** Puts {@code axiom} in the ontology and returns the change that did it. */
    private OWLOntologyChange added(OWLAxiom axiom) {
        manager.addAxiom(ontology, axiom);
        return new AddAxiom(ontology, axiom);
    }

    /** The change that would retract {@code axiom}, without applying it. */
    private OWLOntologyChange removed(OWLAxiom axiom) {
        return new RemoveAxiom(ontology, axiom);
    }

    private OperationMapper.Outbound out(OWLOntologyChange change) {
        return OperationMapper.toOperation(change, USER, OperationMapper.NO_HINTS);
    }

    private OperationMapper.Inbound in(OntologyOperation operation) {
        return OperationMapper.toChanges(operation, ontology);
    }

    private OntologyOperation operation(String type, Object... keysAndValues) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            data.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return OntologyOperation.local(type, USER, data);
    }

    private void apply(List<OWLOntologyChange> changes) {
        if (!changes.isEmpty()) {
            manager.applyChanges(changes);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(OntologyOperation operation, String key) {
        return (Map<String, Object>) operation.getData().get(key);
    }

    // ================================================================ outbound

    @Test
    void aNewClassBecomesAddClassCarryingItsLabel() {
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), cls("Person").getIRI(),
                factory.getOWLLiteral("Human being", "en")));

        OperationMapper.Outbound result =
                out(new AddAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person"))));

        assertTrue(result.isMapped(), result.getUnmappableReason());
        assertEquals("addClass", result.getOperation().getType());
        assertEquals(NS + "Person", result.getOperation().getIri());
        assertEquals("Human being", result.getOperation().getData().get("label"));
    }

    /** The web client's payload has x, y, w, h and color; a canvas node supplies them. */
    @Test
    void aClassOnTheCanvasCarriesItsGeometrySoThePeerDrawsItInTheSamePlace() {
        OperationMapper.CanvasHints hints = new OperationMapper.CanvasHints() {
            @Override
            public OperationMapper.NodeHint hintFor(String iri) {
                return new OperationMapper.NodeHint(120, 340, 200, 80, "#AABBCC");
            }
        };

        OntologyOperation operation = OperationMapper.toOperation(
                new AddAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person"))),
                USER, hints).getOperation();

        assertEquals(120.0, operation.getData().get("x"));
        assertEquals(340.0, operation.getData().get("y"));
        assertEquals(200.0, operation.getData().get("w"));
        assertEquals(80.0, operation.getData().get("h"));
        assertEquals("#AABBCC", operation.getData().get("color"));
    }

    /** An edit made in Protege's own editors has no canvas geometry, and must still publish. */
    @Test
    void aClassCreatedOutsideTheBoardStillPublishesWithADefaultSize() {
        OntologyOperation operation =
                out(new AddAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person"))))
                        .getOperation();

        assertEquals(0.0, operation.getData().get("x"));
        assertTrue(((Double) operation.getData().get("w")) > 0,
                "a zero-width node would be invisible on the peer's canvas");
    }

    @Test
    void aDeletedClassBecomesRemoveClass() {
        OperationMapper.Outbound result =
                out(removed(factory.getOWLDeclarationAxiom(cls("Person"))));

        assertEquals("removeClass", result.getOperation().getType());
        assertEquals(NS + "Person", result.getOperation().getIri());
    }

    @Test
    void aNewIndividualBecomesAddIndividual() {
        OperationMapper.Outbound result =
                out(new AddAxiom(ontology, factory.getOWLDeclarationAxiom(individual("rex"))));

        assertEquals("addIndividual", result.getOperation().getType());
        assertEquals(NS + "rex", result.getOperation().getIri());
    }

    /**
     * The vocabulary has addIndividual and updateIndividual but no removeIndividual. Inventing
     * one would be rejected by OntologyOperation and ignored by the web client, so the deletion
     * has to be reported as not travelling.
     */
    @Test
    void aDeletedIndividualIsReportedAsUnshareableRatherThanInventingAnOperation() {
        OperationMapper.Outbound result =
                out(removed(factory.getOWLDeclarationAxiom(individual("rex"))));

        assertFalse(result.isMapped());
        assertTrue(result.getUnmappableReason().contains("removeIndividual"),
                result.getUnmappableReason());
        assertTrue(result.getUnmappableReason().contains("rex"),
                "the message must name what did not travel: " + result.getUnmappableReason());
    }

    @Test
    void aSubclassAxiomBecomesAddSubClassOf() {
        OperationMapper.Outbound result =
                out(new AddAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal"))));

        assertEquals("addSubClassOf", result.getOperation().getType());
        assertEquals(NS + "Dog", result.getOperation().getData().get("childIri"));
        assertEquals(NS + "Animal", result.getOperation().getData().get("parentIri"));
    }

    /**
     * There is no removeSubClassOf. The web client stores the edge as a property whose id it
     * derives itself, so the retraction has to be addressed to that derived id.
     */
    @Test
    void aRetractedSubclassAxiomIsAddressedByTheIdTheWebClientDerived() {
        OperationMapper.Outbound result =
                out(removed(factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal"))));

        assertEquals("removeProperty", result.getOperation().getType());
        assertEquals("subClassOf_" + NS + "Dog_" + NS + "Animal",
                result.getOperation().getData().get("id"));
    }

    @Test
    void anExistentialRestrictionBecomesAPropertyEdge() {
        OWLAxiom axiom = factory.getOWLSubClassOfAxiom(cls("Person"),
                factory.getOWLObjectSomeValuesFrom(property("worksFor"), cls("Organisation")));

        OntologyOperation operation = out(new AddAxiom(ontology, axiom)).getOperation();

        assertEquals("addProperty", operation.getType());
        assertEquals(NS + "Person", operation.getData().get("source_id"));
        assertEquals(NS + "Organisation", operation.getData().get("target_id"));
        assertEquals(NS + "worksFor", operation.getData().get("iri"));
        assertEquals("object", operation.getData().get("property_type"));
        assertEquals("rest|some|" + NS + "Person|" + NS + "worksFor|" + NS + "Organisation",
                operation.getData().get("id"));
    }

    /**
     * A universal restriction must not be flattened into the existential one. They mean different
     * things, and the difference does not throw - it just changes what a reasoner concludes.
     */
    @Test
    void aUniversalRestrictionIsDistinguishedFromAnExistentialOne() {
        OWLAxiom axiom = factory.getOWLSubClassOfAxiom(cls("Person"),
                factory.getOWLObjectAllValuesFrom(property("worksFor"), cls("Organisation")));

        OntologyOperation operation = out(new AddAxiom(ontology, axiom)).getOperation();

        assertTrue(String.valueOf(operation.getData().get("id")).startsWith("rest|only|"),
                "the id must record which reading this is: " + operation.getData().get("id"));
    }

    @Test
    void aTypeAssertionBecomesAnEdgeFromIndividualToClass() {
        OWLAxiom axiom = factory.getOWLClassAssertionAxiom(cls("Dog"), individual("rex"));

        OntologyOperation operation = out(new AddAxiom(ontology, axiom)).getOperation();

        assertEquals("addProperty", operation.getType());
        assertEquals(NS + "rex", operation.getData().get("source_id"));
        assertEquals(NS + "Dog", operation.getData().get("target_id"));
        assertEquals("type|" + NS + "rex|" + NS + "Dog", operation.getData().get("id"));
    }

    @Test
    void aLabelOnAClassBecomesUpdateClass() {
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person")));

        OperationMapper.Outbound result = out(new AddAxiom(ontology,
                factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                        cls("Person").getIRI(), factory.getOWLLiteral("Human", "en"))));

        assertEquals("updateClass", result.getOperation().getType());
        assertEquals(NS + "Person", result.getOperation().getIri());
        assertEquals("Human", nested(result.getOperation(), "updates").get("label"));
    }

    @Test
    void aLabelOnAnIndividualBecomesUpdateIndividual() {
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(individual("rex")));

        OperationMapper.Outbound result = out(new AddAxiom(ontology,
                factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                        individual("rex").getIRI(), factory.getOWLLiteral("Rex"))));

        assertEquals("updateIndividual", result.getOperation().getType());
    }

    /**
     * A rename is a removal followed by an addition. Ignoring the removal would leave a deleted
     * label showing on the peer's canvas for good, so it reverts them to the short name and the
     * addition that follows overwrites it.
     */
    @Test
    void aDeletedLabelRevertsThePeerToTheShortNameRatherThanLeavingAGhost() {
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person")));

        OperationMapper.Outbound result = out(removed(
                factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                        cls("Person").getIRI(), factory.getOWLLiteral("Human"))));

        assertEquals("updateClass", result.getOperation().getType());
        assertEquals("Person", nested(result.getOperation(), "updates").get("label"));
    }

    // ---------- annotations that are not labels ----------

    /**
     * Editorial notes have to reach the other editor, or they are a private scratchpad with an
     * ontology-shaped storage format. Everything that is not a label travels as one general
     * operation rather than a type per property.
     */
    @Test
    void anEditorNoteTravelsAsAnAnnotationOperation() {
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person")));

        OperationMapper.Outbound result = out(new AddAxiom(ontology,
                factory.getOWLAnnotationAssertionAxiom(
                        factory.getOWLAnnotationProperty(
                                IRI.create("http://purl.obolibrary.org/obo/IAO_0000116")),
                        cls("Person").getIRI(),
                        factory.getOWLLiteral("the definition needs work"))));

        assertTrue(result.isMapped(), result.getUnmappableReason());
        assertEquals("updateAnnotation", result.getOperation().getType());
        assertEquals("http://example.org/o#Person", result.getOperation().getData().get("iri"));
        assertEquals("http://purl.obolibrary.org/obo/IAO_0000116",
                result.getOperation().getData().get("property"));
        assertEquals("the definition needs work",
                result.getOperation().getData().get("value"));
        assertEquals("", result.getOperation().getData().get("previous"));
    }

    /**
     * An annotation from a peer declares its property, or the ontology leaves OWL 2 DL.
     *
     * <p>Every other writer in this plugin does this already - {@code Provenance},
     * {@code EditorNotes} and {@code Obsoletion} each declare their annotation property behind an
     * {@code isDeclared} guard, and {@code PizzaOntology.v1} carries a comment explaining why. The
     * collaboration path did not, and it is the one path where the property IRI arrives from
     * outside: whatever a web client, a third-party client or a hostile one puts in the operation.
     *
     * <p>"Use of undeclared annotation property" is an OWL 2 DL violation. It is also an invisible
     * one: the OWL API's RDF parser adds the missing declaration on load, so the ontology tests
     * clean again as soon as it is saved and reopened, and {@code robot validate-profile} never sees
     * it. What does see it is the live ontology in front of the editor - OntoBoard's own
     * <em>Profile</em> report - and any reasoner asked to work on the session before a save.
     *
     * <p>Found by adding {@code IAO:0000119} to the pizza fixture without declaring it, noticing
     * the profile report say "outside OWL 2 DL", and then asking which of the plugin's own writers
     * would do the same thing.
     */
    @Test
    void anIncomingAnnotationDeclaresThePropertyItUses() {
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person")));
        IRI definitionSource = IRI.create("http://purl.obolibrary.org/obo/IAO_0000119");
        assertFalse(ontology.isDeclared(factory.getOWLAnnotationProperty(definitionSource)),
                "the property is not declared to begin with; that is the point");

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("iri", "http://example.org/o#Person");
        data.put("property", definitionSource.toString());
        data.put("value", "https://example.org/where-this-came-from");
        data.put("previous", "");
        OperationMapper.Inbound inbound = OperationMapper.toChanges(
                new OntologyOperation("op-1", "updateAnnotation", 1L, "alice", data), ontology);
        manager.applyChanges(inbound.getChanges());

        assertTrue(ontology.isDeclared(factory.getOWLAnnotationProperty(definitionSource)),
                "an undeclared annotation property puts the ontology outside OWL 2 DL, and every "
                        + "other writer in this plugin declares the one it uses");
    }

    /** Removing an annotation declares nothing: there is no property being introduced. */
    @Test
    void removingAnAnnotationDoesNotDeclareAnything() {
        IRI note = IRI.create("http://purl.obolibrary.org/obo/IAO_0000116");
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person")));
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(note), cls("Person").getIRI(),
                factory.getOWLLiteral("going away")));

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("iri", "http://example.org/o#Person");
        data.put("property", note.toString());
        data.put("value", "");
        data.put("previous", "going away");
        OperationMapper.Inbound inbound = OperationMapper.toChanges(
                new OntologyOperation("op-1", "updateAnnotation", 1L, "alice", data), ontology);

        for (org.semanticweb.owlapi.model.OWLOntologyChange change : inbound.getChanges()) {
            assertFalse(change.isAddAxiom()
                            && change.getAxiom() instanceof
                                    org.semanticweb.owlapi.model.OWLDeclarationAxiom,
                    "a removal introduces no property, so it should declare nothing: " + change);
        }
    }

    /**
     * The previous value is what makes the operation safe. Without it a peer applying "this
     * term's note is now X" would delete every other note on the term - and two editors each
     * leaving one is the ordinary case, not an edge case.
     */
    @Test
    void removingANoteNamesTheExactTextThatWent() {
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person")));
        OWLAnnotationAssertionAxiom note = factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(
                        IRI.create("http://purl.obolibrary.org/obo/IAO_0000116")),
                cls("Person").getIRI(), factory.getOWLLiteral("Alice: parent looks wrong"));
        manager.addAxiom(ontology, note);

        OperationMapper.Outbound result = out(new RemoveAxiom(ontology, note));

        assertTrue(result.isMapped(), result.getUnmappableReason());
        assertEquals("", result.getOperation().getData().get("value"));
        assertEquals("Alice: parent looks wrong",
                result.getOperation().getData().get("previous"));
    }

    /** The point of the previous value: the peer must keep the note it was not told about. */
    @Test
    void applyingANoteRemovalLeavesTheOtherEditorsNoteAlone() {
        IRI editorNote = IRI.create("http://purl.obolibrary.org/obo/IAO_0000116");
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person")));
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(editorNote), cls("Person").getIRI(),
                factory.getOWLLiteral("Alice: parent looks wrong")));
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(editorNote), cls("Person").getIRI(),
                factory.getOWLLiteral("Bob: see issue 12")));

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("iri", "http://example.org/o#Person");
        data.put("property", editorNote.toString());
        data.put("value", "");
        data.put("previous", "Alice: parent looks wrong");
        OperationMapper.Inbound inbound = OperationMapper.toChanges(
                new OntologyOperation("op-1", "updateAnnotation", 1L, "alice", data), ontology);
        manager.applyChanges(inbound.getChanges());

        int remaining = 0;
        for (OWLAnnotationAssertionAxiom axiom
                : ontology.getAnnotationAssertionAxioms(cls("Person").getIRI())) {
            if (editorNote.equals(axiom.getProperty().getIRI())) {
                remaining++;
                assertEquals("Bob: see issue 12",
                        ((org.semanticweb.owlapi.model.OWLLiteral) axiom.getValue()).getLiteral());
            }
        }
        assertEquals(1, remaining, "the other editor's note was destroyed");
    }

    @Test
    void anAnnotationRoundTripsThroughTheOperationVocabulary() {
        IRI editorNote = IRI.create("http://purl.obolibrary.org/obo/IAO_0000116");
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person")));
        OWLAnnotationAssertionAxiom note = factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(editorNote), cls("Person").getIRI(),
                factory.getOWLLiteral("needs work"));

        OperationMapper.Outbound outbound = out(new AddAxiom(ontology, note));
        OperationMapper.Inbound inbound =
                OperationMapper.toChanges(outbound.getOperation(), ontology);
        manager.applyChanges(inbound.getChanges());

        assertTrue(ontology.containsAxiom(note),
                "the note did not survive the round trip through the shared vocabulary");
    }

    /**
     * A term tracker item points at a GitHub issue and is IRI-valued, not a literal. Dropping it
     * would lose the link between a term and the discussion about it.
     */
    @Test
    void anIriValuedAnnotationTravelsAsAnIri() {
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person")));

        OperationMapper.Outbound result = out(new AddAxiom(ontology,
                factory.getOWLAnnotationAssertionAxiom(
                        factory.getOWLAnnotationProperty(
                                IRI.create("http://purl.obolibrary.org/obo/IAO_0000233")),
                        cls("Person").getIRI(),
                        IRI.create("https://github.com/ISE-FIZKarlsruhe/mwo/issues/12"))));

        assertTrue(result.isMapped(), result.getUnmappableReason());
        assertEquals("https://github.com/ISE-FIZKarlsruhe/mwo/issues/12",
                result.getOperation().getData().get("value"));
        assertEquals(Boolean.TRUE, result.getOperation().getData().get("valueIsIri"));
    }

    /** An annotation on an anonymous subject has nothing a peer could apply it to. */
    @Test
    void anAnnotationOnAnAnonymousSubjectIsStillUnshareable() {
        OperationMapper.Outbound result = out(new AddAxiom(ontology,
                factory.getOWLAnnotationAssertionAxiom(factory.getRDFSComment(),
                        factory.getOWLAnonymousIndividual(),
                        factory.getOWLLiteral("a note"))));

        assertFalse(result.isMapped());
        assertTrue(result.getUnmappableReason().contains("anonymous"),
                result.getUnmappableReason());
    }

    /**
     * The scoped-domain reading the relation dialog offers puts the restriction on the left, so
     * there is no node for the subject and no edge to draw.
     */
    @Test
    void anAnonymousSubjectIsReportedRatherThanGuessedAt() {
        OWLAxiom axiom = factory.getOWLSubClassOfAxiom(
                factory.getOWLObjectSomeValuesFrom(property("worksFor"), cls("Organisation")),
                cls("Person"));

        OperationMapper.Outbound result = out(new AddAxiom(ontology, axiom));

        assertFalse(result.isMapped());
        assertTrue(result.getUnmappableReason().contains("anonymous"),
                result.getUnmappableReason());
    }

    /**
     * Protege produces far more axiom kinds than the 17 operations cover. Each one must come back
     * named, so a user can see that their equivalence axiom did not reach anyone.
     */
    @Test
    void axiomKindsOutsideTheVocabularyAreNamedInTheReason() {
        OWLAxiom[] beyond = {
            factory.getOWLEquivalentClassesAxiom(cls("Person"), cls("Human")),
            factory.getOWLDisjointClassesAxiom(cls("Dog"), cls("Cat")),
            factory.getOWLFunctionalObjectPropertyAxiom(property("worksFor")),
            factory.getOWLObjectPropertyDomainAxiom(property("worksFor"), cls("Person")),
            factory.getOWLObjectPropertyRangeAxiom(property("worksFor"), cls("Organisation")),
            factory.getOWLHasKeyAxiom(cls("Person"), property("worksFor")),
        };

        for (OWLAxiom axiom : beyond) {
            OperationMapper.Outbound result = out(new AddAxiom(ontology, axiom));
            assertFalse(result.isMapped(), "should not have mapped " + axiom.getAxiomType());
            assertNotNull(result.getUnmappableReason());
            assertTrue(result.getUnmappableReason()
                            .contains(axiom.getAxiomType().getName()),
                    "the reason must name the axiom kind so the user knows what did not travel, "
                            + "but was: " + result.getUnmappableReason());
        }
    }

    @Test
    void aPropertyDeclarationIsNotPublishedBecauseTheEdgeCarriesIt() {
        OperationMapper.Outbound result = out(new AddAxiom(ontology,
                factory.getOWLDeclarationAxiom(property("worksFor"))));

        assertFalse(result.isMapped());
        assertTrue(result.getUnmappableReason().contains("worksFor"),
                result.getUnmappableReason());
    }

    @Test
    void anImportChangeIsReportedSinceAPeerWithoutTheImportResolvesDifferently() {
        OperationMapper.Outbound result = out(new AddImport(ontology,
                factory.getOWLImportsDeclaration(IRI.create("http://example.org/other"))));

        assertFalse(result.isMapped());
        assertTrue(result.getUnmappableReason().contains("import"),
                result.getUnmappableReason());
    }

    @Test
    void nothingUnmappableIsReportedWithAnEmptyReason() {
        OWLAxiom[] all = {
            factory.getOWLDeclarationAxiom(cls("Person")),
            factory.getOWLDeclarationAxiom(property("worksFor")),
            factory.getOWLEquivalentClassesAxiom(cls("Person"), cls("Human")),
            factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal")),
            factory.getOWLClassAssertionAxiom(cls("Dog"), individual("rex")),
        };
        for (OWLAxiom axiom : all) {
            for (OWLOntologyChange change : new OWLOntologyChange[] {
                new AddAxiom(ontology, axiom), new RemoveAxiom(ontology, axiom)}) {
                OperationMapper.Outbound result = out(change);
                if (result.isMapped()) {
                    assertTrue(OntologyOperation.TYPES.contains(result.getOperation().getType()),
                            "produced a type the web client would ignore: "
                                    + result.getOperation().getType());
                    assertNull(result.getUnmappableReason());
                } else {
                    assertNotNull(result.getUnmappableReason());
                    assertTrue(result.getUnmappableReason().trim().length() > 10,
                            "'could not map' tells nobody anything: "
                                    + result.getUnmappableReason());
                }
            }
        }
    }

    @Test
    void anEmptyChangeIsReportedRatherThanThrowing() {
        OperationMapper.Outbound result = out(null);
        assertFalse(result.isMapped());
        assertNotNull(result.getUnmappableReason());
    }

    // ================================================================ inbound

    @Test
    void addClassDeclaresTheClass() {
        OperationMapper.Inbound result = in(operation("addClass", "iri", NS + "Person"));

        assertTrue(result.isUnderstood(), result.getSkippedReason());
        apply(result.getChanges());
        assertTrue(ontology.isDeclared(cls("Person")));
    }

    /** Applying the same operation twice must not add a duplicate declaration. */
    @Test
    void addClassForAClassAlreadyHereChangesNothingButIsStillUnderstood() {
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person")));

        OperationMapper.Inbound result = in(operation("addClass", "iri", NS + "Person"));

        assertTrue(result.isUnderstood());
        assertTrue(result.getChanges().isEmpty(),
                "re-declaring would dirty the ontology for no reason");
    }

    @Test
    void removeClassTakesTheAxiomsThatMentionItWithIt() {
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Dog")));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Animal")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal")));

        apply(in(operation("removeClass", "iri", NS + "Dog")).getChanges());

        assertFalse(ontology.isDeclared(cls("Dog")));
        assertTrue(ontology.getAxioms(org.semanticweb.owlapi.model.AxiomType.SUBCLASS_OF)
                        .isEmpty(),
                "a subclass axiom pointing at a deleted class would leave the two copies "
                        + "disagreeing about more than the one entity");
    }

    /** A removal that has already happened locally is convergence, not an error. */
    @Test
    void removeClassForSomethingAlreadyGoneIsUnderstoodAndDoesNothing() {
        OperationMapper.Inbound result = in(operation("removeClass", "iri", NS + "Ghost"));

        assertTrue(result.isUnderstood());
        assertTrue(result.getChanges().isEmpty());
    }

    /**
     * An operation naming an entity this ontology has never seen must not be dropped: the peer
     * has it, so declaring it here is what keeps the two in step.
     */
    @Test
    void addSubClassOfDeclaresBothEndsItHasNeverSeen() {
        apply(in(operation("addSubClassOf", "childIri", NS + "Dog",
                "parentIri", NS + "Animal")).getChanges());

        assertTrue(ontology.isDeclared(cls("Dog")));
        assertTrue(ontology.isDeclared(cls("Animal")));
        assertTrue(ontology.containsAxiom(
                factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal"))));
    }

    @Test
    void addPropertyWithARestrictionIdRebuildsThatExactRestriction() {
        String edgeId = "rest|some|" + NS + "Person|" + NS + "worksFor|" + NS + "Organisation";

        apply(in(operation("addProperty", "id", edgeId, "iri", NS + "worksFor",
                "source_id", NS + "Person", "target_id", NS + "Organisation",
                "property_type", "object")).getChanges());

        assertTrue(ontology.containsAxiom(factory.getOWLSubClassOfAxiom(cls("Person"),
                factory.getOWLObjectSomeValuesFrom(property("worksFor"), cls("Organisation")))));
    }

    /**
     * The meaning-changing case. An id saying "only" must produce a universal restriction; using
     * source and target alone would silently produce the existential one.
     */
    @Test
    void addPropertyWithAUniversalIdDoesNotProduceAnExistentialRestriction() {
        String edgeId = "rest|only|" + NS + "Person|" + NS + "worksFor|" + NS + "Organisation";

        apply(in(operation("addProperty", "id", edgeId, "iri", NS + "worksFor",
                "source_id", NS + "Person", "target_id", NS + "Organisation",
                "property_type", "object")).getChanges());

        assertTrue(ontology.containsAxiom(factory.getOWLSubClassOfAxiom(cls("Person"),
                factory.getOWLObjectAllValuesFrom(property("worksFor"), cls("Organisation")))));
        assertFalse(ontology.containsAxiom(factory.getOWLSubClassOfAxiom(cls("Person"),
                factory.getOWLObjectSomeValuesFrom(property("worksFor"), cls("Organisation")))),
                "the existential reading is a different claim about the world");
    }

    /** A web-native id says nothing about the axiom form, so the arrow's usual reading is used. */
    @Test
    void addPropertyWithAWebGeneratedIdFallsBackToTheExistentialReading() {
        apply(in(operation("addProperty", "id", "b6e1f4c2-uuid-from-the-browser",
                "iri", NS + "worksFor", "source_id", NS + "Person",
                "target_id", NS + "Organisation", "property_type", "object")).getChanges());

        assertTrue(ontology.containsAxiom(factory.getOWLSubClassOfAxiom(cls("Person"),
                factory.getOWLObjectSomeValuesFrom(property("worksFor"), cls("Organisation")))));
        assertTrue(ontology.isDeclared(property("worksFor")),
                "the edge carries the property, so the receiver has to declare it");
    }

    @Test
    void addIndividualWithATypeAssertsThatType() {
        apply(in(operation("addIndividual", "iri", NS + "rex", "class_iri", NS + "Dog"))
                .getChanges());

        assertTrue(ontology.isDeclared(individual("rex")));
        assertTrue(ontology.containsAxiom(
                factory.getOWLClassAssertionAxiom(cls("Dog"), individual("rex"))));
    }

    @Test
    void removePropertyRetractsTheRestrictionItsIdNames() {
        OWLAxiom axiom = factory.getOWLSubClassOfAxiom(cls("Person"),
                factory.getOWLObjectSomeValuesFrom(property("worksFor"), cls("Organisation")));
        manager.addAxiom(ontology, axiom);

        apply(in(operation("removeProperty", "id",
                "rest|some|" + NS + "Person|" + NS + "worksFor|" + NS + "Organisation"))
                .getChanges());

        assertFalse(ontology.containsAxiom(axiom));
    }

    @Test
    void removePropertyWithAWebSubclassIdRetractsThatSubclassAxiom() {
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal")));

        apply(in(operation("removeProperty", "id",
                "subClassOf_" + NS + "Dog_" + NS + "Animal")).getChanges());

        assertFalse(ontology.containsAxiom(
                factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal"))));
    }

    /**
     * The web client's id format joins two IRIs with an underscore, and IRIs may contain
     * underscores, so no positional rule can decode it: for child {@code #X_Y} and parent
     * {@code #P_Q} the id contains three underscores and only the middle one is the join.
     * Splitting at the first gives {@code (#X, Y_http://...#P_Q)}; at the last,
     * {@code (#X_Y_http://...#P, Q)}. Resolution therefore has to be by search.
     *
     * <p>The two decoys below are the axioms those wrong splits name. They are deliberately
     * contrived - a relative IRI is not something anyone writes on purpose - because the only way
     * to prove the resolution is not positional is to make every positional answer a
     * <em>plausible</em> one. Without them a naive split falls through to the search by accident
     * and this test passes while asserting nothing, which is how it was first written.
     */
    @Test
    void aSubclassIdIsResolvedBySearchAndNotByGuessingWhereTheIrisJoin() {
        OWLClass child = cls("X_Y");
        OWLClass parent = cls("P_Q");
        OWLClass splitAtFirstLeft = factory.getOWLClass(IRI.create(NS + "X"));
        OWLClass splitAtFirstRight = factory.getOWLClass(IRI.create("Y_" + NS + "P_Q"));
        OWLClass splitAtLastLeft = factory.getOWLClass(IRI.create(NS + "X_Y_" + NS + "P"));
        OWLClass splitAtLastRight = factory.getOWLClass(IRI.create("Q"));

        OWLAxiom target = factory.getOWLSubClassOfAxiom(child, parent);
        OWLAxiom decoyFirst = factory.getOWLSubClassOfAxiom(splitAtFirstLeft, splitAtFirstRight);
        OWLAxiom decoyLast = factory.getOWLSubClassOfAxiom(splitAtLastLeft, splitAtLastRight);
        manager.addAxiom(ontology, target);
        manager.addAxiom(ontology, decoyFirst);
        manager.addAxiom(ontology, decoyLast);

        apply(in(operation("removeProperty", "id",
                OperationMapper.webSubClassId(NS + "X_Y", NS + "P_Q"))).getChanges());

        assertFalse(ontology.containsAxiom(target), "the addressed axiom should be gone");
        assertTrue(ontology.containsAxiom(decoyFirst),
                "splitting at the first underscore would have retracted this one instead");
        assertTrue(ontology.containsAxiom(decoyLast),
                "splitting at the last underscore would have retracted this one instead");
    }

    /** The everyday case: underscores in the local names, nothing adversarial. */
    @Test
    void aSubclassIdWithOrdinaryUnderscoredNamesResolves() {
        manager.addAxiom(ontology,
                factory.getOWLSubClassOfAxiom(cls("Working_Dog"), cls("Domestic_Animal")));

        apply(in(operation("removeProperty", "id",
                OperationMapper.webSubClassId(NS + "Working_Dog", NS + "Domestic_Animal")))
                .getChanges());

        assertFalse(ontology.containsAxiom(
                factory.getOWLSubClassOfAxiom(cls("Working_Dog"), cls("Domestic_Animal"))));
    }

    @Test
    void removePropertyForAnAxiomThatIsNotHereIsUnderstoodAndDoesNothing() {
        OperationMapper.Inbound result = in(operation("removeProperty", "id",
                "rest|some|" + NS + "A|" + NS + "r|" + NS + "B"));

        assertTrue(result.isUnderstood());
        assertTrue(result.getChanges().isEmpty());
    }

    @Test
    void anEdgeIdThisVersionCannotParseIsSkippedWithAReasonRatherThanGuessedAt() {
        OperationMapper.Inbound result =
                in(operation("removeProperty", "id", "constellation|of|nonsense"));

        assertFalse(result.isUnderstood());
        assertTrue(result.getSkippedReason().contains("constellation|of|nonsense"),
                result.getSkippedReason());
    }

    @Test
    void updateClassReplacesTheLabelRatherThanAccumulatingLabels() {
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Person")));
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), cls("Person").getIRI(),
                factory.getOWLLiteral("Old name")));

        Map<String, Object> updates = new java.util.LinkedHashMap<String, Object>();
        updates.put("label", "New name");
        apply(in(operation("updateClass", "iri", NS + "Person", "updates", updates))
                .getChanges());

        assertEquals(1, ontology.getAnnotationAssertionAxioms(cls("Person").getIRI()).size(),
                "two rdfs:labels would make the display name arbitrary");
        assertEquals("New name", ontology.getAnnotationAssertionAxioms(cls("Person").getIRI())
                .iterator().next().getValue().asLiteral().get().getLiteral());
    }

    @Test
    void anUpdateThatChangesOnlyCanvasStateTouchesNoAxioms() {
        Map<String, Object> updates = new java.util.LinkedHashMap<String, Object>();
        updates.put("x", 42);
        OperationMapper.Inbound result =
                in(operation("updateClass", "iri", NS + "Person", "updates", updates));

        assertTrue(result.isUnderstood());
        assertTrue(result.getChanges().isEmpty());
    }

    @Test
    void canvasOnlyOperationsAreSkippedWithAReasonNamingThem() {
        for (String type : new String[] {"addStickyNote", "updateStickyNote", "removeStickyNote",
            "addFrame", "updateFrame", "removeFrame"}) {
            OperationMapper.Inbound result = in(operation(type, "id", "n1"));
            assertFalse(result.isUnderstood(), type + " should be skipped");
            assertTrue(result.getSkippedReason().contains(type), result.getSkippedReason());
            assertTrue(result.getChanges().isEmpty());
        }
    }

    @Test
    void literalOperationsAreSkippedAndSayTheOntologyIsUnaffected() {
        for (String type : new String[] {"addLiteral", "updateLiteral", "removeLiteral"}) {
            OperationMapper.Inbound result = in(operation(type, "id", "l1"));
            assertFalse(result.isUnderstood());
            assertTrue(result.getSkippedReason().contains("literal"), result.getSkippedReason());
        }
    }

    @Test
    void anOperationMissingTheFieldItNeedsIsSkippedRatherThanApplyingSomethingWrong() {
        assertFalse(in(operation("addClass", "label", "no iri here")).isUnderstood());
        assertFalse(in(operation("removeClass")).isUnderstood());
        assertFalse(in(operation("addSubClassOf", "childIri", NS + "Dog")).isUnderstood());
        assertFalse(in(operation("addProperty", "id", "x")).isUnderstood());
        assertFalse(in(operation("removeProperty")).isUnderstood());
    }

    @Test
    void aNullOntologyIsSkippedRatherThanThrowing() {
        assertFalse(OperationMapper.toChanges(operation("addClass", "iri", NS + "A"), null)
                .isUnderstood());
    }

    // ================================================================ symmetry

    /**
     * The property that matters most: an axiom that leaves as an operation comes back as the same
     * axiom. Checked by mapping out of one ontology and in to an empty one, which is what
     * actually happens between two people.
     */
    @Test
    void everyMappableAxiomSurvivesTheRoundTrip() throws Exception {
        OWLAxiom[] axioms = {
            factory.getOWLDeclarationAxiom(cls("Person")),
            factory.getOWLDeclarationAxiom(individual("rex")),
            factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal")),
            factory.getOWLSubClassOfAxiom(cls("Person"),
                    factory.getOWLObjectSomeValuesFrom(property("worksFor"),
                            cls("Organisation"))),
            factory.getOWLSubClassOfAxiom(cls("Person"),
                    factory.getOWLObjectAllValuesFrom(property("speaks"), cls("Language"))),
            factory.getOWLClassAssertionAxiom(cls("Dog"), individual("rex")),
        };

        for (OWLAxiom axiom : axioms) {
            OWLOntologyManager theirs = OWLManager.createOWLOntologyManager();
            OWLOntology peer = theirs.createOntology(IRI.create("http://example.org/o"));

            OperationMapper.Outbound sent = out(added(axiom));
            assertTrue(sent.isMapped(), "should map: " + axiom);

            OperationMapper.Inbound received =
                    OperationMapper.toChanges(sent.getOperation(), peer);
            assertTrue(received.isUnderstood(), received.getSkippedReason());
            if (!received.getChanges().isEmpty()) {
                theirs.applyChanges(received.getChanges());
            }

            assertTrue(peer.containsAxiom(axiom),
                    "round trip lost the axiom: " + axiom + " -> "
                            + sent.getOperation().getType() + " -> " + peer.getAxioms());
        }
    }

    /**
     * And the reverse: a retraction that leaves as an operation retracts the same axiom on the
     * other side. This is where an asymmetric id scheme shows up - the add and the remove have to
     * address the same thing.
     */
    @Test
    void everyMappableRetractionSurvivesTheRoundTrip() throws Exception {
        OWLAxiom[] axioms = {
            factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal")),
            factory.getOWLSubClassOfAxiom(cls("Person"),
                    factory.getOWLObjectSomeValuesFrom(property("worksFor"),
                            cls("Organisation"))),
            factory.getOWLSubClassOfAxiom(cls("Person"),
                    factory.getOWLObjectAllValuesFrom(property("speaks"), cls("Language"))),
            factory.getOWLClassAssertionAxiom(cls("Dog"), individual("rex")),
        };

        for (OWLAxiom axiom : axioms) {
            OWLOntologyManager theirs = OWLManager.createOWLOntologyManager();
            OWLOntology peer = theirs.createOntology(IRI.create("http://example.org/o"));
            theirs.addAxiom(peer, axiom);

            OperationMapper.Outbound sent = out(removed(axiom));
            assertTrue(sent.isMapped(), "should map the retraction of: " + axiom);

            OperationMapper.Inbound received =
                    OperationMapper.toChanges(sent.getOperation(), peer);
            assertTrue(received.isUnderstood(), received.getSkippedReason());
            theirs.applyChanges(received.getChanges());

            assertFalse(peer.containsAxiom(axiom),
                    "round trip failed to retract: " + axiom + " via "
                            + sent.getOperation().getData());
        }
    }

    /** Applying the same received operation twice must leave the ontology as it was. */
    @Test
    void applyingAnOperationTwiceIsIdempotent() {
        OntologyOperation operation = operation("addSubClassOf",
                "childIri", NS + "Dog", "parentIri", NS + "Animal");

        apply(in(operation).getChanges());
        int after = ontology.getAxiomCount();
        apply(in(operation).getChanges());

        assertEquals(after, ontology.getAxiomCount(),
                "a redelivered operation must not duplicate axioms");
    }
}
