package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.git.GitRepo;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;

/**
 * The lines a curator reads about a conflicted ontology, read out of git's three stages.
 *
 * <p>This is the half of conflict handling that talks to git: pull the common ancestor, this
 * branch and the other branch out of the index with {@code git show :N:path}, parse each as an
 * ontology, and hand the three to {@link ConflictReport}. The judgement about what disagrees
 * lives there; what lives here is which files are worth asking about, what to say when git cannot
 * produce one of the three, and the fact that none of it may ever throw.
 *
 * <p><b>Why the merged file on disk is no use.</b> Measured on a real conflicted repository: the
 * working copy of a conflicted {@code .ttl} fails to parse outright - {@code Content is not
 * allowed in prolog}, because git has written {@code <<<<<<<} into it. All three stages parse
 * perfectly. So the only readable versions of a conflicted ontology are the ones in the index,
 * and a side-by-side view of the file on disk is a view of something that is no longer an
 * ontology.
 *
 * <p><b>Nothing here throws.</b> It is called while a commit is being refused, and the refusal
 * stands on its own. A report that could not be produced must not replace a clear message about
 * the conflict with an obscure one about the OWL API.
 */
public final class ConflictReview {

    /**
     * Read the document, do not go and get what it imports. See {@link #stage}.
     *
     * <p>Silent rather than throwing, because a missing import is not this report's business; no
     * retries and a short timeout, because the measured cost of the defaults was 13.6 seconds and
     * an exception where a two-second answer was available.
     */
    private static final OWLOntologyLoaderConfiguration WITHOUT_FETCHING_IMPORTS =
            new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                    .setRetriesToAttempt(0)
                    .setConnectionTimeout(2000);

    private ConflictReview() {
    }

    /**
     * Describes each conflicted file that is an ontology, one block per file.
     *
     * @param runner       how {@code git show} gets run; replaced in tests
     * @param repository   the working tree the conflict is in
     * @param conflicted   the paths git reported as unmerged, from {@link GitRepo.Status}
     * @return lines to show, in order, or empty when there is nothing an ontology diff can add
     */
    public static List<String> of(ProcessRunner.Runner runner, File repository,
            List<String> conflicted) {
        List<String> lines = new ArrayList<String>();
        // WHAT THE TWO SIDES ARE DEPENDS ON THE OPERATION, and calling them the wrong thing is
        // worse than not naming them. In a merge, stage 2 is this branch and stage 3 is the one
        // being merged in. In a REBASE, cherry-pick or revert the roles invert: stage 2 is what
        // is already on the new base and stage 3 is the commit being replayed - so telling a
        // curator that their own edit is "the other branch" invites them to discard their work.
        // MEASURED: during `git rebase`, status still prints UU and nothing in it says so.
        boolean merging = isMerging(runner, repository);
        String ourName = merging ? "this branch" : "the version already here";
        String theirName = merging ? "the other branch" : "the change being applied";
        if (!merging && !conflicted.isEmpty()) {
            lines.add("A rebase, cherry-pick or revert is in progress, so the two sides are not "
                    + "simply two branches: one version is what is already on the new base and "
                    + "the other is the change being replayed onto it.");
        }
        for (String path : conflicted) {
            if (!looksLikeAnOntology(path)) {
                // A conflicted README is a real conflict and not one an ontology diff can say
                // anything useful about. The refusal already names every conflicted file, so
                // saying nothing here loses nothing.
                continue;
            }
            try {
                describe(runner, repository, path, lines, ourName, theirName);
            } catch (RuntimeException | IOException | LinkageError couldNotCompare) {   // NOPMD - soft
                lines.add(path + ": the three versions could not be compared ("
                        + couldNotCompare.getClass().getSimpleName() + ").");
            }
        }
        return lines;
    }

