package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectSettings;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRegenerator;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkScaffold;
import java.io.File;
import java.util.Arrays;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Project &gt; Update project files - re-render the generated files of an existing project.
 *
 * <p>ODK's {@code update_repo}. Without it every fix to the generator reaches only <em>newly
 * created</em> projects: four rounds of corrections in this plugin - the catalog the build ignored,
 * the release gate weaker than CI, the reasoner accepting equivalences nobody asserted, a README
 * documenting three of seven targets - reached nobody who already had a project. Their only route
 * was to create a new project and copy the ontology across.
 *
 * <p><b>Shows the diff first, and writes nothing by default.</b> This action can destroy work, so
 * the preview is the default and writing is a deliberate second step. What it would change, and by
 * how many lines, is the whole content of the report.
 *
 * <p><b>What it will not touch:</b> the edit file, the custom Makefile, the YAML it reads from, the
 * ID ranges - which hold every editor's allocated range, not just this machine's - and the catalog,
 * which holds real import mappings that {@code Catalog.addEntry} wrote and the template would
 * delete. {@link OdkScaffold#seededFilesFor} is the list, and the report names it.
 */
public class UpdateProjectAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_WRITE = "write";

    private volatile boolean write;

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
        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(),
                "Update project files",
                "Re-renders the generated files of this ODK project - the Makefile, the CI "
                        + "workflow, the report profile, the SPARQL check, the .gitignore and the "
                        + "README - from the project's own " + "<id>-odk.yaml. Your ontology, your "
                        + "custom Makefile, your ID ranges and your catalog are never touched.",
                Arrays.asList(
                        Parameter.of(OPTION_WRITE, "Write the changes", Parameter.Kind.FLAG)
                                .defaultValue("false")
                                .help("Off by default. With it off you get the full list of what "
                                        + "would change and by how many lines, and nothing is "
                                        + "written - which is how to look before trusting this "
                                        + "with a project you care about.\n\nThe files it can "
                                        + "write are only ever the generated ones. It cannot touch "
                                        + "your ontology.")
                                .build()));
        if (chosen == null) {
            return false;
        }
        write = "true".equalsIgnoreCase(chosen.get(OPTION_WRITE));
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("File", "State", "Lines differing");

        File projectRoot = projectRootOf(ontology);
        if (projectRoot == null) {
            return result.failed("This ontology has not been saved, so there is no project to "
                    + "update.").build();
        }

        OdkRegenerator.Plan plan;
        try {
            plan = OdkRegenerator.plan(projectRoot);
        } catch (OdkProjectSettings.UnreadableProjectException cannotRead) {
            return result.failed(cannotRead.getMessage()).build();
        }

        result.note("Project: " + projectRoot.getAbsolutePath());
        result.note("ROBOT version: " + plan.getRobotVersion()
                + (plan.isRobotVersionFromProject() ? " (from this project)" : ""));
        if (!plan.isRobotVersionFromProject()) {
            // Never substitute this silently: it would move their CI onto a different ROBOT than
            // the one their project has been building with.
            result.warn("This project records no robot_version, so it predates that key. Updating "
                    + "would write " + plan.getRobotVersion() + " - this plugin's version - into "
                    + "its CI. If the project has been building with a different ROBOT, add "
                    + "'robot_version: <version>' to its -odk.yaml first.");
        }

        for (OdkRegenerator.Change change : plan.getChanges()) {
            result.row(change.getPath(),
                    change.isNew() ? "missing" : change.isChanged() ? "out of date" : "up to date",
                    change.isChanged() ? String.valueOf(change.changedLines()) : "0");
        }

        result.note("Never touched: " + String.join(", ",
                OdkScaffold.seededFilesFor(idOf(projectRoot, plan))));

        if (plan.isUpToDate()) {
            return result.summary("Already up to date. All "
                    + plan.getChanges().size() + " generated files match what this OntoBoard "
                    + "produces.").build();
        }

        int changed = plan.getChanged().size();
        if (!write) {
            result.note("Nothing was written - 'Write the changes' was off.");
            return result.summary(changed + " of " + plan.getChanges().size()
                    + " generated files would change.").build();
        }
        if (BackgroundRun.abandoned()) {
            result.note("Nothing was written - you stopped waiting before it finished.");
            return result.summary(changed + " files would have changed, but this was abandoned.")
                    .build();
        }

        try {
            for (File written : OdkRegenerator.apply(projectRoot, plan)) {
                result.wrote(written);
            }
        } catch (OdkRegenerator.OdkRegenerationException partlyDone) {
            for (File written : partlyDone.getWritten()) {
                result.wrote(written);
            }
            return result.failed("Stopped part way: " + partlyDone.getMessage()
                    + ". The files listed above were already written, so the project is in a mixed "
                    + "state - run this again once the cause is fixed.").build();
        }
        result.note("Commit these as their own change, so the diff is reviewable.");
        return result.summary("Updated " + changed + " generated files.").build();
    }

    /** The ontology id, for naming the untouched files. */
    private static String idOf(File projectRoot, OdkRegenerator.Plan plan) {
        try {
            return OdkProjectSettings.read(projectRoot).getConfig().getOntologyId();
        } catch (RuntimeException cannotRead) {
            return "<id>";
        }
    }
}
