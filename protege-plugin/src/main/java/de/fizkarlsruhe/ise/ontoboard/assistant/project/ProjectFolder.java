package de.fizkarlsruhe.ise.ontoboard.assistant.project;

import de.fizkarlsruhe.ise.ontoboard.assistant.history.KeyRedactor;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.RoleStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The assistant's settings that belong to a project rather than a person: a folder called
 * {@code assistant} next to the ontology file - {@code src/ontology/assistant/} in an ODK
 * repository - so they are committed and the whole team gets them (spec D3, D4).
 *
 * <ul>
 *   <li>{@code assistant/project.properties}: {@code localModelsOnly=true} keeps this ontology
 *       away from cloud models.
 *   <li>{@code assistant/roles/<id>.md}: edited and custom roles.
 *   <li>{@code assistant/conversations/<start>-<connection>.md}: saved conversations, when
 *       {@code saveConversations=true}, kept out of git by {@code assistant/.gitignore} - and
 *       {@code assistant/conversations/released/} for the conversations of those who chose to
 *       release theirs. That choice is each person's, kept in their own preferences (1.144.0).
 * </ul>
 *
 * <p>Nothing is created until something is saved: opening an ontology must not leave a folder in
 * somebody's repository.
 */
public final class ProjectFolder {

    static final String FOLDER = "assistant";
    static final String PROPERTIES = "project.properties";
    static final String LOCAL_ONLY = "localModelsOnly";
    static final String SAVE_CONVERSATIONS = "saveConversations";
    static final String CONVERSATIONS = "conversations";
    static final String RELEASED = "released";
    static final String GITIGNORE = ".gitignore";
    /**
     * OntoBoard's block in assistant/.gitignore: everything in conversations/ stays out of git
     * except conversations/released/. It is the same whoever writes it, so a colleague who commits
     * it changes nothing for anyone (1.144.0, review 1.143.0 I-1).
     */
    static final String BLOCK_COMMENT = "# OntoBoard: saved conversations stay out of git unless"
            + " their author released them (Settings > Save conversations in this project).";
    private static final String[] BLOCK = {BLOCK_COMMENT, CONVERSATIONS + "/*",
        "!" + CONVERSATIONS + "/" + RELEASED + "/"};
    /** What 1.142.0 and 1.143.0 wrote. It ignored the folder itself, so git could take nothing back. */
    private static final String OLD_COMMENT = "# OntoBoard: saved conversations stay out of git"
            + " (Settings > Save conversations in this project).";

    /**
     * A link or a junction on the way to the conversations folder that could not be followed to
     * where it really is: a clone it leads into may lack the rules, though every exclude found
     * was written. Said as such - it was said as rules that could not be written (1.149.0, the
     * review of the recorder-01 fix).
     */
    public static final class NotFollowed extends IOException {
        private static final long serialVersionUID = 1L;
        private final File folder;
        private final String reason;

        NotFollowed(File folder, Throwable failed) {
            super(folder + " could not be followed (" + reasonOf(failed) + ")", failed);
            this.folder = folder;
            this.reason = reasonOf(failed);
        }

        /** The link or junction, or the folder as opened when its canonical form failed. */
        public File getFolder() {
            return folder;
        }

        public String getReason() {
            return reason;
        }

        private static String reasonOf(Throwable failed) {
            if (failed instanceof java.nio.file.FileSystemException
                    && ((java.nio.file.FileSystemException) failed).getReason() != null) {
                return ((java.nio.file.FileSystemException) failed).getReason();
            }
            if (failed instanceof java.nio.file.AccessDeniedException) {
                return "access denied";
            }
            return failed.getMessage() == null ? failed.toString() : failed.getMessage();
        }
    }

    /**
     * A person's choice for their own saved conversations in a project (1.142.0), kept in their
     * preferences since 1.144.0 - in the shared project file, one person's Release published
     * everyone's (review 1.143.0 I-1).
     */
    public enum ConversationSharing {
        /** Not chosen: theirs stay out of git meanwhile. */
        UNDECIDED(""),
        /** Theirs go to conversations/, which git ignores. */
        PRIVATE("private"),
        /** Theirs go to conversations/released/, which git sees. */
        RELEASED("released");

