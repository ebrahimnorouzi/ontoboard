package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataAllValuesFrom;
import org.semanticweb.owlapi.model.OWLDataExactCardinality;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataHasValue;
import org.semanticweb.owlapi.model.OWLDataMaxCardinality;
import org.semanticweb.owlapi.model.OWLDataMinCardinality;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyExpression;
import org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLDataRange;
import org.semanticweb.owlapi.model.OWLDataSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLHasKeyAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectUnionOf;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * A board drawn UML-like (1.155.0, decided with the user 2026-11-19): a class's data properties
 * are rows in its box, {@code name : datatype [n..m]}, a value it is given {@code name = value},
 * and its keys a row of their own; the datatype boxes and the lines to them that said the same
 * thing go. An object property stays what the board already draws for it: its restrictions'
 * arrows, and an arrow from its domain to its range. No property is a box of its own, unless the
 * open ontology's own properties nothing on the board shows are asked for.
 *
 * <p>Which data properties a class holds: the open ontology's own whose domain is the class - a
 * named class or a union of named classes - and every one an axiom about the class restricts,
 * imported or not (an import's property is drawn only where something about a term on the board
 * uses it). A functional one has at most one value.
 */
public final class UmlBoard {

    private UmlBoard() {
    }

    /** One data property's row on one class: what is known of its datatype and how many values. */
    private static final class Row {
        String type;
        int least;
        int most = Integer.MAX_VALUE;
        boolean known;
        String value;

        void atLeast(int n) {
            least = Math.max(least, n);
            known = true;
        }

        void atMost(int n) {
            most = Math.min(most, n);
            known = true;
        }

        String text(String name) {
            if (value != null) {
                return name + " = " + value;
            }
            StringBuilder row = new StringBuilder(name);
            if (type != null) {
                row.append(" : ").append(type);
            }
            if (known) {
                row.append(" [").append(least == most ? String.valueOf(least)
                        : least + ".." + (most == Integer.MAX_VALUE ? "*" : String.valueOf(most)))
                        .append(']');
            }
            return row.toString();
        }
    }

    /** What one class's box holds: a row for each of its data properties, and its keys. */
    private static final class Rows {
        final Map<OWLDataProperty, Row> properties = new HashMap<OWLDataProperty, Row>();
        final List<String> keys = new ArrayList<String>();
        /** The properties the keys name. */
        final Set<String> keyed = new HashSet<String>();
    }

    /**
     * The board drawn UML-like from its members: no property is a box of its own - a data
     * property is a row in its classes' boxes, an object property its arrows. With
     * {@code showUnused}, the open ontology's own properties among the members that no row and no
     * arrow shows are drawn as boxes as well, so every one of them can still be found on the board.
     */
    public static Projection draw(OWLOntology ontology, Set<String> members, boolean showExpressions,
            boolean showUnused) {
        if (ontology == null) {
            return OntologyProjection.project(ontology, members, showExpressions);
        }
        Set<String> drawnMembers = new HashSet<String>(members);
        drawnMembers.removeAll(propertiesAmong(ontology, members));
        Projection drawn = withRows(OntologyProjection.project(ontology, drawnMembers, showExpressions),
                ontology);
        if (!showUnused) {
            return drawn;
        }
        Set<String> unused = unused(drawn, ontology, members);
        if (unused.isEmpty()) {
            return drawn;
        }
        drawnMembers.addAll(unused);
        return withRows(OntologyProjection.project(ontology, drawnMembers, showExpressions), ontology);
    }

