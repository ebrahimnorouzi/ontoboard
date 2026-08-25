package de.fizkarlsruhe.ise.ontoboard.collab;

import de.fizkarlsruhe.ise.ontoboard.axiom.AxiomRemoval;
import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectAllValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * Translates between OWL API changes and the operation vocabulary the web application speaks.
 *
 * <p>The two models do not line up, and pretending otherwise is how a collaboration feature ends
 * up quietly losing edits. The web application's model is a drawing: classes with an x and y,
 * and <em>properties</em> that are edges between two nodes. OWL's model is a set of axioms, most
 * of which have no edge form at all. So this class is mostly a statement of where the overlap
 * ends:
 *
 * <ul>
 *   <li>Outbound, a change either maps or comes back with a reason it could not. Nothing is
 *       dropped in silence - a user whose {@code owl:hasKey} axiom did not reach a colleague
 *       needs telling, and the caller aggregates those reasons into a visible count.
 *   <li>Inbound, an operation either produces changes, produces none because the local ontology
 *       already agrees, or is skipped with a reason. Removals are idempotent and additions
 *       declare what they need, so an operation naming an entity this ontology has never seen
 *       converges rather than failing.
 * </ul>
 *
 * <p>Pure functions over OWL API types with no Protege dependency and no Swing, so all of it is
 * unit-testable - which matters more here than usual, because the alternative way to check a
 * mapping is to run two editors and squint.
 *
 * <p>Edge identity is the subtle part. The plugin names an edge by the axiom it draws
 * ({@code rest|some|A|R|B}, {@code sub|A|B}, {@code type|i|C}, {@code dr|A|R|B}), which
 * {@link AxiomRemoval} can parse straight back into that axiom. Reusing those ids as the
 * operation's {@code id} makes add and remove symmetric across the wire. The one exception is
 * imposed by the web client: {@code addSubClassOf} makes up its own id,
 * {@code subClassOf_<child>_<parent>}, so a subclass retraction has to be addressed that way and
 * {@link #subClassOfAxiomWithWebId} resolves it by search rather than by splitting on an
 * underscore that IRIs are allowed to contain.
 */
public final class OperationMapper {

    /** The id format {@code useOperationSync.ts} mints for a subclass edge. */
    static final String WEB_SUBCLASS_PREFIX = "subClassOf_";

    private static final IRI RDFS_LABEL = OWLRDFVocabulary.RDFS_LABEL.getIRI();

    /** Where a node sits on the canvas, since web-client payloads carry geometry. */
    public interface CanvasHints {

        /** Geometry and colour for {@code iri}, or {@code null} when it is not on the canvas. */
        NodeHint hintFor(String iri);
    }

    /** Canvas geometry for one node, in graph space. */
    public static final class NodeHint {
        private final double x;
        private final double y;
        private final double width;
        private final double height;
        private final String colour;

        public NodeHint(double x, double y, double width, double height, String colour) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.colour = colour;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getWidth() {
            return width;
        }

        public double getHeight() {
            return height;
        }

        /** {@code #RRGGBB}, or null to let the receiver choose. */
        public String getColour() {
            return colour;
        }
    }

    /**
     * Hints for an ontology whose entities are not on any canvas.
     *
     * <p>Used when publishing a change the user made in one of Protege's own editors rather than
     * on the board: there is no geometry to send, and the receiver places the node itself.
     */
    public static final CanvasHints NO_HINTS = new CanvasHints() {
        @Override
        public NodeHint hintFor(String iri) {
            return null;
        }
    };

    /** What one {@link OWLOntologyChange} became. */
    public static final class Outbound {
        private final OntologyOperation operation;
        private final String unmappableReason;

        private Outbound(OntologyOperation operation, String unmappableReason) {
            this.operation = operation;
            this.unmappableReason = unmappableReason;
        }

        /** The operation to publish, or null when {@link #getUnmappableReason()} is set. */
        public OntologyOperation getOperation() {
            return operation;
        }

        /**
         * Why this change cannot be shared, phrased for a user rather than a maintainer, or null
         * when it mapped. Never blank when set: "could not map" tells nobody anything.
         */
        public String getUnmappableReason() {
            return unmappableReason;
        }

        public boolean isMapped() {
            return operation != null;
        }
    }

    /** What one {@link OntologyOperation} became locally. */
    public static final class Inbound {
        private final List<OWLOntologyChange> changes;
        private final String skippedReason;

        private Inbound(List<OWLOntologyChange> changes, String skippedReason) {
            this.changes = changes;
            this.skippedReason = skippedReason;
        }

        /** Changes to apply; never null, and empty when the ontology already agrees. */
        public List<OWLOntologyChange> getChanges() {
            return changes;
        }

        /** Why nothing was produced, or null when the operation was understood. */
        public String getSkippedReason() {
            return skippedReason;
        }

        /**
         * True when this operation is one this plugin knows how to apply. An understood
         * operation with no changes means the local ontology is already in the requested state,
         * which is success, not failure.
         */
        public boolean isUnderstood() {
            return skippedReason == null;
        }
    }

    private OperationMapper() {
    }

    // ------------------------------------------------------------------ outbound

    /**
     * Maps a local change into an operation for the shared session.
     *
     * @param change what Protege applied - this plugin's own edit, or one made in any other view
     * @param userId the operation's author, stamped again by the bridge from the access token
     * @param hints canvas geometry, or {@link #NO_HINTS} when the change came from outside the
     *     board
     */
    public static Outbound toOperation(OWLOntologyChange change, String userId,
            CanvasHints hints) {
        if (change == null) {
            return unmappable("an empty change");
        }
        if (!change.isAxiomChange()) {
            // Imports and ontology annotations. Worth reporting rather than hiding: an import
            // the peer does not have makes their copy resolve differently from yours.
            return unmappable("a change to the ontology's imports or annotations, which the "
                    + "shared session has no way to express");
        }
        OWLAxiom axiom = change.getAxiom();
        boolean adding = change.isAddAxiom();
        OWLOntology ontology = change.getOntology();

        if (axiom instanceof OWLDeclarationAxiom) {
            return declaration((OWLDeclarationAxiom) axiom, adding, userId, ontology, hints);
        }
        if (axiom instanceof OWLSubClassOfAxiom) {
            return subClassOf((OWLSubClassOfAxiom) axiom, adding, userId, ontology, hints);
        }
        if (axiom instanceof OWLClassAssertionAxiom) {
            return classAssertion((OWLClassAssertionAxiom) axiom, adding, userId, ontology);
        }
        if (axiom instanceof OWLAnnotationAssertionAxiom) {
            return annotation((OWLAnnotationAssertionAxiom) axiom, adding, userId, ontology);
        }
        // Everything else: equivalence, disjointness, property characteristics, hasKey, chains,
        // negative assertions, datatype definitions, SWRL rules. All are real OWL that this
        // vocabulary simply cannot carry, and the name is included so the user can see what did
        // not travel rather than being told "something".
        return unmappable("a " + readableAxiomType(axiom) + " axiom, which the shared session "
                + "has no way to express");
    }

    private static Outbound declaration(OWLDeclarationAxiom axiom, boolean adding, String userId,
            OWLOntology ontology, CanvasHints hints) {
        OWLEntity entity = axiom.getEntity();
        String iri = entity.getIRI().toString();

        if (entity.isOWLClass()) {
            if (!adding) {
                return mapped("removeClass", userId, single("iri", iri));
            }
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("id", iri);
            data.put("iri", iri);
            data.put("label", DisplayLabels.forEntity(ontology, entity));
            putGeometry(data, hints.hintFor(iri), 160, 60);
            return mapped("addClass", userId, data);
        }
        if (entity.isOWLNamedIndividual()) {
            if (!adding) {
                // The vocabulary has addIndividual and updateIndividual but no removeIndividual;
                // see the 17 types in useOperationSync.ts. Inventing one here would be rejected
                // by OntologyOperation's constructor and ignored by the web client, so the
                // honest outcome is to say the deletion did not travel.
                return unmappable("the deletion of individual " + shortForm(iri)
                        + " - the shared vocabulary has addIndividual but no removeIndividual");
            }
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("id", iri);
            data.put("iri", iri);
            data.put("label", DisplayLabels.forEntity(ontology, entity));
            data.put("class_iri", "");
            NodeHint hint = hints.hintFor(iri);
            data.put("x", hint == null ? 0.0 : hint.getX());
            data.put("y", hint == null ? 0.0 : hint.getY());
            return mapped("addIndividual", userId, data);
        }
        // Object, data and annotation properties, and datatypes. The web application has no
        // notion of a standalone property - its "property" is an edge between two nodes - so a
        // bare declaration has nowhere to go. Harmless in practice: the edge that uses the
        // property carries its IRI, and the receiver declares it then.
        return unmappable("the declaration of " + shortForm(iri) + ", which the shared session "
                + "only records once an edge uses it");
    }

    private static Outbound subClassOf(OWLSubClassOfAxiom axiom, boolean adding, String userId,
            OWLOntology ontology, CanvasHints hints) {
        OWLClassExpression subClass = axiom.getSubClass();
        OWLClassExpression superClass = axiom.getSuperClass();
        if (subClass.isAnonymous()) {
            // The scoped-domain reading, among others. There is no node for the anonymous side.
            return unmappable("an axiom whose subject is an anonymous class expression, which "
                    + "the shared session cannot draw");
        }
        String childIri = subClass.asOWLClass().getIRI().toString();

        if (!superClass.isAnonymous()) {
            String parentIri = superClass.asOWLClass().getIRI().toString();
            if (adding) {
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("childIri", childIri);
                data.put("parentIri", parentIri);
                return mapped("addSubClassOf", userId, data);
            }
            // No removeSubClassOf exists, but addSubClassOf stores the edge as a property whose
            // id the web client derives, so removeProperty addressed to that id retracts it.
            return mapped("removeProperty", userId,
                    single("id", webSubClassId(childIri, parentIri)));
        }

        // A restriction on a named property and a named filler is the existential or
        // universal edge the canvas draws, and the web client's generic "property" edge is
        // exactly the right shape for it.
        String qualifier;
        OWLObjectPropertyExpression property;
        OWLClassExpression filler;
        if (superClass instanceof OWLObjectSomeValuesFrom) {
            qualifier = "some";
            property = ((OWLObjectSomeValuesFrom) superClass).getProperty();
            filler = ((OWLObjectSomeValuesFrom) superClass).getFiller();
        } else if (superClass instanceof OWLObjectAllValuesFrom) {
            qualifier = "only";
            property = ((OWLObjectAllValuesFrom) superClass).getProperty();
            filler = ((OWLObjectAllValuesFrom) superClass).getFiller();
        } else {
            return unmappable("a " + readableClassExpression(superClass)
                    + " restriction, which the shared session cannot draw as an edge");
        }
        if (property.isAnonymous() || filler.isAnonymous()) {
            return unmappable("a restriction on an anonymous property or filler, which the "
                    + "shared session cannot draw as an edge");
        }
        String propertyIri = property.asOWLObjectProperty().getIRI().toString();
        String targetIri = filler.asOWLClass().getIRI().toString();
        String edgeId = "rest|" + qualifier + "|" + childIri + "|" + propertyIri + "|" + targetIri;

        if (!adding) {
            return mapped("removeProperty", userId, single("id", edgeId));
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", edgeId);
        data.put("iri", propertyIri);
        data.put("label", DisplayLabels.forEntity(ontology, property.asOWLObjectProperty()));
        data.put("source_id", childIri);
        data.put("target_id", targetIri);
        data.put("property_type", "object");
        return mapped("addProperty", userId, data);
    }

    private static Outbound classAssertion(OWLClassAssertionAxiom axiom, boolean adding,
            String userId, OWLOntology ontology) {
        OWLIndividual individual = axiom.getIndividual();
        OWLClassExpression type = axiom.getClassExpression();
        if (individual.isAnonymous() || type.isAnonymous()) {
            return unmappable("a type assertion involving an anonymous individual or class "
                    + "expression, which the shared session cannot draw");
        }
        String individualIri = individual.asOWLNamedIndividual().getIRI().toString();
        String classIri = type.asOWLClass().getIRI().toString();
        String edgeId = "type|" + individualIri + "|" + classIri;

        if (!adding) {
            return mapped("removeProperty", userId, single("id", edgeId));
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", edgeId);
        data.put("iri", "rdf:type");
        data.put("label", "rdf:type");
        data.put("source_id", individualIri);
        data.put("target_id", classIri);
        data.put("property_type", "annotation");
        return mapped("addProperty", userId, data);
    }

    private static Outbound annotation(OWLAnnotationAssertionAxiom axiom, boolean adding,
            String userId, OWLOntology ontology) {
        if (!RDFS_LABEL.equals(axiom.getProperty().getIRI())) {
            return unmappable("an annotation with " + shortForm(
                    axiom.getProperty().getIRI().toString())
                    + ", which the shared session only carries for rdfs:label");
        }
        if (!(axiom.getSubject() instanceof IRI)) {
            return unmappable("a label on an anonymous subject");
        }
        IRI subject = (IRI) axiom.getSubject();
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();

        String type;
        if (ontology.isDeclared(factory.getOWLClass(subject))) {
            type = "updateClass";
        } else if (ontology.isDeclared(factory.getOWLNamedIndividual(subject))) {
            type = "updateIndividual";
        } else {
            return unmappable("a label on " + shortForm(subject.toString())
                    + ", which is neither a declared class nor a declared individual");
        }

        // A rename arrives as a removal then an addition. Reporting the removal as a reversion to
        // the short name keeps the peer in step through the intermediate state, and the addition
        // that follows immediately overwrites it - whereas ignoring the removal would leave a
        // deleted label showing on the peer's canvas indefinitely.
        String label = adding ? literalText(axiom.getValue()) : shortForm(subject.toString());
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("label", label);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("iri", subject.toString());
        data.put("updates", updates);
        return mapped(type, userId, data);
    }

    // ------------------------------------------------------------------ inbound

    /**
     * Maps an operation from the shared session into changes to apply locally.
     *
     * <p>The caller applies them through {@code OWLModelManager} so Protege's undo and its other
     * views stay correct, and must guard the apply so the resulting change events are not
     * published straight back out.
     */
    public static Inbound toChanges(OntologyOperation operation, OWLOntology ontology) {
        if (operation == null || ontology == null) {
            return skipped("an empty operation");
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        Map<String, Object> data = operation.getData();
        String type = operation.getType();

        if ("addClass".equals(type)) {
            String iri = text(data, "iri");
            if (iri == null) {
                return skipped("an addClass with no iri");
            }
            return understood(declareIfAbsent(ontology, factory.getOWLClass(IRI.create(iri))));
        }
        if ("removeClass".equals(type)) {
            String iri = text(data, "iri");
            if (iri == null) {
                return skipped("a removeClass with no iri");
            }
            return understood(retract(ontology, factory.getOWLClass(IRI.create(iri))));
        }
        if ("addIndividual".equals(type)) {
            String iri = text(data, "iri");
            if (iri == null) {
                return skipped("an addIndividual with no iri");
            }
            OWLNamedIndividual individual = factory.getOWLNamedIndividual(IRI.create(iri));
            List<OWLOntologyChange> changes = declareIfAbsent(ontology, individual);
            String classIri = text(data, "class_iri");
            if (classIri != null && !classIri.trim().isEmpty()) {
                OWLClass type2 = factory.getOWLClass(IRI.create(classIri));
                changes.addAll(declareIfAbsent(ontology, type2));
                addIfAbsent(changes, ontology,
                        factory.getOWLClassAssertionAxiom(type2, individual));
            }
            return understood(changes);
        }
        if ("updateClass".equals(type) || "updateIndividual".equals(type)) {
            return understood(relabel(ontology, factory, data));
        }
        if ("addSubClassOf".equals(type)) {
            String childIri = text(data, "childIri");
            String parentIri = text(data, "parentIri");
            if (childIri == null || parentIri == null) {
                return skipped("an addSubClassOf missing childIri or parentIri");
            }
            OWLClass child = factory.getOWLClass(IRI.create(childIri));
            OWLClass parent = factory.getOWLClass(IRI.create(parentIri));
            List<OWLOntologyChange> changes = declareIfAbsent(ontology, child);
            changes.addAll(declareIfAbsent(ontology, parent));
            addIfAbsent(changes, ontology, factory.getOWLSubClassOfAxiom(child, parent));
            return understood(changes);
        }
        if ("addProperty".equals(type)) {
            return addProperty(ontology, factory, data);
        }
        if ("removeProperty".equals(type)) {
            return removeProperty(ontology, text(data, "id"));
        }
        if ("addLiteral".equals(type) || "updateLiteral".equals(type)
                || "removeLiteral".equals(type)) {
            return skipped("a literal-node operation; the plugin's canvas has no literal nodes "
                    + "yet, so the ontology is unaffected either way");
        }
        // Sticky notes and frames are annotations on the drawing, deliberately not stored in the
        // ontology - see the sidecar rule. Nothing is lost by skipping them, and the sidecar
        // format already reserves room for when they are drawn.
        return skipped("a " + type + " operation; sticky notes and frames are not drawn by the "
                + "plugin yet");
    }

    private static Inbound addProperty(OWLOntology ontology, OWLDataFactory factory,
            Map<String, Object> data) {
        String edgeId = text(data, "id");
        String sourceIri = text(data, "source_id");
        String targetIri = text(data, "target_id");
        String propertyIri = text(data, "iri");

        // An id in the plugin's own form states the axiom exactly, so it is preferred over
        // guessing from source and target - a "rest|only|..." edge is a universal restriction,
        // not an existential one, and getting that wrong changes the ontology's meaning.
        if (edgeId != null && edgeId.startsWith("type|")) {
            return fromEdgeId(ontology, edgeId);
        }
        if (edgeId != null && edgeId.startsWith("rest|")) {
            return fromEdgeId(ontology, edgeId);
        }
        if (edgeId != null && edgeId.startsWith(WEB_SUBCLASS_PREFIX)) {
            return skipped("an addProperty carrying a subclass id; the web client sends "
                    + "addSubClassOf for those");
        }
        if (sourceIri == null || targetIri == null || propertyIri == null) {
            return skipped("an addProperty missing source_id, target_id or iri");
        }
        // A web-native id (a uuid) says nothing about the axiom form, so the existential
        // reading is used - the same default the relation dialog offers, and the reading an
        // arrow almost always means.
        OWLClass source = factory.getOWLClass(IRI.create(sourceIri));
        OWLClass target = factory.getOWLClass(IRI.create(targetIri));
        OWLObjectProperty property = factory.getOWLObjectProperty(IRI.create(propertyIri));
        List<OWLOntologyChange> changes = declareIfAbsent(ontology, source);
        changes.addAll(declareIfAbsent(ontology, target));
        changes.addAll(declareIfAbsent(ontology, property));
        addIfAbsent(changes, ontology, factory.getOWLSubClassOfAxiom(source,
                factory.getOWLObjectSomeValuesFrom(property, target)));
        return understood(changes);
    }

    /**
     * Rebuilds the axiom an edge id names and adds it, declaring the entities it mentions.
     *
     * <p>{@link AxiomRemoval#removalsFor} already parses every id form the canvas mints, so the
     * removals it produces are inverted rather than the parsing being written a second time -
     * two parsers for one format would drift.
     */
    private static Inbound fromEdgeId(OWLOntology ontology, String edgeId) {
        List<OWLOntologyChange> removals;
        try {
            removals = AxiomRemoval.removalsFor(ontology, edgeId);
        } catch (AxiomRemoval.UnknownEdgeException unknown) {
            return skipped("an edge id this version does not understand: " + edgeId);
        }
        // removalsFor returns only axioms the ontology already has, so an empty result means the
        // axiom is absent - which is exactly the one we need to add. Reconstructing it therefore
        // cannot go through removalsFor, and AxiomRemoval offers no builder, so the id is
        // rebuilt here for the two forms that reach this path.
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        if (!removals.isEmpty()) {
            // Already present. Understood, and nothing to do.
            return understood(changes);
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        String[] parts = edgeId.split("\\|");
        if ("type".equals(parts[0]) && parts.length == 3) {
            OWLNamedIndividual individual =
                    factory.getOWLNamedIndividual(IRI.create(parts[1]));
            OWLClass type = factory.getOWLClass(IRI.create(parts[2]));
            changes.addAll(declareIfAbsent(ontology, individual));
            changes.addAll(declareIfAbsent(ontology, type));
            addIfAbsent(changes, ontology, factory.getOWLClassAssertionAxiom(type, individual));
            return understood(changes);
        }
        if ("rest".equals(parts[0]) && parts.length == 5) {
            OWLClass source = factory.getOWLClass(IRI.create(parts[2]));
            OWLObjectProperty property = factory.getOWLObjectProperty(IRI.create(parts[3]));
            OWLClass target = factory.getOWLClass(IRI.create(parts[4]));
            changes.addAll(declareIfAbsent(ontology, source));
            changes.addAll(declareIfAbsent(ontology, property));
            changes.addAll(declareIfAbsent(ontology, target));
            OWLClassExpression restriction = "only".equals(parts[1])
                    ? factory.getOWLObjectAllValuesFrom(property, target)
                    : factory.getOWLObjectSomeValuesFrom(property, target);
            addIfAbsent(changes, ontology,
                    factory.getOWLSubClassOfAxiom(source, restriction));
            return understood(changes);
        }
        return skipped("an edge id this version cannot rebuild: " + edgeId);
    }

    private static Inbound removeProperty(OWLOntology ontology, String edgeId) {
        if (edgeId == null || edgeId.trim().isEmpty()) {
            return skipped("a removeProperty with no id");
        }
        if (edgeId.startsWith(WEB_SUBCLASS_PREFIX)) {
            OWLSubClassOfAxiom axiom = subClassOfAxiomWithWebId(ontology, edgeId);
            if (axiom == null) {
                // Already absent, or never existed here. Either way the ontologies agree.
                return understood(Collections.<OWLOntologyChange>emptyList());
            }
            return understood(one(new RemoveAxiom(ontology, axiom)));
        }
        try {
            // Returns only axioms the ontology actually has, so a stale removal is a no-op.
            return understood(new ArrayList<OWLOntologyChange>(
                    AxiomRemoval.removalsFor(ontology, edgeId)));
        } catch (AxiomRemoval.UnknownEdgeException unknown) {
            return skipped("a removeProperty for an id this version does not understand: "
                    + edgeId);
        }
    }

    /**
     * Finds the subclass axiom the web client would have given {@code webId}.
     *
     * <p>The format is {@code subClassOf_<child>_<parent>}, and IRIs may themselves contain
     * underscores, so splitting the string is ambiguous - {@code subClassOf_a_b_c} could be
     * {@code (a, b_c)} or {@code (a_b, c)}. Reconstructing the id from each axiom the ontology
     * holds and comparing is unambiguous, and an ontology's subclass axioms are indexed, so this
     * is cheap enough for something that happens once per retraction.
     *
     * @return the matching axiom, or null when this ontology has no such subclass relation
     */
    static OWLSubClassOfAxiom subClassOfAxiomWithWebId(OWLOntology ontology, String webId) {
        for (OWLSubClassOfAxiom axiom : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            if (axiom.getSubClass().isAnonymous() || axiom.getSuperClass().isAnonymous()) {
                continue;
            }
            String candidate = webSubClassId(
                    axiom.getSubClass().asOWLClass().getIRI().toString(),
                    axiom.getSuperClass().asOWLClass().getIRI().toString());
            if (candidate.equals(webId)) {
                return axiom;
            }
        }
        return null;
    }

    /** The id {@code useOperationSync.ts} derives for a subclass edge. */
    static String webSubClassId(String childIri, String parentIri) {
        return WEB_SUBCLASS_PREFIX + childIri + "_" + parentIri;
    }

    // ------------------------------------------------------------------ helpers

    private static List<OWLOntologyChange> relabel(OWLOntology ontology, OWLDataFactory factory,
            Map<String, Object> data) {
        String iri = text(data, "iri");
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        if (iri == null) {
            return changes;
        }
        Object updates = data.get("updates");
        String label = null;
        if (updates instanceof Map) {
            Object value = ((Map<?, ?>) updates).get("label");
            label = value == null ? null : String.valueOf(value);
        }
        if (label == null) {
            // An update that changes something other than the label - a colour, a position.
            // Nothing in the ontology to change, and nothing lost.
            return changes;
        }
        IRI subject = IRI.create(iri);
        for (OWLAnnotationAssertionAxiom existing : ontology.getAnnotationAssertionAxioms(subject)) {
            if (RDFS_LABEL.equals(existing.getProperty().getIRI())) {
                changes.add(new RemoveAxiom(ontology, existing));
            }
        }
        addIfAbsent(changes, ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), subject, factory.getOWLLiteral(label)));
        return changes;
    }

    private static List<OWLOntologyChange> declareIfAbsent(OWLOntology ontology,
            OWLEntity entity) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        if (!ontology.isDeclared(entity)) {
            changes.add(new AddAxiom(ontology, ontology.getOWLOntologyManager()
                    .getOWLDataFactory().getOWLDeclarationAxiom(entity)));
        }
        return changes;
    }

    /**
     * Retracts an entity the way Protege's own delete does: its declaration and every axiom that
     * mentions it.
     *
     * <p>Leaving the referencing axioms would strand a subclass axiom pointing at an undeclared
     * class, and the two copies of the ontology would then disagree about more than the one
     * entity. Protege's undo can put it all back, which is the safety net that makes applying a
     * peer's deletion the right call rather than a reckless one.
     */
    private static List<OWLOntologyChange> retract(OWLOntology ontology, OWLEntity entity) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        for (OWLAxiom axiom : ontology.getReferencingAxioms(entity)) {
            changes.add(new RemoveAxiom(ontology, axiom));
        }
        return changes;
    }

    private static void addIfAbsent(List<OWLOntologyChange> changes, OWLOntology ontology,
            OWLAxiom axiom) {
        if (!ontology.containsAxiom(axiom)) {
            changes.add(new AddAxiom(ontology, axiom));
        }
    }

    private static void putGeometry(Map<String, Object> data, NodeHint hint, double defaultWidth,
            double defaultHeight) {
        data.put("x", hint == null ? 0.0 : hint.getX());
        data.put("y", hint == null ? 0.0 : hint.getY());
        data.put("w", hint == null ? defaultWidth : hint.getWidth());
        data.put("h", hint == null ? defaultHeight : hint.getHeight());
        if (hint != null && hint.getColour() != null) {
            data.put("color", hint.getColour());
        }
    }

    private static List<OWLOntologyChange> one(OWLOntologyChange change) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        changes.add(change);
        return changes;
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put(key, value);
        return data;
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String literalText(org.semanticweb.owlapi.model.OWLAnnotationValue value) {
        return value instanceof OWLLiteral ? ((OWLLiteral) value).getLiteral()
                : String.valueOf(value);
    }

    private static Outbound mapped(String type, String userId, Map<String, Object> data) {
        return new Outbound(OntologyOperation.local(type, userId, data), null);
    }

    private static Outbound unmappable(String reason) {
        return new Outbound(null, reason);
    }

    private static Inbound understood(List<OWLOntologyChange> changes) {
        return new Inbound(changes, null);
    }

    private static Inbound skipped(String reason) {
        return new Inbound(Collections.<OWLOntologyChange>emptyList(), reason);
    }

    /** {@code SubClassOf} rather than {@code OWLSubClassOfAxiomImpl}, for a user-facing message. */
    private static String readableAxiomType(OWLAxiom axiom) {
        return axiom.getAxiomType().getName();
    }

    private static String readableClassExpression(OWLClassExpression expression) {
        return expression.getClassExpressionType().getName();
    }

    /** The part of an IRI a person recognises, for messages. */
    private static String shortForm(String iri) {
        return DisplayLabels.shortNameOf(IRI.create(iri));
    }
}
