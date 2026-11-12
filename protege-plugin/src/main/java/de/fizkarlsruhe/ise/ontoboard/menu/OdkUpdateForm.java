package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkContainer;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkUpdate;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Update project files for an ODK project: what would change, shown first and nothing written;
 * then, when asked, the update.
 *
 * <p>The update is ODK's: the project's files are brought in line with its configuration
 * ({@code <id>-odk.yaml}) as {@code odk.py update} of ODK does it. ODK's own program makes it
 * in ODK's container where that runs and ODK's image is on the machine; otherwise OntoBoard
 * makes it itself ({@link OdkUpdate}), with the same result. What would change is worked out
 * by OntoBoard in either case, so that it can be shown before anything is written - ODK's own
 * update has no such look.
 *
 * <p>No Protege types and no Swing: whoever has the project open is a {@link Session}.
 */
final class OdkUpdateForm {

    /** The form's one choice: whether to write. Off when the form opens. */
    static final String WRITE = "write";

    private OdkUpdateForm() {
    }

    // ------------------------------------------------------------------ who makes it

    /** Who makes the update: ODK's own program in its container, or OntoBoard itself. */
    static final class Way {
        private final String runtime;
        private final String image;
        private final OdkContainer.Host host;
        private final String refusal;

        private Way(String runtime, String image, OdkContainer.Host host, String refusal) {
            this.runtime = runtime;
            this.image = image;
            this.host = host;
            this.refusal = refusal;
        }

        /** Whether ODK's own program makes it, in its container. */
        boolean inContainer() {
            return refusal == null && runtime != null;
        }

        /** Why nobody makes it, for a person to read; null when somebody does. */
        String refusal() {
            return refusal;
        }
    }

    /**
     * @param use         what the person settled about containers
     * @param runtime     the program that runs containers here, or null
     * @param image       ODK's image for an update, when it is on the machine; else null -
     *                    nothing is fetched to make an update
     * @param noContainer why no container makes it, as the start of a sentence; null when one
     *                    does
     * @param howToGetOne what to do to have one, said with a refusal; or null
     */
    static Way way(OdkRoute.Use use, String runtime, String image, OdkContainer.Host host,
            String noContainer, String howToGetOne) {
        if (runtime != null && image != null) {
            return new Way(runtime, image, host, null);
        }
        if (!OdkRoute.mayRunWithoutContainer(use)) {
            return new Way(null, null, null, noContainer + ", and OntoBoard's setting for "
                    + "ODK's targets is \"" + use.label() + "\": nothing is written."
                    + (howToGetOne == null ? "" : "\n\n" + howToGetOne));
        }
        return new Way(null, null, null, null);
    }

    /** The sentence above the form. */
    static String explanation() {
        return "Brings this project's files in line with its configuration (<id>-odk.yaml), "
                + "as ODK's own update does. The Makefile, run.sh, the checks ODK ships, the "
                + "workflows for continuous integration and ODK's pages on its workflows are "
                + "written again; what the configuration now asks for and is missing is added; "
                + "what you write yourself - the ontology's content, the README, your own "
                + "makefile and checks - is not touched. The edit file's import declarations "
                + "are brought in line with the configuration, so the ontology is saved first "
                + "and read again afterwards. First you see what would change; nothing is "
                + "written unless you ask for it.";
    }

    // ------------------------------------------------------------------ whoever has it open

    /** What the form needs of whoever has the project open. */
    interface Session {

        /** Whether the person stopped waiting: nothing is written then. */
        boolean stopped();

        /** Whether the edit file is open here, so that it is behind once the update wrote it. */
        boolean isOpen(File editFile);

        /**
         * Reads the edit file again from disk.
         *
         * @return why that did not work, or null when it did
         */
        String readAgain(File editFile);
    }

    // ------------------------------------------------------------------ the update

    /** As {@link #run(String, File, boolean, Way, ProcessRunner.Runner, Session)}, with nobody
     *  who has the project open. */
    static OperationResult run(String operation, File projectRoot, boolean write, Way way,
            ProcessRunner.Runner runner) {
        return run(operation, projectRoot, write, way, runner, null);
    }

