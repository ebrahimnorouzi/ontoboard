package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ApiKeys;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import de.fizkarlsruhe.ise.ontoboard.assistant.host.AssistantPreferences;
import de.fizkarlsruhe.ise.ontoboard.assistant.host.FakePreferences;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.SessionKeys;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Review 1.145.0 keys-01: a key goes only to the host it was given for. Picking a preset over a
 * saved connection kept its name, so its typed and remembered key went to the preset's provider
 * in "Test connection" and "Fetch the list", and in every chat after Save. Every key here is made
 * up.
 */
class ConnectionEditorTest {

    private static final String TYPED = "Tp4qR8" + "sV2wX6yZ0aB3cD7eF1gH5jK9mN";
    private static final String REMEMBERED = "Rm2bN6" + "vC0xZ4lK8jH1gF5dS9aP3oI7uY";
    private static final String MISTRAL = "Ms5tR1" + "aL9kE3yQ7wE2rT6yU0iO4pA8sD";
    private static final Map<String, String> NO_ENVIRONMENT = Collections.emptyMap();

    private final AssistantPreferences store = new AssistantPreferences(new FakePreferences());

    @BeforeEach
    void anOpenAiConnectionWithItsKeys() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing needs a display");
        store.saveConnections(Arrays.asList(
                Connection.of("OpenAI", ConnectionKind.OPENAI_COMPATIBLE).withModel("gpt-x"),
                Connection.of("Mistral", ConnectionKind.OPENAI_COMPATIBLE)
                        .withBaseUrl("https://api.mistral.ai/v1")
                        .withKeyVariable("MISTRAL_API_KEY")));
        store.setActiveConnection("OpenAI");
        store.rememberKey("OpenAI", REMEMBERED);
        SessionKeys.put("OpenAI", TYPED);
    }

    @AfterEach
    void forgetTheTypedKeys() {
        for (String name : new String[] {"OpenAI", "Mistral", "Mistral 2", "OpenAI work",
            "Elsewhere", "OpenAI 2", "Claude", "Claude 2"}) {
            SessionKeys.clear(name);
        }
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<T>();
        AtomicReference<Exception> failure = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (Exception thrown) {
                failure.set(thrown);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    /** The editor showing the active connection, as Settings opens it. */
    private ConnectionEditor editor(Map<String, String> environment) {
        ConnectionEditor editor = new ConnectionEditor(store, environment, () -> { });
        editor.load(store.active());
        return editor;
    }

    private Connection stored(String name) {
        for (Connection connection : store.connections()) {
            if (connection.getName().equals(name)) {
                return connection;
            }
        }
        return null;
    }

    /** (a) A preset picked over a saved connection starts a new one, and asks for its key. */
    @Test
    void aPresetPickedOverASavedConnectionStartsANewOne() throws Exception {
        Object[] seen = onEdt(() -> {
            ConnectionEditor editor = editor(Collections.singletonMap("MISTRAL_API_KEY",
                    MISTRAL));
            editor.typeKey("Hf8qW2" + "eR6tY0uI4oP8aS2dF6gH0jK4lZ");
            editor.choosePreset("Mistral");
            Object[] shown = {editor.read(), editor.rememberBox().isSelected(),
                editor.typedKey(), editor.keyToSend()};
            editor.save();
            return shown;
        });

        Connection shown = (Connection) seen[0];
        assertEquals("Mistral 2", shown.getName(), "the preset's label, made unique");
        assertEquals("https://api.mistral.ai/v1", shown.getBaseUrl());
        assertEquals(Boolean.FALSE, seen[1], "Remember is unticked");
        assertEquals("", seen[2], "the key field is emptied");
        assertEquals(MISTRAL, seen[3], "$MISTRAL_API_KEY, not a key given for OpenAI");
        assertEquals(3, store.connections().size(), store.connections().toString());
        assertEquals("https://api.openai.com/v1", stored("OpenAI").getBaseUrl());
        assertEquals(TYPED, SessionKeys.get("OpenAI"), "OpenAI keeps its own key");
        assertEquals(REMEMBERED, store.rememberedKey("OpenAI"));
        assertEquals("", SessionKeys.get("Mistral 2"));
        assertFalse(store.isKeyRemembered("Mistral 2"));
    }

    /** (c) "Test connection" and "Fetch the list" send a stored key to its own host only. */
    @Test
    void aStoredKeyIsNotSentToAnotherHost() throws Exception {
        String[] sent = onEdt(() -> {
            ConnectionEditor editor = editor(NO_ENVIRONMENT);
            String own = editor.keyToSend();
            editor.setAddress("https://api.mistral.ai/v1");
            String typedForOpenAi = editor.keyToSend();
            SessionKeys.clear("OpenAI");
            String rememberedForOpenAi = editor.keyToSend();
            editor.setAddress("https://API.openai.com/v1/");
            return new String[] {own, typedForOpenAi, rememberedForOpenAi, editor.keyToSend()};
        });

        assertEquals(TYPED, sent[0], "its own host gets the key typed for it");
        assertEquals("", sent[1], "another host does not get the key typed for OpenAI");
        assertEquals("", sent[2], "nor the one remembered for it");
        assertEquals(REMEMBERED, sent[3], "a host differs in case only: the same host");
    }

    /**
     * (b) Saved with another host and no key typed, the name's typed and remembered keys are
     * forgotten - and stay known, so what is written later keeps them out.
     */
    @Test
    void savedWithAnotherHostTheKeysGivenForTheOldOneAreForgotten() throws Exception {
        onEdt(() -> {
            ConnectionEditor editor = editor(NO_ENVIRONMENT);
            editor.setAddress("https://api.mistral.ai/v1");
            editor.save();
            return null;
        });

        assertEquals("https://api.mistral.ai/v1", stored("OpenAI").getBaseUrl());
        assertEquals("", SessionKeys.get("OpenAI"));
        assertFalse(store.isKeyRemembered("OpenAI"));
        Collection<String> known = SessionKeys.seen(Collections.<String>emptyList());
        assertTrue(known.contains(TYPED) && known.contains(REMEMBERED), "still redacted");
    }

    /**
     * (b) Settings' Delete leaves the typed key in memory: a new connection saved under the
     * deleted one's name, with no key typed, must not inherit a key given for a host it no
     * longer knows.
     */
    @Test
    void aNewConnectionDoesNotInheritADeletedOnesTypedKey() throws Exception {
        store.saveConnections(Arrays.asList(stored("Mistral")));
        store.forgetKey("OpenAI");

        String sent = onEdt(() -> {
            ConnectionEditor editor = new ConnectionEditor(store, NO_ENVIRONMENT, () -> { });
            editor.fill("OpenAI", ConnectionKind.OPENAI_COMPATIBLE, "https://llm.example.org/v1",
                    "m");
            editor.save();
            return editor.keyToSend();
        });

        assertEquals("", SessionKeys.get("OpenAI"));
        assertEquals("", sent);
    }

    /** The same host keeps its keys: editing the model or the address's form is no reason. */
    @Test
    void savedWithTheSameHostTheKeysAreKept() throws Exception {
        onEdt(() -> {
            ConnectionEditor editor = editor(NO_ENVIRONMENT);
            editor.setAddress("https://api.openai.com/v1/");
            editor.save();
            return null;
        });

        assertEquals(TYPED, SessionKeys.get("OpenAI"));
        assertEquals(REMEMBERED, store.rememberedKey("OpenAI"));
    }

    /**
     * Review 1.145.0 keys-09, deferred to 1.147.0: renaming a connection dropped its typed and
     * remembered key while Remember stayed ticked, so the next chat had no key. On the same host
     * the keys move with the name.
     */
    @Test
    void aRenamedConnectionKeepsItsKeysOnTheSameHost() throws Exception {
        onEdt(() -> {
            ConnectionEditor editor = editor(NO_ENVIRONMENT);
            editor.rename("OpenAI work");
            editor.save();
            return null;
        });

        assertEquals(TYPED, SessionKeys.get("OpenAI work"));
        assertEquals(REMEMBERED, store.rememberedKey("OpenAI work"));
        assertEquals("", SessionKeys.get("OpenAI"));
        assertFalse(store.isKeyRemembered("OpenAI"));
    }

    /** keys-01 holds through a rename: renamed onto another host, the keys stay behind. */
    @Test
    void aRenamedConnectionOnAnotherHostLeavesItsKeysBehind() throws Exception {
        onEdt(() -> {
            ConnectionEditor editor = editor(NO_ENVIRONMENT);
            editor.rename("Elsewhere");
            editor.setAddress("https://llm.example.org/v1");
            editor.save();
            return null;
        });

        assertEquals("", SessionKeys.get("Elsewhere"));
        assertFalse(store.isKeyRemembered("Elsewhere"));
        assertEquals("", SessionKeys.get("OpenAI"));
        assertFalse(store.isKeyRemembered("OpenAI"));
    }

    /**
     * Review 1.146.0 keys-02: on a new form, a key typed after one preset stayed in the field
     * when a second preset was picked, and went to that provider in "Test connection" and "Fetch
     * the list" - and, after Save, in every chat; ticked, in every session.
     */
    @Test
    void aKeyTypedOnANewFormDoesNotFollowASecondPreset() throws Exception {
        Object[] seen = onEdt(() -> {
            ConnectionEditor editor = new ConnectionEditor(store, NO_ENVIRONMENT, () -> { });
            editor.newConnection();
            editor.choosePreset("OpenAI");
            String first = editor.read().getName();
            editor.typeKey("Op7kL3" + "mN9bV1cX5zQ8wE2rT6yU0iO4pA");
            editor.rememberBox().setSelected(true);
            editor.choosePreset("Mistral");
            Object[] shown = {editor.typedKey(), editor.keyToSend(),
                editor.rememberBox().isSelected(), first, editor.read()};
            editor.save();
            return shown;
        });

        assertEquals("", seen[0], "the key field is emptied");
        assertEquals("", seen[1], "the key typed for OpenAI does not go to Mistral");
        assertEquals(Boolean.FALSE, seen[2], "Remember is unticked");
        assertEquals("OpenAI 2", seen[3], "the first preset's label, made unique");
        Connection shown = (Connection) seen[4];
        assertEquals("Mistral 2", shown.getName(), "the second preset's label replaces it");
        assertEquals("https://api.mistral.ai/v1", shown.getBaseUrl());
        assertEquals(3, store.connections().size(), store.connections().toString());
        assertEquals("https://api.openai.com/v1", stored("OpenAI").getBaseUrl());
        assertEquals("", SessionKeys.get("Mistral 2"));
        assertFalse(store.isKeyRemembered("Mistral 2"));
    }

    /**
     * Review 1.146.0 keys-02: a Kind change that put another kind's address in place of the one
     * shown kept the key typed for the old address, and sent it to the new one.
     */
    @Test
    void aKindChangeThatReplacesTheAddressEmptiesTheKeyField() throws Exception {
        java.util.List<Connection> all = new java.util.ArrayList<Connection>(store.connections());
        all.add(Connection.of("Claude", ConnectionKind.ANTHROPIC));
        store.saveConnections(all);

        Object[] seen = onEdt(() -> {
            ConnectionEditor editor = new ConnectionEditor(store, NO_ENVIRONMENT, () -> { });
            editor.load(stored("Claude"));
            editor.typeKey("An3tH7" + "rP1cK5eY9mA2dE6uP0fO4rT8sX");
            editor.setKind(ConnectionKind.OPENAI_COMPATIBLE);
            return new Object[] {editor.typedKey(), editor.keyToSend(), editor.read()};
        });

        assertEquals("", seen[0], "the key field is emptied");
        assertEquals("", seen[1], "the key typed for Anthropic does not go to OpenAI");
        assertEquals("https://api.openai.com/v1", ((Connection) seen[2]).getBaseUrl());
    }

    /**
     * Review 1.146.0 keys-04: New... and a preset whose label a saved connection has took that
     * label as the name, and Save replaced the saved connection and erased its remembered key.
     */
    @Test
    void aPresetOnANewFormDoesNotReplaceTheSavedConnectionOfItsName() throws Exception {
        String name = onEdt(() -> {
            ConnectionEditor editor = editor(NO_ENVIRONMENT);
            editor.newConnection();
            editor.choosePreset("OpenAI");
            String shown = editor.read().getName();
            editor.save();
            return shown;
        });

        assertEquals("OpenAI 2", name, "the preset's label, made unique");
        assertEquals(3, store.connections().size(), store.connections().toString());
        assertEquals("gpt-x", stored("OpenAI").getModel(), "the saved connection is kept");
        assertEquals(REMEMBERED, store.rememberedKey("OpenAI"), "with its remembered key");
        assertEquals(TYPED, SessionKeys.get("OpenAI"));
    }

    /**
     * Review 1.146.0 keys-04, the rest: New..., a saved connection's name typed by hand, and Save
     * replaced that connection and erased its remembered key.
     */
    @Test
    void aNewConnectionWithASavedOnesNameIsNotSavedOverIt() throws Exception {
        String said = onEdt(() -> {
            ConnectionEditor editor = editor(NO_ENVIRONMENT);
            editor.newConnection();
            editor.rename("OpenAI");
            editor.save();
            return editor.resultLine().getText();
        });

        assertEquals("gpt-x", stored("OpenAI").getModel());
        assertEquals(REMEMBERED, store.rememberedKey("OpenAI"));
        assertTrue(said.contains("exists"), said);
    }

    /**
     * Review 1.147.0 keys-01 (a 1.147.0 regression): a connection renamed onto another saved
     * connection's name, Remember ticked and no key typed, kept that connection's remembered key -
     * and from the next session on, every chat sent it to the renamed connection's host. Save
     * refuses the rename, as it refuses a new connection under a saved one's name. The reviewer's
     * steps (R1); every key here is made up.
     */
    @Test
    void aConnectionRenamedOntoASavedOnesNameIsNotSavedOverIt() throws Exception {
        String anthropic = "An7tH3" + "rP1cK5eY9mQ2wE8rT4yU6iO0pZ";
        String openRouter = "Or4uT8" + "eR2kE6yX0cV3bN7mQ1wZ5aS9dF";
        java.util.List<Connection> all = new java.util.ArrayList<Connection>(store.connections());
        all.add(Connection.of("Claude", ConnectionKind.ANTHROPIC));
        all.add(Connection.of("Claude 2", ConnectionKind.OPENAI_COMPATIBLE)
                .withBaseUrl("https://openrouter.ai/api/v1"));
        store.saveConnections(all);
        store.rememberKey("Claude", anthropic);
        SessionKeys.put("Claude 2", openRouter);

        String said = onEdt(() -> {
            ConnectionEditor editor = new ConnectionEditor(store, NO_ENVIRONMENT, () -> { });
            editor.load(stored("Claude 2"));
            editor.rename("Claude");
            editor.rememberBox().setSelected(true);
            editor.save();
            return editor.resultLine().getText();
        });
        // The next Protege session has no typed keys; the chat resolves the key as AssistantView
        // does.
        String typedForClaude = SessionKeys.get("Claude");
        SessionKeys.clear("Claude");
        Connection claude = stored("Claude");
        String sent = ApiKeys.resolve(claude, SessionKeys.get("Claude"), NO_ENVIRONMENT,
                store.rememberedKey("Claude")).getKey();

        assertFalse(anthropic.equals(sent) && !"api.anthropic.com".equals(claude.getHost()),
                "the key remembered for api.anthropic.com goes to " + claude.getHost());
        assertTrue(said.contains("Not saved: a connection called Claude exists"), said);
        assertEquals(4, store.connections().size(), store.connections().toString());
        assertEquals("api.anthropic.com", claude.getHost());
        assertEquals(anthropic, store.rememberedKey("Claude"));
        assertEquals("", typedForClaude);
        assertEquals("openrouter.ai", stored("Claude 2").getHost());
        assertEquals(openRouter, SessionKeys.get("Claude 2"));
        assertFalse(store.isKeyRemembered("Claude 2"));
        assertEquals("OpenAI", store.active().getName());
    }
}
