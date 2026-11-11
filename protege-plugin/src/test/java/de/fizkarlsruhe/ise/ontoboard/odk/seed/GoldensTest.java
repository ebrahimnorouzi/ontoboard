package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The goldens every ODK test compares with are ODK's own output, and are all there.
 *
 * <p>They are made in the pinned ODK image by {@code tools/odk-goldens/generate.py} and committed,
 * so the tests need no Docker. This checks the fixture rather than the plugin: a golden folder
 * that lost a file to a {@code .gitignore}, or that was regenerated with another ODK, would make
 * every parity test compare with the wrong thing and still pass or fail for the wrong reason.
 */
class GoldensTest {

    @Test
    void theGoldensWereMadeByThePinnedImage() {
        String provenance = Goldens.provenance();

        assertTrue(provenance.contains("ODK_VERSION=" + OdkPin.VERSION), provenance);
        assertTrue(provenance.contains("ROBOT version 1.9.10"), provenance);
        assertTrue(provenance.contains("Jinja2 3.1.6"), provenance);
        assertEquals(8, Goldens.configNames().size(), Goldens.configNames().toString());
        assertEquals(79, Goldens.corpusNames().size());
    }

    /**
     * The seed of the config measured on 2026-11-11, when the decision to become ODK-identical
     * was taken: 45 files, and a Makefile that records the config's own fingerprint.
     */
    @Test
    void theEvidenceSeedIsTheOneMeasuredOn20261111() {
        Map<String, byte[]> seed = Goldens.seedTree("evidence-eggboil");

        assertEquals(45, seed.size(), seed.keySet().toString());
        String[] makefile = new String(seed.get("src/ontology/Makefile"), StandardCharsets.UTF_8)
                .split("\n", -1);
        assertEquals("# ODK Version: v1.6.1", makefile[3]);
        assertEquals("CONFIG_HASH=                "
                + "8a5a8b7d2e2f0c8698940dd146c9113a5d914b7cd9da41d128e55ef029ec30b6", makefile[12]);
        assertEquals(0, seed.get("src/ontology/imports/iao_terms.txt").length);
        assertEquals(0, seed.get("src/ontology/tmp/.gitkeep").length);
        assertTrue(seed.containsKey(".gitignore"), "a golden's own .gitignore must not hide it");
        assertTrue(seed.containsKey(".github/workflows/qc.yml"));
    }

    /** ODK copies the config into the project as it is; every golden shows that. */
    @Test
    void everySeedHoldsItsOwnConfigUnchanged() {
        for (String name : Goldens.configNames()) {
            Map<String, byte[]> seed = Goldens.seedTree(name);
            String id = String.valueOf(Goldens.model(name).get("id"));

            assertArrayEquals(Goldens.configBytes(name),
                    seed.get("src/ontology/" + id + "-odk.yaml"), name);
        }
    }

    /** What New ODK project will write: ODK's four checks and OntoBoard's, and its own profile. */
    @Test
    void theNewProjectsSeedHasTheProfileAndTheLabelCheckDeclared() {
        Map<String, byte[]> seed = Goldens.seedTree("eggboil");
        String makefile = new String(seed.get("src/ontology/Makefile"), StandardCharsets.UTF_8);

        assertTrue(seed.containsKey("src/ontology/profile.txt"),
                "custom_profile: TRUE makes ODK's seed write the profile");
        assertTrue(makefile.contains("SPARQL_VALIDATION_CHECKS =  owldef-self-reference iri-range "
                + "label-with-iri multiple-replaced_by check_labels \n"), "the declared checks");
        assertTrue(makefile.contains("REPORT_PROFILE_OPTS =       --profile $(ROBOT_PROFILE)\n"));
        assertFalse(seed.containsKey("mkdocs.yaml"),
                "no documentation block: OntoBoard's own mkdocs.yaml must not meet one of ODK's");
        String qc = new String(seed.get(".github/workflows/qc.yml"), StandardCharsets.UTF_8);
        assertTrue(qc.contains("container: obolibrary/odkfull:v1.6.1"), qc);
    }

    @Test
    void everyCorpusConfigHasAManifestAndAModel() {
        for (String name : Goldens.corpusNames()) {
            assertFalse(Goldens.corpusConfig(name).isEmpty(), name);
            assertFalse(Goldens.manifest(name).isEmpty(), name);
            assertTrue(Goldens.corpusModel(name).containsKey("id"), name);
        }
    }

    /** Jinja2's own cut of each of the fourteen templates, for the lexer to be held to. */
    @Test
    void theLexerFixtureCoversTheFourteenTemplates() {
        int templates = 0;
        for (TemplateSet.Entry entry : TemplateSet.bundled().entries()) {
            if (!entry.isTemplate()) {
                continue;
            }
            templates++;
            List<Goldens.Chunk> chunks = Goldens.lexerChunks(entry.path());
            assertFalse(chunks.isEmpty(), entry.path());
        }
        assertEquals(14, templates);

        List<Goldens.Chunk> runBat = Goldens.lexerChunks("src/ontology/run.bat.jinja2");
        assertEquals("data", runBat.get(0).kind);
        assertEquals("docker run -v %cd%\\..\\..\\:/work -w /work/src/ontology ", runBat.get(0).text);
        assertEquals("block", runBat.get(1).kind);
        assertEquals("if project.robot_java_args is defined", runBat.get(1).text);
        assertEquals(1, runBat.get(1).line);
    }
}
