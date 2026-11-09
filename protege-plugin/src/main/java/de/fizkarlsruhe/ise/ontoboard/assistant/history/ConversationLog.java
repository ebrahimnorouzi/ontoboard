package de.fizkarlsruhe.ise.ontoboard.assistant.history;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Turn;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Reply;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheck;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * One conversation, saved in the project as Markdown (round-2 spec, "Chat history";
 * maintainer: "saved and annotated clearly, when, which models used").
 *
 * <ul>
 *   <li>A header: when it started, which OntoBoard, which ontology and version, which
 *       connection and model, which role.</li>
 *   <li>Each turn: when it was asked, the context sent, the model that answered with its time
 *       and tokens, the answer as it came, and its proposals with what the person did.</li>
 * </ul>
 *
 * <p>Each write is the whole conversation, so the file is always complete and a crash loses at
 * most the answer in progress. Every write goes through {@link KeyRedactor} first.
 */
public final class ConversationLog {

    /** What the header says. */
    public static final class Header {
        private final long started;
        private final String ontoBoardVersion;
        private final String ontologyIri;
        private final String ontologyVersionIri;
        private final String connectionName;
        private final String kind;
        private final String host;
        private final String model;
        private final String role;

        public Header(long started, String ontoBoardVersion, String ontologyIri,
                String ontologyVersionIri, String connectionName, String kind, String host,
                String model, String role) {
            this.started = started;
            this.ontoBoardVersion = ontoBoardVersion;
            this.ontologyIri = ontologyIri;
            this.ontologyVersionIri = ontologyVersionIri;
            this.connectionName = connectionName;
            this.kind = kind;
            this.host = host;
            this.model = model;
            this.role = role;
        }
    }

    private final File file;
    private final Header header;

    private ConversationLog(File file, Header header) {
        this.file = file;
        this.header = header;
    }

    /**
     * A log in {@code directory} named {@code stem.md}, or {@code stem-2.md} and so on when that
     * name is taken: two conversations started in the same second are two files.
     */
    public static ConversationLog start(File directory, String stem, Header header) {
        File file = new File(directory, stem + ".md");
        for (int n = 2; file.exists(); n++) {
            file = new File(directory, stem + "-" + n + ".md");
        }
        return new ConversationLog(file, header);
    }

