package de.fizkarlsruhe.ise.ontoboard.model;

import de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectAllValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

/**
 * Projects an ontology onto the canvas, restricted to the entities the user has chosen to
 * show (spec section 5.3). An edge is emitted only when both endpoints are on the canvas.
 */
public final class OntologyProjection {

    private OntologyProjection() {
    }

    /**
     * A node that knows whether its term carries an editorial note, and what it is called.
     *
     * <p>The identifier comes along because a label alone is the one thing an editor cannot
     * cite: labels are edited, identifiers are not, and a reviewer asking "which term is that?"
     * wants {@code obo:BFO_0000023}, not "role". Protege's own entity list shows both.
     */
    private static CanvasNode noted(OWLOntology ontology,
            org.semanticweb.owlapi.model.IRI entity, NodeKind kind, String label) {
        return new CanvasNode(iri(entity), kind, label, EditorNotes.allNotesOn(ontology, entity))
                .withCurie(Curies.curieFor(ontology, entity));
    }

    /**
     * Every entity worth putting on a board, for "Add all".
     *
     * <p>Classes, individuals, object properties and data properties - the four kinds
     * {@link #project} draws. The list used to be classes and individuals only, which meant the
     * one bulk gesture in the tool silently withheld two of the four kinds it can render: press
     * "Add all" on an ontology built around its object properties and you got the class tree and
     * no properties, with nothing saying why. The property hierarchy the canvas can draw was
     * unreachable except by dragging each property across by hand.
     *
     * <p>Datatypes are deliberately absent. They appear as nodes only where a data property edge
     * puts them there, and adding every datatype an ontology mentions would put xsd:string on the
     * board with nothing attached to it.
     */
    public static Set<String> everythingWorthShowing(OWLOntology ontology) {
        return termIdentifiers(ontology, Imports.EXCLUDED);
    }

    /**
     * What a board that shows the whole ontology draws, in the order it is drawn in when there is
     * too much to draw: the ontology's own terms, then what it imports, each alphabetically, and
     * none that was hidden.
     */
    public static List<String> termsToShow(OWLOntology ontology, Collection<String> hidden) {
        Set<String> own = termIdentifiers(ontology, Imports.EXCLUDED);
        Set<String> skip = new java.util.HashSet<String>(hidden);
        List<String> ordered = new ArrayList<String>();
        for (String iri : new java.util.TreeSet<String>(own)) {
            if (!skip.contains(iri)) {
                ordered.add(iri);
            }
        }
        for (String iri : new java.util.TreeSet<String>(
                termIdentifiers(ontology, Imports.INCLUDED))) {
            if (!own.contains(iri) && !skip.contains(iri)) {
                ordered.add(iri);
            }
        }
        return ordered;
    }

