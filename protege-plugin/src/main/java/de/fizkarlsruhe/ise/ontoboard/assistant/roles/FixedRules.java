package de.fizkarlsruhe.ise.ontoboard.assistant.roles;

/**
 * The rules OntoBoard adds after every role, which no role can edit away because OntoBoard
 * depends on them (spec D4, D7): propose, never decide; "I don't know" is an answer; never an
 * invented IRI; ask for missing context in an {@code ontoboard-need} block. Shown in "What is
 * sent" like everything else.
 */
public final class FixedRules {

    private static final String PATH = "/assistant/fixed-rules.md";

    private FixedRules() {
    }

    public static String text() {
        String text = Resources.text(PATH);
        if (text == null) {
            throw new IllegalStateException(PATH + " is missing from the OntoBoard jar");
        }
        return text.trim();
    }
}
