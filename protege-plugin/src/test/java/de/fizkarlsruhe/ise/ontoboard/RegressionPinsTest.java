package de.fizkarlsruhe.ise.ontoboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.axiom.AxiomRemoval;
import de.fizkarlsruhe.ise.ontoboard.collab.OntologyOperation;
import de.fizkarlsruhe.ise.ontoboard.collab.OperationMapper;
import de.fizkarlsruhe.ise.ontoboard.git.InsideRepository;
import de.fizkarlsruhe.ise.ontoboard.odk.MakeRun;
import de.fizkarlsruhe.ise.ontoboard.odk.MakeTargets;
import de.fizkarlsruhe.ise.ontoboard.odk.Obsoletion;
import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseDiff;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * One test per fix that shipped without one.
 *
 * <p>An audit asked the question this project's own testing rules ask - "would this test still fail
 * if the code under test were deleted?" - of the fixes from the previous round, and found six with
 * nothing pinning them at all. A fix nobody can break by accident is a fix; a fix with no test is a
 * comment. These are deliberately small and each names the defect it holds shut.
 *
 * <p>They live together rather than in each class's own test because what they have in common is
 * the reason they exist. A future reader deciding whether one is redundant should read the defect
 * in its javadoc first.
 */
class RegressionPinsTest {

    private static final String NS = "http://example.org/o#";

    private static String utf8(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
    }

    // ---------------------------------------------------------------- the Build menu

    /**
     * Custom targets reach the Build menu.
     *
     * <p>{@code MakeTargets} read only the file named {@code Makefile} and ignored the
     * {@code -include} on its own first line - the line the scaffold writes and the README tells
     * the user to put their own rules behind. A target added there ran from the shell and was
     * invisible in the menu forever.
     */
    @Test
    void targetsFromAnIncludedMakefileReachTheMenu(@TempDir Path dir) throws Exception {
        File ontology = dir.toFile();
        Files.write(new File(ontology, "Makefile").toPath(), String.join("\n",
                "ONT := abc",
                "-include $(ONT).Makefile",
                ".PHONY: all",
                "all:",
                "\t@echo built").getBytes(Charset.forName("UTF-8")));
        Files.write(new File(ontology, "abc.Makefile").toPath(), String.join("\n",
                "publish_to_the_registry:",
                "\t@echo mine").getBytes(Charset.forName("UTF-8")));

        List<String> targets = MakeTargets.of(new File(ontology, "abc-edit.owl"));

        assertTrue(targets.contains("publish_to_the_registry"),
                "a target from the included Makefile is missing: " + targets);
        assertTrue(targets.contains("all"), targets.toString());
    }

    /** A missing include is not an error - make itself tolerates it. */
    @Test
    void aMissingIncludeIsTolerated(@TempDir Path dir) throws Exception {
        Files.write(new File(dir.toFile(), "Makefile").toPath(), String.join("\n",
                "ONT := abc",
                "-include $(ONT).Makefile",
                "all:",
                "\t@echo built").getBytes(Charset.forName("UTF-8")));

        assertTrue(MakeTargets.of(new File(dir.toFile(), "abc-edit.owl")).contains("all"));
    }

    /**
     * The Build menu offers targets in the curated order its own help text promises.
     *
     * <p>{@code MakeTargets.ordered} exists to put the useful ones first; the only menu that offers
     * targets never called it, and pre-selected whatever happened to be first in the file.
     */
    @Test
    void theBuildMenuOffersTheUsefulTargetsFirst(@TempDir Path dir) throws Exception {
        Files.write(new File(dir.toFile(), "Makefile").toPath(), String.join("\n",
                "zzz_housekeeping:",
                "\t@echo nope",
                "all:",
                "\t@echo built",
                "test:",
                "\t@echo tested").getBytes(Charset.forName("UTF-8")));

        List<String> targets = MakeRun.targetsFor(new File(dir.toFile(), "abc-edit.owl"));

        assertEquals("all", targets.get(0),
                "the first target offered is what the dialog pre-selects: " + targets);
        assertTrue(targets.indexOf("test") < targets.indexOf("zzz_housekeeping"), targets.toString());
    }

    // ---------------------------------------------------------------- obsoletion

