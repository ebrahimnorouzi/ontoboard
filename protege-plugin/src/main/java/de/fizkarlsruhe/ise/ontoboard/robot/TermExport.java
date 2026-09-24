package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.obolibrary.robot.ExportOperation;
import org.obolibrary.robot.IOHelper;
import org.obolibrary.robot.export.Table;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * ROBOT's {@code export} - the open ontology as a table of terms and chosen columns.
 *
 * <p>The inverse of {@link TemplateSheet}, and the operation curators ask for most often that
 * Protege has no answer to: "give me a spreadsheet of every class with its label, its definition and
 * its parent". Protege can show that one term at a time; it cannot hand you the file.
 *
 * <p><b>Why this is safe inside the bundle, when the report was not.</b> Two separate hazards had to
 * be checked rather than assumed, because both have already bitten this plugin:
 *
 * <ul>
 *   <li><em>Resource enumeration.</em> {@code createExportTable} does not enumerate any resource
 *       directory, so it is not subject to the failure that broke {@code ReportOperation} - see
 *       {@link ReportQueries}.
 *   <li><em>Apache POI.</em> POI is embedded but its logging backend is not: {@code log4j-api}
 *       declares its own OSGi {@code Bundle-Activator} and bnd rejects a second one, and
 *       {@code log4j-over-slf4j} supplies the log4j 1.x API while POI 5.x calls the 2.x one. So any
 *       POI class initialisation throws {@code NoClassDefFoundError}. In {@code export.Table} that
 *       is confined to {@code asWorkbook}, which {@code write} calls only for {@code xlsx}. This
 *       class therefore offers every other format ROBOT supports and refuses {@code xlsx} with a
 *       reason, rather than letting a user pick it and get a stack trace.
 * </ul>
 *
 * <p>{@link de.fizkarlsruhe.ise.ontoboard.SelfCheck} exercises this in the host at startup, because
 * "it works in the bundle" is not something a test on Maven's classpath can establish.
 *
 * <p>Pure OWL API and robot-core; no Protege types and no Swing.
 */
public final class TermExport {

    /** ROBOT's own option keys, spelled once so a typo cannot silently do nothing. */
    public static final String OPTION_FORMAT = "format";
    public static final String OPTION_INCLUDE = "include";
    public static final String OPTION_SORT = "sort";
    public static final String OPTION_ENTITY_FORMAT = "entity-format";
    public static final String OPTION_SPLIT = "split";

    /** The format ROBOT can produce but this bundle cannot, and the only one. */
    public static final String UNSUPPORTED_FORMAT = "xlsx";

    /** YAML is rendered by ROBOT but not written by it; see {@link #formats()}. */
    private static final String YAML = "yaml";

    private TermExport() {
    }

    /**
     * The formats that work here, best-supported first.
     *
     * <p>This list is what {@code Table.write} actually dispatches on - {@code tsv}, {@code csv},
     * {@code html}, {@code html-list}, {@code json}, {@code xlsx}, read off its bytecode - minus
     * {@code xlsx}, plus {@code yaml}.
     *
     * <p>{@code yaml} is the one format written here rather than by ROBOT: {@code Table} renders it
     * through {@code toYAML()} but {@code write} has no branch for it, so asking ROBOT to write a
     * {@code .yaml} silently produces no file. That was found by a test requiring every offered
     * format to produce one, which is why the list and the writer cannot drift apart.
     *
     * <p>{@code xlsx} is absent deliberately; see the class comment. Offering it and failing would
     * be worse than not offering it.
     */
    public static List<String> formats() {
        return Collections.unmodifiableList(
                Arrays.asList("tsv", "csv", "json", YAML, "html", "html-list"));
    }

    /**
     * A starting set of columns that resolves against any ontology.
     *
     * <p>Only ROBOT keywords, deliberately. This began as {@code IRI, LABEL, type, definition,
     * SubClass Of} on the assumption that an unresolvable column would come back empty. It does not:
     * ROBOT throws {@code INVALID COLUMN ERROR unable to find property for column header
     * 'definition'} and the whole export fails. Since {@code definition} resolves only where the
     * ontology declares an annotation property labelled that way - which the OBO ontologies do and a
     * plain OWL file does not - having it in the defaults made the default export fail on exactly
     * the ontologies a new user is most likely to try it on.
     *
     * <p>{@link #annotationColumns(OWLOntology)} is how a caller offers the properties that really
     * are there.
     */
    public static List<String> defaultColumns() {
        return Collections.unmodifiableList(
                Arrays.asList("IRI", "LABEL", "type", "SubClass Of"));
    }

