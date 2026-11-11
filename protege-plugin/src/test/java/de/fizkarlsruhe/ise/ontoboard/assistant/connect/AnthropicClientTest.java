package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Anthropic's Messages API, streamed, over plain HTTPS with no SDK. */
class AnthropicClientTest {

    private static final String SSE = "text/event-stream";

    private FakeModelServer server;
    private Connection connection;

    @BeforeEach
    void start() throws IOException {
        server = FakeModelServer.start();
        connection = Connection.of("Claude", ConnectionKind.ANTHROPIC).withBaseUrl(server.url())
                .withModel("claude-x");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static String event(String name, String json) {
        return "event: " + name + "\ndata: " + json + "\n\n";
    }

    private static String delta(String text) {
        return event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}");
    }

    private static String start(int inputTokens) {
        return event("message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":"
                + "{\"input_tokens\":" + inputTokens + ",\"output_tokens\":1}}}");
    }

    private static String end(int outputTokens) {
        return event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":"
                + "\"end_turn\"},\"usage\":{\"output_tokens\":" + outputTokens + "}}")
                + event("message_stop", "{\"type\":\"message_stop\"}");
    }

    private static ChatRequest request() {
        return new ChatRequest("You help.",
                Arrays.asList(Message.user("first"), Message.assistant("second")), 2048);
    }

    @Test
    void sendsTheMessagesApiShape() throws Exception {
        server.respond("/v1/messages", 200, SSE, start(3), delta("ok"), end(1));

        new AnthropicClient(connection, "sk-test").chat(request(), delta -> { });

        JsonNode body = new ObjectMapper().readTree(server.lastBody());
        assertEquals("claude-x", body.path("model").asText());
        assertEquals(2048, body.path("max_tokens").asInt());
        assertEquals("You help.", body.path("system").asText());
        assertTrue(body.path("stream").asBoolean());
        assertEquals(2, body.path("messages").size());
        assertEquals("user", body.path("messages").get(0).path("role").asText());
        assertEquals("first", body.path("messages").get(0).path("content").asText());
        assertEquals("assistant", body.path("messages").get(1).path("role").asText());
        assertEquals("sk-test", server.lastHeaders().get("x-api-key"));
        assertEquals("2023-06-01", server.lastHeaders().get("anthropic-version"));
        assertTrue(server.lastHeaders().get("content-type").startsWith("application/json"));
    }

    @Test
    void streamsTextInOrderAndTotalsIt() throws Exception {
        server.respond("/v1/messages", 200, SSE, start(12), event("ping", "{\"type\":\"ping\"}"),
                delta("Every "), delta("pizza "), delta("has a base."), end(7));
        List<String> heard = new ArrayList<String>();

        Reply reply = new AnthropicClient(connection, "sk-test").chat(request(), heard::add);

        assertEquals(Arrays.asList("Every ", "pizza ", "has a base."), heard);
        assertEquals("Every pizza has a base.", reply.getText());
        assertEquals(12, reply.getInputTokens());
        assertEquals(7, reply.getOutputTokens());
        assertEquals("end_turn", reply.getStopReason());
    }

    @Test
    void utf8SplitAcrossChunksSurvives() throws Exception {
        String text = "Größe → 大";
        byte[] whole = (start(1) + delta(text) + end(1)).getBytes(StandardCharsets.UTF_8);
        byte[] umlaut = "ö".getBytes(StandardCharsets.UTF_8);
        int split = indexOf(whole, umlaut) + 1;
        server.respondBytes("/v1/messages", 200, SSE, Arrays.copyOfRange(whole, 0, split),
                Arrays.copyOfRange(whole, split, whole.length));

        Reply reply = new AnthropicClient(connection, "sk-test").chat(request(), delta -> { });

        assertEquals(text, reply.getText());
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(haystack, i, i + needle.length), needle)) {
                return i;
            }
        }
        throw new AssertionError("not found");
    }

    @Test
    void anErrorEventIsAServerError() {
        server.respond("/v1/messages", 200, SSE, start(1), delta("Half"),
                event("error", "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\","
                        + "\"message\":\"Overloaded\"}}"));

        ModelCallException failure = assertThrows(ModelCallException.class,
                () -> new AnthropicClient(connection, "sk-test").chat(request(), d -> { }));

        assertEquals(ModelCallException.Kind.SERVER, failure.getKind());
        assertTrue(failure.getMessage().contains("Overloaded"), failure.getMessage());
    }

    @Test
    void listsModels() throws Exception {
        server.respond("/v1/models", 200, "application/json",
                "{\"data\":[{\"id\":\"claude-a\",\"type\":\"model\"},{\"id\":\"claude-b\"}],"
                        + "\"has_more\":false}");

        assertEquals(Arrays.asList("claude-a", "claude-b"),
                new AnthropicClient(connection, "sk-test").listModels());
        assertEquals("sk-test", server.lastHeaders().get("x-api-key"));
    }

    /**
     * Review #3: the JDK refuses a header value with a line break by throwing an
     * IllegalArgumentException that quotes the whole value - the key. The client cleans the key,
     * so the request is made and nothing quotes it.
     */
    @Test
    void aKeyWithALineBreakIsCleanedBeforeItReachesAHeader() throws Exception {
        server.respond("/v1/messages", 200, SSE, start(1), delta("ok"), end(1));

        Reply reply = new AnthropicClient(connection, "sk-line\nbreak").chat(request(), d -> { });

        assertEquals("ok", reply.getText());
        assertEquals("sk-linebreak", server.lastHeaders().get("x-api-key"));
    }

    @Test
    void aRefusedKeyIsUnauthorized() {
        server.respond("/v1/messages", 401, "application/json",
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                        + "\"message\":\"invalid x-api-key sk-test\"}}");

        ModelCallException failure = assertThrows(ModelCallException.class,
                () -> new AnthropicClient(connection, "sk-test").chat(request(), d -> { }));

        assertEquals(ModelCallException.Kind.UNAUTHORIZED, failure.getKind());
        assertFalse(failure.getMessage().contains("sk-test"), failure.getMessage());
    }
}
