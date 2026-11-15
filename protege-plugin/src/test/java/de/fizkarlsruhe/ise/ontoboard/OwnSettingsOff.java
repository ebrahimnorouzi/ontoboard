package de.fizkarlsruhe.ise.ontoboard;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/**
 * Keeps a test run apart from what the person at this machine has chosen for their own Protégé.
 *
 * <p>Whether ODK's container is used, and which program runs it, are settings kept with the
 * user's preferences - the same ones for a Protégé, for a host started to try a build, and for
 * a test run on that machine. Somebody who chooses "Never in a container" for their own work
 * would otherwise find tests failing that expect a container to be found. So before the first
 * test, the settings are switched off for the run; a test that is about the settings names a
 * place of its own for them.
 *
 * <p>Registered through {@code META-INF/services}, as {@link GitHubAnnotations} is.
 */
public class OwnSettingsOff implements TestExecutionListener {

    /** The property {@code OdkRoute} reads; the name is repeated here to keep it out of main. */
    public static final String PROPERTY = "ontoboard.odk.settings";

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        if (System.getProperty(PROPERTY) == null) {
            System.setProperty(PROPERTY, "none");
        }
    }
}
