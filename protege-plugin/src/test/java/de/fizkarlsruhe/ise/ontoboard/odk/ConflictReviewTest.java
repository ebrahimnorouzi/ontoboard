package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a curator is told about a conflicted ontology, from the stages git actually kept.
 *
 * <p>Every shape here was measured on a real merge before it was written down: an ordinary
 * content clash keeps stages 1, 2 and 3 ({@code UU}); one side deleting the file keeps 1 and 3,
 * or 1 and 2 ({@code DU}, {@code UD}); both sides creating it keeps 2 and 3 and has no ancestor
 * ({@code AA}). Plain delete/delete never arrives - git resolves it silently and the file is
 * absent from the status - but {@code GitRepo.isUnmerged} admits {@code DD}, so the path exists
 * and is covered here rather than left to be discovered by a user.
 *
 * <p>The commit is being refused either way while this runs, so the one thing that must never
 * happen is a throw: it would replace a clear message about the conflict with an obscure one.
 */
class ConflictReviewTest {

    private static final String NS = "http://example.org/o#";

    /** Turtle for an ontology whose one class carries the given label. */
    private static String withLabel(String label) {
        return "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "<http://example.org/o> a owl:Ontology .\n"
                + "<" + NS + "Material> a owl:Class ;\n"
                + "    rdfs:label \"" + label + "\" .\n";
    }

    /** What git leaves in the working copy of a conflicted file. It is not an ontology. */
    private static final String WITH_MARKERS = "<<<<<<< HEAD\n"
            + "    rdfs:label \"Material Entity\" .\n"
            + "=======\n"
            + "    rdfs:label \"Material Thing\" .\n"
            + ">>>>>>> other\n";

    /**
     * A git that keeps exactly the stages it is given.
     *
     * <p>It answers both calls the reviewer makes: {@code ls-files -u} with one line per stage
     * it has, in git's real {@code <mode> <sha> <stage>TAB<path>} format, and {@code show :N:path}
     * with that stage's content. A stage it does not have fails, which is what git does.
     */
    private static ProcessRunner.Runner gitWith(final Map<String, String> stages) {
        return gitWith(stages, true);
    }

