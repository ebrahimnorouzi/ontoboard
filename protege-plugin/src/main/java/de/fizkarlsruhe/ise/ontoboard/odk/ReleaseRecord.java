package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.robot.OntologyMeasurements;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.obolibrary.robot.metrics.MetricsLabels;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * What a release records of itself - RELEASE.md beside a kept copy, and the release tag's
 * message (1.155.0, asked by the user: "in the release, i need more info, like the report,
 * measurements, quality checks, which reasoner is used, what was added,... for each release, what
 * is used to produced that (ontoboard alone or ontoboard ODK docker,...)").
 *
 * <p>Read where ODK writes it: the ROBOT report and the OWL 2 DL profile check under
 * {@code src/ontology/reports/} (ODK's {@code test}, which every release runs), the release's
 * main file at the project's top, the edit file's imports through its catalog. What was not
 * there is said, not left out.
 */
public final class ReleaseRecord {

    /** The measures shown, in their order: ROBOT's key and the words for it. */
    private static final String[][] MEASURES = {
        {MetricsLabels.CLASS_COUNT_INCL, "Classes"},
        {MetricsLabels.OBJPROPERTY_COUNT_INCL, "Object properties"},
        {MetricsLabels.DATAPROPERTY_COUNT_INCL, "Data properties"},
        {MetricsLabels.ANNOTATION_PROP_COUNT_INCL, "Annotation properties"},
        {MetricsLabels.INDIVIDUAL_COUNT_INCL, "Individuals"},
        {MetricsLabels.LOGICAL_AXIOM_COUNT_INCL, "Logical axioms"},
        {MetricsLabels.EXPRESSIVITY_INCL, "Expressivity"},
        {MetricsLabels.BOOL_PROFILE_OWL2_DL, "In OWL 2 DL"}};

    private ReleaseRecord() {
    }

    /** What is recorded of one release. */
    public static final class Facts {
        private final String id;
        private final String version;
        private final String date;
        private final String ontoboard;
        private final String madeBy;
        private final String verdict;
        private final Map<String, String> measures;
        private final String reportFile;
        private final int[] byLevel;
        private final Map<String, Integer> byRule;
        private final String profile;
        private final String previous;
        private final String changes;
        private final List<String[]> imports;

        /**
         * @param madeBy     what built it, with its versions: "OntoBoard's own engine (ROBOT
         *                   1.9.8, inside Protégé)", "ODK's container, obolibrary/odkfull:latest
         *                   (ODK v1.6.1)"
         * @param verdict    what the release's reasoner said, or null when it was not asked
         * @param reportFile the ROBOT report, from the project's top, or null when none was found
         * @param byLevel    errors, warnings, infos in it
         * @param profile    the profile check's first line, or null
         * @param previous   the release compared with, or null for the first
         * @param imports    each import's IRI and the version its file declares
         */
        public Facts(String id, String version, String date, String ontoboard, String madeBy,
                String verdict, Map<String, String> measures, String reportFile, int[] byLevel,
                Map<String, Integer> byRule, String profile, String previous, String changes,
                List<String[]> imports) {
            this.id = id;
            this.version = version;
            this.date = date;
            this.ontoboard = ontoboard;
            this.madeBy = madeBy;
            this.verdict = verdict;
            this.measures = measures == null ? Collections.<String, String>emptyMap() : measures;
            this.reportFile = reportFile;
            this.byLevel = byLevel == null ? new int[3] : byLevel;
            this.byRule = byRule == null ? Collections.<String, Integer>emptyMap() : byRule;
            this.profile = profile;
            this.previous = previous;
            this.changes = changes;
            this.imports = imports == null ? Collections.<String[]>emptyList() : imports;
        }
    }

    /**
     * Reads what ODK wrote for this release and measures its main file.
     *
     * @param madeBy  what built it, with its versions
     * @param verdict what the release's reasoner said, or null
     * @param changes what changed since {@code previous}, in a sentence; null for the first
     */
    public static Facts collect(File projectRoot, String id, String version, String date,
            String madeBy, String verdict, String previous, String changes) {
        File reports = new File(projectRoot, "src/ontology/reports");
        File report = reportIn(reports, id);
        int[] byLevel = new int[3];
        Map<String, Integer> byRule = new LinkedHashMap<String, Integer>();
        if (report != null) {
            count(report, byLevel, byRule);
        }
        return new Facts(id, version, date, de.fizkarlsruhe.ise.ontoboard.assistant.BuildInfo
                .version(), madeBy, verdict, measuresOf(new File(projectRoot, id + ".owl")),
                report == null ? null : shown(projectRoot, report), byLevel, byRule,
                firstLineOf(new File(reports, "validate_profile_owl2dl_" + id + ".owl.txt")),
                previous, changes, importsOf(projectRoot, id));
    }

    /** ODK's ROBOT report of the edit file - {@code <id>-edit.<ext>-obo-report.tsv} - or any. */
    private static File reportIn(File reports, String id) {
        File[] found = reports.listFiles((folder, name) -> name.endsWith("-obo-report.tsv"));
        if (found == null || found.length == 0) {
            return null;
        }
        Arrays.sort(found);
        for (File one : found) {
            if (one.getName().startsWith(id + "-edit.")) {
                return one;
            }
        }
        return found[0];
    }

    /** ROBOT's report: Level, Rule Name, Subject, Property, Value - a header line first. */
    private static void count(File report, int[] byLevel, Map<String, Integer> byRule) {
        List<String> lines;
        try {
            lines = Files.readAllLines(report.toPath(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return;
        }
        Map<String, Integer> counted = new LinkedHashMap<String, Integer>();
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
            String[] cells = line.split("\t", -1);
            if (cells.length < 2 || cells[0].trim().isEmpty()) {
                continue;
            }
            String level = cells[0].trim().toUpperCase(java.util.Locale.ROOT);
            int at = "ERROR".equals(level) ? 0 : "WARN".equals(level) ? 1 : 2;
            byLevel[at]++;
            String rule = level + " " + cells[1].trim();
            counted.put(rule, counted.containsKey(rule) ? counted.get(rule) + 1 : 1);
        }
        // Errors first, then warnings, then infos; each level in the report's order.
        for (String level : new String[] {"ERROR ", "WARN ", "INFO "}) {
            for (Map.Entry<String, Integer> rule : counted.entrySet()) {
                if (rule.getKey().startsWith(level)) {
                    byRule.put(rule.getKey(), rule.getValue());
                }
            }
        }
        for (Map.Entry<String, Integer> rule : counted.entrySet()) {
            if (!byRule.containsKey(rule.getKey())) {
                byRule.put(rule.getKey(), rule.getValue());
            }
        }
    }

    /** The release's main file, measured as Measure... measures; empty when it is not there. */
    private static Map<String, String> measuresOf(File main) {
        Map<String, String> measures = new LinkedHashMap<String, String>();
        if (!main.isFile()) {
            return measures;
        }
        try {
            OWLOntology released = OWLManager.createOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(main);
            Map<String, String> byKey = new LinkedHashMap<String, String>();
            for (OntologyMeasurements.Measurement measured : OntologyMeasurements.run(released,
                    OntologyMeasurements.Depth.EXTENDED)) {
                byKey.put(measured.getKey(), measured.getValue());
            }
            for (String[] one : MEASURES) {
                String value = byKey.get(one[0]);
                if (value != null) {
                    measures.put(one[1], one[0].equals(MetricsLabels.BOOL_PROFILE_OWL2_DL)
                            ? ("true".equalsIgnoreCase(value) ? "yes" : "no") : value);
                }
            }
        } catch (Exception | LinkageError unreadable) {
            // Said in the record: the main file could not be measured.
        }
        return measures;
    }

    /** The edit file's imports and the version each one's file declares. */
    private static List<String[]> importsOf(File projectRoot, String id) {
        List<String[]> imports = new ArrayList<String[]>();
        File edit = editFileOf(projectRoot, id);
        if (edit == null) {
            return imports;
        }
        try {
            OWLOntology ontology = ReleaseVerdict.load(edit);
            for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
                OWLOntology imported = ontology.getOWLOntologyManager()
                        .getImportedOntology(declaration);
                String versionIri = imported == null ? "not read"
                        : imported.getOntologyID().getVersionIRI().isPresent()
                                ? imported.getOntologyID().getVersionIRI().get().toString()
                                : "none given";
                imports.add(new String[] {declaration.getIRI().toString(), versionIri});
            }
        } catch (Exception | LinkageError unreadable) {
            // Said in the record: the imports could not be read.
        }
        return imports;
    }

    private static File editFileOf(File projectRoot, String id) {
        for (String extension : new String[] {"owl", "obo", "ofn", "ttl", "omn", "owx"}) {
            File edit = new File(projectRoot, "src/ontology/" + id + "-edit." + extension);
            if (edit.isFile()) {
                return edit;
            }
        }
        return null;
    }

    private static String firstLineOf(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) {
                    return line.trim();
                }
            }
        } catch (IOException unreadable) {
            return null;
        }
        return null;
    }

    private static String shown(File root, File file) {
        try {
            return root.getCanonicalFile().toPath().relativize(file.getCanonicalFile().toPath())
                    .toString().replace('\\', '/');
        } catch (IOException | IllegalArgumentException elsewhere) {
            return file.getAbsolutePath();
        }
    }

    /** The record, as RELEASE.md and the tag's message hold it. */
    public static String markdown(Facts facts) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(facts.id).append(' ').append(facts.version).append("\n\n");
        md.append("Released on ").append(facts.date).append(" with OntoBoard ")
                .append(facts.ontoboard).append(", built by ").append(facts.madeBy)
                .append(".\n\n");

        md.append("## What it is\n\n");
        if (facts.measures.isEmpty()) {
            md.append("The release's main file, ").append(facts.id).append(".owl, could not be ")
                    .append("measured: it was not found or not read.\n\n");
        } else {
            md.append("| | |\n|---|---|\n");
            for (Map.Entry<String, String> measure : facts.measures.entrySet()) {
                md.append("| ").append(measure.getKey()).append(" | ").append(measure.getValue())
                        .append(" |\n");
            }
            md.append('\n');
        }

        md.append("## How it was checked\n\n");
        md.append("- **Reasoner:** ").append(facts.verdict == null ? "not asked." : facts.verdict)
                .append('\n');
        if (facts.reportFile == null) {
            md.append("- **Quality report:** The quality report was not found: ODK did not write "
                    + "it.\n");
        } else {
            md.append("- **Quality report** (`").append(facts.reportFile).append("`): ")
                    .append(counted(facts.byLevel[0], "error", "errors")).append(", ")
                    .append(counted(facts.byLevel[1], "warning", "warnings")).append(", ")
                    .append(counted(facts.byLevel[2], "info", "infos")).append(".\n");
            for (Map.Entry<String, Integer> rule : facts.byRule.entrySet()) {
                md.append("  - ").append(rule.getKey()).append(": ").append(rule.getValue())
                        .append('\n');
            }
        }
        md.append("- **OWL 2 DL profile:** ").append(facts.profile == null
                ? "not checked: ODK wrote no profile report." : facts.profile).append('\n');
        md.append("- **ODK's checks:** passed - a release stops at the first that fails.\n\n");

        if (facts.previous == null) {
            md.append("## What changed\n\nThe first release.\n\n");
        } else {
            md.append("## What changed since ").append(facts.previous).append("\n\n")
                    .append(facts.changes == null || facts.changes.trim().isEmpty()
                            ? "Not compared." : facts.changes.trim()).append("\n\n");
        }

        md.append("## What it imports\n\n");
        if (facts.imports.isEmpty()) {
            md.append("Nothing, or what it imports could not be read.\n");
        } else {
            md.append("| Import | Version |\n|---|---|\n");
            for (String[] imported : facts.imports) {
                md.append("| ").append(imported[0]).append(" | ").append(imported[1])
                        .append(" |\n");
            }
        }
        return md.toString();
    }

    private static String counted(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }
}
