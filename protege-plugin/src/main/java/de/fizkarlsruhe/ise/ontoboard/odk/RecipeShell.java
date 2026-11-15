package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * {@code ||}, {@code ;}, a group in braces, {@code if ... then ... else ... fi}, a pipe, a
 * file for input or output, and {@code "$(...)"} inside double quotes. No variables, no file
 * patterns, no background jobs: a line with any of those is refused, saying which, and so is
 * a command nobody here knows. ODK's {@code clean} loop is the one line read whole.
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
            for (Word word : words) {
                arguments.add(running.value(word));
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
                File[] inside = new File(running.folder, name).listFiles();
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
                    return "'" + name + "' is a program of ODK's image that OntoBoard's own "
                            + "engine has no stand-in for (" + oneLine(line) + ")";
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
            try {
                status = root.run(running, "", running.top);
            } catch (Exit exit) {
                status = exit.status;
            } catch (IOException failed) {
                said.add(String.valueOf(failed.getMessage()));
                status = 1;
            }
            running.printed(running.top);
            if (running.top.length() > 0) {
                // A last line without its line break.
                said.add(running.top.toString());
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
        Reader reader = new Reader(command);
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
        private final List<Token> tokens = new ArrayList<Token>();
        private int at;

        Reader(String line) {
            this.line = line;
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
                    word.parts.add(text.substring(i + 1, close));
                    word.quoted = true;
                    i = close + 1;
                } else if (c == '"') {
                    i = doubleQuoted(text, i + 1, word);
                } else if (c == '\\') {
                    if (i + 1 >= length) {
                        throw notRead("a backslash at the end");
                    }
                    word.parts.add(String.valueOf(text.charAt(i + 1)));
                    word.quoted = true;
                    i += 2;
                } else if (c == '$') {
                    throw notRead("'" + dollarAt(text, i) + "' (a variable, or a command's "
                            + "output outside double quotes)");
                } else if (c == '`') {
                    throw notRead("a command in backquotes (`)");
                } else if (c == '*' || c == '?') {
                    throw notRead("a file pattern ('" + wordAround(text, i) + "')");
                } else if ((c == '[' || c == '{' || c == '}') && !alone(text, i, word)) {
                    // On its own, [ is the test and { } hold a group. Inside a word they
                    // would be a pattern or a list of alternatives.
                    throw notRead("'" + wordAround(text, i) + "' (a pattern or a list in "
                            + "brackets)");
                } else {
                    word.parts.add(String.valueOf(c));
                    i++;
                }
            }
            ended(word);
        }

        private Word ended(Word word) {
            if (word != null) {
                merge(word);
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
                throw notRead("'" + c + "' (commands in parentheses)");
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
                            word.parts.add(literal.toString());
                            literal = new StringBuilder();
                        }
                        Reader inner = new Reader(text.substring(i + 2, close));
                        Node node = inner.list();
                        if (inner.at < inner.tokens.size()) {
                            throw notRead("'" + inner.tokens.get(inner.at).shown() + "' inside "
                                    + "$(...)");
                        }
                        word.parts.add(node);
                        i = close + 1;
                        continue;
                    }
                    throw notRead("'" + dollarAt(text, i) + "' (a shell variable)");
                }
                literal.append(c);
                i++;
            }
            word.parts.add(literal.toString());
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

        // -------------------------------------------------------------- commands

        private boolean operator(String operator) {
            return at < tokens.size() && operator.equals(tokens.get(at).operator);
        }

        private boolean reserved(String word) {
            return at < tokens.size() && tokens.get(at).word != null
                    && tokens.get(at).word.is(word);
        }

        private boolean ends() {
            return at >= tokens.size() || reserved("then") || reserved("else")
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
        for (String part : path.split("/")) {
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
        for (String part : path.split("/")) {
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
        if (path.startsWith("/") || new File(path).isAbsolute() || climbs(path) > 2) {
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

    private static void delete(File file) throws IOException {
        File[] children = Files.isSymbolicLink(file.toPath()) ? null : file.listFiles();
        for (File child : children == null ? new File[0] : children) {
            delete(child);
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
        Tool test = new Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return verdict(arguments, null) == null
                        ? "'[ " + String.join(" ", arguments) + "' is not a test read here: "
                                + "only -f, -d, -e, -n, -z, = and !=" : null;
            }

            @Override
            public int run(Call call) {
                return verdict(call.arguments(), call) ? 0 : 1;
            }

            /** The test's answer; with no call, whether it is one that is read at all. */
            private Boolean verdict(List<String> arguments, Call call) {
                List<String> words = new ArrayList<String>(arguments);
                if (!words.isEmpty() && words.get(words.size() - 1).equals("]")) {
                    words.remove(words.size() - 1);
                }
                if (words.size() == 2 && words.get(0).matches("-[fde]")) {
                    if (call == null) {
                        return Boolean.TRUE;
                    }
                    File file = call.file(words.get(1));
                    return words.get(0).equals("-f") ? file.isFile()
                            : words.get(0).equals("-d") ? file.isDirectory() : file.exists();
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
        tools.put("[", test);
        tools.put("test", test);
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
