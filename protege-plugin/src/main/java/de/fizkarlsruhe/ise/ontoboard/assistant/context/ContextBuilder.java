package de.fizkarlsruhe.ise.ontoboard.assistant.context;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.robot.Explanations;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * What the model is told about the ontology (spec D3): the selected term and its neighbourhood by
 * default; the hierarchy or the whole ontology only when the person adds them.
 *
 * <p>Plain text the person can read in "What is sent", rendered with labels as names. Every list
 * is sorted and capped, so the same ontology gives the same request, and a class with three
 * hundred children costs twenty names and a count.
 */
public final class ContextBuilder {

    public static final int CHILDREN = 20;
    public static final int SIBLINGS = 10;
    public static final int MENTIONED = 30;

    /** A mentioned term's definition is a hint, not the definition's whole text. */
    static final int DEFINITION_HINT = 160;

    /**
     * The annotations a term's block sends (spec D3, and what docs/assistant.md promises):
     * labels, synonyms, definitions and comments. Editor notes, examples and any other annotation
     * - a contributor's ORCID or address, say - stay in the column's list and are not sent.
     */
    static final java.util.Set<String> SENT_ANNOTATIONS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<String>(java.util.Arrays.asList("label", "synonym",
                    "definition", "comment")));

    private static final String DEFINITION = "http://purl.obolibrary.org/obo/IAO_0000115";
    private static final String[] TITLES = {"http://purl.org/dc/terms/title",
        "http://purl.org/dc/elements/1.1/title", "http://www.w3.org/2000/01/rdf-schema#label"};

    private ContextBuilder() {
    }

    /** The ontology's IRI and title: the one line every request starts its context with. */
    public static String header(OWLOntology ontology) {
        String iri = ontology.getOntologyID().getOntologyIRI().isPresent()
                ? ontology.getOntologyID().getOntologyIRI().get().toString() : "(no IRI)";
        String title = null;
        for (String property : TITLES) {
            for (OWLAnnotation annotation : ontology.getAnnotations()) {
                if (title == null && property.equals(annotation.getProperty().getIRI().toString())
                        && annotation.getValue().asLiteral().isPresent()) {
                    title = TermLines.oneLine(annotation.getValue().asLiteral().get().getLiteral());
                }
            }
        }
        return "Ontology: " + iri + (title == null || title.isEmpty() ? "" : " (title: " + title
                + ")");
    }

    /** What a chip is called: the term's label, or what the chip stands for. */
    public static String titleOf(OWLOntology ontology, ContextItem item) {
        switch (item.getKind()) {
            case TERM:
                List<OWLEntity> entities = TermLines.entities(ontology, item.getIri());
                return entities.isEmpty() ? DisplayLabels.shortNameOf(item.getIri())
                        : DisplayLabels.forEntity(ontology, entities.get(0));
            case HIERARCHY:
                return "class hierarchy";
            case WHOLE_ONTOLOGY:
                return "whole ontology";
            default:
                return item.getTitle();
        }
    }

    /** The text a chip contributes to a request. */
    public static String render(OWLOntology ontology, ContextItem item) {
        switch (item.getKind()) {
            case TERM:
                return term(ontology, item.getIri());
            case HIERARCHY:
                return hierarchy(ontology);
            case WHOLE_ONTOLOGY:
                return wholeOntology(ontology);
            default:
                return item.getText();
        }
    }

    /** One term's block: what it says, line by line, and its neighbours by label. */
    public static String term(OWLOntology ontology, IRI iri) {
        List<OWLEntity> entities = TermLines.entities(ontology, iri);
        if (entities.isEmpty()) {
            return iri + " is no longer in this ontology.\n";
        }
        StringBuilder out = new StringBuilder();
        for (OWLEntity entity : entities) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(kindOf(entity, true)).append(": ")
                    .append(DisplayLabels.forEntity(ontology, entity)).append('\n');
            out.append("  IRI: ").append(entity.getIRI()).append('\n');
            List<TermLine> lines = TermLines.of(ontology, entity);
            for (TermLine line : lines) {
                if (line.getGroup() == TermLine.Group.ANNOTATION
                        && !SENT_ANNOTATIONS.contains(line.getKey())) {
                    continue;
                }
                out.append("  ").append(line.getKey()).append(": ").append(line.getText())
                        .append('\n');
            }
            neighbours(out, ontology, entity);
            mentioned(out, ontology, entity, lines);
        }
        return out.toString();
    }

    private static void neighbours(StringBuilder out, OWLOntology ontology, OWLEntity entity) {
        if (entity.isOWLClass()) {
            OWLClass cls = entity.asOWLClass();
            Set<OWLEntity> parents = namedParents(ontology, cls);
            out.append("  Parents: ").append(names(ontology, parents, Integer.MAX_VALUE))
                    .append('\n');
            out.append("  Children: ").append(names(ontology, namedChildren(ontology, cls),
                    CHILDREN)).append('\n');
            Set<OWLEntity> siblings = new HashSet<OWLEntity>();
            for (OWLEntity parent : parents) {
                siblings.addAll(namedChildren(ontology, parent.asOWLClass()));
            }
            siblings.remove(cls);
            out.append("  Siblings: ").append(names(ontology, siblings, SIBLINGS)).append('\n');
        } else if (entity.isOWLObjectProperty() || entity.isOWLDataProperty()) {
            Set<OWLEntity> supers = new HashSet<OWLEntity>();
            Set<OWLEntity> subs = new HashSet<OWLEntity>();
            for (OWLOntology part : ontology.getImportsClosure()) {
                if (entity.isOWLObjectProperty()) {
                    OWLObjectProperty property = entity.asOWLObjectProperty();
                    for (OWLSubObjectPropertyOfAxiom axiom
                            : part.getAxioms(org.semanticweb.owlapi.model.AxiomType
                                    .SUB_OBJECT_PROPERTY)) {
                        collectProperty(axiom.getSubProperty(), axiom.getSuperProperty(), property,
                                supers, subs);
                    }
                } else {
                    OWLDataProperty property = entity.asOWLDataProperty();
                    for (OWLSubDataPropertyOfAxiom axiom
                            : part.getAxioms(org.semanticweb.owlapi.model.AxiomType
                                    .SUB_DATA_PROPERTY)) {
                        if (property.equals(axiom.getSubProperty())
                                && !axiom.getSuperProperty().isAnonymous()) {
                            supers.add(axiom.getSuperProperty().asOWLDataProperty());
                        }
                        if (property.equals(axiom.getSuperProperty())
                                && !axiom.getSubProperty().isAnonymous()) {
                            subs.add(axiom.getSubProperty().asOWLDataProperty());
                        }
                    }
                }
            }
            out.append("  Parents: ").append(names(ontology, supers, Integer.MAX_VALUE))
                    .append('\n');
            out.append("  Children: ").append(names(ontology, subs, CHILDREN)).append('\n');
        }
    }

    private static void collectProperty(OWLObjectPropertyExpression sub,
            OWLObjectPropertyExpression sup, OWLObjectProperty property, Set<OWLEntity> supers,
            Set<OWLEntity> subs) {
        if (property.equals(sub) && sup.isNamed()) {
            supers.add(sup.asOWLObjectProperty());
        }
        if (property.equals(sup) && sub.isNamed()) {
            subs.add(sub.asOWLObjectProperty());
        }
    }

    /** The other terms the entity's axioms mention, each with its kind and a definition hint. */
    private static void mentioned(StringBuilder out, OWLOntology ontology, OWLEntity entity,
            List<TermLine> lines) {
        Set<OWLEntity> mentioned = new HashSet<OWLEntity>();
        for (TermLine line : lines) {
            if (line.getGroup() == TermLine.Group.SEMANTICS) {
                mentioned.addAll(line.getAxiom().getSignature());
            }
        }
        mentioned.remove(entity);
        List<OWLEntity> shown = new ArrayList<OWLEntity>();
        for (OWLEntity other : mentioned) {
            if (!other.isBuiltIn() && !other.isOWLDatatype()) {
                shown.add(other);
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        sortByLabel(ontology, shown);
        out.append("  Terms used here:\n");
        int count = 0;
        for (OWLEntity other : shown) {
            if (count == MENTIONED) {
                out.append("    and ").append(shown.size() - MENTIONED).append(" more\n");
                break;
            }
            out.append("    ").append(DisplayLabels.forEntity(ontology, other)).append(" (")
                    .append(kindOf(other, false)).append(')');
            String definition = definitionOf(ontology, other);
            if (!definition.isEmpty()) {
                out.append(": ").append(definition);
            }
            out.append('\n');
            count++;
        }
    }

    private static String definitionOf(OWLOntology ontology, OWLEntity entity) {
        List<String> found = new ArrayList<String>();
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (OWLAnnotationAssertionAxiom axiom
                    : part.getAnnotationAssertionAxioms(entity.getIRI())) {
                if (DEFINITION.equals(axiom.getProperty().getIRI().toString())
                        && axiom.getValue().asLiteral().isPresent()) {
                    found.add(TermLines.oneLine(axiom.getValue().asLiteral().get().getLiteral()));
                }
            }
        }
        if (found.isEmpty()) {
            return "";
        }
        Collections.sort(found);
        String definition = found.get(0);
        return definition.length() <= DEFINITION_HINT ? definition
                : definition.substring(0, DEFINITION_HINT - 1) + "…";
    }

    /**
     * The asserted class hierarchy as an indented label tree, two spaces a level. A class with
     * two parents is written out in full under the first and named with "(also under ...)"
     * under the others, so a diamond does not double the text and a cycle does not loop.
     */
    public static String hierarchy(OWLOntology ontology) {
        Map<OWLClass, Set<OWLClass>> children = new HashMap<OWLClass, Set<OWLClass>>();
        Set<OWLClass> hasParent = new HashSet<OWLClass>();
        Set<OWLClass> classes = new LinkedHashSet<OWLClass>();
        for (OWLClass cls : ontology.getClassesInSignature(Imports.INCLUDED)) {
            if (!cls.isOWLThing() && !cls.isOWLNothing()) {
                classes.add(cls);
            }
        }
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (OWLSubClassOfAxiom axiom
                    : part.getAxioms(org.semanticweb.owlapi.model.AxiomType.SUBCLASS_OF)) {
                OWLClassExpression sub = axiom.getSubClass();
                OWLClassExpression sup = axiom.getSuperClass();
                if (sub.isAnonymous() || sup.isAnonymous() || sup.isOWLThing()) {
                    continue;
                }
                OWLClass child = sub.asOWLClass();
                OWLClass parent = sup.asOWLClass();
                if (!children.containsKey(parent)) {
                    children.put(parent, new HashSet<OWLClass>());
                }
                children.get(parent).add(child);
                hasParent.add(child);
            }
        }
        StringBuilder out = new StringBuilder();
        Map<OWLClass, String> shownUnder = new HashMap<OWLClass, String>();
        List<OWLClass> roots = new ArrayList<OWLClass>();
        for (OWLClass cls : classes) {
            if (!hasParent.contains(cls)) {
                roots.add(cls);
            }
        }
        sortClasses(ontology, roots);
        for (OWLClass root : roots) {
            walk(out, ontology, root, 0, "", children, shownUnder);
        }
        // A class on a cycle of subclass axioms has a parent and no root above it.
        List<OWLClass> unreached = new ArrayList<OWLClass>();
        for (OWLClass cls : classes) {
            if (!shownUnder.containsKey(cls)) {
                unreached.add(cls);
            }
        }
        sortClasses(ontology, unreached);
        for (OWLClass cls : unreached) {
            if (!shownUnder.containsKey(cls)) {
                walk(out, ontology, cls, 0, "", children, shownUnder);
            }
        }
        return out.toString();
    }

    private static void walk(StringBuilder out, OWLOntology ontology, OWLClass cls, int depth,
            String parentLabel, Map<OWLClass, Set<OWLClass>> children,
            Map<OWLClass, String> shownUnder) {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
        String label = DisplayLabels.forEntity(ontology, cls);
        if (shownUnder.containsKey(cls)) {
            String first = shownUnder.get(cls);
            out.append(label).append(first.isEmpty() ? " (shown above)"
                    : " (also under " + first + ")").append('\n');
            return;
        }
        shownUnder.put(cls, parentLabel);
        out.append(label).append('\n');
        Set<OWLClass> below = children.get(cls);
        if (below == null) {
            return;
        }
        List<OWLClass> sorted = new ArrayList<OWLClass>(below);
        sortClasses(ontology, sorted);
        for (OWLClass child : sorted) {
            walk(out, ontology, child, depth + 1, label, children, shownUnder);
        }
    }

    /**
     * Every logical axiom of the ontology itself - not its imports - and its labels, synonyms,
     * definitions and comments, one per line, sorted. The most a person can choose to send.
     */
    public static String wholeOntology(OWLOntology ontology) {
        Set<String> lines = new TreeSet<String>();
        for (OWLAxiom axiom : ontology.getAxioms()) {
            if (axiom.isLogicalAxiom()) {
                lines.add(AssistantRendering.render(ontology, axiom.getAxiomWithoutAnnotations()));
            } else if (axiom instanceof OWLAnnotationAssertionAxiom) {
                OWLAnnotationAssertionAxiom annotation = (OWLAnnotationAssertionAxiom) axiom;
                // instanceof, not asIRI(): OWL API 4.5.9 (Protégé 5.5.0's) has no asIRI on a subject.
                if (!(annotation.getSubject() instanceof IRI)
                        || !annotation.getValue().asLiteral().isPresent()) {
                    continue;
                }
                IRI subject = (IRI) annotation.getSubject();
                List<OWLEntity> named = TermLines.entities(ontology, subject);
                String label = named.isEmpty() ? DisplayLabels.shortNameOf(subject)
                        : DisplayLabels.forEntity(ontology, named.get(0));
                TermLine line = TermLines.annotationLine(ontology, label, annotation);
                if (line != null && isWorthSending(line)) {
                    lines.add(line.getRendered());
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static boolean isWorthSending(TermLine line) {
        String key = line.getKey();
        return "label".equals(key) || "synonym".equals(key) || "definition".equals(key)
                || "comment".equals(key);
    }

    private static Set<OWLEntity> namedParents(OWLOntology ontology, OWLClass cls) {
        Set<OWLEntity> parents = new HashSet<OWLEntity>();
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (OWLSubClassOfAxiom axiom : part.getSubClassAxiomsForSubClass(cls)) {
                if (!axiom.getSuperClass().isAnonymous() && !axiom.getSuperClass().isOWLThing()) {
                    parents.add(axiom.getSuperClass().asOWLClass());
                }
            }
        }
        return parents;
    }

    private static Set<OWLEntity> namedChildren(OWLOntology ontology, OWLClass cls) {
        Set<OWLEntity> children = new HashSet<OWLEntity>();
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (OWLSubClassOfAxiom axiom : part.getSubClassAxiomsForSuperClass(cls)) {
                if (!axiom.getSubClass().isAnonymous()) {
                    children.add(axiom.getSubClass().asOWLClass());
                }
            }
        }
        return children;
    }

    /** Labels, sorted, at most {@code cap}, then "and N more"; "(none)" for nothing. */
    private static String names(OWLOntology ontology, Collection<OWLEntity> entities, int cap) {
        if (entities.isEmpty()) {
            return "(none)";
        }
        List<OWLEntity> sorted = new ArrayList<OWLEntity>(entities);
        sortByLabel(ontology, sorted);
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (OWLEntity entity : sorted) {
            if (count == cap) {
                out.append(", and ").append(sorted.size() - cap).append(" more");
                break;
            }
            out.append(count == 0 ? "" : ", ").append(DisplayLabels.forEntity(ontology, entity));
            count++;
        }
        return out.toString();
    }

    private static void sortByLabel(final OWLOntology ontology, List<OWLEntity> entities) {
        Collections.sort(entities, new Comparator<OWLEntity>() {
            @Override
            public int compare(OWLEntity a, OWLEntity b) {
                int byLabel = DisplayLabels.forEntity(ontology, a)
                        .compareToIgnoreCase(DisplayLabels.forEntity(ontology, b));
                return byLabel != 0 ? byLabel : a.getIRI().compareTo(b.getIRI());
            }
        });
    }

    private static void sortClasses(final OWLOntology ontology, List<OWLClass> classes) {
        Collections.sort(classes, new Comparator<OWLClass>() {
            @Override
            public int compare(OWLClass a, OWLClass b) {
                int byLabel = DisplayLabels.forEntity(ontology, a)
                        .compareToIgnoreCase(DisplayLabels.forEntity(ontology, b));
                return byLabel != 0 ? byLabel : a.getIRI().compareTo(b.getIRI());
            }
        });
    }

    /** "Class" or "class", "Object property" or "object property"... */
    static String kindOf(OWLEntity entity, boolean capitalised) {
        EntityType<?> type = entity.getEntityType();
        String kind;
        if (type == EntityType.CLASS) {
            kind = "class";
        } else if (type == EntityType.OBJECT_PROPERTY) {
            kind = "object property";
        } else if (type == EntityType.DATA_PROPERTY) {
            kind = "data property";
        } else if (type == EntityType.ANNOTATION_PROPERTY) {
            kind = "annotation property";
        } else if (type == EntityType.NAMED_INDIVIDUAL) {
            kind = "individual";
        } else {
            kind = "datatype";
        }
        return capitalised ? Character.toUpperCase(kind.charAt(0)) + kind.substring(1) : kind;
    }
}
