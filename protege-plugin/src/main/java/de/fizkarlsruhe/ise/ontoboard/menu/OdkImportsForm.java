package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.ImportProducts;
import de.fizkarlsruhe.ise.ontoboard.odk.MakeRun;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Refresh imports for an ODK project: the modules are built by ODK's own targets for them -
 * {@code refresh-imports}, {@code refresh-<id>} and their {@code no-mirror-} kin - and not by
 * an extraction of OntoBoard's own that comes close to them.
 *
 * <p>Who runs the targets is decided as for Build and Release: ODK's own tools where they are
 * installed on the machine, else ODK in its container, else OntoBoard itself, which runs ODK's
 * Makefile with its own engine and fetches what the Makefile fetches. What the person settled
 * about containers is kept to.
 *
 * <p>What the run changed is on disk, and whoever has the project open still holds the modules
 * as they were: each module file that is no longer what it was is handed over to be read
 * again - also after a run that failed part way.
 *
 * <p>No Swing and nothing of Protege's: {@code RefreshImportsAction} shows what this decides.
 */
final class OdkImportsForm {

    /** Whether to rebuild; off when the form opens, so that it reports and touches nothing. */
    static final String REBUILD = "rebuild";
    /** Whether the ontologies the project mirrors are fetched again first. */
    static final String FETCH = "mirror";
    /** Every import, or one. */
    static final String WHICH = "which";
    /** The first choice of {@link #WHICH}. */
    static final String ALL = "All of them";

    private OdkImportsForm() {
    }

    // ------------------------------------------------------------------ who refreshes

    /** Who runs ODK's targets for the imports - or why nobody does. */
    static final class Way {

        /** Who runs them. */
        enum Kind {
            /** ODK's own tools, installed on the machine (ODK Core). */
            NATIVE,
            /** ODK in its container. */
            CONTAINER,
            /** OntoBoard itself: ODK's Makefile run by OntoBoard's engine. */
            ITSELF
        }

        private final Kind kind;
        private final String refusal;
        private final String who;

        private Way(Kind kind, String refusal, String who) {
            this.kind = kind;
            this.refusal = refusal;
            this.who = who;
        }

        /** Who runs the targets; null when nobody does. */
        Kind kind() {
            return kind;
        }

        /** Why nobody runs them, for a person to read; null when somebody does. */
        String refusal() {
            return refusal;
        }

        /** Who runs them, as the end of a sentence: "in ODK's container (...)". */
        String who() {
            return who;
        }
    }

    /**
     * @param nativeActivation the activation script of a native ODK environment, or null when
     *                         none is set
     * @param use              what the person settled about containers
     * @param runtime          the program that runs containers here, or null
     * @param image            ODK's image, when it is on the machine; else null - nothing is
     *                         fetched to refresh imports
     * @param noContainer      why no container runs the targets, as the start of a sentence;
     *                         null when one does
     * @param howToGetOne      what to do to have one, said with a refusal; or null
     */
    static Way way(File nativeActivation, OdkRoute.Use use, String runtime, String image,
            String noContainer, String howToGetOne) {
        boolean onlyAContainer = !OdkRoute.mayRunWithoutContainer(use);
        if (nativeActivation != null && !onlyAContainer) {
            return new Way(Way.Kind.NATIVE, null, "with ODK's own tools installed on this "
                    + "machine");
        }
        if (runtime != null && image != null) {
            return new Way(Way.Kind.CONTAINER, null, "in ODK's container (" + image
                    + ", through " + runtime + ")");
        }
        if (onlyAContainer) {
            return new Way(null, noContainer + ", and OntoBoard's setting for ODK's targets is \""
                    + use.label() + "\": nothing is run."
                    + (howToGetOne == null ? "" : "\n\n" + howToGetOne), "");
        }
        return new Way(Way.Kind.ITSELF, null, "by OntoBoard itself, inside Protege, with ODK's "
                + "own Makefile (" + noContainer + ")");
    }

    // ------------------------------------------------------------------ the form

    /**
     * The sentence above the form.
     *
     * @param found what the project declares, in a sentence
     */
    static String explanation(String found, Way way) {
        return "An import module is meant to be regenerated from a committed list of terms, "
                + "not kept as a file. " + found + " " + (way.refusal() == null
                        ? "Rebuilding runs ODK's own targets for this project's imports "
                                + "(refresh-imports, or refresh-<id> for one of them), "
                                + way.who() + "."
                        : "Rebuilding would run ODK's own targets for them (refresh-imports), "
                                + "but as things are: " + way.refusal().replace("\n\n", " "));
    }

