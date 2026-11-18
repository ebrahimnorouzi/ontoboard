package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.fizkarlsruhe.ise.ontoboard.odk.DosdpTools;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * When Build and Release offer to download DOSDP-Tools: where OntoBoard's own engine refused
 * for want of it - and only there, as the download would cure nothing else.
 */
class DosdpOfferTest {

    private static final String WANTED = "'test' needs something OntoBoard's own engine does "
            + "not do: " + DosdpTools.NOT_INSTALLED + " - OntoBoard downloads it once.";

    @Test
    void buildOffersItWhenNothingRunsForWantOfIt() {
        String nothing = "Docker is not running, and OntoBoard's own engine does not run this "
                + "project either: " + WANTED;

        assertEquals(nothing, DosdpOffer.forBuild(nothing,
                Collections.<String, String>emptyMap()));
    }

    /** The other targets are offered meanwhile; test is the one the form starts with. */
    @Test
    void buildOffersItWhenTestIsNotRunForWantOfIt() {
        Map<String, String> refused = new LinkedHashMap<String, String>();
        refused.put("reason_test", "'reason_test' needs something else.");
        refused.put("test", WANTED);

        assertEquals(WANTED, DosdpOffer.forBuild(null, refused));
    }

    @Test
    void buildDoesNotOfferItForAnythingElse() {
        Map<String, String> refused = Collections.singletonMap("test",
                "'test' needs something OntoBoard's own engine does not do: 'dosdp' is a "
                        + "program OntoBoard's own engine has no stand-in for.");

        assertNull(DosdpOffer.forBuild(null, refused));
        assertNull(DosdpOffer.forBuild("Docker is not running, and OntoBoard's own engine "
                + "does not run this project either: no Makefile.", refused));
        assertNull(DosdpOffer.forBuild(null, Collections.singletonMap("reason_test", WANTED)));
    }

    @Test
    void releaseOffersItWhenItIsRefusedForWantOfIt() {
        assertEquals(WANTED, DosdpOffer.forRelease(WANTED));
        assertNull(DosdpOffer.forRelease("Docker is not running."));
        assertNull(DosdpOffer.forRelease(null));
    }
}
