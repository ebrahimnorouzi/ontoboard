package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.MakeRun;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkContainer;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkImage;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectSettings;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRegenerator;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkScaffold;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.OntologyManagerFactory;
import org.semanticweb.owlapi.io.IRIDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.OntologyCopy;

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
 * ({@link Open#readAgain}).
 *
 * <p><b>A project of OntoBoard's older layout</b> - its Makefile written by OntoBoard 1.135 or
 * before - is re-rendered as it always was, until it has been migrated. What that will not
 * touch: the edit file, the custom Makefile, the YAML it reads from, the ID ranges - which hold
 * every editor's allocated range, not just this machine's - and the catalog, which holds real
 * import mappings that {@code Catalog.addEntry} wrote and the template would delete.
 * {@link OdkScaffold#seededFilesFor} is the list, and the report names it.
 */
public class UpdateProjectAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_WRITE = "write";

    private volatile boolean write;

    /** Who makes an ODK project's update, decided when the form is confirmed; null for the
     *  older layout. */
    private volatile OdkUpdateForm.Way odkWay;

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
        File open;
        try {
            open = fileOf(getOWLModelManager().getActiveOntology());
        } catch (RuntimeException notSaved) {
            open = null;
        }
        if (open != null && OdkRelease.isOdkProject(open)) {
            return configureOdk();
        }
        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(),
                "Update project files",
                // Not "the report profile, the SPARQL check": those are written once, when the
                // project is made, and never re-rendered (1.135.1).
                "Re-renders the generated files of this ODK project - the Makefile, the CI "
                        + "workflows, the .gitattributes, the .gitignore and the README - from the "
                        + "project's own <id>-odk.yaml. Your ontology, your custom Makefile, your "
                        + "report profile, your tests, your ID ranges and your catalog are never "
                        + "touched.",
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

    /**
     * The form of an ODK project, the save its writing needs, and who would write.
     *
     * <p>Who writes is asked of the machine here, once, and said with what would change: a
     * container program that answers and the pinned ODK's image, or OntoBoard itself. An image
     * is never fetched for it.
     */
    private boolean configureOdk() {
        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(),
                "Update project files", OdkUpdateForm.explanation(),
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
        OdkUpdateForm.Way way = odkWay;
        if (way != null) {
            File projectRoot = projectRootOf(ontology);
            if (projectRoot == null) {
                return OperationResult.failed(operationName(), "This ontology has not been "
                        + "saved, so there is no project to update.");
            }
            return OdkUpdateForm.run(operationName(), projectRoot, write, way,
                    ProcessRunner.real(), new Open());
        }
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

    // ------------------------------------------------------------------ what is open

    /**
     * Protege, as the update form needs it: whether the person stopped waiting, and the edit
     * file read again once the update has rewritten it on disk.
     */
    private final class Open implements OdkUpdateForm.Session {

        @Override
        public boolean stopped() {
            return BackgroundRun.abandoned();
        }

        @Override
        public boolean isOpen(File editFile) {
            return loadedFrom(editFile) != null;
        }

        /**
         * Three steps, each the way Protege does it itself (read from the classes of 5.5.0
         * and 5.6.9, which agree).
         *
         * <p>The catalog first ({@code OntologyCatalogManager.reloadFolder}): the update wrote
         * it, and Protege keeps what it read of it when the project was opened.
         *
         * <p>Then what the edit file imports now and Protege has not loaded: read from its
         * file in a manager on the side and moved into Protege's own, as Protege does when an
         * import is added by hand ({@code AddImportsStrategy}). Protege's reload reads the
         * edit file in a manager without the catalog, where an import resolves only if
         * Protege has it loaded already; a new one was looked for on the web and the reload
         * failed (seen in a running Protege: "Could not load imported ontology").
         *
         * <p>Then Protege's own reload ({@code OWLModelManager.reload}), on the dispatch
         * thread, where it shows its progress: what is open is patched to what the file now
         * holds. The files' times are not noted beforehand: a reload that works notes the
         * edit file's itself, and after one that does not Protege goes on to ask about the
         * file that changed outside of it - which is then the truth.
         */
        @Override
        public String readAgain(File editFile, Map<String, File> imports) {
            final OWLOntology open = loadedFrom(editFile);
            if (open == null) {
                return null;
            }
            final OWLModelManager manager = getOWLModelManager();
            final File folder = editFile.getAbsoluteFile().getParentFile();
            // What goes wrong in the first two steps shows in the third, in Protege's words.
            onDispatchThread(new Step() {
                @Override
                public void take() throws Exception {
                    manager.getOntologyCatalogManager().reloadFolder(folder);
                }
            });
            OWLOntologyManager owl = manager.getOWLOntologyManager();
            for (Map.Entry<String, File> served : imports.entrySet()) {
                if (served.getValue() == null || owl.contains(IRI.create(served.getKey()))) {
                    continue;
                }
                try {
                    OWLOntologyManager aside = OntologyManagerFactory.createManager();
                    aside.getIRIMappers().add(owl.getIRIMappers());
                    aside.loadOntologyFromOntologyDocument(
                            new IRIDocumentSource(IRI.create(served.getValue())),
                            new OWLOntologyLoaderConfiguration().setMissingImportHandlingStrategy(
                                    MissingImportHandlingStrategy.SILENT));
                    for (OWLOntology loaded : aside.getOntologies()) {
                        if (!owl.contains(loaded.getOntologyID())) {
                            owl.copyOntology(loaded, OntologyCopy.MOVE);
                        }
                    }
                } catch (Exception | LinkageError notLoaded) {
                    // Said by the reload below.
                }
            }
            return onDispatchThread(new Step() {
                @Override
                public void take() throws Exception {
                    manager.reload(open);
                }
            });
        }
    }

    /** Something to do that may fail. */
    private interface Step {
        void take() throws Exception;
    }

    /** Does it on the dispatch thread and waits; why it failed, or null. */
    private static String onDispatchThread(final Step step) {
        final String[] failure = new String[1];
        Runnable work = new Runnable() {
            @Override
            public void run() {
                try {
                    step.take();
                } catch (Exception | LinkageError cannot) {
                    failure[0] = said(cannot);
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            work.run();
            return failure[0];
        }
        try {
            SwingUtilities.invokeAndWait(work);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "the wait for it was interrupted";
        } catch (InvocationTargetException thrown) {
            return said(thrown.getCause() == null ? thrown : thrown.getCause());
        }
        return failure[0];
    }

    /** The ontology Protege has loaded from a file, or null when it has none from there. */
    private OWLOntology loadedFrom(File file) {
        for (OWLOntology loaded : getOWLModelManager().getOntologies()) {
            File document = fileOf(loaded);
            if (document != null && sameFile(document, file)) {
                return loaded;
            }
        }
        return null;
    }

    private static boolean sameFile(File one, File other) {
        try {
            return Files.isSameFile(one.toPath(), other.toPath());
        } catch (IOException | RuntimeException cannotTell) {
            return one.getAbsoluteFile().equals(other.getAbsoluteFile());
        }
    }

    /** A failure in its own words, down to the cause that says something. */
    private static String said(Throwable failure) {
        Throwable at = failure;
        while (at.getCause() != null && at.getCause() != at
                && (at.getMessage() == null || at.getMessage().trim().isEmpty()
                        || at.getMessage().equals(at.getCause().toString()))) {
            at = at.getCause();
        }
        String message = at.getMessage() == null || at.getMessage().trim().isEmpty()
                ? at.getClass().getSimpleName() : at.getMessage().trim();
        return message.endsWith(".") ? message.substring(0, message.length() - 1) : message;
    }
}
