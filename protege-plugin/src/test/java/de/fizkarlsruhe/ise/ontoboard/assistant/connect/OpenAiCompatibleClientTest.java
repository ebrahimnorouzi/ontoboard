package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** OpenAI's chat completions, and every server that speaks them at another base URL. */
class OpenAiCompatibleClientTest {

    private static final String SSE = "text/event-stream";

    private FakeModelServer server;
    private Connection connection;

    @BeforeEach
    void start() throws IOException {
        server = FakeModelServer.start();
        connection = Connection.of("LM Studio", ConnectionKind.OPENAI_COMPATIBLE)
                .withBaseUrl(server.url() + "/v1").withModel("qwen");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static String data(String json) {
        return "data: " + json + "\n\n";
    }

    private static String piece(String text) {
        return data("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text
                + "\"},\"finish_reason\":null}]}");
    }

    private static String finish() {
        return data("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}")
                + data("{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}")
                + "data: [DONE]\n\n";
    }

    private static ChatRequest request() {
        return new ChatRequest("You help.",
                Arrays.asList(Message.user("first"), Message.assistant("second")), 2048);
    }

    @Test
    void sendsTheChatCompletionsShape() throws Exception {
        server.respond("/v1/chat/completions", 200, SSE, piece("ok"), finish());

        new OpenAiCompatibleClient(connection, "sk-o").chat(request(), d -> { });

        JsonNode body = new ObjectMapper().readTree(server.lastBody());
        assertEquals("qwen", body.path("model").asText());
        assertEquals(2048, body.path("max_tokens").asInt());
        assertTrue(body.path("stream").asBoolean());
        assertTrue(body.path("stream_options").path("include_usage").asBoolean());
        JsonNode messages = body.path("messages");
        assertEquals(3, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("You help.", messages.get(0).path("content").asText());
        assertEquals("user", messages.get(1).path("role").asText());
        assertEquals("assistant", messages.get(2).path("role").asText());
        assertEquals("Bearer sk-o", server.lastHeaders().get("authorization"));
    }

    @Test
    void streamsTextInOrderAndTotalsIt() throws Exception {
        server.respond("/v1/chat/completions", 200, SSE,
                data("{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}"),
                piece("Every "), piece("pizza."), finish());
        List<String> heard = new ArrayList<String>();

        Reply reply = new OpenAiCompatibleClient(connection, "sk-o").chat(request(), heard::add);

        assertEquals(Arrays.asList("Every ", "pizza."), heard);
        assertEquals("Every pizza.", reply.getText());
        assertEquals(10, reply.getInputTokens());
        assertEquals(5, reply.getOutputTokens());
        assertEquals("stop", reply.getStopReason());
    }

    @Test
    void retriesOnceWithoutStreamOptions() throws Exception {
        server.respondOnce("/v1/chat/completions", 400, "application/json",
                "{\"error\":{\"message\":\"Unrecognized request argument: stream_options\"}}");
        server.respond("/v1/chat/completions", 200, SSE, piece("ok"), "data: [DONE]\n\n");

        Reply reply = new OpenAiCompatibleClient(connection, "sk-o").chat(request(), d -> { });

        assertEquals("ok", reply.getText());
        assertEquals(2, server.requestCount());
        assertFalse(server.lastBody().contains("stream_options"), server.lastBody());
    }

    /** Review #8: OpenAI's reasoning models take max_completion_tokens instead of max_tokens. */
    @Test
    void retriesWithMaxCompletionTokens() throws Exception {
        server.respondOnce("/v1/chat/completions", 400, "application/json",
                "{\"error\":{\"message\":\"Unsupported parameter: 'max_tokens' is not supported"
                        + " with this model. Use 'max_completion_tokens' instead.\"}}");
        server.respond("/v1/chat/completions", 200, SSE, piece("ok"), "data: [DONE]\n\n");

        Reply reply = new OpenAiCompatibleClient(connection, "sk-o").chat(request(), d -> { });

        assertEquals("ok", reply.getText());
        JsonNode body = new ObjectMapper().readTree(server.lastBody());
        assertEquals(2048, body.path("max_completion_tokens").asInt());
        assertTrue(body.path("max_tokens").isMissingNode(), server.lastBody());
    }

    @Test
    void noKeyMeansNoAuthorizationHeader() throws Exception {
        server.respond("/v1/chat/completions", 200, SSE, piece("ok"), finish());

        new OpenAiCompatibleClient(connection, "").chat(request(), d -> { });

        assertFalse(server.lastHeaders().containsKey("authorization"));
    }

    @Test
    void aServerThatIgnoresStreamingStillAnswers() throws Exception {
        server.respond("/v1/chat/completions", 200, "application/json",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"whole\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                        + "\"completion_tokens\":1}}");
        List<String> heard = new ArrayList<String>();

        Reply reply = new OpenAiCompatibleClient(connection, "").chat(request(), heard::add);

        assertEquals("whole", reply.getText());
        assertEquals(Arrays.asList("whole"), heard);
    }

    @Test
    void listsModels() throws Exception {
        server.respond("/v1/models", 200, "application/json",
                "{\"object\":\"list\",\"data\":[{\"id\":\"qwen\"},{\"id\":\"llama\"}]}");

        assertEquals(Arrays.asList("qwen", "llama"),
                new OpenAiCompatibleClient(connection, "sk-o").listModels());
    }

    /**
     * Gemini's OpenAI-compatible endpoint lists "models/gemini-2.5-flash"; its chat completions
     * are documented with "gemini-2.5-flash", so that is what the picker offers.
     */
    @Test
    void geminisModelPrefixIsDropped() throws Exception {
        server.respond("/v1/models", 200, "application/json", "{\"object\":\"list\",\"data\":["
                + "{\"id\":\"models/gemini-2.5-flash\",\"object\":\"model\"}]}");

        assertEquals(Arrays.asList("gemini-2.5-flash"),
                new OpenAiCompatibleClient(connection, "sk-g").listModels());
    }

    @Test
    void aRefusedKeyIsUnauthorized() {
        server.respond("/v1/chat/completions", 401, "application/json",
                "{\"error\":{\"message\":\"Incorrect API key provided: sk-o\"}}");

        ModelCallException failure = assertThrows(ModelCallException.class,
                () -> new OpenAiCompatibleClient(connection, "sk-o").chat(request(), d -> { }));

        assertEquals(ModelCallException.Kind.UNAUTHORIZED, failure.getKind());
        assertFalse(failure.getMessage().contains("sk-o"), failure.getMessage());
    }
}
