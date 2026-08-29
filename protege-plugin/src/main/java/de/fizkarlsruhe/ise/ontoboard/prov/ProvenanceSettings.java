package de.fizkarlsruhe.ise.ontoboard.prov;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.prefs.PreferencesManager;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Who to record as the author of new terms, and whether to record anyone at all.
 *
 * <p>The default is the part that matters. Stamping provenance onto an ontology that has never
 * carried any introduces a convention its maintainers did not choose, on every term anyone adds,
 * and appears in their next diff as unexplained churn - so the default follows the ontology rather
 * than a preference. {@link Mode#FOLLOW_THE_ONTOLOGY} is what a user gets until they say otherwise,
 * and it means: stamp if this ontology already records provenance, or if it is an ODK project,
 * where the convention is near-universal.
 *
 * <p>The identity is separate from the collaboration display name on purpose. A cursor label is a
 * nickname; an author recorded in a published ontology should be an ORCID, which is resolvable and
 * which two people cannot share. The two are different things and conflating them would put "eno"
 * in an ontology where {@code https://orcid.org/0000-...} belongs.
 */
public final class ProvenanceSettings {

    /** When to stamp. */
    public enum Mode {
        /** Stamp if the ontology already does, or if it is an ODK project. */
        FOLLOW_THE_ONTOLOGY,
        /** Always stamp. */
        ALWAYS,
        /** Never stamp. */
        NEVER
    }

    static final String PREFERENCE_SET = "de.fizkarlsruhe.ise.ontoboard";
    static final String KEY_AGENT = "provenance.agent";
    static final String KEY_MODE = "provenance.mode";

    private final String agent;
    private final Mode mode;

    public ProvenanceSettings(String agent, Mode mode) {
        this.agent = agent == null ? "" : agent.trim();
        this.mode = mode == null ? Mode.FOLLOW_THE_ONTOLOGY : mode;
    }

    public static ProvenanceSettings load() {
        return load(preferences());
    }

    public static void save(ProvenanceSettings settings) {
        save(preferences(), settings);
    }

    static ProvenanceSettings load(Preferences preferences) {
        String stored = preferences.getString(KEY_MODE, Mode.FOLLOW_THE_ONTOLOGY.name());
        Mode mode;
        try {
            mode = Mode.valueOf(stored);
        } catch (IllegalArgumentException unknown) {
            // A preference written by a newer version. Following the ontology is the safe
            // reading of an unknown instruction, since it changes nothing that was not already
            // being done.
            mode = Mode.FOLLOW_THE_ONTOLOGY;
        }
        return new ProvenanceSettings(preferences.getString(KEY_AGENT, ""), mode);
    }

    static void save(Preferences preferences, ProvenanceSettings settings) {
        preferences.putString(KEY_AGENT, settings.getAgent());
        preferences.putString(KEY_MODE, settings.getMode().name());
    }

    /** The ORCID or name to record. May be empty, in which case nothing is stamped. */
    public String getAgent() {
        return agent;
    }

    public Mode getMode() {
        return mode;
    }

    /** The agent as it will appear: a canonical ORCID IRI, or the name as typed. */
    public String canonicalAgent() {
        String orcid = Provenance.normaliseOrcid(agent);
        return orcid != null ? orcid : agent;
    }

    /** True when the configured identity is an ORCID, which is what a published ontology wants. */
    public boolean hasOrcid() {
        return Provenance.normaliseOrcid(agent) != null;
    }

    /**
     * Whether to stamp new terms in this ontology.
     *
     * @param isOdkProject whether the project mints from ID ranges, where the convention is
     *     near-universal
     */
    public boolean shouldStamp(OWLOntology ontology, boolean isOdkProject) {
        if (agent.isEmpty()) {
            // Nobody to credit. Recording an empty author would be worse than recording none.
            return false;
        }
        switch (mode) {
            case ALWAYS:
                return true;
            case NEVER:
                return false;
            case FOLLOW_THE_ONTOLOGY:
            default:
                return Provenance.isUsedIn(ontology) || isOdkProject;
        }
    }

    /** One line for a dialog, saying what will happen and why. */
    public String describe(OWLOntology ontology, boolean isOdkProject) {
        if (agent.isEmpty()) {
            return "No author is configured, so new terms will not record who made them.";
        }
        if (!shouldStamp(ontology, isOdkProject)) {
            return "New terms will not be stamped: this ontology does not record provenance, and "
                    + "adding it would change a convention its maintainers did not choose.";
        }
        return "New terms will record " + canonicalAgent() + " as dcterms:contributor"
                + (hasOrcid() ? "" : " (an ORCID would be better - it is resolvable and cannot be "
                        + "confused with another person of the same name)") + ".";
    }

    private static Preferences preferences() {
        return PreferencesManager.getInstance().getApplicationPreferences(PREFERENCE_SET);
    }
}
