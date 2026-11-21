package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.axiom.EntityFactory;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Readable names, a project's choice (1.155.0): "some users prefer to have spokable IRIs, not
 * with letters and numbers ... it's annoying to change the IRIs always". A project whose ODK
 * configuration says {@code ontoboard_term_names: readable} names a new term from what is typed,
 * under the project's own address: a class BoiledEgg, a property or an individual hasPart, the
 * typed name kept as its label.
 */
class TermMinterReadableTest {

    private static final String NS = "https://example.org/eggboil/";

    @TempDir
    File root;

    private OWLOntologyManager manager;
    private OWLOntology ontology;

    @BeforeEach
    void anOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("https://example.org/eggboil.owl"));
    }

    /** ODK's seeded ranges beside the edit file, and a configuration with {@code extra}. */
    private File project(String extra) throws Exception {
        File folder = new File(root, "src/ontology");
        assertTrue(folder.mkdirs());
        Files.write(new File(folder, "eggboil-idranges.owl").toPath(),
                Goldens.seedTree("eggboil").get("src/ontology/eggboil-idranges.owl"));
        Files.write(new File(folder, "eggboil-odk.yaml").toPath(),
                ("id: eggboil\nuribase: https://example.org\n" + extra)
                        .getBytes(StandardCharsets.UTF_8));
        return new File(folder, "eggboil-edit.owl");
    }

    private TermMinter readable() throws Exception {
        return TermMinter.forOntologyFile(project("ontoboard_term_names: readable\n"), "Ada");
    }

    @Test
    void aClassIsNamedFromWhatIsTyped() throws Exception {
        TermMinter minter = readable();

        assertEquals(TermMinter.Style.READABLE, minter.getStyle());
        assertFalse(minter.needsClaim(), "readable names take no range");
        assertEquals(NS + "BoiledEgg",
                minter.mintFor(ontology, "boiled egg", EntityFactory.Kind.CLASS).toString());
    }

    @Test
    void aPropertyAndAnIndividualBeginInLowerCase() throws Exception {
        TermMinter minter = readable();

        assertEquals(NS + "hasPart", minter.mintFor(ontology, "has part",
                EntityFactory.Kind.OBJECT_PROPERTY).toString());
        assertEquals(NS + "myEgg", minter.mintFor(ontology, "my egg",
                EntityFactory.Kind.INDIVIDUAL).toString());
        assertEquals(NS + "boiledEgg", minter.mintFor(ontology, "BoiledEgg",
                EntityFactory.Kind.OBJECT_PROPERTY).toString());
        assertEquals(NS + "HasPart", minter.mintFor(ontology, "hasPart",
                EntityFactory.Kind.CLASS).toString());
    }

    /** ODK's report wants a label on every term, and the typed name is it - also "BoiledEgg". */
    @Test
    void theTypedNameIsAlwaysTheLabel() throws Exception {
        TermMinter minter = readable();
        IRI iri = minter.mintFor(ontology, "BoiledEgg", EntityFactory.Kind.CLASS);

        List<OWLOntologyChange> changes =
                minter.declare(ontology, iri, EntityFactory.Kind.CLASS, "BoiledEgg");

        OWLDataFactory factory = manager.getOWLDataFactory();
        assertTrue(changes.contains(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), iri, factory.getOWLLiteral("BoiledEgg", "en")))),
                String.valueOf(changes));
    }

    @Test
    void aNameTakenIsRefusedInWords() throws Exception {
        TermMinter minter = readable();
        OWLDataFactory factory = manager.getOWLDataFactory();
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(NS + "BoiledEgg"))));

        IllegalArgumentException taken = assertThrows(IllegalArgumentException.class,
                () -> minter.mintFor(ontology, "boiled egg", EntityFactory.Kind.CLASS));
        assertTrue(taken.getMessage().contains(NS + "BoiledEgg"), taken.getMessage());
    }

    @Test
    void theNamespaceIsTheProjectsAddress() throws Exception {
        TermMinter minter = TermMinter.forOntologyFile(project(
                "uribase_suffix: eggs\nontoboard_term_names: readable\n"), "Ada");

        assertEquals("https://example.org/eggs/BoiledEgg",
                minter.mintFor(ontology, "boiled egg", EntityFactory.Kind.CLASS).toString());
        assertTrue(minter.describe().contains("https://example.org/eggs/"), minter.describe());
    }

    @Test
    void aProjectThatDoesNotSayNumbersAsBefore() throws Exception {
        assertEquals(TermMinter.Style.NUMERIC,
                TermMinter.forOntologyFile(project(""), "Ada").getStyle());
    }

    @Test
    void numberedIsNumbered() throws Exception {
        assertEquals(TermMinter.Style.NUMERIC, TermMinter.forOntologyFile(
                project("ontoboard_term_names: numbered\n"), "Ada").getStyle());
    }

    /**
     * A project with readable names is an ODK project all the same: Record who edits terms, set
     * to follow the ontology, stamps its new terms as it does a numbered project's (the 1.155.0
     * review's M8 - the board asked whether the names were numbered).
     */
    @Test
    void aReadableProjectIsAnOdkProject() throws Exception {
        assertTrue(readable().isOdkProject());
        assertFalse(TermMinter.forOntologyFile(new File(root, "plain.owl"), "Ada")
                .isOdkProject(), "a file of its own, outside any ODK project");
    }
}
