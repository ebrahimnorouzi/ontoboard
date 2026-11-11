package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

/** A finished answer: its text, what it cost in tokens when the server said, and how long it took. */
public final class Reply {

    /** For a count the server did not report. */
    public static final int UNKNOWN = -1;

    private final String text;
    private final int inputTokens;
    private final int outputTokens;
    private final String stopReason;
    private final long millis;

    public Reply(String text, int inputTokens, int outputTokens, String stopReason, long millis) {
        this.text = text == null ? "" : text;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.stopReason = stopReason == null ? "" : stopReason;
        this.millis = millis;
    }

    public String getText() {
        return text;
    }

    /** {@link #UNKNOWN} when the server did not say. */
    public int getInputTokens() {
        return inputTokens;
    }

    /** {@link #UNKNOWN} when the server did not say. */
    public int getOutputTokens() {
        return outputTokens;
    }

    /** As the server named it ("end_turn", "stop", "length", ...), or empty. */
    public String getStopReason() {
        return stopReason;
    }

    public long getMillis() {
        return millis;
    }
}
