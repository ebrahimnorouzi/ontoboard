package de.fizkarlsruhe.ise.ontoboard.assistant.context;

/**
 * An estimate of how many tokens a text costs: a quarter of its characters.
 *
 * <p>Deliberately crude and always shown with "~": every model counts differently, no tokenizer
 * is embedded, and the number exists to let a person see that "whole ontology" is two hundred
 * times "this term" before they send it - not to bill anybody.
 */
public final class Tokens {

    private Tokens() {
    }

    public static int estimate(String text) {
        return text == null ? 0 : (text.length() + 3) / 4;
    }
}
