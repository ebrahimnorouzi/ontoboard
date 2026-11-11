package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One of ODK's templates, read once and rendered as Jinja2 3.1.6 renders it for ODK.
 *
 * <p>ODK v1.6.1's templates use a small part of Jinja2: {@code if} with {@code elif} and
 * {@code else}, {@code for} over one name, {@code set} of one name, and the expressions of
 * {@link JinjaExpr}. This does that part, with Python's rules for every value ({@link Py}), and
 * refuses the rest where it stands - a macro, an include, a filter ODK does not use. The refusal
 * is the point: a later ODK that reaches for something new then fails a test here, instead of
 * seeding projects that differ from ODK's in a way nobody sees.
 *
 * <p>Names are scoped as Jinja2 scopes them. An {@code if} has no scope of its own, so what it
 * sets is seen after it. A {@code for} has one per turn: what one turn sets is gone at the next
 * turn and after the loop, and a name set outside is read inside but not changed. Jinja2 decides
 * this when it compiles a template and here it is decided while rendering; the two agree unless a
 * template assigns a name the caller also passes in, which is therefore refused.
 */
public final class JinjaTemplate {

    /** Tags that end or continue a block rather than begin something. */
    private static final Set<String> CLOSERS = new HashSet<String>(Arrays.asList(
            "elif", "else", "endif", "endfor"));

    private final String name;
    private final List<Node> body;
    /** Every name the template sets or loops over, with the line it first does so. */
    private final Map<String, Integer> assigned;

    private JinjaTemplate(String name, List<Node> body, Map<String, Integer> assigned) {
        this.name = name;
        this.body = body;
        this.assigned = assigned;
    }

    /**
     * Reads a template.
     *
     * @param templateName for messages: the template's path in ODK's folder
     * @throws JinjaException when the template is broken, or uses what is not implemented
     */
    public static JinjaTemplate parse(String templateName, String source) {
        return new Parser(templateName, JinjaLexer.tokenize(templateName, source)).template();
    }

