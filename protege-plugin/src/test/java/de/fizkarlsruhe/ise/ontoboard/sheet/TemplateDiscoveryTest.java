package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which folder of templates the sheet editor opens for the ontology in front of it.
 *
 * <p>Reported from a real project: {@code pizza-bfo} keeps its edit file at
 * {@code src/ontology/pizzabfo-edit.owl} and its knowledge-graph templates at
 * {@code kg/templates}. Opened from the edit file, the editor looked only in
 * {@code src/templates}, found nothing, and said "No templates yet" over nine sheets that were
 * one folder away. Every fixture here is synthetic and lives in a temporary folder, which is
 * also the ceiling the search may not climb above - so a {@code .git} somewhere above the
 * temporary directory on the machine running the tests cannot change an answer.
 */
class TemplateDiscoveryTest {

    @TempDir
    File dir;

    // ------------------------------------------------------------------ the reported defect

    @Test
    void theEditFileOfAProjectWithAKnowledgeGraphFindsKgTemplates() throws Exception {
        File root = project("pizza-bfo");
        File edit = file(root, "src/ontology/pizzabfo-edit.owl");
        File kg = sheets(root, "kg/templates", "01_cities.tsv", "02_pizzerias.tsv");

        TemplateDiscovery found = discover(edit);

        assertNotNull(found.getChosen(), "nine sheets one folder away is not 'no templates'");
        assertEquals(kg, found.getChosen().getFolder());
        assertEquals(TemplateDiscovery.Reason.KNOWLEDGE_GRAPH, found.getChosen().getReason());
        assertEquals(2, found.getChosen().getSheets());
        assertEquals(root, found.getProjectRoot());
    }

    @Test
    void theGraphFileFindsTheTemplatesBesideIt() throws Exception {
        File root = project("pizza-bfo");
        file(root, "src/ontology/pizzabfo-edit.owl");
        File graph = file(root, "kg/pizzabfo-kg.ttl");
        File kg = sheets(root, "kg/templates", "a.tsv");

        TemplateDiscovery found = discover(graph);

        assertEquals(kg, found.getChosen().getFolder());
        assertEquals(TemplateDiscovery.Reason.BESIDE, found.getChosen().getReason(),
                "kg/templates is beside the graph file, and is listed once, not twice");
        assertEquals(1, count(found.getCandidates(), kg));
    }

    @Test
    void theReleaseAtTheRootFindsKgTemplates() throws Exception {
        File root = project("pizza-bfo");
        file(root, "src/ontology/pizzabfo-edit.owl");
        File release = file(root, "pizzabfo.owl");
        File kg = sheets(root, "kg/templates", "a.tsv");

        assertEquals(kg, discover(release).getChosen().getFolder());
    }

    @Test
    void anEarlierReleaseFindsTheProjectsTemplates() throws Exception {
        File root = project("pizza-bfo");
        file(root, "src/ontology/pizzabfo-edit.owl");
        File old = file(root, "releases/1.0.0/pizzabfo.owl");
        File kg = sheets(root, "kg/templates", "a.tsv");

        TemplateDiscovery found = discover(old);

        assertEquals(root, found.getProjectRoot());
        assertEquals(kg, found.getChosen().getFolder());
    }

    // ------------------------------------------------------------------ layouts that worked

    @Test
    void anOdkProjectStillOpensSrcTemplates() throws Exception {
        File root = project("mwo");
        File edit = file(root, "src/ontology/mwo-edit.owl");
        File odk = sheets(root, "src/templates", "terms.tsv");

        TemplateDiscovery found = discover(edit);

        assertEquals(odk, found.getChosen().getFolder());
        assertEquals(TemplateDiscovery.Reason.ODK, found.getChosen().getReason());
        assertEquals(odk, found.getFolderForNewSheets());
    }

    @Test
    void aLooseOntologyStillOpensTheTemplatesBesideIt() throws Exception {
        File folder = new File(dir, "loose");
        File ontology = file(folder, "pizza.owl");
        File beside = sheets(folder, "templates", "a.csv");

        TemplateDiscovery found = discover(ontology);

        assertEquals(beside, found.getChosen().getFolder());
        assertEquals(TemplateDiscovery.Reason.BESIDE, found.getChosen().getReason());
        assertNull(found.getProjectRoot(), "no .git and no src/ontology: no project");
    }

