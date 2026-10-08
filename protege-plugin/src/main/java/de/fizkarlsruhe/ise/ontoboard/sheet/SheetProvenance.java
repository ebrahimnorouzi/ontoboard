package de.fizkarlsruhe.ise.ontoboard.sheet;

import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns;
import java.util.ArrayList;
import java.util.List;

/**
 * Who added a row, when, and who touched it last - recorded in the sheet itself.
 *
 * <p>The sheet is the source of truth for a knowledge graph built this way: the generated OWL
 * is rebuilt from it on every run, so provenance written only into the OWL is provenance that
 * disappears at the next build. It has to live in the spreadsheet, as columns, and let ROBOT
 * carry it into the graph like any other annotation.
 *
 * <h2>The same vocabulary the rest of the plugin uses</h2>
 *
 * <p>Deliberately not a second scheme. {@link Provenance} already decided this question for
 * terms edited in Prot&eacute;g&eacute;, and argued it: {@code dcterms:contributor} for the
 * person rather than {@code dcterms:creator}, which is effectively dead in OBO;
 * {@code dcterms:created} for when the thing was introduced; and {@code dcterms:date} for when
 * it last changed. Those three IRIs are read from that class rather than written out again
 * here, so a change there cannot leave the two halves disagreeing.
 *
 * <p>Day precision, for the reason given there: a last-modified stamp is rewritten on every
 * edit, and second precision would put a diff in the repository every time somebody fixed a
 * typo.
 *
 * <h2>Three columns, written the way ROBOT needs them</h2>
 *
 * <pre>
 *   AI http://purl.org/dc/terms/contributor      an ORCID, which is an IRI
 *   A  http://purl.org/dc/terms/contributor      a plain name, when there is no ORCID
 *   AT http://purl.org/dc/terms/created^^xsd:date
 *   AT http://purl.org/dc/terms/date^^xsd:date
 * </pre>
 *
 * <p>The contributor column is {@code AI} for an ORCID and {@code A} for a name, because an
 * ORCID is a thing with an IRI and a name is a string - and an {@code AI} column holding a
 * plain name is the defect this plugin reports in 1.106.0. Which one a sheet gets is decided
 * once, when the columns are added, from whether the person has an ORCID.
 *
 * <p>Nothing here is automatic. {@link #stamp} is called when somebody asks for it, and
 * {@link #ensureColumns} changes the template row - which is a visible edit to a file in git,
 * not something to do behind a user's back.
 */
public final class SheetProvenance {

    /** {@code dcterms:contributor}, from the class that chose it. */
    public static final String CONTRIBUTOR = Provenance.CONTRIBUTOR.toString();

    /** {@code dcterms:created}. */
    public static final String CREATED = Provenance.CREATED.toString();

    /** {@code dcterms:date}, meaning last modified. */
    public static final String MODIFIED = Provenance.MODIFIED.toString();

    private static final String DATE = "^^xsd:date";

    /** What a row says about itself. */
    public static final class Record {
        private final List<String> contributors;
        private final String created;
        private final String modified;

        Record(List<String> contributors, String created, String modified) {
            this.contributors = contributors;
            this.created = created;
            this.modified = modified;
        }

        /** Everybody named, in the order the cell names them. */
        public List<String> getContributors() {
            return contributors;
        }

        /** The creation date, or empty. */
        public String getCreated() {
            return created;
        }

        /** The last-modified date, or empty. */
        public String getModified() {
            return modified;
        }

        public boolean isEmpty() {
            return contributors.isEmpty() && created.isEmpty() && modified.isEmpty();
        }

        /** One line for a status bar or a tooltip. */
        public String describe() {
            if (isEmpty()) {
                return "Nothing recorded about who added this row or when.";
            }
            StringBuilder text = new StringBuilder();
            if (!created.isEmpty()) {
                text.append("Added ").append(created);
            }
            if (!contributors.isEmpty()) {
                text.append(text.length() == 0 ? "By " : " by ");
                for (int at = 0; at < contributors.size(); at++) {
                    text.append(at == 0 ? "" : ", ").append(shorten(contributors.get(at)));
                }
            }
            if (!modified.isEmpty() && !modified.equals(created)) {
                text.append(text.length() == 0 ? "Last changed " : ", last changed ")
                        .append(modified);
            }
            return text.append('.').toString();
        }

