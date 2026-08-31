package de.fizkarlsruhe.ise.ontoboard.git;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
        private final boolean hasUpstream;

        Status(String branch, int ahead, int behind, List<String> changed,
                List<String> untracked, boolean hasUpstream) {
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

        /** False for a branch that has never been pushed, which needs a different push command. */
        public boolean hasUpstream() {
            return hasUpstream;
        }

        public boolean isClean() {
            return changed.isEmpty() && untracked.isEmpty();
        }

        @Override
        public String toString() {
            return branch + (ahead > 0 ? " +" + ahead : "") + (behind > 0 ? " -" + behind : "")
                    + ", " + changed.size() + " changed, " + untracked.size() + " new";
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
            String path = line.substring(3).trim();
            if ("??".equals(code)) {
                untracked.add(path);
            } else {
                changed.add(path);
            }
        }
        return new Status(branch, ahead, behind, changed, untracked, hasUpstream);
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
