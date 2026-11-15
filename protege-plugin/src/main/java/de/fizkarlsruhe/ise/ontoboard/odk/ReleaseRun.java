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
     * What to run for a release: {@code make prepare_release -B VERSION=...}.
     *
     * <p>{@code -B} - every target is made again - is how ODK's own instructions release
     * ({@code sh run.sh make prepare_release -B}), and it is no nicety: ODK's {@code test}
     * builds the ontology it validates, the very file a release puts at the top of the
     * project, and stamps it with the day. Without -B a release made after a test finds that
     * file up to date and takes it for its own - a release numbered 1.0.0 then carries the
     * day as its version IRI.
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
        if (version == null || OntologyVersion.parseOrNull(version) == null) {
            throw new IllegalArgumentException("'" + version + "' is not a version: a date such "
                    + "as 2026-11-11, or numbers such as 1.0.0.");
        }
        List<String> arguments = new ArrayList<String>(Arrays.asList("make",
                refreshImports ? "prepare_release" : "prepare_release_fast", "-B",
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
         * A shell inside the container reads the script: with Windows line endings it reports
         * "not found" for every command, which names the wrong thing.
         */
        public boolean hasWindowsLineEndings() {
            return text.contains("\r\n");
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

    /** The project's {@code release.sh}, or null when it has none. */
    public static Script scriptIn(File ontologyDirectory) {
        File file = new File(ontologyDirectory, "release.sh");
        if (!file.isFile()) {
            return null;
        }
        try {
            return new Script(file,
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
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
        return there == null ? null : "Version " + version + " has been released: " + there
                + ". A version IRI cannot be taken back once somebody has imported it, so the "
                + "next release gets the next number.";
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
        List<File> copies = new ArrayList<File>();
        for (int i = 0; i < assets.size(); i++) {
            File copy = new File(folder, paths.get(i).replace('/', File.separatorChar));
            Files.createDirectories(copy.getParentFile().toPath());
            Files.copy(assets.get(i).toPath(), copy.toPath());
            copies.add(copy);
        }
        return copies;
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

        Staging(List<List<String>> commands, List<String> leftOut, String head) {
            this.commands = Collections.unmodifiableList(commands);
            this.leftOut = Collections.unmodifiableList(leftOut);
            this.head = head == null ? "" : head.trim();
        }

        /** The commands, in the order they are run, each from the project's root. */
        public List<List<String>> commands() {
            return commands;
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
        List<String> names = new ArrayList<String>();
        for (File asset : assets) {
            names.add(relative(projectRoot, asset));
        }
        String kept = "releases/" + version;
        boolean hasCopy = new File(projectRoot, kept).isDirectory();

        List<List<String>> commands = new ArrayList<List<String>>();
        commands.add(Arrays.asList("git", "add", "-u"));
        List<String> add = new ArrayList<String>(Arrays.asList("git", "add", "-f", "--"));
        add.addAll(names);
        commands.add(add);
        if (hasCopy) {
            commands.add(Arrays.asList("git", "add", "--", kept));
        }
        commands.add(Arrays.asList("git", "commit", "-m", "release(" + version + ")"));
        commands.add(Arrays.asList("git", "tag", "-a", GitTags.tagFor(version), "-m",
                ontologyId + " " + version));

        GitRepo.Status status = GitRepo.parseStatus(gitStatus);
        List<String> leftOut = new ArrayList<String>();
        for (String path : status.getUntracked()) {
            String folder = path.endsWith("/") ? path : path + "/";
            boolean added = names.contains(path) || (hasCopy && (kept + "/").startsWith(folder));
            if (!added) {
                leftOut.add(path);
            }
        }
        return new Staging(commands, leftOut, status.getBranch());
    }
}
