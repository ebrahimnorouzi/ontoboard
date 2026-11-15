package de.fizkarlsruhe.ise.ontoboard.assistant.host;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ApiKeys;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionCheck;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.SessionKeys;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.SettingsStore;
import de.fizkarlsruhe.ise.ontoboard.odk.Toolchain;
import java.util.Map;

/**
 * The assistant's row in *Check requirements...*: the active connection, checked by asking for
 * its model list - no tokens spent, nothing about the ontology sent. A row and not a capability,
 * so someone who never sets up a model still reads "everything available".
 */
public final class AssistantRequirement {

    static final String NAME = "Assistant model";

    private AssistantRequirement() {
    }

    public static Toolchain.Tool probe() {
        return probe(AssistantPreferences.load(), System.getenv());
    }

    public static Toolchain.Tool probe(SettingsStore store, Map<String, String> environment) {
        Connection active = store.active();
        if (active == null) {
            ConnectionCheck.Result notSetUp = ConnectionCheck.notSetUp();
            return Toolchain.Tool.of(NAME, Toolchain.State.ABSENT, notSetUp.getDetail(),
                    notSetUp.getRemedy());
        }
        String key = ApiKeys.resolve(active, SessionKeys.get(active.getName()), environment,
                store.rememberedKey(active.getName())).getKey();
        ConnectionCheck.Result result = ConnectionCheck.run(active, key);
        Toolchain.State state;
        switch (result.getState()) {
            case READY:
                state = Toolchain.State.PRESENT;
                break;
            case NOT_WORKING:
                state = Toolchain.State.NOT_WORKING;
                break;
            default:
                state = Toolchain.State.ABSENT;
                break;
        }
        return Toolchain.Tool.of(NAME, state, result.getDetail(), result.getRemedy());
    }
}
