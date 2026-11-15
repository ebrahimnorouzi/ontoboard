package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.fizkarlsruhe.ise.ontoboard.tools.HttpRequest;
import de.fizkarlsruhe.ise.ontoboard.tools.HttpsOpener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * What the three clients share: one {@link HttpsOpener} per call, kept so {@link #stop} can close
 * it from another thread; the connection's plain-http policy on every request; and failures
 * turned into messages that never carry the key.
 *
 * <p>A client is made for one call, as the session does: once stopped it stays stopped, so a
 * Stop pressed between two requests of the same call cannot be lost.
 */
abstract class AbstractModelClient implements ModelClient {

    /**
     * Five minutes: a local model can take minutes to load before its first word, and a timeout
     * that fires first reads as "the model is broken". Stop is the way out for a person who
     * does not want to wait.
     */
    static final int MODEL_READ_MILLIS = 300_000;

    static final ObjectMapper JSON = new ObjectMapper();

    protected final Connection connection;
    protected final String apiKey;
    private volatile HttpsOpener opener;
    private volatile boolean stopped;
    private volatile int readMillis = MODEL_READ_MILLIS;

    /** How long a model call waits for the next byte; tests wait less than five minutes. */
    void readMillis(int millis) {
        this.readMillis = millis;
    }

    AbstractModelClient(Connection connection, String apiKey) {
        this.connection = connection;
        this.apiKey = ApiKeys.clean(apiKey);
    }

    /**
     * Never waits. The connection is closed on a thread of its own, because closing it can block:
     * the JDK's chunked stream synchronizes {@code close} with a {@code read} in progress, so a
     * close issued while a stalled server holds a read blocks until that read returns - measured
     * at 59 s against a server that went quiet. Called on the event thread, that would freeze
     * Protégé. The call itself ends at once, because it reads through {@link #lines}.
     */
    @Override
    public void stop() {
        stopped = true;
        final HttpsOpener current = opener;
        if (current != null) {
            Thread closer = new Thread(current::stop, "OntoBoard assistant: closing a connection");
            closer.setDaemon(true);
            closer.start();
        }
    }

    /** Marks the end of a streamed answer in {@link #lines}' queue. */
    private static final Object END = new Object();

    /**
     * The lines of a streamed answer, read on a daemon thread so that {@link #stop} ends the call
     * at once even while the server says nothing. The calling thread takes the lines from a queue
     * and checks for Stop between polls; the reading thread is abandoned when stopped and ends
     * when the server closes or the read timeout fires.
     */
    protected SseReader.Lines lines(final InputStream body) {
        final java.util.concurrent.BlockingQueue<Object> queue =
                new java.util.concurrent.LinkedBlockingQueue<Object>();
        final int waited = readMillis;
        Thread reader = new Thread(() -> {
            boolean any = false;
            try (BufferedReader in = utf8(body)) {
                String line;
                while ((line = in.readLine()) != null) {
                    any = true;
                    queue.add(line);
                }
                queue.add(END);
            } catch (IOException failure) {
                // Headers came, but not one line of the answer (1.142.0, L2).
                queue.add(any ? failure : ModelCalls.nothingYet(failure, waited));
            } catch (RuntimeException failure) {
                queue.add(new IOException(failure));
            }
        }, "OntoBoard assistant: reading an answer");
        reader.setDaemon(true);
        reader.start();
        return () -> {
            while (true) {
                if (stopped) {
                    throw stoppedNow();
                }
                Object item;
                try {
                    item = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    stop();
                    throw stoppedNow();
                }
                if (item == null) {
                    continue;
                }
                if (item == END) {
                    queue.add(END);
                    return null;
                }
                if (item instanceof IOException) {
                    throw (IOException) item;
                }
                return (String) item;
            }
        };
    }

    protected boolean isStopped() {
        return stopped;
    }

    protected HttpRequest post(String path, JsonNode body) {
        try {
            return HttpRequest.post(connection.getBaseUrl() + path, JSON.writeValueAsBytes(body))
                    .header("Content-Type", "application/json")
                    .readTimeoutMillis(readMillis);
        } catch (IOException cannotWrite) {
            // Writing a tree of strings and numbers to bytes cannot fail.
            throw new IllegalStateException(cannotWrite);
        }
    }

    protected HttpRequest get(String path) {
        return HttpRequest.get(connection.getBaseUrl() + path);
    }

    /** Sends with a fresh opener under the connection's policy; failures come back translated. */
    protected HttpsOpener.Answer send(HttpRequest request) throws ModelCallException {
        if (stopped) {
            throw stoppedNow();
        }
        HttpsOpener fresh = HttpsOpener.real();
        opener = fresh;
        // Either stop() saw this opener, or this sees stop(): never neither.
        if (stopped) {
            fresh.stop();
            throw stoppedNow();
        }
        try {
            return fresh.send(request.plainHttp(connection.getPlainHttp()));
        } catch (IOException failure) {
            // A model call that times out here has not received its status line (1.142.0, L2).
            throw failed("POST".equals(request.getMethod())
                    ? ModelCalls.nothingYet(failure, request.getReadTimeoutMillis()) : failure);
        } catch (RuntimeException refused) {
            // HttpURLConnection reports a header value it will not send with an
            // IllegalArgumentException that quotes the value - which for the auth header is the
            // key. So the original message is never passed on, only what kind of failure it was.
            throw new ModelCallException(ModelCallException.Kind.BAD_REPLY, "The request to "
                    + connection.getHost() + " could not be made ("
                    + refused.getClass().getSimpleName() + "). Check the address and the key.");
        }
    }

    /** Any failure as the person should read it; STOPPED whenever Stop was pressed. */
    protected ModelCallException failed(IOException failure) {
        if (stopped) {
            return new ModelCallException(ModelCallException.Kind.STOPPED, "Stopped.", failure);
        }
        return ModelCalls.translate(failure, connection, apiKey);
    }

    /**
     * Called when a streamed answer ends. Stop closes the connection, and a closed connection
     * reads as an ordinary end of stream - measured: without this check a stopped call returned
     * a normal, truncated reply, which the person would take for the model's whole answer.
     */
    protected void endOfStream() throws ModelCallException {
        if (stopped) {
            throw stoppedNow();
        }
    }

    protected static ModelCallException stoppedNow() {
        return new ModelCallException(ModelCallException.Kind.STOPPED, "Stopped.");
    }

    /** UTF-8 over the whole stream, so a character split between two network reads survives. */
    protected static BufferedReader utf8(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** The JSON in one event or line, or null when it is not JSON. */
    protected static JsonNode parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return JSON.readTree(text);
        } catch (IOException notJson) {
            return null;
        }
    }

    /** A whole JSON body, read for a model list. */
    protected JsonNode readJson(HttpsOpener.Answer answer) throws ModelCallException {
        try (BufferedReader reader = utf8(answer.getBody())) {
            JsonNode root = JSON.readTree(reader);
            if (root == null) {
                throw new ModelCallException(ModelCallException.Kind.BAD_REPLY,
                        connection.getHost() + " answered with an empty body.");
            }
            return root;
        } catch (ModelCallException failure) {
            throw failure;
        } catch (IOException failure) {
            throw failed(failure);
        }
    }

    protected static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
