package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a release records of itself (1.155.0, asked by the user: "in the release, i need more
 * info, like the report, measurements, quality checks, which reasoner is used, what was added,...
 * for each release, what is used to produced that (ontoboard alone or ontoboard ODK docker,...)").
 */
class ReleaseRecordTest {

    private static ReleaseRecord.Facts eggboil() {
        Map<String, String> measures = new LinkedHashMap<String, String>();
        measures.put("Classes", "12");
        measures.put("Object properties", "3");
        measures.put("Data properties", "1");
        measures.put("Annotation properties", "5");
        measures.put("Individuals", "2");
        measures.put("Logical axioms", "40");
        measures.put("Expressivity", "ALC");
        measures.put("In OWL 2 DL", "yes");
        Map<String, Integer> byRule = new LinkedHashMap<String, Integer>();
        byRule.put("WARN missing_definition", 2);
        byRule.put("INFO lowercase_definition", 1);
        return new ReleaseRecord.Facts("eggboil", "1.1.0", "2026-11-19", "1.155.0",
                "OntoBoard's own engine (ROBOT 1.9.8, inside Protégé)",
                "HermiT finds the ontology, with its imports, consistent, and every class can "
                        + "have members (under a second).",
                measures, "src/ontology/reports/eggboil-edit.owl-obo-report.tsv",
                new int[] {0, 2, 1}, byRule,
                "OWL 2 DL Profile Report: [Ontology and imports closure in profile]",
                "1.0.0", "+2 classes, 1 obsoleted.",
                Arrays.asList(new String[][] {{
                    "http://purl.obolibrary.org/obo/eggboil/imports/ro_import.owl",
                    "http://purl.obolibrary.org/obo/eggboil/releases/2026-11-19/imports/"
                            + "ro_import.owl"}}));
    }

    @Test
    void theRecordReadsLikeTheGolden() throws Exception {
        String golden = new String(Files.readAllBytes(new File(
                "src/test/resources/odk/release-record/RELEASE.md").toPath()),
                StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertEquals(golden, ReleaseRecord.markdown(eggboil()));
    }

    /** Where ODK wrote no report, the record says so - it does not leave the line out. */
    @Test
    void aMissingReportIsSaidNotSkipped(@TempDir File root) throws Exception {
        project(root);

        String record = ReleaseRecord.markdown(ReleaseRecord.collect(root, "eggboil", "1.1.0",
                "2026-11-19", "ODK's container, obolibrary/odkfull:latest (ODK v1.6.1)", null,
                null, null));

        assertTrue(record.contains("The quality report was not found: ODK did not write it."),
                record);
        assertTrue(record.contains("built by ODK's container, obolibrary/odkfull:latest "
                + "(ODK v1.6.1)"), record);
        assertTrue(record.contains("- **Reasoner:** not asked."), record);
    }

    /** ODK's report, its profile check and the release's main file, read where ODK writes them. */
    @Test
    void whatOdkWroteIsRead(@TempDir File root) throws Exception {
        project(root);
        File reports = new File(root, "src/ontology/reports");
        assertTrue(reports.mkdirs());
        Files.write(new File(reports, "eggboil-edit.owl-obo-report.tsv").toPath(), ("Level\tRule "
                + "Name\tSubject\tProperty\tValue\n"
                + "WARN\tmissing_definition\tEGGBOIL:1\tIAO:0000115\t\n"
                + "WARN\tmissing_definition\tEGGBOIL:2\tIAO:0000115\t\n"
                + "INFO\tlowercase_definition\tEGGBOIL:3\tIAO:0000115\tegg\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(reports, "validate_profile_owl2dl_eggboil.owl.txt").toPath(),
                ("OWL 2 DL Profile Report: [Ontology and imports closure in profile]\n")
                        .getBytes(StandardCharsets.UTF_8));

        String record = ReleaseRecord.markdown(ReleaseRecord.collect(root, "eggboil", "1.1.0",
                "2026-11-19", "OntoBoard's own engine", "HermiT finds it consistent.",
                "1.0.0", "+2 classes."));

        assertTrue(record.contains("0 errors, 2 warnings, 1 info."), record);
        assertTrue(record.contains("  - WARN missing_definition: 2"), record);
        assertTrue(record.contains("**OWL 2 DL profile:** OWL 2 DL Profile Report: [Ontology "
                + "and imports closure in profile]"), record);
        assertTrue(record.contains("| Classes | 2 |"), record);
        assertTrue(record.contains("## What changed since 1.0.0\n\n+2 classes."), record);
        assertTrue(record.contains("| http://example.org/eggboil/imports/x_import.owl | "
                + "http://example.org/eggboil/releases/1.1.0/imports/x_import.owl |"), record);
    }

    /** eggboil: its main release file, its edit file importing one module through the catalog. */
    private static void project(File root) throws Exception {
        File ontology = new File(root, "src/ontology");
        assertTrue(new File(ontology, "imports").mkdirs());
        Files.write(new File(root, "eggboil.owl").toPath(), ("Prefix(:=<http://example.org/"
                + "eggboil/>)\nOntology(<http://example.org/eggboil.owl>\n"
                + "Declaration(Class(:Egg))\nDeclaration(Class(:Pan))\nSubClassOf(:Egg :Pan)\n)\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(ontology, "eggboil-edit.owl").toPath(), ("Ontology(<http://"
                + "example.org/eggboil.owl>\nImport(<http://example.org/eggboil/imports/"
                + "x_import.owl>)\n)\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(ontology, "imports/x_import.owl").toPath(), ("Ontology(<http://"
                + "example.org/eggboil/imports/x_import.owl> <http://example.org/eggboil/"
                + "releases/1.1.0/imports/x_import.owl>\n)\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(ontology, "catalog-v001.xml").toPath(), Catalog.withEntry(null,
                "http://example.org/eggboil/imports/x_import.owl", "imports/x_import.owl")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(ontology, "eggboil-odk.yaml").toPath(), "id: eggboil\n"
                .getBytes(StandardCharsets.UTF_8));
    }
}