    /**
     * Whether a plain merge is what left these conflicts, rather than a rebase or cherry-pick.
     *
     * <p>{@code MERGE_HEAD} exists only during a merge. A rebase leaves {@code rebase-merge} or
     * {@code rebase-apply} instead, a cherry-pick {@code CHERRY_PICK_HEAD}, a revert
     * {@code REVERT_HEAD} - and in all of those stage 2 and stage 3 mean the opposite of what
     * they mean in a merge. Asked once per report rather than once per file.
     *
     * <p>Answered through git rather than by looking for {@code .git/MERGE_HEAD} on disk,
     * because in a worktree or a submodule {@code .git} is a file pointing elsewhere and the
     * directory this code was handed is not where that ref lives.
     */
    private static boolean isMerging(ProcessRunner.Runner runner, File repository) {
        try {
            return runner.run(repository,
                    java.util.Arrays.asList("git", "rev-parse", "--verify", "-q", "MERGE_HEAD"),
                    GitRepo.TIMEOUT_MINUTES, null).isSuccess();
        } catch (IOException | RuntimeException cannotTell) {   // NOPMD - soft by design
            // A merge is the ordinary case, and the wording for it is the one already tested.
            return true;
        }
    }

    private static void describe(ProcessRunner.Runner runner, File repository, String path,
            List<String> lines, String ourName, String theirName) throws IOException {
        // Which stages git kept IS the shape of the conflict, so git is asked rather than
        // guessed at. Inferring the shape from which versions PARSE would report a stage the
        // OWL API cannot read as a deletion, which is a lie about the repository.
        Set<Integer> kept = GitRepo.parseUnmergedStages(
                ask(runner, repository, GitRepo.unmergedStagesCommand(path)));

        if (kept.isEmpty()) {
            lines.add(path + ": git no longer keeps a conflicted version of this file, so there "
                    + "is nothing to compare.");
            return;
        }
        if (!kept.contains(2) && !kept.contains(3)) {
            lines.add(path + ": neither branch has this file any more. If other paths below are "
                    + "also conflicted, the two sides may have renamed it differently rather "
                    + "than deleted it.");
            return;
        }
        // EACH ONE-SIDED CASE SPLITS ON WHETHER GIT KEPT AN ANCESTOR, and the first version of
        // this did not. Without stage 1 there is no common version of the path, so "no longer
        // has this file" asserts a history git has just said never existed - the same confident
        // lie that asking git for the stage list was meant to prevent.
        //
        // MEASURED: two sides renaming one file differently (rename/rename, git 2.38.1) gives
        // `DD a.ttl`, `AU b.ttl`, `UA c.ttl` - stage 1 alone for the old name, stage 2 alone for
        // ours, stage 3 alone for theirs. Told that "the other branch no longer has b.ttl", a
        // curator restoring it would throw away one of the two renames; the real decision is
        // only which name to keep.
        if (!kept.contains(2)) {
            lines.add(path + (kept.contains(1)
                    ? ": this branch no longer has this file, and the other branch still changes "
                      + "it - a decision about the file, not about a term."
                    : ": only the other branch has this file, and there is no common version of "
                      + "it. Usually both sides renamed one file differently, and the decision "
                      + "is which name to keep."));
            return;
        }
        if (!kept.contains(3)) {
            lines.add(path + (kept.contains(1)
                    ? ": the other branch no longer has this file, and this branch still changes "
                      + "it - a decision about the file, not about a term."
                    : ": only this branch has this file, and there is no common version of it. "
                      + "Usually both sides renamed one file differently, and the decision is "
                      + "which name to keep."));
            return;
        }

        OWLOntology ours = stage(runner, repository, 2, path);
        OWLOntology theirs = stage(runner, repository, 3, path);
        if (!kept.contains(1)) {
            // Both sides created it. Diffing against an empty ancestor would report every term in
            // the file as added on both sides - a list nobody reads, which would bury the one
            // thing worth knowing: these two files have no shared history to merge along.
            lines.add(path + ": both branches created this file independently"
                    + sizes(ours, theirs) + ", so there is no common version to merge from.");
            return;
        }

        OWLOntology ancestor = stage(runner, repository, 1, path);
        if (ancestor == null || ours == null || theirs == null) {
            lines.add(path + ": git kept all three versions, but " + unreadable(ancestor, ours,
                    theirs) + " will not parse as an ontology, so they cannot be compared. That "
                    + "is a broken file on one side rather than a disagreement to settle.");
            return;
        }

        ConflictReport report = ConflictReport.between(ancestor, ours, theirs);
        lines.add(path + ": " + report.summary());
        for (ConflictReport.Collision collision : report.getCollisions()) {
            lines.add("    " + collision.describe(ourName, theirName));
        }
        if (report.getCollisions().isEmpty()) {
            // GIT SAYS THESE TWO VERSIONS CLASH, so "nothing disagrees" cannot be the whole
            // story and must not be left to read as one. MEASURED on a real conflict where both
            // sides edited only the ontology's own rdfs:label: the summary read "Both sides
            // changed 0 and 0 terms, and nothing they both touched disagrees" while the commit
            // was being refused - which invites the reader to conclude the refusal is a bug.
            //
            // ReleaseDiff describes six things that can happen to a TERM. An ontology-level
            // annotation, a comment, a synonym, an xref, a contributor line or a reordering is
            // none of them, and that is where the difference is when no term disagrees.
            lines.add("    The two versions still differ somewhere this comparison does not "
                    + "reach: it compares terms - added, obsoleted, relabelled, redefined, "
                    + "moved - and not ontology-level annotations, comments or synonyms.");
        }
    }

