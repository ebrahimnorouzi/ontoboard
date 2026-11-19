package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.git.GitRepo;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a release asks of ODK's make, of a project's own release script, and of git.
 *
 * <p>A release of an ODK project is ODK's {@code prepare_release}: it builds the release files
 * and copies them to the top of the repository. A project that has a
 * {@code src/ontology/release.sh} of its own - nfdicore and mwo have - is released by that
 * script instead, since that is how its maintainers release it. Afterwards the release is a
 * commit and a tag {@code v<version>}; ODK keeps the release files out of git until then.
 *
 * <p>This class decides and words those steps and runs none of them: the arguments for make,
 * the three lines of a release script a release changes, the copy of a release under
 * {@code releases/<version>/}, and the git commands, which are shown before they are run.
 */
public final class ReleaseRun {

    private ReleaseRun() {
    }

    // ------------------------------------------------------------------ make

    /**
     * What a release runs first: {@code make clean}.
     *
     * <p>ODK's {@code test} builds the ontology it validates, the very file a release puts
     * at the top of the project, and stamps it with the day. A release made after a test
     * would find that file up to date and take it for its own - a release numbered 1.0.0
     * then carries the day as its version IRI. Cleaning takes that file away, as the
     * release scripts of real projects do before they release.
     *
     * <p>Not {@code make -B}, although ODK's own instructions release that way: it makes
     * everything again, the import modules too, and ODK's rule for a module declared
     * custom is an error that tells the project to write its own. OntoBoard declares what
     * it keeps as it is - a pattern, a MIREOT import, a file of this machine - as custom,
     * so such a project could not be released at all (seen live, 1.153.0).
     */
    public static List<String> cleanArguments() {
        return new ArrayList<String>(Arrays.asList("make", "clean"));
    }

    /**
     * What to run for a release, after {@link #cleanArguments}:
     * {@code make prepare_release VERSION=...}.
     *
     * @param shape          the version IRI's shape; ODK's own needs no further argument, any
     *                       other is handed to make as {@code ANNOTATE_ONTOLOGY_VERSION=...},
     *                       one argument, as nfdicore's release script hands it over
     * @param ontbase        the project's {@code ONTBASE}: what its version IRIs start with
     * @param refreshImports false for {@code prepare_release_fast}, which builds from the
     *                       imports as they are and needs no network
     */
    public static List<String> makeArguments(String version, VersionIriShape shape,
            String ontbase, boolean refreshImports) {
        return makeArguments(version, shape, ontbase, refreshImports, null);
    }

    /**
     * As {@link #makeArguments(String, VersionIriShape, String, boolean)}, with the reasoner the
     * person chose for the release as {@code REASONER=} - ODK's Makefile variable, which every
     * route hands to make (1.155.0: "for release the user define the reasoner").
     *
     * @param reasoner the reasoner's name as ROBOT takes it, or null for the project's own
     */
    public static List<String> makeArguments(String version, VersionIriShape shape,
            String ontbase, boolean refreshImports, String reasoner) {
        List<String> arguments = plainMakeArguments(version, shape, ontbase, refreshImports);
        if (reasoner != null && !reasoner.trim().isEmpty()) {
            arguments.add("REASONER=" + reasoner.trim());
        }
        return arguments;
    }

    private static List<String> plainMakeArguments(String version, VersionIriShape shape,
            String ontbase, boolean refreshImports) {
        if (version == null || OntologyVersion.parseOrNull(version) == null) {
            throw new IllegalArgumentException("'" + version + "' is not a version: a date such "
                    + "as 2026-11-11, or numbers such as 1.0.0.");
        }
        List<String> arguments = new ArrayList<String>(Arrays.asList("make",
                refreshImports ? "prepare_release" : "prepare_release_fast",
                "VERSION=" + version));
        String annotate = shape.annotateArgument(ontbase, version);
        if (annotate != null) {
            arguments.add("ANNOTATE_ONTOLOGY_VERSION=" + annotate);
        }
        return arguments;
    }

    // ------------------------------------------------------------------ the project's own script

