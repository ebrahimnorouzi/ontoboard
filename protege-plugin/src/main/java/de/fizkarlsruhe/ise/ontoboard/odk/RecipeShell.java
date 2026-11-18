package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A recipe line of ODK's Makefile, run without a shell.
 *
 * <p>make hands each recipe line to {@code /bin/sh}. Where ODK's container cannot run there
 * may be no sh either - Protégé on Windows finds none - so OntoBoard's engine reads the line
 * itself. It reads the part of the shell that ODK's recipes use: words and quotes, {@code &&},
 * {@code ||}, {@code ;}, a group in braces or in parentheses, {@code if ... then ... else ...
 * fi}, a pipe, a file for input or output, {@code "$(...)"} inside double quotes, and file
 * patterns ({@code *}, {@code ?}, {@code [...]}) as POSIX sh expands them. No variables, no
 * background jobs: a line with either is refused, saying which, and so is a command nobody
 * here knows. ODK's {@code clean} loop is the one line read whole.
 *
 * <p>A line is read first ({@link #parse}), asked whether every command in it can be run
 * ({@link Script#whyNot}), and only then run ({@link Script#run}). The commands themselves are
 * {@link Tool}s: the shell's own few ({@link #builtins}), and whatever the caller adds -
 * {@code robot} and the stand-ins for the tools of ODK's image.
 *
 * <p>What the shell's own commands write or remove stays inside the project: {@code ../..}
 * from the Makefile's folder is its top, and a path that climbs higher is refused.
 */
final class RecipeShell {

    private RecipeShell() {
    }

    /** A line uses something of the shell that is not read here; the message says what. */
    static final class NotRead extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotRead(String message) {
            super(message);
        }
    }

    /** A command a recipe names. */
    interface Tool {
        /** Why this call cannot be run here, or null. Asked for every call before any runs. */
        String whyNot(List<String> arguments);

        /** Runs it; the exit status, 0 for success. */
        int run(Call call) throws IOException;
    }

    /** One call of a tool: its arguments, what it reads, and where what it says goes. */
    static final class Call {
        private final List<String> arguments;
        private final File folder;
        private final String in;
        private final StringBuilder out;
        private final List<String> said;

        Call(List<String> arguments, File folder, String in, StringBuilder out,
                List<String> said) {
            this.arguments = Collections.unmodifiableList(arguments);
            this.folder = folder;
            this.in = in;
            this.out = out;
            this.said = said;
        }

        List<String> arguments() {
            return arguments;
        }

        /** The folder make runs in: a path that does not start at a root is read from here. */
        File folder() {
            return folder;
        }

        File file(String path) {
            File file = new File(path);
            return file.isAbsolute() || path.startsWith("/") ? file : new File(folder, path);
        }

        /** What the command before it in a pipe wrote, or a file given with {@code <}. */
        String in() {
            return in;
        }

        /** Standard output, as it is: the next command of a pipe reads it. */
        void write(String text) {
            out.append(text);
        }

        /** One line of standard output. */
        void say(String line) {
            out.append(line).append('\n');
        }

        /** A line for the person, past any pipe or file: what a command says on its error stream. */
        void complain(String line) {
            said.add(line);
        }
    }

    // ------------------------------------------------------------------ what a line is

    private abstract static class Node {
        abstract void commands(List<Simple> into);

        abstract int run(Running running, String in, StringBuilder out) throws IOException;
    }

    /** A word: text, and what a {@code "$(...)"} in it will have written. */
    private static final class Word {
        private final List<Object> parts = new ArrayList<Object>();
        private boolean quoted;
        /** Which characters of the text no quote protects: only those make a file pattern. */
        private final BitSet bare = new BitSet();
        private int length;
        /** Whether a {@code *}, {@code ?} or {@code [} stands in it unquoted. */
        private boolean globby;
        /** The word as a file pattern; null when it is not one. */
        private Glob glob;

        void add(String text, boolean unquoted) {
            if (unquoted) {
                bare.set(length, length + text.length());
            }
            length += text.length();
            parts.add(text);
        }

        /** A {@code "$(...)"}: the word is computed, and is never a file pattern. */
        void add(Node node) {
            parts.add(node);
        }

        /** The word when it is plain text; null when part of it is computed. */
        String text() {
            StringBuilder text = new StringBuilder();
            for (Object part : parts) {
                if (!(part instanceof String)) {
                    return null;
                }
                text.append(part);
            }
            return text.toString();
        }

        boolean is(String reserved) {
            return !quoted && reserved.equals(text());
        }

        String shown() {
            String text = text();
            return text != null ? text : "$(...)";
        }
    }

    /**
     * A word read as a file pattern: its parts between slashes, each a name as it is written
     * (a String) or a pattern (a Pattern). What sh makes of it is the files it matches.
     */
    private static final class Glob {
        private final List<Object> steps;

        Glob(List<Object> steps) {
            this.steps = steps;
        }
    }

    private static final class Simple extends Node {
        private final List<Word> words = new ArrayList<Word>();
        private Word to;
        /** With {@code >>}: what is written is added to the file's end. */
        private boolean adding;
        private Word from;

        @Override
        void commands(List<Simple> into) {
            into.add(this);
            for (Word word : words) {
                for (Object part : word.parts) {
                    if (part instanceof Node) {
                        ((Node) part).commands(into);
                    }
                }
            }
        }

        @Override
        int run(Running running, String in, StringBuilder out) throws IOException {
            List<String> arguments = new ArrayList<String>();
            boolean matched = false;
            boolean computed = false;
            for (Word word : words) {
                if (word.glob == null) {
                    // A word whose value is computed - "$(cat x)", "$x" - was looked at before
                    // the run as its text only, which is no file: its tool is asked again below,
                    // with the value (the 1.154.0 review's C1 - an empty one is the make folder
                    // itself, one that climbs out is outside the project).
                    computed |= word.text() == null;
                    arguments.add(running.value(word));
                    continue;
                }
                // As sh: the files the pattern matches, or the word itself when none does.
                List<String> files = running.matches(word.glob);
                matched |= !files.isEmpty();
                arguments.addAll(files.isEmpty() ? Collections.singletonList(word.text())
                        : files);
            }
            String name = arguments.remove(0);
            if (name.equals("exit")) {
                throw new Exit(arguments.isEmpty() ? 0 : Integer.parseInt(arguments.get(0)));
            }
            String input = in;
            if (from != null) {
                File file = running.file(running.value(from));
                if (!file.isFile()) {
                    running.said.add(running.value(from) + ": No such file or directory");
                    return 1;
                }
                input = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
            StringBuilder written = to == null ? out : new StringBuilder();
            Tool tool = running.tools.get(name);
            if (tool == null) {
                running.said.add(name + ": command not found");
                return 127;
            }
            if (matched || computed) {
                // The look before the run saw the pattern, or the words' text and not their
                // values: the tool is asked about the files it gets.
                String why = tool.whyNot(arguments);
                if (why != null) {
                    throw new Refused(why);
                }
            }
            int status = tool.run(new Call(arguments, running.folder, input, written,
                    running.said));
            running.printed(written);
            if (to != null) {
                File file = running.file(running.value(to));
                if (file.getParentFile() != null) {
                    Files.createDirectories(file.getParentFile().toPath());
                }
                byte[] bytes = written.toString().getBytes(StandardCharsets.UTF_8);
                if (adding) {
                    Files.write(file.toPath(), bytes, java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                } else {
                    Files.write(file.toPath(), bytes);
                }
            }
            return status;
        }
    }

    private static final class Pipe extends Node {
        private final List<Node> commands = new ArrayList<Node>();

        @Override
        void commands(List<Simple> into) {
            for (Node command : commands) {
                command.commands(into);
            }
        }

        @Override
        int run(Running running, String in, StringBuilder out) throws IOException {
            String flowing = in;
            int status = 0;
            for (int i = 0; i < commands.size(); i++) {
                boolean last = i == commands.size() - 1;
                StringBuilder next = last ? out : new StringBuilder();
                status = commands.get(i).run(running, flowing, next);
                flowing = last ? "" : next.toString();
            }
            return status;
        }
    }

    private static final class AndOr extends Node {
        private final Node left;
        private final boolean and;
        private final Node right;

        AndOr(Node left, boolean and, Node right) {
            this.left = left;
            this.and = and;
            this.right = right;
        }

        @Override
        void commands(List<Simple> into) {
            left.commands(into);
            right.commands(into);
        }

        @Override
        int run(Running running, String in, StringBuilder out) throws IOException {
            int status = left.run(running, in, out);
            return (status == 0) == and ? right.run(running, in, out) : status;
        }
    }

    private static final class Sequence extends Node {
        private final List<Node> parts = new ArrayList<Node>();

        @Override
        void commands(List<Simple> into) {
            for (Node part : parts) {
                part.commands(into);
            }
        }

        @Override
        int run(Running running, String in, StringBuilder out) throws IOException {
            int status = 0;
            for (Node part : parts) {
                status = part.run(running, in, out);
            }
            return status;
        }
    }

    private static final class If extends Node {
        private Node condition;
        private Node then;
        private Node otherwise;

        @Override
        void commands(List<Simple> into) {
            condition.commands(into);
            then.commands(into);
            if (otherwise != null) {
                otherwise.commands(into);
            }
        }

        @Override
        int run(Running running, String in, StringBuilder out) throws IOException {
            if (condition.run(running, in, out) == 0) {
                return then.run(running, in, out);
            }
            return otherwise == null ? 0 : otherwise.run(running, in, out);
        }
    }

    /** {@code ( list )}: the list in a shell of its own - an exit inside ends the group only. */
    private static final class Group extends Node {
        private final Node body;

        Group(Node body) {
            this.body = body;
        }

        @Override
        void commands(List<Simple> into) {
            body.commands(into);
        }

        @Override
        int run(Running running, String in, StringBuilder out) throws IOException {
            try {
                return body.run(running, in, out);
            } catch (Exit exit) {
                return exit.status;
            }
        }
    }

    /** ODK's {@code clean} loop: what is inside each of the folders named, when they are here. */
    private static final class Clean extends Node {
        private final List<String> folders;

        Clean(List<String> folders) {
            this.folders = folders;
        }

        @Override
        void commands(List<Simple> into) {
            // Nothing to ask a tool: the line is read whole, and is its own command.
        }

        @Override
        int run(Running running, String in, StringBuilder out) throws IOException {
            for (String name : folders) {
                // "case $reldir in .*|"") ;;": a folder that is this one, or outside it, is
                // left alone.
                if (climbs(name) > 0 || normal(name).isEmpty() || name.startsWith("/")) {
                    continue;
                }
                // "realpath": where the folder really is. One that is a link - a junction
                // on Windows - to somewhere outside is outside, and ODK's loop leaves it
                // alone; so does this one (review of 1.153.0: it was emptied).
                File really = reallyInside(running.folder, new File(running.folder, name));
                if (really == null) {
                    continue;
                }
                File[] inside = really.listFiles();
                for (File child : inside == null ? new File[0] : inside) {
                    // "$reldir/*": the shell's star leaves out a name that starts with a
                    // dot, and ODK keeps tmp/.gitkeep in git.
                    if (!child.getName().startsWith(".")) {
                        delete(child);
                    }
                }
            }
            return 0;
        }
    }

    /** {@code exit N}: the line ends there, with that status. */
    private static final class Exit extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        Exit(int status) {
            super(null, null, false, false);
            this.status = status;
        }
    }

    /**
     * A command to which a file pattern gave files its tool does not take. The line stops
     * there, past any {@code ||}: sh would have done what is not done here.
     */
    private static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String why) {
            super(why, null, false, false);
        }
    }

    /** A run of one line: the tools, the folder, and what is said. */
    private static final class Running {
        private final Map<String, Tool> tools;
        private final File folder;
        private final List<String> said;
        /** The line's own standard output: what no pipe and no file takes. */
        private final StringBuilder top = new StringBuilder();

        Running(Map<String, Tool> tools, File folder, List<String> said) {
            this.tools = tools;
            this.folder = folder;
            this.said = said;
        }

        /**
         * After a command: when it wrote to the line's own output, its whole lines are said
         * now, so that they stand where they were printed among what commands complain of.
         */
        void printed(StringBuilder written) {
            if (written != top) {
                return;
            }
            int end = top.lastIndexOf("\n");
            if (end >= 0) {
                said.addAll(Arrays.asList(top.substring(0, end).split("\n", -1)));
                top.delete(0, end + 1);
            }
        }

        File file(String path) {
            File file = new File(path);
            return file.isAbsolute() || path.startsWith("/") ? file : new File(folder, path);
        }

        /**
         * The files a pattern matches, read from the make folder as sh reads them: each
         * pattern between slashes against the names in its folder - a name that starts with a
         * dot only when the pattern does too - sorted by their bytes, as in ODK's C locale.
         * Empty when none matches.
         */
        List<String> matches(Glob glob) {
            List<String> found = Collections.singletonList("");
            for (Object step : glob.steps) {
                List<String> next = new ArrayList<String>();
                for (String prefix : found) {
                    if (step instanceof String) {
                        next.add(joined(prefix, (String) step));
                        continue;
                    }
                    String[] names = (prefix.isEmpty() ? folder : file(prefix)).list();
                    for (String name : names == null ? new String[0] : names) {
                        if (((Pattern) step).matcher(name).matches()) {
                            next.add(joined(prefix, name));
                        }
                    }
                }
                found = next;
            }
            List<String> matches = new ArrayList<String>();
            for (String path : found) {
                if (there(path)) {
                    matches.add(path);
                }
            }
            Collections.sort(matches, BYTES);
            return matches;
        }

        /** Whether a path names something, as lstat sees it; one that ends in / a folder. */
        private boolean there(String path) {
            File file = file(path);
            if (path.endsWith("/")) {
                return file.isDirectory();
            }
            try {
                return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
            } catch (java.nio.file.InvalidPathException notAName) {
                return false;
            }
        }

        /** A word's text, with what each {@code $(...)} in it writes, less its last line breaks. */
        String value(Word word) throws IOException {
            StringBuilder value = new StringBuilder();
            for (Object part : word.parts) {
                if (part instanceof String) {
                    value.append(part);
                } else {
                    StringBuilder written = new StringBuilder();
                    ((Node) part).run(this, "", written);
                    int end = written.length();
                    while (end > 0 && written.charAt(end - 1) == '\n') {
                        end--;
                    }
                    value.append(written, 0, end);
                }
            }
            return value.toString();
        }
    }

    private static String joined(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    /** Text in the order of its bytes in UTF-8: how sort and sh order names in the C locale. */
    private static final Comparator<String> BYTES = new Comparator<String>() {
        @Override
        public int compare(String one, String other) {
            byte[] a = one.getBytes(StandardCharsets.UTF_8);
            byte[] b = other.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < Math.min(a.length, b.length); i++) {
                if (a[i] != b[i]) {
                    return (a[i] & 0xff) - (b[i] & 0xff);
                }
            }
            return a.length - b.length;
        }
    };

    // ------------------------------------------------------------------ a line, read

    /** A recipe line as read. */
    static final class Script {
        private final Node root;
        private final String line;

        private Script(Node root, String line) {
            this.root = root;
            this.line = line;
        }

        /**
         * Why the line cannot be run with these tools, or null: a command none of them is, or
         * a call its tool refuses. Nothing is run to find out.
         */
        String whyNot(Map<String, Tool> tools) {
            List<Simple> commands = new ArrayList<Simple>();
            root.commands(commands);
            for (Simple command : commands) {
                String name = command.words.get(0).text();
                if (name == null) {
                    return "a command whose name is computed is not run here: " + line;
                }
                if (command.words.get(0).glob != null) {
                    return "a command named by a file pattern is not run here: " + line;
                }
                // A file pattern is asked as it is written. What it matches lies where its text
                // does - each pattern between slashes is one name, never . or .. - so one that
                // reaches outside the project is refused here; its files are asked again when
                // the line runs.
                List<String> arguments = new ArrayList<String>();
                for (Word word : command.words.subList(1, command.words.size())) {
                    arguments.add(word.shown());
                }
                for (Word file : new Word[] {command.to, command.from}) {
                    if (file != null && file.text() == null) {
                        return "a file whose name is computed is not read or written here: "
                                + line;
                    }
                }
                if (command.to != null) {
                    String outside = whyNotWritten(command.to.text());
                    if (outside != null) {
                        return outside;
                    }
                }
                if (name.equals("exit")) {
                    if (arguments.size() > 1 || (arguments.size() == 1
                            && !arguments.get(0).matches("[0-9]{1,3}"))) {
                        return "'exit " + String.join(" ", arguments) + "' is not an exit "
                                + "with a status";
                    }
                    continue;
                }
                Tool tool = tools.get(name);
                if (tool == null) {
                    return "'" + name + "' is a program OntoBoard's own engine has no stand-in "
                            + "for (" + oneLine(line) + ")";
                }
                String why = tool.whyNot(arguments);
                if (why != null) {
                    return why;
                }
            }
            return null;
        }

        /**
         * Runs the line in a folder.
         *
         * @param said what the line prints, line by line, as it goes
         * @return the exit status: 0 for success
         */
        int run(Map<String, Tool> tools, File folder, List<String> said) {
            Running running = new Running(tools, folder, said);
            int status;
            String refused = null;
            try {
                status = root.run(running, "", running.top);
            } catch (Exit exit) {
                status = exit.status;
            } catch (Refused refusal) {
                refused = refusal.getMessage();
                status = 1;
            } catch (IOException failed) {
                said.add(String.valueOf(failed.getMessage()));
                status = 1;
            }
            running.printed(running.top);
            if (running.top.length() > 0) {
                // A last line without its line break.
                said.add(running.top.toString());
            }
            if (refused != null) {
                // The line's last word: why it stopped.
                said.add(refused);
            }
            return status;
        }
    }

    private static String oneLine(String line) {
        String flat = line.replace("\\\n", " ").replaceAll("\\s+", " ").trim();
        return flat.length() > 160 ? flat.substring(0, 157) + "..." : flat;
    }

    // ------------------------------------------------------------------ reading

    private static final Pattern CLEAN = Pattern.compile("for dir in ((?:[A-Za-z0-9_./-]+ )+); "
            + "do reldir=\\$\\(realpath --relative-to=\\$\\(pwd\\) \\$dir\\) ; case \\$reldir in "
            + "\\.\\*\\|\"\"\\) ;; \\*\\) rm -rf \\$reldir/\\* ;; esac done");

    /**
     * Reads a recipe line as make hands it to the shell: with a continued line's backslash
     * and line break still in it.
     *
     * @throws NotRead when it uses something of the shell that is not read here
     */
    static Script parse(String command) {
        Matcher clean = CLEAN.matcher(command.replace("\\\n", " ").replaceAll("\\s+", " ")
                .trim());
        if (clean.matches()) {
            return new Script(new Clean(Arrays.asList(clean.group(1).trim().split(" "))),
                    command);
        }
        Reader reader = new Reader(command, true);
        Node root = reader.list();
        if (reader.at < reader.tokens.size()) {
            throw reader.notRead("'" + reader.tokens.get(reader.at).shown() + "' where a "
                    + "command should end");
        }
        return new Script(root, command);
    }

    /** One piece of a line: a word, or an operator. */
    private static final class Token {
        private final Word word;
        private final String operator;

        Token(Word word, String operator) {
            this.word = word;
            this.operator = operator;
        }

        String shown() {
            return operator != null ? operator : word.shown();
        }
    }

    private static final class Reader {
        private static final List<String> NOT_READ_WORDS = Arrays.asList("for", "while",
                "until", "case", "do", "done", "esac", "function", "select", "!", "[[");

        private final String line;
        /** Whether a word may be a file pattern: not inside {@code "$(...)"}. */
        private final boolean patterns;
        private final List<Token> tokens = new ArrayList<Token>();
        private int at;

        Reader(String line, boolean patterns) {
            this.line = line;
            this.patterns = patterns;
            tokenize(line);
        }

        NotRead notRead(String what) {
            return new NotRead("OntoBoard's own engine does not read " + what + " in a recipe "
                    + "line (" + oneLine(line) + ")");
        }

        // -------------------------------------------------------------- words

        private void tokenize(String text) {
            int length = text.length();
            Word word = null;
            int i = 0;
            while (i < length) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i += 2;
                    continue;
                }
                if (c == ' ' || c == '\t') {
                    word = ended(word);
                    i++;
                    continue;
                }
                if (c == '\n') {
                    throw notRead("a second line");
                }
                String operator = operatorAt(text, i);
                if (operator != null) {
                    if ((c == '>' || c == '<') && word != null && !word.quoted
                            && word.text() != null && word.text().matches("[0-9]+")) {
                        throw notRead("'" + word.text() + c + "' (a numbered stream)");
                    }
                    word = ended(word);
                    tokens.add(new Token(null, operator));
                    i += operator.length();
                    continue;
                }
                if (word == null) {
                    word = new Word();
                    if (c == '#') {
                        throw notRead("a comment (#)");
                    }
                    if (c == '~') {
                        throw notRead("a home folder (~)");
                    }
                }
                if (c == '\'') {
                    int close = text.indexOf('\'', i + 1);
                    if (close < 0) {
                        throw notRead("a quote that is not closed");
                    }
                    word.add(text.substring(i + 1, close), false);
                    word.quoted = true;
                    i = close + 1;
                } else if (c == '"') {
                    i = doubleQuoted(text, i + 1, word);
                } else if (c == '\\') {
                    if (i + 1 >= length) {
                        throw notRead("a backslash at the end");
                    }
                    word.add(String.valueOf(text.charAt(i + 1)), false);
                    word.quoted = true;
                    i += 2;
                } else if (c == '$') {
                    throw notRead("'" + dollarAt(text, i) + "' (a variable, or a command's "
                            + "output outside double quotes)");
                } else if (c == '`') {
                    throw notRead("a command in backquotes (`)");
                } else if (c == '*' || c == '?' || (c == '[' && !alone(text, i, word))) {
                    // On its own, [ is the test. Anywhere else these make a file pattern.
                    if (!patterns) {
                        throw notRead("a file pattern ('" + wordAround(text, i) + "') inside "
                                + "$(...)");
                    }
                    word.add(String.valueOf(c), true);
                    word.globby = true;
                    i++;
                } else if ((c == '{' || c == '}') && !alone(text, i, word)) {
                    // On their own, { } hold a group. Inside a word they would be bash's list
                    // of alternatives, which sh does not have.
                    throw notRead("'" + wordAround(text, i) + "' (a list in braces)");
                } else {
                    word.add(String.valueOf(c), true);
                    i++;
                }
            }
            ended(word);
        }

        private Word ended(Word word) {
            if (word != null) {
                merge(word);
                if (word.globby) {
                    if (word.text() == null) {
                        throw notRead("a file pattern in a word with a $(...) in it");
                    }
                    word.glob = glob(word);
                }
                tokens.add(new Token(word, null));
            }
            return null;
        }

        /** Whether the character at i is a word by itself: nothing before it, an end after. */
        private static boolean alone(String text, int i, Word word) {
            if (!word.parts.isEmpty()) {
                return false;
            }
            if (i + 1 >= text.length()) {
                return true;
            }
            char next = text.charAt(i + 1);
            return next == ' ' || next == '\t' || next == ';' || next == '\\';
        }

        /** Joins neighbouring pieces of text, so that a word of plain text is one piece. */
        private static void merge(Word word) {
            List<Object> merged = new ArrayList<Object>();
            StringBuilder text = null;
            for (Object part : word.parts) {
                if (part instanceof String) {
                    if (text == null) {
                        text = new StringBuilder();
                    }
                    text.append(part);
                } else {
                    if (text != null) {
                        merged.add(text.toString());
                        text = null;
                    }
                    merged.add(part);
                }
            }
            if (text != null || merged.isEmpty()) {
                merged.add(text == null ? "" : text.toString());
            }
            word.parts.clear();
            word.parts.addAll(merged);
        }

        private String operatorAt(String text, int i) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (c == '&') {
                if (next == '&') {
                    return "&&";
                }
                throw notRead("'&' (a command left running)");
            }
            if (c == '|') {
                return next == '|' ? "||" : "|";
            }
            if (c == ';') {
                if (next == ';') {
                    throw notRead("';;'");
                }
                return ";";
            }
            if (c == '>') {
                if (next == '&') {
                    throw notRead("'>&'");
                }
                return next == '>' ? ">>" : ">";
            }
            if (c == '<') {
                if (next == '<' || next == '&') {
                    throw notRead("'<" + next + "'");
                }
                return "<";
            }
            if (c == '(' || c == ')') {
                return String.valueOf(c);
            }
            return null;
        }

        /** From after the opening quote to after the closing one. */
        private int doubleQuoted(String text, int from, Word word) {
            int length = text.length();
            StringBuilder literal = new StringBuilder();
            int i = from;
            while (true) {
                if (i >= length) {
                    throw notRead("a quote that is not closed");
                }
                char c = text.charAt(i);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && i + 1 < length) {
                    char next = text.charAt(i + 1);
                    if (next == '\n') {
                        i += 2;
                        continue;
                    }
                    if (next == '$' || next == '`' || next == '"' || next == '\\') {
                        literal.append(next);
                        i += 2;
                        continue;
                    }
                    literal.append(c);
                    i++;
                    continue;
                }
                if (c == '`') {
                    throw notRead("a command in backquotes (`)");
                }
                if (c == '$') {
                    if (i + 1 < length && text.charAt(i + 1) == '(') {
                        int close = closing(text, i + 2);
                        if (literal.length() > 0) {
                            word.add(literal.toString(), false);
                            literal = new StringBuilder();
                        }
                        Reader inner = new Reader(text.substring(i + 2, close), false);
                        Node node = inner.list();
                        if (inner.at < inner.tokens.size()) {
                            throw notRead("'" + inner.tokens.get(inner.at).shown() + "' inside "
                                    + "$(...)");
                        }
                        word.add(node);
                        i = close + 1;
                        continue;
                    }
                    throw notRead("'" + dollarAt(text, i) + "' (a shell variable)");
                }
                literal.append(c);
                i++;
            }
            word.add(literal.toString(), false);
            word.quoted = true;
            return i + 1;
        }

        /** The index of the parenthesis that closes a {@code $(} whose text starts at from. */
        private int closing(String text, int from) {
            int depth = 1;
            for (int i = from; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\'') {
                    int close = text.indexOf('\'', i + 1);
                    if (close < 0) {
                        break;
                    }
                    i = close;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    return i;
                }
            }
            throw notRead("a $( that is not closed");
        }

        private static String dollarAt(String text, int i) {
            Matcher name = Pattern.compile("\\$(\\{[^}]*\\}|\\([^)]*\\)|[A-Za-z_][A-Za-z0-9_]*"
                    + "|.)?").matcher(text);
            return name.find(i) && name.start() == i ? name.group() : "$";
        }

        private static String wordAround(String text, int i) {
            int start = i;
            while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
                start--;
            }
            int end = i;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            return text.substring(start, end);
        }

        // -------------------------------------------------------------- file patterns

        /** The word as a file pattern, or null when nothing in it makes one after all. */
        private Glob glob(Word word) {
            String text = word.text();
            if (text.startsWith("/")) {
                throw notRead("a file pattern that starts at the top of the file system ('"
                        + text + "')");
            }
            List<Object> steps = new ArrayList<Object>();
            boolean any = false;
            int start = 0;
            while (true) {
                int slash = text.indexOf('/', start);
                int end = slash < 0 ? text.length() : slash;
                Object step = step(text, word.bare, start, end);
                any |= step instanceof Pattern;
                steps.add(step);
                if (slash < 0) {
                    break;
                }
                start = slash + 1;
            }
            return any ? new Glob(steps) : null;
        }

        /**
         * One part of a word between slashes: its text when nothing in it is a pattern, or
         * the pattern. A name that starts with a dot is matched only by a part that does too.
         */
        private Object step(String text, BitSet bare, int start, int end) {
            StringBuilder regex = new StringBuilder();
            StringBuilder literal = new StringBuilder();
            boolean pattern = false;
            int i = start;
            while (i < end) {
                char c = text.charAt(i);
                String piece = null;
                int next = i + 1;
                if (bare.get(i) && c == '*') {
                    piece = ".*";
                } else if (bare.get(i) && c == '?') {
                    piece = ".";
                } else if (bare.get(i) && c == '[') {
                    int close = bracketEnd(text, bare, i, end);
                    if (close >= 0) {
                        piece = bracket(text, bare, i, close);
                        next = close + 1;
                    }
                }
                if (piece == null) {
                    literal.append(c);
                } else {
                    if (literal.length() > 0) {
                        regex.append(Pattern.quote(literal.toString()));
                        literal.setLength(0);
                    }
                    regex.append(piece);
                    pattern = true;
                }
                i = next;
            }
            if (!pattern) {
                return text.substring(start, end);
            }
            if (literal.length() > 0) {
                regex.append(Pattern.quote(literal.toString()));
            }
            String dot = text.charAt(start) == '.' ? "" : "(?!\\.)";
            try {
                return Pattern.compile("(?s)" + dot + regex);
            } catch (java.util.regex.PatternSyntaxException unread) {
                throw notRead("the file pattern '" + text + "'");
            }
        }

        /** Where the bracket opened at open closes, or -1: a [ without its ] is itself. */
        private static int bracketEnd(String text, BitSet bare, int open, int end) {
            int i = open + 1;
            if (i < end && bare.get(i) && (text.charAt(i) == '!' || text.charAt(i) == '^')) {
                i++;
            }
            if (i < end && text.charAt(i) == ']') {
                // A ] first is one of the characters, not the end.
                i++;
            }
            while (i < end && !(text.charAt(i) == ']' && bare.get(i))) {
                i++;
            }
            return i < end ? i : -1;
        }

        /** A bracket expression as a class of Java's patterns: its characters and ranges. */
        private String bracket(String text, BitSet bare, int open, int close) {
            int i = open + 1;
            StringBuilder set = new StringBuilder("[");
            if (bare.get(i) && (text.charAt(i) == '!' || text.charAt(i) == '^')) {
                set.append('^');
                i++;
            }
            int first = i;
            while (i < close) {
                int c = text.codePointAt(i);
                if (c == '[' && i + 1 < close && ":=.".indexOf(text.charAt(i + 1)) >= 0) {
                    throw notRead("a class of characters in the file pattern '" + text + "'");
                }
                if (c == '-' && bare.get(i) && i > first && i + 1 < close) {
                    set.append('-');
                } else {
                    set.append("\\x{").append(Integer.toHexString(c)).append('}');
                }
                i += Character.charCount(c);
            }
            return set.append(']').toString();
        }

        // -------------------------------------------------------------- commands

        private boolean operator(String operator) {
            return at < tokens.size() && operator.equals(tokens.get(at).operator);
        }

        private boolean reserved(String word) {
            return at < tokens.size() && tokens.get(at).word != null
                    && tokens.get(at).word.is(word);
        }

        private boolean ends() {
            return at >= tokens.size() || operator(")") || reserved("then") || reserved("else")
                    || reserved("elif") || reserved("fi") || reserved("}");
        }

        /** Commands one after the other, to the end or to a word that closes something. */
        Node list() {
            Sequence sequence = new Sequence();
            while (true) {
                if (ends()) {
                    break;
                }
                sequence.parts.add(andOr());
                if (operator(";")) {
                    at++;
                    continue;
                }
                break;
            }
            if (sequence.parts.isEmpty()) {
                throw notRead(at >= tokens.size() ? "an empty command"
                        : "'" + tokens.get(at).shown() + "' where a command should start");
            }
            return sequence.parts.size() == 1 ? sequence.parts.get(0) : sequence;
        }

        private Node andOr() {
            Node left = pipe();
            while (operator("&&") || operator("||")) {
                boolean and = operator("&&");
                at++;
                left = new AndOr(left, and, pipe());
            }
            return left;
        }

        private Node pipe() {
            Pipe pipe = new Pipe();
            pipe.commands.add(command());
            while (operator("|")) {
                at++;
                pipe.commands.add(command());
            }
            return pipe.commands.size() == 1 ? pipe.commands.get(0) : pipe;
        }

        private Node command() {
            if (reserved("{")) {
                at++;
                Node body = list();
                expect("}");
                return body;
            }
            if (operator("(")) {
                at++;
                Node body = list();
                if (!operator(")")) {
                    throw notRead("a '(' that is not closed");
                }
                at++;
                return new Group(body);
            }
            if (reserved("if")) {
                at++;
                If first = new If();
                If branch = first;
                while (true) {
                    branch.condition = list();
                    expect("then");
                    branch.then = list();
                    if (!reserved("elif")) {
                        break;
                    }
                    // "elif c; then a" is "else if c; then a" that needs no fi of its own.
                    at++;
                    If next = new If();
                    branch.otherwise = next;
                    branch = next;
                }
                if (reserved("else")) {
                    at++;
                    branch.otherwise = list();
                }
                expect("fi");
                return first;
            }
            Simple simple = new Simple();
            while (at < tokens.size()) {
                Token token = tokens.get(at);
                if (token.operator != null) {
                    if (!token.operator.equals(">") && !token.operator.equals(">>")
                            && !token.operator.equals("<")) {
                        break;
                    }
                    if (at + 1 >= tokens.size() || tokens.get(at + 1).word == null
                            || simple.words.isEmpty()) {
                        throw notRead("'" + token.operator + "' without a command before it "
                                + "and a file after it");
                    }
                    if (tokens.get(at + 1).word.glob != null) {
                        // sh does not expand a pattern there: the file is named as written.
                        throw notRead("a file pattern ('" + tokens.get(at + 1).word.text()
                                + "') as the file of '" + token.operator + "'");
                    }
                    if (token.operator.startsWith(">")) {
                        simple.to = tokens.get(at + 1).word;
                        simple.adding = token.operator.equals(">>");
                    } else {
                        simple.from = tokens.get(at + 1).word;
                    }
                    at += 2;
                    continue;
                }
                if (!simple.words.isEmpty() && (token.word.is("}") || token.word.is("then")
                        || token.word.is("else") || token.word.is("elif")
                        || token.word.is("fi"))) {
                    // "{ a; }" and "if a; then": these close after a ';', which ended the
                    // command already. Standing right after its words they are words too.
                    simple.words.add(token.word);
                    at++;
                    continue;
                }
                if (simple.words.isEmpty()) {
                    String name = token.word.quoted ? null : token.word.text();
                    if (name != null && NOT_READ_WORDS.contains(name)) {
                        throw notRead("'" + name + "'");
                    }
                    if (name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
                        throw notRead("'" + name + "' (a variable set for a command)");
                    }
                }
                simple.words.add(token.word);
                at++;
            }
            if (simple.words.isEmpty()) {
                throw notRead(at >= tokens.size() ? "an empty command"
                        : "'" + tokens.get(at).shown() + "' where a command should start");
            }
            return simple;
        }

        private void expect(String word) {
            if (!reserved(word)) {
                throw notRead("a line where '" + word + "' is missing"
                        + (at < tokens.size() ? " before '" + tokens.get(at).shown() + "'" : ""));
            }
            at++;
        }
    }

    // ------------------------------------------------------------------ inside the project

    /** How many folders above the Makefile's folder a path ends, read as text; 0 for inside. */
    private static int climbs(String path) {
        int depth = 0;
        int lowest = 0;
        // A backslash too: on Windows it parts folders as a slash does, and a quoted path
        // written with it was one name here, which climbs nowhere (review of 1.153.0).
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            depth += part.equals("..") ? -1 : 1;
            lowest = Math.min(lowest, depth);
        }
        return -lowest;
    }

    /** A path with its {@code .} and {@code x/..} taken out; leading {@code ..} kept. */
    private static String normal(String path) {
        List<String> parts = new ArrayList<String>();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..") && !parts.isEmpty()
                    && !parts.get(parts.size() - 1).equals("..")) {
                parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }

    /**
     * Why a path is not written to, or null. The project's top is two folders above the
     * Makefile's ({@code ../..}); nothing outside it is touched.
     */
    static String whyNotWritten(String path) {
        if (path.startsWith("/") || path.startsWith("\\") || new File(path).isAbsolute()
                || climbs(path) > 2) {
            return "'" + path + "' is outside the project, and a recipe run by OntoBoard's own "
                    + "engine writes and removes inside the project only";
        }
        return null;
    }

    /** Why a path is not removed: outside the project, or a folder the project is in. */
    static String whyNotRemoved(String path) {
        String outside = whyNotWritten(path);
        if (outside != null) {
            return outside;
        }
        String normal = normal(path);
        if (normal.isEmpty() || normal.matches("\\.\\.(/\\.\\.)*")) {
            return "'" + path + "' is the project's own folder, or one it is in; a recipe run "
                    + "by OntoBoard's own engine does not remove that";
        }
        return null;
    }

    /**
     * Where a folder of the project really is, as {@code realpath --relative-to} and ODK's
     * {@code case} after it read it: null when it is not there, when it is the project's
     * folder itself, when it lies outside it, or when its first step from there starts with
     * a dot.
     */
    static File reallyInside(File project, File folder) {
        try {
            Path home = project.toPath().toRealPath();
            Path real = folder.toPath().toRealPath();
            if (real.equals(home) || !real.startsWith(home)) {
                return null;
            }
            return home.relativize(real).getName(0).toString().startsWith(".") ? null
                    : real.toFile();
        } catch (IOException | RuntimeException notThere) {
            return null;
        }
    }

    /**
     * Removes a file, or a folder with what is in it. A link - a junction on Windows - is
     * removed as the link it is: what it leads to is somebody's, and is not gone into.
     */
    private static void delete(File file) throws IOException {
        BasicFileAttributes is;
        try {
            is = Files.readAttributes(file.toPath(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException gone) {
            return;
        }
        // A junction says it is a directory and "other" at once.
        if (is.isDirectory() && !is.isOther() && !is.isSymbolicLink()) {
            File[] children = file.listFiles();
            for (File child : children == null ? new File[0] : children) {
                delete(child);
            }
        }
        Files.deleteIfExists(file.toPath());
    }

    // ------------------------------------------------------------------ the shell's own commands

    /** The commands of the shell and of coreutils that ODK's recipes use, each as they use it. */
    static Map<String, Tool> builtins() {
        Map<String, Tool> tools = new LinkedHashMap<String, Tool>();
        tools.put("echo", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return !arguments.isEmpty() && arguments.get(0).matches("-[neE]+")
                        ? "'echo " + arguments.get(0) + "' is not read here: only a plain echo"
                        : null;
            }

            @Override
            public int run(Call call) {
                call.say(String.join(" ", call.arguments()));
                return 0;
            }
        });
        tools.put("true", status(0));
        tools.put("false", status(1));
        tools.put("cat", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return options(arguments).isEmpty() ? null
                        : "'cat " + options(arguments).get(0) + "' is not read here";
            }

            @Override
            public int run(Call call) throws IOException {
                if (call.arguments().isEmpty()) {
                    call.write(call.in());
                    return 0;
                }
                int status = 0;
                for (String name : call.arguments()) {
                    File file = call.file(name);
                    if (!file.isFile()) {
                        call.complain("cat: " + name + ": No such file or directory");
                        status = 1;
                        continue;
                    }
                    call.write(new String(Files.readAllBytes(file.toPath()),
                            StandardCharsets.UTF_8));
                }
                return status;
            }
        });
        tools.put("sort", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return arguments.isEmpty() ? null
                        : "'sort " + String.join(" ", arguments) + "' is not read here: only a "
                                + "plain sort of what a pipe hands it";
            }

            @Override
            public int run(Call call) {
                List<String> lines = lines(call.in());
                Collections.sort(lines);
                for (String line : lines) {
                    call.say(line);
                }
                return 0;
            }
        });
        tools.put("uniq", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return arguments.isEmpty() ? null
                        : "'uniq " + String.join(" ", arguments) + "' is not read here: only a "
                                + "plain uniq of what a pipe hands it";
            }

            @Override
            public int run(Call call) {
                String last = null;
                for (String line : lines(call.in())) {
                    if (!line.equals(last)) {
                        call.say(line);
                    }
                    last = line;
                }
                return 0;
            }
        });
        tools.put("tr", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return arguments.equals(Arrays.asList("-d", "\\r")) ? null
                        : "'tr " + String.join(" ", arguments) + "' is not read here: only "
                                + "tr -d '\\r'";
            }

            @Override
            public int run(Call call) {
                call.write(call.in().replace("\r", ""));
                return 0;
            }
        });
        tools.put("sha256sum", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return arguments.isEmpty() ? null
                        : "'sha256sum " + String.join(" ", arguments) + "' is not read here: "
                                + "only the sum of what a pipe hands it";
            }

            @Override
            public int run(Call call) {
                try {
                    StringBuilder hex = new StringBuilder();
                    for (byte b : MessageDigest.getInstance("SHA-256").digest(
                            call.in().getBytes(StandardCharsets.UTF_8))) {
                        hex.append(String.format("%02x", b & 0xff));
                    }
                    call.say(hex + "  -");
                    return 0;
                } catch (NoSuchAlgorithmException impossible) {
                    throw new IllegalStateException("No SHA-256 in this Java", impossible);
                }
            }
        });
        tools.put("cut", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return arguments.size() == 1 && arguments.get(0).matches("-c[1-9][0-9]*-[0-9]+")
                        ? null : "'cut " + String.join(" ", arguments) + "' is not read here: "
                                + "only cut -c<first>-<last>";
            }

            @Override
            public int run(Call call) {
                String[] range = call.arguments().get(0).substring(2).split("-");
                int first = Integer.parseInt(range[0]);
                int last = Integer.parseInt(range[1]);
                for (String line : lines(call.in())) {
                    call.say(line.length() < first ? ""
                            : line.substring(first - 1, Math.min(last, line.length())));
                }
                return 0;
            }
        });
        tools.put("[", test(true));
        tools.put("test", test(false));
        tools.put("cmp", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return arguments.size() == 3 && arguments.get(0).equals("-s") ? null
                        : "'cmp " + String.join(" ", arguments) + "' is not read here: only "
                                + "cmp -s <file> <file>";
            }

            @Override
            public int run(Call call) throws IOException {
                File one = call.file(call.arguments().get(1));
                File other = call.file(call.arguments().get(2));
                if (!one.isFile() || !other.isFile()) {
                    return 2;
                }
                return Arrays.equals(Files.readAllBytes(one.toPath()),
                        Files.readAllBytes(other.toPath())) ? 0 : 1;
            }
        });
        tools.put("mkdir", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                if (arguments.isEmpty() || !arguments.get(0).equals("-p")
                        || options(arguments).size() != 1) {
                    return "'mkdir " + String.join(" ", arguments) + "' is not read here: only "
                            + "mkdir -p <folders>";
                }
                return firstRefusal(arguments.subList(1, arguments.size()), false);
            }

            @Override
            public int run(Call call) throws IOException {
                for (String name : call.arguments().subList(1, call.arguments().size())) {
                    Files.createDirectories(call.file(name).toPath());
                }
                return 0;
            }
        });
        tools.put("touch", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return !options(arguments).isEmpty()
                        ? "'touch " + options(arguments).get(0) + "' is not read here"
                        : firstRefusal(arguments, false);
            }

            @Override
            public int run(Call call) throws IOException {
                for (String name : call.arguments()) {
                    File file = call.file(name);
                    if (file.exists()) {
                        file.setLastModified(System.currentTimeMillis());
                    } else {
                        if (file.getParentFile() != null && !file.getParentFile().isDirectory()) {
                            call.complain("touch: cannot touch '" + name + "': No such file or "
                                    + "directory");
                            return 1;
                        }
                        Files.write(file.toPath(), new byte[0]);
                    }
                }
                return 0;
            }
        });
        tools.put("cp", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                if (arguments.size() != 2 || !options(arguments).isEmpty()) {
                    return "'cp " + String.join(" ", arguments) + "' is not read here: only "
                            + "cp <file> <file>";
                }
                return whyNotWritten(arguments.get(1));
            }

            @Override
            public int run(Call call) throws IOException {
                File from = call.file(call.arguments().get(0));
                File to = call.file(call.arguments().get(1));
                if (!from.isFile()) {
                    call.complain("cp: cannot stat '" + call.arguments().get(0) + "': No such "
                            + "file or directory");
                    return 1;
                }
                Files.copy(from.toPath(), (to.isDirectory() ? new File(to, from.getName()) : to)
                        .toPath(), StandardCopyOption.REPLACE_EXISTING);
                return 0;
            }
        });
        tools.put("ln", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                if (arguments.size() != 2 || !options(arguments).isEmpty()) {
                    return "'ln " + String.join(" ", arguments) + "' is not read here: only "
                            + "ln <file> <file>";
                }
                return whyNotWritten(arguments.get(1));
            }

            @Override
            public int run(Call call) throws IOException {
                // A second name for the same file. A copy does for what ODK uses it for - a
                // plugin put where robot looks - and needs no file system that can link.
                File from = call.file(call.arguments().get(0));
                File to = call.file(call.arguments().get(1));
                if (!from.isFile()) {
                    call.complain("ln: failed to access '" + call.arguments().get(0)
                            + "': No such file or directory");
                    return 1;
                }
                if (to.exists()) {
                    call.complain("ln: failed to create hard link '" + call.arguments().get(1)
                            + "': File exists");
                    return 1;
                }
                Files.copy(from.toPath(), to.toPath());
                return 0;
            }
        });
        tools.put("mv", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                if (arguments.size() != 2 || !options(arguments).isEmpty()) {
                    return "'mv " + String.join(" ", arguments) + "' is not read here: only "
                            + "mv <file> <file>";
                }
                String from = whyNotRemoved(arguments.get(0));
                return from != null ? from : whyNotWritten(arguments.get(1));
            }

            @Override
            public int run(Call call) throws IOException {
                File from = call.file(call.arguments().get(0));
                File to = call.file(call.arguments().get(1));
                if (!from.exists()) {
                    call.complain("mv: cannot stat '" + call.arguments().get(0) + "': No such "
                            + "file or directory");
                    return 1;
                }
                Files.move(from.toPath(), (to.isDirectory() ? new File(to, from.getName()) : to)
                        .toPath(), StandardCopyOption.REPLACE_EXISTING);
                return 0;
            }
        });
        tools.put("rm", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                for (String option : options(arguments)) {
                    if (!option.matches("-(f|r|rf|fr)")) {
                        return "'rm " + option + "' is not read here: only -f and -r";
                    }
                }
                return firstRefusal(names(arguments), true);
            }

            @Override
            public int run(Call call) throws IOException {
                String flags = String.join("", options(call.arguments()));
                boolean force = flags.contains("f");
                boolean recursive = flags.contains("r");
                int status = 0;
                for (String name : names(call.arguments())) {
                    File file = call.file(name);
                    if (!file.exists()) {
                        if (!force) {
                            call.complain("rm: cannot remove '" + name + "': No such file or "
                                    + "directory");
                            status = 1;
                        }
                    } else if (file.isDirectory() && !recursive) {
                        call.complain("rm: cannot remove '" + name + "': Is a directory");
                        status = 1;
                    } else {
                        delete(file);
                    }
                }
                return status;
            }
        });
        tools.put("rsync", new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                if (arguments.size() < 3 || !arguments.get(0).equals("-R")
                        || options(arguments).size() != 1) {
                    return "'rsync " + String.join(" ", arguments) + "' is not read here: only "
                            + "rsync -R <files> <folder>, as ODK copies a release's files";
                }
                for (String name : arguments.subList(1, arguments.size() - 1)) {
                    if (climbs(name) > 0 || name.startsWith("/")) {
                        return "'rsync -R " + name + "': a file above the Makefile's folder "
                                + "has no place under the folder it is copied to";
                    }
                }
                return whyNotWritten(arguments.get(arguments.size() - 1));
            }

            @Override
            public int run(Call call) throws IOException {
                List<String> arguments = call.arguments();
                File target = call.file(arguments.get(arguments.size() - 1));
                int status = 0;
                for (String name : arguments.subList(1, arguments.size() - 1)) {
                    File from = call.file(name);
                    if (!from.isFile()) {
                        call.complain("rsync: link_stat \"" + name + "\" failed: No such file "
                                + "or directory");
                        status = 23;
                        continue;
                    }
                    File to = new File(target, normal(name));
                    Files.createDirectories(to.getParentFile().toPath());
                    Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                return status;
            }
        });
        return tools;
    }

    /**
     * The test: {@code [ ... ]}, or {@code test ...} without the closing bracket. The forms
     * ODK's recipes use: a file that is there (-f, -d, -e) or has something in it (-s), a word
     * that is empty or not (-z, -n, or the word by itself), two words that are the same or
     * not (=, !=) - and no word at all, which is false.
     */
    private static Tool test(final boolean bracket) {
        return new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                if (bracket && (arguments.isEmpty()
                        || !arguments.get(arguments.size() - 1).equals("]"))) {
                    return "'[ " + String.join(" ", arguments) + "' is a test without its "
                            + "closing ']'";
                }
                return verdict(arguments, null) == null
                        ? "'" + (bracket ? "[ " : "test ") + String.join(" ", arguments)
                                + "' is not a test read here: only -f, -d, -e, -s, -n, -z, = "
                                + "and !=, and a word by itself" : null;
            }

            @Override
            public int run(Call call) {
                return verdict(call.arguments(), call) ? 0 : 1;
            }

            /** The test's answer; with no call, whether it is one that is read at all. */
            private Boolean verdict(List<String> arguments, Call call) {
                List<String> words = new ArrayList<String>(arguments);
                if (bracket && !words.isEmpty() && words.get(words.size() - 1).equals("]")) {
                    words.remove(words.size() - 1);
                }
                if (words.size() < 2) {
                    // No word is false; one word is true when it has something in it.
                    return words.size() == 1 && !words.get(0).isEmpty();
                }
                if (words.size() == 2 && words.get(0).matches("-[fdes]")) {
                    if (call == null) {
                        return Boolean.TRUE;
                    }
                    File file = call.file(words.get(1));
                    switch (words.get(0)) {
                        case "-f":
                            return file.isFile();
                        case "-d":
                            return file.isDirectory();
                        case "-s":
                            // A folder's size is never 0 where ODK runs.
                            return file.isDirectory() || file.length() > 0;
                        default:
                            return file.exists();
                    }
                }
                if (words.size() == 2 && words.get(0).matches("-[nz]")) {
                    return words.get(0).equals("-n") != words.get(1).isEmpty();
                }
                if (words.size() == 3 && words.get(1).matches("!?=")) {
                    return words.get(1).equals("=") == words.get(0).equals(words.get(2));
                }
                return null;
            }
        };
    }

    private static Tool status(final int status) {
        return new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return null;
            }

            @Override
            public int run(Call call) {
                return status;
            }
        };
    }

    /** The arguments that are options: they start with a dash and are more than the dash. */
    private static List<String> options(List<String> arguments) {
        List<String> options = new ArrayList<String>();
        for (String argument : arguments) {
            if (argument.startsWith("-") && argument.length() > 1) {
                options.add(argument);
            }
        }
        return options;
    }

    private static List<String> names(List<String> arguments) {
        List<String> names = new ArrayList<String>(arguments);
        names.removeAll(options(arguments));
        return names;
    }

    private static String firstRefusal(List<String> paths, boolean removing) {
        for (String path : paths) {
            String why = removing ? whyNotRemoved(path) : whyNotWritten(path);
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    /** A text's lines; a last line without its line break counts. */
    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<String>();
        if (text.isEmpty()) {
            return lines;
        }
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        lines.addAll(Arrays.asList(body.split("\n", -1)));
        return lines;
    }
}
