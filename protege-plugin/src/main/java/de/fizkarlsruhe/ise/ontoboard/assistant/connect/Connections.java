package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Connections as JSON: one format for Protégé's saved settings and for the benchmark's models
 * file, so a connection that works in the plugin can be pasted into a benchmark run as it is.
 *
 * <pre>
 * [{"name":"Ollama","kind":"ollama","baseUrl":"http://localhost:11434","model":"llama3.1",
 *   "keyVariable":"","plainHttpOnNetwork":false,"contextLength":8192,"maxOutputTokens":2048}]
 * </pre>
 *
 * <p>Missing keys take the kind's defaults; unknown keys are ignored, so a models file can carry
 * a {@code "_note"} for the person filling it in. Never a key field: see {@link Connection}.
 */
public final class Connections {

    /**
     * Jackson quotes the text it read in every error's location by default, and a models file can
     * hold a key: a trailing comma printed the key on the terminal (1.146.0, review 1.145.0
     * keys-02).
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION);

    /** Jackson names a word it cannot read in full: a key pasted without quotes is one. */
    private static final java.util.regex.Pattern UNRECOGNIZED =
            java.util.regex.Pattern.compile("Unrecognized token '[^']*'");

    private Connections() {
    }

    public static String toJson(List<Connection> connections) {
        ArrayNode array = JSON.createArrayNode();
        for (Connection connection : connections) {
            ObjectNode entry = array.addObject();
            entry.put("name", connection.getName());
            entry.put("kind", connection.getKind().getId());
            entry.put("baseUrl", connection.getBaseUrl());
            entry.put("model", connection.getModel());
            entry.put("keyVariable", connection.getKeyVariable());
            entry.put("plainHttpOnNetwork", connection.isPlainHttpOnNetwork());
            entry.put("contextLength", connection.getContextLength());
            entry.put("maxOutputTokens", connection.getMaxOutputTokens());
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(array);
        } catch (IOException cannotWrite) {
            // Writing a tree of strings and numbers to a String cannot fail.
            throw new IllegalStateException(cannotWrite);
        }
    }

    /**
     * @throws IllegalArgumentException naming the entry (counting from 1) that cannot be read
     */
    public static List<Connection> fromJson(String json) {
        List<Connection> connections = new ArrayList<Connection>();
        if (json == null || json.trim().isEmpty()) {
            return connections;
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException notJson) {
            // Without the cause: its message names the unrecognised word, which a stack trace
            // would print (1.146.0, review 1.145.0 keys-02).
            throw new IllegalArgumentException("the connections are not valid JSON: "
                    + described(notJson));
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("the connections must be a JSON array");
        }
        int number = 0;
        for (JsonNode entry : root) {
            number++;
            String name = text(entry, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("connection " + number + " has no name");
            }
            String kindId = text(entry, "kind");
            if (kindId.isEmpty()) {
                throw new IllegalArgumentException("connection " + number + " has no kind");
            }
            ConnectionKind kind = ConnectionKind.byId(kindId);
            Connection connection = Connection.of(name, kind);
            if (entry.has("baseUrl")) {
                connection = connection.withBaseUrl(text(entry, "baseUrl"));
            }
            connection = connection.withModel(text(entry, "model"));
            if (entry.has("keyVariable")) {
                connection = connection.withKeyVariable(text(entry, "keyVariable"));
            }
            connection = connection
                    .withPlainHttpOnNetwork(entry.path("plainHttpOnNetwork").asBoolean(false))
                    .withContextLength(entry.path("contextLength")
                            .asInt(Connection.DEFAULT_CONTEXT_LENGTH))
                    .withMaxOutputTokens(entry.path("maxOutputTokens")
                            .asInt(Connection.DEFAULT_MAX_OUTPUT_TOKENS));
            connections.add(connection);
        }
        return connections;
    }

    /** The parser's own words and where it stopped, without any of the text it read. */
    private static String described(IOException notJson) {
        if (!(notJson instanceof JsonProcessingException)) {
            return "it could not be read";
        }
        JsonProcessingException parse = (JsonProcessingException) notJson;
        String said = UNRECOGNIZED.matcher(String.valueOf(parse.getOriginalMessage()))
                .replaceAll("Unrecognized token");
        JsonLocation where = parse.getLocation();
        return where == null ? said
                : said + " (line " + where.getLineNr() + ", column " + where.getColumnNr() + ")";
    }

    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        return value == null || value.isNull() ? "" : value.asText().trim();
    }
}
