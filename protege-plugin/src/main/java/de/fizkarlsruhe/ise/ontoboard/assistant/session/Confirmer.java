package de.fizkarlsruhe.ise.ontoboard.assistant.session;

/**
 * Asks the person, once per session, before the first request leaves for a cloud model
 * (spec D3): "This sends about N tokens about X to api.anthropic.com."
 */
public interface Confirmer {

    boolean confirmCloudSend(String host, int estimatedTokens, String subject);
}
