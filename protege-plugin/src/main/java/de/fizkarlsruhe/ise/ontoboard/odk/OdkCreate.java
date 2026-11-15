package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.menu.BackgroundRun;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkInstaller;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * New ODK project: ODK's seed, then OntoBoard's extras, then a first commit.
 *
 * <ol>
 *   <li>The wizard's answers become an ODK configuration ({@link OdkConfigWriter}).
 *   <li>ODK seeds the project from it: in its container when Docker or Podman runs and the
 *       pinned image is on the machine ({@link OdkSeedInContainer}), otherwise from its
 *       templates inside OntoBoard ({@link OdkInstaller}). The two write the same bytes, which
 *       is tested against each other, so a container that fails for the machine's own reasons
 *       - a drive Docker may not mount - does not cost anybody their project.
 *   <li>OntoBoard's extras are added ({@link OdkExtras}); never over a file of the seed.
 *   <li>With git installed the project becomes a repository on the branch {@code main} with one
 *       commit, "initial commit", of everything written - what {@code odk.py seed} does. Not the
 *       first build ODK's seed goes on to make: that needs the network, and is the person's to
 *       start. Without git the project is made all the same, and the result says what was not
 *       recorded.
 * </ol>
 *
 * <p>Nothing is written before the answers have passed every rule, and a project that cannot be
 * finished is taken away again, so the form can simply be tried once more.
 */
public final class OdkCreate {

    /** What seeded the project. */
    public enum Route {
        /** ODK itself, in its container. */
        CONTAINER,
        /** ODK's templates, bundled in OntoBoard. */
        BUNDLED
    }

    /** What Create did. */
    public static final class Created {
        private final List<File> written;
        private final Route route;
        private final boolean committed;
        private final String notRecorded;
        private final List<String> notes;

        Created(List<File> written, Route route, boolean committed, String notRecorded,
                List<String> notes) {
            this.written = Collections.unmodifiableList(written);
            this.route = route;
            this.committed = committed;
            this.notRecorded = notRecorded;
            this.notes = Collections.unmodifiableList(notes);
        }

        /** Every file of the new project, the seed's first. */
        public List<File> written() {
            return written;
        }

        public Route route() {
            return route;
        }

        /** Whether the files are the first commit of a new repository. */
        public boolean committed() {
            return committed;
        }

        /** Why there is no commit although one was wanted, and what to do; else null. */
        public String notRecorded() {
            return notRecorded;
        }

        /** What else the person should read: each a sentence or two. Usually empty. */
        public List<String> notes() {
            return notes;
        }

        /** In a sentence or two: what kind of project this is, and whether git has it. */
        public String summary() {
            StringBuilder said = new StringBuilder(route == Route.CONTAINER
                    ? "ODK " + OdkPin.VERSION + " wrote it, in its container."
                    : "It is the project ODK " + OdkPin.VERSION + " writes, made from ODK's "
                            + "templates inside OntoBoard.");
            if (committed) {
                said.append(" It is a git repository, and these files are its first commit.");
            } else if (notRecorded != null) {
                said.append(' ').append(notRecorded);
            }
            for (String note : notes) {
                said.append(' ').append(note);
            }
            return said.toString();
        }
    }

    private static final String NO_GIT = "It is not recorded in git: ";

    private OdkCreate() {
    }

    /** Creates the project and records it in git. */
    public static Created create(OdkProjectConfig config, File adoptedFileOrNull,
            ProcessRunner.Runner runner) {
        return create(config, adoptedFileOrNull, runner, true);
    }

