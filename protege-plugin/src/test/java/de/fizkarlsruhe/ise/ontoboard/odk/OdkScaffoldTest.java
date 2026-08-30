package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

class OdkScaffoldTest {

    private static OdkProjectConfig config(File into) {
        return new OdkProjectConfig("mwo", "Materials Workflow Ontology",
                "Workflows for materials science", "http://purl.obolibrary.org/obo/mwo.owl",
                "https://creativecommons.org/licenses/by/4.0/", into);
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
    }

    @Test
    void generatesTheOdkWorkspaceLayout(@TempDir Path dir) {
        OdkProjectConfig config = config(dir.toFile());
        OdkScaffold.create(config);

        File root = config.getProjectRoot();
        File ontology = new File(new File(root, "src"), "ontology");
        for (String expected : new String[] {
            "mwo-edit.owl", "mwo.owl", "mwo-odk.yaml", "Makefile", "mwo.Makefile",
            "mwo-idranges.owl", "catalog-v001.xml", "profile.txt"}) {
            assertTrue(new File(ontology, expected).isFile(), "missing " + expected);
        }
        assertTrue(new File(ontology, "imports").isDirectory());
        assertTrue(new File(new File(root, "src"), "sparql").isDirectory());
        assertTrue(new File(root, "README.md").isFile());
        assertTrue(new File(root, ".gitignore").isFile());
        assertTrue(new File(new File(new File(root, ".github"), "workflows"), "qc.yml").isFile());
    }

    /**
     * The whole point is that Protege can open the result immediately. If the generated OWL
     * does not parse, the user's first action after the wizard fails.
     */
    @Test
    void theGeneratedEditFileIsAnOntologyProtegeCanOpen(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = config(dir.toFile());
        OdkScaffold.create(config);

        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(config.getEditFile());

        assertEquals("http://purl.obolibrary.org/obo/mwo.owl",
                ontology.getOntologyID().getOntologyIRI().get().toString());
    }

    @Test
    void theUsersBaseIriIsUsedRatherThanAPlaceholder(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("abc", "ABC", "",
                "https://example.institute/abc", "", dir.toFile());
        OdkScaffold.create(config);

        String owl = read(config.getEditFile());
        assertTrue(owl.contains("https://example.institute/abc"), owl.substring(0, 400));
        assertTrue(!owl.contains("example.org"), "a placeholder IRI leaked into the output");
    }

    @Test
    void anOmittedIriFallsBackToTheOboConvention(@TempDir Path dir) {
        OdkProjectConfig config =
                new OdkProjectConfig("abc", "ABC", "", "", "", dir.toFile());
        assertEquals("http://purl.obolibrary.org/obo/abc.owl", config.getBaseIri());
    }

    @Test
    void trailingDelimitersInTheIriAreNormalisedAway(@TempDir Path dir) {
        assertEquals("http://x.org/abc", new OdkProjectConfig(
                "abc", "ABC", "", "http://x.org/abc#", "", dir.toFile()).getBaseIri());
        assertEquals("http://x.org/abc", new OdkProjectConfig(
                "abc", "ABC", "", "http://x.org/abc/", "", dir.toFile()).getBaseIri());
    }