    /**
     * Looks at what an update would do and, when asked, makes it.
     *
     * @param write   false to look only
     * @param runner  starts ODK's container, when that makes the update
     * @param session whoever has the project open, or null
     */
    static OperationResult run(String operation, File projectRoot, boolean write, Way way,
            ProcessRunner.Runner runner, Session session) {
        OperationResult.Builder result = OperationResult.of(operation).columns("File", "State");
        // Looking writes nothing and starts nothing, so nobody refuses it.
        if (write && way.refusal() != null) {
            return result.failed(way.refusal()).build();
        }
        File root = projectRoot.getAbsoluteFile();
        OdkUpdate.Plan plan;
        boolean editFileChanges;
        try {
            plan = OdkUpdate.plan(root);
            // Also what stops ODK's own update half way: found here, before anybody writes.
            editFileChanges = OdkUpdate.check(root, plan);
        } catch (OdkUpdate.Refused refused) {
            return result.failed(refused.getMessage()).build();
        } catch (RuntimeException unreadable) {
            return result.failed("The project could not be looked at: " + said(unreadable)
                    + ". Nothing was written.").build();
        }
        result.note("Project: " + root.getPath());
        result.note("ODK: " + (plan.fromVersion() == null ? "the project's Makefile does not "
                + "say which wrote it" : "the project's files are " + plan.fromVersion() + "'s")
                + "; the update writes " + plan.toVersion() + "'s.");

        int changing = 0;
        int fresh = 0;
        for (OdkUpdate.Change change : plan.changes()) {
            result.row(change.path(), change.isNew() ? "new"
                    : change.isChanged() ? "changes" : "as it is");
            if (change.isChanged()) {
                changing++;
                fresh += change.isNew() ? 1 : 0;
            }
        }
        File editFile = plan.editFile();
        if (editFile != null) {
            result.row(relative(root, editFile), editFileChanges ? "changes: its import "
                    + "declarations are brought in line with the configuration" : "as it is");
            changing += editFileChanges ? 1 : 0;
            result.note(plan.imports().isEmpty() ? "The edit file imports nothing after the "
                    + "update: the configuration declares no import." : "The edit file imports "
                    + "after the update: " + String.join(", ", plan.imports()));
        }
        for (String said : plan.warnings()) {
            result.note("ODK's update says: " + said);
        }

        if (changing == 0) {
            return result.summary("Already up to date: the project's files are what ODK "
                    + plan.toVersion() + " writes for its configuration.").build();
        }
        String count = changing + (changing == 1 ? " file" : " files");
        if (!write) {
            result.note("Nothing was written - 'Write the changes' was off.");
            result.note(way.refusal() != null
                    ? "As things are, nobody would write them: " + way.refusal()
                    : way.inContainer() ? "With it on, ODK's own update writes them, in its "
                            + "container (" + way.image + ")."
                            : "With it on, OntoBoard itself writes them, as ODK's own "
                                    + "update does; no container is used.");
            return result.summary(count + " would change"
                    + (fresh == 0 ? "" : ", " + fresh + " of them new") + ".").build();
        }
        if (session != null && session.stopped()) {
            result.note("Nothing was written - you stopped waiting before it finished.");
            return result.summary(count + " would have changed, but this was abandoned.")
                    .build();
        }

        List<File> written;
        String failure = null;
        if (way.inContainer()) {
            OdkUpdate.Ran ran;
            try {
                ran = OdkUpdate.inContainer(way.runtime, way.image, way.host, root, plan,
                        runner);
            } catch (OdkUpdate.Refused refused) {
                return result.failed(refused.getMessage()).build();
            }
            for (String line : ran.getTranscript()) {
                result.note(line);
            }
            written = ran.getWritten();
            failure = ran.getFailure();
        } else {
            result.note("Made by OntoBoard itself, as ODK's own update makes it; no container "
                    + "was used.");
            byte[] editBefore = bytesOrNull(editFile);
            try {
                written = OdkUpdate.apply(root, plan);
            } catch (OdkUpdate.Refused refused) {
                return result.failed(refused.getMessage()).build();
            } catch (RuntimeException stopped) {
                written = noLongerAsTheyWere(root, plan, editBefore);
                failure = "The update stopped part way: " + said(stopped) + ". "
                        + (written.isEmpty() ? "Nothing was written."
                                : written.size() + (written.size() == 1 ? " file was"
                                        : " files were") + " written before it stopped; they "
                                        + "are listed. The project is part old and part new "
                                        + "now - update again once the cause is put right.");
            }
        }
        for (File file : written) {
            result.wrote(file);
        }
        if (editFile != null && written.contains(editFile) && session != null
                && session.isOpen(editFile)) {
            String notRead = session.readAgain(editFile);
            if (notRead == null) {
                result.note("The edit file was rewritten on disk, and the ontology was read "
                        + "again from it.");
            } else {
                result.warn("The edit file was rewritten on disk, and reading it again did "
                        + "not work: " + notRead + ". What is open is behind the file now. "
                        + "Read it again with File > Reload, or close the project and open it "
                        + "again, before you edit - saving what is open would write the old "
                        + "import declarations back.");
            }
        }
        if (failure != null) {
            return result.failed(failure).build();
        }
        result.note("Commit these as a change of their own, so that the update can be told "
                + "from your work.");
        return result.summary("Updated " + written.size()
                + (written.size() == 1 ? " file" : " files") + (way.inContainer()
                        ? " with ODK's own update, in its container." : ", by OntoBoard itself.")
        ).build();
    }

    /**
     * What a stopped update had written by then: the files of the plan that are no longer as
     * the plan found them.
     */
    private static List<File> noLongerAsTheyWere(File root, OdkUpdate.Plan plan,
            byte[] editBefore) {
        List<File> changed = new ArrayList<File>();
        for (OdkUpdate.Change change : plan.changes()) {
            File file = new File(root, change.path().replace('/', File.separatorChar));
            if (!Arrays.equals(change.before(), bytesOrNull(file))) {
                changed.add(file);
            }
        }
        if (plan.editFile() != null
                && !Arrays.equals(editBefore, bytesOrNull(plan.editFile()))) {
            changed.add(plan.editFile());
        }
        return changed;
    }

    /** The file's bytes; null when there is no such file or it cannot be read. */
    private static byte[] bytesOrNull(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** A failure's own words, without a full stop to run into the next sentence. */
    private static String said(RuntimeException failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage().trim();
        return message.endsWith(".") ? message.substring(0, message.length() - 1) : message;
    }

    private static String relative(File root, File file) {
        return root.toPath().relativize(file.getAbsoluteFile().toPath()).toString()
                .replace('\\', '/');
    }
}
