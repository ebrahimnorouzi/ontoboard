package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * What two people changed about the same term, named in a curator's words.
 *
 * <p><b>Why a text diff cannot do this.</b> A conflicted {@code .owl} is the one file a
 * side-by-side text view is useless on: git writes {@code <<<<<<<} into it, which makes it
 * unparseable, so the moment a conflict exists the thing on screen has stopped being an ontology.
 * And an ontology's serialisation is not canonical - reorder the {@code owl:Class} elements or
 * relabel {@code _:b0} and you get a hundred-percent text diff and a zero-axiom diff - so even
 * without markers, lines are the wrong unit.
 *
 * <p>Git keeps three clean versions of a conflicted file: the common ancestor, this branch, and
 * the other one. Comparing those three as ontologies turns "which lines clash" into "which terms
 * did both sides change", which is a question a curator can actually answer.
 *
 * <p><b>What this does and does not promise.</b> It reports; it does not resolve. Deciding which
 * of two definitions of a class is right is not a tool's decision, and this plugin has no
 * three-way merge to offer - {@link ReleaseDiff} describes changes rather than producing
 * applicable ones. What a report can do is make the disagreement visible before somebody picks a
 * side by accident, which is what happens today: the file is committed with markers in it, or one
 * side is taken wholesale because it was easier to read.
 *
 * <p><b>Agreement is not conflict.</b> Two people who made the <em>same</em> change - both
 * renamed a class to the same thing, both obsoleted it - have not conflicted, and saying they did
 * would train a reader to ignore the report. Those are reported separately, as agreements.
 */
public final class ConflictReport {

    /** One term both sides touched. */
    public static final class Collision {
        private final IRI iri;
        private final ReleaseDiff.Change ours;
        private final String oursValue;
        private final ReleaseDiff.Change theirs;
        private final String theirsValue;
        private final boolean agreed;

        Collision(IRI iri, ReleaseDiff.Change ours, String oursValue,
                ReleaseDiff.Change theirs, String theirsValue, boolean agreed) {
            this.iri = iri;
            this.ours = ours;
            this.oursValue = oursValue == null ? "" : oursValue;
            this.theirs = theirs;
            this.theirsValue = theirsValue == null ? "" : theirsValue;
            this.agreed = agreed;
        }

        public IRI getIri() {
            return iri;
        }

        public ReleaseDiff.Change getOurs() {
            return ours;
        }

        public ReleaseDiff.Change getTheirs() {
            return theirs;
        }

        public String getOursValue() {
            return oursValue;
        }

        public String getTheirsValue() {
            return theirsValue;
        }

        /**
         * Whether both sides did the same thing to the same term, with the same result.
         *
         * <p><b>Two independent additions are never agreement.</b> For {@link
         * ReleaseDiff.Change#ADDED} the recorded value is only the term's {@code rdfs:label} -
         * see ReleaseDiff, which builds that change with an empty before and the label as after -
         * so two people who each introduced the same IRI with the same label would be called
         * agreed while their definitions, parents and restrictions went uncompared. A term two
         * people created separately is exactly the one somebody should look at.
         *
         * <p>Agreement is also refused when the two sides changed the term in more than one way
         * and those sets differ; {@link ConflictReport#between} decides that, because it is the
         * only place that sees every change to a term rather than one of them.
         */
        public boolean isAgreement() {
            return agreed && ours == theirs && ours != ReleaseDiff.Change.ADDED
                    && oursValue.equals(theirsValue);
        }

        /**
         * The sentence a curator reads.
         *
         * <p>Named rather than numbered: "both sides redefined Material Entity" is something a
         * person can act on, where "conflict in hunk 3 of 7" is not.
         */
        public String describe(String ourName, String theirName) {
            String term = shortForm(iri);
            if (isAgreement()) {
                return "Both " + ourName + " and " + theirName + " "
                        + ours.getLabel().toLowerCase(java.util.Locale.ROOT) + " " + term
                        + " the same way.";
            }
            if (ours == theirs) {
                return "Both sides " + ours.getLabel().toLowerCase(java.util.Locale.ROOT) + " "
                        + term + ", differently: " + ourName + " has "
                        + describeValue(oursValue) + ", " + theirName + " has "
                        + describeValue(theirsValue) + ".";
            }
            return ourName + " " + ours.getLabel().toLowerCase(java.util.Locale.ROOT) + " " + term
                    + "; " + theirName + " "
                    + theirs.getLabel().toLowerCase(java.util.Locale.ROOT) + " it.";
        }

        private static String describeValue(String value) {
            return value.isEmpty() ? "nothing" : "\"" + value + "\"";
        }
    }

    private final List<Collision> collisions;
    private final List<Collision> agreements;
    private final int oursTotal;
    private final int theirsTotal;

    private ConflictReport(List<Collision> collisions, List<Collision> agreements,
            int oursTotal, int theirsTotal) {
        this.collisions = Collections.unmodifiableList(collisions);
        this.agreements = Collections.unmodifiableList(agreements);
        this.oursTotal = oursTotal;
        this.theirsTotal = theirsTotal;
    }

