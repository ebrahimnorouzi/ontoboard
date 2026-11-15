package de.fizkarlsruhe.ise.ontoboard.assistant.context;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import java.io.StringWriter;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxObjectRenderer;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.util.ShortFormProvider;

/**
 * OWL as the assistant sends it: Manchester syntax with labels as names - as {@code Explanations}
 * writes it, so the chat and *Explain...* name things alike - but with every typed literal
 * written with its type.
 *
 * <p>The Manchester renderer writes {@code "9.50"^^xsd:decimal} as {@code 9.50} and a float as
 * {@code 9.50f}, which is correct syntax and a trap for a reader: on the pizza example the
 * reasoner's justification read "funghi price has value 9.50" and "has value Range: float", and
 * the decimal-against-float clash it found was invisible. A local model then explained a clash
 * that does not exist (measured, 1.136.0). Written {@code "9.50"^^xsd:decimal}, the clash is on
 * the page.
 */
public final class AssistantRendering {

    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private AssistantRendering() {
    }

    public static String render(final OWLOntology ontology, OWLObject object) {
        if (object == null) {
            return "";
        }
        if (object instanceof OWLLiteral) {
            return literal((OWLLiteral) object);
        }
        StringWriter written = new StringWriter();
        ShortFormProvider labels = new ShortFormProvider() {
            @Override
            public String getShortForm(OWLEntity entity) {
                // A datatype keeps its prefix, so "Range: xsd:float" sits beside
                // "\"9.50\"^^xsd:decimal" and the two read as the two types they are.
                if (entity.isOWLDatatype()) {
                    String iri = entity.getIRI().toString();
                    if (iri.startsWith(XSD)) {
                        return "xsd:" + iri.substring(XSD.length());
                    }
                    if (iri.startsWith(RDF)) {
                        return "rdf:" + iri.substring(RDF.length());
                    }
                }
                return name(unique(ontology, entity));
            }

            @Override
            public void dispose() {
            }
        };
        ManchesterOWLSyntaxObjectRenderer renderer =
                new ManchesterOWLSyntaxObjectRenderer(written, labels) {
                    @Override
                    public void visit(OWLLiteral node) {
                        write(literal(node));
                    }
                };
        object.accept(renderer);
        return written.toString().replace('\n', ' ').replace('\r', ' ').replaceAll(" +", " ")
                .trim();
    }

    /**
     * Per ontology, the names more than one term has; dropped when a label or term changes.
     * Keyed by identity (Guava's weak keys): two ontologies with the same IRI are equal to the
     * OWL API, and must not share an entry.
     */
    private static final java.util.Map<OWLOntology, java.util.Set<String>> SHARED =
            new com.google.common.collect.MapMaker().weakKeys().makeMap();
    private static final java.util.Map<org.semanticweb.owlapi.model.OWLOntologyManager, Boolean>
            WATCHED = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    /**
     * The entity's name, unique in the ontology (1.140.0): its label; its short name when another
     * term has the same label (MWO labels two properties "is subject of"); its IRI when even the
     * short name is shared. A name two terms share would make an axiom the model copies back
     * ambiguous.
     */
    public static String unique(OWLOntology ontology, OWLEntity entity) {
        String label = DisplayLabels.forEntity(ontology, entity);
        java.util.Set<String> shared = shared(ontology);
        if (!shared.contains(key(label))) {
            return label;
        }
        String shortName = DisplayLabels.shortNameOf(entity.getIRI());
        if (!shortName.isEmpty() && !shared.contains(key(shortName))) {
            return shortName;
        }
        return "<" + entity.getIRI() + ">";
    }

    private static String key(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static java.util.Set<String> shared(OWLOntology ontology) {
        java.util.Set<String> known = SHARED.get(ontology);
        if (known != null) {
            return known;
        }
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                ontology.getOWLOntologyManager();
        if (WATCHED.put(manager, Boolean.TRUE) == null) {
            manager.addOntologyChangeListener(changes -> {
                for (org.semanticweb.owlapi.model.OWLOntologyChange change : changes) {
                    if (change.isAxiomChange() && (change.getAxiom()
                            instanceof org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom
                            || change.getAxiom()
                                    instanceof org.semanticweb.owlapi.model.OWLDeclarationAxiom)) {
                        SHARED.clear();
                        return;
                    }
                }
            });
        }
        java.util.Map<String, java.util.Set<IRI>> byName =
                new java.util.HashMap<String, java.util.Set<IRI>>();
        for (OWLEntity entity : ontology.getSignature(
                org.semanticweb.owlapi.model.parameters.Imports.INCLUDED)) {
            if (entity.isBuiltIn()) {
                continue;
            }
            for (String name : new String[] {DisplayLabels.forEntity(ontology, entity),
                DisplayLabels.shortNameOf(entity.getIRI())}) {
                java.util.Set<IRI> iris = byName.get(key(name));
                if (iris == null) {
                    iris = new java.util.HashSet<IRI>();
                    byName.put(key(name), iris);
                }
                iris.add(entity.getIRI());
            }
        }
        java.util.Set<String> result = new java.util.HashSet<String>();
        for (java.util.Map.Entry<String, java.util.Set<IRI>> entry : byName.entrySet()) {
            if (entry.getValue().size() > 1) {
                result.add(entry.getKey());
            }
        }
        SHARED.put(ontology, result);
        return result;
    }

    /** Manchester keywords a name must not be read as. */
    private static final java.util.Set<String> KEYWORDS = new java.util.HashSet<String>(
            java.util.Arrays.asList("and", "or", "not", "some", "only", "value", "min", "max",
                    "exactly", "that", "inverse", "self", "true", "false", "integer", "decimal",
                    "float", "string"));

    /**
     * A name as Manchester syntax reads it back (1.139.0): in single quotes when the label holds
     * anything but letters, digits, '_', '-', '.', ':' and '#', or is a keyword - as Protégé
     * writes 'cheese topping'. The model copies axioms back as proposals; unquoted, a label with
     * a space does not parse.
     */
    public static String name(String label) {
        if (label == null || label.isEmpty()) {
            return "''";
        }
        if (label.startsWith("<") && label.endsWith(">")) {
            return label;
        }
        boolean plain = label.matches("[\\p{L}_][\\p{L}\\p{N}_\\-.:#]*")
                && !KEYWORDS.contains(label.toLowerCase(java.util.Locale.ROOT));
        return plain ? label : "'" + label.replace("'", "\\'") + "'";
    }

    /** {@code "text"@de}, {@code "plain"}, or {@code "9.50"^^xsd:decimal}. */
    static String literal(OWLLiteral literal) {
        String quoted = "\"" + literal.getLiteral().replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"";
        if (literal.hasLang()) {
            return quoted + "@" + literal.getLang();
        }
        IRI type = literal.getDatatype().getIRI();
        String iri = type.toString();
        if (iri.equals(XSD + "string") || iri.equals(RDF + "PlainLiteral")) {
            return quoted;
        }
        if (iri.startsWith(XSD)) {
            return quoted + "^^xsd:" + iri.substring(XSD.length());
        }
        if (iri.startsWith(RDF)) {
            return quoted + "^^rdf:" + iri.substring(RDF.length());
        }
        return quoted + "^^<" + iri + ">";
    }
}
