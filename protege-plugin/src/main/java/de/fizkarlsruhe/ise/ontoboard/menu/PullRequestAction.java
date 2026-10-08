package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.git.GitRepo;
import de.fizkarlsruhe.ise.ontoboard.git.PullRequest;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Project &gt; Pull request… - propose the branch you have been working on, and see what is open.
 *
 * <p>The last of the git surface. <i>Git…</i> has done status, stage, commit, pull, push and
 * branch since 1.19.0 and <i>Open from GitHub…</i> clones, so the only step still sending a
 * curator to a browser was the one that ends the work: asking for it to be merged.
 *
 * <p><b>It runs {@code gh}.</b> {@link PullRequest} says why at length; the short of it is that a
 * pull request needs an authenticated GitHub session, {@code gh} already holds one in the system
 * keyring, and the alternative was for this plugin to ask for a token and keep it.
 *
 * <p><b>Nothing is proposed without being shown first.</b> Creating a pull request is the one
 * operation in OntoBoard that other people see the moment it happens - it sends mail, it appears
 * on a board, it asks for somebody's time. So the exact command is on screen and
 * {@code gh pr create --dry-run} has already validated the branch, the base and the remote before
 * anything is announced. The same rule <i>Run a project script…</i> follows, for the same reason.
 */
public class PullRequestAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_WHAT = "what";
    private static final String OPTION_TITLE = "title";
    private static final String OPTION_BODY = "body";
    private static final String OPTION_BASE = "base";
    private static final String OPTION_DRAFT = "draft";

    private static final String LIST = "Show what is open";
    private static final String CREATE = "Open a pull request for this branch";

    private volatile String what = LIST;
    private volatile String title = "";
    private volatile String body = "";
    private volatile String base = "";
    private volatile boolean draft;

    @Override
    protected String operationName() {
        return "Pull request";
    }

    @Override
    protected boolean runsInBackground() {
        return true;
    }

    @Override
    protected boolean configure() {
        File repository = repository();
        if (repository == null) {
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "This ontology is not inside a git checkout, so there is no branch to "
                            + "propose.\n\nOntoBoard > Project > Open from GitHub clones one.",
                    "Not a git project", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Pull request",
                "Propose the branch you are on, or see what is already open. OntoBoard runs the "
                        + "GitHub CLI (gh) for this, so your credentials stay where you already "
                        + "keep them - it stores none of its own.",
                Arrays.asList(
                        Parameter.of(OPTION_WHAT, "Do what", Parameter.Kind.CHOICE)
                                .choices(LIST, CREATE)
                                .defaultValue(LIST)
                                .help("Showing what is open needs nothing but a GitHub remote "
                                        + "and a logged-in gh.\n\nOpening one proposes the "
                                        + "branch you are currently on. It is checked with "
                                        + "gh's own --dry-run first, and you see the exact "
                                        + "command before anything is created.")
                                .build(),
                        Parameter.of(OPTION_TITLE, "Title", Parameter.Kind.TEXT)
                                .defaultValue("")
                                .help("What the pull request is called. Required when opening "
                                        + "one.\n\nFor an ontology change, what changed and why "
                                        + "is more use to a reviewer than the branch name: "
                                        + "\"Import RO object properties for the process "
                                        + "pattern\" rather than \"ro-import\".")
                                .build(),
                        Parameter.of(OPTION_BODY, "Description", Parameter.Kind.MULTILINE)
                                .defaultValue("")
                                .help("The description. Left empty is fine.\n\nWorth saying: "
                                        + "which terms were added or obsoleted, whether the "
                                        + "imports were refreshed, and whether the quality "
                                        + "report is clean - the three things a reviewer of an "
                                        + "ontology change checks first.")
                                .build(),
                        Parameter.of(OPTION_BASE, "Merge into", Parameter.Kind.TEXT)
                                .defaultValue("")
                                .help("The branch to merge into. Empty means the repository's "
                                        + "default, which gh resolves - an ODK project's is not "
                                        + "always 'main'.")
                                .build(),
                        Parameter.of(OPTION_DRAFT, "Open it as a draft", Parameter.Kind.FLAG)
                                .defaultValue("false")
                                .help("A draft is visible and reviewable but cannot be merged "
                                        + "until marked ready. For an ontology change that is "
                                        + "still being built, it is the honest state - it gets "
                                        + "CI running without asking anybody to review yet.")
                                .build()));
        if (chosen == null) {
            return false;
        }
        what = chosen.get(OPTION_WHAT);
        title = chosen.get(OPTION_TITLE) == null ? "" : chosen.get(OPTION_TITLE).trim();
        body = chosen.get(OPTION_BODY) == null ? "" : chosen.get(OPTION_BODY);
        base = chosen.get(OPTION_BASE) == null ? "" : chosen.get(OPTION_BASE).trim();
        draft = "true".equalsIgnoreCase(chosen.get(OPTION_DRAFT));
        return true;
    }

    @Override
    protected OperationResult run(org.semanticweb.owlapi.model.OWLOntology ontology) {
        File repository = repository();
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Number", "State", "Title", "Branch", "Author");
        if (repository == null) {
            return result.failed("This ontology has not been saved inside a git checkout.")
                    .build();
        }
        ProcessRunner.Runner runner = ProcessRunner.real();

        GitRepo.Status status;
        try {
            ProcessRunner.Outcome outcome = runner.run(repository, GitRepo.statusCommand(),
                    GitRepo.TIMEOUT_MINUTES, null);
            if (!outcome.isSuccess()) {
                return result.failed(GitRepo.explain("status", outcome.getExitCode(),
                        outcome.getOutput())).build();
            }
            status = GitRepo.parseStatus(outcome.getOutput());
        } catch (IOException cannotRun) {
            return result.failed("Could not run git: " + cannotRun.getMessage()).build();
        }
        result.note("Branch: " + status.getBranch()
                + (status.hasUpstream() ? "" : " (not pushed yet)"));

        // Whether gh can be used at all, asked once and used by both paths.
        String unavailable;
        try {
            ProcessRunner.Outcome auth = runner.run(repository, PullRequest.authCommand(),
                    GitRepo.TIMEOUT_MINUTES, null);
            unavailable = PullRequest.whyUnavailable(auth.getExitCode(), auth.getOutput());
        } catch (IOException cannotRun) {
            unavailable = PullRequest.whyUnavailable(-1, Arrays.asList(cannotRun.getMessage()));
        }

        if (unavailable != null) {
            result.warn(unavailable);
            offerTheBrowser(runner, repository, status, result);
            return result.summary("The GitHub CLI is not usable here - see the note above.")
                    .build();
        }

        if (CREATE.equals(what)) {
            return create(runner, repository, status, result);
        }
        return list(runner, repository, result);
    }

    /** What is open, as a table. */
    private OperationResult list(ProcessRunner.Runner runner, File repository,
            OperationResult.Builder result) {
        try {
            ProcessRunner.Outcome outcome = runner.run(repository, PullRequest.listCommand(),
                    GitRepo.TIMEOUT_MINUTES, null);
            if (!outcome.isSuccess()) {
                return result.failed("gh could not list the pull requests: "
                        + lastLine(outcome.getOutput())).build();
            }
            List<PullRequest.Summary> open = PullRequest.parseList(join(outcome.getOutput()));
            for (PullRequest.Summary one : open) {
                result.row("#" + one.getNumber(), one.describeState(), one.getTitle(),
                        one.getHead(), one.getAuthor());
            }
            if (open.isEmpty()) {
                return result.summary("Nothing is open on this repository.").build();
            }
            for (PullRequest.Summary one : open) {
                result.note("#" + one.getNumber() + "  " + one.getUrl());
            }
            return result.summary(open.size() + " open.").build();
        } catch (IOException cannotRun) {
            return result.failed("Could not run gh: " + cannotRun.getMessage()).build();
        }
    }

    /**
     * Opening one, after a dry run and a confirmation.
     *
     * <p>Three gates, in the order that fails cheapest: the branch has to be proposable at all,
     * {@code gh} has to agree in a dry run, and the user has to approve the exact command.
     */
    private OperationResult create(ProcessRunner.Runner runner, File repository,
            GitRepo.Status status, OperationResult.Builder result) {
        if (title.isEmpty()) {
            return result.failed("A pull request needs a title. Run this again and give it one.")
                    .build();
        }
        String cannotPropose = PullRequest.whyBranchCannotBeProposed(status.getBranch(),
                base.isEmpty() ? null : base, status.hasUpstream());
        if (cannotPropose != null) {
            return result.failed(cannotPropose).build();
        }

        List<String> command = PullRequest.createCommand(base, title, body, draft);
        try {
            ProcessRunner.Outcome dry = runner.run(repository, PullRequest.dryRunOf(command),
                    GitRepo.TIMEOUT_MINUTES, null);
            if (!dry.isSuccess()) {
                return result.failed("gh refused this before creating anything: "
                        + lastLine(dry.getOutput())).build();
            }
            result.note("Checked with gh --dry-run: it would open this.");
        } catch (IOException cannotRun) {
            return result.failed("Could not run gh: " + cannotRun.getMessage()).build();
        }

        if (!approved(status, command)) {
            return result.summary("Nothing was created.").build();
        }
        if (BackgroundRun.abandoned()) {
            return result.failed("Stopped before anything was created.").build();
        }

        try {
            ProcessRunner.Outcome outcome = runner.run(repository, command,
                    GitRepo.TIMEOUT_MINUTES, null);
            if (!outcome.isSuccess()) {
                return result.failed("gh could not open it: " + lastLine(outcome.getOutput()))
                        .build();
            }
            // gh prints the URL of what it created, and that is the one thing worth keeping.
            for (String line : outcome.getOutput()) {
                if (line != null && line.trim().startsWith("http")) {
                    result.note("Opened: " + line.trim());
                }
            }
            return result.summary("Pull request opened from " + status.getBranch() + ".").build();
        } catch (IOException cannotRun) {
            return result.failed("Could not run gh: " + cannotRun.getMessage()).build();
        }
    }

    /**
     * The confirmation.
     *
     * <p>On the event thread, because it is a question. The command is shown as it will be run -
     * not a description of it - so that what is approved and what happens are the same text.
     */
    private boolean approved(GitRepo.Status status, final List<String> command) {
        StringBuilder shown = new StringBuilder();
        for (String word : command) {
            shown.append(shown.length() == 0 ? "" : " ")
                    .append(word.indexOf(' ') >= 0 ? "\"" + word + "\"" : word);
        }
        final String message = "This will open a pull request from " + status.getBranch()
                + ", which other people will see.\n\n" + shown + "\n\nGo ahead?";
        final boolean[] yes = new boolean[1];
        try {
            Runnable ask = new Runnable() {
                @Override
                public void run() {
                    yes[0] = javax.swing.JOptionPane.showConfirmDialog(getOWLWorkspace(), message,
                            "Open this pull request?", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                            javax.swing.JOptionPane.QUESTION_MESSAGE)
                            == javax.swing.JOptionPane.OK_OPTION;
                }
            };
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                ask.run();
            } else {
                javax.swing.SwingUtilities.invokeAndWait(ask);
            }
        } catch (Exception interrupted) {
            return false;
        }
        return yes[0];
    }

    /**
     * The one answer that needs nothing installed.
     *
     * <p>GitHub's compare form opens from a URL, so a user without {@code gh} is given the link
     * rather than an apology. It is the one place where leaving Prot&eacute;g&eacute; is the
     * honest recommendation.
     */
    private void offerTheBrowser(ProcessRunner.Runner runner, File repository,
            GitRepo.Status status, OperationResult.Builder result) {
        String remote = remoteOf(runner, repository);
        String url = PullRequest.compareUrl(remote, status.getBranch());
        if (url != null) {
            result.note("Open this in a browser to propose " + status.getBranch()
                    + " without gh: " + url);
        } else if (remote == null) {
            result.note("This checkout has no 'origin' remote, so there is nowhere to propose "
                    + "the branch to.");
        } else {
            result.note("The remote " + remote + " is not a GitHub repository, so there is no "
                    + "pull request form to open.");
        }
    }

    /** The origin URL, or null. */
    private String remoteOf(ProcessRunner.Runner runner, File repository) {
        try {
            ProcessRunner.Outcome outcome = runner.run(repository,
                    Arrays.asList("git", "remote", "get-url", "origin"),
                    GitRepo.TIMEOUT_MINUTES, null);
            if (!outcome.isSuccess() || outcome.getOutput().isEmpty()) {
                return null;
            }
            String first = outcome.getOutput().get(0);
            return first == null || first.trim().isEmpty() ? null : first.trim();
        } catch (IOException cannotRun) {
            return null;
        }
    }

    private static String join(List<String> output) {
        StringBuilder all = new StringBuilder();
        for (String line : output) {
            all.append(line);
        }
        return all.toString();
    }

    /** The last thing a failing command said, which is usually the reason. */
    private static String lastLine(List<String> output) {
        for (int at = output.size() - 1; at >= 0; at--) {
            String line = output.get(at);
            if (line != null && !line.trim().isEmpty()) {
                return line.trim();
            }
        }
        return "it printed nothing.";
    }

    private File repository() {
        return GitRepo.repositoryFor(fileOf(getOWLModelManager().getActiveOntology()));
    }
}
