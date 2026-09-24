package de.fizkarlsruhe.ise.ontoboard.git;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Getting a repository onto the machine, by running git.
 *
 * <p>Running git rather than embedding a Java implementation, deliberately. An ODK repository is a
 * working copy someone will commit and push from, and it has to be the same working copy their
 * shell, their IDE and their CI see - with their credential helper, their SSH agent, their proxy
 * settings and their {@code .gitconfig}. A separate Java Git implementation would produce a
 * checkout that looks right and then cannot push, for reasons that live in configuration this
 * plugin cannot see.
 *
 * <p>The parts that decide anything - the command line, what a failure means, where the checkout
 * goes - are separated from launching the process, so they are testable without git and without
 * the network. The failure messages are most of the value: git's own are written for people who
 * already know git, and "Repository not found" is what it says for a private repository, a typo,
 * and a repository that was deleted.
 */
public final class GitClone {

    /** Long enough for a large ODK repository on a slow line; short enough not to hang forever. */
    static final long TIMEOUT_MINUTES = 10;

    /** The result of running a command. */
    public static final class Outcome {
        private final int exitCode;
        private final String output;

        public Outcome(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        public int getExitCode() {
            return exitCode;
        }

        /** stdout and stderr together, as git interleaves progress across both. */
        public String getOutput() {
            return output;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /** How a command gets run. Replaced in tests, so the decisions can be checked without git. */
    public interface Runner {
        Outcome run(File workingDirectory, List<String> command) throws IOException;
    }

    private GitClone() {
    }

    /**
     * The clone command - always the default branch.
     *
     * <p>No {@code --branch}, even when the link named one. A GitHub URL gives no way to tell
     * where a ref ends and a path begins, so the ref taken from the link is a guess, and a wrong
     * guess passed to {@code git clone} fails with "Remote branch 'feature' not found" - naming a
     * branch the user never typed, about a repository where {@code feature/x} exists and is
     * exactly what they asked for. Cloning first and asking the repository which branches it has
     * turns the guess into a fact. See {@link #remoteBranchesCommand()}.
     */
    public static List<String> cloneCommand(GitHubUrl url, File into) {
        List<String> command = new ArrayList<String>(
                Arrays.asList("git", "clone", "--progress"));
        command.add(url.getCloneUrl());
        command.add(into.getAbsolutePath());
        return command;
    }

    /** Lists the branches the clone actually has, so a slash-bearing ref can be resolved. */
    public static List<String> remoteBranchesCommand() {
        return Arrays.asList("git", "branch", "-r", "--format=%(refname:short)");
    }

    /** Switches the checkout to {@code ref}. */
    public static List<String> checkoutCommand(String ref) {
        return Arrays.asList("git", "checkout", ref);
    }

    /**
     * Branch names from {@code git branch -r}, without the remote prefix.
     *
     * <p>{@code origin/HEAD -> origin/main} is a symbolic ref, not a branch, and offering it as
     * one would let a link resolve to a "branch" no checkout can be made of.
     */
    public static List<String> remoteBranches(String output) {
        List<String> branches = new ArrayList<String>();
        if (output == null) {
            return branches;
        }
        for (String line : output.split("\r?\n")) {
            String name = line.trim();
            if (name.isEmpty() || name.contains("->")) {
                continue;
            }
            int slash = name.indexOf('/');
            if (slash > 0) {
                name = name.substring(slash + 1);
            }
            if (!name.isEmpty() && !branches.contains(name)) {
                branches.add(name);
            }
        }
        return branches;
    }

    /** Where a fresh checkout goes: a directory named after the repository, under {@code parent}. */
    public static File directoryFor(File parent, GitHubUrl url) {
        return new File(parent, url.getRepository());
    }

    /**
     * Whether {@code directory} is already a checkout of this repository.
     *
     * <p>By the <b>origin</b> remote specifically, not by anything that appears in the config.
     * The standard OBO contribution workflow is to clone your own fork and add the upstream
     * repository as a second remote, so a config containing
     * {@code [remote "upstream"] url = .../ISE-FIZKarlsruhe/mwo} is entirely ordinary - and a
     * substring search over the whole file calls that a checkout of upstream. The user is then
     * told "already cloned", their fork is pulled and opened, and the result names a repository
     * they are not on. They edit and push believing otherwise. Submodule URLs do the same thing.
     *
     * <p>The match also has to end at the repository name, or {@code owner/mwo} matches
     * {@code owner/mwo-tools}.
     */
    public static boolean isCheckoutOf(File directory, GitHubUrl url) {
        String origin = originOf(directory);
        if (origin == null) {
            return false;
        }
        String expected = (url.getOwner() + "/" + url.getRepository()).toLowerCase(Locale.ROOT);
        String remote = origin.toLowerCase(Locale.ROOT);
        for (String separator : new String[] {"github.com/", "github.com:"}) {
            int at = remote.indexOf(separator);
            if (at < 0) {
                continue;
            }
            String rest = remote.substring(at + separator.length());
            if (rest.endsWith(".git")) {
                rest = rest.substring(0, rest.length() - 4);
            }
            while (rest.endsWith("/")) {
                rest = rest.substring(0, rest.length() - 1);
            }
            if (rest.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The url of the {@code origin} remote, or null.
     *
     * <p>Parsed rather than grepped, because which remote a url belongs to is the entire question.
     * A git config is INI-shaped: a {@code [remote "origin"]} header, then indented keys until the
     * next header.
     */
    static String originOf(File directory) {
        File config = new File(new File(directory, ".git"), "config");
        if (!config.isFile()) {
            // A worktree or a submodule has .git as a FILE pointing elsewhere. Not something we
            // can read an origin out of here, and claiming "not a checkout" is the safe answer -
            // the caller then refuses rather than pulling into somebody's worktree.
            return null;
        }
        try {
            String text = new String(java.nio.file.Files.readAllBytes(config.toPath()), "UTF-8");
            boolean inOrigin = false;
            for (String line : text.split("\r?\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    inOrigin = trimmed.replace("\"", "").replaceAll("\\s+", " ")
                            .toLowerCase(Locale.ROOT).startsWith("[remote origin]");
                    continue;
                }
                if (inOrigin && trimmed.toLowerCase(Locale.ROOT).startsWith("url")) {
                    int equals = trimmed.indexOf('=');
                    if (equals > 0) {
                        return trimmed.substring(equals + 1).trim();
                    }
                }
            }
            return null;
        } catch (IOException cannotRead) {
            return null;
        }
    }

    /**
     * What went wrong, in terms someone can act on.
     *
     * <p>Git's messages are written for people who already know git. "Repository not found" is
     * what it says for a private repository, for a typo, and for one that has been deleted, and a
     * user who is looking at a repository in their browser reads it as "the tool is broken".
     */
    public static String describeFailure(GitHubUrl url, Outcome outcome) {
        String output = outcome.getOutput().toLowerCase(Locale.ROOT);
        // Local filesystem failures FIRST. git says "Permission denied" for a directory it cannot
        // create as readily as for a key it cannot use, and blaming GitHub for a folder the user
        // does not own sends them to configure an access token for a public repository.
        if (output.contains("could not create work tree dir")
                || output.contains("unable to create directory")
                || output.contains("read-only file system")
                || output.contains("no space left on device")) {
            return "git could not write to that folder: " + lastLineOf(outcome.getOutput())
                    + ". Choose somewhere you own - your home directory rather than a system "
                    + "folder - and try again. This is nothing to do with GitHub.";
        }
        // 401/403 before the generic network branch, or an expired token in the credential
        // manager is reported as a connectivity problem and the one useful message never appears.
        if (output.contains("error: 401") || output.contains("error: 403")
                || output.contains("http 401") || output.contains("http 403")) {
            return "GitHub rejected the stored credentials for " + url.getFullName()
                    + " (HTTP " + (output.contains("401") ? "401" : "403")
                    + "). A token in your credential manager has most likely expired: clone the "
                    + "repository once from a terminal to refresh it, then try again.";
        }
        if (output.contains("repository not found") || output.contains("404")) {
            return "GitHub says there is no repository at " + url.getFullName() + ". If you can "
                    + "see it in a browser it is private, and git has to be able to authenticate: "
                    + "set up a credential helper (or the gh CLI) and try again. Otherwise check "
                    + "the owner and repository names.";
        }
        if (output.contains("could not resolve host") || output.contains("unable to access")
                || output.contains("connection timed out")) {
            return "Could not reach github.com. Check the network, or a proxy if you are behind "
                    + "one - git reads its own proxy settings, not Protege's.";
        }
        if (output.contains("authentication failed") || output.contains("could not read username")
                || (output.contains("permission denied")
                        && (output.contains("publickey") || output.contains("github.com")))) {
            return "GitHub refused the credentials for " + url.getFullName() + ". For a private "
                    + "repository git needs a credential helper or a personal access token; "
                    + "cloning it once from a terminal is the quickest way to find out what it "
                    + "wants.";
        }
        if (output.contains("already exists and is not an empty directory")) {
            return "That directory already has something in it. Choose an empty one, or a parent "
                    + "directory where a new folder can be made.";
        }
        if (output.contains("remote branch") && output.contains("not found")) {
            return "That branch is not in " + url.getFullName()
                    + ". Paste a link to the repository itself to get its default branch.";
        }
        if (outcome.getExitCode() == 127 || output.contains("cannot run program")) {
            return gitMissing();
        }
        String tail = lastLineOf(outcome.getOutput());
        return "git clone failed (exit " + outcome.getExitCode() + ")"
                + (tail.isEmpty() ? "." : ": " + tail);
    }

    /** What to say when git is not installed - which is a setup problem, not a bug. */
    public static String gitMissing() {
        return "git is not on the PATH. Opening a project from GitHub runs git, so that the "
                + "checkout is one you can commit and push from with your own credentials. "
                + "Install git and restart Protege, or clone the repository yourself and open "
                + "the ontology with File > Open.";
    }

    /** The last line with anything on it - git puts the reason there. */
    public static String lastLineOf(String output) {
        if (output == null) {
            return "";
        }
        String[] lines = output.split("\r?\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "";
    }

    /**
     * Runs commands for real, through the shared {@link ProcessRunner}.
     *
     * <p>The hardened implementation lives there because {@code make} needs exactly the same
     * thing, and a second copy of "read on its own thread so the timeout can actually fire" is a
     * second chance to get it wrong.
     */
    public static Runner processRunner() {
        final ProcessRunner.Runner runner = ProcessRunner.real();
        return new Runner() {
            @Override
            public Outcome run(File workingDirectory, List<String> command) throws IOException {
                ProcessRunner.Outcome outcome = runner.run(workingDirectory, command,
                        TIMEOUT_MINUTES, null);
                StringBuilder text = new StringBuilder();
                for (String line : outcome.getOutput()) {
                    text.append(line).append('\n');
                }
                if (outcome.timedOut()) {
                    text.append("git did not finish within ").append(TIMEOUT_MINUTES)
                            .append(" minutes and was stopped. If the repository is private, git ")
                            .append("may have been waiting for a credential it cannot ask for ")
                            .append("from here.\n");
                }
                return new Outcome(outcome.getExitCode(), text.toString());
            }
        };
    }

    /** Whether git can be run at all. */
    public static boolean isAvailable(Runner runner) {
        try {
            return runner.run(null, Arrays.asList("git", "--version")).isSuccess();
        } catch (IOException notThere) {
            return false;
        } catch (RuntimeException notThere) {
            return false;
        }
    }
}
