package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What stands inside {@code {{ }}} and {@code {% %}} in ODK's templates: read with Jinja2's
 * grammar, worked out with Python's rules.
 *
 * <p>From loosest to tightest, as in Jinja2: {@code or}, {@code and}, {@code not}, one comparison
 * ({@code ==}, {@code !=}, {@code in}, {@code not in}), {@code ~}, then a value with what hangs
 * on it - {@code .name}, {@code ['name']}, a method call, and after those {@code |filter} and
 * {@code is test}. So {@code not x|length} is {@code not (x|length)}, and {@code 'a' ~ r|upper}
 * is {@code 'a' ~ (r|upper)}.
 *
 * <p>Values: a name, a quoted text, {@code true}, {@code false} and {@code none} in either case,
 * a list in brackets, and {@code loop.last}. Filters: {@code default}, {@code upper},
 * {@code lower}, {@code length}, {@code replace}, {@code first}. Tests: {@code defined},
 * {@code none}, {@code iterable}, {@code false}, and {@code sameas} a constant. Methods, on a
 * text only: {@code upper()}, {@code startswith(text)}, {@code split(text)}. That is every one
 * ODK v1.6.1 uses, and each behaves as Jinja2 3.1.6's does, down to what looks like an accident:
 * {@code default} leaves {@code None} alone, {@code lower} of {@code False} is {@code false}, a
 * value that is not there has length 0 and can be looped over.
 *
 * <p>The rest of Jinja2 - numbers and arithmetic, parentheses, {@code x if y else z}, keyword
 * arguments, every other filter, test and method - is refused when the template is read, with
 * its line. What Python would refuse while rendering ({@code None.upper()}, a loop over
 * {@code None}) is refused while rendering, with the line too.
 */
final class JinjaExpr {

    /** Words that are part of the grammar, so never the name of a value. */
    private static final Set<String> GRAMMAR = new HashSet<String>(Arrays.asList(
            "and", "or", "not", "in", "is", "if", "else", "elif"));

    private static final Set<String> CONSTANTS = new HashSet<String>(Arrays.asList(
            "true", "True", "false", "False", "none", "None"));

    /**
     * Names Jinja2 itself puts in every template. Nothing of ODK's uses one; read as ordinary
     * names they would be undefined here and defined there.
     */
    private static final Set<String> JINJA_GLOBALS = new HashSet<String>(Arrays.asList(
            "range", "dict", "lipsum", "cycler", "joiner", "namespace"));

    /** Tests that take no argument; {@code sameas} takes one and is read apart. */
    private static final Set<String> TESTS = new HashSet<String>(Arrays.asList(
            "defined", "none", "iterable", "false"));

    /** Each filter with the fewest and the most arguments it takes. */
    private static final Map<String, int[]> FILTERS = new HashMap<String, int[]>();

    /** Each method with the number of arguments it takes. */
    private static final Map<String, Integer> METHODS = new HashMap<String, Integer>();

    static {
        FILTERS.put("default", new int[] {0, 2});
        FILTERS.put("upper", new int[] {0, 0});
        FILTERS.put("lower", new int[] {0, 0});
        FILTERS.put("length", new int[] {0, 0});
        FILTERS.put("first", new int[] {0, 0});
        FILTERS.put("replace", new int[] {2, 2});
        METHODS.put("upper", 0);
        METHODS.put("startswith", 1);
        METHODS.put("split", 1);
    }

    private static final String SIGNS = ".|()[],~=";

    private JinjaExpr() {
    }

    // ------------------------------------------------------------------ reading

    private enum Kind {
        NAME, TEXT, SIGN, END
    }

    /** One word of a tag: a name, a quoted text, a sign, or the tag's end. */
    private static final class Word {
        final Kind kind;
        final String text;
        final int line;

        Word(Kind kind, String text, int line) {
            this.kind = kind;
            this.text = text;
            this.line = line;
        }

        boolean is(Kind wanted, String value) {
            return kind == wanted && text.equals(value);
        }

        /** The word as a message shows it. */
        String shown() {
            if (kind == Kind.END) {
                return "the end of the tag";
            }
            return kind == Kind.TEXT ? "the text '" + text + "'" : "'" + text + "'";
        }
    }

