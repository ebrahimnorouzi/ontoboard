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
        UNUSED_COLUMN
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

        Finding(Kind kind, String sheet, int row, int column, String heading, String cell,
                String message, String suggestion) {
            this.kind = kind;
            this.sheet = sheet;
            this.row = row;
            this.column = column;
            this.heading = heading;
            this.cell = cell == null ? "" : cell;
            this.message = message;
            this.suggestion = suggestion == null ? "" : suggestion;
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

    private SheetAudit() {
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
        ambiguousNames(sheet, rows, columns, index, findings);
        return findings;
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
                if (column.namesATerm() && index != null) {
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

    private static void reference(String sheet, int row, TemplateColumns.Column column,
            String heading, String raw, LabelIndex index, List<Finding> findings) {
        for (String part : split(raw, column.getSplit())) {
            String value = part.trim();
            if (value.isEmpty() || looksLikeAnIri(value)) {
                continue;
            }
            if (value.length() > 1 && value.startsWith("'") && value.endsWith("'")) {
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
        List<TemplateColumns.Column> labels = labelColumns(columns);
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

    private static void ambiguousNames(String sheet, List<List<String>> rows,
            TemplateColumns columns, LabelIndex index, List<Finding> findings) {
        if (index == null) {
            return;
        }
        TemplateColumns.Column id = columns.firstOf(TemplateColumns.Kind.ID);
        List<TemplateColumns.Column> labels = labelColumns(columns);
        if (id == null || labels.isEmpty()) {
            return;
        }
        Set<String> reported = new LinkedHashSet<String>();
        for (int rowIndex = 2; rowIndex < rows.size(); rowIndex++) {
            for (TemplateColumns.Column column : labels) {
                String label = cell(rows.get(rowIndex), column.getNumber()).trim();
                if (label.isEmpty() || !reported.add(label)) {
                    continue;
                }
                for (LabelIndex.Ambiguity ambiguity : index.ambiguous()) {
                    if (!ambiguity.getLabel().equals(label)) {
                        continue;
                    }
                    findings.add(new Finding(Kind.AMBIGUOUS_NAME, sheet, rowIndex + 1,
                            column.getNumber(), column.getHeading(), label,
                            ambiguity.describe(), ""));
                }
            }
        }
    }

    private static List<TemplateColumns.Column> labelColumns(TemplateColumns columns) {
        List<TemplateColumns.Column> found = new ArrayList<TemplateColumns.Column>();
        for (TemplateColumns.Column column : columns.getColumns()) {
            if (column.getKind() == TemplateColumns.Kind.LABEL
                    || column.getKind() == TemplateColumns.Kind.ANNOTATION_LANGUAGE) {
                found.add(column);
                continue;
            }
            if (column.getKind() != TemplateColumns.Kind.ANNOTATION) {
                continue;
            }
            String property = column.getProperty();
            int cut = Math.max(property.lastIndexOf('#'), property.lastIndexOf(':'));
            String local = property.substring(cut + 1);
            if ("label".equals(local) || local.startsWith("label@")) {
                found.add(column);
            }
        }
        return found;
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
