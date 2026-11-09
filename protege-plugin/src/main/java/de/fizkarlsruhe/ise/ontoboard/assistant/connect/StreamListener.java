package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

/** Receives an answer as it is written. Called on the thread making the call. */
public interface StreamListener {

    void onText(String delta);
}
