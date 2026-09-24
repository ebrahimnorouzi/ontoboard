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
    }

    @Override
    public void dispose() {
        // Nothing is held open: the self-check builds an in-memory ontology and drops it.
    }
}
