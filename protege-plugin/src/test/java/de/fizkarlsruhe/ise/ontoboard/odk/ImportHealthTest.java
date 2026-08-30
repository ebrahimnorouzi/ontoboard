package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Noticing that an ontology opened without half of itself.
 *
 * <p>An ontology whose imports did not resolve opens perfectly happily: the hierarchy is there,
 * the file is there, and the thousands of classes it was importing are not. A term looks unused, a
 * subclass axiom points at nothing, and a reasoner reports no inconsistency because half the
 * axioms are absent. Nothing in the window says so, which is why it is worth saying here.
 */
class ImportHealthTest {

    private static final String PROJECT = "http://purls.helmholtz-metadaten.de/mwo/mwo";

    /** An ontology declaring an import, with none of them loaded. */
    private static OWLOntology declaring(OWLOntologyManager manager, String... importIris)
            throws Exception {
        OWLOntology ontology = manager.createOntology(IRI.create(PROJECT + ".owl"));
        for (String iri : importIris) {
            manager.applyChange(new AddImport(ontology,
                    manager.getOWLDataFactory().getOWLImportsDeclaration(IRI.create(iri))));
        }
        return ontology;
    }

    private static File catalogWith(File directory, String... pairs) throws Exception {
        String xml = null;
        for (int i = 0; i < pairs.length; i += 2) {
            xml = Catalog.withEntry(xml, pairs[i], pairs[i + 1]);
        }
        File catalog = new File(directory, "catalog-v001.xml");
        Files.write(catalog.toPath(), (xml == null ? Catalog.empty() : xml).getBytes("UTF-8"));
        return catalog;
    }

    // ---------- what is missing ----------

    @Test
    void animportThatLoadedIsNotReported(@TempDir File directory) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = declaring(manager, PROJECT + "/imports/iao_import.owl");
        manager.createOntology(IRI.create(PROJECT + "/imports/iao_import.owl"));

