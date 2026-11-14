package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.Catalog;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportGuard;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkContainer;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkMigration;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkUpdate;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>One thing ODK's own update does without a word is not done without one here: it takes
 * out of the edit file every import the configuration does not declare. Those imports are
 * named when looking, and before writing whoever has the project open is asked what is to
 * become of each ({@link ImportGuard}): kept - declared in the configuration, with a module -
 * or taken out.
 *
 * <p>No Protege types and no Swing: whoever has the project open is a {@link Session}.
 */
final class OdkUpdateForm {

    /** How the summary of an update that had nothing to write begins. */
    static final String ALREADY_UP_TO_DATE = "Already up to date";

    /** How the summary of an update that wrote what it was to write begins. */
    static final String UPDATED = "Updated";

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

    /** The sentence above the form for a project OntoBoard 1.135 or before made. */
    static String migrationExplanation() {
        return "This project was made by OntoBoard 1.135 or before: its Makefile is "
                + "OntoBoard's own. From 1.153 a project is ODK's - the Makefile, run.sh, "
                + "the checks ODK ships and the workflows for continuous integration are "
                + "written by ODK from the project's configuration (<id>-odk.yaml). This "
                + "update carries the project over: the configuration becomes one ODK "
                + "reads, the modules your edit file imports are declared in it, your test "
                + "queries in src/sparql go on being asked of the ontology with its "
                + "imports - by a rule added to your own makefile - and ODK's update "
                + "writes the rest. What you write yourself is kept - the ontology's "
                + "content, the README, the pages under docs/, the releases, the report "
                + "profile, the test queries, the modules and their term lists. The edit "
                + "file is saved in "
                + "functional syntax, as ODK keeps it, so the ontology is saved first and "
                + "read again afterwards. First you see what would change; nothing is "
                + "written unless you ask for it.";
    }

    /** What the result of a migration says first. */
    private static final String MIGRATION = "This project was made by OntoBoard 1.135 or "
            + "before: its Makefile is OntoBoard's own. With this update it becomes an ODK "
            + "project - its Makefile and the files beside it written by ODK from the "
            + "configuration - and keeps what is yours: the ontology's content, the README, "
            + "the pages under docs/, the releases, the report profile, the test queries, "
            + "the modules and their term lists.";

    /** What a failure after the conversion says after its own sentence. */
    private static final String GOES_ON = " What is the project's own was converted "
            + "before that - its configuration, and the rule for its questions; they are "
            + "listed - and Update project files goes on from here when it is run again.";

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
         * @param imports what the edit file imports now, each with the file of the project
         *                its catalog serves it from, or null where it serves it from none.
         *                Some may be new, and so not loaded by whoever has the project
         *                open - and the catalog was written by the update too
         * @return why that did not work, or null when it did
         */
        String readAgain(File editFile, Map<String, File> imports);

        /**
         * The update wrote the edit file again with what it held: the same bytes, a newer
         * time. There is nothing to read again; whoever has it open notes the file's new
         * time, so as not to take it for a change made behind its back.
         */
        void writtenAsItWas(File editFile);