    @Test
    void theOdkEditFileDoesNotLookInsideSrcOntology() throws Exception {
        File root = project("mwo");
        File edit = file(root, "src/ontology/mwo-edit.owl");

        for (TemplateDiscovery.Candidate each : discover(edit).getCandidates()) {
            assertFalse(each.getFolder().equals(new File(root, "src/ontology/templates")),
                    "an ODK project keeps its templates in src/templates, as ODK's Makefile does");
        }
    }

    // ------------------------------------------------------------------ precedence

    @Test
    void whenBothExistTheEditFileOpensSrcTemplatesAndNamesTheOther() throws Exception {
        File root = project("both");
        File edit = file(root, "src/ontology/both-edit.owl");
        File odk = sheets(root, "src/templates", "component.tsv");
        File kg = sheets(root, "kg/templates", "a.tsv", "b.tsv");

        TemplateDiscovery found = discover(edit);

        assertEquals(odk, found.getChosen().getFolder());
        assertTrue(found.isAmbiguous());
        assertEquals(1, found.getAlternatives().size());
        assertEquals(kg, found.getAlternatives().get(0).getFolder());
        assertTrue(found.describe().contains("kg/templates"),
                "the folder not opened is named, so nobody has to know it is there: "
                        + found.describe());
    }

    @Test
    void whenBothExistTheGraphFileOpensKgTemplates() throws Exception {
        File root = project("both");
        file(root, "src/ontology/both-edit.owl");
        File graph = file(root, "kg/graph.ttl");
        sheets(root, "src/templates", "component.tsv");
        File kg = sheets(root, "kg/templates", "a.tsv");

        assertEquals(kg, discover(graph).getChosen().getFolder());
    }

    @Test
    void anEmptyFolderGivesWayToOneWithSheets() throws Exception {
        File root = project("p");
        File edit = file(root, "src/ontology/p-edit.owl");
        assertTrue(new File(root, "src/templates").mkdirs());
        File kg = sheets(root, "kg/templates", "a.tsv");

        TemplateDiscovery found = discover(edit);

        assertEquals(kg, found.getChosen().getFolder());
        TemplateDiscovery.Candidate odk = found.getCandidates().get(0);
        assertEquals(new File(root, "src/templates"), odk.getFolder());
        assertTrue(odk.exists());
        assertEquals(0, odk.getSheets());
    }

    @Test
    void theOrderIsTheSameEveryTime() throws Exception {
        File root = project("p");
        File edit = file(root, "src/ontology/p-edit.owl");

        List<File> first = folders(discover(edit).getCandidates());
        List<File> second = folders(discover(edit).getCandidates());

        assertEquals(first, second);
        assertEquals(new File(root, "src/templates"), first.get(0));
        assertEquals(new File(root, "kg/templates"), first.get(1));
        assertEquals(new File(root, "templates"), first.get(2));
    }

    // ------------------------------------------------------------------ boundaries

    @Test
    void nothingThereMeansNoChoiceAndNothingCreated() throws Exception {
        File root = project("empty");
        File edit = file(root, "src/ontology/empty-edit.owl");

        TemplateDiscovery found = discover(edit);

        assertNull(found.getChosen());
        assertEquals(new File(root, "src/templates"), found.getFolderForNewSheets());
        for (TemplateDiscovery.Candidate each : found.getCandidates()) {
            assertFalse(each.getFolder().exists(),
                    "looking must not create " + each.getFolder());
        }
        String said = found.describe();
        assertTrue(said.contains("src/templates") && said.contains("kg/templates"),
                "says where it looked: " + said);
    }

    @Test
    void anotherProjectsTemplatesAreNeverPicked() throws Exception {
        // The shape of this repository: test/pizza is a repository of its own inside the
        // OntoBoard working copy. The outer project's templates are not the inner one's.
        File outer = project("outer");
        sheets(outer, "kg/templates", "outer.tsv");
        sheets(outer, "templates", "outer.tsv");
        File inner = new File(outer, "test/pizza");
        assertTrue(new File(inner, ".git").mkdirs());
        File edit = file(inner, "src/ontology/pizza-edit.owl");

        TemplateDiscovery found = discover(edit);

        assertEquals(inner, found.getProjectRoot());
        assertNull(found.getChosen(), "the nearest repository is the project, and it has none");
        for (TemplateDiscovery.Candidate each : found.getCandidates()) {
            assertTrue(each.getFolder().getPath().startsWith(inner.getPath()),
                    each.getFolder() + " is outside " + inner);
        }
    }

