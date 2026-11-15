package de.fizkarlsruhe.ise.ontoboard.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One request for {@link HttpsOpener#send}: a method, a URL, headers, an optional body, and how
 * far plain http may reach.
 *
 * <p>Written for the assistant's model connections, which need what a download never did: a
 * POST with a JSON body, an API key in a header, and a read timeout long enough for a local model
 * to load. Immutable in spirit - each call returns this same object for chaining, and the opener
 * reads it once.
 */
public final class HttpRequest {

    /** Where a request may go over plain http instead of HTTPS. */
    public enum PlainHttp {
        /** HTTPS only. */
        NEVER,
        /** Also plain http to this machine: Ollama's default address. */
        THIS_MACHINE,
        /** Also plain http to any host - only when the person said the server is on their network. */
        NETWORK
    }

    /** A minute, as for downloads, unless the caller asks for more. */
    static final int DEFAULT_READ_MILLIS = 60_000;

    private final String method;
    private final String url;
    private final byte[] body;
    private final Map<String, String> headers = new LinkedHashMap<String, String>();
    private int readTimeoutMillis = DEFAULT_READ_MILLIS;
    private PlainHttp plainHttp = PlainHttp.NEVER;

    private HttpRequest(String method, String url, byte[] body) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("a request needs a URL");
        }
        this.method = method;
        this.url = url.trim();
        this.body = body;
    }

    public static HttpRequest get(String url) {
        return new HttpRequest("GET", url, null);
    }

    public static HttpRequest post(String url, byte[] body) {
        return new HttpRequest("POST", url, body == null ? new byte[0] : body.clone());
    }

    /** Adds or replaces one header. */
    public HttpRequest header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public HttpRequest readTimeoutMillis(int millis) {
        this.readTimeoutMillis = millis < 1 ? DEFAULT_READ_MILLIS : millis;
        return this;
    }

    public HttpRequest plainHttp(PlainHttp policy) {
        this.plainHttp = policy == null ? PlainHttp.NEVER : policy;
        return this;
    }

    public String getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    /** In the order they were added. */
    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    /** The body, or null for a GET. */
    public byte[] getBody() {
        return body == null ? null : body.clone();
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public PlainHttp getPlainHttp() {
        return plainHttp;
    }
}
