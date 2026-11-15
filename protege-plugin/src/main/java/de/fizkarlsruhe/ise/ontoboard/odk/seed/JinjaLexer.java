package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a template into text, {@code {{ expressions }}} and {@code {% tags %}}, where Jinja2
 * 3.1.6 cuts it with its default settings - which is how ODK renders.
 *
 * <p>The rules, each of which shows in the bytes of a seeded file:
 *
 * <ul>
 *   <li>Line endings become line feeds, and one final newline is dropped
 *       ({@code keep_trailing_newline} is off) - why a seeded Makefile ends without one.
 *   <li>A {@code -} just inside a delimiter ({@code {%-}, {@code -%}}, {@code {{-}, {@code -}}},
 *       {@code {#-}, {@code -#}}) removes all whitespace on that side, newlines included, by
 *       Python's idea of whitespace: a no-break space counts. A {@code +} there, or nothing,
 *       removes none ({@code trim_blocks} and {@code lstrip_blocks} are off).
 *   <li>A comment produces nothing. A {@code raw} block is text, whatever it contains.
 *   <li>A delimiter inside a quoted string, or while a bracket is open, does not end a tag.
 * </ul>
 *
 * <p>Adjacent pieces of text come out as one token, since nothing is rendered between them.
 * Jinja2 lets an unclosed tag through to its parser; here it is refused at once, with its line.
 */
public final class JinjaLexer {

    /** What a token is. */
    public enum Kind {
        /** Text, written as it is. */
        DATA,
        /** The inside of {@code {{ ... }}}. */
        VARIABLE,
        /** The inside of {@code {% ... %}}. */
        BLOCK
    }

    /** One piece of a template. */
    public static final class Token {
        public final Kind kind;
        /** The text, or for a tag its inside, without the delimiters and trimmed. */
        public final String text;
        /** The line the piece starts on, counted from 1. */
        public final int line;

        Token(Kind kind, String text, int line) {
            this.kind = kind;
            this.text = text;
            this.line = line;
        }

        @Override
        public String toString() {
            return kind + "@" + line + ":" + text;
        }
    }

    private final String name;
    private final String source;
    private final List<Token> tokens = new ArrayList<Token>();
    private int pos;
    private int line = 1;

    private JinjaLexer(String name, String source) {
        this.name = name;
        this.source = prepared(source);
    }

    /**
     * The template as tokens.
     *
     * @param templateName for messages: the template's path in ODK's folder
     * @throws JinjaException when a tag, a comment, a raw block or a string is not closed
     */
    public static List<Token> tokenize(String templateName, String source) {
        JinjaLexer lexer = new JinjaLexer(templateName, source);
        lexer.run();
        return lexer.tokens;
    }

    /** As Jinja2 prepares a source: every line ending a line feed, one final newline dropped. */
    static String prepared(String source) {
        String text = source.replace("\r\n", "\n").replace('\r', '\n');
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    private void run() {
        while (pos < source.length()) {
            int open = nextOpening(pos);
            if (open < 0) {
                data(source.substring(pos), 0);
                return;
            }
            char kind = source.charAt(open + 1);
            int inside = open + 2;
            char sign = inside < source.length() ? source.charAt(inside) : 0;
            boolean strips = sign == '-';
            if (sign == '-' || sign == '+') {
                inside++;
            }
            String text = source.substring(pos, open);
            int strippedNewlines = 0;
            if (strips) {
                String kept = rstrip(text);
                strippedNewlines = newlinesIn(text.substring(kept.length()));
                text = kept;
            }
            data(text, strippedNewlines);
            pos = inside;
            if (kind == '#') {
                comment();
            } else if (kind == '{') {
                tag(Kind.VARIABLE, "}}");
            } else if (rawBegins(inside)) {
                raw();
            } else {
                tag(Kind.BLOCK, "%}");
            }
        }
    }

    /** The next {@code {%}, {@code {{} or {@code {#} at or after {@code from}, or -1. */
    private int nextOpening(int from) {
        for (int at = source.indexOf('{', from); at >= 0 && at + 1 < source.length();
                at = source.indexOf('{', at + 1)) {
            char next = source.charAt(at + 1);
            if (next == '%' || next == '{' || next == '#') {
                return at;
            }
        }
        return -1;
    }

    /** Appends text - to the text before it when that is the last token - and counts its lines. */
    private void data(String text, int strippedNewlines) {
        if (!text.isEmpty()) {
            int last = tokens.size() - 1;
            if (last >= 0 && tokens.get(last).kind == Kind.DATA) {
                Token before = tokens.get(last);
                tokens.set(last, new Token(Kind.DATA, before.text + text, before.line));
            } else {
                tokens.add(new Token(Kind.DATA, text, line));
            }
        }
        line += newlinesIn(text) + strippedNewlines;
    }

    /** From just inside {@code {#}: to the first {@code #}}, {@code -#}} or {@code +#}}. */
    private void comment() {
        int startLine = line;
        int end = source.indexOf("#}", pos);
        if (end < 0) {
            throw new JinjaException(name, startLine, "Missing end of comment tag");
        }
        boolean strips = end > pos && source.charAt(end - 1) == '-';
        line += newlinesIn(source.substring(pos, end));
        pos = end + 2;
        if (strips) {
            skipWhitespace();
        }
    }

    /**
     * From just inside {@code {{} or {@code {%}: to the closing delimiter that is outside every
     * string and every bracket.
     */
    private void tag(Kind kind, String close) {
        int startLine = line;
        int start = pos;
        int depth = 0;
        int at = pos;
        while (at < source.length()) {
            char c = source.charAt(at);
            if (c == '\'' || c == '"') {
                int closing = at + 1;
                while (closing < source.length() && source.charAt(closing) != c) {
                    closing += source.charAt(closing) == '\\' ? 2 : 1;
                }
                if (closing >= source.length()) {
                    throw new JinjaException(name, startLine + newlinesIn(source.substring(start,
                            at)), "a quoted text that is not closed: " + excerpt(at));
                }
                at = closing + 1;
                continue;
            }
            if (depth == 0) {
                boolean signed = (c == '-' || c == '+') && source.startsWith(close, at + 1);
                // {{ x +}} is not Jinja2: only a tag or a comment may be closed with a plus.
                if (signed && c == '+' && kind == Kind.VARIABLE) {
                    signed = false;
                }
                if (signed || source.startsWith(close, at)) {
                    tokens.add(new Token(kind, strip(source.substring(start, at)), startLine));
                    line += newlinesIn(source.substring(start, at));
                    pos = at + close.length() + (signed ? 1 : 0);
                    if (signed && c == '-') {
                        skipWhitespace();
                    }
                    return;
                }
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
                depth--;
            }
            at++;
        }
        throw new JinjaException(name, startLine, "a " + (kind == Kind.VARIABLE ? "{{" : "{%")
                + " that is never closed: " + excerpt(start));
    }

    /** Whether {@code {%} (and its sign) is followed by {@code raw %}} or {@code raw -%}}. */
    private boolean rawBegins(int inside) {
        int at = skip(inside);
        if (!source.startsWith("raw", at)) {
            return false;
        }
        at = skip(at + 3);
        return source.startsWith("%}", at) || source.startsWith("-%}", at);
    }

    /** From just inside a {@code {% raw %}}: its text, up to the {@code {% endraw %}}. */
    private void raw() {
        int startLine = line;
        int at = skip(skip(pos) + 3);
        boolean stripsAfter = source.charAt(at) == '-';
        int begin = at + (stripsAfter ? 3 : 2);
        line += newlinesIn(source.substring(pos, begin));
        pos = begin;
        if (stripsAfter) {
            skipWhitespace();
        }
        for (int open = source.indexOf("{%", pos); open >= 0; open = source.indexOf("{%", open + 2)) {
            int inside = open + 2;
            char sign = inside < source.length() ? source.charAt(inside) : 0;
            if (sign == '-' || sign == '+') {
                inside++;
            }
            int word = skip(inside);
            if (!source.startsWith("endraw", word)) {
                continue;
            }
            int close = skip(word + 6);
            boolean plus = source.startsWith("+%}", close);
            boolean minus = source.startsWith("-%}", close);
            if (!plus && !minus && !source.startsWith("%}", close)) {
                continue;
            }
            String text = source.substring(pos, open);
            int strippedNewlines = 0;
            if (sign == '-') {
                String kept = rstrip(text);
                strippedNewlines = newlinesIn(text.substring(kept.length()));
                text = kept;
            }
            data(text, strippedNewlines);
            int end = close + (plus || minus ? 3 : 2);
            line += newlinesIn(source.substring(open, end));
            pos = end;
            if (minus) {
                skipWhitespace();
            }
            return;
        }
        throw new JinjaException(name, startLine, "Missing end of raw directive");
    }

    /** Moves past whitespace, counting the lines in it: what {@code -%}} does to what follows. */
    private void skipWhitespace() {
        int end = skip(pos);
        line += newlinesIn(source.substring(pos, end));
        pos = end;
    }

    /** The first position at or after {@code from} that is not whitespace. */
    private int skip(int from) {
        int at = from;
        while (at < source.length() && isSpace(source.charAt(at))) {
            at++;
        }
        return at;
    }

    private String excerpt(int at) {
        int end = Math.min(source.length(), at + 40);
        int newline = source.indexOf('\n', at);
        return source.substring(at, newline >= 0 && newline < end ? newline : end);
    }

    private static int newlinesIn(String text) {
        int count = 0;
        for (int at = text.indexOf('\n'); at >= 0; at = text.indexOf('\n', at + 1)) {
            count++;
        }
        return count;
    }

    /**
     * Python's {@code str.isspace()} for one character, which is also what its {@code \s}
     * matches: not Java's {@code Character.isWhitespace}, which leaves the no-break spaces out.
     */
    static boolean isSpace(char c) {
        return (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x20) || c == 0x85 || c == 0xA0
                || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029
                || c == 0x202F || c == 0x205F || c == 0x3000;
    }

    /** Python's {@code str.rstrip()}. */
    static String rstrip(String text) {
        int end = text.length();
        while (end > 0 && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** Python's {@code str.strip()}. */
    static String strip(String text) {
        String right = rstrip(text);
        int start = 0;
        while (start < right.length() && isSpace(right.charAt(start))) {
            start++;
        }
        return right.substring(start);
    }
}