    /** The inside of one {@code {{ }}} or {@code {% %}}, read word by word. */
    static final class Tag {
        private final String template;
        private final List<Word> words = new ArrayList<Word>();
        private final int loops;
        private int at;

        /**
         * @param line  the line the tag starts on
         * @param loops how many {@code for} loops the tag stands inside: {@code loop.last} needs
         *              one
         */
        Tag(String template, String text, int line, int loops) {
            this.template = template;
            this.loops = loops;
            int now = line;
            int pos = 0;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (JinjaLexer.isSpace(c)) {
                    now += c == '\n' ? 1 : 0;
                    pos++;
                } else if (isNameStart(c)) {
                    int end = pos + 1;
                    while (end < text.length() && isNamePart(text.charAt(end))) {
                        end++;
                    }
                    words.add(new Word(Kind.NAME, text.substring(pos, end), now));
                    pos = end;
                } else if (c == '\'' || c == '"') {
                    int end = text.indexOf(c, pos + 1);
                    if (end < 0) {
                        throw new JinjaException(template, now, "a quoted text that is not closed");
                    }
                    String quoted = text.substring(pos + 1, end);
                    if (quoted.indexOf('\\') >= 0) {
                        throw new JinjaException(template, now, "a backslash in the quoted text "
                                + c + quoted + c + ": escapes are not implemented, ODK's "
                                + "templates have none");
                    }
                    words.add(new Word(Kind.TEXT, quoted, now));
                    for (int i = quoted.indexOf('\n'); i >= 0; i = quoted.indexOf('\n', i + 1)) {
                        now++;
                    }
                    pos = end + 1;
                } else if ((c == '=' || c == '!') && pos + 1 < text.length()
                        && text.charAt(pos + 1) == '=') {
                    words.add(new Word(Kind.SIGN, text.substring(pos, pos + 2), now));
                    pos += 2;
                } else if (SIGNS.indexOf(c) >= 0) {
                    words.add(new Word(Kind.SIGN, String.valueOf(c), now));
                    pos++;
                } else if (c >= '0' && c <= '9') {
                    throw new JinjaException(template, now, "a number: numbers and arithmetic "
                            + "are not implemented, ODK's templates have none");
                } else {
                    throw new JinjaException(template, now, "'" + c + "' is not implemented: "
                            + "of Jinja2's signs ODK's templates use only . | ( ) [ ] , ~ = == !=");
                }
            }
            words.add(new Word(Kind.END, "", now));
        }

        private static boolean isNameStart(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        }

        private static boolean isNamePart(char c) {
            return isNameStart(c) || (c >= '0' && c <= '9');
        }

        private Word peek() {
            return words.get(at);
        }

        private Word take() {
            Word word = words.get(at);
            if (word.kind != Kind.END) {
                at++;
            }
            return word;
        }

        private boolean isName(String name) {
            return peek().is(Kind.NAME, name);
        }

        private boolean isSign(String sign) {
            return peek().is(Kind.SIGN, sign);
        }

        private JinjaException refuse(Word where, String message) {
            return new JinjaException(template, where.line, message);
        }

        // ---------------------------------------------- what a statement needs

        /** The tag's first word: {@code if}, {@code for}, {@code endif}... */
        String keyword() {
            Word first = take();
            if (first.kind != Kind.NAME) {
                throw refuse(first, "a tag begins with its name, not with " + first.shown());
            }
            return first.text;
        }

        /** A name that {@code set} or {@code for} may give a value to. */
        String target() {
            Word name = take();
            if (name.kind != Kind.NAME || GRAMMAR.contains(name.text)
                    || CONSTANTS.contains(name.text) || JINJA_GLOBALS.contains(name.text)
                    || name.text.equals("loop")) {
                throw refuse(name, "a name to give a value to is expected where " + name.shown()
                        + " stands");
            }
            return name.text;
        }

        /** Reads the word {@code expected}, or refuses with what is not implemented instead. */
        void word(String expected, String instead) {
            if (!isName(expected)) {
                throw refuse(peek(), "'" + expected + "' is expected where " + peek().shown()
                        + " stands; " + instead);
            }
            take();
        }

        /** Reads the sign {@code expected}, or refuses with what is not implemented instead. */
        void sign(String expected, String instead) {
            if (!isSign(expected)) {
                throw refuse(peek(), "'" + expected + "' is expected where " + peek().shown()
                        + " stands; " + instead);
            }
            take();
        }

