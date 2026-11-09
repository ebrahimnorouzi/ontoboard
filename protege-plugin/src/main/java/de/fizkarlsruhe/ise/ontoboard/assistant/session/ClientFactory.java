package de.fizkarlsruhe.ise.ontoboard.assistant.session;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelClient;

/** Makes the client for one call; {@code ModelClients::create} outside tests. */
public interface ClientFactory {

    ModelClient create(Connection connection, String apiKey);
}
