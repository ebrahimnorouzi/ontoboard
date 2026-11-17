package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.git.GitRepo;
import de.fizkarlsruhe.ise.ontoboard.odk.GitTags;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease;
import de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion;
import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseRoute;
import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseRun;
import de.fizkarlsruhe.ise.ontoboard.odk.TargetWords;
import de.fizkarlsruhe.ise.ontoboard.odk.VersionIriShape;
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
 * Release..., for an ODK project: what the dialog proposes, asks and refuses, what is shown
 * before git records anything, and what the result says.
 *
 * <p>No Swing here. {@link ReleaseAction} shows the form and the question; this decides what is
 * in them, so that it can be tested without a window.
 */
final class OdkReleaseForm {

    static final String VERSION = "version";
    static final String SHAPE = "shape";
    static final String PATTERN = "pattern";
    static final String REFRESH = "refresh";
    static final String REFUSE = "refuse";
    static final String UNCOMPARED = "uncompared";
    static final String PRIOR = "prior";
    static final String COMMIT_NEW = "commitnew";

    /** The choice that makes the pattern field count. */
    static final String OWN_PATTERN = "The pattern typed below";

    /** How many released versions the help of the version field names. */
    private static final int VERSIONS_NAMED = 5;

    /** How many new files the notice before a release names before it counts the rest. */
    private static final int NEW_FILES_NAMED = 5;

    /** How many files the commit leaves out are named before the rest is counted. */
    private static final int LEFT_OUT_NAMED = 10;

    private OdkReleaseForm() {
    }

    // ------------------------------------------------------------------ what the form starts from

    /** The project as it is when Release... is chosen. */
    static final class Start {
        private final File editFile;
        private final OdkRelease.Facts facts;
        private final List<String> tags;
        private final List<OntologyVersion> released;
        private final OdkRelease.Proposal proposal;
        private final ReleaseRun.Script script;
        private final OntologyVersion declared;
        private final String today;
        private final String refusal;
        /** The working tree as git sees it; null without a repository or without git. */
        private final GitRepo.Status status;
        /** Who builds the release; null for ODK in its container, the route of old. */
        private final ReleaseRoute route;

        private Start(File editFile, OdkRelease.Facts facts, List<String> tags,
                List<OntologyVersion> released, OdkRelease.Proposal proposal,
                ReleaseRun.Script script, OntologyVersion declared, String today,
                String refusal, GitRepo.Status status) {
            this(editFile, facts, tags, released, proposal, script, declared, today, refusal,
                    status, null);
        }

        private Start(File editFile, OdkRelease.Facts facts, List<String> tags,
                List<OntologyVersion> released, OdkRelease.Proposal proposal,
                ReleaseRun.Script script, OntologyVersion declared, String today,
                String refusal, GitRepo.Status status, ReleaseRoute route) {
            this.route = route;
            this.status = status;
            this.editFile = editFile;
            this.facts = facts;
            this.tags = tags;
            this.released = released;
            this.proposal = proposal;
            this.script = script;
            this.declared = declared;
            this.today = today;
            this.refusal = refusal;
        }

        OdkRelease.Facts facts() {
            return facts;
        }

        /** Why no form is shown at all - a release script that cannot be run - or null. */
        String refusal() {
            return refusal;
        }

        /** The project's own release script, or null when ODK's target releases it. */
        ReleaseRun.Script script() {
            return script;
        }

        /**
         * The same project, with who builds its release: ODK in a native environment, ODK in
         * its container, or OntoBoard itself. The form says which, and offers what that route
         * can do.
         */
        Start withRoute(ReleaseRoute route) {
            return new Start(editFile, facts, tags, released, proposal, script, declared, today,
                    refusal, status, route);
        }

        /** Whether the release may fetch the imported ontologies again first. */
        boolean mayRefreshImports() {
            return route == null || route.refreshesImports();
        }

        private boolean isBuiltBy(ReleaseRoute.Kind kind) {
            return route != null && route.kind() == kind;
        }
    }