    @Test
    void withoutAProjectItDoesNotClimbIntoUnrelatedFolders() throws Exception {
        File unrelated = new File(dir, "downloads");
        sheets(unrelated, "kg/templates", "someone-else.tsv");
        File ontology = file(unrelated, "a/b/thing.owl");

        TemplateDiscovery found = discover(ontology);

        assertNull(found.getProjectRoot());
        assertNull(found.getChosen());
        assertEquals(1, found.getCandidates().size(), "only the folder beside the file");
    }

    @Test
    void theClimbIsBounded() throws Exception {
        File root = project("deep");
        File ontology = file(root, "a/b/c/d/e/f/deep.owl");
        sheets(root, "kg/templates", "a.tsv");

        assertNull(discover(ontology).getProjectRoot(),
                "a project root six folders up is too far to be a guess worth making");
    }

    @Test
    void subfoldersAreNotSearched() throws Exception {
        File root = project("p");
        File edit = file(root, "src/ontology/p-edit.owl");
        sheets(root, "kg/templates/archive", "old.tsv");

        TemplateDiscovery found = discover(edit);

        assertNull(found.getChosen(), "kg/templates holds no sheet itself; its subfolder is "
                + "not searched");
    }

    @Test
    void aGitFileMarksAWorktreeAsAProject() throws Exception {
        File root = new File(dir, "worktree");
        assertTrue(root.mkdirs());
        Files.write(new File(root, ".git").toPath(),
                "gitdir: ../main/.git/worktrees/x".getBytes(Charset.forName("UTF-8")));
        File graph = file(root, "data/graph.ttl");
        File kg = sheets(root, "kg/templates", "a.tsv");

        assertEquals(kg, discover(graph).getChosen().getFolder());
    }

    // ------------------------------------------------------------------ paths

    @Test
    void folderNamesAreMatchedWithoutRegardToCase() throws Exception {
        File root = project("Upper");
        File edit = file(root, "Src/Ontology/upper-edit.owl");
        File odk = sheets(root, "Src/templates", "a.tsv");

        TemplateDiscovery found = discover(edit);

        assertEquals(root, found.getProjectRoot());
        assertEquals(odk, found.getChosen().getFolder());
        assertEquals(TemplateDiscovery.Reason.ODK, found.getChosen().getReason());
    }

    @Test
    void aDocumentIriWithSpacesIsAFileAgain() throws Exception {
        File root = project("my project (copy)");
        File edit = file(root, "src/ontology/p-edit.owl");
        URI document = edit.toURI();

        File back = TemplateDiscovery.fileOf(document);

        assertEquals(edit.getAbsoluteFile(), back.getAbsoluteFile());
        assertEquals(root, discover(back).getProjectRoot());
    }

    @Test
    void anIriThatIsNotAFileIsNoFile() {
        assertNull(TemplateDiscovery.fileOf(URI.create("http://purl.obolibrary.org/obo/bfo.owl")));
        assertNull(TemplateDiscovery.fileOf(URI.create("urn:uuid:1234")));
        assertNull(TemplateDiscovery.fileOf(null));
    }

    @Test
    void aWindowsDriveLetterUriIsReadOnWindows() {
        assumeTrue(File.separatorChar == '\\', "drive letters exist only on Windows");
        File back = TemplateDiscovery.fileOf(URI.create("file:/C:/Users/someone/My%20Project/x.owl"));
        assertEquals(new File("C:\\Users\\someone\\My Project\\x.owl"), back);
    }

    @Test
    void locationsAreShownRelativeToTheProjectWithForwardSlashes() throws Exception {
        File root = project("p");
        File folder = new File(root, "kg" + File.separator + "templates");

        assertEquals("kg/templates", TemplateDiscovery.shortName(folder, root));
        assertEquals(folder.getAbsolutePath(), TemplateDiscovery.shortName(folder, null));
    }

    @Test
    void noFileMeansNothingToDiscover() {
        TemplateDiscovery found = TemplateDiscovery.of(null);

        assertNull(found.getChosen());
        assertTrue(found.getCandidates().isEmpty());
        assertNull(found.getKey());
        assertTrue(found.describe().contains("Open folder"),
                "an unsaved ontology still gets a way forward: " + found.describe());
    }

    // ------------------------------------------------------------------ switching ontologies

