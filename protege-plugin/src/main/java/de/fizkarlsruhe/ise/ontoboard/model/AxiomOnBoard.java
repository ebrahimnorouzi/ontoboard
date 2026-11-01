package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Which drawn arrows show an axiom - so that clicking the axiom's line in Protégé's description
 * view takes the board to them.
 *
 * <p>Asked for: "once I click a logic line in the left panel I want to be taken to the edge and
 * the classes it relates on the canvas". An arrow shows the axiom when both its ends are terms
 * the axiom mentions, or one end is and the other is a grey expression node - the restriction or
 * union the axiom is about; and when the axiom names a property, an arrow labelled with another
 * property does not count. Pure: the ends and labels come from the board, the axiom from Protégé.
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
}
