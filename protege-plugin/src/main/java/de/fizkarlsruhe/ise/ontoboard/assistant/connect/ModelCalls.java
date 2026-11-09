package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.fizkarlsruhe.ise.ontoboard.tools.HttpsOpener;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Turns whatever a model call threw into a {@link ModelCallException} whose message says what
 * happened and what to do, for the three clients alike.
 *
 * <p>The server's own words are kept where it gave any - "invalid x-api-key" tells a person more
 * than any paraphrase - but only as a short excerpt: a proxy in front of an API answers with an
 * HTML page, and a page of markup in a chat column helps nobody.
 */
public final class ModelCalls {

    /** The longest excerpt of a server's explanation kept in a message. */
    static final int MAX_SAID = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ModelCalls() {
    }

    public static ModelCallException translate(IOException failure, Connection connection) {
        return translate(failure, connection, null);
    }

    /**
     * @param apiKey blanked out of the message wherever a server echoed it; null when there is
     *     none
     */
    public static ModelCallException translate(IOException failure, Connection connection,
            String apiKey) {
        ModelCallException translated = classify(failure, connection);
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty() || !translated.getMessage().contains(key)) {
            return translated;
        }
        return new ModelCallException(translated.getKind(),
                translated.getMessage().replace(key, "(your key)"));
    }

    /**
     * A read timeout before any part of the answer arrived (1.142.0, L2). Not "stopped answering
     * before the reply was complete": nothing had begun. A model on a computer without a graphics
     * card reads the whole request before its first word, and a long request can take minutes.
     */
    static final class NothingReceived extends SocketTimeoutException {
        private static final long serialVersionUID = 1L;
        final int waitedMillis;

        NothingReceived(int waitedMillis) {
            super("nothing received in " + waitedMillis + " ms");
            this.waitedMillis = waitedMillis;
        }
    }

    /**
     * A timeout while connecting. Java 8 and 11 say "connect timed out", 13 and later "Connect
     * timed out", and the second was taken for a server that sent nothing (1.145.0, review
     * 1.144.0 chips-parse-1).
     */
    static boolean isConnectTimeout(IOException failure) {
        return failure instanceof SocketTimeoutException && failure.getMessage() != null
                && failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("connect");
    }

    /** A read timeout, as {@link NothingReceived} when it is not a connect timeout. */
    static IOException nothingYet(IOException failure, int waitedMillis) {
        if (failure instanceof SocketTimeoutException && !(failure instanceof NothingReceived)
                && !isConnectTimeout(failure)) {
            NothingReceived silent = new NothingReceived(waitedMillis);
            silent.initCause(failure);
            return silent;
        }
        return failure;
    }

    /** What a person reads when the server sent nothing at all within the wait. */
    public static ModelCallException silent(Connection connection, int waitedMillis) {
        String start = connection.getHost() + " sent nothing in " + duration(waitedMillis) + ".";
        if (connection.isLocal()) {
            return new ModelCallException(ModelCallException.Kind.SERVER, start
                    + " A local model without a graphics card reads the whole request before its"
                    + " first word, and a long request can take longer than that. Send less"
                    + " context (remove some chips), or use a smaller model.");
        }
        return new ModelCallException(ModelCallException.Kind.SERVER, start
                + " The service may be busy. Send again in a little while.");
    }

    /**
     * An address as a message may show it: without a user and password, and without a query,
     * which can carry a key (1.144.0, review 1.143.0 M-4: a saved conversation records errors).
     * Up to the last "@" before the path: a password may hold one (1.145.0, review 1.144.0
     * chips-parse-4).
     */
    static String address(String url) {
        if (url == null) {
            return "";
        }
        String shown = url.replaceFirst("^([A-Za-z][A-Za-z0-9+.-]*://)[^/?#]*@", "$1");
        int query = shown.indexOf('?');
        return query < 0 ? shown : shown.substring(0, query);
    }

    /** "5 minutes", "30 seconds", "0.3 seconds". */
    static String duration(int millis) {
        if (millis >= 60_000 && millis % 60_000 == 0) {
            int minutes = millis / 60_000;
            return minutes == 1 ? "1 minute" : minutes + " minutes";
        }
        if (millis % 1000 == 0) {
            int seconds = millis / 1000;
            return seconds == 1 ? "1 second" : seconds + " seconds";
        }
        return String.format(java.util.Locale.ROOT, "%.1f seconds", millis / 1000.0);
    }

    private static ModelCallException classify(IOException failure, Connection connection) {
        if (failure instanceof ModelCallException) {
            return (ModelCallException) failure;
        }
        if (failure instanceof NothingReceived) {
            return silent(connection, ((NothingReceived) failure).waitedMillis);
        }
        String host = connection.getHost();
        if (failure instanceof HttpsOpener.Refused) {
            HttpsOpener.Refused refused = (HttpsOpener.Refused) failure;
            int status = refused.getStatus();
            String said = serverMessage(refused.getBody());
            if (status == 401 || status == 403) {
                return new ModelCallException(ModelCallException.Kind.UNAUTHORIZED, host
                        + " refused the key (" + status + "): " + said
                        + ". Check the key in Settings.");
            }
            if (status == 404) {
                String model = connection.getModel().isEmpty() ? "(no model chosen)"
                        : "\"" + connection.getModel() + "\"";
                return new ModelCallException(ModelCallException.Kind.MODEL_NOT_FOUND, host
                        + " has no model " + model + " (404): " + said
                        + ". Pick a model from the list in Settings.");
            }
            if (status == 429) {
                return new ModelCallException(ModelCallException.Kind.RATE_LIMITED, host
                        + " is limiting requests (429): " + said
                        + ". Wait a little and send again.");
            }
            if (status >= 500) {
                return new ModelCallException(ModelCallException.Kind.SERVER, host
                        + " had a problem (" + status + "): " + said + ".");
            }
            return new ModelCallException(ModelCallException.Kind.SERVER, host
                    + " refused the request (" + status + "): " + said + ".");
        }
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.contains("over HTTPS only")) {
            return new ModelCallException(ModelCallException.Kind.REFUSED_BY_POLICY, message,
                    failure);
        }
        if (message.contains("was stopped")) {
            return new ModelCallException(ModelCallException.Kind.STOPPED, "Stopped.", failure);
        }
        if (failure instanceof ConnectException || failure instanceof UnknownHostException
                || failure instanceof NoRouteToHostException
                || isConnectTimeout(failure)) {
            if (connection.getKind() == ConnectionKind.OLLAMA) {
                return new ModelCallException(ModelCallException.Kind.UNREACHABLE,
                        "Nothing answered at " + address(connection.getBaseUrl())
                                + ". Is Ollama running? Start it with `ollama serve`.", failure);
            }
            return new ModelCallException(ModelCallException.Kind.UNREACHABLE,
                    "Nothing answered at " + address(connection.getBaseUrl()) + " (" + message
                            + "). Check the address and this computer's connection.", failure);
        }
        if (failure instanceof SocketTimeoutException) {
            return new ModelCallException(ModelCallException.Kind.SERVER, host
                    + " stopped answering before the reply was complete (" + message + ").",
                    failure);
        }
        if (HttpsOpener.isCertificatePathFailure(failure)) {
            return new ModelCallException(ModelCallException.Kind.UNREACHABLE, "This Java cannot"
                    + " check the certificate of " + host + ". On Protégé 5.5.0 on macOS that is"
                    + " a known limit; a local model through Ollama is unaffected.", failure);
        }
        return new ModelCallException(ModelCallException.Kind.SERVER,
                "The call to " + host + " failed: " + message, failure);
    }

    /**
     * The explanation in an error body: {@code error.message}, a string {@code error}, or
     * {@code message} when the body is JSON; otherwise the text with any markup taken out. At
     * most {@value #MAX_SAID} characters.
     */
    public static String serverMessage(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "no explanation given";
        }
        String said = null;
        try {
            JsonNode root = JSON.readTree(body);
            if (root != null && root.isObject()) {
                JsonNode error = root.get("error");
                if (error != null && error.isObject() && error.path("message").isTextual()) {
                    said = error.path("message").asText();
                } else if (error != null && error.isTextual()) {
                    said = error.asText();
                } else if (root.path("message").isTextual()) {
                    said = root.path("message").asText();
                } else if (root.path("detail").isTextual()) {
                    said = root.path("detail").asText();
                }
            }
        } catch (IOException notJson) {
            // An HTML page from a proxy, or plain text: handled below.
        }
        if (said == null) {
            said = body.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                    .replaceAll("<[^>]*>", " ")
                    .replace("&nbsp;", " ");
        }
        said = said.replaceAll("\\s+", " ").trim();
        if (said.isEmpty()) {
            return "no explanation given";
        }
        return said.length() <= MAX_SAID ? said : said.substring(0, MAX_SAID - 1) + "…";
    }
}
