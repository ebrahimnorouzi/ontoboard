package de.fizkarlsruhe.ise.ontoboard.git;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The everyday git commands, for a project this plugin opened.
 *
 * <p>Cloning was the whole of the git integration, which made the checkout a read-only curiosity:
 * everything the plugin can now do to an ODK project - mint a term, allocate an ID range, write an
 * import module and its catalog entry, cut a release - produces changes that a user then had to
 * leave Protege to commit. An ontology editor that can change a repository and not record the
 * change is one you have to babysit from a terminal anyway.
 *
 * <p>Only the safe half of git is here. Status, commit, pull with fast-forward only, push, and
 * making a branch. No merge, no rebase, no reset, no force: those need a conflict resolved or a
 * history rewritten, neither of which a menu item should start on somebody's behalf, and both of
 * which are better done where the user can see what they are doing.
 *
 * <p>Pure command construction and output reading - no process launching, no Protege types, no
 * Swing - so the decisions are testable without a repository.
 */
public final class GitRepo {

    /** Long enough for a push over a slow line, short enough not to hold a thread all session. */
    public static final long TIMEOUT_MINUTES = 5;

    private GitRepo() {
    }

    // ---------------------------------------------------------------- commands

    /** {@code --porcelain} because the human-readable format changes between git versions. */
    public static List<String> statusCommand() {
        return Arrays.asList("git", "status", "--porcelain=v1", "--branch");
    }