    /**
     * Compares the three versions git keeps of a conflicted file.
     *
     * @param ancestor the common ancestor, git's stage 1
     * @param ours     what this branch says, stage 2
     * @param theirs   what the other branch says, stage 3
     */
    public static ConflictReport between(OWLOntology ancestor, OWLOntology ours,
            OWLOntology theirs) {
        if (ancestor == null || ours == null || theirs == null) {
            throw new IllegalArgumentException(
                    "three versions are needed: the ancestor, this branch and the other one");
        }
        Map<IRI, List<ReleaseDiff.TermChange>> oursByTerm =
                byTerm(ReleaseDiff.between(ancestor, ours));
        Map<IRI, List<ReleaseDiff.TermChange>> theirsByTerm =
                byTerm(ReleaseDiff.between(ancestor, theirs));

        List<Collision> collisions = new ArrayList<Collision>();
        List<Collision> agreements = new ArrayList<Collision>();
        for (Map.Entry<IRI, List<ReleaseDiff.TermChange>> mine : oursByTerm.entrySet()) {
            List<ReleaseDiff.TermChange> other = theirsByTerm.get(mine.getKey());
            if (other == null) {
                // Changed on one side only. Not a conflict: a merge takes it, and saying
                // otherwise would bury the real collisions in a list nobody reads.
                continue;
            }
            // EVERY change to the term on each side decides agreement, not the one that happens
            // to be reported last. A term relabelled AND moved on both sides, to the same parent
            // but different labels, reduced to one MOVED entry per side under the old reading -
            // identical, so the report called it agreement and the label disagreement vanished.
            boolean agreed = signature(mine.getValue()).equals(signature(other));
            ReleaseDiff.TermChange mineShown = mine.getValue().get(mine.getValue().size() - 1);
            ReleaseDiff.TermChange otherShown = other.get(other.size() - 1);
            Collision collision = new Collision(mine.getKey(),
                    mineShown.getChange(), mineShown.getAfter(),
                    otherShown.getChange(), otherShown.getAfter(), agreed);
            if (collision.isAgreement()) {
                agreements.add(collision);
            } else {
                collisions.add(collision);
            }
        }
        return new ConflictReport(collisions, agreements, oursByTerm.size(), theirsByTerm.size());
    }

    /**
     * EVERY change to each term, in the order the diff reported them.
     *
     * <p>{@link ReleaseDiff} can report a term twice - relabelled and moved, say - and a
     * collision is about the term, not about one of the things that happened to it. An earlier
     * version kept only the last change per term, which decided agreement on a sample of one:
     * a term relabelled and moved on both sides, to the same parent but to different labels,
     * reduced to one identical MOVED entry per side and was reported as agreement, with the
     * label disagreement gone from the report entirely. Keeping all of them lets
     * {@link #between} compare what each side actually did.
     */
    private static Map<IRI, List<ReleaseDiff.TermChange>> byTerm(ReleaseDiff diff) {
        Map<IRI, List<ReleaseDiff.TermChange>> byIri =
                new LinkedHashMap<IRI, List<ReleaseDiff.TermChange>>();
        for (ReleaseDiff.TermChange change : diff.getChanges()) {
            List<ReleaseDiff.TermChange> here = byIri.get(change.getIri());
            if (here == null) {
                here = new ArrayList<ReleaseDiff.TermChange>();
                byIri.put(change.getIri(), here);
            }
            here.add(change);
        }
        return byIri;
    }

    /**
     * What one side did to one term, as a set that can be compared with the other side's.
     *
     * <p>A set rather than a list, because the diff's order between two changes to the same term
     * is not something either side chose, and two sides that made the same pair of edits have
     * agreed whichever order they come back in.
     */
    private static Set<String> signature(List<ReleaseDiff.TermChange> changes) {
        Set<String> seen = new TreeSet<String>();
        for (ReleaseDiff.TermChange change : changes) {
            seen.add(change.getChange().name() + "\u0000"
                    + (change.getAfter() == null ? "" : change.getAfter()));
        }
        return seen;
    }

    /** Terms both sides changed, differently. These are what a person has to decide about. */
    public List<Collision> getCollisions() {
        return collisions;
    }

    /** Terms both sides changed in the same way. Reported so they are not mistaken for trouble. */
    public List<Collision> getAgreements() {
        return agreements;
    }

    public boolean isClean() {
        return collisions.isEmpty();
    }

    /**
     * The headline, with the numbers behind it.
     *
     * <p>The totals are there because "3 terms to decide about" means something different when
     * each side changed four terms than when each changed four hundred.
     */
    public String summary() {
        if (collisions.isEmpty()) {
            return "Both sides changed " + oursTotal + " and " + theirsTotal
                    + " terms, and nothing they both touched disagrees."
                    + (agreements.isEmpty() ? ""
                            : " " + agreements.size() + (agreements.size() == 1 ? " was" : " were")
                                    + " changed the same way on both.");
        }
        return collisions.size() + " term" + (collisions.size() == 1 ? "" : "s")
                + " changed on both sides, differently, out of " + oursTotal + " and "
                + theirsTotal + ". Each needs somebody to choose.";
    }

    private static String shortForm(IRI iri) {
        String text = iri.toString();
        int cut = Math.max(text.lastIndexOf('#'), text.lastIndexOf('/'));
        return cut < 0 ? text : text.substring(cut + 1);
    }
}
