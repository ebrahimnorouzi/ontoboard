package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyAlreadyExistsException;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Reading an ontology from a web address: with a limit on how long a silent server is waited
 * for, a way to stop, a plain reason when it fails, and the ontology's own file found when the
 * address is a page about it.
 *
 * <p><b>Why OntoBoard reads the address itself instead of handing it to the OWL API.</b> Measured
 * against a server that accepts the connection and then sends nothing - which is what a published
 * ontology's own server was doing when a user tried to find terms in it - the OWL API's download
 * waits 20 seconds, tries again five times, and starts over for each format it guesses at. Find
 * terms sat at "Loading..." for minutes with no way to stop it, and then said only that the load
 * failed. Here a silent server is given up on after {@link #SILENT_SECONDS}, the dialog's Stop
 * closes the connection, and each way a download fails becomes a sentence that says what to do.
 * Imports go through the same reader, so one dead import server cannot hold the load up either.
 *
 * <p><b>Why a web page is followed.</b> Many ontology addresses answer a browser with their
 * documentation, and people paste the address their browser showed them. The pages the usual
 * documentation tools generate link to the ontology's files ("Download serialization: OWL, TTL"),
 * so a page's links to ontology files are tried, the ones on the page's own site first.
 *
 * <p>Pure Java and OWL API; no Prot&eacute;g&eacute; types and no Swing.
 */
public final class WebOntology {

    /** Seconds to wait for a server to accept the connection. */
    public static final int CONNECT_SECONDS = 15;

    /** Seconds a server may stay silent, before its first byte or between two, before giving up. */
    public static final int SILENT_SECONDS = 30;

    /** Redirects followed before calling it a loop. */
    static final int MAX_REDIRECTS = 10;

    /** Links tried on a page about an ontology before giving up on it. */
    static final int MAX_LINKS_TRIED = 4;

    /**
     * Ontology formats first, Turtle first among them because it is what most servers that
     * negotiate offer; HTML last but not refused, so a server that only has a page sends the page
     * - which is then followed - rather than an error.
     */
    static final String ACCEPT = "text/turtle, application/rdf+xml;q=0.95, "
            + "application/owl+xml;q=0.9, text/owl-functional;q=0.85, application/n-triples;q=0.8, "
            + "application/ld+json;q=0.75, text/owl-manchester;q=0.7, application/xml;q=0.5, "
            + "text/xml;q=0.4, text/html;q=0.3, */*;q=0.1";

    /** The endings of ontology files, best first: what a page's links are judged by. */
    private static final String[] ENDINGS = {".owl", ".ttl", ".rdf", ".owx", ".ofn", ".omn",
        ".jsonld", ".nt", ".n3", ".obo", ".xml"};

    private static final Pattern LINK = Pattern.compile(
            "<(a|link)\\b([^>]*?)\\bhref\\s*=\\s*(\"([^\"]*)\"|'([^']*)')([^>]*)>",
            Pattern.CASE_INSENSITIVE);

    private WebOntology() {
    }

    /** What a load is doing, for a dialog to show while it waits. */
    public interface Progress {
        /** Called from the loading thread, with a sentence such as "Waiting for host...". */
        void said(String what);
    }

    /** A load in progress, which another thread can stop. */
    public static final class Attempt {
        private final int connectMillis;
        private final int silentMillis;
        private volatile boolean stopped;
        private volatile URLConnection open;

        /** With the usual limits, {@link #CONNECT_SECONDS} and {@link #SILENT_SECONDS}. */
        public Attempt() {
            this(CONNECT_SECONDS * 1000, SILENT_SECONDS * 1000);
        }

        Attempt(int connectMillis, int silentMillis) {
            this.connectMillis = connectMillis;
            this.silentMillis = silentMillis;
        }

        /** Stops the load: the open connection is closed, so even a silent server lets go. */
        public void stop() {
            stopped = true;
            URLConnection connection = open;
            if (connection instanceof HttpURLConnection) {
                ((HttpURLConnection) connection).disconnect();
            }
        }

        public boolean isStopped() {
            return stopped;
        }

        /** How long a silent server is waited for, in seconds, for a dialog to say so. */
        public int getSilentSeconds() {
            return Math.max(1, silentMillis / 1000);
        }
    }

    /** Whether this IRI is one this class reads: an http or https address. */
    public static boolean isWebAddress(IRI iri) {
        String scheme = iri == null ? null : iri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /**
     * The ontology at this web address, its imports read the same way.
     *
     * @param notes where a sentence worth showing the user is added - that the address was a page
     *     and which of its links was read
     * @throws RobotException saying why, in words that say what to do about it
     */
    public static OWLOntology load(OWLOntologyManager manager, IRI address, Attempt attempt,
            Progress progress, List<String> notes) {
        Attempt running = attempt == null ? new Attempt() : attempt;
        Progress telling = progress == null ? what -> { } : progress;
        List<File> downloaded = new ArrayList<File>();
        Downloads imports = new Downloads(running, telling, downloaded, notes);
        manager.getIRIMappers().add(imports);
        try {
            Fetched document = ontologyDocumentAt(address.toString(), running, telling,
                    downloaded, notes);
            telling.said("Reading the ontology" + (document.size > 0 ? " ("
                    + megabytes(document.size) + ")" : "") + "...");
            OWLOntologyLoaderConfiguration configuration = manager.getOntologyLoaderConfiguration()
                    .setConnectionTimeout(running.silentMillis).setRetriesToAttempt(1);
            OWLOntology ontology;
            try {
                ontology = manager.loadOntologyFromOntologyDocument(
                        new FileDocumentSource(document.file, null, document.mimeType()),
                        configuration);
            } catch (OWLOntologyAlreadyExistsException already) {
                ontology = manager.getOntology(already.getOntologyID());
            }
            if (running.isStopped()) {
                throw stoppedOn(address.toString());
            }
            // An import that could not be read was given a stand-in. Taken out again, so the
            // import is declared and not loaded - the missing import it is.
            for (OWLOntology part : new ArrayList<OWLOntology>(manager.getOntologies())) {
                if (imports.unread.contains(manager.getOntologyDocumentIRI(part))) {
                    manager.removeOntology(part);
                }
            }
            // Where it came from, as the address that was given: what findAlreadyLoaded matches
            // the next time somebody types it.
            manager.setOntologyDocumentIRI(ontology, address);
            return ontology;
        } catch (RobotException failed) {
            throw failed;
        } catch (Exception failed) {
            if (running.isStopped()) {
                throw stoppedOn(address.toString());
            }
            throw new RobotException(address + " was downloaded, but it is not an ontology "
                    + "OntoBoard can read: " + firstLineOf(failed), failed);
        } finally {
            manager.getIRIMappers().remove(imports);
            for (File file : downloaded) {
                if (!file.delete()) {
                    file.deleteOnExit();
                }
            }
        }
    }

    /**
     * Imports, read through the same downloader.
     *
     * <p>An IRI mapper, because that is the one place the OWL API asks before it downloads an
     * import itself. One that cannot be read is mapped to a stand-in, not handed back: handing it
     * back would let the OWL API's own download - the one with five retries - try again. The
     * stand-in is removed after the load, which leaves the import declared and missing.
     */
    private static final class Downloads implements OWLOntologyIRIMapper {
        private static final long serialVersionUID = 1L;
        private final transient Attempt attempt;
        private final transient Progress progress;
        private final transient List<File> downloaded;
        private final transient List<String> notes;
        private final transient Map<IRI, IRI> read = new LinkedHashMap<IRI, IRI>();
        private final transient java.util.Set<IRI> unread = new java.util.HashSet<IRI>();

        Downloads(Attempt attempt, Progress progress, List<File> downloaded, List<String> notes) {
            this.attempt = attempt;
            this.progress = progress;
            this.downloaded = downloaded;
            this.notes = notes;
        }

        @Override
        public IRI getDocumentIRI(IRI ontologyIri) {
            if (!isWebAddress(ontologyIri)) {
                return null;
            }
            if (read.containsKey(ontologyIri)) {
                return read.get(ontologyIri);
            }
            IRI local;
            try {
                if (attempt.isStopped()) {
                    throw stoppedOn(ontologyIri.toString());
                }
                local = IRI.create(ontologyDocumentAt(ontologyIri.toString(), attempt, progress,
                        downloaded, null).file);
            } catch (RobotException unreadable) {
                if (notes != null) {
                    notes.add("Its import " + ontologyIri + " could not be read: "
                            + unreadable.getMessage());
                }
                local = IRI.create(standInFor(ontologyIri, downloaded));
                unread.add(local);
            }
            read.put(ontologyIri, local);
            return local;
        }
    }

    /** A downloaded document, where it ended up and what the server said it was. */
    static final class Fetched {
        final File file;
        final URL from;
        final String contentType;
        final long size;

        Fetched(File file, URL from, String contentType, long size) {
            this.file = file;
            this.from = from;
            this.contentType = contentType;
            this.size = size;
        }

        /** The media type without its parameters, or null for one that says nothing. */
        String mimeType() {
            if (contentType == null) {
                return null;
            }
            String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            return type.isEmpty() || "text/plain".equals(type)
                    || "application/octet-stream".equals(type) ? null : type;
        }

        boolean isPage() throws IOException {
            String type = mimeType();
            if (type != null && (type.equals("text/html")
                    || type.equals("application/xhtml+xml"))) {
                return true;
            }
            String start = startOf(file).toLowerCase(Locale.ROOT);
            return start.startsWith("<!doctype html") || start.startsWith("<html");
        }
    }

    /**
     * The ontology document at an address: the address itself, or the first file a page at it
     * links to that is not a page too.
     */
    static Fetched ontologyDocumentAt(String address, Attempt attempt, Progress progress,
            List<File> downloaded, List<String> notes) {
        Fetched first = fetch(address, attempt, progress, downloaded);
        try {
            if (!first.isPage()) {
                return first;
            }
        } catch (IOException unreadable) {
            throw new RobotException("Could not read what " + hostOf(address) + " sent: "
                    + unreadable.getMessage(), unreadable);
        }
        List<String> links;
        try {
            links = ontologyLinksIn(new String(Files.readAllBytes(first.file.toPath()),
                    StandardCharsets.UTF_8), first.from);
        } catch (IOException unreadable) {
            throw new RobotException("Could not read the page at " + address + ": "
                    + unreadable.getMessage(), unreadable);
        }
        if (links.isEmpty()) {
            throw new RobotException(address + " is a web page, not an ontology file, and it does "
                    + "not link to anything that looks like one (.owl, .ttl, .rdf and the like). "
                    + "On that page, look for a link to the ontology's file - often under "
                    + "\"Download\" - and give that address instead.");
        }
        RobotException last = null;
        for (String link : links.subList(0, Math.min(MAX_LINKS_TRIED, links.size()))) {
            try {
                Fetched linked = fetch(link, attempt, progress, downloaded);
                if (linked.isPage()) {
                    continue;
                }
                if (notes != null) {
                    notes.add(address + " is a web page about the ontology, so its file " + link
                            + " was read instead.");
                }
                return linked;
            } catch (IOException unreadable) {
                last = new RobotException(unreadable.getMessage(), unreadable);
            } catch (RobotException failed) {
                if (attempt.isStopped()) {
                    throw failed;
                }
                last = failed;
            }
        }
        throw new RobotException(address + " is a web page about the ontology. It links to "
                + links.get(0) + (last == null ? ", but that is a page too."
                        : ", but that could not be read either: " + last.getMessage()));
    }

    /**
     * The links on a page that look like ontology files, resolved against the page's address:
     * those on the page's own site first, then by how good a format they are.
     *
     * <p>A page about an ontology also links to the ontologies it imports, which would be the
     * wrong thing to read - hence the page's own site first.
     */
    static List<String> ontologyLinksIn(String page, URL base) {
        List<String[]> found = new ArrayList<String[]>();
        Matcher link = LINK.matcher(page);
        while (link.find()) {
            String href = link.group(4) != null ? link.group(4) : link.group(5);
            String attributes = (link.group(2) + " " + link.group(6)).toLowerCase(Locale.ROOT);
            if (href == null || href.trim().isEmpty() || href.startsWith("#")
                    || href.toLowerCase(Locale.ROOT).startsWith("mailto:")) {
                continue;
            }
            int ending = endingRank(href);
            boolean alternate = "link".equalsIgnoreCase(link.group(1))
                    && attributes.contains("alternate") && (attributes.contains("rdf")
                            || attributes.contains("turtle") || attributes.contains("owl")
                            || attributes.contains("n-triples") || attributes.contains("ld+json"));
            if (ending < 0 && !alternate) {
                continue;
            }
            try {
                URL resolved = new URL(base, href.trim().replace("&amp;", "&"));
                if (!"http".equalsIgnoreCase(resolved.getProtocol())
                        && !"https".equalsIgnoreCase(resolved.getProtocol())) {
                    continue;
                }
                boolean sameSite = resolved.getHost().equalsIgnoreCase(base.getHost());
                found.add(new String[] {resolved.toString(), (sameSite ? "0" : "1")
                        + (alternate ? "0" : String.format(Locale.ROOT, "%02d", 1 + ending))});
            } catch (MalformedURLException notALink) {
                continue;
            }
        }
        java.util.Collections.sort(found, (a, b) -> a[1].compareTo(b[1]));
        List<String> links = new ArrayList<String>();
        for (String[] each : found) {
            if (!links.contains(each[0])) {
                links.add(each[0]);
            }
        }
        return links;
    }

    /** Where in {@link #ENDINGS} the link's path ends, or -1 for a link to something else. */
    private static int endingRank(String href) {
        String path = href.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
        for (int at = 0; at < ENDINGS.length; at++) {
            if (path.endsWith(ENDINGS[at])) {
                return at;
            }
        }
        return -1;
    }

    /**
     * Downloads one address to a temporary file, following redirects between http and https,
     * which Java's own redirect handling refuses to do.
     */
    static Fetched fetch(String address, Attempt attempt, Progress progress,
            List<File> downloaded) {
        URL url;
        try {
            url = new URL(address);
        } catch (MalformedURLException notAnAddress) {
            throw new RobotException("\"" + address + "\" is not a web address.", notAnAddress);
        }
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (attempt.isStopped()) {
                throw stoppedOn(address);
            }
            String host = url.getHost();
            HttpURLConnection connection;
            try {
                connection = (HttpURLConnection) url.openConnection();
            } catch (IOException | ClassCastException cannotOpen) {
                throw new RobotException("Could not open " + url + ": " + cannotOpen.getMessage(),
                        cannotOpen);
            }
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(attempt.connectMillis);
            connection.setReadTimeout(attempt.silentMillis);
            connection.setRequestProperty("Accept", ACCEPT);
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("User-Agent", "OntoBoard (a Protege plugin)");
            attempt.open = connection;
            try {
                progress.said("Connecting to " + host + "...");
                try {
                    connection.connect();
                } catch (SocketTimeoutException slow) {
                    throw new RobotException(host + " did not accept a connection within "
                            + Math.max(1, attempt.connectMillis / 1000) + " seconds. Check the "
                            + "address, and your network connection.", slow);
                }
                progress.said("Waiting for " + host + " to answer...");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new RobotException(host + " answered " + code + " (a redirect) for "
                                + url + " without saying where to.");
                    }
                    url = new URL(url, location.trim());
                    continue;
                }
                if (code >= 400) {
                    throw new RobotException(describeRefusal(code, connection.getResponseMessage(),
                            url));
                }
                return save(connection, url, attempt, progress, downloaded);
            } catch (RobotException failed) {
                throw attempt.isStopped() ? stoppedOn(address) : failed;
            } catch (IOException failed) {
                if (attempt.isStopped()) {
                    throw stoppedOn(address);
                }
                throw new RobotException(describeFailure(failed, host, attempt), failed);
            } finally {
                attempt.open = null;
            }
        }
        throw new RobotException(address + " redirects more than " + MAX_REDIRECTS
                + " times - it goes round in a circle, so nothing was read.");
    }

    private static Fetched save(HttpURLConnection connection, URL url, Attempt attempt,
            Progress progress, List<File> downloaded) throws IOException {
        File file = File.createTempFile("ontoboard-download-", ".tmp");
        file.deleteOnExit();
        downloaded.add(file);
        String host = url.getHost();
        long total = connection.getContentLengthLong();
        long size = 0;
        long told = 0;
        InputStream body = connection.getInputStream();
        if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
            body = new GZIPInputStream(body);
        }
        try (InputStream in = body; OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (attempt.isStopped()) {
                    throw stoppedOn(url.toString());
                }
                out.write(buffer, 0, read);
                size += read;
                if (size - told >= 512 * 1024) {
                    told = size;
                    progress.said("Downloading from " + host + ": " + megabytes(size)
                            + (total > 0 ? " of " + megabytes(total) : "") + "...");
                }
            }
        }
        return new Fetched(file, url, connection.getContentType(), size);
    }

    /** "There is nothing at ..." and the like, for an HTTP status that is a refusal. */
    static String describeRefusal(int code, String message, URL url) {
        String status = code + (message == null || message.trim().isEmpty() ? ""
                : " " + message.trim());
        String host = url.getHost();
        if (code == 404 || code == 410) {
            return "There is nothing at " + url + " (" + host + " answered " + status + "). Check "
                    + "the address - an ontology's page usually shows the address of its file.";
        }
        if (code == 401 || code == 403 || code == 407) {
            return host + " refused to give out " + url + " (" + status + "). It needs a login, "
                    + "or does not allow downloads from programs.";
        }
        if (code == 429) {
            return host + " asked for fewer requests (" + status + "). Wait a minute and try "
                    + "again.";
        }
        if (code >= 500) {
            return host + " had a problem answering for " + url + " (" + status + "). The server "
                    + "may be down or overloaded - try again later, or read a copy of the "
                    + "ontology instead.";
        }
        return host + " refused " + url + " (" + status + ").";
    }

    /** What went wrong, for the ways a download fails before any answer arrives. */
    static String describeFailure(IOException failed, String host, Attempt attempt) {
        if (failed instanceof UnknownHostException) {
            return "There is no server called " + host + ". Check the address - or the network "
                    + "connection, if every address fails.";
        }
        if (failed instanceof SocketTimeoutException) {
            int seconds = attempt.getSilentSeconds();
            return host + " accepted the connection but sent nothing for " + seconds
                    + (seconds == 1 ? " second" : " seconds") + ", so OntoBoard stopped waiting. "
                    + "The server may be down or overloaded: try again later, or read a copy of "
                    + "the ontology instead - a file you downloaded, or the one in its repository.";
        }
        if (failed instanceof ConnectException) {
            return host + " refused the connection - nothing answers at that address, or a "
                    + "firewall is in the way.";
        }
        if (failed instanceof javax.net.ssl.SSLException) {
            return "Could not make a secure connection to " + host + ": " + failed.getMessage();
        }
        if (failed instanceof SocketException) {
            return host + " closed the connection without answering (" + failed.getMessage()
                    + "). The server may be down or overloaded: try again later, or read a copy "
                    + "of the ontology instead.";
        }
        return "Could not read from " + host + ": " + failed.getMessage();
    }

    private static RobotException stoppedOn(String address) {
        return new RobotException("Stopped - nothing was read from " + address + ".");
    }

    private static String hostOf(String address) {
        try {
            return new URL(address).getHost();
        } catch (MalformedURLException notAnAddress) {
            return address;
        }
    }

    /**
     * A file holding only an ontology header with the import's own IRI.
     *
     * <p>Named, not empty: an empty file reads as an anonymous ontology, and the OWL API's RDF
     * parsers respond to an anonymous import by copying it into the importing ontology and
     * deleting the import declaration - so the import that could not be read would vanish, and
     * nothing would say it was missing.
     */
    private static File standInFor(IRI ontologyIri, List<File> downloaded) {
        StringBuilder escaped = new StringBuilder();
        for (char c : ontologyIri.toString().toCharArray()) {
            if (c <= ' ' || "<>\"{}|^`\\".indexOf(c) >= 0) {
                escaped.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
            } else {
                escaped.append(c);
            }
        }
        try {
            File file = File.createTempFile("ontoboard-unread-", ".ttl");
            file.deleteOnExit();
            downloaded.add(file);
            Files.write(file.toPath(), ("<" + escaped + "> "
                    + "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
                    + "<http://www.w3.org/2002/07/owl#Ontology> .\n")
                    .getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException cannotCreate) {
            throw new RobotException("Could not create a temporary file: "
                    + cannotCreate.getMessage(), cannotCreate);
        }
    }

    /** The first characters of a file, without leading whitespace or a byte order mark. */
    private static String startOf(File file) throws IOException {
        byte[] head = new byte[512];
        int length;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            length = Math.max(0, in.read(head));
        }
        String text = new String(head, 0, length, StandardCharsets.UTF_8);
        return text.replace("\uFEFF", "").trim();
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String firstLineOf(Throwable failed) {
        String message = failed.getMessage() == null ? failed.getClass().getSimpleName()
                : failed.getMessage();
        String line = message.split("\\R", 2)[0].trim();
        return line.length() > 300 ? line.substring(0, 300) + "..." : line;
    }
}
