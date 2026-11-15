package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import java.util.List;

/**
 * One kind of model server, spoken to over plain HTTPS (spec D1): no vendor SDK.
 *
 * <p>An instance belongs to one connection and one key. {@link #stop} may be called from any
 * thread and makes a call in progress end at once with {@link ModelCallException.Kind#STOPPED}.
 */
public interface ModelClient {

    /** The models the server offers, without spending tokens. */
    List<String> listModels() throws ModelCallException;

    /** Sends the request and streams the answer into the listener as it arrives. */
    Reply chat(ChatRequest request, StreamListener listener) throws ModelCallException;

    void stop();
}