    /**
     * The open ontology's own properties among the members that the board as drawn does not
     * show: no row on a class's box, no arrow - a restriction's, a domain to a range, an
     * assertion's - and no value of an individual on the board.
     */
    public static Set<String> unused(Projection drawn, OWLOntology ontology, Set<String> members) {
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        Set<String> unused = new HashSet<String>();
        for (String iri : propertiesAmong(ontology, members)) {
            // OWL's top and bottom properties are in the signature of a file that names one, and
            // are no property of the ontology's; an import's property it names is the import's.
            IRI named = IRI.create(iri);
            org.semanticweb.owlapi.model.OWLEntity property =
                    ontology.containsObjectPropertyInSignature(named, Imports.INCLUDED)
                            ? factory.getOWLObjectProperty(named) : factory.getOWLDataProperty(named);
            if (!iri.startsWith("http://www.w3.org/2002/07/owl#")
                    && PropertyOutline.isOwn(ontology, property)) {
                unused.add(iri);
            }
        }
        if (unused.isEmpty()) {
            return unused;
        }
        Set<String> shown = new HashSet<String>();
        for (CanvasEdge edge : drawn.getEdges()) {
            String property = propertyOf(edge.getId());
            if (property != null) {
                shown.add(property);
            }
        }
        for (CanvasNode node : drawn.getNodes()) {
            if (node.getKind() == NodeKind.CLASS) {
                Rows rows = rowsOf(ontology, factory.getOWLClass(IRI.create(node.getId())));
                for (OWLDataProperty property : rows.properties.keySet()) {
                    shown.add(property.getIRI().toString());
                }
                shown.addAll(rows.keyed);
            } else if (node.getKind() == NodeKind.INDIVIDUAL) {
                // A value is a box of its own off the individual, its line named by the property;
                // the line's id does not say which.
                OWLNamedIndividual individual = factory.getOWLNamedIndividual(IRI.create(node.getId()));
                for (OWLOntology part : ontology.getImportsClosure()) {
                    for (org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom axiom
                            : part.getDataPropertyAssertionAxioms(individual)) {
                        if (!axiom.getProperty().isAnonymous()) {
                            shown.add(axiom.getProperty().asOWLDataProperty().getIRI().toString());
                        }
                    }
                    for (org.semanticweb.owlapi.model.OWLNegativeDataPropertyAssertionAxiom axiom
                            : part.getNegativeDataPropertyAssertionAxioms(individual)) {
                        if (!axiom.getProperty().isAnonymous()) {
                            shown.add(axiom.getProperty().asOWLDataProperty().getIRI().toString());
                        }
                    }
                }
            }
        }
        unused.removeAll(shown);
        return unused;
    }

    /** The board without its rows: the Data properties layer switched off. */
    public static Projection withoutRows(Projection drawn) {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        for (CanvasNode node : drawn.getNodes()) {
            nodes.add(node.getRows().isEmpty() ? node
                    : node.withRows(Collections.<String>emptyList()));
        }
        return new Projection(nodes, drawn.getEdges());
    }

    /** The board without the arrows that stand for object properties: that layer switched off. */
    public static Projection withoutObjectProperties(Projection drawn, OWLOntology ontology) {
        if (ontology == null) {
            return drawn;
        }
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        for (CanvasEdge edge : drawn.getEdges()) {
            String property = propertyOf(edge.getId());
            if (property == null || !ontology.containsObjectPropertyInSignature(IRI.create(property),
                    Imports.INCLUDED)) {
                edges.add(edge);
            }
        }
        return new Projection(drawn.getNodes(), edges);
    }

    /**
     * The property a line stands for: {@link PropertyEdgeId#propertyIn}, and an assertion's line
     * between two individuals, {@code opa|subject|property|object} or its negation.
     */
    static String propertyOf(String edgeId) {
        String property = PropertyEdgeId.propertyIn(edgeId);
        if (property != null || edgeId == null) {
            return property;
        }
        String[] parts = edgeId.split("\\|", -1);
        if ((parts[0].equals("opa") || parts[0].equals("nopa")) && parts.length == 4
                && !parts[2].isEmpty()) {
            return parts[2];
        }
        return null;
    }

    /**
     * The members that are properties - object or data - and nothing else: a term punned as a
     * class or an individual as well keeps its box.
     */
    private static Set<String> propertiesAmong(OWLOntology ontology, Set<String> members) {
        Set<String> properties = new HashSet<String>();
        if (ontology == null) {
            return properties;
        }
        for (String member : members) {
            IRI iri = IRI.create(member);
            if ((ontology.containsObjectPropertyInSignature(iri, Imports.INCLUDED)
                    || ontology.containsDataPropertyInSignature(iri, Imports.INCLUDED))
                    && !ontology.containsClassInSignature(iri, Imports.INCLUDED)
                    && !ontology.containsIndividualInSignature(iri, Imports.INCLUDED)) {
                properties.add(member);
            }
        }
        return properties;
    }

