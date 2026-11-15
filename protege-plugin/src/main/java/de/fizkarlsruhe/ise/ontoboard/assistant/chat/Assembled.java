package de.fizkarlsruhe.ise.ontoboard.assistant.chat;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ChatRequest;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Message;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.Tokens;
import java.util.Locale;

/**
 * A request ready to send, and the text "What is sent" shows for it (spec D3): rendered from the
 * very object that is sent, so the view cannot drift from the request.
 *
 * <p>Never the key. The sign-in line says which header carries one, with {@code (your key)} in
 * its place.
 */
public final class Assembled {

    private final ChatRequest request;
    private final int droppedTurns;

    Assembled(ChatRequest request, int droppedTurns) {
        this.request = request;
        this.droppedTurns = droppedTurns;
    }

    public ChatRequest getRequest() {
        return request;
    }

    /** Earlier finished turns left out to stay within the history budget. */
    public int getDroppedTurns() {
        return droppedTurns;
    }

    /** About a quarter of the characters of everything sent. */
    public int getEstimatedTokens() {
        int tokens = Tokens.estimate(request.getSystem());
        for (Message message : request.getMessages()) {
            tokens += Tokens.estimate(message.getText());
        }
        return tokens;
    }

    /** Exactly what goes to whom: the destination, the model, the sign-in, the size, the text. */
    public String describe(Connection connection) {
        StringBuilder out = new StringBuilder();
        out.append("To: ").append(connection.getHost()).append(" (")
                .append(connection.describeWhere()).append(")\n");
        out.append("Model: ").append(connection.getModel().isEmpty() ? "(none chosen)"
                : connection.getModel()).append('\n');
        out.append("Sign-in: ").append(signIn(connection)).append('\n');
        out.append(String.format(Locale.ROOT, "About ~%,d tokens%n", getEstimatedTokens())
                .replace("\r\n", "\n"));
        if (droppedTurns > 0) {
            out.append(droppedTurns).append(" earlier turns left out to stay within the budget.\n");
        }
        out.append('\n').append(request.getSystem());
        for (Message message : request.getMessages()) {
            out.append("\n[").append(message.getRole() == Message.Role.USER ? "you" : "assistant")
                    .append("]\n").append(message.getText()).append('\n');
        }
        return out.toString();
    }

    private static String signIn(Connection connection) {
        switch (connection.getKind()) {
            case ANTHROPIC:
                return "x-api-key: (your key)";
            case OPENAI_COMPATIBLE:
                return "Authorization: Bearer (your key)";
            default:
                return "none";
        }
    }
}