    /**
     * The text Jinja2 renders with these names: for ODK, {@code project} and {@code env}.
     *
     * @param context the values by name; each a value as {@link Py} lists them
     * @throws JinjaException where Python would raise: an attribute of something undefined, a
     *                        loop over {@code None}
     */
    public String render(Map<String, Object> context) {
        for (Map.Entry<String, Integer> target : assigned.entrySet()) {
            if (context.containsKey(target.getKey())) {
                throw new JinjaException(name, target.getValue(), "the template gives '"
                        + target.getKey() + "' a value, and so does the caller: which one a "
                        + "line then reads is decided in Jinja2 by rules not implemented here");
            }
        }
        Scope top = new Scope(name, context);
        StringBuilder out = new StringBuilder();
        for (Node node : body) {
            node.render(top, out);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ names

    /** The names in reach at one point of a rendering. */
    static final class Scope {
        final String template;
        private final Map<String, Object> context;
        private final Scope outer;
        private final Map<String, Object> names = new HashMap<String, Object>();
        private final boolean turn;
        private final boolean last;

        /** The template's own scope. */
        Scope(String template, Map<String, Object> context) {
            this.template = template;
            this.context = context;
            this.outer = null;
            this.turn = false;
            this.last = false;
        }

        /** One turn of a loop that stands in {@code outer}. */
        Scope(Scope outer, boolean last) {
            this.template = outer.template;
            this.context = outer.context;
            this.outer = outer;
            this.turn = true;
            this.last = last;
        }

        /** The value of a name: the nearest one set, else the caller's, else undefined. */
        Object resolve(String name) {
            for (Scope scope = this; scope != null; scope = scope.outer) {
                Object value = scope.names.get(name);
                if (value != null) {
                    return value;
                }
            }
            Object given = context.get(name);
            return given != null ? given : Py.undefined(name);
        }

        void set(String name, Object value) {
            names.put(name, value);
        }

        /** Whether the innermost loop is on its last item. */
        boolean lastTurn() {
            for (Scope scope = this; scope != null; scope = scope.outer) {
                if (scope.turn) {
                    return scope.last;
                }
            }
            throw new IllegalStateException("loop.last outside a loop");
        }
    }

    // ------------------------------------------------------------------ what a template is made of

    private abstract static class Node {
        abstract void render(Scope scope, StringBuilder out);
    }

    private static void renderAll(List<Node> nodes, Scope scope, StringBuilder out) {
        for (Node node : nodes) {
            node.render(scope, out);
        }
    }

    private static final class Text extends Node {
        private final String text;

        Text(String text) {
            this.text = text;
        }

        @Override
        void render(Scope scope, StringBuilder out) {
            out.append(text);
        }
    }

    /** {@code {{ value }}}. */
    private static final class Print extends Node {
        private final int line;
        private final JinjaExpr.Expr value;

        Print(int line, JinjaExpr.Expr value) {
            this.line = line;
            this.value = value;
        }

        @Override
        void render(Scope scope, StringBuilder out) {
            Object printed = value.eval(scope);
            try {
                out.append(Py.str(printed));
            } catch (IllegalStateException refused) {
                throw new JinjaException(scope.template, line, refused.getMessage());
            }
        }
    }

    private static final class If extends Node {
        private final List<JinjaExpr.Expr> conditions;
        private final List<List<Node>> branches;
        private final List<Node> otherwise;

        If(List<JinjaExpr.Expr> conditions, List<List<Node>> branches, List<Node> otherwise) {
            this.conditions = conditions;
            this.branches = branches;
            this.otherwise = otherwise;
        }

        @Override
        void render(Scope scope, StringBuilder out) {
            for (int i = 0; i < conditions.size(); i++) {
                if (conditions.get(i).holds(scope)) {
                    renderAll(branches.get(i), scope, out);
                    return;
                }
            }
            renderAll(otherwise, scope, out);
        }
    }

    private static final class For extends Node {
        private final int line;
        private final String target;
        private final JinjaExpr.Expr iterable;
        private final List<Node> body;

        For(int line, String target, JinjaExpr.Expr iterable, List<Node> body) {
            this.line = line;
            this.target = target;
            this.iterable = iterable;
            this.body = body;
        }

        @Override
        void render(Scope scope, StringBuilder out) {
            Object over = iterable.eval(scope);
            List<Object> items = new ArrayList<Object>();
            try {
                for (Object item : Py.iterate(over)) {
                    items.add(item);
                }
            } catch (IllegalStateException refused) {
                throw new JinjaException(scope.template, line, refused.getMessage());
            }
            for (int i = 0; i < items.size(); i++) {
                Scope turn = new Scope(scope, i == items.size() - 1);
                turn.set(target, items.get(i));
                renderAll(body, turn, out);
            }
        }
    }

    /** {@code {% set name = value %}}. */
    private static final class Assign extends Node {
        private final String target;
        private final JinjaExpr.Expr value;

        Assign(String target, JinjaExpr.Expr value) {
            this.target = target;
            this.value = value;
        }

        @Override
        void render(Scope scope, StringBuilder out) {
            scope.set(target, value.eval(scope));
        }
    }

    // ------------------------------------------------------------------ reading

    /** Turns the lexer's tokens into nodes, pairing every if and for with its end. */
    private static final class Parser {
        private final String name;
        private final List<JinjaLexer.Token> tokens;
        private final Map<String, Integer> assigned = new LinkedHashMap<String, Integer>();
        private int at;
        /** How many for loops the token being read stands inside. */
        private int loops;

        Parser(String name, List<JinjaLexer.Token> tokens) {
            this.name = name;
            this.tokens = tokens;
        }

        JinjaTemplate template() {
            return new JinjaTemplate(name, nodes(null, 0), assigned);
        }

        /**
         * The nodes up to the end of the template or, inside an if or a for, up to the tag that
         * ends or continues it - which is left unread, for whoever opened the block.
         *
         * @param opener   {@code "if"} or {@code "for"} inside a block, null at the top
         * @param openedOn the opener's line
         */
        private List<Node> nodes(String opener, int openedOn) {
            List<Node> nodes = new ArrayList<Node>();
            while (at < tokens.size()) {
                JinjaLexer.Token token = tokens.get(at);
                if (token.kind == JinjaLexer.Kind.DATA) {
                    nodes.add(new Text(token.text));
                    at++;
                    continue;
                }
                JinjaExpr.Tag tag = new JinjaExpr.Tag(name, token.text, token.line, loops);
                if (token.kind == JinjaLexer.Kind.VARIABLE) {
                    JinjaExpr.Expr value = tag.expression();
                    tag.end();
                    nodes.add(new Print(token.line, value));
                    at++;
                    continue;
                }
                String keyword = tag.keyword();
                if (CLOSERS.contains(keyword)) {
                    if (opener == null) {
                        throw new JinjaException(name, token.line, "{% " + keyword + " %} "
                                + "without the {% " + (keyword.equals("endfor") ? "for" : "if")
                                + " %} it belongs to");
                    }
                    return nodes;
                }
                at++;
                if (keyword.equals("if")) {
                    nodes.add(conditional(tag, token.line));
                } else if (keyword.equals("for")) {
                    nodes.add(loop(tag, token.line));
                } else if (keyword.equals("set")) {
                    nodes.add(assignment(tag, token.line));
                } else {
                    throw new JinjaException(name, token.line, "{% " + keyword + " %} is not "
                            + "implemented: ODK " + OdkPin.VERSION + "'s templates use only if, "
                            + "for and set");
                }
            }
            if (opener != null) {
                throw new JinjaException(name, openedOn, "the {% " + opener + " %} here is never "
                        + "closed");
            }
            return nodes;
        }

        /** The tag that stopped {@link #nodes}: read, and stepped over. */
        private JinjaExpr.Tag closing() {
            JinjaLexer.Token token = tokens.get(at++);
            return new JinjaExpr.Tag(name, token.text, token.line, loops);
        }

        private Node conditional(JinjaExpr.Tag tag, int line) {
            List<JinjaExpr.Expr> conditions = new ArrayList<JinjaExpr.Expr>();
            List<List<Node>> branches = new ArrayList<List<Node>>();
            List<Node> otherwise = Collections.emptyList();
            conditions.add(tag.expression());
            tag.end();
            boolean inElse = false;
            while (true) {
                List<Node> branch = nodes("if", line);
                int closedOn = tokens.get(at).line;
                JinjaExpr.Tag closing = closing();
                String keyword = closing.keyword();
                if (inElse) {
                    otherwise = branch;
                } else {
                    branches.add(branch);
                }
                if (keyword.equals("endif")) {
                    closing.end();
                    return new If(conditions, branches, otherwise);
                }
                if (keyword.equals("elif") && !inElse) {
                    conditions.add(closing.expression());
                    closing.end();
                } else if (keyword.equals("else") && !inElse) {
                    closing.end();
                    inElse = true;
                } else {
                    throw new JinjaException(name, closedOn, "{% " + keyword + " %} where the "
                            + "{% if %} of line " + line + " expects its {% endif %}");
                }
            }
        }

        private Node loop(JinjaExpr.Tag tag, int line) {
            String target = tag.target();
            tag.word("in", "a loop over several names at once is not implemented");
            // Read before the loop counts: the list is worked out outside the loop it feeds.
            JinjaExpr.Expr iterable = tag.expression();
            tag.end();
            note(target, line);
            loops++;
            List<Node> body = nodes("for", line);
            loops--;
            int closedOn = tokens.get(at).line;
            JinjaExpr.Tag closing = closing();
            String keyword = closing.keyword();
            if (!keyword.equals("endfor")) {
                throw new JinjaException(name, closedOn, keyword.equals("else")
                        ? "{% else %} for a {% for %} that has no items is not implemented"
                        : "{% " + keyword + " %} where the {% for %} of line " + line
                                + " expects its {% endfor %}");
            }
            closing.end();
            return new For(line, target, iterable, body);
        }

        private Node assignment(JinjaExpr.Tag tag, int line) {
            String target = tag.target();
            tag.sign("=", "a {% set %} block and several names at once are not implemented");
            JinjaExpr.Expr value = tag.expression();
            tag.end();
            note(target, line);
            return new Assign(target, value);
        }

        private void note(String target, int line) {
            if (!assigned.containsKey(target)) {
                assigned.put(target, line);
            }
        }
    }
}