    /** The projection with every class box's rows, and without the datatype boxes the rows replace. */
    public static Projection withRows(Projection drawn, OWLOntology ontology) {
        if (drawn == null || ontology == null) {
            return drawn;
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        for (CanvasNode node : drawn.getNodes()) {
            if (node.getKind() != NodeKind.CLASS) {
                nodes.add(node);
                continue;
            }
            Rows rows = rowsOf(ontology, factory.getOWLClass(IRI.create(node.getId())));
            Map<String, String> sorted = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<OWLDataProperty, Row> row : rows.properties.entrySet()) {
                String name = labelOf(ontology, row.getKey());
                sorted.put(name + "\u0000" + row.getKey().getIRI(), row.getValue().text(name));
            }
            List<String> lines = new ArrayList<String>(sorted.values());
            List<String> keys = new ArrayList<String>(rows.keys);
            Collections.sort(keys);
            lines.addAll(keys);
            nodes.add(lines.isEmpty() ? node : node.withRows(lines));
        }
        // The lines to datatype boxes said what the rows now say; the boxes go with them when
        // nothing else reaches them.
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        Set<String> classes = new HashSet<String>();
        Set<String> datatypes = new HashSet<String>();
        for (CanvasNode node : nodes) {
            if (node.getKind() == NodeKind.CLASS) {
                classes.add(node.getId());
            } else if (node.getKind() == NodeKind.DATATYPE) {
                datatypes.add(node.getId());
            }
        }
        Set<String> reached = new HashSet<String>();
        for (CanvasEdge edge : drawn.getEdges()) {
            if (edge.getKind() == CanvasEdge.Kind.DATA_PROPERTY && classes.contains(edge.getSourceId())
                    && datatypes.contains(edge.getTargetId())) {
                continue;
            }
            edges.add(edge);
            reached.add(edge.getSourceId());
            reached.add(edge.getTargetId());
        }
        List<CanvasNode> kept = new ArrayList<CanvasNode>();
        for (CanvasNode node : nodes) {
            if (node.getKind() != NodeKind.DATATYPE || reached.contains(node.getId())) {
                kept.add(node);
            }
        }
        return new Projection(kept, edges);
    }

