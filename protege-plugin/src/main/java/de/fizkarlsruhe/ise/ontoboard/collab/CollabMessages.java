package de.fizkarlsruhe.ise.ontoboard.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes and decodes the bridge protocol.
 *
 * <p>Kept separate from the socket so the wire format is testable without a server — which
 * matters more here than usual, because a field-name mismatch with
 * {@code collab/bridge.mjs} does not throw. The bridge would reject an unknown message and
 * close, and a subtly wrong {@code data} payload would reach the web client and be ignored in
 * silence. Both are far easier to catch by asserting the encoding than by watching two clients
 * fail to agree.
 *
 * <p>Every message shares one envelope with a {@code t} discriminator. See the protocol block
 * at the top of {@code bridge.mjs}; the two must be changed together.
 */
public final class CollabMessages {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What a decoded server message turned out to be. */
    public enum Kind {
        WELCOME,
        OPERATION,
        PEERS,
        ERROR,
        /** Understood JSON that is not a message we handle — ignore, do not fail. */
        UNKNOWN
    }

    /** A decoded server message. Only the fields relevant to {@link #getKind()} are set. */
    public static final class Incoming {
        private final Kind kind;
        private final String user;
        private final String message;
        private final OntologyOperation operation;
        private final List<PeerPresence> peers;

        Incoming(Kind kind, String user, String message, OntologyOperation operation,
                List<PeerPresence> peers) {
            this.kind = kind;
            this.user = user;
            this.message = message;
            this.operation = operation;
            this.peers = peers;
        }

        public Kind getKind() {
            return kind;
        }

        /** Set for {@link Kind#WELCOME}: the name the server authenticated us as. */
        public String getUser() {
            return user;
        }

        /** Set for {@link Kind#ERROR}: why the server refused us. */
        public String getMessage() {
            return message;
        }

        public OntologyOperation getOperation() {
            return operation;
        }

        public List<PeerPresence> getPeers() {
            return peers;
        }
    }

    private CollabMessages() {
    }

    /** The first message on a connection; nothing else is accepted before it. */
    public static String hello(CollabSettings settings) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("t", "hello");
        node.put("board", settings.getBoard());
        node.put("token", settings.getToken());
        node.put("colour", settings.getColour());
        // Optional on the wire and relayed rather than checked: the bridge has no opinion about
        // which ontology anybody edits, but it is the only thing that can put two peers in touch
        // with each other's answer.
        if (!settings.getOntologyIri().isEmpty()) {
            node.put("ontology", settings.getOntologyIri());
        }
        return node.toString();
    }

    public static String operation(OntologyOperation operation) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("t", "op");
        ObjectNode op = envelope.putObject("op");
        op.put("id", operation.getId());
        op.put("type", operation.getType());
        op.put("timestamp", operation.getTimestamp());
        op.put("userId", operation.getUserId());
        op.set("data", MAPPER.valueToTree(operation.getData()));
        return envelope.toString();
    }

    /** Coordinates are graph-space; see {@link PeerPresence}. */
    public static String presence(String user, String colour, double x, double y,
            String selection) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("t", "presence");
        node.put("user", user);
        node.put("colour", colour);
        node.put("x", x);
        node.put("y", y);
        if (selection == null) {
            node.putNull("selection");
        } else {
            node.put("selection", selection);
        }
        return node.toString();
    }

    /**
     * Decodes a server message.
     *
     * <p>Never throws. A collaboration transport that can crash the editor on a malformed
     * frame is worse than one that drops it: the ontology being edited matters more than the
     * message. Anything unrecognised comes back as {@link Kind#UNKNOWN}.
     */
    public static Incoming decode(String raw, long receivedAt) {
        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (Exception notJson) {
            return new Incoming(Kind.UNKNOWN, null, null, null, null);
        }
        if (root == null || !root.isObject()) {
            return new Incoming(Kind.UNKNOWN, null, null, null, null);
        }
        String type = text(root, "t");

        if ("welcome".equals(type)) {
            return new Incoming(Kind.WELCOME, text(root, "user"), null, null,
                    peers(root.get("peers"), receivedAt));
        }
        if ("error".equals(type)) {
            return new Incoming(Kind.ERROR, null, text(root, "message"), null, null);
        }
        if ("peers".equals(type)) {
            return new Incoming(Kind.PEERS, null, null, null,
                    peers(root.get("peers"), receivedAt));
        }
        if ("op".equals(type)) {
            OntologyOperation operation = operation(root.get("op"));
            if (operation == null) {
                // A malformed or unknown-type operation is dropped rather than guessed at.
                return new Incoming(Kind.UNKNOWN, null, null, null, null);
            }
            return new Incoming(Kind.OPERATION, null, null, operation, null);
        }
        return new Incoming(Kind.UNKNOWN, null, null, null, null);
    }

    private static OntologyOperation operation(JsonNode op) {
        if (op == null || !op.isObject()) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        JsonNode payload = op.get("data");
        if (payload != null && payload.isObject()) {
            Iterator<String> names = payload.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                data.put(name, scalar(payload.get(name)));
            }
        }
        try {
            return new OntologyOperation(text(op, "id"), text(op, "type"),
                    op.has("timestamp") ? op.get("timestamp").asLong() : 0L,
                    text(op, "userId"), data);
        } catch (IllegalArgumentException rejected) {
            return null;
        }
    }

    private static List<PeerPresence> peers(JsonNode array, long receivedAt) {
        List<PeerPresence> peers = new ArrayList<PeerPresence>();
        if (!(array instanceof ArrayNode)) {
            return peers;
        }
        for (JsonNode peer : array) {
            if (!peer.isObject()) {
                continue;
            }
            String user = text(peer, "user");
            if (user == null || user.trim().isEmpty()) {
                // An unnamed cursor cannot be attributed, so it is not worth drawing.
                continue;
            }
            peers.add(new PeerPresence(user, text(peer, "colour"),
                    peer.has("x") ? peer.get("x").asDouble() : 0,
                    peer.has("y") ? peer.get("y").asDouble() : 0,
                    text(peer, "selection"), receivedAt, text(peer, "ontology")));
        }
        return peers;
    }

    /** Keeps payloads to JSON scalars; nested structures are stringified rather than lost. */
    private static Object scalar(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.isIntegralNumber() ? (Object) node.asLong() : (Object) node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