    /**
     * Reads the project: its names, its releases, its last release's version IRI, its script.
     *
     * @param declared the version the open ontology declares, or null
     * @param today    {@code YYYY-MM-DD}
     * @throws IllegalStateException when the file is not in an ODK project
     */
    static Start start(File editFile, OntologyVersion declared, String today,
            ProcessRunner.Runner git) {
        OdkRelease.Facts facts = OdkRelease.Facts.of(editFile);
        List<String> tags = GitTags.releases(facts.root(), git);
        ReleaseRun.Script script = null;
        String refusal = null;
        try {
            script = ReleaseRun.scriptIn(facts.ontology());
            refusal = script == null ? null : OdkRelease.whyNotAScriptToRun(script);
        } catch (IllegalStateException unreadable) {
            refusal = unreadable.getMessage();
        }
        GitRepo.Status status = null;
        try {
            ProcessRunner.Outcome asked = git.run(facts.root(), GitRepo.statusCommand(), 1, null);
            status = asked.isSuccess() ? GitRepo.parseStatus(asked.getOutput()) : null;
        } catch (IOException noGit) {
            status = null;
        } catch (RuntimeException noGit) {
            status = null;
        }
        return new Start(editFile, facts, tags, OdkRelease.released(facts, tags),
                OdkRelease.proposal(editFile, tags), script, declared, today, refusal, status);
    }

    // ------------------------------------------------------------------ work that is not committed

    /**
     * What to say, before the release is built, of files git has never tracked; null when
     * there are none, or no repository to have them.
     */
    static String newFilesNotice(Start start) {
        // The project's own new files under src/ are offered in the form, to be committed
        // first: what is said here is what the release would leave out whatever is ticked.
        List<String> offered = newFilesOfTheProject(start);
        List<String> fresh = new ArrayList<String>();
        for (String path : ReleaseRun.untrackedOfTheProject(start.facts.root(),
                repositoryOf(start), start.status)) {
            if (!offered.contains(path)) {
                fresh.add(path);
            }
        }
        if (fresh.isEmpty()) {
            return null;
        }
        boolean one = fresh.size() == 1;
        String named = String.join(", ", fresh.subList(0, Math.min(NEW_FILES_NAMED,
                fresh.size())));
        if (fresh.size() > NEW_FILES_NAMED) {
            named += " and " + (fresh.size() - NEW_FILES_NAMED) + " more";
        }
        return "Git has never been told about " + fresh.size() + " new file" + (one ? "" : "s")
                + " in this project: " + named + ". The commit of a release takes changed "
                + "files with it and leaves " + (one ? "it" : "new files") + " out, so the "
                + "release's tag would not hold " + (one ? "it" : "them") + " - and a new "
                + "import module or test that is missing there breaks the project for whoever "
                + "fetches it. To have " + (one ? "it" : "them") + " in the release, commit "
                + "your work first: OntoBoard > Share > Git..., and there \"Commit everything, "
                + "with a message\".";
    }

    /** The project's new files under src/, which the form offers to commit first. */
    static List<String> newFilesOfTheProject(Start start) {
        return ReleaseRun.newFilesOfTheProject(start.facts.root(), repositoryOf(start),
                start.status);
    }

    /** The top of the repository the project is in; null where there is none. */
    private static File repositoryOf(Start start) {
        return de.fizkarlsruhe.ise.ontoboard.git.GitRepo.repositoryFor(
                new File(start.facts.ontology(), "Makefile"));
    }

    /** The box that commits the project's new files first; null where there are none. */
    private static Parameter commitNewFirst(Start start, Map<String, String> values) {
        List<String> fresh = newFilesOfTheProject(start);
        if (fresh.isEmpty()) {
            return null;
        }
        // One file is "it"; a folder git lists whole holds files: "they".
        boolean one = fresh.size() == 1 && !fresh.get(0).endsWith("/");
        String named = String.join(", ", fresh.subList(0, Math.min(NEW_FILES_NAMED,
                fresh.size())));
        if (fresh.size() > NEW_FILES_NAMED) {
            named += " and " + (fresh.size() - NEW_FILES_NAMED) + " more";
        }
        return Parameter.of(COMMIT_NEW, "Commit the project's " + ReleaseRun.described(fresh)
                + " first", Parameter.Kind.FLAG)
                .defaultValue(values.get(COMMIT_NEW) == null ? "true" : values.get(COMMIT_NEW))
                .help("Git has never been told about " + (one ? "this file" : "these files")
                        + " of the project: " + named + ". With this on, " + (one ? "it is"
                                : "they are") + " committed first, in a commit of "
                        + (one ? "its" : "their") + " own - \"" + ReleaseRun.newFilesMessage(
                                "<version>") + "\" - and then the release, so the release's "
                        + "tag holds " + (one ? "it" : "them") + ": a new import module or test "
                        + "missing from a tag breaks the project for whoever fetches it. Off "
                        + "leaves " + (one ? "it" : "them") + " out of the release's commit, "
                        + "as before OntoBoard 1.154.0.")
                .build();
    }