    /** "2026-11-06-143012-ollama": the start, in local time, and the connection's name. */
    public static String stem(long started, String connectionName) {
        String name = connectionName == null ? "" : connectionName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        String when = new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT)
                .format(new Date(started));
        return name.isEmpty() ? when : when + "-" + name;
    }

    public File getFile() {
        return file;
    }

    /** Writes the whole conversation, keys redacted, replacing what the file held. */
    public void write(List<Turn> turns, Collection<String> keys,
            Function<Proposal, ProposalCheck.Check> checks) throws IOException {
        File directory = file.getAbsoluteFile().getParentFile();
        Files.createDirectories(directory.toPath());
        String text = KeyRedactor.redact(render(turns, checks), keys);
        File partial = new File(directory, file.getName() + ".part");
        Files.write(partial.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    String render(List<Turn> turns, Function<Proposal, ProposalCheck.Check> checks) {
        StringBuilder out = new StringBuilder();
        out.append("# OntoBoard assistant conversation\n\n");
        out.append("| | |\n|---|---|\n");
        row(out, "Started", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
                .format(new Date(header.started)));
        row(out, "OntoBoard", header.ontoBoardVersion);
        row(out, "Ontology", header.ontologyIri);
        row(out, "Ontology version", header.ontologyVersionIri);
        row(out, "Connection", header.connectionName + " (" + header.kind + ") at "
                + header.host);
        row(out, "Model", header.model);
        row(out, "Role", header.role);
        out.append("\nWritten by OntoBoard after each answer. Keys are never written: a key"
                + " OntoBoard knows reads \"(your key)\", and anything shaped like one \"(a"
                + " key)\".\n");
        int number = 0;
        for (Turn turn : turns) {
            number++;
            out.append("\n## ").append(number).append(" · You · ")
                    .append(new SimpleDateFormat("HH:mm:ss", Locale.ROOT)
                            .format(new Date(turn.getStarted())))
                    .append("\n\n").append(turn.getMessage().trim()).append("\n\n");
            if (!turn.getContextTitles().isEmpty()) {
                out.append("Context: ").append(String.join(", ", turn.getContextTitles()))
                        .append("\n\n");
            }
            out.append("### Assistant · ").append(answeredBy(turn)).append("\n\n");
            out.append(turn.getReply().trim()).append('\n');
            if (turn.getError() != null) {
                out.append("\n_Ended early: ").append(turn.getError()).append("_\n");
            }
            if (!turn.getProposals().isEmpty()) {
                out.append("\n**Proposals**\n\n");
                for (Proposal proposal : turn.getProposals()) {
                    proposal(out, proposal, checks.apply(proposal));
                }
            }
        }
        return out.toString();
    }

    private static void row(StringBuilder out, String name, String value) {
        String shown = value == null || value.trim().isEmpty() ? "(none)"
                : value.replace("|", "\\|").replace('\n', ' ');
        out.append("| ").append(name).append(" | ").append(shown).append(" |\n");
    }

    /** "qwen2.5:7b (ollama) · 4.2 s · 1,840 → 96 tokens". */
    private static String answeredBy(Turn turn) {
        String model = turn.getModel();
        int space = model.indexOf(' ');
        StringBuilder out = new StringBuilder();
        if (space > 0) {
            out.append(model.substring(space + 1)).append(" (").append(model, 0, space)
                    .append(')');
        } else {
            out.append(model.isEmpty() ? turn.getConnectionName() : model);
        }
        Reply info = turn.getReplyInfo();
        if (info != null) {
            if (info.getMillis() > 0) {
                out.append(String.format(Locale.ROOT, " · %.1f s", info.getMillis() / 1000.0));
            }
            if (info.getInputTokens() != Reply.UNKNOWN
                    && info.getOutputTokens() != Reply.UNKNOWN) {
                out.append(String.format(Locale.ROOT, " · %,d → %,d tokens",
                        info.getInputTokens(), info.getOutputTokens()));
            }
        }
        return out.toString();
    }

    private static void proposal(StringBuilder out, Proposal proposal,
            ProposalCheck.Check check) {
        out.append("- ").append(proposal.describe()).append(": ").append(done(proposal))
                .append('\n');
        if (proposal.getAction() != null && proposal.getAction().isLogic()) {
            // As written, under the ontology's names (review 1.139.0 I1).
            if (proposal.getAction() == Proposal.Action.REMOVE_AXIOM) {
                out.append("  - axiom: ").append(nonNull(proposal.getShownAxiom())).append('\n');
            } else if (proposal.getAction() == Proposal.Action.ADD_AXIOM) {
                out.append("  - adds: ").append(nonNull(proposal.getShownAxiom())).append('\n');
            } else {
                out.append("  - removes: ").append(nonNull(proposal.getShownAxiom()))
                        .append('\n');
                out.append("  - adds: ").append(nonNull(proposal.getShownWith())).append('\n');
            }
            for (String note : proposal.getNotes()) {
                out.append("  - note: ").append(note).append('\n');
            }
            if (check != null) {
                out.append("  - reasoner: ").append(check.describe()).append('\n');
            }
        } else {
            for (String was : proposal.getReplaced()) {
                out.append("  - was: ").append(was).append('\n');
            }
            out.append("  - now: ").append(proposal.getValueText()).append('\n');
        }
        if (proposal.getReason() != null && !proposal.getReason().trim().isEmpty()) {
            out.append("  - why: ").append(proposal.getReason().trim()).append('\n');
        }
    }

    private static String done(Proposal proposal) {
        switch (proposal.getStatus()) {
            case ACCEPTED:
                return "accepted";
            case REJECTED:
                return "rejected";
            case REVISED:
                return "revision asked";
            case INVALID:
                return "cannot be applied (" + nonNull(proposal.getProblem()) + ")";
            default:
                return "not decided";
        }
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }
}
