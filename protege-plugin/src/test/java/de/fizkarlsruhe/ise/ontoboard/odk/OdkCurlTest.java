package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * curl, as ODK's Makefile calls it and as OntoBoard's engine stands in for it: an ontology to
 * mirror, or a plugin of ROBOT's, fetched to a file of the project. Against a server on this
 * machine and against files; nothing here reaches the network.
 */
class OdkCurlTest {

    private static final byte[] ONTOLOGY = ("Ontology(<https://example.org/served.owl>)\n"
            + "# with a line that is not ASCII: éè\n").getBytes(StandardCharsets.UTF_8);

    @TempDir
    File temp;

    private File folder;
    private final List<String> said = new ArrayList<String>();
    private HttpServer server;
    private String base;
    private final List<String> asked = Collections.synchronizedList(new ArrayList<String>());
    private long pauseBefore;
    /** Each request as the server was asked it: a proxy is asked with the whole address. */
    private final List<String> askedInFull =
            Collections.synchronizedList(new ArrayList<String>());
    private Function<String, String> environmentBefore;
    private final Map<String, String> environment = new HashMap<String, String>();
    /** When the server was first asked for an answer that a test stops. */
    private final AtomicLong askedAt = new AtomicLong();

    @BeforeEach
    void aProjectAndAServer() throws IOException {
        folder = new File(temp, "myont/src/ontology");
        Files.createDirectories(folder.toPath());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String path = exchange.getRequestURI().getPath();
                asked.add(path);
                askedInFull.add(exchange.getRequestURI().toString());
                if (path.equals("/served.owl")) {
                    answer(exchange, 200, ONTOLOGY);
                } else if (path.equals("/moved")) {
                    exchange.getResponseHeaders().add("Location", "/served.owl");
                    answer(exchange, 302, new byte[0]);
                } else if (path.matches("/moved-30[378]")) {
                    exchange.getResponseHeaders().add("Location", "/served.owl");
                    answer(exchange, Integer.parseInt(path.substring("/moved-".length())),
                            new byte[0]);
                } else if (path.equals("/moved-nowhere")) {
                    answer(exchange, 302, new byte[0]);
                } else if (path.equals("/elsewhere")) {
                    exchange.getResponseHeaders().add("Location", base + "/moved");
                    answer(exchange, 301, new byte[0]);
                } else if (path.equals("/circle")) {
                    exchange.getResponseHeaders().add("Location", "/circle");
                    answer(exchange, 302, new byte[0]);
                } else if (path.equals("/busy")) {
                    int times = Collections.frequency(asked, "/busy");
                    if (times <= 2) {
                        answer(exchange, 503, "busy".getBytes(StandardCharsets.UTF_8));
                    } else {
                        answer(exchange, 200, ONTOLOGY);
                    }
                } else if (path.equals("/slow")) {
                    try {
                        Thread.sleep(6000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    answer(exchange, 200, ONTOLOGY);
                } else if (path.equals("/dribble")) {
                    // An answer that takes its time: a little every 50 ms, for 6 s.
                    askedAt.compareAndSet(0, System.currentTimeMillis());
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        for (int i = 0; i < 120; i++) {
                            exchange.getResponseBody().write(new byte[1024]);
                            exchange.getResponseBody().flush();
                            Thread.sleep(50);
                        }
                    } catch (IOException | InterruptedException gone) {
                        // The one who asked has left.
                    }
                    exchange.close();
                } else if (path.equals("/always-busy")) {
                    askedAt.compareAndSet(0, System.currentTimeMillis());
                    answer(exchange, 503, "busy".getBytes(StandardCharsets.UTF_8));
                } else if (path.equals("/needs-a-name")) {
                    exchange.getResponseHeaders().add("Proxy-Authenticate",
                            "Basic realm=\"the house\"");
                    answer(exchange, 407, "who are you".getBytes(StandardCharsets.UTF_8));
                } else if (path.equals("/lost")) {
                    exchange.getResponseHeaders().add("Location", "http://[nowhere");
                    answer(exchange, 302, new byte[0]);
                } else if (path.equals("/to-a-file")) {
                    exchange.getResponseHeaders().add("Location",
                            new File(temp, "not-for-the-web.txt").toURI().toString());
                    answer(exchange, 302, new byte[0]);
                } else {
                    answer(exchange, 404, "<html>nothing here</html>"
                            .getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        server.start();
        pauseBefore = OdkCurl.firstPauseMillis;
        OdkCurl.firstPauseMillis = 5;
        // No test here goes by what this machine's environment happens to name.
        environmentBefore = OdkCurl.environment;
        OdkCurl.environment = environment::get;
    }

    @AfterEach
    void theServerStops() {
        OdkCurl.firstPauseMillis = pauseBefore;
        OdkCurl.environment = environmentBefore;
        server.stop(0);
    }

    private static void answer(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private String whyNot(String line) {
        return RecipeShell.parse(line).whyNot(OdkTools.forProject());
    }

    /** Asked first, as the engine asks; then run in the project's src/ontology. */
    private int run(String line) {
        RecipeShell.Script script = RecipeShell.parse(line);
        assertNull(script.whyNot(OdkTools.forProject()), line);
        return script.run(OdkTools.forProject(), folder, said);
    }

    /** As the engine runs a line of a run the person can stop. */
    private int run(String line, BooleanSupplier stopped) {
        RecipeShell.Script script = RecipeShell.parse(line);
        assertNull(script.whyNot(OdkTools.forProject()), line);
        return script.run(OdkTools.forProject(stopped), folder, said);
    }

    private byte[] bytes(String path) throws IOException {
        return Files.readAllBytes(new File(folder, path).toPath());
    }

    // ------------------------------------------------------------------ what ODK's lines do

    /** The line of ODK's rule for an ontology to mirror, word for word. */
    @Test
    void whatIsAtTheAddressIsSavedAsItIs() throws IOException {
        int status = run("curl -L " + base + "/served.owl --create-dirs -o tmp/ro-download.owl "
                + "--retry 4 --max-time 200");

        assertEquals(0, status, said.toString());
        assertArrayEquals(ONTOLOGY, bytes("tmp/ro-download.owl"));
        assertEquals(Collections.singletonList("/served.owl"), asked);
    }

    /** With -L a redirect is followed, to an address beside the first or a whole one. */
    @Test
    void aRedirectIsFollowed() throws IOException {
        assertEquals(0, run("curl -L " + base + "/elsewhere --create-dirs -o tmp/a.owl "
                + "--retry 4 --max-time 200"), said.toString());

        assertArrayEquals(ONTOLOGY, bytes("tmp/a.owl"));
        assertEquals(java.util.Arrays.asList("/elsewhere", "/moved", "/served.owl"), asked);
    }

    /** ODK's line for a plugin of ROBOT's puts the file before the address. */
    @Test
    void theOptionsMayComeInAnyOrder() throws IOException {
        Files.createDirectories(new File(folder, "tmp/plugins").toPath());

        assertEquals(0, run("curl -L -o tmp/plugins/mine.jar " + base + "/served.owl"),
                said.toString());

        assertArrayEquals(ONTOLOGY, bytes("tmp/plugins/mine.jar"));
    }

    /** As curl: a folder is made only when --create-dirs asks for it. */
    @Test
    void withoutCreateDirsAFolderThatIsNotThereIsNotMade() {
        int status = run("curl -L -o no/such/a.owl " + base + "/served.owl");

        assertNotEquals(0, status);
        assertFalse(new File(folder, "no").exists());
        assertTrue(String.join("\n", said).contains("no/such/a.owl"), said.toString());
    }

    // ------------------------------------------------------------------ what goes wrong

    /**
     * curl itself would save the server's page about the missing file and say nothing, and
     * ROBOT would then fail to read that page as an ontology. Here the line fails, with the
     * address and the server's answer, and no file is left that looks like an ontology.
     */
    @Test
    void whatTheServerRefusesIsSaidAndNotSaved() {
        int status = run("curl -L " + base + "/gone.owl --create-dirs -o tmp/a.owl --retry 4 "
                + "--max-time 200");

        assertNotEquals(0, status);
        assertFalse(new File(folder, "tmp/a.owl").exists());
        String words = String.join("\n", said);
        assertTrue(words.contains("404"), words);
        assertTrue(words.contains(base + "/gone.owl"), words);
        assertEquals(1, asked.size(), "what is not there is not asked for again: " + asked);
    }

    /** --retry N: a server that is busy is asked again, up to N more times. */
    @Test
    void aBusyServerIsAskedAgain() throws IOException {
        assertEquals(0, run("curl -L " + base + "/busy --create-dirs -o tmp/a.owl --retry 4 "
                + "--max-time 200"), said.toString());

        assertArrayEquals(ONTOLOGY, bytes("tmp/a.owl"));
        assertEquals(3, asked.size(), asked.toString());
    }

    @Test
    void andNoMoreOftenThanAsked() {
        int status = run("curl -L " + base + "/busy --create-dirs -o tmp/a.owl --retry 1 "
                + "--max-time 200");

        assertNotEquals(0, status);
        assertEquals(2, asked.size(), asked.toString());
        assertTrue(String.join("\n", said).contains("503"), said.toString());
        assertFalse(new File(folder, "tmp/a.owl").exists());
    }

    /** --max-time S: a server that sends nothing is left after that many seconds. */
    @Test
    void aServerThatSendsNothingIsLeftAfterTheTimeGiven() {
        long started = System.currentTimeMillis();

        int status = run("curl -L " + base + "/slow --create-dirs -o tmp/a.owl --max-time 1");

        long took = System.currentTimeMillis() - started;
        assertNotEquals(0, status);
        assertTrue(took < 5000, "left after " + took + " ms");
        assertTrue(String.join("\n", said).contains("1 second"), said.toString());
        assertFalse(new File(folder, "tmp/a.owl").exists());
    }

    /** And one that does send, but would take longer than that, is left as well. */
    @Test
    void aServerThatSendsTooSlowlyIsLeftAfterTheTimeGiven() {
        long started = System.currentTimeMillis();

        int status = run("curl -L " + base + "/dribble --create-dirs -o tmp/a.owl "
                + "--max-time 1");

        long took = System.currentTimeMillis() - started;
        assertNotEquals(0, status);
        assertTrue(took < 4000, "left after " + took + " ms");
        assertTrue(String.join("\n", said).contains("1 second"), said.toString());
        assertArrayEquals(new String[0], new File(folder, "tmp").list(),
                "neither the file nor the part of it that came");
    }

    @Test
    void anAddressThatGoesRoundInACircleIsLeft() {
        int status = run("curl -L " + base + "/circle --create-dirs -o tmp/a.owl");

        assertNotEquals(0, status);
        assertTrue(String.join("\n", said).contains("redirect"), said.toString());
    }

    /** However the server says that the address has moved. */
    @Test
    void everyKindOfMoveIsFollowed() throws IOException {
        for (int kind : new int[] {303, 307, 308}) {
            said.clear();

            assertEquals(0, run("curl -L " + base + "/moved-" + kind + " --create-dirs "
                    + "-o tmp/" + kind + ".owl"), kind + ": " + said);

            assertArrayEquals(ONTOLOGY, bytes("tmp/" + kind + ".owl"));
        }
    }

    @Test
    void aMoveThatDoesNotSayWhereToIsSaid() {
        int status = run("curl -L " + base + "/moved-nowhere --create-dirs -o tmp/a.owl "
                + "--retry 4");

        assertNotEquals(0, status);
        assertTrue(String.join("\n", said).contains("without saying where to"),
                said.toString());
        assertEquals(Collections.singletonList("/moved-nowhere"), asked);
        assertFalse(new File(folder, "tmp/a.owl").exists());
    }

    /** A move to something that is no address is said once; asking again changes nothing. */
    @Test
    void aMoveToNoAddressIsSaidAndNotAskedAgain() {
        int status = run("curl -L " + base + "/lost --create-dirs -o tmp/a.owl --retry 4");

        assertNotEquals(0, status);
        assertEquals(Collections.singletonList("/lost"), asked);
        assertTrue(String.join("\n", said).contains("http://[nowhere"), said.toString());
        assertFalse(new File(folder, "tmp/a.owl").exists());
    }

    /**
     * As curl: a server cannot send the fetch to a file of this machine. What it would
     * otherwise have is a way to put any file the person can read into the project.
     */
    @Test
    void aMoveToAFileOfThisMachineIsNotFollowed() throws IOException {
        Files.write(new File(temp, "not-for-the-web.txt").toPath(),
                "private".getBytes(StandardCharsets.UTF_8));

        int status = run("curl -L " + base + "/to-a-file --create-dirs -o tmp/a.owl");

        assertNotEquals(0, status);
        assertFalse(new File(folder, "tmp/a.owl").exists());
        assertTrue(String.join("\n", said).contains("neither an http nor an https"),
                said.toString());
    }

    /** Without -L a move is not followed, and no empty file is left in its place. */
    @Test
    void withoutLocationAMoveIsNotFollowed() {
        int status = run("curl " + base + "/moved --create-dirs -o tmp/a.owl");

        assertNotEquals(0, status);
        assertEquals(Collections.singletonList("/moved"), asked);
        assertTrue(String.join("\n", said).contains("-L"), said.toString());
        assertFalse(new File(folder, "tmp/a.owl").exists());
    }

    // ------------------------------------------------------------------ a proxy

    /**
     * curl reads its proxy from the environment, and ODK's container is given the same
     * variables: so the fetch goes the way the machine's other programs go. The proxy is
     * asked for the whole address; the server it names need not be known here at all.
     */
    @Test
    void theProxyTheEnvironmentNamesIsAsked() throws IOException {
        environment.put("http_proxy", base);

        int status = run("curl -L http://ontologies.invalid/served.owl --create-dirs "
                + "-o tmp/a.owl --retry 4 --max-time 200");

        assertEquals(0, status, said.toString());
        assertArrayEquals(ONTOLOGY, bytes("tmp/a.owl"));
        assertEquals(Collections.singletonList("http://ontologies.invalid/served.owl"),
                askedInFull);
    }

    /** Written as people write it: without a scheme, with a slash, with a name in it. */
    @Test
    void theProxyIsReadHoweverItIsWritten() throws IOException {
        String hostAndPort = base.substring("http://".length());
        int n = 0;
        for (String written : new String[] {hostAndPort, base + "/",
            "http://someone:secret@" + hostAndPort}) {
            environment.put("HTTP_PROXY", written);
            said.clear();

            int status = run("curl -L http://ontologies.invalid/served.owl --create-dirs "
                    + "-o tmp/" + (++n) + ".owl");

            assertEquals(0, status, written + ": " + said);
            assertArrayEquals(ONTOLOGY, bytes("tmp/" + n + ".owl"));
        }
        assertEquals(3, askedInFull.size(), askedInFull.toString());
    }

    /** all_proxy is for every kind of address that has none of its own. */
    @Test
    void allProxyIsForAnAddressWithoutOneOfItsOwn() throws IOException {
        environment.put("all_proxy", base);

        assertEquals(0, run("curl -L http://ontologies.invalid/served.owl --create-dirs "
                + "-o tmp/a.owl"), said.toString());

        assertArrayEquals(ONTOLOGY, bytes("tmp/a.owl"));
    }

    /** no_proxy: a host named there, or one of a domain named there, is asked itself. */
    @Test
    void aHostNoProxyNamesIsAskedItself() throws IOException {
        // A proxy nothing listens at: whoever asks it fails.
        environment.put("http_proxy", "http://127.0.0.1:1");
        environment.put("no_proxy", "example.org, 127.0.0.1");

        assertEquals(0, run("curl -L " + base + "/served.owl --create-dirs -o tmp/a.owl"),
                said.toString());

        assertArrayEquals(ONTOLOGY, bytes("tmp/a.owl"));
        assertEquals(Collections.singletonList("/served.owl"), askedInFull,
                "asked as a server is asked, not as a proxy");
    }

    /** A variable that is there and holds nothing names no proxy. */
    @Test
    void anEmptyProxyVariableNamesNone() throws IOException {
        environment.put("http_proxy", " ");

        assertEquals(0, run("curl -L " + base + "/served.owl --create-dirs -o tmp/a.owl"),
                said.toString());

        assertArrayEquals(ONTOLOGY, bytes("tmp/a.owl"));
    }

    @Test
    void noProxyNamesDomainsAndEverything() {
        assertTrue(OdkCurl.isExcepted("purl.obolibrary.org", "obolibrary.org"));
        assertTrue(OdkCurl.isExcepted("purl.obolibrary.org", "localhost,.obolibrary.org"));
        assertTrue(OdkCurl.isExcepted("OBOLibrary.org", "obolibrary.org"));
        assertTrue(OdkCurl.isExcepted("anything.example", "*"));
        assertTrue(OdkCurl.isExcepted("purl.obolibrary.org", "purl.obolibrary.org:80"));
        assertTrue(OdkCurl.isExcepted("[::1]", "localhost,::1"));
        assertTrue(OdkCurl.isExcepted("[::1]", "[::1]"));
        assertFalse(OdkCurl.isExcepted("notobolibrary.org", "obolibrary.org"));
        assertFalse(OdkCurl.isExcepted("purl.obolibrary.org", ""));
        assertFalse(OdkCurl.isExcepted("purl.obolibrary.org", null));
    }

    /** A proxy that asks who is asking: said as the proxy's answer, without its password. */
    @Test
    void aProxyThatAsksForANameIsSaid() {
        environment.put("http_proxy", "http://someone:secret@"
                + base.substring("http://".length()));

        int status = run("curl -L http://ontologies.invalid/needs-a-name --create-dirs "
                + "-o tmp/a.owl --retry 4");

        String all = String.join("\n", said);
        assertNotEquals(0, status);
        assertTrue(all.contains("proxy") && all.contains("407"), all);
        assertFalse(all.contains("secret"), "a password is never said: " + all);
        assertEquals(1, askedInFull.size(), "and it is not asked again: " + askedInFull);
        assertFalse(new File(folder, "tmp/a.owl").exists());
    }

    /**
     * The same before an https server, where the proxy is asked to open a way through and
     * Java says its refusal in its own words: said as the proxy's, and not asked again.
     */
    @Test
    void aProxyThatAsksForANameBeforeAnHttpsServerIsSaidToo() throws Exception {
        final ServerSocket proxy = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        final AtomicInteger knocks = new AtomicInteger();
        Thread answering = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        try (Socket one = proxy.accept()) {
                            knocks.incrementAndGet();
                            BufferedReader in = new BufferedReader(new InputStreamReader(
                                    one.getInputStream(), StandardCharsets.ISO_8859_1));
                            String line = in.readLine();
                            while (line != null && !line.isEmpty()) {
                                line = in.readLine();
                            }
                            one.getOutputStream().write(("HTTP/1.1 407 Proxy Authentication "
                                    + "Required\r\nProxy-Authenticate: Basic realm=\"the "
                                    + "house\"\r\nContent-Length: 0\r\nConnection: close"
                                    + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                            one.getOutputStream().flush();
                        }
                    }
                } catch (IOException closed) {
                    // The test is over.
                }
            }
        });
        answering.setDaemon(true);
        answering.start();
        try {
            environment.put("https_proxy", "http://someone:secret@127.0.0.1:"
                    + proxy.getLocalPort());

            int status = run("curl -L https://ontologies.invalid/a.owl --create-dirs "
                    + "-o tmp/a.owl --retry 4");

            String all = String.join("\n", said);
            assertNotEquals(0, status);
            assertTrue(all.contains("the proxy 127.0.0.1 asks for a name"), all);
            assertFalse(all.contains("secret"), all);
            assertTrue(knocks.get() >= 1 && knocks.get() <= 2, "asked, and not asked "
                    + "again for --retry's sake: " + knocks.get() + " times");
        } finally {
            proxy.close();
        }
    }

    /** What is not a proxy's address is said, and the fetch does not go another way. */
    @Test
    void aProxyThatIsNotReadIsSaid() {
        environment.put("http_proxy", "https://someone:secret@proxy.invalid:8443");

        int status = run("curl -L " + base + "/served.owl --create-dirs -o tmp/a.owl");

        String all = String.join("\n", said);
        assertNotEquals(0, status);
        assertTrue(all.contains("proxy.invalid") && all.contains("http_proxy"), all);
        assertFalse(all.contains("secret"), all);
        assertTrue(asked.isEmpty(), "the server was not asked past the proxy: " + asked);
    }

    // ------------------------------------------------------------------ a stop

    /** The person stops the run while a file comes in: the fetch ends, and leaves nothing. */
    @Test
    void aFetchThatIsStoppedEndsAtOnceAndLeavesNothing() {
        int status = run("curl -L " + base + "/dribble --create-dirs -o tmp/a.owl "
                + "--retry 4 --max-time 200", () -> asked.contains("/dribble"));

        long took = System.currentTimeMillis() - askedAt.get();
        assertNotEquals(0, status);
        assertTrue(took < 3000, "ended " + took + " ms after the server was asked");
        assertEquals(1, Collections.frequency(asked, "/dribble"), "not asked again");
        assertTrue(String.join("\n", said).contains("stopped"), said.toString());
        assertArrayEquals(new String[0], new File(folder, "tmp").list(),
                "neither the file nor a part of it");
    }

    /** Nor is a move followed once the run is stopped. */
    @Test
    void aStopEndsTheFollowingOfMoves() {
        int status = run("curl -L " + base + "/circle --create-dirs -o tmp/a.owl",
                () -> asked.size() >= 3);

        assertNotEquals(0, status);
        assertEquals(3, asked.size(), asked.toString());
        assertTrue(String.join("\n", said).contains("stopped"), said.toString());
    }

    /** After a stop a busy server is not asked again, whatever --retry says. */
    @Test
    void aBusyServerIsNotAskedAgainOnceTheRunIsStopped() {
        OdkCurl.firstPauseMillis = 2000;

        int status = run("curl -L " + base + "/always-busy --create-dirs -o tmp/a.owl "
                + "--retry 4 --max-time 200", () -> asked.contains("/always-busy"));

        long took = System.currentTimeMillis() - askedAt.get();
        assertNotEquals(0, status);
        assertEquals(1, Collections.frequency(asked, "/always-busy"), asked.toString());
        assertTrue(took < 1500, "did not wait to ask again: " + took + " ms");
    }

    /** A stop asked for while it waits to ask again ends the wait. */
    @Test
    void aStopEndsTheWaitBeforeAskingAgain() {
        OdkCurl.firstPauseMillis = 4000;

        int status = run("curl -L " + base + "/always-busy --create-dirs -o tmp/a.owl "
                + "--retry 4 --max-time 200", () -> {
                    // Not yet when the server has just answered; 300 ms into the wait.
                    if (askedAt.get() == 0) {
                        return false;
                    }
                    return System.currentTimeMillis() - askedAt.get() > 300;
                });

        long took = System.currentTimeMillis() - askedAt.get();
        assertNotEquals(0, status);
        assertEquals(1, Collections.frequency(asked, "/always-busy"), asked.toString());
        assertTrue(took < 2500, "the wait of 4 s was ended: " + took + " ms");
    }

    @Test
    void aServerThatIsNotThereIsSaid() throws IOException {
        // A port nothing listens on: this server's own, once it has stopped.
        server.stop(0);

        int status = run("curl -L " + base + "/served.owl --create-dirs -o tmp/a.owl");

        assertNotEquals(0, status);
        assertTrue(String.join("\n", said).contains("nothing answers at 127.0.0.1"),
                said.toString());
        assertFalse(new File(folder, "tmp/a.owl").exists());
    }

    // ------------------------------------------------------------------ files

    /** curl reads a file: address as any other; so a mirror can be a file on this machine. */
    @Test
    void aFileAddressIsRead() throws IOException {
        File upstream = new File(temp, "upstream/served.owl");
        Files.createDirectories(upstream.getParentFile().toPath());
        Files.write(upstream.toPath(), ONTOLOGY);
        String asJavaWritesIt = upstream.toURI().toString();
        String withThreeSlashes = asJavaWritesIt.replaceFirst("^file:/(?!/)", "file:///");

        assertEquals(0, run("curl -L " + asJavaWritesIt + " --create-dirs -o tmp/a.owl "
                + "--retry 4 --max-time 200"), said.toString());
        assertEquals(0, run("curl -L " + withThreeSlashes + " --create-dirs -o tmp/b.owl "
                + "--retry 4 --max-time 200"), said.toString());

        assertArrayEquals(ONTOLOGY, bytes("tmp/a.owl"));
        assertArrayEquals(ONTOLOGY, bytes("tmp/b.owl"));
    }

    /** As people write such an address: with a blank in it, or with the machine's name. */
    @Test
    void aFileAddressWithABlankOrAHostIsRead() throws IOException {
        File upstream = new File(temp, "with blank/served.owl");
        Files.createDirectories(upstream.getParentFile().toPath());
        Files.write(upstream.toPath(), ONTOLOGY);
        String path = upstream.getAbsolutePath().replace('\\', '/');
        path = path.startsWith("/") ? path : "/" + path;

        assertEquals(0, run("curl -L \"file://" + path + "\" --create-dirs -o tmp/a.owl"),
                said.toString());
        assertEquals(0, run("curl -L \"file://localhost" + path + "\" --create-dirs "
                + "-o tmp/b.owl"), said.toString());

        assertArrayEquals(ONTOLOGY, bytes("tmp/a.owl"));
        assertArrayEquals(ONTOLOGY, bytes("tmp/b.owl"));
    }

    @Test
    void aFileThatIsNotThereIsSaid() {
        String missing = new File(temp, "upstream/nothing.owl").toURI().toString();

        int status = run("curl -L " + missing + " --create-dirs -o tmp/a.owl");

        assertNotEquals(0, status);
        assertTrue(String.join("\n", said).contains("nothing.owl"), said.toString());
        assertFalse(new File(folder, "tmp/a.owl").exists());
    }

    // ------------------------------------------------------------------ what is not read

    /** What ODK's Makefile does not ask of curl is refused by name, before anything runs. */
    @Test
    void anOptionThatIsNotReadIsNamed() {
        String posting = whyNot("curl -s -X POST " + base + "/x -o tmp/a.json");
        assertNotNull(posting);
        assertTrue(posting.contains("'-s' is not among them"), posting);

        String nowhere = whyNot("curl -L " + base + "/served.owl");
        assertNotNull(nowhere, "curl would print the file; no recipe of ODK's wants that");
        assertTrue(nowhere.contains("-o"), nowhere);

        String other = whyNot("curl -L ftp://example.org/a.owl -o tmp/a.owl");
        assertNotNull(other);
        assertTrue(other.contains("ftp"), other);

        String many = whyNot("curl -L " + base + "/a.owl -o tmp/a.owl --retry many");
        assertNotNull(many);
        assertTrue(many.contains("not a whole number"), many);

        String two = whyNot("curl -L " + base + "/a.owl " + base + "/b.owl -o tmp/a.owl");
        assertNotNull(two);
        assertTrue(two.contains("two addresses"), two);

        String cut = whyNot("curl -L " + base + "/a.owl -o");
        assertNotNull(cut);
        assertTrue(cut.contains("'-o' nothing"), cut);
        assertNotNull(whyNot("curl -L -o tmp/a.owl"), "no address");
    }

    /** As every command of a recipe run here: it writes inside the project only. */
    @Test
    void itWritesInsideTheProjectOnly() {
        String outside = whyNot("curl -L " + base + "/served.owl -o ../../../a.owl");

        assertNotNull(outside);
        assertTrue(outside.contains("outside the project"), outside);
    }

    @Test
    void curlIsAmongTheProgramsOfAProject() {
        assertTrue(OdkTools.forProject().containsKey("curl"));
        assertNull(whyNot("curl -L http://purl.obolibrary.org/obo/ro.owl --create-dirs "
                + "-o tmp/ro-download.owl --retry 4 --max-time 200"));
    }
}
