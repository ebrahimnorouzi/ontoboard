package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.obolibrary.robot.IOHelper;
import org.obolibrary.robot.Template;
import org.obolibrary.robot.exceptions.RowParseException;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A spreadsheet of terms, turned into axioms. ROBOT's {@code template}, run from Protege.
 *
 * <p>The single most used ROBOT feature in OBO projects, and the one that decides whether a domain
 * expert can contribute at all. Somebody who knows what a copolymer is, and does not want to learn
 * Manchester syntax, can fill in a spreadsheet - and forty terms with definitions, parents and
 * cross-references arrive as axioms in one step. That is the workflow OBO projects actually run on,
 * and until now it lived entirely on the command line.
 *
 * <p>A ROBOT template is a table whose <b>first row is your own column headings</b>, whose
 * <b>second row says what each column means</b> in ROBOT's own small language ({@code ID},
 * {@code LABEL}, {@code TYPE}, {@code SC %} for a parent, {@code A IAO:0000115} for a definition),
 * and whose remaining rows are terms, one per row.
 *
 * <h2>What this adds over running ROBOT itself</h2>
 *
 * <p>ROBOT stops at the first bad row. A spreadsheet with twelve mistakes in it means twelve
 * runs - fix one, re-run, discover the next - and each round trip is a context switch for somebody
 * who was thinking about polymers. So when anything fails, every remaining row is checked
 * separately and <em>all</em> the problems come back at once, each with its row, its column and
 * the cell that caused it. That is the difference between a tool a domain expert can use and one
 * they give up on.
 *
 * <p>Checking each row separately would report false errors for a row that refers to a term the
 * sheet itself introduces two rows later - so the per-row pass is given the whole sheet's terms as
 * context first. Getting that wrong would produce confident, precise, wrong findings, which is
 * worse than the single error ROBOT gives.
 *
 * <p>Pure OWL API and robot-core; no Protege types and no Swing.
 */
public final class TemplateSheet {

    /** ROBOT's row numbers count the two header rows, so they match a spreadsheet's own. */
    private static final int FIRST_DATA_ROW = 2;

    /** One thing wrong, in the terms a person can find in their spreadsheet. */
    public static final class Problem {
        private final int row;
        private final int column;
        private final String columnName;
        private final String cell;
        private final String message;

        Problem(int row, int column, String columnName, String cell, String message) {
            this.row = row;
            this.column = column;
            this.columnName = columnName;
            this.cell = cell == null ? "" : cell;
            this.message = message;
        }

        /** As the spreadsheet numbers it: the first data row is row 3. Zero for a whole-column
         * problem. */
        public int getRow() {
            return row;
        }

        /** 1-based, or zero when the problem is not about one column. */
        public int getColumn() {
            return column;
        }

        /** The heading the author wrote, which is how they will find the column. */
        public String getColumnName() {
            return columnName;
        }

        /** The offending cell's contents. */
        public String getCell() {
            return cell;
        }

        public String getMessage() {
            return message;
        }

        /** Where it is, in one phrase. */
        public String where() {
            if (row == 0) {
                return columnName.isEmpty() ? "the template" : "column \"" + columnName + "\"";
            }
            return "row " + row + (columnName.isEmpty() ? "" : ", column \"" + columnName + "\"");
        }

        @Override
        public String toString() {
            return where() + ": " + message;
        }
    }

    /** What came out, and what went wrong. */
    public static final class Result {
        private final OWLOntology ontology;
        private final List<Problem> problems;
        private final int dataRows;
        private final boolean templateUnusable;

        Result(OWLOntology ontology, List<Problem> problems, int dataRows,
                boolean templateUnusable) {
            this.ontology = ontology;
            this.problems = Collections.unmodifiableList(problems);
            this.dataRows = dataRows;
            this.templateUnusable = templateUnusable;
        }

        /** The axioms, or null when the template row itself could not be read. */
        public OWLOntology getOntology() {
            return ontology;
        }

        /** Every problem found, not only the first. */
        public List<Problem> getProblems() {
            return problems;
        }

        /** How many rows of terms the sheet held. */
        public int getDataRows() {
            return dataRows;
        }

