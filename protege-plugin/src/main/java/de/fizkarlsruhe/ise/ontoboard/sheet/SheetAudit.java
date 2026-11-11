package de.fizkarlsruhe.ise.ontoboard.sheet;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything wrong, odd or probably-a-mistake in a sheet, with what to do about each.
 *
 * <p>Separate from {@code TemplateSheet} on purpose. That class answers "will this build, and
 * if not where is the fault" - a question about ROBOT. This one answers "is this data any
 * good", which is a question nobody was asking and which has different answers: a sheet can
 * build perfectly and still be full of duplicated entities, pasted whitespace, leaked
 * placeholders and names that mean two things.
 *
 * <p>Measured on the MatWerk knowledge graph, which builds with zero problems: its 26 sheets
 * index to 6,058 names of which 30 name more than one thing, one of them naming three. Several
 * labels end in {@code _name_}, which is a template placeholder that escaped into the data. So
 * "it builds" and "it is right" are genuinely different questions.
 *
 * <p>Every finding carries a {@link Finding#getSuggestion() suggestion} where there is an
 * obvious replacement, so an editor can offer to apply it rather than only complain. Where
 * there is no safe replacement the suggestion is empty and the message says what the choice is,
 * because guessing which of two duplicated entities to keep is not a decision a tool should
 * make.
 *
 * <p>Pure logic over strings and a {@link LabelIndex}: no OWL API, no Swing, no robot-core, so
 * it is cheap enough to re-run while somebody types.
 */
public final class SheetAudit {

    /** What sort of thing was found. */
    public enum Kind {
        /** A name in this sheet means more than one thing. */
        AMBIGUOUS_NAME,
        /** Two rows carry the same identifier. */
        DUPLICATE_ID,
        /** Two rows carry names so similar that one is probably a typo of the other. */
        NEAR_DUPLICATE,
        /** A reference that names nothing, with the closest real name offered. */
        UNRESOLVED_REFERENCE,
        /** Space at an end, a doubled space, a tab, or a non-breaking space. */
        WHITESPACE,
        /** A curly quote or dash, which is what a word processor does to pasted text. */
        TYPOGRAPHIC_CHARACTER,
        /** A row with data but no identifier. */
        MISSING_ID,
        /** Text that looks like it came from a template rather than from a curator. */
        PLACEHOLDER,
        /** A reference wrapped in quotes, which works in some columns and not in this one. */
        QUOTED_REFERENCE,
        /** A column with data in it that the template row ignores. */
        UNUSED_COLUMN,
        /**
         * A cell that writes a datatype its column's template string already gives (1.135.2).
         *
         * <p>ROBOT reads no datatype in such a cell - the column gives it - so the suffix stays in
         * the text: {@code 2026-01-15^^xsd:date} in a column {@code AT dcterms:date^^xsd:date}
         * becomes the literal {@code "2026-01-15^^xsd:date"^^xsd:date}, which is no date. Found on
         * a knowledge graph built from sheets, where nothing said a word about it.
         */
        DATATYPE_TWICE,
        /**
         * Two sheets are the subject of one identifier and disagree about what it is.
         *
         * <p>Not the same thing as two sheets being the subject of one identifier, which is
         * ordinary and useful: ROBOT merges templates, so one sheet can give a person their
         * name and another their affiliation.
         *
         * <p>Measured on the published NFDI-MatWerk workbook, which decided the shape of this
         * check. 1,639 of its 6,688 identifiers are the subject of rows in more than one sheet.
         * Of those, 1,627 carry a label on both sides - and all 1,627 agree, exactly, which is
         * a thing its curators are keeping true by hand. Twenty disagree about the declared
         * type. None disagree about a label. So a check that reported every identifier spanning
         * sheets would be a list of 1,619 non-problems, and a check that reported only the
         * contradictions is a list of twenty.
         *
         * <p>Raised, therefore, only where the sheets contradict each other: a different label,
         * or a different type, for one identifier, in two files neither author can see from the
         * other. Which one is right is not a tool's decision, so nothing is suggested.
         */
        CONFLICTING_DEFINITION
    }

    /** One thing found, where it is, and what to do. */
    public static final class Finding {
        private final Kind kind;
        private final String sheet;
        private final int row;
        private final int column;
        private final String heading;
        private final String cell;
        private final String message;
        private final String suggestion;
        private final String name;

        Finding(Kind kind, String sheet, int row, int column, String heading, String cell,
                String message, String suggestion) {
            this(kind, sheet, row, column, heading, cell, message, suggestion, "");
        }

        Finding(Kind kind, String sheet, int row, int column, String heading, String cell,
                String message, String suggestion, String name) {
            this.kind = kind;
            this.sheet = sheet;
            this.row = row;
            this.column = column;
            this.heading = heading;
            this.cell = cell == null ? "" : cell;
            this.message = message;
            this.suggestion = suggestion == null ? "" : suggestion;
            this.name = name == null ? "" : name;
        }

        /**
         * The name that means more than one thing, for an {@link Kind#AMBIGUOUS_NAME}; empty for
         * every other finding. Not the cell: a cell can hold several names, split.
         */
        public String getName() {
            return name;
        }

        public Kind getKind() {
            return kind;
        }

        public String getSheet() {
            return sheet;
        }

        /** As the spreadsheet numbers it: the first data row is 3. Zero for a whole column. */
        public int getRow() {
            return row;
        }

        /** 1-based, or zero when the finding is not about one column. */
        public int getColumn() {
            return column;
        }

        /** The heading the author wrote, which is how they will find the column. */
        public String getHeading() {
            return heading;
        }

        public String getCell() {
            return cell;
        }

        public String getMessage() {
            return message;
        }

        /**
         * What the cell should say instead, or empty when there is no safe answer.
         *
         * <p>Empty is not a failure. Two rows that duplicate an entity need somebody to decide
         * which survives, and a tool that picked one would eventually pick wrong.
         */
        public String getSuggestion() {
            return suggestion;
        }

        /** Whether an editor can offer to apply this without asking anything further. */
        public boolean isFixable() {
            return !suggestion.isEmpty();
        }

        /** Where it is, in one phrase. */
        public String where() {
            if (row == 0) {
                return column == 0 ? sheet : sheet + ", column \"" + heading + "\"";
            }
            return column == 0 ? sheet + " row " + row
                    : sheet + " row " + row + ", column \"" + heading + "\"";
        }

        @Override
        public String toString() {
            return where() + ": " + message;
        }
    }

    /** Labels that differ by at most this many edits are offered as each other's typo. */
    private static final int CLOSE_ENOUGH = 2;

    /**
     * Below this length an edit distance of two is most of the word.
     *
     * <p>Without it, "iron" and "zinc" come back as near-duplicates of each other, which is
     * worse than saying nothing.
     */
    private static final int LONG_ENOUGH_TO_COMPARE = 6;

    /** Text a template leaves behind when a field was never filled in. */
    private static final String[] PLACEHOLDERS = {
        "_name_", "xxx", "tbd", "todo", "n/a", "lorem ipsum", "your text here", "..."
    };

    // The characters a word processor or a web page substitutes, written as code points rather
    // than as themselves. A \\u escape would not do: Java resolves those before it lexes, so
    // '\\u00a0' becomes a raw non-breaking space in the token stream. Keeping this file pure
    // ASCII also means it compiles the same whatever encoding the compiler assumes.
    private static final char NO_BREAK_SPACE = 0x00a0;
    private static final char LEFT_SINGLE_QUOTE = 0x2018;
    private static final char RIGHT_SINGLE_QUOTE = 0x2019;
    private static final char LEFT_DOUBLE_QUOTE = 0x201c;
    private static final char RIGHT_DOUBLE_QUOTE = 0x201d;
    private static final char EN_DASH = 0x2013;
    private static final char EM_DASH = 0x2014;

    /** Row 1 is the author's headings, row 2 is ROBOT's, so data starts at 3. */
    private static final int FIRST_DATA_ROW = 3;

    private SheetAudit() {
    }

    /**
     * Audits what no single sheet can see: one identifier that two sheets disagree about.
     *
     * <p>Every other check in this class reads one sheet, because that is the unit somebody
     * edits. This one cannot: the whole point is the second file. ROBOT reads a folder of
     * templates and merges what they assert, so two sheets contributing different columns about
     * one identifier is the intended way to use it - and two sheets asserting a different LABEL
     * or a different TYPE for one identifier is a contradiction that neither author can see and
     * that ROBOT resolves silently, by whichever order the files happen to be merged in.
     *
     * <p>A finding is raised against each participating row, so that both sides are marked
     * rather than the one that happens to be scanned first.
     *
     * @param sheets every sheet in play, name to rows, both header rows included
     */
    public static List<Finding> across(Map<String, List<List<String>>> sheets) {
        List<Finding> findings = new ArrayList<Finding>();
        if (sheets == null || sheets.size() < 2) {
            return findings;
        }
        Map<String, List<Claim>> claims = new LinkedHashMap<String, List<Claim>>();
        for (Map.Entry<String, List<List<String>>> each : sheets.entrySet()) {
            collectClaims(each.getKey(), each.getValue(), claims);
        }
        for (Map.Entry<String, List<Claim>> each : claims.entrySet()) {
            List<Claim> rows = each.getValue();
            if (rows.size() < 2 || oneSheetOnly(rows)) {
                continue;
            }
            report(each.getKey(), rows, true, findings);
            report(each.getKey(), rows, false, findings);
        }
        return findings;
    }

    /** One row that is the subject of an identifier, and what its sheet says about it. */
    private static final class Claim {
        private final String sheet;
        private final int row;
        private final String label;
        private final int labelColumn;
        private final Set<String> types;
        private final int typeColumn;

        Claim(String sheet, int row, String label, int labelColumn, Set<String> types,
                int typeColumn) {
            this.sheet = sheet;
            this.row = row;
            this.label = label;
            this.labelColumn = labelColumn;
            this.types = types;
            this.typeColumn = typeColumn;
        }

        String where() {
            return sheet + " row " + row;
        }
    }

    private static void collectClaims(String sheetName, List<List<String>> rows,
            Map<String, List<Claim>> into) {
        if (rows == null || rows.size() < 2) {
            return;
        }
        String sheet = sheetName == null || sheetName.trim().isEmpty() ? "this sheet"
                : sheetName.trim();
        TemplateColumns columns = TemplateColumns.of(rows);
        TemplateColumns.Column id = columns.firstOf(TemplateColumns.Kind.ID);
        if (id == null) {
            return;
        }
        TemplateColumns.Column label = columns.firstLabelColumn();
        TemplateColumns.Column type = columns.firstOf(TemplateColumns.Kind.TYPE);
        for (int at = FIRST_DATA_ROW - 1; at < rows.size(); at++) {
            List<String> row = rows.get(at);
            String identifier = cell(row, id.getNumber()).trim();
            if (identifier.isEmpty()) {
                continue;
            }
            List<Claim> seen = into.get(identifier);
            if (seen == null) {
                seen = new ArrayList<Claim>();
                into.put(identifier, seen);
            }
            seen.add(new Claim(sheet, at + 1,
                    label == null ? "" : cell(row, label.getNumber()).trim(),
                    label == null ? 0 : label.getNumber(),
                    type == null ? new LinkedHashSet<String>()
                            : valuesIn(cell(row, type.getNumber()), type.getSplit()),
                    type == null ? 0 : type.getNumber()));
        }
    }

    /**
     * A cell's values, separated as its template row says to separate them.
     *
     * <p>A {@code TYPE SPLIT=,} cell holding two classes in the other order is the same cell,
     * and comparing the raw strings would call that a contradiction.
     */
    private static Set<String> valuesIn(String value, String split) {
        Set<String> values = new LinkedHashSet<String>();
        if (value == null) {
            return values;
        }
        String[] parts = split == null || split.isEmpty() ? new String[] {value}
                : value.split(java.util.regex.Pattern.quote(split), -1);
        for (String part : parts) {
            String one = part.trim();
            if (!one.isEmpty()) {
                values.add(one);
            }
        }
        return values;
    }

    private static boolean oneSheetOnly(List<Claim> rows) {
        String first = rows.get(0).sheet;
        for (Claim claim : rows) {
            if (!claim.sheet.equals(first)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Raises a finding against every row, when the rows disagree.
     *
     * <p>A blank is not a disagreement. The sheet that contributes other columns and leaves the
     * label empty is the normal case and must stay silent.
     */
    private static void report(String identifier, List<Claim> rows, boolean aboutLabel,
            List<Finding> findings) {
        Map<String, List<Claim>> saying = new LinkedHashMap<String, List<Claim>>();
        for (Claim claim : rows) {
            // Sorted, so that a SPLIT cell listing the same two classes in the other order is
            // the same cell. Set equality is the question; Set.toString() answers a different
            // one, and answered it wrongly for every reordered cell in the workbook.
            String said = aboutLabel ? claim.label : new java.util.TreeSet<String>(claim.types)
                    .toString();
            if (aboutLabel ? said.isEmpty() : claim.types.isEmpty()) {
                continue;
            }
            List<Claim> already = saying.get(said);
            if (already == null) {
                already = new ArrayList<Claim>();
                saying.put(said, already);
            }
            already.add(claim);
        }
        if (saying.size() < 2) {
            return;
        }
        for (Map.Entry<String, List<Claim>> group : saying.entrySet()) {
            for (Claim claim : group.getValue()) {
                findings.add(new Finding(Kind.CONFLICTING_DEFINITION, claim.sheet, claim.row,
                        aboutLabel ? claim.labelColumn : claim.typeColumn,
                        aboutLabel ? "Label" : "TYPE",
                        aboutLabel ? claim.label : readable(claim.types),
                        identifier + " is also the subject of " + elsewhere(claim, rows)
                                + ", and " + (aboutLabel ? "the labels disagree: "
                                        : "the types disagree: ") + disagreement(saying)
                                + ". ROBOT merges these sheets, so one of them wins by merge "
                                + "order and nothing says which.",
                        ""));
            }
        }
    }

    private static String elsewhere(Claim claim, List<Claim> rows) {
        StringBuilder said = new StringBuilder();
        for (Claim other : rows) {
            if (other == claim) {
                continue;
            }
            said.append(said.length() == 0 ? "" : ", ").append(other.where());
        }
        return said.toString();
    }

    private static String disagreement(Map<String, List<Claim>> saying) {
        StringBuilder said = new StringBuilder();
        for (Map.Entry<String, List<Claim>> group : saying.entrySet()) {
            said.append(said.length() == 0 ? "" : " against ");
            said.append('"').append(readable(group.getKey())).append("\" in ");
            StringBuilder where = new StringBuilder();
            for (Claim claim : group.getValue()) {
                where.append(where.length() == 0 ? "" : " and ").append(claim.sheet);
            }
            said.append(where);
        }
        return said.toString();
    }

    private static String readable(Object value) {
        String said = String.valueOf(value);
        return said.startsWith("[") && said.endsWith("]")
                ? said.substring(1, said.length() - 1) : said;
    }

    /**
     * Audits one sheet.
     *
     * @param sheetName what to call it in a finding
     * @param rows the whole table, both header rows included
     * @param index every name in play, so a reference can be resolved and a typo guessed at;
     *     may be null, in which case reference checks are skipped rather than guessed
     */
    public static List<Finding> of(String sheetName, List<List<String>> rows, LabelIndex index) {
        List<Finding> findings = new ArrayList<Finding>();
        if (rows == null || rows.size() < 2) {
            return findings;
        }
        String sheet = sheetName == null || sheetName.trim().isEmpty() ? "this sheet"
                : sheetName.trim();
        TemplateColumns columns = TemplateColumns.of(rows);
        List<String> headings = rows.get(0);

        duplicateIdentifiers(sheet, rows, columns, findings);
        missingIdentifiers(sheet, rows, columns, findings);
        cellByCell(sheet, rows, columns, headings, index, findings);
        unusedColumns(sheet, rows, columns, findings);
        nearDuplicateNames(sheet, rows, columns, findings);
        if (index != null) {
            ambiguities(sheet, rows, columns, headings, byLabel(index), findings);
        }
        return findings;
    }

    /**
     * The names these sheets use that mean more than one thing, worst first: a label a row is
     * given, or a name a cell refers to, that the sheets and the ontology give two things. What
     * Check sheets... and Template... count, from the same scan that makes the
     * {@link Kind#AMBIGUOUS_NAME} findings of {@link #of}: the count a warning gives is the number
     * of names the list shows (1.135.2).
     *
     * <p>Found on a knowledge graph built from sheets: Template... counted more names that mean
     * more than one thing than Check sheets... listed. Both counted every label the index held
     * twice - comments taken for names, and labels the imported ontologies each give two terms,
     * which no sheet uses - while the list held only what the sheets' own rows are named. A label
     * the ontology gives two things is the sheets' problem only where a cell names it: that cell
     * gets whichever ROBOT picks, so it is listed and counted.
     *
     * @param sheets every sheet in play, name to rows, both header rows included
     * @param index every name in play, the sheets' and the ontology's
     */
    public static List<LabelIndex.Ambiguity> ambiguousNamesIn(
            Map<String, List<List<String>>> sheets, LabelIndex index) {
        List<LabelIndex.Ambiguity> used = new ArrayList<LabelIndex.Ambiguity>();
        if (sheets == null || index == null) {
            return used;
        }
        Map<String, LabelIndex.Ambiguity> ambiguous = byLabel(index);
        Set<String> names = new java.util.HashSet<String>();
        for (Map.Entry<String, List<List<String>>> sheet : sheets.entrySet()) {
            List<List<String>> rows = sheet.getValue();
            if (rows == null || rows.size() < 2) {
                continue;
            }
            List<Finding> found = new ArrayList<Finding>();
            ambiguities(sheet.getKey(), rows, TemplateColumns.of(rows), rows.get(0), ambiguous,
                    found);
            for (Finding finding : found) {
                names.add(finding.getName());
            }
        }
        for (LabelIndex.Ambiguity ambiguity : index.ambiguous()) {
            if (names.contains(ambiguity.getLabel())) {
                used.add(ambiguity);
            }
        }
        return used;
    }

    /**
     * The cells of one sheet that write a datatype their column already gives - see
     * {@link Kind#DATATYPE_TWICE} - for Template... to warn of before it builds the sheet.
     */
    public static List<Finding> datatypesWrittenTwice(String sheetName, List<List<String>> rows) {
        List<Finding> findings = new ArrayList<Finding>();
        if (rows == null || rows.size() < 2) {
            return findings;
        }
        String sheet = sheetName == null || sheetName.trim().isEmpty() ? "this sheet"
                : sheetName.trim();
        TemplateColumns columns = TemplateColumns.of(rows);
        for (int rowIndex = 2; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            for (TemplateColumns.Column column : columns.getColumns()) {
                String raw = cell(row, column.getNumber());
                if (!raw.trim().isEmpty()) {
                    datatypeTwice(sheet, rowIndex + 1, column,
                            heading(rows.get(0), column.getNumber()), raw, findings);
                }
            }
        }
        return findings;
    }

    /** Each label that names more than one thing, by the label. */
    private static Map<String, LabelIndex.Ambiguity> byLabel(LabelIndex index) {
        Map<String, LabelIndex.Ambiguity> ambiguous =
                new LinkedHashMap<String, LabelIndex.Ambiguity>();
        for (LabelIndex.Ambiguity ambiguity : index.ambiguous()) {
            ambiguous.put(ambiguity.getLabel(), ambiguity);
        }
        return ambiguous;
    }

    private static String heading(List<String> headings, int column) {
        int at = column - 1;
        return at >= 0 && at < headings.size() && headings.get(at) != null
                ? headings.get(at).trim() : "";
    }

    private static String cell(List<String> row, int column) {
        int at = column - 1;
        return row != null && at >= 0 && at < row.size() && row.get(at) != null
                ? row.get(at) : "";
    }

    private static boolean blank(List<String> row) {
        for (String each : row) {
            if (each != null && !each.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- identifiers

    private static void duplicateIdentifiers(String sheet, List<List<String>> rows,
            TemplateColumns columns, List<Finding> findings) {
        TemplateColumns.Column id = columns.firstOf(TemplateColumns.Kind.ID);
        if (id == null) {
            return;
        }
        Map<String, Integer> firstSeen = new LinkedHashMap<String, Integer>();
        for (int index = 2; index < rows.size(); index++) {
            String identifier = cell(rows.get(index), id.getNumber()).trim();
            if (identifier.isEmpty()) {
                continue;
            }
            Integer already = firstSeen.get(identifier);
            if (already == null) {
                firstSeen.put(identifier, index + 1);
                continue;
            }
            findings.add(new Finding(Kind.DUPLICATE_ID, sheet, index + 1, id.getNumber(),
                    id.getHeading(), identifier,
                    "Row " + already + " already uses this identifier. Both rows describe the "
                            + "same thing, so everything they say is merged onto it - which is "
                            + "fine if that was meant and a silent collision if it was not. "
                            + "Give one of them its own identifier, or merge the two rows.", ""));
        }
    }

    private static void missingIdentifiers(String sheet, List<List<String>> rows,
            TemplateColumns columns, List<Finding> findings) {
        TemplateColumns.Column id = columns.firstOf(TemplateColumns.Kind.ID);
        if (id == null) {
            return;
        }
        for (int index = 2; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (blank(row) || !cell(row, id.getNumber()).trim().isEmpty()) {
                continue;
            }
            findings.add(new Finding(Kind.MISSING_ID, sheet, index + 1, id.getNumber(),
                    id.getHeading(), "",
                    "This row has data but no identifier, so nothing it says is kept: there is "
                            + "no thing for it to be about. Give it an identifier, or delete the "
                            + "row.", ""));
        }
    }

    // ---------------------------------------------------------------- cell by cell

    private static void cellByCell(String sheet, List<List<String>> rows,
            TemplateColumns columns, List<String> headings, LabelIndex index,
            List<Finding> findings) {
        for (int rowIndex = 2; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (blank(row)) {
                continue;
            }
            for (TemplateColumns.Column column : columns.getColumns()) {
                String raw = cell(row, column.getNumber());
                if (raw.trim().isEmpty()) {
                    continue;
                }
                int number = rowIndex + 1;
                String name = heading(headings, column.getNumber());
                whitespace(sheet, number, column, name, raw, findings);
                typography(sheet, number, column, name, raw, findings);
                placeholder(sheet, number, column, name, raw, findings);
                datatypeTwice(sheet, number, column, name, raw, findings);
                // Class expressions are left to ROBOT. A cell there is Manchester syntax
                // rather than a name - `'has disposition' some %` is not something to look up
                // - and ROBOT already reports a bad one properly, with the row, the column and
                // the expression. Checking it here produced two false findings on the first
                // real template it met, against a sheet ROBOT built without complaint.
                if (column.namesATerm() && index != null
                        && column.getKind() != TemplateColumns.Kind.CLASS_EXPRESSION) {
                    reference(sheet, number, column, name, raw, index, findings);
                }
            }
        }
    }

    private static void whitespace(String sheet, int row, TemplateColumns.Column column,
            String heading, String raw, List<Finding> findings) {
        String what = "";
        if (raw.indexOf('\t') >= 0) {
            what = "a tab";
        } else if (raw.indexOf(NO_BREAK_SPACE) >= 0) {
            what = "a non-breaking space, which is what a web page or a word processor pastes";
        } else if (raw.indexOf("  ") >= 0) {
            what = "two spaces in a row";
        } else if (!raw.equals(raw.trim())) {
            what = "space around it";
        }
        if (what.isEmpty()) {
            return;
        }
        String tidied = raw.replace(NO_BREAK_SPACE, ' ').replace('\t', ' ')
                .replaceAll(" +", " ").trim();
        // A literal keeps its text as typed, so the space is really in the data. A reference is
        // resolved by text, so the space decides whether it resolves at all.
        String why = column.namesATerm()
                ? "This column names another term, and the name is matched as text, so "
                        + "whitespace decides whether it is found."
                : "This text is kept exactly as written, so the whitespace ends up in the "
                        + "ontology.";
        findings.add(new Finding(Kind.WHITESPACE, sheet, row, column.getNumber(), heading, raw,
                "There is " + what + " in this cell. " + why, tidied.equals(raw) ? "" : tidied));
    }

    private static void typography(String sheet, int row, TemplateColumns.Column column,
            String heading, String raw, List<Finding> findings) {
        String tidied = raw
                .replace(LEFT_SINGLE_QUOTE, '\'').replace(RIGHT_SINGLE_QUOTE, '\'')
                .replace(LEFT_DOUBLE_QUOTE, '"').replace(RIGHT_DOUBLE_QUOTE, '"')
                .replace(EN_DASH, '-').replace(EM_DASH, '-');
        if (tidied.equals(raw)) {
            return;
        }
        findings.add(new Finding(Kind.TYPOGRAPHIC_CHARACTER, sheet, row, column.getNumber(),
                heading, raw,
                "This cell has a curly quote or a long dash in it, which is what a word "
                        + "processor turns a plain one into. It is a different character, so a "
                        + "name written this way will not match the same name written plainly.",
                tidied));
    }

    private static void placeholder(String sheet, int row, TemplateColumns.Column column,
            String heading, String raw, List<Finding> findings) {
        String folded = raw.trim().toLowerCase();
        for (String placeholder : PLACEHOLDERS) {
            if (!folded.contains(placeholder)) {
                continue;
            }
            findings.add(new Finding(Kind.PLACEHOLDER, sheet, row, column.getNumber(), heading,
                    raw,
                    "This looks like text a template left behind rather than something somebody "
                            + "wrote: it contains \"" + placeholder + "\". It will go into the "
                            + "ontology exactly as it stands.", ""));
            return;
        }
    }

    /**
     * A datatype written into a cell whose column's template string gives one - see
     * {@link Kind#DATATYPE_TWICE}. Only in an {@code AT} column, where ROBOT was seen to keep the
     * suffix as text; elsewhere a {@code ^^} is how a cell is typed - {@code 8^^xsd:decimal} in an
     * {@code I} column for a data property, which ROBOT reads as a decimal - and is right.
     */
    private static void datatypeTwice(String sheet, int row, TemplateColumns.Column column,
            String heading, String raw, List<Finding> findings) {
        if (column.getKind() != TemplateColumns.Kind.ANNOTATION_TYPED
                || column.getDatatype().isEmpty() || raw.indexOf("^^") < 0) {
            return;
        }
        String datatype = column.getDatatype();
        String written = "";
        String cellType = "";
        StringBuilder alone = new StringBuilder();
        List<String> parts = split(raw, column.getSplit());
        for (int at = 0; at < parts.size(); at++) {
            String part = parts.get(at);
            int caret = part.indexOf("^^");
            if (caret >= 0 && written.isEmpty()) {
                written = part.trim();
                cellType = part.substring(caret + 2).trim();
            }
            alone.append(at == 0 ? "" : column.getSplit())
                    .append(caret < 0 ? part : withoutQuotes(part.substring(0, caret)));
        }
        findings.add(new Finding(Kind.DATATYPE_TWICE, sheet, row, column.getNumber(), heading,
                raw,
                "The template row already gives this column the datatype " + datatype + " (\""
                        + column.getSpec() + "\"), so ROBOT reads none in the cell: \"^^"
                        + cellType + "\" stays part of the text, and the value written is \""
                        + written + "\" typed as " + datatype + ", which is not a valid one."
                        + (cellType.equals(datatype) ? "" : " A value of another type than "
                                + datatype + " needs a column of its own.")
                        + " Write the value alone.",
                alone.toString()));
    }

    /** The text before a {@code ^^}, without the quotes a Turtle literal puts around it. */
    private static String withoutQuotes(String value) {
        String end = value.replaceAll("\\s+$", "");
        String core = end.trim();
        if (core.length() >= 2 && core.startsWith("\"") && core.endsWith("\"")) {
            return end.substring(0, end.indexOf('"')) + core.substring(1, core.length() - 1);
        }
        return end;
    }

    private static void reference(String sheet, int row, TemplateColumns.Column column,
            String heading, String raw, LabelIndex index, List<Finding> findings) {
        for (String part : split(raw, column.getSplit())) {
            String value = part.trim();
            if (value.isEmpty() || looksLikeAnIri(value)) {
                continue;
            }
            // A CLASS EXPRESSION COLUMN WANTS THE QUOTES. `SC %` with 'expert discussion' is
            // how ROBOT's own documentation says to name a label with a space in it, and the
            // Manchester parser needs them. Reporting it was a false positive found by
            // running this over a template written for a real project: ROBOT accepted the
            // sheet with no problems while the audit called two of its three rows wrong, and
            // a check that contradicts the builder is one nobody will believe twice.
            if (column.getKind() != TemplateColumns.Kind.CLASS_EXPRESSION
                    && value.length() > 1 && value.startsWith("'") && value.endsWith("'")) {
                String unquoted = value.substring(1, value.length() - 1);
                findings.add(new Finding(Kind.QUOTED_REFERENCE, sheet, row, column.getNumber(),
                        heading, raw,
                        "The quotes are part of the name here. Quoting a label is how a class "
                                + "expression column wants it written, and this column is not "
                                + "one - so it looks for a term called \"" + value + "\", "
                                + "quotes included.",
                        raw.replace(value, unquoted)));
                continue;
            }
            if (index.knows(value)) {
                continue;
            }
            String closest = closestTo(value, index.labels());
            findings.add(new Finding(Kind.UNRESOLVED_REFERENCE, sheet, row, column.getNumber(),
                    heading, raw,
                    closest.isEmpty()
                            ? "Nothing in this ontology or in any open sheet is called \"" + value
                                    + "\", so there is no term for this cell to point at."
                            : "Nothing is called \"" + value + "\". The closest name that does "
                                    + "exist is \"" + closest + "\".",
                    closest.isEmpty() ? "" : raw.replace(value, closest)));
        }
    }

    private static List<String> split(String raw, String separator) {
        if (separator == null || separator.isEmpty()) {
            return Collections.singletonList(raw);
        }
        List<String> parts = new ArrayList<String>();
        int at = 0;
        while (true) {
            int next = raw.indexOf(separator, at);
            if (next < 0) {
                parts.add(raw.substring(at));
                return parts;
            }
            parts.add(raw.substring(at, next));
            at = next + separator.length();
        }
    }

    /** A CURIE or a full IRI is not a name, and must not be guessed at as one. */
    private static boolean looksLikeAnIri(String value) {
        if (value.indexOf(' ') >= 0) {
            return false;
        }
        int colon = value.indexOf(':');
        return colon > 0;
    }

    // ---------------------------------------------------------------- whole columns

    private static void unusedColumns(String sheet, List<List<String>> rows,
            TemplateColumns columns, List<Finding> findings) {
        for (TemplateColumns.Column column : columns.getColumns()) {
            if (column.getKind() != TemplateColumns.Kind.UNUSED) {
                continue;
            }
            int filled = 0;
            for (int index = 2; index < rows.size(); index++) {
                if (!cell(rows.get(index), column.getNumber()).trim().isEmpty()) {
                    filled++;
                }
            }
            if (filled == 0) {
                continue;
            }
            findings.add(new Finding(Kind.UNUSED_COLUMN, sheet, 0, column.getNumber(),
                    column.getHeading(), "",
                    filled + (filled == 1 ? " cell" : " cells") + " in this column hold data, "
                            + "and the template row says nothing about the column - so every one "
                            + "of them is thrown away. Say what the column means, or move the "
                            + "data.", ""));
        }
    }

    // ---------------------------------------------------------------- duplicated things

    private static void nearDuplicateNames(String sheet, List<List<String>> rows,
            TemplateColumns columns, List<Finding> findings) {
        TemplateColumns.Column id = columns.firstOf(TemplateColumns.Kind.ID);
        List<TemplateColumns.Column> labels = columns.labelColumns();
        if (id == null || labels.isEmpty()) {
            return;
        }
        Map<String, Integer> seen = new LinkedHashMap<String, Integer>();
        Map<String, String> identifiers = new LinkedHashMap<String, String>();
        for (int index = 2; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            String identifier = cell(row, id.getNumber()).trim();
            for (TemplateColumns.Column column : labels) {
                String label = cell(row, column.getNumber()).trim();
                if (label.length() < LONG_ENOUGH_TO_COMPARE) {
                    continue;
                }
                for (Map.Entry<String, Integer> candidate : seen.entrySet()) {
                    if (candidate.getKey().equals(label)) {
                        continue;
                    }
                    if (!within(label, candidate.getKey(), CLOSE_ENOUGH)) {
                        continue;
                    }
                    // Two spellings of one name on ONE identifier is a second name for a thing,
                    // not a duplicate of it. Only a different identifier means two things.
                    if (identifier.equals(identifiers.get(candidate.getKey()))) {
                        continue;
                    }
                    findings.add(new Finding(Kind.NEAR_DUPLICATE, sheet, index + 1,
                            column.getNumber(), column.getHeading(), label,
                            "\"" + label + "\" is one edit away from \"" + candidate.getKey()
                                    + "\" in row " + candidate.getValue() + ", and the two rows "
                                    + "have different identifiers - so either this is a typo, or "
                                    + "there are two entries for one thing. Neither is something "
                                    + "a tool should decide.", ""));
                }
                seen.put(label, index + 1);
                identifiers.put(label, identifier);
            }
        }
    }

    /**
     * The names in one sheet that mean more than one thing: a label a row is given, at the first
     * row that gives it, and a name a cell refers to, at every such cell - each of those gets
     * whichever thing ROBOT picks, so each needs its IRI written. Names are labels, as
     * {@link TemplateColumns#labelColumns} says: a comment two rows share names nothing (1.135.2).
     * A class expression's cell refers to names too - see {@link #namesInExpressions} (1.135.2
     * review) - and an IRI or a CURIE is no name.
     */
    private static void ambiguities(String sheet, List<List<String>> rows,
            TemplateColumns columns, List<String> headings,
            Map<String, LabelIndex.Ambiguity> ambiguous, List<Finding> findings) {
        if (ambiguous.isEmpty()) {
            return;
        }
        boolean named = columns.firstOf(TemplateColumns.Kind.ID) != null;
        Set<String> reported = new LinkedHashSet<String>();
        for (int rowIndex = 2; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            for (TemplateColumns.Column column : named ? columns.labelColumns()
                    : Collections.<TemplateColumns.Column>emptyList()) {
                String label = cell(row, column.getNumber()).trim();
                LabelIndex.Ambiguity ambiguity = ambiguous.get(label);
                if (ambiguity != null && reported.add(label)) {
                    findings.add(new Finding(Kind.AMBIGUOUS_NAME, sheet, rowIndex + 1,
                            column.getNumber(), column.getHeading(), label,
                            ambiguity.describe(), "", label));
                }
            }
            for (TemplateColumns.Column column : columns.getColumns()) {
                if (!column.namesATerm()) {
                    continue;
                }
                String raw = cell(row, column.getNumber());
                for (String name
                        : column.getKind() == TemplateColumns.Kind.CLASS_EXPRESSION
                                ? namesInExpressions(raw, column) : namesReferredTo(raw, column)) {
                    LabelIndex.Ambiguity ambiguity = ambiguous.get(name);
                    if (ambiguity != null) {
                        findings.add(new Finding(Kind.AMBIGUOUS_NAME, sheet, rowIndex + 1,
                                column.getNumber(), heading(headings, column.getNumber()), raw,
                                ambiguity.describe(), "", name));
                    }
                }
            }
        }
    }

    /** The names a cell refers to: each of its values but an IRI, a CURIE or a quoted one. */
    private static List<String> namesReferredTo(String raw, TemplateColumns.Column column) {
        List<String> names = new ArrayList<String>();
        if (raw.trim().isEmpty()) {
            return names;
        }
        for (String part : split(raw, column.getSplit())) {
            String value = part.trim();
            if (!value.isEmpty() && !looksLikeAnIri(value)
                    && !(value.length() > 1 && value.startsWith("'") && value.endsWith("'"))) {
                names.add(value);
            }
        }
        return names;
    }

    /** The words of Manchester syntax, which name no term. */
    private static final Set<String> KEYWORDS = new java.util.HashSet<String>(
            java.util.Arrays.asList("and", "or", "not", "some", "only", "value", "min", "max",
                    "exactly", "that", "inverse", "self", "true", "false"));

    /**
     * The names a class expression's cell refers to - in {@code C}, {@code SC}, {@code EC},
     * {@code DC}, {@code DOMAIN} and {@code RANGE} columns, where ROBOT resolves each by its label
     * and picks one of two silently (1.135.2 review) - read as ROBOT 1.9.8 reads the cell
     * (QuotedEntityChecker.wrap): a cell that starts with a quote or a bracket is an expression,
     * and each quoted name in it, and each word that is no keyword, IRI, CURIE or number, is a
     * name; any other cell with a space in it is one name, quoted whole, its keywords, commas and
     * brackets included - "measured value", or "plan and not area", which nothing is called and
     * ROBOT fails on; a cell of one word is one name, or an expression when it holds a bracket.
     * A literal, a full IRI in angle brackets and a datatype's facets in square ones name nothing.
     */
    private static List<String> namesInExpressions(String raw, TemplateColumns.Column column) {
        List<String> names = new ArrayList<String>();
        if (raw.trim().isEmpty()) {
            return names;
        }
        for (String part : split(raw, column.getSplit())) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            char first = value.charAt(0);
            if (first == '\'' || first == '"' || first == '(') {
                names.addAll(namesInExpression(value));
            } else if (value.matches("(?s).*\\s.*")) {
                names.add(value);
            } else if (value.matches(".*[(){}\\[\\]].*")) {
                names.addAll(namesInExpression(value));
            } else if (!looksLikeAnIri(value)) {
                names.add(value);
            }
        }
        return names;
    }

    private static List<String> namesInExpression(String text) {
        List<String> names = new ArrayList<String>();
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '\'' || c == '"' || c == '<' || c == '[') {
                char close = c == '<' ? '>' : c == '[' ? ']' : c;
                int end = text.indexOf(close, at + 1);
                if (end < 0) {
                    break;
                }
                String quoted = text.substring(at + 1, end).trim();
                if (c == '\'' && !quoted.isEmpty()) {
                    names.add(quoted);
                }
                at = end + 1;
            } else if (Character.isLetter(c) || c == '_') {
                int start = at;
                while (at < text.length() && !Character.isWhitespace(text.charAt(at))
                        && "()[]{},'\"<>^".indexOf(text.charAt(at)) < 0) {
                    at++;
                }
                String word = text.substring(start, at);
                if (!KEYWORDS.contains(word.toLowerCase(java.util.Locale.ROOT))
                        && !looksLikeAnIri(word)) {
                    names.add(word);
                }
            } else {
                at++;
            }
        }
        return names;
    }

    /** The closest known name, or empty when nothing is close enough to be worth offering. */
    static String closestTo(String value, Iterable<String> known) {
        if (value.length() < LONG_ENOUGH_TO_COMPARE) {
            return "";
        }
        String best = "";
        int bestDistance = CLOSE_ENOUGH + 1;
        for (String candidate : known) {
            if (Math.abs(candidate.length() - value.length()) > CLOSE_ENOUGH
                    || candidate.length() < LONG_ENOUGH_TO_COMPARE) {
                continue;
            }
            int distance = distance(value, candidate, bestDistance);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean within(String one, String other, int limit) {
        return Math.abs(one.length() - other.length()) <= limit
                && !onlyTheNumbersDiffer(one, other)
                && distance(one, other, limit + 1) <= limit;
    }

    /**
     * Whether two names are the same but for their digits, which makes them a series.
     *
     * <p>Without this the check is unusable. The MatWerk {@code temporal} sheet names its rows
     * {@code temporal region 1} to {@code temporal region 171}; every one of them is a single
     * edit from several others, and the sheet alone produced 10,059 near-duplicate findings -
     * all of them wrong, because serial names are the point rather than a typo. Stripping the
     * digits and comparing what is left separates {@code temporal region 1} from
     * {@code temporal region 2} without losing {@code Stuttgart} from {@code Stuttgart_} or
     * {@code Fraunhofer-Gesellschaft} from {@code Fraunhofer-Gesellschafft}.
     */
    private static boolean onlyTheNumbersDiffer(String one, String other) {
        return withoutDigits(one).equals(withoutDigits(other));
    }

    private static String withoutDigits(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int at = 0; at < text.length(); at++) {
            if (!Character.isDigit(text.charAt(at))) {
                out.append(text.charAt(at));
            }
        }
        return out.toString();
    }

    /**
     * Levenshtein distance, abandoned as soon as it passes {@code ceiling}.
     *
     * <p>Two rows of integers rather than a matrix, and an early exit, because this runs over
     * every pair of similar-length names in a sheet - and one real sheet here has 5,096 rows.
     */
    private static int distance(String one, String other, int ceiling) {
        int width = other.length();
        int[] previous = new int[width + 1];
        int[] current = new int[width + 1];
        for (int at = 0; at <= width; at++) {
            previous[at] = at;
        }
        for (int row = 1; row <= one.length(); row++) {
            current[0] = row;
            int best = current[0];
            for (int column = 1; column <= width; column++) {
                int substitute = previous[column - 1]
                        + (one.charAt(row - 1) == other.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(Math.min(current[column - 1] + 1,
                        previous[column] + 1), substitute);
                best = Math.min(best, current[column]);
            }
            if (best >= ceiling) {
                return ceiling;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[width];
    }
}
