package de.fizkarlsruhe.ise.ontoboard.prov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.protege.editor.core.prefs.Preferences;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * When provenance is stamped, and under whose name.
 *
 * <p>The default is the whole point and the easy thing to get wrong. Stamping every ontology
 * someone opens would write a convention into projects whose maintainers never chose it, showing
 * up as unexplained churn in a diff they did not expect; never stamping would make the feature
 * useless in the projects that want it. So the default follows the ontology, and these tests pin
 * that decision rather than the preference plumbing.
 */
class ProvenanceSettingsTest {

    private static final String ORCID = "https://orcid.org/0000-0001-9625-1899";

    /** In-memory Preferences; Protege's is an interface, so no running Protege is needed. */
    private static final class FakePreferences implements Preferences {
        private final Map<String, Object> values = new LinkedHashMap<String, Object>();

        @Override
        public void clear() {
            values.clear();
        }

        @Override
        public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }

        @Override
        public void putString(String key, String value) {
            values.put(key, value);
        }

        @Override
        public List<String> getStringList(String key, List<String> fallback) {
            return fallback;
        }

        @Override
        public void putStringList(String key, List<String> value) {
            values.put(key, new ArrayList<String>(value));
        }

        @Override
        public int getInt(String key, int fallback) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : fallback;
        }

        @Override
        public void putInt(String key, int value) {
            values.put(key, value);
        }

        @Override
        public float getFloat(String key, float fallback) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : fallback;
        }

        @Override
        public void putFloat(String key, float value) {
            values.put(key, value);
        }

        @Override
        public long getLong(String key, long fallback) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : fallback;
        }

        @Override
        public void putLong(String key, long value) {
            values.put(key, value);
        }

        @Override
        public double getDouble(String key, double fallback) {
            Object value = values.get(key);
            return value instanceof Double ? (Double) value : fallback;
        }

        @Override
        public void putDouble(String key, double value) {
            values.put(key, value);
        }

        @Override
        public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override
        public void putBoolean(String key, boolean value) {
            values.put(key, value);
        }

        @Override
        public byte[] getByteArray(String key, byte[] fallback) {
            return fallback;
        }

        @Override
        public void putByteArray(String key, byte[] value) {
            values.put(key, value);
        }

        @Override
        public List<byte[]> getByteArrayList(String key, List<byte[]> fallback) {
            return fallback;
        }

        @Override
        public void putByteArrayList(String key, List<byte[]> value) {
            values.put(key, value);
        }
    }

    private final FakePreferences preferences = new FakePreferences();
    private OWLOntologyManager manager;
    private OWLOntology plain;
    private OWLOntology alreadyStamped;

    @BeforeEach
    void twoOntologies() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        plain = manager.createOntology(IRI.create("http://example.org/plain"));
        alreadyStamped = manager.createOntology(IRI.create("http://example.org/stamped"));
        IRI person = IRI.create("http://example.org/stamped#Person");
        manager.addAxiom(alreadyStamped, manager.getOWLDataFactory().getOWLDeclarationAxiom(
                manager.getOWLDataFactory().getOWLClass(person)));
        manager.applyChanges(Provenance.stampNew(alreadyStamped, person, ORCID, "2026-08-28"));
    }

    private ProvenanceSettings settings(String agent, ProvenanceSettings.Mode mode) {
        return new ProvenanceSettings(agent, mode);
    }

    // ---------- the default ----------

    /**
     * The decision this class exists for. An ontology that has never recorded provenance does not
     * start recording it because somebody opened it in this plugin.
     */
    @Test
    void anOntologyWithNoProvenanceIsNotStampedByDefault() {
        assertFalse(settings(ORCID, ProvenanceSettings.Mode.FOLLOW_THE_ONTOLOGY)
                .shouldStamp(plain, false));
    }

    @Test
    void anOntologyThatAlreadyRecordsProvenanceKeepsBeingStamped() {
        assertTrue(settings(ORCID, ProvenanceSettings.Mode.FOLLOW_THE_ONTOLOGY)
                .shouldStamp(alreadyStamped, false));
    }

    /** In an ODK project the convention is near-universal, so following it is the right default. */
    @Test
    void anOdkProjectIsStampedEvenBeforeItsFirstProvenanceAnnotation() {
        assertTrue(settings(ORCID, ProvenanceSettings.Mode.FOLLOW_THE_ONTOLOGY)
                .shouldStamp(plain, true));
    }

    @Test
    void theModesOverrideTheDefaultInBothDirections() {
        assertTrue(settings(ORCID, ProvenanceSettings.Mode.ALWAYS).shouldStamp(plain, false));
        assertFalse(settings(ORCID, ProvenanceSettings.Mode.NEVER)
                .shouldStamp(alreadyStamped, true));
    }

    /** Recording an empty author is worse than recording none - it looks like data and is not. */
    @Test
    void withNoConfiguredAuthorNothingIsStampedWhateverTheMode() {
        assertFalse(settings("", ProvenanceSettings.Mode.ALWAYS).shouldStamp(alreadyStamped, true));
        assertFalse(settings("   ", ProvenanceSettings.Mode.ALWAYS).shouldStamp(plain, true));
    }

    // ---------- the identity ----------

    @Test
    void anOrcidIsCanonicalisedHoweverItWasTyped() {
        assertEquals(ORCID, settings("0000-0001-9625-1899",
                ProvenanceSettings.Mode.ALWAYS).canonicalAgent());
        assertTrue(settings("0000-0001-9625-1899", ProvenanceSettings.Mode.ALWAYS).hasOrcid());
    }

    @Test
    void aPlainNameIsKeptAsTypedAndReportedAsNotAnOrcid() {
        ProvenanceSettings named = settings("Alice Smith", ProvenanceSettings.Mode.ALWAYS);

        assertEquals("Alice Smith", named.canonicalAgent());
        assertFalse(named.hasOrcid());
    }

    /** An ORCID is what a published ontology wants, so a name gets a nudge rather than a refusal. */
    @Test
    void theDescriptionSuggestsAnOrcidWhenOneIsNotConfigured() {
        String described = settings("Alice Smith", ProvenanceSettings.Mode.ALWAYS)
                .describe(plain, false);

        assertTrue(described.contains("ORCID"), described);
        assertTrue(described.contains("Alice Smith"), described);
    }

    @Test
    void theDescriptionDoesNotNagWhenAnOrcidIsConfigured() {
        String described = settings(ORCID, ProvenanceSettings.Mode.ALWAYS).describe(plain, false);

        assertTrue(described.contains(ORCID), described);
        assertFalse(described.contains("would be better"), described);
    }

    @Test
    void theDescriptionExplainsWhyNothingWillBeStamped() {
        assertTrue(settings(ORCID, ProvenanceSettings.Mode.FOLLOW_THE_ONTOLOGY)
                .describe(plain, false).contains("did not choose"));
        assertTrue(settings("", ProvenanceSettings.Mode.ALWAYS)
                .describe(plain, true).contains("No author is configured"));
    }

    // ---------- persistence ----------

    @Test
    void whatIsSavedComesBack() {
        ProvenanceSettings.save(preferences,
                new ProvenanceSettings(ORCID, ProvenanceSettings.Mode.ALWAYS));

        ProvenanceSettings loaded = ProvenanceSettings.load(preferences);
        assertEquals(ORCID, loaded.getAgent());
        assertEquals(ProvenanceSettings.Mode.ALWAYS, loaded.getMode());
    }

    @Test
    void nothingConfiguredLoadsAsFollowTheOntologyWithNoAuthor() {
        ProvenanceSettings loaded = ProvenanceSettings.load(preferences);

        assertEquals("", loaded.getAgent());
        assertEquals(ProvenanceSettings.Mode.FOLLOW_THE_ONTOLOGY, loaded.getMode());
    }

    /**
     * A preference written by a newer version must not crash an older one, and following the
     * ontology is the safe reading of an instruction we do not understand - it changes nothing
     * that was not already happening.
     */
    @Test
    void anUnrecognisedModeFallsBackToFollowingTheOntology() {
        preferences.putString(ProvenanceSettings.KEY_MODE, "SOMETHING_FROM_THE_FUTURE");

        assertEquals(ProvenanceSettings.Mode.FOLLOW_THE_ONTOLOGY,
                ProvenanceSettings.load(preferences).getMode());
    }

    @Test
    void theKeysAreNamespacedSoTheyCannotCollideWithProteges() {
        assertTrue(ProvenanceSettings.KEY_AGENT.startsWith("provenance."));
        assertTrue(ProvenanceSettings.KEY_MODE.startsWith("provenance."));
        assertTrue(ProvenanceSettings.PREFERENCE_SET.startsWith("de.fizkarlsruhe"));
    }
}
