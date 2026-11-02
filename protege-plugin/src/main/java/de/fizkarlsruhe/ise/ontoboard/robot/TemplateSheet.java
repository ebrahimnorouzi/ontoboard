package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.obolibrary.robot.IOHelper;
import org.obolibrary.robot.QuotedEntityChecker;
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
        private List<Problem> warnings = Collections.<Problem>emptyList();

        Result(OWLOntology ontology, List<Problem> problems, int dataRows,
                boolean templateUnusable) {
            this.ontology = ontology;
            this.problems = Collections.unmodifiableList(problems);
            this.dataRows = dataRows;
            this.templateUnusable = templateUnusable;
        }

        Result withWarnings(List<Problem> found) {
            this.warnings = Collections.unmodifiableList(found);
            return this;
        }

        /**
         * Cells that were read, and read differently from what was probably meant - a date
         * written as plain text for a property whose values are dates. Nothing failed, so these
         * are not {@link #getProblems() problems}.
         */
        public List<Problem> getWarnings() {
            return warnings;
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

    /**
     * Every name the sheets of one folder introduce, for each of them to use.
     *
     * <p>A knowledge graph kept in sheets is mostly references from a row of one sheet to a row of
     * another, by label or by ID - every reference in the MatWerk workbook's dataportal sheet names
     * a row that another sheet defines. Building one sheet against the ontology alone, as
     * Template... did, made each of those an error, although the sheet editor had offered the name
     * and Check sheets... had accepted it: ROBOT only knows the rows of the templates it was given.
     *
     * <p>ROBOT reads a template's names when it constructs it and resolves references when it
     * generates, both through a QuotedEntityChecker. Handed one checker, every sheet of the folder
     * is constructed first and generated after, so any sheet can name a row of any other - in any
     * order, two sheets that name each other included. ROBOT's own command line constructs and
     * generates one template after the other, so there, and in an ODK component listing several
     * templates, a sheet can name only rows of the sheets listed before it.
     */
    public static final class Names {
        private final IOHelper io;
        private final QuotedEntityChecker checker;
        private final List<String> sheets = new ArrayList<String>();
        private final List<String> unavailable = new ArrayList<String>();

        private Names(IOHelper io, QuotedEntityChecker checker) {
            this.io = io;
            this.checker = checker;
        }

        /** The sheets whose rows can be named, in the order they were read. */
        public List<String> getSheets() {
            return Collections.unmodifiableList(sheets);
        }

        /** Sheets, or rows of them, whose names could not be taken in - each saying why. */
        public List<String> getUnavailable() {
            return Collections.unmodifiableList(unavailable);
        }
    }

    /**
     * The names {@code sheets} introduce - name of each sheet to its rows - with the ontology's.
     *
     * <p>Each sheet's two header rows are tried first, so a file beside the sheets that is not a
     * template - a data export - is set aside after one try rather than read row by row.
     */
    public static Names namesOf(Map<String, List<List<String>>> sheets, OWLOntology context,
            Map<String, String> prefixes) {
        return namesOf(sheets, context, prefixes, null);
    }

    /**
     * {@link #namesOf(Map, OWLOntology, Map)} for building the sheet called {@code self}: a row of
     * another sheet that carries one of its labels is not lent. ROBOT decides what a row without
     * TYPE is - class, individual, property - by what its label already names, so a class row
     * "Margherita" became the menu item "Margherita" of another sheet, and failed. The sheet's own
     * rows answer to its own labels, as they did when it was built alone.
     */
    public static Names namesOf(Map<String, List<List<String>>> sheets, OWLOntology context,
            Map<String, String> prefixes, String self) {
        IOHelper io = ioHelper(prefixes);
        java.util.Set<String> ownLabels = labelsOf(self == null ? null : sheets.get(self));
        // Set up as ROBOT's Template sets up its own checker when it is not handed one.
        QuotedEntityChecker checker = new QuotedEntityChecker();
        checker.setIOHelper(io);
        checker.addProvider(new org.semanticweb.owlapi.util.SimpleShortFormProvider());
        checker.addProperty(OWLManager.getOWLDataFactory().getRDFSLabel());
        if (context != null) {
            checker.addAll(context);
        }
        Names names = new Names(io, checker);
        // Not the context again for every sheet: it is in the checker once already.
        OWLOntology nothing = emptyOntology();
        for (Map.Entry<String, List<List<String>>> sheet : sheets.entrySet()) {
            List<List<String>> rows = sheet.getValue();
            if (rows == null || rows.size() < 2) {
                continue;
            }
            try {
                new Template(sheet.getKey(), rows.subList(0, 2), nothing, io, checker);
            } catch (Exception notATemplate) {
                names.unavailable.add(sheet.getKey() + ": none of its rows can be named from "
                        + "other sheets - " + explain(notATemplate));
                continue;
            }
            List<String> refused = lend(sheet.getKey(), rows, io, checker, nothing,
                    sheet.getKey().equals(self) ? java.util.Collections.<String>emptySet()
                            : ownLabels);
            names.sheets.add(sheet.getKey());
            if (!refused.isEmpty()) {
                names.unavailable.add(sheet.getKey() + ": " + refused.size() + " row"
                        + (refused.size() == 1 ? "" : "s") + " cannot be named from other sheets "
                        + "- the ID uses a prefix nothing defines, so ROBOT will not build the row "
                        + "either: " + String.join(", ", refused));
            }
        }
        return names;
    }

    /**
     * Puts a sheet's rows into the checker by every name other sheets may use for them.
     *
     * <p>Through ROBOT itself - a table of the row's ID, its TYPE and one label column, which is
     * what ROBOT reads to register a row - once for each label column, because ROBOT takes only
     * the last rdfs:label column of a sheet while the sheet editor offers every one. A row is lent
     * only with an ID ROBOT can expand: one it cannot is refused when its own sheet is built, and
     * lent it would bind its label to an IRI that row never gets. Only a label that is not blank:
     * ROBOT binds a blank one too, and an empty part of a SPLIT cell would then name that row.
     *
     * @return the rows refused for their ID, as "row N (ID)"
     */
    private static List<String> lend(String name, List<List<String>> rows, IOHelper io,
            QuotedEntityChecker checker, OWLOntology nothing, java.util.Set<String> masked) {
        List<String> refused = new ArrayList<String>();
        TemplateColumns columns = TemplateColumns.of(rows);
        TemplateColumns.Column id = columns.firstOf(TemplateColumns.Kind.ID);
        if (id == null) {
            return refused;
        }
        TemplateColumns.Column type = columns.firstOf(TemplateColumns.Kind.TYPE);
        boolean first = true;
        for (TemplateColumns.Column label : columns.labelColumns()) {
            if (!namesARow(label)) {
                continue;
            }
            List<List<String>> table = new ArrayList<List<String>>();
            table.add(type == null ? Arrays.asList("ID", "Label")
                    : Arrays.asList("ID", "Type", "Label"));
            table.add(type == null ? Arrays.asList(id.getSpec(), label.getSpec())
                    : Arrays.asList(id.getSpec(), type.getSpec(), label.getSpec()));
            for (int index = FIRST_DATA_ROW; index < rows.size(); index++) {
                List<String> row = rows.get(index);
                // The cells as written, as ROBOT reads them: it expands the ID untrimmed and
                // registers the label untrimmed, so " ex:x1" is refused by its own sheet and
                // must not be lent here as ex:x1.
                String identifier = rawAt(row, id.getNumber());
                String text = rawAt(row, label.getNumber());
                if (identifier.trim().isEmpty() || text.trim().isEmpty()
                        || masked.contains(text.trim())) {
                    continue;
                }
                if (io.createIRI(identifier) == null) {
                    if (first) {
                        refused.add("row " + (index + 1) + " (" + identifier.trim() + ")");
                    }
                    continue;
                }
                table.add(type == null ? Arrays.asList(identifier, text)
                        : Arrays.asList(identifier, rawAt(row, type.getNumber()), text));
            }
            first = false;
            if (table.size() > 2) {
                try {
                    new Template(name, table, nothing, io, checker);
                } catch (Exception cannotLend) {
                    // A label column ROBOT cannot read lends nothing; the sheet's own build
                    // reports the column.
                }
            }
        }
        return refused;
    }

    /** Every label a sheet gives its rows, trimmed; empty for no sheet. */
    private static java.util.Set<String> labelsOf(List<List<String>> rows) {
        java.util.Set<String> labels = new java.util.HashSet<String>();
        if (rows == null || rows.size() < 2) {
            return labels;
        }
        for (TemplateColumns.Column label : TemplateColumns.of(rows).labelColumns()) {
            if (!namesARow(label)) {
                continue;
            }
            for (int index = FIRST_DATA_ROW; index < rows.size(); index++) {
                String text = rawAt(rows.get(index), label.getNumber()).trim();
                if (!text.isEmpty()) {
                    labels.add(text);
                }
            }
        }
        return labels;
    }

    /** A label column ROBOT resolves names by: LABEL, or an annotation with rdfs:label. */
    private static boolean namesARow(TemplateColumns.Column column) {
        if (column.getKind() == TemplateColumns.Kind.LABEL) {
            return true;
        }
        String property = column.getProperty() == null ? "" : column.getProperty().trim();
        return property.equals("rdfs:label")
                || property.equals("http://www.w3.org/2000/01/rdf-schema#label");
    }

    /** The cell in a 1-based column as written; empty when the row is shorter. */
    private static String rawAt(List<String> row, int number) {
        int at = number - 1;
        String cell = at >= 0 && at < row.size() ? row.get(at) : null;
        return cell == null ? "" : cell;
    }

    /**
     * Every sheet of a folder, each built against the names of all of them - in the order given,
     * so any of them may name a row of any other. Each sheet gets the names fresh, as a single
     * sheet built on its own would: a build writes into the checker it is given.
     */
    public static Map<String, Result> runAll(Map<String, List<List<String>>> sheets,
            OWLOntology context, Map<String, String> prefixes, String iriBase) {
        Map<String, Result> results = new LinkedHashMap<String, Result>();
        for (Map.Entry<String, List<List<String>>> sheet : sheets.entrySet()) {
            results.put(sheet.getKey(), run(sheet.getKey(), sheet.getValue(), context, prefixes,
                    IRI.create((iriBase == null ? "http://www.ontoboard.org/template/" : iriBase)
                            + sheet.getKey()), namesOf(sheets, context, prefixes,
                                    sheet.getKey())));
        }
        return results;
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
        return run(name, rows, context, prefixes, outputIri, null);
    }

    /**
     * {@link #run(String, List, OWLOntology, Map, IRI)}, with the rows of other sheets nameable
     * as well: {@code names} is what {@link #namesOf} read from the folder the sheet is in, or
     * null for the ontology alone.
     */
    public static Result run(String name, List<List<String>> rows, OWLOntology context,
            Map<String, String> prefixes, IRI outputIri, Names names) {
        List<Problem> problems = new ArrayList<Problem>();
        if (rows == null || rows.size() < 2) {
            problems.add(new Problem(0, 0, "", "",
                    "A template needs at least two rows: your own column headings, then a row "
                            + "saying what each column means (ID, LABEL, SC %, and so on)."));
            return new Result(null, problems, 0, true);
        }
        int dataRows = rows.size() - 2;
        IOHelper io = names != null ? names.io : ioHelper(prefixes);
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
            template(name, rows.subList(0, 2), context, io, names)
                    .generateOutputOntology(iri, false);
        } catch (Exception badTemplate) {
            problems.add(new Problem(0, 0, "", "", explain(badTemplate)));
            return new Result(null, problems, dataRows, true);
        }

        OWLOntology strict = null;
        try {
            strict = template(name, rows, context, io, names).generateOutputOntology(iri, false);
        } catch (Exception someRowFailed) {
            // Expected. Which row is worked out below, along with every other one - not just this
            // first one, which is all ROBOT would tell us.
            strict = null;
        }
        List<Problem> warnings = untypedValues(rows, context, io);
        if (strict != null) {
            return new Result(strict, problems, dataRows, false).withWarnings(warnings);
        }

        OWLOntology salvaged;
        try {
            salvaged = template(name, rows, context, io, names).generateOutputOntology(iri, true);
        } catch (Exception nothingSalvageable) {
            // ROBOT's own salvage gave up, so there is no partial ontology to report against.
            // Building from the rows that do work is the only way to keep the two promises this
            // class exists for - every problem placed, and the good rows surviving the bad ones.
            return fromRowsThatWork(name, rows, context, io, iri, dataRows, templateFaults,
                    names);
        }

        problems.addAll(problemsPerRow(name, rows, contextIncluding(context, salvaged), io, iri,
                names));
        return new Result(salvaged, problems, dataRows, false).withWarnings(warnings);
    }

    /**
     * Values written as plain text in a column whose data property says its values are of some
     * other datatype - a date, a number.
     *
     * <p>ROBOT does not apply a property's range: measured on robot-core 1.9.8, a column
     * {@code I ex:at} over a property with range {@code xsd:dateTime} turns the cell
     * {@code 2026-11-01T18:00:00} into {@code "2026-11-01T18:00:00"^^xsd:string}. It reports
     * nothing, the graph builds, and a SPARQL query comparing dates then finds nothing. The
     * spelling that gives a typed value is the datatype in the cell:
     * {@code 2026-11-01T18:00:00^^xsd:dateTime}.
     */
    static List<Problem> untypedValues(List<List<String>> rows, OWLOntology context,
            IOHelper io) {
        List<Problem> warnings = new ArrayList<Problem>();
        if (context == null || rows.size() <= FIRST_DATA_ROW) {
            return warnings;
        }
        for (TemplateColumns.Column column : TemplateColumns.of(rows).getColumns()) {
            if (column.getKind() != TemplateColumns.Kind.INDIVIDUAL) {
                continue;
            }
            IRI property;
            try {
                property = io.createIRI(column.getProperty().trim());
            } catch (RuntimeException unresolvable) {
                continue;
            }
            if (property == null || !context.containsDataPropertyInSignature(property,
                    org.semanticweb.owlapi.model.parameters.Imports.INCLUDED)) {
                continue;
            }
            org.semanticweb.owlapi.model.OWLDatatype range = typedRangeOf(context, property);
            if (range == null) {
                continue;
            }
            String shortName = range.getIRI().toString()
                    .replace("http://www.w3.org/2001/XMLSchema#", "xsd:");
            for (int index = FIRST_DATA_ROW; index < rows.size(); index++) {
                List<String> row = rows.get(index);
                int at = column.getNumber() - 1;
                String cell = at < row.size() ? row.get(at).trim() : "";
                if (cell.isEmpty() || cell.contains("^^")) {
                    continue;
                }
                warnings.add(new Problem(index + 1, column.getNumber(), column.getHeading(),
                        cell, "Built as plain text, but " + column.getProperty().trim()
                                + " says its values are " + shortName + ". ROBOT does not "
                                + "apply a property's range, so write the type in the cell: "
                                + cell + "^^" + shortName));
            }
        }
        return warnings;
    }

    /** A declared range of the data property that is a datatype other than plain text. */
    private static org.semanticweb.owlapi.model.OWLDatatype typedRangeOf(OWLOntology context,
            IRI property) {
        org.semanticweb.owlapi.model.OWLDataProperty data = context.getOWLOntologyManager()
                .getOWLDataFactory().getOWLDataProperty(property);
        for (OWLOntology part : context.getImportsClosure()) {
            for (org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom axiom
                    : part.getDataPropertyRangeAxioms(data)) {
                if (axiom.getRange().isDatatype()) {
                    org.semanticweb.owlapi.model.OWLDatatype range =
                            axiom.getRange().asOWLDatatype();
                    String iri = range.getIRI().toString();
                    if (!iri.endsWith("#string") && !iri.endsWith("#Literal")
                            && !iri.endsWith("#PlainLiteral") && !iri.endsWith("#langString")) {
                        return range;
                    }
                }
            }
        }
        return null;
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
            OWLOntology context, IOHelper io, String iri, Names names) {
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
                template(name, oneRow, context, io, names).generateOutputOntology(iri, false);
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
            List<Problem> templateFaults, Names names) {
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
                    OWLOntology produced = oneRow(name, rows, index, scope, io, iri, names);
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
                    failures.get(index), names));
        }
        return new Result(built, problems, dataRows, false);
    }

    /** One data row, with the two header rows above it, as ROBOT needs to be handed it. */
    private static OWLOntology oneRow(String name, List<List<String>> rows, int index,
            OWLOntology scope, IOHelper io, String iri, Names names) throws Exception {
        List<List<String>> justThisRow = new ArrayList<List<String>>(rows.subList(0, 2));
        justThisRow.add(rows.get(index));
        return template(name, justThisRow, scope, io, names).generateOutputOntology(iri, false);
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
            String iri, Exception failure, Names names) {
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
                    onlyThisColumn(row, templateRow, column, width), scope, io, iri, names);
            boolean buildsWithout = builds(name, rows, index,
                    withoutThisColumn(row, column), scope, io, iri, names);
            if (failsAlone && buildsWithout) {
                return new Problem(index + 1, column + 1, headingAt(headings, column + 1), cell,
                        whyNot(cell, templateRow.get(column), io, names));
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
            if (builds(name, rows, index, withoutThisColumn(row, column), scope, io, iri,
                    names)) {
                return new Problem(index + 1, column + 1, headingAt(headings, column + 1), cell,
                        whyNot(cell, templateRow.get(column), io, names));
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
            List<String> replacement, OWLOntology scope, IOHelper io, String iri, Names names) {
        List<List<String>> altered = new ArrayList<List<String>>(rows);
        altered.set(index, replacement);
        try {
            oneRow(name, altered, index, scope, io, iri, names);
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
                || spec.equalsIgnoreCase("TYPE") || LABEL_ANNOTATION.matcher(spec).matches();
    }

    /**
     * An rdfs:label column written as an annotation - A, AL, AT or AI. Held constant like LABEL:
     * cleared in an experiment, ROBOT registers the row under an empty name, and with one checker
     * for a folder an empty part of a SPLIT cell in another row would then name it.
     */
    private static final java.util.regex.Pattern LABEL_ANNOTATION = java.util.regex.Pattern
            .compile("(?i)A[LTI]?\\s+(rdfs:label|<?http://www\\.w3\\.org/2000/01/rdf-schema#label>?)"
                    + "([@^\\s].*)?");

    /**
     * Why a cell that points at a term did not work, in terms its author can act on.
     *
     * <p>ROBOT's own message for this is "object cannot be null", which describes a variable
     * rather than a spreadsheet. What the person needs to know is that this column holds a
     * reference to a term, and that this text is not one.
     */
    private static String whyNot(String cell, String templateCell, IOHelper io, Names names) {
        boolean otherSheets = names != null && names.sheets.size() > 1;
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
            if (otherSheets) {
                return "This names a term properly, and the term is not here: nothing in this "
                        + "ontology, its imports or the other sheets in this folder is \""
                        + value + "\". Bring it in with "
                        + "OntoBoard > Reuse > Find terms in another ontology..., "
                        + "or add the row that defines it to one of the sheets.";
            }
            return "This names a term properly, and the term is not here: nothing in this "
                    + "ontology or its imports is \"" + value + "\". Either bring it in with "
                    + "OntoBoard > Reuse > Find terms in another ontology..., or - if another sheet "
                    + "defines it - keep that sheet in the same folder as this one, where every "
                    + "sheet can name the rows of the others.";
        }
        return "Nothing in this ontology" + (otherSheets ? " or in the other sheets in this folder"
                : "") + " is called \"" + value + "\", so there is no term for this column to point "
                + "at. The column is \"" + spec + "\", which holds a reference to a term: an IRI, a "
                + "CURIE such as obo:BFO_0000001, or the exact label of a term that already exists "
                + "- here, in an import, in another row of this sheet, or in another sheet in the "
                + "same folder.";
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
            IOHelper io, Names names) throws Exception {
        if (names != null) {
            // The folder's checker: every sheet's rows are in it already, and this sheet's own
            // go in again, which changes nothing.
            return new Template(name, rows, context == null ? emptyOntology() : context, io,
                    names.checker);
        }
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
