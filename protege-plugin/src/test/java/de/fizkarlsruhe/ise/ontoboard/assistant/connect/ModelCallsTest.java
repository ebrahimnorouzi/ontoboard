package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.tools.HttpsOpener;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URL;
import org.junit.jupiter.api.Test;

/** What a person reads when a model call fails: the cause, in words they can act on. */
class ModelCallsTest {

    private final Connection claude = Connection.of("Claude", ConnectionKind.ANTHROPIC)
            .withModel("claude-x");
    private final Connection ollama = Connection.of("Ollama", ConnectionKind.OLLAMA)
            .withModel("llama3.1");

    private static HttpsOpener.Refused refused(int status, String body) throws IOException {
        return new HttpsOpener.Refused(new URL("https://api.anthropic.com/v1/messages"), status,
                body);
    }

    @Test
    void a401SaysTheKeyWasRefused() throws IOException {
        ModelCallException failure = ModelCalls.translate(
                refused(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                        + "\"message\":\"invalid x-api-key\"}}"), claude);

        assertEquals(ModelCallException.Kind.UNAUTHORIZED, failure.getKind());
        assertTrue(failure.getMessage().contains("api.anthropic.com"), failure.getMessage());
        assertTrue(failure.getMessage().contains("invalid x-api-key"), failure.getMessage());
    }

    @Test
    void a404NamesTheModel() throws IOException {
        ModelCallException failure = ModelCalls.translate(
                refused(404, "{\"error\":\"model 'claude-x' not found\"}"), claude);

        assertEquals(ModelCallException.Kind.MODEL_NOT_FOUND, failure.getKind());
        assertTrue(failure.getMessage().contains("claude-x"), failure.getMessage());
    }

    @Test
    void a429IsRateLimited() throws IOException {
        assertEquals(ModelCallException.Kind.RATE_LIMITED,
                ModelCalls.translate(refused(429, "slow down"), claude).getKind());
    }

    @Test
    void a500IsTheServers() throws IOException {
        assertEquals(ModelCallException.Kind.SERVER,
                ModelCalls.translate(refused(529, "{\"error\":{\"message\":\"Overloaded\"}}"),
                        claude).getKind());
    }

    @Test
    void nothingListeningAtOllamaSaysStartIt() {
        ModelCallException failure =
                ModelCalls.translate(new ConnectException("Connection refused"), ollama);

        assertEquals(ModelCallException.Kind.UNREACHABLE, failure.getKind());
        assertTrue(failure.getMessage().contains("ollama serve"), failure.getMessage());
        assertTrue(failure.getMessage().contains("http://localhost:11434"), failure.getMessage());
    }

    @Test
    void nothingListeningElsewhereNamesTheAddress() {
        ModelCallException failure = ModelCalls.translate(
                new java.net.UnknownHostException("api.anthropic.com"), claude);

        assertEquals(ModelCallException.Kind.UNREACHABLE, failure.getKind());
        assertTrue(failure.getMessage().contains("https://api.anthropic.com"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains("ollama"), failure.getMessage());
    }

    @Test
    void aPolicyRefusalStaysAPolicyRefusal() {
        ModelCallException failure = ModelCalls.translate(new IOException(
                "OntoBoard sends to lab over HTTPS only. Plain http is allowed to this machine, "
                        + "or to a server you marked as on your network."), ollama);

        assertEquals(ModelCallException.Kind.REFUSED_BY_POLICY, failure.getKind());
        assertTrue(failure.getMessage().contains("on your network"), failure.getMessage());
    }

    @Test
    void anHtmlErrorPageBecomesAShortPlainMessage() throws IOException {
        StringBuilder page = new StringBuilder("<html><head><title>401</title></head><body>"
                + "<h1>401 Authorization Required</h1>");
        for (int i = 0; i < 100; i++) {
            page.append("<p>proxy filler text</p>");
        }
        page.append("</body></html>");

        ModelCallException failure = ModelCalls.translate(refused(401, page.toString()), claude);

        assertTrue(failure.getMessage().contains("401 Authorization Required"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains("<"), failure.getMessage());
        assertTrue(ModelCalls.serverMessage(page.toString()).length() <= 200);
    }

    @Test
    void theKeyNeverAppearsInAMessage() throws IOException {
        ModelCallException failure = ModelCalls.translate(
                refused(401, "{\"error\":{\"message\":\"key sk-secret is not valid\"}}"), claude,
                "sk-secret");

        assertFalse(failure.getMessage().contains("sk-secret"), failure.getMessage());
        assertTrue(failure.getMessage().contains("(your key)"), failure.getMessage());
    }

    @Test
    void aStoppedCallIsStopped() {
        assertEquals(ModelCallException.Kind.STOPPED,
                ModelCalls.translate(new ModelCallException(ModelCallException.Kind.STOPPED,
                        "Stopped."), claude).getKind());
    }
}
