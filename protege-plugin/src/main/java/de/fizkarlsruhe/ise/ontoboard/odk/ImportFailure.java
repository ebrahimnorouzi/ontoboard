package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Why fetching an import's source stopped a run of ODK's Makefile: which import, from which
 * address, and whether the network, the server or what came was why.
 *
 * <p>Read from what make and the fetch said, which is the same in ODK's container, with ODK's
 * tools on the machine and in OntoBoard's engine. make says the recipe of the import's
 * {@code mirror-<id>} rule before it runs it - {@code curl -L <address> ... -o
 * tmp/<id>-download.owl}, or ROBOT's {@code convert -I <address> -o tmp/mirror-<id>.owl} for
 * an import with a {@code mirror_from} - then curl or ROBOT says what went wrong, and make
 * stops at {@code mirror-<id>}. A run that stopped anywhere else is no fetch that failed, and
 * is left to the explanation of the run ({@link MakeRun#explain}).
 *
 * <p>ODK's curl is called without {@code --fail}: in ODK's image a server's page saying the
 * file is not there is saved as the file, and the word is ROBOT's - it cannot read what came.
 * OntoBoard's own curl stops at the server's answer, and says it ({@link OdkCurl}).
 *
 * <p>The rule {@code $(MIRRORDIR)/%.owl} puts what was fetched into {@code mirror/<id>.owl}
 * only after {@code mirror-<id>} went through: a fetch that failed leaves the copy the project
 * had where it was, and ODK's {@code no-mirror-refresh-<id>} builds the module from it
 * ({@link #offer}).
 */
public final class ImportFailure {

    /** Why the fetch failed. */
    public enum Kind {
        /** Nothing answered: no network, no such server, refused, silent, or not trusted. */
        NETWORK,
        /** The server answered, with something other than the file. */
        SERVER,
        /** What came is not an ontology ROBOT can read. */
        CONTENT
    }

    /** make's own last word on a target: {@code make[1]: *** [Makefile:498: mirror-ro] Error 7}. */
    private static final Pattern STOP = Pattern.compile(
            "make(?:\\[\\d+\\])?: \\*\\*\\* \\[(?:[^\\]]*?:\\d+: )?([^\\]]+)\\] Error \\d+.*");
    private static final Pattern MIRROR = Pattern.compile("mirror-(.+)");
    private static final Pattern ADDRESS = Pattern.compile("(?i)(?:https?|ftps?|file):.+");
    /** curl's word on a fetch - also on the line of its progress meter, after it. */
    private static final String CURL_SAYS = "curl: (";
    private static final Pattern CURL_STATUS = Pattern.compile("curl: \\((\\d+)\\).*");
    /** curl's statuses for nothing that answered: proxy, name, refused, silent, TLS, trust. */
    private static final List<Integer> NO_ANSWER =
            Arrays.asList(5, 6, 7, 28, 35, 52, 55, 56, 58, 60, 77, 97);
    /** ... and for an answer that was not the file: an error of the server, a redirect loop. */
    private static final List<Integer> NOT_THE_FILE = Arrays.asList(22, 47);
    /** Java's and the OWL API's words for nothing that answered, as ROBOT passes them on. */
    private static final Pattern NO_ANSWER_WORDS = Pattern.compile("unknownhostexception"
            + "|connectexception|connection refused|connection reset|timed out"
            + "|sockettimeoutexception|no route to host|network is unreachable"
            + "|could not resolve host|temporary failure in name resolution"
            + "|name or service not known|sslhandshakeexception|sslexception|pkix path"
            + "|certificate");
    /** An HTTP error said by Java, or its "not found": the address alone, as the message. */
    private static final Pattern SERVERS_WORDS = Pattern.compile(
            "HTTP response code: \\d{3}|(?:IOException|FileNotFoundException): "
                    + "(?:https?|ftps?)://\\S+$");
    /** What curl's progress meter writes: its two heads, and its rows of numbers. */
    private static final Pattern PROGRESS = Pattern.compile(
            "%\\s+Total\\s.*|Dload\\s+Upload.*|[\\d\\s.:kMGTP-]+");

    private final String importId;
    private final String address;
    private final Kind kind;
    private final String said;

    private ImportFailure(String importId, String address, Kind kind, String said) {
        this.importId = importId;
        this.address = address;
        this.kind = kind;
        this.said = said;
    }

    /**
     * The import whose source could not be fetched, read from a run's transcript; null when
     * the run did not stop on fetching one.
     *
     * @param transcript what the run said, an entry of which may be several lines
     */
    public static ImportFailure of(List<String> transcript) {
        List<String> lines = new ArrayList<String>();
        for (String entry : transcript) {
            lines.addAll(Arrays.asList(entry.split("\r\n|\r|\n", -1)));
        }
        // The first of make's stops is where the run stopped: a make that runs make stops
        // after the one it ran, naming its own target.
        int stop = -1;
        String id = null;
        for (int at = 0; at < lines.size() && stop < 0; at++) {
            Matcher stopped = STOP.matcher(lines.get(at).trim());
            if (stopped.matches()) {
                stop = at;
                Matcher mirror = MIRROR.matcher(stopped.group(1).trim());
                id = mirror.matches() ? mirror.group(1) : null;
            }
        }
        if (id == null) {
            return null;
        }
        // The line that fetched this import's source: the last one before the stop that
        // names its file - not an earlier import's, which went through.
        int fetch = -1;
        String address = null;
        for (int at = stop - 1; at >= 0 && address == null; at--) {
            address = fetchedFor(lines.get(at), id);
            fetch = at;
        }
        if (address == null) {
            return null;
        }
        // What was said after the recipe as make said it - a line of which that ends in a
        // backslash goes on in the next.
        int said = fetch;
        while (said < stop && lines.get(said).trim().endsWith("\\")) {
            said++;
        }
        said++;
        // curl's last word, after its tries; then the first word of ROBOT's after it, which
        // is the last word when curl went through.
        int curl = -1;
        for (int at = said; at < stop; at++) {
            if (lines.get(at).contains(CURL_SAYS)) {
                curl = at;
            }
        }
        for (int at = Math.max(said, curl + 1); at < stop; at++) {
            String line = lines.get(at).trim();
            if (!isNoise(line)) {
                // curl's word on the line itself (a move it would not follow, a stop): no
                // network, server or content to name.
                return line.startsWith("curl:") ? null
                        : new ImportFailure(id, address, kindOfRobotsWord(line), line);
            }
        }
        if (curl < 0) {
            return null;
        }
        String line = lines.get(curl);
        String curlSaid = line.substring(line.indexOf(CURL_SAYS)).trim();
        Matcher status = CURL_STATUS.matcher(curlSaid);
        int code = status.matches() ? Integer.parseInt(status.group(1)) : -1;
        Kind kind = NO_ANSWER.contains(code) ? Kind.NETWORK
                : NOT_THE_FILE.contains(code) ? Kind.SERVER : null;
        return kind == null ? null : new ImportFailure(id, address, kind, curlSaid);
    }

    /**
     * The address a recipe line fetches this import's source from, or null when it fetches
     * nothing of it: curl's line of ODK's mirror rule writes {@code tmp/<id>-download.owl},
     * {@code mirror/<id>.owl.gz} or {@code mirror/<id>-base.owl.gz}; ROBOT's reads the
     * address with {@code -I} and writes {@code tmp/mirror-<id>.owl}.
     */
    private static String fetchedFor(String line, String id) {
        String[] words = line.trim().split("\\s+");
        boolean curl = words[0].equals("curl") || words[0].endsWith("/curl");
        String output = null;
        String address = null;
        String iri = null;
        for (int at = 0; at < words.length; at++) {
            String next = at + 1 < words.length ? words[at + 1] : null;
            if (words[at].equals("-o") || words[at].equals("--output")) {
                output = next;
            } else if (words[at].equals("-I") || words[at].equals("--input-iri")) {
                iri = next;
            } else if (curl && address == null && ADDRESS.matcher(words[at]).matches()) {
                address = words[at];
            }
        }
        if (output == null) {
            return null;
        }
        String file = output.substring(output.lastIndexOf('/') + 1);
        if (curl) {
            return file.equals(id + "-download.owl") || file.equals(id + ".owl.gz")
                    || file.equals(id + "-base.owl.gz") ? address : null;
        }
        return iri != null && file.equals("mirror-" + id + ".owl") ? iri : null;
    }

    /** A line that says nothing of why: curl's progress and tries, ROBOT's pointers, make's. */
    private static boolean isNoise(String line) {
        return line.isEmpty() || line.contains(CURL_SAYS) || PROGRESS.matcher(line).matches()
                || line.startsWith("Warning: Problem") || line.startsWith("Warning: Transient")
                || line.startsWith("Use the -vvv option") || line.startsWith("Use the --help")
                || line.startsWith("For details see:") || line.matches("make(\\[\\d+\\])?: "
                        + "(Entering|Leaving) directory.*");
    }

    private static Kind kindOfRobotsWord(String said) {
        if (NO_ANSWER_WORDS.matcher(said.toLowerCase(Locale.ROOT)).find()) {
            return Kind.NETWORK;
        }
        return SERVERS_WORDS.matcher(said).find() ? Kind.SERVER : Kind.CONTENT;
    }

    /** The import's id, as the configuration declares it. */
    public String importId() {
        return importId;
    }

    /** Where its source was fetched from, as the recipe says it. */
    public String address() {
        return address;
    }

    public Kind kind() {
        return kind;
    }

    /** What the fetch said last: curl's or ROBOT's own words. */
    public String said() {
        return said;
    }

    /** The server the address names; the address itself where it names none. */
    private String host() {
        try {
            String host = new URI(address).getHost();
            return host == null ? address : host;
        } catch (java.net.URISyntaxException notOne) {
            return address;
        }
    }

    /** Which import, from where and why, in plain words - and what was said, as it was. */
    public String sentence() {
        String source = "The source of the import " + importId + " ";
        String words = said.startsWith("curl:") ? said : "ROBOT: " + said;
        switch (kind) {
            case NETWORK:
                return source + "could not be downloaded from " + address + ": the network, or "
                        + host() + ", did not answer. " + words;
            case SERVER:
                return source + "could not be downloaded from " + address + ": " + host()
                        + " answered, but not with the ontology - the address may have moved, "
                        + "or the server has trouble. " + words;
            default:
                return source + "came from " + address + ", but what came is not an ontology "
                        + "ROBOT can read - a server sends a page of its own in its place when "
                        + "the file is not there, or when it is busy. " + words;
        }
    }

    /** The copy of the source the project has, {@code mirror/<id>.owl}; null without one. */
    public File copy(File ontologyDirectory) {
        File copy = new File(ontologyDirectory, "mirror/" + importId + ".owl");
        return copy.isFile() ? copy : null;
    }

    /**
     * What builds the module from the copy the project has - ODK's own
     * {@code no-mirror-refresh-<id>} - as a button says it, with the copy's date; null
     * without a copy.
     */
    public String offer(File ontologyDirectory) {
        File copy = copy(ontologyDirectory);
        return copy == null ? null : "Build the module from the copy you have (mirror/"
                + importId + ".owl, from " + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                        .format(new Date(copy.lastModified())) + ")";
    }
}