        /**
         * Whether the second row - the one saying what the columns mean - could not be read.
         *
         * <p>Different in kind from a bad row, and worth separating: a bad template row means
         * nothing at all was produced and there is nothing to salvage, whereas bad data rows leave
         * every other row perfectly usable.
         */
        public boolean isTemplateUnusable() {
            return templateUnusable;
        }

        public int getAxiomCount() {
            return ontology == null ? 0 : ontology.getAxiomCount();
        }
    }

    private TemplateSheet() {
    }

    /**
     * Reads a template file. TSV or CSV, decided by the extension, as ROBOT decides it.
     *
     * @throws RobotException if the file cannot be read
     */
    public static List<List<String>> read(File file) {
        if (file == null || !file.isFile()) {
            throw new RobotException("No template file to read.");
        }
        String name = file.getName().toLowerCase();
        boolean csv = name.endsWith(".csv");
        boolean tsv = name.endsWith(".tsv") || name.endsWith(".tab");
        try {
            if (!csv && !tsv) {
                // An extension this does not know stays with ROBOT, so its refusal and its
                // wording are unchanged.
                return IOHelper.readTable(file.getAbsolutePath());
            }
            // UTF-8 EXPLICITLY. IOHelper.readTable(String) opens the file with the platform
            // default charset, so on a Windows machine - where Charset.defaultCharset() is
            // windows-1252 - every non-ASCII character in a UTF-8 spreadsheet is corrupted on
            // the way in. Measured on the MatWerk city sheet: the file holds the bytes
            // 4A C3 BC for "Ju" + u-umlaut, and readTable returned the seven-character string
            // J U+00C3 U+00BC lich. That is exactly UTF-8 bytes decoded as ISO-8859-1, and it
            // means every German name in those sheets - Julich, Saarbrucken, Institut fuer
            // Materialwissenschaft - was entering the ontology as mojibake through
            // Template... > Add the axioms.
            //
            // It hid because it is platform-dependent: on a machine whose default charset is
            // already UTF-8 the same code is correct, so neither the test suite nor a release
            // build would show it. The Reader overloads take ROBOT's own parser, with its own
            // quoting rules, and only the decoding changes.
            InputStream bytes = new FileInputStream(file);
            try {
                Reader reader = new InputStreamReader(bytes, Charset.forName("UTF-8"));
                List<List<String>> rows = csv ? IOHelper.readCSV(reader)
                        : IOHelper.readTSV(reader);
                return withoutByteOrderMark(rows);
            } finally {
                bytes.close();
            }
        } catch (IOException cannotRead) {
            throw new RobotException("Could not read " + file.getName() + ": "
                    + cannotRead.getMessage(), cannotRead);
        }
    }

    /**
     * Drops a byte order mark from the very first cell.
     *
     * <p>A UTF-8 BOM is three bytes that decode to one invisible character, and a spreadsheet
     * program may write one. Left in place it sits in front of the first header - usually
     * {@code ID} - so the column is not recognised, and nothing on screen shows why.
     */
    private static List<List<String>> withoutByteOrderMark(List<List<String>> rows) {
        if (rows.isEmpty() || rows.get(0).isEmpty()) {
            return rows;
        }
        String first = rows.get(0).get(0);
        if (first == null || first.isEmpty() || first.charAt(0) != '﻿') {
            return rows;
        }
        List<String> header = new ArrayList<String>(rows.get(0));
        header.set(0, first.substring(1));
        List<List<String>> fixed = new ArrayList<List<String>>(rows);
        fixed.set(0, header);
        return fixed;
    }

