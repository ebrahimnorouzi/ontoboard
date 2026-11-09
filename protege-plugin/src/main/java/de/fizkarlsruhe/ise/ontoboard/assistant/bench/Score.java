package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One answer's scores, by name in a fixed order, and whether it passes. */
public final class Score {

    private final LinkedHashMap<String, Double> values;
    private final boolean pass;
    private final String error;

    Score(LinkedHashMap<String, Double> values, boolean pass) {
        this(values, pass, null);
    }

    /** @param error why a check could not be made, for the row; null when every one was */
    Score(LinkedHashMap<String, Double> values, boolean pass, String error) {
        this.values = values;
        this.pass = pass;
        this.error = error;
    }

    public Map<String, Double> getValues() {
        return Collections.unmodifiableMap(values);
    }

    public boolean isPass() {
        return pass;
    }

    /** Why a check could not be made (1.143.0); null when every one was. */
    public String getError() {
        return error;
    }
}
