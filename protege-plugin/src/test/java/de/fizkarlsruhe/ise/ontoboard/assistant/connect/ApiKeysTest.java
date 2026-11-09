package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Where a call's key comes from (spec D2): this session, then the environment, then remembered. */
class ApiKeysTest {

    private final Connection claude = Connection.of("Claude", ConnectionKind.ANTHROPIC);

    private static Map<String, String> env(String name, String value) {
        Map<String, String> environment = new HashMap<String, String>();
        environment.put(name, value);
        return environment;
    }

    @Test
    void sessionBeatsEnvironmentBeatsRemembered() {
        Map<String, String> environment = env("ANTHROPIC_API_KEY", "from-env");

        ApiKeys.Resolved session = ApiKeys.resolve(claude, "typed", environment, "saved");
        assertEquals("typed", session.getKey());
        assertEquals(ApiKeys.Source.SESSION, session.getSource());

        ApiKeys.Resolved fromEnvironment = ApiKeys.resolve(claude, null, environment, "saved");
        assertEquals("from-env", fromEnvironment.getKey());
        assertEquals(ApiKeys.Source.ENVIRONMENT, fromEnvironment.getSource());

        ApiKeys.Resolved remembered =
                ApiKeys.resolve(claude, "", Collections.<String, String>emptyMap(), "saved");
        assertEquals("saved", remembered.getKey());
        assertEquals(ApiKeys.Source.REMEMBERED, remembered.getSource());
    }

    @Test
    void aPastedKeyIsTrimmed() {
        assertEquals("sk-abc", ApiKeys.resolve(claude, "  sk-abc\n", null, null).getKey());
        assertEquals("sk-env",
                ApiKeys.resolve(claude, null, env("ANTHROPIC_API_KEY", "sk-env\r\n"), null)
                        .getKey());
    }

    /** Review #3 and M8: a key never contains whitespace, so none may reach a header. */
    @Test
    void aKeyWithALineBreakOrInvisibleSpaceIsCleaned() {
        assertEquals("sk-ab", ApiKeys.resolve(claude, "sk-a\nb ​ ", null, null)
                .getKey());
        assertEquals("sk-ab", ApiKeys.clean(" sk-a\r\nb\t"));
    }

    @Test
    void blankValuesAreSkipped() {
        ApiKeys.Resolved resolved = ApiKeys.resolve(claude, "   ",
                env("ANTHROPIC_API_KEY", "  "), "saved");

        assertEquals("saved", resolved.getKey());
    }

    @Test
    void noKeyAnywhereIsNone() {
        ApiKeys.Resolved resolved = ApiKeys.resolve(claude, null, null, null);

        assertEquals("", resolved.getKey());
        assertEquals(ApiKeys.Source.NONE, resolved.getSource());
    }

    @Test
    void theVariableIsReportedWhenItWasUsed() {
        ApiKeys.Resolved resolved =
                ApiKeys.resolve(claude, null, env("ANTHROPIC_API_KEY", "k"), null);

        assertEquals("ANTHROPIC_API_KEY", resolved.getVariable());
    }

    @Test
    void aConnectionWithoutAVariableNeverReadsTheEnvironment() {
        Connection ollama = Connection.of("o", ConnectionKind.OLLAMA);

        assertEquals(ApiKeys.Source.NONE,
                ApiKeys.resolve(ollama, null, env("", "should-not-be-read"), null).getSource());
    }
}
