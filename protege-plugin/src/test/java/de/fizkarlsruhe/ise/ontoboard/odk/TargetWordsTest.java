package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * ODK's targets in plain words, with the target and make's command beside them for those who
 * know ODK (the testers of 1.132.0: "check everything", "check quickly (keep the imports)",
 * "release").
 */
class TargetWordsTest {

    /**
     * What each target is, whatever Build's boxes say: test and prepare_release fetch the imports
     * only with "Download the imports' sources again" ticked, so their words do not claim it
     * (the 1.154.0 review's M3); the fast targets never fetch them.
     */
    @Test
    void eachOfOdksMainTargetsHasPlainWords() {
        assertEquals("Check everything", TargetWords.of("test"));
        assertEquals("Check quickly (keeps the imports as they are)",
                TargetWords.of("test_fast"));
        assertEquals("Release", TargetWords.of("prepare_release"));
        assertEquals("Release, with the imports as they are",
                TargetWords.of("prepare_release_fast"));
        assertEquals("Download and rebuild every import", TargetWords.of("refresh-imports"));
    }

    @Test
    void anyOtherTargetIsItself() {
        assertEquals("reason_test", TargetWords.of("reason_test"));
        assertEquals("all", TargetWords.of("all"));
    }

    /** The words, then make's command; a target without words is said once, as the command. */
    @Test
    void aChoiceSaysTheWordsAndMakesCommand() {
        assertEquals("Check everything - make test", TargetWords.choice("test"));
        assertEquals("Release, with the imports as they are - make prepare_release_fast",
                TargetWords.choice("prepare_release_fast"));
        assertEquals("make reason_test", TargetWords.choice("reason_test"));
    }

    /** What is chosen is the target the choice names - and a bare target is itself. */
    @Test
    void theTargetOfAChoiceIsTheOneItNames() {
        for (String target : Arrays.asList("test", "test_fast", "prepare_release",
                "prepare_release_fast", "refresh-imports", "reason_test", "all", "clean",
                "make")) {
            assertEquals(target, TargetWords.target(TargetWords.choice(target)), target);
            assertEquals(target, TargetWords.target(target), target);
        }
        assertEquals(null, TargetWords.target(null));
    }
}