        /** Refuses anything left in the tag. */
        void end() {
            if (peek().kind != Kind.END) {
                throw refuse(peek(), peek().shown() + " is not expected here");
            }
        }

        // ---------------------------------------------- the grammar

        /** A whole expression; {@code or} binds loosest. */
        Expr expression() {
            Expr left = conjunction();
            while (isName("or")) {
                int line = take().line;
                left = new Either(line, left, conjunction());
            }
            return left;
        }

        private Expr conjunction() {
            Expr left = negation();
            while (isName("and")) {
                int line = take().line;
                left = new Both(line, left, negation());
            }
            return left;
        }

        private Expr negation() {
            if (isName("not")) {
                int line = take().line;
                return new Not(line, negation());
            }
            return comparison();
        }

        private Expr comparison() {
            Expr left = joined();
            int line = peek().line;
            String sign = comparisonSign();
            if (sign == null) {
                return left;
            }
            Expr right = joined();
            Word after = peek();
            if (comparisonSign() != null) {
                throw refuse(after, "a second comparison in a row, as in a == b == c, is not "
                        + "implemented");
            }
            return new Compare(line, left, sign, right);
        }

        /** Reads {@code ==}, {@code !=}, {@code in} or {@code not in} if one stands here. */
        private String comparisonSign() {
            if (isSign("==") || isSign("!=")) {
                return take().text;
            }
            if (isName("in")) {
                take();
                return "in";
            }
            if (isName("not") && words.get(at + 1).is(Kind.NAME, "in")) {
                at += 2;
                return "not in";
            }
            return null;
        }

        private Expr joined() {
            Expr first = operand();
            if (!isSign("~")) {
                return first;
            }
            int line = peek().line;
            List<Expr> parts = new ArrayList<Expr>();
            parts.add(first);
            while (isSign("~")) {
                take();
                parts.add(operand());
            }
            return new Joined(line, parts);
        }

        /**
         * A value with what hangs on it. As in Jinja2, attributes, items and calls come first
         * and filters and tests after them: nothing but another filter or test follows a filter.
         */
        private Expr operand() {
            Expr node = value();
            while (true) {
                if (isSign(".")) {
                    take();
                    Word name = take();
                    if (name.kind != Kind.NAME) {
                        throw refuse(name, "a name is expected after '.', not " + name.shown());
                    }
                    node = isSign("(") ? call(node, name) : new Attr(name.line, node, name.text);
                } else if (isSign("[")) {
                    int line = take().line;
                    Word key = take();
                    if (key.kind != Kind.TEXT || !isSign("]")) {
                        throw refuse(key, "between brackets only a quoted name is implemented, "
                                + "as in env['ODK_VERSION']");
                    }
                    take();
                    node = new Item(line, node, key.text);
                } else if (isSign("(")) {
                    throw refuse(peek(), "only .upper(), .startswith(text) and .split(text) can "
                            + "be called");
                } else {
                    break;
                }
            }
            while (true) {
                if (isSign("|")) {
                    take();
                    node = filter(node);
                } else if (isName("is")) {
                    take();
                    node = test(node);
                } else if (isSign("(")) {
                    throw refuse(peek(), "calling what a filter or a test gives is not "
                            + "implemented");
                } else {
                    return node;
                }
            }
        }

        private Expr value() {
            Word word = take();
            if (word.kind == Kind.TEXT) {
                if (peek().kind == Kind.TEXT) {
                    throw refuse(peek(), "two quoted texts side by side are not implemented");
                }
                return new Const(word.line, word.text);
            }
            if (word.kind == Kind.NAME) {
                return named(word);
            }
            if (word.is(Kind.SIGN, "[")) {
                List<Expr> items = new ArrayList<Expr>();
                if (!isSign("]")) {
                    items.add(expression());
                    while (isSign(",")) {
                        take();
                        items.add(expression());
                    }
                }
                sign("]", "a list is closed with ']'");
                return new ListOf(word.line, items);
            }
            if (word.is(Kind.SIGN, "(")) {
                throw refuse(word, "parentheses around an expression are not implemented");
            }
            throw refuse(word, "a value is expected where " + word.shown() + " stands");
        }

