package de.fizkarlsruhe.ise.ontoboard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sending a request with a body: what a model API needs and a download never did.
 *
 * <p>The server is plain http on 127.0.0.1, which a request reaches only with
 * {@link HttpRequest.PlainHttp#THIS_MACHINE} or wider - the same policy Ollama on this machine
 * relies on.
 */
class HttpsOpenerSendTest {

    private HttpServer server;
    private String base;
    private volatile String seenMethod;
    private volatile String seenContentType;
    private volatile String seenTestHeader;
    private volatile String seenBody;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", exchange -> {
            seenMethod = exchange.getRequestMethod();
            seenContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            seenTestHeader = exchange.getRequestHeaders().getFirst("x-test");
            seenBody = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            byte[] answer = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        server.createContext("/denied", exchange -> {
            readAll(exchange.getRequestBody());
            byte[] answer = "{\"error\":{\"message\":\"invalid x-api-key\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        server.createContext("/long", exchange -> {
            readAll(exchange.getRequestBody());
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 10_000; i++) {
                text.append('x');
            }
            byte[] answer = text.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        server.createContext("/moved", exchange -> {
            readAll(exchange.getRequestBody());
            exchange.getResponseHeaders().add("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/silent", exchange -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream body = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = body.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static HttpRequest post(String url, String json) {
        return HttpRequest.post(url, json.getBytes(StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .plainHttp(HttpRequest.PlainHttp.THIS_MACHINE);
    }

    @Test
    void postsTheBodyAndHeaders() throws IOException {
        HttpsOpener.Answer answer = HttpsOpener.real()
                .send(post(base + "/ok", "{\"a\":1}").header("x-test", "yes"));

        assertEquals(200, answer.getStatus());
        assertEquals("ok", new String(readAll(answer.getBody()), StandardCharsets.UTF_8));
        assertEquals("POST", seenMethod);
        assertEquals("application/json", seenContentType);
        assertEquals("yes", seenTestHeader);
        assertEquals("{\"a\":1}", seenBody);
    }

    @Test
    void aRefusalCarriesTheServersOwnWords() {
        HttpsOpener.Refused refused = assertThrows(HttpsOpener.Refused.class,
                () -> HttpsOpener.real().send(post(base + "/denied", "{}")));

        assertEquals(401, refused.getStatus());
        assertTrue(refused.getBody().contains("invalid x-api-key"), refused.getBody());
    }

    @Test
    void aLongErrorBodyIsCutAt4000Characters() {
        HttpsOpener.Refused refused = assertThrows(HttpsOpener.Refused.class,
                () -> HttpsOpener.real().send(post(base + "/long", "{}")));

        assertEquals(500, refused.getStatus());
        assertTrue(refused.getBody().length() <= 4000, "length " + refused.getBody().length());
    }

    @Test
    void aModelRequestIsNotRedirected() {
        IOException failed = assertThrows(IOException.class,
                () -> HttpsOpener.real().send(post(base + "/moved", "{}")));

        assertTrue(failed.getMessage().contains("302"), failed.getMessage());
        assertTrue(failed.getMessage().contains("not redirected"), failed.getMessage());
    }

    @Test
    void plainHttpToThisMachineNeedsThePolicy() throws IOException {
        IOException refused = assertThrows(IOException.class, () -> HttpsOpener.real().send(
                HttpRequest.get(base + "/ok").plainHttp(HttpRequest.PlainHttp.NEVER)));
        assertTrue(refused.getMessage().contains("HTTPS only"), refused.getMessage());

        HttpsOpener.Answer answer = HttpsOpener.real().send(
                HttpRequest.get(base + "/ok").plainHttp(HttpRequest.PlainHttp.THIS_MACHINE));
        assertEquals(200, answer.getStatus());
        assertEquals("GET", seenMethod);
    }

    @Test
    void plainHttpToAnotherHostNeedsNetwork() {
        IOException refused = assertThrows(IOException.class, () -> HttpsOpener.real().send(
                HttpRequest.get("http://example.invalid/x")
                        .plainHttp(HttpRequest.PlainHttp.THIS_MACHINE)));

        assertFalse(refused instanceof UnknownHostException,
                "refused before any connection was attempted");
        assertTrue(refused.getMessage().contains("example.invalid"), refused.getMessage());
        assertTrue(refused.getMessage().contains("on your network"), refused.getMessage());
    }

    /** As {@code HttpsOpenerTest.stopLetsGoOfASilentServer}, for a request with a body. */
    @Test
    void stopLetsGoOfASilentSend() throws Exception {
        HttpsOpener opener = HttpsOpener.real();
        ExecutorService running = Executors.newSingleThreadExecutor();
        try {
            Future<HttpsOpener.Answer> sending =
                    running.submit(() -> opener.send(post(base + "/silent", "{}")));
            Thread.sleep(500);
            long stopped = System.nanoTime();

            opener.stop();

            ExecutionException failed = assertThrows(ExecutionException.class,
                    () -> sending.get(10, TimeUnit.SECONDS));
            long millis = (System.nanoTime() - stopped) / 1_000_000;
            assertTrue(failed.getCause() instanceof IOException, String.valueOf(failed.getCause()));
            assertTrue(millis < 3_000, "it took " + millis + " ms to let go");
        } finally {
            running.shutdownNow();
        }
    }
}
