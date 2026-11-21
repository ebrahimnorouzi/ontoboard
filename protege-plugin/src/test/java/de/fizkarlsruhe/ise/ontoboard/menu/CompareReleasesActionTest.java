package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseDiff;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompareReleasesActionTest {

    /**
     * Releases are ordered as versions, not as text.
     *
     * <p>As text, 0.10.0 sorts before 0.9.0, and "From must be an earlier release than To" refused
     * to compare a semantic project's ninth minor release with its tenth.
     */
    @Test
    void versionsCompareAsVersions() {
        assertTrue(CompareReleasesAction.isEarlier("0.9.0", "0.10.0"));
        assertFalse(CompareReleasesAction.isEarlier("0.10.0", "0.9.0"));
        assertFalse(CompareReleasesAction.isEarlier("1.0.0", "1.0.0"));
        assertTrue(CompareReleasesAction.isEarlier("2026-09-30", "2026-10-31"));
    }

    /** The releases a project kept are listed newest first, by version. */
    @Test
    void theReleasesAreListedNewestFirst(@TempDir File project) {
        for (String version : new String[] {"0.9.0", "0.10.0", "0.2.0"}) {
            assertTrue(new File(project, "releases/" + version).mkdirs());
        }
        File edit = new File(project, "src/ontology/menu-edit.owl");

        assertEquals("[0.10.0, 0.9.0, 0.2.0]", CompareReleasesAction.releasesOf(edit).toString());
    }

    /**
     * A release that is only a git tag - ODK's kind - is offered as a choice beside the folders,
     * and compared as what the tag holds.
     */
    @Test
    void aTaggedReleaseIsOfferedAndCompared(@TempDir File root) throws Exception {
        assumeTrue(ProcessRunner.isAvailable(ProcessRunner.real(), "git", "--version"),
                "git is not installed");
        File edit = new File(root, "src/ontology/demo-edit.owl");
        write(edit, "<rdf:RDF/>");
        write(new File(root, "releases/0.9.0/demo.owl"), demo());
        git(root, "init", "-q");
        for (String[] release : new String[][] {{"1.0.0", "Egg"}, {"1.1.0", "Egg", "Pan"}}) {
            write(new File(root, "demo.owl"),
                    demo(Arrays.copyOfRange(release, 1, release.length)));
            git(root, "add", "-f", "demo.owl");
            git(root, "commit", "-q", "-m", "release(" + release[0] + ")");
            git(root, "tag", "-a", "v" + release[0], "-m", "demo " + release[0]);
        }
        write(new File(root, "demo.owl"), demo("Egg", "Pan", "Lid"));

        assertEquals("[1.1.0, 1.0.0, 0.9.0]", CompareReleasesAction.releasesOf(edit).toString());

        ReleaseDiff diff = ReleaseDiff.between(CompareReleasesAction.release(edit, "1.0.0"),
                CompareReleasesAction.release(edit, "1.1.0"));
        assertEquals(1, diff.getChanges().size(), diff.summary());
        assertEquals(ReleaseDiff.Change.ADDED, diff.getChanges().get(0).getChange());
        assertEquals("https://example.org/demo#Pan", diff.getChanges().get(0).getIri().toString(),
                "Lid is in the root's demo.owl now, not in either release");
    }

    /**
     * Compare says how each of the two was checked, as its release recorded it (1.155.0): which
     * reasoner, and what it said; a release with no record, and the copy that is open, say so.
     */
    @Test
    void compareShowsBothVerdicts(@TempDir File root) throws Exception {
        File edit = new File(root, "src/ontology/demo-edit.owl");
        write(edit, "<rdf:RDF/>");
        write(new File(root, "releases/1.0.0/demo.owl"), demo("Egg"));
        write(new File(root, "releases/1.1.0/demo.owl"), demo("Egg", "Pan"));
        write(new File(root, "releases/1.2.0/demo.owl"), demo("Egg", "Pan", "Lid"));
        write(new File(root, "releases/1.1.0/RELEASE.md"), "# demo 1.1.0\n\n## How it was checked\n\n"
                + "- **Reasoner:** ELK said consistent; it does not reason with inverse properties.\n");
        write(new File(root, "releases/1.2.0/RELEASE.md"), "# demo 1.2.0\n\n## How it was checked\n\n"
                + "- **Reasoner:** HermiT said consistent, every class satisfiable.\n");

        List<String> notes = CompareReleasesAction.checkedNotes(edit, "1.1.0", "1.2.0");
        List<String> older = CompareReleasesAction.checkedNotes(edit, "1.0.0", "what I have open now");

        assertEquals(Arrays.asList(
                "1.1.0 was checked: ELK said consistent; it does not reason with inverse properties.",
                "1.2.0 was checked: HermiT said consistent, every class satisfiable."), notes);
        assertTrue(older.get(0).startsWith("1.0.0 has no record"), older.toString());
        assertTrue(older.get(1).contains("not released"), older.toString());
    }

    /**
     * What is open is compared as a release holds it - its imports folded in, reasoned, relaxed
     * and reduced - as History compares its working copy. nfdicore's edit file holds 20 axioms and
     * imports its terms from two components: 3.0.5 against "what I have open now" came back "356
     * removed (2534 axioms to 20)" (Phase 7, probe host, 2026-11-20).
     */
    @Test
    void whatIsOpenIsComparedWithItsImports(@TempDir File root) throws Exception {
        File edit = new File(root, "src/ontology/demo-edit.owl");
        write(edit, "<rdf:RDF/>");
        write(new File(root, "releases/1.0.0/demo.owl"), demo("Egg", "Pan"));
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLOntology component = manager.loadOntologyFromOntologyDocument(
                new org.semanticweb.owlapi.io.StringDocumentSource(demo("Egg", "Pan")
                        .replace("demo.owl", "components/main.owl")));
        org.semanticweb.owlapi.model.OWLOntology open = manager.createOntology(
                org.semanticweb.owlapi.model.IRI.create("https://example.org/demo.owl"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(open,
                manager.getOWLDataFactory().getOWLImportsDeclaration(
                        component.getOntologyID().getOntologyIRI().get())));
        assertEquals(2, open.getImportsClosure().size(), "the component is imported");

        ReleaseDiff diff = ReleaseDiff.between(CompareReleasesAction.release(edit, "1.0.0"),
                CompareReleasesAction.workingCopy(open));

        assertEquals(java.util.Collections.emptyList(), diff.getChanges(), diff.summary());
    }

    private static String demo(String... classes) {
        StringBuilder text = new StringBuilder("Prefix(:=<https://example.org/demo#>)\n"
                + "Ontology(<https://example.org/demo.owl>\n");
        for (String name : classes) {
            text.append("Declaration(Class(:").append(name).append("))\n");
        }
        return text.append(")\n").toString();
    }

    private static void write(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void git(File root, String... arguments) throws IOException {
        List<String> command = new ArrayList<String>(Arrays.asList("git", "-c",
                "user.name=Test", "-c", "user.email=test@example.org", "-c",
                "core.autocrlf=false"));
        command.addAll(Arrays.asList(arguments));
        ProcessRunner.Outcome outcome = ProcessRunner.real().run(root, command, 1, null);
        assertTrue(outcome.isSuccess(), command + ": " + outcome.getOutput());
    }
}
