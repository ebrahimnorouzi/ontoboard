package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.pattern.DesignPattern;
import de.fizkarlsruhe.ise.ontoboard.pattern.PatternLibrary;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatternLibraryActionTest {

    /**
     * A search finds a pattern that is not among the suggestions.
     *
     * <p>The pizza walkthrough's three searches each listed nothing in a new project, because the
     * dialog opens on "Suggested for this ontology" and a search looked only among those.
     */
    @Test
    void aSearchFindsWhatIsNotSuggested() {
        List<DesignPattern> none = Collections.<DesignPattern>emptyList();
        for (String[] search : new String[][] {
            {"components", "componency"}, {"classifies", "classification"}, {"cost", "price"}}) {
            List<DesignPattern> shown = PatternLibraryAction.suggestedThenTheRest(none,
                    PatternLibrary.matching(PatternLibrary.all(), search[0]), true);
            assertTrue(idsOf(shown).contains(search[1]), search[0] + " lists " + idsOf(shown));
        }
    }

    /** The suggestions still come first, each once, and with nothing typed they are all there is. */
    @Test
    void theSuggestionsComeFirst() {
        DesignPattern price = PatternLibrary.find("price");
        List<DesignPattern> matching = new ArrayList<DesignPattern>(PatternLibrary.all());

        List<DesignPattern> shown = PatternLibraryAction.suggestedThenTheRest(
                Arrays.asList(price), matching, true);
        assertEquals("price", shown.get(0).getId());
        assertEquals(matching.size(), shown.size(), "nothing listed twice");

        assertEquals(Arrays.asList(price), PatternLibraryAction.suggestedThenTheRest(
                Arrays.asList(price), matching, false));
    }

    /**
     * The confirmation names the module and term list the import writes, for every pattern.
     *
     * <p>It said {@code imports/<id>_import.owl}, and the import names the files after the
     * pattern as it reads it - its own IRI, or its file - so for 49 of the 157 patterns a code
     * review loaded, the dialog promised a file that was never written: {@code agent-role} is
     * written as {@code agentrole_import.owl}. Compared here with the import's own reading of the
     * copy, imports left out so nothing is fetched.
     */
    @Test
    void theConfirmationNamesTheFilesTheImportWrites(@org.junit.jupiter.api.io.TempDir
            java.io.File folder) throws Exception {
        java.io.File mirror = new java.io.File(folder, "mirror");
        int differFromTheId = 0;
        List<String> wrong = new ArrayList<String>();
        for (DesignPattern pattern : PatternLibrary.all()) {
            java.io.File copied = PatternLibrary.copyTo(pattern, mirror);
            org.semanticweb.owlapi.model.OWLOntologyManager reader =
                    org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
            reader.setOntologyLoaderConfiguration(reader.getOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(
                            org.semanticweb.owlapi.model.MissingImportHandlingStrategy.SILENT));
            final org.semanticweb.owlapi.model.IRI nowhere = org.semanticweb.owlapi.model.IRI
                    .create(new java.io.File(folder, "no-such-import.owl").toURI());
            reader.getIRIMappers().add(iri -> nowhere);
            String written;
            try {
                written = de.fizkarlsruhe.ise.ontoboard.robot.TermExtract.shortNameOf(
                        de.fizkarlsruhe.ise.ontoboard.robot.OntologySource.loadFile(reader,
                                copied));
            } catch (Exception unreadable) {
                continue; // the import refuses it too, so there is nothing to promise
            }
            String promised = PatternLibraryAction.moduleNameFor(pattern, mirror);
            if (!written.equals(promised)) {
                wrong.add(pattern.getId() + " promises " + promised + ", writes " + written);
            }
            differFromTheId += written.equals(pattern.getId()) ? 0 : 1;
        }
        assertEquals("[]", wrong.toString());
        assertTrue(differFromTheId > 0, "no pattern tells the two apart, so this checks nothing");
        assertEquals("agentrole",
                PatternLibraryAction.moduleNameFor(PatternLibrary.find("agent-role"), mirror));
    }

    /** What the confirmation says, with all terms, with some selected, and a second time. */
    @Test
    void theConfirmationSaysWhatComesIn() {
        String all = PatternLibraryAction.whatAddingWrites("Measurement", 25, 18, 0,
                "measurement", false);
        assertTrue(all.contains("25 classes and 18 properties"), all);
        assertTrue(all.contains("imports/measurement_import.owl"), all);
        assertTrue(all.contains("measurement_terms.txt"), all);
        assertFalse(all.contains("selected"), all);
        assertFalse(all.contains("already"), all);

        // One click on a row narrowed the import to that row, and only "1 class and 0
        // properties" said so - a participant's next task needed the boxes left out.
        String one = PatternLibraryAction.whatAddingWrites("Measurement", 1, 0, 43,
                "measurement", false);
        assertTrue(one.contains("Only the term selected in the list is added"), one);
        assertTrue(one.contains("Add all 43"), one);
        String three = PatternLibraryAction.whatAddingWrites("Measurement", 2, 1, 43,
                "measurement", false);
        assertTrue(three.contains("Only the 3 terms selected in the list are added"), three);

        String again = PatternLibraryAction.whatAddingWrites("Measurement", 25, 18, 0,
                "measurement", true);
        assertTrue(again.startsWith("Measurement is already imported."), again);
    }

    /**
     * A pattern counts as imported by its term list's {@code # Pattern:} line and its module being
     * among the open ontology's imports; files an undone import left behind do not count.
     */
    @Test
    void aPatternIsImportedWhileItsModuleIsImported(@org.junit.jupiter.api.io.TempDir
            java.io.File folder) throws Exception {
        java.io.File imports = new java.io.File(folder, "imports");
        assertTrue(imports.mkdirs());
        java.io.File moduleFile = new java.io.File(imports, "agentrole_import.owl");
        org.semanticweb.owlapi.model.IRI moduleIri = org.semanticweb.owlapi.model.IRI.create(
                "https://example.org/myont/imports/agentrole_import.owl");
        org.semanticweb.owlapi.model.OWLOntologyManager writer =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        writer.saveOntology(writer.createOntology(moduleIri),
                org.semanticweb.owlapi.model.IRI.create(moduleFile.toURI()));
        java.nio.file.Files.write(new java.io.File(imports, "agentrole_terms.txt").toPath(),
                ("# Pattern: agent-role\nhttps://example.org/x#A\n").getBytes("UTF-8"));

        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLOntology mine = manager.createOntology(
                org.semanticweb.owlapi.model.IRI.create("https://example.org/myont.owl"));
        assertEquals(null, PatternLibraryAction.importedAs(mine, imports, "agent-role"),
                "the files alone do not make it imported");

        manager.loadOntologyFromOntologyDocument(moduleFile);
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(mine,
                manager.getOWLDataFactory().getOWLImportsDeclaration(moduleIri)));
        assertEquals(moduleFile.getAbsoluteFile(),
                PatternLibraryAction.importedAs(mine, imports, "agent-role").getAbsoluteFile());
        assertEquals(null, PatternLibraryAction.importedAs(mine, imports, "agentrole"),
                "another pattern is not imported because this one is");
    }

    private static List<String> idsOf(List<DesignPattern> patterns) {
        List<String> ids = new ArrayList<String>();
        for (DesignPattern pattern : patterns) {
            ids.add(pattern.getId());
        }
        return ids;
    }
}
