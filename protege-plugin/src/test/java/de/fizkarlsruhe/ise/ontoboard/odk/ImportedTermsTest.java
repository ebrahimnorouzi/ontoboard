package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.util.SimpleIRIMapper;

/**
 * Which terms an ontology takes from where: a module cut from an upper ontology by its term list,
 * and a small ontology imported whole.
 */
class ImportedTermsTest {

    private static final String UPPER = "http://example.org/upper#";
    private static final IRI MODULE_IRI =
            IRI.create("http://example.org/pizza/imports/upper_import.owl");

    @TempDir
    File root;

    private OWLOntology upstream;
    private OWLOntology edit;
    private File moduleFile;
    private File termsFile;

    private static OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(UPPER + name));
    }

    @BeforeEach
    void project() throws Exception {
        // The upper ontology: entity > continuant > material entity > object, and process.
        OWLOntologyManager upper = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = upper.getOWLDataFactory();
        upstream = upper.createOntology(IRI.create("http://example.org/upper"));
        String[][] tree = {{"continuant", "entity"}, {"material_entity", "continuant"},
            {"object", "material_entity"}, {"process", "entity"}};
        for (String[] pair : tree) {
            upper.addAxiom(upstream, df.getOWLSubClassOfAxiom(cls(df, pair[0]),
                    cls(df, pair[1])));
        }
        for (String name : new String[] {"entity", "continuant", "material_entity", "object",
            "process"}) {
            upper.addAxiom(upstream, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    IRI.create(UPPER + name), df.getOWLLiteral(name.replace('_', ' '))));
        }

        // The project: the module cut for "object", its term list with a header and a comment.
        File imports = new File(root, "src/ontology/imports");
        assertTrue(imports.mkdirs());
        moduleFile = new File(imports, "upper_import.owl");
        termsFile = new File(imports, "upper_terms.txt");
        Files.write(termsFile.toPath(), ("# Terms extracted into upper_import.owl\n"
                + "# Source: http://example.org/upper\n"
                + UPPER + "object # object\n").getBytes(StandardCharsets.UTF_8));
        TermExtract.Result cut = TermExtract.run(upstream,
                Collections.singletonList(IRI.create(UPPER + "object")), TermExtract.Method.BOT,
                MODULE_IRI);
        cut.getModule().getOWLOntologyManager().saveOntology(cut.getModule(),
                IRI.create(moduleFile.toURI()));

        // A small ontology imported whole, from a file outside imports/.
        File unitsFile = new File(root, "units.ttl");
        Files.write(unitsFile.toPath(), ("@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "<http://example.org/units> a owl:Ontology ; rdfs:label \"units\" .\n"
                + "<http://example.org/units#Gram> a owl:Class ; rdfs:label \"gram\" .\n")
                .getBytes(StandardCharsets.UTF_8));

        // The edit file: imports both, and uses "object" once.
        OWLOntologyManager protege = OWLManager.createOWLOntologyManager();
        protege.getIRIMappers().add(new SimpleIRIMapper(MODULE_IRI, IRI.create(moduleFile)));
        protege.getIRIMappers().add(new SimpleIRIMapper(IRI.create("http://example.org/units"),
                IRI.create(unitsFile)));
        OWLDataFactory pdf = protege.getOWLDataFactory();
        edit = protege.createOntology(IRI.create("http://example.org/pizza"));
        protege.applyChange(new org.semanticweb.owlapi.model.AddImport(edit,
                pdf.getOWLImportsDeclaration(MODULE_IRI)));
        protege.applyChange(new org.semanticweb.owlapi.model.AddImport(edit,
                pdf.getOWLImportsDeclaration(IRI.create("http://example.org/units"))));
        protege.loadOntology(MODULE_IRI);
        protege.loadOntology(IRI.create("http://example.org/units"));
        protege.addAxiom(edit, pdf.getOWLSubClassOfAxiom(
                pdf.getOWLClass(IRI.create("http://example.org/pizza#Pizza")),
                pdf.getOWLClass(IRI.create(UPPER + "object"))));
    }

    private static ImportedTerms.Term term(ImportedTerms.Source source, String label) {
        for (ImportedTerms.Term term : source.getTerms()) {
            if (term.getLabel().equals(label)) {
                return term;
            }
        }
        throw new AssertionError("no " + label + " in " + source.getName());
    }

    @Test
    void eachSourceListsItsTermsWithLabelsAndHowTheyCameIn() {
        List<ImportedTerms.Source> sources = ImportedTerms.of(edit, root,
                new File(root, "src/ontology"));

        assertEquals(2, sources.size(), "the module, then what is imported whole");
        ImportedTerms.Source upper = sources.get(0);
        assertEquals("upper", upper.getName());
        assertEquals("http://example.org/upper", upper.getFrom());
        assertTrue(upper.isEditable());
        assertEquals(1, upper.countChosen());

        ImportedTerms.Term object = term(upper, "object");
        assertEquals(ImportedTerms.How.CHOSEN, object.getHow());
        assertEquals("class", object.getKind());
        assertEquals(1, object.getUsedByYou(), "Pizza is a subclass of it");
        assertTrue(object.isRemovable());

        ImportedTerms.Term continuant = term(upper, "continuant");
        assertEquals(ImportedTerms.How.BROUGHT_ALONG, continuant.getHow());
        assertEquals(Arrays.asList("object"), continuant.getNeededBy());
        assertFalse(continuant.isRemovable());
        assertFalse(upper.getTerms().toString().contains("process"), "not chosen, not needed");

        ImportedTerms.Source units = sources.get(1);
        assertEquals("units", units.getName());
        assertNull(units.getModule());
        assertFalse(units.isEditable());
        assertEquals(ImportedTerms.How.WHOLE, term(units, "gram").getHow());
    }

    @Test
    void aTermOnTheListButNotInTheModuleSaysSo() throws Exception {
        Files.write(termsFile.toPath(), (new String(Files.readAllBytes(termsFile.toPath()),
                StandardCharsets.UTF_8) + UPPER + "process # process\n")
                .getBytes(StandardCharsets.UTF_8));

        ImportedTerms.Source upper = ImportedTerms.of(edit, root,
                new File(root, "src/ontology")).get(0);

        assertEquals(ImportedTerms.How.NOT_IN_MODULE, term(upper, "process").getHow());
        assertEquals(2, upper.countChosen());
    }

    @Test
    void termsAreAddedAndRemovedWithoutTouchingTheOtherLines() throws Exception {
        String before = new String(Files.readAllBytes(termsFile.toPath()),
                StandardCharsets.UTF_8);
        Map<IRI, String> adding = new LinkedHashMap<IRI, String>();
        adding.put(IRI.create(UPPER + "process"), "process");
        adding.put(IRI.create(UPPER + "object"), "object");

        String added = ImportedTerms.withTerms(before, adding);
        String removed = ImportedTerms.withoutTerms(added,
                new HashSet<IRI>(Arrays.asList(IRI.create(UPPER + "object"))));

        assertEquals(before + UPPER + "process # process\n", added, "object was already there");
        assertTrue(removed.startsWith("# Terms extracted into upper_import.owl\n# Source: "),
                "the header kept");
        assertEquals(Arrays.asList(IRI.create(UPPER + "process")), TermFile.termsIn(removed));
    }

    /**
     * Rebuilding keeps the module's IRI and changes the copy that is open, so a term just added
     * can be used at once.
     */
    @Test
    void aRebuildKeepsTheModulesIriAndChangesTheOpenCopy() {
        assertEquals(MODULE_IRI, ModuleRebuild.declaredIriOf(moduleFile));
        OWLOntology open = ModuleRebuild.openFrom(edit, moduleFile);
        assertNotNull(open);

        TermExtract.Result rebuilt = ModuleRebuild.extract(upstream,
                Arrays.asList(IRI.create(UPPER + "object"), IRI.create(UPPER + "process")),
                TermExtract.Method.BOT, ModuleRebuild.declaredIriOf(moduleFile), "2026-11-01");
        open.getOWLOntologyManager().applyChanges(
                ModuleRebuild.changesToMatch(open, rebuilt.getModule()));

        assertEquals(MODULE_IRI, open.getOntologyID().getOntologyIRI().get());
        assertTrue(open.containsClassInSignature(IRI.create(UPPER + "process")));
        assertTrue(open.containsClassInSignature(IRI.create(UPPER + "object")));
        assertEquals(rebuilt.getModule().getAxioms(), open.getAxioms());
        assertTrue(ModuleRebuild.changesToMatch(open, rebuilt.getModule()).isEmpty(),
                "a second rebuild with the same terms changes nothing");
        Map<IRI, String> recorded = new HashMap<IRI, String>();
        for (org.semanticweb.owlapi.model.OWLAnnotation annotation : open.getAnnotations()) {
            recorded.put(annotation.getProperty().getIRI(), annotation.getValue().toString());
        }
        assertTrue(recorded.containsKey(IRI.create("http://purl.org/dc/terms/source")),
                "where it came from is recorded: " + recorded);
    }
}
