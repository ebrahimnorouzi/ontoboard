package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Patterns that have just been imported, waiting for the canvas to notice.
 *
 * <p>The pattern browser and the canvas are two unrelated Protege components: one is a menu
 * action that opens a dialog, the other is a view that may not even be on screen. The browser
 * knows which pattern was chosen and which terms went in; the canvas is the only thing that can
 * draw them. Nothing in Protege's API carries "I just imported these eight IRIs, and they are
 * called Agent Role" from one to the other.
 *
 * <p>So this is a handover: the import leaves a note, and the canvas picks it up the next time it
 * refreshes - which an ontology change triggers anyway, so with the board open that is
 * immediately, and with it closed it is when the board is next opened.
 *
 * <p><b>A short queue, not one slot.</b> One slot meant the second of two imports replaced the
 * first. With the board open that never mattered - each import refreshes it and the note is
 * collected before the next - but with the board closed it lost a pattern: measured in Protege
 * 5.6.9 on a Mac, importing Componency and then Classification from the Entities tab and opening
 * the board afterwards framed Classification, and Componency was nowhere on the board at all. It
 * is the common case, too: the library is a menu item, usable from any tab. So every import is
 * kept until the board takes it, at most {@link #HELD} of them, oldest dropped first; importing
 * the same pattern into the same ontology again replaces its note, since the later choice of
 * terms is the one that went in.
 *
 * <p><b>Each note names the ontology it went into</b>, so a board showing another ontology
 * leaves it alone, even if that ontology happens to import the same pattern.
 *
 * <p><b>A note is removed once it is framed</b>, so a refresh for any other reason - an edit, a
 * reasoner run, switching tab - cannot re-frame a pattern that was already framed, moving the
 * user's arrangement out from under them.
 */
public final class PatternArrival {

    /** How many imports are kept for a board that is not open. */
    static final int HELD = 16;

    private PatternArrival() {
    }

    /** What arrived. */
    public static final class Arrival {
        private final String ontology;
        private final String label;
        private final List<String> iris;

        Arrival(String ontology, String label, List<String> iris) {
            this.ontology = ontology;
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

        /**
         * Whether this went into {@code ontology}, as {@link #idOf} identifies it. A note left
         * without saying where it went is for any board.
         */
        public boolean isFor(String ontology) {
            return this.ontology == null || this.ontology.equals(ontology);
        }
    }

    private static final List<Arrival> WAITING = new ArrayList<Arrival>();

    /**
     * How a note and a board identify an ontology: its ontology IRI and version IRI, or for an
     * anonymous one the identifier the OWL API gave it.
     */
    public static String idOf(org.semanticweb.owlapi.model.OWLOntology ontology) {
        return ontology == null ? null : ontology.getOntologyID().toString();
    }

    /** Records that a pattern's terms have just gone into some ontology. */
    public static void imported(String label, List<String> iris) {
        imported(null, label, iris);
    }

    /**
     * Records that a pattern's terms have just gone into {@code ontology}, as {@link #idOf}
     * identifies it.
     *
     * <p>Called whether or not the canvas is open.
     */
    public static synchronized void imported(String ontology, String label, List<String> iris) {
        if (iris == null || iris.isEmpty()) {
            return;
        }
        Arrival arrival = new Arrival(ontology, label, iris);
        for (Iterator<Arrival> each = WAITING.iterator(); each.hasNext();) {
            Arrival earlier = each.next();
            if (same(earlier.label, label) && same(earlier.ontology, ontology)) {
                each.remove();
            }
        }
        WAITING.add(arrival);
        while (WAITING.size() > HELD) {
            WAITING.remove(0);
        }
    }

    /** Everything waiting, oldest first, left in place. */
    public static synchronized List<Arrival> waiting() {
        return new ArrayList<Arrival>(WAITING);
    }

    /** Removes {@code arrival}, once it is framed. False if it was not waiting. */
    public static synchronized boolean take(Arrival arrival) {
        return WAITING.remove(arrival);
    }

    /** The oldest waiting arrival, removing it. Null when there is none. */
    public static synchronized Arrival take() {
        return WAITING.isEmpty() ? null : WAITING.remove(0);
    }

    /** The oldest waiting arrival, left in place. Null when there is none. */
    public static synchronized Arrival peek() {
        return WAITING.isEmpty() ? null : WAITING.get(0);
    }

    /** Whether anything is waiting, without consuming it. */
    public static synchronized boolean isWaiting() {
        return !WAITING.isEmpty();
    }

    /** Drops everything waiting. For tests. */
    public static synchronized void forget() {
        WAITING.clear();
    }

    private static boolean same(String one, String other) {
        return one == null ? other == null : one.equals(other);
    }
}
