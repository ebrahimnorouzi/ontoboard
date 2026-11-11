package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Ollama's own chat API: newline-delimited JSON, and a context length OntoBoard sets. */
class OllamaClientTest {

    private static final String NDJSON = "application/x-ndjson";

    private FakeModelServer server;
    private Connection connection;

    @BeforeEach
    void start() throws IOException {
        server = FakeModelServer.start();
        connection = Connection.of("Ollama", ConnectionKind.OLLAMA).withBaseUrl(server.url())
                .withModel("llama3.1");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static String line(String content) {
        return "{\"model\":\"llama3.1\",\"message\":{\"role\":\"assistant\",\"content\":\""
                + content + "\"},\"done\":false}\n";
    }

    private static String done() {
        return "{\"model\":\"llama3.1\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
                + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":20,"
                + "\"eval_count\":4}\n";
    }

    private static ChatRequest request() {
        return new ChatRequest("You help.",
                Arrays.asList(Message.user("first"), Message.assistant("second")), 2048);
    }

    @Test
    void sendsTheChatShape() throws Exception {
        server.respond("/api/chat", 200, NDJSON, line("ok"), done());

        new OllamaClient(connection, "").chat(request(), d -> { });

        JsonNode body = new ObjectMapper().readTree(server.lastBody());
        assertEquals("llama3.1", body.path("model").asText());
        assertTrue(body.path("stream").asBoolean());
        assertEquals(8192, body.path("options").path("num_ctx").asInt());
        assertEquals(2048, body.path("options").path("num_predict").asInt());
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("You help.", body.path("messages").get(0).path("content").asText());
        assertEquals(3, body.path("messages").size());
        assertFalse(server.lastHeaders().containsKey("authorization"));
    }

    @Test
    void streamsTextInOrderAndTotalsIt() throws Exception {
        server.respond("/api/chat", 200, NDJSON, line("Every "), line("pizza."), done());
        List<String> heard = new ArrayList<String>();

        Reply reply = new OllamaClient(connection, "").chat(request(), heard::add);

        assertEquals(Arrays.asList("Every ", "pizza."), heard);
        assertEquals("Every pizza.", reply.getText());
        assertEquals(20, reply.getInputTokens());
        assertEquals(4, reply.getOutputTokens());
        assertEquals("stop", reply.getStopReason());
    }

    @Test
    void anErrorLineIsAServerError() {
        server.respond("/api/chat", 200, NDJSON, line("Half"),
                "{\"error\":\"model requires more system memory\"}\n");

        ModelCallException failure = assertThrows(ModelCallException.class,
                () -> new OllamaClient(connection, "").chat(request(), d -> { }));

        assertEquals(ModelCallException.Kind.SERVER, failure.getKind());
        assertTrue(failure.getMessage().contains("more system memory"), failure.getMessage());
    }

    @Test
    void anUnknownModelSaysPickOne() {
        server.respond("/api/chat", 404, "application/json",
                "{\"error\":\"model \\\"llama3.1\\\" not found, try pulling it first\"}");

        ModelCallException failure = assertThrows(ModelCallException.class,
                () -> new OllamaClient(connection, "").chat(request(), d -> { }));

        assertEquals(ModelCallException.Kind.MODEL_NOT_FOUND, failure.getKind());
        assertTrue(failure.getMessage().contains("try pulling it first"), failure.getMessage());
    }

    @Test
    void listsModels() throws Exception {
        server.respond("/api/tags", 200, "application/json",
                "{\"models\":[{\"name\":\"llama3.1:latest\"},{\"name\":\"qwen2.5:7b\"}]}");

        assertEquals(Arrays.asList("llama3.1:latest", "qwen2.5:7b"),
                new OllamaClient(connection, "").listModels());
    }

    @Test
    void aKeyForAProxiedServerIsSentAndARefusalIsUnauthorized() {
        server.respond("/api/chat", 401, "text/plain", "unauthorized");

        ModelCallException failure = assertThrows(ModelCallException.class,
                () -> new OllamaClient(connection, "tok").chat(request(), d -> { }));

        assertEquals("Bearer tok", server.lastHeaders().get("authorization"));
        assertEquals(ModelCallException.Kind.UNAUTHORIZED, failure.getKind());
    }

    /** Review focus 1: a model that stalls mid-answer must let go when Stop is pressed. */
    @Test
    void stopDuringAStalledStreamReturnsWithinThreeSeconds() throws Exception {
        server.stall("/api/chat", NDJSON, line("Half an answer"));
        OllamaClient client = new OllamaClient(connection, "");
        List<String> heard = Collections.synchronizedList(new ArrayList<String>());
        ExecutorService running = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> answering = running.submit(() -> client.chat(request(), heard::add));
            Thread.sleep(500);
            long stopped = System.nanoTime();

            client.stop();

            ExecutionException failed = assertThrows(ExecutionException.class,
                    () -> answering.get(10, TimeUnit.SECONDS));
            long millis = (System.nanoTime() - stopped) / 1_000_000;
            assertTrue(failed.getCause() instanceof ModelCallException,
                    String.valueOf(failed.getCause()));
            assertEquals(ModelCallException.Kind.STOPPED,
                    ((ModelCallException) failed.getCause()).getKind());
            assertTrue(millis < 3_000, "it took " + millis + " ms to let go");
            assertEquals(Arrays.asList("Half an answer"), heard,
                    "the partial text reached the listener before Stop");
        } finally {
            running.shutdownNow();
        }
    }

    /** Review 1.143.0 M-4: the error, which a saved conversation records, names no password. */
    @Test
    void anUnreachableAddressIsNamedWithoutItsCredentials() throws IOException {
        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        Connection nobody = connection.withBaseUrl("http://ontouser:s3cretPassw0rd@127.0.0.1:"
                + closed);

        ModelCallException failure = assertThrows(ModelCallException.class,
                () -> new OllamaClient(nobody, "").chat(request(), d -> { }));

        assertFalse(failure.getMessage().contains("s3cretPassw0rd"), failure.getMessage());
        assertFalse(failure.getMessage().contains("ontouser"), failure.getMessage());
        assertTrue(failure.getMessage().contains("127.0.0.1:" + closed), failure.getMessage());
    }

    @Test
    void unreachableSaysOllamaServe() throws IOException {
        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        Connection nobody = connection.withBaseUrl("http://127.0.0.1:" + closed);

        ModelCallException failure = assertThrows(ModelCallException.class,
                () -> new OllamaClient(nobody, "").chat(request(), d -> { }));

        assertEquals(ModelCallException.Kind.UNREACHABLE, failure.getKind());
        assertTrue(failure.getMessage().contains("ollama serve"), failure.getMessage());
    }

    @Test
    void theFactoryPicksTheClientByKind() {
        assertTrue(ModelClients.create(connection, "") instanceof OllamaClient);
        assertTrue(ModelClients.create(Connection.of("a", ConnectionKind.ANTHROPIC), "k")
                instanceof AnthropicClient);
        assertTrue(ModelClients.create(Connection.of("o", ConnectionKind.OPENAI_COMPATIBLE), "k")
                instanceof OpenAiCompatibleClient);
    }
}
