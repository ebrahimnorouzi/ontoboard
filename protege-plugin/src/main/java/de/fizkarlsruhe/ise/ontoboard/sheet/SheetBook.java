package de.fizkarlsruhe.ise.ontoboard.sheet;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns;
import de.fizkarlsruhe.ise.ontoboard.robot.TemplateSheet;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * A folder of spreadsheets, open for editing.
 *
 * <p>The model under the sheet editor. It holds every sheet in the folder at once, because that
 * is the only way the interesting questions have answers: a reference in one sheet almost
 * always names a row in another - measured on the MatWerk knowledge graph, every reference in
 * its {@code dataportal} sheet is a plain-text name whose target is defined five sheets
 * upstream - so completion, resolution and the audit all need the whole set.
 *
 * <h2>Rows and columns are numbered as a spreadsheet numbers them</h2>
 *
 * <p>Row 1 is the author's headings, row 2 is ROBOT's, and the first row of data is row 3.
 * Columns are 1-based. Every method here and every finding from {@link SheetAudit} uses those
 * numbers, so what a person reads in a message matches what they see in their spreadsheet.
 * Internal list indices are nobody else's business.
 *
 * <h2>Nothing is written until it is asked for</h2>
 *
 * <p>Edits change this model and mark the sheet unsaved. {@link #save} writes one sheet back in
 * the format it was read in. A file is never touched as a side effect of anything else, because
 * these files are in git and somebody else is editing them.
 *
 * <h2>Undo is here rather than in Protege</h2>
 *
 * <p>Protege's undo stack covers the ontology. These edits are to files, so it cannot see them,
 * and an editor without undo is one people are afraid to use. The stack is bounded: a sheet of
 * 5,096 rows with unbounded history would hold several copies of itself.
 *
 * <p>No Swing and no Protege types, so the whole model is testable headlessly - which is where
 * the mutation logic belongs, rather than behind a table.
 */
public final class SheetBook {

    /** How many edits can be undone. Deep enough to recover from a mistake, not a session. */
    private static final int HISTORY = 200;

    /** The first row that holds data, as a spreadsheet numbers rows. */
    public static final int FIRST_DATA_ROW = 3;

    /** One sheet: its file, its rows, and whether it has been changed. */
    public static final class Sheet {
        private final String name;
        private final File file;
        private final List<List<String>> rows;
        private final char delimiter;
        private boolean unsaved;

        /**
         * When the file was last read or written by us, as the filesystem reports it.
         *
         * <p>Kept so that saving can tell the difference between a file nobody has touched and
         * a file that has moved under us - a {@code git pull}, a merge, the pipeline that
         * rebuilds these sheets, or the same folder open in Excel. Saving was a bare write, so
         * in all of those cases it silently replaced somebody else's work with a copy of a
         * file read minutes earlier.
         */
        private long readAt;

        Sheet(String name, File file, List<List<String>> rows, char delimiter) {
            this.name = name;
            this.file = file;
            this.rows = rows;
            this.delimiter = delimiter;
            this.readAt = file == null ? 0L : file.lastModified();
        }

        /** The file's name without its extension, which is what references and findings use. */
        public String getName() {
            return name;
        }

        /** Null for a sheet that was never on disk. */
        public File getFile() {
            return file;
        }

        /** Both header rows plus the data, live - edits through {@link SheetBook} change it. */
        public List<List<String>> getRows() {
            return rows;
        }

        /** How many rows of data, excluding the two header rows. */
        public int getDataRows() {
            return Math.max(0, rows.size() - 2);
        }

        /** The last row number a spreadsheet would show, or 2 when there is no data. */
        public int getLastRow() {
            return rows.size();
        }

        public boolean isUnsaved() {
            return unsaved;
        }

        /** The typed columns, re-read each time because the template row can be edited too. */
        public TemplateColumns getColumns() {
            return TemplateColumns.of(rows);
        }

        @Override
        public String toString() {
            return name + (unsaved ? " (unsaved)" : "") + " " + getDataRows() + " rows";
        }
    }

    /**
     * Saving would have overwritten a file that changed since it was read.
     *
     * <p>Its own type so that a caller can offer the only two answers that make sense -
     * overwrite it, or reload and lose the edits - rather than printing a message and carrying
     * on. {@link #getSheet} and {@link #getFile} are there so the question can name them.
     */
    public static final class ChangedOnDisk extends IOException {
        private static final long serialVersionUID = 1L;

        private final String sheet;
        private final File file;

        ChangedOnDisk(String sheet, File file) {
            super(file.getName() + " changed on disk after it was opened here, so saving "
                    + sheet + " would overwrite whatever that change was.");
            this.sheet = sheet;
            this.file = file;
        }

        public String getSheet() {
            return sheet;
        }

        public File getFile() {
            return file;
        }
    }

    /** One reversible change. */
    private static final class Change {
        private final String sheet;
        private final String what;
        private final Runnable undo;

        Change(String sheet, String what, Runnable undo) {
            this.sheet = sheet;
            this.what = what;
            this.undo = undo;
        }
    }

    /**
     * A file in the folder that is not open, and why it is not.
     *
     * <p>This type exists because the alternative was a {@code continue}. A file ROBOT's reader
     * throws on was skipped so that one bad file could not stop the other twenty-five from
     * opening - which is right - but nothing then said it had been skipped, so the file was
     * simply absent: no tab, no message, nothing in the log. Two sheets of a twenty-six sheet
     * knowledge graph went missing that way, and the only trace was a tab count nobody counts.
     */
    public static final class Unreadable {
        private final File file;
        private final String reason;

        Unreadable(File file, String reason) {
            this.file = file;
            this.reason = reason;
        }

        public File getFile() {
            return file;
        }

        /** The name the sheet would have had. */
        public String getName() {
            return stem(file.getName());
        }

        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return getName() + ": " + reason;
        }
    }

    private final Map<String, Sheet> sheets = new LinkedHashMap<String, Sheet>();
    private final List<Unreadable> unreadable = new ArrayList<Unreadable>();
    private final List<Change> history = new ArrayList<Change>();

    /** Where changes go while a gesture is being recorded, or null. See {@link #asOneChange}. */
    private List<Change> group;
    private OWLOntology ontology;
    private String ontologyName = "the open ontology";
    private LabelIndex index;

    private SheetBook() {
    }

    /** An empty book, for a caller building one sheet at a time or in a test. */
    public static SheetBook empty() {
        return new SheetBook();
    }

    /**
     * Every {@code .tsv} and {@code .csv} in a folder.
     *
     * <p>A file that cannot be read, or that has fewer than two rows and so has no template
     * row, is skipped rather than failing the lot - one bad file in a folder of twenty-six
     * should not stop the other twenty-five from opening.
     */
    public static SheetBook open(File folder) {
        SheetBook book = new SheetBook();
        if (folder == null || !folder.isDirectory()) {
            return book;
        }
        File[] files = folder.listFiles();
        if (files == null) {
            return book;
        }
        List<File> ordered = new ArrayList<File>(Arrays.asList(files));
        Collections.sort(ordered, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File file : ordered) {
            String lower = file.getName().toLowerCase();
            if (!file.isFile() || !(lower.endsWith(".tsv") || lower.endsWith(".csv"))) {
                continue;
            }
            try {
                List<List<String>> rows = new ArrayList<List<String>>();
                for (List<String> row : TemplateSheet.read(file)) {
                    rows.add(new ArrayList<String>(row));
                }
                if (rows.size() < 2) {
                    book.unreadable.add(new Unreadable(file, "it has no template row: a sheet "
                            + "needs a heading row and a row of ROBOT template strings under "
                            + "it"));
                    continue;
                }
                book.add(stem(file.getName()), file, rows, lower.endsWith(".csv") ? ',' : '\t');
            } catch (RuntimeException cannotRead) {
                book.unreadable.add(new Unreadable(file, because(cannotRead)));
            }
        }
        return book;
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Adds a sheet that is already in memory. */
    public Sheet add(String name, File file, List<List<String>> rows, char delimiter) {
        Sheet sheet = new Sheet(name, file, rows, delimiter);
        sheets.put(name, sheet);
        index = null;
        return sheet;
    }

    /**
     * The ontology whose names should also be completable.
     *
     * <p>Held rather than indexed immediately: a book is opened before anybody asks a question
     * about it, and indexing a large ontology's labels is not free.
     */
    /**
     * What to tell somebody whose file did not open.
     *
     * <p>OpenCSV names the symptom at the point it gave up, which is the end of the file, and
     * not the cause, which is a cell several hundred rows earlier. Since that is the failure
     * this folder actually hits, it gets translated.
     */
    private static String because(RuntimeException cannotRead) {
        String said = cannotRead.getMessage();
        if (said == null || said.trim().isEmpty()) {
            return cannotRead.getClass().getSimpleName();
        }
        if (said.contains("Unterminated quoted field")) {
            return "a cell opens a double quote and never closes it, so the reader ran to the "
                    + "end of the file looking for the other one. Quote the cell, or double the "
                    + "quote inside it.";
        }
        return said.trim();
    }

    /**
     * The files in the folder that are not open, in the order they were found.
     *
     * <p>Empty for the ordinary case. A caller that opened a folder is expected to say what is
     * in here, because a sheet that is not open is a sheet whose rows nobody is editing and
     * whose errors nobody is seeing.
     */
    public List<Unreadable> getUnreadable() {
        return Collections.unmodifiableList(new ArrayList<Unreadable>(unreadable));
    }

    public SheetBook against(OWLOntology open, String name) {
        this.ontology = open;
        if (name != null && !name.trim().isEmpty()) {
            this.ontologyName = name.trim();
        }
        index = null;
        return this;
    }

    public List<Sheet> getSheets() {
        return Collections.unmodifiableList(new ArrayList<Sheet>(sheets.values()));
    }

    public Sheet get(String name) {
        return sheets.get(name);
    }

    public int size() {
        return sheets.size();
    }

    /** Whether anything at all has unsaved changes. */
    public boolean isUnsaved() {
        for (Sheet sheet : sheets.values()) {
            if (sheet.unsaved) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- reading cells

    /** The cell at a spreadsheet's own row and column numbers, or empty. */
    public String cell(String sheetName, int row, int column) {
        Sheet sheet = sheets.get(sheetName);
        if (sheet == null || row < 1 || row > sheet.rows.size() || column < 1) {
            return "";
        }
        List<String> line = sheet.rows.get(row - 1);
        return column <= line.size() && line.get(column - 1) != null
                ? line.get(column - 1) : "";
    }

    /** How wide the sheet is: the most columns any row has, template row included. */
    public int width(String sheetName) {
        Sheet sheet = sheets.get(sheetName);
        if (sheet == null) {
            return 0;
        }
        int widest = 0;
        for (List<String> row : sheet.rows) {
            widest = Math.max(widest, row.size());
        }
        return widest;
    }

    // ---------------------------------------------------------------- changing them

    /**
     * Sets one cell.
     *
     * <p>Rows short of the column are padded rather than refused: a TSV written by hand often
     * stops at the last filled cell, and a person clicking an empty cell at the end of such a
     * row is not making a mistake.
     *
     * @return whether anything changed
     */
    public boolean setCell(String sheetName, int row, int column, String value) {
        final Sheet sheet = sheets.get(sheetName);
        if (sheet == null || row < 1 || row > sheet.rows.size() || column < 1) {
            return false;
        }
        final List<String> line = sheet.rows.get(row - 1);
        while (line.size() < column) {
            line.add("");
        }
        final String before = line.get(column - 1) == null ? "" : line.get(column - 1);
        final String after = value == null ? "" : value;
        if (before.equals(after)) {
            return false;
        }
        final int at = column - 1;
        line.set(at, after);
        touched(sheet, "edit of " + sheetName + " row " + row + " column " + column,
                new Runnable() {
                    @Override
                    public void run() {
                        line.set(at, before);
                    }
                });
        return true;
    }

    /** A new empty row at the end, returned as its spreadsheet row number. */
    public int addRow(String sheetName) {
        Sheet sheet = sheets.get(sheetName);
        return sheet == null ? 0 : insertRow(sheetName, sheet.rows.size() + 1);
    }

    /**
     * A new empty row, pushing anything at that position down.
     *
     * @param row where to put it, as a spreadsheet numbers rows; never above the header rows
     * @return the row number it went in at, or 0 if it could not
     */
    public int insertRow(String sheetName, int row) {
        final Sheet sheet = sheets.get(sheetName);
        if (sheet == null) {
            return 0;
        }
        final int at = Math.max(2, Math.min(row - 1, sheet.rows.size()));
        List<String> blank = new ArrayList<String>();
        for (int column = 0; column < width(sheetName); column++) {
            blank.add("");
        }
        sheet.rows.add(at, blank);
        touched(sheet, "new row in " + sheetName, new Runnable() {
            @Override
            public void run() {
                sheet.rows.remove(at);
            }
        });
        return at + 1;
    }

    /** A copy of a row, placed directly below it. Returns the new row's number, or 0. */
    public int duplicateRow(String sheetName, int row) {
        final Sheet sheet = sheets.get(sheetName);
        if (sheet == null || row < FIRST_DATA_ROW || row > sheet.rows.size()) {
            return 0;
        }
        final int at = row;
        sheet.rows.add(at, new ArrayList<String>(sheet.rows.get(row - 1)));
        touched(sheet, "copy of " + sheetName + " row " + row, new Runnable() {
            @Override
            public void run() {
                sheet.rows.remove(at);
            }
        });
        return at + 1;
    }

    /** Removes a data row. The header rows cannot be removed this way. */
    public boolean deleteRow(String sheetName, int row) {
        final Sheet sheet = sheets.get(sheetName);
        if (sheet == null || row < FIRST_DATA_ROW || row > sheet.rows.size()) {
            return false;
        }
        final int at = row - 1;
        final List<String> removed = sheet.rows.remove(at);
        touched(sheet, "deletion of " + sheetName + " row " + row, new Runnable() {
            @Override
            public void run() {
                sheet.rows.add(at, removed);
            }
        });
        return true;
    }

    /**
     * Copies one cell down a column, as a spreadsheet's fill handle does.
     *
     * <p>A number at the end of the text is incremented rather than repeated, because that is
     * what somebody dragging the handle expects and because real sheets are full of it: the
     * MatWerk {@code temporal} sheet is 171 rows of {@code temporal region <n>}.
     *
     * @return how many cells were changed
     */
    public int fillDown(String sheetName, int column, int fromRow, int toRow) {
        // Only data rows, at both ends. A test filling from row 1 copied the author's own
        // heading - "City" - down over the data and reported three cells changed, which is
        // exactly the sort of thing somebody would do by dragging from the wrong place. The
        // header rows can be edited, but never by a fill.
        if (fromRow < FIRST_DATA_ROW) {
            return 0;
        }
        String source = cell(sheetName, fromRow, column);
        if (source.isEmpty() || toRow <= fromRow) {
            return 0;
        }
        int trailing = source.length();
        while (trailing > 0 && Character.isDigit(source.charAt(trailing - 1))) {
            trailing--;
        }
        final boolean numbered = trailing < source.length();
        final String stem = source.substring(0, trailing);
        final long first = numbered ? Long.parseLong(source.substring(trailing)) : 0;
        final String from = source;
        final String where = sheetName;
        final int down = column;
        final int top = fromRow;
        final int bottom = toRow;
        return asOneChange("fill down " + (toRow - fromRow) + " cells in " + sheetName,
                new Gesture() {
                    @Override
                    public int run() {
                        int changed = 0;
                        for (int row = top + 1; row <= bottom; row++) {
                            String value = numbered ? stem + (first + (row - top)) : from;
                            if (setCell(where, row, down, value)) {
                                changed++;
                            }
                        }
                        return changed;
                    }
                });
    }

    /**
     * Pastes a block of cells, the top left landing at the given position.
     *
     * <p>What arrives from a clipboard when somebody copies out of Excel. Rows beyond the end
     * of the sheet are added, because pasting forty rows into a sheet with ten is the normal
     * reason to paste.
     *
     * @return how many cells were changed
     */
    public int paste(final String sheetName, final int row, final int column,
            final List<List<String>> block) {
        if (sheets.get(sheetName) == null || block == null || block.isEmpty()) {
            return 0;
        }
        return asOneChange("paste into " + sheetName, new Gesture() {
            @Override
            public int run() {
                int changed = 0;
                for (int down = 0; down < block.size(); down++) {
                    int target = row + down;
                    while (target > sheets.get(sheetName).rows.size()) {
                        addRow(sheetName);
                    }
                    List<String> line = block.get(down);
                    for (int across = 0; across < line.size(); across++) {
                        if (setCell(sheetName, target, column + across, line.get(across))) {
                            changed++;
                        }
                    }
                }
                return changed;
            }
        });
    }

    private void touched(Sheet sheet, String what, Runnable undo) {
        sheet.unsaved = true;
        index = null;
        Change change = new Change(sheet.name, what, undo);
        if (group != null) {
            // Inside a gesture. Held aside rather than put in the history, so that forty rows
            // of pasted cells cannot push the previous forty edits off the end of it.
            group.add(change);
            return;
        }
        history.add(change);
        while (history.size() > HISTORY) {
            history.remove(0);
        }
    }

    /** A gesture that changes many cells and reports how many. */
    public interface Gesture {
        int run();
    }

    /**
     * Runs a gesture and leaves one thing to undo.
     *
     * <p>Every cell edit records its own reversal, which is right: the cell is the only thing
     * that knows what it held. But undo is a person's idea, not a cell's, and the person
     * pasted once - so pasting forty rows into two columns used to need eighty undos, during
     * which the sheet passed through seventy-nine states nobody had ever asked for. With a
     * history of 200 entries, that one paste also pushed almost everything else off the end.
     *
     * <p>Nesting is allowed and does nothing: the outermost gesture owns the entry, so a
     * gesture built out of smaller ones is still one undo.
     */
    public int asOneChange(String what, Gesture body) {
        if (group != null) {
            return body.run();
        }
        final List<Change> made = new ArrayList<Change>();
        group = made;
        int changed;
        try {
            changed = body.run();
        } finally {
            group = null;
        }
        if (made.isEmpty()) {
            return changed;
        }
        if (made.size() == 1) {
            history.add(made.get(0));
        } else {
            history.add(new Change(made.get(0).sheet, what, new Runnable() {
                @Override
                public void run() {
                    for (int at = made.size() - 1; at >= 0; at--) {
                        made.get(at).undo.run();
                    }
                }
            }));
        }
        while (history.size() > HISTORY) {
            history.remove(0);
        }
        return changed;
    }

    // ---------------------------------------------------------------- undo

    /** What undoing would reverse, or empty when there is nothing. */
    public String nextUndo() {
        return history.isEmpty() ? "" : history.get(history.size() - 1).what;
    }

    public boolean canUndo() {
        return !history.isEmpty();
    }

    /** Reverses the last change. Returns the sheet it was in, or empty. */
    public String undo() {
        if (history.isEmpty()) {
            return "";
        }
        Change change = history.remove(history.size() - 1);
        change.undo.run();
        index = null;
        return change.sheet;
    }

    // ---------------------------------------------------------------- asking questions

    /**
     * Every name in every sheet, and in the ontology if one was given.
     *
     * <p>Rebuilt on demand and then held until something changes. Measured on the 26 MatWerk
     * sheets the whole index is 6,058 names and takes well under a second, so rebuilding it
     * after an edit is cheap enough to do rather than to invalidate cleverly.
     */
    public LabelIndex index() {
        if (index == null) {
            LabelIndex built = LabelIndex.empty().plus(ontology, ontologyName);
            for (Sheet sheet : sheets.values()) {
                built = built.plus(sheet.name, sheet.rows);
            }
            index = built;
        }
        return index;
    }

    /** Where a search found something. */
    public static final class Match {
        private final String sheet;
        private final int row;
        private final int column;

        Match(String sheet, int row, int column) {
            this.sheet = sheet;
            this.row = row;
            this.column = column;
        }

        public String getSheet() {
            return sheet;
        }

        /** The row as a spreadsheet numbers rows. */
        public int getRow() {
            return row;
        }

        /** The column, 1-based. */
        public int getColumn() {
            return column;
        }

        @Override
        public String toString() {
            return sheet + " row " + row + ", column " + column;
        }
    }

    /**
     * The next cell containing {@code text}, starting after a position and wrapping round.
     *
     * <p>Across every sheet, in the order they are open, because a knowledge graph spread over
     * twenty-six sheets is one body of data and "which tab is Fraunhofer in" is the question
     * being asked. Case-insensitive and substring, which is what a person typing three letters
     * into a search box means.
     *
     * <p>Wrapping, and visiting each cell at most once, so repeated calls walk every match and
     * then come back to the first rather than stopping at the end of a sheet. The template rows
     * are searched too: a heading or a ROBOT spec is a legitimate thing to look for, and
     * excluding them would make the box lie about how many matches there are.
     *
     * @param fromSheet where to start, or null to start at the beginning
     * @return the next match, or null when there is none anywhere
     */
    public Match findNext(String text, String fromSheet, int fromRow, int fromColumn) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String wanted = text.trim().toLowerCase(java.util.Locale.ROOT);
        List<String> order = new ArrayList<String>(sheets.keySet());
        if (order.isEmpty()) {
            return null;
        }
        int startSheet = fromSheet == null ? 0 : Math.max(0, order.indexOf(fromSheet));
        for (int step = 0; step <= order.size(); step++) {
            String name = order.get((startSheet + step) % order.size());
            Sheet sheet = sheets.get(name);
            boolean first = step == 0 && fromSheet != null;
            for (int row = first ? Math.max(1, fromRow) : 1; row <= sheet.rows.size(); row++) {
                int column = first && row == Math.max(1, fromRow) ? fromColumn + 1 : 1;
                for (; column <= width(name); column++) {
                    if (cell(name, row, column).toLowerCase(java.util.Locale.ROOT)
                            .contains(wanted)) {
                        return new Match(name, row, column);
                    }
                }
            }
        }
        return null;
    }

    /** How many cells, in every sheet, contain {@code text}. */
    public int countMatches(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        String wanted = text.trim().toLowerCase(java.util.Locale.ROOT);
        int found = 0;
        for (Sheet sheet : sheets.values()) {
            for (int row = 1; row <= sheet.rows.size(); row++) {
                for (int column = 1; column <= width(sheet.name); column++) {
                    if (cell(sheet.name, row, column).toLowerCase(java.util.Locale.ROOT)
                            .contains(wanted)) {
                        found++;
                    }
                }
            }
        }
        return found;
    }

    /** Everything wrong with one sheet, checked against every name in the book. */
    public List<SheetAudit.Finding> audit(String sheetName) {
        Sheet sheet = sheets.get(sheetName);
        return sheet == null ? Collections.<SheetAudit.Finding>emptyList()
                : SheetAudit.of(sheet.name, sheet.rows, index());
    }

    /** Everything wrong with every sheet. */
    public List<SheetAudit.Finding> auditAll() {
        List<SheetAudit.Finding> found = new ArrayList<SheetAudit.Finding>();
        for (Sheet sheet : sheets.values()) {
            found.addAll(SheetAudit.of(sheet.name, sheet.rows, index()));
        }
        return found;
    }

    /** Names to offer for what somebody has typed into a cell that names a term. */
    public List<LabelIndex.Entry> completionsFor(String sheetName, int column, String typed,
            int limit) {
        Sheet sheet = sheets.get(sheetName);
        if (sheet == null) {
            return Collections.emptyList();
        }
        TemplateColumns.Column spec = sheet.getColumns().at(column);
        if (spec == null || !spec.namesATerm()) {
            return Collections.emptyList();
        }
        return index().suggest(typed, limit);
    }

    // ---------------------------------------------------------------- writing it back

    /**
     * Writes one sheet back to the file it came from, in the format it was read in.
     *
     * <p>A TSV is written with tabs and no quoting, which is safe because a tab cannot appear
     * in a cell - and a cell containing one is reported by the audit rather than quoted around.
     * A CSV is quoted properly, because a comma in a definition is ordinary.
     *
     * @throws IOException if the file cannot be written
     */
    public void save(String sheetName) throws IOException {
        save(sheetName, false);
    }

    /**
     * Writes the sheet back to the file it came from.
     *
     * @param overwriteWhateverIsThere true to write even though the file has changed since it
     *     was read. Only pass it after asking somebody, because what it overwrites is a change
     *     that is not in this editor and therefore not in its undo history either.
     * @throws ChangedOnDisk if the file changed and this was not told to overwrite it
     */
    public void save(String sheetName, boolean overwriteWhateverIsThere) throws IOException {
        Sheet sheet = sheets.get(sheetName);
        if (sheet == null) {
            throw new IOException("No sheet called " + sheetName);
        }
        if (sheet.file == null) {
            throw new IOException(sheetName + " was never on disk, so there is nowhere to save "
                    + "it. Use writeTo to choose a file.");
        }
        if (!overwriteWhateverIsThere && changedOnDisk(sheetName)) {
            throw new ChangedOnDisk(sheetName, sheet.file);
        }
        writeTo(sheetName, sheet.file);
    }

    /**
     * Has the file this sheet came from changed since it was read?
     *
     * <p>False for a sheet that was never on disk, and false if the file has gone - a missing
     * file is not a conflicting change, and saving is how to put it back.
     */
    public boolean changedOnDisk(String sheetName) {
        Sheet sheet = sheets.get(sheetName);
        if (sheet == null || sheet.file == null || !sheet.file.isFile()) {
            return false;
        }
        long now = sheet.file.lastModified();
        return now != 0L && sheet.readAt != 0L && now != sheet.readAt;
    }

    /** Every sheet whose file has changed under us, in sheet order. */
    public List<String> changedOnDisk() {
        List<String> names = new ArrayList<String>();
        for (String name : sheets.keySet()) {
            if (changedOnDisk(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /** Writes one sheet to a named file and, if it was unsaved, marks it saved. */
    public void writeTo(String sheetName, File file) throws IOException {
        Sheet sheet = sheets.get(sheetName);
        if (sheet == null) {
            throw new IOException("No sheet called " + sheetName);
        }
        Files.write(file.toPath(), render(sheet).getBytes(Charset.forName("UTF-8")));
        sheet.unsaved = false;
        if (file.equals(sheet.file)) {
            // Our own write is not somebody else's change. Without this the second save of a
            // session refuses, having found a modification time it set itself.
            sheet.readAt = file.lastModified();
        }
    }

    /** The sheet as text, exactly as {@link #save} would write it. */
    public String render(String sheetName) {
        Sheet sheet = sheets.get(sheetName);
        return sheet == null ? "" : render(sheet);
    }

    private static String render(Sheet sheet) {
        StringBuilder text = new StringBuilder();
        for (List<String> row : sheet.rows) {
            text.append(Delimited.line(row, sheet.delimiter));
        }
        return text.toString();
    }
}
