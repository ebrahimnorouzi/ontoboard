package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.util.SimpleIRIMapper;

/**
 * Reading an ontology from a web address, against a server in the test.
 *
 * <p>The case that started this: a published ontology's own server accepted the connection and
 * then sent nothing. Find terms waited for the OWL API's five 20-second retries, again for each
 * format, said "Loading..." for minutes and then only that the load failed.
 */
class WebOntologyTest {

    private static final String TURTLE = "@prefix : <http://example.org/web#> .\n"
            + "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
            + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
            + "<http://example.org/web> a owl:Ontology .\n"
            + ":Pizza a owl:Class ; rdfs:label \"pizza\" .\n";

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serve(String path, int code, String type, String body) {
        server.createContext(path, exchange -> answer(exchange, code, type, body));
    }

    private static void answer(HttpExchange exchange, int code, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (type != null) {
            exchange.getResponseHeaders().add("Content-Type", type);
        }
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static OWLOntology load(String address, WebOntology.Attempt attempt,
            List<String> notes) {
        return OntologySource.loadLeavingOutMissingImports(OWLManager.createOWLOntologyManager(),
                IRI.create(address), attempt, null, notes);
    }

    @Test
    void anOntologyFileIsRead() {
        serve("/web.ttl", 200, "text/turtle", TURTLE);

        OWLOntology read = load(base + "/web.ttl", null, new ArrayList<String>());

        assertEquals("http://example.org/web",
                read.getOntologyID().getOntologyIRI().get().toString());
        assertEquals(1, read.getClassesInSignature().size());
    }

    /** The case that started this, with the wait cut to a second so the test does not take 30. */
    @Test
    void aServerThatSaysNothingIsGivenUpOnWithAReason() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            List<Socket> held = new ArrayList<Socket>();
            Thread accepting = new Thread(() -> {
                try {
                    while (true) {
                        held.add(silent.accept());
                    }
                } catch (IOException closed) {
                    // The test is over.
                }
            });
            accepting.setDaemon(true);
            accepting.start();

            long started = System.currentTimeMillis();
            RobotException failed = assertThrows(RobotException.class, () -> load(
                    "http://127.0.0.1:" + silent.getLocalPort() + "/ontology/3.0.5",
                    new WebOntology.Attempt(2000, 1000), new ArrayList<String>()));
            long took = System.currentTimeMillis() - started;

            assertTrue(failed.getMessage().contains("accepted the connection but sent nothing for"
                    + " 1 second"), failed.getMessage());
            assertTrue(failed.getMessage().contains("try again later"), failed.getMessage());
            assertTrue(took < 8000, "gave up in " + took + " ms, not after retries");
        }
    }

    @Test
    void stopLetsGoOfASilentServer() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread accepting = new Thread(() -> {
                List<Socket> held = new ArrayList<Socket>();
                try {
                    while (true) {
                        held.add(silent.accept());
                    }
                } catch (IOException closed) {
                    // The test is over.
                }
            });
            accepting.setDaemon(true);
            accepting.start();
            WebOntology.Attempt attempt = new WebOntology.Attempt(2000, 60000);
            Thread stopper = new Thread(() -> {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                    return;
                }
                attempt.stop();
            });

            long started = System.currentTimeMillis();
            stopper.start();
            RobotException stopped = assertThrows(RobotException.class, () -> load(
                    "http://127.0.0.1:" + silent.getLocalPort() + "/onto.ttl", attempt,
                    new ArrayList<String>()));

            assertTrue(stopped.getMessage().startsWith("Stopped"), stopped.getMessage());
            assertTrue(System.currentTimeMillis() - started < 5000);
        }
    }

    /**
     * A documentation page, as the usual generators write it: the files under "Download
     * serialization", an imported ontology on another site, and a broken first link.
     */
    @Test
    void aPageAboutTheOntologyIsFollowedToItsFile() {
        serve("/docs/", 200, "text/html; charset=utf-8", "<!DOCTYPE html><html><body>"
                + "<p>Imported ontologies: <a href=\"http://example.org/other.owl\">other</a></p>"
                + "<p>Download serialization: <a href=\"ontology.owl\">OWL</a> "
                + "<a href=\"ontology.ttl\">TTL</a></p></body></html>");
        serve("/docs/ontology.owl", 404, "text/plain", "gone");
        serve("/docs/ontology.ttl", 200, "text/turtle", TURTLE);
        List<String> notes = new ArrayList<String>();

        OWLOntology read = load(base + "/docs/", null, notes);

        assertEquals(1, read.getClassesInSignature().size());
        assertEquals(1, notes.size(), notes.toString());
        assertTrue(notes.get(0).contains("is a web page about the ontology, so its file "
                + base + "/docs/ontology.ttl was read instead"), notes.get(0));
    }

    @Test
    void aPageThatLinksToNoFileSaysWhatToLookFor() {
        serve("/about", 200, "text/html", "<html><body><a href=\"/team\">Team</a></body></html>");

        RobotException failed = assertThrows(RobotException.class,
                () -> load(base + "/about", null, new ArrayList<String>()));

        assertTrue(failed.getMessage().contains("is a web page, not an ontology file"),
                failed.getMessage());
        assertTrue(failed.getMessage().contains("Download"), failed.getMessage());
    }

    /** Content negotiation: the address answers with the file when asked for an ontology. */
    @Test
    void anAddressThatNegotiatesIsAskedForAnOntologyFirst() {
        server.createContext("/ontology", exchange -> {
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (accept != null && accept.startsWith("text/turtle")) {
                answer(exchange, 200, "text/turtle", TURTLE);
            } else {
                answer(exchange, 200, "text/html", "<html><body>Docs</body></html>");
            }
        });

        assertEquals(1, load(base + "/ontology", null, new ArrayList<String>())
                .getClassesInSignature().size());
    }

    @Test
    void aRedirectIsFollowed() {
        server.createContext("/old", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/new.ttl");
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
        });
        serve("/new.ttl", 200, "text/turtle", TURTLE);

        assertEquals(1, load(base + "/old", null, new ArrayList<String>())
                .getClassesInSignature().size());
    }

    @Test
    void nothingAtTheAddressSaysSo() {
        serve("/gone.owl", 404, "text/plain", "Not here");

        RobotException failed = assertThrows(RobotException.class,
                () -> load(base + "/gone.owl", null, new ArrayList<String>()));

        assertTrue(failed.getMessage().startsWith("There is nothing at " + base + "/gone.owl"),
                failed.getMessage());
    }

    @Test
    void aServerThatDoesNotExistIsNamed() {
        RobotException failed = assertThrows(RobotException.class, () -> load(
                "http://no-such-host.invalid/ontology.owl", null, new ArrayList<String>()));

        assertTrue(failed.getMessage().contains("There is no server called no-such-host.invalid")
                || failed.getMessage().contains("no-such-host.invalid"), failed.getMessage());
    }

    /** An import is read the same way, and one that cannot be read is left out and named. */
    @Test
    void anImportThatCannotBeReadIsLeftOutAndSaidWhy() {
        String main = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<http://example.org/main> a owl:Ontology ;\n"
                + "  owl:imports <" + base + "/fine.ttl> , <" + base + "/slow.ttl> .\n";
        serve("/main.ttl", 200, "text/turtle", main);
        serve("/fine.ttl", 200, "text/turtle", TURTLE);
        server.createContext("/slow.ttl", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                // Answers late either way.
            }
            answer(exchange, 200, "text/turtle", TURTLE);
        });
        List<String> notes = new ArrayList<String>();

        long started = System.currentTimeMillis();
        OWLOntology read = load(base + "/main.ttl", new WebOntology.Attempt(2000, 1000), notes);

        assertTrue(System.currentTimeMillis() - started < 8000);
        assertEquals(2, read.getImportsClosure().size(), "the readable import is there");
        List<IRI> missing = OntologySource.missingImportsOf(read);
        assertEquals(1, missing.size(), missing.toString());
        assertEquals(base + "/slow.ttl", missing.get(0).toString());
        assertTrue(notes.toString().contains("Its import " + base + "/slow.ttl could not be read"),
                notes.toString());
    }

    /** A catalog's local copy wins over the network: nothing is downloaded. */
    @Test
    void anAddressTheManagerMapsToAFileIsReadFromTheFile(@TempDir File folder)
            throws Exception {
        File local = new File(folder, "web.ttl");
        Files.write(local.toPath(), TURTLE.getBytes(StandardCharsets.UTF_8));
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        IRI unreachable = IRI.create("http://no-such-host.invalid/web.ttl");
        manager.getIRIMappers().add(new SimpleIRIMapper(unreachable, IRI.create(local)));

        OWLOntology read = OntologySource.load(manager, unreachable);

        assertNotNull(read);
        assertEquals(1, read.getClassesInSignature().size());
    }

    @Test
    void linksOnTheSameSiteComeFirstThenTheBetterFormat() throws Exception {
        URL page = new URL("https://docs.example.org/onto/index.html");
        String html = "<link rel=\"alternate\" type=\"text/turtle\" href=\"latest\">"
                + "<a href='http://purl.example.com/upper.owl'>an import</a>"
                + "<a href=\"ontology.nt\">NT</a> <a href=\"ontology.ttl\">TTL</a>"
                + "<a href=\"ontology.owl#top\">OWL</a> <a href=\"#section\">x</a>"
                + "<a href=\"style.css\">css</a> <a href=\"mailto:a@b.c\">mail</a>";

        List<String> links = WebOntology.ontologyLinksIn(html, page);

        assertEquals("https://docs.example.org/onto/latest", links.get(0), links.toString());
        assertEquals("https://docs.example.org/onto/ontology.owl#top", links.get(1));
        assertEquals("https://docs.example.org/onto/ontology.ttl", links.get(2));
        assertEquals("https://docs.example.org/onto/ontology.nt", links.get(3));
        assertEquals("http://purl.example.com/upper.owl", links.get(4));
        assertEquals(5, links.size());
        assertFalse(links.toString().contains("style.css"));
    }

    @Test
    void eachRefusalIsSaidInWords() throws Exception {
        URL url = new URL("https://example.org/a.owl");

        assertTrue(WebOntology.describeRefusal(403, "Forbidden", url).contains("needs a login"));
        assertTrue(WebOntology.describeRefusal(503, "Service Unavailable", url)
                .contains("try again later"));
        assertTrue(WebOntology.describeRefusal(429, null, url).contains("Wait a minute"));
    }
}
