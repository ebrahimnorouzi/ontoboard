package de.fizkarlsruhe.ise.ontoboard.odk;

import static de.fizkarlsruhe.ise.ontoboard.odk.ReleaseHistoryTest.assumeGit;
import static de.fizkarlsruhe.ise.ontoboard.odk.ReleaseHistoryTest.demo;
import static de.fizkarlsruhe.ise.ontoboard.odk.ReleaseHistoryTest.git;
import static de.fizkarlsruhe.ise.ontoboard.odk.ReleaseHistoryTest.kept;
import static de.fizkarlsruhe.ise.ontoboard.odk.ReleaseHistoryTest.tagged;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The releases of a project are listed without asking git again while its tags have not changed.
 *
 * <p>Two git processes per listing - the tags, and which of them hold the ontology - took about
 * 0.2 s on the machine they were measured on, and every new class on the board, every focus of
 * the guide and every History asked them on the Swing thread. So what git said is remembered, and
 * asked again only when the repository's tag refs may have changed: a tag made, deleted or moved,
 * the refs packed. A remembered answer that is wrong would let an identifier a release carried be
 * given out again, so each way a tag can change is tried here.
 */
class ReleaseTagsTest {

    /** The real git, with every call through it counted: the tags are listed through it. */
    private static final class Counting implements ProcessRunner.Runner {
        int calls;