        private final String id;

        ConversationSharing(String id) {
            this.id = id;
        }

        /** As kept in a person's preferences: "private", "released", or "". */
        public String id() {
            return id;
        }

        public static ConversationSharing of(String id) {
            for (ConversationSharing each : values()) {
                if (each.id.equals(id == null ? "" : id.trim())) {
                    return each;
                }
            }
            return UNDECIDED;
        }
    }

    private final File directory;

    private ProjectFolder(File directory) {
        this.directory = directory;
    }

    /** The folder for an ontology saved in this file, or null when it has no file yet. */
    public static ProjectFolder of(File ontologyFile) {
        if (ontologyFile == null) {
            return null;
        }
        File parent = ontologyFile.getAbsoluteFile().getParentFile();
        return parent == null ? null : new ProjectFolder(new File(parent, FOLDER));
    }

    public File getDirectory() {
        return directory;
    }

    /**
     * Names this project in a person's preferences: its folder's real path. The canonical path
     * kept a junction's name, so one project reached through a junction had two keys - and its
     * person two independent choices about releasing (1.146.0, review 1.144.0 privacy-08).
     */
    public String key() {
        return real(directory).getPath();
    }

    /**
     * A path with links and junctions followed: the deepest part that exists, as the file system
     * names it, and the rest as written - so the name is the same before and after the rest is
     * made (1.146.0, review 1.144.0 privacy-06 and privacy-08).
     */
    static File real(File file) {
        File absolute = file.getAbsoluteFile();
        java.util.Deque<String> rest = new java.util.ArrayDeque<String>();
        for (File up = absolute; up != null; up = up.getParentFile()) {
            if (up.exists()) {
                try {
                    java.nio.file.Path path = up.toPath().toRealPath();
                    for (String name : rest) {
                        path = path.resolve(name);
                    }
                    return path.toFile();
                } catch (IOException | SecurityException unresolved) {
                    break;
                }
            }
            rest.push(up.getName());
        }
        try {
            return absolute.getCanonicalFile();
        } catch (IOException unresolved) {
            return absolute;
        }
    }

