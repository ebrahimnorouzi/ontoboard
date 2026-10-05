package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The scripts a project keeps in {@code src/scripts}, and how each one would be run.
 *
 * <p>Asked for directly: "it should be possible that users have scripts and it should be run
 * everything inside the protege". Nothing in the plugin read, listed or ran them.
 *
 * <p><b>They are not peripheral.</b> A real ODK Makefile sets
 * {@code SHELL = $(SCRIPTSDIR)/run-command.sh}, so every recipe line of a 767-line build already
 * executes through a script in this directory. Running one by hand is the same kind of act as
 * running a target.
 *
 * <p><b>And on a real project they are container-only.</b> Measured on the one this was built
 * against: {@code run-command.sh} invokes {@code /usr/bin/time}, {@code update_repo.sh} invokes
 * {@code /tools/odk.py}, and {@code validate_id_ranges.sc} is an Ammonite script needing
 * {@code amm}. All three of those exist only inside {@code obolibrary/odkfull}. So "run it on
 * this machine" is the answer that would fail, and the container route is the real one - which
 * is why this class reports a route rather than assuming a shell.
 */
public final class ProjectScripts {

    /** Where ODK puts them, unless the Makefile says otherwise. */
    static final String DEFAULT_DIRECTORY = "src/scripts";

    /** Interpreters inferred from an extension when a script carries no shebang. */
    private static final String[][] BY_EXTENSION = {
        {".sh", "sh"},
        {".bash", "bash"},
        {".py", "python3"},
        {".pl", "perl"},
        {".rb", "ruby"},
        {".sc", "amm"},
    };

    /** One script, as found on disk. */
    public static final class Script {
        private final File file;
        private final String interpreter;
        private final boolean crlf;
        private final String whyNotRunnable;

        Script(File file, String interpreter, boolean crlf, String whyNotRunnable) {
            this.file = file;
            this.interpreter = interpreter;
            this.crlf = crlf;
            this.whyNotRunnable = whyNotRunnable;
        }

        public File getFile() {
            return file;
        }

        public String getName() {
            return file.getName();
        }

        /** The program that would run it - from its shebang, else from its extension. */
        public String getInterpreter() {
            return interpreter;
        }

        /**
         * Whether the file has Windows line endings.
         *
         * <p>Worth surfacing rather than silently working around. A shell in a Linux container
         * reading a script whose first line ends {@code \r} reports "cannot execute: required
         * file not found", naming the interpreter rather than the line ending, and people lose
         * an afternoon to it. ODK's own documentation warns about the same thing, and all three
         * scripts in the project this was measured against have CRLF endings.
         */
        public boolean hasWindowsLineEndings() {
            return crlf;
        }

        /** Why OntoBoard will not offer to run it, or null. */
        public String getWhyNotRunnable() {
            return whyNotRunnable;
        }

        public boolean isRunnable() {
            return whyNotRunnable == null;
        }

        @Override
        public String toString() {
            return getName() + (interpreter == null ? "" : " (" + interpreter + ")");
        }
    }

    private ProjectScripts() {
    }

    /**
     * Every script in the project's scripts directory, in name order.
     *
     * <p>The directory is {@code src/scripts} unless the Makefile declares {@code SCRIPTSDIR},
     * which a project may move. Reading the Makefile for it rather than assuming means a
     * project that moved the directory is not told it has no scripts.
     *
     * @param ontologyDirectory the project's {@code src/ontology}
     */
    public static List<Script> in(File ontologyDirectory) {
        File directory = directoryFor(ontologyDirectory);
        List<Script> scripts = new ArrayList<Script>();
        if (directory == null || !directory.isDirectory()) {
            return scripts;
        }
        File[] found = directory.listFiles();
        if (found == null) {
            return scripts;
        }
        List<File> files = new ArrayList<File>();
        for (File candidate : found) {
            if (candidate.isFile()) {
                files.add(candidate);
            }
        }
        // Sorted, so the list does not reshuffle between openings of the same project.
        Collections.sort(files, new java.util.Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        for (File file : files) {
            scripts.add(describe(file));
        }
        return Collections.unmodifiableList(scripts);
    }

    /** The scripts directory, honouring a {@code SCRIPTSDIR} the Makefile declares. */
    static File directoryFor(File ontologyDirectory) {
        if (ontologyDirectory == null) {
            return null;
        }
        File src = ontologyDirectory.getParentFile();
        File root = src == null ? null : src.getParentFile();
        if (root == null) {
            return null;
        }
        String declared = scriptsDirIn(new File(ontologyDirectory, "Makefile"));
        if (declared != null) {
            // SCRIPTSDIR is written relative to src/ontology, which is where make runs.
            File moved = new File(ontologyDirectory, declared);
            if (moved.isDirectory()) {
                return tidied(moved);
            }
        }
        return tidied(new File(root, DEFAULT_DIRECTORY.replace('/', File.separatorChar)));
    }

    /**
     * The same directory without the {@code ..} segments in it.
     *
     * <p>A real project declares {@code SCRIPTSDIR = ../scripts}, which resolves to
     * {@code src/ontology/../scripts}. That path works, but it is shown to the user - in the
     * "looked in" message and, once it has been turned into a container path, in the command
     * they have to read and agree to before anything runs. A command with {@code ..} in the
     * middle of it is harder to check, and checking it is the whole point of showing it.
     */
    private static File tidied(File directory) {
        try {
            return directory.getCanonicalFile();
        } catch (IOException notResolvable) {
            return directory.getAbsoluteFile();
        }
    }

    /** The {@code SCRIPTSDIR = ...} a Makefile declares, or null. */
    static String scriptsDirIn(File makefile) {
        if (makefile == null || !makefile.isFile()) {
            return null;
        }
        try {
            for (String line : new String(Files.readAllBytes(makefile.toPath()),
                    StandardCharsets.UTF_8).split("\r?\n")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("SCRIPTSDIR")) {
                    continue;
                }
                int equals = trimmed.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String value = trimmed.substring(equals + 1).trim();
                if (!value.isEmpty() && !value.contains("$")) {
                    // A value built from other variables cannot be resolved without running
                    // make, and guessing at one would point the list at the wrong directory.
                    return value;
                }
            }
        } catch (IOException unreadable) {
            return null;
        }
        return null;
    }

