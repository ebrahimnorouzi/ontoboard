package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.MakeRun;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkContainer;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkImage;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkMigration;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.util.Arrays;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Project &gt; Update project files - bring the files of an existing project in line with its
 * configuration.
 *
 * <p>ODK's {@code update_repo}. Without it every fix to the generator reaches only <em>newly
 * created</em> projects: four rounds of corrections in this plugin - the catalog the build ignored,
 * the release gate weaker than CI, the reasoner accepting equivalences nobody asserted, a README
 * documenting three of seven targets - reached nobody who already had a project. Their only route
 * was to create a new project and copy the ontology across.
 *
 * <p><b>Shows what would change first, and writes nothing by default.</b> This action can destroy
 * work, so looking is the default and writing is a deliberate second step.
 *
 * <p><b>An ODK project</b> is updated as ODK updates it ({@link OdkUpdateForm}): by ODK's own
 * {@code odk.py update} in its container where that runs and the pinned ODK's image is on the
 * machine, else by OntoBoard itself, with the same files. ODK's update rewrites the edit file's
 * import declarations on disk, so the ontology is saved before it and read again after it
 * ({@link ProtegeSession#readAgain}).
 *
 * <p><b>A project of OntoBoard's older layout</b> - its Makefile written by OntoBoard 1.135 or
 * before - becomes an ODK project here ({@link OdkMigration}): what is the project's own is
 * converted - its configuration, its test queries, the declaration of the modules it imports
 * - and then ODK's update writes the rest. The form says so, the look shows the
 * configuration as it becomes and every file that changes, and nothing of the person's own
 * is rewritten: the ontology's content, the README, the pages, the releases, the report
 * profile, the custom Makefile, the modules and their term lists.
 */
public class UpdateProjectAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private volatile boolean write;

    /** Who makes the update, decided when the form is confirmed. */
    private volatile OdkUpdateForm.Way odkWay;

    /** Writes the project, or runs make or git in it: waits for a run kept behind. */
    @Override
    protected boolean writesTheProject() {
        return true;
    }

    @Override
    protected String operationName() {
        return "Update project files";
    }

    @Override
    protected boolean runsInBackground() {
        return true;
    }

    @Override
    protected boolean configure() {
        odkWay = null;
        File root;
        try {
            root = projectRootOf(getOWLModelManager().getActiveOntology());
        } catch (RuntimeException notSaved) {
            root = null;
        }
        // A project OntoBoard 1.135 or before made is carried over to ODK by this update:
        // the form says so before anything is looked at.
        return configureOdk(root != null && OdkMigration.isOntoBoards1x(root));
    }

    /**
     * The form of an ODK project, the save its writing needs, and who would write.
     *
     * <p>Who writes is asked of the machine here, once, and said with what would change: a
     * container program that answers and the pinned ODK's image, or OntoBoard itself. An image
     * is never fetched for it.
     */
    private boolean configureOdk(boolean olderLayout) {
        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(),
                "Update project files", olderLayout ? OdkUpdateForm.migrationExplanation()
                        : OdkUpdateForm.explanation(),
                Arrays.asList(
                        Parameter.of(OdkUpdateForm.WRITE, "Write the changes",
                                Parameter.Kind.FLAG)
                                .defaultValue("false")
                                .help("Off by default. With it off you get the list of the "
                                        + "files the update writes and which of them would "
                                        + "change, and nothing is written - which is how to "
                                        + "look before trusting this with a project you care "
                                        + "about.\n\nWith it on, the files that would change "
                                        + "are written. Commit your work first: the update is "
                                        + "then a change of its own that git can show you, and "
                                        + "take back.")
                                .build()));
        if (chosen == null) {
            return false;
        }
        write = "true".equalsIgnoreCase(chosen.get(OdkUpdateForm.WRITE));
        // ODK's update reads the edit file from disk and writes it there; what is open is read
        // again afterwards. Changes that are only in Protege would be in neither.
        if (write && SaveFirst.isNeeded(getOWLModelManager()) && SaveFirst.ask(getOWLWorkspace(),
                getOWLModelManager(), "The ontology has changes that are not saved yet. The "
                        + "update rewrites the edit file on disk, and the ontology is read "
                        + "again from it afterwards, so they would be lost.",
                "Save and update", null, "Cannot update") != SaveFirst.Answer.SAVED) {
            return false;
        }
        ProcessRunner.Runner real = ProcessRunner.real();
        String found = MakeRun.containerRuntime(real);
        String image = found == null ? null : OdkImage.pinned(OdkImage.probe(found, real));
        odkWay = OdkUpdateForm.way(OdkRoute.use(), found, image,
                image == null ? null : OdkContainer.Host.detect(found, real),
                found == null ? OdkRoute.noContainerBecause(real)
                        : image == null ? OdkImage.pinnedIsNotHere() : null,
                found == null ? null : OdkImage.howToFetch());
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        File projectRoot = projectRootOf(ontology);
        if (projectRoot == null) {
            return OperationResult.failed(operationName(), "This ontology has not been "
                    + "saved, so there is no project to update.");
        }
        // An ODK project is updated as ODK updates it; one of the older layout is carried
        // over first; anything else is told what is missing for either.
        return OdkUpdateForm.run(operationName(), projectRoot, write, odkWay,
                ProcessRunner.real(), new ProtegeSession(getOWLWorkspace(),
                        getOWLModelManager(), projectRoot));
    }
}
