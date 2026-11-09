package de.fizkarlsruhe.ise.ontoboard.assistant.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The assistant's settings that belong to the project, next to the ontology and so in its git
 * repository: the local-only switch and the team's roles (spec D3, D4).
 */
class ProjectFolderTest {

    @TempDir
    File dir;

    private File ontology() {
        return new File(dir, "pizza-edit.owl");
    }

    /** Review (1.137.0): roles are committed with the project, so a pasted key must not be. */
    @Test
    void aKeyPastedIntoARoleIsNotWritten() throws IOException {
        ProjectFolder.of(ontology()).roles(java.util.Collections::<String>emptyList).save("custom",
                "Use the key sk-proj-ABCDEFGHIJKLMNOPQRSTUV12345 to reach the model.");

        String written = new String(Files.readAllBytes(new File(new File(new File(dir,
                "assistant"), "roles"), "custom.md").toPath()), StandardCharsets.UTF_8);
        assertFalse(written.contains("sk-proj-"), written);
        assertTrue(written.contains("(a key)"), written);
    }

    /**
     * Review 1.141.0 I5: a key OntoBoard knows but that has no known shape (Mistral's, a
     * self-hosted server's) was written into a role file, and the roles folder is committed.
     */
    @Test
    void aKnownKeyIsRedactedFromARoleFile() throws IOException {
        String key = "Mx7pLq2Rt9Vw4Yz8Ab3Cd6Ef1Gh5Jk0";

        ProjectFolder.of(ontology()).roles(() -> java.util.Collections.singletonList(key))
                .save("custom", "Reach the model with " + key + " please.");

        String written = new String(Files.readAllBytes(new File(new File(new File(dir,
                "assistant"), "roles"), "custom.md").toPath()), StandardCharsets.UTF_8);
        assertFalse(written.contains(key), written);
        assertTrue(written.contains("(your key)"), written);
    }

    @Test
    void savingConversationsIsOffWithoutAFile() {
        assertFalse(ProjectFolder.of(ontology()).isSavingConversations());
    }

    /** One file, two switches: setting one must not reset the other. */
    @Test
    void savingConversationsRoundTripsBesideLocalOnly() throws IOException {
        ProjectFolder folder = ProjectFolder.of(ontology());

        folder.setLocalModelsOnly(true);
        folder.setSavingConversations(true);

        assertTrue(ProjectFolder.of(ontology()).isSavingConversations());
        assertTrue(ProjectFolder.of(ontology()).isLocalModelsOnly());
        folder.setLocalModelsOnly(false);
        assertTrue(ProjectFolder.of(ontology()).isSavingConversations());
    }

    private String gitignore() throws IOException {
        return new String(Files.readAllBytes(new File(new File(dir, "assistant"), ".gitignore")
                .toPath()), StandardCharsets.UTF_8);
    }

    private void writeGitignore(String text) throws IOException {
        File folder = new File(dir, "assistant");
        folder.mkdirs();
        Files.write(new File(folder, ".gitignore").toPath(),
                text.getBytes(StandardCharsets.UTF_8));
    }

    private static final String BLOCK = "# OntoBoard: saved conversations stay out of git unless"
            + " their author released them (Settings > Save conversations in this project).\n"
            + "conversations/*\n!conversations/released/\n";

    private static List<String> names(List<File> files) {
        List<String> found = new java.util.ArrayList<String>();
        for (File file : files) {
            found.add(file.getName());
        }
        return found;
    }

    /**
     * 1.144.0 (review 1.143.0 I-1): one block, the same whoever writes it, so a colleague who
     * commits it changes nothing for anyone; each person's choice only says which folder their
     * own conversations go to.
     */
    @Test
    void theRulesAreOneBlockWrittenOnce() throws IOException {
        ProjectFolder folder = ProjectFolder.of(ontology());

        folder.ensureConversationRules();
        folder.ensureConversationRules();

        assertEquals(BLOCK, gitignore());
    }

