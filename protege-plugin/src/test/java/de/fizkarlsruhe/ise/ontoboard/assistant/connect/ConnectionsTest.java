package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.tools.HttpRequest;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Saved connections: what Protégé's preferences hold and what the benchmark's models file is. */
class ConnectionsTest {

    @Test
    void roundTripKeepsEveryField() {
        Connection claude = Connection.of("Claude", ConnectionKind.ANTHROPIC)
                .withModel("claude-x").withMaxOutputTokens(4096);
        Connection lab = Connection.of("Lab server", ConnectionKind.OLLAMA)
                .withBaseUrl("http://192.168.1.5:11434").withModel("llama3.1")
                .withPlainHttpOnNetwork(true).withContextLength(16384)
                .withKeyVariable("LAB_TOKEN");

        List<Connection> back = Connections.fromJson(Connections.toJson(Arrays.asList(claude, lab)));

        assertEquals(Arrays.asList(claude, lab), back);
    }

    @Test
    void missingFieldsTakeTheKindsDefaults() {
        Connection ollama = Connections.fromJson("[{\"name\":\"o\",\"kind\":\"ollama\"}]").get(0);

        assertEquals("http://localhost:11434", ollama.getBaseUrl());
        assertEquals(8192, ollama.getContextLength());
        assertEquals(2048, ollama.getMaxOutputTokens());
        assertEquals("", ollama.getModel());
        assertEquals("", ollama.getKeyVariable());

        Connection anthropic = Connection.of("a", ConnectionKind.ANTHROPIC);
        assertEquals("https://api.anthropic.com", anthropic.getBaseUrl());
        assertEquals("ANTHROPIC_API_KEY", anthropic.getKeyVariable());
        assertEquals("https://api.openai.com/v1",
                Connection.of("b", ConnectionKind.OPENAI_COMPATIBLE).getBaseUrl());
        assertEquals("OPENAI_API_KEY",
                Connection.of("b", ConnectionKind.OPENAI_COMPATIBLE).getKeyVariable());
    }

    @Test
    void anUnknownKindNamesTheChoices() {
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> Connections.fromJson("[{\"name\":\"x\",\"kind\":\"gemini\"}]"));

        assertTrue(wrong.getMessage().contains("anthropic, openai-compatible, ollama"),
                wrong.getMessage());
    }

    @Test
    void anEntryWithoutANameSaysWhichOne() {
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> Connections.fromJson(
                        "[{\"name\":\"a\",\"kind\":\"ollama\"},{\"kind\":\"ollama\"}]"));

        assertEquals("connection 2 has no name", wrong.getMessage());
    }

    /**
     * Review 1.145.0 keys-02: Jackson's message quoted what it read - the whole text in its
     * location, an unrecognised word in full - so a models file with a syntax error put its key
     * on the terminal. The key is made up.
     */
    @Test
    void textThatIsNotJsonIsNotQuotedInTheMessage() {
        String key = "Qm7RkT2vXp9LwN4sJd8HfB3cZy6GaE1u";
        String[] texts = {
            "[{\"name\":\"m\",\"kind\":\"ollama\",\"apiKey\":\"" + key + "\",}]",
            "[{\"name\":\"m\",\"kind\":\"ollama\",\"apiKey\":" + key + "}]",
        };
        for (String text : texts) {
            IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                    () -> Connections.fromJson(text));

            for (Throwable said = wrong; said != null; said = said.getCause()) {
                assertFalse(String.valueOf(said.getMessage()).contains(key.substring(0, 12)),
                        said.getMessage());
            }
            assertTrue(wrong.getMessage().contains("not valid JSON"), wrong.getMessage());
            assertTrue(wrong.getMessage().contains("line 1, column "), wrong.getMessage());
        }
    }

    @Test
    void unknownKeysAreIgnoredSoAModelsFileCanCarryNotes() {
        Connection noted = Connections.fromJson(
                "[{\"name\":\"a\",\"kind\":\"ollama\",\"_note\":\"fill in the model\"}]").get(0);

        assertEquals("a", noted.getName());
    }

    @Test
    void localMeansThisMachineOrPrivateNetwork() {
        assertTrue(Connection.of("o", ConnectionKind.OLLAMA).isLocal());
        assertTrue(Connection.of("o", ConnectionKind.OLLAMA).isThisMachine());
        assertFalse(Connection.of("a", ConnectionKind.ANTHROPIC).isLocal());
        Connection lab = Connection.of("o", ConnectionKind.OLLAMA)
                .withBaseUrl("http://192.168.1.5:11434");
        assertTrue(lab.isLocal());
        assertFalse(lab.isThisMachine());
        assertEquals("192.168.1.5", lab.getHost());
        assertEquals("api.anthropic.com", Connection.of("a", ConnectionKind.ANTHROPIC).getHost());
    }

    @Test
    void plainHttpPolicyFollowsTheTick() {
        Connection ollama = Connection.of("o", ConnectionKind.OLLAMA);

        assertEquals(HttpRequest.PlainHttp.THIS_MACHINE, ollama.getPlainHttp());
        assertEquals(HttpRequest.PlainHttp.NETWORK,
                ollama.withPlainHttpOnNetwork(true).getPlainHttp());
    }

    @Test
    void aTrailingSlashOnTheBaseUrlIsDropped() {
        assertEquals("http://localhost:11434", Connection.of("o", ConnectionKind.OLLAMA)
                .withBaseUrl("http://localhost:11434/").getBaseUrl());
    }

    /**
     * Review 1.143.0 M-4: an address java.net.URL cannot read - written without its scheme - was
     * shown whole wherever the host is, a password with it.
     */
    @org.junit.jupiter.api.Test
    void anAddressWithoutASchemeIsShownWithoutItsCredentials() {
        Connection typed = Connection.of("GPU box", ConnectionKind.OLLAMA)
                .withBaseUrl("ontouser:s3cretPassw0rd@gpu-box:11434");

        org.junit.jupiter.api.Assertions.assertEquals("gpu-box:11434", typed.getHost());
    }

    @org.junit.jupiter.api.Test
    void credentialsInAnAddressAreFound() {
        org.junit.jupiter.api.Assertions.assertTrue(Connection.of("a", ConnectionKind.OLLAMA)
                .withBaseUrl("http://ontouser:pw@127.0.0.1:11434").hasCredentials());
        org.junit.jupiter.api.Assertions.assertTrue(Connection.of("a", ConnectionKind.OLLAMA)
                .withBaseUrl("ontouser:pw@gpu-box:11434").hasCredentials());
        org.junit.jupiter.api.Assertions.assertFalse(Connection.of("a", ConnectionKind.OLLAMA)
                .withBaseUrl("http://localhost:11434").hasCredentials());
        org.junit.jupiter.api.Assertions.assertFalse(Connection.of("a", ConnectionKind.OLLAMA)
                .withBaseUrl("https://api.example.org/v1/models@2024").hasCredentials());
    }
}
