package de.fizkarlsruhe.ise.ontoboard.assistant.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ChatRequest;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelClient;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Reply;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.StreamListener;
import de.fizkarlsruhe.ise.ontoboard.assistant.project.ProjectFolder;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.AssistantSession;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.OntologyAccess;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/** "Save conversations in this project": after every answer, and after every decision. */
class ConversationRecorderTest {

    @TempDir
    File dir;

    private final OWLOntology pizza = MiniPizza.load();
    private final List<String> problems = new ArrayList<String>();
    private String reply = "ok";
    private ProjectFolder project;
    private AssistantSession session;
    /** This person's choice for their own conversations (1.144.0, review 1.143.0 I-1). */
    private ProjectFolder.ConversationSharing sharing = ProjectFolder.ConversationSharing.UNDECIDED;
    private final List<String> notices = new ArrayList<String>();
    /** The keys OntoBoard knows at each save; a test can replace them, as Settings does. */
    private List<String> currentKeys = new ArrayList<String>(
            Arrays.asList("sk-secret-key-0123456789"));
    /**
     * Done once inside the next model call, while its answer is coming: where a click in
     * Settings, or a pulled project file, lands in Protégé (1.146.0, review 1.145.0 recorder-01).
     */
    private Runnable whileAnswering;
    /**
     * While true, a model call waits in {@link #calls} until the test runs it, as on Protégé's
     * background thread: run at once, the answer ends inside send() (review 1.145.0 recorder-03).
     */
    private boolean queueCalls;
    private final List<Runnable> calls = new ArrayList<Runnable>();
    /** While true, this person's choice cannot be read. */
    private boolean sharingUnreadable;
    /** An answer with one proposal, a definition: something to accept or revise. */
    private static final String DEFINITION = "```ontoboard-proposal\naction: set\n"
            + "subject: vegetarian pizza\nproperty: definition\n"
            + "value: A pizza whose toppings are all vegetarian.\n```";

    @BeforeEach
    void open() {
        final File file = new File(dir, "pizza-edit.owl");
        project = ProjectFolder.of(file);
        session = new AssistantSession(new OntologyAccess() {
            @Override
            public OWLOntology ontology() {
                return pizza;
            }

            @Override
            public IRI selected() {
                return MiniPizza.iri("VegetarianPizza");
            }

            @Override
            public File ontologyFile() {
                return file;
            }
        }, call -> {
            if (queueCalls) {
                calls.add(call);
            } else {
                call.run();
            }
        }, Runnable::run);
        session.setClientFactory((connection, key) -> new ModelClient() {
            @Override
            public List<String> listModels() {
                return Collections.emptyList();
            }

            @Override
            public Reply chat(ChatRequest request, StreamListener listener) {
                listener.onText(reply);
                Runnable during = whileAnswering;
                whileAnswering = null;
                if (during != null) {
                    during.run();
                }
                return new Reply(reply, 10, 5, "stop", 100);
            }

            @Override
            public void stop() {
            }
        });
        session.setConnection(Connection.of("Ollama", ConnectionKind.OLLAMA)
                .withModel("qwen2.5:7b"), "");
        session.setProposalApplier(changes -> pizza.getOWLOntologyManager().applyChanges(changes));
        ConversationRecorder.attach(session, () -> project, this::sharingOf,
                turn -> new ConversationLog.Header(turn.getStarted(), "1.137.0",
                        "http://example.org/mini-pizza", null, "Ollama", "ollama", "localhost",
                        "qwen2.5:7b", "Ontology expert"),
                () -> currentKeys, notices::add, problems::add);
    }

    /** This person's choice, read from their preferences at each save and each question. */
    private ProjectFolder.ConversationSharing sharingOf(ProjectFolder folder) {
        if (sharingUnreadable) {
            throw new IllegalStateException("the preferences cannot be read");
        }
        return sharing;
    }

    private String only() throws IOException {
        assertEquals(1, project.conversations().size());
        return new String(Files.readAllBytes(project.conversations().get(0).toPath()),
                StandardCharsets.UTF_8);
    }

    @Test
    void withTheSwitchOffNothingIsWritten() {
        session.send("q");

        assertTrue(project.conversations().isEmpty());
        assertTrue(!project.getDirectory().exists(), "not even the folder");
    }