    @Test
    void anExistingGitignoreKeepsItsOtherLinesAndItsLineEndings() throws IOException {
        writeGitignore("*.tmp\r\nnotes/\r\n");

        ProjectFolder.of(ontology()).ensureConversationRules();

        assertEquals("*.tmp\r\nnotes/\r\n" + BLOCK.replace("\n", "\r\n"), gitignore());
    }

    @Test
    void aLastLineWithoutANewlineIsNotJoinedToTheBlock() throws IOException {
        writeGitignore("*.tmp");

        ProjectFolder.of(ontology()).ensureConversationRules();

        assertEquals("*.tmp\n" + BLOCK, gitignore());
    }

    /**
     * The 1.142.0 rule ignored the folder itself, and git cannot take back a file inside an
     * ignored folder: released conversations would stay ignored.
     */
    @Test
    void theOldRuleIsReplaced() throws IOException {
        writeGitignore("# OntoBoard: saved conversations stay out of git (Settings > Save"
                + " conversations in this project).\nconversations/\n*.bak\n");

        ProjectFolder.of(ontology()).ensureConversationRules();

        assertEquals("*.bak\n" + BLOCK, gitignore());
    }

    /** Review 1.143.0 M-1: a later line that takes conversations back cannot come after it. */
    @Test
    void theBlockStaysLast() throws IOException {
        writeGitignore(BLOCK + "!conversations/\n");

        ProjectFolder.of(ontology()).ensureConversationRules();

        assertEquals("!conversations/\n" + BLOCK, gitignore());
    }