        private Expr named(Word word) {
            String name = word.text;
            if (name.equals("true") || name.equals("True")) {
                return new Const(word.line, Boolean.TRUE);
            }
            if (name.equals("false") || name.equals("False")) {
                return new Const(word.line, Boolean.FALSE);
            }
            if (name.equals("none") || name.equals("None")) {
                return new Const(word.line, Py.NONE);
            }
            if (name.equals("loop")) {
                if (loops == 0) {
                    throw refuse(word, "'loop' outside a {% for %}");
                }
                if (!isSign(".") || !words.get(at + 1).is(Kind.NAME, "last")) {
                    throw refuse(word, "of the loop's own values only loop.last is implemented");
                }
                at += 2;
                return new LastTurn(word.line);
            }
            if (GRAMMAR.contains(name)) {
                throw refuse(word, "a value is expected where '" + name + "' stands");
            }
            if (JINJA_GLOBALS.contains(name)) {
                throw refuse(word, "Jinja2's own '" + name + "' is not implemented");
            }
            return new Name(word.line, name);
        }

        private Expr call(Expr on, Word method) {
            Integer wanted = METHODS.get(method.text);
            if (wanted == null) {
                throw refuse(method, "the method ." + method.text + "() is not implemented: "
                        + "ODK's templates call only .upper(), .startswith(text) and "
                        + ".split(text)");
            }
            List<Expr> given = arguments();
            if (given.size() != wanted) {
                throw refuse(method, "." + method.text + "() is implemented with " + wanted
                        + " argument(s), not " + given.size());
            }
            return new Call(method.line, on, method.text, given);
        }

        private Expr filter(Expr of) {
            Word name = take();
            if (name.kind != Kind.NAME) {
                throw refuse(name, "a filter's name is expected after '|', not " + name.shown());
            }
            int[] takes = FILTERS.get(name.text);
            if (takes == null) {
                throw refuse(name, "the filter '" + name.text + "' is not implemented: ODK's "
                        + "templates use only default, upper, lower, length, replace and first");
            }
            List<Expr> given = isSign("(") ? arguments() : Collections.<Expr>emptyList();
            if (given.size() < takes[0] || given.size() > takes[1]) {
                throw refuse(name, "the filter '" + name.text + "' is implemented with "
                        + (takes[0] == takes[1] ? String.valueOf(takes[0])
                                : takes[0] + " to " + takes[1])
                        + " argument(s), not " + given.size());
            }
            return new Filter(name.line, of, name.text, given);
        }

        private Expr test(Expr of) {
            boolean negated = isName("not");
            if (negated) {
                take();
            }
            Word name = take();
            if (name.kind != Kind.NAME) {
                throw refuse(name, "a test's name is expected after 'is', not " + name.shown());
            }
            Object same = null;
            if (name.text.equals("sameas")) {
                Word constant = peek();
                Expr argument = constant.kind == Kind.NAME ? value() : null;
                if (!(argument instanceof Const) || ((Const) argument).value instanceof String) {
                    throw refuse(constant, "'is sameas' is implemented for True, False and None "
                            + "only");
                }
                same = ((Const) argument).value;
            } else if (!TESTS.contains(name.text)) {
                throw refuse(name, "the test 'is " + name.text + "' is not implemented: ODK's "
                        + "templates use only defined, none, iterable, false and sameas");
            }
            // Jinja2 reads a name, a text or a bracket after a test as the test's argument.
            Word next = peek();
            boolean argument = next.kind == Kind.TEXT || isSign("(") || isSign("[")
                    || (next.kind == Kind.NAME && !isName("and") && !isName("or")
                            && !isName("else"));
            if (argument) {
                throw refuse(next, next.shown() + " cannot follow 'is " + name.text + "'");
            }
            return new Test(name.line, of, name.text, negated, same);
        }

