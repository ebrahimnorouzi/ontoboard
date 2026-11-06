package de.fizkarlsruhe.ise.ontoboard.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Following GitHub's redirect to the file, and nothing else.
 *
 * <p>GitHub answers a release download with one redirect, to
 * {@code release-assets.githubusercontent.com} (measured). Redirects are followed by hand rather
 * than by {@code HttpURLConnection}, so that the trust store chosen for a retry applies to every
 * hop and so that no hop can leave HTTPS. The server here is plain HTTP on 127.0.0.1, which only
 * the test opener accepts.
 */
class HttpsOpenerTest {

    private static final byte[] RELEASE = "the release".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private String base;
    /** Holds the silent server's answer back until the test is over. */
    private final java.util.concurrent.CountDownLatch release =
            new java.util.concurrent.CountDownLatch(1);

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/file", exchange -> {
            exchange.sendResponseHeaders(200, RELEASE.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(RELEASE);
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/file");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/silent", exchange -> {
            try {
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
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

    /**
     * Cancel has to let go of a server that has gone quiet, not wait out a 60-second read
     * timeout behind a window that blocks the rest of Protege.
     */
    @Test
    void stopLetsGoOfASilentServer() throws Exception {
        HttpsOpener opener = HttpsOpener.forLoopbackTests();
        java.util.concurrent.ExecutorService running =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Download.Opened> opening =
                    running.submit(() -> opener.open(base + "/silent"));
            Thread.sleep(500);
            long stopped = System.nanoTime();

            opener.stop();

            java.util.concurrent.ExecutionException failed = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> opening.get(10, java.util.concurrent.TimeUnit.SECONDS));
            long millis = (System.nanoTime() - stopped) / 1_000_000;
            assertTrue(failed.getCause() instanceof IOException, String.valueOf(failed.getCause()));
            assertTrue(millis < 3_000, "it took " + millis + " ms to let go");
        } finally {
            running.shutdownNow();
        }
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

    @Test
    void followsARedirectToTheFile() throws IOException {
        Download.Opened opened = HttpsOpener.forLoopbackTests().open(base + "/redirect");

        assertEquals(RELEASE.length, opened.getLength());
        assertArrayEquals(RELEASE, readAll(opened.getBody()));
    }

    @Test
    void stopsAfterFiveRedirects() {
        IOException failed = assertThrows(IOException.class,
                () -> HttpsOpener.forLoopbackTests().open(base + "/loop"));

        assertTrue(failed.getMessage().contains("redirects"), failed.getMessage());
    }

    @Test
    void aMissingFileSaysWhichAndWhy() {
        IOException failed = assertThrows(IOException.class,
                () -> HttpsOpener.forLoopbackTests().open(base + "/missing"));

        assertTrue(failed.getMessage().contains("404"), failed.getMessage());
        assertTrue(failed.getMessage().contains(base + "/missing"), failed.getMessage());
    }

    @Test
    void refusesToLeaveHttps() {
        assertThrows(IOException.class, () -> HttpsOpener.redirectTarget(
                new URL("https://github.com/dgarijo/Widoco/releases/download/v1.4.25/x.jar"),
                "http://example.org/x.jar", false));
        assertThrows(IOException.class, () -> HttpsOpener.real().open(base + "/file"),
                "the real opener does not open plain HTTP, even on this machine");
    }

    @Test
    void resolvesARelativeLocation() throws IOException {
        assertEquals(new URL("https://github.com/c/d"),
                HttpsOpener.redirectTarget(new URL("https://github.com/a/b"), "/c/d", false));
    }

    @Test
    void certificatePathFailuresAreRecognised() {
        SSLHandshakeException pkix = new SSLHandshakeException("sun.security.validator."
                + "ValidatorException: PKIX path building failed: sun.security.provider.certpath"
                + ".SunCertPathBuilderException: unable to find valid certification path to "
                + "requested target");

        assertTrue(HttpsOpener.isCertificatePathFailure(pkix));
        assertTrue(HttpsOpener.isCertificatePathFailure(new IOException("wrapped", pkix)));
        assertFalse(HttpsOpener.isCertificatePathFailure(new ConnectException("refused")));
        assertFalse(HttpsOpener.isCertificatePathFailure(null));
    }

    @Test
    void theFallbacksPerOperatingSystem(@TempDir File dir) throws IOException {
        File present = new File(dir, "cacerts");
        Files.write(present.toPath(), new byte[] {1});
        File absent = new File(dir, "not-there");

        assertEquals(Collections.singletonList("Windows-ROOT"),
                HttpsOpener.trustFallbacks("Windows 11", Arrays.asList(present, absent)));
        assertEquals(Collections.emptyList(),
                HttpsOpener.trustFallbacks("Mac OS X", Arrays.asList(present, absent)));
        assertEquals(Collections.singletonList(present.getPath()),
                HttpsOpener.trustFallbacks("Linux", Arrays.asList(present, absent)));
    }
}
