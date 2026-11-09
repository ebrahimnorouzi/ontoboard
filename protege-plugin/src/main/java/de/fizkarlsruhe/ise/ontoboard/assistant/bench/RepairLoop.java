package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Assembled;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.RenderedItem;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.RequestAssembler;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Turn;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ChatRequest;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelCallException;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Reply;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.AssistantRendering;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextItem;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.Problems;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalBlock;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalValidator;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.FixedRules;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.Questions;
import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * The repair task (round 2; maintainer: "apply the suggestion, check the consistency again and
 * by iterative loop see if it's solved or not"):
 *
 * <ol>
 *   <li>the model gets the justification and is asked for a repair, as the plugin asks;</li>
 *   <li>its proposals are validated as the plugin validates them, and its removals,
 *       replacements and additions are applied to a copy;</li>
 *   <li>the reasoner runs again; if the problem is still there, the new justification goes back
 *       - with the conversation so far, and why nothing was removed if nothing was - up to
 *       {@link #MAX_ROUNDS} rounds.</li>
 * </ol>
 *
 * <p>A repair takes away what is wrong: what was removed, and what a replacement replaced, is
 * compared with what was injected. An addition is applied too (1.139.0), but the logic is
 * monotonic, so an addition alone cannot remove the problem: it is no removal (1.149.0, review
 * 1.148.0 bench-02).
 */
final class RepairLoop {

    static final int MAX_ROUNDS = 3;

    /** One call to the model under test. */
    interface Model {
        Reply ask(ChatRequest request) throws ModelCallException;
    }

    /** What happened, round by round, and what it adds up to. */
    static final class Outcome {
        boolean solved;
        int rounds;
        boolean removedFault;
        int collateral;
        int proposals;
        int matched;
        final List<String> replies = new ArrayList<String>();
        final List<String> sent = new ArrayList<String>();
        long millis;
        int inputTokens;
        int outputTokens;
        String error;

        /**
         * Over all rounds, the share of the answers' blocks that changed the logic: removals and
         * replacements applied, and additions. Annotation cards, blocks shown as Cannot be
         * applied, a removal of an axiom an earlier block of the same answer removed, and an
         * addition of an axiom already there count against it; 0 with no block (1.149.0, review
         * 1.148.0 bench-01: it said "parsed and matched an axiom", and the documents "the share
         * OntoBoard would offer"; 1.150.0, review 1.149.0 bench-04).
         */
        double validProposals() {
            return proposals == 0 ? 0.0 : (double) matched / proposals;
        }
    }

    private RepairLoop() {
    }

    /**
     * The loop. An answer the reasoner cannot handle - HermiT's UnsupportedFacetException and
     * UnsupportedDatatypeException are RuntimeExceptions - is the outcome's error, with every
     * answer received so far kept: it ended the whole run, and every paid answer with it
     * (1.143.0, review 1.141.0 I2).
     */
    static Outcome run(BenchCase c, OWLOntology base, OWLReasonerFactory reasoner,
            String reasonerName, String roleText, int maxOutput, Model model) {
        Outcome outcome = new Outcome();
        outcome.inputTokens = Reply.UNKNOWN;
        outcome.outputTokens = Reply.UNKNOWN;
        try {
            return rounds(c, base, reasoner, reasonerName, roleText, maxOutput, model, outcome);
        } catch (RuntimeException cannotReason) {
            outcome.solved = false;
            outcome.error = "the reasoner could not check this answer: " + cannotReason;
            return outcome;
        }
    }

    private static Outcome rounds(BenchCase c, OWLOntology base, OWLReasonerFactory reasoner,
            String reasonerName, String roleText, int maxOutput, Model model, Outcome outcome) {
        Set<OWLAxiom> injected = BenchCases.faultAxioms(c);
        OWLOntology working = copy(base, injected);
        Set<String> justification = justificationLines(c);
        List<Turn> history = new ArrayList<Turn>();
        List<RenderedItem> context = c.getContext();
        String message = c.getMessage();
        List<OWLAxiom> removedAxioms = new ArrayList<OWLAxiom>();
        int outsideJustification = 0;
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            outcome.rounds = round;
            Assembled assembled = RequestAssembler.assemble(roleText, FixedRules.text(),
                    c.getHeader(), context, history, message, maxOutput);
            outcome.sent.add(assembled.getRequest().getMessages()
                    .get(assembled.getRequest().getMessages().size() - 1).getText());
            Reply reply;
            try {
                reply = model.ask(assembled.getRequest());
            } catch (ModelCallException failed) {
                outcome.error = failed.getMessage();
                return outcome;
            }
            outcome.replies.add(reply.getText());
            outcome.millis += Math.max(0, reply.getMillis());
            outcome.inputTokens = add(outcome.inputTokens, reply.getInputTokens());
            outcome.outputTokens = add(outcome.outputTokens, reply.getOutputTokens());

            List<String> refused = new ArrayList<String>();
            int removed = 0;
            int added = 0;
            ProposalValidator validator = new ProposalValidator(working);
            for (Proposal proposal : validator.validateAll(ProposalBlock.parse(reply.getText()))) {
                outcome.proposals++;
                if (proposal.getAction() == null) {
                    // The validator's reason, which names the actions there are: taken for an
                    // annotation card, it was "only changes to the logic are applied here" after
                    // an empty description (1.150.0, review 1.149.0 bench-01).
                    refused.add(named(proposal) + proposal.getProblem());
                    continue;
                }
                if (!proposal.getAction().isLogic()) {
                    refused.add(proposal.describe() + ": only changes to the logic are applied"
                            + " here");
                    continue;
                }
                if (proposal.getStatus() != Proposal.Status.OFFERED) {
                    // Named as the answer wrote it, or by what it would do: without its axiom:
                    // line, a block was quoted as "null" (1.151.0, review 1.150.0 bench-02).
                    refused.add(named(proposal) + proposal.getProblem());
                    continue;
                }
                if (proposal.getAction() == Proposal.Action.ADD_AXIOM) {
                    // An addition repairs nothing by itself here; it is applied, and the
                    // reasoner says whether the problem is gone (1.139.0). It is no removal:
                    // counted as one, the next request said "after that removal" and dropped
                    // why a removal sent with it was refused (1.149.0, review 1.148.0 bench-02).
                    if (working.containsAxiom(proposal.getNewAxiom())) {
                        // It changes nothing - the same addition again, or an axiom the
                        // ontology has - so it counts against, as a repeated removal does
                        // (1.150.0, review 1.149.0 bench-04).
                        continue;
                    }
                    outcome.matched++;
                    working.getOWLOntologyManager().addAxiom(working, proposal.getNewAxiom());
                    added++;
                    continue;
                }
                OWLAxiom axiom = proposal.getMatchedAxiom();
                if (!working.containsAxiom(axiom)) {
                    if (proposal.getAction() == Proposal.Action.REPLACE_AXIOM) {
                        // Dropped with the axiom it meant to add, and the model was not told
                        // (1.151.0, review 1.150.0 bench-03). A repeated removal stays silent.
                        refused.add(named(proposal) + "an earlier block of this answer already"
                                + " removed it, so nothing was replaced");
                    }
                    continue;
                }
                if (proposal.getAction() == Proposal.Action.REPLACE_AXIOM) {
                    // The replaced axiom counts as removed for removedFault and collateral; the
                    // new one is added with it.
                    working.getOWLOntologyManager().addAxiom(working, proposal.getNewAxiom());
                }
                outcome.matched++;
                boolean wasInjected = injected.contains(axiom)
                        || injected.contains(axiom.getAxiomWithoutAnnotations());
                boolean inJustification = justification.contains(
                        normal(AssistantRendering.render(working, axiom)));
                if (injected.isEmpty() ? inJustification : wasInjected) {
                    outcome.removedFault = true;
                } else if (injected.isEmpty()) {
                    outsideJustification++;
                    // Kept on the outcome as counted, as an injected case's is: a call or the
                    // reasoner failing later returned 0 (1.150.0, review 1.149.0 bench-06).
                    outcome.collateral = outsideJustification;
                } else {
                    outcome.collateral++;
                }
                working.getOWLOntologyManager().removeAxiom(working, axiom);
                removedAxioms.add(axiom);
                removed++;
            }

            if (!broken(c, working, reasoner)) {
                outcome.solved = true;
                if (injected.isEmpty()) {
                    outcome.collateral = unneeded(c, working, reasoner, removedAxioms);
                }
                return outcome;
            }
            ContextItem problem = problemOf(c, working, reasoner, reasonerName);
            // Each justification the loop sends counts as one it was shown: for a problem the
            // ontology already had, a removal from round 2's counted as collateral, where the same
            // removal in round 1 is the fault (1.151.0, review 1.150.0 bench-01).
            for (String line : problem.getText().split("\n")) {
                if (line.startsWith("  ") && !line.trim().isEmpty()) {
                    justification.add(normal(line));
                }
            }
            Turn turn = new Turn(message, Collections.<String>emptyList(), "bench");
            turn.append(reply.getText());
            turn.finish(reply);
            history.add(turn);
            context = Collections.singletonList(new RenderedItem(problem.getTitle(),
                    problem.getText()));
            String question = "inconsistent".equals(c.getProblem()) ? Questions.whyInconsistent()
                    : Questions.whyUnsatisfiable(targetLabel(c, working));
            message = (removed == 0
                    ? "Nothing was removed: " + (refused.isEmpty() ? "the answer proposed no"
                            + " removal." : reasons(refused))
                            + (added == 0 ? "" : " The addition was applied, but an addition"
                                    + " alone cannot remove the problem.")
                    : "The reasoner still finds the problem after "
                            + (removed == 1 ? "that removal." : "those removals.")
                            // A removal refused beside one applied is said too, with its reason
                            // (1.150.0, review 1.149.0 bench-03).
                            + (refused.isEmpty() ? "" : " Not applied: " + reasons(refused)))
                    + " The new justification is in the context. " + question;
        }
        if (injected.isEmpty()) {
            outcome.collateral = outsideJustification;
        }
        return outcome;
    }

    /** How a refused block is named before its reason: its axiom, quoted, or what it would do. */
    private static String named(Proposal proposal) {
        String axiom = proposal.getAxiom();
        if (axiom != null && !axiom.trim().isEmpty()) {
            return "\"" + axiom.trim() + "\": ";
        }
        String described = proposal.describe();
        return described == null || described.trim().isEmpty() ? "" : described + ": ";
    }

    /**
     * The reasons, as one sentence: each ended with its own "." before, which "; " and a last
     * "." doubled (1.149.0, review 1.148.0 bench-02).
     */
    private static String reasons(List<String> refused) {
        List<String> trimmed = new ArrayList<String>();
        for (String reason : refused) {
            String text = reason.trim();
            while (text.endsWith(".")) {
                text = text.substring(0, text.length() - 1).trim();
            }
            trimmed.add(text);
        }
        return String.join("; ", trimmed) + ".";
    }

    /**
     * For a problem the ontology already had, the removals that were not needed (review I3):
     * each removed axiom is put back in turn, and one whose return leaves the problem solved
     * stays back and counts. What is left removed is a set that was needed.
     */
    private static int unneeded(BenchCase c, OWLOntology working, OWLReasonerFactory factory,
            List<OWLAxiom> removed) {
        int count = 0;
        for (OWLAxiom axiom : removed) {
            working.getOWLOntologyManager().addAxiom(working, axiom);
            if (broken(c, working, factory)) {
                working.getOWLOntologyManager().removeAxiom(working, axiom);
            } else {
                count++;
            }
        }
        return count;
    }

    /** Whether the case's problem is still there: inconsistent, or the target unsatisfiable. */
    private static boolean broken(BenchCase c, OWLOntology working, OWLReasonerFactory factory) {
        OWLReasoner reasoner = factory.createReasoner(working);
        try {
            if (!reasoner.isConsistent()) {
                return true;
            }
            return !c.getTargetIri().isEmpty() && !reasoner.isSatisfiable(target(c, working));
        } finally {
            reasoner.dispose();
        }
    }

    private static int add(int total, int more) {
        if (more == Reply.UNKNOWN) {
            return total;
        }
        return total == Reply.UNKNOWN ? more : total + more;
    }

    /** The problem the case is about, as it stands in the working copy; null when it is gone. */
    private static ContextItem problemOf(BenchCase c, OWLOntology working,
            OWLReasonerFactory factory, String reasonerName) {
        OWLReasoner reasoner = factory.createReasoner(working);
        boolean consistent;
        boolean targetSatisfiable = true;
        try {
            consistent = reasoner.isConsistent();
            if (consistent && !c.getTargetIri().isEmpty()) {
                targetSatisfiable = reasoner.isSatisfiable(target(c, working));
            }
        } finally {
            reasoner.dispose();
        }
        if (!consistent) {
            return Problems.inconsistency(working, Problems.diagnose(working, factory),
                    reasonerName);
        }
        if (!targetSatisfiable) {
            return Problems.unsatisfiable(working, factory, target(c, working), reasonerName);
        }
        return null;
    }

    private static OWLClass target(BenchCase c, OWLOntology working) {
        return working.getOWLOntologyManager().getOWLDataFactory()
                .getOWLClass(IRI.create(c.getTargetIri()));
    }

    private static String targetLabel(BenchCase c, OWLOntology working) {
        return c.getTargetIri().isEmpty() ? "the class"
                : DisplayLabels.forEntity(working, target(c, working));
    }

    /** Every axiom line of every justification in the case's context, whitespace normalised. */
    private static Set<String> justificationLines(BenchCase c) {
        Set<String> lines = new HashSet<String>();
        for (RenderedItem item : c.getContext()) {
            for (String line : item.getText().split("\n")) {
                if (line.startsWith("  ") && !line.trim().isEmpty()) {
                    lines.add(normal(line));
                }
            }
        }
        return lines;
    }

    private static String normal(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    /** The ontology with the fault added, in a manager of its own. */
    static OWLOntology copy(OWLOntology base, Set<OWLAxiom> extra) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology copy = manager.createOntology(base.getOntologyID());
            manager.addAxioms(copy, base.getAxioms());
            manager.addAxioms(copy, extra);
            return copy;
        } catch (OWLOntologyCreationException cannotCopy) {
            throw new IllegalStateException("could not copy the ontology", cannotCopy);
        }
    }
}
