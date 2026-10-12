package de.fizkarlsruhe.ise.ontoboard.views;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.protege.editor.owl.ui.OWLWorkspaceViewsTab;
import org.slf4j.Logger;

/**
 * The arrangement a tab ships with, and getting it to users who have already opened that tab.
 *
 * <p>{@code defaultViewConfigFileName} in plugin.xml is consulted the first time a tab is
 * created. After that Prot&eacute;g&eacute; saves the arrangement the user is looking at into
 * application preferences and restores that instead, so a new view config in a new release
 * reaches nobody who has opened the tab before. That is invisible rather than broken - upgrading
 * appears to do nothing.
 *
 * <p>Shared between the tabs rather than inherited, because there are now two of them -
 * {@link OntoBoardTab} and {@link SheetsTab} - and the decision each one makes is the same
 * decision with a different config and a different preference key. One copy of it means one
 * place where the rule about checking the outcome rather than the intention lives.
 */
public final class ShippedLayout {

    /**
     * Our own preference set, so nothing here can disturb Prot&eacute;g&eacute;'s. Deliberately
     * not the {@code ViewLayoutPreferences} set that holds the saved arrangement itself: that key
     * format is internal to {@code ViewsPane} and reaching into it would break on a
     * Prot&eacute;g&eacute; upgrade, whereas {@code reset()} is the same public call the Window
     * menu makes.
     */
    static final String PREFERENCE_SET = "de.fizkarlsruhe.ise.ontoboard";

    private ShippedLayout() {
    }

    /**
     * Adopts the shipped arrangement once, if this tab has not got it.
     *
     * <p>The trigger is a digest of the shipped config rather than a hand-maintained version
     * number, so editing the layout is enough and there is no second thing to remember.
     *
     * <p><b>Check the outcome, not the intention.</b> Remembering "this layout was adopted" is
     * not the same as it having arrived: {@code reset()} rearranges the tab that is open, and the
     * arrangement is only persisted when Prot&eacute;g&eacute; exits cleanly. Kill it first -
     * which the smoke script does every run, and which a crash does to a user - and the next
     * launch restores the old saved arrangement while the digest says there is nothing to do. The
     * layout then reverts silently and permanently. Measured between 1.113.0 and 1.114.0: the
     * stacked layout adopted, both views built, and one release later the tab was back to one
     * view with no change to the config at all. So the digest only suppresses <em>repeated</em>
     * resets; what triggers one is a view the shipped layout asks for that is not actually here.
     */
    static void adoptOnce(OWLWorkspaceViewsTab tab, String configResource, Logger logger) {
        String shipped = revisionOfResource(configResource, logger);
        if (shipped == null) {
            // Without a digest there is nothing to compare, and resetting on every launch would
            // discard the user's arrangement each time. Leaving the layout alone is the safer of
            // the two failures.
            return;
        }
        org.protege.editor.core.prefs.Preferences preferences =
                org.protege.editor.core.prefs.PreferencesManager.getInstance()
                        .getApplicationPreferences(PREFERENCE_SET);
        String key = "tabLayoutRevision." + nameOf(configResource) + "." + installKey();
        if (shipped.equals(preferences.getString(key, null))
                && everyViewTheLayoutAsksForIsPresent(tab, configResource)) {
            return;
        }
        try {
            tab.reset();
            preferences.putString(key, shipped);
            logger.info("OntoBoard: adopted the {} layout shipped with this version ({})",
                    nameOf(configResource), shipped);
        } catch (RuntimeException failed) {
            // A tab that throws out of initialise() does not appear at all, which is a far worse
            // outcome than a stale arrangement. The revision is left unwritten so the next launch
            // tries again.
            logger.warn("OntoBoard: could not apply the new layout for {}; the previous"
                    + " arrangement is still in use. Window > Reset selected tab to default"
                    + " state will apply it manually.", nameOf(configResource), failed);
        }
    }

    /** {@code /viewconfig-sheetstab.xml} to {@code viewconfig-sheetstab}, for a log line. */
    private static String nameOf(String configResource) {
        String name = configResource.startsWith("/") ? configResource.substring(1)
                : configResource;
        return name.endsWith(".xml") ? name.substring(0, name.length() - 4) : name;
    }

