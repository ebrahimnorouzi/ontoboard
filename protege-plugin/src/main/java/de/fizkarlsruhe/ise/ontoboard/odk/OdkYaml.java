package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Reading and editing a project's {@code <id>-odk.yaml} without rewriting it.
 *
 * <p>This is the file the whole ODK workflow turns on, and OntoBoard could neither show it nor
 * change it: the wizard wrote it once and afterwards five scalars were read by one regex. On a
 * real ODK repository even those five are unreachable, because the regenerator refuses such a
 * project before reading anything - so exactly one key, {@code title}, was ever read.
 *
 * <p><b>Edits are spliced, not re-serialised.</b> That is the whole design and it was chosen by
 * measurement. Loading a real file and dumping it back rewrites 28 of its 41 lines - 13 even
 * with the indentation options tuned - normalising a flow sequence, a three-space nested indent,
 * CRLF to LF and a missing final newline. A one-word change would arrive in a pull request as a
 * whole-file diff, which is the quiet subtraction this project has now fixed twice. Locating the
 * value with the parser and replacing exactly those characters in the original text gives a
 * one-line diff.
 *
 * <p><b>Only the snakeyaml API that two different copies agree on.</b> snakeyaml arrives twice:
 * once as a compile dependency of robot-core, and once bundled inside
 * {@code protege-editor-owl}, which wins on the classpath and is older. The newer accessors -
 * {@code ScalarNode.getScalarStyle()} returning an enum, {@code LoaderOptions.setProcessComments}
 * - do not exist in Protege's copy, so this uses the legacy {@code getStyle()} and no
 * {@code LoaderOptions} at all. That it compiles here is the proof: the build resolves Protege's
 * copy, so anything that compiles works against either. The same two-copies-one-package trap
 * that {@code Explanations} documents for {@code ProtegeExplanationOrderer}.
 *
 * <p>What it refuses to edit matters as much as what it edits. A sequence, a nested mapping and
 * a block scalar are reported read-only, because replacing their span would delete the whole
 * structure; a file with a duplicate top-level key is refused outright, because YAML accepts one
 * silently and an edit would patch whichever copy the walk reached while ODK reads the other.
 */
public final class OdkYaml {

    /** Why an entry cannot be edited here, or null when it can. */
    public enum Editable {
        /** A plain scalar: safe to replace in place. */
        YES(null),

        /** A list. Replacing its span would delete every item. */
        SEQUENCE("a list - edit it in a text editor, so no item is lost"),

        /** A nested block, such as import_group. */
        MAPPING("a nested block - edit it in a text editor, so nothing inside it is lost"),

        /** A literal or folded scalar, whose span is the indicator and the whole body. */
        BLOCK("a block of text - its span covers every indented line, so replacing it here "
                + "would delete the block");

        private final String reason;

        Editable(String reason) {
            this.reason = reason;
        }

        /** What to tell the user, or null when the value is editable. */
        public String getReason() {
            return reason;
        }

        public boolean isEditable() {
            return reason == null;
        }
    }

    /** One value in the file, at any depth. */
    public static final class Entry {
        private final String key;
        private final String path;
        private final int depth;
        private final String value;
        private final int line;
        private final Editable editable;

        Entry(String key, String path, int depth, String value, int line, Editable editable) {
            this.key = key;
            this.path = path;
            this.depth = depth;
            this.value = value;
            this.line = line;
            this.editable = editable;
        }

        /** The leaf name, for display: {@code fail_on}, not {@code robot_report.fail_on}. */
        public String getKey() {
            return key;
        }

        /**
         * How to address this value: {@code robot_report.fail_on}, {@code export_formats[1]}.
         *
         * <p>A top-level key is its own path, so anything written against the old
         * key-only API keeps working.
         */
        public String getPath() {
            return path;
        }

        /** 0 for a top-level key, 1 for something one block in. For indenting a list. */
        public int getDepth() {
            return depth;
        }

        /** The scalar's text, or a short description for a structure. */
        public String getValue() {
            return value;
        }

        /** 1-based, for showing beside the key. */
        public int getLine() {
            return line;
        }

        public Editable getEditable() {
            return editable;
        }

        @Override
        public String toString() {
            return key + ": " + value;
        }
    }