        /**
         * Asks what is to become of the imports the update would take out.
         *
         * @return a decision for each finding, in their order; null when nothing was said
         */
        List<ImportGuard.Decision> decide(List<ImportGuard.Finding> findings);
    }

    // ------------------------------------------------------------------ the question's words

    /** The sentence above the question about the imports. */
    static String importsExplanation() {
        return "ODK's update gives the edit file the imports the project's configuration "
                + "declares, and no others. The edit file imports what is listed here without "
                + "the configuration declaring it, so the update would take it out. Say what "
                + "is to become of each. Nothing is written if you cancel.";
    }

    /**
     * What can become of an import, as the question offers it: the choice's words to what it
     * means, the first the one the question starts with. Taking out is never the first where
     * keeping is possible, and a module of the terms used is not offered where none is used -
     * it would be empty.
     */
    static LinkedHashMap<String, ImportGuard.Decision> choicesFor(
            ImportGuard.Finding finding) {
        LinkedHashMap<String, ImportGuard.Decision> choices =
                new LinkedHashMap<String, ImportGuard.Decision>();
        if (finding.isReadable()) {
            int used = finding.termsUsed();
            if (used > 0) {
                choices.put("Keep what the ontology uses of it: a module of " + used
                        + (used == 1 ? " term" : " terms"), ImportGuard.Decision.MODULE);
            }
            choices.put("Keep all of it: the whole ontology as its module",
                    ImportGuard.Decision.WHOLE);
        }
        choices.put("Take the import out", ImportGuard.Decision.REMOVE);
        return choices;
    }

    /** What the question says of one import, behind its question mark. */
    static String helpFor(ImportGuard.Finding finding, File projectRoot) {
        ImportGuard.Undeclared item = finding.item();
        StringBuilder help = new StringBuilder(item.iri()).append(" is imported by the edit "
                + "file and not declared in the project's configuration.");
        if (!finding.isReadable()) {
            return help.append("\n\nNo module can be made of it: ")
                    .append(finding.whyUnreadable()).append(". It can only be taken out. To "
                            + "keep it, cancel, put the import right, and update again.")
                    .toString();
        }
        help.append("\n\nThe ontology's own axioms use ").append(finding.termsUsed())
                .append(" of its terms.");
        help.append("\n\nKept, it becomes the product '").append(finding.name())
                .append("' in the configuration, with the module src/ontology/imports/")
                .append(finding.name()).append("_import.owl, and the edit file imports the "
                        + "module from then on.");
        help.append(!finding.isFrozen() ? "\n\nODK fetches it again from its address whenever "
                + "the imports are refreshed."
                : item.localFileOrNull() != null ? "\n\nIt is read from " + relative(
                        projectRoot.getAbsoluteFile(), item.localFileOrNull()) + ", a file of "
                        + "the project, so there is no address ODK could fetch it from "
                        + "again: ODK keeps the module as it is."
                        : "\n\nIt is read from a file on this machine, which is an address "
                                + "on no other, so there is nothing ODK could fetch it from "
                                + "again: ODK keeps the module as it is, and the path is "
                                + "written into no file of the project.");
        return help.toString();
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
        OdkMigration.Plan migration = null;
        boolean converted = false;
        boolean editFileChanges;
        try {
            if (OdkMigration.isOntoBoards1x(root)) {
                // A project of OntoBoard's older layout: what is its own is converted
                // first, and the update is planned on the configuration as it becomes.
                migration = OdkMigration.plan(root);
                plan = migration.update();
            } else {
                plan = OdkUpdate.plan(root);
            }
            // Also what stops ODK's own update half way: found here, before anybody writes
            // and before anybody is asked anything.
            editFileChanges = OdkUpdate.check(root, plan);
        } catch (OdkUpdate.Refused refused) {
            return result.failed(refused.getMessage()).build();
        } catch (RuntimeException unreadable) {
            return result.failed("The project could not be looked at: " + said(unreadable)
                    + ". Nothing was written.").build();
        }
        result.note("Project: " + root.getPath());

        int converting = 0;
        if (migration != null) {
            result.note(MIGRATION);
            result.row(migration.configPath(), migration.changesTheConfig()
                    ? "changes: becomes a configuration ODK's update goes by" : "as it is");
            converting += migration.changesTheConfig() ? 1 : 0;
            int questions = migration.questions().size();
            if (migration.addsTheQuestionRule()) {
                result.row(migration.ownMakefilePath(), "changes: the rule that asks the "
                        + "project's questions is added at its end");
                converting++;
            }
            if (questions > 0) {
                result.note("Your " + (questions == 1 ? "test query" : questions + " test "
                        + "queries") + " in src/sparql (" + String.join(", ",
                        migration.questions()).replace("src/sparql/", "") + ") "
                        + (questions == 1 ? "stays as it is and is" : "stay as they are and "
                                + "are") + " asked of the ontology with its imports, as "
                        + "before: ODK's own checks read the edit file without its "
                        + "imports, where such a query changes its answer. The rule that "
                        + "asks them is " + (migration.addsTheQuestionRule() ? "added to "
                                : "in ") + migration.ownMakefilePath() + ", and ODK's test "
                        + "runs it.");
            }
            for (String said : migration.declared()) {
                result.note(said);
            }
            for (String said : migration.notes()) {
                result.note(said);
            }
            for (String said : migration.warnings()) {
                result.warn(said);
            }
            if (!write && migration.changesTheConfig()) {
                result.note("The configuration as it becomes:");
                for (String line : migration.newConfig().replace("\r\n", "\n")
                        .split("\n")) {
                    // A bar first: a note is kept without the blanks it starts with.
                    result.note("| " + line);
                }
            }
        }

        // The imports the update would take out: named when looking, decided before writing.
        // (For a migration the list holds what the conversion wrote as well.)
        List<File> declared = new ArrayList<File>();
        int toDecide = plan.undeclared().size();
        if (toDecide > 0 && !write) {
            for (ImportGuard.Undeclared item : plan.undeclared()) {
                result.note("The edit file imports " + item.iri() + (item.localFileOrNull()
                        == null ? "" : " (read from " + relative(root, item.localFileOrNull())
                        + ")") + ", which the configuration does not declare: ODK's update "
                        + "would take this import out. With 'Write the changes' on you are "
                        + "asked first what is to become of it.");
            }
        }
        if (toDecide > 0 && write) {
            List<String> takenOut = new ArrayList<String>();
            for (ImportGuard.Undeclared item : plan.undeclared()) {
                takenOut.add(item.iri());
            }
            if (session == null) {
                return result.failed(ImportGuard.sentenceFor(takenOut)).build();
            }
            if (session.stopped()) {
                result.note("Nothing was written - you stopped waiting before it finished.");
                return result.summary("Nothing was written: this was abandoned.").build();
            }
            ImportGuard.Reading reading;
            List<ImportGuard.Finding> findings;
            try {
                reading = ImportGuard.read(root, plan.editFile(),
                        migration == null ? null : migration.newConfig());
                findings = reading.findings(plan.undeclared());
            } catch (IOException | RuntimeException unreadable) {
                return result.failed("The imports the update would take out could not be "
                        + "looked at: " + said(unreadable) + ". Nothing was written.").build();
            }
            List<ImportGuard.Decision> decisions = session.decide(findings);
            if (decisions == null || decisions.size() != findings.size()) {
                result.note("Nothing was written - what is to become of the imports the update "
                        + "would take out was not said.");
                return result.summary("Nothing was written.").build();
            }
            if (migration != null) {
                // Answered: from here on things are written. The conversion first - what
                // is declared for an import goes into the configuration as it becomes.
                String notConverted = convert(root, migration, declared);
                if (notConverted != null) {
                    return result.failed(notConverted).build();
                }
                converted = true;
            }
            for (int i = 0; i < findings.size(); i++) {
                ImportGuard.Finding finding = findings.get(i);
                ImportGuard.Decision decision = decisions.get(i);
                try {
                    for (File file : reading.declare(finding, decision)) {
                        if (!declared.contains(file)) {
                            declared.add(file);
                        }
                    }
                } catch (IOException | RuntimeException notDeclared) {
                    for (File file : declared) {
                        result.wrote(file);
                    }
                    return result.failed(finding.item().iri() + " could not be declared: "
                            + said(notDeclared) + ". The update was not made."
                            + (declared.isEmpty() ? "" : " What had been written for the "
                                    + "imports before it is listed.")
                            + (converted ? GOES_ON : "")).build();
                }
                result.note(decision == ImportGuard.Decision.REMOVE
                        ? finding.item().iri() + ": the update takes this import out, as you "
                                + "said."
                        : finding.item().iri() + ": declared as the product " + finding.name()
                                + (decision == ImportGuard.Decision.MODULE
                                        ? ", its module the " + finding.termsUsed()
                                                + (finding.termsUsed() == 1 ? " term"
                                                        : " terms") + " of it the ontology uses"
                                        : ", its module the whole ontology")
                                + (finding.isFrozen() ? "; ODK keeps the module as it is - it "
                                        + "is read from a file, so there is no address to "
                                        + "fetch it from again" : "; ODK fetches it "
                                        + "again from its address when the imports are "
                                        + "refreshed") + ".");
            }
            try {
                // Again: the configuration has changed. What was undeclared is taken out by
                // the update now - agreed to, or with its module in its place.
                plan = OdkUpdate.plan(root).removing(takenOut);
                editFileChanges = OdkUpdate.check(root, plan);
            } catch (RuntimeException notPlanned) {
                for (File file : declared) {
                    result.wrote(file);
                }
                return result.failed(said(notPlanned) + (declared.isEmpty() ? ""
                        : " What had been written for the imports is listed.")
                        + (converted ? GOES_ON : "")).build();
            }
        }

        result.note("ODK: " + (migration != null ? "the project's Makefile is OntoBoard's "
                + "own" : plan.fromVersion() == null ? "the project's Makefile does not "
                + "say which wrote it" : "the project's files are " + plan.fromVersion() + "'s")
                + "; the update writes " + plan.toVersion() + "'s.");
        int changing = converting;
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
        for (String module : plan.notImported()) {
            result.note(module + " is a module nothing imports: the edit file does not import "
                    + "it, and the configuration declares no product for it. The update "
                    + "leaves it as it is.");
        }
        for (String said : plan.warnings()) {
            result.note("ODK's update says: " + said);
        }

        if (changing == 0 && declared.isEmpty()) {
            return result.summary(ALREADY_UP_TO_DATE + ": the project's files are what ODK "
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
                    + (fresh == 0 ? "" : ", " + fresh + " of them new")
                    + (toDecide == 0 ? "." : "; " + toDecide + (toDecide == 1 ? " import"
                            : " imports") + " the update would take out, to decide about "
                            + "first.")
                    + (migration == null ? "" : " The project becomes an ODK project."))
                    .build();
        }
        for (File file : declared) {
            result.wrote(file);
        }
        if (session != null && session.stopped()) {
            result.note(declared.isEmpty() ? "Nothing was written - you stopped waiting before "
                    + "it finished." : "The update was not made - you stopped waiting before "
                    + "it finished. What had been written for the imports is listed.");
            return result.summary(count + " would have changed, but this was abandoned.")
                    .build();
        }

        if (migration != null && !converted) {
            String notConverted = convert(root, migration, declared);
            if (notConverted != null) {
                return result.failed(notConverted).build();
            }
            converted = true;
            for (File file : declared) {
                result.wrote(file);
            }
        }
        // What a failure from here on says besides: the conversion stands.
        String besides = converted ? GOES_ON : "";

        List<File> written;
        String failure = null;
        boolean editWrittenAsItWas = false;
        if (way.inContainer()) {
            // ODK's own update writes the edit file again also where its imports are what
            // the configuration asks for: the same bytes, a newer time.
            de.fizkarlsruhe.ise.ontoboard.odk.FileStamps editBefore = editFile == null ? null
                    : de.fizkarlsruhe.ise.ontoboard.odk.FileStamps.of(
                            java.util.Collections.singletonList(editFile));
            OdkUpdate.Ran ran;
            try {
                ran = OdkUpdate.inContainer(way.runtime, way.image, way.host, root, plan,
                        runner);
            } catch (OdkUpdate.Refused refused) {
                return result.failed(refused.getMessage() + besides).build();
            }
            for (String line : ran.getTranscript()) {
                result.note(line);
            }
            written = ran.getWritten();
            failure = ran.getFailure();
            editWrittenAsItWas = editBefore != null && !written.contains(editFile)
                    && !editBefore.now().writtenSince(editBefore).isEmpty();
        } else {
            result.note("Made by OntoBoard itself, as ODK's own update makes it; no container "
                    + "was used.");
            byte[] editBefore = bytesOrNull(editFile);
            try {
                written = OdkUpdate.apply(root, plan);
            } catch (OdkUpdate.Refused refused) {
                return result.failed(refused.getMessage() + besides).build();
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
        int all = declared.size();
        for (File file : written) {
            if (!declared.contains(file)) {
                result.wrote(file);
                all++;
            }
        }
        if (editFile != null && written.contains(editFile) && session != null
                && session.isOpen(editFile)) {
            String notRead = session.readAgain(editFile, servedFrom(root, plan.imports()));
            if (notRead == null) {
                result.note("The edit file was rewritten on disk, and the ontology was read "
                        + "again from it.");
            } else {
                result.warn("The edit file was rewritten on disk, and reading it again did "
                        + "not work: " + notRead + ". What is open is behind the file now. "
                        + "Close the project and open it again before you edit - saving "
                        + "what is open would write the old import declarations back.");
            }
        } else if (editWrittenAsItWas && session != null && session.isOpen(editFile)) {
            // No change, so it is not listed and nothing is read again - but whoever has
            // it open would take the newer time for one.
            session.writtenAsItWas(editFile);
        }
        if (failure != null) {
            return result.failed(failure + besides).build();
        }
        result.note("Commit these as a change of their own, so that the update can be told "
                + "from your work.");
        return result.summary(UPDATED + " " + all + (all == 1 ? " file" : " files")
                + (way.inContainer() ? " with ODK's own update, in its container."
                        : ", by OntoBoard itself.")
                + (migration == null ? "" : " The project is an ODK project now.")).build();
    }

    /**
     * Writes the conversion of a project of the older layout - its configuration, and the
     * rule for its questions - and adds what it wrote to {@code into}.
     *
     * @return why it was not written, for a person to read; null when it was
     */
    private static String convert(File root, OdkMigration.Plan migration, List<File> into) {
        try {
            for (File file : OdkMigration.convert(root, migration)) {
                if (!into.contains(file)) {
                    into.add(file);
                }
            }
            return null;
        } catch (IOException | RuntimeException notWritten) {
            return "The project's configuration could not be converted: "
                    + said(notWritten) + ". The update was not made; run it again once that "
                    + "is put right.";
        }
    }

    /** The file of the project its catalog serves each import from; null where from none. */
    private static Map<String, File> servedFrom(File root, List<String> imports) {
        File catalog = new File(root, "src/ontology/catalog-v001.xml");
        byte[] bytes = bytesOrNull(catalog);
        String text = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        Map<String, File> served = new LinkedHashMap<String, File>();
        for (String iri : imports) {
            String path = Catalog.entryFor(text, iri);
            File file = path == null || path.contains(":") ? null
                    : new File(catalog.getParentFile(), path.replace('/', File.separatorChar));
            served.put(iri, file != null && file.isFile() ? file.getAbsoluteFile() : null);
        }
        return served;
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
    private static String said(Exception failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage().trim();
        return message.endsWith(".") ? message.substring(0, message.length() - 1) : message;
    }

    private static String relative(File root, File file) {
        return root.toPath().relativize(file.getAbsoluteFile().toPath()).toString()
                .replace('\\', '/');
    }
}