    /** The form's fields: whether to rebuild, whether to fetch again, and which import. */
    static List<Parameter> parameters(List<ImportProducts.Product> declared, Way way) {
        List<Parameter> fields = new ArrayList<Parameter>();
        fields.add(Parameter.of(REBUILD, "Rebuild the modules", Parameter.Kind.FLAG)
                .defaultValue("false")
                .help("Off by default, so this reports without touching anything and without "
                        + "needing the network - which is the question worth asking about a "
                        + "repository you have just cloned: can it be rebuilt at all?\n\n"
                        + "Turning it on runs ODK's own targets for the imports, as "
                        + "'sh run.sh make refresh-imports' does in a terminal"
                        + (way.refusal() == null ? " - here " + way.who() + "." : "."))
                .build());
        fields.add(Parameter.of(FETCH, "Download fresh copies first (update the mirror)",
                Parameter.Kind.FLAG)
                .defaultValue("false")
                .help("ODK keeps a copy of each ontology the project imports from under "
                        + "src/ontology/mirror/ and builds the modules from those copies.\n\n"
                        + "On: every copy is fetched again before the modules are built "
                        + "(ODK's refresh-imports) - what you want when an imported ontology "
                        + "has released.\n\nOff: the modules are built again from the copies "
                        + "as they are (ODK's no-mirror-refresh-imports) - enough after a term "
                        + "was added to a term list, and it needs no network. An import that "
                        + "has no copy yet is fetched all the same: git does not keep the "
                        + "mirror, so a fresh clone has none.")
                .build());
        if (!declared.isEmpty()) {
            List<String> choices = new ArrayList<String>();
            choices.add(ALL);
            for (ImportProducts.Product product : declared) {
                choices.add(product.getId());
            }
            fields.add(Parameter.of(WHICH, "Which import", Parameter.Kind.CHOICE)
                    .choices(choices.toArray(new String[0]))
                    .defaultValue(ALL)
                    .help("Every import the project declares, or one of them (ODK's "
                            + "refresh-<id>) - quicker when one term list changed or one "
                            + "ontology has released.")
                    .build());
        }
        return fields;
    }

    // ------------------------------------------------------------------ what is asked of make

    /** What was asked, as targets of ODK's Makefile. */
    static final class Asked {
        private final List<String> targets;
        private final List<String> notes;

        private Asked(List<String> targets, List<String> notes) {
            this.targets = Collections.unmodifiableList(targets);
            this.notes = Collections.unmodifiableList(notes);
        }

        /** The targets, in the order to run them. */
        List<String> targets() {
            return targets;
        }

        /** What is done otherwise than asked, and why; for the result's log. */
        List<String> notes() {
            return notes;
        }
    }

    /**
     * The targets of ODK's Makefile that do what was asked.
     *
     * <p>ODK's no-mirror targets build from the mirrors as they are and stop where one is
     * missing ("No rule to make target"). A fresh clone has none - git does not keep them -
     * so an import whose mirror is not there is refreshed with the target that fetches it.
     *
     * @param which             {@link #ALL}, or the id of one import
     * @param fetch             whether the mirrors are fetched again first
     * @param ontologyDirectory the project's {@code src/ontology}
     */
    static Asked asked(List<ImportProducts.Product> declared, String which, boolean fetch,
            File ontologyDirectory) {
        List<String> targets = new ArrayList<String>();
        List<String> notes = new ArrayList<String>();
        boolean all = which == null || which.equals(ALL);
        if (fetch) {
            targets.add(all ? "refresh-imports" : "refresh-" + which);
            return new Asked(targets, notes);
        }
        List<String> ids = new ArrayList<String>();
        List<String> withoutMirror = new ArrayList<String>();
        for (ImportProducts.Product product : declared) {
            if (all || product.getId().equals(which)) {
                ids.add(product.getId());
                if (needsAMirror(product) && !new File(ontologyDirectory, "mirror/"
                        + product.getId() + ".owl").isFile()) {
                    withoutMirror.add(product.getId());
                }
            }
        }
        if (!all && ids.isEmpty()) {
            // Not among what the configuration declares: ODK's Makefile is asked as it is.
            ids.add(which);
        }
        if (all && withoutMirror.isEmpty()) {
            targets.add("no-mirror-refresh-imports");
            return new Asked(targets, notes);
        }
        for (String id : ids) {
            if (withoutMirror.contains(id)) {
                targets.add("refresh-" + id);
                notes.add(id + " has no copy in src/ontology/mirror yet, so it is fetched "
                        + "(refresh-" + id + "): ODK builds a module from its mirror.");
            } else {
                targets.add("no-mirror-refresh-" + id);
            }
        }
        return new Asked(targets, notes);
    }

