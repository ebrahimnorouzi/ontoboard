package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import java.util.List;

/**
 * Whether a connection works, for *Check requirements...*: it asks for the model list and
 * nothing else, so checking costs no tokens and sends nothing about the ontology.
 */
public final class ConnectionCheck {

    /** Ready to use, set up but not working, or not set up at all. */
    public enum State {
        READY,
        NOT_WORKING,
        NOT_SET_UP
    }

    /** What was found, and what to do about it (empty when nothing). */
    public static final class Result {
        private final State state;
        private final String detail;
        private final String remedy;

        Result(State state, String detail, String remedy) {
            this.state = state;
            this.detail = detail;
            this.remedy = remedy;
        }

        public State getState() {
            return state;
        }

        public String getDetail() {
            return detail;
        }

        public String getRemedy() {
            return remedy;
        }
    }

    static final String PICK_A_MODEL =
            "Pick a model from the list in the Assistant column's Settings tab.";

    private ConnectionCheck() {
    }

    public static Result notSetUp() {
        return new Result(State.NOT_SET_UP, "not set up",
                "Set up a model in the Assistant column's Settings tab: a key for a cloud model,"
                        + " or Ollama on this machine.");
    }

    public static Result run(Connection connection, String apiKey) {
        if (connection == null) {
            return notSetUp();
        }
        List<String> models;
        try {
            models = ModelClients.create(connection, apiKey).listModels();
        } catch (ModelCallException failure) {
            return new Result(State.NOT_WORKING, failure.getMessage(), "");
        }
        String where = connection.getKind().getLabel() + " at " + connection.getHost() + ": "
                + models.size() + (models.size() == 1 ? " model" : " models");
        String model = connection.getModel();
        if (model.isEmpty()) {
            return new Result(State.NOT_WORKING, where + "; no model is chosen", PICK_A_MODEL);
        }
        if (!offers(models, model, connection.getKind())) {
            return new Result(State.NOT_WORKING, model + " is not on " + connection.getHost(),
                    PICK_A_MODEL);
        }
        return new Result(State.READY, where + "; " + model + " is there", "");
    }

    /** Ollama reads a name without a tag as {@code :latest}, and lists it with the tag. */
    private static boolean offers(List<String> models, String model, ConnectionKind kind) {
        if (models.contains(model)) {
            return true;
        }
        return kind == ConnectionKind.OLLAMA && !model.contains(":")
                && models.contains(model + ":latest");
    }
}
