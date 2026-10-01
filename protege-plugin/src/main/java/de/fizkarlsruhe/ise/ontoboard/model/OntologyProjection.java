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
        List<CanvasNode> nodes = new ArrayList<>();
        List<CanvasEdge> edges = new ArrayList<>();

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
                nodes.add(marked(local, new CanvasNode(iri(cls.getIRI()), NodeKind.CLASS,
                        DisplayLabels.forEntity(ontology, cls),
                        EditorNotes.allNotesOn(ontology, cls.getIRI()))));
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
                nodes.add(marked(local, noted(ontology, property.getIRI(),
                        NodeKind.OBJECT_PROPERTY,
                        DisplayLabels.forEntity(ontology, property))));
            }
        }
        for (OWLDataProperty property : ontology.getDataPropertiesInSignature(Imports.INCLUDED)) {
            if (isOn(onCanvasIris, property.getIRI())) {
                nodes.add(marked(local, noted(ontology, property.getIRI(), NodeKind.DATA_PROPERTY,
                        DisplayLabels.forEntity(ontology, property))));
            }
        }

        collectSubClassEdges(ontology, onCanvasIris, nodes, edges);
        collectSubPropertyEdges(ontology, onCanvasIris, edges);
        collectLegacyDomainRangeEdges(ontology, onCanvasIris, edges);
        collectTypeEdges(ontology, onCanvasIris, edges);

        return new Projection(nodes, edges);
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
            List<CanvasNode> nodes, List<CanvasEdge> edges) {
        // Included, so the hierarchy between two imported terms is drawn. Both ends still have to
        // be on the board, so this cannot pull in an import's whole class tree.
        collectSubClassEdges(ontology.getAxioms(AxiomType.SUBCLASS_OF, Imports.INCLUDED),
                on, nodes, edges);
    }

    /**
     * SubClassOf(A B) and SubClassOf(A ObjectSomeValuesFrom(R B)) / ObjectAllValuesFrom. Also
     * SubClassOf(A DataSomeValuesFrom(R D)) for a plain datatype D, which additionally
     * projects a DATATYPE node for D (never gated on canvas membership: a datatype is a leaf
     * pulled in by showing the class, not a first-class canvas citizen).
     *
     * <p>Package-private (rather than the private the rest of this class uses) so tests can
     * pass an explicit, ordered {@link Collection} of axioms and pin an exact iteration order.
     * {@link OWLOntology#getAxioms(org.semanticweb.owlapi.model.AxiomType)} returns an
     * unordered {@code Set} whose real iteration order is an implementation detail of
     * owlapi-impl's internal hash map - not something a test should depend on to reproduce a
     * specific code path.
     */
    static void collectSubClassEdges(Collection<OWLSubClassOfAxiom> axioms, Set<String> on,
            List<CanvasNode> nodes, List<CanvasEdge> edges) {
        for (OWLSubClassOfAxiom axiom : axioms) {
            if (axiom.getSubClass().isAnonymous()) {
                continue;
            }
            IRI subIri = axiom.getSubClass().asOWLClass().getIRI();
            if (!isOn(on, subIri)) {
                continue;
            }
            OWLClassExpression sup = axiom.getSuperClass();

            if (!sup.isAnonymous()) {
                IRI supIri = sup.asOWLClass().getIRI();
                if (isOn(on, supIri)) {
                    edges.add(new CanvasEdge("sub|" + subIri + "|" + supIri,
                            iri(subIri), iri(supIri), "", CanvasEdge.Kind.SUBCLASS));
                }
            } else if (sup instanceof OWLObjectSomeValuesFrom) {
                OWLObjectSomeValuesFrom some = (OWLObjectSomeValuesFrom) sup;
                addRestrictionEdge(on, edges, subIri, some.getProperty(), some.getFiller(), "some");
            } else if (sup instanceof OWLObjectAllValuesFrom) {
                OWLObjectAllValuesFrom all = (OWLObjectAllValuesFrom) sup;
                addRestrictionEdge(on, edges, subIri, all.getProperty(), all.getFiller(), "only");
            } else if (sup instanceof OWLDataSomeValuesFrom) {
                OWLDataSomeValuesFrom data = (OWLDataSomeValuesFrom) sup;
                if (data.getProperty().isAnonymous() || !data.getFiller().isDatatype()) {
                    // Not a plain datatype range (e.g. a facet-restricted DatatypeRestriction,
                    // or an anonymous property) - unsupported shape, skip only this axiom.
                    continue;
                }
                IRI propIri = data.getProperty().asOWLDataProperty().getIRI();
                IRI dtIri = data.getFiller().asOWLDatatype().getIRI();
                CanvasNode datatypeNode = new CanvasNode(iri(dtIri), NodeKind.DATATYPE, localName(dtIri));
                if (!nodes.contains(datatypeNode)) {
                    nodes.add(datatypeNode);
                }
                edges.add(new CanvasEdge("data|" + subIri + "|" + propIri + "|" + dtIri,
                        iri(subIri), iri(dtIri), localName(propIri), CanvasEdge.Kind.DATA_PROPERTY));
            }
        }
    }

    private static void addRestrictionEdge(Set<String> on, List<CanvasEdge> edges, IRI subIri,
            org.semanticweb.owlapi.model.OWLObjectPropertyExpression property,
            OWLClassExpression filler, String qualifier) {
        if (property.isAnonymous() || filler.isAnonymous()) {
            return;
        }
        IRI propIri = property.asOWLObjectProperty().getIRI();
        IRI fillerIri = filler.asOWLClass().getIRI();
        if (!isOn(on, fillerIri)) {
            return;
        }
        String label = "some".equals(qualifier)
                ? localName(propIri)
                : localName(propIri) + " (only)";
        edges.add(new CanvasEdge("rest|" + qualifier + "|" + subIri + "|" + propIri + "|" + fillerIri,
                iri(subIri), iri(fillerIri), label, CanvasEdge.Kind.OBJECT_PROPERTY));
    }

    /**
     * Ontologies produced by the retired web app encoded every property edge as a global
     * rdfs:domain plus rdfs:range pair. Render those so existing boards still open.
     */
    private static void collectLegacyDomainRangeEdges(OWLOntology ontology, Set<String> on,
            List<CanvasEdge> edges) {
        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
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
                    edges.add(new CanvasEdge(
                            "dr|" + domainIri + "|" + property.getIRI() + "|" + rangeIri,
                            iri(domainIri), iri(rangeIri), localName(property.getIRI()),
                            CanvasEdge.Kind.OBJECT_PROPERTY));
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
