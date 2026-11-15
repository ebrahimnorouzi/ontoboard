package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.tools.HttpsOpener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * curl, as ODK's Makefile calls it, without curl: what fetches the ontology a project mirrors
 * and a plugin of ROBOT's.
 *
 * <p>ODK's lines are {@code curl -L <address> --create-dirs -o <file> --retry <n> --max-time
 * <seconds>} for an ontology at its OBO address, and {@code curl -L -o <file> <address>} for
 * a plugin. Those options are read, in any order. Anything else of curl's - a request that is
 * not a plain fetch, a header, output to the terminal - is named and the line refused before
 * anything of the target runs, as for every command a recipe names.
 *
 * <p>It does what curl does with those options: follows where the address has moved, makes
 * the folder for the file when asked to, asks a server again that is busy or silent (for the
 * answers curl itself asks again for: 408, 429, 500, 502, 503, 504 and a timeout), gives up
 * after the time given, and reads a {@code file:} address as it reads any other - so the
 * ontology to mirror can be a file on this machine. The file is written beside its place and
 * put there when it is whole.
 *
 * <p><b>One thing it does not do as curl does.</b> Without {@code -f}, which ODK's lines do
 * not pass, curl saves whatever the server sends - also the page that says the file is not
 * there - and reports success; ROBOT then fails on that page with a parser's complaint. Here
 * a refusal of the server fails the line, with the address and the server's answer, and no
 * file is left that looks like an ontology and is none.
 *
 * <p>The certificate of an https server is checked with this Java's trust store and, where
 * that one does not know the authority - Protege 5.5.0's Java predates several - with the
 * operating system's ({@link HttpsOpener#withOperatingSystemsTrust}). Never with none.
 *
 * <p>As curl, it goes through the proxy the environment names ({@code http_proxy},
 * {@code https_proxy}, {@code all_proxy}; not for a host {@code no_proxy} names) - the same
 * variables ODK's container is given. A proxy that wants a name and password is not given
 * one, and that is said.
 *
 * <p>A fetch can take minutes, so it asks whether the person has stopped the run: with
 * every piece of the file that comes in, and before it asks a server again. A server that
 * is silent is waited for as long as it would be otherwise - at most a minute.
 */
final class OdkCurl implements RecipeShell.Tool {

    /** Before a server is asked again: curl waits a second, and twice as long each time. */
    static long firstPauseMillis = 1000;

    /** Where curl reads its proxy from: the environment. Another one in tests. */
    static Function<String, String> environment = new Function<String, String>() {
        @Override
        public String apply(String name) {
            return System.getenv(name);
        }
    };

    /** curl's own limit on following an address that has moved. */
    private static final int MAX_REDIRECTS = 50;
    private static final int CONNECT_MILLIS = 30_000;
    private static final int SILENT_MILLIS = 60_000;
    private static final List<Integer> ASKED_AGAIN = Arrays.asList(408, 429, 500, 502, 503, 504);
    /** curl's exit status when the person ends it: 128 and the signal's number. */
    private static final int STOPPED = 130;

    /** Whether the person has stopped the run; null where a run cannot be stopped. */
    private final BooleanSupplier stopped;

    OdkCurl() {
        this(null);
    }

    /**
     * @param stopped asked while a file comes in and before a server is asked again: a
     *                fetch can take minutes, and a run that was stopped does not wait for it
     */
    OdkCurl(BooleanSupplier stopped) {
        this.stopped = stopped;
    }

    private boolean isStopped() {
        return stopped != null && stopped.getAsBoolean();
    }

    /** What a line asks of curl. */
    private static final class Asked {
        String address;
        String output;
        boolean follows;
        boolean makesFolders;
        int retries;
        int maxSeconds;
    }

    /** Why a fetch did not work: what to say, curl's exit status, and whether to ask again. */
    private static final class Failed extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        final boolean askAgain;

