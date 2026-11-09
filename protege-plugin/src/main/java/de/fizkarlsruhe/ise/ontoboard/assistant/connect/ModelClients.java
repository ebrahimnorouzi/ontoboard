package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

/** The client for a connection's kind. One per call: a stopped client stays stopped. */
public final class ModelClients {

    private ModelClients() {
    }

    public static ModelClient create(Connection connection, String apiKey) {
        switch (connection.getKind()) {
            case ANTHROPIC:
                return new AnthropicClient(connection, apiKey);
            case OPENAI_COMPATIBLE:
                return new OpenAiCompatibleClient(connection, apiKey);
            case OLLAMA:
                return new OllamaClient(connection, apiKey);
            default:
                throw new IllegalArgumentException("no client for " + connection.getKind());
        }
    }
}
