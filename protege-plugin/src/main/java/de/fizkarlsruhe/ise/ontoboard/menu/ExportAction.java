package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.robot.TermExport;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * ROBOT &gt; Export - the open ontology as a table of terms and the columns you choose.
 *
 * <p>The thing curators ask for that Protege has no answer to: a file listing every term with its
 * label, its parent and whichever annotations matter, ready for a spreadsheet or a script. Protege
 * shows that one term at a time.
 *
 * <p><b>The column field offers what will work.</b> ROBOT resolves any column that is not one of its
 * own keywords by matching an annotation property's <em>label</em>, and a name that matches nothing
 * fails the whole export with {@code INVALID COLUMN ERROR}. So the help text lists both the keywords
 * and the annotation labels this particular ontology actually declares, rather than leaving the user
 * to guess and get an error. This is also why {@code definition} is not a default: it works on an OBO
 * ontology and fails on a plain OWL file.
 */
public class ExportAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_COLUMNS = "columns";
    private static final String OPTION_INCLUDE = "include";
    private static final String OPTION_FORMAT = "format";
    private static final String OPTION_ENTITIES = "entities";
    private static final String OPTION_FILE = "file";

    /** Long enough to see the shape of the export; the file holds all of it. */
    private static final int MAX_PREVIEW_ROWS = 500;

    /** What ROBOT's {@code include} option takes, behind labels a curator recognises. */
    private static final Map<String, String> INCLUDE = new LinkedHashMap<String, String>();

    static {
        INCLUDE.put("Classes", "classes");
        INCLUDE.put("Classes and individuals", "classes individuals");
        INCLUDE.put("Classes, properties and individuals", "classes properties individuals");
    }

    private volatile List<String> columns = TermExport.defaultColumns();
    private volatile Map<String, String> options = TermExport.defaultOptions();
    private volatile File file;

    @Override
    protected String operationName() {
        return "Export";
    }

    @Override
    protected boolean configure() {
        List<String> available = annotationColumnsOfOpenOntology();

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Export",
                "ROBOT writes a table with one row per term and one column per thing you ask for. "
                        + "The ontology is not modified.",
                Arrays.asList(
                        Parameter.of(OPTION_COLUMNS, "Columns, one per line",
                                Parameter.Kind.MULTILINE)
                                .defaultValue(join(TermExport.defaultColumns()))
                                .required()
                                .help(columnHelp(available))
                                .build(),
                        Parameter.of(OPTION_INCLUDE, "Which terms", Parameter.Kind.CHOICE)
                                .choices(INCLUDE.keySet().toArray(new String[0]))
                                .defaultValue("Classes")
                                .required()
                                .help("Properties and individuals are left out by default because "
                                        + "most exports are about the class hierarchy, and "
                                        + "including them mixes three kinds of row in one table.")
                                .build(),
                        Parameter.of(OPTION_ENTITIES, "Show related terms as",
                                Parameter.Kind.CHOICE)
                                .choices("LABEL", "NAME", "CURIE", "ID", "IRI")
                                .defaultValue("LABEL")
                                .required()
                                .help("How a term is written inside a cell - the parents in "
                                        + "'SubClass Of', for instance. Labels read best; IRIs are "
                                        + "what a script wants.")
                                .build(),
                        Parameter.of(OPTION_FORMAT, "File format", Parameter.Kind.CHOICE)
                                .choices(TermExport.formats().toArray(new String[0]))
                                .defaultValue("tsv")
                                .required()
                                .help("TSV opens in every spreadsheet and survives definitions "
                                        + "containing commas. JSON and YAML are for scripts, HTML "
                                        + "for something to send someone.\n\nxlsx is not offered: "
                                        + "Apache POI cannot start inside a Protege plugin, "
                                        + "because the logging library it needs brings its own "
                                        + "OSGi activator and cannot be bundled alongside "
                                        + "Protege's.")
                                .build(),
                        Parameter.of(OPTION_FILE, "Save to (optional)", Parameter.Kind.FILE)
                                .help("Leave this empty to look at the result here first. The "
                                        + "result window can save what it shows either way; this "
                                        + "writes the file with ROBOT's own writer, which is what "
                                        + "you want if something else is going to parse it.")
                                .build()));
        if (chosen == null) {
            return false;
        }

        columns = split(chosen.get(OPTION_COLUMNS));
        Map<String, String> wanted = TermExport.defaultOptions();
        wanted.put(TermExport.OPTION_FORMAT, chosen.get(OPTION_FORMAT));
        wanted.put(TermExport.OPTION_INCLUDE,
                INCLUDE.get(chosen.get(OPTION_INCLUDE)) == null
                        ? "classes"
                        : INCLUDE.get(chosen.get(OPTION_INCLUDE)));
        wanted.put(TermExport.OPTION_ENTITY_FORMAT,
                String.valueOf(chosen.get(OPTION_ENTITIES)).toUpperCase(Locale.ROOT));
        options = wanted;

        String path = chosen.get(OPTION_FILE);
        file = path == null || path.trim().isEmpty() ? null : new File(path.trim());
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        TermExport.Result export = TermExport.run(ontology, columns, options);
        OperationResult.Builder result = OperationResult.of(operationName());

        List<String[]> rows = export.getRows();
        if (rows.isEmpty()) {
            return result.failed("ROBOT produced no table at all, not even a header row.").build();
        }

        result.columns(rows.get(0));
        int shown = 0;
        for (int row = 1; row < rows.size(); row++) {
            if (shown >= MAX_PREVIEW_ROWS) {
                result.note("Showing the first " + MAX_PREVIEW_ROWS + " of "
                        + export.getTermCount() + " terms."
                        + (file == null ? " Save to a file to get all of them." : ""));
                break;
            }
            result.row(padded(rows.get(row), rows.get(0).length));
            shown++;
        }

        // Recorded so a saved result says what produced it: a table of terms with no record of the
        // columns and the selection behind it cannot be compared with another run.
        result.note("Columns: " + join(export.getColumns()).replace('\n', ' '));
        result.note("Terms: " + options.get(TermExport.OPTION_INCLUDE));
        result.note("Format: " + export.getFormat());

        if (file != null) {
            export.save(file);
            result.note("Written to " + file.getAbsolutePath());
        }
        return result.summary(export.getTermCount() + " terms, "
                + export.getColumns().size() + " columns"
                + (file == null ? ", not written to a file" : "")).build();
    }

    /** ROBOT can return a short row; the table needs every row the same width. */
    private static String[] padded(String[] row, int width) {
        if (row.length == width) {
            return row;
        }
        String[] padded = new String[width];
        for (int i = 0; i < width; i++) {
            padded[i] = i < row.length && row[i] != null ? row[i] : "";
        }
        return padded;
    }

    /**
     * The annotation labels the open ontology declares, or empty if it cannot be read here.
     *
     * <p>Called on the EDT while building the dialog, so it must not throw: a failure to enumerate
     * annotation properties would be a poor reason to refuse to open the export window at all.
     */
    private List<String> annotationColumnsOfOpenOntology() {
        try {
            return TermExport.annotationColumns(getOWLModelManager().getActiveOntology());
        } catch (RuntimeException cannotRead) {
            return new ArrayList<String>();
        }
    }

    private static String columnHelp(List<String> available) {
        StringBuilder help = new StringBuilder();
        help.append("ROBOT understands these by name:\n  ");
        help.append(join(TermExport.knownColumns()).replace("\n", ", "));
        help.append("\n\nAnything else is matched against an annotation property's label. ");
        if (available.isEmpty()) {
            help.append("This ontology declares no labelled annotation properties, so only the "
                    + "names above will work here. A column ROBOT cannot match fails the whole "
                    + "export rather than coming back empty.");
        } else {
            help.append("This ontology also has:\n  ");
            help.append(join(available).replace("\n", ", "));
            help.append("\n\nA column ROBOT cannot match fails the whole export rather than "
                    + "coming back empty, so prefer a name from these lists.");
        }
        return help.toString();
    }

    private static String join(List<String> values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(value);
        }
        return text.toString();
    }

    private static List<String> split(String text) {
        List<String> values = new ArrayList<String>();
        if (text == null) {
            return values;
        }
        for (String line : text.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                values.add(line.trim());
            }
        }
        return values;
    }
}
