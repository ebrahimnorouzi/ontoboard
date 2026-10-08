package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A pattern that has just been imported, waiting for the canvas to notice.
 *
 * <p>The pattern browser and the canvas are two unrelated Protege components: one is a menu
 * action that opens a dialog, the other is a view that may not even be on screen. The browser
 * knows which pattern was chosen and which terms went in; the canvas is the only thing that can
 * draw them. Nothing in Protege's API carries "I just imported these eight IRIs, and they are
 * called Agent Role" from one to the other.
 *
 * <p>So this is a one-slot handover: the import leaves a note, and the canvas picks it up the
 * next time it refreshes - which an ontology change triggers anyway, so in practice that is
 * immediately.
 *
 * <p><b>Why a single slot rather than a queue.</b> Two imports before a single refresh should
 * leave the second pattern framed, not both half-framed: the terms of the first are on the board
 * by then and would be reported as already placed, producing an empty frame with a name. One slot
 * is also one thing to reason about when the canvas is closed, which is the common case - the
 * note is simply replaced, and nothing accumulates.
 *
 * <p><b>Why it is consumed rather than read.</b> {@link #take()} clears the slot, so a refresh
 * for any other reason - an edit, a reasoner run, switching tab - cannot re-frame a pattern that
 * was already framed, moving the user's arrangement out from under them.
 */
public final class PatternArrival {

    private PatternArrival() {
    }

    /** What arrived. */
    public static final class Arrival {
        private final String label;
        private final List<String> iris;

        Arrival(String label, List<String> iris) {
            this.label = label;
            this.iris = Collections.unmodifiableList(new ArrayList<String>(iris));
        }

        /** The pattern's name, which becomes the frame's label. */
        public String getLabel() {
            return label;
        }

        /** The terms that were imported, in the order the pattern lists them. */
        public List<String> getIris() {
            return iris;
        }
    }

    private static volatile Arrival waiting;

    /**
     * Records that a pattern's terms have just gone into the ontology.
     *
     * <p>Called whether or not the canvas is open. A note nobody collects costs one object and is
     * replaced by the next import.
     */
    public static void imported(String label, List<String> iris) {
        if (iris == null || iris.isEmpty()) {
            return;
        }
        waiting = new Arrival(label, iris);
    }

    /** The waiting arrival, clearing it. Null when there is none. */
    public static Arrival take() {
        Arrival held = waiting;
        waiting = null;
        return held;
    }

    /** Whether anything is waiting, without consuming it. For tests. */
    public static boolean isWaiting() {
        return waiting != null;
    }

    /** Drops anything waiting. For tests, and for a canvas that is closing. */
    public static void forget() {
        waiting = null;
    }
}