    /** How big the two unrelated versions are, when both could be read. */
    private static String sizes(OWLOntology ours, OWLOntology theirs) {
        if (ours == null || theirs == null) {
            return "";
        }
        return " - " + ours.getAxiomCount() + " axioms here, " + theirs.getAxiomCount() + " there";
    }

    /** Which of the three git kept cannot be read, named as a person would name them. */
    private static String unreadable(OWLOntology ancestor, OWLOntology ours, OWLOntology theirs) {
        List<String> broken = new ArrayList<String>();
        if (ancestor == null) {
            broken.add(GitRepo.stageName(1));
        }
        if (ours == null) {
            broken.add(GitRepo.stageName(2));
        }
        if (theirs == null) {
            broken.add(GitRepo.stageName(3));
        }
        if (broken.size() == 1) {
            return broken.get(0);
        }
        String last = broken.remove(broken.size() - 1);
        return String.join(", ", broken) + " and " + last;
    }

    /** Runs a git command and hands back its lines, empty when git would not answer. */
    private static List<String> ask(ProcessRunner.Runner runner, File repository,
            List<String> command) throws IOException {
        ProcessRunner.Outcome outcome = runner.run(repository, command, GitRepo.TIMEOUT_MINUTES,
                null);
        return outcome.isSuccess() ? outcome.getOutput() : new ArrayList<String>();
    }

    /**
     * One stage of a conflicted file, parsed, or null when git or the parser would not.
     *
     * <p><b>Imports are not fetched.</b> MEASURED, with an ontology importing two unreachable
     * IRIs: a default-configured manager threw {@code UnloadableImportException} after 13.6
     * seconds, which would both report a perfectly readable ODK edit file as unparseable AND cost
     * that per stage, three stages per file. The configuration below answered in 2.5 seconds with
     * the ontology intact. An ODK edit file imports its modules by IRI, so this is the ordinary
     * case rather than a corner of one.
     *
     * <p>Nothing is lost by not fetching them: {@link ConflictReport} compares what each version
     * ASSERTS, and {@code getAxioms()} is the ontology's own axioms either way. An import would
     * contribute nothing to the comparison and a network round trip to every run of it.
     */
    private static OWLOntology stage(ProcessRunner.Runner runner, File repository, int stage,
            String path) throws IOException {
        ProcessRunner.Outcome outcome = runner.run(repository, GitRepo.stageCommand(stage, path),
                GitRepo.TIMEOUT_MINUTES, null);
        if (!outcome.isSuccess()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (String line : outcome.getOutput()) {
            text.append(line).append('\n');
        }
        try {
            // A fresh manager per stage: three versions of one ontology share an ontology IRI,
            // and one manager would refuse the second as already loaded.
            //
            // An EMPTY blob is not an unreadable one. git exits 0 for a file one side emptied,
            // and an empty document parses to an ontology with no axioms - which is the truth
            // about that side and reads as "every term removed", where returning null here would
            // have claimed the file will not parse.
            return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                    new StringDocumentSource(text.toString()), WITHOUT_FETCHING_IMPORTS);
        } catch (OWLOntologyCreationException willNotParse) {
            return null;
        }
    }

    /** Whether it is worth asking the OWL API to read this one. */
    static boolean looksLikeAnOntology(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".owl") || lower.endsWith(".ttl") || lower.endsWith(".rdf")
                || lower.endsWith(".omn") || lower.endsWith(".ofn") || lower.endsWith(".obo")
                || lower.endsWith(".owx");
    }
}
