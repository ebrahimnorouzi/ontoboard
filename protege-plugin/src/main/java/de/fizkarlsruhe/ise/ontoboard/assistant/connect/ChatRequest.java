package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything one call sends: the system text (role, fixed rules, context), the conversation, and
 * how long the answer may be. Exactly what "What is sent" shows, because that view is rendered
 * from the same object ({@code chat/Assembled}).
 */
public final class ChatRequest {

    private final String system;
    private final List<Message> messages;
    private final int maxOutputTokens;

    public ChatRequest(String system, List<Message> messages, int maxOutputTokens) {
        this.system = system == null ? "" : system;
        this.messages = Collections.unmodifiableList(new ArrayList<Message>(messages));
        this.maxOutputTokens = maxOutputTokens;
    }

    public String getSystem() {
        return system;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }
}
