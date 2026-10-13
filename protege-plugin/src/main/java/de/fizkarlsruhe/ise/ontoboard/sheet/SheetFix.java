package de.fizkarlsruhe.ise.ontoboard.sheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applying what the audit suggested, one finding or a whole kind at a time.
 *
 * <p>{@link SheetAudit} finds 8,031 things in the NFDI-MatWerk knowledge graph as published,
 * and 7,446 of them carry an exact replacement. Working through 7,446 cells by hand is not an
 * offer anybody takes up, so the point of this class is "fix all 135 whitespace findings" as one
 * action with one undo.
 *
 * <p>Measured on that workbook: applying everything takes the 8,031 findings down to 625, and
 * the only kinds left are the ones no replacement can settle - 184 near-duplicates, 83
 * duplicated identifiers, 23 ambiguous names, 22 unused columns, 10 placeholders, 1 missing
 * identifier - plus 265 unresolved references, which is four fewer than before, because the
 * typographic character that made a reference miss its target was usually in both cells.
 *
 * <h2>It re-reads before it writes</h2>
 *
 * <p>A finding records what a cell said when the audit ran. By the time somebody clicks, the
 * cell may have been edited - by them, or by an earlier fix in the same batch. So every fix
 * checks that the cell still holds what the finding described and <b>skips it otherwise</b>,
 * rather than overwriting whatever is there now with a replacement computed for something else.
 * That is the difference between a bulk fix and bulk damage.
 *
 * <h2>It will not guess</h2>
 *
 * <p>Only findings that carry a suggestion are applied. The ones that do not - two rows for one
 * thing, a name that means two things, a placeholder somebody has to replace with real content -
 * are counted as left alone and reported as such. A tool that picked which of two duplicated
 * entities to keep would eventually pick wrong, silently, in somebody's published graph.
 */
public final class SheetFix {

    /** What applying a batch did. */
    public static final class Outcome {
        private final int applied;
        private final int skipped;
        private final int notFixable;
        private final List<String> skippedWhy;

        Outcome(int applied, int skipped, int notFixable, List<String> skippedWhy) {
            this.applied = applied;
            this.skipped = skipped;
            this.notFixable = notFixable;
            this.skippedWhy = Collections.unmodifiableList(skippedWhy);
        }

        /** How many cells were changed. */
        public int getApplied() {
            return applied;
        }

        /** How many carried a replacement but no longer matched what the audit saw. */
        public int getSkipped() {
            return skipped;
        }

        /** How many never had a replacement to apply. */
        public int getNotFixable() {
            return notFixable;
        }

        /** One line per skipped finding, naming the cell and why it was left. */
        public List<String> getSkippedWhy() {
            return skippedWhy;
        }

        public boolean changedAnything() {
            return applied > 0;
        }

        /** What to tell somebody, in one sentence. */
        public String describe() {
            if (applied == 0 && skipped == 0 && notFixable == 0) {
                return "Nothing to apply.";
            }
            StringBuilder text = new StringBuilder();
            text.append(applied).append(applied == 1 ? " cell" : " cells").append(" changed");
            if (skipped > 0) {
                text.append(", ").append(skipped).append(" left alone because ")
                        .append(skipped == 1 ? "it had" : "they had")
                        .append(" been edited since the check ran");
            }
            if (notFixable > 0) {
                text.append(", ").append(notFixable)
                        .append(" that need a person rather than a replacement");
            }
            return text.append('.').toString();
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    private SheetFix() {
    }

    /**
     * Applies one finding.
     *
     * @return true when the cell was changed; false when it had no suggestion, or no longer
     *     holds what the finding was about
     */
    public static boolean apply(SheetBook book, SheetAudit.Finding finding) {
        if (book == null || finding == null || !finding.isFixable()) {
            return false;
        }
        if (finding.getRow() < 1 || finding.getColumn() < 1) {
            return false;
        }
        String now = book.cell(finding.getSheet(), finding.getRow(), finding.getColumn());
        if (!now.equals(finding.getCell())) {
            return false;
        }
        return book.setCell(finding.getSheet(), finding.getRow(), finding.getColumn(),
                finding.getSuggestion());
    }

    /**
     * Applies every finding in the list that can be applied, as one thing to undo.
     *
     * <p>One undo for the batch, which this class has always claimed and did not do. Measured
     * on the NFDI-MatWerk workbook: applying everything the audit suggests changes 7,406 cells,
     * and before this each one was its own undo entry against a history that holds 200 - so
     * the largest offer in the whole tool, "fix 7,406 things", was the one action that could
     * not be taken back, and taking back as much of it as the history held left the sheets in
     * a state that was neither before nor after.
     */
    public static Outcome applyAll(final SheetBook book, final List<SheetAudit.Finding> findings) {
        final int[] counts = new int[3];
        final List<String> why = new ArrayList<String>();
        if (book == null || findings == null) {
            return new Outcome(0, 0, 0, why);
        }
        book.asOneChange("apply the audit's suggestions", new SheetBook.Gesture() {
            @Override
            public int run() {
                for (SheetAudit.Finding finding : findings) {
                    if (!finding.isFixable()) {
                        counts[2]++;
                        continue;
                    }
                    if (apply(book, finding)) {
                        counts[0]++;
                        continue;
                    }
                    counts[1]++;
                    if (why.size() < 20) {
                        why.add(finding.where() + " no longer holds \"" + finding.getCell()
                                + "\"");
                    }
                }
                return counts[0];
            }
        });
        return new Outcome(counts[0], counts[1], counts[2], why);
    }

    /** Applies every finding of one kind. */
    public static Outcome applyKind(SheetBook book, List<SheetAudit.Finding> findings,
            SheetAudit.Kind kind) {
        List<SheetAudit.Finding> wanted = new ArrayList<SheetAudit.Finding>();
        if (findings != null) {
            for (SheetAudit.Finding finding : findings) {
                if (finding.getKind() == kind) {
                    wanted.add(finding);
                }
            }
        }
        return applyAll(book, wanted);
    }

    /**
     * How many of each kind could be applied right now, for offering the batches.
     *
     * <p>Counts only the fixable ones, because a button saying "fix 49 ambiguous names" that
     * then fixes none of them is worse than no button.
     */
    public static Map<SheetAudit.Kind, Integer> fixableByKind(
            List<SheetAudit.Finding> findings) {
        Map<SheetAudit.Kind, Integer> counts =
                new LinkedHashMap<SheetAudit.Kind, Integer>();
        if (findings == null) {
            return counts;
        }
        for (SheetAudit.Finding finding : findings) {
            if (!finding.isFixable()) {
                continue;
            }
            Integer already = counts.get(finding.getKind());
            counts.put(finding.getKind(), already == null ? 1 : already + 1);
        }
        return counts;
    }

    /**
     * Undoes a whole batch and says how many cells were put back.
     *
     * <p>One undo does it, because {@link #applyAll} records the batch as one change. This
     * used to call undo once per cell, which was right for the two-cell batch in a test and
     * wrong for the 7,406-cell batch a real workbook produces: the history holds 200 entries,
     * so 7,206 of those undos had nothing left to reverse and the sheets ended up in a state
     * that was neither before the fix nor after it.
     *
     * <p>Assumes nothing has been changed since the batch was applied, as it always has - the
     * batch is the most recent change or it is not the one being undone.
     */
    public static int undoBatch(SheetBook book, Outcome outcome) {
        if (book == null || outcome == null || outcome.getApplied() == 0 || !book.canUndo()) {
            return 0;
        }
        book.undo();
        return outcome.getApplied();
    }
}