    /**
     * A project's own release script, read for the three lines a release changes:
     * {@code VERSION=}, {@code PRIOR_VERSION=} and {@code ANNOTATE_ONTOLOGY_VERSION=}.
     */
    public static final class Script {
        private static final Pattern VERSION =
                Pattern.compile("(?m)^(VERSION=)(\"?)([^\"\\r\\n]*?)(\"?)([ \\t]*)$");
        private static final Pattern PRIOR =
                Pattern.compile("(?m)^(PRIOR_VERSION=)(\"?)([^\"\\r\\n]*?)(\"?)([ \\t]*)$");
        private static final Pattern ANNOTATE =
                Pattern.compile("(?m)^(ANNOTATE_ONTOLOGY_VERSION=)(\")([^\\r\\n]*)(\")([ \\t]*)$");

        private final File file;
        private final String text;

        Script(File file, String text) {
            this.file = file;
            this.text = text;
        }

        public File file() {
            return file;
        }

        /** The script as it is. */
        public String text() {
            return text;
        }

        /** What {@code VERSION=} is set to, or null when the script has no such line. */
        public String version() {
            return valueOf(VERSION);
        }

        public String priorVersion() {
            return valueOf(PRIOR);
        }

        /** The quoted value of {@code ANNOTATE_ONTOLOGY_VERSION=}, as the script writes it. */
        public String annotate() {
            return valueOf(ANNOTATE);
        }

        /**
         * Whether the script has Windows line endings, as Git for Windows checks nfdicore and
         * MWO out. The shell in ODK's container, dash, takes each carriage return for part of
         * the line's last word - the version, the target - so it is not given the file as it is
         * ({@link #shellWords()}).
         */
        public boolean hasWindowsLineEndings() {
            return text.contains("\r\n");
        }

        /**
         * The words a shell in ODK's container is given to run the script: {@code sh
         * release.sh}; or, for a script with Windows line endings, a copy in the container read
         * with the carriage returns taken off, run under the script's own name - {@code $0} is
         * the same, and the file is not changed (1.155.0: before, such a release was refused).
         *
         * <p>No word has a double quote in it: Java on Windows does not escape one inside an
         * argument it hands to a program, and docker would read the words apart.
         */
        public List<String> shellWords() {
            String name = file.getName();
            if (!hasWindowsLineEndings()) {
                return Arrays.asList("sh", name);
            }
            String copy = "/tmp/" + name;
            return Arrays.asList("sh", "-c", "sed 's/\\r$//' " + name + " > " + copy
                    + " && exec sh -c '. " + copy + "' " + name);
        }

        /**
         * Writes the script, as {@link #withLines} gave it. The text is the file's bytes,
         * each one character ({@link #BYTES}), so that what was not set comes back byte for
         * byte whatever the file's encoding: read as UTF-8, a comment in Latin-1 lost every
         * letter that is not ASCII (review of 1.153.0).
         */
        public void write(String changed) throws IOException {
            Files.write(file.toPath(), changed.getBytes(BYTES));
        }

        private String valueOf(Pattern line) {
            Matcher match = line.matcher(text);
            return match.find() ? match.group(3) : null;
        }

        /**
         * The script with those lines set, and not another character changed.
         *
         * @param version  the version to release
         * @param prior    the version before it, or null to leave that line as it is
         * @param annotate the new quoted value of ANNOTATE_ONTOLOGY_VERSION, or null to leave it
         * @throws IllegalStateException    when the script has no line for a value given
         * @throws IllegalArgumentException when a version is not a version
         */
        public String withLines(String version, String prior, String annotate) {
            String changed = set(text, VERSION, "VERSION", checkedVersion(version));
            if (prior != null) {
                changed = set(changed, PRIOR, "PRIOR_VERSION", checkedVersion(prior));
            }
            if (annotate != null) {
                if (annotate.indexOf('"') >= 0 || annotate.indexOf('\n') >= 0
                        || annotate.indexOf('\r') >= 0 || annotate.indexOf('`') >= 0) {
                    throw new IllegalArgumentException("The value of ANNOTATE_ONTOLOGY_VERSION "
                            + "stands between double quotes in the script: it cannot hold one, "
                            + "a backquote or a line break.");
                }
                changed = set(changed, ANNOTATE, "ANNOTATE_ONTOLOGY_VERSION", annotate);
            }
            return changed;
        }