    // ------------------------------------------------------------------ what is proposed

    /**
     * The version the form proposes.
     *
     * <p>After the newer of what the edit file declares and the newest release numbered the
     * same way. Before any release: 1.0.0 for a project OntoBoard made, whose release page
     * says so, and the day's date for another ODK repository, which is ODK's own default. A
     * version the project's script already names, and nobody has released, is taken as meant.
     */
    static OntologyVersion proposedVersion(Start start) {
        OntologyVersion proposed;
        if (start.declared != null) {
            proposed = start.declared.suggestNext(start.today);
        } else if (!start.released.isEmpty()) {
            proposed = start.released.get(0).suggestNext(start.today);
        } else {
            proposed = ReleaseRun.keepsReleasesFolder(start.facts.root())
                    ? OntologyVersion.of("1.0.0") : OntologyVersion.of(start.today);
        }
        OntologyVersion newest = newest(start, proposed.getScheme());
        if (newest != null && newest.compareTo(proposed) >= 0) {
            OntologyVersion after = newest.suggestNext(start.today);
            if (after.compareTo(newest) > 0) {
                proposed = after;
            }
        }
        OntologyVersion scripted = start.script == null || start.script.version() == null
                ? null : OntologyVersion.parseOrNull(start.script.version().trim());
        if (scripted != null && !start.released.contains(scripted)) {
            OntologyVersion before = newest(start, scripted.getScheme());
            if (before == null || scripted.compareTo(before) > 0) {
                proposed = scripted;
            }
        }
        return proposed;
    }

    /** The newest release numbered this way, or null. */
    private static OntologyVersion newest(Start start, OntologyVersion.Scheme scheme) {
        for (OntologyVersion version : start.released) {
            if (version.getScheme() == scheme) {
                return version;
            }
        }
        return null;
    }