    @Test
    void switchingToAnotherFileOfTheSameProjectKeepsWhatIsOpen() throws Exception {
        File root = project("p");
        File edit = file(root, "src/ontology/p-edit.owl");
        File imported = file(root, "src/ontology/imports/bfo_import.owl");
        sheets(root, "kg/templates", "a.tsv");

        File shown = discover(edit).getKey();

        assertEquals(TemplateDiscovery.Reaction.KEEP,
                TemplateDiscovery.onActiveOntologyChanged(discover(imported), shown, false));
    }

    @Test
    void switchingToAnotherProjectOpensItsTemplates() throws Exception {
        File one = project("one");
        File two = project("two");
        File first = file(one, "src/ontology/one-edit.owl");
        File second = file(two, "src/ontology/two-edit.owl");

        File shown = discover(first).getKey();

        assertEquals(TemplateDiscovery.Reaction.OPEN,
                TemplateDiscovery.onActiveOntologyChanged(discover(second), shown, false));
    }

    @Test
    void unsavedEditsAreNeverReplacedBecauseTheOntologyChanged() throws Exception {
        File one = project("one");
        File two = project("two");
        File shown = discover(file(one, "src/ontology/one-edit.owl")).getKey();

        assertEquals(TemplateDiscovery.Reaction.HOLD,
                TemplateDiscovery.onActiveOntologyChanged(
                        discover(file(two, "src/ontology/two-edit.owl")), shown, true));
    }

    @Test
    void anOntologyWithoutAFileLeavesTheSheetsAlone() throws Exception {
        File shown = discover(file(project("p"), "src/ontology/p-edit.owl")).getKey();

        assertEquals(TemplateDiscovery.Reaction.KEEP,
                TemplateDiscovery.onActiveOntologyChanged(TemplateDiscovery.of(null), shown,
                        false));
    }

    @Test
    void theFirstOntologyOpensItsTemplates() throws Exception {
        File edit = file(project("p"), "src/ontology/p-edit.owl");

        assertEquals(TemplateDiscovery.Reaction.OPEN,
                TemplateDiscovery.onActiveOntologyChanged(discover(edit), null, false));
    }

    /**
     * Somebody who chose a folder with Open folder... chose it. Switching the active ontology
     * says where that ontology's templates are; it does not take the folder away.
     */
    @Test
    void aFolderChosenByHandIsNotReplacedAutomatically() throws Exception {
        File one = project("one");
        File two = project("two");
        File shown = discover(file(one, "src/ontology/one-edit.owl")).getKey();

        assertEquals(TemplateDiscovery.Reaction.HOLD,
                TemplateDiscovery.onActiveOntologyChanged(
                        discover(file(two, "src/ontology/two-edit.owl")), shown, false, true));
        assertEquals(TemplateDiscovery.Reaction.KEEP,
                TemplateDiscovery.onActiveOntologyChanged(
                        discover(file(one, "kg/graph.ttl")), shown, false, true));
    }

    // ------------------------------------------------------------------ helpers

    private TemplateDiscovery discover(File ontology) {
        return TemplateDiscovery.of(ontology, dir);
    }

    private File project(String name) {
        File root = new File(dir, name);
        assertTrue(new File(root, ".git").mkdirs());
        return root;
    }

    private static File file(File under, String relative) throws Exception {
        File file = new File(under, relative.replace('/', File.separatorChar));
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        Files.write(file.toPath(), "x".getBytes(Charset.forName("UTF-8")));
        return file;
    }

    private static File sheets(File under, String relative, String... names) throws Exception {
        File folder = new File(under, relative.replace('/', File.separatorChar));
        assertTrue(folder.isDirectory() || folder.mkdirs());
        for (String name : names) {
            Files.write(new File(folder, name).toPath(),
                    "ID\tLabel\nID\tLABEL\n".getBytes(Charset.forName("UTF-8")));
        }
        return folder;
    }

    private static int count(List<TemplateDiscovery.Candidate> candidates, File folder) {
        int seen = 0;
        for (TemplateDiscovery.Candidate each : candidates) {
            if (each.getFolder().equals(folder)) {
                seen++;
            }
        }
        return seen;
    }

    private static List<File> folders(List<TemplateDiscovery.Candidate> candidates) {
        List<File> out = new ArrayList<File>();
        for (TemplateDiscovery.Candidate each : candidates) {
            out.add(each.getFolder());
        }
        return out;
    }
}
