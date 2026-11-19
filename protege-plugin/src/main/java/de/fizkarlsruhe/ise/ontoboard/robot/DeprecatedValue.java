package de.fizkarlsruhe.ise.ontoboard.robot;

import java.util.Locale;
import org.semanticweb.owlapi.model.OWLLiteral;

/**
 * What an {@code owl:deprecated} value says, read without the OWL API's {@code parseBoolean()}.
 *
 * <p>That method throws for every string and every literal with a language - "true" too - on
 * OWL API 4.5.9 and 4.5.29 alike. nfdicore v3.0.0 and v3.0.1 give {@code owl:deprecated} a
 * sentence, "please use BFO2020 property ...", and History fell over on nfdicore (1.155.0).
 * The value is read as xsd:boolean's lexical form is: "true" or "1" deprecates, whatever the
 * datatype; anything else does not.
 */
public final class DeprecatedValue {

    private DeprecatedValue() {
    }

    /** Whether the value deprecates: "true" or "1", any case, any datatype. Never throws. */
    public static boolean isTrue(OWLLiteral value) {
        String said = lexical(value);
        return "true".equals(said) || "1".equals(said);
    }

    /** Whether the value is neither true nor false: worth a line in a release's notes. */
    public static boolean isNoBoolean(OWLLiteral value) {
        String said = lexical(value);
        return said != null && !"true".equals(said) && !"1".equals(said)
                && !"false".equals(said) && !"0".equals(said);
    }

    private static String lexical(OWLLiteral value) {
        return value == null ? null : value.getLiteral().trim().toLowerCase(Locale.ROOT);
    }
}