    @Test
    void withTheSwitchOnEachAnswerIsSaved() throws IOException {
        project.setSavingConversations(true);

        session.send("What is a vegetarian pizza?");

        String text = only();
        assertTrue(text.contains("What is a vegetarian pizza?"), text);
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void anAcceptedProposalIsRecordedAsAccepted() throws IOException {
        project.setSavingConversations(true);
        reply = "```ontoboard-proposal\naction: set\nsubject: vegetarian pizza\n"
                + "property: definition\nvalue: A pizza whose toppings are all vegetarian.\n```";
        session.send("Write a definition.");

        session.accept(session.turns().get(0).getProposals().get(0));

        assertTrue(only().contains(": accepted"), only());
    }

    @Test
    void theSessionsKeyIsNotWritten() throws IOException {
        project.setSavingConversations(true);
        reply = "Your key sk-secret-key-0123456789 should not be pasted here.";

        session.send("q");

        assertTrue(!only().contains("sk-secret-key-0123456789"), only());
    }

    /** Runs git here; the test is skipped where there is no git. */
    private int git(String... arguments) throws Exception {
        return gitIn(dir, arguments);
    }

    private int gitIn(File where, String... arguments) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));
        Process process;
        try {
            process = new ProcessBuilder(command).directory(where).redirectErrorStream(true)
                    .start();
        } catch (IOException noGit) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "git is not installed");
            return -1;
        }
        byte[] ignored = new byte[1024];
        while (process.getInputStream().read(ignored) >= 0) {
            // Read it so that git never blocks on a full pipe.
        }
        return process.waitFor();
    }

    /** What git prints, here: its status, say. */
    private String gitOut(File where, String... arguments) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));
        Process process;
        try {
            process = new ProcessBuilder(command).directory(where).redirectErrorStream(true)
                    .start();
        } catch (IOException noGit) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "git is not installed");
            return "";
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        for (int read; (read = process.getInputStream().read(buffer)) >= 0;) {
            out.write(buffer, 0, read);
        }
        process.waitFor();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * A private conversation in a project, its shared assistant/.gitignore deleted by a
     * colleague's pull, so that only the clone's own exclude keeps it out: what git status says.
     */
    private String statusWithOnlyTheExclude(File repo, File ontologyFolder) throws Exception {
        assertEquals(0, gitIn(repo, "init", "-q"));
        onlyTheExcludesLeft(ontologyFolder);
        return gitOut(repo, "status", "--porcelain", "-uall");
    }

    /** The same, for a project whose clones the test made: the status is asked of each. */
    private void onlyTheExcludesLeft(File ontologyFolder) throws Exception {
        ProjectFolder there = ProjectFolder.of(new File(ontologyFolder, "pizza-edit.owl"));
        there.setSavingConversations(true);
        there.ensureConversationRules();
        File conversation = new File(there.conversationsDirectory(
                ProjectFolder.ConversationSharing.PRIVATE), "2026-11-08-100000-x.md");
        conversation.getParentFile().mkdirs();
        java.nio.file.Files.write(conversation.toPath(), "q".getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.delete(new File(there.getDirectory(), ".gitignore").toPath());
    }

    /**
     * Review 1.144.0 privacy-06: the exclude rules were the folder's path as it is, and in a
     * pattern "[v2]" is a set of characters - so for a project in "onto [v2]" they held nothing.
     */
    @Test
    void theExcludeHoldsForAFolderWithBracketsInItsName() throws Exception {
        File repo = new File(dir, "repo");
        File folder = new File(repo, "onto [v2]");
        folder.mkdirs();

        String status = statusWithOnlyTheExclude(repo, folder);

        assertFalse(status.contains("2026-11-08-100000-x.md"), status);
        assertTrue(status.contains("pizza-edit") || status.contains("assistant/"), status);
    }

    /**
     * Review 1.147.0 recorder-01: walking from both paths stopped at the first clone whose exclude
     * could not be written - here the clone that holds the junction - so the project's own clone,
     * where the person commits, got no rules, and nobody was told. Review 1.148.0 recorder-01: X,
     * the clone the project was opened through, still listed the conversation once the shared
     * file was gone, and its failure was said nowhere.
     */
    @Test
    void aCloneWhoseExcludeCannotBeWrittenDoesNotCostTheOtherOne() throws Exception {
        File x = new File(dir, "x");
        File p = new File(dir, "p");
        File onto = new File(p, "onto");
        x.mkdirs();
        onto.mkdirs();
        assertEquals(0, gitIn(x, "init", "-q"));
        assertEquals(0, gitIn(p, "init", "-q"));
        File exclude = new File(new File(new File(x, ".git"), "info"), "exclude");
        exclude.getParentFile().mkdirs();
        if (!exclude.exists()) {
            java.nio.file.Files.write(exclude.toPath(), new byte[0]);
        }
        File link = new File(x, "link");
        de.fizkarlsruhe.ise.ontoboard.assistant.project.Links.link(link, onto);
        org.junit.jupiter.api.Assumptions.assumeTrue(exclude.setWritable(false),
                "the exclude cannot be made read-only here");
        try {
            // Through the recorder, which is where the person hears of it.
            project = ProjectFolder.of(new File(link, "pizza-edit.owl"));
            project.setSavingConversations(true);
            sharing = ProjectFolder.ConversationSharing.PRIVATE;
            session.send("q");
            session.send("q2");
            String saved = project.conversations().get(0).getName();
            java.nio.file.Files.delete(new File(project.getDirectory(), ".gitignore").toPath());

            String status = gitOut(p, "status", "--porcelain", "-uall");

            assertFalse(status.contains(saved), status);
            if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                    .startsWith("windows")) {
                assertEquals(1, problems.size(), "X's failure is said, once: " + problems);
                assertTrue(problems.get(0).contains(exclude.getCanonicalPath()),
                        problems.toString());
            } else {
                // Links makes a symbolic link here, which git in X does not follow: X needs no
                // rules, and nothing is tried there or said (1.149.0, the review of the
                // recorder-01 fix: on Linux the test failed, and CI with it).
                assertTrue(problems.isEmpty(), problems.toString());
            }
        } finally {
            exclude.setWritable(true);
        }
    }

    /** A clone's own exclude, made empty where git made none. */
    private static File excludeOf(File clone) throws IOException {
        File exclude = new File(new File(new File(clone, ".git"), "info"), "exclude");
        exclude.getParentFile().mkdirs();
        if (!exclude.exists()) {
            Files.write(exclude.toPath(), new byte[0]);
        }
        return exclude;
    }

    private static boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows");
    }

    /**
     * Review 1.149.0 recorder-02: only the first exclude that could not be written was kept, and
     * the project's own clone was tried last. X holds a junction to the project in P, both
     * excludes read-only, the project opened through X: only X was named, and P - where the
     * person commits - listed the conversation in git once a pull deleted assistant/.gitignore.
     * Every exclude that could not be written is named, the project's own clone first.
     */
    @Test
    void everyExcludeThatCannotBeWrittenIsNamedTheProjectsOwnCloneFirst() throws Exception {
        File x = new File(dir, "x");
        File p = new File(dir, "p");
        File onto = new File(p, "onto");
        x.mkdirs();
        onto.mkdirs();
        assertEquals(0, gitIn(x, "init", "-q"));
        assertEquals(0, gitIn(p, "init", "-q"));
        File excludeX = excludeOf(x);
        File excludeP = excludeOf(p);
        File link = new File(x, "link");
        de.fizkarlsruhe.ise.ontoboard.assistant.project.Links.link(link, onto);
        org.junit.jupiter.api.Assumptions.assumeTrue(excludeX.setWritable(false)
                && excludeP.setWritable(false), "the excludes cannot be made read-only here");
        try {
            project = ProjectFolder.of(new File(link, "pizza-edit.owl"));
            project.setSavingConversations(true);
            sharing = ProjectFolder.ConversationSharing.PRIVATE;

            session.send("q");
            session.send("q2");

            assertEquals(1, project.conversations().size(), "still saved: the shared block holds");
            assertEquals(1, problems.size(), "once per conversation, file and reason: " + problems);
            String said = problems.get(0);
            String inP = excludeP.getCanonicalPath();
            assertTrue(said.contains(inP), said);
            if (onWindows()) {
                // Git in X goes into the junction: X's exclude is named as well, after P's.
                String inX = excludeX.getCanonicalPath();
                assertTrue(said.contains(inX), said);
                assertTrue(said.indexOf(inP) < said.indexOf(inX), said);
                assertTrue(said.contains("Make those files writable."), said);
            }
        } finally {
            excludeX.setWritable(true);
            excludeP.setWritable(true);
        }
    }

    /**
     * The review of the recorder-02 fix: opened through a junction to the clone's top folder, or
     * to the folder above it, the clone's one exclude was reached by two walks under two names,
     * and named twice - "...\repos\pizza\.git\info\exclude ... and ...\w\.git\info\exclude ...
     * Make those files writable." It is named once, by its real path.
     */
    @Test
    void anExcludeReachedThroughAJunctionToTheClonesTopIsNamedOnce() throws Exception {
        namedOnceThrough("w", "");
    }

    @Test
    void anExcludeReachedThroughAJunctionAboveTheCloneIsNamedOnce() throws Exception {
        namedOnceThrough("up", "pizza");
    }

    /** P in repos, opened through a junction to P, or to repos and then P's folder. */
    private void namedOnceThrough(String junction, String below) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(onWindows(), "a junction git goes into");
        File repos = new File(dir, "repos");
        File p = new File(repos, "pizza");
        new File(p, "onto").mkdirs();
        assertEquals(0, gitIn(p, "init", "-q"));
        File excludeP = excludeOf(p);
        File link = new File(dir, junction);
        de.fizkarlsruhe.ise.ontoboard.assistant.project.Links.link(link,
                below.isEmpty() ? p : repos);
        File opened = new File(below.isEmpty() ? link : new File(link, below), "onto");
        org.junit.jupiter.api.Assumptions.assumeTrue(excludeP.setWritable(false),
                "the exclude cannot be made read-only here");
        try {
            project = ProjectFolder.of(new File(opened, "pizza-edit.owl"));
            project.setSavingConversations(true);
            sharing = ProjectFolder.ConversationSharing.PRIVATE;

            session.send("q");

            assertEquals(1, problems.size(), problems.toString());
            String said = problems.get(0);
            assertTrue(said.contains(excludeP.getCanonicalPath()), said);
            assertFalse(said.contains(link.getPath()), said);
            assertTrue(said.contains("Make that file writable."), said);
        } finally {
            excludeP.setWritable(true);
        }
    }

    /**
     * Review 1.149.0 recorder-02, once per conversation and per file: P's exclude, named at the
     * first answer, is not named again; X's, which cannot be written from the second answer on,
     * is named then, alone. The check was on the first failure only, and P's, tried first, hid
     * X's for the rest of the conversation. Windows only: elsewhere Links makes a symbolic link,
     * which git in X does not go into, so X is never tried.
     */
    @Test
    void anExcludeThatFailsLaterIsNamedThenAndTheFirstIsNotNamedAgain() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(onWindows(),
                "a junction, which git in X goes into, is made only on Windows");
        File x = new File(dir, "x");
        File p = new File(dir, "p");
        File onto = new File(p, "onto");
        x.mkdirs();
        onto.mkdirs();
        assertEquals(0, gitIn(x, "init", "-q"));
        assertEquals(0, gitIn(p, "init", "-q"));
        File excludeX = excludeOf(x);
        File excludeP = excludeOf(p);
        File link = new File(x, "link");
        de.fizkarlsruhe.ise.ontoboard.assistant.project.Links.link(link, onto);
        org.junit.jupiter.api.Assumptions.assumeTrue(excludeP.setWritable(false),
                "the exclude cannot be made read-only here");
        try {
            project = ProjectFolder.of(new File(link, "pizza-edit.owl"));
            project.setSavingConversations(true);
            sharing = ProjectFolder.ConversationSharing.PRIVATE;
            session.send("q");
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.get(0).contains(excludeP.getCanonicalPath()), problems.toString());
            Files.write(excludeX.toPath(), new byte[0]);
            assertTrue(excludeX.setWritable(false), "X's exclude made read-only");

            session.send("q2");
            session.send("q3");

            assertEquals(2, problems.size(), problems.toString());
            assertTrue(problems.get(1).contains(excludeX.getCanonicalPath()), problems.get(1));
            assertFalse(problems.get(1).contains(excludeP.getCanonicalPath()), problems.get(1));
        } finally {
            excludeX.setWritable(true);
            excludeP.setWritable(true);
        }
    }

    /**
     * Review 1.149.0 recorder-03: X holds a directory symbolic link (mklink /D) to the project in
     * P, and the project was opened through it. Git does not go into such a link - Git for
     * Windows goes into a junction, not into a symbolic link - yet X was given rules, and with its
     * exclude read-only the person was told git in X sees the conversations. Nothing is said of X
     * and X gets no rules; P still gets them. Where no symbolic link can be made, it is skipped.
     */
    @Test
    void aCloneHoldingADirectorySymbolicLinkToTheProjectIsLeftAlone() throws Exception {
        File x = new File(dir, "x");
        File p = new File(dir, "p");
        File onto = new File(p, "onto");
        x.mkdirs();
        onto.mkdirs();
        assertEquals(0, gitIn(x, "init", "-q"));
        assertEquals(0, gitIn(p, "init", "-q"));
        File excludeX = excludeOf(x);
        File excludeP = excludeOf(p);
        File link = new File(x, "sl");
        try {
            Files.createSymbolicLink(link.toPath(), onto.getAbsoluteFile().toPath());
        } catch (UnsupportedOperationException | IOException cannot) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "no directory symbolic link can be made here: " + cannot);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(excludeX.setWritable(false),
                "the exclude cannot be made read-only here");
        try {
            project = ProjectFolder.of(new File(link, "pizza-edit.owl"));
            project.setSavingConversations(true);
            sharing = ProjectFolder.ConversationSharing.PRIVATE;

            session.send("q");

            assertTrue(problems.isEmpty(), "git in X does not go into the link: " + problems);
            assertTrue(read(excludeP).contains("/onto/assistant/conversations/*"),
                    read(excludeP));
            excludeX.setWritable(true);
            session.send("q2");
            assertFalse(read(excludeX).contains("conversations"), read(excludeX));
        } finally {
            excludeX.setWritable(true);
        }
        String saved = project.conversations().get(0).getName();
        Files.delete(new File(project.getDirectory(), ".gitignore").toPath());
        String inX = gitOut(x, "status", "--porcelain", "-uall");
        assertFalse(inX.contains(saved), inX);
        String inP = gitOut(p, "status", "--porcelain", "-uall");
        assertFalse(inP.contains(saved), inP);
    }

    /**
     * Review 1.148.0 recorder-01: the clone's own exclude could not be written - read-only, as a
     * copy or a restore leaves it - and the person heard only "kept out of git". After a pulled
     * deletion of assistant/.gitignore, their next commit of everything took the conversation.
     */
    @Test
    void anExcludeThatCannotBeWrittenIsSaid() throws Exception {
        assertEquals(0, git("init", "-q"));
        File exclude = new File(new File(new File(dir, ".git"), "info"), "exclude");
        exclude.getParentFile().mkdirs();
        if (!exclude.exists()) {
            Files.write(exclude.toPath(), new byte[0]);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(exclude.setWritable(false),
                "the exclude cannot be made read-only here");
        try {
            project.setSavingConversations(true);
            sharing = ProjectFolder.ConversationSharing.PRIVATE;

            session.send("q");
            session.send("q2");

            assertEquals(1, project.conversations().size(), "still saved: the shared block holds");
            assertEquals(1, problems.size(), "once per conversation and reason: " + problems);
            assertTrue(problems.get(0).contains(exclude.getCanonicalPath()), problems.toString());
            assertTrue(problems.get(0).contains("writable"), problems.toString());
        } finally {
            exclude.setWritable(true);
        }
    }

    /**
     * Review 1.148.0 recorder-01 (R2): an exclude another program holds locked. Reading or writing
     * a locked file fails with a reason that names no file, and the person must know which one.
     */
    @Test
    void aLockedExcludeIsSaidByName() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).startsWith("windows"),
                "only on Windows does a lock stop another writer");
        assertEquals(0, git("init", "-q"));
        File exclude = new File(new File(new File(dir, ".git"), "info"), "exclude");
        exclude.getParentFile().mkdirs();
        if (!exclude.exists()) {
            Files.write(exclude.toPath(), "# made by the test\n".getBytes(StandardCharsets.UTF_8));
        }
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.PRIVATE;

        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                exclude.toPath(), java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE);
                java.nio.channels.FileLock lock = channel.lock()) {
            session.send("q");
        }

        assertEquals(1, project.conversations().size(), "still saved: the shared block holds");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains(exclude.getCanonicalPath()), problems.toString());
    }

    /** An exclude another program holds open, as Windows words it: FormatMessage ends ".\r\n". */
    private static java.nio.file.FileSystemException heldOpen(File exclude) {
        return new java.nio.file.FileSystemException(exclude.getPath(), null,
                "The process cannot access the file because it is being used by another"
                        + " process.\r\n");
    }

    /**
     * Review 1.150.0 recorder-03: an exclude another program held open was said as "(The process
     * cannot access the file because it is being used by another process.\r\n)" - the line broken
     * inside the parentheses, in the chat and in Settings - with "Make that file writable", which
     * it was: only closing the program that holds it frees it.
     */
    @Test
    void anExcludeHeldOpenIsSaidOnOneLineWithAdviceThatFreesIt() {
        File exclude = new File(new File(new File(dir, ".git"), "info"), "exclude");
        boolean before = ConversationRecorder.locking;
        ConversationRecorder.locking = true;
        String said;
        try {
            said = ConversationRecorder.excludeProblem(project, heldOpen(exclude));
        } finally {
            ConversationRecorder.locking = before;
        }

        assertFalse(said.contains("\r") || said.contains("\n"), said);
        assertTrue(said.contains(exclude.getPath() + " could not be written (The process cannot"
                + " access the file because it is being used by another process):"), said);
        // "it" came after "until OntoBoard writes it again", said of assistant/.gitignore (the
        // review of the 1.151.0 recorder fix).
        assertTrue(said.endsWith(" Close the program that holds that file open."), said);
        assertFalse(said.contains("writable"), said);
    }

    /**
     * Review of the 1.151.0 recorder fix: every failure but access denied was told "Close the
     * program that holds it open" - a read-only file system, a full disk, a .git/info that is a
     * file, and anywhere but Windows, where no program's lock stops a writer. The reason is shown,
     * and that advice is given only where a lock can be the cause.
     */
    @Test
    void aFailureNoProgramCausesGetsNoAdviceToCloseOne() {
        File info = new File(new File(dir, ".git"), "info");
        File exclude = new File(info, "exclude");
        File inP = new File(new File(new File(new File(dir, "p"), ".git"), "info"), "exclude");
        IOException mixed = new java.nio.file.AccessDeniedException(inP.getPath());
        mixed.addSuppressed(new java.nio.file.FileSystemException(exclude.getPath(), null,
                "Read-only file system"));
        boolean before = ConversationRecorder.locking;
        List<String> said = new ArrayList<String>();
        try {
            ConversationRecorder.locking = false;
            said.add(ConversationRecorder.excludeProblem(project, heldOpen(exclude)));
            said.add(ConversationRecorder.excludeProblem(project,
                    new java.nio.file.FileSystemException(exclude.getPath(), null,
                            "Read-only file system")));
            ConversationRecorder.locking = true;
            said.add(ConversationRecorder.excludeProblem(project,
                    new java.nio.file.FileAlreadyExistsException(info.getPath())));
            ConversationRecorder.locking = false;
            said.add(ConversationRecorder.excludeProblem(project, mixed));
        } finally {
            ConversationRecorder.locking = before;
        }

        for (String each : said.subList(0, 3)) {
            assertTrue(each.endsWith(" until OntoBoard writes it again."), each);
        }
        // Two files named: "that file" would not say which.
        assertTrue(said.get(3).endsWith(" Make " + inP.getPath() + " writable."), said.get(3));
        for (String each : said) {
            assertFalse(each.contains("Close the program"), each);
        }
    }

    /**
     * The same reason in the rules' own problem: a shared assistant/.gitignore another program
     * held open broke the line inside the parentheses there too (1.151.0, beside review 1.150.0
     * recorder-03).
     */
    @Test
    void aGitignoreHeldOpenIsSaidOnOneLine() {
        File gitignore = new File(project.getDirectory(), ".gitignore");

        String said = ConversationRecorder.rulesProblem(project, heldOpen(gitignore));

        assertFalse(said.contains("\r") || said.contains("\n"), said);
        assertTrue(said.endsWith("(" + gitignore.getPath() + ": The process cannot access the file"
                + " because it is being used by another process)."), said);
    }

    /** The same for several; with one read-only among them each is told what frees it. */
    @Test
    void excludesHeldOpenOrReadOnlyAreEachGivenTheAdviceThatFreesThem() {
        File inP = new File(new File(new File(new File(dir, "p"), ".git"), "info"), "exclude");
        File inX = new File(new File(new File(new File(dir, "x"), ".git"), "info"), "exclude");
        IOException two = heldOpen(inP);
        two.addSuppressed(heldOpen(inX));
        IOException mixed = new java.nio.file.AccessDeniedException(inP.getPath());
        mixed.addSuppressed(heldOpen(inX));
        boolean before = ConversationRecorder.locking;
        ConversationRecorder.locking = true;
        String both;
        String mix;
        try {
            both = ConversationRecorder.excludeProblem(project, two);
            mix = ConversationRecorder.excludeProblem(project, mixed);
        } finally {
            ConversationRecorder.locking = before;
        }

        assertTrue(both.endsWith(" Close the programs that hold those files open."), both);
        assertFalse(both.contains("writable") || both.contains("\n"), both);
        assertTrue(mix.endsWith(" Make " + inP.getPath() + " writable, and close the program that"
                + " holds " + inX.getPath() + " open."), mix);
        assertTrue(mix.contains(inP.getPath() + " could not be written (access denied)"), mix);
    }

    /**
     * Review 1.150.0 recorder-01: B\J1 leads to C\x, and C\x\J2 to the project in A, opened as
     * B\J1\J2. J1 cannot be followed - as under a folder that may be entered but not listed - and
     * B's exclude is read-only. Only B's exclude was named, and nothing more at any later save, so
     * J1 - behind which git in C lists the conversations - was never mentioned. Both are named,
     * once. Windows only: elsewhere Links makes symbolic links, which the canonical path has
     * followed already, and no junction is followed.
     */
    @Test
    void aJunctionThatCannotBeFollowedIsNamedBesideAnExcludeThatCannotBeWritten()
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(onWindows(),
                "a junction is made only on Windows");
        File a = new File(dir, "a");
        File proj = new File(a, "proj");
        File b = new File(dir, "b");
        File c = new File(dir, "c");
        File x = new File(c, "x");
        proj.mkdirs();
        b.mkdirs();
        x.mkdirs();
        for (File clone : new File[] {a, b, c}) {
            assertEquals(0, gitIn(clone, "init", "-q"));
        }
        de.fizkarlsruhe.ise.ontoboard.assistant.project.Links.link(new File(x, "J2"), proj);
        File j1 = new File(b, "J1");
        de.fizkarlsruhe.ise.ontoboard.assistant.project.Links.link(j1, x);
        File excludeB = excludeOf(b);
        org.junit.jupiter.api.Assumptions.assumeTrue(excludeB.setWritable(false),
                "the exclude cannot be made read-only here");
        try (AutoCloseable hook = de.fizkarlsruhe.ise.ontoboard.assistant.project.Links
                .unfollowable("J1")) {
            project = ProjectFolder.of(new File(new File(j1, "J2"), "pizza-edit.owl"));
            project.setSavingConversations(true);
            sharing = ProjectFolder.ConversationSharing.PRIVATE;

            session.send("q");
            session.send("q2");
        } finally {
            excludeB.setWritable(true);
        }

        assertEquals(1, problems.size(), "once per conversation, file and reason: " + problems);
        String said = problems.get(0);
        assertTrue(said.contains(excludeB.getCanonicalPath() + " could not be written"), said);
        assertTrue(said.contains("could not follow " + j1.getAbsolutePath() + " to where it really"
                + " is (Access is denied)"), said);
        assertFalse(said.contains("\n"), said);
    }

    /**
     * Review 1.150.0 recorder-02: B\S, a directory symbolic link, leads to C\c\J, and J is a
     * junction in clone C to the project in A; the project is opened as B\S. Only where the link
     * leads in the end was known - A\proj - so C, whose git goes into J, got no rules, and once a
     * pull deleted assistant/.gitignore it listed the private conversation. Where a symbolic link
     * leads as written in it is walked too. Windows only, where a junction can be made and the
     * canonical path keeps a symbolic link; skipped where no symbolic link can be made.
     */
    @Test
    void aCloneHoldingAJunctionThatASymbolicLinkNamesGetsTheRules() throws Exception {
        throughASymbolicLinkToAJunction(false, false);
    }

    /**
     * The same with a relative link, B\S to ..\c\c\J as mklink /D makes it, reached through a
     * junction W\J0 to B. Windows resolves it from W\J0, the path as opened, not from B, where
     * the link is (the 1.151.0 probe: ..\t from W\J0\S listed W\t): it leads to W\c\c\J, so clone
     * W\c needs the rules.
     */
    @Test
    void aRelativeSymbolicLinkReachedThroughAJunctionLeadsWhereWindowsTakesIt() throws Exception {
        throughASymbolicLinkToAJunction(true, true);
    }

    /**
     * The same link as Java makes it: a file link, as its target cannot be found from B. Windows
     * goes through it all the same, but toRealPath fails on it, and its target as written went
     * unread (1.151.0, the first fix of review 1.150.0 recorder-02).
     */
    @Test
    void aFileLinkToAFolderIsWalkedAsWrittenToo() throws Exception {
        throughASymbolicLinkToAJunction(true, false);
    }

    private void throughASymbolicLinkToAJunction(boolean relativeThroughAJunction,
            boolean asMklinkMakesIt) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(onWindows(),
                "a junction, which git in C goes into, is made only on Windows");
        File a = new File(dir, "a");
        File proj = new File(a, "proj");
        File b = new File(dir, "b");
        File w = new File(dir, "w");
        File c = new File(relativeThroughAJunction ? w : dir, "c");
        File cc = new File(c, "c");
        proj.mkdirs();
        b.mkdirs();
        cc.mkdirs();
        for (File clone : new File[] {a, b, c}) {
            assertEquals(0, gitIn(clone, "init", "-q"));
        }
        File junction = new File(cc, "J");
        de.fizkarlsruhe.ise.ontoboard.assistant.project.Links.link(junction, proj);
        File link = new File(b, "S");
        if (asMklinkMakesIt) {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/D", "S",
                    "..\\c\\c\\J").directory(b).redirectErrorStream(true).start();
            byte[] ignored = new byte[1024];
            while (process.getInputStream().read(ignored) >= 0) {
                // Read it so that cmd never blocks on a full pipe.
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(process.waitFor() == 0
                    && Files.isSymbolicLink(link.toPath()),
                    "no directory symbolic link can be made here");
        } else {
            try {
                Files.createSymbolicLink(link.toPath(), relativeThroughAJunction
                        ? java.nio.file.Paths.get("..", "c", "c", "J")
                        : junction.getAbsoluteFile().toPath());
            } catch (UnsupportedOperationException | IOException cannot) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "no directory symbolic link can be made here: " + cannot);
            }
        }
        File opened = link;
        if (relativeThroughAJunction) {
            File j0 = new File(w, "J0");
            de.fizkarlsruhe.ise.ontoboard.assistant.project.Links.link(j0, b);
            opened = new File(j0, "S");
        }

        onlyTheExcludesLeft(opened);

        for (File clone : new File[] {a, b, c}) {
            String status = gitOut(clone, "status", "--porcelain", "-uall");
            assertFalse(status.contains("2026-11-08-100000-x.md"), clone.getName() + ": " + status);
        }
        assertTrue(read(excludeOf(c)).contains("/c/J/assistant/conversations/*"),
                read(excludeOf(c)));
    }

    /**
     * Review 1.147.0 recorder-02 (and 1.148.0 recorder-02): a junction L inside clone X to the top
     * folder of a submodule, the project opened through it. Seen through L, the submodule's .git
     * file names ../.git/modules/sub from X - no repository - so git in X goes into L as an
     * ordinary folder; the walk stopped at that file, and X got no rules.
     */
    @Test
    void theExcludeHoldsForAJunctionInsideTheCloneToASubmodulesTopFolder() throws Exception {
        File source = new File(dir, "source");
        source.mkdirs();
        assertEquals(0, gitIn(source, "init", "-q"));
        Files.write(new File(source, "readme.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, gitIn(source, "add", "readme.txt"));
        assertEquals(0, gitIn(source, "-c", "user.name=t", "-c", "user.email=t@example.org",
                "commit", "-q", "-m", "first"));
        File superproject = new File(dir, "s");
        superproject.mkdirs();
        assertEquals(0, gitIn(superproject, "init", "-q"));
        assertEquals(0, gitIn(superproject, "-c", "protocol.file.allow=always", "submodule", "add",
                "-q", source.getAbsolutePath(), "sub"));
        File sub = new File(superproject, "sub");
        String dotGit = new String(Files.readAllBytes(new File(sub, ".git").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(dotGit.contains("../.git/modules/sub"), dotGit);
        File onto = new File(sub, "onto");
        onto.mkdirs();
        File x = new File(dir, "x");
        x.mkdirs();
        File link = new File(x, "L");
        de.fizkarlsruhe.ise.ontoboard.assistant.project.Links.link(link, sub);

        String status = statusWithOnlyTheExclude(x, new File(link, "onto"));

        assertFalse(status.contains("2026-11-08-100000-x.md"), status);
        String inSub = gitOut(sub, "status", "--porcelain", "-uall");
        assertFalse(inSub.contains("2026-11-08-100000-x.md"), inSub);
    }

    /**
     * Review 1.147.0 recorder-03 (and 1.148.0 recorder-02): A\j1 leads to B\x, and B\x\j2 to
     * C\proj; the project is opened as A\j1\j2. Git in B goes through j2 into the project, but B
     * lies neither on the path as opened nor on the real path, and got no rules.
     */
    @Test
    void everyCloneOfAChainOfJunctionsGetsTheRules() throws Exception {
        File a = new File(dir, "a");
        File b = new File(dir, "b");
        File c = new File(dir, "c");
        File x = new File(b, "x");
        File proj = new File(c, "proj");
        a.mkdirs();
        x.mkdirs();
        proj.mkdirs();
        for (File clone : new File[] {a, b, c}) {
            assertEquals(0, gitIn(clone, "init", "-q"));
        }
        File j1 = new File(a, "j1");
        de.fizkarlsruhe.ise.ontoboard.assistant.project.Links.link(j1, x);
        de.fizkarlsruhe.ise.ontoboard.assistant.project.Links.link(new File(x, "j2"), proj);

        onlyTheExcludesLeft(new File(j1, "j2"));

        for (File clone : new File[] {a, b, c}) {
            String status = gitOut(clone, "status", "--porcelain", "-uall");
            assertFalse(status.contains("2026-11-08-100000-x.md"), clone.getName() + ": " + status);
        }
    }

    /**
     * A .git folder that is no repository - empty, say - is not where git in the clone above
     * stops, so it is not where the rules go either (1.149.0, review 1.147.0 recorder-02: only a
     * real git directory ends the walk).
     */
    @Test
    void theExcludeHoldsPastAGitFolderThatIsNoRepository() throws Exception {
        File repo = new File(dir, "repo");
        File onto = new File(repo, "onto");
        new File(onto, ".git").mkdirs();

        String status = statusWithOnlyTheExclude(repo, onto);

        assertFalse(status.contains("2026-11-08-100000-x.md"), status);
    }

    /**
     * Review 1.146.0 recorder-01: a junction inside the clone that points out of it. The walk
     * started from the real path only (1.146.0), found no .git there and wrote no exclude rule,
     * while git, going into the junction, saw the private conversation.
     */
    @Test
    void theExcludeHoldsForAJunctionInsideTheCloneThatPointsOut() throws Exception {
        File repo = new File(dir, "repo");
        repo.mkdirs();
        File outside = new File(new File(dir, "outside"), "onto");
        outside.mkdirs();
        File onto = new File(repo, "onto");
        de.fizkarlsruhe.ise.ontoboard.assistant.project.Links.link(onto, outside);

        String status = statusWithOnlyTheExclude(repo, onto);

        assertFalse(status.contains("2026-11-08-100000-x.md"), status);
    }

    /**
     * Review 1.144.0 privacy-06: opened through a junction to a folder in a clone, the walk up
     * from the path as opened found no .git, and no exclude rule was written.
     */
    @Test
    void theExcludeHoldsForAProjectOpenedThroughALink() throws Exception {
        File repo = new File(dir, "repo");
        File onto = new File(repo, "onto");
        onto.mkdirs();
        File link = new File(dir, "link");
        de.fizkarlsruhe.ise.ontoboard.assistant.project.Links.link(link, onto);

        String status = statusWithOnlyTheExclude(repo, link);

        assertFalse(status.contains("2026-11-08-100000-x.md"), status);
    }

    private String relative(File file) {
        return dir.toPath().relativize(file.toPath()).toString().replace('\\', '/');
    }

    /** 1.144.0 (review 1.143.0 I-1): what each person's choice promises, asked of git itself. */
    @Test
    void aPrivateConversationIsIgnoredByGit() throws Exception {
        assertEquals(0, git("init", "-q"));
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.PRIVATE;

        session.send("q");

        assertEquals(1, project.conversations().size());
        assertEquals(0, git("check-ignore", "-q", relative(project.conversations().get(0))),
                "git ignores it");
        assertEquals(1, git("check-ignore", "-q", "assistant/project.properties"),
                "the switch itself is still shared");
    }

    @Test
    void aReleasedConversationIsSeenByGit() throws Exception {
        assertEquals(0, git("init", "-q"));
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.RELEASED;

        session.send("q");

        File saved = project.conversations().get(0);
        assertEquals(project.conversationsDirectory(ProjectFolder.ConversationSharing.RELEASED),
                saved.getParentFile());
        assertEquals(1, git("check-ignore", "-q", relative(saved)), "git does not ignore it");
    }

    @Test
    void withNoChoiceAConversationStaysOutOfGit() throws Exception {
        assertEquals(0, git("init", "-q"));
        project.setSavingConversations(true);

        session.send("q");

        assertEquals(1, project.conversations().size());
        assertEquals(0, git("check-ignore", "-q", relative(project.conversations().get(0))));
    }

    /**
     * Review 1.143.0 I-1: a colleague's Release, in the shared project file as 1.142.0 wrote it,
     * does not publish the conversations of someone who never chose.
     */
    @Test
    void aReleaseInTheProjectFileDoesNotPublishMine() throws Exception {
        assertEquals(0, git("init", "-q"));
        project.getDirectory().mkdirs();
        Files.write(new File(project.getDirectory(), "project.properties").toPath(),
                "saveConversations=true\nreleaseConversations=true\n"
                        .getBytes(StandardCharsets.UTF_8));

        session.send("q");

        assertEquals(1, project.conversations().size());
        assertEquals(0, git("check-ignore", "-q", relative(project.conversations().get(0))));
    }

    /** Two people on one project: one released, one did not choose; git sees only the first. */
    @Test
    void oneReleasesAndAnotherDoesNot() throws Exception {
        assertEquals(0, git("init", "-q"));
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        session.send("from the one who released");
        session.clear();
        sharing = ProjectFolder.ConversationSharing.UNDECIDED;

        session.send("from the one who did not choose");

        File released = null;
        File kept = null;
        for (File file : project.conversations()) {
            if (file.getParentFile().getName().equals("released")) {
                released = file;
            } else {
                kept = file;
            }
        }
        assertEquals(1, git("check-ignore", "-q", relative(released)), "released: seen");
        assertEquals(0, git("check-ignore", "-q", relative(kept)), "not chosen: ignored");
    }

    /** Review 1.143.0 M-1: a line that takes conversations back cannot publish a private one. */
    @Test
    void aNegationInTheGitignoreCannotPublishAPrivateConversation() throws Exception {
        assertEquals(0, git("init", "-q"));
        project.setSavingConversations(true);
        Files.write(new File(project.getDirectory(), ".gitignore").toPath(),
                "!conversations/\n!conversations/*.md\n".getBytes(StandardCharsets.UTF_8));
        sharing = ProjectFolder.ConversationSharing.PRIVATE;

        session.send("q");

        assertEquals(0, git("check-ignore", "-q", relative(project.conversations().get(0))));
    }

    private String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private File in(ProjectFolder.ConversationSharing choice) {
        File[] files = project.conversationsDirectory(choice).listFiles(
                (folder, name) -> name.endsWith(".md"));
        assertEquals(1, files == null ? 0 : files.length, "one file in "
                + project.conversationsDirectory(choice));
        return files[0];
    }

    /** Every file in conversations/released/, one after another: what git sees. */
    private String released() throws IOException {
        StringBuilder all = new StringBuilder();
        File[] files = project.conversationsDirectory(ProjectFolder.ConversationSharing.RELEASED)
                .listFiles((folder, name) -> name.endsWith(".md"));
        if (files != null) {
            for (File file : files) {
                all.append(read(file));
            }
        }
        return all.toString();
    }

    /** The switch, from inside an answer, where an IOException cannot be thrown. */
    private void saving(boolean on) {
        try {
            project.setSavingConversations(on);
        } catch (IOException unwritable) {
            throw new java.io.UncheckedIOException(unwritable);
        }
    }

    /** Runs the model calls that wait, as Protégé's background thread would. */
    private void drain() {
        while (!calls.isEmpty()) {
            calls.remove(0).run();
        }
    }

    /**
     * The git probe of the 1.144.0 design (m2): switching to private while a conversation is
     * open stops its released file at once; the conversation goes on in a private file.
     */
    @Test
    void switchingToPrivateStopsTheReleasedFileAtOnce() throws IOException {
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        session.send("said while released");
        sharing = ProjectFolder.ConversationSharing.PRIVATE;

        session.send("said after the switch");

        String released = read(in(ProjectFolder.ConversationSharing.RELEASED));
        String kept = read(in(ProjectFolder.ConversationSharing.PRIVATE));
        assertTrue(released.contains("said while released"), released);
        assertFalse(released.contains("said after the switch"), released);
        assertTrue(kept.contains("said after the switch"), kept);
    }

    /** Switching to Release releases only what comes after it, never what was said before. */
    @Test
    void switchingToReleasedReleasesOnlyWhatFollows() throws IOException {
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.PRIVATE;
        session.send("said while private");
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        session.send("answered when the switch was seen");

        session.send("said after the switch");

        String released = read(in(ProjectFolder.ConversationSharing.RELEASED));
        String kept = read(in(ProjectFolder.ConversationSharing.PRIVATE));
        assertTrue(released.contains("said after the switch"), released);
        assertFalse(released.contains("said while private"), released);
        assertFalse(released.contains("answered when the switch was seen"), released);
        assertTrue(kept.contains("said while private"), kept);
        assertTrue(kept.contains("answered when the switch was seen"), kept);
    }

    /**
     * The git probe (x6): a colleague who deletes the shared assistant/.gitignore and pushes would
     * expose everyone's private conversations from their next pull. The clone's own
     * .git/info/exclude, never committed, keeps them out.
     */
    @Test
    void aDeletedSharedGitignoreStillKeepsMineOutOfGit() throws Exception {
        assertEquals(0, git("init", "-q"));
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.PRIVATE;
        session.send("q");

        java.nio.file.Files.delete(new File(project.getDirectory(), ".gitignore").toPath());

        assertEquals(0, git("check-ignore", "-q", relative(project.conversations().get(0))),
                "still ignored, by the clone's own exclude");
    }

    @Test
    void inAWorktreeTheExcludeIsTheRepositorysOwn() throws Exception {
        File main = new File(dir, "main");
        main.mkdirs();
        assertEquals(0, gitIn(main, "init", "-q"));
        java.nio.file.Files.write(new File(main, "readme.txt").toPath(),
                "x".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, gitIn(main, "add", "readme.txt"));
        assertEquals(0, gitIn(main, "-c", "user.name=t", "-c", "user.email=t@example.org",
                "commit", "-q", "-m", "first"));
        File worktree = new File(dir, "wt");
        assertEquals(0, gitIn(main, "worktree", "add", "-q", worktree.getAbsolutePath()));
        ProjectFolder there = ProjectFolder.of(new File(worktree, "pizza-edit.owl"));
        there.setSavingConversations(true);
        there.ensureConversationRules();
        File conversation = new File(there.conversationsDirectory(
                ProjectFolder.ConversationSharing.PRIVATE), "2026-11-07-100000-x.md");
        conversation.getParentFile().mkdirs();
        java.nio.file.Files.write(conversation.toPath(), "q".getBytes(StandardCharsets.UTF_8));

        java.nio.file.Files.delete(new File(there.getDirectory(), ".gitignore").toPath());

        assertEquals(0, gitIn(worktree, "check-ignore", "-q",
                "assistant/conversations/2026-11-07-100000-x.md"));
    }

    /**
     * The git probe (x2): a .gitignore inside conversations/ that takes them back beats the block,
     * so nothing is saved, and the person is told why.
     */
    @Test
    void aGitignoreInsideConversationsThatTakesThemBackStopsSaving() throws IOException {
        project.setSavingConversations(true);
        File inner = project.conversationsDirectory(ProjectFolder.ConversationSharing.PRIVATE);
        inner.mkdirs();
        java.nio.file.Files.write(new File(inner, ".gitignore").toPath(),
                "!*\n".getBytes(StandardCharsets.UTF_8));

        session.send("q");

        assertTrue(project.conversations().isEmpty());
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("conversations"), problems.toString());
    }

    /**
     * Review 1.144.0 privacy-01: each save writes the whole conversation again, redacted against
     * the keys known then. A key pasted while asking about a 401, then corrected in Settings, was
     * written in clear at the next save.
     */
    @Test
    void aKeyReplacedInSettingsStaysRedactedInTheSavedConversation() throws IOException {
        project.setSavingConversations(true);
        String old = "Mx7pLq2Rt9Vw4Yz8Ab3Cd6Ef1Gh5Jk0";
        currentKeys = new ArrayList<String>(Arrays.asList(old));
        reply = "The server says 401. Is " + old + " right?";
        session.send("q");
        currentKeys = new ArrayList<String>(Arrays.asList("Nw2Corrected0Key1Typed2In3Settings"));
        reply = "ok";

        session.send("again");

        assertFalse(only().contains(old), only());
    }

    /**
     * Review 1.144.0 privacy-07: saving switched on during a conversation - by a colleague's
     * project file - released the turns said while nothing was saved.
     */
    @Test
    void turnsSaidBeforeSavingWentOnAreNotReleased() throws IOException {
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        session.send("said while nothing was saved");
        project.setSavingConversations(true);
        session.send("said when saving was seen");

        session.send("said after that");

        String released = read(in(ProjectFolder.ConversationSharing.RELEASED));
        assertFalse(released.contains("said while nothing was saved"), released);
        assertTrue(released.contains("said after that"), released);
        assertTrue(read(in(ProjectFolder.ConversationSharing.PRIVATE))
                .contains("said while nothing was saved"));
        assertEquals(1, notices.size(), notices.toString());
        assertTrue(notices.get(0).contains("so far is kept out of git")
                && notices.get(0).contains("released/"), notices.get(0));
    }

    /** Review 1.144.0 privacy-07, when saving goes off and on again in one conversation. */
    @Test
    void turnsSaidWhileSavingWasOffAreNotReleasedWhenItGoesOnAgain() throws IOException {
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        project.setSavingConversations(true);
        session.send("first, saved");
        project.setSavingConversations(false);
        session.send("said while it was off");
        project.setSavingConversations(true);
        session.send("when it was seen again");

        session.send("after that");

        StringBuilder released = new StringBuilder();
        for (File file : project.conversationsDirectory(ProjectFolder.ConversationSharing.RELEASED)
                .listFiles((folder, name) -> name.endsWith(".md"))) {
            released.append(read(file));
        }
        assertFalse(released.toString().contains("said while it was off"), released.toString());
        assertTrue(released.toString().contains("first, saved"), released.toString());
        assertTrue(released.toString().contains("after that"), released.toString());
        assertTrue(read(in(ProjectFolder.ConversationSharing.PRIVATE))
                .contains("said while it was off"));
    }

    /** 1.144.0: once per conversation, the person is told where it is saved and what git does. */
    @Test
    void theFirstSaveSaysWhereAndWhetherGitSeesIt() throws IOException {
        project.setSavingConversations(true);

        session.send("q");
        session.send("again");

        assertEquals(1, notices.size(), notices.toString());
        assertTrue(notices.get(0).contains("kept out of git"), notices.get(0));
        assertTrue(notices.get(0).contains("Settings"), "it says where to choose: " + notices);
    }

    /** Not written is better than written and committable: the file says what was asked. */
    @Test
    void withoutTheIgnoreRuleNothingIsWrittenAndThePersonIsTold() throws IOException {
        project.setSavingConversations(true);
        new File(project.getDirectory(), ".gitignore").mkdirs();

        session.send("q");

        assertTrue(project.conversations().isEmpty());
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains(".gitignore"), problems.toString());
    }

    @Test
    void clearingStartsANewFileEvenWithinTheSameSecond() throws IOException {
        project.setSavingConversations(true);

        session.send("first");
        session.clear();
        session.send("second");

        assertEquals(2, project.conversations().size());
    }

    /** A .gitignore inside conversations/ that takes them back, as a colleague might push it. */
    private File takingThemBack() throws IOException {
        File inner = new File(project.conversationsDirectory(), ".gitignore");
        inner.getParentFile().mkdirs();
        Files.write(inner.toPath(), "!*.md\n".getBytes(StandardCharsets.UTF_8));
        return inner;
    }

    /**
     * Review 1.145.0 recorder-02: a turn not saved because the rules could not be put in place
     * counts as said while nothing was saved; Release chosen after it does not release it.
     */
    @Test
    void aTurnNotSavedBecauseTheRulesFailedIsNotReleasedWhenReleaseIsChosen() throws IOException {
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.PRIVATE;
        File inner = takingThemBack();
        session.send("said while it could not be saved");
        assertEquals(1, problems.size(), problems.toString());
        Files.delete(inner.toPath());
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        session.send("said when it could be saved again");

        session.send("said after that");

        assertFalse(released().contains("said while it could not be saved"), released());
        assertTrue(released().contains("said after that"), released());
        assertTrue(read(in(ProjectFolder.ConversationSharing.PRIVATE))
                .contains("said while it could not be saved"));
        assertEquals(1, notices.size(), notices.toString());
        assertTrue(notices.get(0).contains("so far is kept out of git"), notices.get(0));
    }

    /** Review 1.145.0 recorder-02, with Release chosen before the rules failed. */
    @Test
    void aTurnNotSavedBecauseTheRulesFailedStaysPrivateWithReleaseChosenBefore()
            throws IOException {
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        File inner = takingThemBack();
        session.send("said while it could not be saved");
        Files.delete(inner.toPath());
        session.send("said when it could be saved again");

        session.send("said after that");

        assertFalse(released().contains("said while it could not be saved"), released());
        assertTrue(released().contains("said after that"), released());
        assertTrue(read(in(ProjectFolder.ConversationSharing.PRIVATE))
                .contains("said while it could not be saved"));
    }

    /** Review 1.145.0 recorder-02, when assistant/.gitignore could not be written. */
    @Test
    void aTurnNotSavedBecauseTheGitignoreCouldNotBeWrittenIsNotReleased() throws IOException {
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        File gitignore = new File(project.getDirectory(), ".gitignore");
        gitignore.mkdirs();
        session.send("said while it could not be saved");
        assertEquals(1, problems.size(), problems.toString());
        Files.delete(gitignore.toPath());
        session.send("said when it could be saved again");

        session.send("said after that");

        assertFalse(released().contains("said while it could not be saved"), released());
        assertTrue(released().contains("said after that"), released());
        assertTrue(read(in(ProjectFolder.ConversationSharing.PRIVATE))
                .contains("said while it could not be saved"));
    }

    /**
     * Review 1.145.0 recorder-01: what holds when a question is asked decides. Saving switched
     * on while its answer comes - ticked in Settings, which never cuts an answer off - released
     * the question asked while nothing was saved.
     */
    @Test
    void aQuestionAskedWhileNothingWasSavedIsNotReleasedWhenSavingGoesOnDuringItsAnswer()
            throws IOException {
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        whileAnswering = () -> saving(true);
        session.send("asked while nothing was saved");

        session.send("asked after saving went on");

        assertFalse(released().contains("asked while nothing was saved"), released());
        assertTrue(released().contains("asked after saving went on"), released());
        assertTrue(read(in(ProjectFolder.ConversationSharing.PRIVATE))
                .contains("asked while nothing was saved"));
        assertTrue(notices.get(0).contains("so far is kept out of git"), notices.toString());
    }

    /** Review 1.145.0 recorder-01: saving back on during the answer to a question asked off. */
    @Test
    void aQuestionAskedWhileSavingWasOffIsNotReleasedWhenItGoesBackOnDuringItsAnswer()
            throws IOException {
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        saving(true);
        session.send("first, released");
        saving(false);
        whileAnswering = () -> saving(true);
        session.send("asked while it was off");

        session.send("after that");

        assertFalse(released().contains("asked while it was off"), released());
        assertTrue(released().contains("first, released"), released());
        assertTrue(released().contains("after that"), released());
        assertTrue(read(in(ProjectFolder.ConversationSharing.PRIVATE))
                .contains("asked while it was off"));
    }

    /** Review 1.145.0 recorder-01: Release chosen while a question asked undecided is answered. */
    @Test
    void aQuestionAskedUndecidedIsNotReleasedWhenReleaseIsChosenDuringItsAnswer()
            throws IOException {
        saving(true);
        whileAnswering = () -> sharing = ProjectFolder.ConversationSharing.RELEASED;
        session.send("asked while undecided");

        session.send("after that");

        assertFalse(released().contains("asked while undecided"), released());
        assertTrue(released().contains("after that"), released());
        assertTrue(read(in(ProjectFolder.ConversationSharing.PRIVATE))
                .contains("asked while undecided"));
    }

    /** Review 1.145.0 recorder-01: Release chosen again during the answer to one asked private. */
    @Test
    void aQuestionAskedPrivateIsNotReleasedWhenReleaseIsChosenAgainDuringItsAnswer()
            throws IOException {
        saving(true);
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        session.send("first, released");
        sharing = ProjectFolder.ConversationSharing.PRIVATE;
        whileAnswering = () -> sharing = ProjectFolder.ConversationSharing.RELEASED;
        session.send("asked while private");

        session.send("after that");

        assertFalse(released().contains("asked while private"), released());
        assertTrue(released().contains("first, released"), released());
        assertTrue(released().contains("after that"), released());
        assertTrue(read(in(ProjectFolder.ConversationSharing.PRIVATE))
                .contains("asked while private"));
    }

    /**
     * Review 1.145.0 recorder-01: the choice is read when a question is asked. One that cannot
     * be read is not a Release, and the failure stays out of send(), where it left the session
     * busy and every later question ignored.
     */
    @Test
    void aChoiceThatCannotBeReadWhenAQuestionIsAskedIsNotARelease() throws IOException {
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        sharingUnreadable = true;
        whileAnswering = () -> sharingUnreadable = false;
        session.send("asked while the choice could not be read");

        session.send("after that");

        assertFalse(session.isBusy());
        assertFalse(released().contains("asked while the choice could not be read"), released());
        assertTrue(released().contains("after that"), released());
    }

    /**
     * Review 1.145.0 recorder-03: after a split the private file is the whole conversation, so
     * accepting the proposal of the answer that showed the switch is in a file.
     */
    @Test
    void aDecisionAfterTheSplitIsSavedInThePrivateFile() throws IOException {
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.PRIVATE;
        session.send("said while private");
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        reply = DEFINITION;
        session.send("answered when the switch was seen");

        session.accept(session.turns().get(1).getProposals().get(0));

        String kept = read(in(ProjectFolder.ConversationSharing.PRIVATE));
        assertTrue(kept.contains(": accepted"), kept);
        assertFalse(kept.contains(": not decided"), kept);
    }

    /**
     * Review 1.145.0 recorder-03: Revise asked just after choosing Release splits the
     * conversation while its answer is still coming; that answer reaches the private file.
     */
    @Test
    void anAnswerInFlightAtTheSplitIsSavedInThePrivateFile() throws IOException {
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.PRIVATE;
        reply = DEFINITION;
        session.send("said while private");
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        queueCalls = true;
        reply = "the revised answer";
        assertTrue(session.revise(session.turns().get(0).getProposals().get(0), "shorter"));

        drain();

        String kept = read(in(ProjectFolder.ConversationSharing.PRIVATE));
        assertTrue(kept.contains("the revised answer"), kept);
    }

    /**
     * Review 1.145.0 recorder-04: switched to private, the conversation was said to be kept out
     * of git while its released part stays where git sees it. The notice names that file.
     */
    @Test
    void goingPrivateNamesTheReleasedFileGitStillSees() throws Exception {
        assertEquals(0, git("init", "-q"));
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        session.send("said while released");
        File releasedFile = in(ProjectFolder.ConversationSharing.RELEASED);
        sharing = ProjectFolder.ConversationSharing.PRIVATE;

        session.send("said after the switch");

        assertEquals(1, git("check-ignore", "-q", relative(releasedFile)), "git sees it");
        assertEquals(2, notices.size(), notices.toString());
        assertTrue(notices.get(1).contains("released/" + releasedFile.getName()),
                notices.get(1));
    }

    /** Review 1.145.0 recorder-04: a second split does not say the released part is kept out. */
    @Test
    void aSecondSplitNamesTheReleasedFileInsteadOfSayingAllIsKeptOut() throws IOException {
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        project.setSavingConversations(true);
        session.send("first, saved");
        String releasedFile = in(ProjectFolder.ConversationSharing.RELEASED).getName();
        project.setSavingConversations(false);
        session.send("said while it was off");
        project.setSavingConversations(true);

        session.send("when it was seen again");

        assertEquals(2, notices.size(), notices.toString());
        assertFalse(notices.get(1).contains("so far is kept out of git"), notices.get(1));
        assertTrue(notices.get(1).contains("released/" + releasedFile), notices.get(1));
    }

    /** Review 1.145.0 recorder-04: private, split to Release, then private again. */
    @Test
    void goingPrivateAgainAfterASplitNamesTheReleasedFile() throws IOException {
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.PRIVATE;
        session.send("said while private");
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        session.send("answered when the switch was seen");
        session.send("released");
        String releasedFile = in(ProjectFolder.ConversationSharing.RELEASED).getName();
        sharing = ProjectFolder.ConversationSharing.PRIVATE;

        session.send("said when private again");

        assertEquals(3, notices.size(), notices.toString());
        assertTrue(notices.get(2).contains("released/" + releasedFile), notices.get(2));
        assertFalse(released().contains("said when private again"), released());
    }

    /**
     * Review 1.145.0 recorder-07: the person was told the conversation was saved, then that it
     * could not be. A failed first write gives only the problem; the first written save says
     * where it went.
     */
    @Test
    void aFailedFirstWriteIsNotAnnouncedAsSaved() throws IOException {
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.PRIVATE;
        File inTheWay = project.conversationsDirectory();
        Files.write(inTheWay.toPath(), "a file where the folder goes"
                .getBytes(StandardCharsets.UTF_8));
        session.send("not written");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(notices.isEmpty(), notices.toString());
        Files.delete(inTheWay.toPath());

        session.send("written");

        assertEquals(1, notices.size(), notices.toString());
        assertTrue(notices.get(0).contains("kept out of git"), notices.get(0));
        assertTrue(read(in(ProjectFolder.ConversationSharing.PRIVATE)).contains("not written"));
    }

    /** Review 1.145.0 recorder-07, for a released conversation. */
    @Test
    void aFailedFirstReleasedWriteIsNotAnnouncedAsSaved() throws IOException {
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.RELEASED;
        File inTheWay = project.conversationsDirectory(ProjectFolder.ConversationSharing.RELEASED);
        inTheWay.getParentFile().mkdirs();
        Files.write(inTheWay.toPath(), "a file where the folder goes"
                .getBytes(StandardCharsets.UTF_8));
        session.send("not written");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(notices.isEmpty(), notices.toString());
        Files.delete(inTheWay.toPath());

        session.send("written");

        assertEquals(1, notices.size(), notices.toString());
        assertTrue(notices.get(0).contains("released/"), notices.get(0));
        assertTrue(released().contains("not written"), released());
    }

    /**
     * Review 1.145.0 recorder-08: a .gitignore inside conversations/ that takes them back is
     * named, not assistant/.gitignore; the person learns that git may see what was saved there
     * before; and it is said once, not at every answer and decision.
     */
    @Test
    void aGitignoreThatTakesConversationsBackIsNamedOnceWithWhatGitMayNowSee() throws Exception {
        assertEquals(0, git("init", "-q"));
        project.setSavingConversations(true);
        sharing = ProjectFolder.ConversationSharing.PRIVATE;
        session.send("saved before");
        File before = in(ProjectFolder.ConversationSharing.PRIVATE);
        session.clear();
        File inner = takingThemBack();
        reply = DEFINITION;
        session.send("not saved");
        session.accept(session.turns().get(0).getProposals().get(0));

        session.send("not saved either");

        assertEquals(1, git("check-ignore", "-q", relative(before)), "git sees it now");
        assertEquals(1, problems.size(), problems.toString());
        String problem = problems.get(0);
        assertTrue(problem.contains(inner.getPath()), problem);
        assertFalse(problem.contains("without "
                + new File(project.getDirectory(), ".gitignore").getPath()), problem);
        assertTrue(problem.contains("Git may also see the conversations saved in "
                + project.conversationsDirectory().getPath() + " before"), problem);
    }

    /** Review 1.145.0 recorder-08: once per conversation and reason. */
    @Test
    void theRulesProblemIsSaidAgainInANewConversationAndForAnotherReason() throws IOException {
        project.setSavingConversations(true);
        File inner = takingThemBack();
        session.send("q");
        session.send("again");
        assertEquals(1, problems.size(), problems.toString());
        assertFalse(problems.get(0).contains("Git may also see"), "nothing was saved before: "
                + problems.get(0));
        session.clear();

        session.send("a new conversation");
        assertEquals(2, problems.size(), problems.toString());
        Files.delete(inner.toPath());
        File gitignore = new File(project.getDirectory(), ".gitignore");
        gitignore.mkdirs();

        session.send("another reason");

        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(2).contains(gitignore.getPath()), problems.get(2));
    }
}