        private String set(String script, Pattern line, String name, String value) {
            Matcher match = line.matcher(script);
            if (!match.find()) {
                throw new IllegalStateException(file.getName() + " has no line '" + name
                        + "=...', so OntoBoard cannot set it there. Edit the script, or release "
                        + "without it.");
            }
            return script.substring(0, match.start(3)) + value + script.substring(match.end(3));
        }

        private static String checkedVersion(String version) {
            if (version == null || OntologyVersion.parseOrNull(version) == null) {
                throw new IllegalArgumentException("'" + version + "' is not a version: a date "
                        + "such as 2026-11-11, or numbers such as 1.0.0.");
            }
            return version;
        }
    }

    /**
     * How a release script's bytes are held as text: one character each, which no encoding
     * the file may be in can break. What OntoBoard reads from it and sets in it - versions,
     * the line that annotates - is ASCII.
     */
    static final java.nio.charset.Charset BYTES = StandardCharsets.ISO_8859_1;

    /** The project's {@code release.sh}, or null when it has none. */
    public static Script scriptIn(File ontologyDirectory) {
        File file = new File(ontologyDirectory, "release.sh");
        if (!file.isFile()) {
            return null;
        }
        try {
            return new Script(file, new String(Files.readAllBytes(file.toPath()), BYTES));
        } catch (IOException unreadable) {
            throw new IllegalStateException(file + " could not be read: "
                    + unreadable.getMessage(), unreadable);
        }
    }

    // ------------------------------------------------------------------ releases/<version>/

