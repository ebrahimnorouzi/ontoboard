package de.fizkarlsruhe.ise.ontoboard.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The wire format is asserted here rather than discovered by running two clients, because a
 * mismatch with collab/bridge.mjs does not throw: the bridge closes on an unknown message,
 * and a subtly wrong payload reaches the web client and is ignored in silence.
 */
class CollabMessagesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    private static Map<String, Object> data(String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put(key, value);
        return data;
    }

    // ---------- outgoing ----------

    /** Field names must match bridge.mjs exactly: board, token, colour under t="hello". */
    @Test
    void helloCarriesTheFieldsTheBridgeRequires() throws Exception {
        JsonNode node = parse(CollabMessages.hello(
                new CollabSettings("ws://x:1235", "board-7", "jwt-abc", "alice", "#ABCDEF")));

        assertEquals("hello", node.get("t").asText());
        assertEquals("board-7", node.get("board").asText());
        assertEquals("jwt-abc", node.get("token").asText());
        assertEquals("#ABCDEF", node.get("colour").asText());
    }

    @Test
    void operationIsWrappedInTheEnvelopeTheBridgeExpects() throws Exception {
        OntologyOperation op = new OntologyOperation("op-1", "addClass", 1234L, "alice",
                data("iri", "http://example.org/o#Person"));

        JsonNode node = parse(CollabMessages.operation(op));

        assertEquals("op", node.get("t").asText());
        JsonNode inner = node.get("op");
        assertEquals("op-1", inner.get("id").asText());
        assertEquals("addClass", inner.get("type").asText());
        assertEquals(1234L, inner.get("timestamp").asLong());
        assertEquals("alice", inner.get("userId").asText());
        assertEquals("http://example.org/o#Person", inner.get("data").get("iri").asText());
    }

    @Test
    void presenceSendsNumericCoordinatesTheBridgeWillAccept() throws Exception {
        JsonNode node = parse(CollabMessages.presence("bob", "#111111", 12.5, -3.0, "iri:x"));

        assertEquals("presence", node.get("t").asText());
        assertTrue(node.get("x").isNumber(), "the bridge rejects non-numeric coordinates");
        assertTrue(node.get("y").isNumber());
        assertEquals(12.5, node.get("x").asDouble());
        assertEquals("iri:x", node.get("selection").asText());
    }

    @Test
    void presenceSendsAnExplicitNullSelectionRatherThanOmittingIt() throws Exception {
        JsonNode node = parse(CollabMessages.presence("bob", "#111111", 0, 0, null));
        assertTrue(node.has("selection"));
        assertTrue(node.get("selection").isNull());
    }

    // ---------- incoming ----------

    @Test
    void decodesWelcomeWithItsPeerList() {
        CollabMessages.Incoming in = CollabMessages.decode(
                "{\"t\":\"welcome\",\"user\":\"alice\",\"peers\":"
                        + "[{\"user\":\"bob\",\"colour\":\"#222222\",\"x\":1,\"y\":2}]}", 500L);

        assertEquals(CollabMessages.Kind.WELCOME, in.getKind());
        assertEquals("alice", in.getUser());
        assertEquals(1, in.getPeers().size());
        assertEquals("bob", in.getPeers().get(0).getUser());
        assertEquals(500L, in.getPeers().get(0).getSeenAt(),
                "seenAt must come from receipt time, or staleness cannot be judged");
    }

    @Test
    void decodesAnOperation() {
        CollabMessages.Incoming in = CollabMessages.decode(
                "{\"t\":\"op\",\"op\":{\"id\":\"o1\",\"type\":\"addSubClassOf\","
                        + "\"timestamp\":9,\"userId\":\"bob\",\"data\":{\"childIri\":\"a\"}}}", 0L);

        assertEquals(CollabMessages.Kind.OPERATION, in.getKind());
        assertEquals("addSubClassOf", in.getOperation().getType());
        assertEquals("bob", in.getOperation().getUserId());
        assertEquals("a", in.getOperation().getData().get("childIri"));
    }

    @Test
    void decodesAnErrorSoTheReasonCanBeShown() {
        CollabMessages.Incoming in = CollabMessages.decode(
                "{\"t\":\"error\",\"message\":\"invalid token: jwt expired\"}", 0L);

        assertEquals(CollabMessages.Kind.ERROR, in.getKind());
        assertTrue(in.getMessage().contains("expired"),
                "the server's reason must survive decoding, or the user gets no explanation");
    }

    /**
     * A transport that can crash the editor on a bad frame is worse than one that drops it —
     * the ontology being edited matters more than the message.
     */
    @Test
    void malformedInputIsDroppedRatherThanThrown() {
        for (String bad : new String[] {"", "not json", "[]", "null", "{}", "{\"t\":42}",
            "{\"t\":\"op\"}", "{\"t\":\"op\",\"op\":\"nope\"}"}) {
            CollabMessages.Incoming in = CollabMessages.decode(bad, 0L);
            assertNotNull(in, "decode must never return null for: " + bad);
            assertEquals(CollabMessages.Kind.UNKNOWN, in.getKind(), "for input: " + bad);
        }
    }

    /** An operation type the web client would ignore is dropped here instead. */
    @Test
    void anUnknownOperationTypeIsDroppedNotGuessedAt() {
        CollabMessages.Incoming in = CollabMessages.decode(
                "{\"t\":\"op\",\"op\":{\"id\":\"o1\",\"type\":\"addWidget\",\"data\":{}}}", 0L);
        assertEquals(CollabMessages.Kind.UNKNOWN, in.getKind());
    }

    /** Dedup is by id; an operation without one would echo between clients forever. */
    @Test
    void anOperationWithoutAnIdIsDropped() {
        CollabMessages.Incoming in = CollabMessages.decode(
                "{\"t\":\"op\",\"op\":{\"type\":\"addClass\",\"data\":{}}}", 0L);
        assertEquals(CollabMessages.Kind.UNKNOWN, in.getKind());
    }

    @Test
    void peersWithoutANameAreSkippedSinceTheyCannotBeAttributed() {
        CollabMessages.Incoming in = CollabMessages.decode(
                "{\"t\":\"peers\",\"peers\":[{\"user\":\"\"},{\"user\":\"amy\",\"x\":1,\"y\":1}]}",
                0L);

        assertEquals(1, in.getPeers().size());
        assertEquals("amy", in.getPeers().get(0).getUser());
    }

    @Test
    void aPeerListThatIsNotAnArrayYieldsNoPeersRatherThanFailing() {
        CollabMessages.Incoming in =
                CollabMessages.decode("{\"t\":\"peers\",\"peers\":\"oops\"}", 0L);
        assertEquals(CollabMessages.Kind.PEERS, in.getKind());
        assertTrue(in.getPeers().isEmpty());
    }

    // ---------- round trip ----------

    @Test
    void anOperationSurvivesEncodingAndDecoding() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("iri", "http://example.org/o#Person");
        payload.put("x", 12L);
        payload.put("visible", true);
        OntologyOperation sent = new OntologyOperation("id-9", "updateClass", 77L, "amy", payload);

        CollabMessages.Incoming in =
                CollabMessages.decode(CollabMessages.operation(sent), 0L);

        OntologyOperation received = in.getOperation();
        assertEquals(sent.getId(), received.getId());
        assertEquals(sent.getType(), received.getType());
        assertEquals(sent.getTimestamp(), received.getTimestamp());
        assertEquals(sent.getUserId(), received.getUserId());
        assertEquals("http://example.org/o#Person", received.getIri());
        assertEquals(12L, received.getData().get("x"));
        assertEquals(Boolean.TRUE, received.getData().get("visible"));
    }

    // ---------- the type vocabulary ----------

    @Test
    void theTypeVocabularyMatchesTheBridgeAndWebClient() {
        assertEquals(17, OntologyOperation.TYPES.size(),
                "TYPES must stay in step with useOperationSync.ts and bridge.mjs");
        for (String expected : new String[] {"addClass", "updateClass", "removeClass",
            "addProperty", "removeProperty", "addSubClassOf", "addIndividual",
            "updateIndividual", "addLiteral", "updateLiteral", "removeLiteral",
            "addStickyNote", "updateStickyNote", "removeStickyNote", "addFrame",
            "updateFrame", "removeFrame"}) {
            assertTrue(OntologyOperation.TYPES.contains(expected), "missing " + expected);
        }
    }

    @Test
    void constructingAnUnknownTypeFailsLoudlyWithAnActionableMessage() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new OntologyOperation("i", "addWidget", 0L, "u", null));
        assertTrue(thrown.getMessage().contains("useOperationSync.ts"),
                "the message must say where else the vocabulary lives: " + thrown.getMessage());
    }

    @Test
    void localOperationsGetAFreshIdEachTime() {
        OntologyOperation first = OntologyOperation.local("addClass", "u", null);
        OntologyOperation second = OntologyOperation.local("addClass", "u", null);
        assertNotNull(first.getId());
        assertTrue(!first.getId().equals(second.getId()), "ids must be unique for dedup");
    }

    @Test
    void anAbsentIriReadsAsNullRatherThanThrowing() {
        assertNull(new OntologyOperation("i", "addClass", 0L, "u", null).getIri());
    }
}
