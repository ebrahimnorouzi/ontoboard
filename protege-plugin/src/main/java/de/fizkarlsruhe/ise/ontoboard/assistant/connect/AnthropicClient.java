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
 * Anthropic's Messages API: {@code POST /v1/messages} with {@code stream: true}, answered in
 * server-sent events.
 *
 * <p>The events read: {@code message_start} (input tokens), {@code content_block_delta} with a
 * {@code text_delta} (the answer as it is written), {@code message_delta} (output tokens and the
 * stop reason), {@code message_stop}, and {@code error}, which a stream can carry after it has
 * started - "Overloaded" arrives that way. {@code ping} and anything new are ignored, so a
 * server that adds an event type does not break a call.
 */
public final class AnthropicClient extends AbstractModelClient {

    static final String API_VERSION = "2023-06-01";

    public AnthropicClient(Connection connection, String apiKey) {
        super(connection, apiKey);
    }

    private HttpRequest signed(HttpRequest request) {
        return request.header("x-api-key", apiKey).header("anthropic-version", API_VERSION);
    }

    @Override
    public List<String> listModels() throws ModelCallException {
        JsonNode root = readJson(send(signed(get("/v1/models?limit=1000"))));
        List<String> models = new ArrayList<String>();
        for (JsonNode model : root.path("data")) {
            String id = model.path("id").asText("");
            if (!id.isEmpty()) {
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
        if (!request.getSystem().isEmpty()) {
            body.put("system", request.getSystem());
        }
        ArrayNode messages = body.putArray("messages");
        for (Message message : request.getMessages()) {
            messages.addObject().put("role", message.getApiRole())
                    .put("content", message.getText());
        }
        body.put("stream", true);

        long started = System.nanoTime();
        HttpsOpener.Answer answer = send(signed(post("/v1/messages", body)));
        StringBuilder text = new StringBuilder();
        int inputTokens = Reply.UNKNOWN;
        int outputTokens = Reply.UNKNOWN;
        String stopReason = "";
        try {
            SseReader events = new SseReader(lines(answer.getBody()));
            SseReader.Event event;
            while ((event = events.next()) != null) {
                JsonNode data = parse(event.getData());
                if (data == null) {
                    continue;
                }
                String type = "message".equals(event.getEvent())
                        ? data.path("type").asText("") : event.getEvent();
                if ("message_start".equals(type)) {
                    inputTokens = data.path("message").path("usage").path("input_tokens")
                            .asInt(Reply.UNKNOWN);
                } else if ("content_block_delta".equals(type)) {
                    JsonNode delta = data.path("delta");
                    if ("text_delta".equals(delta.path("type").asText(""))) {
                        String piece = delta.path("text").asText("");
                        text.append(piece);
                        listener.onText(piece);
                    }
                } else if ("message_delta".equals(type)) {
                    outputTokens = data.path("usage").path("output_tokens").asInt(outputTokens);
                    stopReason = data.path("delta").path("stop_reason").asText(stopReason);
                } else if ("message_stop".equals(type)) {
                    break;
                } else if ("error".equals(type)) {
                    throw new ModelCallException(ModelCallException.Kind.SERVER,
                            connection.getHost() + " reported an error while answering: "
                                    + ModelCalls.serverMessage(event.getData()) + ".");
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
