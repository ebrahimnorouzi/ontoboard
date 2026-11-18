package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.git.GitRepo;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * A release of an ODK project, from the version chosen to the tag.
 *
 * <p>ODK builds it: {@code make prepare_release} in ODK's container, or the project's own
 * {@code release.sh} there when it has one. Around that, this does what a maintainer does by
 * hand: refuses a version that has been released; asks the Makefile which files the release is
 * made of; holds the new release against the previous one and stops when a published term is
 * gone; keeps a copy under {@code releases/<version>/} for a project OntoBoard made; and
 * commits and tags, after the git commands have been shown and agreed to. Nothing is pushed.
 *
 * <p>No Swing and no Protégé here. ODK is reached through {@link Odk} and git through a
 * {@link ProcessRunner.Runner}, so a test can stand in for both.
 */
public final class OdkRelease {

    /** The label of the choice that stops a release which drops a published term. */
    public static final String REFUSE_LABEL = "Refuse to release when a published term is gone";

    /** The box that lets a release that cannot be compared with the last one go on (F6). */
    public static final String UNCOMPARED_LABEL =
            "Release even when it cannot be compared with the last release";

    /** A full release prints tens of thousands of lines; the transcript keeps this many. */
    static final int MAX_LINES = 4000;

    /** How many earlier releases are asked for their file before giving up on comparing. */
    private static final int RELEASES_TRIED = 5;

    /** How much of a released file is read for its header. */
    private static final int HEAD_BYTES = 256 * 1024;

    private OdkRelease() {
    }

    // ------------------------------------------------------------------ the project

    /** What an ODK project says of itself, read from its Makefile. */
    public static final class Facts {
        private static final Pattern ASSIGNMENT = Pattern.compile(
                "(?m)^([A-Za-z_][A-Za-z0-9_]*)[ \\t]*([:?]?)=[ \\t]*(.*?)[ \\t]*\\r?$");
        private static final Pattern REFERENCE =
                Pattern.compile("\\$[({]([A-Za-z_][A-Za-z0-9_]*)[)}]");

        private final File root;
        private final File ontology;
        private final String id;
        private final String ontbase;

        private Facts(File root, File ontology, String id, String ontbase) {
            this.root = root;
            this.ontology = ontology;
            this.id = id;
            this.ontbase = ontbase;
        }

        /**
         * @param editFile the project's edit file, {@code <project>/src/ontology/<id>-edit.owl}
         * @throws IllegalStateException when that is not in an ODK project, with the reason
         */
        public static Facts of(File editFile) {
            File ontology = editFile == null ? null : editFile.getAbsoluteFile().getParentFile();
            File src = ontology == null ? null : ontology.getParentFile();
            File root = src == null ? null : src.getParentFile();
            if (root == null || !"ontology".equalsIgnoreCase(ontology.getName())
                    || !"src".equalsIgnoreCase(src.getName())) {
                throw new IllegalStateException("This is not an ODK project: an ODK project "
                        + "keeps its edit file in <project>/src/ontology, and this one is in "
                        + (ontology == null ? "no folder" : ontology.getAbsolutePath()) + ".");
            }
            File makefile = new File(ontology, "Makefile");
            if (!makefile.isFile()) {
                throw new IllegalStateException("This is not an ODK project: there is no "
                        + "Makefile in " + ontology.getAbsolutePath() + ".");
            }
            Map<String, String> variables = new LinkedHashMap<String, String>();
            try {
                Matcher line = ASSIGNMENT.matcher(new String(
                        Files.readAllBytes(makefile.toPath()), StandardCharsets.UTF_8));
                while (line.find()) {
                    if (!variables.containsKey(line.group(1))) {
                        variables.put(line.group(1), line.group(3));
                    }
                }
            } catch (IOException unreadable) {
                throw new IllegalStateException(makefile.getAbsolutePath() + " could not be "
                        + "read: " + unreadable.getMessage(), unreadable);
            }
            String id = expanded("ONT", variables, 0);
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
                throw new IllegalStateException("The Makefile in " + ontology.getAbsolutePath()
                        + " does not name its ontology (a line ONT= ...), so it is not one ODK "
                        + "wrote.");
            }
            // make keeps a variable's last value, and ODK's Makefile reads the project's own
            // last: what that file sets is what make uses. A base read from ODK's Makefile
            // alone put a version IRI of another shape than ODK's under the wrong address
            // (review of 1.153.0). "?=" gives a value only to what has none.
            File own = new File(ontology, id + ".Makefile");
            if (own.isFile()) {
                try {
                    Matcher line = ASSIGNMENT.matcher(new String(
                            Files.readAllBytes(own.toPath()), StandardCharsets.UTF_8));
                    while (line.find()) {
                        if (!"?".equals(line.group(2))
                                || !variables.containsKey(line.group(1))) {
                            variables.put(line.group(1), line.group(3));
                        }
                    }
                } catch (IOException unreadable) {
                    // What ODK's Makefile says stands.
                }
            }
            String ontbase = expanded("ONTBASE", variables, 0);
            return new Facts(root, ontology, id,
                    ontbase != null && ontbase.matches("https?://\\S+") ? ontbase : null);
        }

        /** A variable's value with the variables it names filled in, or null when unknown. */
        private static String expanded(String name, Map<String, String> variables, int depth) {
            String value = variables.get(name);
            if (value == null || depth > 8) {
                return null;
            }
            Matcher reference = REFERENCE.matcher(value);
            StringBuffer filled = new StringBuffer();
            while (reference.find()) {
                String inner = expanded(reference.group(1), variables, depth + 1);
                if (inner == null) {
                    return null;
                }
                reference.appendReplacement(filled, Matcher.quoteReplacement(inner));
            }
            reference.appendTail(filled);
            return filled.toString();
        }

        /** The repository's top: where ODK puts the release files. */
        public File root() {
            return root;
        }

        /** {@code src/ontology}: where the Makefile is, and where make runs. */
        public File ontology() {
            return ontology;
        }

        /** {@code ONT}: the ontology's id, which its files are named after. */
        public String id() {
            return id;
        }