    /** What the form holds when it opens. */
    static Map<String, String> defaults(Start start) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        OntologyVersion proposed = proposedVersion(start);
        values.put(VERSION, proposed.getText());
        if (start.script == null) {
            VersionIriShape shape = start.facts.ontbase() == null
                    ? VersionIriShape.of(VersionIriShape.Kind.ODK) : start.proposal.shape();
            values.put(SHAPE, label(shape.kind(), start.facts));
            if (start.facts.ontbase() != null) {
                values.put(PATTERN, shape.pattern());
            }
            if (start.mayRefreshImports()) {
                values.put(REFRESH, "true");
            }
        } else if (start.script.priorVersion() != null) {
            OntologyVersion before = newest(start, proposed.getScheme());
            values.put(PRIOR, before == null ? start.script.priorVersion().trim()
                    : before.getText());
        }
        values.put(REFUSE, "true");
        values.put(UNCOMPARED, "false");
        if (!newFilesOfTheProject(start).isEmpty()) {
            values.put(COMMIT_NEW, "true");
        }
        return values;
    }

    /** A shape as the form's choice shows it: the IRI of the main file, the version left open. */
    static String label(VersionIriShape.Kind kind, OdkRelease.Facts facts) {
        String base = facts.ontbase() == null ? "<ONTBASE>" : facts.ontbase();
        String file = facts.id() + ".owl";
        switch (kind) {
            case ODK:
                return base + "/releases/<version>/" + file + "  (ODK's)";
            case VERSION_AND_FILE:
                return base + "/<version>/" + file;
            case VERSION_ONLY:
                return base + "/<version>";
            default:
                return OWN_PATTERN;
        }
    }

    // ------------------------------------------------------------------ the form

    /** The sentence above the form. */
    static String explanation(Start start) {
        String git = " Then the git commands for the commit and the tag are shown, and run "
                + "when you agree. Nothing is pushed.";
        if (start.script != null) {
            String annotate = start.script.annotate();
            return "This project is released by its own script, src/ontology/"
                    + start.script.file().getName() + ", so that is what runs, in ODK's "
                    + "container. OntoBoard sets its line VERSION="
                    + (start.script.priorVersion() == null ? "" : " and its line PRIOR_VERSION=")
                    + " first and changes nothing else in it. The version IRI is the script's: "
                    + (annotate == null ? "it sets none of its own, so ODK's is written."
                            : annotate + ".") + git;
        }
        // A release cleans first, as ODK's maintainers say a release should, and that empties
        // ODK's scratch folders: said, because the next test fetches the imports again.
        String what = "ODK's make clean runs first and empties tmp/ and mirror/, so that "
                + "nothing an earlier build left is taken for the release's; then the "
                + "reasoner, the quality report and the project's tests run, and "
                + "the release files are written to the top of the project"
                + (ReleaseRun.keepsReleasesFolder(start.facts.root())
                        ? " and copied to releases/<version>/." : ".");
        if (start.isBuiltBy(ReleaseRoute.Kind.ITSELF)) {
            // Said first, so that nobody takes this for a run of ODK's container.
            return start.route.noContainer() + ", so OntoBoard builds the release itself, "
                    + "inside Protege, with ODK's own Makefile (prepare_release): " + what
                    + (start.mayRefreshImports() ? "" : " The import modules are used as they "
                            + "are, since the release that fetches the imported ontologies "
                            + "again first is not built here: "
                            + de.fizkarlsruhe.ise.ontoboard.odk.OdkEngine.firstSentence(
                                    start.route.whyNotFull())) + git;
        }
        return "Builds the release with ODK's prepare_release, "
                + (start.isBuiltBy(ReleaseRoute.Kind.NATIVE) ? "in the native ODK environment"
                        : "in ODK's container") + ": " + what + git;
    }

    /** The form, with whatever was typed last time already in it. */
    static List<Parameter> parameters(Start start, Map<String, String> values) {
        List<Parameter> parameters = new ArrayList<Parameter>();
        parameters.add(Parameter.of(VERSION, "Version", Parameter.Kind.TEXT)
                .defaultValue(values.get(VERSION))
                .required()
                .help("It becomes the tag v<version>"
                        + (start.script == null ? " and part of the version IRI"
                                : " and the script's line VERSION=")
                        + ", so it is what anybody uses to say which release they have. Numbers "
                        + "as MAJOR.MINOR.PATCH - patch when nothing moved, minor when terms "
                        + "were added, major when something published is gone or has moved - "
                        + "or a date such as " + start.today + ". A version is released once. "
                        + history(start))
                .build());
        if (start.script == null) {
            parameters.add(shapeChoice(start, values));
            if (start.facts.ontbase() != null) {
                parameters.add(Parameter.of(PATTERN, "Pattern", Parameter.Kind.TEXT)
                        .defaultValue(values.get(PATTERN))
                        .help("Counts only when the choice above is \"" + OWN_PATTERN + "\". "
                                + "Write the address with {ontbase} for " + start.facts.ontbase()
                                + ", {version} for the version and {file} for the file's name, "
                                + "for example {ontbase}/v{version}/{file}.")
                        .build());
            }
            if (start.mayRefreshImports()) {
                parameters.add(Parameter.of(REFRESH, "Refresh the imports first",
                        Parameter.Kind.FLAG)
                        .defaultValue(values.get(REFRESH))
                        .help("Fetches the ontologies this project imports again and rebuilds "
                                + "its import modules before the release is built, as ODK's "
                                + "prepare_release does. That needs the network and takes "
                                + "longest. Off builds from the import modules as they are "
                                + "(prepare_release_fast).")
                        .build());
            }
        } else if (start.script.priorVersion() != null) {
            parameters.add(Parameter.of(PRIOR, "Previous version", Parameter.Kind.TEXT)
                    .defaultValue(values.get(PRIOR))
                    .required()
                    .help("The script has a line PRIOR_VERSION=, which its own steps use - "
                            + "a script may write it into the ontology as the version this one "
                            + "follows. Normally the last release.")
                    .build());
        }
        parameters.add(Parameter.of(REFUSE, OdkRelease.REFUSE_LABEL, Parameter.Kind.FLAG)
                .defaultValue(values.get(REFUSE))
                .help("When the release is built it is held against the last one. A term that "
                        + "is gone - not marked owl:deprecated, gone - breaks every ontology "
                        + "that imported it, and nothing tells them. With this on, such a "
                        + "release is not copied, committed or tagged. ODK's own tests run as "
                        + "part of the build either way.")
                .build());
        // F6 (ruling 16): a release that cannot be held against the last one stops, as one
        // with a term gone does; this is the way on.
        parameters.add(Parameter.of(UNCOMPARED, OdkRelease.UNCOMPARED_LABEL,
                Parameter.Kind.FLAG)
                .defaultValue(values.get(UNCOMPARED))
                .help("Where the last release cannot be read - a broken file under releases/, "
                        + "a tag without the release's main file - the new release cannot be "
                        + "held against it, and it is not copied, committed or tagged. With "
                        + "this on it is, without the comparison, and the result says so.")
                .build());
        Parameter first = commitNewFirst(start, values);
        if (first != null) {
            parameters.add(first);
        }
        return parameters;
    }

    private static Parameter shapeChoice(Start start, Map<String, String> values) {
        List<String> choices = new ArrayList<String>();
        choices.add(label(VersionIriShape.Kind.ODK, start.facts));
        if (start.facts.ontbase() != null) {
            choices.add(label(VersionIriShape.Kind.VERSION_AND_FILE, start.facts));
            choices.add(label(VersionIriShape.Kind.VERSION_ONLY, start.facts));
            choices.add(OWN_PATTERN);
        }
        String chosen = values.get(SHAPE);
        String last = start.proposal.lastVersion() == null
                ? "This is the first release, so the choice is free."
                : "The last release, " + start.proposal.lastVersion() + ", "
                        + (start.proposal.lastVersionIri() == null ? "shows none."
                                : "has " + start.proposal.lastVersionIri() + ".");
        return Parameter.of(SHAPE, "Version IRI", Parameter.Kind.CHOICE)
                .choices(choices.toArray(new String[0]))
                .defaultValue(choices.contains(chosen) ? chosen : choices.get(0))
                .help("How the version IRI of each released file is written; it is shown for "
                        + start.facts.id() + ".owl, and every file of the release gets its "
                        + "own. ODK's points into the releases folder. A version IRI is how a "
                        + "release is cited and imported and cannot be taken back, so a "
                        + "project keeps the shape it has. " + last
                        + (start.facts.ontbase() == null ? " This project's Makefile does not "
                                + "say plainly what its version IRIs start with (ONTBASE), so "
                                + "only ODK's own shape can be written from here." : ""))
                .build();
    }

    private static String history(Start start) {
        if (start.released.isEmpty()) {
            return "Nothing has been released yet.";
        }
        StringBuilder named = new StringBuilder();
        for (OntologyVersion version : start.released.subList(0,
                Math.min(VERSIONS_NAMED, start.released.size()))) {
            named.append(named.length() == 0 ? "" : ", ").append(version.getText());
        }
        int more = start.released.size() - VERSIONS_NAMED;
        return "Released so far, newest first: " + named
                + (more > 0 ? " and " + more + " before" : "") + ".";
    }

    // ------------------------------------------------------------------ what is refused

    /** The version as typed, without blanks and without the v of a tag. */
    private static String versionIn(Map<String, String> chosen, String key) {
        String typed = chosen.get(key) == null ? "" : chosen.get(key).trim();
        return typed.matches("[vV][0-9].*") ? typed.substring(1) : typed;
    }

    /** Why what was chosen cannot be released, each reason a sentence; empty when it can. */
    static List<String> problems(Start start, Map<String, String> chosen) {
        List<String> problems = new ArrayList<String>();
        String typed = versionIn(chosen, VERSION);
        OntologyVersion releasing = OntologyVersion.parseOrNull(typed);
        if (releasing == null) {
            problems.add("'" + typed + "' is neither a date ("
                    + OntologyVersion.Scheme.DATE.getExample() + ") nor a semantic version ("
                    + OntologyVersion.Scheme.SEMANTIC.getExample() + "). The version names "
                    + "the tag and goes into the version IRI, so it has to be one that sorts.");
        } else {
            String there = ReleaseRun.whyNotThisVersion(start.facts.root(), typed, start.tags);
            OntologyVersion newest = newest(start, releasing.getScheme());
            if (there != null) {
                problems.add(there);
            } else if (newest != null && releasing.compareTo(newest) < 0) {
                problems.add(newest.getText() + " has been released, and " + typed + " would be "
                        + "published below it. "
                        + (releasing.getScheme() == OntologyVersion.Scheme.DATE
                                ? "Release with today's date, or a later one."
                                : "Type a version above it: " + newest.nextPatch().getText()
                                        + " if nothing moved, " + newest.nextMinor().getText()
                                        + " if terms were added."));
            }
        }
        if (start.script == null) {
            try {
                shape(start, chosen);
            } catch (IllegalArgumentException notAPattern) {
                problems.add(notAPattern.getMessage());
            }
        } else if (chosen.containsKey(PRIOR)
                && OntologyVersion.parseOrNull(versionIn(chosen, PRIOR)) == null) {
            problems.add("The previous version, '" + versionIn(chosen, PRIOR) + "', is neither "
                    + "a date nor a semantic version. It goes into the script's line "
                    + "PRIOR_VERSION=.");
        }
        return problems;
    }

    /** The shape chosen. The pattern counts only when it is the choice. */
    private static VersionIriShape shape(Start start, Map<String, String> chosen) {
        String choice = chosen.get(SHAPE);
        if (OWN_PATTERN.equals(choice) && start.facts.ontbase() != null) {
            return VersionIriShape.custom(chosen.get(PATTERN));
        }
        for (VersionIriShape.Kind kind : new VersionIriShape.Kind[] {
            VersionIriShape.Kind.VERSION_AND_FILE, VersionIriShape.Kind.VERSION_ONLY}) {
            if (start.facts.ontbase() != null && label(kind, start.facts).equals(choice)) {
                return VersionIriShape.of(kind);
            }
        }
        return VersionIriShape.of(VersionIriShape.Kind.ODK);
    }

    /** The release as chosen. For what {@link #problems} lets through. */
    static OdkRelease.Request request(Start start, Map<String, String> chosen) {
        return new OdkRelease.Request(start.editFile, versionIn(chosen, VERSION),
                start.script == null ? shape(start, chosen) : null,
                start.mayRefreshImports() && !"false".equalsIgnoreCase(chosen.get(REFRESH)),
                !"false".equalsIgnoreCase(chosen.get(REFUSE)),
                "true".equalsIgnoreCase(chosen.get(UNCOMPARED)),
                !newFilesOfTheProject(start).isEmpty()
                        && !"false".equalsIgnoreCase(chosen.get(COMMIT_NEW)));
    }

    /**
     * The line under the form (E3): the release in plain words, then make's commands as the
     * release runs them - make clean, then ODK's target with the version, and the version IRI
     * where it is not ODK's shape - for those who know ODK. Null for a project released by its
     * own script: the explanation above the form names the script.
     */
    static java.util.function.Function<Map<String, String>, String> lineUnder(
            final Start start) {
        return start.script != null ? null : chosen -> commandShown(start, chosen);
    }

    private static String commandShown(Start start, Map<String, String> chosen) {
        boolean fresh = start.mayRefreshImports()
                && !"false".equalsIgnoreCase(chosen.get(REFRESH));
        String target = fresh ? "prepare_release" : "prepare_release_fast";
        String version = versionIn(chosen, VERSION);
        List<String> make;
        try {
            make = ReleaseRun.makeArguments(version, shape(start, chosen), start.facts.ontbase(),
                    fresh);
        } catch (IllegalArgumentException notYet) {
            // Not a version, or a pattern that cannot be one, yet: what was typed. The form
            // says what is wrong with it when it is sent.
            make = Arrays.asList("make", target, "VERSION=" + version);
        }
        return TargetWords.of(target) + " - " + String.join(" ", ReleaseRun.cleanArguments())
                + ", then " + asTyped(make);
    }

    /**
     * A command as a terminal takes it: an argument with a blank or a {@code $} in it in single
     * quotes, after its {@code NAME=} when it has one - make's {@code $@} stays make's.
     */
    private static String asTyped(List<String> arguments) {
        StringBuilder line = new StringBuilder();
        for (String argument : arguments) {
            if (line.length() > 0) {
                line.append(' ');
            }
            if (argument.matches("[A-Za-z0-9_./=:@%+,-]*")) {
                line.append(argument);
                continue;
            }
            int equals = argument.indexOf('=');
            String name = equals > 0
                    && argument.substring(0, equals).matches("[A-Za-z_][A-Za-z0-9_]*")
                    ? argument.substring(0, equals + 1) : "";
            line.append(name).append('\'')
                    .append(argument.substring(name.length()).replace("'", "'\\''"))
                    .append('\'');
        }
        return line.toString();
    }

    // ------------------------------------------------------------------ before git records it

    /** What is shown before the commit: where, on which branch, the commands, what is left. */
    static String confirmation(ReleaseRun.Staging staging, File root) {
        StringBuilder text = new StringBuilder("The release is built. To record it, these git "
                + "commands run in\n").append(root.getAbsolutePath()).append('\n');
        if (staging.branch() != null) {
            text.append("on branch ").append(staging.branch()).append(":\n\n");
        } else if (staging.onNoBranch()) {
            text.append("where no branch is checked out (a detached HEAD), so the commit will "
                    + "be reachable through its tag only:\n\n");
        } else {
            text.append("on the branch that is checked out:\n\n");
        }
        text.append(staging.shown()).append("\n\n");
        int putBack = staging.putBack().size();
        if (putBack > 0) {
            text.append("The first command puts back ").append(putBack == 1 ? "a file"
                    : putBack + " files").append(" that git tracks in ODK's scratch folders "
                    + "and that cleaning before the release removed, so that the commit does "
                    + "not delete ").append(putBack == 1 ? "it" : "them").append(".\n");
        }
        List<String> leftOut = staging.leftOut();
        if (!leftOut.isEmpty()) {
            text.append("Not taken into the commit, because git has never tracked ")
                    .append(leftOut.size() == 1 ? "it: " : "them: ")
                    .append(String.join(", ", leftOut.subList(0,
                            Math.min(LEFT_OUT_NAMED, leftOut.size()))));
            if (leftOut.size() > LEFT_OUT_NAMED) {
                text.append(" and ").append(leftOut.size() - LEFT_OUT_NAMED).append(" more");
            }
            text.append(".\n");
        }
        return text.append("Nothing is pushed.").toString();
    }

    // ------------------------------------------------------------------ the release

    /**
     * The release, where nobody reads again what it rewrote: that is said instead.
     *
     * @param routeNote a word about who builds it, said first, or null
     */
    static OperationResult run(String operation, Start start, Map<String, String> chosen,
            OdkRelease.Odk odk, ProcessRunner.Runner git, OdkRelease.Asks asks,
            String routeNote) {
        return run(operation, start, chosen, odk, git, asks, routeNote, null);
    }

    /**
     * The release.
     *
     * @param routeNote  a word about who builds it, said first: why ODK's image is not the ODK
     *                   that wrote the project, or that ROBOT, run by OntoBoard itself, has
     *                   less memory than the project asks for; null for none
     * @param readsAgain whoever has the project open, asked to read again the edit file
     *                   and the import modules where the release rewrote them; null to say
     *                   instead that they were rewritten
     */
    static OperationResult run(String operation, Start start, Map<String, String> chosen,
            OdkRelease.Odk odk, ProcessRunner.Runner git, OdkRelease.Asks asks,
            String routeNote, ReadsAgain readsAgain) {
        OperationResult.Builder result = OperationResult.of(operation);
        OdkRelease.Request request = request(start, chosen);
        result.note("Project: " + start.facts.root().getAbsolutePath());
        result.note("Version: " + request.version());
        if (routeNote != null) {
            result.warn(routeNote);
        }
        if (odk.runsTheMakefileAsItIs()) {
            // F5: make runs a Makefile that is not ODK's as it is; the engine would refuse it.
            String notOdks = de.fizkarlsruhe.ise.ontoboard.odk.FallbackEligibility
                    .notOdksMakefile(request.editFile());
            if (notOdks != null) {
                result.warn(notOdks);
            }
        }
        if (start.script != null) {
            String said = setScriptLines(start, chosen, request.version());
            if (said == null) {
                return result.failed("The release script could not be written, so nothing "
                        + "was run: " + start.script.file().getAbsolutePath()).build();
            }
            result.note(said);
        } else if (start.facts.ontbase() != null) {
            result.note("Version IRI: " + request.shape().iriFor(start.facts.ontbase(),
                    request.version(), start.facts.id() + ".owl"));
        }

        de.fizkarlsruhe.ise.ontoboard.odk.FileStamps before =
                de.fizkarlsruhe.ise.ontoboard.odk.FileStamps.of(watched(start));
        OdkRelease.Outcome outcome = OdkRelease.run(request, odk, git, asks);
        for (String line : outcome.getTranscript()) {
            result.note(line);
        }
        for (File file : outcome.getWritten()) {
            result.wrote(file);
        }
        for (String warning : outcome.getWarnings()) {
            result.warn(warning);
        }
        List<File> rewrittenFiles = before.now().writtenSince(before);
        if (!rewrittenFiles.isEmpty() && readsAgain != null) {
            WhatARunWrote.Done done = readsAgain.readAgain(rewrittenFiles);
            if (done.said() != null) {
                result.note(done.said());
            }
            for (String why : done.notRead()) {
                result.warn(why);
            }
        } else {
            String rewritten = rewrittenUnderProtege(start, rewrittenFiles);
            if (rewritten != null) {
                result.warn(rewritten);
            }
        }
        // Last, where the summary says they are: the git commands that were not run.
        for (String line : outcome.getLater()) {
            result.note(line);
        }
        if (!outcome.isOk()) {
            return result.failed(outcome.getFailure()).build();
        }
        return result.summary(outcome.getSummary()).build();
    }

    /**
     * Sets the script's lines to what was chosen; what it says in the result, or null when the
     * file could not be written.
     */
    private static String setScriptLines(Start start, Map<String, String> chosen,
            String version) {
        ReleaseRun.Script script = start.script;
        String prior = chosen.containsKey(PRIOR) && script.priorVersion() != null
                ? versionIn(chosen, PRIOR) : null;
        String text = script.withLines(version, prior, null);
        try {
            if (!text.equals(script.text())) {
                script.write(text);
            }
        } catch (IOException cannotWrite) {
            return null;
        }
        return script.file().getName() + ": VERSION=" + version
                + (prior == null ? "" : ", PRIOR_VERSION=" + prior)
                + (text.equals(script.text()) ? " (as it was)"
                        : " (was " + script.version()
                                + (prior == null ? "" : ", " + script.priorVersion()) + ")");
    }

    // ------------------------------------------------------------------ files Protégé has open

    /** The files a release may rewrite that Protégé has in memory: the edit file, its imports. */
    private static List<File> watched(Start start) {
        List<File> files = new ArrayList<File>();
        files.add(start.editFile);
        File[] imports = new File(start.facts.ontology(), "imports").listFiles();
        if (imports != null) {
            Arrays.sort(imports);
            for (File module : imports) {
                if (module.isFile()) {
                    files.add(module);
                }
            }
        }
        return files;
    }

    /**
     * What to say when the release rewrote a file Protégé has open, or null. Protégé does not
     * notice a file changing under it, and its next save would put the old one back.
     */
    private static String rewrittenUnderProtege(Start start, List<File> rewritten) {
        File editFile = start.editFile.getAbsoluteFile().toPath().normalize().toFile();
        boolean edit = false;
        int modules = 0;
        for (File file : rewritten) {
            if (file.equals(editFile)) {
                edit = true;
            } else {
                modules++;
            }
        }
        if (!edit && modules == 0) {
            return null;
        }
        String imports = modules + " import module" + (modules == 1 ? "" : "s");
        if (!edit) {
            return "The release rewrote " + imports + " on disk. Protege still has what it "
                    + "loaded before: reopen the ontology to work with what the release "
                    + "wrote.";
        }
        return "The release rewrote " + start.editFile.getName()
                + (modules == 0 ? "" : " and " + imports) + " on disk. Protege still has the "
                + "ontology as it was before: reopen it before you edit further, or the next "
                + "save puts the old edit file back over what the release wrote.";
    }
}