    /** A file this class will not touch, and why. */
    public static final class UnreadableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnreadableException(String message) {
            super(message);
        }
    }

    private OdkYaml() {
    }

    /**
     * Every top-level key, in file order.
     *
     * @throws UnreadableException when the file is not a YAML mapping, or declares a key twice
     */
    public static List<Entry> entriesIn(String text) {
        List<Entry> entries = new ArrayList<Entry>();
        collect(rootOf(text), "", 0, entries);
        return Collections.unmodifiableList(entries);
    }

    /**
     * Walks the whole document, not just the top level.
     *
     * <p>The reason this was ever shallow is worth stating, because it looked like a limit of
     * the technique and was not. Editing splices a value's own span out of the original text,
     * and a scalar's span is exactly its own characters wherever it sits - so a scalar two
     * blocks down is no more dangerous to replace than a top-level one. What cannot be spliced
     * is a <em>structure</em>, whose span covers everything inside it; that is still refused.
     *
     * <p>Before this, nine of {@code mwo-odk.yaml}'s thirteen top-level keys were editable and
     * the four structures were dead ends - which is where most of the custom-import workflow
     * left Prot&eacute;g&eacute;, because {@code import_group} and {@code robot_report} are
     * blocks. Their scalars are now reachable: {@code robot_report.fail_on},
     * {@code import_group.module_type}, {@code export_formats[1]}.
     *
     * <p>List items are scalars too, so an existing entry can be changed. Adding or removing one
     * is not here: that inserts or deletes a line rather than replacing a span, and is a
     * different piece of work.
     */
    private static void collect(Node node, String prefix, int depth, List<Entry> into) {
        if (node instanceof MappingNode) {
            Set<String> seen = new LinkedHashSet<String>();
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode)) {
                    continue;
                }
                String key = ((ScalarNode) tuple.getKeyNode()).getValue();
                if (!seen.add(key)) {
                    // Checked per mapping, not once for the file: two `products` inside
                    // import_group are as ambiguous as two at the top, and an edit would patch
                    // whichever copy the walk reached while ODK reads the other.
                    throw new UnreadableException("This file declares '"
                            + (prefix.isEmpty() ? key : prefix + "." + key) + "' more than once. "
                            + "OntoBoard will not edit a file with a duplicate key, because an "
                            + "edit could change the copy the build does not use. Remove the "
                            + "duplicate in a text editor first.");
                }
                Node value = tuple.getValueNode();
                String path = prefix.isEmpty() ? key : prefix + "." + key;
                into.add(new Entry(key, addressable(key) ? path : "", depth, describe(value),
                        value.getStartMark().getLine() + 1, editabilityOf(value)));
                if (addressable(key)) {
                    collect(value, path, depth + 1, into);
                }
            }
            return;
        }
        if (node instanceof SequenceNode) {
            List<Node> items = ((SequenceNode) node).getValue();
            for (int at = 0; at < items.size(); at++) {
                Node item = items.get(at);
                String path = prefix + "[" + at + "]";
                into.add(new Entry("[" + at + "]", path, depth, describe(item),
                        item.getStartMark().getLine() + 1, editabilityOf(item)));
                collect(item, path, depth + 1, into);
            }
        }
    }

    /**
     * Whether a key can be put in a path unambiguously.
     *
     * <p>A key containing {@code .} or {@code [} would make {@code a.b} mean two things. No ODK
     * configuration has one, and a value that cannot be addressed without ambiguity is shown
     * with an empty path and refused for editing rather than guessed at - the alternative is an
     * edit landing on a different key than the one clicked.
     */
    private static boolean addressable(String key) {
        return key != null && key.indexOf('.') < 0 && key.indexOf('[') < 0
                && key.indexOf(']') < 0;
    }

    /**
     * {@code text} with one key's value replaced, and everything else byte-identical.
     *
     * @throws UnreadableException when the key is absent or its value may not be replaced
     */
    public static String withValue(String text, String path, String newValue) {
        Node value = resolve(rootOf(text), path);
        if (value == null) {
            throw new UnreadableException("This file has no '" + path + "'.");
        }
        {
            Editable editable = editabilityOf(value);
            if (!editable.isEditable()) {
                throw new UnreadableException("'" + path + "' is " + editable.getReason() + ".");
            }
            int from = clamp(text, value.getStartMark().getIndex());
            int to = clamp(text, value.getEndMark().getIndex());
            String replacement = quoted(newValue, (ScalarNode) value);
            if (from == to) {
                // An empty value gives a zero-width span, and it sits BEFORE whatever spaces
                // follow the colon - measured. Inserting there would leave those spaces
                // stranded after the new value. Step past them, then add one only if the colon
                // is not already followed by whitespace, or "description:" gains a value with
                // no space and "description: " gains two.
                while (to < text.length()
                        && (text.charAt(to) == ' ' || text.charAt(to) == '	')) {
                    to++;
                }
                from = to;
                if (from == 0 || (text.charAt(from - 1) != ' ' && text.charAt(from - 1) != '	')) {
                    replacement = " " + replacement;
                }
            }
            return text.substring(0, from) + replacement + text.substring(to);
        }
    }

    /**
     * Adds an item to the end of a list, spliced in rather than rewritten.
     *
     * <p>The gap this closes. Every scalar in the file has been editable at any depth since
     * 1.89.0, and the one thing a user most often wants to do to an ODK YAML - add an import to
     * {@code import_group.products}, add a format to {@code export_formats} - still meant opening
     * a text editor. The documented roadmap has had it at number one ever since.
     *
     * <p><b>Indentation is copied from the list's own last item, never computed.</b> ODK files in
     * the wild indent sequences both ways - flush with the key, and two spaces in - and both are
     * valid YAML. Guessing would reformat somebody's file on the first edit, which is the whole
     * thing splice-not-rewrite exists to avoid. The last item is the one piece of evidence about
     * what this file does, so it is what gets copied.
     *
     * @param path a sequence: {@code export_formats}, {@code import_group.products}
     * @throws UnreadableException if there is no such list, or it is not a list
     */
    public static String appendTo(String text, String path, String newValue) {
        Node node = resolve(rootOf(text), path);
        if (node == null) {
            throw new UnreadableException("This file has no '" + path + "'.");
        }
        if (!(node instanceof SequenceNode)) {
            throw new UnreadableException("'" + path + "' is not a list.");
        }
        List<Node> items = ((SequenceNode) node).getValue();
        if (items.isEmpty()) {
            // Nothing to copy an indent from. An empty sequence is written `key: []` or as a key
            // with nothing under it, and the two need different edits; refusing is honest, and
            // the dialog says to add the first item by hand.
            throw new UnreadableException("'" + path + "' is empty, so there is no existing item "
                    + "to match the indentation of. Add the first one in a text editor.");
        }
        Node last = items.get(items.size() - 1);
        int end = clamp(text, last.getEndMark().getIndex());
        // Past the end of the line the last item finishes on, so a trailing comment stays with it.
        int lineEnd = text.indexOf('\n', end);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        String indent = indentOfLineAt(text, clamp(text, last.getStartMark().getIndex()));
        String marker = dashAt(text, clamp(text, last.getStartMark().getIndex())) ? "- " : "- ";
        String addition = "\n" + indent + marker + scalarFor(newValue);
        return text.substring(0, lineEnd) + addition + text.substring(lineEnd);
    }

    /**
     * Removes one item from a list, with the whole of its line and nothing else.
     *
     * <p>Takes the line the item begins on through to the line its last nested value ends on, so
     * a mapping item - which is what an {@code import_group} product is - goes as one unit rather
     * than leaving its continuation lines orphaned under the item before it.
     *
     * @param path an item in a sequence: {@code export_formats[1]}
     * @throws UnreadableException if the path does not name an item of a list
     */
    public static String removeFrom(String text, String path) {
        int bracket = path == null ? -1 : path.lastIndexOf('[');
        if (bracket < 0) {
            throw new UnreadableException("'" + path + "' does not name an item of a list.");
        }
        Node node = resolve(rootOf(text), path);
        if (node == null) {
            throw new UnreadableException("This file has no '" + path + "'.");
        }
        int start = clamp(text, node.getStartMark().getIndex());
        int lineStart = text.lastIndexOf('\n', Math.max(0, start - 1)) + 1;

        // Bounded by the NEXT item, not by this one's end mark. snakeyaml ends a mapping inside a
        // sequence at the point the following item begins, not at the end of its own last line -
        // so taking "to the end of the line the end mark falls on" swallowed the next item's first
        // line as well. Removing one import product deleted the one after it; the test for that
        // failed on the first run and is the only reason this is written the hard way.
        Node next = siblingAfter(text, path);
        int lineEnd;
        if (next != null) {
            int nextStart = clamp(text, next.getStartMark().getIndex());
            lineEnd = text.lastIndexOf('\n', Math.max(0, nextStart - 1));
        } else {
            lineEnd = text.indexOf('\n', clamp(text, node.getEndMark().getIndex()));
        }
        if (lineEnd < 0) {
            // The last line of the file: take the newline before it instead, so the file does not
            // end up with a trailing blank line where the item used to be.
            return text.substring(0, Math.max(0, lineStart - 1));
        }
        return text.substring(0, lineStart) + text.substring(lineEnd + 1);
    }

    /** The item after the one this path names, or null when it is the last. */
    private static Node siblingAfter(String text, String path) {
        int bracket = path.lastIndexOf('[');
        int close = path.indexOf(']', bracket);
        if (close < 0) {
            return null;
        }
        int which;
        try {
            which = Integer.parseInt(path.substring(bracket + 1, close).trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
        Node parent = resolve(rootOf(text), path.substring(0, bracket));
        if (!(parent instanceof SequenceNode)) {
            return null;
        }
        List<Node> items = ((SequenceNode) parent).getValue();
        return which + 1 < items.size() ? items.get(which + 1) : null;
    }

    /** The leading whitespace of the line the index falls on. */
    private static String indentOfLineAt(String text, int index) {
        int lineStart = text.lastIndexOf('\n', Math.max(0, index - 1)) + 1;
        int at = lineStart;
        while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '	')) {
            at++;
        }
        return text.substring(lineStart, at);
    }

    /** Whether the item at this index is written with a leading dash. */
    private static boolean dashAt(String text, int index) {
        String indent = indentOfLineAt(text, index);
        int lineStart = text.lastIndexOf('\n', Math.max(0, index - 1)) + 1;
        return text.startsWith(indent + "-", lineStart);
    }

    /**
     * A new scalar, quoted only when YAML would otherwise read it as something else.
     *
     * <p>A value added to a list has no original to copy a quoting style from, so this decides.
     * Anything that would parse as a boolean, a number, a null or a date is quoted, because
     * {@code - yes} in an ODK YAML is the boolean true and not the string "yes" - and so is a
     * value carrying a character that would change the structure.
     */
    static String scalarFor(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return "''";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean looksLikeSomethingElse =
                lower.matches("true|false|yes|no|on|off|null|~")
                || text.matches("[-+]?[0-9][0-9_]*(\\.[0-9]*)?([eE][-+]?[0-9]+)?")
                // A colon only separates a key from a value when whitespace follows it, or when it
                // ends the token. Quoting on any colon at all put quotes round every IRI in the
                // file - and an import's mirror_from is always a URL, which is the single most
                // common thing anybody adds to one of these lists. Same for '#': it starts a
                // comment only after whitespace.
                || text.contains(": ") || text.contains(":\t") || text.endsWith(":")
                || text.contains(" #") || text.contains("\t#") || text.startsWith("#")
                || text.indexOf('\n') >= 0
                || text.startsWith("[") || text.startsWith("{") || text.startsWith("*")
                || text.startsWith("&") || text.startsWith("!") || text.startsWith("%")
                || text.startsWith("@") || text.startsWith("`") || text.startsWith("\"")
                || text.startsWith("'");
        if (!looksLikeSomethingElse) {
            return text;
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * The node a path names, or null.
     *
     * <p>Accepts {@code id}, {@code robot_report.fail_on} and {@code export_formats[1]}, and
     * mixtures of the two. A top-level key is a one-step path, so every caller written against
     * the key-only version keeps working unchanged.
     */
    private static Node resolve(Node from, String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        Node at = from;
        for (String step : path.trim().split("\\.")) {
            String name = step;
            // A step may carry indices: products[0], or even [0][1].
            int bracket = name.indexOf('[');
            String indices = bracket < 0 ? "" : name.substring(bracket);
            if (bracket >= 0) {
                name = name.substring(0, bracket);
            }
            if (!name.isEmpty()) {
                at = childOf(at, name);
                if (at == null) {
                    return null;
                }
            }
            for (String index : indexesIn(indices)) {
                if (!(at instanceof SequenceNode)) {
                    return null;
                }
                List<Node> items = ((SequenceNode) at).getValue();
                int which;
                try {
                    which = Integer.parseInt(index);
                } catch (NumberFormatException notANumber) {
                    return null;
                }
                if (which < 0 || which >= items.size()) {
                    return null;
                }
                at = items.get(which);
            }
        }
        return at;
    }

    private static Node childOf(Node node, String key) {
        if (!(node instanceof MappingNode)) {
            return null;
        }
        for (NodeTuple tuple : ((MappingNode) node).getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode
                    && key.equals(((ScalarNode) tuple.getKeyNode()).getValue())) {
                return tuple.getValueNode();
            }
        }
        return null;
    }

    /** The numbers in a run of {@code [0][2]}, in order. */
    private static List<String> indexesIn(String brackets) {
        List<String> found = new ArrayList<String>();
        int at = 0;
        while (at < brackets.length()) {
            int open = brackets.indexOf('[', at);
            int close = open < 0 ? -1 : brackets.indexOf(']', open);
            if (open < 0 || close < 0) {
                break;
            }
            found.add(brackets.substring(open + 1, close));
            at = close + 1;
        }
        return found;
    }

    /**
     * Whether a value is safe to write bare, or must be quoted.
     *
     * <p>A value carrying {@code #}, a colon-space, a leading or trailing space, or looking like
     * a boolean or a null, changes what the file means when written plain - and the user finds
     * out when CI fails rather than when they click OK. A value that was quoted stays quoted.
     */
    static String quoted(String value, ScalarNode original) {
        String text = value == null ? "" : value;
        Character style = original == null ? null : original.getStyle();
        boolean wasQuoted = style != null
                && (style.charValue() == '\'' || style.charValue() == '"');
        boolean needsQuotes = text.isEmpty()
                || text.contains("#")
                || text.contains(": ")
                || text.endsWith(":")
                || !text.equals(text.trim())
                || looksLikeSomethingElse(text);
        if (wasQuoted || needsQuotes) {
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return text;
    }

    /** Words YAML reads as a boolean or a null rather than as text. */
    private static boolean looksLikeSomethingElse(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.equals("true") || lower.equals("false") || lower.equals("yes")
                || lower.equals("no") || lower.equals("on") || lower.equals("off")
                || lower.equals("null") || lower.equals("~");
    }

    /**
     * A mark index, clamped to the text.
     *
     * <p>{@code Mark.getIndex()} is a <em>char</em> index, measured against the copy of
     * snakeyaml this build resolves: on a file containing an emoji, 39 chars and 38 codepoints,
     * the raw index selects {@code mwo} correctly while converting it through
     * {@code offsetByCodePoints} selects {@code wo} and a newline.
     *
     * <p>Worth recording because the first version of this class did convert, on the reasonable
     * belief that {@code Mark} counts codepoints - it is built over an {@code int[]} buffer, and
     * some versions document it that way. The conversion introduced exactly the off-by-one
     * corruption it was written to prevent, and a test with an emoji in it is what caught it.
     * Measuring beat reasoning, again.
     */
    static int clamp(String text, int index) {
        return Math.max(0, Math.min(index, text.length()));
    }

    private static MappingNode rootOf(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new UnreadableException("The configuration file is empty.");
        }
        Node root;
        try {
            // No LoaderOptions: comment processing is off by default, and the setter for it
            // exists only in the newer snakeyaml. Comments survive regardless, because the file
            // is spliced rather than re-serialised.
            root = new Yaml().compose(new java.io.StringReader(text));
        } catch (RuntimeException notYaml) {
            throw new UnreadableException("This file could not be read as YAML: "
                    + notYaml.getMessage());
        }
        if (!(root instanceof MappingNode)) {
            throw new UnreadableException("This file is not a YAML mapping, so it has no keys "
                    + "to show.");
        }
        return (MappingNode) root;
    }

    private static Editable editabilityOf(Node value) {
        if (value instanceof SequenceNode) {
            return Editable.SEQUENCE;
        }
        if (value instanceof MappingNode) {
            return Editable.MAPPING;
        }
        if (value instanceof ScalarNode) {
            Character style = ((ScalarNode) value).getStyle();
            // A literal or folded scalar's span covers the indicator and every indented line
            // under it, so a one-line replacement would delete the whole block.
            if (style != null && (style.charValue() == '|' || style.charValue() == '>')) {
                return Editable.BLOCK;
            }
            return Editable.YES;
        }
        return Editable.MAPPING;
    }

    /** What to show in the value column for something that is not a plain scalar. */
    private static String describe(Node value) {
        if (value instanceof SequenceNode) {
            int size = ((SequenceNode) value).getValue().size();
            return "(" + size + (size == 1 ? " item)" : " items)");
        }
        if (value instanceof MappingNode) {
            int size = ((MappingNode) value).getValue().size();
            return "(" + size + (size == 1 ? " key)" : " keys)");
        }
        if (value instanceof ScalarNode) {
            return ((ScalarNode) value).getValue();
        }
        return "";
    }
}
