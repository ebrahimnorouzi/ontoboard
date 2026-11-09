package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

/** One turn of a conversation as a model API sees it. The system text travels separately. */
public final class Message {

    /** Who said it. */
    public enum Role {
        USER,
        ASSISTANT
    }

    private final Role role;
    private final String text;

    private Message(Role role, String text) {
        this.role = role;
        this.text = text == null ? "" : text;
    }

    public static Message user(String text) {
        return new Message(Role.USER, text);
    }

    public static Message assistant(String text) {
        return new Message(Role.ASSISTANT, text);
    }

    public Role getRole() {
        return role;
    }

    public String getText() {
        return text;
    }

    /** {@code "user"} or {@code "assistant"}, as all three APIs spell it. */
    public String getApiRole() {
        return role == Role.USER ? "user" : "assistant";
    }
}