        private List<Expr> arguments() {
            take();
            List<Expr> given = new ArrayList<Expr>();
            if (isSign(")")) {
                take();
                return given;
            }
            given.add(expression());
            while (isSign(",")) {
                take();
                given.add(expression());
            }
            sign(")", "arguments are given in order, without names");
            return given;
        }
    }

    // ------------------------------------------------------------------ working out

    /** An expression, read. */
    abstract static class Expr {
        final int line;

        Expr(int line) {
            this.line = line;
        }

        /** The value; what Python would refuse is refused with the template and the line. */
        final Object eval(JinjaTemplate.Scope scope) {
            try {
                return value(scope);
            } catch (IllegalStateException refused) {
                throw new JinjaException(scope.template, line, refused.getMessage());
            }
        }

        /** Whether the value counts as true. */
        final boolean holds(JinjaTemplate.Scope scope) {
            return Py.truthy(eval(scope));
        }

        abstract Object value(JinjaTemplate.Scope scope);
    }

    private static final class Const extends Expr {
        final Object value;

        Const(int line, Object value) {
            super(line);
            this.value = value;
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            return value;
        }
    }

    private static final class Name extends Expr {
        private final String name;

        Name(int line, String name) {
            super(line);
            this.name = name;
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            return scope.resolve(name);
        }
    }

    private static final class Attr extends Expr {
        private final Expr of;
        private final String name;

        Attr(int line, Expr of, String name) {
            super(line);
            this.of = of;
            this.name = name;
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            return Py.attr(of.eval(scope), name);
        }
    }

    private static final class Item extends Expr {
        private final Expr of;
        private final String key;

        Item(int line, Expr of, String key) {
            super(line);
            this.of = of;
            this.key = key;
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            return Py.item(of.eval(scope), key);
        }
    }

    /** {@code loop.last}: whether the loop around is on its last item. */
    private static final class LastTurn extends Expr {
        LastTurn(int line) {
            super(line);
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            return Boolean.valueOf(scope.lastTurn());
        }
    }

    private static final class ListOf extends Expr {
        private final List<Expr> items;

        ListOf(int line, List<Expr> items) {
            super(line);
            this.items = items;
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            List<Object> values = new ArrayList<Object>();
            for (Expr item : items) {
                values.add(item.eval(scope));
            }
            return values;
        }
    }

    /** {@code a ~ b}: what each side prints, joined - so {@code None} joins as "None". */
    private static final class Joined extends Expr {
        private final List<Expr> parts;

        Joined(int line, List<Expr> parts) {
            super(line);
            this.parts = parts;
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            StringBuilder out = new StringBuilder();
            for (Expr part : parts) {
                out.append(Py.str(part.eval(scope)));
            }
            return out.toString();
        }
    }

    private static final class Compare extends Expr {
        private final Expr left;
        private final String sign;
        private final Expr right;

        Compare(int line, Expr left, String sign, Expr right) {
            super(line);
            this.left = left;
            this.sign = sign;
            this.right = right;
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            Object a = left.eval(scope);
            Object b = right.eval(scope);
            if (sign.equals("==")) {
                return Py.eq(a, b);
            }
            if (sign.equals("!=")) {
                return !Py.eq(a, b);
            }
            return sign.equals("in") ? Py.contains(b, a) : !Py.contains(b, a);
        }
    }

    private static final class Not extends Expr {
        private final Expr of;

        Not(int line, Expr of) {
            super(line);
            this.of = of;
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            return !of.holds(scope);
        }
    }

    /** {@code a and b}: {@code a} when it is false, else {@code b} - a value, not a boolean. */
    private static final class Both extends Expr {
        private final Expr left;
        private final Expr right;

        Both(int line, Expr left, Expr right) {
            super(line);
            this.left = left;
            this.right = right;
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            Object first = left.eval(scope);
            return Py.truthy(first) ? right.eval(scope) : first;
        }
    }

    /** {@code a or b}: {@code a} when it is true, else {@code b}. */
    private static final class Either extends Expr {
        private final Expr left;
        private final Expr right;

        Either(int line, Expr left, Expr right) {
            super(line);
            this.left = left;
            this.right = right;
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            Object first = left.eval(scope);
            return Py.truthy(first) ? first : right.eval(scope);
        }
    }

    /** A method of Python's str, called on a text. */
    private static final class Call extends Expr {
        private final Expr on;
        private final String method;
        private final List<Expr> arguments;

        Call(int line, Expr on, String method, List<Expr> arguments) {
            super(line);
            this.on = on;
            this.method = method;
            this.arguments = arguments;
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            Object target = on.eval(scope);
            if (target instanceof PyUndefined) {
                throw new IllegalStateException("'" + ((PyUndefined) target).hint()
                        + "' is undefined, so ." + method + "() cannot be called on it");
            }
            List<Object> given = new ArrayList<Object>();
            for (Expr argument : arguments) {
                given.add(argument.eval(scope));
            }
            if (!(target instanceof String)) {
                throw new IllegalStateException("." + method + "() is called on "
                        + Py.describe(target) + "; only a text has it");
            }
            String text = (String) target;
            if (method.equals("upper")) {
                return text.toUpperCase(Locale.ROOT);
            }
            if (!(given.get(0) instanceof String)) {
                throw new IllegalStateException("." + method + "() needs a text, got "
                        + Py.describe(given.get(0)));
            }
            String argument = (String) given.get(0);
            return method.equals("startswith") ? (Object) text.startsWith(argument)
                    : split(text, argument);
        }
    }

    /** Python's {@code text.split(separator)}: at every occurrence, empty parts kept. */
    static List<Object> split(String text, String separator) {
        if (separator.isEmpty()) {
            throw new IllegalStateException("split('') has nothing to split at");
        }
        List<Object> parts = new ArrayList<Object>();
        int from = 0;
        for (int found = text.indexOf(separator); found >= 0;
                found = text.indexOf(separator, from)) {
            parts.add(text.substring(from, found));
            from = found + separator.length();
        }
        parts.add(text.substring(from));
        return parts;
    }

    private static final class Filter extends Expr {
        private final Expr of;
        private final String filter;
        private final List<Expr> arguments;

        Filter(int line, Expr of, String filter, List<Expr> arguments) {
            super(line);
            this.of = of;
            this.filter = filter;
            this.arguments = arguments;
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            Object value = of.eval(scope);
            // Python works out every argument before the call, needed or not.
            List<Object> given = new ArrayList<Object>();
            for (Expr argument : arguments) {
                given.add(argument.eval(scope));
            }
            if (filter.equals("default")) {
                Object instead = given.isEmpty() ? "" : given.get(0);
                boolean alsoWhenFalse = given.size() > 1 && Py.truthy(given.get(1));
                boolean missing = value instanceof PyUndefined
                        || (alsoWhenFalse && !Py.truthy(value));
                return missing ? instead : value;
            }
            if (filter.equals("upper")) {
                return Py.str(value).toUpperCase(Locale.ROOT);
            }
            if (filter.equals("lower")) {
                return Py.str(value).toLowerCase(Locale.ROOT);
            }
            if (filter.equals("length")) {
                return length(value);
            }
            if (filter.equals("first")) {
                Iterator<Object> items = Py.iterate(value).iterator();
                return items.hasNext() ? items.next() : Py.undefined("the first of nothing");
            }
            String find = Py.str(given.get(0));
            if (find.isEmpty()) {
                throw new IllegalStateException("replace('', ...) is not implemented");
            }
            return Py.str(value).replace(find, Py.str(given.get(1)));
        }

        /** Python's {@code len()}, which Jinja2's Undefined answers with 0. */
        private static Object length(Object value) {
            if (value instanceof String) {
                String text = (String) value;
                return (long) text.codePointCount(0, text.length());
            }
            if (value instanceof List) {
                return (long) ((List<?>) value).size();
            }
            if (value instanceof Map) {
                return (long) ((Map<?, ?>) value).size();
            }
            if (value instanceof PyUndefined) {
                return 0L;
            }
            throw new IllegalStateException(Py.describe(value) + " has no length");
        }
    }

    private static final class Test extends Expr {
        private final Expr of;
        private final String test;
        private final boolean negated;
        /** For {@code sameas}: the constant to be the same as. */
        private final Object same;

        Test(int line, Expr of, String test, boolean negated, Object same) {
            super(line);
            this.of = of;
            this.test = test;
            this.negated = negated;
            this.same = same;
        }

        @Override
        Object value(JinjaTemplate.Scope scope) {
            return passes(of.eval(scope)) != negated;
        }

        private boolean passes(Object value) {
            if (test.equals("defined")) {
                return !(value instanceof PyUndefined);
            }
            if (test.equals("none")) {
                return value instanceof PyNone;
            }
            if (test.equals("iterable")) {
                return value instanceof String || value instanceof List || value instanceof Map
                        || value instanceof PyUndefined;
            }
            if (test.equals("false")) {
                return Boolean.FALSE.equals(value);
            }
            // sameas is Python's "is": True is the same as True and as nothing else, not as 1
            // and not as the text "true".
            return same instanceof Boolean ? same.equals(value) : value instanceof PyNone;
        }
    }
}
