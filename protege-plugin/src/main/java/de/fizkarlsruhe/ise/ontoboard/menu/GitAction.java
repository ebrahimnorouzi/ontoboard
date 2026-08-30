package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.git.GitRepo;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Git &gt; the everyday commands, for the project you have open.
 *
 * <p>Cloning was the whole of the git integration, which made the checkout read-only in practice.
 * Everything else this plugin now does to an ODK project - minting a term, allocating an ID range,
 * writing an import module and its catalog entry, cutting a release - produces changes somebody
 * then had to leave Protege to record. An editor that can change a repository and not commit the
 * change is one you have to babysit from a terminal anyway.
 *
 * <p>Only the safe half of git. No merge, rebase, reset or force: each needs a conflict resolved
 * or a history rewritten, and neither is something a menu item should start on somebody's behalf.
 */
public class GitAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_WHAT = "what";
    private static final String OPTION_MESSAGE = "message";
    private static final String OPTION_BRANCH = "branch";

    private static final String STATUS = "Show me what has changed";
    private static final String COMMIT = "Commit everything, with a message";
    private static final String PULL = "Pull (fast-forward only)";
    private static final String PUSH = "Push";
    private static final String BRANCH = "Start a branch";

    private volatile String what = STATUS;
    private volatile String message = "";
    private volatile String branch = "";

    @Override
    protected String operationName() {
        return "Git";
    }

    @Override
    protected boolean configure() {
        File repository = repository();
        if (repository == null) {
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "This ontology is not inside a git checkout, so there is nothing to commit "
                            + "to.\n\nOntoBoard > Project > Open from GitHub clones one, and "
                            + "OntoBoard > Project > New ODK project makes a directory you can "
                            + "run 'git init' in.",
                    "Not a git project", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }

        List<Parameter> parameters = Arrays.asList(
                Parameter.of(OPTION_WHAT, "Do what", Parameter.Kind.CHOICE)
                        .choices(STATUS, COMMIT, PULL, PUSH, BRANCH)
                        .defaultValue(STATUS)
                        .required()
                        .help("Showing is read-only and is the one to start with. Committing "
                                + "records everything changed and everything new, because a new "
                                + "import module or release directory is exactly what this plugin "
                                + "produces and a commit that left it out would look like it "
                                + "worked. Pulling is fast-forward only - merging an ontology is "
                                + "not something this should begin on your behalf. Merge, rebase "
                                + "and reset are deliberately absent for the same reason.")
                        .build(),
                Parameter.of(OPTION_MESSAGE, "Commit message", Parameter.Kind.MULTILINE)
                        .defaultValue("")
                        .help("What changed and why. This is the record somebody reads in a year "
                                + "when they are trying to work out where a term came from, so "
                                + "'update' costs them an afternoon. Only used when committing.")
                        .build(),
                Parameter.of(OPTION_BRANCH, "Branch name", Parameter.Kind.TEXT)
                        .defaultValue("")
                        .help("Only used when starting a branch. Convention in OBO projects is "
                                + "issue-123 or a short description of the change - whatever your "
                                + "project already uses.")
                        .build());

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Git",
                "Working in " + repository.getAbsolutePath(), parameters);
        if (chosen == null) {
            return false;
        }
        what = chosen.get(OPTION_WHAT);
        message = chosen.get(OPTION_MESSAGE) == null ? "" : chosen.get(OPTION_MESSAGE).trim();
        branch = chosen.get(OPTION_BRANCH) == null ? "" : chosen.get(OPTION_BRANCH).trim();
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        File repository = repository();
        OperationResult.Builder result = OperationResult.of(operationName());
        result.note("Repository: " + repository.getAbsolutePath());

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
        describe(status, result);

        if (STATUS.equals(what)) {
            return result.summary(summaryOf(status)).build();
        }
        if (BackgroundRun.abandoned()) {
            return result.failed("Stopped before anything was done.").build();
        }

        try {
            if (COMMIT.equals(what)) {
                return commit(runner, repository, status, ontology, result);
            }
            if (PULL.equals(what)) {
                return simple(runner, repository, GitRepo.pullCommand(), "pull", result);
            }
            if (PUSH.equals(what)) {
                return simple(runner, repository,
                        status.hasUpstream() ? GitRepo.pushCommand()
                                : GitRepo.pushNewBranchCommand(status.getBranch()),
                        "push", result);
            }
            if (BRANCH.equals(what)) {
                if (branch.isEmpty()) {
                    return result.failed("Give the branch a name.").build();
                }
                return simple(runner, repository, GitRepo.createBranchCommand(branch),
                        "branch", result);
            }
        } catch (IOException cannotRun) {
            return result.failed("Could not run git: " + cannotRun.getMessage()).build();
        }
        return result.summary(summaryOf(status)).build();
    }

    private OperationResult commit(ProcessRunner.Runner runner, File repository,
            GitRepo.Status status, OWLOntology ontology, OperationResult.Builder result)
            throws IOException {
        if (message.isEmpty()) {
            return result.failed("A commit needs a message. It is the record somebody reads in a "
                    + "year trying to work out where a term came from.").build();
        }
        if (status.isClean()) {
            return result.failed(GitRepo.explain("commit", 1,
                    Arrays.asList("nothing to commit"))).build();
        }
        // Said before committing, because afterwards it is a commit that silently records less
        // than the user believes.
        String unsaved = GitRepo.unsavedWarning(getOWLModelManager().isDirty(ontology));
        if (unsaved != null) {
            result.warn(unsaved);
        }

        ProcessRunner.Outcome staged = runner.run(repository, GitRepo.stageCommand(),
                GitRepo.TIMEOUT_MINUTES, null);
        if (!staged.isSuccess()) {
            return result.failed(GitRepo.explain("add", staged.getExitCode(),
                    staged.getOutput())).build();
        }
        ProcessRunner.Outcome committed = runner.run(repository, GitRepo.commitCommand(message),
                GitRepo.TIMEOUT_MINUTES, null);
        for (String line : committed.getOutput()) {
            result.note(line);
        }
        if (!committed.isSuccess()) {
            return result.failed(GitRepo.explain("commit", committed.getExitCode(),
                    committed.getOutput())).build();
        }
        result.note("Nothing has been pushed - run Git again and choose Push when you are ready.");
        return result.summary("Committed " + (status.getChanged().size()
                + status.getUntracked().size()) + " files.").build();
    }

    private OperationResult simple(ProcessRunner.Runner runner, File repository,
            List<String> command, String operation, OperationResult.Builder result)
            throws IOException {
        ProcessRunner.Outcome outcome = runner.run(repository, command, GitRepo.TIMEOUT_MINUTES,
                null);
        for (String line : outcome.getOutput()) {
            result.note(line);
        }
        if (!outcome.isSuccess()) {
            return result.failed(GitRepo.explain(operation, outcome.getExitCode(),
                    outcome.getOutput())).build();
        }
        if ("pull".equals(operation)) {
            result.warn("Protege does not watch the disk. If the pull changed the ontology you "
                    + "have open, reopen it - otherwise your next save writes over what came in.");
        }
        return result.summary("git " + operation + " succeeded.").build();
    }

    /** The working copy, as a table, before anything is done to it. */
    private void describe(GitRepo.Status status, OperationResult.Builder result) {
        result.note("Branch: " + status.getBranch()
                + (status.hasUpstream() ? "" : " (never pushed)"));
        if (status.getBehind() > 0) {
            result.warn(status.getBehind() + " commit" + (status.getBehind() == 1 ? "" : "s")
                    + " on the remote that you do not have. Pull before you push.");
        }
        if (status.getAhead() > 0) {
            result.note(status.getAhead() + " commit" + (status.getAhead() == 1 ? "" : "s")
                    + " here that the remote does not have.");
        }
        if (status.isClean()) {
            return;
        }
        result.columns("State", "File");
        List<String> rows = new ArrayList<String>();
        for (String path : status.getChanged()) {
            result.row("changed", path);
            rows.add(path);
        }
        for (String path : status.getUntracked()) {
            result.row("new", path);
            rows.add(path);
        }
    }

    private static String summaryOf(GitRepo.Status status) {
        if (status.isClean()) {
            return "Nothing has changed since the last commit, on branch " + status.getBranch()
                    + ".";
        }
        return status.getChanged().size() + " changed and " + status.getUntracked().size()
                + " new file" + (status.getUntracked().size() == 1 ? "" : "s") + " on branch "
                + status.getBranch() + ".";
    }

    private File repository() {
        return GitRepo.repositoryFor(fileOf(getOWLModelManager().getActiveOntology()));
    }

    /** The ontology's own file, or null when it has never been saved. */
    private File fileOf(OWLOntology ontology) {
        if (ontology == null) {
            return null;
        }
        try {
            URI documentUri = getOWLModelManager().getOWLOntologyManager()
                    .getOntologyDocumentIRI(ontology).toURI();
            return "file".equalsIgnoreCase(documentUri.getScheme()) ? new File(documentUri) : null;
        } catch (RuntimeException notAFile) {
            return null;
        }
    }
}