    /**
     * Review 1.144.0 privacy-02: Windows PowerShell 5.1 writes a byte-order mark first; git skips
     * it, and the check that a .gitignore inside conversations/ takes nothing back did not.
     */
    @Test
    void aNegationAfterAByteOrderMarkInsideConversationsStillStopsSaving() throws IOException {
        File inner = new File(new File(new File(dir, "assistant"), "conversations"), ".gitignore");
        inner.getParentFile().mkdirs();
        byte[] rules = "!*.md\n".getBytes(StandardCharsets.UTF_8);
        byte[] withMark = new byte[rules.length + 3];
        withMark[0] = (byte) 0xEF;
        withMark[1] = (byte) 0xBB;
        withMark[2] = (byte) 0xBF;
        System.arraycopy(rules, 0, withMark, 3, rules.length);
        Files.write(inner.toPath(), withMark);

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> ProjectFolder.of(ontology()).ensureConversationRules());
    }

    /**
     * Review 1.144.0 privacy-08: the key was the path as opened, so a project reached through a
     * junction had two keys - and its person two independent choices about releasing.
     */
    @Test
    void aProjectReachedThroughALinkHasOneKey() throws Exception {
        File real = new File(dir, "real");
        real.mkdirs();
        File link = new File(dir, "link");
        Links.link(link, real);

        String before = ProjectFolder.of(new File(link, "pizza-edit.owl")).key();
        new File(real, "assistant").mkdirs();

        assertEquals(ProjectFolder.of(new File(real, "pizza-edit.owl")).key(), before);
        assertEquals(ProjectFolder.of(new File(real, "pizza-edit.owl")).key(),
                ProjectFolder.of(new File(link, "pizza-edit.owl")).key());
    }

    /**
     * The 1.148.0 recorder reviewer's notes: the canonical form of the path as opened was worked
     * out before any clone was tried, so its failure cost the real path's clone its rules too. A
     * File that cannot give its canonical form stands in for such a path; the clone is made by
     * hand, as git lays one out, so that no git is needed.
     */
    @Test
    void aPathWithoutACanonicalFormStillGivesTheRealPathsCloneItsRules() throws IOException {
        File repo = new File(dir, "repo");
        File git = new File(repo, ".git");
        new File(git, "objects").mkdirs();
        new File(git, "refs").mkdirs();
        Files.write(new File(git, "HEAD").toPath(),
                "ref: refs/heads/main\n".getBytes(StandardCharsets.UTF_8));
        File conversations = new File(new File(new File(repo, "onto"), "assistant"),
                "conversations");
        conversations.mkdirs();
        final File unresolvable = new File(conversations.getAbsolutePath()) {
            private static final long serialVersionUID = 1L;

            @Override
            public File getAbsoluteFile() {
                return this;
            }

            @Override
            public File getCanonicalFile() throws IOException {
                throw new IOException("made up: no canonical form here");
            }

            @Override
            public String getCanonicalPath() throws IOException {
                throw new IOException("made up: no canonical form here");
            }
        };

        IOException failed = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> GitExclude.keepOutOfGit(unresolvable));

        File exclude = new File(new File(git, "info"), "exclude");
        assertTrue(exclude.isFile(), "the real path's clone got no rules: " + failed);
        String rules = new String(Files.readAllBytes(exclude.toPath()), StandardCharsets.UTF_8);
        assertTrue(rules.contains("/onto/assistant/conversations/*"), rules);
        assertTrue(String.valueOf(failed.getMessage()).contains("made up"), String.valueOf(failed));
    }

    /** A clone made by hand, as git makes one, with its conversations folder. */
    private File cloneWithConversations(String name) throws IOException {
        File repo = new File(dir, name);
        File git = new File(repo, ".git");
        new File(git, "objects").mkdirs();
        new File(git, "refs").mkdirs();
        Files.write(new File(git, "HEAD").toPath(),
                "ref: refs/heads/main\n".getBytes(StandardCharsets.UTF_8));
        File conversations = new File(new File(new File(repo, "onto"), "assistant"),
                "conversations");
        conversations.mkdirs();
        return conversations;
    }

    /**
     * The review of the recorder-01 fix: every folder on the way was followed to where it really
     * is, and one that could not be - its parent may be entered but not listed, as on a share -
     * was said once per conversation, and on every tick, as rules that could not be written,
     * though each clone had them. Only a link or a junction leads elsewhere.
     */
    @Test
    void aFolderOnTheWayThatIsNoLinkIsNotFollowed() throws IOException {
        File conversations = cloneWithConversations("plain");
        GitExclude.Follow before = GitExclude.follow;
        GitExclude.follow = folder -> {
            throw new java.nio.file.AccessDeniedException(folder.toString());
        };
        try {
            GitExclude.keepOutOfGit(conversations);
        } finally {
            GitExclude.follow = before;
        }

        File exclude = new File(new File(new File(new File(dir, "plain"), ".git"), "info"),
                "exclude");
        String rules = new String(Files.readAllBytes(exclude.toPath()), StandardCharsets.UTF_8);
        assertTrue(rules.contains("/onto/assistant/conversations/*"), rules);
    }

    /**
     * The same review: a junction that could not be followed was said as "the rules could not be
     * put into your clone's own .git/info/exclude". It names the junction, and that a clone it
     * leads into may lack the rules. Windows only: elsewhere Links makes a symbolic link, which
     * the canonical path has followed already.
     */
    @Test
    void aJunctionThatCannotBeFollowedIsSaidAsSuch() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).startsWith("windows"));
        File conversations = cloneWithConversations("target");
        File junction = new File(dir, "through");
        Links.link(junction, conversations.getParentFile().getParentFile());
        File opened = new File(new File(junction, "assistant"), "conversations");
        GitExclude.Follow before = GitExclude.follow;
        GitExclude.follow = folder -> {
            throw new java.nio.file.AccessDeniedException(folder.toString());
        };
        IOException failed;
        try {
            failed = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                    () -> GitExclude.keepOutOfGit(opened));
        } finally {
            GitExclude.follow = before;
        }

        String said = de.fizkarlsruhe.ise.ontoboard.assistant.history.ConversationRecorder
                .excludeProblem(ProjectFolder.of(new File(junction, "pizza-edit.owl")), failed);
        assertTrue(said.contains("could not follow " + junction.getAbsolutePath()), said);
        assertTrue(said.contains("access denied"), said);
        assertFalse(said.contains("could not be put") || said.contains("could not be written"),
                said);
    }

    /** A clone made by hand, as git makes one: its top folder. */
    private File madeClone(String name) throws IOException {
        File repo = new File(dir, name);
        File git = new File(repo, ".git");
        new File(git, "objects").mkdirs();
        new File(git, "refs").mkdirs();
        Files.write(new File(git, "HEAD").toPath(),
                "ref: refs/heads/main\n".getBytes(StandardCharsets.UTF_8));
        return repo;
    }

    /**
     * Review 1.150.0 recorder-01: B\J1 leads to C\x, and C\x\J2 to A\proj; opened as B\J1\J2, J1
     * cannot be followed and B's exclude is read-only. The junction was thrown only when every
     * exclude could be written, so B's failure, said once and failing at every save, kept it
     * unsaid, and C - which git goes into through J1 - unknown to anyone. It comes after the
     * exclude failures, as suppressed. Windows only: elsewhere Links makes symbolic links, which
     * the canonical path has followed already.
     */
    @Test
    void aJunctionThatCannotBeFollowedComesAfterTheExcludesThatCannotBeWritten()
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).startsWith("windows"));
        File a = madeClone("A");
        File proj = new File(a, "proj");
        proj.mkdirs();
        File x = new File(madeClone("C"), "x");
        x.mkdirs();
        File b = madeClone("B");
        Links.link(new File(x, "J2"), proj);
        File j1 = new File(b, "J1");
        Links.link(j1, x);
        File excludeB = new File(new File(new File(b, ".git"), "info"), "exclude");
        excludeB.getParentFile().mkdirs();
        Files.write(excludeB.toPath(), new byte[0]);
        org.junit.jupiter.api.Assumptions.assumeTrue(excludeB.setWritable(false),
                "the exclude cannot be made read-only here");
        File opened = new File(new File(new File(j1, "J2"), "assistant"), "conversations");
        opened.mkdirs();
        IOException failed;
        try (AutoCloseable hook = Links.unfollowable("J1")) {
            failed = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                    () -> GitExclude.keepOutOfGit(opened));
        } finally {
            excludeB.setWritable(true);
        }

        assertTrue(failed instanceof java.nio.file.AccessDeniedException, String.valueOf(failed));
        assertEquals(excludeB.getCanonicalPath(),
                ((java.nio.file.FileSystemException) failed).getFile());
        Throwable[] more = failed.getSuppressed();
        assertEquals(1, more.length, java.util.Arrays.toString(more));
        assertTrue(more[0] instanceof ProjectFolder.NotFollowed, String.valueOf(more[0]));
        assertEquals(j1.getAbsoluteFile(), ((ProjectFolder.NotFollowed) more[0]).getFolder());
    }

    /**
     * Review of the 1.151.0 recorder fix: W\S leads to J\S as written in it, and J is a junction
     * to W, so each round of the walk met S again under a longer name - W\J\S, W\J\J\S - until
     * Windows' limit of reparse points: 18 s, at every save, on the Swing thread, when the
     * conversations folder is such a link. Targets as written are walked a few times at most;
     * then the link is followed to where it really is, which fails in a circle, and that is said.
     */
    @Test
    void aLinkLeadingBackToItselfThroughAJunctionIsWalkedAFewTimesAtMost() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).startsWith("windows"),
                "a junction is made only on Windows");
        File w = madeClone("W");
        Links.link(new File(w, "J"), w);
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/D", "S", "J\\S")
                .directory(w).redirectErrorStream(true).start();
        byte[] ignored = new byte[1024];
        while (process.getInputStream().read(ignored) >= 0) {
            // Read it so that cmd never blocks on a full pipe.
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(process.waitFor() == 0,
                "no directory symbolic link can be made here");
        File conversations = new File(new File(new File(w, "S"), "assistant"), "conversations");
        List<java.nio.file.Path> walked = new java.util.ArrayList<java.nio.file.Path>();
        java.util.function.Consumer<java.nio.file.Path> before = GitExclude.reading;
        GitExclude.reading = walked::add;
        try {
            org.junit.jupiter.api.Assertions.assertThrows(ProjectFolder.NotFollowed.class,
                    () -> GitExclude.keepOutOfGit(conversations));
        } finally {
            GitExclude.reading = before;
        }

        assertTrue(walked.size() <= 8, walked.size() + " walked: " + walked);
    }

    /**
     * Review 1.149.0 recorder-04: each save walked from every view of the folder to the top of its
     * clone, or to the root, and looked again at each folder an earlier view had looked at - on
     * the Swing thread, at every answer, decision and reasoner check. A project opened through a
     * link, in no clone: both views lead up through the same folders. Each is looked at once.
     */
    @Test
    void eachFolderIsLookedAtOnceHoweverManyViewsLeadThere() throws Exception {
        File real = new File(new File(dir, "real"), "onto");
        real.mkdirs();
        File link = new File(dir, "link");
        Links.link(link, real);
        File conversations = new File(new File(link, "assistant"), "conversations");
        conversations.mkdirs();
        List<File> looked = new java.util.ArrayList<File>();
        java.util.function.Consumer<File> before = GitExclude.looking;
        GitExclude.looking = looked::add;
        try {
            GitExclude.keepOutOfGit(conversations);
        } finally {
            GitExclude.looking = before;
        }

        java.util.Set<File> once = new java.util.HashSet<File>();
        List<File> again = new java.util.ArrayList<File>();
        for (File folder : looked) {
            if (!once.add(folder)) {
                again.add(folder);
            }
        }
        assertTrue(looked.contains(dir.getCanonicalFile()), "the walk went up: " + looked);
        assertTrue(again.isEmpty(), "looked at again: " + again + ", of " + looked);
    }

    /** The choice is stored before the first save makes the folder: its key must not change. */
    @Test
    void theKeyIsTheSameBeforeAndAfterTheFolderExists() throws IOException {
        ProjectFolder folder = ProjectFolder.of(ontology());
        String before = folder.key();

        folder.getDirectory().mkdirs();

        assertEquals(before, folder.key());
    }

    @Test
    void releasedConversationsHaveAFolderOfTheirOwn() {
        ProjectFolder folder = ProjectFolder.of(ontology());
        File conversations = new File(new File(dir, "assistant"), "conversations");

        assertEquals(conversations, folder.conversationsDirectory(
                ProjectFolder.ConversationSharing.PRIVATE));
        assertEquals(conversations, folder.conversationsDirectory(
                ProjectFolder.ConversationSharing.UNDECIDED));
        assertEquals(new File(conversations, "released"), folder.conversationsDirectory(
                ProjectFolder.ConversationSharing.RELEASED));
    }

    @Test
    void theListHoldsPrivateAndReleasedConversationsNewestFirst() throws IOException {
        ProjectFolder folder = ProjectFolder.of(ontology());
        File mine = folder.conversationsDirectory(ProjectFolder.ConversationSharing.PRIVATE);
        File released = folder.conversationsDirectory(ProjectFolder.ConversationSharing.RELEASED);
        released.mkdirs();
        Files.write(new File(mine, "2026-11-01-100000-a.md").toPath(),
                "a".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(released, "2026-11-02-100000-b.md").toPath(),
                "b".getBytes(StandardCharsets.UTF_8));

        assertEquals(java.util.Arrays.asList("2026-11-02-100000-b.md", "2026-11-01-100000-a.md"),
                names(folder.conversations()));
    }

    /** The project file is shared, so it holds nobody's choice; a 1.142.0 choice in it goes. */
    @Test
    void theProjectFileHoldsNoSharingChoice() throws IOException {
        File properties = new File(new File(dir, "assistant"), "project.properties");
        properties.getParentFile().mkdirs();
        Files.write(properties.toPath(), "saveConversations=true\nreleaseConversations=true\n"
                .getBytes(StandardCharsets.UTF_8));

        ProjectFolder.of(ontology()).setLocalModelsOnly(true);

        String written = new String(Files.readAllBytes(properties.toPath()),
                StandardCharsets.UTF_8);
        assertFalse(written.contains("releaseConversations"), written);
        assertTrue(written.contains("saveConversations=true"), written);
    }

    @Test
    void conversationsAreListedNewestFirst() throws IOException {
        File conversations = new File(new File(dir, "assistant"), "conversations");
        conversations.mkdirs();
        new File(conversations, "2026-11-05-090000-ollama.md").createNewFile();
        new File(conversations, "2026-11-06-143012-claude.md").createNewFile();
        new File(conversations, "notes.txt").createNewFile();

        java.util.List<File> listed = ProjectFolder.of(ontology()).conversations();

        assertEquals(2, listed.size());
        assertEquals("2026-11-06-143012-claude.md", listed.get(0).getName());
    }

    @Test
    void noFileMeansNoProjectFolder() {
        assertNull(ProjectFolder.of(null));
    }

    @Test
    void theFolderIsNextToTheOntology() {
        assertEquals(new File(dir, "assistant"), ProjectFolder.of(ontology()).getDirectory());
    }

    @Test
    void localOnlyIsOffWithoutAFile() {
        assertFalse(ProjectFolder.of(ontology()).isLocalModelsOnly());
    }

    @Test
    void localOnlyRoundTrips() throws IOException {
        ProjectFolder.of(ontology()).setLocalModelsOnly(true);

        assertTrue(ProjectFolder.of(ontology()).isLocalModelsOnly());
        String written = new String(Files.readAllBytes(
                new File(dir, "assistant/project.properties").toPath()), StandardCharsets.UTF_8);
        assertTrue(written.contains("localModelsOnly=true"), written);
        assertFalse(written.matches("(?s).*\\d{2}:\\d{2}:\\d{2}.*"),
                "no timestamp, so saving twice does not change a committed file: " + written);

        ProjectFolder.of(ontology()).setLocalModelsOnly(false);
        assertFalse(ProjectFolder.of(ontology()).isLocalModelsOnly());
    }

    @Test
    void rolesAreFilesNextToTheOntology() throws IOException {
        ProjectFolder.of(ontology()).roles(java.util.Collections::<String>emptyList).save("x", "# X\nText ä.");

        File file = new File(dir, "assistant/roles/x.md");
        assertTrue(file.isFile());
        assertEquals("# X\nText ä.",
                new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        assertEquals("# X\nText ä.", ProjectFolder.of(ontology()).roles(java.util.Collections::<String>emptyList).load().get("x"));

        ProjectFolder.of(ontology()).roles(java.util.Collections::<String>emptyList).remove("x");
        assertFalse(file.exists());
    }

    /**
     * Review 1.149.0 ui-2: the roles folder says whether two names that differ only in case are
     * one file there, as on Windows and macOS, or two, as on Linux - before the folder exists
     * too. Asked of the file system next to the ontology, as the roles are saved there.
     */
    @Test
    void theRolesFolderIgnoresCaseWhereItsFileSystemDoes() throws IOException {
        File probe = new File(dir, "case-probe.md");
        Files.write(probe.toPath(), new byte[0]);
        boolean oneFile = new File(dir, "CASE-PROBE.md").exists();

        boolean said = ProjectFolder.of(ontology()).roles(
                java.util.Collections::<String>emptyList).ignoresCase();

        assertEquals(oneFile, said);
    }

    @Test
    void nothingIsWrittenUntilSomethingIsSaved() throws IOException {
        ProjectFolder folder = ProjectFolder.of(ontology());
        folder.isLocalModelsOnly();
        folder.roles(java.util.Collections::<String>emptyList).load();

        assertFalse(new File(dir, "assistant").exists());
    }
}
