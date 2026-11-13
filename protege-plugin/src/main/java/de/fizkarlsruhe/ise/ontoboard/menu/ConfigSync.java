package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.MakeRun;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkContainer;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkImage;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkSync;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.awt.Component;
import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.protege.editor.owl.model.OWLModelManager;

/**
 * After an action changed an ODK project's configuration - Import terms, the pattern library,
 * a question's check, Project configuration - the project's files are brought in line with it,
 * as Update project files writes them: the question about imports the update would take out
 * first, the files written, Protege reading the edit file and new imports again afterwards.
 * Who does it is {@link OdkSync}'s: ODK's own update in the image of the project's own ODK,
 * OntoBoard's own for a project on the pinned ODK, else nobody now - and what is said names
 * which, or why not and what brings the files in line later.
 */
public final class ConfigSync {

    private static final String OPERATION = "Update project files";

    /** What came of bringing the files in line. */
    public static final class Done {
        private final boolean inLine;
        private final String said;
        private final OperationResult result;

        private Done(boolean inLine, String said, OperationResult result) {
            this.inLine = inLine;
            this.said = said;
            this.result = result;
        }

        /** Whether the project's files are what its configuration asks for now. */
        public boolean inLine() {
            return inLine;
        }

        /** What came of it, in a sentence or two for whoever changed the configuration. */
        public String said() {
            return said;
        }

        /**
         * Whether the update's whole result is worth showing: it failed, or it has something
         * to warn of - where it stopped, what could not be read again.
         */
        public boolean needsALook() {
            return result != null && result.getStatus() != OperationResult.Status.SUCCEEDED;
        }

        /** The update's own result; null where nobody ran one. */
        public OperationResult result() {
            return result;
        }
    }

    private ConfigSync() {
    }

    /**
     * Who would bring this project in line now. The machine is asked - which program runs
     * containers, which images are on it - and nothing is run or fetched.
     */
    static OdkSync.Way wayFor(File editFile) {
        File ontologyDirectory = editFile.getAbsoluteFile().getParentFile();
        ProcessRunner.Runner real = ProcessRunner.real();
        OdkRoute.Use use = OdkRoute.use();
        String version = OdkImage.versionOfProject(ontologyDirectory);
        String runtime = use == OdkRoute.Use.NEVER_CONTAINER ? null
                : MakeRun.containerRuntime(real);
        // The image of the ODK that wrote the project's files and no other: an update in
        // another ODK's image would move the project to that ODK.
        String own = runtime == null ? null
                : OdkImage.ofProjectsOwnVersion(ontologyDirectory, OdkImage.probe(runtime, real));
        String noContainer = own != null ? null
                : runtime == null ? OdkRoute.noContainerBecause(real)
                : version == null ? "This project's Makefile does not say which ODK wrote it, "
                        + "so no image on this machine is known to be its own"
                : "The image of this project's ODK (" + version + ") is not on this machine";
        return OdkSync.decide(version, use, runtime, own, noContainer);
    }

    /**
     * What to say of an update that ran, or of none.
     *
     * @param way    who was to bring the files in line
     * @param synced the update's result; null where nobody ran one
     */
    static Done done(OdkSync.Way way, OperationResult synced) {
        if (synced == null) {
            return new Done(false, way.why(), null);
        }
        String summary = synced.getSummary();
        // By how the update's own summary begins, not by whether it lists files: an update
        // given up after the imports were declared lists those and wrote nothing else.
        if (synced.getStatus() != OperationResult.Status.FAILED
                && summary.startsWith(OdkUpdateForm.UPDATED)) {
            return new Done(true, "Then the project's files were brought in line with it, "
                    + "as OntoBoard > Project > Update project files... does: " + summary,
                    synced);
        }
        if (synced.getStatus() != OperationResult.Status.FAILED
                && summary.startsWith(OdkUpdateForm.ALREADY_UP_TO_DATE)) {
            return new Done(true, "The project's files needed no change for it.", synced);
        }
        return new Done(false, summary + (summary.endsWith(".") ? "" : ".")
                + " The configuration was written; "
                + "OntoBoard > Project > Update project files... brings the project's "
                + "files in line with it" + (synced.getStatus()
                        == OperationResult.Status.FAILED ? " once that is sorted out." : "."),
                synced);
    }

