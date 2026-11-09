package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.fizkarlsruhe.ise.ontoboard.tools.HttpRequest;
import de.fizkarlsruhe.ise.ontoboard.tools.HttpsOpener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ollama's own chat API: {@code POST /api/chat}, answered in newline-delimited JSON.
 *
 * <p>Its own API rather than its OpenAI-compatible one because this one takes
 * {@code options.num_ctx}. Without it Ollama uses its default context, and a prompt longer than
 * that is cut from the front without an error - the role and the fixed rules are what go first.
 * {@code num_predict} caps the answer the way {@code max_tokens} does elsewhere.
 */
public final class OllamaClient extends AbstractModelClient {

    public OllamaClient(Connection connection, String apiKey) {
        super(connection, apiKey);
    }

    /** Ollama needs no key; one is sent only for a server behind a proxy that asks for it. */
    private HttpRequest signed(HttpRequest request) {
        return apiKey.isEmpty() ? request : request.header("Authorization", "Bearer " + apiKey);
    }

    @Override
    public List<String> listModels() throws ModelCallException {
        JsonNode root = readJson(send(signed(get("/api/tags"))));
        List<String> models = new ArrayList<String>();
        for (JsonNode model : root.path("models")) {
            String name = model.path("name").asText("");
            if (!name.isEmpty()) {
                models.add(name);
            }
        }
        return models;
    }

    @Override
    public Reply chat(ChatRequest request, StreamListener listener) throws ModelCallException {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", connection.getModel());
        ArrayNode messages = body.putArray("messages");
        if (!request.getSystem().isEmpty()) {
            messages.addObject().put("role", "system").put("content", request.getSystem());
        }
        for (Message message : request.getMessages()) {
            messages.addObject().put("role", message.getApiRole())
                    .put("content", message.getText());
        }
        body.put("stream", true);
        ObjectNode options = body.putObject("options");
        options.put("num_ctx", connection.getContextLength());
        options.put("num_predict", request.getMaxOutputTokens());

        long started = System.nanoTime();
        HttpsOpener.Answer answer = send(signed(post("/api/chat", body)));
        StringBuilder text = new StringBuilder();
        int inputTokens = Reply.UNKNOWN;
        int outputTokens = Reply.UNKNOWN;
        String stopReason = "";
        try {
            SseReader.Lines reader = lines(answer.getBody());
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode data = parse(line);
                if (data == null) {
                    continue;
                }
                if (data.has("error")) {
                    throw new ModelCallException(ModelCallException.Kind.SERVER,
                            connection.getHost() + " reported an error while answering: "
                                    + ModelCalls.serverMessage(line) + ".");
                }
                String piece = data.path("message").path("content").asText("");
                if (!piece.isEmpty()) {
                    text.append(piece);
                    listener.onText(piece);
                }
                if (data.path("done").asBoolean(false)) {
                    inputTokens = data.path("prompt_eval_count").asInt(Reply.UNKNOWN);
                    outputTokens = data.path("eval_count").asInt(Reply.UNKNOWN);
                    stopReason = data.path("done_reason").asText("");
                    break;
                }
            }
        } catch (IOException failure) {
            throw failed(failure);
        }
        endOfStream();
        return new Reply(text.toString(), inputTokens, outputTokens, stopReason,
                millisSince(started));
    }
}