    /**
     * The annotation properties this ontology actually has, by label, for a caller to offer.
     *
     * <p>Any column that is not one of {@link #knownColumns()} is resolved by matching an annotation
     * property's label, and a name that matches nothing fails the entire export. So the useful thing
     * to put in front of a user is not a free-text box but the list of labels that will work.
     *
     * <p>The imports closure is included, because an imported property is one the export can use.
     * Properties with no label are skipped: there is nothing to type.
     */
    public static List<String> annotationColumns(OWLOntology ontology) {
        List<String> labels = new ArrayList<String>();
        if (ontology == null) {
            return labels;
        }
        for (org.semanticweb.owlapi.model.OWLAnnotationProperty property
                : ontology.getAnnotationPropertiesInSignature(
                        org.semanticweb.owlapi.model.parameters.Imports.INCLUDED)) {
            // One ontology at a time: EntitySearcher.getAnnotations takes a single ontology in OWL
            // API 4.5, not the closure.
            for (OWLOntology inClosure : ontology.getImportsClosure()) {
                for (org.semanticweb.owlapi.model.OWLAnnotation annotation
                        : org.semanticweb.owlapi.search.EntitySearcher.getAnnotations(
                                property, inClosure)) {
                    if (!annotation.getProperty().isLabel()) {
                        continue;
                    }
                    String label = literal(annotation);
                    if (label != null && !labels.contains(label)) {
                        labels.add(label);
                    }
                }
            }
        }
        Collections.sort(labels);
        return Collections.unmodifiableList(labels);
    }

