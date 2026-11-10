package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLDisjointDataPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLDisjointObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentDataPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLNaryPropertyAxiom;
import org.semanticweb.owlapi.model.OWLObjectInverseOf;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLObjectUnionOf;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLProperty;
import org.semanticweb.owlapi.model.OWLPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLPropertyExpression;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom;

/**
 * Which drawn arrows show an axiom - so that clicking the axiom's line in Protégé's description
 * view takes the board to them.
 *
 * <p>Asked for: "once I click a logic line in the left panel I want to be taken to the edge and
 * the classes it relates on the canvas". An arrow shows the axiom when both its ends are terms
 * the axiom mentions, or one end is and the other is a grey expression node - the restriction or
 * union the axiom is about; and when the axiom names a property, an arrow labelled with another
 * property does not count. A property's own lines - to its domain, its range, its super-property
 * and the rest - carry no property's name, and are found by their ids instead. Pure: the ends and
 * labels come from the board, the axiom from Protégé.
 */
public final class AxiomOnBoard {

    private AxiomOnBoard() {
    }

    /** One drawn arrow, as far as matching it to an axiom needs. */
    public static final class Arrow {
        final String id;
        final String source;
        final String target;
        final String label;

        public Arrow(String id, String source, String target, String label) {
            this.id = id;
            this.source = source;
            this.target = target;
            this.label = label == null ? "" : label;
        }
    }

    /** The ids of the arrows that show {@code axiom}, in board order. */
    public static List<String> arrowsFor(OWLAxiom axiom, OWLOntology ontology,
            List<Arrow> arrows) {
        Set<String> drawnAs = propertyLines(axiom);
        if (drawnAs != null) {
            List<String> found = new ArrayList<String>();
            for (Arrow arrow : arrows) {
                if (drawnAs.contains(arrow.id)) {
                    found.add(arrow.id);
                }
            }
            return found;
        }
        Set<String> terms = new HashSet<String>();
        Set<String> properties = new HashSet<String>();
        for (OWLEntity entity : axiom.getSignature()) {
            if (entity.isOWLClass() || entity.isOWLNamedIndividual()) {
                terms.add(entity.getIRI().toString());
            } else if (entity.isOWLObjectProperty() || entity.isOWLDataProperty()) {
                properties.add(DisplayLabels.forEntity(ontology, entity));
                terms.add(entity.getIRI().toString());
            }
        }
        String said = axiom.toString();
        boolean some = said.contains("SomeValuesFrom") || said.contains("HasValue");
        boolean only = said.contains("AllValuesFrom");
        boolean count = said.contains("Cardinality");
        List<String> found = new ArrayList<String>();
        for (Arrow arrow : arrows) {
            boolean from = terms.contains(arrow.source) || isExpression(arrow.source);
            boolean to = terms.contains(arrow.target) || isExpression(arrow.target);
            boolean named = terms.contains(arrow.source) || terms.contains(arrow.target);
            if (!from || !to || !named) {
                continue;
            }
            if (!properties.isEmpty() && !namesOneOf(arrow.label, properties)) {
                continue;
            }
            // The kind of restriction too: SubClassOf(A, R some B) is not the ∀ R arrow
            // between the same two classes.
            String symbol = arrow.label.trim();
            boolean universal = symbol.startsWith(OntologyProjection.ONLY.trim());
            boolean counted = symbol.startsWith("\u2265") || symbol.startsWith("\u2264")
                    || symbol.startsWith("= ");
            if ((universal && !only) || (counted && !count)
                    || (symbol.startsWith(OntologyProjection.SOME.trim()) && !some
                            && (only || count))) {
                continue;
            }
            found.add(arrow.id);
        }
        return found;
    }

    private static boolean isExpression(String id) {
        return id != null && id.startsWith(OntologyProjection.EXPRESSION_PREFIX);
    }

