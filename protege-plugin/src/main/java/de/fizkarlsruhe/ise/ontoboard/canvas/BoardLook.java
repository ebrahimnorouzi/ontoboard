package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the board draws each kind of box and line (1.155.0): a box's fill, its outline and its
 * shape, a line's colour and its dash, and how faded an imported term is. The defaults are
 * 1.154.0's board, style for style; a person's look and a project's are kept as what differs from
 * them - {@code obClass.fill=#FFEEDD} - and {@link SchemaStyles#install(com.mxgraph.view.mxGraph,
 * BoardLook)} builds the stylesheet from the whole.
 *
 * <p>A class's outline is its namespace's colour on the board (see {@link PrefixColours}); the
 * outline here is the one the key shows. A value no board can draw - a colour that is no
 * {@code #RRGGBB}, a shape there is none of - is never taken, so a sidecar edited by hand cannot
 * break a board.
 */
public final class BoardLook {

    /** The shapes a box can take. */
    public static final List<String> SHAPES = Collections.unmodifiableList(
            Arrays.asList("rectangle", "rounded", "ellipse", "hexagon"));

    /** The box kinds, in the order the settings list them: style, fill, outline, shape. */
    static final String[][] BOXES = {
        {SchemaStyles.CLASS, "#FFFFFF", "#2D6FBF", "rounded"},
        {SchemaStyles.INDIVIDUAL, "#F3EEFB", "#6B4FA0", "rounded"},
        {SchemaStyles.DATATYPE, "#EAF5EF", "#2F7A4C", "ellipse"},
        {SchemaStyles.LITERAL, "#FFF6DB", "#8A6A00", "rectangle"},
        {SchemaStyles.EXPRESSION, "#F2F2F4", "#6B6B76", "rectangle"},
        {SchemaStyles.OBJECT_PROPERTY_NODE, "#FFEFDC", "#B35C00", "hexagon"},
        {SchemaStyles.DATA_PROPERTY_NODE, "#E6F2F4", "#0E6E78", "hexagon"},
        {SchemaStyles.STICKY_NOTE, "#FFF3B0", "#A8871C", "rectangle"},
        {SchemaStyles.FRAME, "none", "#2D6FBF", "rectangle"},
    };

    /** The line kinds, in the order the settings list them: style, colour, dash ("" solid). */
    static final String[][] LINES = {
        {SchemaStyles.SUBCLASS, "#3B4756", ""},
        {SchemaStyles.OBJECT_PROPERTY, "#2D6FBF", ""},
        {SchemaStyles.ONLY, "#8E2D6B", "8 4"},
        {SchemaStyles.COUNT, "#1F7A3A", "2 3"},
        {SchemaStyles.NOT, "#7A7A7A", "3 3"},
        {SchemaStyles.DATA_PROPERTY, "#0E6E78", ""},
        {SchemaStyles.TYPE, "#6B4FA0", "2 4"},
        {SchemaStyles.SUB_PROPERTY, "#B35C00", ""},
        {SchemaStyles.DISJOINT, "#B3261E", "4 4"},
        {SchemaStyles.INVERSE, "#B35C00", "6 3"},
        {SchemaStyles.DOMAIN_RANGE, "#C08A4B", "8 4"},
        {SchemaStyles.RULE_PART, "#5B6B8C", "2 3"},
        {SchemaStyles.INFERRED_SUBCLASS, "#707B89", "1 5"},
        {SchemaStyles.INFERRED_TYPE, "#707B89", "1 5"},
    };

    /** How faded an imported term is drawn, as a percentage: the key. */
    public static final String IMPORTED_OPACITY = SchemaStyles.IMPORTED + ".opacity";

    /** Which arrangement a board lays itself out with: SECTIONS (the Outline), HIERARCHICAL, ... */
    public static final String ARRANGEMENT = "board.arrangement";

    /** Which way a Hierarchical tree runs: down, or right. */
    public static final String DIRECTION = "board.direction";

    /** How far apart an arrangement stands boxes: compact, normal or roomy. */
    public static final String SPACING = "board.spacing";

    /** Whether a property is a row and its arrows, UML-like, or a box of its own: rows or boxes. */
    public static final String PROPERTIES = "board.properties";

    /** The values each of the four board settings takes, the default first. */
    static final String[][] BOARD = {
        {ARRANGEMENT, "SECTIONS", "HIERARCHICAL", "ORGANIC", "CIRCLE", "GRID"},
        {DIRECTION, "down", "right"},
        {SPACING, "normal", "compact", "roomy"},
        {PROPERTIES, "rows", "boxes"},
    };

    private static final BoardLook DEFAULTS = build();

    /** Every key, in the settings' order, to its value. */
    private final Map<String, String> values;

    private BoardLook(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    private static BoardLook build() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String[] box : BOXES) {
            values.put(box[0] + ".fill", box[1]);
            values.put(box[0] + ".stroke", box[2]);
            values.put(box[0] + ".shape", box[3]);
        }
        for (String[] line : LINES) {
            values.put(line[0] + ".colour", line[1]);
            values.put(line[0] + ".line", line[2]);
        }
        values.put(IMPORTED_OPACITY, String.valueOf(SchemaStyles.IMPORTED_OPACITY));
        for (String[] setting : BOARD) {
            values.put(setting[0], setting[1]);
        }
        return new BoardLook(values);
    }

    /** 1.154.0's board. */
    public static BoardLook defaults() {
        return DEFAULTS;
    }

    /**
     * The look a board is drawn in: the defaults, a person's own over them, and a project's over
     * both - each only where it says something (decided 2026-11-19: per user, a project can
     * override).
     */
    public static BoardLook effective(Map<String, String> person, Map<String, String> project) {
        return DEFAULTS.withChanges(person).withChanges(project);
    }

    /** Changes as text, a key and its value to a line: how a person's look is kept. */
    public static String format(Map<String, String> changes) {
        StringBuilder text = new StringBuilder();
        if (changes != null) {
            for (Map.Entry<String, String> change : changes.entrySet()) {
                text.append(change.getKey()).append('=').append(change.getValue()).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * Changes read from {@link #format}'s text; a line that is no {@code key=value} is skipped, and
     * what the values say is left to {@link #withChanges}.
     */
    public static Map<String, String> parse(String text) {
        Map<String, String> changes = new LinkedHashMap<String, String>();
        if (text == null) {
            return changes;
        }
        for (String line : text.split("\r?\n")) {
            int equals = line.indexOf('=');
            if (equals > 0 && !line.startsWith("#")) {
                changes.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        return changes;
    }

    /** A value, or null for a key no kind has. */
    public String get(String key) {
        return values.get(key);
    }

    /** Every key, in the order the settings list them. */
    public List<String> keys() {
        return Collections.unmodifiableList(new java.util.ArrayList<String>(values.keySet()));
    }

    /**
     * This look with one value changed. A key no kind has, or a value no board can draw, changes
     * nothing; a colour is kept in capitals, as the defaults are written.
     */
    public BoardLook with(String key, String value) {
        if (key == null || value == null || !values.containsKey(key)) {
            return this;
        }
        String drawn = value.trim();
        if (isColour(key) && drawn.matches("#[0-9A-Fa-f]{6}")) {
            drawn = drawn.toUpperCase(java.util.Locale.ROOT);
        }
        if (!drawable(key, drawn) || drawn.equals(values.get(key))) {
            return this;
        }
        Map<String, String> changed = new LinkedHashMap<String, String>(values);
        changed.put(key, drawn);
        return new BoardLook(changed);
    }

    /** This look with each of these values that a board can draw. */
    public BoardLook withChanges(Map<String, String> changes) {
        BoardLook look = this;
        if (changes != null) {
            for (Map.Entry<String, String> change : changes.entrySet()) {
                look = look.with(change.getKey(), change.getValue());
            }
        }
        return look;
    }

    /** What differs from the defaults, in the settings' order: what is kept. */
    public Map<String, String> changes() {
        Map<String, String> changes = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (!value.getValue().equals(DEFAULTS.values.get(value.getKey()))) {
                changes.put(value.getKey(), value.getValue());
            }
        }
        return changes;
    }

    private static boolean isColour(String key) {
        return key.endsWith(".fill") || key.endsWith(".stroke") || key.endsWith(".colour");
    }

    private static boolean drawable(String key, String value) {
        if (isColour(key)) {
            return value.matches("#[0-9A-F]{6}")
                    || key.equals(SchemaStyles.FRAME + ".fill") && value.equals("none");
        }
        if (key.endsWith(".shape")) {
            return SHAPES.contains(value);
        }
        if (key.endsWith(".line")) {
            return value.matches("([1-9][0-9]?( [1-9][0-9]?)*)?");
        }
        for (String[] setting : BOARD) {
            if (setting[0].equals(key)) {
                return Arrays.asList(setting).subList(1, setting.length).contains(value);
            }
        }
        if (key.equals(IMPORTED_OPACITY)) {
            try {
                int percent = Integer.parseInt(value);
                return percent >= 20 && percent <= 100;
            } catch (NumberFormatException notANumber) {
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BoardLook && values.equals(((BoardLook) other).values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "BoardLook" + changes();
    }
}