    /**
     * Turns a sheet into axioms.
     *
     * @param name the file's name, which appears in ROBOT's own messages
     * @param rows the whole table including both header rows
     * @param context the ontology whose terms and labels the sheet may refer to - normally the one
     *     being edited, with its imports; may be null
     * @param prefixes the editing project's own prefix map, so its CURIEs resolve; may be null
     * @param outputIri the IRI to give the generated ontology; may be null
     */
    public static Result run(String name, List<List<String>> rows, OWLOntology context,
            Map<String, String> prefixes, IRI outputIri) {
        List<Problem> problems = new ArrayList<Problem>();
        if (rows == null || rows.size() < 2) {
            problems.add(new Problem(0, 0, "", "",
                    "A template needs at least two rows: your own column headings, then a row "
                            + "saying what each column means (ID, LABEL, SC %, and so on)."));
            return new Result(null, problems, 0, true);
        }
        int dataRows = rows.size() - 2;
        IOHelper io = ioHelper(prefixes);
        String iri = outputIri == null ? "http://www.ontoboard.org/template" : outputIri.toString();

        // Faults in the template row itself, found before ROBOT is asked anything. These are the
        // ones ROBOT does not object to and does not get right either - a column that silently
        // writes onto a property nobody defined - so they have to be found by reading the row
        // rather than by watching what ROBOT does with it. See TemplateColumns.
        List<Problem> templateFaults = new ArrayList<Problem>();
        for (TemplateColumns.Fault fault : TemplateColumns.of(rows).getFaults()) {
            templateFaults.add(new Problem(0, fault.getColumn().getNumber(),
                    fault.getColumn().getHeading(), fault.getColumn().getSpec(),
                    fault.getMessage()));
        }
        problems.addAll(templateFaults);

        // The template row on its own first. A problem there is a whole-table problem - nothing is
        // produced and no row is salvageable - so it must not be reported as though one row were
        // at fault.
        try {
            template(name, rows.subList(0, 2), context, io).generateOutputOntology(iri, false);
        } catch (Exception badTemplate) {
            problems.add(new Problem(0, 0, "", "", explain(badTemplate)));
            return new Result(null, problems, dataRows, true);
        }

        OWLOntology strict = null;
        try {
            strict = template(name, rows, context, io).generateOutputOntology(iri, false);
        } catch (Exception someRowFailed) {
            // Expected. Which row is worked out below, along with every other one - not just this
            // first one, which is all ROBOT would tell us.
            strict = null;
        }
        if (strict != null) {
            return new Result(strict, problems, dataRows, false);
        }

        OWLOntology salvaged;
        try {
            salvaged = template(name, rows, context, io).generateOutputOntology(iri, true);
        } catch (Exception nothingSalvageable) {
            // ROBOT's own salvage gave up, so there is no partial ontology to report against.
            // Building from the rows that do work is the only way to keep the two promises this
            // class exists for - every problem placed, and the good rows surviving the bad ones.
            return fromRowsThatWork(name, rows, context, io, iri, dataRows, templateFaults);
        }

        problems.addAll(problemsPerRow(name, rows, contextIncluding(context, salvaged), io, iri));
        return new Result(salvaged, problems, dataRows, false);
    }

    /**
     * Every bad row, checked separately, so all of them come back at once.
     *
     * <p>The reason this class exists rather than a direct call to ROBOT. Twelve mistakes in a
     * spreadsheet means twelve runs of {@code robot template} - fix one, re-run, discover the
     * next - and each round trip interrupts somebody who was thinking about their subject rather
     * than about tooling.
     */
    private static List<Problem> problemsPerRow(String name, List<List<String>> rows,
            OWLOntology context, IOHelper io, String iri) {
        List<Problem> problems = new ArrayList<Problem>();
        List<String> headings = rows.get(0);
        for (int index = FIRST_DATA_ROW; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (isBlank(row)) {
                continue;
            }
            List<List<String>> oneRow = new ArrayList<List<String>>(rows.subList(0, 2));
            oneRow.add(row);
            try {
                template(name, oneRow, context, io).generateOutputOntology(iri, false);
            } catch (RowParseException failed) {
                int column = failed.colNum;
                problems.add(new Problem(index + 1, column,
                        headingAt(headings, column), failed.cellValue, explain(failed)));
            } catch (Exception failed) {
                problems.add(new Problem(index + 1, 0, "", "", explain(failed)));
            }
        }
        return problems;
    }

