package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
        try {
            return IOHelper.readTable(file.getAbsolutePath());
        } catch (IOException cannotRead) {
            throw new RobotException("Could not read " + file.getName() + ": "
                    + cannotRead.getMessage(), cannotRead);
        }
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
        } catch (Exception nothingUsable) {
            problems.add(new Problem(0, 0, "", "", explain(nothingUsable)));
            return new Result(null, problems, dataRows, false);
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
        StringBuilder text = new StringBuilder();
        text.append("ID\tLabel\tParent\tDefinition\tDefinition source\tNote\n");
        text.append("ID\tLABEL\tSC %\tA IAO:0000115\t>A IAO:0000119\tA IAO:0000116\n");
        text.append(name).append(":0000001\tan example term\towl:Thing\t")
                .append("What this term means, in a sentence that could stand alone.\t")
                .append("https://doi.org/...\t")
                .append("Delete this row and the two example rows below it.\n");
        text.append(name).append(":0000002\ta child of it\t").append(name).append(":0000001\t")
                .append("A parent can be an ID from this sheet, an ID from your ontology, or a "
                        + "label in quotes.\t\t\n");
        text.append(name).append(":0000003\ta related term\t\"an example term\"\t")
                .append("The second row is the one that matters: it says what each column means. "
                        + "SC % is 'this column holds the parent'.\t\t\n");
        return text.toString();
    }
}
