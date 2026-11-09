package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 1.142.0 (L2): a read timeout before any part of the answer arrived is not "stopped answering
 * before the reply was complete". A local model on a slow computer can take minutes to read a
 * long request before its first word; the person is told that, and what to do about it.
 */
class ModelClientTimeoutTest {

    private static ChatRequest request() {
        return new ChatRequest("You help.", Arrays.asList(Message.user("a long question")), 64);
    }

    /** A server that accepts the connection, reads the request, and never answers. */
    private static final class Silent implements AutoCloseable {
        private final ServerSocket server;
        private volatile Socket held;

        private Silent() throws IOException {
            server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            Thread accepting = new Thread(() -> {
                try {
                    held = server.accept();
                    InputStream in = held.getInputStream();
                    byte[] buffer = new byte[4096];
                    while (in.read(buffer) >= 0) {
                        // Read the request and say nothing.
                    }
                } catch (IOException closed) {
                    // The test is over.
                }
            }, "silent model server");
            accepting.setDaemon(true);
            accepting.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            Socket socket = held;
            if (socket != null) {
                socket.close();
            }
            server.close();
        }
    }

    @Test
    void aServerThatSendsNothingIsReportedAsSilent() throws Exception {
        try (Silent silent = new Silent()) {
            OllamaClient client = new OllamaClient(Connection.of("Ollama", ConnectionKind.OLLAMA)
                    .withBaseUrl(silent.url()).withModel("qwen"), "");
            client.readMillis(300);

            ModelCallException failure = assertThrows(ModelCallException.class,
                    () -> client.chat(request(), d -> { }));

            String message = failure.getMessage();
            assertTrue(message.contains("sent nothing in 0.3 seconds"), message);
            assertTrue(message.contains("local model"), message);
            assertFalse(message.contains("stopped answering"), message);
        }
    }

    /** LM Studio and llama.cpp send their headers at once and the first line after reading. */
    @Test
    void headersAndThenNothingIsAlsoSilent() throws Exception {
        FakeModelServer server = FakeModelServer.start();
        try {
            server.stall("/v1/chat/completions", "text/event-stream", "");
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    Connection.of("LM Studio", ConnectionKind.OPENAI_COMPATIBLE)
                            .withBaseUrl(server.url() + "/v1").withModel("qwen"), "");
            client.readMillis(300);

            ModelCallException failure = assertThrows(ModelCallException.class,
                    () -> client.chat(request(), d -> { }));

            assertTrue(failure.getMessage().contains("sent nothing"), failure.getMessage());
        } finally {
            server.close();
        }
    }

    @Test
    void aReplyCutShortKeepsItsMessage() throws Exception {
        FakeModelServer server = FakeModelServer.start();
        try {
            server.stall("/api/chat", "application/x-ndjson",
                    "{\"model\":\"qwen\",\"message\":{\"role\":\"assistant\",\"content\":\"Half\"},"
                            + "\"done\":false}\n");
            OllamaClient client = new OllamaClient(Connection.of("Ollama", ConnectionKind.OLLAMA)
                    .withBaseUrl(server.url()).withModel("qwen"), "");
            client.readMillis(300);

            ModelCallException failure = assertThrows(ModelCallException.class,
                    () -> client.chat(request(), d -> { }));

            assertTrue(failure.getMessage().contains(
                    "stopped answering before the reply was complete"), failure.getMessage());
        } finally {
            server.close();
        }
    }

    /** Review focus 4: a cloud service is not "a local model". */
    @Test
    void aSilentCloudServiceIsNotCalledALocalModel() {
        Connection cloud = Connection.of("Anthropic", ConnectionKind.ANTHROPIC)
                .withBaseUrl("https://api.anthropic.com");

        String message = ModelCalls.silent(cloud, 300_000).getMessage();

        assertTrue(message.contains("api.anthropic.com sent nothing in 5 minutes"), message);
        assertFalse(message.contains("local model"), message);
    }

    /**
     * Review 1.144.0 chips-parse-1: Java 8 and 11 say "connect timed out", Java 13 and later
     * "Connect timed out". The second was taken for a server that sent nothing in 5 minutes.
     */
    @Test
    void aConnectTimeoutInEitherCaseIsNotNothingReceived() {
        Connection local = Connection.of("Ollama", ConnectionKind.OLLAMA)
                .withBaseUrl("http://10.255.255.1:11434");
        for (String said : new String[] {"connect timed out", "Connect timed out"}) {
            java.io.IOException failure = ModelCalls.nothingYet(
                    new java.net.SocketTimeoutException(said), 300_000);

            ModelCallException translated = ModelCalls.translate(failure, local);

            assertTrue(translated.getKind() == ModelCallException.Kind.UNREACHABLE,
                    said + ": " + translated.getMessage());
            assertFalse(translated.getMessage().contains("sent nothing"), translated.getMessage());
        }
    }

    /** Review 1.144.0 chips-parse-4: a password holding "@" left its end in the message. */
    @Test
    void anAddressIsShownWithoutAPasswordThatHoldsAnAt() {
        assertTrue(ModelCalls.address("https://alice:p@ss-Hunter2@llm.example.org:8443/v1")
                .equals("https://llm.example.org:8443/v1"),
                ModelCalls.address("https://alice:p@ss-Hunter2@llm.example.org:8443/v1"));
    }
}
