package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * THE GATE (1.155.0): every library pattern, as a project that imports it sees it, is clean.
 *
 * <p>Its {@code pattern.owl} - what an import copies - passes OntoBoard's quality report with the
 * profile a new ODK project carries (ROBOT's own rules and levels: no ERROR); every entity it
 * names has an {@code rdfs:label}; HermiT finds it consistent with no unsatisfiable class - with
 * no list of exceptions -; its {@code pattern.ttl} says the same; and every pattern OntoBoard
 * changed since 1.154.0 says so where a user looks: its {@code metadata.json} lists the changes
 * under {@code curated}, and its ontology carries an {@code rdfs:comment} that starts "Curated by
 * OntoBoard", which a project that imports it can read.
 *
 * <p>Each assertion names the failing patterns. Imports are stubbed as {@link PatternSoundness}
 * does; the library is audited once for the class.
 */
class PatternQualityTest {

    private static final String CURATED = de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance.CURATED;

    private static Map<String, PatternAudit.Row> rows;           // NOPMD - audited once

    private static File patternsDirectory() {
        return new File(new File(System.getProperty("user.dir")).getAbsoluteFile()
                .getParentFile(), "patterns");
    }

    @BeforeAll
    static void auditTheLibraryOnce() throws Exception {
        File stubs = new File("target", "pattern-quality-stubs");
        assertTrue(stubs.isDirectory() || stubs.mkdirs(), "could not make " + stubs);
        PatternAudit audit = new PatternAudit(stubs);
        rows = new TreeMap<String, PatternAudit.Row>();
        File[] directories = patternsDirectory().listFiles();
        Arrays.sort(directories);
        for (File directory : directories) {
            if (new File(directory, "pattern.owl").isFile()) {
                rows.put(directory.getName(), audit.audit(directory));
            }
        }
        assertFalse(rows.isEmpty(), "no pattern found in " + patternsDirectory());
    }

    /**
     * No ERROR in ODK's report - but one: a missing licence where patterns/licences.tsv records
     * that the source's licence could not be found, which is a question for a person (see
     * {@link #aMissingLicenceIsAnOpenQuestion}). Once the licence is known and written there,
     * curation writes it into the patterns and this exception no longer applies to them.
     */
    @Test
    void everyPatternPassesTheQualityReport() throws Exception {
        Map<String, String> failing = new TreeMap<String, String>();
        for (PatternAudit.Row row : rows.values()) {
            Map<String, Integer> rules = new TreeMap<String, Integer>();
            for (QualityFinding error : row.errors()) {
                if (!("missing_ontology_license".equals(error.getRule()) && licenceIsAnOpenQuestion(row.id()))) {
                    rules.merge(error.getRule(), 1, Integer::sum);
                }
            }
            if (!rules.isEmpty()) {
                failing.put(row.id(), rules.toString());
            }
        }
        assertTrue(failing.isEmpty(), failing.size() + " of " + rows.size()
                + " patterns have an ERROR in ODK's report: " + failing);
    }

    /** No pattern lacks a licence silently: each such source is recorded as an open question, with its evidence. */
    @Test
    void aMissingLicenceIsAnOpenQuestion() throws Exception {
        Map<String, String> silent = new TreeMap<String, String>();
        int open = 0;
        for (PatternAudit.Row row : rows.values()) {
            for (QualityFinding error : row.errors()) {
                if ("missing_ontology_license".equals(error.getRule())) {
                    String[] source = sourceOf(row.id());
                    if (source == null || !source[1].isEmpty() || source[2].trim().isEmpty()) {
                        silent.put(row.id(), source == null ? "no row in licences.tsv" : source[0]);
                    } else {
                        open++;
                    }
                }
            }
        }
        assertTrue(silent.isEmpty(), "patterns without a licence that licences.tsv does not record as an"
                + " open question: " + silent);
        System.out.println(open + " patterns wait for the licence of their source (patterns/licences.tsv)");
    }

    private static boolean licenceIsAnOpenQuestion(String id) throws Exception {
        String[] source = sourceOf(id);
        return source != null && source[1].isEmpty() && !source[2].trim().isEmpty();
    }

    /** The licences.tsv row of a pattern's source: prefix, licence ("" when not found), evidence. */
    private static String[] sourceOf(String id) throws Exception {
        JsonNode metadata = new ObjectMapper().readTree(new File(new File(patternsDirectory(), id), "metadata.json"));
        return PatternCuration.Licences.read(new File(patternsDirectory(), "licences.tsv"))
                .of(metadata.path("source").asText(null), metadata.path("pattern_iri").asText(null));
    }

    @Test
    void everyEntityHasALabel() {
        Map<String, Integer> failing = new TreeMap<String, Integer>();
        int entities = 0;
        for (PatternAudit.Row row : rows.values()) {
            if (!row.unlabelled().isEmpty()) {
                failing.put(row.id(), row.unlabelled().size());
                entities += row.unlabelled().size();
            }
        }
        assertTrue(failing.isEmpty(), failing.size() + " of " + rows.size() + " patterns name "
                + entities + " entities without rdfs:label (pattern=count): " + failing);
    }

    @Test
    void everyPatternIsConsistentAndCoherent() {
        Map<String, String> failing = new TreeMap<String, String>();
        for (PatternAudit.Row row : rows.values()) {
            if (!"coherent".equals(row.reasoner())) {
                failing.put(row.id(), row.reasoner() + " " + row.unsatisfiable());
            }
        }
        assertTrue(failing.isEmpty(), failing.size() + " of " + rows.size()
                + " patterns are not consistent and coherent to HermiT: " + failing);
    }

    @Test
    void owlAndTtlSayTheSame() {
        StringBuilder failing = new StringBuilder();
        for (PatternAudit.Row row : rows.values()) {
            if (row.owlTtlDiffer()) {
                failing.append(failing.length() == 0 ? "" : ", ").append(row.id());
            }
        }
        assertTrue(failing.length() == 0,
                "pattern.ttl's logical axioms differ from pattern.owl's: " + failing);
    }

    @Test
    void everyCurationIsRecorded() throws Exception {
        Map<String, String[]> shipped = shippedIn1154();
        Map<String, String> failing = new TreeMap<String, String>();
        for (PatternAudit.Row row : rows.values()) {
            String[] before = shipped.get(row.id());
            File directory = new File(patternsDirectory(), row.id());
            if (before == null || before[0].equals(fingerprint(new File(directory, "pattern.owl")))
                    && before[1].equals(fingerprint(new File(directory, "pattern.ttl")))) {
                continue;
            }
            JsonNode curated = new ObjectMapper().readTree(new File(directory, "metadata.json"))
                    .path("curated");
            boolean listed = curated.isArray() && curated.size() > 0;
            boolean commented = false;
            for (String comment : row.headerComments()) {
                commented |= comment.startsWith(CURATED);
            }
            if (!listed || !commented) {
                failing.put(row.id(), (listed ? "" : "no \"curated\" in metadata.json ")
                        + (commented ? "" : "no rdfs:comment \"" + CURATED + "...\""));
            }
        }
        assertTrue(failing.isEmpty(), failing.size() + " patterns changed since 1.154.0 without "
                + "saying so: " + failing);
    }

    /** id -> {pattern.owl, pattern.ttl} SHA-256 as 1.154.0 shipped them. */
    private static Map<String, String[]> shippedIn1154() throws Exception {
        Map<String, String[]> shipped = new HashMap<String, String[]>();
        for (String line : Files.readAllLines(new File(
                "src/test/resources/pattern/pattern-files-1.154.0.tsv").toPath(),
                StandardCharsets.UTF_8)) {
            String[] cells = line.split("\t");
            if (cells.length == 3 && !line.startsWith("#") && !"id".equals(cells[0])) {
                shipped.put(cells[0], new String[] {cells[1], cells[2]});
            }
        }
        assertFalse(shipped.isEmpty(), "no 1.154.0 fingerprints read");
        return shipped;
    }

    /** SHA-256 of a file with its line endings as LF, as the fingerprints were made. */
    private static String fingerprint(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        java.io.ByteArrayOutputStream lf = new java.io.ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') {
                continue;
            }
            lf.write(bytes[i]);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(lf.toByteArray())) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
