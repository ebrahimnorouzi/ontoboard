package de.fizkarlsruhe.ise.ontoboard.assistant.history;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Turn;
import de.fizkarlsruhe.ise.ontoboard.assistant.project.ProjectFolder;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheck;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.AssistantSession;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.SessionListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Writes a session's conversation into the project while "Save conversations in this project"
 * is on: after each answer, and after each decision on a proposal, so the file says what became
 * of it. A cleared conversation is a new file. With the switch off nothing is written, not even
 * the folder. The rules that keep them out of git are in place before the first word is written
 * (1.142.0); a person who chose to release theirs has them written to conversations/released/,
 * which git sees, and everyone else's stay out of git - one person's choice never decides another's
 * (1.144.0, review 1.143.0 I-1). The first save of each conversation says where it went. A
 * released file holds only turns said while it was being saved (1.145.0, review 1.144.0
 * privacy-07), as things stood when each question was asked; a turn the rules kept from being
 * saved counts as said while nothing was. Once a conversation has a file in conversations/, that
 * file is the whole conversation, and a released file only what was said after the split
 * (1.146.0, review 1.145.0 recorder-01 to recorder-03). What a notice says has been written.
 * When the clone's own exclude cannot be written, it is saved all the same, and the person is
 * told which file, and why it matters, once per conversation (1.149.0, review 1.148.0
 * recorder-01): every such file, the project's own clone first, each once per conversation
 * (1.150.0, review 1.149.0 recorder-02), and beside them a link or a junction that could not be
 * followed (1.151.0, review 1.150.0 recorder-01).
 */
public final class ConversationRecorder implements SessionListener {