        /** {@code ONTBASE}: what its version IRIs start with; null when not said plainly. */
        public String ontbase() {
            return ontbase;
        }
    }

    // ------------------------------------------------------------------ what is asked

    /** A release, as the person chose it. */
    public static final class Request {
        private final File editFile;
        private final String version;
        private final VersionIriShape shape;
        private final boolean refreshImports;
        private final boolean refuseRemovedTerms;
        private final boolean releaseUncompared;
        private final boolean commitNewFiles;
        private final List<String> newFiles;

        /**
         * A release that stops where it cannot be compared with the last release.
         *
         * @param shape              the version IRI's shape; null for ODK's. A project released
         *                           by a script of its own has the shape its script writes
         * @param refreshImports     false to build from the imports as they are, which needs
         *                           no network: {@code prepare_release_fast}
         * @param refuseRemovedTerms stop before anything is copied or committed when a term the
         *                           previous release published is gone
         */
        public Request(File editFile, String version, VersionIriShape shape,
                boolean refreshImports, boolean refuseRemovedTerms) {
            this(editFile, version, shape, refreshImports, refuseRemovedTerms, false);
        }

        /**
         * @param releaseUncompared go on, with a warning, where the last release cannot be read
         *                          to compare this one with ({@link #UNCOMPARED_LABEL}); false
         *                          stops there (F6)
         */
        public Request(File editFile, String version, VersionIriShape shape,
                boolean refreshImports, boolean refuseRemovedTerms, boolean releaseUncompared) {
            this(editFile, version, shape, refreshImports, refuseRemovedTerms,
                    releaseUncompared, false);
        }

        /**
         * @param commitNewFiles commit the project's new files first, in a commit of their
         *                       own, so that the release's tag holds them
         *                       ({@link ReleaseRun#newFilesOfTheProject}); false leaves them out
         *                       of the release's commit, as 1.153.0 did
         */
        public Request(File editFile, String version, VersionIriShape shape,
                boolean refreshImports, boolean refuseRemovedTerms, boolean releaseUncompared,
                boolean commitNewFiles) {
            this(editFile, version, shape, refreshImports, refuseRemovedTerms, releaseUncompared,
                    commitNewFiles, null);
        }

        /**
         * @param newFiles the new files the form's box named, from git's status before the
         *                 build: only those are committed first (the 1.154.0 review's M1); null
         *                 for the project's new files as git lists them after the build
         */
        public Request(File editFile, String version, VersionIriShape shape,
                boolean refreshImports, boolean refuseRemovedTerms, boolean releaseUncompared,
                boolean commitNewFiles, List<String> newFiles) {
            this.editFile = editFile;
            this.version = version == null ? "" : version.trim();
            this.shape = shape == null ? VersionIriShape.of(VersionIriShape.Kind.ODK) : shape;
            this.refreshImports = refreshImports;
            this.refuseRemovedTerms = refuseRemovedTerms;
            this.releaseUncompared = releaseUncompared;
            this.commitNewFiles = commitNewFiles;
            this.newFiles = newFiles == null ? null
                    : Collections.unmodifiableList(new ArrayList<String>(newFiles));
        }

        public File editFile() {
            return editFile;
        }

        public String version() {
            return version;
        }

        public VersionIriShape shape() {
            return shape;
        }

        public boolean refreshesImports() {
            return refreshImports;
        }

        public boolean refusesRemovedTerms() {
            return refuseRemovedTerms;
        }

        /** Whether it goes on where it cannot be compared with the last release. */
        public boolean releasesUncompared() {
            return releaseUncompared;
        }

        /** Whether the project's new files are committed first, in a commit of their own. */
        public boolean commitsNewFilesFirst() {
            return commitNewFiles;
        }

        /** The new files the form's box named; null where none were named. */
        public List<String> newFiles() {
            return newFiles;
        }
    }

    /** ODK: something that runs a command in the project's {@code src/ontology}. */
    public interface Odk {
        /**
         * @param inContainer      the command as ODK's container sees it: {@code make ...}
         * @param passThroughRunSh for the project's own script, whose {@code sh run.sh make
         *                         ...} lines have to run make where they stand
         */
        ProcessRunner.Outcome run(List<String> inContainer, boolean passThroughRunSh)
                throws IOException;

        /** The same command as a line for the transcript, ready to paste into a terminal. */
        String shown(List<String> inContainer, boolean passThroughRunSh);

        /**
         * Whether the project's Makefile is run as it is - by make, in ODK's container or with
         * ODK's tools - rather than by OntoBoard's engine, which runs only ODK's own (F5).
         */
        default boolean runsTheMakefileAsItIs() {
            return true;
        }
    }

    /** The person, asked before git records anything. */
    public interface Asks {
        /** Whether to run these commands. They are shown first; that is what this is for. */
        boolean commit(ReleaseRun.Staging staging);

        /**
         * Whether the person has stopped waiting for the release. Asked once ODK has built
         * it, before OntoBoard writes anything of its own: a release that went on to copy
         * its files to releases/ after it was stopped left a folder nobody was told of,
         * for which the version was refused as released the next time (review of 1.153.0).
         */
        default boolean stopped() {
            return false;
        }
    }

    /**
     * ODK's own tools installed on this machine - ODK Core's native environment, on Linux and
     * macOS: the same commands, run in a shell after the environment's activation script, as
     * Build runs a target there.
     *
     * <p>Each word is quoted for that shell. A version IRI's pattern reaches make with make's
     * {@code $@} in it, which a shell would otherwise take for its own.
     *
     * @param activationScript the environment's {@code activate-odk-environment.sh}
     */
    public static Odk inNativeEnvironment(final File activationScript,
            final File ontologyDirectory, final ProcessRunner.Runner runner,
            final ProcessRunner.Sink sink) {
        return new Odk() {
            @Override
            public ProcessRunner.Outcome run(List<String> command, boolean passThroughRunSh)
                    throws IOException {
                StringBuilder line = new StringBuilder();
                for (String word : command) {
                    line.append(line.length() == 0 ? "" : " ").append(Toolchain.quote(word));
                }
                return runner.run(ontologyDirectory, Toolchain.nativeCommandFor(activationScript,
                        line.toString()), MakeRun.TIMEOUT_MINUTES, sink);
            }

            @Override
            public String shown(List<String> command, boolean passThroughRunSh) {
                return ReleaseRun.shown(command) + "   (in the native ODK environment)";
            }
        };
    }

    /** ODK's container on this machine. */
    public static Odk inContainer(final String runtime, final String image,
            final OdkContainer.Host host, final File ontologyDirectory,
            final ProcessRunner.Runner runner, final ProcessRunner.Sink sink) {
        return new Odk() {
            @Override
            public ProcessRunner.Outcome run(List<String> inContainer, boolean passThroughRunSh)
                    throws IOException {
                return runner.run(ontologyDirectory, command(inContainer, passThroughRunSh),
                        MakeRun.TIMEOUT_MINUTES, sink);
            }

            @Override
            public String shown(List<String> inContainer, boolean passThroughRunSh) {
                return ReleaseRun.shown(command(inContainer, passThroughRunSh));
            }

            private List<String> command(List<String> inContainer, boolean passThroughRunSh) {
                List<String> command = MakeRun.containerCommandFor(runtime, ontologyDirectory,
                        image, host, inContainer);
                return passThroughRunSh ? OdkContainer.withPassThroughRunSh(command,
                        OdkContainer.passThroughStub()) : command;
            }
        };
    }

    /**
     * OntoBoard itself in ODK's place, for a machine where ODK's container does not run:
     * make's targets are run by OntoBoard's own engine ({@link OdkEngine}) - ODK's Makefile
     * read and run as make runs it, ROBOT inside this JVM, no container, no make, no shell.
     *
     * <p>What is no target of make is not run this way: a project's own release script is a
     * shell script, and what it does between its calls of make is the shell's to do.
     *
     * @param stopped asked before each line of a recipe: whether the person has stopped the
     *                run; may be null
     */
    public static Odk byOntoBoardItself(final File editFile,
            final java.util.function.BooleanSupplier stopped) {
        return new Odk() {
            @Override
            public ProcessRunner.Outcome run(List<String> command, boolean passThroughRunSh) {
                if (command.isEmpty() || !command.get(0).equals("make")) {
                    return refused("'" + String.join(" ", command) + "' is no target of make. "
                            + "OntoBoard itself runs ODK's Makefile; a script of the project's "
                            + "own is run by a shell, in ODK's container.");
                }
                String goal = null;
                Map<String, String> variables = new LinkedHashMap<String, String>();
                for (String word : command.subList(1, command.size())) {
                    int equals = word.indexOf('=');
                    if (word.equals("-s")) {
                        // make's "say no recipe line": asked for where only what a recipe
                        // prints is wanted. The lines make says are told apart by whoever
                        // reads the output.
                        continue;
                    } else if (word.equals("-B") || word.equals("--always-make")) {
                        // Every target made again: carried with the variables, as make
                        // carries both to a make below it.
                        variables.put(de.fizkarlsruhe.ise.ontoboard.odk.make.MakeFile
                                .ALWAYS_MAKE, "");
                    } else if (equals > 0
                            && word.substring(0, equals).matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
                        variables.put(word.substring(0, equals), word.substring(equals + 1));
                    } else if (goal == null && !word.startsWith("-")) {
                        goal = word;
                    } else {
                        return refused("'" + word + "' on make's command line is not read by "
                                + "OntoBoard's own engine: it takes one target and variables.");
                    }
                }
                if (goal == null) {
                    return refused("make without a target is not run by OntoBoard's own engine.");
                }
                OdkEngine.Outcome outcome = OdkEngine.run(editFile, goal, variables, stopped);
                List<String> said = new ArrayList<String>(outcome.getTranscript());
                if (!outcome.isOk()) {
                    said.add(outcome.getFailure());
                }
                // A run the person stopped is one that was ended, as a process is: who
                // reads the outcome then says so, and not that make failed.
                return new ProcessRunner.Outcome(outcome.isOk() ? 0 : 2, said, false,
                        outcome.wasStopped());
            }

            @Override
            public String shown(List<String> command, boolean passThroughRunSh) {
                return ReleaseRun.shown(command) + "   (run by OntoBoard itself, inside Protege)";
            }

            @Override
            public boolean runsTheMakefileAsItIs() {
                // The engine runs ODK's own Makefile only, and refuses another with its reason.
                return false;
            }

            private ProcessRunner.Outcome refused(String why) {
                return new ProcessRunner.Outcome(1, Collections.singletonList(why), false);
            }
        };
    }

    // ------------------------------------------------------------------ what came of it

    /** What a release did. */
    public static final class Outcome {
        private final String failure;
        private final List<String> transcript;
        private final List<File> written;
        private final List<String> warnings;
        private final List<String> later;
        private final boolean committed;
        private final String tag;
        private final String summary;

        private Outcome(String failure, Log log, boolean committed, String tag, String summary) {
            this.failure = failure;
            this.transcript = Collections.unmodifiableList(new ArrayList<String>(log.transcript));
            this.written = Collections.unmodifiableList(new ArrayList<File>(log.written));
            this.warnings = Collections.unmodifiableList(new ArrayList<String>(log.warnings));
            this.later = Collections.unmodifiableList(new ArrayList<String>(log.later));
            this.committed = committed;
            this.tag = tag;
            this.summary = summary;
        }

        public boolean isOk() {
            return failure == null;
        }

        /** Why it stopped, in a sentence that says what is on disk now; null when it did not. */
        public String getFailure() {
            return failure;
        }

        /** The commands run, each before its output. */
        public List<String> getTranscript() {
            return transcript;
        }

        /** The release files, their copies and the notes. */
        public List<File> getWritten() {
            return written;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        /** Whether the release is a commit and a tag now. */
        public boolean isCommitted() {
            return committed;
        }

        /** The tag made, or null. */
        public String getTag() {
            return tag;
        }

        /** What happened, and what is left for the person to do. */
        public String getSummary() {
            return summary;
        }

        /**
         * The git commands that were not run, each a line to copy, under a line that says
         * where to run them; empty when the release is recorded.
         */
        public List<String> getLater() {
            return later;
        }
    }

    /** What is said along the way. */
    private static final class Log {
        private final List<String> transcript = new ArrayList<String>();
        private final List<File> written = new ArrayList<File>();
        private final List<String> warnings = new ArrayList<String>();
        private final List<String> later = new ArrayList<String>();

        /**
         * The git commands that were not run, kept as lines to copy. The sentence that says
         * where they are goes into the summary: what a dialog shows as a sentence cannot be
         * copied, and a summary of ten lines leaves the log no room.
         */
        String leftForLater(String heading, List<List<String>> commands) {
            later.add(heading);
            for (List<String> command : commands) {
                later.add(ReleaseRun.shown(command));
            }
            later.add("git push --follow-tags");
            return " The git commands for that are at the end of the log.";
        }

        void note(String line) {
            transcript.add(line);
        }

        void warn(String warning) {
            warnings.add(warning);
        }

        void ran(String shown, ProcessRunner.Outcome outcome) {
            List<String> output = outcome.getOutput();
            transcript.addAll(output.subList(0, Math.min(MAX_LINES, output.size())));
            if (output.size() > MAX_LINES) {
                warnings.add("The transcript of '" + shown + "' is cut after " + MAX_LINES
                        + " of " + output.size() + " lines.");
            }
        }

        Outcome failed(String why) {
            return new Outcome(why, this, false, null, why);
        }
    }

    // ------------------------------------------------------------------ the release

    /**
     * Releases the project.
     *
     * @param git  runs git, in the project's root
     * @param asks may be null, which is a no
     */
    public static Outcome run(Request request, Odk odk, ProcessRunner.Runner git, Asks asks) {
        Log log = new Log();
        Facts facts;
        try {
            facts = Facts.of(request.editFile);
        } catch (IllegalStateException notOdk) {
            return log.failed(notOdk.getMessage());
        }
        String version = request.version;
        if (OntologyVersion.parseOrNull(version) == null) {
            return log.failed("'" + version + "' is not a version: a date such as 2026-11-11, "
                    + "or numbers such as 1.0.0.");
        }
        List<String> tags = GitTags.releases(facts.root(), git);
        String released = ReleaseRun.whyNotThisVersion(facts.root(), version, tags);
        if (released != null) {
            return log.failed(released);
        }

        // What builds it: the project's own script when it has one, else ODK's target.
        List<String> command;
        boolean passThrough = false;
        ReleaseRun.Script script;
        try {
            script = ReleaseRun.scriptIn(facts.ontology());
            if (script != null) {
                String why = whyNotThisScript(script, version);
                if (why != null) {
                    return log.failed(why);
                }
                command = Arrays.asList("sh", script.file().getName());
                passThrough = new File(facts.ontology(), "run.sh").isFile();
            } else {
                if (facts.ontbase() == null
                        && request.shape.kind() != VersionIriShape.Kind.ODK) {
                    return log.failed("The Makefile of this project does not say plainly what "
                            + "its version IRIs start with (a line ONTBASE= ...), so a version "
                            + "IRI of another shape than ODK's cannot be written from here. "
                            + "Release with ODK's shape, " + VersionIriShape.of(
                                    VersionIriShape.Kind.ODK).pattern() + ".");
                }
                command = ReleaseRun.makeArguments(version, request.shape, facts.ontbase(),
                        request.refreshImports);
            }
        } catch (IllegalStateException cannotRead) {
            return log.failed(cannotRead.getMessage());
        } catch (IllegalArgumentException cannotSay) {
            return log.failed(cannotSay.getMessage());
        }

        ProcessRunner.Outcome built;
        try {
            if (script == null) {
                // What a test left behind is not taken for the release's own.
                ProcessRunner.Outcome cleaned = ran(ReleaseRun.cleanArguments(), false, odk,
                        log);
                if (!cleaned.isSuccess()) {
                    return log.failed(MakeRun.explain("clean", cleaned)
                            + " Nothing was built, copied, committed or tagged.");
                }
            }
            built = ran(command, passThrough, odk, log);
        } catch (IOException cannotStart) {
            return log.failed(cannotStart.getMessage() + " Nothing was built, copied, "
                    + "committed or tagged.");
        }
        if (!built.isSuccess()) {
            return log.failed((script == null
                    ? whyTheBuildStopped(command.get(1), built, request.editFile)
                    : whyTheScriptStopped(script, built))
                    + " Nothing was copied, committed or tagged.");
        }

        // Which files it is made of: the Makefile's to say.
        List<File> assets = Collections.emptyList();
        String why;
        try {
            ProcessRunner.Outcome listed = ran(Arrays.asList("make", "-s",
                    "show_release_assets"), false, odk, log);
            if (listed.isSuccess()) {
                assets = assetsIn(listed.getOutput(), facts, log);
            }
            why = listed.isSuccess() ? "it named no file that is there"
                    : MakeRun.explain("show_release_assets", listed);
        } catch (IOException cannotStart) {
            why = cannotStart.getMessage();
        }
        if (assets.isEmpty()) {
            return log.failed("The release was built, but 'make show_release_assets' did not "
                    + "say which files it is made of (" + why + "), so nothing was copied, "
                    + "committed or tagged. The files are at the top of the project: commit "
                    + "them yourself, or bring the project's Makefile up to date with "
                    + "OntoBoard > Project > Update project files... and release again.");
        }
        log.written.addAll(assets);
        if (asks != null && asks.stopped()) {
            return log.failed(STOPPED_ONCE_BUILT);
        }

        // Against the release before it. A term that is gone rather than obsoleted breaks every
        // ontology that imported it and tells nobody; this is the last moment to catch it
        // before the release is a commit somebody pulls.
        ReleaseDiff diff = null;
        OWLOntology after = null;
        File primary = new File(facts.root(), facts.id() + ".owl");
        Last last = assets.contains(primary) ? lastRelease(facts, tags, version) : null;
        if (last != null) {
            // F6 (ruling 16): the comparison is the last check before the release is a commit
            // somebody pulls - so it stops here, as for a term that is gone, unless the person
            // said to go on without it. Which file could not be read is said: the release just
            // built, or the last one (the 1.154.0 review's M2).
            String uncompared = null;
            try {
                after = load(primary);
            } catch (RuntimeException cannotRead) {
                uncompared = "Could not compare with the " + last.version + " release: the "
                        + "release just built, " + primary.getName() + ", could not be read - "
                        + firstLineOf(cannotRead);
                log.note("What reading " + primary.getName() + " said: "
                        + cannotRead.getMessage());
            }
            if (uncompared == null) {
                try {
                    diff = ReleaseDiff.between(last.load(), after);
                    log.note("Compared with the " + last.version + " release: "
                            + diff.summary());
                } catch (RuntimeException cannotCompare) {
                    uncompared = "Could not compare with the " + last.version + " release: "
                            + firstLineOf(cannotCompare);
                    log.note("What reading the " + last.version + " release said: "
                            + cannotCompare.getMessage());
                }
            }
            if (uncompared != null) {
                if (!request.releaseUncompared) {
                    return log.failed(uncompared + " ODK built the release files and put them "
                            + "at the top of the project; nothing was copied to releases/, "
                            + "committed or tagged. " + (after == null
                                    ? "Look at " + primary.getName() + ", which ODK's build "
                                            + "wrote, and release again"
                                    : "Put the " + last.version + " release right where it is "
                                            + "kept (releases/" + last.version + "/ or its tag) "
                                            + "and release again")
                            + " - or tick \"" + UNCOMPARED_LABEL + "\".");
                }
                log.warn(uncompared);
            }
        }
        if (diff != null && !diff.removals().isEmpty()) {
            for (ReleaseDiff.TermChange removed : diff.removals()) {
                log.warn("Removed: " + named(removed));
            }
            if (request.refuseRemovedTerms) {
                return log.failed(whyTermsAreMissed(diff.removals(), last.version));
            }
        }

        // Holding two ontologies against each other takes its time on large ones: asked
        // again, before the first file of OntoBoard's own is written.
        if (asks != null && asks.stopped()) {
            return log.failed(STOPPED_ONCE_BUILT);
        }

        // The copy that ODK's version IRIs point at, for a project that keeps one.
        boolean kept = ReleaseRun.keepsReleasesFolder(facts.root());
        if (kept) {
            try {
                log.written.addAll(ReleaseRun.copyToReleases(facts.root(), version, assets));
                if (diff != null) {
                    File notes = new File(facts.root(), "releases/" + version + "/CHANGES.md");
                    Files.write(notes.toPath(), diff.asReleaseNotes(version, after)
                            .getBytes(StandardCharsets.UTF_8));
                    log.written.add(notes);
                }
            } catch (IOException cannotCopy) {
                return log.failed("The release was built, but could not be copied to releases/"
                        + version + ": " + cannotCopy.getMessage() + ". Nothing was committed "
                        + "or tagged.");
            } catch (RuntimeException cannotCopy) {
                return log.failed("The release was built, but could not be copied to releases/"
                        + version + ": " + cannotCopy.getMessage() + " Nothing was committed "
                        + "or tagged.");
            }
        }

        String done = "Released " + facts.id() + " " + version + ": " + assets.size()
                + (assets.size() == 1 ? " file" : " files") + " at the top of the project"
                + (kept ? ", a copy under releases/" + version : "");
        return recorded(facts, version, assets, git, asks, log, done,
                request.commitsNewFilesFirst(), request.newFiles());
    }

    /** Said of a release the person stopped when ODK's part was done and OntoBoard's not begun. */
    static final String STOPPED_ONCE_BUILT = "You stopped the release after ODK had built "
            + "it. The release files are at the top of the project; nothing was copied to "
            + "releases/, committed or tagged, so this version can still be released.";

    /**
     * Why ODK's build of a release stopped, as Build says it of the same run: the check and
     * the terms that break it, what the reasoner found, what takes the ontology out of its
     * profile. make's own last line is a row of a check's answer, or the file a check
     * writes; on its own it sent people to the wrong window (review of 1.153.0).
     */
    private static String whyTheBuildStopped(String target, ProcessRunner.Outcome built,
            File editFile) {
        String runners = MakeRun.explain(target, built);
        return built.wasCancelled() || built.timedOut() ? runners
                : OdkTargets.whyARunFailed(target, built.getOutput(), runners, editFile, null);
    }

    /**
     * Runs one command through ODK, into the transcript.
     *
     * @throws IOException when it could not be started, saying which command and why
     */
    private static ProcessRunner.Outcome ran(List<String> command, boolean passThrough, Odk odk,
            Log log) throws IOException {
        String shown = odk.shown(command, passThrough);
        log.note("$ " + shown);
        try {
            ProcessRunner.Outcome outcome = odk.run(command, passThrough);
            log.ran(shown, outcome);
            return outcome;
        } catch (IOException cannotStart) {
            throw new IOException("Could not start '" + shown + "': "
                    + cannotStart.getMessage() + ".", cannotStart);
        } catch (RuntimeException cannotStart) {
            throw new IOException("Could not start '" + shown + "': "
                    + cannotStart.getMessage() + ".", cannotStart);
        }
    }

    // ------------------------------------------------------------------ the project's script

    /**
     * Why the project's release script cannot be run from here whatever the version, or null
     * when it can: its line endings, or no version to read in it.
     */
    public static String whyNotAScriptToRun(ReleaseRun.Script script) {
        String name = script.file().getName();
        if (script.hasWindowsLineEndings()) {
            return name + " has Windows line endings. The shell in ODK's container reads the "
                    + "carriage return at the end of every line as part of its last word - "
                    + "the version, the target - so the script would release nothing, or the "
                    + "wrong thing. Save it with Unix line endings (LF) and release again; "
                    + "git's core.autocrlf is the usual reason a checkout looks like this, and "
                    + "a line '*.sh text eol=lf' in .gitattributes keeps it from coming back.";
        }
        String scripted = script.version();
        if (scripted == null || scripted.trim().isEmpty() || scripted.contains("$")) {
            return "This project is released by its own " + name + ", and that script has no "
                    + "line VERSION=<version> to read, so OntoBoard cannot tell which version "
                    + "it would release - and would tag the wrong one. Run the script in a "
                    + "terminal, or give it a line such as VERSION=1.0.0 for OntoBoard to set.";
        }
        return null;
    }

    /** Why the project's release script is not run for this version, or null when it is. */
    static String whyNotThisScript(ReleaseRun.Script script, String version) {
        String name = script.file().getName();
        String never = whyNotAScriptToRun(script);
        if (never != null) {
            return never;
        }
        String scripted = script.version();
        if (!scripted.trim().equals(version)) {
            return "This project is released by its own " + name + ", which says VERSION="
                    + scripted.trim() + ", and this release is " + version + ". Set the line "
                    + "in the script to the version being released, so that what is built and "
                    + "what is tagged are the same release.";
        }
        return null;
    }

    private static String whyTheScriptStopped(ReleaseRun.Script script,
            ProcessRunner.Outcome outcome) {
        String name = script.file().getName();
        if (outcome.wasCancelled() || outcome.timedOut()) {
            return MakeRun.explain(name, outcome);
        }
        String tail = outcome.lastMeaningfulLine();
        return name + " failed (exit " + outcome.getExitCode() + ")"
                + (tail.isEmpty() ? "." : ": " + tail) + " What it had written by then is "
                + "still there.";
    }

    // ------------------------------------------------------------------ the release's files

    /**
     * The files {@code make show_release_assets} names, as files of the project.
     *
     * <p>The paths are from {@code src/ontology}, on one line. A runtime may print lines of its
     * own around it, so the line is the last one that names a file which is there.
     */
    private static List<File> assetsIn(List<String> output, Facts facts, Log log) {
        Path root = facts.root().toPath().toAbsolutePath().normalize();
        for (int i = output.size() - 1; i >= 0; i--) {
            List<File> there = new ArrayList<File>();
            List<String> missing = new ArrayList<String>();
            List<String> outside = new ArrayList<String>();
            for (String word : output.get(i).trim().split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                Path path;
                try {
                    path = facts.ontology().toPath().toAbsolutePath().resolve(word).normalize();
                } catch (RuntimeException notAPath) {
                    missing.add(word);
                    continue;
                }
                if (!path.startsWith(root) || path.equals(root)) {
                    outside.add(word);
                } else if (!path.toFile().isFile()) {
                    missing.add(word);
                } else if (!there.contains(path.toFile())) {
                    there.add(path.toFile());
                }
            }
            if (!there.isEmpty()) {
                if (!missing.isEmpty()) {
                    log.warn("The Makefile also names " + String.join(", ", missing) + " as "
                            + (missing.size() == 1 ? "a release file" : "release files")
                            + ", and the build left no such file, so "
                            + (missing.size() == 1 ? "it is" : "they are")
                            + " not part of what was recorded.");
                }
                if (!outside.isEmpty()) {
                    log.warn("The Makefile also names " + String.join(", ", outside) + " as "
                            + (outside.size() == 1 ? "a release file" : "release files")
                            + ". That is outside the project's folder, so it is not part of "
                            + "what was recorded: a commit holds what is in the repository.");
                }
                return there;
            }
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------------ the release before

    /** An earlier release, and its main file. */
    private static final class Last {
        private final String version;
        private final File file;
        private final byte[] bytes;

        Last(String version, File file, byte[] bytes) {
            this.version = version;
            this.file = file;
            this.bytes = bytes;
        }

        /** The start of the file as text: where an ontology's header is, whatever its size. */
        String head() throws IOException {
            byte[] start = new byte[HEAD_BYTES];
            int length = 0;
            if (bytes != null) {
                length = Math.min(bytes.length, HEAD_BYTES);
                System.arraycopy(bytes, 0, start, 0, length);
            } else {
                java.io.InputStream in = new java.io.FileInputStream(file);
                try {
                    int read;
                    while (length < HEAD_BYTES
                            && (read = in.read(start, length, HEAD_BYTES - length)) > 0) {
                        length += read;
                    }
                } finally {
                    in.close();
                }
            }
            return new String(start, 0, length, StandardCharsets.UTF_8);
        }

        OWLOntology load() {
            if (file != null) {
                return OdkRelease.load(file);
            }
            File copy = null;
            try {
                copy = File.createTempFile("ontoboard-release", ".owl");
                Files.write(copy.toPath(), bytes);
                return OdkRelease.load(copy);
            } catch (IOException cannotCopy) {
                throw new IllegalStateException("the " + version + " release could not be "
                        + "read out of git: " + cannotCopy.getMessage(), cannotCopy);
            } finally {
                if (copy != null) {
                    copy.delete();
                }
            }
        }
    }

    /**
     * A release, in a manager of its own: it has the IRI of the ontology that is open, and
     * Protégé's manager would hand that one back.
     */
    private static OWLOntology load(File file) {
        return OntologySource.loadLeavingOutMissingImports(
                OWLManager.createOWLOntologyManager(), IRI.create(file.toURI()));
    }

    /**
     * The release before {@code version} - or the newest of all, for null - that has the
     * project's main file: under {@code releases/<version>/}, or at its tag.
     *
     * <p>The same way of numbering only: a project that released 2026-01-01 and now releases
     * 3.0.0 has two histories, and comparing across them would report every term of the one as
     * dropped by the other.
     */
    private static Last lastRelease(Facts facts, List<String> tags, String version) {
        OntologyVersion releasing = version == null ? null : OntologyVersion.parseOrNull(version);
        List<OntologyVersion> earlier = new ArrayList<OntologyVersion>();
        for (OntologyVersion found : released(facts, tags)) {
            if (releasing != null && (found.getScheme() != releasing.getScheme()
                            || found.compareTo(releasing) >= 0)) {
                continue;
            }
            earlier.add(found);
        }
        String main = facts.id() + ".owl";
        int tried = 0;
        for (OntologyVersion candidate : earlier) {
            File folder = new File(facts.root(), "releases/" + candidate.getText() + "/" + main);
            if (folder.isFile()) {
                return new Last(candidate.getText(), folder, null);
            }
            String tag = GitTags.tagFor(candidate.getText());
            if (tags.contains(tag) && tried++ < RELEASES_TRIED) {
                byte[] bytes = GitTags.fileAt(facts.root(), tag, main);
                if (bytes != null) {
                    return new Last(candidate.getText(), null, bytes);
                }
            }
        }
        return null;
    }

    /**
     * The versions this project has released, newest first: the folders under
     * {@code releases/} and the release tags, each version once.
     *
     * @param tags the project's release tags ({@link GitTags#releases})
     */
    public static List<OntologyVersion> released(Facts facts, List<String> tags) {
        List<String> names = new ArrayList<String>();
        File[] folders = new File(facts.root(), "releases").listFiles();
        for (File folder : folders == null ? new File[0] : folders) {
            String[] inside = folder.list();
            if (inside != null && inside.length > 0) {
                names.add(folder.getName());
            }
        }
        for (String tag : tags) {
            names.add(GitTags.versionOf(tag));
        }
        List<OntologyVersion> versions = new ArrayList<OntologyVersion>();
        for (String name : names) {
            OntologyVersion found = name == null ? null : OntologyVersion.parseOrNull(name);
            if (found != null && !versions.contains(found)) {
                versions.add(found);
            }
        }
        return OntologyVersion.newestFirst(versions);
    }

    /** What went wrong, in its first line: a parser's message can run to pages. */
    private static String firstLineOf(RuntimeException failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage().trim();
        int end = message.indexOf('\n');
        String line = (end < 0 ? message : message.substring(0, end)).trim();
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }

    private static String named(ReleaseDiff.TermChange removed) {
        return ReleaseDiff.shortForm(removed.getIri())
                + (removed.getBefore().isEmpty() ? "" : " (" + removed.getBefore() + ")");
    }

    private static String whyTermsAreMissed(List<ReleaseDiff.TermChange> removals,
            String previous) {
        boolean one = removals.size() == 1;
        String it = one ? "it" : "them";
        // Short: the dialog that shows it keeps the build's log on screen below. Every term
        // that is gone is a line of that log.
        return (one ? "1 term that the " + previous + " release published is gone from this "
                + "one: " + named(removals.get(0)) + "."
                : removals.size() + " terms that the " + previous + " release published are "
                        + "gone from this one, " + named(removals.get(0)) + " among them.")
                + " ODK built the release files and put them at the top of the project; "
                + "nothing was copied to releases/, committed or tagged. "
                + "Bring " + it + " back and mark " + it + " owl:deprecated with a replacement, "
                + "so that what imported " + it + " still resolves; then release again. To "
                + "drop " + it + " anyway, untick \"" + REFUSE_LABEL + "\".";
    }

    // ------------------------------------------------------------------ what the dialog proposes

    /** What a release dialog starts from: the last release and the shape of its version IRI. */
    public static final class Proposal {
        private final String lastVersion;
        private final String lastVersionIri;
        private final VersionIriShape shape;

        private Proposal(String lastVersion, String lastVersionIri, VersionIriShape shape) {
            this.lastVersion = lastVersion;
            this.lastVersionIri = lastVersionIri;
            this.shape = shape;
        }

        /** The newest release there is, or null before the first. */
        public String lastVersion() {
            return lastVersion;
        }

        /** Its version IRI, or null when it has none or could not be read. */
        public String lastVersionIri() {
            return lastVersionIri;
        }

        /** The shape that release has when it is one of the three named ones, else ODK's. */
        public VersionIriShape shape() {
            return shape;
        }
    }

    /**
     * Reads the project's last release for what a dialog proposes.
     *
     * <p>Only the start of the released file is read, where its header is: this is asked when
     * the dialog opens, and a release can be hundreds of megabytes.
     *
     * @param tags the project's release tags ({@link GitTags#releases})
     * @throws IllegalStateException when the file is not in an ODK project
     */
    public static Proposal proposal(File editFile, List<String> tags) {
        VersionIriShape odk = VersionIriShape.of(VersionIriShape.Kind.ODK);
        Facts facts = Facts.of(editFile);
        Last last = lastRelease(facts, tags, null);
        if (last == null) {
            return new Proposal(null, null, odk);
        }
        try {
            String versionIri = versionIriIn(last.head());
            VersionIriShape shape = VersionIriShape.detect(versionIri, facts.ontbase(),
                    last.version, facts.id() + ".owl");
            return new Proposal(last.version, versionIri, shape == null ? odk : shape);
        } catch (IOException unreadable) {
            return new Proposal(last.version, null, odk);
        } catch (RuntimeException unreadable) {
            return new Proposal(last.version, null, odk);
        }
    }

    /** {@link #proposal(File, List)}, asking git for the tags. */
    public static Proposal proposal(File editFile, ProcessRunner.Runner git) {
        return proposal(editFile, GitTags.releases(Facts.of(editFile).root(), git));
    }

    /** How an ontology's header says its version IRI, in the syntaxes a release comes in. */
    private static final Pattern[] VERSION_IRI = {
        // RDF/XML, which is what ODK writes an .owl in
        Pattern.compile("<owl:versionIRI\\s+rdf:resource\\s*=\\s*[\"']([^\"'\\s]+)[\"']"),
        // Turtle
        Pattern.compile("owl:versionIRI\\s+<([^>\\s]+)>"),
        // functional and Manchester syntax: the second IRI of the header
        Pattern.compile("Ontology\\s*[(:]\\s*<[^>\\s]+>\\s*<([^>\\s]+)>"),
    };

    /** The version IRI in the start of an ontology file, or null when it shows none. */
    static String versionIriIn(String head) {
        for (Pattern syntax : VERSION_IRI) {
            Matcher header = syntax.matcher(head);
            if (header.find()) {
                return header.group(1).replace("&amp;", "&");
            }
        }
        return null;
    }

    /**
     * Whether the file is the edit file of an ODK project: one built by ODK's Makefile. A
     * project of OntoBoard's older layout has a Makefile as well - OntoBoard wrote it and
     * builds it itself - and is not released this way until it has been converted.
     */
    public static boolean isOdkProject(File editFile) {
        try {
            return !MakeRun.writtenByOntoBoard(Facts.of(editFile).ontology());
        } catch (IllegalStateException notOne) {
            return false;
        }
    }

    /** The shape a release dialog starts with: the last release's, or ODK's. */
    public static VersionIriShape proposedShape(File editFile, ProcessRunner.Runner git) {
        return proposal(editFile, git).shape();
    }

    // ------------------------------------------------------------------ git

    /**
     * The commit and the tag: shown, agreed to, run. A release that is not recorded says why.
     *
     * @param commitNewFirst whether the project's new files are committed first, in a commit
     *                       of their own, so that the tag holds them
     */
    private static Outcome recorded(Facts facts, String version, List<File> assets,
            ProcessRunner.Runner git, Asks asks, Log log, String done, boolean commitNewFirst,
            List<String> newFiles) {
        String where = facts.root().getAbsolutePath();
        String tag = GitTags.tagFor(version);
        if (GitRepo.repositoryFor(new File(facts.ontology(), "Makefile")) == null) {
            log.warn(where + " is not a git repository, so the release was not committed and "
                    + "no tag " + tag + " was made. An ODK release is a commit with that tag; "
                    + "'git init' there makes the project a repository.");
            return new Outcome(null, log, false, null, done + ". Not committed: the project is "
                    + "not a git repository.");
        }
        if (!ProcessRunner.isAvailable(git, "git", "--version")) {
            ReleaseRun.Staging later = ReleaseRun.staging(facts.root(), facts.id(), version,
                    assets, Collections.<String>emptyList());
            log.warn("git is not installed, or not on the PATH Protege was started with, so "
                    + "the release was not committed and no tag " + tag + " was made.");
            return new Outcome(null, log, false, null, done + ". Not committed: git is not "
                    + "installed." + log.leftForLater(forLater(where), later.commands()));
        }
        List<String> status;
        try {
            ProcessRunner.Outcome asked = git.run(facts.root(), GitRepo.statusCommand(),
                    GitRepo.TIMEOUT_MINUTES, null);
            if (!asked.isSuccess()) {
                throw new IOException(GitRepo.explain("status", asked.getExitCode(),
                        asked.getOutput()));
            }
            status = asked.getOutput();
        } catch (IOException cannotAsk) {
            ReleaseRun.Staging later = ReleaseRun.staging(facts.root(), facts.id(), version,
                    assets, Collections.<String>emptyList());
            log.warn("git would not say what state the repository is in (" + cannotAsk.getMessage()
                    + "), so the release was not committed and no tag " + tag + " was made.");
            return new Outcome(null, log, false, null, done + ". Not committed: git status "
                    + "failed." + log.leftForLater(forLater(where), later.commands()));
        }
        ReleaseRun.Staging staging = ReleaseRun.staging(facts.root(),
                GitRepo.repositoryFor(new File(facts.ontology(), "Makefile")), facts.id(),
                version, assets, status, commitNewFirst, newFiles);
        List<String> conflicted = GitRepo.parseStatus(status).getConflicted();
        if (!conflicted.isEmpty()) {
            log.warn("A merge or rebase is unfinished in this repository - in conflict: "
                    + String.join(", ", conflicted) + " - so the release was not committed: a "
                    + "commit now would record the conflict markers. Finish that first.");
            return new Outcome(null, log, false, null, done + ". Not committed: a merge is "
                    + "unfinished." + log.leftForLater(forLater(where), staging.commands()));
        }
        if (asks == null || !asks.commit(staging)) {
            return new Outcome(null, log, false, null, done + ". Not committed."
                    + log.leftForLater(forLater(where), staging.commands()));
        }
        List<List<String>> commands = staging.commands();
        for (int i = 0; i < commands.size(); i++) {
            List<String> command = commands.get(i);
            log.note("$ " + ReleaseRun.shown(command));
            String why;
            try {
                ProcessRunner.Outcome outcome = git.run(facts.root(), command,
                        GitRepo.TIMEOUT_MINUTES, null);
                log.ran(ReleaseRun.shown(command), outcome);
                why = GitRepo.explain(command.get(1), outcome.getExitCode(), outcome.getOutput());
            } catch (IOException cannotRun) {
                why = "git " + command.get(1) + " could not be started: " + cannotRun.getMessage();
            }
            if (why != null) {
                boolean isCommitted = "tag".equals(command.get(1));
                String failure = (isCommitted ? "The release is committed, but the tag " + tag
                        + " was not made: " : "The release is written, but git did not record "
                        + "it: ") + why + " When that is sorted out, run what was not run."
                        + log.leftForLater("Not run yet, in " + where + ":",
                                commands.subList(i, commands.size()));
                return new Outcome(failure, log, isCommitted, null, failure);
            }
        }
        String branch = staging.branch();
        List<String> first = staging.committedFirst();
        return new Outcome(null, log, true, tag, done + ", committed and tagged " + tag
                + (branch != null ? " on " + branch
                        : staging.onNoBranch() ? " on no branch (a detached HEAD)" : "")
                + (first.isEmpty() ? "" : ", after the project's " + ReleaseRun.described(first)
                        + " in a commit of " + (first.size() == 1 ? "its" : "their")
                        + " own (\"" + ReleaseRun.newFilesMessage(version) + "\")")
                + ". Nothing was pushed - when you are ready, in a terminal: "
                + "git push --follow-tags");
    }

    /** The line above the commands of a release that was not recorded. */
    private static String forLater(String where) {
        return "To record the release later, in " + where + ":";
    }
}
