package de.fizkarlsruhe.ise.ontoboard.sheet;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turning a name somebody typed into a thing that exists.
 *
 * <p>The step this workflow otherwise has no answer for. A curator fills in a row about a data
 * portal, reaches the <i>Associated Institute</i> column, types the institute's name - and
 * nothing happens, because that column names another term and no term has that name yet. The
 * only way forward is to leave the sheet, find the one that holds institutes, add a row, invent
 * an identifier, come back, and retype the name. Measured on the MatWerk graph, 123 references
 * name something that does not exist.
 *
 * <h2>What it decides, and how</h2>
 *
 * <p>Three questions, each answered from the sheets rather than asked:
 *
 * <ul>
 *   <li><b>Which sheet should hold it.</b> The one whose rows are already named by this same
 *       column elsewhere. If a <i>City</i> column's other cells resolve to rows in
 *       {@code city}, then a new city belongs in {@code city}. Falling back, a sheet whose name
 *       matches the heading; failing that, there is no answer and it says so rather than
 *       guessing.</li>
 *   <li><b>What type to give it.</b> The type its new neighbours have - the commonest value in
 *       the target sheet's {@code TYPE} column. A sheet of institutes is a sheet of one kind of
 *       thing, so the commonest answer is the right one, and where it is not the proposal shows
 *       the type so it can be changed.</li>
 *   <li><b>What identifier.</b> The shape its neighbours use: their longest common prefix, and
 *       then the next number after the highest they have. Checked against every identifier in
 *       every open sheet, not just the target, because the same namespace is spread across
 *       them.</li>
 * </ul>
 *
 * <h2>It refuses rather than guesses</h2>
 *
 * <p>A proposal that cannot be made carries the reason instead. No target sheet, no identifier
 * column, identifiers whose shape cannot be continued - each is a case where inventing an
 * answer would put a wrong thing in somebody's graph, and the editor can ask instead.
 *
 * <p>Nothing is changed by {@link #propose}; {@link #create} is the only method that writes,
 * and what it writes is one row.
 */
public final class TermCreation {

    /** What would be created, and why there. */
    public static final class Proposal {
        private final String label;
        private final String fromSheet;
        private final int fromRow;
        private final int fromColumn;
        private final String targetSheet;
        private final String type;
        private final String iri;
        private final String why;
        private final String refusal;

        Proposal(String label, String fromSheet, int fromRow, int fromColumn,
                String targetSheet, String type, String iri, String why, String refusal) {
            this.label = label;
            this.fromSheet = fromSheet;
            this.fromRow = fromRow;
            this.fromColumn = fromColumn;
            this.targetSheet = targetSheet;
            this.type = type;
            this.iri = iri;
            this.why = why;
            this.refusal = refusal;
        }

        static Proposal refused(String label, String reason) {
            return new Proposal(label, "", 0, 0, "", "", "", "", reason);
        }

        /** The name that was typed, which becomes the new thing's label. */
        public String getLabel() {
            return label;
        }

        /** The sheet the name was typed into. */
        public String getFromSheet() {
            return fromSheet;
        }

        public int getFromRow() {
            return fromRow;
        }

        public int getFromColumn() {
            return fromColumn;
        }

        /** The sheet the new row would go in. */
        public String getTargetSheet() {
            return targetSheet;
        }

        /** The value for the new row's TYPE column, or empty when the sheet has none. */
        public String getType() {
            return type;
        }

        /** The identifier the new row would get. */
        public String getIri() {
            return iri;
        }

        /** Why this sheet and this type, in one sentence, for the person approving it. */
        public String getWhy() {
            return why;
        }

        /** Empty when the proposal can be made; otherwise what stopped it. */
        public String getRefusal() {
            return refusal;
        }

        public boolean isPossible() {
            return refusal.isEmpty();
        }

        /** What to put in front of somebody: one line they can say yes to. */
        public String describe() {
            if (!isPossible()) {
                return refusal;
            }
            return "Add \"" + label + "\" to " + targetSheet
                    + (type.isEmpty() ? "" : " as " + shorten(type))
                    + ", with the identifier " + iri + ". " + why;
        }

        private static String shorten(String iri) {
            int cut = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
            return cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : iri;
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    private TermCreation() {
    }

    /**
     * Works out what creating the name in this cell would mean. Changes nothing.
     *
     * @param row the cell's row, as a spreadsheet numbers rows
     * @param column the cell's column, 1-based
     */
    public static Proposal propose(SheetBook book, String sheetName, int row, int column) {
        return propose(book, sheetName, row, column, null);
    }

    /**
     * As {@link #propose(SheetBook, String, int, int)}, minting the identifier with
     * {@code minting} rather than from the target sheet's own numbering.
     *
     * <p>Which matters in a shared ODK project: the sheet's numbering says what shape an
     * identifier has, and only the project's {@code -idranges.owl} says which numbers are
     * <em>this editor's</em> to use. Pass the ranges-backed generator and a new row lands in
     * the block allocated to whoever is typing; pass null and it continues the sheet, which is
     * all a project without ranges can do.
     */
    public static Proposal propose(SheetBook book, String sheetName, int row, int column,
            IriGenerator minting) {
        List<Proposal> all = proposeAll(book, sheetName, row, column, minting);
        return all.isEmpty()
                ? Proposal.refused("", "There is nothing in this cell left to create.")
                : all.get(0);
    }

    /**
     * One proposal per name in the cell that does not exist yet.
     *
     * <p>A {@code SPLIT=} column holds several names in one cell, and they are separate things.
     * The first version of this read the whole cell as one name, so
     * {@code Albert-Ludwigs-University of Freiburg, Fraunhofer Institute for Mechanics of
     * Materials} - a real {@code dataportal} cell - would have become a single individual
     * labelled with both institutes joined by a comma. It also meant the "this name already
     * exists" check never fired on such a cell, because the joined string is not a name even
     * when both halves are.
     */
    public static List<Proposal> proposeAll(SheetBook book, String sheetName, int row,
            int column) {
        return proposeAll(book, sheetName, row, column, null);
    }

    /** As {@link #proposeAll(SheetBook, String, int, int)}, with an explicit minter. */
    public static List<Proposal> proposeAll(SheetBook book, String sheetName, int row,
            int column, IriGenerator minting) {
        List<Proposal> proposals = new ArrayList<Proposal>();
        if (book == null || book.get(sheetName) == null) {
            proposals.add(Proposal.refused("", "There is no sheet called " + sheetName + "."));
            return proposals;
        }
        String cell = book.cell(sheetName, row, column).trim();
        if (cell.isEmpty()) {
            proposals.add(Proposal.refused("",
                    "The cell is empty, so there is no name to create."));
            return proposals;
        }
        TemplateColumns.Column spec = book.get(sheetName).getColumns().at(column);
        if (spec == null || !spec.namesATerm()) {
            proposals.add(Proposal.refused(cell, "This column holds text rather than a "
                    + "reference to another term, so there is nothing to create: the text is "
                    + "already the value."));
            return proposals;
        }
        List<String> parts = new ArrayList<String>();
        if (spec.getSplit().isEmpty()) {
            parts.add(cell);
        } else {
            for (String part : cell.split(
                    java.util.regex.Pattern.quote(spec.getSplit()), -1)) {
                if (!part.trim().isEmpty()) {
                    parts.add(part.trim());
                }
            }
        }
        for (String part : parts) {
            proposals.add(proposeOne(book, sheetName, row, column, part, spec,
                    minting));
        }
        return proposals;
    }

    private static Proposal proposeOne(SheetBook book, String sheetName, int row, int column,
            String label, TemplateColumns.Column spec, IriGenerator minting) {
        if (book.index().knows(label)) {
            return Proposal.refused(label, "\"" + label + "\" already names something. "
                    + "Creating a second thing with the same name is what makes a reference "
                    + "ambiguous.");
        }
        if (spec.getKind() == TemplateColumns.Kind.TYPE) {
            // A TYPE names the class a row is an instance of, and that class belongs to an
            // ontology rather than to a sheet. Adding a row for it would make an individual
            // and then say the row is an instance of an individual. On the real sheets this is
            // most of the unresolved references - req_1 alone has rows typed "abbreviation
            // textual entity" - so saying the right thing here matters more than the count.
            return Proposal.refused(label, "\"" + label + "\" is the kind of thing this row is, "
                    + "so it has to be a class in the ontology rather than a row in a sheet. "
                    + "Bring it in with OntoBoard > Find terms in another ontology..., or write "
                    + "its IRI here if it is already in an import.");
        }

        String target = targetFor(book, sheetName, column, spec);
        if (target.isEmpty()) {
            return Proposal.refused(label, "Nothing says which sheet should hold \"" + label
                    + "\". No other cell in this column names a row in any open sheet, and no "
                    + "sheet is named after the column, so the choice is yours.");
        }
        SheetBook.Sheet sheet = book.get(target);
        TemplateColumns.Column id = sheet.getColumns().firstOf(TemplateColumns.Kind.ID);
        if (id == null) {
            return Proposal.refused(label, target + " has no ID column, so a new row there "
                    + "would have no identifier and nothing it said would be kept.");
        }
        if (labelColumnOf(sheet) == 0) {
            return Proposal.refused(label, target + " has no label column, so there is nowhere "
                    + "to put the name - and without it the reference would not resolve.");
        }

        String iri = nextIdentifier(book, target, minting);
        if (iri.isEmpty()) {
            return Proposal.refused(label, "The identifiers in " + target + " do not end in a "
                    + "number, so there is no next one to use. Give the new row an identifier "
                    + "yourself.");
        }
        String type = commonestType(sheet);
        String why = whyThisSheet(book, sheetName, column, target);
        return new Proposal(label, sheetName, row, column, target, type, iri, why, "");
    }

    /**
     * Adds the row the proposal describes.
     *
     * <p>The cell that started it is deliberately left as it was: it already holds the name,
     * and the name now resolves. Rewriting it to the IRI would be a second decision and a
     * worse one - the sheet becomes unreadable - and the editor offers that separately.
     *
     * @return the new row's number, or 0 when nothing was done
     */
    public static int create(SheetBook book, Proposal proposal) {
        if (book == null || proposal == null || !proposal.isPossible()) {
            return 0;
        }
        SheetBook.Sheet sheet = book.get(proposal.getTargetSheet());
        if (sheet == null) {
            return 0;
        }
        TemplateColumns columns = sheet.getColumns();
        TemplateColumns.Column id = columns.firstOf(TemplateColumns.Kind.ID);
        int labelColumn = labelColumnOf(sheet);
        if (id == null || labelColumn == 0) {
            return 0;
        }
        int row = book.addRow(proposal.getTargetSheet());
        if (row == 0) {
            return 0;
        }
        book.setCell(proposal.getTargetSheet(), row, id.getNumber(), proposal.getIri());
        book.setCell(proposal.getTargetSheet(), row, labelColumn, proposal.getLabel());
        TemplateColumns.Column type = columns.firstOf(TemplateColumns.Kind.TYPE);
        if (type != null && !proposal.getType().isEmpty()) {
            book.setCell(proposal.getTargetSheet(), row, type.getNumber(), proposal.getType());
        }
        return row;
    }

    // ---------------------------------------------------------------- deciding where

    /**
     * The sheet this column's other cells already point into.
     *
     * <p>Evidence rather than naming: if eleven cells in a <i>City</i> column resolve to rows in
     * {@code city}, a twelfth city belongs there whatever the column is called. The name of the
     * column is only consulted when no other cell resolves anywhere.
     */
    private static String targetFor(SheetBook book, String sheetName, int column,
            TemplateColumns.Column spec) {
        SheetBook.Sheet sheet = book.get(sheetName);
        Map<String, Integer> votes = new LinkedHashMap<String, Integer>();
        for (int row = SheetBook.FIRST_DATA_ROW; row <= sheet.getLastRow(); row++) {
            String value = book.cell(sheetName, row, column).trim();
            if (value.isEmpty()) {
                continue;
            }
            for (String part : value.split(spec.getSplit().isEmpty() ? "\u0000"
                    : java.util.regex.Pattern.quote(spec.getSplit()))) {
                for (LabelIndex.Entry entry : book.index().lookup(part.trim())) {
                    if (!entry.isFromASheet() || entry.getSource().equals(sheetName)) {
                        continue;
                    }
                    Integer already = votes.get(entry.getSource());
                    votes.put(entry.getSource(), already == null ? 1 : already + 1);
                }
            }
        }
        String best = "";
        int most = 0;
        for (Map.Entry<String, Integer> vote : votes.entrySet()) {
            if (vote.getValue() > most) {
                most = vote.getValue();
                best = vote.getKey();
            }
        }
        if (!best.isEmpty()) {
            return best;
        }
        // Nothing to learn from, so fall back to a sheet named after the column - but only on a
        // WHOLE WORD. A plain `heading.contains(name)` was the first version and it matched a
        // sheet called "s" against the heading "Points at", because the heading contains an s.
        // A one or two letter sheet name matches almost any heading that way, and the wrong
        // sheet is a worse answer than no answer.
        String heading = book.get(sheetName).getColumns().at(column).getHeading()
                .toLowerCase().trim();
        for (SheetBook.Sheet candidate : book.getSheets()) {
            String name = candidate.getName().toLowerCase();
            if (name.equals(heading)) {
                return candidate.getName();
            }
            for (String word : heading.split("[^a-z0-9]+")) {
                if (!word.isEmpty() && word.equals(name)) {
                    return candidate.getName();
                }
            }
        }
        return "";
    }

    private static String whyThisSheet(SheetBook book, String sheetName, int column,
            String target) {
        TemplateColumns.Column spec = book.get(sheetName).getColumns().at(column);
        int resolved = 0;
        for (int row = SheetBook.FIRST_DATA_ROW; row <= book.get(sheetName).getLastRow();
                row++) {
            String value = book.cell(sheetName, row, column).trim();
            if (value.isEmpty()) {
                continue;
            }
            for (LabelIndex.Entry entry : book.index().lookup(value)) {
                if (target.equals(entry.getSource())) {
                    resolved++;
                    break;
                }
            }
        }
        if (resolved > 0) {
            return resolved + (resolved == 1 ? " other cell" : " other cells")
                    + " in \"" + spec.getHeading() + "\" already name rows in " + target + ".";
        }
        return "No other cell in \"" + spec.getHeading() + "\" names anything yet, so "
                + target + " was chosen because it is named after the column.";
    }

    // ---------------------------------------------------------------- deciding what

    /** The commonest TYPE in the sheet, which is what a new neighbour should be. */
    private static String commonestType(SheetBook.Sheet sheet) {
        TemplateColumns.Column type = sheet.getColumns().firstOf(TemplateColumns.Kind.TYPE);
        if (type == null) {
            return "";
        }
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (int at = 2; at < sheet.getRows().size(); at++) {
            List<String> row = sheet.getRows().get(at);
            int column = type.getNumber() - 1;
            String value = column < row.size() && row.get(column) != null
                    ? row.get(column).trim() : "";
            if (value.isEmpty()) {
                continue;
            }
            Integer already = counts.get(value);
            counts.put(value, already == null ? 1 : already + 1);
        }
        String best = "";
        int most = 0;
        for (Map.Entry<String, Integer> each : counts.entrySet()) {
            if (each.getValue() > most) {
                most = each.getValue();
                best = each.getKey();
            }
        }
        return best;
    }

    /** The first column that holds a label, or 0. */
    private static int labelColumnOf(SheetBook.Sheet sheet) {
        for (TemplateColumns.Column column : sheet.getColumns().getColumns()) {
            if (column.getKind() == TemplateColumns.Kind.LABEL
                    || column.getKind() == TemplateColumns.Kind.ANNOTATION_LANGUAGE) {
                return column.getNumber();
            }
            if (column.getKind() != TemplateColumns.Kind.ANNOTATION) {
                continue;
            }
            String property = column.getProperty();
            int cut = Math.max(property.lastIndexOf('#'), property.lastIndexOf(':'));
            String local = property.substring(cut + 1);
            if ("label".equals(local) || local.startsWith("label@")) {
                return column.getNumber();
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- deciding which identifier

    /**
     * The next identifier in the shape the sheet already uses.
     *
     * <p>The longest common prefix of what is there, then one past the highest number after it.
     * Checked for collision against <b>every</b> sheet, because these namespaces are shared:
     * the MatWerk sheets all mint under {@code .../matwerk/msekg/}, so the next free number in
     * {@code city} is not a question about {@code city}.
     */
    /**
     * The identifier a new row in {@code target} gets.
     *
     * <p>One rule, in {@link IriGenerator}, since 1.117.0 - and the reason is a defect the
     * version here had from the start. It took the stem as everything before the trailing
     * digits and appended the next number <em>unpadded</em>, so a sheet of seven-digit
     * identifiers ({@code .../MWO_0002011}, MatWerk's actual shape) produced
     * {@code .../MWO_2012} for its next row: four digits wide, sorting before every identifier
     * it follows, matching no project's {@code iddigits} - and building perfectly happily, so
     * nothing ever said so.
     *
     * @param minting the generator to use, or null to continue {@code target}'s own numbering
     */
    private static String nextIdentifier(SheetBook book, String target, IriGenerator minting) {
        // Re-scoped to the TARGET sheet, not the one the cursor is in. A proposal's whole point
        // is that the row lands somewhere else - "create this organization in organization" is
        // invoked from the instrument sheet - and a minter scoped to the caller gave the new
        // row the caller's shape, permanently.
        IriGenerator generator = minting != null ? minting.forAnotherSheet(target)
                : IriGenerator.forSheet(book, target, null, null, null);
        if (!generator.canMint()) {
            return "";
        }
        return generator.getSource().needsALabel()
                ? generator.mintFor(java.util.Collections.singletonList("")).get(0)
                : generator.mint(1).get(0);
    }
}
