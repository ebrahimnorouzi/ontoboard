package de.fizkarlsruhe.ise.ontoboard.assistant.chat;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Reply;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One question and its answer, as it was asked: what context went with it and which connection
 * answered. A turn that failed or was stopped keeps what arrived, and is not resent as history.
 */
public final class Turn {

    private final String message;
    private final List<String> contextTitles;
    private final String connectionName;
    private final StringBuilder reply = new StringBuilder();
    private String error;
    private boolean finished;
    private Reply replyInfo;

    public Turn(String message, List<String> contextTitles, String connectionName) {
        this(message, contextTitles, connectionName, "");
    }

    /** @param model the kind and model that answer, "ollama qwen2.5:7b": what provenance names */
    public Turn(String message, List<String> contextTitles, String connectionName, String model) {
        this.message = message == null ? "" : message;
        this.contextTitles = Collections.unmodifiableList(new ArrayList<String>(contextTitles));
        this.connectionName = connectionName == null ? "" : connectionName;
        this.model = model == null ? "" : model;
        this.started = System.currentTimeMillis();
    }

    private final String model;
    private final long started;
    private List<Proposal> proposals = Collections.emptyList();

    /** "ollama qwen2.5:7b"; empty when not known. */
    public String getModel() {
        return model;
    }

    /** When it was asked, in milliseconds since the epoch. */
    public long getStarted() {
        return started;
    }

    /** The proposals of the answer, each in its current status. */
    public synchronized List<Proposal> getProposals() {
        return Collections.unmodifiableList(new ArrayList<Proposal>(proposals));
    }

    public synchronized void setProposals(List<Proposal> found) {
        proposals = new ArrayList<Proposal>(found);
    }

    /** The proposal with this id, or null. */
    public synchronized Proposal proposal(int id) {
        for (Proposal proposal : proposals) {
            if (proposal.getId() == id) {
                return proposal;
            }
        }
        return null;
    }

    /** Puts a proposal's new status in place of the old one. */
    public synchronized void replaceProposal(Proposal next) {
        for (int i = 0; i < proposals.size(); i++) {
            if (proposals.get(i).getId() == next.getId()) {
                proposals.set(i, next);
                return;
            }
        }
    }

    public synchronized void append(String delta) {
        if (delta != null) {
            reply.append(delta);
        }
    }

    /** The server's own account of the answer replaces what was streamed, if they differ. */
    public synchronized void finish(Reply info) {
        finished = true;
        replyInfo = info;
        if (info != null && !info.getText().isEmpty()) {
            reply.setLength(0);
            reply.append(info.getText());
        }
    }

    /** Ends the turn with what arrived and why it stopped. */
    public synchronized void fail(String why) {
        finished = true;
        error = why == null || why.trim().isEmpty() ? "Failed." : why;
    }

    public String getMessage() {
        return message;
    }

    public synchronized String getReply() {
        return reply.toString();
    }

    public List<String> getContextTitles() {
        return contextTitles;
    }

    public String getConnectionName() {
        return connectionName;
    }

    /** Why the turn ended without a whole answer, or null. */
    public synchronized String getError() {
        return error;
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    /** Tokens, time and stop reason, once finished without an error; otherwise null. */
    public synchronized Reply getReplyInfo() {
        return replyInfo;
    }
}
