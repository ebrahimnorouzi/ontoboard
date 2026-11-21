package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;

/**
 * Check this ontology, without a project: the reasoner, ROBOT's quality report with ODK's
 * default profile and the label check, on whatever is open - each with its verdict in a line,
 * nothing written. The testers of 1.132.0 asked for it: "Today Build needs a project."
 */
class LooseCheckTest {

    private static final String EGG = "https://example.org/eggboil/";

    /** The egg's edit file by itself, as somebody opens a loose .owl file. */
    private static OWLOntology egg() throws Exception {
        byte[] bytes = UpdateCases.before("egg-changed").get("src/ontology/eggboil-edit.owl");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        // What it imports is not there: it is checked alone, and nothing is fetched.
        final IRI nowhere = IRI.create(new File("target/no-such-import.owl").getAbsoluteFile());
        manager.getIRIMappers().add(iri -> nowhere);
        manager.setOntologyLoaderConfiguration(manager.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        return manager.loadOntologyFromOntologyDocument(new StreamDocumentSource(
                new ByteArrayInputStream(bytes), IRI.create(EGG + "eggboil-edit.owl")));
    }

    private static OWLClass egg(OWLOntology ontology, String number) {
        return ontology.getOWLOntologyManager().getOWLDataFactory()
                .getOWLClass(IRI.create(EGG + "EGGBOIL_" + number));
    }

    private static List<String> checks(LooseCheck.Result result) {
        List<String> names = new ArrayList<String>();
        for (LooseCheck.Line line : result.lines()) {
            names.add(line.check());
        }
        return names;
    }

    @Test
    void theEggAlonePassesEachCheck() throws Exception {
        LooseCheck.Result result = LooseCheck.run(egg(), LooseCheck.Options.defaults());

        assertEquals(Arrays.asList("Reasoner", "Quality report", "Labels"), checks(result));
        for (LooseCheck.Line line : result.lines()) {
            assertTrue(line.passed(), line.check() + ": " + line.verdict());
        }
        assertTrue(result.passed());
        assertTrue(result.lines().get(0).verdict().contains("HermiT"),
                result.lines().get(0).verdict());
        assertTrue(result.lines().get(1).verdict().contains("ODK's default profile"),
                result.lines().get(1).verdict());
    }

    @Test
    void anUnsatisfiableClassIsNamedOnTheReasonersLine() throws Exception {
        OWLOntology ontology = egg();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLClass impossible = egg(ontology, "0000098");
        manager.addAxiom(ontology, factory.getOWLDisjointClassesAxiom(egg(ontology, "0000001"),
                egg(ontology, "0000002")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(impossible,
                egg(ontology, "0000001")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(impossible,
                egg(ontology, "0000002")));
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), impossible.getIRI(),
                factory.getOWLLiteral("egg that is a boiling", "en")));

        LooseCheck.Result result = LooseCheck.run(ontology, LooseCheck.Options.defaults());

        LooseCheck.Line reasoner = result.lines().get(0);
        assertFalse(reasoner.passed(), reasoner.verdict());
        assertTrue(reasoner.verdict().contains("egg that is a boiling"), reasoner.verdict());
        assertFalse(result.passed());
    }

    @Test
    void aClassWithoutALabelIsNamedOnTheLabelsLine() throws Exception {
        OWLOntology ontology = egg();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        manager.addAxiom(ontology, manager.getOWLDataFactory().getOWLSubClassOfAxiom(
                egg(ontology, "0000099"), egg(ontology, "0000001")));

        LooseCheck.Result result = LooseCheck.run(ontology, LooseCheck.Options.defaults());

        LooseCheck.Line labels = result.lines().get(2);
        assertFalse(labels.passed(), labels.verdict());
        assertTrue(labels.verdict().contains("EGGBOIL_0000099"), labels.verdict());
        assertTrue(result.lines().get(0).passed(), "the reasoner has nothing against it");
    }

    @Test
    void anErrorOfTheProfileFailsTheReportAndNamesItsRule() throws Exception {
        OWLOntology ontology = egg();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (annotation.getProperty().getIRI().toString()
                    .equals("http://purl.org/dc/terms/license")) {
                manager.applyChange(new RemoveOntologyAnnotation(ontology, annotation));
            }
        }

        LooseCheck.Result result = LooseCheck.run(ontology, LooseCheck.Options.defaults());

