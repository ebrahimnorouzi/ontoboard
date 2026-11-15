package de.fizkarlsruhe.ise.ontoboard.assistant.session;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.NameResolver;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Turn;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextItem;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheck;
import java.util.List;

/** What the column hears from a session. Called on the UI executor. */
public interface SessionListener {

    default void onTurnStarted(Turn turn) {
    }

    default void onText(Turn turn, String delta) {
    }

    default void onTurnFinished(Turn turn) {
    }

    default void onContextChanged(List<ContextItem> context) {
    }

    /** The model asked for terms: each found, not found, or ambiguous. */
    default void onNeeds(List<NameResolver.Resolution> resolutions) {
    }

    /** Something the person should read: a refusal, or why a call failed. */
    default void onNotice(String message) {
    }

    /**
     * The answer's proposals, validated, after {@link #onTurnFinished}; the turn already holds
     * them then, so a listener that draws the turn draws its cards.
     */
    default void onProposals(Turn turn, List<Proposal> proposals) {
    }

    /** A proposal was accepted, rejected, revised, or found invalid on accepting. */
    default void onProposalChanged(Proposal proposal) {
    }

    /**
     * What is happening now, in words: "Sending ~1,200 tokens to localhost…", "Waiting for
     * qwen2.5:7b…", "Writing… 40 words", "Checking 1 proposal with HermiT…", "Done in 3.2 s",
     * "Stopped." or "Failed: …".
     *
     * @param working whether something is still under way
     */
    default void onStatus(String text, boolean working) {
    }

    /** What the reasoner says about a removal, checked on a copy in the background. */
    default void onProposalChecked(Proposal proposal, ProposalCheck.Check check) {
    }
}
