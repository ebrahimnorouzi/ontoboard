package de.fizkarlsruhe.ise.ontoboard.tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertPathBuilderException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Opens a release download over HTTPS: GitHub's redirect followed, nothing else allowed.
 *
 * <p><b>Redirects are followed here, not by {@code HttpURLConnection}.</b> GitHub answers a
 * release URL with one redirect to {@code release-assets.githubusercontent.com} (measured). Doing
 * it by hand means no hop can leave HTTPS, and the trust store a retry uses applies to every hop
 * rather than only the first.
 *
 * <p><b>Protégé 5.5.0's Java cannot check GitHub's certificate.</b> Measured on 2026-11-05: JRE
 * 1.8.0_121, the one 5.5.0 bundles, fails both downloads with "PKIX path building failed" - its
 * trust store predates the authority GitHub uses now - while 5.6.9's 11.0.25 succeeds. The same
 * 1.8.0_121 succeeds with a trust manager built from Windows' own store, {@code Windows-ROOT}
 * (93 roots on the machine measured), which Windows Update keeps current. So after a
 * certificate-path failure, and only then, the download is tried again against the operating
 * system's store: Windows' own, or the system Java store a Linux distribution maintains. Never
 * against no checking at all - the pinned SHA-256 would catch altered bytes anyway, but a
 * download that silently trusts anybody is not something to ship. macOS's Java offers no
 * equivalent, so there the failure is reported and the user downloads the file by hand.
 *
 * <p><b>{@link #send} is for the assistant's model connections</b> (1.136.0): a request with a
 * method, headers and a body, the same trust-store fallback, and an answer whose body is handed
 * back unread so a streamed reply can be read as it arrives. <b>It follows no redirect at
 * all.</b> {@code HttpURLConnection} turns a redirected POST into a GET, so following one would
 * send a different request from the one shown to the person; and a redirect could carry the API
 * key in its headers to a host nobody chose. No model API redirects a request, so a 3xx is
 * reported, not followed. Plain http is allowed only as far as the request's
 * {@link HttpRequest.PlainHttp} says: to this machine for Ollama's default address, to another
 * host only when the person marked the server as on their network.
 */
public final class HttpsOpener implements Download.Opener {

    /** GitHub uses one; five leaves room without allowing a loop. */
    static final int MAX_REDIRECTS = 5;

    static final String WINDOWS_TRUST = "Windows-ROOT";

    /** Where Debian/Ubuntu and Fedora/RHEL keep the system's Java trust store. */
    static final List<File> LINUX_STORES = Collections.unmodifiableList(Arrays.asList(
            new File("/etc/ssl/certs/java/cacerts"), new File("/etc/pki/java/cacerts")));

    private static final int CONNECT_MILLIS = 15_000;
    private static final int READ_MILLIS = 60_000;

    private final boolean allowLoopbackHttp;
    private volatile boolean stopped;
    private volatile HttpURLConnection current;

    private HttpsOpener(boolean allowLoopbackHttp) {
        this.allowLoopbackHttp = allowLoopbackHttp;
    }

    /**
     * Stops an {@link #open} in progress, from any thread: the connection it is waiting on is
     * closed, so even a server that has gone quiet lets go at once instead of after its timeout.
     * The same way {@code WebOntology.Attempt.stop} lets go of a slow import.
     */
    public void stop() {
        stopped = true;
        HttpURLConnection connection = current;
        if (connection != null) {
            connection.disconnect();
        }
    }

    /** HTTPS only. */
    public static HttpsOpener real() {
        return new HttpsOpener(false);
    }

    /** Also plain HTTP to this machine, for tests that serve files on 127.0.0.1. */
    static HttpsOpener forLoopbackTests() {
        return new HttpsOpener(true);
    }

    @Override
    public Download.Opened open(String url) throws IOException {
        URL start = new URL(url);
        if (!allowed(start, allowLoopbackHttp)) {
            throw new IOException("OntoBoard downloads tools over HTTPS only, and " + url
                    + " is not an HTTPS address.");
        }
        return withTrustFallback(factory -> openWith(start, factory));
    }

    /** One attempt at a connection, with another trust store's factory or the JVM's own (null). */
    private interface Attempt<T> {
        T with(SSLSocketFactory factory) throws IOException;
    }

    /**
     * Tries once with the JVM's trust store and, after a certificate-path failure and only then,
     * once with each of the operating system's.
     */
    private static <T> T withTrustFallback(Attempt<T> attempt) throws IOException {
        try {
            return attempt.with(null);
        } catch (IOException first) {
            if (!isCertificatePathFailure(first)) {
                throw first;
            }
            IOException last = first;
            for (String store : trustFallbacks(System.getProperty("os.name", ""), LINUX_STORES)) {
                SSLSocketFactory factory;
                try {
                    factory = socketFactoryFrom(store);
                } catch (GeneralSecurityException | IOException cannotLoad) {
                    continue;
                }
                try {
                    return attempt.with(factory);
                } catch (IOException again) {
                    if (!isCertificatePathFailure(again)) {
                        throw again;
                    }
                    last = again;
                }
            }
            // The certificate failure, not a later one: it is what the message has to explain.
            throw last;
        }
    }

    /** The most of an error body kept: enough for any API's explanation, not a whole HTML page. */
    static final int MAX_ERROR_CHARS = 4000;

    /** What came back from {@link #send}: a status below 300, and the body, not yet read. */
    public static final class Answer {
        private final int status;
        private final InputStream body;
        private final String contentType;

        Answer(int status, InputStream body, String contentType) {
            this.status = status;
            this.body = body;
            this.contentType = contentType == null ? "" : contentType;
        }

        public int getStatus() {
            return status;
        }

        /** The caller reads and closes it; {@link HttpsOpener#stop} closes it from elsewhere. */
        public InputStream getBody() {
            return body;
        }

        /** Never null. */
        public String getContentType() {
            return contentType;
        }
    }

    /**
     * The server answered 400 or above. The status and the server's own words, which is where a
     * model API says what was wrong - "invalid x-api-key", "model not found".
     *
     * <p>The message names only the host, the status and the path. The body may echo what was
     * sent, so it is kept apart for a caller that knows what to blank out of it.
     */
    public static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String body;

        public Refused(URL url, int status, String body) {
            super(url.getHost() + " answered " + status + " for " + url.getPath() + ".");
            this.status = status;
            this.body = body == null ? "" : body;
        }

        public int getStatus() {
            return status;
        }

        /** At most {@value HttpsOpener#MAX_ERROR_CHARS} characters, never null. */
        public String getBody() {
            return body;
        }
    }

    /**
     * Sends one request, follows no redirect, and hands back the answer with its body unread.
     *
     * @throws Refused for a status of 400 or above, with the server's own words
     * @throws IOException for a redirect, a plain-http address the request's policy does not
     *     allow, a connection failure, or {@link #stop}
     */
    public Answer send(HttpRequest request) throws IOException {
        final URL url = new URL(request.getUrl());
        refuseUnlessAllowed(url, request.getPlainHttp());
        return withTrustFallback(factory -> sendWith(url, request, factory));
    }

    private Answer sendWith(URL url, HttpRequest request, SSLSocketFactory factory)
            throws IOException {
        if (stopped) {
            throw new IOException("The request was stopped.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        current = connection;
        // As in openWith: either stop() sees the published connection or this sees stop().
        if (stopped) {
            connection.disconnect();
            throw new IOException("The request was stopped.");
        }
        if (factory != null && connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(factory);
        }
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(CONNECT_MILLIS);
        connection.setReadTimeout(request.getReadTimeoutMillis());
        connection.setRequestMethod(request.getMethod());
        connection.setRequestProperty("User-Agent", "OntoBoard");
        for (java.util.Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        byte[] body = request.getBody();
        if (body != null) {
            // Not setFixedLengthStreamingMode, although the length is known. In streaming mode
            // the JDK answers a 401 by disconnecting and throwing HttpRetryException, so the
            // server's own explanation ("invalid x-api-key") never reaches getErrorStream -
            // measured, the body came back empty. A request body here is a few hundred
            // kilobytes at most, so letting HttpURLConnection buffer it costs nothing.
            connection.setDoOutput(true);
            try (java.io.OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
        }
        int code = connection.getResponseCode();
        if (code >= 300 && code < 400) {
            connection.disconnect();
            throw new IOException(url.getHost() + " answered " + code + " for " + url.getPath()
                    + "; a request to a model is not redirected.");
        }
        if (code >= 400) {
            String said = errorText(connection);
            connection.disconnect();
            throw new Refused(url, code, said);
        }
        return new Answer(code, connection.getInputStream(), connection.getContentType());
    }

    /** The error body as UTF-8 text, cut at {@link #MAX_ERROR_CHARS}; empty when there is none. */
    private static String errorText(HttpURLConnection connection) {
        InputStream in = connection.getErrorStream();
        if (in == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        try (java.io.Reader reader =
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int read;
            while (text.length() < MAX_ERROR_CHARS && (read = reader.read(buffer)) != -1) {
                text.append(buffer, 0, Math.min(read, MAX_ERROR_CHARS - text.length()));
            }
        } catch (IOException unreadable) {
            // What arrived before the failure is still the best explanation there is.
        }
        return text.toString();
    }

    /** Refuses plain http beyond what the request's policy allows, before connecting. */
    private static void refuseUnlessAllowed(URL url, HttpRequest.PlainHttp policy)
            throws IOException {
        String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
        if ("https".equals(protocol)) {
            return;
        }
        if ("http".equals(protocol)) {
            if (policy == HttpRequest.PlainHttp.NETWORK) {
                return;
            }
            if (policy == HttpRequest.PlainHttp.THIS_MACHINE
                    && Hosts.isThisMachine(url.getHost())) {
                return;
            }
        }
        throw new IOException("OntoBoard sends to " + url.getHost() + " over HTTPS only. Plain"
                + " http is allowed to this machine, or to a server you marked as on your"
                + " network.");
    }

    private Download.Opened openWith(URL url, SSLSocketFactory factory) throws IOException {
        URL at = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (stopped) {
                throw new IOException("The download was stopped.");
            }
            HttpURLConnection connection = (HttpURLConnection) at.openConnection();
            current = connection;
            // Checked again after publishing the connection: either stop() sees it and closes it,
            // or this sees stop() - never neither.
            if (stopped) {
                connection.disconnect();
                throw new IOException("The download was stopped.");
            }
            if (factory != null && connection instanceof HttpsURLConnection) {
                ((HttpsURLConnection) connection).setSSLSocketFactory(factory);
            }
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_MILLIS);
            connection.setReadTimeout(READ_MILLIS);
            connection.setRequestProperty("User-Agent", "OntoBoard");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400 && code != 304) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) {
                    throw new IOException(at + " answered " + code
                            + " without saying where the file is.");
                }
                at = redirectTarget(at, location, allowLoopbackHttp);
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IOException(at.getHost() + " answered " + code + " for " + at + ".");
            }
            return new Download.Opened(connection.getInputStream(),
                    connection.getContentLengthLong());
        }
        throw new IOException("More than " + MAX_REDIRECTS + " redirects from " + url
                + ", so it was abandoned.");
    }

    /** Where a redirect leads, refusing anything that is not HTTPS. */
    static URL redirectTarget(URL from, String location, boolean allowLoopbackHttp)
            throws IOException {
        URL to = new URL(from, location);
        if (!allowed(to, allowLoopbackHttp)) {
            throw new IOException("Did not follow the redirect from " + from + " to " + to
                    + ": OntoBoard downloads tools over HTTPS only.");
        }
        return to;
    }

    private static boolean allowed(URL url, boolean allowLoopbackHttp) {
        String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
        if ("https".equals(protocol)) {
            return true;
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return allowLoopbackHttp && "http".equals(protocol)
                && ("127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host));
    }

    /**
     * Whether a failure is the JVM not trusting the server's certificate chain - the one failure
     * another trust store can fix.
     */
    public static boolean isCertificatePathFailure(Throwable failure) {
        Throwable at = failure;
        for (int depth = 0; at != null && depth < 20; depth++) {
            if (at instanceof CertPathBuilderException) {
                return true;
            }
            String message = at.getMessage();
            if (message != null && (message.contains("PKIX path building failed")
                    || message.contains("unable to find valid certification path"))) {
                return true;
            }
            at = at.getCause() == at ? null : at.getCause();
        }
        return false;
    }

    /** The operating system's trust stores worth trying after a certificate-path failure. */
    static List<String> trustFallbacks(String osName, List<File> linuxStores) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return Collections.emptyList();
        }
        if (os.contains("win")) {
            return Collections.singletonList(WINDOWS_TRUST);
        }
        List<String> found = new ArrayList<String>();
        for (File store : linuxStores) {
            if (store.isFile()) {
                found.add(store.getPath());
            }
        }
        return found;
    }

    private static SSLSocketFactory socketFactoryFrom(String store)
            throws GeneralSecurityException, IOException {
        KeyStore trusted;
        if (WINDOWS_TRUST.equals(store)) {
            trusted = KeyStore.getInstance(WINDOWS_TRUST);
            trusted.load(null, null);
        } else {
            trusted = KeyStore.getInstance(KeyStore.getDefaultType());
            try (InputStream in = new FileInputStream(store)) {
                trusted.load(in, null);
            }
        }
        TrustManagerFactory trust =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context.getSocketFactory();
    }

    /**
     * What a download here would have to work with, without downloading anything.
     *
     * <p>For the self-check: inside Protégé the bundle imports {@code javax.net.ssl} as optional,
     * so whether these classes load at all is a property of the host's OSGi container, and the
     * place to find that out is a startup check rather than the first click on Download.
     */
    public static String selfCheck() throws GeneralSecurityException, IOException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);
        StringBuilder described = new StringBuilder("TLS from ")
                .append(context.getProvider().getName()).append(" (")
                .append(String.join(", ", context.getDefaultSSLParameters().getProtocols()))
                .append(")");
        if (trustFallbacks(System.getProperty("os.name", ""), LINUX_STORES)
                .contains(WINDOWS_TRUST)) {
            KeyStore windows = KeyStore.getInstance(WINDOWS_TRUST);
            windows.load(null, null);
            described.append("; Windows trust store: ").append(windows.size()).append(" roots");
        }
        return described.toString();
    }
}