    /**
     * Brings the project in line, the way decided. Off the dispatch thread: it may start a
     * container, and it asks about imports the update would take out.
     *
     * @param workspace Protege's window, for that question
     * @param editFile  the project's edit file, saved
     */
    static Done run(Component workspace, OWLModelManager models, File editFile,
            OdkSync.Way way) {
        if (way.how() == OdkSync.How.CONFIG_ONLY) {
            return done(way, null);
        }
        File root = ReleaseAction.projectRootOf(editFile);
        ProcessRunner.Runner real = ProcessRunner.real();
        OperationResult synced;
        try {
            OdkUpdateForm.Way update = way.how() == OdkSync.How.CONTAINER
                    ? OdkUpdateForm.way(OdkRoute.Use.AUTOMATIC, way.runtime(), way.image(),
                            OdkContainer.Host.detect(way.runtime(), real), null, null)
                    : OdkUpdateForm.way(OdkRoute.Use.AUTOMATIC, null, null, null, null, null);
            synced = OdkUpdateForm.run(OPERATION, root, true, update, real,
                    new ProtegeSession(workspace, models, root));
        } catch (RuntimeException failed) {
            synced = OperationResult.failed(OPERATION, "The project's files could not be "
                    + "brought in line: " + OntoBoardAction.said(failed) + ".");
        }
        return done(way, synced);
    }

    /**
     * For an action that reports with a result of its own: what came of it, into that result.
     * A plain success is one sentence and the files written; anything else is a warning, with
     * the update's own log after it - that is where it says what stopped it.
     */
    static void tell(OperationResult.Builder result, Done done) {
        if (done.result() != null) {
            for (File file : done.result().getFiles()) {
                result.wrote(file);
            }
        }
        if (done.inLine() && !done.needsALook()) {
            result.note(done.said());
            return;
        }
        result.warn(done.said());
        if (done.needsALook()) {
            result.note("What the update itself said:");
            for (String line : done.result().getLog()) {
                result.note("  " + line);
            }
        }
    }

    /**
     * For an action that says what it did in a message of its own - a question's check,
     * Project configuration. Called on the dispatch thread once the configuration is written;
     * returns at once. The update runs behind OntoBoard's waiting dialog, and {@code then} is
     * called on the dispatch thread with what came of it. Where the update has more to say
     * than a sentence - it failed, it warns of something - its own result is shown first.
     *
     * <p>An ontology with changes that are not saved is asked about first: the update writes
     * the edit file on disk where its imports change, and Protege reads it again from there.
     *
     * @param workspace Protege's window
     * @param editFile  the project's edit file
     */
    public static void afterConfigChange(final Component workspace, final OWLModelManager models,
            final File editFile, final Consumer<Done> then) {
        if (SaveFirst.isNeeded(models) && SaveFirst.ask(workspace, models, "The ontology has "
                + "changes that are not saved yet. The project's files are brought in line "
                + "with the configuration now - where its imports change, the edit file is "
                + "rewritten on disk and read again from there, so they would be lost.",
                "Save and bring in line", "Leave the files for later", "Cannot save")
                != SaveFirst.Answer.SAVED) {
            then.accept(done(OdkSync.later("The ontology has changes that are not saved, so "
                    + "the project's files were left as they are"), null));
            return;
        }
        final AtomicReference<Done> made = new AtomicReference<Done>();
        BackgroundRun.execute(workspace, "Bring the project's files in line",
                new Callable<OperationResult>() {
                    @Override
                    public OperationResult call() {
                        Done done = run(workspace, models, editFile, wayFor(editFile));
                        made.set(done);
                        return done.result();
                    }
                }, new Consumer<OperationResult>() {
                    @Override
                    public void accept(OperationResult given) {
                        Done done = made.get();
                        if (done == null) {
                            // Given up while it ran, or it threw: what BackgroundRun
                            // says of that is the sentence, and nothing more is shown.
                            done = done(null, given);
                        } else if (done.needsALook()) {
                            ResultDialog.show(workspace, done.result());
                        }
                        then.accept(done);
                    }
                });
    }
}