    /**
     * Builds the sheet from the rows that work, when ROBOT cannot build it at all.
     *
     * <p>Reached when even {@code allowPartial} throws, which is not a rare corner. Measured on
     * robot-core 1.9.8: an {@code I} column - the kind that points at another term - whose cell
     * holds plain text that is not a known label raises a bare
     * {@code NullPointerException("object cannot be null")} in <em>both</em> strict and partial
     * mode. Before this method existed that cost the caller everything: a sheet of sixty good
     * rows with one typo in it produced no ontology, and the single problem reported was
     * "object cannot be null" against no row and no column - which is nothing a person can act
     * on. The MatWerk KG spreadsheet this was measured against is built almost entirely of
     * {@code I} columns, so it is the normal case there rather than an exotic one.
     *
     * <p>Rows are run one at a time and the ones that succeed are kept. Repeatedly, because a row
     * may legitimately point at a term another row introduces: each pass adds what it built to
     * the context of the next, and it stops when a pass gets nowhere. So a chain of forward
     * references resolves in as many passes as it is long, and an unresolvable one costs one
     * wasted pass rather than a wrong answer.
     */
    private static Result fromRowsThatWork(String name, List<List<String>> rows,
            OWLOntology context, IOHelper io, String iri, int dataRows,
            List<Problem> templateFaults) {
        List<Problem> problems = new ArrayList<Problem>(templateFaults);
        List<String> headings = rows.get(0);
        List<String> templateRow = rows.get(1);

        Set<Integer> pending = new LinkedHashSet<Integer>();
        for (int index = FIRST_DATA_ROW; index < rows.size(); index++) {
            if (!isBlank(rows.get(index))) {
                pending.add(index);
            }
        }

        OWLOntology built = emptyOntology();
        Map<Integer, Exception> failures = new LinkedHashMap<Integer, Exception>();
        OWLOntology scope = contextIncluding(context, built);

        boolean progressed = true;
        while (progressed && !pending.isEmpty()) {
            progressed = false;
            failures.clear();
            for (Iterator<Integer> each = pending.iterator(); each.hasNext();) {
                int index = each.next();
                try {
                    OWLOntology produced = oneRow(name, rows, index, scope, io, iri);
                    built.getOWLOntologyManager().addAxioms(built, produced.getAxioms());
                    each.remove();
                    progressed = true;
                } catch (Exception rowFailed) {
                    failures.put(index, rowFailed);
                }
            }
            if (progressed) {
                scope = contextIncluding(context, built);
            }
        }

        for (Integer index : pending) {
            problems.add(blame(name, rows, index, headings, templateRow, scope, io, iri,
                    failures.get(index)));
        }
        return new Result(built, problems, dataRows, false);
    }

    /** One data row, with the two header rows above it, as ROBOT needs to be handed it. */
    private static OWLOntology oneRow(String name, List<List<String>> rows, int index,
            OWLOntology scope, IOHelper io, String iri) throws Exception {
        List<List<String>> justThisRow = new ArrayList<List<String>>(rows.subList(0, 2));
        justThisRow.add(rows.get(index));
        return template(name, justThisRow, scope, io).generateOutputOntology(iri, false);
    }