    /**
     * OntoBoard's block, at the end of assistant/.gitignore (1.144.0). At the end, no later line can
     * take a private conversation back (review 1.143.0 M-1); the 1.142.0 rule, which ignored the
     * folder itself, goes. Every other line and the file's line endings stay; an unchanged file is
     * not written again.
     *
     * @return why a clone's own {@code .git/info/exclude} could not be written - the project's
     *     own clone's first, each other one that could not be added to it as suppressed (1.150.0,
     *     review 1.149.0 recorder-02), and after them a {@link NotFollowed} for a link or a
     *     junction on the way that could not be followed (1.151.0, review 1.150.0 recorder-01) -
     *     or that NotFollowed alone when every exclude found was written; null when every
     *     exclude found was written, or there is no clone. Saving goes on: the block in
     *     assistant/.gitignore holds. But
     *     once a pull deletes that file, only the exclude keeps private conversations out of git,
     *     and only the person can make it writable - dropped here, nobody was told (1.149.0,
     *     review 1.148.0 recorder-01). A caller that ignores it compiles unchanged.
     * @throws IOException when assistant/.gitignore cannot be written, or a .gitignore inside
     *     conversations/ takes conversations back: then nothing may be saved
     */
    public IOException ensureConversationRules() throws IOException {
        // A .gitignore inside conversations/ that takes files back beats any rule above it: no
        // block can keep a private conversation out of git then (the git probe, x2).
        File inner = new File(conversationsDirectory(), GITIGNORE);
        if (inner.isFile()) {
            String rules = new String(Files.readAllBytes(inner.toPath()), StandardCharsets.UTF_8);
            // Git skips a byte-order mark before the first line, which Windows PowerShell 5.1
            // writes, and trim() does not (1.145.0, review 1.144.0 privacy-02).
            if (rules.startsWith("\uFEFF")) {
                rules = rules.substring(1);
            }
            for (String line : rules.split("\\r?\\n")) {
                if (line.trim().startsWith("!")) {
                    throw new IOException(inner.getPath() + " takes conversations back into git"
                            + " (\"" + line.trim() + "\")");
                }
            }
        }
        Files.createDirectories(directory.toPath());
        File file = gitignore();
        String text = file.exists() ? new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8) : "";
        String ending = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<String>(java.util.Arrays.asList(
                text.isEmpty() ? new String[0] : text.split("\\r?\\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        List<String> wanted = new ArrayList<String>();
        for (String line : lines) {
            if (!isOurs(line)) {
                wanted.add(line);
            }
        }
        wanted.addAll(java.util.Arrays.asList(BLOCK));
        if (!wanted.equals(lines) || !text.endsWith("\n")) {
            StringBuilder updated = new StringBuilder();
            for (String line : wanted) {
                updated.append(line).append(ending);
            }
            Files.write(file.toPath(), updated.toString().getBytes(StandardCharsets.UTF_8));
        }
        // The same rules in the clone's own exclude, which nobody can commit away. The shared
        // block above still holds when it cannot be written, so this is no reason to stop.
        try {
            GitExclude.keepOutOfGit(conversationsDirectory());
            return null;
        } catch (IOException notWritten) {
            return notWritten;
        } catch (RuntimeException notWritten) {
            return new IOException(notWritten.toString(), notWritten);
        }
    }

    private static boolean isOurs(String line) {
        String rule = line.trim();
        if (rule.equals(OLD_COMMENT)) {
            return true;
        }
        for (String part : BLOCK) {
            if (rule.equals(part)) {
                return true;
            }
        }
        return rule.equals(CONVERSATIONS + "/") || rule.equals(CONVERSATIONS)
                || rule.equals("/" + CONVERSATIONS + "/") || rule.equals("/" + CONVERSATIONS);
    }

    private File gitignore() {
        return new File(directory, GITIGNORE);
    }

    /** Where a person's conversations go: conversations/released/ when they chose Release. */
    public File conversationsDirectory(ConversationSharing sharing) {
        return sharing == ConversationSharing.RELEASED
                ? new File(conversationsDirectory(), RELEASED) : conversationsDirectory();
    }

    /** The project's properties; empty without a file; null when the file cannot be read. */
    private Properties properties() {
        Properties properties = new Properties();
        File file = new File(directory, PROPERTIES);
        if (!file.isFile()) {
            return properties;
        }
        try (InputStream in = new FileInputStream(file)) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        } catch (IOException unreadable) {
            return null;
        }
    }

    public boolean isLocalModelsOnly() {
        Properties properties = properties();
        // A file that says "local only" but cannot be read is safer read as local only.
        return properties == null
                || Boolean.parseBoolean(properties.getProperty(LOCAL_ONLY, "false").trim());
    }

    /** "Save conversations in this project": off unless the file says so. */
    public boolean isSavingConversations() {
        Properties properties = properties();
        return properties != null && Boolean.parseBoolean(
                properties.getProperty(SAVE_CONVERSATIONS, "false").trim());
    }

    public void setLocalModelsOnly(boolean localOnly) throws IOException {
        write(localOnly, isSavingConversations());
    }

    public void setSavingConversations(boolean save) throws IOException {
        write(isLocalModelsOnly(), save);
    }

    /**
     * Written by hand rather than with {@code Properties.store}, which stamps the date into the
     * file: a committed file whose every save is a change is a file that conflicts.
     */
    private void write(boolean localOnly, boolean save) throws IOException {
        Files.createDirectories(directory.toPath());
        String text = "# OntoBoard assistant settings for this project, shared through git.\n"
                + "# localModelsOnly=true allows only models on this machine or this network.\n"
                + LOCAL_ONLY + "=" + localOnly + "\n"
                + "# saveConversations=true writes each conversation to assistant/conversations/,"
                + " keys removed.\n"
                + SAVE_CONVERSATIONS + "=" + save + "\n";
        Files.write(new File(directory, PROPERTIES).toPath(),
                text.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code assistant/conversations/}: where saved conversations go. */
    public File conversationsDirectory() {
        return new File(directory, CONVERSATIONS);
    }

    /**
     * The saved conversations, private and released, newest first: their names start with when
     * they started.
     */
    public List<File> conversations() {
        List<File> found = new ArrayList<File>();
        for (File folder : new File[] {conversationsDirectory(),
            conversationsDirectory(ConversationSharing.RELEASED)}) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".md")) {
                        found.add(file);
                    }
                }
            }
        }
        Collections.sort(found, (a, b) -> b.getName().compareTo(a.getName()));
        return found;
    }

    /**
     * Roles as files in {@code assistant/roles/}, which are committed with the project: every key
     * OntoBoard knows is redacted from them, and anything shaped like a key (1.143.0, review
     * 1.141.0 I5 - a known key without a known shape had been written).
     *
     * @param knownKeys the keys OntoBoard knows when a role is saved
     */
    public RoleStore roles(final java.util.function.Supplier<? extends java.util.Collection<String>>
            knownKeys) {
        final File roles = new File(directory, "roles");
        return new RoleStore() {
            @Override
            public Map<String, String> load() throws IOException {
                Map<String, String> texts = new LinkedHashMap<String, String>();
                File[] files = roles.listFiles();
                if (files == null) {
                    return texts;
                }
                java.util.Arrays.sort(files);
                for (File file : files) {
                    String name = file.getName();
                    if (file.isFile() && name.endsWith(".md")) {
                        texts.put(name.substring(0, name.length() - 3), new String(
                                Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                    }
                }
                return texts;
            }

            @Override
            public void save(String id, String text) throws IOException {
                Files.createDirectories(roles.toPath());
                // Committed with the project: no key stays in (1.138.0; known keys, 1.143.0).
                Files.write(new File(roles, id + ".md").toPath(), KeyRedactor.redact(text,
                        knownKeys.get()).getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public void remove(String id) throws IOException {
                Files.deleteIfExists(new File(roles, id + ".md").toPath());
            }

            private Boolean ignoresCase;

            /**
             * Asked of the file system the roles are saved in. Taken for granted, a text typed
             * for one's own "reviewer" went into a team's Reviewer.md on Linux, where the two
             * are two files (1.150.0, review 1.149.0 ui-2).
             */
            @Override
            public boolean ignoresCase() {
                if (ignoresCase == null) {
                    ignoresCase = Boolean.valueOf(sameInOtherCapitals(roles));
                }
                return ignoresCase.booleanValue();
            }
        };
    }

    /**
     * Whether a name in other capitals is the same file here: asked of a file in the folder if
     * it holds one, else of the folder, or of its nearest parent that exists, in its own parent.
     */
    static boolean sameInOtherCapitals(File folder) {
        File[] inside = folder.listFiles();
        if (inside != null) {
            for (File file : inside) {
                if (!flipped(file.getName()).equals(file.getName())) {
                    return sameAs(file);
                }
            }
        }
        for (File at = folder.getAbsoluteFile(); at != null; at = at.getParentFile()) {
            if (at.exists() && at.getParentFile() != null
                    && !flipped(at.getName()).equals(at.getName())) {
                return sameAs(at);
            }
        }
        return false;
    }

    /** Whether this existing file, named in other capitals, is itself. */
    private static boolean sameAs(File file) {
        File other = new File(file.getParentFile(), flipped(file.getName()));
        try {
            return other.exists() && Files.isSameFile(file.toPath(), other.toPath());
        } catch (IOException | RuntimeException unknown) {
            return false;
        }
    }

    /** The name with each letter in the other case. */
    private static String flipped(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            out.append(Character.isUpperCase(c) ? Character.toLowerCase(c)
                    : Character.toUpperCase(c));
        }
        return out.toString();
    }
}
