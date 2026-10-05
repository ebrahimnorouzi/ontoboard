package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which cells have their tooltip kept open, and what each card says.
 *
 * <p>OntoGraf has {@code PinTooltipsAction}, which stops a tooltip vanishing when the pointer
 * leaves. The reason to want it is comparing: OntoBoard's tooltips disappear on exit, so holding
 * two terms side by side - what each is a subclass of, what each is disjoint from - is a matter
 * of memory.
 *
 * <p>Separate from the painting because the decisions are here and they are the part that can be
 * wrong without anybody noticing: which cells are pinned, what order they stack in, when a pin
 * stops being valid. {@link PinnedTooltipLayer} only draws what this says.
 */
public final class PinnedTooltips {

    /**
     * How many can be open at once.
     *
     * <p>Four is enough to compare and few enough to still see the diagram. Without a limit a
     * stray modifier-click spree covers the board, and the way out of that is not obvious -
     * every card is sitting on top of the thing you would click to unpin it.
     */
    public static final int MOST_PINNED = 4;

    /** Characters per line before a card wraps. Wider than the hover tooltip; it is a panel. */
    static final int WRAP_AT = 52;

    /** One open card. */
    public static final class Card {
        private final String cellId;
        private final List<String> lines;

        Card(String cellId, List<String> lines) {
            this.cellId = cellId;
            this.lines = Collections.unmodifiableList(lines);
        }

        public String getCellId() {
            return cellId;
        }

        /** The tooltip's text, already wrapped. */
        public List<String> getLines() {
            return lines;
        }

        @Override
        public String toString() {
            return cellId + lines;
        }
    }

    private final Set<String> pinned = new LinkedHashSet<String>();

    /**
     * Pins the cell, or unpins it if it is already pinned.
     *
     * <p>A toggle rather than separate verbs, because it is driven by one gesture on the cell
     * itself and the state is visible - the card is either there or it is not.
     *
     * @return true if the cell is pinned afterwards
     */
    public boolean toggle(String cellId) {
        if (cellId == null || cellId.isEmpty()) {
            return false;
        }
        if (pinned.remove(cellId)) {
            return false;
        }
        // The oldest goes, not the newest: the card just asked for is the one wanted.
        while (pinned.size() >= MOST_PINNED) {
            pinned.remove(pinned.iterator().next());
        }
        pinned.add(cellId);
        return true;
    }

    public boolean isPinned(String cellId) {
        return cellId != null && pinned.contains(cellId);
    }

    public boolean isEmpty() {
        return pinned.isEmpty();
    }

    public int size() {
        return pinned.size();
    }

    /** Forgets every pin. */
    public void clear() {
        pinned.clear();
    }

    /**
     * Drops pins whose cell is no longer on the board.
     *
     * <p>Called after the projection is rebuilt. A pin on a term that has been deleted, or that
     * left the board, would otherwise be a card floating at the last place its cell happened to
     * be - saying something true about a term that is not there.
     *
     * @return how many were dropped
     */
    public int keepOnly(Set<String> cellIdsStillPresent) {
        if (cellIdsStillPresent == null) {
            int had = pinned.size();
            pinned.clear();
            return had;
        }
        int before = pinned.size();
        pinned.retainAll(cellIdsStillPresent);
        return before - pinned.size();
    }

    /**
     * The open cards, oldest first, skipping any cell with no tooltip.
     *
     * @param tooltipsByCellId the HTML tooltips the graph already built, by cell id
     */
    public List<Card> cards(Map<String, String> tooltipsByCellId) {
        List<Card> cards = new java.util.ArrayList<Card>();
        Map<String, String> source = tooltipsByCellId == null
                ? new LinkedHashMap<String, String>() : tooltipsByCellId;
        for (String cellId : pinned) {
            List<String> lines = wrapAll(CanvasTooltips.toLines(source.get(cellId)));
            if (!lines.isEmpty()) {
                cards.add(new Card(cellId, lines));
            }
        }
        return Collections.unmodifiableList(cards);
    }

    /** Breaks long lines, so a card is a card and not a stripe across the board. */
    static List<String> wrapAll(List<String> lines) {
        List<String> wrapped = new java.util.ArrayList<String>();
        for (String line : lines) {
            if (line.length() <= WRAP_AT) {
                wrapped.add(line);
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : line.split(" ")) {
                if (current.length() > 0 && current.length() + 1 + word.length() > WRAP_AT) {
                    wrapped.add(current.toString());
                    current = new StringBuilder();
                } else if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(word);
            }
            if (current.length() > 0) {
                wrapped.add(current.toString());
            }
        }
        return wrapped;
    }
}