    /**
     * Works out which cell broke a row, by experiment on the row itself.
     *
     * <p>Established by experiment rather than by reasoning about ROBOT's rules, because the
     * failure that makes this necessary is an undifferentiated {@code NullPointerException} that
     * carries no row, no column and no cell. A rule for what an {@code I} column accepts would be
     * a guess about somebody else's code that could go stale without a test noticing.
     *
     * <p><b>A cell is blamed only when the evidence runs both ways:</b> the row still fails when
     * that cell is the only one present, and the row builds when that cell alone is removed.
     * Removal on its own is not enough, and the first version of this made exactly that mistake -
     * it blamed the {@code TYPE} column of a row whose fault was four columns further along,
     * because a row with no {@code TYPE} creates no individual, so the offending assertion is
     * never attempted and the row "builds" by doing nothing. A precise, confident, wrong finding
     * is worse than the vague one it replaced, so both directions are required.
     *
     * <p>{@code ID}, {@code LABEL} and {@code TYPE} are held constant throughout: they say which
     * thing the row is about, and a row stripped of them is a different row. The cost of that is
     * that a fault in {@code TYPE} itself cannot be isolated this way, so it is tried separately
     * at the end, and if nothing can be shown the row is reported without a column rather than
     * with a guessed one.
     */
    private static Problem blame(String name, List<List<String>> rows, int index,
            List<String> headings, List<String> templateRow, OWLOntology scope, IOHelper io,
            String iri, Exception failure) {
        if (failure instanceof RowParseException) {
            RowParseException parsed = (RowParseException) failure;
            return new Problem(index + 1, parsed.colNum, headingAt(headings, parsed.colNum),
                    parsed.cellValue, explain(parsed));
        }
        List<String> row = rows.get(index);
        int width = Math.min(row.size(), templateRow.size());

        for (int column = 0; column < width; column++) {
            String cell = row.get(column);
            if (cell == null || cell.trim().isEmpty() || isStructural(templateRow.get(column))) {
                continue;
            }
            boolean failsAlone = !builds(name, rows, index,
                    onlyThisColumn(row, templateRow, column, width), scope, io, iri);
            boolean buildsWithout = builds(name, rows, index,
                    withoutThisColumn(row, column), scope, io, iri);
            if (failsAlone && buildsWithout) {
                return new Problem(index + 1, column + 1, headingAt(headings, column + 1), cell,
                        whyNot(cell, templateRow.get(column), io));
            }
        }

        // TYPE is held constant above, so if it is the fault nothing isolates it. Removing it
        // makes the row produce nothing at all, which is not evidence on its own - but combined
        // with every other column having been cleared, it is the only candidate left.
        for (int column = 0; column < width; column++) {
            String cell = row.get(column);
            if (cell == null || cell.trim().isEmpty()
                    || !"TYPE".equalsIgnoreCase(templateRow.get(column).trim())) {
                continue;
            }
            if (builds(name, rows, index, withoutThisColumn(row, column), scope, io, iri)) {
                return new Problem(index + 1, column + 1, headingAt(headings, column + 1), cell,
                        whyNot(cell, templateRow.get(column), io));
            }
        }

        return new Problem(index + 1, 0, "", "",
                failure == null ? "This row could not be read." : explain(failure));
    }

    /** The row with everything cleared but the columns that say which thing it is about. */
    private static List<String> onlyThisColumn(List<String> row, List<String> templateRow,
            int keep, int width) {
        List<String> trimmed = new ArrayList<String>(row);
        for (int column = 0; column < width; column++) {
            if (column != keep && !isStructural(templateRow.get(column))) {
                trimmed.set(column, "");
            }
        }
        return trimmed;
    }

    private static List<String> withoutThisColumn(List<String> row, int drop) {
        List<String> trimmed = new ArrayList<String>(row);
        trimmed.set(drop, "");
        return trimmed;
    }

    /** Whether the row builds with the given cells in place of its own. */
    private static boolean builds(String name, List<List<String>> rows, int index,
            List<String> replacement, OWLOntology scope, IOHelper io, String iri) {
        List<List<String>> altered = new ArrayList<List<String>>(rows);
        altered.set(index, replacement);
        try {
            oneRow(name, altered, index, scope, io, iri);
            return true;
        } catch (Exception stillBroken) {
            return false;
        }
    }

    /**
     * The columns that say which thing the row is about, rather than something about it.
     *
     * <p>Held constant while the others are varied. {@code TYPE} belongs here even though it is
     * not an identifier: without it no individual is created, so no assertion about one is
     * attempted and every other column looks innocent.
     */
    private static boolean isStructural(String templateCell) {
        String spec = templateCell == null ? "" : templateCell.trim();
        return spec.equalsIgnoreCase("ID") || spec.equalsIgnoreCase("LABEL")
                || spec.equalsIgnoreCase("TYPE");
    }

