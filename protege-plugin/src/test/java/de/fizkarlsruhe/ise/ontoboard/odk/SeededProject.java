package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import de.fizkarlsruhe.ise.ontoboard.robot.QuestionTests;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A project as New ODK project makes it - ODK's seed, from ODK's templates inside OntoBoard, no
 * container and no git - for tests that edit it as a person would in Protege and then run ODK's
 * targets on it with OntoBoard's own engine.
 *
 * <p>The ontology a test holds stands for what Protege has open; {@link #save} writes it to the
 * edit file, which is what ODK's make reads.
 */
public final class SeededProject {

    /** Where the project's own terms are named. */
    public static final String NS = "https://example.org/eggboil/";

    /** The egg guide's sixth task: a dataset, the relation has part, and the question. */
    public static final IRI DATASET = IRI.create(NS + "EGGBOIL_0001004");
    public static final IRI DATUM = IRI.create(NS + "EGGBOIL_0001005");
    public static final IRI HAS_PART = IRI.create("http://purl.obolibrary.org/obo/BFO_0000051");
    public static final String QUESTION = "Every kind of egg boiling dataset has some has part?";

    private static final ProcessRunner.Runner NOTHING_INSTALLED = new ProcessRunner.Runner() {
        @Override
        public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
            throw new IOException("Cannot run program \"" + command.get(0) + "\"");
        }
    };

    private SeededProject() {
    }

    /**
     * A project of the test's own naming, made as New ODK project makes one on a machine
     * with nothing installed: what was written.
     */
    public static OdkCreate.Created create(OdkProjectConfig config) {
        return OdkCreate.create(config, null, NOTHING_INSTALLED, false);
    }

    /** Makes the project in a folder {@code eggboil} under {@code where}: its edit file. */
    public static File in(File where) {
        OdkProjectConfig config = new OdkProjectConfig("eggboil", "Test kitchen ontology",
                "Made by a test", NS + "eggboil.owl",
                "https://creativecommons.org/licenses/by/4.0/", where);
        OdkCreate.create(config, null, NOTHING_INSTALLED, false);
        return config.getEditFile();
    }

    /** The project's top folder. */
    public static File root(File edit) {
        return edit.getParentFile().getParentFile().getParentFile();
    }

    /** The edit file as Protege would have it open; read from its bytes, so no file is held. */
    public static OWLOntology open(File edit) throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                new StreamDocumentSource(new ByteArrayInputStream(
                        Files.readAllBytes(edit.toPath()))));
    }

    /** Saved, as the person saves before a build: ODK's make reads the file. */
    public static void save(OWLOntology ontology, File edit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ontology.getOWLOntologyManager().saveOntology(ontology,
                new FunctionalSyntaxDocumentFormat(), bytes);
        Files.write(edit.toPath(), bytes.toByteArray());
    }

    public static void label(OWLOntology ontology, IRI term, String label) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), term,
                df.getOWLLiteral(label)));
    }

    /** A test written from a question: the query, and the rule that asks the questions. */
    public static void question(File edit, String name, String sparql) throws IOException {
        Files.write(new File(root(edit), "src/sparql/" + name).toPath(),
                sparql.getBytes(StandardCharsets.UTF_8));
        OdkQuestions.ensure(root(edit));
    }

    /**
     * The egg guide's sixth task: the dataset and has part, labelled, and the test written;
     * with {@code answered}, the model says what the question asks for.
     */
    public static OWLOntology t6(File edit, boolean answered) throws Exception {
        OWLOntology ontology = open(edit);
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass dataset = df.getOWLClass(DATASET);
        OWLClass datum = df.getOWLClass(DATUM);
        OWLObjectProperty hasPart = df.getOWLObjectProperty(HAS_PART);
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(dataset));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(datum));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(hasPart));
        label(ontology, DATASET, "egg boiling dataset");
        label(ontology, HAS_PART, "has part");
        label(ontology, DATUM, "measurement datum");
        if (answered) {
            manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(dataset,
                    df.getOWLObjectSomeValuesFrom(hasPart, datum)));
        }
        save(ontology, edit);
        question(edit, QuestionTests.fileNameFor(QUESTION), QuestionTests.query(
                QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME, QUESTION, DATASET, HAS_PART,
                null, 1));
        return ontology;
    }

    /**
     * Adds what only a fuller reasoner than ELK sees: a value with a relation whose inverse
     * has a range the value cannot be in. Returns the class, labelled {@code label}.
     */
    public static OWLClass whatOnlyHermiTSees(OWLOntology ontology, String label) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass value = df.getOWLClass(IRI.create(NS + "EGGBOIL_0009000"));
        OWLClass gdc = df.getOWLClass(IRI.create(NS + "EGGBOIL_0009001"));
        OWLClass sdc = df.getOWLClass(IRI.create(NS + "EGGBOIL_0009002"));
        OWLClass quality = df.getOWLClass(IRI.create(NS + "EGGBOIL_0009003"));
        OWLObjectProperty qualityOf = df.getOWLObjectProperty(IRI.create(NS + "EGGBOIL_0009004"));
        OWLObjectProperty hasQuality = df.getOWLObjectProperty(IRI.create(NS + "EGGBOIL_0009005"));
        label(ontology, value.getIRI(), label);
        label(ontology, gdc.getIRI(), "generically dependent");
        label(ontology, sdc.getIRI(), "specifically dependent");
        label(ontology, quality.getIRI(), "quality");
        label(ontology, qualityOf.getIRI(), "quality of");
        label(ontology, hasQuality.getIRI(), "has quality");
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(value, gdc));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(quality, sdc));
        manager.addAxiom(ontology, df.getOWLDisjointClassesAxiom(gdc, sdc));
        manager.addAxiom(ontology, df.getOWLInverseObjectPropertiesAxiom(qualityOf, hasQuality));
        manager.addAxiom(ontology, df.getOWLObjectPropertyRangeAxiom(hasQuality, quality));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(value,
                df.getOWLObjectSomeValuesFrom(qualityOf, df.getOWLThing())));
        return value;
    }

    /** Runs one of ODK's targets on the project, by OntoBoard itself. */
    public static OdkEngine.Outcome run(File edit, String goal) {
        return OdkEngine.run(edit, goal, Collections.<String, String>emptyMap(), null);
    }
}