    /**
     * Where a term new to a board goes: just right of its first parent already placed - a class's
     * superclass, an individual's class, a property's super-property - and below anything already
     * there. Null when none of its parents is placed.
     */
    public static double[] besideItsParent(OWLOntology ontology, String iri,
            de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout layout) {
        IRI term = IRI.create(iri);
        org.semanticweb.owlapi.model.OWLDataFactory df =
                ontology.getOWLOntologyManager().getOWLDataFactory();
        List<IRI> parents = new ArrayList<IRI>();
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (OWLSubClassOfAxiom axiom : part.getSubClassAxiomsForSubClass(df.getOWLClass(term))) {
                if (!axiom.getSuperClass().isAnonymous()) {
                    parents.add(axiom.getSuperClass().asOWLClass().getIRI());
                }
            }
            for (OWLClassAssertionAxiom axiom
                    : part.getClassAssertionAxioms(df.getOWLNamedIndividual(term))) {
                if (!axiom.getClassExpression().isAnonymous()) {
                    parents.add(axiom.getClassExpression().asOWLClass().getIRI());
                }
            }
            for (OWLSubObjectPropertyOfAxiom axiom
                    : part.getObjectSubPropertyAxiomsForSubProperty(df.getOWLObjectProperty(term))) {
                if (!axiom.getSuperProperty().isAnonymous()) {
                    parents.add(axiom.getSuperProperty().asOWLObjectProperty().getIRI());
                }
            }
            for (OWLSubDataPropertyOfAxiom axiom
                    : part.getDataSubPropertyAxiomsForSubProperty(df.getOWLDataProperty(term))) {
                parents.add(axiom.getSuperProperty().asOWLDataProperty().getIRI());
            }
        }
        for (IRI parent : parents) {
            de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout.NodeLayout at =
                    layout.nodes.get(parent.toString());
            if (at == null) {
                continue;
            }
            double x = at.x + (at.w <= 0 ? 160 : at.w) + 70;
            double y = at.y;
            for (int tries = 0; tries < 400 && taken(layout, x, y); tries++) {
                y += 76;
            }
            return new double[] {x, y};
        }
        return null;
    }

    /** The first free spot at or below ({@code x}, {@code y}) - for a box placed beside another. */
    public static double[] besideItsParentAt(
            de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout layout, double x, double y) {
        double down = y;
        for (int tries = 0; tries < 400 && taken(layout, x, down); tries++) {
            down += 76;
        }
        return new double[] {x, down};
    }

    /**
     * {@code root} and everything under it: its subclasses, theirs, and so on, and the
     * individuals of any of them - what folding a class on the board puts out of sight.
     */
    public static Set<String> everythingUnder(OWLOntology ontology, String root) {
        Set<String> found = new java.util.LinkedHashSet<String>();
        if (ontology == null || root == null) {
            return found;
        }
        org.semanticweb.owlapi.model.OWLDataFactory df =
                ontology.getOWLOntologyManager().getOWLDataFactory();
        java.util.Deque<IRI> waiting = new java.util.ArrayDeque<IRI>();
        waiting.add(IRI.create(root));
        found.add(root);
        while (!waiting.isEmpty()) {
            OWLClass at = df.getOWLClass(waiting.poll());
            for (OWLOntology part : ontology.getImportsClosure()) {
                for (OWLSubClassOfAxiom axiom : part.getSubClassAxiomsForSuperClass(at)) {
                    if (!axiom.getSubClass().isAnonymous()
                            && found.add(axiom.getSubClass().asOWLClass().getIRI().toString())) {
                        waiting.add(axiom.getSubClass().asOWLClass().getIRI());
                    }
                }
                for (OWLClassAssertionAxiom axiom : part.getClassAssertionAxioms(at)) {
                    if (!axiom.getIndividual().isAnonymous()) {
                        found.add(axiom.getIndividual().asOWLNamedIndividual().getIRI()
                                .toString());
                    }
                }
            }
        }
        return found;
    }

    private static boolean taken(de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout layout,
            double x, double y) {
        java.awt.geom.Rectangle2D wanted = new java.awt.geom.Rectangle2D.Double(x, y, 180, 60);
        for (de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout.NodeLayout node
                : layout.nodes.values()) {
            if (node != null && wanted.intersects(node.x, node.y, node.w <= 0 ? 160 : node.w,
                    node.h <= 0 ? 60 : node.h)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The identifiers of every drawable term, without building nodes or reading labels.
     *
     * <p>Three callers want exactly this and none of them wants the labels: "Add all", telling local
     * terms from imported ones on every refresh, and deciding which sidecar entries are stale. Reading
     * a label per term per refresh would put a {@code DisplayLabels} lookup on the redraw path for
     * every term in the file.
     *
     * <p><b>Why four queries rather than {@code getSignature(imports)}.</b> In OWL API 4.5.29 a call to
     * {@code getSignature(Imports.INCLUDED)} permanently pollutes the same ontology's cached
     * {@code Imports.EXCLUDED} signature - measured, in this order:
     *
     * <pre>
     *   getSignature(EXCLUDED)  -&gt; [edit#Mine]
     *   getSignature(INCLUDED)  -&gt; [edit#Mine, obo/BFO_0000002]
     *   getSignature(EXCLUDED)  -&gt; [edit#Mine, obo/BFO_0000002]   &lt;- wrong
     * </pre>
     *
     * The per-kind queries are not affected. This is not a theoretical concern: the search box asks
     * with imports included, and "Add all" asks without - so on the old code, searching once made
     * "Add all" offer every term in every import for the rest of the session, which on an ODK project
     * is tens of thousands of nodes on one click. {@code OntologyProjectionTest} pins it.
     */
    public static Set<String> termIdentifiers(OWLOntology ontology, Imports imports) {
        Set<String> iris = new java.util.LinkedHashSet<String>();
        if (ontology == null) {
            return iris;
        }
        for (OWLClass cls : ontology.getClassesInSignature(imports)) {
            iris.add(iri(cls.getIRI()));
        }
        for (OWLNamedIndividual individual : ontology.getIndividualsInSignature(imports)) {
            iris.add(iri(individual.getIRI()));
        }
        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature(imports)) {
            iris.add(iri(property.getIRI()));
        }
        for (OWLDataProperty property : ontology.getDataPropertiesInSignature(imports)) {
            iris.add(iri(property.getIRI()));
        }
        return iris;
    }

    /**
     * The same four kinds, as nodes with their labels, for searching.
     *
     * <p>The canvas search box needs to answer a question the board alone cannot: the user typed
     * "margherita", nothing on the board matches, and the two possible reasons - the term does not
     * exist, or it exists and has not been added - lead to opposite next actions. Answering it needs
     * every term's <em>label</em>, not just its IRI, because a label is what people type.
     *
     * <p>This is deliberately the same walk {@link #everythingWorthShowing} does, and that method now
     * delegates to it, so "what counts as a term worth showing" is decided once. It had already
     * drifted once - the list was classes and individuals only while {@link #project} drew four
     * kinds - and a second copy of the rule in a search index is a second chance to drift, in a place
     * where the symptom would be a term that cannot be found and no error anywhere.
     *
     * <p>No edges are built, which is what makes it cheap enough to hold in memory for a large
     * ontology: the search box never draws anything, it only reports and centres.
     */
    public static List<CanvasNode> everyTermWorthShowing(OWLOntology ontology) {
        return everyTermWorthShowing(ontology, Imports.EXCLUDED);
    }

    /**
     * The same, over the edit file alone or over its imports as well.
     *
     * <p>The two callers want different answers and both are right. <em>Add all</em> asks for the edit
     * file: an ODK project importing BFO, ChEBI and the OBO relations would otherwise put tens of
     * thousands of terms on a board on one click, which is not a diagram. The search box asks for
     * everything, because "that term is not on the board" is only a useful answer if it is true of the
     * ontology the user is actually working in - imports and all - and because Ctrl+Enter can now draw
     * an imported term.
     */
    public static List<CanvasNode> everyTermWorthShowing(OWLOntology ontology, Imports imports) {
        List<CanvasNode> terms = new ArrayList<>();
        if (ontology == null) {
            return terms;
        }
        // Four queries rather than one over the whole signature, for the reason set out on
        // termIdentifiers: getSignature(INCLUDED) corrupts the cached EXCLUDED answer in OWL API
        // 4.5.29, and this method is the caller that asks with imports included.
        for (OWLClass cls : ontology.getClassesInSignature(imports)) {
            terms.add(new CanvasNode(iri(cls.getIRI()), NodeKind.CLASS,
                    DisplayLabels.forEntity(ontology, cls)));
        }
        for (OWLNamedIndividual individual : ontology.getIndividualsInSignature(imports)) {
            terms.add(new CanvasNode(iri(individual.getIRI()), NodeKind.INDIVIDUAL,
                    DisplayLabels.forEntity(ontology, individual)));
        }
        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature(imports)) {
            terms.add(new CanvasNode(iri(property.getIRI()), NodeKind.OBJECT_PROPERTY,
                    DisplayLabels.forEntity(ontology, property)));
        }
        for (OWLDataProperty property : ontology.getDataPropertiesInSignature(imports)) {
            terms.add(new CanvasNode(iri(property.getIRI()), NodeKind.DATA_PROPERTY,
                    DisplayLabels.forEntity(ontology, property)));
        }
        return terms;
    }

    /**
     * The identifiers the edit file itself declares, for telling local terms from imported ones.
     *
     * <p>Collected once per projection rather than asked per term: {@code isDeclared} walks the
     * imports closure, and a board of a hundred terms over an ODK project's imports would ask it a
     * hundred times per refresh - and a refresh happens on every edit anywhere in Prot&eacute;g&eacute;.
     */
    private static Set<String> localSignature(OWLOntology ontology) {
        return termIdentifiers(ontology, Imports.EXCLUDED);
    }

    /** The node, marked imported unless the edit file declares it. */
    private static CanvasNode marked(Set<String> local, CanvasNode node) {
        return local.contains(node.getId()) ? node : node.asImported();
    }

    public static Projection project(OWLOntology ontology, Set<String> onCanvasIris) {
        // WITHOUT anonymous class expressions, which is what every caller got before 1.123.0 and
        // what the tests that count nodes and edges were written against. The canvas passes the
        // reader's own choice; see the three-argument form.
        return project(ontology, onCanvasIris, false);
    }

    /**
     * The same, saying whether anonymous class expressions get nodes of their own.
     *
     * <p><b>Why it is a choice and not simply on.</b> It is the difference between a diagram that
     * omits a third of what an ontology says and one that is slower to draw. Measured on MWO
     * 3.0.1 with every term on the board: 524 nodes and 489 edges without, 668 and 904 with, and
     * a render of 1,241ms against 2,580ms - on a path that runs after every edit anywhere in
     * Protege. On the boards people actually keep, it is free: the worst single class in MWO
     * expands to 27 nodes, which renders in 38ms.
     */
    public static Projection project(OWLOntology ontology, Set<String> onCanvasIris,
            boolean drawExpressions) {
        List<CanvasNode> nodes = new ArrayList<>();
        List<CanvasEdge> edges = new ArrayList<>();

        // Nothing open is a state the canvas has to survive, not an error. Protege can have no
        // active ontology - while it is switching between them, and before the first one is
        // loaded - and the view renders on construction. Until 1.74.0 this threw a
        // NullPointerException there, and Protege answered it by replacing the Schema Canvas
        // with "An error occurred whilst creating the view" for the rest of the session.
        if (ontology == null || onCanvasIris == null) {
            return new Projection(nodes, edges);
        }

        // Imports included since 1.61.0. Dragging bfo:continuant onto the board used to do nothing
        // visible at all: this method looked only at the edit file's own signature, so no node was
        // drawn, and pruneStaleMembers then quietly removed the entry from the sidecar. For an ODK
        // project - which is what this plugin scaffolds - most of the terms a curator refers to are
        // imported, so "the canvas cannot draw them" excluded the ordinary case.
        //
        // Safe to widen because every loop here is gated on membership: nothing appears unless the
        // user asked for it. What must NOT widen is everythingWorthShowing, which drives "Add all".
        Set<String> local = localSignature(ontology);
        for (OWLClass cls : ontology.getClassesInSignature(Imports.INCLUDED)) {
            if (isOn(onCanvasIris, cls.getIRI())) {
                // The summary is for classes only: superclasses, equivalents and disjoints
                // are class notions, and an individual's types are already drawn as edges.
                // ≡ before a defined class's name, as Protégé's class tree marks one: its arrows
                // are necessary AND sufficient, and drawn alike they looked like a class that
                // only had necessary conditions.
                nodes.add(marked(local, new CanvasNode(iri(cls.getIRI()), NodeKind.CLASS,
                        (isDefined(ontology, cls) ? "\u2261 " : "")
                                + DisplayLabels.forEntity(ontology, cls),
                        EditorNotes.allNotesOn(ontology, cls.getIRI()))
                        .withCurie(Curies.curieFor(ontology, cls.getIRI()))
                        .withSummary(TermSummary.of(ontology, cls))));
            }
        }
        for (OWLNamedIndividual ind : ontology.getIndividualsInSignature(Imports.INCLUDED)) {
            if (isOn(onCanvasIris, ind.getIRI())) {
                nodes.add(marked(local, noted(ontology, ind.getIRI(), NodeKind.INDIVIDUAL,
                        DisplayLabels.forEntity(ontology, ind))));
            }
        }

        for (OWLObjectProperty property
                : ontology.getObjectPropertiesInSignature(Imports.INCLUDED)) {
            if (isOn(onCanvasIris, property.getIRI())) {
                // The characteristics ride on the label: transitive, functional and the rest
                // are facts about the property itself, so there is no second term for an edge
                // to reach and an edge would misstate the arity. 25 such axioms on MWO 3.0.1
                // were hover text, which is to say invisible.
                nodes.add(marked(local, noted(ontology, property.getIRI(),
                        NodeKind.OBJECT_PROPERTY,
                        DisplayLabels.forEntity(ontology, property)
                                + characteristicsOf(ontology, property.getIRI()))));
            }
        }
        for (OWLDataProperty property : ontology.getDataPropertiesInSignature(Imports.INCLUDED)) {
            if (isOn(onCanvasIris, property.getIRI())) {
                nodes.add(marked(local, noted(ontology, property.getIRI(), NodeKind.DATA_PROPERTY,
                        DisplayLabels.forEntity(ontology, property)
                                + (ontology.getFunctionalDataPropertyAxioms(property).isEmpty()
                                        ? "" : " (functional)"))));
            }
        }

        collectSubClassEdges(ontology, onCanvasIris, nodes, edges, drawExpressions);
        collectEquivalenceEdges(ontology, onCanvasIris, nodes, edges, drawExpressions);
        collectSubPropertyEdges(ontology, onCanvasIris, edges);
        collectDomainRangeEdges(ontology, onCanvasIris, nodes, edges, drawExpressions);
        collectLegacyDomainRangeEdges(ontology, onCanvasIris, edges);
        collectTypeEdges(ontology, onCanvasIris, edges);
        collectTheRest(ontology, onCanvasIris, nodes, edges, drawExpressions);
        collectRules(ontology, onCanvasIris, nodes, edges);
        collectTheLast(ontology, onCanvasIris, nodes, edges);

        // One cell per id. An import closure restates axioms across modules - on a real ODK
        // project's board ten ids arrive two to four times - and SchemaGraph.render inserts an
        // mxCell per list entry while keeping only the last in its id index. The extra cells are
        // drawn, hit-tested and exported, and the ones the index has forgotten cannot be
        // selected or deleted. CanvasEdge equality is the id alone, so a set is the whole fix.
        return new Projection(nodes,
                new ArrayList<CanvasEdge>(new java.util.LinkedHashSet<CanvasEdge>(edges)));
    }

    /**
     * Property hierarchy edges, for properties the user has put on the board.
     *
     * <p>Before this, {@code rdfs:subPropertyOf} was not drawn at all - there was no node for a
     * property and no edge kind for the relation, so a property hierarchy was invisible on a canvas
     * that otherwise showed the class hierarchy prominently. Both ends must be on the board: an
     * arrow to a property nobody asked to see would drag unrequested nodes onto the diagram, which
     * is the opt-in rule this canvas is built around.
     *
     * <p>Anonymous property expressions - an inverse, for instance - are skipped. They have no IRI,
     * so there is nothing to draw and nothing to identify the edge by.
     */
    private static void collectSubPropertyEdges(OWLOntology ontology, Set<String> on,
            List<CanvasEdge> edges) {
        for (OWLSubObjectPropertyOfAxiom axiom
                : ontology.getAxioms(AxiomType.SUB_OBJECT_PROPERTY, Imports.INCLUDED)) {
            if (axiom.getSubProperty().isAnonymous() || axiom.getSuperProperty().isAnonymous()) {
                continue;
            }
            addSubPropertyEdge(on, edges,
                    axiom.getSubProperty().asOWLObjectProperty().getIRI(),
                    axiom.getSuperProperty().asOWLObjectProperty().getIRI());
        }
        for (OWLSubDataPropertyOfAxiom axiom
                : ontology.getAxioms(AxiomType.SUB_DATA_PROPERTY, Imports.INCLUDED)) {
            if (axiom.getSubProperty().isAnonymous() || axiom.getSuperProperty().isAnonymous()) {
                continue;
            }
            addSubPropertyEdge(on, edges,
                    axiom.getSubProperty().asOWLDataProperty().getIRI(),
                    axiom.getSuperProperty().asOWLDataProperty().getIRI());
        }
        // An equivalence is two sub-property arrows, the way collectEquivalenceEdges draws one
        // between classes: it is exactly both, and a reader of a hierarchy reads the pair so.
        for (org.semanticweb.owlapi.model.OWLNaryPropertyAxiom<?> axiom
                : equivalentProperties(ontology)) {
            List<IRI> named = new ArrayList<IRI>();
            List<IRI> inverted = new ArrayList<IRI>();
            for (Object property : axiom.getProperties()) {
                org.semanticweb.owlapi.model.OWLPropertyExpression expression =
                        (org.semanticweb.owlapi.model.OWLPropertyExpression) property;
                if (!expression.isAnonymous()) {
                    named.add(((org.semanticweb.owlapi.model.OWLEntity) expression).getIRI());
                } else if (expression instanceof org.semanticweb.owlapi.model.OWLObjectInverseOf
                        && !((org.semanticweb.owlapi.model.OWLObjectInverseOf) expression)
                                .getInverse().isAnonymous()) {
                    inverted.add(((org.semanticweb.owlapi.model.OWLObjectInverseOf) expression)
                            .getInverse().asOWLObjectProperty().getIRI());
                }
            }
            for (IRI one : named) {
                for (IRI other : named) {
                    if (!one.equals(other)) {
                        addSubPropertyEdge(on, edges, one, other);
                    }
                }
                // p equivalent to the inverse of q is what InverseObjectProperties(p q) says,
                // and OBI writes it that way: drawn as the same line.
                for (IRI other : inverted) {
                    if (!one.equals(other) && isOn(on, one) && isOn(on, other)) {
                        IRI from = one.toString().compareTo(other.toString()) <= 0 ? one : other;
                        IRI to = from.equals(one) ? other : one;
                        addUnlessAlreadyThere(edges, new CanvasEdge("inv|" + from + "|" + to,
                                iri(from), iri(to), "", CanvasEdge.Kind.INVERSE));
                    }
                }
            }
        }
    }

    private static List<org.semanticweb.owlapi.model.OWLNaryPropertyAxiom<?>> equivalentProperties(
            OWLOntology ontology) {
        List<org.semanticweb.owlapi.model.OWLNaryPropertyAxiom<?>> axioms =
                new ArrayList<org.semanticweb.owlapi.model.OWLNaryPropertyAxiom<?>>();
        axioms.addAll(ontology.getAxioms(AxiomType.EQUIVALENT_OBJECT_PROPERTIES, Imports.INCLUDED));
        axioms.addAll(ontology.getAxioms(AxiomType.EQUIVALENT_DATA_PROPERTIES, Imports.INCLUDED));
        return axioms;
    }

    private static void addSubPropertyEdge(Set<String> on, List<CanvasEdge> edges, IRI sub,
            IRI sup) {
        if (!isOn(on, sub) || !isOn(on, sup)) {
            return;
        }
        edges.add(new CanvasEdge("subprop|" + iri(sub) + "|" + iri(sup), iri(sub), iri(sup),
                "rdfs:subPropertyOf", CanvasEdge.Kind.SUB_PROPERTY));
    }

    /** Thin caller: production code always scans every SubClassOf axiom in the ontology. */
    private static void collectSubClassEdges(OWLOntology ontology, Set<String> on,
            List<CanvasNode> nodes, List<CanvasEdge> edges, boolean drawExpressions) {
        // Included, so the hierarchy between two imported terms is drawn. Both ends still have to
        // be on the board, so this cannot pull in an import's whole class tree.
        collectSubClassEdges(ontology,
                ontology.getAxioms(AxiomType.SUBCLASS_OF, Imports.INCLUDED), on, nodes, edges,
                drawExpressions);
    }

    /**
     * Restrictions written in a class's definition rather than in a SubClassOf axiom.
     *
     * <p>This is where most real ontologies keep them, and the canvas could not see any of it.
     * Measured on the project this was reported against: of its 74 SubClassOf axioms, <b>none</b>
     * has a restriction as its superclass, while 32 of its 34 EquivalentClasses axioms contain
     * one - 51 authored relationships in all, every one of them invisible. The board showed its
     * property boxes floating with nothing attached, which is precisely the complaint that
     * produced this work, and the boxes were not the cause.
     *
     * <p>An equivalence says more than a subclass axiom - the condition is sufficient as well as
     * necessary - and the arrow drawn here does not yet say so. That is deliberate for one
     * release: a separate edge kind means a new colour, a legend row and a style, and the legend
     * has a completeness guard that must be changed in the same commit. The tooltip names the
     * construct in the meantime.
     */
    private static void collectEquivalenceEdges(OWLOntology ontology, Set<String> on,
            List<CanvasNode> nodes, List<CanvasEdge> edges, boolean drawExpressions) {
        for (org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom axiom
                : ontology.getAxioms(AxiomType.EQUIVALENT_CLASSES, Imports.INCLUDED)) {
            if (drawExpressions && axiom.getNamedClasses().isEmpty()) {
                // No named class on either side - "having a role of R is having a part in P"
                // - so each side is its node, and the two are joined both ways, the way a named
                // equivalence is drawn.
                List<String> sides = new ArrayList<String>();
                for (OWLClassExpression side : axiom.getClassExpressionsAsList()) {
                    String node = expressionNode(side, nodes);
                    expand(on, node, side, 1, nodes, edges);
                    sides.add(node);
                }
                for (String one : sides) {
                    for (String other : sides) {
                        if (!one.equals(other)) {
                            addUnlessAlreadyThere(edges, new CanvasEdge("eqx|" + one + "|" + other,
                                    one, other, "", CanvasEdge.Kind.SUBCLASS));
                        }
                    }
                }
                continue;
            }
            for (OWLClassExpression named : axiom.getClassExpressions()) {
                if (named.isAnonymous()) {
                    continue;
                }
                IRI subject = named.asOWLClass().getIRI();
                if (!isOn(on, subject)) {
                    continue;
                }
                for (OWLClassExpression other : axiom.getClassExpressions()) {
                    // A named operand goes the same way as an anonymous one now. Until 1.122.0
                    // this branch was `if (other.isAnonymous())`, so EquivalentClasses(A B) - two
                    // named classes, the plainest equivalence there is - drew nothing at all.
                    walk(ontology, on, subject, other, PropertyEdgeId.Origin.EQUIVALENCE,
                            nodes, edges, drawExpressions);
                }
            }
        }
    }

    /**
     * One class expression, as far as it carries relations between two terms on the board.
     *
     * <p>Recursive because a conjunction is how an ontology says "all of these at once", and the
     * relations are the operands. Nothing else recurses: a union is a disjunction and drawing its
     * operands as if they all held would be a false statement about the ontology, and a
     * complement is a negation, which an arrow cannot express at all. Both are left undrawn
     * rather than approximated.
     *
     * <p>The filler must already be on the board. That is this canvas's opt-in rule and it is not
     * relaxed here: an arrow may reveal a relation between two terms the user chose, never drag a
     * third onto the diagram.
     */
    private static void walk(OWLOntology ontology, Set<String> on, IRI subject,
            OWLClassExpression expression, PropertyEdgeId.Origin origin,
            List<CanvasNode> nodes, List<CanvasEdge> edges, boolean drawExpressions) {
        if (!expression.isAnonymous()) {
            // A NAMED OPERAND, which until 1.122.0 fell off the end of this method drawing
            // nothing. SubClassOf(A, ObjectIntersectionOf(B, R some C)) asserts A subClassOf B
            // just as plainly as SubClassOf(A B) does, and the hierarchy edge was simply lost -
            // measured on MWO 3.0.1, 41 of them, 32 inside a SubClassOf intersection and 9
            // inside an equivalence. Nothing reached here with a named expression before: the
            // only callers are the intersection branch below and the two collectors, both of
            // which handle a named side themselves and return.
            subclassBetweenNamed(on, subject, expression.asOWLClass().getIRI(), origin, edges);
            return;
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectIntersectionOf) {
            for (OWLClassExpression operand
                    : ((org.semanticweb.owlapi.model.OWLObjectIntersectionOf) expression)
                            .getOperands()) {
                // A conjunct of a SubClassOf is no longer the whole superclass, so it stops being
                // retractable - see PropertyEdgeId.Origin. Inside an equivalence it was never
                // retractable, so the origin stays as it is - except for a NAMED conjunct, the
                // genus of a definition: CheesyPizza = Pizza and (hasTopping some Cheese) makes
                // CheesyPizza a kind of Pizza and not the other way round, and the equivalence
                // origin drew both arrows - "pizza is a kind of cheesy pizza", 46 times on MWO.
                walk(ontology, on, subject, operand,
                        origin == PropertyEdgeId.Origin.SUBCLASS
                                || (origin == PropertyEdgeId.Origin.EQUIVALENCE
                                        && !operand.isAnonymous())
                                ? PropertyEdgeId.Origin.CONJUNCT
                                : origin,
                        nodes, edges, drawExpressions);
            }
            return;
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLQuantifiedObjectRestriction) {
            // One branch for some, only, min, max and exactly: OWLObjectCardinalityRestriction
            // extends OWLQuantifiedObjectRestriction, so all five answer getProperty and
            // getFiller. Checked with javap rather than assumed.
            org.semanticweb.owlapi.model.OWLQuantifiedObjectRestriction restriction =
                    (org.semanticweb.owlapi.model.OWLQuantifiedObjectRestriction) expression;
            objectEdge(ontology, on, subject, origin, restriction.getProperty(),
                    restriction.getFiller(), qualifierOf(expression), edges,
                    drawExpressions, nodes);
            return;
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectHasValue) {
            org.semanticweb.owlapi.model.OWLObjectHasValue has =
                    (org.semanticweb.owlapi.model.OWLObjectHasValue) expression;
            if (has.getProperty().isAnonymous() || has.getFiller().isAnonymous()) {
                return;
            }
            addObjectEdge(ontology, on, subject, origin, "value",
                    has.getProperty().asOWLObjectProperty().getIRI(),
                    has.getFiller().asOWLNamedIndividual().getIRI(), edges);
            return;
        }
        if (expression instanceof OWLDataSomeValuesFrom) {
            OWLDataSomeValuesFrom data = (OWLDataSomeValuesFrom) expression;
            if (data.getProperty().isAnonymous() || !data.getFiller().isDatatype()) {
                // Not a plain datatype range - a facet-restricted DatatypeRestriction, or an
                // anonymous property. An unsupported shape skips itself and nothing else.
                return;
            }
            dataEdge(ontology, subject, origin, data.getProperty().asOWLDataProperty().getIRI(),
                    data.getFiller().asOWLDatatype().getIRI(), nodes, edges);
            return;
        }
        // EVERYTHING ELSE USED TO FALL OFF THE END OF THIS METHOD. A union, a complement, an
        // enumeration, a hasSelf, a facet-restricted data range: no edge, no node, no trace, and
        // a page that said the graph was the axioms the pattern asserts. Measured on MWO 3.0.1,
        // DUL and NFDIcore, these are the shapes behind the 34% of restrictions that were
        // invisible. The expression gets a node of its own and is opened up from there.
        if (drawExpressions) {
            String expressionId = expressionNode(expression, nodes);
            addUnlessAlreadyThere(edges, new CanvasEdge(
                    "exps|" + subject + "|" + expressionId, iri(subject), expressionId, "",
                    CanvasEdge.Kind.SUBCLASS));
            if (origin == PropertyEdgeId.Origin.EQUIVALENCE) {
                addUnlessAlreadyThere(edges, new CanvasEdge(
                        "exps|" + expressionId + "|" + subject, expressionId, iri(subject), "",
                        CanvasEdge.Kind.SUBCLASS));
            }
            expand(on, expressionId, expression, 1, nodes, edges);
        }
    }

    /**
     * A restriction whose filler is anonymous: the arrow keeps its label and gains a target.
     *
     * <p>Kept apart from {@link #objectEdge} rather than folded into it because the id is
     * different in kind: {@code PropertyEdgeId}'s filler slot holds an IRI everywhere else, and
     * {@code AxiomRemoval} builds an {@code OWLClass} out of it. An expression id in that slot
     * would make a retraction offer to delete an axiom that does not exist, so it carries the
     * {@code exp|} prefix and {@code AxiomRemoval} refuses it by name.
     */
    private static void drawnAsExpression(Set<String> on, IRI subject,
            PropertyEdgeId.Origin origin,
            org.semanticweb.owlapi.model.OWLObjectPropertyExpression property,
            OWLClassExpression filler, String qualifier, List<CanvasNode> nodes,
            List<CanvasEdge> edges) {
        if (!isOn(on, subject)) {
            return;
        }
        String fillerId = filler.isAnonymous()
                ? expressionNode(filler, nodes)
                : iri(filler.asOWLClass().getIRI());
        if (filler.isAnonymous()) {
            expand(on, fillerId, filler, 1, nodes, edges);
        } else if (!isOn(on, filler.asOWLClass().getIRI())) {
            return;
        }
        addUnlessAlreadyThere(edges, new CanvasEdge(
                PropertyEdgeId.of(origin, qualifier, iri(subject),
                        property.isAnonymous()
                                ? iri(property.getNamedProperty().getIRI())
                                : iri(property.asOWLObjectProperty().getIRI()),
                        fillerId),
                iri(subject), fillerId,
                propertyLabel(property) + " " + qualifier, CanvasEdge.Kind.OBJECT_PROPERTY));
    }

    /**
     * The hierarchy edge between two named classes, both ways round for an equivalence.
     *
     * <p><b>Why an equivalence is drawn as two subclass arrows.</b> {@code A equivalentTo B} is
     * exactly {@code A subClassOf B} and {@code B subClassOf A}, so two arrows say what the axiom
     * says and a reader of any hierarchy notation reads the pair as equivalence. The alternative
     * is a new {@code CanvasEdge.Kind}, and that is not a free choice here: a kind needs a
     * colour, a style, a legend row, a tooltip, a DOT shape and a change to the legend's
     * completeness guard, all in the same commit, which is the reason
     * {@link #collectEquivalenceEdges} gives for not having added one already.
     *
     * <p>Ids are the plain {@code sub|} ones so a relation asserted twice - once on its own and
     * once inside a conjunction, which is redundant but legal and not rare - is one arrow rather
     * than two drawn on top of each other. That is what the duplicate check is for.
     */
    private static void subclassBetweenNamed(Set<String> on, IRI subject, IRI other,
            PropertyEdgeId.Origin origin, List<CanvasEdge> edges) {
        if (subject.equals(other) || !isOn(on, subject) || !isOn(on, other)) {
            return;
        }
        addUnlessAlreadyThere(edges, new CanvasEdge("sub|" + subject + "|" + other,
                iri(subject), iri(other), "", CanvasEdge.Kind.SUBCLASS));
        if (origin == PropertyEdgeId.Origin.EQUIVALENCE) {
            addUnlessAlreadyThere(edges, new CanvasEdge("sub|" + other + "|" + subject,
                    iri(other), iri(subject), "", CanvasEdge.Kind.SUBCLASS));
        }
    }

    /**
     * Adds an edge unless one with the same id is already there.
     *
     * <p>A linear scan, because the only callers are the two shapes that can restate a relation
     * an earlier collector already drew, and a board is a few hundred edges. The id is what
     * {@code SchemaGraph.render} keys its cells by, so a duplicate id is two cells drawn one on
     * top of the other - invisible, and one of them unreachable.
     */
    private static void addUnlessAlreadyThere(List<CanvasEdge> edges, CanvasEdge edge) {
        for (CanvasEdge already : edges) {
            if (already.getId().equals(edge.getId())) {
                return;
            }
        }
        edges.add(edge);
    }

    // ---------- the relations that used to be hover text only ----------

    /**
     * Disjointness, inverse pairs, property assertions, property chains and keys.
     *
     * <p>Five axiom types that said something about two terms on the board and drew nothing.
     * Measured on MWO 3.0.1 before this: 15 DisjointClasses, 38 InverseObjectProperties, 3
     * SubPropertyChainOf, 5 ObjectPropertyAssertion and 0 HasKey, every one of them invisible.
     *
     * <p><b>All of them obey the board's opt-in rule</b>: both ends must already be on the
     * canvas. That is what keeps disjointness from doubling the edge count of a taxonomy - a
     * class is disjoint from a great many things it is never drawn beside.
     */
    private static void collectTheRest(OWLOntology ontology, Set<String> on,
            List<CanvasNode> nodes, List<CanvasEdge> edges, boolean drawExpressions) {
        for (org.semanticweb.owlapi.model.OWLDisjointClassesAxiom axiom
                : ontology.getAxioms(AxiomType.DISJOINT_CLASSES, Imports.INCLUDED)) {
            List<String> named = new ArrayList<String>();
            for (OWLClassExpression operand : axiom.getClassExpressions()) {
                if (!operand.isAnonymous() && isOn(on, operand.asOWLClass().getIRI())) {
                    named.add(iri(operand.asOWLClass().getIRI()));
                } else if (operand.isAnonymous() && drawExpressions) {
                    // BFO says a continuant is disjoint with anything that is part of an
                    // occurrent. With class expressions drawn, that restriction has its node.
                    String node = expressionNode(operand, nodes);
                    expand(on, node, operand, 1, nodes, edges);
                    named.add(node);
                }
            }
            // Pairwise. An n-ary DisjointClasses means every pair is disjoint, and drawing a hub
            // would invent a node the ontology does not have; n is 2 for all 15 of MWO's.
            for (int first = 0; first < named.size(); first++) {
                for (int second = first + 1; second < named.size(); second++) {
                    String left = named.get(first);
                    String right = named.get(second);
                    // One edge per pair, in a fixed order, so the same axiom does not draw two
                    // lines on top of each other when it is stated from both sides.
                    String from = left.compareTo(right) <= 0 ? left : right;
                    String to = from.equals(left) ? right : left;
                    addUnlessAlreadyThere(edges, new CanvasEdge(
                            "disj|" + from + "|" + to, from, to, "",
                            CanvasEdge.Kind.DISJOINT));
                }
            }
        }

        for (org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom axiom
                : ontology.getAxioms(AxiomType.INVERSE_OBJECT_PROPERTIES, Imports.INCLUDED)) {
            if (axiom.getFirstProperty().isAnonymous() || axiom.getSecondProperty().isAnonymous()) {
                continue;
            }
            IRI first = axiom.getFirstProperty().asOWLObjectProperty().getIRI();
            IRI second = axiom.getSecondProperty().asOWLObjectProperty().getIRI();
            if (!isOn(on, first) || !isOn(on, second) || first.equals(second)) {
                continue;
            }
            IRI from = first.toString().compareTo(second.toString()) <= 0 ? first : second;
            IRI to = from.equals(first) ? second : first;
            addUnlessAlreadyThere(edges, new CanvasEdge(
                    "inv|" + from + "|" + to, iri(from), iri(to), "", CanvasEdge.Kind.INVERSE));
        }

        for (org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom axiom
                : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION, Imports.INCLUDED)) {
            if (axiom.getSubject().isAnonymous() || axiom.getObject().isAnonymous()
                    || axiom.getProperty().isAnonymous()) {
                continue;
            }
            IRI subject = axiom.getSubject().asOWLNamedIndividual().getIRI();
            IRI object = axiom.getObject().asOWLNamedIndividual().getIRI();
            IRI property = axiom.getProperty().asOWLObjectProperty().getIRI();
            if (!isOn(on, subject) || !isOn(on, object)) {
                continue;
            }
            // A fact between two individuals, not a restriction on a class - so it carries the
            // property's name and nothing about quantification.
            addUnlessAlreadyThere(edges, new CanvasEdge(
                    "opa|" + subject + "|" + property + "|" + object,
                    iri(subject), iri(object), localName(property),
                    CanvasEdge.Kind.OBJECT_PROPERTY));
        }

        for (org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom axiom
                : ontology.getAxioms(AxiomType.SUB_PROPERTY_CHAIN_OF, Imports.INCLUDED)) {
            if (axiom.getSuperProperty().isAnonymous()) {
                continue;
            }
            IRI superProperty = axiom.getSuperProperty().asOWLObjectProperty().getIRI();
            if (!isOn(on, superProperty)) {
                continue;
            }
            // A CHAIN IS NOT A TWO-ENDED SHAPE, which is why it was never drawn: hasPart o
            // hasPart -> hasPart has three terms and an order. It reuses the node that already
            // stands for "something the ontology says that is not a term" - the one anonymous
            // class expressions get - with the links numbered so the order survives.
            StringBuilder reads = new StringBuilder("chain: ");
            List<IRI> links = new ArrayList<IRI>();
            boolean drawable = true;
            for (org.semanticweb.owlapi.model.OWLObjectPropertyExpression link
                    : axiom.getPropertyChain()) {
                if (link.isAnonymous()) {
                    drawable = false;
                    break;
                }
                links.add(link.asOWLObjectProperty().getIRI());
                // By label: "chain: BFO_0000051 o RO_0000057" said nothing to anybody without
                // the identifiers by heart.
                reads.append(links.size() > 1 ? " o " : "").append(
                        DisplayLabels.forEntity(ontology, link.asOWLObjectProperty()));
            }
            if (!drawable || links.isEmpty()) {
                continue;
            }
            String chainId = EXPRESSION_PREFIX + digestOf(axiom.toString());
            CanvasNode chain = new CanvasNode(chainId, NodeKind.EXPRESSION, reads.toString());
            if (!nodes.contains(chain)) {
                nodes.add(chain);
            }
            addUnlessAlreadyThere(edges, new CanvasEdge("chain|" + chainId + "|" + superProperty,
                    chainId, iri(superProperty), "", CanvasEdge.Kind.SUB_PROPERTY));
            for (int at = 0; at < links.size(); at++) {
                if (!isOn(on, links.get(at))) {
                    continue;
                }
                addUnlessAlreadyThere(edges, new CanvasEdge(
                        "chain|" + chainId + "|" + at + "|" + links.get(at),
                        iri(links.get(at)), chainId, String.valueOf(at + 1),
                        CanvasEdge.Kind.SUB_PROPERTY));
            }
        }

        for (org.semanticweb.owlapi.model.OWLHasKeyAxiom axiom
                : ontology.getAxioms(AxiomType.HAS_KEY, Imports.INCLUDED)) {
            if (axiom.getClassExpression().isAnonymous()) {
                continue;
            }
            IRI owner = axiom.getClassExpression().asOWLClass().getIRI();
            if (!isOn(on, owner)) {
                continue;
            }
            for (org.semanticweb.owlapi.model.OWLObjectPropertyExpression key
                    : axiom.getObjectPropertyExpressions()) {
                if (key.isAnonymous() || !isOn(on, key.asOWLObjectProperty().getIRI())) {
                    continue;
                }
                IRI property = key.asOWLObjectProperty().getIRI();
                addUnlessAlreadyThere(edges, new CanvasEdge("key|" + owner + "|" + property,
                        iri(owner), iri(property), "key", CanvasEdge.Kind.OBJECT_PROPERTY));
            }
        }
    }

    /**
     * The characteristics of a property, as a suffix on its label.
     *
     * <p>Transitive, functional, symmetric and the rest are properties OF a property - there is
     * no second term for an edge to reach, so an edge would be a lie about arity. They were
     * hover text, which means invisible: 25 such axioms on MWO 3.0.1. On the label they are
     * visible at a glance and cost no edges.
     */
    static String characteristicsOf(OWLOntology ontology, IRI property) {
        org.semanticweb.owlapi.model.OWLObjectProperty owl = ontology.getOWLOntologyManager()
                .getOWLDataFactory().getOWLObjectProperty(property);
        List<String> found = new ArrayList<String>();
        if (!ontology.getTransitiveObjectPropertyAxioms(owl).isEmpty()) {
            found.add("transitive");
        }
        if (!ontology.getSymmetricObjectPropertyAxioms(owl).isEmpty()) {
            found.add("symmetric");
        }
        if (!ontology.getAsymmetricObjectPropertyAxioms(owl).isEmpty()) {
            found.add("asymmetric");
        }
        if (!ontology.getReflexiveObjectPropertyAxioms(owl).isEmpty()) {
            found.add("reflexive");
        }
        if (!ontology.getIrreflexiveObjectPropertyAxioms(owl).isEmpty()) {
            found.add("irreflexive");
        }
        if (!ontology.getFunctionalObjectPropertyAxioms(owl).isEmpty()) {
            found.add("functional");
        }
        if (!ontology.getInverseFunctionalObjectPropertyAxioms(owl).isEmpty()) {
            found.add("inverse functional");
        }
        if (found.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder(" (");
        for (int at = 0; at < found.size(); at++) {
            joined.append(at > 0 ? ", " : "").append(found.get(at));
        }
        return joined.append(")").toString();
    }

    // ---------- anonymous class expressions ----------

    /** Every expression node id starts with this. Nothing else on the board does. */
    public static final String EXPRESSION_PREFIX = "exp|";

    /**
     * How deep into a nested expression the canvas will draw before it stops.
     *
     * <p>Expressions are trees, so this is not cycle protection - it is a readability limit. The
     * deepest in the local files is three ({@code R some (B and (S some C))}); four leaves one
     * level of headroom, and a diagram that needed five would be unreadable as a diagram.
     */
    private static final int DEEPEST = 4;

    /**
     * The node that stands for one anonymous class expression, minted if it is new.
     *
     * <p><b>The id is a digest of the expression's own syntax</b>, so the same expression is the
     * same node on every render - which is what lets a layout keep it in place and a selection
     * survive a refresh - and two different expressions are two nodes even when they read alike.
     * The alternative, numbering them in encounter order, renumbers the whole board whenever an
     * axiom is added above them.
     *
     * <p>Not an IRI, and deliberately not shaped like one: {@code AxiomRemoval} must be able to
     * tell an arrow that points at a term from one that points at scaffolding, because it builds
     * {@code OWLClass(IRI.create(filler))} out of an edge id and that is nonsense for an
     * expression.
     */
    private static String expressionNode(OWLClassExpression expression, List<CanvasNode> nodes) {
        String id = EXPRESSION_PREFIX + digestOf(expression.toString());
        CanvasNode node = new CanvasNode(id, NodeKind.EXPRESSION, operatorOf(expression));
        if (!nodes.contains(node)) {
            nodes.add(node);
        }
        return id;
    }

    /** A short stable hash. SHA-1 of the syntax, first twelve hex characters. */
    private static String digestOf(String syntax) {
        try {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-1");
            byte[] hash = sha.digest(syntax.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int at = 0; at < 6; at++) {
                hex.append(String.format("%02x", hash[at]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException noSha) {
            // Every JRE has SHA-1; this is here because the checked exception is.
            return Integer.toHexString(syntax.hashCode());
        }
    }

    /**
     * What to write inside an expression node: the operator, not the whole expression.
     *
     * <p>The operands are drawn as the nodes it points at, so repeating them in the label would
     * say everything twice and make the box as wide as the sentence.
     */
    private static String operatorOf(OWLClassExpression expression) {
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectUnionOf) {
            return "or";
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectIntersectionOf) {
            return "and";
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectComplementOf) {
            return "not";
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectOneOf) {
            return "one of";
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectHasSelf) {
            return "Self";
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLQuantifiedObjectRestriction) {
            org.semanticweb.owlapi.model.OWLQuantifiedObjectRestriction restriction =
                    (org.semanticweb.owlapi.model.OWLQuantifiedObjectRestriction) expression;
            return propertyLabel(restriction.getProperty()) + " " + qualifierOf(expression);
        }
        if (expression instanceof OWLDataSomeValuesFrom) {
            return "data restriction";
        }
        return "expression";
    }

    /** {@code hasTopping}, or {@code inverse hasTopping} for an inverse property. */
    private static String propertyLabel(
            org.semanticweb.owlapi.model.OWLObjectPropertyExpression property) {
        if (!property.isAnonymous()) {
            return localName(property.asOWLObjectProperty().getIRI());
        }
        return "inverse " + localName(property.getNamedProperty().getIRI());
    }

    /**
     * Draws what an expression is made of, as edges out of its node.
     *
     * <p>Recursive, because an operand can be another expression - which is the case that was
     * undrawable before: a restriction whose filler is anonymous had nothing to point at.
     * A named operand is drawn only when it is on the board, like everything else here; an
     * anonymous one always gets a node, because leaving it out would make the operator a lie.
     */
    private static void expand(Set<String> on, String parentId, OWLClassExpression expression,
            int depth, List<CanvasNode> nodes, List<CanvasEdge> edges) {
        if (depth >= DEEPEST) {
            return;
        }
        for (OWLClassExpression operand : operandsOf(expression)) {
            String targetId;
            if (operand.isAnonymous()) {
                targetId = expressionNode(operand, nodes);
                expand(on, targetId, operand, depth + 1, nodes, edges);
            } else {
                IRI named = operand.asOWLClass().getIRI();
                if (!isOn(on, named)) {
                    continue;
                }
                targetId = iri(named);
            }
            addUnlessAlreadyThere(edges, new CanvasEdge(
                    "eop|" + parentId + "|" + targetId, parentId, targetId, "",
                    CanvasEdge.Kind.SUBCLASS));
        }
        for (org.semanticweb.owlapi.model.OWLIndividual member : membersOf(expression)) {
            if (member.isAnonymous() || !isOn(on, member.asOWLNamedIndividual().getIRI())) {
                continue;
            }
            String targetId = iri(member.asOWLNamedIndividual().getIRI());
            addUnlessAlreadyThere(edges, new CanvasEdge(
                    "eop|" + parentId + "|" + targetId, parentId, targetId, "",
                    CanvasEdge.Kind.TYPE));
        }
    }

    /** The class expressions one expression is built from, in the order it names them. */
    private static List<OWLClassExpression> operandsOf(OWLClassExpression expression) {
        if (expression instanceof org.semanticweb.owlapi.model.OWLNaryBooleanClassExpression) {
            return new ArrayList<OWLClassExpression>(
                    ((org.semanticweb.owlapi.model.OWLNaryBooleanClassExpression) expression)
                            .getOperands());
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectComplementOf) {
            return java.util.Collections.singletonList(
                    ((org.semanticweb.owlapi.model.OWLObjectComplementOf) expression).getOperand());
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLQuantifiedObjectRestriction) {
            return java.util.Collections.singletonList(
                    ((org.semanticweb.owlapi.model.OWLQuantifiedObjectRestriction) expression)
                            .getFiller());
        }
        return java.util.Collections.emptyList();
    }

    /** The individuals an enumeration or a hasValue names. */
    private static java.util.Collection<org.semanticweb.owlapi.model.OWLIndividual> membersOf(
            OWLClassExpression expression) {
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectOneOf) {
            return new ArrayList<org.semanticweb.owlapi.model.OWLIndividual>(
                    ((org.semanticweb.owlapi.model.OWLObjectOneOf) expression).getIndividuals());
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectHasValue) {
            return java.util.Collections.singletonList(
                    ((org.semanticweb.owlapi.model.OWLObjectHasValue) expression).getFiller());
        }
        return java.util.Collections.<org.semanticweb.owlapi.model.OWLIndividual>emptyList();
    }

    /** {@code some}, {@code only}, {@code min2}, {@code max1}, {@code exactly3}. */
    private static String qualifierOf(OWLClassExpression expression) {
        if (expression instanceof OWLObjectSomeValuesFrom) {
            return "some";
        }
        if (expression instanceof OWLObjectAllValuesFrom) {
            return "only";
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectCardinalityRestriction) {
            org.semanticweb.owlapi.model.OWLObjectCardinalityRestriction cardinality =
                    (org.semanticweb.owlapi.model.OWLObjectCardinalityRestriction) expression;
            String shape = expression instanceof org.semanticweb.owlapi.model.OWLObjectMinCardinality
                    ? "min"
                    : expression instanceof org.semanticweb.owlapi.model.OWLObjectMaxCardinality
                            ? "max" : "exactly";
            return shape + cardinality.getCardinality();
        }
        return "some";
    }

    /** A restriction whose filler is a named class. */
    private static void objectEdge(OWLOntology ontology, Set<String> on, IRI subject,
            PropertyEdgeId.Origin origin,
            org.semanticweb.owlapi.model.OWLObjectPropertyExpression property,
            OWLClassExpression filler, String qualifier, List<CanvasEdge> edges,
            boolean drawExpressions, List<CanvasNode> nodes) {
        if (property.isAnonymous() || filler.isAnonymous()) {
            // AN ANONYMOUS FILLER HAS NO BOX TO POINT AT, which is why this used to return and
            // draw nothing - 39 of MWO 3.0.1's 116 restriction occurrences, 34%. With
            // expressions on, the filler gets a node and the arrow gets its target; the
            // property keeps its label whether or not it is itself anonymous, so an inverse
            // property reads as "inverse hasPart some" rather than disappearing.
            if (drawExpressions) {
                drawnAsExpression(on, subject, origin, property, filler, qualifier, nodes, edges);
            }
            return;
        }
        addObjectEdge(ontology, on, subject, origin, qualifier,
                property.asOWLObjectProperty().getIRI(), filler.asOWLClass().getIRI(), edges);
    }

    /**
     * The one place a property arrow is built, so its id and its label are decided once.
     *
     * <p>The id of the two shapes that existed before this is byte-identical to what it was:
     * {@code rest|some|...} and {@code rest|only|...} travel between peers in a live session and
     * are parsed by {@code AxiomRemoval}, and an id that changed shape would reach an older build
     * as an unrecognised edge. Everything new is behind {@code pe|}, where an older peer meets a
     * clean refusal instead of a misreading.
     */
    private static void addObjectEdge(OWLOntology ontology, Set<String> on, IRI subject,
            PropertyEdgeId.Origin origin, String qualifier, IRI property, IRI filler,
            List<CanvasEdge> edges) {
        if (!isOn(on, filler)) {
            return;
        }
        boolean legacy = origin == PropertyEdgeId.Origin.SUBCLASS
                && ("some".equals(qualifier) || "only".equals(qualifier));
        String id = legacy
                ? "rest|" + qualifier + "|" + subject + "|" + property + "|" + filler
                : PropertyEdgeId.of(origin, qualifier, iri(subject), iri(property), iri(filler));
        // A scoped domain - anything with an R to a B is an A - drew exactly like the
        // restriction A subClassOf R some B, which says something else entirely. Same arrow,
        // since it is the one the user drew to make it; a label that says which.
        String label = origin == PropertyEdgeId.Origin.SCOPED_DOMAIN
                ? arrowLabel(ontology, property, "some") + " (scoped domain)"
                : arrowLabel(ontology, property, qualifier);
        edges.add(new CanvasEdge(id, iri(subject), iri(filler), label,
                CanvasEdge.Kind.OBJECT_PROPERTY));
    }

    /** A data restriction, which also puts its datatype on the board as a leaf. */
    private static void dataEdge(OWLOntology ontology, IRI subject,
            PropertyEdgeId.Origin origin, IRI property, IRI datatype, List<CanvasNode> nodes,
            List<CanvasEdge> edges) {
        // Never gated on membership: a datatype is a leaf pulled in by showing the class, not a
        // first-class canvas citizen.
        CanvasNode datatypeNode =
                new CanvasNode(iri(datatype), NodeKind.DATATYPE, localName(datatype));
        if (!nodes.contains(datatypeNode)) {
            nodes.add(datatypeNode);
        }
        String id = origin == PropertyEdgeId.Origin.SUBCLASS
                ? "data|" + subject + "|" + property + "|" + datatype
                : PropertyEdgeId.of(origin, "dsome", iri(subject), iri(property), iri(datatype));
        edges.add(new CanvasEdge(id, iri(subject), iri(datatype),
                arrowLabel(ontology, property, "some"), CanvasEdge.Kind.DATA_PROPERTY));
    }

    /**
     * What an arrow is called.
     *
     * <p>The property's label, not its IRI fragment. Every node on the board has shown its label
     * since the canvas was written, and the arrows between them showed {@code RO_0002202} - so on
     * an OBO or ODK ontology, which is what this plugin is for, the one part of the diagram that
     * names a relation was the one part written in numbers.
     *
     * <p>The qualifier is appended only when it is not the plain existential, because a diagram
     * where every arrow carries "(some)" says nothing it did not already say.
     */
    private static String arrowLabel(OWLOntology ontology, IRI property, String qualifier) {
        String name = DisplayLabels.forEntity(ontology,
                ontology.getOWLOntologyManager().getOWLDataFactory()
                        .getOWLObjectProperty(property));
        if ("some".equals(qualifier)) {
            return name;
        }
        if (qualifier.startsWith("min") || qualifier.startsWith("max")
                || qualifier.startsWith("exactly")) {
            String shape = qualifier.replaceAll("[0-9]+$", "");
            String count = qualifier.substring(shape.length());
            return name + " (" + shape + " " + count + ")";
        }
        return name + " (" + qualifier + ")";
    }

    /**
     * SubClassOf, in all the shapes it is written in.
     *
     * <p>{@code SubClassOf(A B)} is the hierarchy. Anything anonymous on the superclass side
     * goes to {@link #walk}, which handles a restriction standing alone, a conjunction of them,
     * the five object qualifiers and a data restriction. The superclass side used to be read
     * three ways - some, only, and a data some - and anything else was dropped without trace.
     *
     * <p>An anonymous SUBclass side is read too, in one shape:
     * {@code SubClassOf(ObjectSomeValuesFrom(R B) A)}. That is a scoped domain, and it is one of
     * the six axioms this plugin offers to write when a user draws an arrow - so until now the
     * canvas could write an axiom it could not then draw, and the arrow vanished at the next
     * refresh with the axiom silently in the file. Two of the other five are fixed by the
     * cardinality branch in {@link #walk}; the remaining two are global domain and range, which
     * {@link #collectLegacyDomainRangeEdges} still draws only as a pair.
     *
     * <p>Package-private (rather than the private the rest of this class uses) so tests can
     * pass an explicit, ordered {@link Collection} of axioms and pin an exact iteration order.
     * {@link OWLOntology#getAxioms(org.semanticweb.owlapi.model.AxiomType)} returns an
     * unordered {@code Set} whose real iteration order is an implementation detail of
     * owlapi-impl's internal hash map - not something a test should depend on to reproduce a
     * specific code path.
     */
    static void collectSubClassEdges(OWLOntology ontology, Collection<OWLSubClassOfAxiom> axioms,
            Set<String> on, List<CanvasNode> nodes, List<CanvasEdge> edges) {
        // The shape the tests call, which pins an exact axiom order. Expressions off, so the
        // boards those tests describe are the ones they were written against.
        collectSubClassEdges(ontology, axioms, on, nodes, edges, false);
    }

    static void collectSubClassEdges(OWLOntology ontology, Collection<OWLSubClassOfAxiom> axioms,
            Set<String> on, List<CanvasNode> nodes, List<CanvasEdge> edges,
            boolean drawExpressions) {
        for (OWLSubClassOfAxiom axiom : axioms) {
            OWLClassExpression sub = axiom.getSubClass();
            OWLClassExpression sup = axiom.getSuperClass();

            if (sub.isAnonymous()) {
                if (!sup.isAnonymous() && isScopedDomain(sub)) {
                    scopedDomain(ontology, on, sub, sup.asOWLClass().getIRI(), edges);
                } else if (drawExpressions) {
                    // ANY OTHER GENERAL CLASS AXIOM - "anything that is both A and B is a C" -
                    // was dropped without a word. Its subclass side is its node, the arrow runs
                    // from there.
                    String from = expressionNode(sub, nodes);
                    expand(on, from, sub, 1, nodes, edges);
                    String to;
                    if (sup.isAnonymous()) {
                        to = expressionNode(sup, nodes);
                        expand(on, to, sup, 1, nodes, edges);
                    } else if (isOn(on, sup.asOWLClass().getIRI())) {
                        to = iri(sup.asOWLClass().getIRI());
                    } else {
                        continue;
                    }
                    addUnlessAlreadyThere(edges, new CanvasEdge("gci|" + from + "|" + to, from,
                            to, "", CanvasEdge.Kind.SUBCLASS));
                }
                continue;
            }
            IRI subIri = sub.asOWLClass().getIRI();
            if (!isOn(on, subIri)) {
                continue;
            }
            if (!sup.isAnonymous()) {
                IRI supIri = sup.asOWLClass().getIRI();
                if (isOn(on, supIri)) {
                    edges.add(new CanvasEdge("sub|" + subIri + "|" + supIri,
                            iri(subIri), iri(supIri), "", CanvasEdge.Kind.SUBCLASS));
                }
                continue;
            }
            walk(ontology, on, subIri, sup, PropertyEdgeId.Origin.SUBCLASS, nodes, edges,
                    drawExpressions);
        }
    }

    /** Whether a class is equivalent to a class expression - defined, not only described. */
    static boolean isDefined(OWLOntology ontology, OWLClass cls) {
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom axiom
                    : part.getEquivalentClassesAxioms(cls)) {
                for (OWLClassExpression side : axiom.getClassExpressions()) {
                    if (side.isAnonymous()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The shape {@link #scopedDomain} draws: some of a named property to a named class. */
    private static boolean isScopedDomain(OWLClassExpression anonymousSubclass) {
        return anonymousSubclass instanceof OWLObjectSomeValuesFrom
                && !((OWLObjectSomeValuesFrom) anonymousSubclass).getProperty().isAnonymous()
                && !((OWLObjectSomeValuesFrom) anonymousSubclass).getFiller().isAnonymous();
    }

    /**
     * {@code SubClassOf(ObjectSomeValuesFrom(R B) A)} - "anything with an R to a B is an A".
     *
     * <p>Drawn as {@code A --R--> B}, which is the arrow the user drew to produce it. The axiom
     * reads in the other direction, and that is why this is a branch of its own rather than part
     * of the walk: the subject of the arrow is the superclass here, not the subclass.
     */
    private static void scopedDomain(OWLOntology ontology, Set<String> on,
            OWLClassExpression anonymousSubclass, IRI superClass, List<CanvasEdge> edges) {
        if (!isOn(on, superClass)
                || !(anonymousSubclass instanceof OWLObjectSomeValuesFrom)) {
            return;
        }
        OWLObjectSomeValuesFrom some = (OWLObjectSomeValuesFrom) anonymousSubclass;
        if (some.getProperty().isAnonymous() || some.getFiller().isAnonymous()) {
            return;
        }
        addObjectEdge(ontology, on, superClass, PropertyEdgeId.Origin.SCOPED_DOMAIN, "some",
                some.getProperty().asOWLObjectProperty().getIRI(),
                some.getFiller().asOWLClass().getIRI(), edges);
    }

    /**
     * SWRL rules, each as a node reading the rule - "rule: person(?x) ∧ has age(?x, ?a) →
     * adult(?x)" - with a line from each term its body uses ("if") and to each term its head
     * concludes ("then").
     *
     * <p>The last kind of logical axiom the board did not draw: on two published ontologies
     * built on BFO, every other one was drawn and four rules in each were not. A rule has no two
     * ends to draw a line between, so it takes the node property chains already use - something
     * the ontology says that is not a term.
     */
    private static void collectRules(OWLOntology ontology, Set<String> on,
            List<CanvasNode> nodes, List<CanvasEdge> edges) {
        for (org.semanticweb.owlapi.model.SWRLRule rule
                : ontology.getAxioms(AxiomType.SWRL_RULE, Imports.INCLUDED)) {
            Set<IRI> body = new java.util.LinkedHashSet<IRI>();
            Set<IRI> head = new java.util.LinkedHashSet<IRI>();
            String reads = "rule: " + atoms(ontology, rule.getBody(), body) + " \u2192 "
                    + atoms(ontology, rule.getHead(), head);
            boolean touchesBoard = false;
            for (IRI term : body) {
                touchesBoard |= isOn(on, term);
            }
            for (IRI term : head) {
                touchesBoard |= isOn(on, term);
            }
            if (!touchesBoard) {
                continue;
            }
            String ruleId = EXPRESSION_PREFIX + digestOf(rule.toString());
            CanvasNode node = new CanvasNode(ruleId, NodeKind.EXPRESSION, reads);
            if (!nodes.contains(node)) {
                nodes.add(node);
            }
            for (IRI term : body) {
                if (isOn(on, term)) {
                    addUnlessAlreadyThere(edges, new CanvasEdge("rule|" + ruleId + "|if|" + term,
                            iri(term), ruleId, "if", CanvasEdge.Kind.RULE_PART));
                }
            }
            for (IRI term : head) {
                if (isOn(on, term)) {
                    addUnlessAlreadyThere(edges, new CanvasEdge("rule|" + ruleId + "|then|" + term,
                            ruleId, iri(term), "then", CanvasEdge.Kind.RULE_PART));
                }
            }
        }
    }

    /**
     * The axioms the board drew nothing for, and said nothing about: a disjoint union, same and
     * different individuals, a data value, a negative assertion, disjoint properties. None is in
     * the two ontologies measured, so each is drawn with a shape the board already has.
     */
    private static void collectTheLast(OWLOntology ontology, Set<String> on,
            List<CanvasNode> nodes, List<CanvasEdge> edges) {
        org.semanticweb.owlapi.model.OWLDataFactory df =
                ontology.getOWLOntologyManager().getOWLDataFactory();
        // DisjointUnion(A B C): B and C are kinds of A, and no B is a C.
        for (org.semanticweb.owlapi.model.OWLDisjointUnionAxiom axiom
                : ontology.getAxioms(AxiomType.DISJOINT_UNION, Imports.INCLUDED)) {
            IRI whole = axiom.getOWLClass().getIRI();
            List<IRI> parts = new ArrayList<IRI>();
            for (OWLClassExpression part : axiom.getClassExpressions()) {
                if (!part.isAnonymous() && isOn(on, part.asOWLClass().getIRI())) {
                    parts.add(part.asOWLClass().getIRI());
                }
            }
            for (IRI part : parts) {
                if (isOn(on, whole)) {
                    addUnlessAlreadyThere(edges, new CanvasEdge("sub|" + part + "|" + whole,
                            iri(part), iri(whole), "", CanvasEdge.Kind.SUBCLASS));
                }
            }
            pairwise(parts, "disj|", "", CanvasEdge.Kind.DISJOINT, edges);
        }
        for (org.semanticweb.owlapi.model.OWLSameIndividualAxiom axiom
                : ontology.getAxioms(AxiomType.SAME_INDIVIDUAL, Imports.INCLUDED)) {
            pairwise(namedIndividuals(axiom.getIndividuals(), on), "same|", "same as",
                    CanvasEdge.Kind.OBJECT_PROPERTY, edges);
        }
        for (org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom axiom
                : ontology.getAxioms(AxiomType.DIFFERENT_INDIVIDUALS, Imports.INCLUDED)) {
            pairwise(namedIndividuals(axiom.getIndividuals(), on), "diff|", "different from",
                    CanvasEdge.Kind.OBJECT_PROPERTY, edges);
        }
        for (org.semanticweb.owlapi.model.OWLDisjointObjectPropertiesAxiom axiom
                : ontology.getAxioms(AxiomType.DISJOINT_OBJECT_PROPERTIES, Imports.INCLUDED)) {
            List<IRI> named = new ArrayList<IRI>();
            for (org.semanticweb.owlapi.model.OWLObjectPropertyExpression property
                    : axiom.getProperties()) {
                if (!property.isAnonymous() && isOn(on, property.asOWLObjectProperty().getIRI())) {
                    named.add(property.asOWLObjectProperty().getIRI());
                }
            }
            pairwise(named, "pdisj|", "", CanvasEdge.Kind.DISJOINT, edges);
        }
        for (org.semanticweb.owlapi.model.OWLDisjointDataPropertiesAxiom axiom
                : ontology.getAxioms(AxiomType.DISJOINT_DATA_PROPERTIES, Imports.INCLUDED)) {
            List<IRI> named = new ArrayList<IRI>();
            for (org.semanticweb.owlapi.model.OWLDataPropertyExpression property
                    : axiom.getProperties()) {
                if (isOn(on, property.asOWLDataProperty().getIRI())) {
                    named.add(property.asOWLDataProperty().getIRI());
                }
            }
            pairwise(named, "pdisj|", "", CanvasEdge.Kind.DISJOINT, edges);
        }
        for (org.semanticweb.owlapi.model.OWLNegativeObjectPropertyAssertionAxiom axiom
                : ontology.getAxioms(AxiomType.NEGATIVE_OBJECT_PROPERTY_ASSERTION,
                        Imports.INCLUDED)) {
            if (axiom.getSubject().isAnonymous() || axiom.getObject().isAnonymous()
                    || axiom.getProperty().isAnonymous()) {
                continue;
            }
            IRI subject = axiom.getSubject().asOWLNamedIndividual().getIRI();
            IRI object = axiom.getObject().asOWLNamedIndividual().getIRI();
            IRI property = axiom.getProperty().asOWLObjectProperty().getIRI();
            if (isOn(on, subject) && isOn(on, object)) {
                addUnlessAlreadyThere(edges, new CanvasEdge("nopa|" + subject + "|" + property
                        + "|" + object, iri(subject), iri(object),
                        "not " + arrowLabel(ontology, property, "some"),
                        CanvasEdge.Kind.OBJECT_PROPERTY));
            }
        }
        // A data value: a box with the value in it, off the individual it belongs to.
        for (org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom axiom
                : ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION, Imports.INCLUDED)) {
            valueEdge(ontology, on, axiom.getSubject(), axiom.getProperty(), axiom.getObject(),
                    "", nodes, edges, df);
        }
        for (org.semanticweb.owlapi.model.OWLNegativeDataPropertyAssertionAxiom axiom
                : ontology.getAxioms(AxiomType.NEGATIVE_DATA_PROPERTY_ASSERTION,
                        Imports.INCLUDED)) {
            valueEdge(ontology, on, axiom.getSubject(), axiom.getProperty(), axiom.getObject(),
                    "not ", nodes, edges, df);
        }
    }

    private static void valueEdge(OWLOntology ontology, Set<String> on,
            org.semanticweb.owlapi.model.OWLIndividual subject,
            org.semanticweb.owlapi.model.OWLDataPropertyExpression property,
            org.semanticweb.owlapi.model.OWLLiteral value, String not, List<CanvasNode> nodes,
            List<CanvasEdge> edges, org.semanticweb.owlapi.model.OWLDataFactory df) {
        if (subject.isAnonymous() || !isOn(on, subject.asOWLNamedIndividual().getIRI())) {
            return;
        }
        IRI who = subject.asOWLNamedIndividual().getIRI();
        IRI what = property.asOWLDataProperty().getIRI();
        String text = value.getLiteral();
        String id = "lit|" + digestOf(who + "|" + what + "|" + value);
        CanvasNode box = new CanvasNode(id, NodeKind.LITERAL,
                text.length() > 60 ? text.substring(0, 59) + "\u2026" : text);
        if (!nodes.contains(box)) {
            nodes.add(box);
        }
        addUnlessAlreadyThere(edges, new CanvasEdge("dpa|" + id, iri(who), id,
                not + DisplayLabels.forEntity(ontology, df.getOWLDataProperty(what)),
                CanvasEdge.Kind.DATA_PROPERTY));
    }

    private static List<IRI> namedIndividuals(
            Collection<org.semanticweb.owlapi.model.OWLIndividual> individuals, Set<String> on) {
        List<IRI> named = new ArrayList<IRI>();
        for (org.semanticweb.owlapi.model.OWLIndividual individual : individuals) {
            if (!individual.isAnonymous()
                    && isOn(on, individual.asOWLNamedIndividual().getIRI())) {
                named.add(individual.asOWLNamedIndividual().getIRI());
            }
        }
        return named;
    }

    /** One edge per pair, in a fixed order, so an axiom stated twice is one line. */
    private static void pairwise(List<IRI> terms, String prefix, String label,
            CanvasEdge.Kind kind, List<CanvasEdge> edges) {
        for (int first = 0; first < terms.size(); first++) {
            for (int second = first + 1; second < terms.size(); second++) {
                IRI left = terms.get(first);
                IRI right = terms.get(second);
                IRI from = left.toString().compareTo(right.toString()) <= 0 ? left : right;
                IRI to = from.equals(left) ? right : left;
                addUnlessAlreadyThere(edges, new CanvasEdge(prefix + from + "|" + to, iri(from),
                        iri(to), label, kind));
            }
        }
    }

    /** "a(?x) ∧ b(?x, ?y)", collecting the named terms the atoms use into {@code terms}. */
    private static String atoms(OWLOntology ontology,
            Set<org.semanticweb.owlapi.model.SWRLAtom> atoms, Set<IRI> terms) {
        StringBuilder reads = new StringBuilder();
        for (org.semanticweb.owlapi.model.SWRLAtom atom : atoms) {
            if (reads.length() > 0) {
                reads.append(" \u2227 ");
            }
            Object predicate = atom.getPredicate();
            String name;
            if (predicate instanceof org.semanticweb.owlapi.model.OWLEntity) {
                org.semanticweb.owlapi.model.OWLEntity entity =
                        (org.semanticweb.owlapi.model.OWLEntity) predicate;
                terms.add(entity.getIRI());
                name = DisplayLabels.forEntity(ontology, entity);
            } else if (predicate instanceof IRI) {
                name = builtIn((IRI) predicate);
            } else if (predicate instanceof org.semanticweb.owlapi.model.OWLObject) {
                // An anonymous class, an inverse property, a data range: in Manchester syntax with
                // labels, and its terms joined to the rule - it used to read as functional syntax
                // with whole IRIs, and nothing in it had a line.
                org.semanticweb.owlapi.model.OWLObject anonymous =
                        (org.semanticweb.owlapi.model.OWLObject) predicate;
                for (org.semanticweb.owlapi.model.OWLEntity entity : anonymous.getSignature()) {
                    if (!entity.isBuiltIn()) {
                        terms.add(entity.getIRI());
                    }
                }
                name = "(" + manchester(ontology, anonymous) + ")";
            } else {
                name = String.valueOf(predicate);
            }
            StringBuilder arguments = new StringBuilder();
            for (org.semanticweb.owlapi.model.SWRLArgument argument : atom.getAllArguments()) {
                arguments.append(arguments.length() == 0 ? "" : ", ").append(argument(ontology,
                        argument));
            }
            reads.append(name).append('(').append(arguments).append(')');
        }
        return reads.toString();
    }

    /** A SWRL built-in by its usual prefix: swrlb:greaterThan, swrlx:makeOWLThing. */
    private static String builtIn(IRI builtIn) {
        String text = builtIn.toString();
        if (text.startsWith("http://www.w3.org/2003/11/swrlb#")) {
            return "swrlb:" + localName(builtIn);
        }
        if (text.startsWith("http://www.w3.org/2003/11/swrlx#")) {
            return "swrlx:" + localName(builtIn);
        }
        return localName(builtIn);
    }

    /** An OWL object in Manchester syntax, each term by the label the board shows it with. */
    private static String manchester(final OWLOntology ontology,
            org.semanticweb.owlapi.model.OWLObject object) {
        org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl
                renderer = new org.semanticweb.owlapi.manchestersyntax.renderer
                        .ManchesterOWLSyntaxOWLObjectRendererImpl();
        renderer.setShortFormProvider(new org.semanticweb.owlapi.util.ShortFormProvider() {
            @Override
            public String getShortForm(org.semanticweb.owlapi.model.OWLEntity entity) {
                return DisplayLabels.forEntity(ontology, entity);
            }

            @Override
            public void dispose() {
            }
        });
        return renderer.render(object);
    }

    private static String argument(OWLOntology ontology,
            org.semanticweb.owlapi.model.SWRLArgument argument) {
        if (argument instanceof org.semanticweb.owlapi.model.SWRLVariable) {
            return "?" + localName(((org.semanticweb.owlapi.model.SWRLVariable) argument)
                    .getIRI());
        }
        if (argument instanceof org.semanticweb.owlapi.model.SWRLIndividualArgument) {
            org.semanticweb.owlapi.model.OWLIndividual individual =
                    ((org.semanticweb.owlapi.model.SWRLIndividualArgument) argument)
                            .getIndividual();
            return individual.isAnonymous() ? individual.toString()
                    : DisplayLabels.forEntity(ontology, individual.asOWLNamedIndividual());
        }
        if (argument instanceof org.semanticweb.owlapi.model.SWRLLiteralArgument) {
            return ((org.semanticweb.owlapi.model.SWRLLiteralArgument) argument).getLiteral()
                    .getLiteral();
        }
        return String.valueOf(argument);
    }

    /**
     * Every domain and range of every property on the board, from the property's node.
     *
     * <p>Measured before this on two published ontologies built on BFO: of 921 logical axioms in
     * one, 112 were domains and ranges and none of them showed - a property was drawn only as a
     * pair of a single named domain and a single named range, in the same file, as a class-to-
     * class arrow. A union, a domain without a range, and any property from an import drew
     * nothing at all.
     *
     * <p><b>A union is drawn as itself.</b> {@code domain(p, A or B)} is not
     * {@code domain(p, A)} and {@code domain(p, B)} - two domain axioms mean <em>both</em> - so
     * two plain edges to A and B would say the opposite of the axiom. With class expressions
     * drawn, the union gets its node, as everywhere else; without, each operand's edge says
     * "domain, one of".
     */
    private static void collectDomainRangeEdges(OWLOntology ontology, Set<String> on,
            List<CanvasNode> nodes, List<CanvasEdge> edges, boolean drawExpressions) {
        for (OWLObjectProperty property
                : ontology.getObjectPropertiesInSignature(Imports.INCLUDED)) {
            if (!isOn(on, property.getIRI())) {
                continue;
            }
            for (OWLOntology part : ontology.getImportsClosure()) {
                for (OWLObjectPropertyDomainAxiom axiom
                        : part.getObjectPropertyDomainAxioms(property)) {
                    scopeEdge(on, property.getIRI(), axiom.getDomain(), "domain", nodes, edges,
                            drawExpressions);
                }
                for (OWLObjectPropertyRangeAxiom axiom
                        : part.getObjectPropertyRangeAxioms(property)) {
                    scopeEdge(on, property.getIRI(), axiom.getRange(), "range", nodes, edges,
                            drawExpressions);
                }
            }
        }
        for (OWLDataProperty property : ontology.getDataPropertiesInSignature(Imports.INCLUDED)) {
            if (!isOn(on, property.getIRI())) {
                continue;
            }
            for (OWLOntology part : ontology.getImportsClosure()) {
                for (org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom axiom
                        : part.getDataPropertyDomainAxioms(property)) {
                    scopeEdge(on, property.getIRI(), axiom.getDomain(), "domain", nodes, edges,
                            drawExpressions);
                }
                for (org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom axiom
                        : part.getDataPropertyRangeAxioms(property)) {
                    if (!axiom.getRange().isDatatype()) {
                        continue;
                    }
                    IRI datatype = axiom.getRange().asOWLDatatype().getIRI();
                    CanvasNode datatypeNode =
                            new CanvasNode(iri(datatype), NodeKind.DATATYPE, localName(datatype));
                    if (!nodes.contains(datatypeNode)) {
                        nodes.add(datatypeNode);
                    }
                    addUnlessAlreadyThere(edges, new CanvasEdge(
                            "range|" + property.getIRI() + "|" + datatype,
                            iri(property.getIRI()), iri(datatype), "range",
                            CanvasEdge.Kind.DOMAIN_RANGE));
                }
            }
        }
    }

    /** One domain or range edge, or one per operand of a union, or one to its expression. */
    private static void scopeEdge(Set<String> on, IRI property, OWLClassExpression scope,
            String which, List<CanvasNode> nodes, List<CanvasEdge> edges,
            boolean drawExpressions) {
        if (!scope.isAnonymous()) {
            IRI named = scope.asOWLClass().getIRI();
            if (isOn(on, named) && !named.equals(IRI.create(
                    "http://www.w3.org/2002/07/owl#Thing"))) {
                addUnlessAlreadyThere(edges, new CanvasEdge(which + "|" + property + "|" + named,
                        iri(property), iri(named), which, CanvasEdge.Kind.DOMAIN_RANGE));
            }
            return;
        }
        if (drawExpressions) {
            String node = expressionNode(scope, nodes);
            expand(on, node, scope, 1, nodes, edges);
            addUnlessAlreadyThere(edges, new CanvasEdge(which + "|" + property + "|" + node,
                    iri(property), node, which, CanvasEdge.Kind.DOMAIN_RANGE));
        } else if (scope instanceof org.semanticweb.owlapi.model.OWLObjectUnionOf) {
            for (OWLClassExpression operand
                    : ((org.semanticweb.owlapi.model.OWLObjectUnionOf) scope).getOperands()) {
                if (!operand.isAnonymous() && isOn(on, operand.asOWLClass().getIRI())) {
                    IRI named = operand.asOWLClass().getIRI();
                    addUnlessAlreadyThere(edges, new CanvasEdge(
                            which + "-or|" + property + "|" + named, iri(property), iri(named),
                            which + ", one of", CanvasEdge.Kind.DOMAIN_RANGE));
                }
            }
        }
    }

    /**
     * Ontologies produced by the retired web app encoded every property edge as a global
     * rdfs:domain plus rdfs:range pair. Render those so existing boards still open.
     *
     * <p>Only for a property that is not on the board itself. With the property's node there,
     * {@link #collectDomainRangeEdges} draws its domain and range from it, and this arrow would
     * say the same thing a second time - as if it were a restriction.
     */
    private static void collectLegacyDomainRangeEdges(OWLOntology ontology, Set<String> on,
            List<CanvasEdge> edges) {
        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
            if (isOn(on, property.getIRI())) {
                continue;
            }
            for (OWLObjectPropertyDomainAxiom domainAxiom
                    : ontology.getObjectPropertyDomainAxioms(property)) {
                if (domainAxiom.getDomain().isAnonymous()) {
                    continue;
                }
                IRI domainIri = domainAxiom.getDomain().asOWLClass().getIRI();
                if (!isOn(on, domainIri)) {
                    continue;
                }
                for (OWLObjectPropertyRangeAxiom rangeAxiom
                        : ontology.getObjectPropertyRangeAxioms(property)) {
                    if (rangeAxiom.getRange().isAnonymous()) {
                        continue;
                    }
                    IRI rangeIri = rangeAxiom.getRange().asOWLClass().getIRI();
                    if (!isOn(on, rangeIri)) {
                        continue;
                    }
                    // In the domain-and-range style and saying so: drawn as a restriction it read
                    // as "every D has an R to some Rg", which a domain and a range do not say.
                    edges.add(new CanvasEdge(
                            "dr|" + domainIri + "|" + property.getIRI() + "|" + rangeIri,
                            iri(domainIri), iri(rangeIri),
                            arrowLabel(ontology, property.getIRI(), "some")
                                    + " (domain \u2192 range)",
                            CanvasEdge.Kind.DOMAIN_RANGE));
                }
            }
        }
    }

    private static void collectTypeEdges(OWLOntology ontology, Set<String> on, List<CanvasEdge> edges) {
        for (OWLClassAssertionAxiom axiom
                : ontology.getAxioms(AxiomType.CLASS_ASSERTION, Imports.INCLUDED)) {
            if (axiom.getIndividual().isAnonymous() || axiom.getClassExpression().isAnonymous()) {
                continue;
            }
            IRI indIri = axiom.getIndividual().asOWLNamedIndividual().getIRI();
            IRI clsIri = axiom.getClassExpression().asOWLClass().getIRI();
            if (isOn(on, indIri) && isOn(on, clsIri)) {
                edges.add(new CanvasEdge("type|" + indIri + "|" + clsIri,
                        iri(indIri), iri(clsIri), "", CanvasEdge.Kind.TYPE));
            }
        }
    }

    private static boolean isOn(Set<String> onCanvasIris, IRI candidate) {
        return onCanvasIris.contains(candidate.toString());
    }

    private static String iri(IRI value) {
        return value.toString();
    }

    /** Delegates so short-name derivation lives in exactly one place. */
    private static String localName(IRI value) {
        return DisplayLabels.shortNameOf(value);
    }
}
