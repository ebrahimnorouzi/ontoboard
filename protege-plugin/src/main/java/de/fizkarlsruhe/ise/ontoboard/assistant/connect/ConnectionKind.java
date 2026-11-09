package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

/**
 * The three kinds of server the assistant can talk to (spec D1).
 *
 * <p>OpenAI-compatible covers OpenAI and anything that speaks its API at a base URL the person
 * sets. Ollama has a kind of its own because its native API accepts {@code options.num_ctx}; its
 * OpenAI-compatible route does not, and a prompt longer than the default context is then cut
 * without a word.
 */
public enum ConnectionKind {

    ANTHROPIC("anthropic", "Anthropic", "https://api.anthropic.com", "ANTHROPIC_API_KEY"),
    OPENAI_COMPATIBLE("openai-compatible", "OpenAI-compatible", "https://api.openai.com/v1",
            "OPENAI_API_KEY"),
    OLLAMA("ollama", "Ollama", "http://localhost:11434", "");

    private final String id;
    private final String label;
    private final String defaultBaseUrl;
    private final String defaultKeyVariable;

    ConnectionKind(String id, String label, String defaultBaseUrl, String defaultKeyVariable) {
        this.id = id;
        this.label = label;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultKeyVariable = defaultKeyVariable;
    }

    /** As written in saved settings and in a benchmark's models file. */
    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** The environment variable read for a key, or empty when the kind needs none. */
    public String getDefaultKeyVariable() {
        return defaultKeyVariable;
    }

    @Override
    public String toString() {
        return label;
    }

    /** @throws IllegalArgumentException naming the ids that exist */
    public static ConnectionKind byId(String id) {
        StringBuilder known = new StringBuilder();
        for (ConnectionKind kind : values()) {
            if (kind.id.equalsIgnoreCase(id == null ? "" : id.trim())) {
                return kind;
            }
            known.append(known.length() == 0 ? "" : ", ").append(kind.id);
        }
        throw new IllegalArgumentException("unknown kind of connection \"" + id
                + "\"; the kinds are " + known);
    }
}
