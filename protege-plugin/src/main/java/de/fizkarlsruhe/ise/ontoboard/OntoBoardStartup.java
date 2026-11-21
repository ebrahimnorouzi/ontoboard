package de.fizkarlsruhe.ise.ontoboard;

import org.protege.editor.owl.model.OWLEditorKitHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@link SelfCheck} when Protege opens an ontology, and writes the answer to the log.
 *
 * <p>This is the smallest thing that puts the host back in the loop. Five releases shipped without
 * ever being loaded in a Protege, and the one defect the host did find - the quality report failing
 * on both hosts for an OSGi reason no test could see - sat in {@code protege.log} for nine versions.
 * A check that runs at startup and prints one greppable line turns "we believe it works in the
 * bundle" into something {@code tools/smoke.ps1} can assert and a release receipt can record.
 *
 * <p>{@code OWLEditorKitHook} is the same class and the same extension point -
 * {@code org.protege.editor.core.application.EditorKitHook} - on Protege 5.5.0 and 5.6.9, verified
 * with {@code javap} against both installs. {@code explanation-workbench} ships in both and uses it,
 * so it is not a corner of the API that might go away.
 *
 * <p><b>It cannot throw.</b> {@code initialise()} is declared {@code throws Exception} and Protege
 * calls it while building the editor kit, so an exception here could cost the user their session.
 * Nothing this class does is worth that, so everything is caught - including {@code Error}, because
 * the failures it is looking for are {@code NoClassDefFoundError} and {@code NoSuchMethodError}.
 */
public class OntoBoardStartup extends OWLEditorKitHook {

    private static final Logger LOGGER = LoggerFactory.getLogger(OntoBoardStartup.class);

    /** What takes OntoBoard's listeners off this window's model manager when the window closes. */
    private final java.util.List<Runnable> unwatch = new java.util.ArrayList<Runnable>();

    /**
     * Set this to run the self-test at startup as well as the self-check.
     *
     * <p>A system property rather than a preference, because the thing that needs to set it is
     * {@code tools/smoke.ps1}, launching Protege from a script before a release.
     */
    static final String SELF_TEST_PROPERTY = "ontoboard.selftest";

    @Override
    public void initialise() {
        try {
            SelfCheck.Result result = SelfCheck.run();
            for (SelfCheck.Check check : result.getChecks()) {
                if (check.isPassed()) {
                    LOGGER.info("OntoBoard self-check ok: {} - {}", check.getName(),
                            check.getDetail());
                } else {
                    LOGGER.error("OntoBoard self-check FAILED: {} - {}", check.getName(),
                            check.getDetail());
                }
            }
            // One line, stable wording, matched by tools/smoke.ps1.
            if (result.isPassed()) {
                LOGGER.info(result.summary());
            } else {
                LOGGER.error(result.summary());
            }
        } catch (Throwable neverLetThisStopProtege) {
            LOGGER.error("OntoBoard self-check: FAIL 0/0 - the check itself could not run",
                    neverLetThisStopProtege);
        }

        // The imports Protégé reads and leaves out because their files call themselves by another
        // name - nfdicore's eight modules - are put in as ROBOT and ODK's make read them (1.155.0).
        try {
            if (getEditorKit() instanceof org.protege.editor.owl.OWLEditorKit) {
                unwatch.add(ImportLinksOnOpen.watch(
                        (org.protege.editor.owl.OWLEditorKit) getEditorKit()));
                // A release file opened is said for what it is, once (1.155.0).
                unwatch.add(ReleaseProductNotice.watch(
                        (org.protege.editor.owl.OWLEditorKit) getEditorKit()));
                // What is saved, for a release kept behind to name before it commits (1.155.0).
                unwatch.add(SavesRecorded.watch(
                        (org.protege.editor.owl.OWLEditorKit) getEditorKit()));
            }
        } catch (Throwable neverLetThisStopProtege) {
            LOGGER.error("OntoBoard: imports left out by Protégé are not watched for",
                    neverLetThisStopProtege);
        }

        // Only when asked. The self-test runs six ROBOT operations, which takes a second or two and
        // starts a reasoner - not something to do to somebody who just wanted to open an ontology.
        // tools/smoke.ps1 -SelfTest sets this, which is what turns "a maintainer can run the
        // self-test" into "the release process runs it and asserts the result".
        if (Boolean.getBoolean(SELF_TEST_PROPERTY)) {
            runSelfTest();
        }
    }

    /**
     * Drives the read-only menu items and logs what each reported.
     *
     * <p>Reuses {@code SelfTestAction} rather than reimplementing it, so the automated run and the
     * menu item cannot drift: if one works the other does.
     */
    private void runSelfTest() {
        try {
            de.fizkarlsruhe.ise.ontoboard.menu.SelfTestAction test =
                    new de.fizkarlsruhe.ise.ontoboard.menu.SelfTestAction();
            test.setEditorKit(getEditorKit());
            test.initialise();
            de.fizkarlsruhe.ise.ontoboard.menu.OperationResult outcome = test.selfTest();

            for (java.util.List<String> row : outcome.getRows()) {
                // "item | result | what it reported", one line each, so a failure names itself.
                LOGGER.info("OntoBoard self-test item: {}", join(row));
            }
            if (outcome.isSuccess()) {
                LOGGER.info("OntoBoard self-test: PASS - {}", outcome.getSummary());
            } else {
                LOGGER.error("OntoBoard self-test: FAIL - {}", outcome.getSummary());
            }
        } catch (Throwable neverLetThisStopProtege) {
            LOGGER.error("OntoBoard self-test: FAIL - the self-test itself could not run",
                    neverLetThisStopProtege);
        }
    }

    private static String join(java.util.List<String> cells) {
        StringBuilder text = new StringBuilder();
        for (String cell : cells) {
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(cell);
        }
        return text.toString();
    }

    @Override
    public void dispose() {
        // The self-check holds nothing open: it builds an in-memory ontology and drops it. The
        // listeners initialise put on this window's model manager are taken off: Protégé disposes
        // this hook before the model manager, which names each listener still there as an ERROR -
        // three each time a window closed, its error sign lit (the hand check of 1.155.0).
        for (Runnable each : unwatch) {
            try {
                each.run();
            } catch (Throwable neverLetThisStopProtege) {
                LOGGER.warn("OntoBoard: a listener was not taken off the closing window",
                        neverLetThisStopProtege);
            }
        }
        unwatch.clear();
    }
}
