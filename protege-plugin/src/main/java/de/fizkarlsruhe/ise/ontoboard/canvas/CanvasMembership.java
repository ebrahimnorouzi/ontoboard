package de.fizkarlsruhe.ise.ontoboard.canvas;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

/**
 * Which entities the user has chosen to show (spec section 5.3). The canvas is opt-in
 * because Protege routinely opens ontologies far larger than any diagram can hold.
 *
 * <p>Removing an entity here is purely a view operation and never touches the ontology.
 */
public class CanvasMembership {

    private final CanvasLayout layout;

    public CanvasMembership(CanvasLayout layout) {
        this.layout = layout;
    }

    public boolean add(String iri) {
        if (layout.onCanvas.contains(iri)) {
            return false;
        }
        layout.onCanvas.add(iri);
        return true;
    }

    public boolean remove(String iri) {
        layout.nodes.remove(iri);
        return layout.onCanvas.remove(iri);
    }

    public boolean contains(String iri) {
        return layout.onCanvas.contains(iri);
    }

    public Set<String> asSet() {
        return new HashSet<>(layout.onCanvas);
    }

    public int size() {
        return layout.onCanvas.size();
    }

    /** Adds every entity directly related to {@code iri}. Returns how many were newly added. */
    public int expandOneHop(OWLOntology ontology, String iri) {
        Set<String> neighbours = new LinkedHashSet<>();

        for (OWLSubClassOfAxiom axiom : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            collectIfTouches(axiom.getSubClass(), axiom.getSuperClass(), iri, neighbours);
            collectIfTouches(axiom.getSuperClass(), axiom.getSubClass(), iri, neighbours);
        }

        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
            Set<String> domains = new LinkedHashSet<>();
            Set<String> ranges = new LinkedHashSet<>();
            for (OWLObjectPropertyDomainAxiom d : ontology.getObjectPropertyDomainAxioms(property)) {
                addNamed(d.getDomain(), domains);
            }
            for (OWLObjectPropertyRangeAxiom r : ontology.getObjectPropertyRangeAxioms(property)) {
                addNamed(r.getRange(), ranges);
            }
            if (domains.contains(iri)) {
                neighbours.addAll(ranges);
            }
            if (ranges.contains(iri)) {
                neighbours.addAll(domains);
            }
        }

        for (OWLClassAssertionAxiom axiom : ontology.getAxioms(AxiomType.CLASS_ASSERTION)) {
            if (axiom.getIndividual().isAnonymous() || axiom.getClassExpression().isAnonymous()) {
                continue;
            }
            String ind = axiom.getIndividual().asOWLNamedIndividual().getIRI().toString();
            String cls = axiom.getClassExpression().asOWLClass().getIRI().toString();
            if (ind.equals(iri)) {
                neighbours.add(cls);
            }
            if (cls.equals(iri)) {
                neighbours.add(ind);
            }
        }

        neighbours.remove(iri);
        int added = 0;
        for (String neighbour : neighbours) {
            if (add(neighbour)) {
                added++;
            }
        }
        return added;
    }

    private static void collectIfTouches(org.semanticweb.owlapi.model.OWLClassExpression anchor,
            org.semanticweb.owlapi.model.OWLClassExpression other, String iri, Set<String> into) {
        if (anchor.isAnonymous() || !anchor.asOWLClass().getIRI().toString().equals(iri)) {
            return;
        }
        for (OWLEntity entity : other.getSignature()) {
            if (entity.isOWLClass()) {
                into.add(entity.getIRI().toString());
            }
        }
    }

    private static void addNamed(org.semanticweb.owlapi.model.OWLClassExpression expression,
            Set<String> into) {
        if (!expression.isAnonymous()) {
            into.add(expression.asOWLClass().getIRI().toString());
        }
    }
}