        Failed(int status, String said, boolean askAgain) {
            super(said);
            this.status = status;
            this.askAgain = askAgain;
        }
    }

    @Override
    public String whyNot(List<String> arguments) {
        Asked asked;
        try {
            asked = read(arguments);
        } catch (IllegalArgumentException notRead) {
            return notRead.getMessage();
        }
        return RecipeShell.whyNotWritten(asked.output);
    }

    /** The line's options; IllegalArgumentException with the reason for one that is not read. */
    private static Asked read(List<String> arguments) {
        Asked asked = new Asked();
        String line = "'curl " + String.join(" ", arguments) + "'";
        for (int at = 0; at < arguments.size(); at++) {
            String word = arguments.get(at);
            if (word.equals("-L") || word.equals("--location")) {
                asked.follows = true;
            } else if (word.equals("--create-dirs")) {
                asked.makesFolders = true;
            } else if (word.equals("-o") || word.equals("--output")) {
                asked.output = valueOf(arguments, ++at, word, line);
            } else if (word.equals("--retry")) {
                asked.retries = numberOf(arguments, ++at, word, line);
            } else if (word.equals("--max-time") || word.equals("-m")) {
                asked.maxSeconds = numberOf(arguments, ++at, word, line);
            } else if (word.startsWith("-")) {
                throw new IllegalArgumentException(line + " is not read here: of curl's "
                        + "options OntoBoard's own engine reads the ones ODK's Makefile "
                        + "fetches with (-L, -o, --create-dirs, --retry, --max-time), and '"
                        + word + "' is not among them");
            } else if (asked.address != null) {
                throw new IllegalArgumentException(line + " is not read here: it names two "
                        + "addresses, and ODK's lines fetch one");
            } else {
                asked.address = word;
            }
        }
        if (asked.address == null) {
            throw new IllegalArgumentException(line + " names no address to fetch");
        }
        if (asked.output == null) {
            throw new IllegalArgumentException(line + " is not read here: it writes to no "
                    + "file (-o), and what curl would print is of no use to a recipe");
        }
        String scheme = asked.address.contains(":") ? asked.address.substring(0,
                asked.address.indexOf(':')).toLowerCase(Locale.ROOT) : "";
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("file")) {
            throw new IllegalArgumentException(line + " is not read here: OntoBoard's own "
                    + "engine fetches http, https and file addresses, and this one is "
                    + (scheme.isEmpty() ? "none of them" : "'" + scheme + "'"));
        }
        return asked;
    }

    private static String valueOf(List<String> arguments, int at, String option, String line) {
        if (at >= arguments.size()) {
            throw new IllegalArgumentException(line + " gives '" + option + "' nothing");
        }
        return arguments.get(at);
    }

    private static int numberOf(List<String> arguments, int at, String option, String line) {
        String value = valueOf(arguments, at, option, line);
        if (!value.matches("[0-9]{1,6}")) {
            throw new IllegalArgumentException(line + " gives '" + option + "' " + value
                    + ", which is not a whole number");
        }
        return Integer.parseInt(value);
    }

    @Override
    public int run(RecipeShell.Call call) throws IOException {
        Asked asked = read(call.arguments());
        File target = call.file(asked.output);
        File folder = target.getAbsoluteFile().getParentFile();
        if (!folder.isDirectory()) {
            if (!asked.makesFolders) {
                call.complain("curl: (23) there is no folder for " + asked.output
                        + ", and the line does not ask for one to be made (--create-dirs)");
                return 23;
            }
            Files.createDirectories(folder.toPath());
        }
        // Beside its place until it is whole: a fetch that stops leaves nothing that looks
        // like the file.
        File part = new File(folder, target.getName() + ".ontoboard-fetching");
        try {
            for (int attempt = 0;; attempt++) {
                try {
                    fetch(asked, part);
                    break;
                } catch (Failed failed) {
                    if (!failed.askAgain || attempt >= asked.retries
                            || !waited(Math.min(firstPauseMillis << Math.min(attempt, 20),
                                    600_000L))) {
                        call.complain(failed.getMessage());
                        return failed.status;
                    }
                }
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return 0;
        } finally {
            Files.deleteIfExists(part.toPath());
        }
    }

    /** Waits before a server is asked again; false when the run was stopped meanwhile. */
    private boolean waited(long millis) {
        long until = System.currentTimeMillis() + millis;
        try {
            while (!isStopped()) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) {
                    return true;
                }
                Thread.sleep(Math.min(left, 100));
            }
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------------ fetching

    private void fetch(Asked asked, File into) throws Failed, IOException {
        if (asked.address.toLowerCase(Locale.ROOT).startsWith("file:")) {
            File from = fileAt(asked.address);
            if (from == null || !from.isFile()) {
                throw new Failed(37, "curl: (37) there is no file at " + asked.address, false);
            }
            Files.copy(from.toPath(), into.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        long deadline = asked.maxSeconds > 0
                ? System.currentTimeMillis() + asked.maxSeconds * 1000L : Long.MAX_VALUE;
        URL url;
        try {
            url = new URL(asked.address);
        } catch (MalformedURLException notOne) {
            throw new Failed(3, "curl: (3) '" + asked.address + "' is not an address", false);
        }
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (isStopped()) {
                throw stoppedAt(url);
            }
            Proxy proxy = proxyFor(url);
            HttpURLConnection connection = answered(url, asked, deadline, proxy);
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new Failed(47, "curl: " + url.getHost() + " answered " + code
                                + " (moved) for " + url + " without saying where to", false);
                    }
                    if (!asked.follows) {
                        throw new Failed(22, "curl: " + url + " has moved to " + location.trim()
                                + ", and the line does not ask to follow (-L)", false);
                    }
                    URL next;
                    try {
                        next = new URL(url, location.trim());
                    } catch (MalformedURLException notOne) {
                        throw new Failed(3, "curl: (3) " + url + " has moved to '"
                                + location.trim() + "', which is not an address", false);
                    }
                    if (!next.getProtocol().equals("http")
                            && !next.getProtocol().equals("https")) {
                        throw new Failed(1, "curl: " + url + " has moved to " + next
                                + ", which is neither an http nor an https address", false);
                    }
                    url = next;
                    continue;
                }
                if (code == 407) {
                    throw asksForAName(proxy, url);
                }
                if (code >= 400) {
                    String words = connection.getResponseMessage();
                    throw new Failed(22, "curl: (22) " + url.getHost() + " answered " + code
                            + (words == null || words.trim().isEmpty() ? "" : " " + words.trim())
                            + " for " + url + ", so nothing was fetched",
                            ASKED_AGAIN.contains(code));
                }
                try (InputStream in = connection.getInputStream();
                        OutputStream out = new FileOutputStream(into)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        out.write(buffer, 0, read);
                        if (isStopped()) {
                            throw stoppedAt(url);
                        }
                        if (System.currentTimeMillis() > deadline) {
                            throw tookTooLong(url, asked);
                        }
                    }
                }
                return;
            } catch (SocketTimeoutException silent) {
                throw silentFor(url, asked, deadline);
            } catch (IOException broken) {
                throw new Failed(56, "curl: (56) the answer of " + url.getHost() + " for " + url
                        + " broke off: " + wordsOf(broken), true);
            } finally {
                connection.disconnect();
            }
        }
        throw new Failed(47, "curl: (47) " + asked.address + " redirects more than "
                + MAX_REDIRECTS + " times - it goes round in a circle", false);
    }

    /** The connection once the server has answered, its certificate checked. */
    private static HttpURLConnection answered(final URL url, final Asked asked,
            final long deadline, final Proxy proxy) throws Failed {
        final long left = deadline - System.currentTimeMillis();
        if (left <= 0) {
            throw tookTooLong(url, asked);
        }
        try {
            return HttpsOpener.withOperatingSystemsTrust(
                    new HttpsOpener.Trying<HttpURLConnection>() {
                        @Override
                        public HttpURLConnection with(SSLSocketFactory factory)
                                throws IOException {
                            HttpURLConnection connection = (HttpURLConnection) (proxy == null
                                    ? url.openConnection() : url.openConnection(proxy));
                            if (factory != null && connection instanceof HttpsURLConnection) {
                                ((HttpsURLConnection) connection).setSSLSocketFactory(factory);
                            }
                            connection.setInstanceFollowRedirects(false);
                            connection.setConnectTimeout((int) Math.min(CONNECT_MILLIS, left));
                            connection.setReadTimeout((int) Math.min(SILENT_MILLIS, left));
                            connection.setRequestProperty("Accept", "*/*");
                            connection.setRequestProperty("User-Agent",
                                    "OntoBoard (a Protege plugin)");
                            // Connects, and waits for the first word of the answer.
                            connection.getResponseCode();
                            return connection;
                        }
                    });
        } catch (SocketTimeoutException silent) {
            throw silentFor(url, asked, deadline);
        } catch (UnknownHostException unknown) {
            throw new Failed(6, "curl: (6) there is no server called " + url.getHost() + " ("
                    + url + ") - or no network", false);
        } catch (ConnectException refused) {
            throw new Failed(7, "curl: (7) nothing answers at " + url.getHost()
                    + (url.getPort() < 0 ? "" : ":" + url.getPort()) + " (" + url + ")", false);
        } catch (IOException failed) {
            if (HttpsOpener.isCertificatePathFailure(failed)) {
                throw new Failed(60, "curl: (60) the certificate of " + url.getHost() + " could "
                        + "not be checked - neither this Java's trust store nor the operating "
                        + "system's knows its authority (" + wordsOf(failed) + ")", false);
            }
            throw new Failed(56, "curl: (56) " + url.getHost() + " could not be read for " + url
                    + ": " + wordsOf(failed), true);
        }
    }

    // ------------------------------------------------------------------ a proxy

    /** Whether no_proxy names this host: itself, a domain it is in, or everything. */
    static boolean isExcepted(String host, String noProxy) {
        if (noProxy == null) {
            return false;
        }
        String asked = withoutBrackets(host.toLowerCase(Locale.ROOT));
        for (String entry : noProxy.split(",")) {
            String name = withoutBrackets(entry.trim().toLowerCase(Locale.ROOT));
            if (name.equals("*")) {
                return true;
            }
            // host:port - the port is read over; an IPv6 address has more colons than one.
            if (name.indexOf(':') >= 0 && name.indexOf(':') == name.lastIndexOf(':')) {
                name = name.substring(0, name.indexOf(':'));
            }
            while (name.startsWith(".")) {
                name = name.substring(1);
            }
            if (!name.isEmpty() && (asked.equals(name) || asked.endsWith("." + name))) {
                return true;
            }
        }
        return false;
    }

    private static String withoutBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
    }

    /** The first of these variables the environment has a value for, or null. */
    private static String named(String... variables) {
        for (String variable : variables) {
            String value = environment.apply(variable);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    /** [scheme://][name[:password]@]host[:port][/] - as people write a proxy. */
    private static final Pattern PROXY = Pattern.compile("(?:([A-Za-z][A-Za-z0-9+.-]*)://)?"
            + "(?:[^@/]*@)?(\\[[^\\]]+\\]|[^:/@]+)(?::([0-9]{1,5}))?/?");

    /**
     * The proxy the environment names for an address, as curl reads it: {@code http_proxy}
     * or {@code https_proxy} by the kind of address, else {@code all_proxy}, and none for a
     * host {@code no_proxy} names. Null where it names none: Java then goes by its own
     * settings, as it does for everything else Protege fetches.
     */
    private static Proxy proxyFor(URL url) throws Failed {
        String variable = url.getProtocol().equals("https") ? "https_proxy" : "http_proxy";
        String named = named(variable, variable.toUpperCase(Locale.ROOT));
        if (named == null) {
            variable = "all_proxy";
            named = named(variable, variable.toUpperCase(Locale.ROOT));
        }
        if (named == null || isExcepted(url.getHost(), named("no_proxy", "NO_PROXY"))) {
            return null;
        }
        Matcher parts = PROXY.matcher(named);
        String scheme = parts.matches() && parts.group(1) != null
                ? parts.group(1).toLowerCase(Locale.ROOT) : "http";
        if (!parts.matches() || !(scheme.equals("http") || scheme.startsWith("socks"))) {
            // Said without the name and password it may carry.
            throw new Failed(5, "curl: (5) the proxy the environment names (" + variable + ": "
                    + named.replaceFirst("^((?:[A-Za-z][A-Za-z0-9+.-]*://)?)[^@/]*@", "$1")
                    + ") is not one OntoBoard's own engine can ask: it asks an http or a "
                    + "socks proxy, named by its host and port", false);
        }
        // curl's own port for a proxy that names none.
        int port = parts.group(3) == null ? 1080 : Integer.parseInt(parts.group(3));
        return new Proxy(scheme.startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(withoutBrackets(parts.group(2)), port));
    }

    private static Failed asksForAName(Proxy proxy, URL url) {
        return new Failed(22, "curl: (22) the proxy" + (proxy == null ? "" : " "
                + ((InetSocketAddress) proxy.address()).getHostString()) + " asks for a name "
                + "and password (407) before it fetches " + url + ", and OntoBoard's own "
                + "engine sends none", false);
    }

    // ------------------------------------------------------------------ why not

    private static Failed silentFor(URL url, Asked asked, long deadline) {
        if (asked.maxSeconds > 0 && deadline - System.currentTimeMillis() <= 0) {
            return tookTooLong(url, asked);
        }
        return new Failed(28, "curl: (28) " + url.getHost() + " sent nothing for too long, so "
                + "the fetch of " + url + " was given up", true);
    }

    private static Failed stoppedAt(URL url) {
        return new Failed(STOPPED, "curl: the fetch of " + url + " was stopped", false);
    }

    private static Failed tookTooLong(URL url, Asked asked) {
        return new Failed(28, "curl: (28) " + url + " was not fetched within " + asked.maxSeconds
                + (asked.maxSeconds == 1 ? " second" : " seconds") + " (--max-time), so the "
                + "fetch was given up", true);
    }

    /** The file a {@code file:} address names; null when the address is not one of a file. */
    private static File fileAt(String address) {
        try {
            return new File(new URI(address));
        } catch (URISyntaxException | IllegalArgumentException notExact) {
            // An address with a blank or a host in it: its path, taken as it stands.
            String path = address.substring("file:".length()).replaceFirst("^//[^/]*", "");
            return path.isEmpty() ? null : new File(path.matches("/[A-Za-z]:.*")
                    ? path.substring(1) : path);
        }
    }

    private static String wordsOf(IOException failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
