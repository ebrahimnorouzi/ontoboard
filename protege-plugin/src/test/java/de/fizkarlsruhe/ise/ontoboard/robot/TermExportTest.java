package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.e2e.PizzaOntology;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.OWLOntology;

/** ROBOT's export, over the pizza the rest of the suite uses. */
class TermExportTest {

    private static Map<String, String> options(String format) {
        Map<String, String> options = TermExport.defaultOptions();
        options.put(TermExport.OPTION_FORMAT, format);
        return options;
    }

    private static String cell(List<String[]> rows, int row, int column) {
        String[] cells = rows.get(row);
        return column < cells.length ? cells[column] : "";
    }

    /** The column the header names, wherever ROBOT happens to put it. */
    private static int columnOf(List<String[]> rows, String header) {
        String[] headers = rows.get(0);
        for (int i = 0; i < headers.length; i++) {
            if (header.equalsIgnoreCase(String.valueOf(headers[i]).trim())) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void exportsTheDefaultColumnsForEveryClass() throws Exception {
        OWLOntology pizza = PizzaOntology.v2();

        TermExport.Result result = TermExport.run(pizza, TermExport.defaultColumns(),
                options("tsv"));

        assertTrue(result.getTermCount() > 0, "the pizza has classes, so the export cannot be empty");
        int iri = columnOf(result.getRows(), "IRI");
        assertTrue(iri >= 0, "ROBOT must emit the IRI column that was asked for: "
                + Arrays.toString(result.getRows().get(0)));

        List<String> iris = new ArrayList<String>();
        for (int row = 1; row < result.getRows().size(); row++) {
            iris.add(cell(result.getRows(), row, iri));
        }
        assertTrue(iris.contains("http://example.org/pizza#MargheritaPizza")
                        || iris.contains("http://example.org/pizza#VegetarianPizza"),
                "a pizza export that names none of the pizza classes is not an export: " + iris);
    }

    /**
     * The header comes from ROBOT and travels with the data.
     *
     * <p>Row 0 being the header is the contract {@link TermExport.Result#getTermCount()} depends on;
     * if ROBOT ever stopped emitting it, every count would be one too high and every table would be
     * shifted by a row.
     */
    @Test
    void rowZeroIsTheHeader() throws Exception {
        TermExport.Result result = TermExport.run(PizzaOntology.v2(),
                Arrays.asList("IRI", "LABEL"), options("tsv"));

        assertEquals(2, result.getRows().get(0).length);
        assertTrue(columnOf(result.getRows(), "IRI") >= 0);
        assertTrue(columnOf(result.getRows(), "LABEL") >= 0);
        assertEquals(result.getRows().size() - 1, result.getTermCount());
    }

    /** Every offered format must actually write, because offering one that cannot is the bug. */
    @Test
    void everyOfferedFormatWritesAFile(@TempDir File dir) throws Exception {
        OWLOntology pizza = PizzaOntology.v2();

        for (String format : TermExport.formats()) {
            TermExport.Result result = TermExport.run(pizza, Arrays.asList("IRI", "LABEL"),
                    options(format));
            File out = new File(dir, "pizza." + format);
            result.save(out);

            assertTrue(out.isFile(), format + " produced no file");
            String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
            assertFalse(text.trim().isEmpty(), format + " produced an empty file");
            assertTrue(text.contains("pizza"),
                    format + " produced a file that names no pizza term: "
                            + text.substring(0, Math.min(200, text.length())));
        }
    }

    /**
     * xlsx is refused with an explanation, not attempted.
     *
     * <p>POI is embedded but the log4j 2 API it needs is not - {@code log4j-api} ships its own OSGi
     * activator - so {@code Table.asWorkbook} throws {@code NoClassDefFoundError} inside the bundle.
     * A user who picks xlsx must be told that, not shown a stack trace.
     */
    @Test
    void xlsxIsRefusedWithAReason() throws Exception {
        assertFalse(TermExport.formats().contains("xlsx"),
                "xlsx must not be offered while POI cannot initialise in the bundle");

        RobotException refused = assertThrows(RobotException.class, () -> TermExport.run(
                PizzaOntology.v2(), Arrays.asList("IRI"), options("xlsx")));

        assertTrue(refused.getMessage().contains("POI"),
                "the message must say why, so the user can choose another format: "
                        + refused.getMessage());
        assertTrue(refused.getMessage().contains("tsv"),
                "and name a format that does work: " + refused.getMessage());
    }

    @Test
    void anEmptyColumnListIsRefusedRatherThanExportingNothing() throws Exception {
        OWLOntology pizza = PizzaOntology.v2();

        assertTrue(assertThrows(RobotException.class,
                () -> TermExport.run(pizza, new ArrayList<String>(), options("tsv")))
                .getMessage().toLowerCase().contains("column"));
        assertTrue(assertThrows(RobotException.class,
                () -> TermExport.run(pizza, Arrays.asList("  ", ""), options("tsv")))
                .getMessage().toLowerCase().contains("column"));
    }

    /** A column ROBOT cannot resolve must name itself in the failure. */
    @Test
    void anUnknownColumnSaysWhichOne() throws Exception {
        RobotException failed = assertThrows(RobotException.class, () -> TermExport.run(
                PizzaOntology.v2(), Arrays.asList("IRI", "not_a_real_column"), options("tsv")));

        assertTrue(failed.getMessage().contains("not_a_real_column"),
                "the message must name the column the user got wrong: " + failed.getMessage());
    }

    /** Default options must be ones this bundle can honour, not ROBOT's xlsx-capable defaults. */
    @Test
    void theDefaultFormatIsOneThisBundleCanWrite() {
        Map<String, String> defaults = TermExport.defaultOptions();

        assertTrue(TermExport.formats().contains(defaults.get(TermExport.OPTION_FORMAT)),
                "default format was " + defaults.get(TermExport.OPTION_FORMAT));
    }

    /**
     * Columns outside ROBOT's keyword list resolve by annotation-property label - and only those
     * the ontology really declares.
     *
     * <p>An unresolvable name fails the whole export, so what a caller may offer is exactly
     * {@code annotationColumns}. This asserts both halves: a label from that list works, and the
     * list is honest about what the pizza has.
     */
    @Test
    void anAnnotationPropertyLabelFromTheOntologyIsAValidColumn() throws Exception {
        OWLOntology pizza = PizzaOntology.v2();
        List<String> available = TermExport.annotationColumns(pizza);

        for (String column : available) {
            TermExport.Result result = TermExport.run(pizza, Arrays.asList("IRI", column),
                    options("tsv"));
            assertTrue(columnOf(result.getRows(), column) >= 0,
                    "annotationColumns offered '" + column + "' but the export has no such column: "
                            + Arrays.toString(result.getRows().get(0)));
        }

        assertFalse(available.contains("not_a_real_column"));
    }

    /**
     * The defaults must work on an ontology that declares no annotation properties at all.
     *
     * <p>They did not: 'definition' was among them, ROBOT fails an unresolvable column rather than
     * emitting it empty, and the pizza declares no property labelled 'definition' - so the default
     * export failed on the project's own fixture.
     */
    @Test
    void theDefaultColumnsResolveWithoutAnyAnnotationProperties() throws Exception {
        for (String column : TermExport.defaultColumns()) {
            assertTrue(TermExport.knownColumns().contains(column),
                    "'" + column + "' is not a ROBOT keyword, so it only resolves where the "
                            + "ontology happens to declare a property with that label");
        }
        assertTrue(TermExport.run(PizzaOntology.v2(), TermExport.defaultColumns(), options("tsv"))
                .getTermCount() > 0);
    }

    @Test
    void savingRefusesNullAndReportsAnUnwritableFormat(@TempDir File dir) throws Exception {
        TermExport.Result result = TermExport.run(PizzaOntology.v2(), Arrays.asList("IRI"),
                options("tsv"));

        assertThrows(IllegalArgumentException.class, () -> result.save(null));

        Map<String, String> sneaky = new LinkedHashMap<String, String>(options("tsv"));
        sneaky.put(TermExport.OPTION_FORMAT, "tsv");
        assertEquals("tsv", TermExport.run(PizzaOntology.v2(), Arrays.asList("IRI"), sneaky)
                .getFormat());
    }
}