    /**
     * Whether a release of this project is also kept under {@code releases/<version>/}.
     *
     * <p>ODK's version IRIs point there, and a project OntoBoard made - its configuration says
     * so in its first line - or one that has kept its releases there so far gets the copy. An
     * ODK repository OntoBoard did not make does not: it is released exactly as its maintainers
     * release it.
     */
    public static boolean keepsReleasesFolder(File projectRoot) {
        if (new File(projectRoot, "releases").isDirectory()) {
            return true;
        }
        File[] ontology = new File(projectRoot, "src/ontology").listFiles();
        for (File file : ontology == null ? new File[0] : ontology) {
            if (file.isFile() && file.getName().endsWith("-odk.yaml")) {
                try {
                    return OdkConfigWriter.isOntoBoards(new String(
                            Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                } catch (IOException unreadable) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Why this version cannot be released, or null when it can: it has a folder under
     * {@code releases/} or a tag already. A version IRI cannot be taken back once somebody has
     * imported it, so a version is released once.
     *
     * @param releaseTags the project's release tags ({@link GitTags#releases})
     */
    public static String whyNotThisVersion(File projectRoot, String version,
            List<String> releaseTags) {
        String there = null;
        String[] kept = new File(projectRoot, "releases/" + version).list();
        if (kept != null && kept.length > 0) {
            there = "releases/" + version + " is there already";
        } else if (releaseTags.contains(GitTags.tagFor(version))) {
            there = "the tag " + GitTags.tagFor(version) + " is there already";
        }
        if (there == null) {
            String same = sameNumberReleased(projectRoot, version, releaseTags);
            if (same != null) {
                return "Version " + version + " is the same number as " + same + ", which "
                        + "has been released. A version is released once, under one name, so "
                        + "the next release gets the next number.";
            }
        }
        return there == null ? null : "Version " + version + " has been released: " + there
                + ". A version IRI cannot be taken back once somebody has imported it, so the "
                + "next release gets the next number.";
    }

    /**
     * A released version that is this number written another way - 1.0.0 for 1.0 - or null.
     * By its folder and its tag such a version is a new one; by its number it is not, and it
     * would be released a second time under another IRI (review of 1.153.0).
     */
    private static String sameNumberReleased(File projectRoot, String version,
            List<String> releaseTags) {
        OntologyVersion asked = OntologyVersion.parseOrNull(version);
        if (asked == null) {
            return null;
        }
        List<String> released = new ArrayList<String>();
        File[] folders = new File(projectRoot, "releases").listFiles();
        for (File folder : folders == null ? new File[0] : folders) {
            String[] inside = folder.list();
            if (inside != null && inside.length > 0) {
                released.add(folder.getName());
            }
        }
        for (String tag : releaseTags) {
            String tagged = GitTags.versionOf(tag);
            if (tagged != null) {
                released.add(tagged);
            }
        }
        for (String other : released) {
            OntologyVersion is = OntologyVersion.parseOrNull(other);
            if (is != null && !other.equals(version) && is.compareTo(asked) == 0) {
                return other;
            }
        }
        return null;
    }

    /**
     * Copies a release's files to {@code releases/<version>/}, each under the path it has from
     * the project's root.
     *
     * @return the copies, in the order given
     * @throws IllegalStateException    when that release is there already; nothing is written
     * @throws IllegalArgumentException when an asset is not a file inside the project
     */
    public static List<File> copyToReleases(File projectRoot, String version, List<File> assets)
            throws IOException {
        File folder = new File(projectRoot, "releases/" + version);
        String[] kept = folder.list();
        if (kept != null && kept.length > 0) {
            throw new IllegalStateException(whyNotThisVersion(projectRoot, version,
                    Collections.<String>emptyList()));
        }
        List<String> paths = new ArrayList<String>();
        for (File asset : assets) {
            paths.add(relative(projectRoot, asset));
        }
        // Nothing of this release is there (asked above), so whatever is there after a
        // failure is this call's, and goes: a folder under releases/ says the version has
        // been released, and the next try would be refused for it.
        boolean wasThere = folder.exists();
        List<File> copies = new ArrayList<File>();
        try {
            for (int i = 0; i < assets.size(); i++) {
                File copy = new File(folder, paths.get(i).replace('/', File.separatorChar));
                Files.createDirectories(copy.getParentFile().toPath());
                Files.copy(assets.get(i).toPath(), copy.toPath());
                copies.add(copy);
            }
        } catch (IOException | RuntimeException failed) {
            takeBack(folder, wasThere);
            throw failed;
        }
        return copies;
    }

    /**
     * Removes a release's folder after a copy that did not get through, with what the copy
     * left in it; the folder itself stays where it was there, empty, before.
     */
    static void takeBack(File folder, boolean wasThere) {
        File[] inside = folder.listFiles();
        for (File child : inside == null ? new File[0] : inside) {
            takeBack(child, false);
        }
        if (!wasThere) {
            folder.delete();
        }
    }

    /** A file's path from the project's root, with {@code /}; refused when it is not inside. */
    private static String relative(File projectRoot, File file) {
        Path root = projectRoot.getAbsoluteFile().toPath().normalize();
        Path path = file.getAbsoluteFile().toPath().normalize();
        if (!path.startsWith(root) || path.equals(root) || !file.isFile()) {
            throw new IllegalArgumentException(file + " is not a file inside " + projectRoot
                    + ", so it is not a file of this project's release.");
        }
        return root.relativize(path).toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------ git

    /** The git commands that record a release, where the commit goes, and what it leaves out. */
    public static final class Staging {
        /** What git says in place of a branch's name when none is checked out. */
        private static final String NO_BRANCH = "HEAD (no branch)";
        /** What it says before the name of a branch that has no commit yet. */
        private static final String UNBORN = "No commits yet on ";

        private final List<List<String>> commands;
        private final List<String> leftOut;
        private final String head;
        private final List<String> committedFirst;

        Staging(List<List<String>> commands, List<String> leftOut, String head,
                List<String> committedFirst) {
            this.commands = Collections.unmodifiableList(commands);
            this.leftOut = Collections.unmodifiableList(leftOut);
            this.head = head == null ? "" : head.trim();
            this.committedFirst = Collections.unmodifiableList(
                    new ArrayList<String>(committedFirst));
        }

        /** The project's new files, committed before the release in a commit of their own. */
        public List<String> committedFirst() {
            return committedFirst;
        }

        /** The commands, in the order they are run, each from the project's root. */
        public List<List<String>> commands() {
            return commands;
        }

        /**
         * The files of ODK's scratch folders that git tracks and cleaning removed, which the
         * first command puts back; empty when there are none.
         */
        public List<String> putBack() {
            List<String> first = commands.get(0);
            return "checkout".equals(first.get(1)) ? first.subList(3, first.size())
                    : Collections.<String>emptyList();
        }

        /** Files git has never heard of, which the release's commit does not take in. */
        public List<String> leftOut() {
            return leftOut;
        }

        /**
         * The branch the commit goes to: the one checked out. Null when git did not say, and
         * when there is none - see {@link #onNoBranch()}.
         */
        public String branch() {
            if (head.isEmpty() || onNoBranch()) {
                return null;
            }
            return head.startsWith(UNBORN) ? head.substring(UNBORN.length()) : head;
        }

        /**
         * Whether no branch is checked out - a detached HEAD. The commit is then on no branch,
         * and only its tag leads back to it.
         */
        public boolean onNoBranch() {
            return head.equals(NO_BRANCH);
        }

        /** The commands as text, one a line, as they would be typed into a terminal. */
        public String shown() {
            StringBuilder text = new StringBuilder();
            for (List<String> command : commands) {
                text.append(text.length() == 0 ? "" : "\n").append(ReleaseRun.shown(command));
            }
            return text.toString();
        }
    }

    /**
     * A command as one line for a transcript, quoted the way a POSIX shell reads it back: an
     * argument with a blank, a {@code $} or a quote in it stands between double quotes.
     */
    public static String shown(List<String> command) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            String argument = command.get(i);
            text.append(i == 0 ? "" : " ").append(
                    argument.matches("[A-Za-z0-9_./:=@%+,-]+") ? argument
                            : "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"")
                                    .replace("$", "\\$").replace("`", "\\`") + "\"");
        }
        return text.toString();
    }

    /**
     * The commit and the tag of a release.
     *
     * <p>{@code git add -u} takes in the tracked files the release changed. The release files
     * themselves are added by name with {@code -f}: ODK's {@code .gitignore} keeps them out,
     * and this is ODK's own documented way to commit them. The copy under
     * {@code releases/<version>/} is added when there is one. What git has never tracked is not
     * swept in; it is listed.
     *
     * @param assets     the release files at the project's root
     * @param gitStatus  the lines of {@code git status --porcelain=v1 --branch}
     *                   ({@link GitRepo#statusCommand()}), taken before
     */
    public static Staging staging(File projectRoot, String ontologyId, String version,
            List<File> assets, List<String> gitStatus) {
        return staging(projectRoot, projectRoot, ontologyId, version, assets, gitStatus);
    }

    /** ODK's scratch folders, which its {@code clean} empties: nothing in them is a release's. */
    private static final String[] SCRATCH = {"src/ontology/tmp/", "src/ontology/mirror/",
        "src/ontology/target/"};

    /**
     * New files as a sentence says them, after "the project's": files counted, and a folder
     * git lists whole - all of it new - as a folder, not as one file.
     */
    public static String described(List<String> entries) {
        int folders = 0;
        for (String entry : entries) {
            folders += entry.endsWith("/") ? 1 : 0;
        }
        int files = entries.size() - folders;
        String filesSaid = files + " new file" + (files == 1 ? "" : "s");
        if (folders == 0) {
            return filesSaid;
        }
        if (files == 0) {
            return folders == 1 ? "new folder" : folders + " new folders";
        }
        return filesSaid + " and " + folders + " new folder" + (folders == 1 ? "" : "s");
    }

    /**
     * Of the files the box named, those git still lists as new: listed as they are, or inside a
     * folder git now lists whole - the file then, not the folder, which may hold more.
     */
    static List<String> stillNew(List<String> named, List<String> newNow) {
        List<String> still = new ArrayList<String>();
        for (String path : named) {
            for (String listed : newNow) {
                if (path.equals(listed) || (listed.endsWith("/") && path.startsWith(listed))) {
                    still.add(path);
                    break;
                }
            }
        }
        return still;
    }

    /** The message of the commit that takes a project's new files in before its release. */
    public static String newFilesMessage(String version) {
        return "Files of the project before release " + version;
    }

    /**
     * The files of the project git has never been told about, as the project names them; a
     * folder git lists whole is one entry. Not what is elsewhere in the repository.
     *
     * @param repositoryRoot the top of the repository: the project's root, a folder above it,
     *                       or null where it is not known
     */
    public static List<String> untrackedOfTheProject(File projectRoot, File repositoryRoot,
            GitRepo.Status status) {
        List<String> found = new ArrayList<String>();
        if (status == null) {
            return found;
        }
        String prefix = prefixOf(projectRoot, repositoryRoot);
        for (String listed : status.getUntracked()) {
            String path = inProject(listed, prefix);
            if (path != null) {
                found.add(path);
            }
        }
        return found;
    }

    /**
     * The project's new files: what git has never been told about under src/ - an import
     * module and its term list, a test, the board's layout - and none of ODK's scratch
     * folders'. A stray file elsewhere in the project is not one of them.
     */
    public static List<String> newFilesOfTheProject(File projectRoot, File repositoryRoot,
            GitRepo.Status status) {
        List<String> found = new ArrayList<String>();
        for (String path : untrackedOfTheProject(projectRoot, repositoryRoot, status)) {
            if (path.startsWith("src/") && !isScratch(path)) {
                found.add(path);
            }
        }
        return found;
    }

    /** As {@link #newFilesOfTheProject(File, File, GitRepo.Status)}, from git's status. */
    public static List<String> newFilesOfTheProject(File projectRoot, File repositoryRoot,
            List<String> gitStatus) {
        return newFilesOfTheProject(projectRoot, repositoryRoot, GitRepo.parseStatus(
                gitStatus == null ? Collections.<String>emptyList() : gitStatus));
    }

    /** As {@link #staging(File, File, String, String, List, List, boolean)}, as 1.153.0 did. */
    public static Staging staging(File projectRoot, File repositoryRoot, String ontologyId,
            String version, List<File> assets, List<String> gitStatus) {
        return staging(projectRoot, repositoryRoot, ontologyId, version, assets, gitStatus,
                false);
    }

    /**
     * @param commitNewFirst whether the project's new files ({@link #newFilesOfTheProject})
     *                       are committed first, in a commit of their own, so that the release's
     *                       tag holds them; else they are left out, as 1.153.0 did
     */
    public static Staging staging(File projectRoot, File repositoryRoot, String ontologyId,
            String version, List<File> assets, List<String> gitStatus, boolean commitNewFirst) {
        return staging(projectRoot, repositoryRoot, ontologyId, version, assets, gitStatus,
                commitNewFirst, null);
    }

    /**
     * The commit and the tag of a release of a project that may be a folder of a larger
     * repository.
     *
     * <p>Everything is said from the project's root, where the commands run: {@code git add
     * -u -- .} takes in the tracked files of the project the release changed - without the
     * path it would take the whole repository, somebody's unrelated work with it - and git's
     * status, which names files from the repository's top, is read back into the project's
     * own names.
     *
     * <p>A release cleans first (ODK's {@code make clean}), which empties ODK's scratch
     * folders. A file there that git tracks - {@code src/ontology/tmp/README.md}, which
     * older ODKs seeded - would be staged as deleted and go into the release's commit; it is
     * put back first, by a command that is shown with the others.
     *
     * <p>Asked to, the new files the form's box named - from git's status before the build,
     * those of them that are new still - are committed first. What the release's own build
     * left new under src/ (a format ODK's .gitignore does not cover) is said as left out, not
     * swept into that commit (the 1.154.0 review's M1).
     *
     * @param repositoryRoot the top of the repository the project is in: the project's root,
     *                       or a folder above it
     * @param named          the files the box named; null for the project's new files as git's
     *                       status after the build lists them
     */
    public static Staging staging(File projectRoot, File repositoryRoot, String ontologyId,
            String version, List<File> assets, List<String> gitStatus, boolean commitNewFirst,
            List<String> named) {
        return staging(projectRoot, repositoryRoot, ontologyId, version, assets, gitStatus,
                commitNewFirst, named, null);
    }

    /**
     * As {@link #staging(File, File, String, String, List, List, boolean, List)}, the tag's
     * message read from a file of the release - its record (1.155.0) - when one is given.
     *
     * @param tagMessageFile from the project's top, as git in it reads it; null for
     *                       "{@code <id> <version>}"
     */
    public static Staging staging(File projectRoot, File repositoryRoot, String ontologyId,
            String version, List<File> assets, List<String> gitStatus, boolean commitNewFirst,
            List<String> named, String tagMessageFile) {
        List<String> names = new ArrayList<String>();
        for (File asset : assets) {
            names.add(relative(projectRoot, asset));
        }
        String kept = "releases/" + version;
        boolean hasCopy = new File(projectRoot, kept).isDirectory();
        String prefix = prefixOf(projectRoot, repositoryRoot);

        List<List<String>> commands = new ArrayList<List<String>>();
        List<String> putBack = new ArrayList<String>(Arrays.asList("git", "checkout", "--"));
        for (String line : gitStatus == null ? Collections.<String>emptyList() : gitStatus) {
            // " D": gone from the folder and not staged as gone, which is what cleaning
            // does to a file git tracks. A name git had to quote is left as it is.
            if (line != null && line.length() > 3 && line.startsWith(" D ")
                    && line.charAt(3) != '"') {
                String path = inProject(line.substring(3).trim(), prefix);
                if (path != null && isScratch(path)) {
                    putBack.add(path);
                }
            }
        }
        if (putBack.size() > 3) {
            commands.add(putBack);
        }
        List<String> newNow = newFilesOfTheProject(projectRoot, repositoryRoot, gitStatus);
        List<String> first = !commitNewFirst ? Collections.<String>emptyList()
                : named == null ? newNow : stillNew(named, newNow);
        if (!first.isEmpty()) {
            List<String> addFirst = new ArrayList<String>(Arrays.asList("git", "add", "--"));
            addFirst.addAll(first);
            commands.add(addFirst);
            commands.add(Arrays.asList("git", "commit", "-m", newFilesMessage(version)));
        }
        commands.add(Arrays.asList("git", "add", "-u", "--", "."));
        List<String> add = new ArrayList<String>(Arrays.asList("git", "add", "-f", "--"));
        add.addAll(names);
        commands.add(add);
        if (hasCopy) {
            commands.add(Arrays.asList("git", "add", "--", kept));
        }
        commands.add(Arrays.asList("git", "commit", "-m", "release(" + version + ")"));
        commands.add(tagMessageFile == null
                ? Arrays.asList("git", "tag", "-a", GitTags.tagFor(version), "-m",
                        ontologyId + " " + version)
                : Arrays.asList("git", "tag", "-a", GitTags.tagFor(version), "-F",
                        tagMessageFile));

        GitRepo.Status status = GitRepo.parseStatus(gitStatus);
        List<String> leftOut = new ArrayList<String>();
        for (String listed : status.getUntracked()) {
            String path = inProject(listed, prefix);
            if (path == null) {
                // Elsewhere in the repository: not the release's to speak of.
                continue;
            }
            String folder = path.endsWith("/") ? path : path + "/";
            boolean added = names.contains(path) || (hasCopy && (kept + "/").startsWith(folder))
                    || first.contains(path);
            if (!added) {
                leftOut.add(path);
            }
        }
        return new Staging(commands, leftOut, status.getBranch(), first);
    }

    /** The project's path from the repository's top, with a slash after it; empty at the top. */
    private static String prefixOf(File projectRoot, File repositoryRoot) {
        if (repositoryRoot == null) {
            return "";
        }
        Path top = repositoryRoot.getAbsoluteFile().toPath().normalize();
        Path root = projectRoot.getAbsoluteFile().toPath().normalize();
        if (root.equals(top) || !root.startsWith(top)) {
            return "";
        }
        return top.relativize(root).toString().replace(File.separatorChar, '/') + "/";
    }

    /**
     * A path as git's status names it, as the project names it; null when it is elsewhere
     * in the repository. A folder above the project - all of it untracked - keeps the name
     * git gave it.
     */
    private static String inProject(String listed, String prefix) {
        if (prefix.isEmpty()) {
            return listed;
        }
        if (listed.startsWith(prefix)) {
            return listed.substring(prefix.length());
        }
        return listed.endsWith("/") && prefix.startsWith(listed) ? listed : null;
    }

    private static boolean isScratch(String path) {
        for (String folder : SCRATCH) {
            if (path.startsWith(folder)) {
                return true;
            }
        }
        return false;
    }
}
