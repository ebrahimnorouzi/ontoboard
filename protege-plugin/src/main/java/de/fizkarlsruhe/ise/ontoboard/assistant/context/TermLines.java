package de.fizkarlsruhe.ise.ontoboard.assistant.context;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.robot.Explanations;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyCharacteristicAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLDisjointUnionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNaryPropertyAxiom;
import org.semanticweb.owlapi.model.OWLNegativeDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLNegativeObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyCharacteristicAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSameIndividualAxiom;
import org.semanticweb.owlapi.model.OWLSubAnnotationPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * What a term says, one line per annotation and per axiom: the list the assistant column shows,
 * and the body of the term's block in the context.
 *
 * <p>Read from the imports closure, as {@code DisplayLabels} reads labels: a term imported from
 * BFO says what BFO says about it. Rendered with labels as names, through
 * {@link Explanations#render}, so the chat names things the way *Explain...* does. Sorted, so the
 * same ontology always gives the same lines in the same order and the same request.
 */
public final class TermLines {

    /** Annotation properties with a name of their own; the rest are named by their label. */
    private static final Map<String, String> ANNOTATION_KEYS = new LinkedHashMap<String, String>();

    static {
        ANNOTATION_KEYS.put("http://www.w3.org/2000/01/rdf-schema#label", "label");
        ANNOTATION_KEYS.put("http://www.w3.org/2004/02/skos/core#prefLabel", "label");
        ANNOTATION_KEYS.put("http://www.geneontology.org/formats/oboInOwl#hasExactSynonym",
                "synonym");
        ANNOTATION_KEYS.put("http://www.geneontology.org/formats/oboInOwl#hasRelatedSynonym",
                "synonym");
        ANNOTATION_KEYS.put("http://www.geneontology.org/formats/oboInOwl#hasBroadSynonym",
                "synonym");
        ANNOTATION_KEYS.put("http://www.geneontology.org/formats/oboInOwl#hasNarrowSynonym",
                "synonym");
        ANNOTATION_KEYS.put("http://www.w3.org/2004/02/skos/core#altLabel", "synonym");
        ANNOTATION_KEYS.put("http://purl.obolibrary.org/obo/IAO_0000115", "definition");
        ANNOTATION_KEYS.put("http://www.w3.org/2004/02/skos/core#definition", "definition");
        ANNOTATION_KEYS.put("http://www.w3.org/2000/01/rdf-schema#comment", "comment");
        ANNOTATION_KEYS.put("http://purl.obolibrary.org/obo/IAO_0000112", "example");
        ANNOTATION_KEYS.put("http://purl.obolibrary.org/obo/IAO_0000116", "editor note");
    }

    /** The order keys are listed in; anything else comes after, alphabetically. */
    private static final List<String> KEY_ORDER = Arrays.asList("label", "synonym", "definition",
            "comment", "example", "editor note", "SubClassOf", "EquivalentTo", "DisjointWith",
            "DisjointUnionOf", "SubPropertyOf", "InverseOf", "Domain", "Range", "Characteristic",
            "SubPropertyChain", "Types", "Facts", "SameAs", "DifferentFrom");

    private TermLines() {
    }

    /** Every line about every entity with this IRI - a class and an individual may share one. */
    public static List<TermLine> of(OWLOntology ontology, IRI iri) {
        List<TermLine> lines = new ArrayList<TermLine>();
        for (OWLEntity entity : entities(ontology, iri)) {
            lines.addAll(of(ontology, entity));
        }
        return lines;
    }

    /** The entities with this IRI, in a fixed order: classes, properties, individuals. */
    static List<OWLEntity> entities(OWLOntology ontology, IRI iri) {
        List<OWLEntity> found =
                new ArrayList<OWLEntity>(ontology.getEntitiesInSignature(iri, Imports.INCLUDED));
        Collections.sort(found, new Comparator<OWLEntity>() {
            @Override
            public int compare(OWLEntity a, OWLEntity b) {
                return a.getEntityType().getName().compareTo(b.getEntityType().getName());
            }
        });
        return found;
    }

    static List<TermLine> of(OWLOntology ontology, OWLEntity entity) {
        List<TermLine> annotations = new ArrayList<TermLine>();
        List<TermLine> semantics = new ArrayList<TermLine>();
        String subject = DisplayLabels.forEntity(ontology, entity);
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (OWLAnnotationAssertionAxiom axiom
                    : part.getAnnotationAssertionAxioms(entity.getIRI())) {
                TermLine line = annotationLine(ontology, subject, axiom);
                if (line != null && !contains(annotations, line)) {
                    annotations.add(line);
                }
            }
            for (OWLAxiom axiom : part.getReferencingAxioms(entity)) {
                for (TermLine line : semanticLines(ontology, entity, axiom)) {
                    if (!contains(semantics, line)) {
                        semantics.add(line);
                    }
                }
            }
        }
        sort(annotations);
        sort(semantics);
        List<TermLine> all = new ArrayList<TermLine>(annotations);
        all.addAll(semantics);
        return all;
    }

    private static boolean contains(List<TermLine> lines, TermLine candidate) {
        for (TermLine line : lines) {
            if (line.getKey().equals(candidate.getKey())
                    && line.getText().equals(candidate.getText())) {
                return true;
            }
        }
        return false;
    }

    private static void sort(List<TermLine> lines) {
        Collections.sort(lines, new Comparator<TermLine>() {
            @Override
            public int compare(TermLine a, TermLine b) {
                int byKey = Integer.compare(rank(a.getKey()), rank(b.getKey()));
                if (byKey != 0) {
                    return byKey;
                }
                int byName = a.getKey().compareTo(b.getKey());
                return byName != 0 ? byName : a.getText().compareTo(b.getText());
            }
        });
    }

    private static int rank(String key) {
        int at = KEY_ORDER.indexOf(key);
        return at < 0 ? KEY_ORDER.size() : at;
    }

    static TermLine annotationLine(OWLOntology ontology, String subject,
            OWLAnnotationAssertionAxiom axiom) {
        String property = axiom.getProperty().getIRI().toString();
        String key = ANNOTATION_KEYS.get(property);
        if (key == null) {
            key = DisplayLabels.forEntity(ontology, axiom.getProperty());
        }
        String text;
        if (axiom.getValue().asLiteral().isPresent()) {
            OWLLiteral literal = axiom.getValue().asLiteral().get();
            text = oneLine(literal.getLiteral());
            if (literal.hasLang()) {
                text = text + " (" + literal.getLang() + ")";
            }
        } else if (axiom.getValue().asIRI().isPresent()) {
            text = axiom.getValue().asIRI().get().toString();
        } else {
            return null;
        }
        if (text.isEmpty()) {
            return null;
        }
        return new TermLine(TermLine.Group.ANNOTATION, key, text,
                subject + " " + key + ": " + text, axiom);
    }

    /** The lines an axiom gives the entity, when the entity is its subject; none otherwise. */
    private static List<TermLine> semanticLines(OWLOntology ontology, OWLEntity entity,
            OWLAxiom axiom) {
        List<TermLine> lines = new ArrayList<TermLine>();
        if (axiom instanceof OWLSubClassOfAxiom) {
            OWLSubClassOfAxiom sub = (OWLSubClassOfAxiom) axiom;
            if (entity.equals(sub.getSubClass())) {
                add(lines, ontology, "SubClassOf", sub.getSuperClass(), axiom);
            }
        } else if (axiom instanceof OWLEquivalentClassesAxiom) {
            // A property used in a defined class is in the axiom's signature too; only the
            // classes it equates are its subjects.
            OWLEquivalentClassesAxiom equivalent = (OWLEquivalentClassesAxiom) axiom;
            if (entity.isOWLClass() && equivalent.contains(entity.asOWLClass())) {
                for (OWLClassExpression other
                        : equivalent.getClassExpressionsMinus(entity.asOWLClass())) {
                    add(lines, ontology, "EquivalentTo", other, axiom);
                }
            }
        } else if (axiom instanceof OWLDisjointClassesAxiom) {
            if (entity.isOWLClass()
                    && ((OWLDisjointClassesAxiom) axiom).contains(entity.asOWLClass())) {
                for (OWLClassExpression other
                        : ((OWLDisjointClassesAxiom) axiom).getClassExpressionsMinus(
                                entity.asOWLClass())) {
                    add(lines, ontology, "DisjointWith", other, axiom);
                }
            }
        } else if (axiom instanceof OWLDisjointUnionAxiom) {
            OWLDisjointUnionAxiom union = (OWLDisjointUnionAxiom) axiom;
            if (entity.equals(union.getOWLClass())) {
                lines.add(line("DisjointUnionOf", joined(ontology, union.getClassExpressions(),
                        ", "), ontology, axiom));
            }
        } else if (axiom instanceof OWLObjectPropertyDomainAxiom) {
            OWLObjectPropertyDomainAxiom domain = (OWLObjectPropertyDomainAxiom) axiom;
            if (entity.equals(domain.getProperty())) {
                add(lines, ontology, "Domain", domain.getDomain(), axiom);
            }
        } else if (axiom instanceof OWLObjectPropertyRangeAxiom) {
            OWLObjectPropertyRangeAxiom range = (OWLObjectPropertyRangeAxiom) axiom;
            if (entity.equals(range.getProperty())) {
                add(lines, ontology, "Range", range.getRange(), axiom);
            }
        } else if (axiom instanceof OWLDataPropertyDomainAxiom) {
            OWLDataPropertyDomainAxiom domain = (OWLDataPropertyDomainAxiom) axiom;
            if (entity.equals(domain.getProperty())) {
                add(lines, ontology, "Domain", domain.getDomain(), axiom);
            }
        } else if (axiom instanceof OWLDataPropertyRangeAxiom) {
            OWLDataPropertyRangeAxiom range = (OWLDataPropertyRangeAxiom) axiom;
            if (entity.equals(range.getProperty())) {
                add(lines, ontology, "Range", range.getRange(), axiom);
            }
        } else if (axiom instanceof OWLObjectPropertyCharacteristicAxiom) {
            if (entity.equals(((OWLObjectPropertyCharacteristicAxiom) axiom).getProperty())) {
                lines.add(line("Characteristic", characteristic(axiom.getAxiomType()), ontology,
                        axiom));
            }
        } else if (axiom instanceof OWLDataPropertyCharacteristicAxiom) {
            if (entity.equals(((OWLDataPropertyCharacteristicAxiom) axiom).getProperty())) {
                lines.add(line("Characteristic", characteristic(axiom.getAxiomType()), ontology,
                        axiom));
            }
        } else if (axiom instanceof OWLInverseObjectPropertiesAxiom) {
            OWLInverseObjectPropertiesAxiom inverse = (OWLInverseObjectPropertiesAxiom) axiom;
            if (entity.equals(inverse.getFirstProperty())) {
                add(lines, ontology, "InverseOf", inverse.getSecondProperty(), axiom);
            } else if (entity.equals(inverse.getSecondProperty())) {
                add(lines, ontology, "InverseOf", inverse.getFirstProperty(), axiom);
            }
        } else if (axiom instanceof OWLSubObjectPropertyOfAxiom) {
            OWLSubObjectPropertyOfAxiom sub = (OWLSubObjectPropertyOfAxiom) axiom;
            if (entity.equals(sub.getSubProperty())) {
                add(lines, ontology, "SubPropertyOf", sub.getSuperProperty(), axiom);
            }
        } else if (axiom instanceof OWLSubDataPropertyOfAxiom) {
            OWLSubDataPropertyOfAxiom sub = (OWLSubDataPropertyOfAxiom) axiom;
            if (entity.equals(sub.getSubProperty())) {
                add(lines, ontology, "SubPropertyOf", sub.getSuperProperty(), axiom);
            }
        } else if (axiom instanceof OWLSubAnnotationPropertyOfAxiom) {
            OWLSubAnnotationPropertyOfAxiom sub = (OWLSubAnnotationPropertyOfAxiom) axiom;
            if (entity.equals(sub.getSubProperty())) {
                add(lines, ontology, "SubPropertyOf", sub.getSuperProperty(), axiom);
            }
        } else if (axiom instanceof OWLAnnotationPropertyDomainAxiom) {
            OWLAnnotationPropertyDomainAxiom domain = (OWLAnnotationPropertyDomainAxiom) axiom;
            if (entity.equals(domain.getProperty())) {
                lines.add(line("Domain", domain.getDomain().toString(), ontology, axiom));
            }
        } else if (axiom instanceof OWLAnnotationPropertyRangeAxiom) {
            OWLAnnotationPropertyRangeAxiom range = (OWLAnnotationPropertyRangeAxiom) axiom;
            if (entity.equals(range.getProperty())) {
                lines.add(line("Range", range.getRange().toString(), ontology, axiom));
            }
        } else if (axiom instanceof OWLSubPropertyChainOfAxiom) {
            OWLSubPropertyChainOfAxiom chain = (OWLSubPropertyChainOfAxiom) axiom;
            if (entity.equals(chain.getSuperProperty())) {
                lines.add(line("SubPropertyChain",
                        joined(ontology, chain.getPropertyChain(), " o "), ontology, axiom));
            }
        } else if (axiom instanceof OWLNaryPropertyAxiom) {
            naryProperty(lines, ontology, entity, axiom);
        } else if (axiom instanceof OWLClassAssertionAxiom) {
            OWLClassAssertionAxiom assertion = (OWLClassAssertionAxiom) axiom;
            if (entity.equals(assertion.getIndividual())) {
                add(lines, ontology, "Types", assertion.getClassExpression(), axiom);
            }
        } else if (axiom instanceof OWLObjectPropertyAssertionAxiom) {
            OWLObjectPropertyAssertionAxiom fact = (OWLObjectPropertyAssertionAxiom) axiom;
            if (entity.equals(fact.getSubject())) {
                lines.add(line("Facts", render(ontology, fact.getProperty()) + " "
                        + render(ontology, fact.getObject()), ontology, axiom));
            }
        } else if (axiom instanceof OWLDataPropertyAssertionAxiom) {
            OWLDataPropertyAssertionAxiom fact = (OWLDataPropertyAssertionAxiom) axiom;
            if (entity.equals(fact.getSubject())) {
                lines.add(line("Facts", render(ontology, fact.getProperty()) + " "
                        + render(ontology, fact.getObject()), ontology, axiom));
            }
        } else if (axiom instanceof OWLNegativeObjectPropertyAssertionAxiom) {
            OWLNegativeObjectPropertyAssertionAxiom fact =
                    (OWLNegativeObjectPropertyAssertionAxiom) axiom;
            if (entity.equals(fact.getSubject())) {
                lines.add(line("Facts", "not " + render(ontology, fact.getProperty()) + " "
                        + render(ontology, fact.getObject()), ontology, axiom));
            }
        } else if (axiom instanceof OWLNegativeDataPropertyAssertionAxiom) {
            OWLNegativeDataPropertyAssertionAxiom fact =
                    (OWLNegativeDataPropertyAssertionAxiom) axiom;
            if (entity.equals(fact.getSubject())) {
                lines.add(line("Facts", "not " + render(ontology, fact.getProperty()) + " "
                        + render(ontology, fact.getObject()), ontology, axiom));
            }
        } else if (axiom instanceof OWLSameIndividualAxiom) {
            for (OWLIndividual other : ((OWLSameIndividualAxiom) axiom).getIndividuals()) {
                if (!entity.equals(other)) {
                    add(lines, ontology, "SameAs", other, axiom);
                }
            }
        } else if (axiom instanceof OWLDifferentIndividualsAxiom) {
            for (OWLIndividual other : ((OWLDifferentIndividualsAxiom) axiom).getIndividuals()) {
                if (!entity.equals(other)) {
                    add(lines, ontology, "DifferentFrom", other, axiom);
                }
            }
        }
        return lines;
    }

    /** Equivalent and disjoint properties: one line per other property. */
    private static void naryProperty(List<TermLine> lines, OWLOntology ontology, OWLEntity entity,
            OWLAxiom axiom) {
        String key;
        AxiomType<?> type = axiom.getAxiomType();
        if (type == AxiomType.EQUIVALENT_OBJECT_PROPERTIES
                || type == AxiomType.EQUIVALENT_DATA_PROPERTIES) {
            key = "EquivalentTo";
        } else if (type == AxiomType.DISJOINT_OBJECT_PROPERTIES
                || type == AxiomType.DISJOINT_DATA_PROPERTIES) {
            key = "DisjointWith";
        } else {
            return;
        }
        for (Object property : ((OWLNaryPropertyAxiom<?>) axiom).getProperties()) {
            if (!entity.equals(property) && property instanceof OWLObject) {
                add(lines, ontology, key, (OWLObject) property, axiom);
            }
        }
    }

    private static String characteristic(AxiomType<?> type) {
        if (type == AxiomType.FUNCTIONAL_OBJECT_PROPERTY
                || type == AxiomType.FUNCTIONAL_DATA_PROPERTY) {
            return "Functional";
        }
        if (type == AxiomType.INVERSE_FUNCTIONAL_OBJECT_PROPERTY) {
            return "InverseFunctional";
        }
        if (type == AxiomType.TRANSITIVE_OBJECT_PROPERTY) {
            return "Transitive";
        }
        if (type == AxiomType.SYMMETRIC_OBJECT_PROPERTY) {
            return "Symmetric";
        }
        if (type == AxiomType.ASYMMETRIC_OBJECT_PROPERTY) {
            return "Asymmetric";
        }
        if (type == AxiomType.REFLEXIVE_OBJECT_PROPERTY) {
            return "Reflexive";
        }
        if (type == AxiomType.IRREFLEXIVE_OBJECT_PROPERTY) {
            return "Irreflexive";
        }
        return type.getName();
    }

    private static void add(List<TermLine> lines, OWLOntology ontology, String key,
            OWLObject object, OWLAxiom axiom) {
        lines.add(line(key, render(ontology, object), ontology, axiom));
    }

    private static TermLine line(String key, String text, OWLOntology ontology, OWLAxiom axiom) {
        return new TermLine(TermLine.Group.SEMANTICS, key, text,
                render(ontology, axiom.getAxiomWithoutAnnotations()), axiom);
    }

    private static String joined(OWLOntology ontology, Iterable<? extends OWLObject> parts,
            String separator) {
        StringBuilder out = new StringBuilder();
        for (OWLObject part : parts) {
            out.append(out.length() == 0 ? "" : separator).append(render(ontology, part));
        }
        return out.toString();
    }

    static String render(OWLOntology ontology, OWLObject object) {
        if (object instanceof OWLObjectPropertyExpression
                && ((OWLObjectPropertyExpression) object).isNamed()) {
            return DisplayLabels.forEntity(ontology,
                    ((OWLObjectPropertyExpression) object).asOWLObjectProperty());
        }
        return AssistantRendering.render(ontology, object);
    }

    /** Annotation text as one line: a definition with a line break would break the block. */
    static String oneLine(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ')
                .replaceAll(" +", " ").trim();
    }
}
