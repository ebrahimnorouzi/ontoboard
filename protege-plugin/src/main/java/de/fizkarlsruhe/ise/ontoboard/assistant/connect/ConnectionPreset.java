package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

/**
 * The servers people set up most, so a connection is one choice and a model away (round-2 spec,
 * "Connections"). Each fills the kind, the address and the environment variable a key is read
 * from; the person can change any of them afterwards.
 *
 * <p>Gemini is reached through Google's OpenAI-compatible endpoint, which takes a Bearer key and
 * lists models at {@code /models} (checked against Google's documentation, 2026-11-06).
 */
public enum ConnectionPreset {

    ANTHROPIC("Anthropic", ConnectionKind.ANTHROPIC, "https://api.anthropic.com",
            "ANTHROPIC_API_KEY"),
    OPENAI("OpenAI", ConnectionKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1",
            "OPENAI_API_KEY"),
    GEMINI("Google Gemini", ConnectionKind.OPENAI_COMPATIBLE,
            "https://generativelanguage.googleapis.com/v1beta/openai", "GEMINI_API_KEY"),
    OPENROUTER("OpenRouter", ConnectionKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1",
            "OPENROUTER_API_KEY"),
    MISTRAL("Mistral", ConnectionKind.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1",
            "MISTRAL_API_KEY"),
    LM_STUDIO("LM Studio", ConnectionKind.OPENAI_COMPATIBLE, "http://localhost:1234/v1", ""),
    OLLAMA("Ollama", ConnectionKind.OLLAMA, "http://localhost:11434", "");

    private final String label;
    private final ConnectionKind kind;
    private final String baseUrl;
    private final String keyVariable;

    ConnectionPreset(String label, ConnectionKind kind, String baseUrl, String keyVariable) {
        this.label = label;
        this.kind = kind;
        this.baseUrl = baseUrl;
        this.keyVariable = keyVariable;
    }

    public String getLabel() {
        return label;
    }

    public ConnectionKind getKind() {
        return kind;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /** Empty for a server on this machine, which needs no key. */
    public String getKeyVariable() {
        return keyVariable;
    }

    /** The preset with this label, or null. */
    public static ConnectionPreset byLabel(String label) {
        for (ConnectionPreset preset : values()) {
            if (preset.label.equals(label)) {
                return preset;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return label;
    }
}