    /** One class's rows: its data properties, what is said of each, and its keys. */
    private static Rows rowsOf(OWLOntology ontology, OWLClass owlClass) {
        Rows rows = new Rows();
        ownDomains(ontology, owlClass, rows.properties);
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (OWLSubClassOfAxiom axiom : part.getSubClassAxiomsForSubClass(owlClass)) {
                restrictions(axiom.getSuperClass(), rows.properties);
            }
            for (OWLEquivalentClassesAxiom axiom : part.getEquivalentClassesAxioms(owlClass)) {
                for (OWLClassExpression operand : axiom.getClassExpressionsMinus(owlClass)) {
                    restrictions(operand, rows.properties);
                }
            }
            for (OWLHasKeyAxiom axiom : part.getHasKeyAxioms(owlClass)) {
                List<String> names = new ArrayList<String>();
                for (OWLDataPropertyExpression property : axiom.getDataPropertyExpressions()) {
                    if (!property.isAnonymous()) {
                        names.add(labelOf(ontology, property.asOWLDataProperty()));
                        rows.keyed.add(property.asOWLDataProperty().getIRI().toString());
                    }
                }
                for (OWLObjectPropertyExpression property : axiom.getObjectPropertyExpressions()) {
                    if (!property.isAnonymous()) {
                        names.add(DisplayLabels.forEntity(ontology, property.asOWLObjectProperty()));
                        rows.keyed.add(property.asOWLObjectProperty().getIRI().toString());
                    }
                }
                Collections.sort(names);
                if (!names.isEmpty()) {
                    rows.keys.add("key: " + String.join(", ", names));
                }
            }
        }
        for (Map.Entry<OWLDataProperty, Row> row : rows.properties.entrySet()) {
            if (row.getValue().type == null) {
                row.getValue().type = rangeOf(ontology, row.getKey());
            }
            if (isFunctional(ontology, row.getKey())) {
                row.getValue().atMost(1);
            }
        }
        return rows;
    }

    /** The open ontology's own data properties whose domain is the class, or a union holding it. */
    private static void ownDomains(OWLOntology ontology, OWLClass owlClass, Map<OWLDataProperty, Row> rows) {
        for (OWLDataProperty property : ontology.getDataPropertiesInSignature(Imports.EXCLUDED)) {
            for (OWLDataPropertyDomainAxiom axiom : ontology.getDataPropertyDomainAxioms(property)) {
                OWLClassExpression domain = axiom.getDomain();
                boolean here = domain.equals(owlClass) || domain instanceof OWLObjectUnionOf
                        && ((OWLObjectUnionOf) domain).getOperands().contains(owlClass);
                if (here && !rows.containsKey(property)) {
                    rows.put(property, new Row());
                }
            }
        }
    }

    /** What a class expression says of the class's data properties. */
    private static void restrictions(OWLClassExpression expression, Map<OWLDataProperty, Row> rows) {
        if (expression instanceof OWLObjectIntersectionOf) {
            for (OWLClassExpression operand : ((OWLObjectIntersectionOf) expression).getOperands()) {
                restrictions(operand, rows);
            }
            return;
        }
        if (expression instanceof OWLDataSomeValuesFrom) {
            OWLDataSomeValuesFrom some = (OWLDataSomeValuesFrom) expression;
            Row row = rowOf(some.getProperty(), rows);
            if (row != null) {
                row.atLeast(1);
                typeFrom(some.getFiller(), row);
            }
        } else if (expression instanceof OWLDataAllValuesFrom) {
            OWLDataAllValuesFrom only = (OWLDataAllValuesFrom) expression;
            Row row = rowOf(only.getProperty(), rows);
            if (row != null) {
                typeFrom(only.getFiller(), row);
            }
        } else if (expression instanceof OWLDataMinCardinality) {
            OWLDataMinCardinality min = (OWLDataMinCardinality) expression;
            Row row = rowOf(min.getProperty(), rows);
            if (row != null) {
                row.atLeast(min.getCardinality());
                typeFrom(min.getFiller(), row);
            }
        } else if (expression instanceof OWLDataMaxCardinality) {
            OWLDataMaxCardinality max = (OWLDataMaxCardinality) expression;
            Row row = rowOf(max.getProperty(), rows);
            if (row != null) {
                row.atMost(max.getCardinality());
                typeFrom(max.getFiller(), row);
            }
        } else if (expression instanceof OWLDataExactCardinality) {
            OWLDataExactCardinality exact = (OWLDataExactCardinality) expression;
            Row row = rowOf(exact.getProperty(), rows);
            if (row != null) {
                row.atLeast(exact.getCardinality());
                row.atMost(exact.getCardinality());
                typeFrom(exact.getFiller(), row);
            }
        } else if (expression instanceof OWLDataHasValue) {
            OWLDataHasValue has = (OWLDataHasValue) expression;
            Row row = rowOf(has.getProperty(), rows);
            if (row != null) {
                row.value = literal(has.getFiller());
            }
        }
    }

    private static Row rowOf(OWLDataPropertyExpression property, Map<OWLDataProperty, Row> rows) {
        if (property.isAnonymous()) {
            return null;
        }
        OWLDataProperty named = property.asOWLDataProperty();
        Row row = rows.get(named);
        if (row == null) {
            row = new Row();
            rows.put(named, row);
        }
        return row;
    }

    /** A named datatype's name; a range of facets, the datatype they restrict. */
    private static void typeFrom(OWLDataRange range, Row row) {
        if (range == null || range.isTopDatatype()) {
            return;
        }
        if (range.isDatatype()) {
            row.type = DisplayLabels.shortNameOf(range.asOWLDatatype().getIRI());
        } else if (range instanceof org.semanticweb.owlapi.model.OWLDatatypeRestriction) {
            row.type = DisplayLabels.shortNameOf(((org.semanticweb.owlapi.model.OWLDatatypeRestriction) range)
                    .getDatatype().getIRI()) + "...";
        }
    }

    /** The property's named range in the closure, or null. */
    private static String rangeOf(OWLOntology ontology, OWLDataProperty property) {
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (OWLDataPropertyRangeAxiom axiom : part.getDataPropertyRangeAxioms(property)) {
                if (axiom.getRange().isDatatype() && !axiom.getRange().isTopDatatype()) {
                    return DisplayLabels.shortNameOf(axiom.getRange().asOWLDatatype().getIRI());
                }
            }
        }
        return null;
    }

    private static boolean isFunctional(OWLOntology ontology, OWLDataProperty property) {
        for (OWLOntology part : ontology.getImportsClosure()) {
            if (!part.getFunctionalDataPropertyAxioms(property).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String labelOf(OWLOntology ontology, OWLDataProperty property) {
        return DisplayLabels.forEntity(ontology, property);
    }

    private static String literal(OWLLiteral value) {
        if (value.isInteger() || value.isDouble() || value.isFloat() || value.isBoolean()) {
            return value.getLiteral();
        }
        return "\"" + value.getLiteral() + "\"";
    }
}
