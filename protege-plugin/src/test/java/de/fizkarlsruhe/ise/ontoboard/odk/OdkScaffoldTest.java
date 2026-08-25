package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
