package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The id of a property arrow, which one class writes and another reads.
 *
 * <p>It exists because the writer and the reader each used to hold their own copy of the
 * format. The tests here are about the two things a second copy would get wrong: a round trip,
 * and the refusals.
 */
class PropertyEdgeIdTest {

    private static final String A = "http://example.org/o#Pizza";
    private static final String R = "http://example.org/o#hasTopping";
    private static final String B = "http://example.org/o#Topping";

    /** Everything written comes back unchanged. */
    @Test
    void everyOriginAndQualifierRoundTrips() {
        for (PropertyEdgeId.Origin origin : PropertyEdgeId.Origin.values()) {
            for (String qualifier : new String[] {"some", "only", "value", "min2", "max1",
                    "exactly10"}) {
                String id = PropertyEdgeId.of(origin, qualifier, A, R, B);

                PropertyEdgeId.Parsed back = PropertyEdgeId.parse(id);

                assertNotNull(back, id);
                assertEquals(origin, back.getOrigin(), id);
                assertEquals(qualifier, back.getQualifier(), id);
                assertEquals(A, back.getSubject());
                assertEquals(R, back.getProperty());
                assertEquals(B, back.getFiller());
            }
        }
    }

    /** The cardinality and the shape come apart cleanly. */
    @Test
    void aQualifierSplitsIntoShapeAndCount() {
        PropertyEdgeId.Parsed max = PropertyEdgeId.parse(
                PropertyEdgeId.of(PropertyEdgeId.Origin.SUBCLASS, "max1", A, R, B));
        assertEquals("max", max.getShape());
        assertEquals(1, max.getCardinality());

        PropertyEdgeId.Parsed exactly = PropertyEdgeId.parse(
                PropertyEdgeId.of(PropertyEdgeId.Origin.SUBCLASS, "exactly12", A, R, B));
        assertEquals("exactly", exactly.getShape());
        assertEquals(12, exactly.getCardinality());

        PropertyEdgeId.Parsed some = PropertyEdgeId.parse(
                PropertyEdgeId.of(PropertyEdgeId.Origin.SUBCLASS, "some", A, R, B));
        assertEquals("some", some.getShape());
        assertEquals(-1, some.getCardinality(), "no number means no cardinality, not zero");
    }

    /**
     * Ids belonging to anything else are not claimed.
     *
     * <p>Every one of these is a real id this canvas draws, and claiming one would mean parsing
     * somebody else's edge and deleting from a guess.
     */
    @Test
    void theOtherKindsOfEdgeAreNotClaimed() {
        for (String other : new String[] {"sub|" + A + "|" + B,
                "rest|some|" + A + "|" + R + "|" + B,
                "data|" + A + "|" + R + "|" + B,
                "dr|" + A + "|" + R + "|" + B,
                "type|" + A + "|" + B,
                "subprop|" + R + "|" + R,
                "inf|" + A + "|" + B,
                "inft|" + A + "|" + B}) {
            assertFalse(PropertyEdgeId.is(other), other);
            assertNull(PropertyEdgeId.parse(other), other);
        }
    }

    /**
     * A malformed id yields null rather than an exception or a half-read result.
     *
     * <p>These arrive from a peer in a shared session, so the shapes worth checking are the
     * degenerate ones. {@code "pe|"} must not come back as a zero-length array - that trap cost
     * {@code AxiomRemoval} an {@code ArrayIndexOutOfBoundsException} on the event thread once
     * already, in place of the refusal it documents.
     */
    @Test
    void aMalformedIdIsRefusedRatherThanGuessedAt() {
        for (String broken : new String[] {"pe|", "pe||||", "pe|sub", "pe|sub|some|" + A,
                "pe|sub|some|" + A + "|" + R,
                "pe|sub|some|" + A + "|" + R + "|" + B + "|extra",
                "pe|nosuchorigin|some|" + A + "|" + R + "|" + B,
                "pe|sub|some||" + R + "|" + B,
                "pe|sub||" + A + "|" + R + "|" + B,
                null}) {
            assertNull(PropertyEdgeId.parse(broken), String.valueOf(broken));
        }
    }

    /**
     * Exactly two origins may be deleted from, and the other two say why not.
     *
     * <p>This is the reason the origin is in the id at all. A restriction inside a conjunction
     * shares its axiom with the other conjuncts; one inside an equivalence shares it with the
     * whole definition of the class. Deleting either would take away far more than the arrow
     * that was right-clicked.
     */
    @Test
    void onlyTheOriginsThatOwnTheirAxiomAreRetractable() {
        assertTrue(PropertyEdgeId.Origin.SUBCLASS.isRetractable());
        assertTrue(PropertyEdgeId.Origin.SCOPED_DOMAIN.isRetractable());
        assertFalse(PropertyEdgeId.Origin.CONJUNCT.isRetractable());
        assertFalse(PropertyEdgeId.Origin.EQUIVALENCE.isRetractable());
    }

    /** A refusal says what would be lost and what to do instead; a retractable one says nothing. */
    @Test
    void everyRefusalExplainsItself() {
        for (PropertyEdgeId.Origin origin : PropertyEdgeId.Origin.values()) {
            if (origin.isRetractable()) {
                assertNull(origin.getRefusal(), origin.toString());
                continue;
            }
            String refusal = origin.getRefusal();
            assertNotNull(refusal, origin.toString());
            assertTrue(refusal.contains("Protege"),
                    origin + " must say where the edit can be made: " + refusal);
            assertTrue(refusal.length() > 60,
                    origin + " must say what would be lost, not just refuse: " + refusal);
        }
    }

    /** Each origin's token is distinct, or two shapes would parse as one. */
    @Test
    void theTokensAreDistinct() {
        java.util.Set<String> tokens = new java.util.HashSet<String>();
        for (PropertyEdgeId.Origin origin : PropertyEdgeId.Origin.values()) {
            assertTrue(tokens.add(origin.getToken()), origin + " reuses a token");
            assertFalse(origin.getToken().contains("|"), "a token may not contain the separator");
        }
    }
}