    /**
     * Obsoleting a term declares the annotation properties it writes.
     *
     * <p>Without the declarations the edited ontology leaves OWL 2 DL, and OntoBoard's own profile
     * check then reports the violation OntoBoard has just introduced.
     */
    @Test
    void obsoletingDeclaresThePropertiesItWrites() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(NS + "Olive"))));

        manager.applyChanges(Obsoletion.obsolete(ontology, IRI.create(NS + "Olive"),
                IRI.create(NS + "Caper"), false, "too specific"));

        assertTrue(ontology.isDeclared(factory.getOWLAnnotationProperty(Obsoletion.CONSIDER)),
                "oboInOwl:consider was used without being declared");
        assertTrue(ontology.isDeclared(
                factory.getOWLAnnotationProperty(Obsoletion.OBSOLESCENCE_REASON)),
                "IAO:0000231 was used without being declared");
        assertNotNull(de.fizkarlsruhe.ise.ontoboard.reason.ProfileCheck.tightestProfile(ontology),
                "the ontology left OWL 2 DL because of the annotations obsoletion added");
    }

    /**
     * Obsoleting an object property retires the property, not an invented class of the same name.
     *
     * <p>The menu item takes whatever is selected. Treating a property as a class wrote
     * {@code Declaration(Class(P))} for an IRI that is a property, found no axioms to strip -
     * {@code OWLClass} does not equal {@code OWLObjectProperty} with the same IRI - and then
     * reported that the term had been retired and its axioms removed.
     */
    @Test
    void obsoletingAnObjectPropertyActuallyRetiresTheProperty() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        IRI iri = IRI.create(NS + "hasTopping");
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLObjectProperty(iri)));
        manager.addAxiom(ontology, factory.getOWLObjectPropertyDomainAxiom(
                factory.getOWLObjectProperty(iri), factory.getOWLClass(IRI.create(NS + "Pizza"))));

        manager.applyChanges(Obsoletion.obsolete(ontology, iri, null, false, "no longer used"));

        assertTrue(Obsoletion.isObsolete(ontology, iri));
        assertFalse(ontology.isDeclared(factory.getOWLClass(iri)),
                "a class declaration was invented for an IRI that is an object property");
        assertTrue(ontology.isDeclared(factory.getOWLObjectProperty(iri)),
                "the property lost its own declaration");
        assertTrue(ontology.getAxioms(
                        org.semanticweb.owlapi.model.AxiomType.OBJECT_PROPERTY_DOMAIN).isEmpty(),
                "the domain axiom about the property was left in place while the user was told "
                        + "it had been stripped");
    }

    // ---------------------------------------------------------------- the migration pointer

    /**
     * A release note says where a retired term went, whichever property recorded it.
     *
     * <p>The diff read only {@code IAO:0100001}, so every obsoletion recorded as a suggestion -
     * half the feature, and the half the dialog explains at length - lost its pointer entirely.
     */
    @Test
    void theReleaseNoteKeepsAConsiderPointer() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology before = manager.createOntology(IRI.create("http://example.org/before"));
        OWLOntology after = manager.createOntology(IRI.create("http://example.org/after"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        for (OWLOntology version : new OWLOntology[] {before, after}) {
            manager.addAxiom(version, factory.getOWLDeclarationAxiom(
                    factory.getOWLClass(IRI.create(NS + "Olive"))));
        }
        manager.applyChanges(Obsoletion.obsolete(after, IRI.create(NS + "Olive"),
                IRI.create(NS + "Caper"), false, "too specific"));

        String notes = ReleaseDiff.between(before, after).asReleaseNotes("v2", after);

        assertTrue(notes.contains("Caper"),
                "the notes do not say where the term went: " + notes);
        assertTrue(notes.contains("consider"),
                "a suggestion must not read as an exact replacement: " + notes);
    }

    // ---------------------------------------------------------------- hostile input

    /**
     * An edge id of nothing but delimiters is refused, not a crash.
     *
     * <p>Java's default split drops trailing empties, so {@code "|"} yields a zero-length array and
     * {@code parts[0]} threw {@code ArrayIndexOutOfBoundsException} past both callers' catch -
     * onto the Swing event thread from an inbound collaboration operation whose id a peer controls.
     */
    @Test
    void anEdgeIdOfOnlyDelimitersIsRefused() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("http://example.org/o"));

        for (String hostile : new String[] {"|", "||", "|||", "", "nonsense"}) {
            assertThrows(AxiomRemoval.UnknownEdgeException.class,
                    () -> AxiomRemoval.removalsFor(ontology, hostile),
                    "id '" + hostile + "' should be refused, not crash");
        }
    }

    /**
     * A remote annotation operation missing its fields is skipped, not a crash.
     *
     * <p>The bridge validates only an operation's id and type, so {@code data} can be anything or
     * nothing. Four fields were read and dereferenced unconditionally, which put a
     * {@code NullPointerException} on the event thread.
     */
    @Test
    void aRemoteAnnotationWithNoFieldsIsSkipped() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("http://example.org/o"));
        Map<String, Object> nothing = new LinkedHashMap<String, Object>();

        OperationMapper.Inbound inbound = OperationMapper.toChanges(
                new OntologyOperation("op-1", "updateAnnotation", 0L, "peer", nothing), ontology);

        assertFalse(inbound.isUnderstood(),
                "an annotation operation with no subject should be skipped");
        assertNotNull(inbound.getSkippedReason());
    }

    /** And one with a subject but no value, which is the other half of the same read. */
    @Test
    void aRemoteAnnotationWithNoValueIsSkipped() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("http://example.org/o"));
        Map<String, Object> partial = new LinkedHashMap<String, Object>();
        partial.put("iri", NS + "Pizza");
        partial.put("property", "http://www.w3.org/2000/01/rdf-schema#comment");

        OperationMapper.Inbound inbound = OperationMapper.toChanges(
                new OntologyOperation("op-2", "updateAnnotation", 0L, "peer", partial), ontology);

        assertFalse(inbound.isUnderstood(), "nothing to do is not an understood change");
    }

    // ---------------------------------------------------------------- pasted paths

    /**
     * A pasted blob URL cannot open a file outside the checkout.
     *
     * <p>The path came from the URL and went straight into {@code new File(checkout, path)}, so
     * {@code .../blob/main/../../../../other/project.owl} cloned one repository and opened an
     * ontology from somewhere else, while the result said "Opening the file the link pointed at."
     */
    @Test
    void aPastedPathCannotEscapeTheCheckout(@TempDir Path dir) throws Exception {
        File checkout = new File(dir.toFile(), "checkout");
        assertTrue(new File(checkout, "src/ontology").mkdirs());
        Files.write(new File(checkout, "src/ontology/abc-edit.owl").toPath(),
                "<rdf:RDF/>".getBytes(Charset.forName("UTF-8")));
        Files.write(new File(dir.toFile(), "elsewhere.owl").toPath(),
                "<rdf:RDF/>".getBytes(Charset.forName("UTF-8")));

        assertNotNull(InsideRepository.resolve(checkout, "src/ontology/abc-edit.owl"),
                "a path inside the checkout must still resolve");
        assertEquals(null, InsideRepository.resolve(checkout, "../elsewhere.owl"),
                "a path leaving the checkout must not resolve");
        assertEquals(null, InsideRepository.resolve(checkout,
                "src/ontology/../../../elsewhere.owl"),
                "normalisation must happen before the containment check");
        assertEquals(null, InsideRepository.resolve(checkout, null));
        assertEquals(null, InsideRepository.resolve(null, "anything"));
    }

    // ---------------------------------------------------------------- the generated build

    /**
     * What CI runs includes reasoning and the SPARQL check.
     *
     * <p>{@code test: report} alone meant an inconsistent ontology or an unsatisfiable class passed
     * QC green, in a job named QC, and the SPARQL file the scaffold writes was never executed by
     * anything.
     */
    @Test
    void whatCiRunsIncludesReasoningAndTheSparqlCheck(@TempDir Path dir) throws Exception {
        de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectConfig config =
                new de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectConfig("abc", "ABC", "",
                        "http://x.org/abc.owl",
                        "https://creativecommons.org/publicdomain/zero/1.0/", dir.toFile());
        de.fizkarlsruhe.ise.ontoboard.odk.OdkScaffold.create(config);
        File ontology = new File(config.getProjectRoot(), "src/ontology");

        String makefile = utf8(new File(ontology, "Makefile"));
        String testLine = null;
        for (String line : makefile.split("\r?\n")) {
            if (line.startsWith("test:")) {
                testLine = line;
            }
        }
        assertNotNull(testLine, makefile);
        assertTrue(testLine.contains("reason"),
                "CI must reason, or an inconsistent ontology passes: " + testLine);
        assertTrue(testLine.contains("sparql_test"),
                "the scaffolded SPARQL check must run: " + testLine);
        assertTrue(makefile.contains("robot verify"), makefile);
        assertTrue(makefile.contains(".DEFAULT_GOAL := all"),
                "without this the first target of an included Makefile becomes the default goal");

        String workflow = utf8(new File(config.getProjectRoot(), ".github/workflows/qc.yml"));
        assertTrue(workflow.contains("curl -fsSL"),
                "without --fail curl exits 0 on a missing asset and writes 'Not Found' into the "
                        + "jar: " + workflow);
        assertFalse(workflow.contains("releases/latest/download"),
                "the ROBOT version must be pinned, not 'latest': " + workflow);
        assertTrue(workflow.contains("upload-artifact"),
                "a failing run must publish the report that names the violations");
    }

    /** The changes a release writes reach the project root, where a PURL resolves. */
    @Test
    void theGitignoreKeepsTheReleaseAndDropsTheBuildProduct(@TempDir Path dir) throws Exception {
        de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectConfig config =
                new de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectConfig("abc", "ABC", "",
                        "http://x.org/abc.owl",
                        "https://creativecommons.org/publicdomain/zero/1.0/", dir.toFile());
        de.fizkarlsruhe.ise.ontoboard.odk.OdkScaffold.create(config);

        String ignored = utf8(new File(config.getProjectRoot(), ".gitignore"));

        boolean ignoresBuildProduct = false;
        for (String line : ignored.split("\r?\n")) {
            String rule = line.trim();
            if (rule.startsWith("#") || rule.isEmpty()) {
                continue;
            }
            assertFalse(rule.equals("abc.owl") || rule.equals("/abc.owl"),
                    "the release artefact at the project root must be committed: " + rule);
            ignoresBuildProduct |= rule.equals("src/ontology/abc.owl");
        }
        assertTrue(ignoresBuildProduct,
                "what `make reason` writes and `make clean` deletes must not be tracked: "
                        + ignored);
    }
}
