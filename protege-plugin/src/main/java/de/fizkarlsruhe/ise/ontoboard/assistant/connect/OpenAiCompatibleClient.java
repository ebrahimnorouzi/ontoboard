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
 * OpenAI's chat completions, which OpenAI and many other servers speak at a base URL the person
 * sets: OpenRouter, Mistral, LM Studio, vLLM.
 *
 * <p>Two allowances for "compatible" meaning "mostly": {@code stream_options} (which is how the
 * token counts arrive in a stream) is dropped and the call retried once when a server rejects
 * it, and a server that ignores {@code stream} and answers with one JSON body is read as such
 * rather than reported as broken.
 */
public final class OpenAiCompatibleClient extends AbstractModelClient {

    public OpenAiCompatibleClient(Connection connection, String apiKey) {
        super(connection, apiKey);
    }

    /** A local server needs no key, and an empty Bearer header makes some of them refuse. */
    private HttpRequest signed(HttpRequest request) {
        return apiKey.isEmpty() ? request : request.header("Authorization", "Bearer " + apiKey);
    }

    @Override
    public List<String> listModels() throws ModelCallException {
        JsonNode root = readJson(send(signed(get("/models"))));
        List<String> models = new ArrayList<String>();
        for (JsonNode model : root.path("data")) {
            String id = model.path("id").asText("");
            // Gemini lists "models/gemini-2.5-flash" and documents its chat with the bare name.
            if (id.startsWith("models/")) {
                id = id.substring("models/".length());
            }
            if (!id.isEmpty() && !models.contains(id)) {
                models.add(id);
            }
        }
        return models;
    }

    @Override
    public Reply chat(ChatRequest request, StreamListener listener) throws ModelCallException {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", connection.getModel());
        body.put("max_tokens", request.getMaxOutputTokens());
        ArrayNode messages = body.putArray("messages");
        if (!request.getSystem().isEmpty()) {
            messages.addObject().put("role", "system").put("content", request.getSystem());
        }
        for (Message message : request.getMessages()) {
            messages.addObject().put("role", message.getApiRole())
                    .put("content", message.getText());
        }
        body.put("stream", true);
        body.putObject("stream_options").put("include_usage", true);
        // Each allowance is tried once: a server that rejects stream_options, and OpenAI's
        // reasoning models, which reject max_tokens and take max_completion_tokens instead.
        for (int attempt = 0; ; attempt++) {
            try {
                return complete(body, listener);
            } catch (ModelCallException refused) {
                if (attempt >= 2 || refused.getKind() != ModelCallException.Kind.SERVER) {
                    throw refused;
                }
                String said = refused.getMessage();
                if (said.contains("stream_options") && body.has("stream_options")) {
                    body.remove("stream_options");
                } else if (said.contains("max_completion_tokens") && body.has("max_tokens")) {
                    body.put("max_completion_tokens", body.get("max_tokens").asInt());
                    body.remove("max_tokens");
                } else {
                    throw refused;
                }
            }
        }
    }

    private Reply complete(ObjectNode body, StreamListener listener) throws ModelCallException {
        long started = System.nanoTime();
        HttpsOpener.Answer answer = send(signed(post("/chat/completions", body)));
        if (answer.getContentType().contains("application/json")) {
            return whole(readJson(answer), listener, started);
        }
        StringBuilder text = new StringBuilder();
        int inputTokens = Reply.UNKNOWN;
        int outputTokens = Reply.UNKNOWN;
        String stopReason = "";
        try {
            SseReader events = new SseReader(lines(answer.getBody()));
            SseReader.Event event;
            while ((event = events.next()) != null) {
                if ("[DONE]".equals(event.getData().trim())) {
                    break;
                }
                JsonNode data = parse(event.getData());
                if (data == null) {
                    continue;
                }
                if (data.has("error")) {
                    throw new ModelCallException(ModelCallException.Kind.SERVER,
                            connection.getHost() + " reported an error while answering: "
                                    + ModelCalls.serverMessage(event.getData()) + ".");
                }
                JsonNode choices = data.path("choices");
                if (choices.size() > 0) {
                    JsonNode choice = choices.get(0);
                    String piece = choice.path("delta").path("content").asText("");
                    if (!piece.isEmpty()) {
                        text.append(piece);
                        listener.onText(piece);
                    }
                    stopReason = choice.path("finish_reason").asText(stopReason);
                }
                JsonNode usage = data.path("usage");
                if (usage.isObject()) {
                    inputTokens = usage.path("prompt_tokens").asInt(inputTokens);
                    outputTokens = usage.path("completion_tokens").asInt(outputTokens);
                }
            }
        } catch (IOException failure) {
            throw failed(failure);
        }
        endOfStream();
        return new Reply(text.toString(), inputTokens, outputTokens, stopReason,
                millisSince(started));
    }

    /** A server that answered in one body: the same reply, delivered to the listener at once. */
    private static Reply whole(JsonNode root, StreamListener listener, long started) {
        JsonNode choice = root.path("choices").path(0);
        String text = choice.path("message").path("content").asText("");
        if (!text.isEmpty()) {
            listener.onText(text);
        }
        return new Reply(text, root.path("usage").path("prompt_tokens").asInt(Reply.UNKNOWN),
                root.path("usage").path("completion_tokens").asInt(Reply.UNKNOWN),
                choice.path("finish_reason").asText(""), millisSince(started));
    }
}