        LooseCheck.Line report = result.lines().get(1);
        assertFalse(report.passed(), report.verdict());
        assertTrue(report.verdict().contains("missing_ontology_license"), report.verdict());
    }

    /**
     * Found by hand in 1.154.0's release check: in a project with a PMDco pattern imported,
     * Check this ontology... failed its quality report on eight errors of PMDco's own terms,
     * while Build, Quality report... and ODK's test - which ask with the project's profile and
     * count only the project's own terms - passed the same project. In a project the report is
     * asked as the project asks it; a file in no project is asked as before.
     */
    @Test
    void inAProjectTheReportCountsTheProjectsOwnTermsWithItsProfile(
            @org.junit.jupiter.api.io.TempDir File temp) throws Exception {
        File project = new File(temp, "eggboil");
        for (java.util.Map.Entry<String, byte[]> file : UpdateCases.eggProject().entrySet()) {
            File target = new File(project, file.getKey());
            assertTrue(target.getParentFile().isDirectory() || target.getParentFile().mkdirs());
            java.nio.file.Files.write(target.toPath(), file.getValue());
        }
        File edit = new File(project, "src/ontology/eggboil-edit.owl");
        assertTrue(new File(edit.getParentFile(), "profile.txt").isFile(), "the seed's profile");
        OWLOntology ontology = egg();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        // A term of another ontology, as an import brings it, with two labels: ROBOT's
        // multiple_labels, an error - about a term that is not the project's.
        OWLClass imported = factory.getOWLClass(IRI.create("https://w3id.org/pmd/co/PMD_0000638"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(imported));
        for (String label : Arrays.asList("fatigue testing process", "fatigue test")) {
            manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                    factory.getRDFSLabel(), imported.getIRI(),
                    factory.getOWLLiteral(label, "en")));
        }

        LooseCheck.Line asALooseFile = LooseCheck.run(ontology,
                LooseCheck.Options.defaults().withoutSecondOpinion()).lines().get(1);
        LooseCheck.Line inItsProject = LooseCheck.run(ontology,
                LooseCheck.Options.forFile(edit).withoutSecondOpinion()).lines().get(1);

        assertFalse(asALooseFile.passed(), asALooseFile.verdict());
        assertTrue(asALooseFile.verdict().contains("multiple_labels"), asALooseFile.verdict());
        assertTrue(inItsProject.passed(), inItsProject.verdict());
        assertTrue(inItsProject.verdict().contains("profile.txt")
                && inItsProject.verdict().contains("own terms"), inItsProject.verdict());
    }

    // ------------------------------------------------------------------ the reasoner (1.155.0)

    /** The egg project's contradiction in one file: only a reasoner reading inverses sees it. */
    private static OWLOntology inconsistentEgg() throws Exception {
        return org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new org.semanticweb.owlapi.io.StringDocumentSource(
                        "Prefix(obo:=<http://purl.obolibrary.org/obo/>)\n"
                        + "Ontology(<http://purl.obolibrary.org/obo/egg.owl>\n"
                        + "SubClassOf(obo:BFO_0000019 obo:BFO_0000020)\n"
                        + "DisjointClasses(obo:BFO_0000020 obo:BFO_0000031)\n"
                        + "SubClassOf(obo:IAO_0000003 obo:BFO_0000031)\n"
                        + "InverseObjectProperties(obo:IAO_0000039 obo:IAO_0000040)\n"
                        + "ObjectPropertyRange(obo:IAO_0000039 obo:IAO_0000003)\n"
                        + "SubClassOf(obo:EGG_0000001 obo:BFO_0000019)\n"
                        + "SubClassOf(obo:EGG_0000001 ObjectSomeValuesFrom(obo:IAO_0000040 "
                        + "obo:EGG_0000000))\n"
                        + "ClassAssertion(obo:EGG_0000001 obo:EGG_0000002)\n)\n"));
    }

    /** The user, 2026-11-19: "check this ontology, is not offering the choice for reasoner". */
    @Test
    void theReasonerIsTheOneChosen() throws Exception {
        LooseCheck.Result result = LooseCheck.run(egg(),
                LooseCheck.Options.defaults().withReasoner("JFact"));

        assertTrue(result.lines().get(0).verdict().startsWith("JFact"),
                result.lines().get(0).verdict());
        assertEquals("JFact", LooseCheck.Options.defaults().withReasoner("JFact").reasoner());
    }

    @Test
    void aContradictionOnlyHermiTSeesFailsWithHermiT() throws Exception {
        LooseCheck.Line hermit = LooseCheck.run(inconsistentEgg(),
                LooseCheck.Options.defaults().withReasoner("HermiT")).lines().get(0);

        assertFalse(hermit.passed(), hermit.verdict());
        assertTrue(hermit.verdict().contains("inconsistent"), hermit.verdict());
        assertTrue(String.join("\n", hermit.details()).contains("InverseOf"),
                hermit.details().toString());
    }

    @Test
    void elkPassesItAndHermiTsSecondOpinionSaysWhatElkMissed() throws Exception {
        LooseCheck.Line elk = LooseCheck.run(inconsistentEgg(),
                LooseCheck.Options.defaults().withReasoner("ELK")).lines().get(0);

        assertTrue(elk.passed(), elk.verdict());
        assertTrue(elk.verdict().startsWith("ELK"), elk.verdict());
        assertTrue(elk.verdict().contains("HermiT"), elk.verdict());
    }

    @Test
    void aFileInNoProjectPreselectsHermiT(@org.junit.jupiter.api.io.TempDir java.io.File top)
            throws Exception {
        java.io.File alone = new java.io.File(top, "pizza.owl");
        java.nio.file.Files.write(alone.toPath(), "Ontology()\n".getBytes("UTF-8"));

        assertEquals("HermiT", LooseCheck.Options.forFile(alone).reasoner());
        assertEquals("HermiT", LooseCheck.Options.forFile(null).reasoner());
    }
}
