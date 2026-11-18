package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ODK's targets in plain words, as Build and Release offer them: what a target does, then
 * make's command for those who know ODK - "Check quickly (keeps the imports as they are) - make
 * test_fast". The testers of 1.132.0 asked for "check everything", "check quickly (keep the
 * imports)" and "release" in place of ODK's names.
 */
public final class TargetWords {

    /** Between the words and make's command in a choice. */
    private static final String THEN = " - make ";

    /** A choice of a target without words: make's command alone. */
    private static final String MAKE = "make ";

    private static final Map<String, String> WORDS;

    static {
        Map<String, String> words = new LinkedHashMap<String, String>();
        // test and prepare_release fetch the imports again only with Build's box "Download the
        // imports' sources again" ticked, so their words do not claim it (the 1.154.0 review's
        // M3); the fast targets never fetch them, whatever the boxes say.
        words.put("test", "Check everything");
        words.put("test_fast", "Check quickly (keeps the imports as they are)");
        words.put("prepare_release", "Release");
        words.put("prepare_release_fast", "Release, with the imports as they are");
        words.put("refresh-imports", "Download and rebuild every import");
        WORDS = Collections.unmodifiableMap(words);
    }

    private TargetWords() {
    }

    /** What a target does, in plain words; a target without such words is itself. */
    public static String of(String target) {
        String words = WORDS.get(target);
        return words == null ? target : words;
    }

    /**
     * A target as a choice shows it: its words, then make's command; a target without words is
     * said once, as make's command - "make reason_test".
     */
    public static String choice(String target) {
        String words = WORDS.get(target);
        return words == null ? MAKE + target : words + THEN + target;
    }

    /**
     * The target a choice names - the inverse of {@link #choice}; a bare target, as a plain
     * Makefile's list has it, is itself. Null for null.
     */
    public static String target(String choice) {
        if (choice == null) {
            return null;
        }
        int then = choice.lastIndexOf(THEN);
        if (then >= 0) {
            return choice.substring(then + THEN.length());
        }
        // A make target has no blank in it, so "make " is never the start of a bare one.
        return choice.startsWith(MAKE) ? choice.substring(MAKE.length()) : choice;
    }
}
