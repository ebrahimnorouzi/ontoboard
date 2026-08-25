package de.fizkarlsruhe.ise.ontoboard.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * OntoBoard ships no server: a team runs its own and each member configures the plugin, or
 * they collaborate through git instead. These tests pin that two-mode behaviour, and in
 * particular that an incomplete configuration lands in git mode rather than pretending to be
 * live — someone who believes they are collaborating when they are not will lose work.
 */
class CollabSettingsTest {

    private static CollabSettings live() {
        return new CollabSettings("ws://team.example:1235", "board-1", "jwt", "alice", "#111111");
    }

    @Test
    void nothingConfiguredMeansGitMode() {
        assertEquals(CollabSettings.Mode.GIT, CollabSettings.offline().getMode());
        assertTrue(!CollabSettings.offline().isLive());
    }

    @Test
    void everythingConfiguredMeansLiveMode() {
        assertEquals(CollabSettings.Mode.LIVE, live().getMode());
        assertTrue(live().isLive());
    }

    /**
     * A half-configured server fails at connect time and reads as an outage rather than a
     * missing setting, so anything incomplete stays in git mode.
     */
    @Test
    void partialConfigurationStaysInGitMode() {
        assertEquals(CollabSettings.Mode.GIT,
                new CollabSettings("ws://x:1235", "", "jwt", "a", "").getMode());
        assertEquals(CollabSettings.Mode.GIT,
                new CollabSettings("ws://x:1235", "b", "", "a", "").getMode());
        assertEquals(CollabSettings.Mode.GIT,
                new CollabSettings("", "b", "jwt", "a", "").getMode());
    }

    @Test
    void whitespaceOnlyValuesCountAsAbsent() {
        assertEquals(CollabSettings.Mode.GIT,
                new CollabSettings("  ", " ", "\t", "", "").getMode());
    }

    /** "Not connected" tells nobody anything; the message must name what is missing. */
    @Test
    void explainsWhichSettingIsMissing() {
        String why = CollabSettings.offline().explainWhyNotLive();
        assertNotNull(why);
        assertTrue(why.contains("server address"), why);
        assertTrue(why.contains("board id"), why);
        assertTrue(why.contains("access token"), why);
        assertTrue(why.contains("git mode"),
                "it must say what IS working, not only what is not: " + why);
    }

    @Test
    void namesOnlyTheSettingsThatAreActuallyMissing() {
        String why = new CollabSettings("ws://x:1235", "b", "", "a", "").explainWhyNotLive();
        assertTrue(why.contains("access token"), why);
        assertTrue(!why.contains("board id"), "board is configured, so do not report it: " + why);
    }

    @Test
    void explainsNothingWhenLiveModeIsAvailable() {
        assertNull(live().explainWhyNotLive());
    }

    /** The plugin talks to the JSON bridge on 1235, not the browser endpoint on 1234. */
    @Test
    void rejectsAnAddressThatIsNotAWebSocketUrl() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new CollabSettings("http://team.example:1235", "b", "t", "a", "")
                        .validate());
        assertTrue(thrown.getMessage().contains("ws://"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("1235"),
                "the message should point at the right port, since 1234 is the obvious guess");
    }

    @Test
    void acceptsBothPlainAndSecureWebSocketUrls() {
        new CollabSettings("ws://x:1235", "b", "t", "a", "").validate();
        new CollabSettings("wss://x:1235", "b", "t", "a", "").validate();
    }

    /** Git mode has no address to validate, so it must never fail validation. */
    @Test
    void gitModeValidatesWithoutComplaint() {
        CollabSettings.offline().validate();
    }

    @Test
    void aBlankDisplayNameFallsBackRatherThanShowingOthersAnEmptyPeer() {
        String name = new CollabSettings("ws://x:1235", "b", "t", "", "").getDisplayName();
        assertNotNull(name);
        assertTrue(name.trim().length() > 0);
    }

    @Test
    void aColourIsAlwaysAvailableForTheCursor() {
        assertEquals("#4A90D9", CollabSettings.offline().getColour());
        assertEquals("#111111", live().getColour());
    }
}