        @Override
        public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
            calls++;
            return ProcessRunner.real().run(workingDirectory, command, timeoutMinutes, sink);
        }
    }

    /**
     * Makes the repository's tag refs a minute old, as if nobody had touched them lately - so an
     * answer git gives about them may be kept.
     */
    private static void settle(File root) {
        dateTheRefs(root, System.currentTimeMillis() - 60_000);
    }

    /**
     * Makes the repository's tag refs changed just now - not left to the clock: on a busy
     * machine the git commands that made them can take longer than the three seconds an
     * answer is not kept for, and the whole suite at 6ffacf9 saw it so.
     */
    private static void justChanged(File root) {
        dateTheRefs(root, System.currentTimeMillis());
    }

    private static void dateTheRefs(File root, long past) {
        File git = new File(root, ".git");
        List<File> pending = new ArrayList<File>();
        pending.add(new File(git, "refs/tags"));
        while (!pending.isEmpty()) {
            File file = pending.remove(pending.size() - 1);
            if (!file.exists()) {
                continue;
            }
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    pending.add(child);
                }
            }
            assertTrue(file.setLastModified(past), "could not date " + file);
        }
        File packed = new File(git, "packed-refs");
        if (packed.exists()) {
            assertTrue(packed.setLastModified(past), "could not date " + packed);
        }
    }

    private static List<ReleaseHistory.Entry> list(File root, ProcessRunner.Runner git) {
        return ReleaseHistory.releasesOf(root, "demo", git);
    }

    private static String versionsOf(List<ReleaseHistory.Entry> releases) {
        List<String> versions = new ArrayList<String>();
        for (ReleaseHistory.Entry release : releases) {
            versions.add(release.version());
        }
        return versions.toString();
    }

    /** What a listing says: each release's version, source, tag and blob. */
    private static String said(List<ReleaseHistory.Entry> releases) {
        List<String> said = new ArrayList<String>();
        for (ReleaseHistory.Entry release : releases) {
            said.add(release.version() + " " + release.source() + " " + release.tag() + " "
                    + release.blob());
        }
        return said.toString();
    }

    /** Two listings with nothing in between: git is asked for the first only. */
    @Test
    void twoListingsWithNothingBetweenAskGitOnce(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        tagged(root, "1.0.0", demo("Egg"));
        tagged(root, "1.1.0", demo("Egg", "Pan"));
        settle(root);
        Counting counting = new Counting();
        int held = GitTags.holdingAsked();

        String first = said(list(root, counting));
        String second = said(list(root, counting));

        assertEquals(first, second);
        assertTrue(first.startsWith("[1.1.0 TAG v1.1.0 "), first);
        assertEquals(1, counting.calls, "the tags listed once");
        assertEquals(held + 1, GitTags.holdingAsked(), "which hold the ontology asked once");
    }

    /**
     * A tag made after a listing is in the next one - made at once after it, too, within the
     * file system's time resolution of the listing.
     */
    @Test
    void aTagMadeAfterAListingIsInTheNext(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        tagged(root, "1.0.0", demo("Egg"));
        settle(root);
        Counting counting = new Counting();
        assertEquals("[1.0.0]", versionsOf(list(root, counting)));

        tagged(root, "1.1.0", demo("Egg", "Pan"));
        assertEquals("[1.1.0, 1.0.0]", versionsOf(list(root, counting)));

        // And where the refs had just changed when git was asked, so its answer was not kept.
        tagged(root, "1.2.0", demo("Egg", "Pan", "Lid"));
        assertEquals("[1.2.0, 1.1.0, 1.0.0]", versionsOf(list(root, counting)));
        tagged(root, "1.3.0", demo("Egg", "Pan", "Lid", "Cup"));
        assertEquals("[1.3.0, 1.2.0, 1.1.0, 1.0.0]", versionsOf(list(root, counting)));
    }

    /** A tag deleted is gone from the next listing. */
    @Test
    void aTagDeletedIsGone(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        tagged(root, "1.0.0", demo("Egg"));
        tagged(root, "1.1.0", demo("Egg", "Pan"));
        settle(root);
        Counting counting = new Counting();
        assertEquals("[1.1.0, 1.0.0]", versionsOf(list(root, counting)));

        git(root, "tag", "-d", "v1.1.0");

        assertEquals("[1.0.0]", versionsOf(list(root, counting)));
    }

    /** A tag moved to another commit ({@code git tag -f}) is read where it is now. */
    @Test
    void aTagMovedGivesTheNewBlob(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        tagged(root, "1.0.0", demo("Egg"));
        byte[] fixed = demo("Egg", "Pan");
        File file = new File(root, "demo.owl");
        Files.write(file.toPath(), fixed);
        git(root, "add", "-f", "demo.owl");
        git(root, "commit", "-q", "-m", "the release, fixed");
        settle(root);
        Counting counting = new Counting();
        String before = list(root, counting).get(0).blob();

        git(root, "tag", "-f", "-a", "v1.0.0", "-m", "demo 1.0.0, fixed");

        ReleaseHistory.Entry moved = list(root, counting).get(0);
        assertNotEquals(before, moved.blob(), "the blob of the commit the tag is on now");
        assertArrayEquals(fixed, moved.bytes());
    }

    /**
     * Packing the refs changes how git keeps them, not what they say: the answer is the same.
     * A tag deleted from the packed refs is gone, as one deleted from its own file.
     */
    @Test
    void packingTheRefsChangesNothingInTheAnswer(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        tagged(root, "1.0.0", demo("Egg"));
        tagged(root, "1.1.0", demo("Egg", "Pan"));
        settle(root);
        Counting counting = new Counting();
        String loose = said(list(root, counting));

        git(root, "pack-refs", "--all");
        assertFalse(new File(root, ".git/refs/tags/v1.0.0").exists(), "packed");
        assertEquals(loose, said(list(root, counting)));

        settle(root);
        assertEquals(loose, said(list(root, counting)));
        git(root, "tag", "-d", "v1.1.0");
        assertEquals("[1.0.0]", versionsOf(list(root, counting)));
    }

    /** No .git in the project's folder or any above it: the folders, and no process at all. */
    @Test
    void aFolderWithoutARepositoryStartsNoProcess(@TempDir File root) throws Exception {
        for (File folder = root.getAbsoluteFile(); folder != null;
                folder = folder.getParentFile()) {
            assumeTrue(!new File(folder, ".git").exists(),
                    "the temporary folder is inside a repository here: " + folder);
        }
        kept(root, "1.0.0", demo("Egg"));
        Counting counting = new Counting();
        int held = GitTags.holdingAsked();

        assertEquals("[1.0.0]", versionsOf(list(root, counting)));
        assertEquals("[1.0.0]", versionsOf(list(root, counting)));

        assertEquals(0, counting.calls, "no tags asked for");
        assertEquals(held, GitTags.holdingAsked(), "nothing else either");
    }

    /**
     * A linked worktree has a .git file, not a folder: where its refs are is git's to say, so git
     * is asked each time.
     */
    @Test
    void aLinkedWorktreeAsksGitEachTime(@TempDir File temp) throws Exception {
        assumeGit();
        File root = new File(temp, "main");
        assertTrue(root.mkdirs());
        git(root, "init", "-q");
        tagged(root, "1.0.0", demo("Egg"));
        File linked = new File(temp, "linked");
        git(root, "worktree", "add", "-q", linked.getAbsolutePath());
        assertTrue(new File(linked, ".git").isFile());
        settle(root);
        Counting counting = new Counting();

        assertEquals("[1.0.0]", versionsOf(list(linked, counting)));
        assertEquals("[1.0.0]", versionsOf(list(linked, counting)));

        assertEquals(2, counting.calls);
    }

    /**
     * Refs that changed under three seconds before git was asked may change again within the
     * same tick of the file system's clock, unseen: that answer is not kept, as git does not
     * trust its index then.
     */
    @Test
    void anAnswerAboutRefsJustChangedIsNotKept(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        tagged(root, "1.0.0", demo("Egg"));
        justChanged(root);
        Counting counting = new Counting();

        list(root, counting);
        list(root, counting);

        assertEquals(2, counting.calls);
    }

    /**
     * A .git folder git would not take for a repository - an empty one, say - is not where the
     * tags are: git looks further up, and so the answer is not kept by that folder's refs.
     */
    @Test
    void aGitFolderThatIsNoRepositoryIsNotWhereTheTagsAre(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        File project = new File(root, "inner");
        assertTrue(new File(project, ".git").mkdirs());
        Files.write(new File(project, "demo.owl").toPath(), demo("Egg"));
        git(root, "add", "-f", "inner/demo.owl");
        git(root, "commit", "-q", "-m", "release(1.0.0)");
        git(root, "tag", "-a", "v1.0.0", "-m", "demo 1.0.0");
        settle(root);
        Counting counting = new Counting();
        assertEquals("[1.0.0]", versionsOf(list(project, counting)));

        Files.write(new File(project, "demo.owl").toPath(), demo("Egg", "Pan"));
        git(root, "add", "-f", "inner/demo.owl");
        git(root, "commit", "-q", "-m", "release(1.1.0)");
        git(root, "tag", "-a", "v1.1.0", "-m", "demo 1.1.0");

        assertEquals("[1.1.0, 1.0.0]", versionsOf(list(project, counting)));
        assertEquals(2, counting.calls);
    }

    /** Refs kept in a reftable are not files under refs/tags: git is asked each time. */
    @Test
    void refsInAReftableAreAskedForEachTime(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        tagged(root, "1.0.0", demo("Egg"));
        assertTrue(new File(root, ".git/reftable").mkdirs());
        settle(root);
        Counting counting = new Counting();

        list(root, counting);
        list(root, counting);

        assertEquals(2, counting.calls);
    }
}
