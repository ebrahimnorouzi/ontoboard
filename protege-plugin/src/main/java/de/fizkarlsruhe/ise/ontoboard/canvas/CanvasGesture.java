package de.fizkarlsruhe.ise.ontoboard.canvas;

/**
 * What a plain left-drag on empty board does, and what the other gesture falls back to.
 *
 * <p>This has now been decided twice in opposite directions, so it is worth writing down rather
 * than inverting a third time. Before 1.68.0 a drag panned, and selecting several terms needed
 * Ctrl or Shift. 1.68.0 inverted it - a drag selected a region, and panning moved to space, the
 * middle button and the right - on the reasoning that selecting several terms is the commonest
 * thing a person does on a board. In use it is not: a board is bigger than the window far more
 * often than a selection spans several terms, so the gesture people reached for first was the one
 * that had been taken away.
 *
 * <p>So it is a mode now, and neither gesture is behind a doc comment. Panning is the default and
 * the thing a plain drag does; a marquee is a modifier away, or a mode away for anybody doing a
 * lot of it. The two are exact mirrors - whichever mode you are in, the other gesture is on
 * Shift or Ctrl - which is the only arrangement that does not have to be memorised.
 *
 * <p>No Swing here on purpose: the decision is four booleans and is wrong in ways a unit test
 * catches, where the two call sites that use it ({@code CollaborativeGraphComponent.isPanningEvent}
 * and the rubberband in {@code SchemaCanvasView.installSelection}) are not testable at all. Having
 * them share one function is also what stops them from disagreeing and both claiming the drag.
 */
public final class CanvasGesture {

    /** What a plain left-drag on empty board does. */
    public enum Mode {
        /** Moves the view. The default, and what a drag did before 1.68.0. */
        PAN("Pan", "Drag moves the board. Shift-drag or Ctrl-drag selects a region instead."),

        /** Draws a selection rectangle. What a drag did between 1.68.0 and 1.73.0. */
        SELECT("Select", "Drag selects a region. Shift-drag, Ctrl-drag or space-drag moves the "
                + "board instead.");

        private final String label;
        private final String help;

        Mode(String label, String help) {
            this.label = label;
            this.help = help;
        }

        /** One word, for a button. */
        public String getLabel() {
            return label;
        }

        /** Both gestures in a sentence, for its tooltip - a mode nobody can see is a fault. */
        public String getHelp() {
            return help;
        }

        /** The other one. */
        public Mode other() {
            return this == PAN ? SELECT : PAN;
        }
    }

    /**
     * The mode a canvas starts in.
     *
     * <p>Panning, because a board is bigger than the window more often than a selection spans
     * several terms, and because it is what the gesture did for the first sixty-seven releases.
     */
    public static final Mode DEFAULT = Mode.PAN;

    /** Where the choice is remembered, so it survives a restart. */
    public static final String PREFERENCE_KEY = "ontoboard.canvas.gesture";

    private CanvasGesture() {
    }

    /**
     * Whether a left press should start a pan.
     *
     * <p>Never when there is a cell under the cursor: dragging a term moves the term, in both
     * modes, and no modifier changes that. A gesture that sometimes moved a node and sometimes
     * moved the board depending on a key would make every drag a gamble.
     *
     * @param additive whether Shift or Ctrl is down - the keys that mean "add to a selection"
     */
    public static boolean leftDragPans(Mode mode, boolean spaceHeld, boolean additive,
            boolean onCell) {
        if (onCell) {
            return false;
        }
        if (spaceHeld) {
            // Space pans in both modes. It is the one gesture that has never changed meaning,
            // and the hint shown on first run names it.
            return true;
        }
        return mode(mode) == Mode.PAN && !additive;
    }

    /**
     * Whether a left press should start a marquee.
     *
     * <p>The exact complement of {@link #leftDragPans} on empty board, which is checked by a test
     * over every combination rather than by reading these two methods side by side.
     */
    public static boolean leftDragSelects(Mode mode, boolean spaceHeld, boolean additive,
            boolean onCell) {
        if (onCell || spaceHeld) {
            return false;
        }
        return mode(mode) == Mode.SELECT || additive;
    }

    /** The mode named by a stored preference, falling back to the default. */
    public static Mode byName(String name) {
        for (Mode mode : Mode.values()) {
            if (mode.name().equalsIgnoreCase(name == null ? "" : name.trim())) {
                return mode;
            }
        }
        return DEFAULT;
    }

    private static Mode mode(Mode mode) {
        return mode == null ? DEFAULT : mode;
    }
}
