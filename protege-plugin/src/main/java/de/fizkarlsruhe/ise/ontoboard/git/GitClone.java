package de.fizkarlsruhe.ise.ontoboard.git;

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
     * The clone command.
     *
     * <p>{@code --branch} only when the link named one: passing the default branch explicitly
     * fails on a repository whose default is not what you guessed, and guessing "main" is wrong
     * for every repository created before 2020.
     */
    public static List<String> cloneCommand(GitHubUrl url, File into) {
        List<String> command = new ArrayList<String>(
                Arrays.asList("git", "clone", "--progress"));
        if (url.getBranch() != null) {
            command.add("--branch");
            command.add(url.getBranch());
        }
        command.add(url.getCloneUrl());
        command.add(into.getAbsolutePath());
        return command;
    }

    /** Where a fresh checkout goes: a directory named after the repository, under {@code parent}. */
    public static File directoryFor(File parent, GitHubUrl url) {
        return new File(parent, url.getRepository());
    }

    /**
     * Whether {@code directory} is already a checkout of this repository.
     *
     * <p>Checked by looking for {@code .git} and reading the origin out of its config, rather than
     * by the directory's name - two repositories called {@code mwo} from different owners are not
     * the same repository, and pulling one into the other's directory would be a mess nobody would
     * diagnose quickly.
     */
    public static boolean isCheckoutOf(File directory, GitHubUrl url) {
        File config = new File(new File(directory, ".git"), "config");
        if (!config.isFile()) {
            return false;
        }
        try {
            String text = new String(java.nio.file.Files.readAllBytes(config.toPath()), "UTF-8")
                    .toLowerCase(Locale.ROOT);
            String expected = (url.getOwner() + "/" + url.getRepository())
                    .toLowerCase(Locale.ROOT);
            return text.contains("github.com/" + expected)
                    || text.contains("github.com:" + expected);
        } catch (IOException cannotRead) {
            return false;
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
        if (output.contains("authentication failed") || output.contains("permission denied")
                || output.contains("could not read username")) {
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
            return "The branch '" + url.getBranch() + "' is not in " + url.getFullName()
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
     * Runs a command and collects its output.
     *
     * <p>stderr merged into stdout because git writes progress to one and errors to the other, and
     * a failure message split across two streams is reassembled wrongly as often as not.
     */
    public static Runner processRunner() {
        return new Runner() {
            @Override
            public Outcome run(File workingDirectory, List<String> command) throws IOException {
                ProcessBuilder builder = new ProcessBuilder(command);
                if (workingDirectory != null) {
                    builder.directory(workingDirectory);
                }
                builder.redirectErrorStream(true);
                Process process = builder.start();
                StringBuilder output = new StringBuilder();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), "UTF-8"));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                } finally {
                    reader.close();
                }
                try {
                    // Bounded, because a git waiting on a credential prompt that can never be
                    // answered would otherwise hold the background thread for the whole session.
                    if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                        process.destroyForcibly();
                        return new Outcome(-1, output
                                + "\ngit did not finish within " + TIMEOUT_MINUTES
                                + " minutes and was stopped. If the repository is private, git "
                                + "may have been waiting for a password it cannot ask for here.");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    return new Outcome(-1, output + "\ninterrupted");
                }
                return new Outcome(process.exitValue(), output.toString());
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