    private static boolean namesOneOf(String label, Set<String> properties) {
        for (String property : properties) {
            if (label.contains(property)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The ids of the lines a property axiom is drawn as - from the property's own box to its
     * domain, its range, its super-property, an equivalent, its inverse, a property it is disjoint
     * with, or its chain's node - or null for an axiom of any other kind.
     *
     * <p>Found in review (1.135.1): these lines read "domain", "range", "rdfs:subPropertyOf" or
     * nothing, never the property's name, so the label test refused every one of them, and
     * clicking "Domains: process" in a property's description said the axiom was not drawn with
     * its line on the board. Their ends do not tell them apart either: a domain and a range can
     * join the same two boxes, and a restriction on the domain class ends at both. Their ids do,
     * because {@link OntologyProjection} writes each line's kind and both its ends into its id;
     * these are those ids, made the same way.
     */
    private static Set<String> propertyLines(OWLAxiom axiom) {
        Set<String> ids = new HashSet<String>();
        if (axiom instanceof OWLObjectPropertyDomainAxiom
                || axiom instanceof OWLDataPropertyDomainAxiom) {
            OWLPropertyDomainAxiom<?> domain = (OWLPropertyDomainAxiom<?>) axiom;
            scopeLines(ids, "domain", named(domain.getProperty()), domain.getDomain());
        } else if (axiom instanceof OWLObjectPropertyRangeAxiom) {
            OWLObjectPropertyRangeAxiom range = (OWLObjectPropertyRangeAxiom) axiom;
            scopeLines(ids, "range", named(range.getProperty()), range.getRange());
        } else if (axiom instanceof OWLDataPropertyRangeAxiom) {
            OWLDataPropertyRangeAxiom range = (OWLDataPropertyRangeAxiom) axiom;
            if (range.getRange().isDatatype()) {
                add(ids, "range", named(range.getProperty()),
                        range.getRange().asOWLDatatype().getIRI().toString());
            }
        } else if (axiom instanceof OWLSubObjectPropertyOfAxiom
                || axiom instanceof OWLSubDataPropertyOfAxiom) {
            OWLSubPropertyAxiom<?> sub = (OWLSubPropertyAxiom<?>) axiom;
            add(ids, "subprop", named(sub.getSubProperty()), named(sub.getSuperProperty()));
        } else if (axiom instanceof OWLEquivalentObjectPropertiesAxiom
                || axiom instanceof OWLEquivalentDataPropertiesAxiom) {
            // Drawn as a sub-property line each way; p equivalent to the inverse of q, as the
            // inverse line between p and q.
            List<String> plain = new ArrayList<String>();
            List<String> inverted = new ArrayList<String>();
            for (OWLPropertyExpression property
                    : ((OWLNaryPropertyAxiom<?>) axiom).getProperties()) {
                if (!property.isAnonymous()) {
                    plain.add(named(property));
                } else if (property instanceof OWLObjectInverseOf) {
                    inverted.add(named(((OWLObjectInverseOf) property).getInverse()));
                }
            }
            for (String one : plain) {
                for (String other : plain) {
                    if (!one.equals(other)) {
                        add(ids, "subprop", one, other);
                    }
                }
                for (String other : inverted) {
                    pair(ids, "inv", one, other);
                }
            }
        } else if (axiom instanceof OWLInverseObjectPropertiesAxiom) {
            OWLInverseObjectPropertiesAxiom inverse = (OWLInverseObjectPropertiesAxiom) axiom;
            pair(ids, "inv", named(inverse.getFirstProperty()),
                    named(inverse.getSecondProperty()));
        } else if (axiom instanceof OWLDisjointObjectPropertiesAxiom
                || axiom instanceof OWLDisjointDataPropertiesAxiom) {
            List<String> disjoint = new ArrayList<String>();
            for (OWLPropertyExpression property
                    : ((OWLNaryPropertyAxiom<?>) axiom).getProperties()) {
                disjoint.add(named(property));
            }
            for (int first = 0; first < disjoint.size(); first++) {
                for (int second = first + 1; second < disjoint.size(); second++) {
                    pair(ids, "pdisj", disjoint.get(first), disjoint.get(second));
                }
            }
        } else if (axiom instanceof OWLSubPropertyChainOfAxiom) {
            OWLSubPropertyChainOfAxiom chain = (OWLSubPropertyChainOfAxiom) axiom;
            String node = OntologyProjection.chainNodeId(chain);
            add(ids, "chain", node, named(chain.getSuperProperty()));
            List<OWLObjectPropertyExpression> links = chain.getPropertyChain();
            for (int at = 0; at < links.size(); at++) {
                add(ids, "chain", node, String.valueOf(at), named(links.get(at)));
            }
        } else {
            return null;
        }
        return ids;
    }

    /** A domain's or a range's line: to its class, its expression's node, or a union's classes. */
    private static void scopeLines(Set<String> ids, String which, String property,
            OWLClassExpression scope) {
        if (!scope.isAnonymous()) {
            add(ids, which, property, scope.asOWLClass().getIRI().toString());
            return;
        }
        // To the expression's node with class expressions drawn; without them, a union's line to
        // each class in it.
        add(ids, which, property, OntologyProjection.expressionNodeId(scope));
        if (scope instanceof OWLObjectUnionOf) {
            for (OWLClassExpression operand : ((OWLObjectUnionOf) scope).getOperands()) {
                if (!operand.isAnonymous()) {
                    add(ids, which + "-or", property, operand.asOWLClass().getIRI().toString());
                }
            }
        }
    }

    /** The line between two properties, in the order the board writes its ends; none to itself. */
    private static void pair(Set<String> ids, String kind, String one, String other) {
        if (one == null || other == null || one.equals(other)) {
            return;
        }
        boolean first = one.compareTo(other) <= 0;
        add(ids, kind, first ? one : other, first ? other : one);
    }

    /** The id made of these parts, as the board joins them; none when a part is missing. */
    private static void add(Set<String> ids, String... parts) {
        StringBuilder id = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                return;
            }
            id.append(id.length() == 0 ? "" : "|").append(part);
        }
        ids.add(id.toString());
    }

    /** A property's IRI, or null for an inverse, which has no box of its own. */
    private static String named(OWLPropertyExpression property) {
        return property.isAnonymous() ? null : ((OWLProperty) property).getIRI().toString();
    }
}