    /** The id becomes file names, Makefile variables and IRIs; a bad one breaks the build. */
    @Test
    void invalidOntologyIdsAreRejectedWithAnActionableMessage(@TempDir Path dir) {
        for (String bad : new String[] {"", "  ", "MWO", "my ont", "9lives", "my-ont"}) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new OdkProjectConfig(bad, "T", "", "", "", dir.toFile()).validate(),
                    "should have rejected id '" + bad + "'");
            assertTrue(thrown.getMessage().length() > 20,
                    "message must tell the user what to do: " + thrown.getMessage());
        }
    }

    /**
     * The most damaging value this dialog can accept, and the one that looks most harmless.
     *
     * <p>Found in a real generated project: the user typed "o" for the base IRI, and Protege
     * resolved it against the file location on save. The ontology became
     * {@code file:/C:/Users/.../src/ontology/o} and every term
     * {@code file:/C:/Users/.../o#apple} - the author's own folder path baked into the identity
     * of every concept. Nothing failed. It would simply never have meant the same thing to
     * anyone else, and two people collaborating would mint different IRIs for one concept and
     * never converge.
     */
    @Test
    void aRelativeBaseIriIsRejectedBecauseItWouldBakeInALocalFilePath(@TempDir Path dir) {
        for (String relative : new String[] {"o", "mmo", "obo/mmo.owl", "/absolute/path",
            "./here", "ftp://x.org/a", "www.example.org/mmo"}) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new OdkProjectConfig("abc", "ABC", "", relative, "", dir.toFile())
                            .validate(),
                    "should have rejected base IRI '" + relative + "'");
            assertTrue(thrown.getMessage().contains("absolute http"),
                    thrown.getMessage());
            assertTrue(thrown.getMessage().contains(relative),
                    "the message must quote what was entered: " + thrown.getMessage());
        }
    }

    @Test
    void anAbsoluteBaseIriIsAccepted(@TempDir Path dir) {
        new OdkProjectConfig("abc", "ABC", "", "http://purl.obolibrary.org/obo/abc.owl", "",
                dir.toFile()).validate();
        new OdkProjectConfig("abc", "ABC", "", "https://w3id.org/abc", "", dir.toFile())
                .validate();
    }

    /** The fallback has to survive its own validation, or an omitted IRI would be unusable. */
    @Test
    void theFallbackIriPassesValidation(@TempDir Path dir) {
        new OdkProjectConfig("abc", "ABC", "", "", "", dir.toFile()).validate();
    }

    /**
     * The generated ID ranges file must be one ODK recognises and one the plugin can mint from.
     *
     * <p>The previous version wrote an invented has_id_policy property and no ranges, so a fresh
     * project could not allocate an identifier to anybody - which makes two collaborators minting
     * the same identifier a certainty rather than a risk.
     */
    @Test
    void theGeneratedIdRangesFileCanActuallyMint(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("mwo", "MWO", "",
                "http://purl.obolibrary.org/obo/mwo.owl", "", dir.toFile());
        OdkScaffold.create(config);

        String written = new String(java.nio.file.Files.readAllBytes(
                new java.io.File(config.getProjectRoot(),
                        "src/ontology/mwo-idranges.owl").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(written.contains("has_id_policy"),
                "has_id_policy is not an OBO property and ODK does not read it");
        IdRanges ranges = IdRanges.parse(written);
        assertEquals("MWO", ranges.getPolicyName());
        assertEquals(7, ranges.getIdDigits());
        assertEquals(1, ranges.getRanges().size(),
                "a project with no ranges cannot mint at all");

        String owner = ranges.getRanges().get(0).getAllocatedTo();
        String minted = ranges.mint(owner, java.util.Collections.<String>emptySet());
        assertTrue(minted.startsWith("http://purl.obolibrary.org/obo/mwo.owl#MWO_")
                        || minted.contains("MWO_"),
                "minted IRI should carry the policy prefix: " + minted);
        assertTrue(minted.endsWith("0001000"), "first identifier in the range, padded: " + minted);
    }

    /**
     * The generated project must not tell a user to run a file it does not write.
     *
     * <p>The YAML header used to say "sh run.sh make update_repo" - ODK's own instruction, naming
     * a 150-line Docker wrapper this scaffold deliberately does not produce, because running the
     * pipeline without Docker is the reason the plugin embeds robot-core at all.
     */
    @Test
    void theGeneratedProjectDoesNotNameFilesItNeverWrites(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("mwo", "MWO", "",
                "http://purl.obolibrary.org/obo/mwo.owl", "", dir.toFile());
        List<File> written = OdkScaffold.create(config);

        java.util.Set<String> names = new java.util.HashSet<String>();
        for (File file : written) {
            names.add(file.getName());
        }
        String yaml = read(config, "mwo-odk.yaml");
        if (yaml.contains("run.sh")) {
            assertTrue(names.contains("run.sh"),
                    "the YAML names run.sh but the scaffold never writes it");
        }
        assertTrue(yaml.contains("robot on") || yaml.contains("robot directly"),
                "it should say what is actually needed: " + yaml);
    }

    /**
     * A release with no owl:versionIRI cannot be cited or pinned by anyone downstream, and an
     * undated copy means each release destroys the last - which a single cp did.
     */
    @Test
    void prepareReleaseStampsAVersionAndKeepsDatedCopies(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("mwo", "MWO", "",
                "http://purl.obolibrary.org/obo/mwo.owl", "", dir.toFile());
        OdkScaffold.create(config);

        String makefile = read(config, "Makefile");
        assertTrue(makefile.contains("--version-iri"), makefile);
        assertTrue(makefile.contains("owl:versionInfo"), makefile);
        assertTrue(makefile.contains("releases/$(TODAY)"),
                "an undated release directory means each release overwrites the last");
        assertTrue(makefile.contains("TODAY :="), "TODAY has to be defined to be used");
    }

    private static String read(OdkProjectConfig config, String name) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(
                new File(new File(new File(config.getProjectRoot(), "src"), "ontology"),
                        name).toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void aMissingTitleIsRejected(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> new OdkProjectConfig("abc", "", "", "", "", dir.toFile()).validate());
    }

    /** Writing into an existing folder could clobber someone's work. */
    @Test
    void refusesToWriteIntoAnExistingProjectFolder(@TempDir Path dir) {
        OdkProjectConfig config = config(dir.toFile());
        OdkScaffold.create(config);

        assertThrows(IllegalArgumentException.class, () -> OdkScaffold.create(config(dir.toFile())));
    }

    @Test
    void theMakefilePairSeparatesGeneratedFromCustom(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = config(dir.toFile());
        OdkScaffold.create(config);
        File ontology = new File(new File(config.getProjectRoot(), "src"), "ontology");

        String generated = read(new File(ontology, "Makefile"));
        assertTrue(generated.contains("Do NOT edit"), "generated Makefile must warn");
        assertTrue(generated.contains("-include $(ONT).Makefile"),
                "the generated Makefile must include the custom one, or custom targets are lost");
        assertTrue(read(new File(ontology, "mwo.Makefile")).contains("never overwritten"));
    }

    @Test
    void reportsEveryFileItWrote(@TempDir Path dir) {
        List<File> written = OdkScaffold.create(config(dir.toFile()));
        assertTrue(written.size() >= 12, "expected the full workspace, got " + written.size());
        for (File file : written) {
            assertTrue(file.isFile(), "reported but not written: " + file);
        }
    }
    /**
     * The generated project has to do what its own configuration says. The YAML declared
     * fail_on: ERROR and use_labels: TRUE and the Makefile passed neither, so CI did whatever
     * ROBOT defaults to - and a build that announces a policy it does not apply is worse than one
     * that announces nothing, because somebody relies on it.
     */
    @Test
    void theReportTargetPassesWhatTheYamlDeclares(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("abc", "ABC", "",
                "http://x.org/abc.owl", "", dir.toFile());
        OdkScaffold.create(config);

        String makefile = read(config, "Makefile");
        String yaml = read(config, "abc-odk.yaml");

        assertTrue(yaml.contains("fail_on: ERROR"), yaml);
        assertTrue(makefile.contains("--fail-on ERROR"),
                "the Makefile does not pass the fail_on the YAML declares:\n" + makefile);
        assertTrue(yaml.contains("use_labels: TRUE"), yaml);
        assertTrue(makefile.contains("--labels true"),
                "the Makefile does not pass the labels setting the YAML declares:\n" + makefile);
    }

    /**
     * It listed base, full, obo and json and built one .owl, so anybody following the generated
     * configuration went looking for four artefacts and found one, with no way to tell whether
     * the build was broken or the configuration decorative.
     */
    @Test
    void theYamlOnlyPromisesArtefactsTheMakefileBuilds(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("abc", "ABC", "",
                "http://x.org/abc.owl", "", dir.toFile());
        OdkScaffold.create(config);

        String yaml = read(config, "abc-odk.yaml");
        String makefile = read(config, "Makefile");

        for (String promised : new String[] {"  - obo", "  - json", "  - base"}) {
            assertFalse(yaml.contains(promised),
                    "the YAML promises an artefact nothing builds (" + promised.trim()
                            + "):\n" + yaml);
        }
        assertTrue(yaml.contains("  - owl"), yaml);
        assertTrue(makefile.contains("$(ONT).owl"), makefile);
    }

}
