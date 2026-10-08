package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checking a project's statements about itself against each other.
 *
 * <p><b>The fixtures are the real files</b>, copied byte for byte from NFDIcore, MWO and ECTO -
 * CRLF endings and all. The claim this whole check rests on is that its findings fire on real
 * projects rather than on invented ones, so hand-written miniatures would prove nothing.
 *
 * <p>The finding that motivated it: all three of those projects declare CC-BY in
 * {@code src/metadata/<id>.md} while their ontologies annotate CC0. Measured, not assumed, and
 * independently confirmed against the live OBO Foundry entry for ECTO.
 */
class ProjectMetadataTest {

    private static String fixture(String name) throws Exception {
        InputStream in = ProjectMetadataTest.class.getResourceAsStream("/metadata/" + name);
        assertNotNull(in, "fixture missing: " + name);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static List<ProjectMetadata.Finding> of(ProjectMetadata.Field field,
            List<ProjectMetadata.Finding> all) {
        List<ProjectMetadata.Finding> some = new ArrayList<ProjectMetadata.Finding>();
        for (ProjectMetadata.Finding one : all) {
            if (one.getField() == field) {
                some.add(one);
            }
        }
        return some;
    }

    // ---------------------------------------------------------------- front matter

    /** The real .md files have CRLF front matter, and it is read without touching the bytes. */
    @Test
    void theFrontMatterOfARealRegistryEntryIsRead() throws Exception {
        for (String name : new String[] {"nfdicore.md", "mwo.md", "ecto.md"}) {
            String text = fixture(name);
            assertTrue(text.contains("\r\n"), name + " should still have CRLF");
            assertTrue(FrontMatter.has(text), name);

            String yaml = FrontMatter.yamlOf(text);
            assertNotNull(yaml, name);
            assertTrue(yaml.contains("layout: ontology_detail"), name + " header: " + yaml);
            assertFalse(yaml.contains("---"), name + " header must not contain a fence");
            assertFalse(FrontMatter.bodyOf(text).contains("layout:"),
                    name + " body must not contain the header");
        }
    }

    /** A file with no front matter is returned whole rather than mangled. */
    @Test
    void aPlainMarkdownFileHasNoHeader() {
        String plain = "# A heading\n\nSome prose, and a rule:\n\n---\n\nmore prose.\n";

        assertFalse(FrontMatter.has(plain));
        assertNull(FrontMatter.yamlOf(plain));
        assertEquals(plain, FrontMatter.bodyOf(plain));
    }

    /**
     * A horizontal rule inside the body does not become the closing fence.
     *
     * <p>Markdown writes a rule as {@code ---} too, so taking the last fence, or any fence, would
     * swallow half the description into the header.
     */
    @Test
    void aRuleInTheBodyIsNotTheClosingFence() {
        String text = "---\ntitle: X\n---\nprose\n\n---\n\nmore prose\n";

        assertEquals("title: X\n", FrontMatter.yamlOf(text));
        assertTrue(FrontMatter.bodyOf(text).startsWith("prose"));
        assertTrue(FrontMatter.bodyOf(text).contains("more prose"));
    }

    /** A fence has to be alone on its line. */
    @Test
    void anUnderlinedHeadingIsNotAFence() {
        assertFalse(FrontMatter.has("A heading\n---------\ntext\n"));
        assertFalse(FrontMatter.has("--- not a fence\ntitle: x\n---\n"));
    }

    /** An opening fence with no closing one is not front matter. */
    @Test
    void anUnclosedHeaderIsNotRead() {
        assertNull(FrontMatter.yamlOf("---\ntitle: X\nand it never closes\n"));
    }

    // ------------------------------------------------- the finding that motivated this

    /**
     * The licence contradiction, on all three real projects.
     *
     * <p>Each {@code src/metadata/<id>.md} declares CC-BY 3.0; each ontology annotates CC0 1.0.
     */
    @Test
    void everyRealRegistryEntryContradictsItsOwnOntologysLicence() throws Exception {
        for (String id : new String[] {"nfdicore", "mwo", "ecto"}) {
            ProjectMetadata.Sources sources = new ProjectMetadata.Sources()
                    .withId(id)
                    // An OBO IRI, so the registry entry is live and its contents are worth
                    // reporting. The same three files under their real IRIs are reported as
                    // scaffold instead - see anInertRegistryIsReportedOnceNotItemised.
                    .fromOntology("Some Ontology",
                            "http://creativecommons.org/publicdomain/zero/1.0/", "",
                            "http://purl.obolibrary.org/obo/" + id + ".owl")
                    .fromRegistryMd(fixture(id + ".md"));

            List<ProjectMetadata.Finding> licences =
                    of(ProjectMetadata.Field.LICENCE, ProjectMetadata.findingsIn(sources));

            assertFalse(licences.isEmpty(), id + " should report a licence disagreement");
            ProjectMetadata.Finding one = licences.get(0);
            assertTrue(one.getAgainst().toLowerCase().contains("by"),
                    id + " declares: " + one.getAgainst());
            assertTrue(one.getSaid().contains("zero"), id + " annotates: " + one.getSaid());
            assertTrue(one.getAgainstWhere().contains(id), one.getAgainstWhere());
        }
    }

    /** And an agreeing pair reports nothing, whichever form each is written in. */
    @Test
    void aLicenceThatAgreesIsNotAFinding() {
        assertTrue(ProjectMetadata.sameLicence(
                "http://creativecommons.org/publicdomain/zero/1.0/", "CC0"));
        assertTrue(ProjectMetadata.sameLicence(
                "https://creativecommons.org/licenses/by/4.0/", "CC-BY"));
        assertFalse(ProjectMetadata.sameLicence(
                "http://creativecommons.org/publicdomain/zero/1.0/",
                "http://creativecommons.org/licenses/by/3.0/"));
    }

    // ---------------------------------------------------------------- the other checks

    /**
     * A registry entry configuring a PURL namespace the ontology does not use.
     *
     * <p>This is the finding that closed the roadmap item: NFDIcore, MWO and PMDCO declare
     * {@code base_url: /obo/<id>} while their ontologies live under their own domains, and all
     * three answer 404 in the Foundry registry and the PURL config.
     */
    @Test
    void aPurlNamespaceTheOntologyDoesNotOwnIsReported() throws Exception {
        ProjectMetadata.Sources sources = new ProjectMetadata.Sources()
                .withId("nfdicore")
                .fromOntology("NFDI Core", null, null, "https://nfdi.fiz-karlsruhe.de/ontology")
                .fromRegistryYml(fixture("nfdicore.yml"));

        List<ProjectMetadata.Finding> namespace =
                of(ProjectMetadata.Field.NAMESPACE, ProjectMetadata.findingsIn(sources));

        assertEquals(1, namespace.size(), "nfdicore.yml declares an OBO base_url");
        assertTrue(namespace.get(0).explain().contains("issue form"),
                "it must say the Foundry intake has moved: " + namespace.get(0).explain());
    }

    /** And an ontology genuinely in the OBO namespace is not reported. */
    @Test
    void anOboOntologyKeepsItsOboBaseUrl() throws Exception {
        ProjectMetadata.Sources sources = new ProjectMetadata.Sources()
                .withId("ecto")
                .fromOntology("ECTO", null, null, "http://purl.obolibrary.org/obo/ecto.owl")
                .fromRegistryYml(fixture("ecto.yml"));

        assertTrue(of(ProjectMetadata.Field.NAMESPACE,
                ProjectMetadata.findingsIn(sources)).isEmpty());
    }

    /** A title the configuration and the ontology disagree on. */
    @Test
    void aTitleThatDriftedIsReported() {
        ProjectMetadata.Sources sources = new ProjectMetadata.Sources()
                .withId("x")
                .fromOntology("Materials Workflow Ontology", null, null, "")
                .fromOdkYaml("id: mwo\ntitle: MWO\n");

        List<ProjectMetadata.Finding> titles =
                of(ProjectMetadata.Field.TITLE, ProjectMetadata.findingsIn(sources));

        assertEquals(1, titles.size());
        assertEquals("Materials Workflow Ontology", titles.get(0).getSaid());
        assertEquals("MWO", titles.get(0).getAgainst());
    }

    /** The same title said twice is not a finding, whatever its case. */
    @Test
    void anAgreeingTitleIsSilent() {
        ProjectMetadata.Sources sources = new ProjectMetadata.Sources()
                .fromOntology("NFDI Core Ontology", null, null, "")
                .fromOdkYaml("title: nfdi core ontology\n");

        assertTrue(of(ProjectMetadata.Field.TITLE,
                ProjectMetadata.findingsIn(sources)).isEmpty());
    }

    /** A licence the ontology states and the configuration omits. */
    @Test
    void aLicenceMissingFromTheConfigurationIsReported() {
        ProjectMetadata.Sources sources = new ProjectMetadata.Sources()
                .fromOntology("X", "http://creativecommons.org/publicdomain/zero/1.0/", null, "")
                .fromOdkYaml("id: x\ntitle: X\n");

        List<ProjectMetadata.Finding> licences =
                of(ProjectMetadata.Field.LICENCE, ProjectMetadata.findingsIn(sources));

        assertEquals(1, licences.size());
        assertTrue(licences.get(0).getAgainstWhere().contains("ODK YAML"));
    }

    /** Scaffold nobody filled in, found in the real files. */
    @Test
    void emptyScaffoldInARealEntryIsReported() throws Exception {
        ProjectMetadata.Sources sources = new ProjectMetadata.Sources()
                .withId("nfdicore")
                .fromOntology(null, null, null, "http://purl.obolibrary.org/obo/nfdicore.owl")
                .fromRegistryMd(fixture("nfdicore.md"));

        List<ProjectMetadata.Finding> placeholders =
                of(ProjectMetadata.Field.PLACEHOLDER, ProjectMetadata.findingsIn(sources));

        assertFalse(placeholders.isEmpty(),
                "nfdicore.md leaves contact.email and contact.label empty");
    }

    // ---------------------------------------------------------------- nothing to say

    /** A project that agrees with itself produces no findings at all. */
    @Test
    void aConsistentProjectIsSilent() {
        ProjectMetadata.Sources sources = new ProjectMetadata.Sources()
                .withId("good")
                .fromOntology("Good Ontology", "CC0", "A description.",
                        "https://example.org/good")
                .fromOdkYaml("id: good\ntitle: Good Ontology\nlicense: CC0\n");

        assertTrue(ProjectMetadata.findingsIn(sources).isEmpty(),
                ProjectMetadata.findingsIn(sources).toString());
    }

    /** No sources at all is no findings, not an exception. */
    @Test
    void nothingToReadIsNotAFailure() {
        assertTrue(ProjectMetadata.findingsIn(null).isEmpty());
        assertTrue(ProjectMetadata.findingsIn(new ProjectMetadata.Sources()).isEmpty());
        assertTrue(ProjectMetadata.findingsIn(
                new ProjectMetadata.Sources().fromOdkYaml("this: is: not: yaml\n")).isEmpty());
    }

    /** Every finding explains itself; a report of bare values would be no use. */
    @Test
    void everyFindingSaysWhatItMeans() throws Exception {
        ProjectMetadata.Sources sources = new ProjectMetadata.Sources()
                .withId("nfdicore")
                .fromOntology("NFDI Core", "http://creativecommons.org/publicdomain/zero/1.0/",
                        null, "https://nfdi.fiz-karlsruhe.de/ontology")
                .fromOdkYaml("id: nfdicore\ntitle: NFDIcore\n")
                .fromRegistryYml(fixture("nfdicore.yml"))
                .fromRegistryMd(fixture("nfdicore.md"));

        List<ProjectMetadata.Finding> all = ProjectMetadata.findingsIn(sources);
        assertFalse(all.isEmpty(), "the real nfdicore files should disagree in several ways");
        for (ProjectMetadata.Finding one : all) {
            assertFalse(one.explain().trim().isEmpty(), one.toString());
            assertTrue(one.explain().length() > 60,
                    "an explanation has to be worth reading: " + one.explain());
        }
    }

    /**
     * An inert registry entry is reported once, not itemised.
     *
     * <p>The adversarial review of the plan for this caught the design flaw: four of the seven
     * checks read {@code src/metadata}, which the same check reports as a file nothing reads.
     * Saying "nothing reads this" and then listing six things wrong inside it invites somebody
     * to go and fix them - which is exactly the work this exists to tell them not to do.
     *
     * <p>NFDIcore is the real case: its ontology IRI is under its own domain, its registry entry
     * declares an {@code /obo/} base URL, and the Foundry answers 404 for it.
     */
    @Test
    void anInertRegistryIsReportedOnceNotItemised() throws Exception {
        ProjectMetadata.Sources sources = new ProjectMetadata.Sources()
                .withId("nfdicore")
                .fromOntology("NFDI Core",
                        "http://creativecommons.org/publicdomain/zero/1.0/", null,
                        "https://nfdi.fiz-karlsruhe.de/ontology")
                .fromRegistryYml(fixture("nfdicore.yml"))
                .fromRegistryMd(fixture("nfdicore.md"));

        List<ProjectMetadata.Finding> all = ProjectMetadata.findingsIn(sources);

        assertEquals(1, of(ProjectMetadata.Field.NAMESPACE, all).size(),
                "the one finding worth making about a dead file is that it is dead");
        assertTrue(of(ProjectMetadata.Field.PLACEHOLDER, all).isEmpty(),
                "its contents must not be itemised: " + all);
        assertTrue(of(ProjectMetadata.Field.EXAMPLE_TERM, all).isEmpty(), all.toString());
        for (ProjectMetadata.Finding one : of(ProjectMetadata.Field.LICENCE, all)) {
            assertFalse(one.getAgainstWhere().contains(".md"),
                    "a licence inside the dead file is not worth reporting: " + one);
        }
    }

    /** The checks on files the build DOES read always run, inert registry or not. */
    @Test
    void theChecksOnLiveFilesAlwaysRun() {
        ProjectMetadata.Sources sources = new ProjectMetadata.Sources()
                .withId("mwo")
                .fromOntology("Materials Workflow Ontology",
                        "http://creativecommons.org/publicdomain/zero/1.0/", null,
                        "http://purls.helmholtz-metadaten.de/mwo/mwo.owl")
                .fromOdkYaml(String.join("\n", "id: mwo", "title: MWO", ""));

        List<ProjectMetadata.Finding> all = ProjectMetadata.findingsIn(sources);

        assertEquals(1, of(ProjectMetadata.Field.TITLE, all).size(), all.toString());
        assertEquals(1, of(ProjectMetadata.Field.LICENCE, all).size(),
                "the ODK YAML declares no licence and the ontology does: " + all);
    }
}
