package de.fizkarlsruhe.ise.ontoboard.assistant.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * Review 1.144.0 privacy-01: what OntoBoard writes is redacted against the keys it knows, and a
 * key replaced, forgotten or deleted in Settings was no longer known - so a conversation or role
 * that held it was written with it in clear. Every key of the session stays known.
 */
class SessionKeysTest {

    private static Collection<String> seen() {
        return SessionKeys.seen(Collections.<String>emptyList());
    }

    @Test
    void aReplacedKeyIsStillSeen() {
        SessionKeys.put("SessionKeysTest", "Rq1aReplacedKey0123456789");
        SessionKeys.put("SessionKeysTest", "Rq2theNewKey0123456789xy");
        SessionKeys.clear("SessionKeysTest");

        assertTrue(seen().contains("Rq1aReplacedKey0123456789"), seen().toString());
        assertTrue(seen().contains("Rq2theNewKey0123456789xy"), seen().toString());
    }

    @Test
    void aKeyKnownOnceIsSeenFromThenOn() {
        SessionKeys.seen(Collections.singletonList("Kn0wnOnce0123456789abcd"));

        assertTrue(seen().contains("Kn0wnOnce0123456789abcd"), seen().toString());
    }

    @Test
    void aNotedKeyIsSeen() {
        SessionKeys.note(Collections.singletonList("N0tedBeforeForgotten0123"));

        assertTrue(seen().contains("N0tedBeforeForgotten0123"), seen().toString());
    }

    @Test
    void blankKeysAreNotSeen() {
        SessionKeys.note(Arrays.asList(null, "", "   "));

        assertFalse(seen().contains(null));
        assertFalse(seen().contains(""));
        assertFalse(seen().contains("   "));
    }
}