    /**
     * Whether another program's lock can be what stops a write: on Windows, where a file one
     * program holds open another cannot write, and nearly every failure but access denied is
     * that. A test sets it.
     */
    static boolean locking = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).startsWith("windows");

    private final AssistantSession session;
    private final Supplier<ProjectFolder> project;
    private final Function<ProjectFolder, ProjectFolder.ConversationSharing> sharing;
    private final Function<Turn, ConversationLog.Header> header;
    private final Supplier<Collection<String>> keys;
    private final Consumer<String> notices;
    private final Consumer<String> problems;
    /** The conversation, by its first turn, and its file in conversations/ once it has one. */
    private Turn first;
    private ConversationLog privateLog;
    /** Its file in conversations/released/ while it is released, and the first turn it holds. */
    private ConversationLog releasedLog;
    private int releasedFrom;
    /**
     * Its files in conversations/released/ that hold a turn. Git sees them whatever is chosen
     * later, so a notice that says it is kept out of git names them (1.146.0, review 1.145.0
     * recorder-04).
     */
    private final List<String> releasedFiles = new ArrayList<String>();
    /** Where it was last said to be saved: RELEASED, PRIVATE for any other choice, or null. */
    private ProjectFolder.ConversationSharing told;
    /**
     * Why the rules could not be put in place for it, each reason said once (1.146.0, review
     * 1.145.0 recorder-08).
     */
    private final java.util.Set<String> rulesProblems = new java.util.HashSet<String>();
    /**
     * The conversation that had turns asked or answered while nothing was saved, while it was
     * not released, or while the rules could not be put in place, and how many: none of them
     * goes to conversations/released/.
     */
    private Turn unsaved;
    private int unsavedTurns;
    /**
     * Every key known at any save. Each save writes the whole conversation again, and a key
     * replaced in Settings was written in clear at the next one (review 1.144.0 privacy-01).
     */
    private final java.util.Set<String> everKnown = new java.util.LinkedHashSet<String>();

    private ConversationRecorder(AssistantSession session, Supplier<ProjectFolder> project,
            Function<ProjectFolder, ProjectFolder.ConversationSharing> sharing,
            Function<Turn, ConversationLog.Header> header, Supplier<Collection<String>> keys,
            Consumer<String> notices, Consumer<String> problems) {
        this.session = session;
        this.project = project;
        this.sharing = sharing;
        this.header = header;
        this.keys = keys;
        this.notices = notices;
        this.problems = problems;
    }

    /**
     * @param sharing this person's choice for their own conversations in a project
     * @param header the header for a conversation that starts with this turn
     * @param keys every key OntoBoard knows now, to redact
     * @param notices told where a conversation is saved and whether git sees it, once a file of
     *     it is written, and again when that changes
     * @param problems told when a file cannot be written, or the rules that keep it out of git
     *     cannot be put in place, or a clone's own exclude cannot be written - once per
     *     conversation and reason, and for an exclude once per file
     */
    public static ConversationRecorder attach(AssistantSession session,
            Supplier<ProjectFolder> project,
            Function<ProjectFolder, ProjectFolder.ConversationSharing> sharing,
            Function<Turn, ConversationLog.Header> header,
            Supplier<Collection<String>> keys, Consumer<String> notices,
            Consumer<String> problems) {
        ConversationRecorder recorder = new ConversationRecorder(session, project, sharing,
                header, keys, notices, problems);
        session.addListener(recorder);
        return recorder;
    }

    /**
     * What holds when a question is asked decides whether it may be released. Saving ticked on,
     * or Release chosen, while its answer comes - Settings never cuts an answer off, and a pulled
     * project file does the same - was seen only at the save after the answer, which released
     * the question with it (1.146.0, review 1.145.0 recorder-01).
     */
    @Override
    public void onTurnStarted(Turn turn) {
        boolean released;
        try {
            ProjectFolder folder = project.get();
            released = folder != null && folder.isSavingConversations()
                    && sharing.apply(folder) == ProjectFolder.ConversationSharing.RELEASED;
        } catch (RuntimeException unknown) {
            // Not known is not released. Thrown from here it would end send() after the
            // session became busy, and every later question would be ignored.
            released = false;
        }
        if (!released) {
            notReleased(session.turns());
        }
    }

    @Override
    public void onTurnFinished(Turn turn) {
        save();
    }

    @Override
    public void onProposalChanged(Proposal proposal) {
        save();
    }

    @Override
    public void onProposalChecked(Proposal proposal, ProposalCheck.Check check) {
        save();
    }

    /** Where a conversation is saved, and what git does with it. */
    static String savedWhere(ProjectFolder folder, ProjectFolder.ConversationSharing choice) {
        String where = folder.getDirectory().getName() + "/conversations/";
        if (choice == ProjectFolder.ConversationSharing.RELEASED) {
            return "This conversation is saved in " + where + "released/, which git sees: it goes"
                    + " with the project when you commit.";
        }
        if (choice == ProjectFolder.ConversationSharing.PRIVATE) {
            return "This conversation is saved in " + where + ", kept out of git.";
        }
        return "This conversation is saved in " + where + ", kept out of git until you choose in"
                + " Settings > This project whether yours are released.";
    }

    /**
     * When a released file starts after its conversation did. A part released before stays where
     * git sees it, so "what was said so far is kept out of git" is said only when there is none
     * (1.146.0, review 1.145.0 recorder-04).
     */
    static String splitWhere(ProjectFolder folder, List<String> releasedFiles) {
        String where = folder.getDirectory().getName() + "/conversations/";
        String fromHere = "; from here on this conversation is saved in " + where + "released/,"
                + " which git sees: it goes with the project when you commit.";
        if (releasedFiles.isEmpty()) {
            return "What was said so far is kept out of git, in " + where + fromHere;
        }
        return releasedBefore(where, releasedFiles) + "; the rest of what was said until now is"
                + " kept out of git, in " + where + fromHere;
    }

    /**
     * Switched to private when a part of it was released: that part stays in released/, and
     * "kept out of git" alone was false for it (1.146.0, review 1.145.0 recorder-04).
     */
    static String stillReleased(ProjectFolder folder, ProjectFolder.ConversationSharing choice,
            List<String> releasedFiles) {
        String where = folder.getDirectory().getName() + "/conversations/";
        boolean one = releasedFiles.size() == 1;
        return "From here on this conversation is saved in " + where + ", kept out of git"
                + (choice == ProjectFolder.ConversationSharing.UNDECIDED ? " until you choose in"
                        + " Settings > This project whether yours are released" : "")
                + ". " + releasedBefore(where, releasedFiles) + ": " + (one ? "it goes" : "they go")
                + " with the project when you commit unless you delete " + (one ? "it" : "them")
                + ".";
    }

    /**
     * Why the rules that keep conversations out of git are not in place, naming the file that is
     * wrong as the exception does. It blamed assistant/.gitignore when a .gitignore inside
     * conversations/ took them back, and said nothing of the conversations git sees then
     * (1.146.0, review 1.145.0 recorder-08).
     */
    static String rulesProblem(ProjectFolder folder, IOException noRule) {
        String why = noRule.getMessage() == null ? noRule.toString() : noRule.getMessage();
        if (noRule instanceof FileSystemException
                && ((FileSystemException) noRule).getFile() != null) {
            FileSystemException failed = (FileSystemException) noRule;
            // On one line: a shared file another program held open broke it inside the
            // parentheses, as an exclude's did (1.151.0, beside review 1.150.0 recorder-03).
            return "The conversation was not saved: the rules that keep it out of git could not"
                    + " be put in place (" + failed.getFile() + ": " + (failed.getReason() == null
                            ? failed.getClass().getSimpleName() : bare(failed.getReason())) + ").";
        }
        File conversations = folder.conversationsDirectory();
        if (why.startsWith(new File(conversations, ".gitignore").getPath())) {
            String text = "The conversation was not saved: " + why + ", and while it does no"
                    + " other rule can keep one out.";
            File[] before = conversations.listFiles((parent, name) -> name.endsWith(".md"));
            if (before != null && before.length > 0) {
                // That file beats the clone's own exclude too: what was saved there is not
                // kept out of git meanwhile.
                text += " Git may also see the conversations saved in " + conversations.getPath()
                        + " before, until that file no longer takes them back.";
            }
            return text;
        }
        // A reason that names no file, as reading a folder says on Linux ("Is a directory"):
        // assistant/.gitignore is the only file read or written then.
        return "The conversation was not saved: without "
                + new File(folder.getDirectory(), ".gitignore").getPath()
                + " git could pick it up (" + why + ").";
    }

    /**
     * When the clone's own .git/info/exclude could not be written: saving goes on, for the shared
     * assistant/.gitignore holds, but a pulled deletion of that file leaves nothing between git
     * and the private conversations. It was dropped, and the person heard only "kept out of git"
     * (1.149.0, review 1.148.0 recorder-01). Names the file and the reason, as rulesProblem does:
     * an AccessDeniedException's message is only the path. Settings says the same when the switch
     * is ticked. Every exclude that could not be written is named, the project's own clone first:
     * only the first was, and the person's own clone could stay unnamed behind another account's
     * (1.150.0, review 1.149.0 recorder-02).
     */
    public static String excludeProblem(ProjectFolder folder, IOException notWritten) {
        return excludeProblem(folder, everyExclude(notWritten));
    }

    /**
     * The exclude that could not be written and each other one, which
     * {@link ProjectFolder#ensureConversationRules()} adds to it as suppressed - and after them a
     * link or a junction that could not be followed (1.151.0, review 1.150.0 recorder-01).
     */
    static List<IOException> everyExclude(IOException notWritten) {
        List<IOException> every = new ArrayList<IOException>();
        every.add(notWritten);
        for (Throwable more : notWritten.getSuppressed()) {
            if (more instanceof IOException) {
                every.add((IOException) more);
            }
        }
        return every;
    }

    private static String excludeProblem(ProjectFolder folder, List<IOException> notWritten) {
        String shared = folder.getDirectory().getName() + "/.gitignore";
        // A link or a junction that could not be followed has a clause of its own, beside the
        // excludes: it came only first and alone, and one that failed beside an exclude was
        // never said (1.151.0, review 1.150.0 recorder-01).
        List<IOException> excludes = new ArrayList<IOException>();
        List<ProjectFolder.NotFollowed> unfollowed = new ArrayList<ProjectFolder.NotFollowed>();
        for (IOException each : notWritten) {
            if (each instanceof ProjectFolder.NotFollowed) {
                unfollowed.add((ProjectFolder.NotFollowed) each);
            } else {
                excludes.add(each);
            }
        }
        StringBuilder said = new StringBuilder("Your private conversations are kept out of git by ")
                .append(shared).append(", but ");
        if (!excludes.isEmpty()) {
            List<String> denied = new ArrayList<String>();
            List<String> held = new ArrayList<String>();
            for (int i = 0; i < excludes.size(); i++) {
                IOException each = excludes.get(i);
                if (i > 0) {
                    said.append(i == excludes.size() - 1 ? " and " : ", ");
                }
                if (each instanceof FileSystemException
                        && ((FileSystemException) each).getFile() != null) {
                    FileSystemException failed = (FileSystemException) each;
                    String reason = failed.getReason() != null ? bare(failed.getReason())
                            : failed instanceof AccessDeniedException ? "access denied"
                            : failed.getClass().getSimpleName();
                    said.append(failed.getFile()).append(" could not be written (").append(reason)
                            .append(")");
                    // Held open only where a lock can be the cause, and only for a failure of
                    // no kind of its own: a read-only file system, a full disk, a .git/info that
                    // is a file and anything but Windows were told to close a program (the review
                    // of the 1.151.0 recorder fix). Their reason is shown.
                    if (failed instanceof AccessDeniedException) {
                        denied.add(failed.getFile());
                    } else if (locking && failed.getClass() == FileSystemException.class) {
                        held.add(failed.getFile());
                    }
                } else {
                    said.append("the rules could not be put into ")
                            .append(excludes.size() == 1 ? "your clone's" : "a clone's")
                            .append(" own .git/info/exclude (").append(bare(each.getMessage()
                                    == null ? each.toString() : each.getMessage())).append(")");
                }
            }
            said.append(": if someone deletes ").append(shared).append(" and you pull, git sees")
                    .append(" them until OntoBoard writes it again.")
                    .append(advice(denied, held, excludes.size()));
        }
        for (int i = 0; i < unfollowed.size(); i++) {
            ProjectFolder.NotFollowed each = unfollowed.get(i);
            // Every exclude found was written, when it is alone: what failed is finding them
            // all (1.149.0, the review of the recorder-01 fix).
            said.append(excludes.isEmpty() && i == 0 ? "OntoBoard could not follow "
                    : " OntoBoard also could not follow ").append(each.getFolder())
                    .append(" to where it really is (")
                    .append(bare(each.getReason())).append("), so a clone it leads into may not")
                    .append(" have the rules that keep them out once ").append(shared)
                    .append(" is deleted.");
        }
        return said.toString();
    }

    /**
     * What frees the excludes that could not be written: a read-only one is made writable, and
     * one another program holds open is freed by closing that program. "Make that file writable"
     * was said of a file that was, held open by another program (1.151.0, review 1.150.0
     * recorder-03). "That file" only when every file named is one of them: "it" came after
     * "until OntoBoard writes it again", said of the shared .gitignore (the review of the 1.151.0
     * recorder fix).
     *
     * @param named how many excludes were named, each with or without advice
     */
    private static String advice(List<String> denied, List<String> held, int named) {
        if (denied.size() == named) {
            return named == 1 ? " Make that file writable." : " Make those files writable.";
        }
        if (held.size() == named) {
            return named == 1 ? " Close the program that holds that file open."
                    : " Close the programs that hold those files open.";
        }
        // Not all of one kind: "that file" would not say which.
        StringBuilder out = new StringBuilder();
        if (!denied.isEmpty()) {
            out.append(" Make ").append(listed(denied)).append(" writable");
        }
        if (!held.isEmpty()) {
            out.append(denied.isEmpty() ? " Close the program" : ", and close the program")
                    .append(held.size() == 1 ? " that holds " : "s that hold ")
                    .append(listed(held)).append(" open");
        }
        return out.length() == 0 ? "" : out.append('.').toString();
    }

    /** "a", "a and b", "a, b and c". */
    private static String listed(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(i == names.size() - 1 ? " and " : ", ");
            }
            out.append(names.get(i));
        }
        return out.toString();
    }

    /**
     * A reason as the system words it, for inside parentheses in a sentence: on one line, without
     * its full stop. Windows ends one with ".\r\n" (FormatMessage), which broke the line inside
     * the parentheses, in the chat and in Settings (1.151.0, review 1.150.0 recorder-03).
     */
    static String bare(String reason) {
        String text = reason.trim().replaceAll("\\s*[\\r\\n]+\\s*", " ");
        return text.endsWith(".") ? text.substring(0, text.length() - 1).trim() : text;
    }

    /** "The part released before stays in assistant/conversations/released/..., which git sees". */
    private static String releasedBefore(String where, List<String> releasedFiles) {
        StringBuilder files = new StringBuilder();
        for (int i = 0; i < releasedFiles.size(); i++) {
            if (i > 0) {
                files.append(i == releasedFiles.size() - 1 ? " and " : ", ");
            }
            files.append(where).append("released/").append(releasedFiles.get(i));
        }
        return (releasedFiles.size() == 1 ? "The part released before stays in "
                : "The parts released before stay in ") + files + ", which git sees";
    }

    private void save() {
        Collection<String> now = keys.get();
        if (now != null) {
            everKnown.addAll(now);
        }
        List<Turn> turns = session.turns();
        if (turns.isEmpty()) {
            return;
        }
        if (first != turns.get(0)) {
            // A new conversation: its own files, notices and problems.
            first = turns.get(0);
            privateLog = null;
            releasedLog = null;
            releasedFiles.clear();
            told = null;
            rulesProblems.clear();
        }
        ProjectFolder folder = project.get();
        if (folder == null || !folder.isSavingConversations()) {
            notReleased(turns);
            return;
        }
        IOException notInExclude;
        try {
            notInExclude = folder.ensureConversationRules();
        } catch (IOException noRule) {
            // The person is told it was not saved: it counts as said while nothing was saved,
            // or the next save under Release released it from its first turn (1.146.0, review
            // 1.145.0 recorder-02).
            notReleased(turns);
            // Once per conversation and reason: it came at every answer, decision and reasoner
            // check (1.146.0, review 1.145.0 recorder-08).
            if (rulesProblems.add(noRule.getClass().getName() + ": " + noRule.getMessage())) {
                problems.accept(rulesProblem(folder, noRule));
            }
            return;
        }
        try {
            saveIn(folder, turns);
        } finally {
            // After the notice of where it went, which this qualifies; once per conversation and
            // reason, as the rules' own problems are (1.149.0, review 1.148.0 recorder-01) - and
            // per file: every exclude that could not be written is named, one already named in
            // this conversation is not named again (1.150.0, review 1.149.0 recorder-02).
            if (notInExclude != null) {
                List<IOException> unsaid = new ArrayList<IOException>();
                for (IOException each : everyExclude(notInExclude)) {
                    if (rulesProblems.add("exclude " + each.getClass().getName() + ": "
                            + each.getMessage())) {
                        unsaid.add(each);
                    }
                }
                if (!unsaid.isEmpty()) {
                    problems.accept(excludeProblem(folder, unsaid));
                }
            }
        }
    }

    /** Writes the conversation where this person's choice puts it, the rules being in place. */
    private void saveIn(ProjectFolder folder, List<Turn> turns) {
        ProjectFolder.ConversationSharing choice = sharing.apply(folder);
        if (choice != ProjectFolder.ConversationSharing.RELEASED) {
            // Kept out of git, from the first turn. Switched to private while released, the
            // released file is not written again (the git probe of the 1.144.0 design, m2).
            releasedLog = null;
            if (privateLog == null) {
                privateLog = start(folder, choice);
            }
            // Said once the file is written: said before, a first write that failed read as
            // saved and then as not saved, and the save that worked said nothing (1.146.0,
            // review 1.145.0 recorder-07).
            if (write(privateLog, turns, 0) && told != ProjectFolder.ConversationSharing.PRIVATE) {
                notices.accept(releasedFiles.isEmpty() ? savedWhere(folder, choice)
                        : stillReleased(folder, choice, releasedFiles));
                told = ProjectFolder.ConversationSharing.PRIVATE;
            }
            return;
        }
        int notSaved = unsaved == first ? unsavedTurns : 0;
        if (releasedLog == null || notSaved > releasedFrom) {
            if (privateLog == null && notSaved == 0) {
                releasedLog = start(folder, choice);
                releasedFrom = 0;
            } else {
                // What was said before the person chose to release (m2), before saving went on
                // or while it was off - by a colleague's project file, say - stays private,
                // written there once more, and the released file starts from here.
                if (privateLog == null) {
                    privateLog = start(folder, ProjectFolder.ConversationSharing.PRIVATE);
                }
                if (!write(privateLog, turns, 0)) {
                    return;
                }
                unsaved = null;
                releasedLog = start(folder, choice);
                releasedFrom = turns.size();
                notices.accept(splitWhere(folder, releasedFiles));
                told = ProjectFolder.ConversationSharing.RELEASED;
            }
        } else if (privateLog != null) {
            // The private file stays the whole conversation: written only at the split, it
            // missed every decision, reasoner check and answer that came after it - an answer in
            // flight at the split among them, which no file held (1.146.0, review 1.145.0
            // recorder-03). It is kept out of git, so writing it again releases nothing.
            write(privateLog, turns, 0);
        }
        if (write(releasedLog, turns, releasedFrom) && releasedFrom < turns.size()) {
            if (!releasedFiles.contains(releasedLog.getFile().getName())) {
                releasedFiles.add(releasedLog.getFile().getName());
            }
            // The split said where it went when its private file was written; a conversation
            // released from its first turn says so once that turn is written (recorder-07).
            if (told != ProjectFolder.ConversationSharing.RELEASED) {
                notices.accept(savedWhere(folder, choice));
                told = ProjectFolder.ConversationSharing.RELEASED;
            }
        }
    }

    /** Every turn of this conversation so far stays out of conversations/released/. */
    private void notReleased(List<Turn> turns) {
        if (!turns.isEmpty()) {
            unsaved = turns.get(0);
            unsavedTurns = turns.size();
        }
    }

    private ConversationLog start(ProjectFolder folder, ProjectFolder.ConversationSharing choice) {
        return ConversationLog.start(folder.conversationsDirectory(choice),
                ConversationLog.stem(first.getStarted(), first.getConnectionName()),
                header.apply(first));
    }

    /** A file, from the first turn it holds; false when it could not be written. */
    private boolean write(ConversationLog log, List<Turn> turns, int from) {
        if (from >= turns.size()) {
            return true;
        }
        try {
            log.write(turns.subList(from, turns.size()), everKnown, session::checkOf);
            return true;
        } catch (IOException unwritable) {
            problems.accept("The conversation could not be saved in "
                    + log.getFile().getParent() + ": " + unwritable.getMessage());
            return false;
        }
    }
}
