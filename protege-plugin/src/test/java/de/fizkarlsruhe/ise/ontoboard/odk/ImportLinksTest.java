package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.OntologyCopy;

/**
 * nfdicore's import modules call themselves {@code .../ontology/imports/<m>_import.owl} - its
 * release.sh builds them with ONTBASE=https://nfdi.fiz-karlsruhe.de/ontology - while its edit
 * file imports {@code .../ontology/nfdicore/imports/<m>_import.owl}, which the catalog maps to
 * those files. ROBOT and ODK's make take the file the catalog gives; Protégé 5.6.9 loads it and
 * then looks the import up by name, finds none, and leaves the module out: in the probe host the
 * closure held 3 of nfdicore's 11 ontologies, and Measure, the board, the reasoner and the labels
 * of every imported object property went without the eight modules (1.155.0).
 */
class ImportLinksTest {

    private static final String BASE = "https://example.org/onto";

    private File folder;
    private File catalog;

    private void write(String name, String text) throws Exception {
        File file = new File(folder, name);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String ontology(String iri, String body) {
        return "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                + "Ontology(<" + iri + ">\n" + body + ")\n";
    }

    /**
     * An ODK project in nfdicore's shape: the edit file imports two modules through the
     * catalog; module a calls itself by another name, module b by the one it is imported by.
     */
    private void project(File where, String moduleA) throws Exception {
        folder = where;
        write("imports/a_import.owl", moduleA);
        write("imports/b_import.owl", ontology(BASE + "/proj/imports/b_import.owl",
                "Declaration(Class(<" + BASE + "/B>))\n"));
        write("proj-edit.owl", ontology(BASE + "/proj.owl",
                "Import(<" + BASE + "/proj/imports/a_import.owl>)\n"
                        + "Import(<" + BASE + "/proj/imports/b_import.owl>)\n"
                        + "Declaration(Class(<" + BASE + "/P>))\n"));
        String xml = Catalog.withEntry(null, BASE + "/proj/imports/a_import.owl",
                "imports/a_import.owl");
        xml = Catalog.withEntry(xml, BASE + "/proj/imports/b_import.owl", "imports/b_import.owl");
        xml = Catalog.withEntry(xml, BASE + "/proj/imports/a2_import.owl", "imports/a2_import.owl");
        catalog = new File(folder, "catalog-v001.xml");
        Files.write(catalog.toPath(), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static final String MODULE_A = ontology(BASE + "/imports/a_import.owl",
            "Declaration(ObjectProperty(<http://purl.obolibrary.org/obo/BFO_0000051>))\n"
                    + "AnnotationAssertion(rdfs:label <http://purl.obolibrary.org/obo/BFO_0000051> "
                    + "\"has part\")\n");

    /** The catalog's mapping, as Protégé and ROBOT both give their managers. */
    private OWLOntologyIRIMapper catalogMapper() {
        return new OWLOntologyIRIMapper() {
            private static final long serialVersionUID = 1L;

            @Override
            public IRI getDocumentIRI(IRI wanted) {
                try {
                    String mapped = Catalog.entryFor(new String(Files.readAllBytes(
                            catalog.toPath()), StandardCharsets.UTF_8), wanted.toString());
                    File target = mapped == null ? null : new File(folder, mapped);
                    return target != null && target.isFile() ? IRI.create(target.toURI()) : null;
                } catch (Exception cannotRead) {
                    return null;
                }
            }
        };
    }

    /**
     * What is open, as Protégé 5.6.9 has it: read with its imports by a manager of its own, then
     * moved into the workspace's manager - which keeps the ontologies and not which import each
     * answers.
     */
    private OWLOntology openedAsProtegeOpensIt() throws Exception {
        OWLOntologyManager aside = OWLManager.createOWLOntologyManager();
        aside.getIRIMappers().add(catalogMapper());
        OWLOntology read = aside.loadOntologyFromOntologyDocument(new File(folder,
                "proj-edit.owl"));
        assertEquals(aside.getOntologies().size(), read.getImportsClosure().size(),
                "ROBOT's way links every file it read");
        OWLOntologyManager workspace = OWLManager.createOWLOntologyManager();
        workspace.getIRIMappers().add(catalogMapper());
        for (OWLOntology one : new ArrayList<OWLOntology>(aside.getOntologies())) {
            workspace.copyOntology(one, OntologyCopy.MOVE);
        }
        OWLOntology open = workspace.getOntology(IRI.create(BASE + "/proj.owl"));
        assertEquals(2, open.getImportsClosure().size(),
                "the fixture is Protégé's: the module that calls itself otherwise is left out");
        return open;
    }

    private static OWLImportsDeclaration declarationOf(OWLOntology ontology, String iri) {
        return ontology.getOWLOntologyManager().getOWLDataFactory()
                .getOWLImportsDeclaration(IRI.create(iri));
    }

    @Test
    void aModuleThatCallsItselfOtherwiseIsFound(@TempDir File where) throws Exception {
        project(where, MODULE_A);
        OWLOntology open = openedAsProtegeOpensIt();

        List<ImportLinks.Unlinked> found = ImportLinks.of(open, catalog);

        assertEquals(1, found.size(), String.valueOf(found));
        ImportLinks.Unlinked a = found.get(0);
        assertEquals(BASE + "/proj/imports/a_import.owl", a.imported().toString());
        assertEquals(BASE + "/imports/a_import.owl", a.calledItself().toString());
        assertEquals(new File(where, "imports/a_import.owl").getAbsoluteFile(),
                a.file().getAbsoluteFile());
    }

    /**
     * Linked as ROBOT links it: the import answered by the ontology already read from the file
     * the catalog gives - nothing read again, no ontology changed, so nothing is left unsaved.
     */
    @Test
    void itIsLinkedWithoutReadingOrChangingAnything(@TempDir File where) throws Exception {
        project(where, MODULE_A);
        OWLOntology open = openedAsProtegeOpensIt();
        OWLOntologyManager manager = open.getOWLOntologyManager();
        OWLOntology module = manager.getOntology(IRI.create(BASE + "/imports/a_import.owl"));
        int loaded = manager.getOntologies().size();
        final List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        manager.addOntologyChangeListener(changes::addAll);

        List<ImportLinks.Unlinked> linked = ImportLinks.link(open, catalog);

        assertEquals(1, linked.size());
        assertSame(module, manager.getImportedOntology(declarationOf(open,
                BASE + "/proj/imports/a_import.owl")));
        assertEquals(3, open.getImportsClosure().size(), "the closure holds the module now");
        assertTrue(open.getImportsClosure().contains(module));
        assertEquals(loaded, manager.getOntologies().size(), "nothing was read again");
        assertEquals(new ArrayList<OWLOntologyChange>(), changes, "no ontology changed");
        assertEquals(BASE + "/imports/a_import.owl",
                module.getOntologyID().getOntologyIRI().get().toString(), "its name is its own");
        assertTrue(ImportLinks.of(open, catalog).isEmpty(), "nothing is left to link");
    }

    /** A module that imports another, which calls itself otherwise too, is linked as well. */
    @Test
    void whatAModuleImportsIsLinkedToo(@TempDir File where) throws Exception {
        project(where, ontology(BASE + "/imports/a_import.owl",
                "Import(<" + BASE + "/proj/imports/a2_import.owl>)\n"
                        + "Declaration(Class(<" + BASE + "/A>))\n"));
        write("imports/a2_import.owl", ontology(BASE + "/imports/a2_import.owl",
                "Declaration(Class(<" + BASE + "/A2>))\n"));
        OWLOntology open = openedAsProtegeOpensIt();

        List<ImportLinks.Unlinked> linked = ImportLinks.link(open, catalog);

        assertEquals(2, linked.size(), String.valueOf(linked));
        assertEquals(4, open.getImportsClosure().size());
    }

    /**
     * An import the catalog maps to a file that is not there, or to nothing, is no name to put
     * right: it did not arrive, and Check imports says why (ImportHealth).
     */
    @Test
    void anImportThatDidNotArriveIsLeftAlone(@TempDir File where) throws Exception {
        project(where, MODULE_A);
        OWLOntology open = openedAsProtegeOpensIt();
        OWLOntologyManager manager = open.getOWLOntologyManager();
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(open,
                declarationOf(open, BASE + "/proj/imports/a2_import.owl")));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(open,
                declarationOf(open, "https://example.org/elsewhere/x.owl")));

        List<ImportLinks.Unlinked> found = ImportLinks.of(open, catalog);

        assertEquals(1, found.size(), String.valueOf(found));
        assertNull(manager.getImportedOntology(declarationOf(open,
                BASE + "/proj/imports/a2_import.owl")));
    }

    /** Without a catalog nothing is known of where an import's file is: nothing is linked. */
    @Test
    void withoutACatalogNothingIsLinked(@TempDir File where) throws Exception {
        project(where, MODULE_A);
        OWLOntology open = openedAsProtegeOpensIt();

        assertTrue(ImportLinks.link(open, null).isEmpty());
        assertTrue(ImportLinks.link(open, new File(where, "no-catalog.xml")).isEmpty());
        assertEquals(2, open.getImportsClosure().size());
    }

    /**
     * What the person reads: how many, which name each file calls itself and which it is
     * imported by, that ROBOT and ODK read them so, and where the files are put right.
     */
    @Test
    void whatIsSaidNamesTheModulesAndTheWayToPutThemRight(@TempDir File where)
            throws Exception {
        project(where, MODULE_A);
        OWLOntology open = openedAsProtegeOpensIt();

        String said = ImportLinks.said(ImportLinks.link(open, catalog),
                new File(where, "proj-edit.owl"));

        assertTrue(said.startsWith("1 file that proj-edit.owl imports calls itself"), said);
        assertTrue(said.contains("imports/a_import.owl"), said);
        assertTrue(said.contains(BASE + "/imports/a_import.owl"), said);
        assertTrue(said.contains(BASE + "/proj/imports/a_import.owl"), said);
        assertTrue(said.contains("ROBOT"), said);
        assertTrue(said.contains("OntoBoard > Reuse > Check imports"), said);
        assertFalse(said.contains("null"), said);
    }

    /**
     * nfdicore's edit file, imports-edit and nfdicore-main import the same eight modules: 15
     * imports, 8 files. What is said counts files (1.155.0: the first notice said "15 imports").
     */
    @Test
    void twoImportsOfOneFileAreOneFile(@TempDir File where) throws Exception {
        project(where, MODULE_A);
        write("imports/b_import.owl", ontology(BASE + "/proj/imports/b_import.owl",
                "Import(<" + BASE + "/proj/imports/a_import.owl>)\n"
                        + "Declaration(Class(<" + BASE + "/B>))\n"));
        OWLOntology open = openedAsProtegeOpensIt();

        List<ImportLinks.Unlinked> linked = ImportLinks.link(open, catalog);
        String said = ImportLinks.said(linked, new File(where, "proj-edit.owl"));

        assertEquals(2, linked.size(), "two imports");
        assertTrue(said.startsWith("1 file that proj-edit.owl imports calls itself"), said);
        assertFalse(said.contains("more"), said);
    }

    /**
     * What Check imports names: each import answered by a file that calls itself otherwise -
     * linked by OntoBoard or by ROBOT's loading - once for each file, with both names.
     */
    @Test
    void checkImportsNamesEachFileThatCallsItselfOtherwise(@TempDir File where) throws Exception {
        project(where, MODULE_A);
        OWLOntology open = openedAsProtegeOpensIt();
        assertTrue(ImportLinks.namedOtherwise(open, catalog).isEmpty(),
                "left out, it is not named: it did not arrive");
        ImportLinks.link(open, catalog);

        List<String> named = ImportLinks.namedOtherwise(open, catalog);

        assertEquals(1, named.size(), String.valueOf(named));
        assertTrue(named.get(0).contains("imports/a_import.owl"), named.get(0));
        assertTrue(named.get(0).contains(BASE + "/imports/a_import.owl"), named.get(0));
        assertTrue(named.get(0).contains(BASE + "/proj/imports/a_import.owl"), named.get(0));
    }
}
