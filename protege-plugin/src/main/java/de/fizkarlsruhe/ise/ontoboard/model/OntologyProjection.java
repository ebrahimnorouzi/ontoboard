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
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

/**
 * Projects an ontology onto the canvas, restricted to the entities the user has chosen to
 * show (spec section 5.3). An edge is emitted only when both endpoints are on the canvas.
 */
public final class OntologyProjection {

    private OntologyProjection() {
    }

    /** A node that knows whether its term carries an editorial note. */
    private static CanvasNode noted(OWLOntology ontology,
            org.semanticweb.owlapi.model.IRI entity, NodeKind kind, String label) {
        return new CanvasNode(iri(entity), kind, label, EditorNotes.hasNote(ontology, entity));
    }

    public static Projection project(OWLOntology ontology, Set<String> onCanvasIris) {
        List<CanvasNode> nodes = new ArrayList<>();
        List<CanvasEdge> edges = new ArrayList<>();

        for (OWLClass cls : ontology.getClassesInSignature()) {
            if (isOn(onCanvasIris, cls.getIRI())) {
                nodes.add(new CanvasNode(iri(cls.getIRI()), NodeKind.CLASS,
                        DisplayLabels.forEntity(ontology, cls),
                        EditorNotes.hasNote(ontology, cls.getIRI())));
            }
        }
        for (OWLNamedIndividual ind : ontology.getIndividualsInSignature()) {
            if (isOn(onCanvasIris, ind.getIRI())) {
                nodes.add(noted(ontology, ind.getIRI(), NodeKind.INDIVIDUAL,
                        DisplayLabels.forEntity(ontology, ind)));
            }
        }

        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
            if (isOn(onCanvasIris, property.getIRI())) {
                nodes.add(noted(ontology, property.getIRI(), NodeKind.OBJECT_PROPERTY,
                        DisplayLabels.forEntity(ontology, property)));
            }
        }
        for (OWLDataProperty property : ontology.getDataPropertiesInSignature()) {
            if (isOn(onCanvasIris, property.getIRI())) {
                nodes.add(noted(ontology, property.getIRI(), NodeKind.DATA_PROPERTY,
                        DisplayLabels.forEntity(ontology, property)));
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
                : ontology.getAxioms(AxiomType.SUB_OBJECT_PROPERTY)) {
            if (axiom.getSubProperty().isAnonymous() || axiom.getSuperProperty().isAnonymous()) {
                continue;
            }
            addSubPropertyEdge(on, edges,
                    axiom.getSubProperty().asOWLObjectProperty().getIRI(),
                    axiom.getSuperProperty().asOWLObjectProperty().getIRI());
        }
        for (OWLSubDataPropertyOfAxiom axiom : ontology.getAxioms(AxiomType.SUB_DATA_PROPERTY)) {
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
        collectSubClassEdges(ontology.getAxioms(AxiomType.SUBCLASS_OF), on, nodes, edges);
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
        for (OWLClassAssertionAxiom axiom : ontology.getAxioms(AxiomType.CLASS_ASSERTION)) {
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
