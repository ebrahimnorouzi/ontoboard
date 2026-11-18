package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code sed -i 's/RE/REPL/[g]' FILE...}: GNU sed's substitution in place, as projects' own rules
 * use it on the files they make (nfdicore's import rules: {@code s/obofoundry/obolibrary/g}, and
 * a declaration taken out). ODK's image has GNU sed 4.9, run in the C.UTF-8 locale; this does
 * what it does there, and is held against it ({@code OdkSedTest}).
 *
 * <p>Read: {@code -i}, then {@code -e} or not, then one {@code s} command - any delimiter, the
 * expression in POSIX's basic syntax ({@code .}, {@code *}, {@code [...]}, {@code ^}, {@code $},
 * {@code \(} {@code \)}, {@code \{m,n\}}, {@code \1}-{@code \9}), the replacement with
 * {@code &}, {@code \1}-{@code \9}, {@code \&} and {@code \\}, and {@code g} or no flag - then
 * the files. Each file is read line by line as sed reads it: a line is what stands between line
 * breaks, a carriage return of a Windows line is part of it (so {@code $} does not match before
 * one), and a last line without its line break is written back without one. Without {@code g}
 * the first match of each line is replaced; with it every match, and - as GNU sed - no empty
 * match right where the one before ended: {@code a*} replaced by {@code x} everywhere makes
 * {@code baaac} {@code xbxcx}.
 *
 * <p>Refused by name, before anything runs: every other option and command, a second script,
 * a flag but {@code g}, what GNU adds to POSIX's expressions ({@code \+ \? \| \w \b \< ...}),
 * classes of characters ({@code [[:alpha:]]}), escapes of GNU's in the replacement
 * ({@code \n \U ...}), a reference to a group the expression does not have, and a file outside
 * the project.
 */
final class OdkSed implements RecipeShell.Tool {

    static final String ONLY = "only sed -i 's/RE/REPL/[g]' FILE...";