    /**
     * Whether every view this tab's own layout asks for is actually in it.
     *
     * <p>Per layout, not per plugin.xml. Requiring every registered view in every tab was right
     * while there was one tab and became wrong the moment the sheet editor moved into its own:
     * the canvas tab would then have reset itself on every launch, for ever, looking for a view
     * that is deliberately somewhere else.
     *
     * <p>Counted by walking the component tree rather than asked of Prot&eacute;g&eacute;'s view
     * registry, because the question is "is it on screen", and a view that is registered but was
     * dropped when the layout was built is exactly the case this has to catch -
     * Prot&eacute;g&eacute; silently instantiates one of two plugin views placed in a single
     * node.
     *
     * <p>A failure to answer is treated as "present", so a change in
     * Prot&eacute;g&eacute;'s component tree cannot turn this into a reset on every launch.
     * Discarding somebody's arrangement every time they start is worse than a layout that is one
     * release stale.
     */
    static boolean everyViewTheLayoutAsksForIsPresent(java.awt.Container tab,
            String configResource) {
        try {
            int wanted = ourViewsNamedIn(configResource).size();
            return wanted == 0 || ourViewsIn(tab) >= wanted;
        } catch (Exception | LinkageError cannotTell) {
            return true;
        }
    }

    /**
     * The simple names of our own views a view config places, in document order.
     *
     * <p>A {@code pluginId} is {@code <bundle symbolic name>.<extension id>}, and for these the
     * extension id is the view class's simple name - so the last segment is matched against what
     * plugin.xml registers. Prot&eacute;g&eacute;'s own views, which make up most of a config,
     * are not ours to require.
     */
    public static List<String> viewsNamedIn(String configResource) throws Exception {
        return ourViewsNamedIn(configResource);
    }

    static List<String> ourViewsNamedIn(String configResource) throws Exception {
        List<String> ours = new ArrayList<String>();
        List<String> registered = de.fizkarlsruhe.ise.ontoboard.SelfCheck.declaredViewNames();
        InputStream in = ShippedLayout.class.getResourceAsStream(configResource);
        if (in == null) {
            return ours;
        }
        try {
            org.w3c.dom.NodeList properties = javax.xml.parsers.DocumentBuilderFactory
                    .newInstance().newDocumentBuilder().parse(in)
                    .getElementsByTagName("Property");
            for (int at = 0; at < properties.getLength(); at++) {
                org.w3c.dom.Element property = (org.w3c.dom.Element) properties.item(at);
                if (!"pluginId".equals(property.getAttribute("id"))) {
                    continue;
                }
                String value = property.getAttribute("value");
                String last = value.substring(value.lastIndexOf('.') + 1);
                if (registered.contains(last) && !ours.contains(last)) {
                    ours.add(last);
                }
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Closing a classpath resource cannot usefully fail.
            }
        }
        return ours;
    }

    /** How many components in here are one of our own views. */
    static int ourViewsIn(java.awt.Container container) {
        int found = 0;
        for (java.awt.Component child : container.getComponents()) {
            if (child.getClass().getName().startsWith("de.fizkarlsruhe.ise.ontoboard.views.")) {
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
     * supported arrangement here, since every release is smoked on 5.5.0 and 5.6.9 - share this
     * preference set. Without a discriminator the first one to start adopts the new layout,
     * writes the digest, and the second then finds the digest already matching and never adopts
     * anything. Measured: 5.6.9 took the stacked layout and 5.5.0 was still showing the previous
     * one, with the self-test reporting a view that never built.
     *
     * <p>{@code user.dir} is the installation directory for every way Prot&eacute;g&eacute; is
     * normally started - its own launcher, {@code run.bat}, and the smoke script, which sets a
     * working directory explicitly. Somebody starting it from elsewhere gets a different key and
     * so one extra layout reset, which is the harmless direction to be wrong in; the other
     * direction is a layout that never arrives.
     */
    static String installKey() {
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

    /** Digest of a config as built into this jar, or null if it cannot be read. */
    static String revisionOfResource(String configResource, Logger logger) {
        try (InputStream in = ShippedLayout.class.getResourceAsStream(configResource)) {
            return in == null ? null : revisionOf(in);
        } catch (IOException | RuntimeException unreadable) {
            logger.warn("OntoBoard: could not read {}", configResource, unreadable);
            return null;
        }
    }

    /**
     * A short, stable digest of {@code in}.
     *
     * <p>Truncated to 16 hex characters because this only has to distinguish one layout from
     * another, not resist attack, and a preference value a human can read in a registry editor is
     * easier to support.
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
