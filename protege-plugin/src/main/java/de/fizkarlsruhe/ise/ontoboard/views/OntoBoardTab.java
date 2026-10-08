package de.fizkarlsruhe.ise.ontoboard.views;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.prefs.PreferencesManager;
import org.protege.editor.owl.ui.OWLWorkspaceViewsTab;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The OntoBoard tab, which is Protege's own views tab plus one behaviour: it notices when the
 * shipped layout has changed and adopts it once.
 *
 * <p>{@code defaultViewConfigFileName} in plugin.xml is only consulted the first time a tab is
 * created. After that Protege saves the arrangement the user is looking at into application
 * preferences and restores that instead, so a new {@code viewconfig-ontoboardtab.xml} in a new
 * release reaches nobody who has already opened the tab. That is invisible rather than broken -
 * upgrading appears to do nothing - and it is why the fix that moved the class hierarchy from
 * below the canvas to beside it needs this class to actually arrive.
 *
 * <p>The trigger is a digest of the shipped config rather than a hand-maintained version number,
 * so editing the layout is enough and there is no second thing to remember. Reset happens once
 * per distinct layout: a user who rearranges the tab afterwards keeps their arrangement until the
 * shipped layout genuinely changes again, at which point adopting it is the intended outcome.
 */
public class OntoBoardTab extends OWLWorkspaceViewsTab {

    private static final Logger LOGGER = LoggerFactory.getLogger(OntoBoardTab.class);

    static final String CONFIG = "/viewconfig-ontoboardtab.xml";

    /**
     * Our own preference set, so nothing here can disturb Protege's. Deliberately not the
     * {@code ViewLayoutPreferences} set that holds the saved arrangement itself: that key format
     * is internal to {@code ViewsPane} and reaching into it would break on a Protege upgrade,
     * whereas {@link #reset()} is the same public call the Window menu makes.
     */
    private static final String PREFERENCE_SET = "de.fizkarlsruhe.ise.ontoboard";
    private static final String REVISION_KEY = "tabLayoutRevision";

    @Override
    public void initialise() {
        super.initialise();
        adoptShippedLayoutOnce();
    }

    private void adoptShippedLayoutOnce() {
        String shipped = shippedLayoutRevision();
        if (shipped == null) {
            // Without a digest there is nothing to compare, and resetting on every launch would
            // discard the user's arrangement each time. Leaving the layout alone is the safer
            // of the two failures.
            return;
        }
        Preferences preferences =
                PreferencesManager.getInstance().getApplicationPreferences(PREFERENCE_SET);
        String key = REVISION_KEY + "." + installKey();
        // CHECK THE OUTCOME, NOT THE INTENTION. Remembering "this layout was adopted" is not
        // the same as it having arrived: reset() rearranges the tab that is open, and the
        // arrangement is only persisted when Protege exits cleanly. Kill it first - which the
        // smoke script does every run, and which a crash does to a user - and the next launch
        // restores the old saved arrangement while the digest says there is nothing to do. The
        // layout then reverts silently and permanently. Measured between 1.113.0 and 1.114.0:
        // the stacked layout adopted, both views built, and one release later the tab was back
        // to one view with no change to the config at all.
        //
        // So the digest only suppresses REPEATED resets; what triggers one is a view the
        // shipped layout asks for that is not actually here.
        if (shipped.equals(preferences.getString(key, null)) && everyShippedViewIsPresent()) {
            return;
        }
        try {
            reset();
            preferences.putString(key, shipped);
            LOGGER.info("OntoBoard: adopted the layout shipped with this version ({})", shipped);
        } catch (RuntimeException failed) {
            // A tab that throws out of initialise() does not appear at all, which is a far worse
            // outcome than a stale arrangement. The revision is left unwritten so the next
            // launch tries again.
            LOGGER.warn("OntoBoard: could not apply the new tab layout; the previous arrangement"
                    + " is still in use. Window > Reset selected tab to default state will apply"
                    + " it manually.", failed);
        }
    }

    /**
     * Whether every view the shipped layout asks for is actually in this tab.
     *
     * <p>Counted by class rather than asked of Protege's view registry, because the question
     * is "is it on screen", and a view that is registered but was dropped when the layout was
     * built is exactly the case this has to catch - Protege silently instantiates one of two
     * plugin views placed in a single node.
     *
     * <p>A failure to answer is treated as "present", so a change in Protege's component tree
     * cannot turn this into a reset on every launch. Discarding somebody's arrangement every
     * time they start is worse than a layout that is one release stale.
     */
    private boolean everyShippedViewIsPresent() {
        try {
            int wanted = 0;
            for (String name : de.fizkarlsruhe.ise.ontoboard.SelfCheck.declaredViewNames()) {
                if (!name.isEmpty()) {
                    wanted++;
                }
            }
            return wanted == 0 || ourViewsIn(this) >= wanted;
        } catch (Exception | LinkageError cannotTell) {
            return true;
        }
    }

    /** How many components in here are one of our own views. */
    private static int ourViewsIn(java.awt.Container container) {
        int found = 0;
        for (java.awt.Component child : container.getComponents()) {
            if (child.getClass().getName().startsWith(
                    "de.fizkarlsruhe.ise.ontoboard.views.")) {
                found++;
            }
            if (child instanceof java.awt.Container) {
                found += ourViewsIn((java.awt.Container) child);
            }
        }
        return found;
    }

    /**
     * Which installation this is, so two of them do not share one answer.
     *
     * <p>Java preferences are per user, not per application: on Windows they are one registry
     * tree under {@code HKCU}. So two Prot&eacute;g&eacute; installations - which is the
     * supported arrangement here, since every release is smoked on 5.5.0 and 5.6.9 - share
     * this preference set. Without a discriminator the first one to start adopts the new
     * layout, writes the digest, and the second then finds the digest already matching and
     * never adopts anything. Measured: 5.6.9 took the stacked layout and 5.5.0 was still
     * showing the previous one, with the self-test reporting a view that never built.
     *
     * <p>{@code user.dir} is the installation directory for every way Prot&eacute;g&eacute; is
     * normally started - its own launcher, {@code run.bat}, and the smoke script, which sets a
     * working directory explicitly. Somebody starting it from elsewhere gets a different key
     * and so one extra layout reset, which is the harmless direction to be wrong in; the other
     * direction is a layout that never arrives.
     */
    private static String installKey() {
        String where = System.getProperty("user.dir", "");
        if (where.isEmpty()) {
            return "unknown";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(where.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException | java.io.UnsupportedEncodingException
                impossible) {
            return "unknown";
        }
    }

    /** Digest of the config as built into this jar, or null if it cannot be read. */
    private String shippedLayoutRevision() {
        try (InputStream in = OntoBoardTab.class.getResourceAsStream(CONFIG)) {
            return in == null ? null : revisionOf(in);
        } catch (IOException | RuntimeException unreadable) {
            LOGGER.warn("OntoBoard: could not read {}", CONFIG, unreadable);
            return null;
        }
    }

    /**
     * A short, stable digest of {@code in}.
     *
     * <p>Truncated to 16 hex characters because this only has to distinguish one layout from
     * another, not resist attack, and a preference value that a human can read in a registry
     * editor is easier to support.
     */
    static String revisionOf(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JRE", impossible);
        }
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        byte[] bytes = digest.digest();
        for (int i = 0; i < 8; i++) {
            hex.append(String.format("%02x", bytes[i]));
        }
        return hex.toString();
    }
}