    /**
     * Why a cell that points at a term did not work, in terms its author can act on.
     *
     * <p>ROBOT's own message for this is "object cannot be null", which describes a variable
     * rather than a spreadsheet. What the person needs to know is that this column holds a
     * reference to a term, and that this text is not one.
     */
    private static String whyNot(String cell, String templateCell, IOHelper io) {
        String value = cell.trim();
        String spec = templateCell == null ? "" : templateCell.trim();
        int colon = value.indexOf(':');
        boolean looksLikeCurie = colon > 0 && value.indexOf(' ') < 0
                && value.indexOf('/') < 0 && !value.startsWith("http");
        if (looksLikeCurie) {
            String prefix = value.substring(0, colon);
            if (io.getPrefixes().get(prefix) == null) {
                return "\"" + value + "\" looks like a CURIE, but nothing defines the prefix \""
                        + prefix + ":\" - so there is no way to tell which IRI it means. Add the "
                        + "prefix to the project, or write the IRI out in full.";
            }
        }
        // THE CELL MAY ALREADY BE WHAT THE ADVICE ASKS FOR. Telling somebody whose cell holds
        // http://purl.obolibrary.org/obo/OBI_0000245 that the column wants "an IRI, a CURIE, or
        // the exact label" is useless, and it is the commonest failure on real sheets: a
        // knowledge graph built in stages fails this way on every term its upstream sheets have
        // not contributed yet. What they need to know is that the term is absent, not what the
        // column accepts.
        boolean absoluteIri = (value.startsWith("http://") || value.startsWith("https://"))
                && value.indexOf(' ') < 0;
        boolean knownCurie = looksLikeCurie && io.getPrefixes().get(
                value.substring(0, colon)) != null;
        if (absoluteIri || knownCurie) {
            return "This names a term properly, and the term is not here: nothing in this "
                    + "ontology or its imports is \"" + value + "\". Either bring it in with "
                    + "Import terms..., or - if it is meant to come from another sheet - build "
                    + "that sheet first, because a sheet can only point at terms that already "
                    + "exist when it runs.";
        }
        return "Nothing in this ontology is called \"" + value + "\", so there is no term for "
                + "this column to point at. The column is \"" + spec + "\", which holds a "
                + "reference to a term: an IRI, a CURIE such as obo:BFO_0000001, or the exact "
                + "label of a term that already exists - here, in an import, or in another row "
                + "of this sheet.";
    }

    /** Somewhere to accumulate the rows that work. */
    private static OWLOntology emptyOntology() {
        try {
            return OWLManager.createOWLOntologyManager().createOntology(
                    IRI.create("http://www.ontoboard.org/template"));
        } catch (OWLOntologyCreationException cannotCreate) {
            throw new RobotException("Could not start an ontology for the template's output: "
                    + cannotCreate.getMessage(), cannotCreate);
        }
    }