        assertTrue(ImportHealth.missingFrom(ontology, manager,
                catalogWith(directory)).isEmpty());
    }

    @Test
    void anImportThatDidNotLoadIsReported(@TempDir File directory) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = declaring(manager, PROJECT + "/imports/iao_import.owl");

        List<ImportHealth.Missing> missing = ImportHealth.missingFrom(ontology, manager,
                catalogWith(directory));

        assertEquals(1, missing.size());
        assertEquals(IRI.create(PROJECT + "/imports/iao_import.owl"),
                missing.get(0).getIri());
    }

    @Test
    void anOntologyWithNoImportsHasNothingMissing(@TempDir File directory) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

        assertTrue(ImportHealth.missingFrom(declaring(manager), manager,
                catalogWith(directory)).isEmpty());
    }

    @Test
    void nothingToLookAtIsNotAFailure() {
        assertTrue(ImportHealth.missingFrom(null, OWLManager.createOWLOntologyManager(), null)
                .isEmpty());
    }

    /**
     * The one that mattered, reproduced the way the real project does it: a module on disk whose
     * own ontology IRI is not the IRI that imports it, resolved through a catalog.
     *
     * <p>An import resolves to whatever document the catalog points at, and that document's own
     * IRI need not equal the IRI in the import statement. Asking {@code manager.contains(importIri)}
     * instead reports a perfectly resolved import as missing whenever the two differ - and they
     * differ constantly. Measured on the reference ODK project this plugin's own help text points
     * at: three of its four import modules declare an IRI one path segment away from the one that
     * imports them, so opening it produced three warnings that the ontology was missing everything
     * those modules contain, while all of it was there and loaded.
     */
    @Test
    void anImportResolvingToAnOntologyWithADifferentIriIsNotReportedMissing(@TempDir File directory)
            throws Exception {
        // The module, whose declared IRI is one segment away from the IRI that imports it -
        // exactly the shape of mwo's iao_import.owl.
        String moduleIri = "http://purls.helmholtz-metadaten.de/mwo/imports/iao_import.owl";
        String importIri = PROJECT + "/imports/iao_import.owl";
        File moduleFile = new File(directory, "imports/iao_import.owl");
        moduleFile.getParentFile().mkdirs();
        OWLOntologyManager writing = OWLManager.createOWLOntologyManager();
        OWLOntology module = writing.createOntology(IRI.create(moduleIri));
        writing.addAxiom(module, writing.getOWLDataFactory().getOWLDeclarationAxiom(
                writing.getOWLDataFactory().getOWLClass(
                        IRI.create("http://purl.obolibrary.org/obo/IAO_0000109"))));
        writing.saveOntology(module, IRI.create(moduleFile.toURI()));

        File editFile = new File(directory, "thing-edit.owl");
        OWLOntology edited = writing.createOntology(IRI.create(PROJECT + ".owl"));
        writing.applyChange(new AddImport(edited,
                writing.getOWLDataFactory().getOWLImportsDeclaration(IRI.create(importIri))));
        writing.saveOntology(edited, IRI.create(editFile.toURI()));

        final File catalog = catalogWith(directory, importIri, "imports/iao_import.owl");

        // Loaded the way Protege and ROBOT load it: through the catalog.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.getIRIMappers().add(new org.semanticweb.owlapi.model.OWLOntologyIRIMapper() {
            private static final long serialVersionUID = 1L;

            @Override
            public IRI getDocumentIRI(IRI wanted) {
                try {
                    String mapped = Catalog.entryFor(new String(Files.readAllBytes(
                            catalog.toPath()), java.nio.charset.Charset.forName("UTF-8")),
                            wanted.toString());
                    if (mapped == null) {
                        return null;
                    }
                    File target = new File(catalog.getParentFile(), mapped);
                    return target.isFile() ? IRI.create(target.toURI()) : null;
                } catch (Exception cannotRead) {
                    return null;
                }
            }
        });
        OWLOntology opened = manager.loadOntologyFromOntologyDocument(editFile);

        // The import really did resolve - two ontologies in the closure ...
        assertEquals(2, opened.getImportsClosure().size(), "the import did not resolve at all");
        // ... and yet the IRIs differ, which is what the old check got wrong.
        assertFalse(manager.contains(IRI.create(importIri)),
                "this test is only meaningful when the two IRIs differ");

        assertTrue(ImportHealth.missingFrom(opened, manager, catalog).isEmpty(),
                "a resolved import was reported as missing because its IRI differs: "
                        + ImportHealth.missingFrom(opened, manager, catalog));
    }

    // ---------- three problems, three different answers ----------

    /**
     * Both halves of the mapping are in place, so nothing was fetched and nothing was absent - the
     * file itself did not parse. Sending this user to check their network is advice about the one
     * thing that is certainly fine.
     */
    @Test
    void aCatalogEntryWhoseFileIsThereButUnreadableIsNotBlamedOnTheNetwork(@TempDir File directory)
            throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = declaring(manager, PROJECT + "/imports/iao_import.owl");
        File catalog = catalogWith(directory,
                PROJECT + "/imports/iao_import.owl", "imports/iao_import.owl");
        File module = new File(directory, "imports/iao_import.owl");
        module.getParentFile().mkdirs();
        Files.write(module.toPath(), "this is not an ontology".getBytes("UTF-8"));

        ImportHealth.Missing missing =
                ImportHealth.missingFrom(ontology, manager, catalog).get(0);

        assertTrue(missing.isInCatalog());
        assertTrue(missing.fileExists());
        assertTrue(missing.explain().contains("could not be read"), missing.explain());
        assertFalse(missing.explain().contains("proxy"),
                "the file is on disk; the network is not the problem: " + missing.explain());
        assertFalse(missing.explain().contains("make"),
                "the file is there, so building it again is not the answer: " + missing.explain());
    }

    /**
     * The commonest ODK case by far: the module is generated by the build and gitignored, so a
     * fresh checkout has the catalog entry and not the file.
     */
    @Test
    void aCatalogEntryPointingAtAFileNobodyCommittedSaysToRunTheBuild(@TempDir File directory)
            throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = declaring(manager, PROJECT + "/imports/iao_import.owl");
        File catalog = catalogWith(directory,
                PROJECT + "/imports/iao_import.owl", "imports/iao_import.owl");

        ImportHealth.Missing missing =
                ImportHealth.missingFrom(ontology, manager, catalog).get(0);

        assertTrue(missing.isInCatalog());
        assertFalse(missing.fileExists());
        assertTrue(missing.explain().contains("make"), missing.explain());
    }

    /** An IRI under the project's own domain is never published, so nothing can fetch it. */
    @Test
    void aProjectImportWithNoCatalogEntrySaysTheCatalogIsWhatResolvesIt(@TempDir File directory)
            throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = declaring(manager, PROJECT + "/imports/iao_import.owl");

        ImportHealth.Missing missing = ImportHealth
                .missingFrom(ontology, manager, catalogWith(directory)).get(0);

        assertFalse(missing.isInCatalog());
        assertTrue(missing.explain().contains("catalog-v001.xml"), missing.explain());
        assertTrue(missing.explain().contains("never"), missing.explain());
    }

    /** A published ontology that did not download is a different problem with a different fix. */
    @Test
    void aPublishedOntologyThatDidNotDownloadIsBlamedOnTheNetwork(@TempDir File directory)
            throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = declaring(manager, "http://purl.obolibrary.org/obo/ro.owl");

        ImportHealth.Missing missing = ImportHealth
                .missingFrom(ontology, manager, catalogWith(directory)).get(0);

        assertTrue(missing.explain().contains("proxy"), missing.explain());
        assertFalse(missing.explain().contains("catalog"),
                "no catalog entry is expected for a published ontology: " + missing.explain());
    }

    @Test
    void theExplanationAlwaysNamesTheImport(@TempDir File directory) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = declaring(manager, PROJECT + "/imports/iao_import.owl",
                "http://purl.obolibrary.org/obo/ro.owl");

        for (ImportHealth.Missing missing : ImportHealth.missingFrom(ontology, manager,
                catalogWith(directory))) {
            assertTrue(missing.explain().contains(missing.getIri().toString()),
                    missing.explain());
        }
    }

    // ---------- a catalog written the way ODK writes one ----------

    /**
     * The real thing nests its entries in a {@code <group id="odk-managed-catalog">}. Reading only
     * top-level entries would report every import of every ODK project as uncatalogued, which is
     * both wrong and the exact opposite of helpful.
     */
    @Test
    void entriesNestedInAnOdkGroupAreFound(@TempDir File directory) throws Exception {
        String odkCatalog = "<?xml version='1.0' encoding='UTF-8'?>\n"
                + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\" "
                + "prefer=\"public\">\n"
                + "  <group id=\"odk-managed-catalog\" prefer=\"public\">\n"
                + "    <uri name=\"" + PROJECT + "/imports/iao_import.owl\" "
                + "uri=\"imports/iao_import.owl\" />\n"
                + "  </group>\n"
                + "</catalog>\n";
        File catalog = new File(directory, "catalog-v001.xml");
        Files.write(catalog.toPath(), odkCatalog.getBytes("UTF-8"));

        assertEquals("imports/iao_import.owl",
                Catalog.entryFor(odkCatalog, PROJECT + "/imports/iao_import.owl"));

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = declaring(manager, PROJECT + "/imports/iao_import.owl");

        ImportHealth.Missing missing =
                ImportHealth.missingFrom(ontology, manager, catalog).get(0);

        assertTrue(missing.isInCatalog(), "the nested entry was not found");
    }

    @Test
    void aMissingCatalogFileIsNotTreatedAsAnEmptyOne(@TempDir File directory) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = declaring(manager, PROJECT + "/imports/iao_import.owl");

        ImportHealth.Missing missing = ImportHealth.missingFrom(ontology, manager,
                new File(directory, "no-catalog-here.xml")).get(0);

        assertFalse(missing.isInCatalog());
    }
}