    /** Whether ODK builds this import's module from a mirror of its source. */
    private static boolean needsAMirror(ImportProducts.Product product) {
        return product.isMirrored() && !"custom".equals(product.getModuleType());
    }

    // ------------------------------------------------------------------ the run

    /** What the form needs of whoever has the project open. */
    interface Session {

        /**
         * Reads these module files again from disk: the run changed them.
         *
         * @return why that did not work, or null when it did
         */
        String readAgain(List<File> modules);
    }

    /** What a refresh did. */
    static final class Ran {
        private final String failure;
        private final List<String> transcript;
        private final List<File> changed;
        private final String notReadAgain;

        private Ran(String failure, List<String> transcript, List<File> changed,
                String notReadAgain) {
            this.failure = failure;
            this.transcript = Collections.unmodifiableList(transcript);
            this.changed = Collections.unmodifiableList(changed);
            this.notReadAgain = notReadAgain;
        }

        boolean isOk() {
            return failure == null;
        }

        /** Why the imports were not refreshed, in a sentence; null when they were. */
        String failure() {
            return failure;
        }

        /** Each command as it was run, and what it said. */
        List<String> transcript() {
            return transcript;
        }

        /** The module files that are no longer what they were, or are new. */
        List<File> changed() {
            return changed;
        }

        /** Why what changed could not be read again; null when it was, or nothing changed. */
        String notReadAgain() {
            return notReadAgain;
        }
    }

    /**
     * Runs the targets, one call of make each, and hands what changed to whoever has the
     * project open. The first target that fails ends the run.
     *
     * @param variables variables for make's command line, {@code NAME=value} each; none where
     *                  a person runs it
     * @param odk       who runs make in the project's {@code src/ontology}
     */
    static Ran refresh(File editFile, Asked asked, List<String> variables, OdkRelease.Odk odk,
            Session session) {
        File imports = new File(editFile.getAbsoluteFile().getParentFile(), "imports");
        Map<File, String> before = modulesIn(imports);
        List<String> transcript = new ArrayList<String>();
        String failure = null;
        for (String target : asked.targets()) {
            List<String> command = new ArrayList<String>(Arrays.asList("make", target));
            command.addAll(variables);
            transcript.add("$ " + odk.shown(command, false));
            ProcessRunner.Outcome outcome;
            try {
                outcome = odk.run(command, false);
            } catch (IOException | RuntimeException notStarted) {
                failure = "'" + target + "' could not be started: " + (notStarted.getMessage()
                        == null ? notStarted.toString() : notStarted.getMessage());
                break;
            }
            transcript.addAll(outcome.getOutput());
            failure = whyItFailed(target, outcome);
            if (failure != null) {
                break;
            }
        }
        // Also after a failure: what the targets before it wrote is on disk.
        List<File> changed = new ArrayList<File>();
        for (Map.Entry<File, String> module : modulesIn(imports).entrySet()) {
            if (!module.getValue().equals(before.get(module.getKey()))) {
                changed.add(module.getKey());
            }
        }
        String notReadAgain = changed.isEmpty() ? null : session.readAgain(changed);
        return new Ran(failure, transcript, changed, notReadAgain);
    }

    /** Why a target failed, in terms a person can act on; null when it did not. */
    private static String whyItFailed(String target, ProcessRunner.Outcome outcome) {
        if (outcome.isSuccess()) {
            return null;
        }
        if (!outcome.wasCancelled() && !outcome.timedOut()) {
            for (String line : outcome.getOutput()) {
                if (line.contains("No rule to make target")) {
                    return "ODK's Makefile has no rule for something '" + target + "' needs ("
                            + line.trim() + "). An import that was added to the configuration "
                            + "after the Makefile was written is not known to it: "
                            + "OntoBoard > Project > Update project files... writes the "
                            + "Makefile again.";
                }
            }
        }
        return MakeRun.explain(target, outcome);
    }

    /** The module files of the imports folder, each with what it holds, as a hash. */
    private static Map<File, String> modulesIn(File imports) {
        Map<File, String> modules = new TreeMap<File, String>();
        File[] files = imports.listFiles();
        for (File file : files == null ? new File[0] : files) {
            if (file.isFile() && file.getName().endsWith("_import.owl")) {
                modules.put(file, hashOf(file));
            }
        }
        return modules;
    }

    private static String hashOf(File file) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(file.toPath()));
            StringBuilder hex = new StringBuilder();
            for (byte one : digest) {
                hex.append(String.format("%02x", one));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException unreadable) {
            // Read as changed by whoever compares: a file that cannot be read is not the same.
            return "unreadable: " + unreadable.getMessage() + System.nanoTime();
        }
    }
}
