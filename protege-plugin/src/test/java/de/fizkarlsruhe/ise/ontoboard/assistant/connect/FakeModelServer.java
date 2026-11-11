package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A model server on 127.0.0.1 for tests: answers each path with a scripted status and body,
 * sends the body in separately flushed chunks so streaming is exercised, and records what it was
 * sent. No test calls a real model.
 */
public final class FakeModelServer implements AutoCloseable {

    private static final class Script {
        final int status;
        final String contentType;
        final List<byte[]> chunks;
        final boolean stall;

        Script(int status, String contentType, List<byte[]> chunks, boolean stall) {
            this.status = status;
            this.contentType = contentType;
            this.chunks = chunks;
            this.stall = stall;
        }
    }

    private final HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final Map<String, Script> scripts = new ConcurrentHashMap<String, Script>();
    private final Map<String, Script> once = new ConcurrentHashMap<String, Script>();
    private final List<String> bodies = java.util.Collections.synchronizedList(
            new ArrayList<String>());
    private final CountDownLatch closing = new CountDownLatch(1);
    private volatile String lastBody = "";
    private volatile String lastPath = "";
    private volatile Map<String, String> lastHeaders = Collections.emptyMap();
    private volatile int requests;

    private FakeModelServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(threads);
        server.createContext("/", this::handle);
        server.start();
    }

    public static FakeModelServer start() throws IOException {
        return new FakeModelServer();
    }

    /** {@code http://127.0.0.1:<port>}, without a trailing slash. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Answers {@code path} (without its query) with these chunks, each flushed on its own. */
    public void respond(String path, int status, String contentType, String... chunks) {
        List<byte[]> bytes = new ArrayList<byte[]>();
        for (String chunk : chunks) {
            bytes.add(chunk.getBytes(StandardCharsets.UTF_8));
        }
        scripts.put(path, new Script(status, contentType, bytes, false));
    }

    /** Answers the next request to {@code path} this way, and later ones as {@link #respond} says. */
    public void respondOnce(String path, int status, String contentType, String... chunks) {
        List<byte[]> bytes = new ArrayList<byte[]>();
        for (String chunk : chunks) {
            bytes.add(chunk.getBytes(StandardCharsets.UTF_8));
        }
        once.put(path, new Script(status, contentType, bytes, false));
    }

    /** As {@link #respond}, with the chunks as raw bytes - for splitting a character in two. */
    public void respondBytes(String path, int status, String contentType, byte[]... chunks) {
        List<byte[]> bytes = new ArrayList<byte[]>();
        Collections.addAll(bytes, chunks);
        scripts.put(path, new Script(status, contentType, bytes, false));
    }

    /** Sends the headers and the first chunk, then says nothing more until the server closes. */
    public void stall(String path, String contentType, String firstChunk) {
        scripts.put(path, new Script(200, contentType,
                Collections.singletonList(firstChunk.getBytes(StandardCharsets.UTF_8)), true));
    }

    public String lastBody() {
        return lastBody;
    }

    /** Every request body so far, oldest first. */
    public List<String> bodies() {
        synchronized (bodies) {
            return new ArrayList<String>(bodies);
        }
    }

    public String lastPath() {
        return lastPath;
    }

    /** Header names in lower case. */
    public Map<String, String> lastHeaders() {
        return lastHeaders;
    }

    public int requestCount() {
        return requests;
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests++;
        String path = exchange.getRequestURI().getPath();
        Map<String, String> headers = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            headers.put(header.getKey().toLowerCase(Locale.ROOT),
                    header.getValue().isEmpty() ? "" : header.getValue().get(0));
        }
        lastHeaders = headers;
        lastPath = path;
        lastBody = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
        bodies.add(lastBody);
        Script script = once.remove(path);
        if (script == null) {
            script = scripts.get(path);
        }
        if (script == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", script.contentType);
        exchange.sendResponseHeaders(script.status, 0);
        OutputStream out = exchange.getResponseBody();
        try {
            for (byte[] chunk : script.chunks) {
                out.write(chunk);
                out.flush();
            }
            if (script.stall) {
                try {
                    closing.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (IOException clientWentAway) {
            // The client stopped reading - which is what a stop test is for.
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    @Override
    public void close() {
        closing.countDown();
        server.stop(0);
        threads.shutdownNow();
    }
}