    private static String literal(org.semanticweb.owlapi.model.OWLAnnotation annotation) {
        if (!(annotation.getValue() instanceof org.semanticweb.owlapi.model.OWLLiteral)) {
            return null;
        }
        String text = ((org.semanticweb.owlapi.model.OWLLiteral) annotation.getValue())
                .getLiteral().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * The column names ROBOT treats specially, as opposed to looking up as an annotation property.
     *
     * <p>Taken from {@code ExportOperation}'s own string constants rather than from ROBOT's
     * documentation, so this list is what the embedded version actually accepts. Anything not here
     * is resolved by label against the ontology's annotation properties, which is a feature: it is
     * how {@code definition}, {@code has_obo_namespace} or a project's own property get exported.
     */
    public static List<String> knownColumns() {
        return Collections.unmodifiableList(Arrays.asList(
                "IRI", "ID", "CURIE", "LABEL", "NAME", "NS", "type",
                "SubClass Of", "SubClasses", "SubProperty Of",
                "Equivalent Class", "Equivalent Classes",
                "Equivalent Property", "Equivalent Properties",
                "Disjoint With", "Domain", "Range", "SYNONYMS"));
    }

    /** ROBOT's defaults, with the format forced to one this bundle can actually write. */
    public static Map<String, String> defaultOptions() {
        Map<String, String> options =
                new LinkedHashMap<String, String>(ExportOperation.getDefaultOptions());
        options.put(OPTION_FORMAT, "tsv");
        return options;
    }

    /** The table ROBOT produced, plus the rows to show and a way to write the file. */
    public static final class Result {
        private final Table table;
        private final List<String[]> rows;
        private final String format;
        private final List<String> columns;

        Result(Table table, List<String[]> rows, String format, List<String> columns) {
            this.table = table;
            this.rows = Collections.unmodifiableList(rows);
            this.format = format;
            this.columns = Collections.unmodifiableList(columns);
        }

        /** Every row including ROBOT's header row, so the header cannot drift from the data. */
        public List<String[]> getRows() {
            return rows;
        }

        /** Data rows only. */
        public int getTermCount() {
            return Math.max(0, rows.size() - 1);
        }

        public String getFormat() {
            return format;
        }

        public List<String> getColumns() {
            return columns;
        }

        /**
         * Writes the file with ROBOT's own writer.
         *
         * <p>Not a renderer of our own, because CSV quoting and the JSON and YAML shapes are things
         * a consumer will compare against what {@code robot export} produces. Delegating keeps them
         * identical by construction.
         *
         * @throws RobotException if the format is one this bundle cannot write, or writing fails
         */
        public void save(File file) {
            if (file == null) {
                throw new IllegalArgumentException("no file to write");
            }
            if (UNSUPPORTED_FORMAT.equalsIgnoreCase(format)) {
                throw new RobotException(unsupportedFormatMessage());
            }
            try {
                if (YAML.equals(format)) {
                    // ROBOT renders YAML but its writer has no branch for it, so write(…, "yaml")
                    // returns having produced nothing. The rendering is still ROBOT's.
                    java.nio.file.Files.write(file.toPath(),
                            table.toYAML().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    table.write(file.getAbsolutePath(), format);
                }
            } catch (IOException cannotWrite) {
                throw new RobotException("Could not write " + file.getName() + ": "
                        + cannotWrite.getMessage(), cannotWrite);
            } catch (RuntimeException | LinkageError failure) {
                throw new RobotException("ROBOT could not write this export as " + format + ": "
                        + describe(failure), failure);
            }
        }
    }

    /**
     * Exports {@code columns} for the entities {@code options} selects.
     *
     * @param columns ROBOT column names; see {@link #knownColumns()} and {@link #defaultColumns()}
     * @param options ROBOT's own option map - see {@link #defaultOptions()}
     * @throws RobotException if ROBOT cannot build the table, including when a column name is one it
     *     does not recognise. Callers must surface this rather than showing an empty table, which
     *     for an export would quietly produce a file the user then trusts.
     */
    public static Result run(OWLOntology ontology, List<String> columns,
            Map<String, String> options) {
        if (ontology == null) {
            throw new IllegalArgumentException("no ontology to export");
        }
        List<String> wanted = clean(columns);
        if (wanted.isEmpty()) {
            throw new RobotException("Choose at least one column to export.");
        }
        Map<String, String> effective = options == null
                ? defaultOptions()
                : new LinkedHashMap<String, String>(options);
        String format = format(effective);
        if (UNSUPPORTED_FORMAT.equalsIgnoreCase(format)) {
            throw new RobotException(unsupportedFormatMessage());
        }

        try {
            Table table = ExportOperation.createExportTable(ontology, new IOHelper(), wanted,
                    effective);
            // ROBOT sorts only when told to, and an unsorted export of thousands of terms is
            // unusable for comparing two runs.
            if (!"false".equalsIgnoreCase(String.valueOf(effective.get(OPTION_SORT)))) {
                table.setSortColumns();
                table.sortRows();
            }
            List<String[]> rows = table.toList(format);
            return new Result(table, rows == null ? new ArrayList<String[]>() : rows, format,
                    wanted);
        } catch (IllegalArgumentException badColumn) {
            // ROBOT's own message names the column, which is the one thing the user needs.
            throw new RobotException(badColumn.getMessage(), badColumn);
        } catch (LinkageError incompatible) {
            throw new RobotException("ROBOT's export could not run in this Protege: "
                    + describe(incompatible) + ". This is an OSGi packaging problem rather than "
                    + "something wrong with the ontology.", incompatible);
        } catch (Exception failure) {
            throw new RobotException("ROBOT could not build the export: " + describe(failure),
                    failure);
        }
    }

    private static String format(Map<String, String> options) {
        String format = options.get(OPTION_FORMAT);
        return format == null || format.trim().isEmpty()
                ? "tsv"
                : format.trim().toLowerCase(Locale.ROOT);
    }

    /** Blank lines dropped and whitespace trimmed, so a pasted column list behaves. */
    private static List<String> clean(List<String> columns) {
        List<String> wanted = new ArrayList<String>();
        if (columns == null) {
            return wanted;
        }
        for (String column : columns) {
            if (column != null && !column.trim().isEmpty()) {
                wanted.add(column.trim());
            }
        }
        return wanted;
    }

    private static String unsupportedFormatMessage() {
        return "This plugin cannot write " + UNSUPPORTED_FORMAT + ". Apache POI is embedded but the "
                + "logging backend it needs cannot be, because log4j-api declares its own OSGi "
                + "activator. Every other format ROBOT supports works: " + formats() + ".";
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getName()
                : message;
    }
}
