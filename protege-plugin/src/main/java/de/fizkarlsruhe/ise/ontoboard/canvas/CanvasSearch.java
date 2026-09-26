package de.fizkarlsruhe.ise.ontoboard.canvas;

import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Finding a term on the board.
 *
 * <p>Until now there was no way to. A board with a hundred terms on it - which is what <em>Add
 * all</em> produces on the pizza ontology, and a small board by the standards of the ontologies this
 * plugin is for - offered exactly two ways to locate {@code Margherita}: drag the canvas around
 * until it appears, or leave the canvas for Prot&eacute;g&eacute;'s class hierarchy, find it there,
 * and come back. The second is the one people actually did, which means the canvas was something you
 * looked at rather than something you worked in.
 *
 * <p>The matching lives here, separate from the field that drives it, because the matching is the
 * part that can be wrong in ways nobody notices. Two decisions in it are worth stating:
 *
 * <ul>
 *   <li><b>Labels and IRIs both match, and labels win.</b> A domain expert types "american hot"; an
 *       engineer pastes {@code http://…/pizza#AmericanHot}. Both are the same search. Ranking labels
 *       above identifiers means the ontology's own vocabulary is what the box is for, and the IRI is
 *       there for when the label is missing, ambiguous, or being checked.
 *   <li><b>Spaces, hyphens and underscores are ignored as a last resort.</b> Ontology labels say
 *       "American Hot" where the identifier says {@code AmericanHot}, so a search that respects
 *       separators finds the term when it has a label and fails when it does not - and an ontology
 *       under construction is full of terms that do not yet have one. It ranks last rather than
 *       first, so it never outranks something the user typed exactly.
 * </ul>
 *
 * <p>Ranked rather than filtered, because the caller centres the best match and steps through the
 * rest, so the order is load-bearing: a plain {@code contains} would answer {@code marg} with
 * {@code VegetarianMargheritaBase} ahead of {@code Margherita} whenever the model happened to list
 * it first.
 */
public final class CanvasSearch {

    /**
     * How many matches the box offers.
     *
     * <p>Enough to step through, few enough that stepping through beats typing one more letter. A
     * search for "pizza" on the pizza ontology matches most of it, and a list of ninety-odd is a
     * worse answer than "narrow it down".
     */
    public static final int DEFAULT_LIMIT = 12;

    /** No match at all. Deliberately not 0, which is the best possible rank. */
    static final int NO_MATCH = -1;

    private CanvasSearch() {
    }

    /**
     * The best matches for what the user has typed, best first.
     *
     * <p>A blank query matches nothing rather than everything. Everything is what the board already
     * shows, and "97 matches" the moment the field is focused is noise over the one line this
     * feature exists to print.
     */
    public static List<CanvasNode> matches(String query, Collection<CanvasNode> nodes) {
        return matches(query, nodes, DEFAULT_LIMIT);
    }

    /** As {@link #matches(String, Collection)}, with the number of matches to keep. */
    public static List<CanvasNode> matches(String query, Collection<CanvasNode> nodes, int limit) {
        String needle = normalise(query);
        if (needle.isEmpty() || nodes == null || limit <= 0) {
            return Collections.emptyList();
        }

        List<Ranked> ranked = new ArrayList<Ranked>();
        for (CanvasNode node : nodes) {
            if (node == null) {
                continue;
            }
            int rank = rankOf(needle, node);
            if (rank != NO_MATCH) {
                ranked.add(new Ranked(node, rank));
            }
        }
        Collections.sort(ranked, BY_RANK_THEN_NAME);

        List<CanvasNode> best = new ArrayList<CanvasNode>();
        for (Ranked one : ranked) {
            if (best.size() >= limit) {
                break;
            }
            best.add(one.node);
        }
        return best;
    }

    /**
     * How well a node answers an already-normalised query, lower being better, or
     * {@link #NO_MATCH}.
     *
     * <p>The ladder, in order: the label exactly; the identifier's short name exactly; the label
     * from its start; the short name from its start; the label anywhere; the short name anywhere;
     * the whole IRI anywhere; and finally either name with separators removed.
     *
     * <p>"From its start" sits above "anywhere" because that is how someone types a name they
     * already know.
     */
    static int rankOf(String query, CanvasNode node) {
        String label = normalise(node.getLabel());
        String shortName = normalise(localNameOf(node.getId()));
        String iri = normalise(node.getId());

        if (label.equals(query)) {
            return 0;
        }
        if (shortName.equals(query)) {
            return 1;
        }
        if (label.startsWith(query)) {
            return 2;
        }
        if (shortName.startsWith(query)) {
            return 3;
        }
        if (label.contains(query)) {
            return 4;
        }
        if (shortName.contains(query)) {
            return 5;
        }
        if (iri.contains(query)) {
            return 6;
        }

        // Last resort. Only reached when none of the tests above matched, so it can only ever add
        // matches - it cannot reorder the ones already found.
        String squashedQuery = squash(query);
        if (!squashedQuery.isEmpty()
                && (squash(label).contains(squashedQuery)
                        || squash(shortName).contains(squashedQuery))) {
            return 7;
        }
        return NO_MATCH;
    }

    /**
     * The part of an IRI a person reads: after the last {@code #} or {@code /}.
     *
     * <p>Not {@code IRI.getShortForm} or a Prot&eacute;g&eacute; renderer, because this class is
     * asked about {@link CanvasNode}s, whose ids are strings the projection has already produced,
     * and because a search box that needed the OWL API could not be tested without an ontology.
     */
    static String localNameOf(String iri) {
        if (iri == null) {
            return "";
        }
        int hash = iri.lastIndexOf('#');
        if (hash >= 0 && hash + 1 < iri.length()) {
            return iri.substring(hash + 1);
        }
        int slash = iri.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < iri.length()) {
            return iri.substring(slash + 1);
        }
        return iri;
    }

    /** What the node is called on screen, falling back to its short name. */
    public static String nameOf(CanvasNode node) {
        String label = node.getLabel();
        if (label != null && !label.trim().isEmpty()) {
            return label;
        }
        return localNameOf(node.getId());
    }

    /** Trimmed and lower-cased, so every comparison here is case-insensitive by construction. */
    private static String normalise(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    /** The same text with the separators ontologies disagree about removed. */
    private static String squash(String text) {
        StringBuilder squashed = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '-' && c != '_') {
                squashed.append(c);
            }
        }
        return squashed.toString();
    }

    /**
     * Ties break on the displayed name and then the id.
     *
     * <p>Not left to the collection's order, which is the projection's, which is the ontology's. A
     * search box whose second-best match moves when an unrelated axiom is added is one people stop
     * trusting.
     */
    private static final Comparator<Ranked> BY_RANK_THEN_NAME = new Comparator<Ranked>() {
        @Override
        public int compare(Ranked left, Ranked right) {
            if (left.rank != right.rank) {
                return left.rank < right.rank ? -1 : 1;
            }
            int byName = nameOf(left.node).compareToIgnoreCase(nameOf(right.node));
            if (byName != 0) {
                return byName;
            }
            return String.valueOf(left.node.getId()).compareTo(String.valueOf(right.node.getId()));
        }
    };

    /** A node and how well it matched. */
    private static final class Ranked {
        private final CanvasNode node;
        private final int rank;

        Ranked(CanvasNode node, int rank) {
            this.node = node;
            this.rank = rank;
        }
    }
}