    /**
     * The three versions git keeps while a merge is unresolved.
     *
     * <p>A conflicted file is not one file. Git holds three: stage 1 is the common ancestor,
     * stage 2 is what this branch says, stage 3 is what the other one says. The working copy is a
     * fourth thing, and the only one with conflict markers in it - which is why reading the
     * working copy is the wrong way to find out what two people disagreed about, and why
     * resolving an {@code .owl} in a text diff is a losing game: the markers make it unparseable,
     * so the moment a conflict exists the file stops being an ontology.
     *
     * <p>These three are each a clean, parseable blob. Compare them as ontologies and the
     * question becomes "which terms did both sides change", which is a question a curator can
     * answer.
     *
     * @param stage 1 for the ancestor, 2 for ours, 3 for theirs
     */
    public static List<String> stageCommand(int stage, String path) {
        if (stage < 1 || stage > 3) {
            throw new IllegalArgumentException(
                    "a conflicted file has stages 1 (ancestor), 2 (ours) and 3 (theirs), not "
                            + stage);
        }
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("which file?");
        }
        return Arrays.asList("git", "show", ":" + stage + ":" + path.trim());
    }

    /** What each stage is called where a person will read it. */
    public static String stageName(int stage) {
        switch (stage) {
            case 1:
                return "the common ancestor";
            case 2:
                return "this branch";
            case 3:
                return "the other branch";
            default:
                return "stage " + stage;
        }
    }

    /**
     * Asks which versions git kept of one conflicted file: {@code git ls-files -u}.
     *
     * <p>MEASURED on real merges, because which stages exist IS the shape of the conflict: an
     * ordinary content clash keeps 1, 2 and 3 ({@code UU}); a file one side deleted keeps 1 and 3
     * or 1 and 2 ({@code DU}, {@code UD}); a file both sides created keeps 2 and 3 and has no
     * ancestor at all ({@code AA}). A file both sides deleted never arrives - git resolves that
     * silently and the file is absent from the status entirely.
     *
     * <p>Worth one extra git call, because the alternative is inferring the shape from which
     * versions could be PARSED - and a stage git has but the OWL API cannot read is not a
     * deletion. Reporting it as one would be a lie about the repository, told confidently.
     */
    public static List<String> unmergedStagesCommand(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("which file?");
        }
        return Arrays.asList("git", "ls-files", "-u", "--", path.trim());
    }

    /**
     * The stage numbers {@code git ls-files -u} listed.
     *
     * <p>Each line is {@code <mode> <sha> <stage>} then a tab then the path, so the stage is read
     * as the last field before that tab. Not by splitting the whole line on whitespace: a path
     * may contain spaces, and a reader that wandered into the path would answer with nonsense.
     */
    public static Set<Integer> parseUnmergedStages(List<String> output) {
        Set<Integer> stages = new TreeSet<Integer>();
        if (output == null) {
            return stages;
        }
        for (String line : output) {
            int tab = line.indexOf(9);
            if (tab < 1) {
                continue;
            }
            String fields = line.substring(0, tab).trim();
            int lastSpace = fields.lastIndexOf(32);
            if (lastSpace < 0) {
                continue;
            }
            try {
                int stage = Integer.parseInt(fields.substring(lastSpace + 1));
                if (stage >= 1 && stage <= 3) {
                    stages.add(Integer.valueOf(stage));
                }
            } catch (NumberFormatException notAStage) {   // NOPMD - git said something else
                continue;
            }
        }
        return stages;
    }

    /**
     * Commits everything tracked and everything new.
     *
     * <p>{@code -A} rather than only tracked files, because a new import module, a new release
     * directory and a first catalog are exactly the things this plugin produces, and a commit that
     * silently left them out would look like it worked.
     */
    public static List<String> commitCommand(String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("a commit needs a message");
        }
        return Arrays.asList("git", "commit", "-m", message.trim());
    }

    /** Stages everything first; git has no single command for "commit what I see". */
    public static List<String> stageCommand() {
        return Arrays.asList("git", "add", "-A");
    }

    /**
     * {@code --ff-only}, always.
     *
     * <p>A merge or a rebase here can conflict, and resolving an OWL conflict is not something a
     * menu item should begin without being asked. Refusing and saying so leaves a checkout the
     * user still recognises.
     */
    public static List<String> pullCommand() {
        return Arrays.asList("git", "pull", "--ff-only");
    }

    public static List<String> pushCommand() {
        return Arrays.asList("git", "push");
    }

    /** Pushes a branch that has no upstream yet, which a newly made one never has. */
    public static List<String> pushNewBranchCommand(String branch) {
        return Arrays.asList("git", "push", "--set-upstream", "origin", branch);
    }

    public static List<String> createBranchCommand(String name) {
        String branch = name == null ? "" : name.trim();
        if (branch.isEmpty()) {
            throw new IllegalArgumentException("a branch needs a name");
        }
        return Arrays.asList("git", "checkout", "-b", branch);
    }

    // ---------------------------------------------------------------- reading status

    /** What the working copy looks like right now. */
    public static final class Status {
        private final String branch;
        private final int ahead;
        private final int behind;
        private final List<String> changed;
        private final List<String> untracked;
        private final List<String> conflicted;
        private final boolean hasUpstream;

        Status(String branch, int ahead, int behind, List<String> changed,
                List<String> untracked, boolean hasUpstream) {
            this(branch, ahead, behind, changed, untracked,
                    Collections.<String>emptyList(), hasUpstream);
        }

        Status(String branch, int ahead, int behind, List<String> changed,
                List<String> untracked, List<String> conflicted, boolean hasUpstream) {
            this.conflicted = Collections.unmodifiableList(conflicted);
            this.branch = branch;
            this.ahead = ahead;
            this.behind = behind;
            this.changed = Collections.unmodifiableList(changed);
            this.untracked = Collections.unmodifiableList(untracked);
            this.hasUpstream = hasUpstream;
        }

        public String getBranch() {
            return branch;
        }

        /** Commits made here that the remote does not have. */
        public int getAhead() {
            return ahead;
        }

        /** Commits on the remote that are not here - the ones a pull would bring. */
        public int getBehind() {
            return behind;
        }

        /** Tracked files that differ from the last commit. */
        public List<String> getChanged() {
            return changed;
        }

        /** Files git has never been told about - a new import module, before it is added. */
        public List<String> getUntracked() {
            return untracked;
        }

        /**
         * Files in an unmerged state: a merge or a rebase stopped here and left both sides.
         *
         * <p><b>Why this had to exist.</b> The parser had two buckets, {@code ??} and
         * everything else, so {@code UU ontology.owl} - the code git uses for "both sides
         * modified this" - read as an ordinary changed file. The commit path then did what it
         * does for a changed file: {@code git add -A} followed by {@code git commit}, which
         * exits 0 and records a blob containing {@code &lt;&lt;&lt;&lt;&lt;&lt;&lt; HEAD}. The plugin reported
         * "Committed 1 files." Reproduced on a real conflicted tree before this was written.
         *
         * <p>An .owl full of conflict markers does not parse, so the next person to open it
         * loses the ontology, not a line of text - and the commit that did it says it
         * succeeded.
         */
        public List<String> getConflicted() {
            return conflicted;
        }

        /** True while a merge or rebase is unfinished. Committing now would record markers. */
        public boolean hasConflicts() {
            return !conflicted.isEmpty();
        }

        /** False for a branch that has never been pushed, which needs a different push command. */
        public boolean hasUpstream() {
            return hasUpstream;
        }

        public boolean isClean() {
            return changed.isEmpty() && untracked.isEmpty() && conflicted.isEmpty();
        }

        @Override
        public String toString() {
            return branch + (ahead > 0 ? " +" + ahead : "") + (behind > 0 ? " -" + behind : "")
                    + ", " + changed.size() + " changed, " + untracked.size() + " new"
                    + (conflicted.isEmpty() ? "" : ", " + conflicted.size() + " conflicted");
        }
    }

    /**
     * Reads {@code git status --porcelain=v1 --branch}.
     *
     * <p>The porcelain format is the one git promises not to change between versions; the
     * human-readable one is explicitly not, and parsing that is how a tool breaks on somebody
     * else's machine a year later.
     */
    public static Status parseStatus(List<String> output) {
        String branch = "";
        int ahead = 0;
        int behind = 0;
        boolean hasUpstream = false;
        List<String> changed = new ArrayList<String>();
        List<String> untracked = new ArrayList<String>();
        List<String> conflicted = new ArrayList<String>();

        for (String raw : output == null ? Collections.<String>emptyList() : output) {
            String line = raw == null ? "" : raw;
            if (line.startsWith("## ")) {
                String header = line.substring(3);
                int bracket = header.indexOf(" [");
                String names = bracket >= 0 ? header.substring(0, bracket) : header;
                int arrow = names.indexOf("...");
                branch = arrow >= 0 ? names.substring(0, arrow) : names;
                hasUpstream = arrow >= 0;
                if (bracket >= 0) {
                    ahead = numberAfter(header, "ahead ");
                    behind = numberAfter(header, "behind ");
                }
                continue;
            }
            if (line.length() < 4) {
                continue;
            }
            String code = line.substring(0, 2);
            String path = unquotePath(line.substring(3).trim());
            if ("??".equals(code)) {
                untracked.add(path);
            } else if (isUnmerged(code)) {
                conflicted.add(path);
            } else {
                changed.add(path);
            }
        }
        return new Status(branch, ahead, behind, changed, untracked, conflicted, hasUpstream);
    }

    /**
     * The real path, when {@code git status --porcelain} quoted it.
     *
     * <p>MEASURED: a conflicted {@code src/my ontology.owl} is printed as
     * {@code UU "src/my ontology.owl"} - quoted for nothing more exotic than the space - while
     * {@code git ls-files -u} prints the same path bare. Keeping the quotes was not a cosmetic
     * slip: the quoted form ends in {@code "} rather than {@code .owl}, so the conflict report
     * decided the file was not an ontology and said NOTHING about it, and {@code git show} was
     * handed a path with quotes in it. A project whose directories have spaces in them, or a
     * German or Turkish term in a filename, got silence.
     *
     * <p>git's quoting is C-style: the path is wrapped in double quotes, backslash escapes the
     * usual characters, and a non-ASCII byte becomes a three-digit octal escape. The octal
     * escapes are bytes of UTF-8, so they are collected and decoded together rather than one at
     * a time - decoding each separately would turn a German umlaut into two broken characters.
     */
    public static String unquotePath(String path) {
        if (path == null || path.length() < 2 || path.charAt(0) != '"'
                || path.charAt(path.length() - 1) != '"') {
            return path;
        }
        String inside = path.substring(1, path.length() - 1);
        StringBuilder out = new StringBuilder();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int at = 0; at < inside.length(); at++) {
            char here = inside.charAt(at);
            if (here == '\\' && at + 1 < inside.length()) {
                char next = inside.charAt(at + 1);
                if (next >= '0' && next <= '7' && at + 3 < inside.length()) {
                    try {
                        bytes.write(Integer.parseInt(inside.substring(at + 1, at + 4), 8));
                        at += 3;
                        continue;
                    } catch (NumberFormatException notOctal) {   // NOPMD - then it is literal
                        // falls through to the single-character escapes
                    }
                }
                flush(bytes, out);
                at++;
                switch (next) {
                    case 't': out.append('\t'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    default: out.append(next); break;
                }
                continue;
            }
            flush(bytes, out);
            out.append(here);
        }
        flush(bytes, out);
        return out.toString();
    }

    /** Decodes the octal bytes gathered so far as the UTF-8 they are. */
    private static void flush(java.io.ByteArrayOutputStream bytes, StringBuilder out) {
        if (bytes.size() == 0) {
            return;
        }
        try {
            out.append(new String(bytes.toByteArray(), "UTF-8"));
        } catch (java.io.UnsupportedEncodingException everyJvmHasUtf8) {
            out.append(new String(bytes.toByteArray()));
        }
        bytes.reset();
    }

    /**
     * Whether a porcelain code means "a merge left both sides here".
     *
     * <p>The seven git documents in git-status(1) under "Unmerged": both-modified, both-added,
     * both-deleted, and the four one-sided ones. Listed rather than inferred from the presence
     * of a U, because DD has no U in it and would be missed.
     */
    private static boolean isUnmerged(String code) {
        return "UU".equals(code) || "AA".equals(code) || "DD".equals(code)
                || "AU".equals(code) || "UA".equals(code)
                || "DU".equals(code) || "UD".equals(code);
    }

    private static int numberAfter(String text, String marker) {
        int at = text.indexOf(marker);
        if (at < 0) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = at + marker.length(); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            digits.append(c);
        }
        return digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
    }

    // ---------------------------------------------------------------- failures

    /**
     * What went wrong, in terms a user can act on.
     *
     * <p>git's messages assume you already know git. The ones worth translating are the ones an
     * ontology editor hits: nothing to commit, a push rejected because somebody else pushed
     * first, a pull that cannot fast-forward, and no configured identity - which stops a commit
     * dead and has nothing to do with the repository.
     */
    public static String explain(String operation, int exitCode, List<String> output) {
        if (exitCode == 0) {
            return null;
        }
        String text = joined(output).toLowerCase(Locale.ROOT);
        if (text.contains("nothing to commit")) {
            return "Nothing to commit - the working copy already matches the last commit. If you "
                    + "expected changes, check that Protege has saved the ontology: an unsaved "
                    + "edit exists only in Protege's memory and git cannot see it.";
        }
        if (text.contains("please tell me who you are")
                || text.contains("empty ident name")
                || text.contains("unable to auto-detect email address")) {
            return "git does not know who you are, so it will not make a commit. Set it once, in "
                    + "a terminal: git config --global user.name \"Your Name\" and "
                    + "git config --global user.email you@example.org";
        }
        if (text.contains("failed to push") || text.contains("rejected")
                || text.contains("non-fast-forward")) {
            return "The push was rejected because the remote has commits you do not. Pull first - "
                    + "if the pull will not fast-forward either, the two histories have diverged "
                    + "and that is worth sorting out in a terminal where you can see both.";
        }
        if (text.contains("not possible to fast-forward")
                || text.contains("divergent branches")) {
            return "The pull cannot fast-forward: there are commits here and commits there. "
                    + "Merging or rebasing an ontology is not something this should start on your "
                    + "behalf - do it in a terminal, where you can see what conflicts.";
        }
        if (text.contains("no upstream branch") || text.contains("has no upstream")) {
            return "This branch has never been pushed, so git does not know where it belongs. "
                    + "Push it with 'Publish this branch', which sets the upstream.";
        }
        if (text.contains("could not read username")
                || text.contains("authentication failed")
                || text.contains("permission denied (publickey)")) {
            return "GitHub refused the credentials. Nothing here can prompt you for a password - "
                    + "push once from a terminal so your credential helper stores what it needs.";
        }
        if (text.contains("not a git repository")) {
            return "This project is not a git checkout, so there is nothing to " + operation + ".";
        }
        String tail = lastMeaningful(output);
        return "git " + operation + " failed (exit " + exitCode + ")"
                + (tail.isEmpty() ? "." : ": " + tail);
    }

    /**
     * Why committing now would record less than the user thinks, or null.
     *
     * <p>The trap that costs real work: Protege holds edits in memory, git reads the disk, and a
     * commit made with unsaved changes records the file as it was. Nothing about the result would
     * say so - the commit succeeds, and the work is simply not in it.
     */
    public static String unsavedWarning(boolean ontologyIsDirty) {
        if (!ontologyIsDirty) {
            return null;
        }
        return "This ontology has unsaved changes. git commits what is on disk, so anything you "
                + "have not saved will not be in this commit - save first, or the commit will "
                + "record the file as it was.";
    }

    private static String joined(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines == null ? Collections.<String>emptyList() : lines) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    private static String lastMeaningful(List<String> output) {
        if (output == null) {
            return "";
        }
        for (int i = output.size() - 1; i >= 0; i--) {
            String line = output.get(i).trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "";
    }

    /** The repository root above a file, or null when it is not in a checkout. */
    public static File repositoryFor(File somewhereInside) {
        for (File directory = somewhereInside == null ? null : somewhereInside.getParentFile();
                directory != null; directory = directory.getParentFile()) {
            if (new File(directory, ".git").exists()) {
                return directory;
            }
        }
        return null;
    }
}