        private static String shorten(String agent) {
            int cut = agent.lastIndexOf('/');
            return agent.startsWith("http") && cut >= 0 && cut < agent.length() - 1
                    ? agent.substring(cut + 1) : agent;
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    private SheetProvenance() {
    }

    /**
     * Adds the three columns to a sheet that has not got them. Returns how many were added.
     *
     * @param orcid whether the person has an ORCID, which decides whether the contributor
     *     column holds an IRI or a string
     */
    public static int ensureColumns(SheetBook book, String sheetName, boolean orcid) {
        SheetBook.Sheet sheet = book == null ? null : book.get(sheetName);
        if (sheet == null) {
            return 0;
        }
        int added = 0;
        if (columnFor(book, sheetName, CONTRIBUTOR) == 0) {
            // SPLIT=| because a row can have more than one contributor, and without it ROBOT
            // reads the whole cell as a single value. The test that found this put two ORCIDs
            // in a cell and watched one bogus IRI arrive in the graph instead of two people.
            // A bar rather than a comma: ORCIDs do not contain either, but names do contain
            // commas, and the same column holds a plain name when there is no ORCID.
            added += addColumn(book, sheetName, "Added by",
                    (orcid ? "AI " : "A ") + CONTRIBUTOR + " SPLIT=|") ? 1 : 0;
        }
        if (columnFor(book, sheetName, CREATED) == 0) {
            added += addColumn(book, sheetName, "Added on", "AT " + CREATED + DATE) ? 1 : 0;
        }
        if (columnFor(book, sheetName, MODIFIED) == 0) {
            added += addColumn(book, sheetName, "Last changed",
                    "AT " + MODIFIED + DATE) ? 1 : 0;
        }
        return added;
    }

    /** Whether the sheet already records provenance, so a caller can offer to add it or not. */
    public static boolean hasColumns(SheetBook book, String sheetName) {
        return columnFor(book, sheetName, CONTRIBUTOR) > 0
                || columnFor(book, sheetName, CREATED) > 0;
    }

    /**
     * Records that somebody added or changed this row today.
     *
     * <p>Creation is written once and never overwritten - that is what makes it creation. The
     * last-changed date is rewritten every time. A contributor is added rather than replaced,
     * because two people editing one row is the normal case in a shared sheet, and the one who
     * introduced it does not stop being its author because somebody fixed a typo.
     *
     * @param agent an ORCID IRI or a name
     * @param isoDate the day, as {@code yyyy-MM-dd}; {@link Provenance#today()} supplies it
     * @return whether anything changed
     */
    public static boolean stamp(SheetBook book, String sheetName, int row, String agent,
            String isoDate) {
        if (book == null || book.get(sheetName) == null || row < SheetBook.FIRST_DATA_ROW) {
            return false;
        }
        String who = agent == null ? "" : agent.trim();
        String when = isoDate == null || isoDate.trim().isEmpty()
                ? Provenance.today() : isoDate.trim();
        boolean changed = false;

        int created = columnFor(book, sheetName, CREATED);
        if (created > 0 && book.cell(sheetName, row, created).trim().isEmpty()) {
            changed |= book.setCell(sheetName, row, created, when);
        }
        int modified = columnFor(book, sheetName, MODIFIED);
        if (modified > 0) {
            changed |= book.setCell(sheetName, row, modified, when);
        }
        int contributor = columnFor(book, sheetName, CONTRIBUTOR);
        if (contributor > 0 && !who.isEmpty()) {
            String already = book.cell(sheetName, row, contributor).trim();
            if (already.isEmpty()) {
                changed |= book.setCell(sheetName, row, contributor, who);
            } else if (!namesAlready(book, sheetName, contributor, already, who)) {
                String separator = separatorOf(book, sheetName, contributor);
                changed |= book.setCell(sheetName, row, contributor,
                        already + separator + who);
            }
        }
        return changed;
    }

    /** Stamps every row that has anything in it, for a sheet somebody is adopting. */
    public static int stampEveryRow(SheetBook book, String sheetName, String agent,
            String isoDate) {
        SheetBook.Sheet sheet = book == null ? null : book.get(sheetName);
        if (sheet == null) {
            return 0;
        }
        int stamped = 0;
        for (int row = SheetBook.FIRST_DATA_ROW; row <= sheet.getLastRow(); row++) {
            if (!rowHasAnything(book, sheetName, row)) {
                continue;
            }
            if (stamp(book, sheetName, row, agent, isoDate)) {
                stamped++;
            }
        }
        return stamped;
    }

    /** What a row records about itself. Never null. */
    public static Record of(SheetBook book, String sheetName, int row) {
        List<String> contributors = new ArrayList<String>();
        if (book == null || book.get(sheetName) == null) {
            return new Record(contributors, "", "");
        }
        int contributor = columnFor(book, sheetName, CONTRIBUTOR);
        if (contributor > 0) {
            String cell = book.cell(sheetName, row, contributor).trim();
            if (!cell.isEmpty()) {
                String separator = separatorOf(book, sheetName, contributor);
                for (String part : cell.split(
                        java.util.regex.Pattern.quote(separator), -1)) {
                    if (!part.trim().isEmpty()) {
                        contributors.add(part.trim());
                    }
                }
            }
        }
        int created = columnFor(book, sheetName, CREATED);
        int modified = columnFor(book, sheetName, MODIFIED);
        return new Record(contributors,
                created > 0 ? book.cell(sheetName, row, created).trim() : "",
                modified > 0 ? book.cell(sheetName, row, modified).trim() : "");
    }

    /** How many rows with data say nothing about where they came from. */
    public static int rowsWithNoProvenance(SheetBook book, String sheetName) {
        SheetBook.Sheet sheet = book == null ? null : book.get(sheetName);
        if (sheet == null || !hasColumns(book, sheetName)) {
            return 0;
        }
        int bare = 0;
        for (int row = SheetBook.FIRST_DATA_ROW; row <= sheet.getLastRow(); row++) {
            if (rowHasAnything(book, sheetName, row) && of(book, sheetName, row).isEmpty()) {
                bare++;
            }
        }
        return bare;
    }

    // ---------------------------------------------------------------- the columns

    /**
     * The column annotating with this property, or 0.
     *
     * <p>Matched on the property rather than the heading, because the heading is the author's
     * and they may call it anything. The spec can name the property in full or as a CURIE, so
     * both the whole IRI and its last segment are compared.
     */
    private static int columnFor(SheetBook book, String sheetName, String property) {
        SheetBook.Sheet sheet = book == null ? null : book.get(sheetName);
        if (sheet == null) {
            return 0;
        }
        String local = property.substring(property.lastIndexOf('/') + 1);
        for (TemplateColumns.Column column : sheet.getColumns().getColumns()) {
            if (!column.holdsALiteral()
                    && column.getKind() != TemplateColumns.Kind.ANNOTATION_IRI) {
                continue;
            }
            String named = column.getProperty();
            if (named.equals(property)) {
                return column.getNumber();
            }
            int cut = Math.max(named.lastIndexOf('/'), named.lastIndexOf(':'));
            if (cut >= 0 && named.substring(cut + 1).equals(local)) {
                return column.getNumber();
            }
        }
        return 0;
    }

    private static String separatorOf(SheetBook book, String sheetName, int column) {
        TemplateColumns.Column spec = book.get(sheetName).getColumns().at(column);
        return spec == null || spec.getSplit().isEmpty() ? "|" : spec.getSplit();
    }

    private static boolean namesAlready(SheetBook book, String sheetName, int column,
            String cell, String who) {
        String separator = separatorOf(book, sheetName, column);
        for (String part : cell.split(java.util.regex.Pattern.quote(separator), -1)) {
            if (part.trim().equals(who)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a column to every row, headings included.
     *
     * <p>Appended at the right rather than inserted, so no existing column number moves - every
     * finding, every message and anything a caller is holding would otherwise be one column
     * out.
     */
    private static boolean addColumn(SheetBook book, String sheetName, String heading,
            String spec) {
        SheetBook.Sheet sheet = book.get(sheetName);
        int at = book.width(sheetName) + 1;
        boolean changed = book.setCell(sheetName, 1, at, heading);
        changed |= book.setCell(sheetName, 2, at, spec);
        for (int row = SheetBook.FIRST_DATA_ROW; row <= sheet.getLastRow(); row++) {
            book.setCell(sheetName, row, at, "");
        }
        return changed;
    }

    private static boolean rowHasAnything(SheetBook book, String sheetName, int row) {
        for (int column = 1; column <= book.width(sheetName); column++) {
            if (!book.cell(sheetName, row, column).trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
