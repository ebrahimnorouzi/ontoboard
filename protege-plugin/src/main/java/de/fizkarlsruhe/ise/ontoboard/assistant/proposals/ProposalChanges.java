package de.fizkarlsruhe.ise.ontoboard.assistant.proposals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveAxiom;

/**
 * What Accept writes: one change list, so one *Edit → Undo* takes it back.
 *
 * <ul>
 *   <li>{@code set}: the term's values of the property in the proposal's language are removed and
 *       the new one added - the same values the card struck through.</li>
 *   <li>{@code add}: the new value is added.</li>
 *   <li>{@code remove-axiom}: the matched axiom is removed.</li>
 * </ul>
 *
 * <p>What is added carries {@code dcterms:contributor "<provenance>"} as an axiom annotation
 * (spec D5), so the ontology records which suggestions came from a model, and which model.
 */
public final class ProposalChanges {

    static final IRI CONTRIBUTOR = IRI.create("http://purl.org/dc/terms/contributor");

    private ProposalChanges() {
    }

    /**
     * @param proposal validated and OFFERED
     * @param provenance "OntoBoard assistant (ollama qwen2.5:7b)", or null for no annotation
     */
    public static List<OWLOntologyChange> build(OWLOntology ontology, Proposal proposal,
            String provenance) {
        if (proposal.getStatus() != Proposal.Status.OFFERED) {
            throw new IllegalArgumentException("Only an offered proposal is applied: " + proposal);
        }
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        if (proposal.getAction() == Proposal.Action.REMOVE_AXIOM) {
            changes.add(new RemoveAxiom(ontology, proposal.getMatchedAxiom()));
            return changes;
        }
        if (proposal.getAction() == Proposal.Action.ADD_AXIOM
                || proposal.getAction() == Proposal.Action.REPLACE_AXIOM) {
            if (proposal.getAction() == Proposal.Action.REPLACE_AXIOM) {
                changes.add(new RemoveAxiom(ontology, proposal.getMatchedAxiom()));
            }
            OWLAxiom added = proposal.getNewAxiom();
            if (provenance != null) {
                OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
                added = added.getAnnotatedAxiom(Collections.singleton(factory.getOWLAnnotation(
                        factory.getOWLAnnotationProperty(CONTRIBUTOR),
                        factory.getOWLLiteral(provenance))));
            }
            changes.add(new AddAxiom(ontology, added));
            return changes;
        }
        if (proposal.getAction() == Proposal.Action.SET) {
            for (OWLAnnotationAssertionAxiom old : ProposalValidator.inLanguage(
                    ProposalValidator.assertions(ontology, proposal.getSubjectIri(),
                            proposal.getPropertyIri()), proposal.getLanguage())) {
                changes.add(new RemoveAxiom(ontology, old));
            }
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLLiteral value = proposal.getLanguage() == null
                ? factory.getOWLLiteral(proposal.getValueText())
                : factory.getOWLLiteral(proposal.getValueText(), proposal.getLanguage());
        Set<OWLAnnotation> annotations = provenance == null
                ? Collections.<OWLAnnotation>emptySet()
                : Collections.singleton(factory.getOWLAnnotation(
                        factory.getOWLAnnotationProperty(CONTRIBUTOR),
                        factory.getOWLLiteral(provenance)));
        changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(proposal.getPropertyIri()),
                proposal.getSubjectIri(), value, annotations)));
        return changes;
    }
}
