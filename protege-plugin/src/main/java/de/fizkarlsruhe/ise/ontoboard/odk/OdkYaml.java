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

    /** One top-level key, as it appears in the file. */
    public static final class Entry {
        private final String key;
        private final String value;
        private final int line;
        private final Editable editable;

        Entry(String key, String value, int line, Editable editable) {
            this.key = key;
            this.value = value;
            this.line = line;
            this.editable = editable;
        }

        public String getKey() {
            return key;
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
        MappingNode root = rootOf(text);
        List<Entry> entries = new ArrayList<Entry>();
        Set<String> seen = new LinkedHashSet<String>();
        for (NodeTuple tuple : root.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode)) {
                continue;
            }
            String key = ((ScalarNode) tuple.getKeyNode()).getValue();
            if (!seen.add(key)) {
                throw new UnreadableException("This file declares '" + key + "' more than once. "
                        + "OntoBoard will not edit a file with a duplicate key, because an edit "
                        + "could change the copy the build does not use. Remove the duplicate in "
                        + "a text editor first.");
            }
            Node value = tuple.getValueNode();
            entries.add(new Entry(key, describe(value), value.getStartMark().getLine() + 1,
                    editabilityOf(value)));
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * {@code text} with one key's value replaced, and everything else byte-identical.
     *
     * @throws UnreadableException when the key is absent or its value may not be replaced
     */
    public static String withValue(String text, String key, String newValue) {
        MappingNode root = rootOf(text);
        for (NodeTuple tuple : root.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode)
                    || !key.equals(((ScalarNode) tuple.getKeyNode()).getValue())) {
                continue;
            }
            Node value = tuple.getValueNode();
            Editable editable = editabilityOf(value);
            if (!editable.isEditable()) {
                throw new UnreadableException("'" + key + "' is " + editable.getReason() + ".");
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
        throw new UnreadableException("This file has no top-level '" + key + "' key.");
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
