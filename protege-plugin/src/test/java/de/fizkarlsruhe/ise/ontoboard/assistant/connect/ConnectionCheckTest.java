package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The row in Check requirements: lists the models, never spends a token. */
class ConnectionCheckTest {

    private FakeModelServer server;
    private Connection ollama;

    @BeforeEach
    void start() throws IOException {
        server = FakeModelServer.start();
        server.respond("/api/tags", 200, "application/json",
                "{\"models\":[{\"name\":\"llama3.1:latest\"},{\"name\":\"qwen2.5:7b\"}]}");
        ollama = Connection.of("Ollama", ConnectionKind.OLLAMA).withBaseUrl(server.url());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void readyListsTheModels() {
        ConnectionCheck.Result result =
                ConnectionCheck.run(ollama.withModel("llama3.1:latest"), "");

        assertEquals(ConnectionCheck.State.READY, result.getState());
        assertEquals("Ollama at 127.0.0.1: 2 models; llama3.1:latest is there",
                result.getDetail());
        assertEquals("", result.getRemedy());
        assertEquals("/api/tags", server.lastPath(), "only the model list was asked for");
    }

    @Test
    void anOllamaNameWithoutATagMeansLatest() {
        assertEquals(ConnectionCheck.State.READY,
                ConnectionCheck.run(ollama.withModel("llama3.1"), "").getState());
    }

    @Test
    void aModelNotOnTheServerIsNotWorking() {
        ConnectionCheck.Result result = ConnectionCheck.run(ollama.withModel("mistral"), "");

        assertEquals(ConnectionCheck.State.NOT_WORKING, result.getState());
        assertEquals("mistral is not on 127.0.0.1", result.getDetail());
        assertEquals("Pick a model from the list in the Assistant column's Settings tab.",
                result.getRemedy());
    }

    @Test
    void noModelChosenIsNotWorking() {
        ConnectionCheck.Result result = ConnectionCheck.run(ollama, "");

        assertEquals(ConnectionCheck.State.NOT_WORKING, result.getState());
        assertTrue(result.getDetail().contains("no model is chosen"), result.getDetail());
    }

    @Test
    void aFailureIsNotWorkingWithTheMessage() {
        server.respond("/api/tags", 401, "application/json", "{\"error\":\"unauthorized\"}");

        ConnectionCheck.Result result = ConnectionCheck.run(ollama.withModel("x"), "tok");

        assertEquals(ConnectionCheck.State.NOT_WORKING, result.getState());
        assertTrue(result.getDetail().contains("refused the key"), result.getDetail());
    }

    @Test
    void notSetUpSaysHow() {
        ConnectionCheck.Result result = ConnectionCheck.notSetUp();

        assertEquals(ConnectionCheck.State.NOT_SET_UP, result.getState());
        assertEquals("Set up a model in the Assistant column's Settings tab: a key for a cloud"
                + " model, or Ollama on this machine.", result.getRemedy());
    }
}
