package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A ROBOT line that loads an ontology from its address ({@code -I}): w3.org answers
 * {@code http://www.w3.org/ns/dcat3} with "301 Moved Permanently" to the same address in https.
 * Java does not follow a redirect from http to https, and the OWL API of Protégé 5.5.0 (4.5.9)
 * leaves that to Java - ROBOT read W3C's page about the move as the ontology. ODK's (4.5.29)
 * follows it. So OntoBoard follows such a redirect before ROBOT loads (nfdicore's refresh-dcat,
 * found by the live run of 1.154.0).
 */
class IriRedirectsTest {

    private HttpServer server;
    private String here;

    @BeforeEach
    void aServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "https://example.org/ns/dcat3");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> {
            exchange.getResponseHeaders().add("Location", here + "/ontology");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/ontology", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        here = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopIt() {
        server.stop(0);
    }

    @Test
    void aMoveFromHttpToHttpsIsFollowedBeforeRobotLoads() {
        assertEquals("https://example.org/ns/dcat3", IriRedirects.acrossToHttps(here + "/moved"));
    }

    /** Java follows a redirect within http itself, and ROBOT is given the address as written. */
    @Test
    void anythingElseIsLeftAsWritten() {
        assertEquals(here + "/elsewhere", IriRedirects.acrossToHttps(here + "/elsewhere"));
        assertEquals(here + "/ontology", IriRedirects.acrossToHttps(here + "/ontology"));
        assertEquals("https://example.org/x.owl",
                IriRedirects.acrossToHttps("https://example.org/x.owl"));
        assertEquals("http://127.0.0.1:9/nobody", IriRedirects.acrossToHttps(
                "http://127.0.0.1:9/nobody"), "nobody answers: as written, ROBOT says why");
    }

    @Test
    void theInputIriOfALineIsTheOneTheMoveLeadsTo() {
        List<String> line = Arrays.asList("--catalog", "catalog-v001.xml", "convert", "-I",
                here + "/moved", "-o", "tmp/mirror-dcat.owl");

        assertEquals(Arrays.asList("--catalog", "catalog-v001.xml", "convert", "-I",
                "https://example.org/ns/dcat3", "-o", "tmp/mirror-dcat.owl"),
                IriRedirects.inputIrisAcrossToHttps(line));
    }
}