    /** What of a call is not read here; the message says what, or is null for the whole. */
    private static final class NotRead extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotRead(String what) {
            super(what, null, false, false);
        }
    }

    /** One {@code s} command, read. */
    static final class Substitution {
        private final Pattern pattern;
        /** Text as it is (a String), or a group's number (an Integer; 0 is the whole match). */
        private final List<Object> replacement;
        private final boolean global;

        Substitution(Pattern pattern, List<Object> replacement, boolean global) {
            this.pattern = pattern;
            this.replacement = replacement;
            this.global = global;
        }

        /** One line, as sed leaves it. */
        String apply(String line) {
            Matcher match = pattern.matcher(line);
            StringBuilder out = new StringBuilder();
            int copied = 0;
            int from = 0;
            int lastEnd = -1;
            while (from <= line.length() && match.find(from)) {
                int start = match.start();
                int end = match.end();
                if (start == end && start == lastEnd) {
                    // GNU sed: no empty match where the match before ended.
                    if (start >= line.length()) {
                        break;
                    }
                    from = start + Character.charCount(line.codePointAt(start));
                    continue;
                }
                out.append(line, copied, start);
                for (Object part : replacement) {
                    if (part instanceof Integer) {
                        String group = match.group((Integer) part);
                        out.append(group == null ? "" : group);
                    } else {
                        out.append((String) part);
                    }
                }
                copied = end;
                lastEnd = end;
                if (!global) {
                    break;
                }
                if (start == end) {
                    if (end >= line.length()) {
                        break;
                    }
                    from = end + Character.charCount(line.codePointAt(end));
                } else {
                    from = end;
                }
            }
            out.append(line, copied, line.length());
            return out.toString();
        }
    }

    // ------------------------------------------------------------------ the tool

    @Override
    public String whyNot(List<String> arguments) {
        List<String> files;
        try {
            files = filesOf(arguments);
            read(scriptOf(arguments));
        } catch (NotRead notRead) {
            return "'sed " + String.join(" ", arguments) + "' is not read here: "
                    + (notRead.getMessage() == null ? "" : notRead.getMessage() + "; ") + ONLY;
        }
        for (String file : files) {
            String outside = RecipeShell.whyNotWritten(file);
            if (outside != null) {
                return outside;
            }
        }
        return null;
    }

    @Override
    public int run(RecipeShell.Call call) throws IOException {
        Substitution substitution = read(scriptOf(call.arguments()));
        int status = 0;
        for (String name : filesOf(call.arguments())) {
            File file = call.file(name);
            if (file.isDirectory()) {
                call.complain("sed: couldn't edit " + name + ": not a regular file");
                status = 4;
                continue;
            }
            if (!file.isFile()) {
                call.complain("sed: can't read " + name + ": No such file or directory");
                status = Math.max(status, 2);
                continue;
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            Charset charset = utf8(bytes) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
            String text = new String(bytes, charset);
            if (text.isEmpty()) {
                continue;
            }
            boolean ended = text.endsWith("\n");
            String[] lines = (ended ? text.substring(0, text.length() - 1) : text).split("\n", -1);
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < lines.length; i++) {
                out.append(substitution.apply(lines[i]));
                if (i < lines.length - 1 || ended) {
                    out.append('\n');
                }
            }
            Files.write(file.toPath(), out.toString().getBytes(charset));
        }
        return status;
    }

    /** Whether the bytes are UTF-8; otherwise each is a character of its own, and kept. */
    private static boolean utf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException notUtf8) {
            return false;
        }
    }

    // ------------------------------------------------------------------ the call

    /** {@code -i}, {@code -e} or not, the script: where the script stands. */
    private static int scriptAt(List<String> arguments) {
        if (arguments.isEmpty() || !arguments.get(0).equals("-i")) {
            throw new NotRead(null);
        }
        int at = arguments.size() > 1 && arguments.get(1).equals("-e") ? 2 : 1;
        if (at >= arguments.size()) {
            throw new NotRead(null);
        }
        return at;
    }

    private static String scriptOf(List<String> arguments) {
        return arguments.get(scriptAt(arguments));
    }

    private static List<String> filesOf(List<String> arguments) {
        List<String> files = arguments.subList(scriptAt(arguments) + 1, arguments.size());
        if (files.isEmpty()) {
            throw new NotRead("no file to edit");
        }
        for (String file : files) {
            if (file.startsWith("-")) {
                // An option after the script: GNU sed would take it as one.
                throw new NotRead(null);
            }
        }
        return files;
    }

    // ------------------------------------------------------------------ the script

    /** One {@code s} command, as GNU sed reads it. */
    static Substitution read(String script) {
        if (script.length() < 2 || script.charAt(0) != 's') {
            throw new NotRead(null);
        }
        char delimiter = script.charAt(1);
        if (delimiter == '\\' || delimiter == '\n') {
            throw new NotRead(null);
        }
        int[] at = {2};
        String expression = part(script, at, delimiter, true);
        String replacement = part(script, at, delimiter, false);
        String flags = script.substring(at[0]);
        if (!flags.isEmpty() && !flags.equals("g")) {
            throw new NotRead("the flag '" + flags + "'");
        }
        int[] groups = {0};
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex(expression, groups), Pattern.UNIX_LINES);
        } catch (PatternSyntaxException unread) {
            throw new NotRead("the expression '" + expression + "'");
        }
        return new Substitution(pattern, replacement(replacement, groups[0]), flags.equals("g"));
    }

    /**
     * Up to the delimiter, as GNU sed's {@code match_slash} reads it: a backslash before the
     * delimiter is dropped (but for {@code \&} in the replacement), any other is kept.
     */
    private static String part(String script, int[] at, char delimiter, boolean expression) {
        StringBuilder part = new StringBuilder();
        int i = at[0];
        while (i < script.length()) {
            char c = script.charAt(i);
            if (c == delimiter) {
                at[0] = i + 1;
                return part.toString();
            }
            if (c == '\n') {
                break;
            }
            if (c == '\\') {
                if (i + 1 >= script.length()) {
                    break;
                }
                char next = script.charAt(i + 1);
                if (next == 'n' && expression) {
                    throw new NotRead("\\n, a line break, which no line has");
                }
                if (next != '\n' && (next != delimiter || (!expression && next == '&'))) {
                    part.append('\\');
                }
                part.append(next);
                i += 2;
                continue;
            }
            part.append(c);
            i++;
        }
        throw new NotRead("an s command that is not closed");
    }

    /** POSIX's basic expression as a pattern of Java's; {@code groups[0]} counts its groups. */
    private static String regex(String expression, int[] groups) {
        StringBuilder out = new StringBuilder();
        int open = 0;
        // ^ is an anchor at the start and right after \(; * is itself there and after that ^.
        boolean anchorHere = true;
        boolean starIsItself = true;
        // Whether the element before is a repeat: * after * is one *, as in GNU sed; a repeat
        // of an interval is not read.
        boolean afterRepeat = false;
        boolean afterInterval = false;
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            boolean anchorAllowed = anchorHere;
            boolean starLiteral = starIsItself;
            anchorHere = false;
            starIsItself = false;
            boolean repeat = false;
            boolean interval = false;
            if (c == '\\') {
                if (i + 1 >= expression.length()) {
                    throw new NotRead("a backslash at the end of the expression");
                }
                char next = expression.charAt(i + 1);
                i += 2;
                if (next == '(') {
                    out.append('(');
                    groups[0]++;
                    open++;
                    anchorHere = true;
                    starIsItself = true;
                } else if (next == ')') {
                    if (open == 0) {
                        throw new NotRead("an unmatched \\)");
                    }
                    out.append(')');
                    open--;
                } else if (next == '{') {
                    int close = expression.indexOf("\\}", i);
                    if (close < 0) {
                        throw new NotRead("an unmatched \\{");
                    }
                    String body = expression.substring(i, close);
                    if (starLiteral || afterRepeat || !body.matches("[0-9]+(,[0-9]*)?")) {
                        throw new NotRead("the interval \\{" + body + "\\}");
                    }
                    out.append('{').append(body).append('}');
                    i = close + 2;
                    repeat = true;
                    interval = true;
                } else if (next >= '1' && next <= '9') {
                    if (next - '0' > groups[0]) {
                        throw new NotRead("\\" + next + ", a group the expression does not have");
                    }
                    out.append('\\').append(next);
                } else if (Character.isLetterOrDigit(next) || "+?|<>'`{}".indexOf(next) >= 0) {
                    throw new NotRead("\\" + next + ", which is GNU's and not POSIX's basic "
                            + "expressions");
                } else {
                    // Any other character after a backslash is itself: \. \* \[ \/ \\ ...
                    out.append(itself(next));
                }
            } else if (c == '[') {
                int close = bracketEnd(expression, i);
                if (close < 0) {
                    throw new NotRead("an unmatched [");
                }
                out.append(bracket(expression, i, close));
                i = close + 1;
            } else if (c == '*') {
                if (afterInterval) {
                    throw new NotRead("a * after an interval");
                }
                if (starLiteral) {
                    out.append("\\*");
                } else if (!afterRepeat) {
                    out.append('*');
                    repeat = true;
                } else {
                    repeat = true;
                }
                i++;
            } else if (c == '^' && anchorAllowed) {
                out.append('^');
                starIsItself = true;
                i++;
            } else if (c == '$' && (i + 1 == expression.length()
                    || expression.startsWith("\\)", i + 1))) {
                out.append('$');
                i++;
            } else if (c == '.') {
                out.append('.');
                i++;
            } else {
                out.append(itself(c));
                i++;
            }
            afterRepeat = repeat;
            afterInterval = interval;
        }
        if (open > 0) {
            throw new NotRead("an unmatched \\(");
        }
        return out.toString();
    }

    /** A character for itself in a pattern of Java's. */
    private static String itself(char c) {
        return Character.isLetterOrDigit(c) || Character.isSurrogate(c) || c == ' '
                ? String.valueOf(c) : "\\" + c;
    }

    /** Where the bracket opened at {@code open} closes, or -1. */
    private static int bracketEnd(String expression, int open) {
        int i = open + 1;
        if (i < expression.length() && expression.charAt(i) == '^') {
            i++;
        }
        if (i < expression.length() && expression.charAt(i) == ']') {
            i++;
        }
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (c == '[' && i + 1 < expression.length()
                    && ":=.".indexOf(expression.charAt(i + 1)) >= 0) {
                throw new NotRead("a class of characters ('" + expression.substring(open) + "')");
            }
            if (c == ']') {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** A bracket expression as a class of Java's: its characters and ranges, by code point. */
    private static String bracket(String expression, int open, int close) {
        StringBuilder set = new StringBuilder("[");
        int i = open + 1;
        if (expression.charAt(i) == '^') {
            set.append('^');
            i++;
        }
        int first = i;
        while (i < close) {
            int c = expression.codePointAt(i);
            if (c == '-' && i > first && i + 1 < close) {
                set.append('-');
            } else {
                set.append("\\x{").append(Integer.toHexString(c)).append('}');
            }
            i += Character.charCount(c);
        }
        return set.append(']').toString();
    }

    /** The replacement: text, and the groups it names ({@code &} is 0). */
    private static List<Object> replacement(String text, int groups) {
        List<Object> parts = new ArrayList<Object>();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Integer group = null;
            if (c == '&') {
                group = 0;
            } else if (c == '\\') {
                char next = text.charAt(++i);
                if (next >= '1' && next <= '9') {
                    if (next - '0' > groups) {
                        throw new NotRead("\\" + next + ", a group the expression does not have");
                    }
                    group = next - '0';
                } else if (Character.isLetterOrDigit(next)) {
                    throw new NotRead("\\" + next + " in the replacement, which is GNU's");
                } else {
                    literal.append(next);
                }
            } else {
                literal.append(c);
            }
            if (group != null) {
                if (literal.length() > 0) {
                    parts.add(literal.toString());
                    literal.setLength(0);
                }
                parts.add(group);
            }
        }
        if (literal.length() > 0) {
            parts.add(literal.toString());
        }
        return parts;
    }
}
