package de.fizkarlsruhe.ise.ontoboard.assistant.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.FakeModelServer;
import de.fizkarlsruhe.ise.ontoboard.odk.Toolchain;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The "Assistant model" row in *Check requirements...*. */
class AssistantRequirementTest {

    private FakeModelServer server;
    private final AssistantPreferences store = new AssistantPreferences(new FakePreferences());

    @BeforeEach
    void start() throws IOException {
        server = FakeModelServer.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void noConnectionIsAbsentWithTheRemedy() {
        Toolchain.Tool tool = AssistantRequirement.probe(store,
                Collections.<String, String>emptyMap());

        assertEquals("Assistant model", tool.getName());
        assertEquals(Toolchain.State.ABSENT, tool.getState());
        assertTrue(tool.getRemedy().startsWith("Set up a model in the Assistant column's"),
                tool.getRemedy());
    }

    @Test
    void aWorkingOllamaIsPresent() {
        server.respond("/api/tags", 200, "application/json",
                "{\"models\":[{\"name\":\"llama3.1:latest\"}]}");
        store.saveConnections(Arrays.asList(Connection.of("Ollama", ConnectionKind.OLLAMA)
                .withBaseUrl(server.url()).withModel("llama3.1")));

        Toolchain.Tool tool = AssistantRequirement.probe(store,
                Collections.<String, String>emptyMap());

        assertEquals(Toolchain.State.PRESENT, tool.getState());
        assertTrue(tool.getDetail().contains("llama3.1"), tool.getDetail());
    }

    @Test
    void aModelThatIsNotThereIsNotWorking() {
        server.respond("/api/tags", 200, "application/json", "{\"models\":[]}");
        store.saveConnections(Arrays.asList(Connection.of("Ollama", ConnectionKind.OLLAMA)
                .withBaseUrl(server.url()).withModel("llama3.1")));

        assertEquals(Toolchain.State.NOT_WORKING, AssistantRequirement.probe(store,
                Collections.<String, String>emptyMap()).getState());
    }

    @Test
    void theKeyComesFromTheEnvironmentWhenNotRemembered() {
        server.respond("/v1/models", 200, "application/json", "{\"data\":[{\"id\":\"m\"}]}");
        store.saveConnections(Arrays.asList(Connection.of("Lab", ConnectionKind.OPENAI_COMPATIBLE)
                .withBaseUrl(server.url() + "/v1").withModel("m")));

        Toolchain.Tool tool = AssistantRequirement.probe(store,
                Collections.singletonMap("OPENAI_API_KEY", "sk-env"));

        assertEquals(Toolchain.State.PRESENT, tool.getState());
        assertEquals("Bearer sk-env", server.lastHeaders().get("authorization"));
    }
}