    /**
     * The editing ontology plus everything the sheet itself introduces.
     *
     * <p>Without this, a row whose parent is a term defined two rows further down the same sheet
     * would be reported as an error when checked on its own - a confident, precise, wrong finding,
     * which is worse than the single real error ROBOT gives.
     */
    private static OWLOntology contextIncluding(OWLOntology context, OWLOntology generated) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology both = manager.createOntology(
                    IRI.create("http://www.ontoboard.org/template-context"));
            if (context != null) {
                for (OWLAxiom axiom : context.getAxioms(
                        org.semanticweb.owlapi.model.parameters.Imports.INCLUDED)) {
                    manager.addAxiom(both, axiom);
                }
            }
            for (OWLAxiom axiom : generated.getAxioms()) {
                manager.addAxiom(both, axiom);
            }
            return both;
        } catch (OWLOntologyCreationException cannotBuild) {
            // Falling back to the editing ontology alone risks a false error on a self-referring
            // row, so the caller is better served by the original context than by nothing.
            return context;
        }
    }

    private static Template template(String name, List<List<String>> rows, OWLOntology context,
            IOHelper io) throws Exception {
        return context == null ? new Template(name, rows, io)
                : new Template(name, rows, context, io);
    }

    /**
     * ROBOT's message, tidied.
     *
     * <p>Kept rather than rewritten: it names the rule, the cell and what was expected, and a
     * paraphrase would be a worse version of it that also went stale. Only the layout is changed -
     * the Manchester parser's list of alternatives arrives as a column of tab-indented lines,
     * which reads as broken output inside a table cell.
     */
    static String explain(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return failure.getClass().getSimpleName();
        }
        String text = message.replace('\n', ' ').replace('\t', ' ').replaceAll(" +", " ").trim();
        // ROBOT prefixes its messages with a documentation anchor - "template#UNKNOWN ENTITY
        // ERROR" - which is for its own docs site and means nothing in a dialog.
        int hash = text.indexOf('#');
        if (hash > 0 && hash < 20) {
            text = text.substring(hash + 1).trim();
        }
        return text;
    }

    private static String headingAt(List<String> headings, int column) {
        return column >= 1 && column <= headings.size() ? headings.get(column - 1) : "";
    }

    private static boolean isBlank(List<String> row) {
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static IOHelper ioHelper(Map<String, String> prefixes) {
        try {
            IOHelper io = new IOHelper();
            if (prefixes != null) {
                for (Map.Entry<String, String> prefix : prefixes.entrySet()) {
                    if (prefix.getKey() == null || prefix.getValue() == null) {
                        continue;
                    }
                    // Protege spells a prefix "obo:"; ROBOT's addPrefix wants "obo".
                    String prefixName = prefix.getKey().endsWith(":")
                            ? prefix.getKey().substring(0, prefix.getKey().length() - 1)
                            : prefix.getKey();
                    if (!prefixName.isEmpty()) {
                        io.addPrefix(prefixName, prefix.getValue());
                    }
                }
            }
            return io;
        } catch (IOException cannotStart) {
            throw new RobotException("Could not read the prefix map: "
                    + cannotStart.getMessage(), cannotStart);
        }
    }

    /**
     * A template to start from, with the columns an OBO term actually needs.
     *
     * <p>The blank page is the real barrier. Somebody told "fill in a ROBOT template" and left with
     * an empty spreadsheet has to go and find the documentation, and most of them do not come
     * back. The headings are the ones an OBO term needs anyway - an identifier, a label, a parent,
     * a definition and where the definition came from - so the file doubles as an explanation of
     * what a term is expected to carry.
     *
     * @param prefix the project's own prefix, so the example IDs are in the right namespace
     */
    public static String starter(String prefix) {
        String name = prefix == null || prefix.trim().isEmpty() ? "ex" : prefix.trim();
        return starterWithIris(name + ":0000001", name + ":0000002", name + ":0000003");
    }

    /**
     * A starter whose identifiers are the project's own, written out in full.
     *
     * <p><b>This exists because the CURIE version produces nothing.</b> Measured on every real
     * project to hand - PMDco, NFDIcore, MWO, ECTO and a project OntoBoard scaffolds itself -
     * the old starter gave 0 axioms and 3 errors, every time. It takes the prefix from the
     * last segment of the ontology IRI, which is right for MWO and ECTO and wrong for the
     * other two, and then it does not matter which: <em>no project declares that prefix</em>,
     * and ROBOT's 277 built-in ones do not include it either. Against the real
     * {@code mwo-edit.owl}: {@code mwo:0000001} gives 0 axioms and 1 problem,
     * {@code MWO:0000001} the same, and the full IRI gives 4 axioms and none.
     *
     * <p>A full IRI resolves with no prefix map at all, which also means the file still works
     * when ODK's generated Makefile runs it outside Protege - and the Makefile owns template
     * output, so it will.
     *
     * <p>The numbers matter as much as the form. {@code PMD_0000001} and {@code NFDI_0000001}
     * are <em>live published terms</em> - "portion of matter" and "obsolete NFDI resource" -
     * so a starter keeping the hardcoded 0000001 would have asserted
     * {@code rdfs:label "an example term"} onto them and reported a clean success. The caller
     * passes identifiers that are free.
     */
    public static String starterWithIris(String first, String second, String third) {
        StringBuilder text = new StringBuilder();
        text.append("ID\tLabel\tParent\tDefinition\tDefinition source\tNote\n");
        text.append("ID\tLABEL\tSC %\tA IAO:0000115\t>A IAO:0000119\tA IAO:0000116\n");
        text.append(first).append("\tan example term\towl:Thing\t")
                .append("What this term means, in a sentence that could stand alone.\t")
                .append("https://doi.org/...\t")
                .append("Replace these three rows with your own. The identifiers continue the "
                        + "numbering your project already uses.\n");
        text.append(second).append("\ta child of it\t").append(first).append("\t")
                .append("A parent can be an identifier from this sheet, as here, or a label "
                        + "from your ontology - quoted if it has spaces in it.\t\t\n");
        text.append(third).append("\ta related term\t'an example term'\t")
                .append("The second row is the one that matters: it says what each column "
                        + "means. SC % is 'this column holds the parent'.\t\t\n");
        return text.toString();
    }
}