    /**
     * @param merging whether MERGE_HEAD resolves, which is how the reviewer learns that stage 2
     *                is this branch rather than whatever a rebase is replaying onto
     */
    private static ProcessRunner.Runner gitWith(final Map<String, String> stages,
            final boolean merging) {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File directory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) {
                if (command.contains("rev-parse")) {
                    return merging
                            ? new ProcessRunner.Outcome(0, Arrays.asList(
                                    "0000000000000000000000000000000000000000"), false)
                            : new ProcessRunner.Outcome(1, Collections.<String>emptyList(), false);
                }
                String last = command.get(command.size() - 1);
                if (command.contains("ls-files")) {
                    List<String> listed = new ArrayList<String>();
                    for (int stage = 1; stage <= 3; stage++) {
                        if (stages.containsKey(":" + stage + ":" + last)) {
                            listed.add("100644 "
                                    + "0000000000000000000000000000000000000000 " + stage
                                    + "\t" + last);
                        }
                    }
                    return new ProcessRunner.Outcome(0, listed, false);
                }
                String content = stages.get(last);
                if (content == null) {
                    return new ProcessRunner.Outcome(128, Arrays.asList(
                            "fatal: path '" + last + "' does not exist"), false);
                }
                return new ProcessRunner.Outcome(0,
                        Arrays.asList(content.split("\n", -1)), false);
            }
        };
    }

    private static Map<String, String> stages(String path, String ancestor, String ours,
            String theirs) {
        Map<String, String> kept = new LinkedHashMap<String, String>();
        if (ancestor != null) {
            kept.put(":1:" + path, ancestor);
        }
        if (ours != null) {
            kept.put(":2:" + path, ours);
        }
        if (theirs != null) {
            kept.put(":3:" + path, theirs);
        }
        return kept;
    }

    private static List<String> review(Map<String, String> kept, String... conflicted) {
        return ConflictReview.of(gitWith(kept), new File("."), Arrays.asList(conflicted));
    }

    /** The ordinary content conflict: both sides relabelled one class, and both are named. */
    @Test
    void anOrdinaryConflictNamesTheTermAndBothLabels() {
        List<String> lines = review(stages("src/ontology/mwo-edit.ttl", withLabel("Material"),
                withLabel("Material Entity"), withLabel("Material Thing")),
                "src/ontology/mwo-edit.ttl");

        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("src/ontology/mwo-edit.ttl: "), lines.get(0));
        assertTrue(lines.get(0).contains("1 term changed on both sides"), lines.get(0));
        assertTrue(lines.get(1).contains("Material Entity"), lines.get(1));
        assertTrue(lines.get(1).contains("Material Thing"), lines.get(1));
    }

    /**
     * A conflicted file that is not an ontology is passed over in silence.
     *
     * <p>Not to hide it - the refusal names every conflicted file. Passed over because an
     * ontology diff has nothing to say about a README, and a line admitting that for each one
     * would bury the file that does matter.
     */
    @Test
    void aConflictedFileThatIsNotAnOntologyIsNotDescribed() {
        List<String> lines = review(Collections.<String, String>emptyMap(),
                "README.md", "Makefile", ".gitignore");

        assertTrue(lines.isEmpty(), lines.toString());
    }

    /** Each ontology extension ODK writes is recognised, and nothing else is. */
    @Test
    void theExtensionsItWillTryAreTheOnesOdkProduces() {
        for (String yes : new String[] {"a.owl", "a.ttl", "a.rdf", "a.omn", "a.ofn", "a.obo",
                "a.owx", "SRC/ONTOLOGY/MWO.OWL"}) {
            assertTrue(ConflictReview.looksLikeAnOntology(yes), yes);
        }
        for (String no : new String[] {"README.md", "Makefile", "a.owl.bak", "a.json", "a.tsv",
                "catalog-v001.xml"}) {
            assertFalse(ConflictReview.looksLikeAnOntology(no), no);
        }
    }

    /**
     * One side deleted the file. The report says so rather than saying nothing.
     *
     * <p>"Deleted on one side" is a different decision from "both relabelled a term", and worth
     * naming as one. Measured: git really does keep stages 1 and 3 for this, and nothing else.
     */
    @Test
    void oneSideDeletingTheFileIsSaidOutLoud() {
        List<String> ourDeletion = review(
                stages("mwo.owl", withLabel("Material"), null, withLabel("Material Thing")),
                "mwo.owl");
        assertEquals(1, ourDeletion.size(), ourDeletion.toString());
        assertTrue(ourDeletion.get(0).contains("this branch no longer has this file"),
                ourDeletion.get(0));

        List<String> theirDeletion = review(
                stages("mwo.owl", withLabel("Material"), withLabel("Material Entity"), null),
                "mwo.owl");
        assertEquals(1, theirDeletion.size(), theirDeletion.toString());
        assertTrue(theirDeletion.get(0).contains("the other branch no longer has this file"),
                theirDeletion.get(0));
    }

    /**
     * Both sides created the file. There is no ancestor, so there is nothing to diff against.
     *
     * <p>Diffing against an empty ancestor would report every term in the file as added on both
     * sides - a list nobody reads, which would hide the only thing worth knowing: these two
     * files have no shared history to merge along.
     */
    @Test
    void bothSidesCreatingTheFileIsReportedAsHavingNoCommonVersion() {
        List<String> lines = review(
                stages("new.ttl", null, withLabel("Specimen"), withLabel("Sample")), "new.ttl");

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("both branches created this file independently"),
                lines.get(0));
        assertTrue(lines.get(0).contains("no common version"), lines.get(0));
        assertTrue(lines.get(0).contains("axioms here"), lines.get(0));
    }

    /**
     * A STAGE THAT WILL NOT PARSE IS NOT A DELETION, and must not be reported as one.
     *
     * <p>This is the bug that asking git for the stage list exists to prevent. The first version
     * of this code inferred the shape of the conflict from which versions the OWL API could read,
     * so a stage git had - but which would not parse, because somebody committed a broken file or
     * a syntax this plugin does not know - came out as "this branch no longer has this file".
     * That is a confident lie about the repository, and the user's next move after reading it
     * would be wrong.
     */
    @Test
    void aStageThatWillNotParseIsNotReportedAsADeletion() {
        Map<String, String> kept = stages("mwo.owl", withLabel("Material"), WITH_MARKERS,
                withLabel("Material Thing"));

        List<String> lines = review(kept, "mwo.owl");

        assertEquals(1, lines.size(), lines.toString());
        assertFalse(lines.get(0).contains("no longer has this file"),
                "git kept this stage; saying it was deleted is a lie: " + lines.get(0));
        assertTrue(lines.get(0).contains("git kept all three versions"), lines.get(0));
        assertTrue(lines.get(0).contains("this branch will not parse"), lines.get(0));
    }

    /** When more than one version is unreadable, each is named. */
    @Test
    void everyUnreadableVersionIsNamed() {
        Map<String, String> kept = stages("mwo.owl", withLabel("Material"), WITH_MARKERS,
                "this is not an ontology either");

        List<String> lines = review(kept, "mwo.owl");

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("this branch and the other branch will not parse"),
                lines.get(0));
    }

    /**
     * Both sides removed the file. Nothing disagrees about its contents.
     *
     * <p>Measured: a plain delete/delete merge does not reach here at all, because git resolves
     * it silently and the file never appears in the status. {@code GitRepo.isUnmerged} admits
     * {@code DD} though, so the path is reachable, and what it must not say is that the versions
     * "could not be read" - which is what it said while the shape was inferred from parsing.
     *
     * <p>It does not say "both branches removed it" either, although that is what the stage set
     * literally means. {@code DD} is what a rename on both sides leaves behind for the OLD name,
     * and there nothing was removed at all - so the wording states what git holds and points at
     * the other conflicted paths rather than completing a story it cannot see.
     */
    @Test
    void bothSidesRemovingTheFileIsNotADisagreementAboutItsContents() {
        List<String> lines = review(stages("mwo.owl", withLabel("Material"), null, null),
                "mwo.owl");

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("neither branch has this file any more"), lines.get(0));
        assertTrue(lines.get(0).contains("renamed it differently"),
                "a rename on both sides leaves DD for the old name: " + lines.get(0));
        assertFalse(lines.get(0).contains("could not be read"), lines.get(0));
    }

    /** git listing no stages at all is said plainly rather than guessed around. */
    @Test
    void gitKeepingNoStagesIsSaidPlainly() {
        List<String> lines = review(Collections.<String, String>emptyMap(), "mwo.owl");

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("no longer keeps a conflicted version"), lines.get(0));
    }

    /**
     * git failing outright is reported as a line, not as an exception.
     *
     * <p>This runs while a commit is already being refused for a reason the user can act on.
     * Letting an IOException out would replace that reason with a stack trace about git.
     */
    @Test
    void gitFailingOutrightDoesNotThrow() {
        ProcessRunner.Runner broken = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File directory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                throw new IOException("git is not on the PATH");
            }
        };

        List<String> lines = ConflictReview.of(broken, new File("."),
                Collections.singletonList("mwo.owl"));

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("could not be compared"), lines.get(0));
        assertTrue(lines.get(0).contains("IOException"), lines.get(0));
    }

    /** Several conflicted ontologies each get their own block, in the order git listed them. */
    @Test
    void everyConflictedOntologyGetsItsOwnBlock() {
        Map<String, String> kept = new LinkedHashMap<String, String>();
        kept.putAll(stages("one.ttl", withLabel("Material"), withLabel("Material Entity"),
                withLabel("Material Thing")));
        kept.putAll(stages("two.ttl", withLabel("Material"), withLabel("Stuff"),
                withLabel("Matter")));

        List<String> lines = review(kept, "one.ttl", "README.md", "two.ttl");

        List<String> headings = new ArrayList<String>();
        for (String line : lines) {
            if (!line.startsWith("    ")) {
                headings.add(line.substring(0, line.indexOf(58)));
            }
        }
        assertEquals(Arrays.asList("one.ttl", "two.ttl"), headings, lines.toString());
    }

    /**
     * The same edit on both sides leaves nothing to choose between.
     *
     * <p>It is still a conflict as far as git is concerned - two branches touched the same lines -
     * and the commit is still refused. But telling a curator to choose between two identical
     * labels would teach them to skim the report, and the next one they skim will be real.
     */
    @Test
    void theSameEditOnBothSidesLeavesNothingToChoose() {
        List<String> lines = review(stages("mwo.owl", withLabel("Material"),
                withLabel("Material Entity"), withLabel("Material Entity")), "mwo.owl");

        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("nothing they both touched disagrees"), lines.get(0));
        assertTrue(lines.get(0).contains("1 was changed the same way"), lines.get(0));
        // git called these two versions conflicted, so "nothing disagrees" is not the whole
        // story and the report does not leave it reading as one.
        assertTrue(lines.get(1).contains("does not reach"), lines.get(1));
    }

    /**
     * TWO SIDES RENAMING ONE FILE IS NOT A DELETION, and must not be reported as one.
     *
     * <p>MEASURED on git 2.38.1: ours renames a.ttl to b.ttl, theirs renames it to c.ttl, and the
     * merge leaves {@code DD a.ttl}, {@code AU b.ttl}, {@code UA c.ttl} - stage 1 alone for the
     * old name, stage 2 alone for ours, stage 3 alone for theirs. {@code GitRepo.isUnmerged}
     * admits AU and UA, so they reach this code.
     *
     * <p>The first version tested for a missing stage 2 or 3 before asking whether git kept an
     * ancestor at all, and so told the curator that "the other branch no longer has b.ttl" - a
     * path that branch never had. Restoring it, which is what that sentence invites, throws away
     * one of the two renames; the real decision is only which name to keep.
     *
     * <p>No earlier test could reach this: a single-stage set needs a null ancestor together
     * with a null side, and every call passed an ancestor or both sides.
     */
    @Test
    void renamingOneFileOnBothSidesIsNotReportedAsADeletion() {
        assertTrue(review(stages("b.ttl", null, withLabel("Material"), null), "b.ttl")
                .get(0).contains("only this branch has this file"));
        assertTrue(review(stages("c.ttl", null, null, withLabel("Material")), "c.ttl")
                .get(0).contains("only the other branch has this file"));

        for (List<String> lines : Arrays.asList(
                review(stages("b.ttl", null, withLabel("Material"), null), "b.ttl"),
                review(stages("c.ttl", null, null, withLabel("Material")), "c.ttl"))) {
            assertFalse(lines.get(0).contains("no longer has this file"),
                    "git kept no ancestor, so neither side ever had it: " + lines.get(0));
            assertTrue(lines.get(0).contains("no common version"), lines.get(0));
        }
    }

    /**
     * A version one side emptied is readable, and says every term went.
     *
     * <p>git exits 0 with no output for an empty blob, which the first version read as "the
     * parser would not take it" and reported as a broken file. An empty document parses to an
     * ontology with no axioms, which is the truth about that side.
     */
    @Test
    void anEmptyVersionIsReadRatherThanCalledUnparseable() {
        Map<String, String> kept = stages("mwo.owl", withLabel("Material"), "",
                withLabel("Material Thing"));

        List<String> lines = review(kept, "mwo.owl");

        assertFalse(lines.isEmpty(), "an emptied side still deserves a report");
        assertFalse(lines.get(0).contains("will not parse"),
                "an empty file is not an unreadable one: " + lines.get(0));
    }

    /**
     * DURING A REBASE THE TWO SIDES ARE NOT TWO BRANCHES, and are not called that.
     *
     * <p>In a merge, stage 2 is this branch and stage 3 is the branch being merged in. A rebase,
     * cherry-pick or revert inverts the roles: stage 2 is what is already on the new base and
     * stage 3 is the commit being replayed. MEASURED: during {@code git rebase} the status still
     * prints {@code UU} and nothing in it says which operation is unresolved.
     *
     * <p>Why it matters more than wording usually does: told that their own edit is "the other
     * branch", a curator resolving a rebase conflict can discard exactly the work they are
     * trying to keep.
     */
    @Test
    void duringARebaseTheSidesAreNotCalledBranches() {
        Map<String, String> kept = stages("mwo.owl", withLabel("Material"),
                withLabel("Material Entity"), withLabel("Material Thing"));

        List<String> lines = ConflictReview.of(gitWith(kept, false), new File("."),
                Collections.singletonList("mwo.owl"));

        assertTrue(lines.get(0).contains("rebase, cherry-pick or revert is in progress"),
                lines.get(0));
        String collision = lines.get(lines.size() - 1);
        assertFalse(collision.contains("this branch"),
                "stage 2 is not this branch during a rebase: " + collision);
        assertFalse(collision.contains("the other branch"), collision);
        assertTrue(collision.contains("the version already here"), collision);
        assertTrue(collision.contains("the change being applied"), collision);
    }

    /** In an ordinary merge the sides keep the names a curator expects. */
    @Test
    void duringAMergeTheSidesAreTheTwoBranches() {
        Map<String, String> kept = stages("mwo.owl", withLabel("Material"),
                withLabel("Material Entity"), withLabel("Material Thing"));

        List<String> lines = review(kept, "mwo.owl");

        assertEquals(2, lines.size(), "no preamble for a plain merge: " + lines);
        assertTrue(lines.get(1).contains("this branch"), lines.get(1));
        assertTrue(lines.get(1).contains("the other branch"), lines.get(1));
    }

    /**
     * An unreachable import does not stop the report, and does not wait on the network.
     *
     * <p>MEASURED: a default-configured manager threw UnloadableImportException after 13.6
     * seconds on an ontology importing two unreachable IRIs, which would report a perfectly
     * readable ODK edit file as unparseable - and an ODK edit file imports its modules by IRI,
     * so that is the ordinary case. The port below refuses the connection at once, so this test
     * asserts the behaviour without depending on a network.
     */
    @Test
    void anUnreachableImportDoesNotStopTheReport() {
        String importing = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "<http://example.org/o> a owl:Ontology ;\n"
                + "    owl:imports <http://127.0.0.1:1/not-there.owl> .\n"
                + "<" + NS + "Material> a owl:Class ;\n"
                + "    rdfs:label \"Material Entity\" .\n";
        Map<String, String> kept = stages("mwo.owl", withLabel("Material"), importing,
                withLabel("Material Thing"));

        List<String> lines = review(kept, "mwo.owl");

        assertFalse(lines.isEmpty(), lines.toString());
        assertFalse(lines.get(0).contains("will not parse"),
                "the import is unreachable; the document is not: " + lines.get(0));
        assertTrue(lines.get(0).contains("changed on both sides"), lines.get(0));
    }
}