    /** One file: its interpreter, its line endings, and whether this can offer to run it. */
    static Script describe(File file) {
        String firstLine = firstLineOf(file);
        boolean crlf = firstLine != null && firstLine.endsWith("\r");
        String shebang = interpreterFromShebang(firstLine);
        if (shebang != null) {
            return new Script(file, shebang, crlf, null);
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        for (String[] pair : BY_EXTENSION) {
            if (name.endsWith(pair[0])) {
                return new Script(file, pair[1], crlf, null);
            }
        }
        return new Script(file, null, crlf,
                "OntoBoard cannot tell what would run this: it has no #! line and no extension "
                        + "it recognises. Give it a shebang, or run it yourself.");
    }

    /**
     * The program named by a {@code #!} line, or null.
     *
     * <p>{@code #!/usr/bin/env python3} names the program in its argument rather than in the
     * path, which is the form most scripts use now - so the last word wins where the first is
     * {@code env}.
     */
    static String interpreterFromShebang(String firstLine) {
        if (firstLine == null || !firstLine.startsWith("#!")) {
            return null;
        }
        String[] words = firstLine.substring(2).trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return null;
        }
        String program = words[0];
        if (program.endsWith("env") && words.length > 1) {
            program = words[1];
        }
        int slash = Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\'));
        return slash < 0 ? program : program.substring(slash + 1);
    }

    /** The first line, with its line ending kept, or null when the file cannot be read. */
    private static String firstLineOf(File file) {
        try {
            byte[] head = new byte[512];
            int read;
            try (java.io.InputStream stream = Files.newInputStream(file.toPath())) {
                read = stream.read(head);
            }
            if (read <= 0) {
                return "";
            }
            String text = new String(head, 0, read, StandardCharsets.UTF_8);
            int newline = text.indexOf('\n');
            return newline < 0 ? text : text.substring(0, newline);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * The path a container would use for a script, given the project's own mount.
     *
     * <p>The mount is the repository root at {@code /work}, so a host path under the root
     * becomes a {@code /work/...} path. Returning null rather than guessing when the script is
     * outside the root, because a path the container cannot see would fail inside it with a
     * message about the file rather than about the mount.
     *
     * <p>Both paths are resolved before they are compared. Comparing them as written would let
     * two things through that the container cannot see: {@code <root>/../elsewhere/x.sh}, which
     * begins with the root's own text but climbs out of it, and {@code <root>-backup/x.sh},
     * which begins with the root's text and is simply a different directory. Each would compose
     * a plausible {@code /work/...} path for a file that is not under the mount, and the
     * container would then complain about the file rather than about the mount.
     */
    static String containerPathOf(File script, File ontologyDirectory) {
        if (script == null || ontologyDirectory == null) {
            return null;
        }
        File src = ontologyDirectory.getParentFile();
        File root = src == null ? null : src.getParentFile();
        if (root == null) {
            return null;
        }
        // The trailing separator matters: without it a sibling directory whose name merely
        // starts with the root's would be accepted and mapped to a path inside the mount.
        String under = resolved(root).toLowerCase(Locale.ROOT) + "/";
        String scriptPath = resolved(script);
        if (!scriptPath.toLowerCase(Locale.ROOT).startsWith(under)) {
            return null;
        }
        return "/work/" + scriptPath.substring(under.length());
    }

    /** An absolute path with {@code ..} resolved and forward slashes, never a trailing one. */
    private static String resolved(File file) {
        File absolute;
        try {
            absolute = file.getCanonicalFile();
        } catch (IOException notResolvable) {
            absolute = file.getAbsoluteFile();
        }
        String path = absolute.getPath().replace('\\', '/');
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** The interpreters this class knows, for a message that lists them. */
    static List<String> knownExtensions() {
        List<String> extensions = new ArrayList<String>();
        for (String[] pair : BY_EXTENSION) {
            extensions.add(pair[0]);
        }
        return Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(
                extensions.toArray(new String[0]))));
    }
}
