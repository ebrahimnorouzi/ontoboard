package de.fizkarlsruhe.ise.ontoboard.assistant.chat;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import java.util.List;

/**
 * "Save as Markdown": the conversation, with what each question was asked about and which model
 * answered. The only way a conversation reaches the disk - it is otherwise kept in memory and
 * forgotten when Protégé closes. Takes no key, so it cannot write one.
 */
public final class MarkdownExport {

    private MarkdownExport() {
    }

    public static String of(String ontologyTitle, Connection connection, String roleName,
            List<Turn> turns) {
        StringBuilder out = new StringBuilder();
        out.append("# Conversation about ").append(ontologyTitle).append("\n\n");
        if (connection != null) {
            out.append("Model: ").append(connection.getModel().isEmpty() ? "(none chosen)"
                    : connection.getModel()).append(", ").append(connection.getKind().getLabel())
                    .append(" at ").append(connection.getHost()).append("  \n");
        }
        out.append("Role: ").append(roleName == null ? "" : roleName).append("\n");
        for (Turn turn : turns) {
            out.append("\n## You\n\n").append(turn.getMessage().trim()).append("\n\n");
            if (!turn.getContextTitles().isEmpty()) {
                out.append("Context: ").append(String.join(", ", turn.getContextTitles()))
                        .append("\n\n");
            }
            out.append("## Assistant\n\n").append(turn.getReply().trim()).append('\n');
            if (turn.getError() != null) {
                out.append("\n_").append(turn.getError()).append("_\n");
            }
        }
        return out.toString();
    }
}
