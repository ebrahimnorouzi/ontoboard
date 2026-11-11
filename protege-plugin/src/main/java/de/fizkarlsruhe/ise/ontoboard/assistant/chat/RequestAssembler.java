package de.fizkarlsruhe.ise.ontoboard.assistant.chat;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ChatRequest;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Message;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.Tokens;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One request, put together the same way for the plugin and the benchmark, so a score is what a
 * user would get.
 *
 * <p>The system text is the role, the fixed rules, the ontology's header, then one section per
 * context chip. The conversation so far is resent, as every chat API needs, but only finished
 * turns - a stopped half-answer would teach the model its own truncation - and only as much as
 * {@value #HISTORY_BUDGET_TOKENS} estimated tokens hold, newest first. What is left out is
 * counted, and "What is sent" says so.
 */
public final class RequestAssembler {

    public static final int HISTORY_BUDGET_TOKENS = 6000;

    private RequestAssembler() {
    }

    public static Assembled assemble(String roleText, String fixedRules, String header,
            List<RenderedItem> context, List<Turn> history, String message,
            int maxOutputTokens) {
        return assemble(roleText, fixedRules, header, context, history, message, maxOutputTokens,
                HISTORY_BUDGET_TOKENS);
    }

    /** With a smaller history budget, for a model whose context cannot hold the default. */
    public static Assembled assemble(String roleText, String fixedRules, String header,
            List<RenderedItem> context, List<Turn> history, String message,
            int maxOutputTokens, int historyBudgetTokens) {
        StringBuilder system = new StringBuilder();
        system.append(roleText == null ? "" : roleText.trim()).append("\n\n")
                .append(fixedRules == null ? "" : fixedRules.trim()).append("\n\n")
                .append("[ontology]\n").append(header == null ? "" : header.trim()).append('\n');
        for (RenderedItem item : context) {
            system.append("\n[context: ").append(item.getTitle()).append("]\n")
                    .append(item.getText().trim()).append('\n');
        }

        List<Turn> eligible = new ArrayList<Turn>();
        for (Turn turn : history) {
            if (turn.isFinished() && turn.getError() == null) {
                eligible.add(turn);
            }
        }
        List<Turn> kept = new ArrayList<Turn>();
        int spent = 0;
        for (int i = eligible.size() - 1; i >= 0; i--) {
            Turn turn = eligible.get(i);
            int cost = Tokens.estimate(turn.getMessage()) + Tokens.estimate(turn.getReply());
            if (spent + cost > historyBudgetTokens) {
                break;
            }
            spent += cost;
            kept.add(turn);
        }
        Collections.reverse(kept);

        List<Message> messages = new ArrayList<Message>();
        for (Turn turn : kept) {
            messages.add(Message.user(turn.getMessage()));
            messages.add(Message.assistant(turn.getReply()));
        }
        messages.add(Message.user(message == null ? "" : message));
        return new Assembled(new ChatRequest(system.toString(), messages, maxOutputTokens),
                eligible.size() - kept.size());
    }
}
