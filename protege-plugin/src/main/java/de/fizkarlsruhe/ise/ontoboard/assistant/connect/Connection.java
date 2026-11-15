package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import de.fizkarlsruhe.ise.ontoboard.tools.Hosts;
import de.fizkarlsruhe.ise.ontoboard.tools.HttpRequest;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;

/**
 * One saved way of reaching a model: a name the person chose, the kind of server, where it is,
 * which model, and the few numbers that matter per call.
 *
 * <p>Never the key. A key is resolved per call ({@link ApiKeys}) from the session, the
 * environment or - only if the person ticked it - the preferences, so a connection can be saved,
 * shown and written into a benchmark's models file without carrying a credential.
 *
 * <p>Immutable: every {@code with...} returns a new connection.
 */
public final class Connection {

    /** Ollama's own default is smaller; long ontology context needs room (spec D1). */
    public static final int DEFAULT_CONTEXT_LENGTH = 8192;
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 2048;

    private final String name;
    private final ConnectionKind kind;
    private final String baseUrl;
    private final String model;
    private final String keyVariable;
    private final boolean plainHttpOnNetwork;
    private final int contextLength;
    private final int maxOutputTokens;

    private Connection(String name, ConnectionKind kind, String baseUrl, String model,
            String keyVariable, boolean plainHttpOnNetwork, int contextLength,
            int maxOutputTokens) {
        this.name = name == null ? "" : name.trim();
        this.kind = Objects.requireNonNull(kind, "kind");
        String base = baseUrl == null || baseUrl.trim().isEmpty()
                ? kind.getDefaultBaseUrl() : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.model = model == null ? "" : model.trim();
        this.keyVariable = keyVariable == null ? "" : keyVariable.trim();
        this.plainHttpOnNetwork = plainHttpOnNetwork;
        this.contextLength = contextLength > 0 ? contextLength : DEFAULT_CONTEXT_LENGTH;
        this.maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;
    }

    /** A connection with the kind's defaults: its usual address and key variable, no model yet. */
    public static Connection of(String name, ConnectionKind kind) {
        return new Connection(name, kind, kind.getDefaultBaseUrl(), "",
                kind.getDefaultKeyVariable(), false, DEFAULT_CONTEXT_LENGTH,
                DEFAULT_MAX_OUTPUT_TOKENS);
    }

    public Connection withBaseUrl(String value) {
        return new Connection(name, kind, value, model, keyVariable, plainHttpOnNetwork,
                contextLength, maxOutputTokens);
    }

    public Connection withModel(String value) {
        return new Connection(name, kind, baseUrl, value, keyVariable, plainHttpOnNetwork,
                contextLength, maxOutputTokens);
    }

    public Connection withKeyVariable(String value) {
        return new Connection(name, kind, baseUrl, model, value, plainHttpOnNetwork,
                contextLength, maxOutputTokens);
    }

    public Connection withPlainHttpOnNetwork(boolean value) {
        return new Connection(name, kind, baseUrl, model, keyVariable, value, contextLength,
                maxOutputTokens);
    }

    public Connection withContextLength(int value) {
        return new Connection(name, kind, baseUrl, model, keyVariable, plainHttpOnNetwork, value,
                maxOutputTokens);
    }

    public Connection withMaxOutputTokens(int value) {
        return new Connection(name, kind, baseUrl, model, keyVariable, plainHttpOnNetwork,
                contextLength, value);
    }

    public Connection withName(String value) {
        return new Connection(value, kind, baseUrl, model, keyVariable, plainHttpOnNetwork,
                contextLength, maxOutputTokens);
    }

    public String getName() {
        return name;
    }

    public ConnectionKind getKind() {
        return kind;
    }

    /** Without a trailing slash. */
    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    public String getKeyVariable() {
        return keyVariable;
    }

    public boolean isPlainHttpOnNetwork() {
        return plainHttpOnNetwork;
    }

    /** Ollama's {@code num_ctx}; the other kinds' servers decide their own. */
    public int getContextLength() {
        return contextLength;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    /**
     * The host part of the base URL; when it is not a URL, the address itself without a name and
     * password before an "@" - it is shown in errors and in a saved conversation's header
     * (1.144.0, review 1.143.0 M-4).
     */
    public String getHost() {
        try {
            String host = new URL(baseUrl).getHost();
            return host.isEmpty() ? withoutCredentials(baseUrl) : host;
        } catch (MalformedURLException notAUrl) {
            return withoutCredentials(baseUrl);
        }
    }

    private static String withoutCredentials(String address) {
        return address == null ? "" : address.substring(address.lastIndexOf('@') + 1);
    }

    /**
     * Whether the address holds a name and password ({@code http://user:password@host}). Java
     * never sends them, so such an address cannot work, and they would be kept in plain text and
     * shown in errors (1.144.0, review 1.143.0 M-4).
     */
    public boolean hasCredentials() {
        String address = baseUrl == null ? "" : baseUrl.trim();
        int scheme = address.indexOf("://");
        String rest = scheme >= 0 ? address.substring(scheme + 3) : address;
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        return authority.indexOf('@') >= 0;
    }

    public boolean isThisMachine() {
        return Hosts.isThisMachine(getHost());
    }

    /** On this machine or on a private network: what "local models only" allows (spec D3). */
    public boolean isLocal() {
        return isThisMachine() || Hosts.isPrivateNetwork(getHost());
    }

    /** How far plain http may reach for this connection. */
    public HttpRequest.PlainHttp getPlainHttp() {
        return plainHttpOnNetwork ? HttpRequest.PlainHttp.NETWORK
                : HttpRequest.PlainHttp.THIS_MACHINE;
    }

    /** Where answers go, in words: "this machine", "your network" or "cloud". */
    public String describeWhere() {
        return isThisMachine() ? "this machine" : isLocal() ? "your network" : "cloud";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Connection)) {
            return false;
        }
        Connection that = (Connection) other;
        return plainHttpOnNetwork == that.plainHttpOnNetwork
                && contextLength == that.contextLength
                && maxOutputTokens == that.maxOutputTokens
                && name.equals(that.name) && kind == that.kind && baseUrl.equals(that.baseUrl)
                && model.equals(that.model) && keyVariable.equals(that.keyVariable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, kind, baseUrl, model, keyVariable, plainHttpOnNetwork,
                contextLength, maxOutputTokens);
    }

    @Override
    public String toString() {
        return name.isEmpty() ? kind.getLabel() : name;
    }
}