    /**
     * Creates the project.
     *
     * @param adoptedFileOrNull an ontology to start from: it becomes the edit file byte for
     *                          byte, and is itself left as it is
     * @param runner            how commands are run: the container runtime's, and git's
     * @param recordInGit       whether to make the project a git repository with a first commit
     * @throws IllegalArgumentException when an answer breaks a rule - in words for the form;
     *                                  nothing has been written
     * @throws RuntimeException         when the project cannot be written; nothing is left of it
     */
    public static Created create(OdkProjectConfig config, File adoptedFileOrNull,
            ProcessRunner.Runner runner, boolean recordInGit) {
        return create(config, adoptedFileOrNull, runner, recordInGit, new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                // The button of the dialog that waits for this, when one does.
                return BackgroundRun.abandoned();
            }
        });
    }

    /**
     * The same, asking {@code stopped} between its steps whether to go on.
     *
     * @throws CancellationException when it was stopped; nothing is left of the project then,
     *                               the repository git made for it included
     */
    static Created create(OdkProjectConfig config, File adoptedFileOrNull,
            ProcessRunner.Runner runner, boolean recordInGit, BooleanSupplier stopped) {
        config.validate();
        String configText = OdkConfigWriter.forNewProject(config);
        byte[] source = null;
        if (adoptedFileOrNull != null) {
            try {
                source = Files.readAllBytes(adoptedFileOrNull.toPath());
            } catch (IOException unreadable) {
                throw new UncheckedIOException(adoptedFileOrNull + " could not be read: "
                        + unreadable.getMessage(), unreadable);
            }
        }
        File root = config.getProjectRoot().getAbsoluteFile();
        List<String> notes = new ArrayList<String>();
        List<File> written = new ArrayList<File>();
        Route route = Route.BUNDLED;
        try {
            stopIfAsked(stopped);
            List<File> seeded = seedInContainer(configText, root, source, runner, notes, stopped);
            if (seeded != null) {
                route = Route.CONTAINER;
            } else {
                seeded = OdkInstaller.seed(configText, root, source);
            }
            written.addAll(seeded);
            stopIfAsked(stopped);
            addExtras(root, OdkExtras.files(config), written);
            stopIfAsked(stopped);
        } catch (IOException failed) {
            takeBack(root, written);
            throw new UncheckedIOException("Could not write the project into " + root + ": "
                    + failed.getMessage(), failed);
        } catch (RuntimeException failed) {
            // A seed that fails has taken back what it wrote itself.
            takeBack(root, written);
            throw failed;
        }

        if (config.isAdopted() && !config.getBaseIri().equals(OdkConfigWriter.odkIri(config))) {
            notes.add("Your ontology keeps its address, " + config.getBaseIri() + ". ODK names "
                    + "what it releases " + OdkConfigWriter.odkIri(config) + " unless "
                    + config.getOntologyId() + ".Makefile in src/ontology says otherwise.");
        }

        if (config.isAdopted()) {
            String elsewhere = termsElsewhere(config, config.getEditFile());
            if (elsewhere != null) {
                notes.add(elsewhere);
            }
        }

        String notRecorded = null;
        boolean committed = false;
        if (recordInGit) {
            notRecorded = commit(root, runner, notes);
            committed = notRecorded == null;
        }
        if (stopped.getAsBoolean()) {
            if (recordInGit) {
                // The repository git has just made in a folder this made: all of it is ours.
                deleteTree(new File(root, ".git"));
            }
            takeBack(root, written);
            throw new CancellationException(STOPPED);
        }
        return new Created(written, route, committed, notRecorded, notes);
    }

    /**
     * For a project that starts from an existing ontology: what to say when none of the
     * ontology's terms begins with an address the configuration names as the project's own
     * ({@link OdkConfigWriter#namespaces}) - ODK's base release, its report and its lists of
     * terms would then hold none of them.
     *
     * @return the sentence; null when there is nothing to say - no terms, one of them at
     *         home, or a file that cannot be read as an ontology (it is the person's, and is
     *         kept as it is either way)
     */
    static String termsElsewhere(OdkProjectConfig config, File editFile) {
        List<String> own = OdkConfigWriter.namespaces(config);
        if (own.isEmpty()) {
            return null;
        }
        OWLOntology ontology;
        try {
            ontology = ImportGuard.alone(editFile);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        int terms = 0;
        Map<String, Integer> begins = new TreeMap<String, Integer>();
        for (OWLEntity entity : ontology.getSignature()) {
            String iri = entity.getIRI().toString();
            for (String namespace : own) {
                if (iri.startsWith(namespace)) {
                    return null;
                }
            }
            if (entity.isBuiltIn() || entity.isOWLAnnotationProperty()
                    || entity.isOWLDatatype()) {
                // Not counted: an ontology's annotation properties are others' as a rule.
                continue;
            }
            terms++;
            int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
            String begin = cut < 0 ? iri : iri.substring(0, cut + 1);
            Integer so = begins.get(begin);
            begins.put(begin, so == null ? 1 : so + 1);
        }
        if (terms == 0) {
            return null;
        }
        String most = null;
        for (Map.Entry<String, Integer> begin : begins.entrySet()) {
            if (most == null || begin.getValue() > begins.get(most)) {
                most = begin.getKey();
            }
        }
        return (terms == 1 ? "The ontology's one term does not have"
                : "None of the ontology's " + terms + " terms has")
                + " an address that begins with " + String.join(" or ", own) + ", which is "
                + "what the configuration names as the project's own (namespaces: in "
                + config.getOntologyId() + "-odk.yaml); " + (terms == 1 ? "it begins"
                        : "most begin") + " with " + most + ". ODK's base release, its "
                + "quality report and its lists of terms go by those addresses, and so "
                + "hold nothing of the ontology's own. Put the address the terms begin "
                + "with under namespaces: there, then run "
                + "OntoBoard > Project > Update project files...";
    }

    private static final String STOPPED = "Stopped. Nothing is left of the project.";

    private static void stopIfAsked(BooleanSupplier stopped) {
        if (stopped.getAsBoolean()) {
            throw new CancellationException(STOPPED);
        }
    }

    /**
     * Writes OntoBoard's extras into a seeded project, each noted in {@code written}.
     *
     * <p>An extra never replaces what ODK wrote. The seed of the configuration Create writes
     * holds none of these files; one that did - a configuration with a {@code documentation:}
     * block makes ODK write {@code mkdocs.yaml} - keeps ODK's.
     */
    static void addExtras(File root, Map<String, String> extras, List<File> written)
            throws IOException {
        for (Map.Entry<String, String> extra : extras.entrySet()) {
            File target = new File(root, extra.getKey().replace('/', File.separatorChar));
            if (target.exists()) {
                continue;
            }
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), extra.getValue().getBytes(StandardCharsets.UTF_8));
            written.add(target);
        }
    }

    /**
     * ODK's own seed, when its container can run here; null when it cannot, with the reason
     * noted only if a container was there to try.
     */
    private static List<File> seedInContainer(String configText, File root, byte[] source,
            ProcessRunner.Runner runner, List<String> notes, BooleanSupplier stopped) {
        String runtime = MakeRun.containerRuntime(runner);
        if (runtime == null) {
            return null;
        }
        String image = OdkImage.pinned(OdkImage.probe(runtime, runner));
        if (image == null) {
            // Not downloaded unasked; the templates in OntoBoard write the same project.
            return null;
        }
        try {
            return OdkSeedInContainer.run(runtime, image,
                    OdkContainer.Host.detect(runtime, runner), configText, root, source, runner);
        } catch (RuntimeException couldNot) {
            // A seed that was killed because somebody stopped waiting is not one that failed:
            // the templates must not make, after all, the project that was just called off.
            stopIfAsked(stopped);
            notes.add("ODK's container was tried first and could not write the project ("
                    + couldNot.getMessage() + ").");
            return null;
        }
    }

    /**
     * Makes the project a repository with one commit of everything in it.
     *
     * @return null when the commit was made; otherwise why not, and what to do
     */
    private static String commit(File root, ProcessRunner.Runner runner, List<String> notes) {
        if (!ProcessRunner.isAvailable(runner, "git", "--version")) {
            return NO_GIT + "git is not installed. With git, run git init, git add -A and git "
                    + "commit in " + root + ".";
        }
        // init and symbolic-ref rather than init -b, which a git older than 2.28 does not know.
        String failed = git(root, runner, "init", "-q");
        if (failed == null) {
            failed = git(root, runner, "symbolic-ref", "HEAD", "refs/heads/main");
        }
        if (failed == null) {
            failed = git(root, runner, "add", "-A");
        }
        if (failed == null) {
            boolean known = git(root, runner, "config", "--get", "user.name") == null
                    && git(root, runner, "config", "--get", "user.email") == null;
            if (known) {
                failed = git(root, runner, "commit", "-q", "-m", "initial commit");
            } else {
                // A git that knows nobody refuses to commit. As Convert to ODK does, the commit
                // is then made in OntoBoard's name rather than left out.
                failed = git(root, runner, "-c", "user.name=OntoBoard", "-c",
                        "user.email=ontoboard@localhost", "commit", "-q", "-m",
                        "initial commit");
                if (failed == null) {
                    notes.add("git has no name and e-mail for you, so the first commit is in "
                            + "OntoBoard's name. git config --global user.name and user.email "
                            + "set yours for the commits to come.");
                }
            }
        }
        return failed == null ? null
                : NO_GIT + failed + " The project itself is complete; commit it yourself in "
                        + root + ".";
    }

    /** Runs one git command in the project; null when it succeeded, else what went wrong. */
    private static String git(File root, ProcessRunner.Runner runner, String... arguments) {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));
        String shown = "git " + String.join(" ", arguments);
        try {
            ProcessRunner.Outcome outcome = runner.run(root, command, 2, null);
            if (outcome.isSuccess()) {
                return null;
            }
            return shown + (outcome.timedOut() ? " did not finish."
                    : " failed: " + outcome.lastMeaningfulLine() + ".");
        } catch (IOException cannotRun) {
            return shown + " could not be run: " + cannotRun.getMessage() + ".";
        } catch (RuntimeException cannotRun) {
            return shown + " could not be run: " + cannotRun.getMessage() + ".";
        }
    }

    /**
     * Takes away a project that could not be finished: the files Create wrote, and then the
     * folders that are left holding nothing. Nothing else is touched - a file somebody put
     * there in the meantime stays, and so does its folder.
     */
    private static void takeBack(File root, List<File> written) {
        for (int i = written.size() - 1; i >= 0; i--) {
            written.get(i).delete();
        }
        removeIfEmpty(root);
    }

    /**
     * Removes a folder with everything in it; a link is removed as the link it is. Git marks
     * what it stores read-only, which Windows will not delete until the mark is taken off.
     */
    private static void deleteTree(File file) {
        try {
            BasicFileAttributes is = Files.readAttributes(file.toPath(),
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (is.isDirectory() && !is.isOther() && !is.isSymbolicLink()) {
                File[] inside = file.listFiles();
                if (inside != null) {
                    for (File child : inside) {
                        deleteTree(child);
                    }
                }
            }
            file.setWritable(true);
            Files.deleteIfExists(file.toPath());
        } catch (IOException leftBehind) {
            // What is left keeps its folder: takeBack removes only folders that are empty.
        }
    }

    /** Removes a folder when, its empty folders removed, it holds nothing; says whether it did. */
    private static boolean removeIfEmpty(File folder) {
        try {
            BasicFileAttributes is = Files.readAttributes(folder.toPath(),
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // A link or a Windows junction is not gone into: what it leads to is not ours.
            if (!is.isDirectory() || is.isOther() || is.isSymbolicLink()) {
                return false;
            }
        } catch (IOException gone) {
            return false;
        }
        File[] inside = folder.listFiles();
        if (inside == null) {
            return false;
        }
        boolean empty = true;
        for (File child : inside) {
            empty &= removeIfEmpty(child);
        }
        return empty && folder.delete();
    }
}
