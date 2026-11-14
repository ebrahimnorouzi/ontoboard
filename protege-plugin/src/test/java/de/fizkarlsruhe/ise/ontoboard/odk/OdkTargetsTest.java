package de.fizkarlsruhe.ise.ontoboard.odk;

import static de.fizkarlsruhe.ise.ontoboard.odk.SeededProject.DATASET;
import static de.fizkarlsruhe.ise.ontoboard.odk.SeededProject.NS;
import static de.fizkarlsruhe.ise.ontoboard.odk.SeededProject.label;
import static de.fizkarlsruhe.ise.ontoboard.odk.SeededProject.open;
import static de.fizkarlsruhe.ise.ontoboard.odk.SeededProject.question;
import static de.fizkarlsruhe.ise.ontoboard.odk.SeededProject.run;
import static de.fizkarlsruhe.ise.ontoboard.odk.SeededProject.save;
import static de.fizkarlsruhe.ise.ontoboard.odk.SeededProject.t6;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.QuestionTests;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * What a run of ODK's targets says to the person who started it, beyond make's own last line.
 *
 * <p>Each sentence here was first written for the build OntoBoard ran for its own projects, up
 * to 1.135, after somebody was stuck without it: a failing check named neither its rule nor the
 * term; a failing question sent people to add a label nobody was missing; a test about a deleted
 * class failed every build after; "Reason failed: null". The project is ODK's now and the run
 * is ODK's Makefile's - here by OntoBoard itself, on a project as New ODK project makes it
 * ({@link SeededProject}) - and the sentences are said of that run.
 */
class OdkTargetsTest {

    // ------------------------------------------------------------------ a question

    @Test
    void aQuestionTheModelDoesNotAnswerYetSaysSoAndHowToAnswerIt(@TempDir File where)
            throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = t6(edit, false);

        OdkEngine.Outcome ran = run(edit, "question_test");

        assertFalse(ran.isOk(), String.join("\n", ran.getTranscript()));
        String why = OdkTargets.whyItStopped(ran.getTranscript(), edit, ontology);
        assertTrue(why.startsWith("every_kind_of_egg_boiling_dataset_has_some_has_part.rq "
                + "(Every kind of egg boiling dataset has some has part?): egg boiling dataset. "
                + "The model does not answer this question yet."), why);
        assertTrue(why.contains("right-click egg boiling dataset on the board"), why);
        assertTrue(why.contains("Relate to another term"), why);
        assertTrue(why.contains("set Property to has part"), why);
        assertFalse(why.contains("missing label"), why);
        assertFalse(why.contains("Annotations"), why);
        assertFalse(why.contains(DATASET.toString()), "named by its label: " + why);
        assertTrue(String.join("\n", ran.getTranscript()).contains(DATASET.toString()),
                "the log keeps the IRI");
        assertEquals("question_test", OdkTargets.stoppedAt(ran.getTranscript()));
    }

    @Test
    void onceTheModelAnswersTheQuestionPassesAndIsNamed(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = t6(edit, true);

        OdkEngine.Outcome ran = run(edit, "test_fast");

        assertTrue(ran.isOk(), String.valueOf(ran.getFailure()));
        List<String> passed = OdkTargets.passed(ran.getTranscript());
        assertTrue(passed.contains("every_kind_of_egg_boiling_dataset_has_some_has_part.rq"),
                passed.toString());
        assertTrue(passed.contains("check_labels-violation.sparql"), passed.toString());
        assertEquals(new ArrayList<OdkTargets.Failed>(), OdkTargets.failed(ran.getTranscript(),
                edit.getParentFile()));
        assertNull(OdkTargets.whyItStopped(ran.getTranscript(), edit, ontology));
        assertNull(OdkTargets.stoppedAt(ran.getTranscript()));
        assertTrue(OdkTargets.reasonedWithElk(ran.getTranscript()), "ODK's test reasons with ELK");
    }

    /**
     * A question that asks for something in particular - a tensile test has some input that is
     * a bar - is not answered by a class above taking something broader, and the advice names
     * the particular thing.
     */
    @Test
    void aQuestionWithAFillerNamesTheFillerInItsAdvice(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = open(edit);
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLObjectProperty hasInput = df.getOWLObjectProperty(IRI.create(NS + "EGGBOIL_0002000"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(hasInput));
        label(ontology, hasInput.getIRI(), "has input");
        String[][] named = {{"EGGBOIL_0002001", "process"}, {"EGGBOIL_0002002", "tensile test"},
            {"EGGBOIL_0002003", "piece"}, {"EGGBOIL_0002004", "bar"}};
        for (String[] one : named) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                    df.getOWLClass(IRI.create(NS + one[0]))));
            label(ontology, IRI.create(NS + one[0]), one[1]);
        }
        OWLClass process = df.getOWLClass(IRI.create(NS + "EGGBOIL_0002001"));
        OWLClass test = df.getOWLClass(IRI.create(NS + "EGGBOIL_0002002"));
        OWLClass piece = df.getOWLClass(IRI.create(NS + "EGGBOIL_0002003"));
        OWLClass bar = df.getOWLClass(IRI.create(NS + "EGGBOIL_0002004"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(test, process));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(bar, piece));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(process,
                df.getOWLObjectSomeValuesFrom(hasInput, piece)));
        save(ontology, edit);
        String asked = "Every kind of tensile test has some has input that is a bar?";
        question(edit, QuestionTests.fileNameFor(asked), QuestionTests.query(
                QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME_THAT_IS, asked, test.getIRI(),
                hasInput.getIRI(), bar.getIRI(), 0));

        OdkEngine.Outcome red = run(edit, "question_test");

        assertFalse(red.isOk(), String.join("\n", red.getTranscript()));
        String why = OdkTargets.whyItStopped(red.getTranscript(), edit, ontology);
        assertTrue(why.contains(QuestionTests.fileNameFor(asked)), why);
        assertTrue(why.contains("does not answer this question yet"), why);
        assertTrue(why.contains("right-click tensile test on the board"), why);
        assertTrue(why.contains("pick bar"), why);
        assertTrue(why.contains("set Property to has input"), why);

        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(test,
                df.getOWLObjectSomeValuesFrom(hasInput, bar)));
        save(ontology, edit);
        OdkEngine.Outcome green = run(edit, "question_test");
        assertTrue(green.isOk(), String.valueOf(green.getFailure()));
    }

    /**
     * A question about a value - every egg has some mass - is not answered by an arrow on
     * the board: a value's restriction is written in the Classes tab, and the advice says
     * that.
     */
    @Test
    void aQuestionAboutAValueIsAnsweredInTheClassesTab(@TempDir File where) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(NS + "eggboil.owl"));
        OWLDataFactory df = manager.getOWLDataFactory();
        IRI egg = IRI.create(NS + "EGGBOIL_0006001");
        IRI mass = IRI.create(NS + "EGGBOIL_0006002");
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(df.getOWLClass(egg)));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(df.getOWLDataProperty(mass)));
        label(ontology, egg, "egg");
        label(ontology, mass, "has mass");
        File asked = new File(where, "every_kind_of_egg_has_some_has_mass.rq");
        Files.write(asked.toPath(), QuestionTests.query(
                QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME,
                "Every kind of egg has some has mass?", egg, mass, null, 0)
                .getBytes(StandardCharsets.UTF_8));

        assertEquals("The model does not answer this question yet. To make it answer, add "
                + "'has mass some' and the value's type (such as xsd:decimal) to egg's "
                + "SubClass Of, in Protege's Classes tab; then save and build again.",
                OdkTargets.adviceFor(asked, ontology));
    }

    // ------------------------------------------------------------------ the label check

    /**
     * The most ordinary first edit there is: a class added with Protege's defaults has no
     * label. The failure names the rule and the term, and says where a label is added.
     */
    @Test
    void anUnlabelledClassFailsNamingTheRuleAndTheTermAndWhereALabelIsAdded(
            @TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = open(edit);
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass unnamed = df.getOWLClass(IRI.create(NS + "EGGBOIL_0003001"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(unnamed,
                df.getOWLClass(IRI.create(NS + "EGGBOIL_0000000"))));
        save(ontology, edit);

        OdkEngine.Outcome ran = run(edit, "sparql_test");

        assertFalse(ran.isOk(), String.join("\n", ran.getTranscript()));
        String why = OdkTargets.whyItStopped(ran.getTranscript(), edit, ontology);
        assertEquals("check_labels-violation.sparql (Every class this ontology describes "
                + "carries an rdfs:label.): EGGBOIL_0003001. Each listed term breaks the rule "
                + "beside it: fix the term (a missing label is added in Protege under "
                + "Annotations), or edit the check in src/sparql if the rule does not apply to "
                + "this project.", why);
        assertEquals("sparql_test", OdkTargets.stoppedAt(ran.getTranscript()));
    }

    /** The label check of a project carried over from 1.135 is a question, under its old name. */
    @Test
    void theOlderLabelCheckAndAQuestionAboutLabelsGetTheSameAdvice(@TempDir File where)
            throws Exception {
        File old = new File(where, "check_labels.rq");
        Files.write(old.toPath(), "SELECT ?class WHERE { ?class a ?x }\n".getBytes(
                StandardCharsets.UTF_8));
        File hand = new File(where, "mine.rq");
        Files.write(hand.toPath(), "SELECT ?class WHERE { ?class a ?x }\n".getBytes(
                StandardCharsets.UTF_8));

        File asked = new File(where, "every_egg_and_every_kind_of_it_has_a_label.rq");
        Files.write(asked.toPath(), QuestionTests.query(
                QuestionTests.Template.EVERY_ONE_HAS_A_LABEL,
                "Every egg, and every kind of it, has a label?",
                IRI.create(NS + "EGGBOIL_0000001"), null, null, 0)
                .getBytes(StandardCharsets.UTF_8));

        assertTrue(OdkTargets.adviceFor(old, null).contains("a missing label is added in "
                + "Protege under Annotations"));
        assertTrue(OdkTargets.adviceFor(asked, null).contains("a missing label is added in "
                + "Protege under Annotations"), "a question about labels is answered by a label");
        assertEquals("Each listed term breaks the rule beside it: change the model so it does "
                + "not, or edit the check in src/sparql if the rule does not apply to this "
                + "project.", OdkTargets.adviceFor(hand, null));
    }

    /**
     * One of ODK's own checks. Its file has no comment that says its rule, so the rule is
     * said for it; and the file is ODK's, written again by every update of the project's
     * files - "edit the check" would be advice that does not last.
     */
    @Test
    void aCheckOfOdksOwnSaysItsRuleAndIsNotToBeEdited(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = open(edit);
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        IRI egg = IRI.create(NS + "EGGBOIL_0008001");
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(df.getOWLClass(egg)));
        label(ontology, egg, "boiled egg, see https://example.org/eggs");
        save(ontology, edit);

        OdkEngine.Outcome ran = run(edit, "sparql_test");

        assertFalse(ran.isOk(), String.join("\n", ran.getTranscript()));
        assertEquals("label-with-iri-violation.sparql (No label contains a web address.): "
                + "boiled egg, see https://example.org/eggs. Each listed term breaks that "
                + "rule: fix the term, or, if the rule does not apply to this project, take "
                + "the check's name out of custom_sparql_checks in the project's "
                + "<id>-odk.yaml and run OntoBoard > Project > Update project files... - the "
                + "check's file is ODK's own and is written again by every update, so an "
                + "edit to it does not last.",
                OdkTargets.whyItStopped(ran.getTranscript(), edit, ontology));
    }

    // ------------------------------------------------------------------ a test about nothing

    @Test
    void aTermTheOntologyDoesNotHaveIsNamed() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology(
                IRI.create(NS + "eggboil.owl"));
        String gone = NS + "EGGBOIL_0009999";

        assertEquals(gone, OdkTargets.goneFrom(ontology,
                Collections.singletonList(Arrays.asList(gone, ""))));
        assertNull(OdkTargets.goneFrom(ontology,
                Collections.singletonList(Arrays.asList("a literal", ""))));
        assertNull(OdkTargets.goneFrom(ontology, Collections.<List<String>>emptyList()));
    }

    /** A test written, as tests were until 1.135.1, on a class that was then deleted. */
    @Test
    void theBuildSaysToDeleteATestAboutNothing(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = open(edit);
        String gone = NS + "EGGBOIL_0009999";
        question(edit, "every_kind_of_gone_has_a_label.rq",
                "# Every Gone, and every kind of it, has a label?\n"
                        + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                        + "SELECT DISTINCT ?term WHERE {\n"
                        + "  { ?term rdfs:subClassOf* <" + gone + "> . FILTER(isIRI(?term)) }\n"
                        + "  FILTER NOT EXISTS { ?term rdfs:label ?label }\n"
                        + "}\n");

        OdkEngine.Outcome ran = run(edit, "question_test");

        assertFalse(ran.isOk(), String.join("\n", ran.getTranscript()));
        String why = OdkTargets.whyItStopped(ran.getTranscript(), edit, ontology);
        assertTrue(why.contains(gone + " is not in the ontology any more"), why);
        assertTrue(why.endsWith("the test is about nothing: delete src/sparql/"
                + "every_kind_of_gone_has_a_label.rq."), why);
    }

    /**
     * A question that cannot be read fails the build. (In the build OntoBoard ran for its
     * own projects, a query with a syntax error once counted as no violations, and passed.)
     */
    @Test
    void aQuestionThatCannotBeReadFailsTheBuild(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        question(edit, "broken.rq", "SELECT ?x WHERE { this is not sparql");

        OdkEngine.Outcome ran = run(edit, "question_test");

        assertFalse(ran.isOk(), "a check that cannot run must not pass");
        assertEquals("question_test", OdkTargets.stoppedAt(ran.getTranscript()));
        assertTrue(String.join("\n", ran.getTranscript()).contains("broken.rq"),
                String.join("\n", ran.getTranscript()));
    }

    // ------------------------------------------------------------------ the rule of a check

    @Test
    void theRuleIsReadFromTheQuerysLeadingComment(@TempDir File where) throws Exception {
        File query = new File(where, "check.rq");
        Files.write(query.toPath(), ("# Every class needs a label.\n#   Second line.\n"
                + "SELECT ?x WHERE { ?x ?y ?z }\n").getBytes(StandardCharsets.UTF_8));
        assertEquals("Every class needs a label. Second line.", OdkTargets.ruleOf(query));

        Files.write(query.toPath(), "SELECT ?x WHERE { ?x ?y ?z }\n".getBytes(
                StandardCharsets.UTF_8));
        assertNull(OdkTargets.ruleOf(query));
        assertNull(OdkTargets.ruleOf(new File(where, "not-there.rq")));
        assertNull(OdkTargets.ruleOf(null));
    }

    /**
     * A comment that goes on after an empty comment line is for whoever opens the file: the
     * rule is its first paragraph. The label check ODK's test runs has seven lines of comment.
     */
    @Test
    void theRuleIsTheFirstParagraphOfALongerComment(@TempDir File where) throws Exception {
        File query = new File(where, "check-violation.sparql");
        Files.write(query.toPath(), ("# Every class carries a label.\n#\n# An ODK check: make "
                + "runs it on the edit file.\n# And more.\nSELECT ?x WHERE { ?x ?y ?z }\n")
                .getBytes(StandardCharsets.UTF_8));

        assertEquals("Every class carries a label.", OdkTargets.ruleOf(query));
    }

    // ------------------------------------------------------------------ what make said

    /** As ROBOT in ODK's container says it: the rows under the line, as CSV. */
    @Test
    void aFailedCheckIsReadFromWhatMakeSaid(@TempDir File ontologyDirectory) {
        List<String> transcript = Arrays.asList(
                "robot --catalog catalog-v001.xml verify -i tmp/merged-egg-edit.ofn --queries "
                        + "../sparql/a-violation.sparql ../sparql/b-violation.sparql -O reports",
                "PASS Rule ../sparql/a-violation.sparql: 0 violation(s)",
                "FAIL Rule ../sparql/b-violation.sparql: 2 violation(s)",
                "term,label",
                "https://example.org/t1,\"salt, coarse\"",
                "https://example.org/t2,\"a \"\"quoted\"\" name\"",
                "make: *** [Makefile:269: sparql_test] Error 1");

        List<OdkTargets.Failed> failed = OdkTargets.failed(transcript, ontologyDirectory);

        assertEquals(1, failed.size(), failed.toString());
        OdkTargets.Failed check = failed.get(0);
        assertEquals("b-violation.sparql", check.name());
        assertEquals(new File(new File(ontologyDirectory.getParentFile(), "sparql"),
                "b-violation.sparql").getAbsoluteFile().toPath().normalize(),
                check.file().getAbsoluteFile().toPath().normalize());
        assertEquals(2, check.violations());
        assertEquals(Arrays.asList("term", "label"), check.columns());
        assertEquals(Arrays.asList(Arrays.asList("https://example.org/t1", "salt, coarse"),
                Arrays.asList("https://example.org/t2", "a \"quoted\" name")), check.rows());
        assertEquals(Collections.singletonList("a-violation.sparql"),
                OdkTargets.passed(transcript));
        assertEquals("sparql_test", OdkTargets.stoppedAt(transcript));
        assertEquals("b-violation.sparql: salt, coarse, a \"quoted\" name. Each listed term "
                + "breaks the rule beside it: change the model so it does not, or edit the "
                + "check in src/sparql if the rule does not apply to this project.",
                OdkTargets.whyItStopped(transcript, new File(ontologyDirectory, "egg-edit.owl"),
                        null));
    }

    /** Where nothing follows the line, the rows are those ROBOT left under reports/. */
    @Test
    void theRowsAreReadFromTheReportWhereTheRunDidNotSayThem(@TempDir File ontologyDirectory)
            throws Exception {
        File reports = new File(ontologyDirectory, "reports");
        assertTrue(reports.mkdirs());
        Files.write(new File(reports, "b-violation.csv").toPath(),
                "term\nhttps://example.org/t1\nhttps://example.org/t2\nhttps://example.org/t3\n"
                        .getBytes(StandardCharsets.UTF_8));
        List<String> transcript = Arrays.asList(
                "FAIL Rule ../sparql/b-violation.sparql: 3 violation(s)",
                "make[1]: *** [Makefile:269: sparql_test] Error 1",
                "make: *** [Makefile:116: test_fast] Error 2");

        List<OdkTargets.Failed> failed = OdkTargets.failed(transcript, ontologyDirectory);

        assertEquals(Collections.singletonList("term"), failed.get(0).columns());
        assertEquals(3, failed.get(0).rows().size());
        assertEquals("sparql_test", OdkTargets.stoppedAt(transcript), "where the make that was "
                + "deepest stopped, not the one that started it");
    }

    /** A check that lists more terms than are named counts the rest: they are in the log. */
    @Test
    void moreTermsThanAreNamedAreCounted(@TempDir File ontologyDirectory) {
        List<String> transcript = new ArrayList<String>();
        transcript.add("FAIL Rule ../sparql/b-violation.sparql: 12 violation(s)");
        transcript.add("term");
        for (int i = 1; i <= 12; i++) {
            transcript.add("https://example.org/t" + i);
        }
        transcript.add("make: *** [Makefile:269: sparql_test] Error 1");

        String why = OdkTargets.whyItStopped(transcript,
                new File(ontologyDirectory, "egg-edit.owl"), null);

        assertTrue(why.startsWith("b-violation.sparql: https://example.org/t1, "
                + "https://example.org/t2, "), why);
        assertTrue(why.contains("https://example.org/t9, https://example.org/t10 and 2 more. "
                + "Each listed term breaks the rule beside it"), why);
        assertFalse(why.contains("https://example.org/t11"), why);
    }

    @Test
    void aRunThatStoppedForSomethingElseIsNotExplainedHere() {
        List<String> transcript = Arrays.asList("robot merge --input x.owl",
                "x.owl: No such file or directory",
                "make: *** [Makefile:300: x.owl] Error 1");

        assertNull(OdkTargets.whyItStopped(transcript, new File("egg-edit.owl"), null));
        assertEquals("x.owl", OdkTargets.stoppedAt(transcript));
        assertFalse(OdkTargets.reasonedWithElk(transcript));
    }

    // ------------------------------------------------------------------ whose test it is

    /**
     * A summary has room for a few names. ODK's own checks pass in every project and say
     * nothing about what the person did; the project's own are its questions and each check
     * ODK does not ship a query for - the label check OntoBoard adds among them.
     */
    @Test
    void theProjectsOwnTestsAreItsQuestionsAndTheChecksOdkDoesNotShip() {
        assertEquals(Arrays.asList("check_labels-violation.sparql", "mine-violation.sparql",
                "every_kind_of_x.rq"), OdkTargets.ownAmong(Arrays.asList(
                "owldef-self-reference-violation.sparql", "iri-range-violation.sparql",
                "check_labels-violation.sparql", "label-with-iri-violation.sparql",
                "multiple-replaced_by-violation.sparql", "dc-properties-violation.sparql",
                "mine-violation.sparql", "every_kind_of_x.rq")));
        assertEquals(new ArrayList<String>(), OdkTargets.ownAmong(
                Collections.<String>emptyList()));
    }

    // ------------------------------------------------------------------ the profile

    /**
     * ODK's test ends with the OWL 2 DL check. A count on a transitive relation - has part,
     * exactly two - is the usual way out of the profile; ELK passes over it, the report does
     * not look for it, and make's last line is whatever the check's report ends with.
     */
    @Test
    void whatTakesTheOntologyOutOfOwl2DlIsCountedAndTheFirstNamed(@TempDir File where)
            throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = t6(edit, true);
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLObjectProperty hasPart = df.getOWLObjectProperty(SeededProject.HAS_PART);
        manager.addAxiom(ontology, df.getOWLTransitiveObjectPropertyAxiom(hasPart));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(df.getOWLClass(DATASET),
                df.getOWLObjectMaxCardinality(2, hasPart, df.getOWLThing())));
        save(ontology, edit);

        OdkEngine.Outcome ran = run(edit, "test");

        assertFalse(ran.isOk(), String.join("\n", ran.getTranscript()));
        String why = OdkTargets.whyItStopped(ran.getTranscript(), edit, ontology);
        assertEquals("The ontology is not in OWL 2 DL, which ODK's test asks of it - 1 "
                + "violation: Use of non-simple property in a restriction: "
                + "ObjectMaxCardinality(2 has part owl:Thing). The list is in "
                + "reports/validate_profile_owl2dl_eggboil.owl.txt, and OntoBoard > Check and "
                + "test > Profile... names the axioms that take the ontology out of the "
                + "profile.", why, String.join("\n", ran.getTranscript()));
        assertEquals("reports/validate_profile_owl2dl_eggboil.owl.txt",
                OdkTargets.stoppedAt(ran.getTranscript()));
    }

    /** As ROBOT says it; several are counted, and the first is named as the run named it. */
    @Test
    void severalThingsOutsideTheProfileAreCountedAndTheFirstNamed() {
        List<String> transcript = Arrays.asList(
                "robot --catalog catalog-v001.xml validate-profile --profile DL -i "
                        + "tmp/validate.ofn -o reports/validate_profile_owl2dl_egg.owl.txt || "
                        + "{ cat reports/validate_profile_owl2dl_egg.owl.txt && exit 1; }",
                "PROFILE VIOLATION ERROR https://example.org/egg.owl violates profile DL",
                "OWL 2 DL Profile Report: Ontology and imports closure NOT in profile. The "
                        + "following violations are present:",
                "Use of undeclared class: <https://example.org/A> [SubClassOf("
                        + "<https://example.org/A> <https://example.org/B>) in OntologyID("
                        + "OntologyIRI(<https://example.org/egg.owl>))]",
                "Use of undeclared class: <https://example.org/B> [SubClassOf("
                        + "<https://example.org/A> <https://example.org/B>) in OntologyID("
                        + "OntologyIRI(<https://example.org/egg.owl>))]",
                "",
                "make: *** [Makefile:251: reports/validate_profile_owl2dl_egg.owl.txt] Error 1");

        assertEquals("The ontology is not in OWL 2 DL, which ODK's test asks of it - 2 "
                + "violations, the first: Use of undeclared class: <https://example.org/A>. "
                + "The list is in reports/validate_profile_owl2dl_egg.owl.txt, and OntoBoard > "
                + "Check and test > Profile... names the axioms that take the ontology out "
                + "of the profile.",
                OdkTargets.whyItStopped(transcript, new File("egg-edit.owl"), null));
    }

    /** A profile the ontology is in says nothing. */
    @Test
    void anOntologyInTheProfileIsNotSpokenOf() {
        List<String> transcript = Arrays.asList(
                "robot --catalog catalog-v001.xml validate-profile --profile DL -i "
                        + "tmp/validate.ofn -o reports/validate_profile_owl2dl_egg.owl.txt || "
                        + "{ cat reports/validate_profile_owl2dl_egg.owl.txt && exit 1; }",
                "OWL 2 DL Profile Report: Ontology and imports closure in profile",
                "x.owl: No such file or directory",
                "make: *** [Makefile:300: x.owl] Error 1");

        assertNull(OdkTargets.whyItStopped(transcript, new File("egg-edit.owl"), null));
    }

    // ------------------------------------------------------------------ the reasoner

    /**
     * ROBOT says "unsatisfiable: <IRI>" and stops. The class is named as the person knows it,
     * with what makes it unable to have members and how to take that out.
     */
    @Test
    void aClassThatCanHaveNoMembersIsNamedWithWhatMakesItSo(@TempDir File where)
            throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = open(edit);
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass soft = df.getOWLClass(IRI.create(NS + "EGGBOIL_0004001"));
        OWLClass hard = df.getOWLClass(IRI.create(NS + "EGGBOIL_0004002"));
        OWLClass both = df.getOWLClass(IRI.create(NS + "EGGBOIL_0004003"));
        label(ontology, soft.getIRI(), "soft egg");
        label(ontology, hard.getIRI(), "hard egg");
        label(ontology, both.getIRI(), "odd egg");
        manager.addAxiom(ontology, df.getOWLDisjointClassesAxiom(soft, hard));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(both, soft));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(both, hard));
        save(ontology, edit);

        OdkEngine.Outcome ran = run(edit, "reason_test");

        assertFalse(ran.isOk(), String.join("\n", ran.getTranscript()));
        String why = OdkTargets.whyItStopped(ran.getTranscript(), edit, ontology);
        assertTrue(why.startsWith("odd egg can have no members."), why);
        assertTrue(why.contains("right-click it on the board and choose Delete axiom"), why);
        assertFalse(why.contains(both.getIRI().toString()), "by its name: " + why);
    }

    /**
     * ROBOT says only that the ontology is inconsistent, and stops. The failure says what
     * that means - something belongs to a class that can have no members.
     */
    @Test
    void anInconsistentOntologyIsSaidAsSuch(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = open(edit);
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass soft = df.getOWLClass(IRI.create(NS + "EGGBOIL_0007001"));
        OWLClass hard = df.getOWLClass(IRI.create(NS + "EGGBOIL_0007002"));
        label(ontology, soft.getIRI(), "soft egg");
        label(ontology, hard.getIRI(), "hard egg");
        manager.addAxiom(ontology, df.getOWLDisjointClassesAxiom(soft, hard));
        org.semanticweb.owlapi.model.OWLNamedIndividual one =
                df.getOWLNamedIndividual(IRI.create(NS + "EGGBOIL_0007003"));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(soft, one));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(hard, one));
        save(ontology, edit);

        OdkEngine.Outcome ran = run(edit, "reason_test");

        assertFalse(ran.isOk(), String.join("\n", ran.getTranscript()));
        String why = OdkTargets.whyItStopped(ran.getTranscript(), edit, ontology);
        assertTrue(why != null && why.startsWith("The ontology is inconsistent: an individual "
                + "belongs to a class that can have"), why + "\n"
                + String.join("\n", ran.getTranscript()));
    }

    /**
     * A project whose Makefile reasons with HermiT is asked about with HermiT: what only
     * it sees is then why the run stopped. Asked with ELK, nothing would be found, and
     * nothing is made up.
     */
    @Test
    void whatTheProjectsOwnReasonerFoundIsAskedOfThatReasoner() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology(
                IRI.create(NS + "eggboil.owl"));
        SeededProject.whatOnlyHermiTSees(ontology, "egg mass value");
        String reasons = "robot --catalog catalog-v001.xml reason --input "
                + "tmp/eggboil-preprocess.owl --reasoner %s --equivalent-classes-allowed "
                + "asserted-only \\\n\t--exclude-tautologies structural --output test.owl "
                + "&& rm test.owl";
        List<String> withHermit = Arrays.asList(String.format(reasons, "hermit"),
                "unsatisfiable: " + NS + "EGGBOIL_0009000",
                "make: *** [Makefile:224: reason_test] Error 1");
        List<String> withElk = Arrays.asList(String.format(reasons, "ELK"),
                "unsatisfiable: " + NS + "EGGBOIL_0009000",
                "make: *** [Makefile:224: reason_test] Error 1");

        String why = OdkTargets.whyItStopped(withHermit, new File("eggboil-edit.owl"),
                ontology);

        assertTrue(why != null && why.startsWith("egg mass value can have no members."),
                String.valueOf(why));
        assertNull(OdkTargets.whyItStopped(withElk, new File("eggboil-edit.owl"), ontology),
                "ELK does not see it");
        assertFalse(OdkTargets.reasonedWithElk(withHermit));
        assertTrue(OdkTargets.reasonedWithElk(withElk));
    }

    /**
     * What ELK cannot see, HermiT is asked about, and the build says so without failing.
     *
     * <p>Found by a tester: picking <i>quality of</i> for a value's relation made the class
     * unsatisfiable through the inverse <i>has quality</i>, whose range is a quality. ELK
     * ignores inverse properties, so the test passed and the class was released.
     */
    @Test
    void whatOnlyHermiTSeesIsSaid() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(NS + "eggboil.owl"));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass value = df.getOWLClass(IRI.create(NS + "value"));
        OWLClass mass = df.getOWLClass(IRI.create(NS + "mass"));
        OWLClass gdc = df.getOWLClass(IRI.create(NS + "gdc"));
        OWLClass sdc = df.getOWLClass(IRI.create(NS + "sdc"));
        OWLClass quality = df.getOWLClass(IRI.create(NS + "quality"));
        OWLObjectProperty qualityOf = df.getOWLObjectProperty(IRI.create(NS + "qualityOf"));
        OWLObjectProperty hasQuality = df.getOWLObjectProperty(IRI.create(NS + "hasQuality"));
        label(ontology, value.getIRI(), "hardness value");
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(value, gdc));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(quality, sdc));
        manager.addAxiom(ontology, df.getOWLDisjointClassesAxiom(gdc, sdc));
        manager.addAxiom(ontology, df.getOWLInverseObjectPropertiesAxiom(qualityOf, hasQuality));
        manager.addAxiom(ontology, df.getOWLObjectPropertyRangeAxiom(hasQuality, quality));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(value,
                df.getOWLObjectSomeValuesFrom(qualityOf, df.getOWLThing())));

        String second = OdkTargets.secondOpinion(ontology, Reasoners.Choice.ELK.newFactory());

        assertTrue(second.startsWith("HermiT finds that hardness value can have no members."),
                second);
        assertTrue(second.contains(" A relation or parent added to it is the usual cause:"),
                second);
        assertNull(OdkTargets.secondOpinion(ontology, Reasoners.Choice.HERMIT.newFactory()),
                "asked only after ELK");
        assertNull(OdkTargets.secondOpinion(null, Reasoners.Choice.ELK.newFactory()));

        // Several classes are spoken of as several (1.135.1 review).
        label(ontology, mass.getIRI(), "egg mass value");
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(mass, value));
        String several = OdkTargets.secondOpinion(ontology, Reasoners.Choice.ELK.newFactory());
        assertTrue(several.startsWith("HermiT finds that egg mass value, hardness value can "
                + "have no members."), several);
        assertTrue(several.contains(" A relation or parent added to one of them is the usual "
                + "cause:"), several);
    }

    // ------------------------------------------------------------------ the report

    /**
     * ROBOT's report stops ODK's test at ERROR. make's last line is one finding out of however
     * many; the failure says how many there are, names the first, and where they all are.
     */
    @Test
    void theReportsErrorsAreCountedAndTheFirstNamed(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = open(edit);
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        for (String id : new String[] {"EGGBOIL_0005001", "EGGBOIL_0005002"}) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                    df.getOWLClass(IRI.create(NS + id))));
            label(ontology, IRI.create(NS + id), "boiled egg");
        }
        save(ontology, edit);

        OdkEngine.Outcome ran = run(edit, "test_fast");

        assertFalse(ran.isOk(), String.join("\n", ran.getTranscript()));
        String why = OdkTargets.whyItStopped(ran.getTranscript(), edit, ontology);
        assertTrue(why.startsWith("The quality report found 2 errors: duplicate_label boiled "
                + "egg; duplicate_label boiled egg. All of them are in reports/"), why);
        assertTrue(why.endsWith(".tsv; "
                + "OntoBoard > Check and test > Quality report... lists them one by one."),
                why);
    }

    /** The report prints its first five; three are named, and the rest are counted. */
    @Test
    void moreErrorsThanAreNamedAreCounted() {
        List<String> transcript = Arrays.asList(
                "robot --catalog catalog-v001.xml report -i tmp/merged-egg-edit.ofn -l true "
                        + "--profile profile.txt --fail-on ERROR --print 5 -o "
                        + "reports/egg-edit.owl-obo-report.tsv",
                "Violations: 5", "-----------------", "ERROR:      5", "WARN:       0",
                "INFO:       0", "", "First 5 violations:",
                "ERROR\tduplicate_label\ta\trdfs:label\ta",
                "ERROR\tduplicate_label\tb\trdfs:label\ta",
                "ERROR\tmissing_label\tc\trdfs:label\t",
                "ERROR\tmissing_label\td\trdfs:label\t",
                "ERROR\tmissing_label\te\trdfs:label\t",
                "make: *** [Makefile:230: reports/egg-edit.owl-obo-report.tsv] Error 1");

        assertEquals("The quality report found 5 errors: duplicate_label a; duplicate_label "
                + "b; missing_label c and 2 more. All of them are in "
                + "reports/egg-edit.owl-obo-report.tsv; "
                + "OntoBoard > Check and test > Quality report... lists them one by one.",
                OdkTargets.whyItStopped(transcript, new File("egg-edit.owl"), null));
    }

    private static final String ONLY_THE_QUESTION = "q.rq: https://example.org/t1. Each "
            + "listed term breaks the rule beside it: change the model so it does not, or "
            + "edit the check in src/sparql if the rule does not apply to this project.";

    /**
     * A report that does not stop the run - fail_on: None, which is what ODK gives a
     * project that says nothing about its report - is not why a later line stopped it.
     */
    @Test
    void aReportThatDoesNotStopTheRunIsNotWhyItStopped(@TempDir File ontologyDirectory) {
        List<String> transcript = Arrays.asList(
                "robot --catalog catalog-v001.xml report -i tmp/merged-egg-edit.ofn -l true "
                        + "--fail-on None --print 5 -o reports/egg-edit.owl-obo-report.tsv",
                "Violations: 2", "-----------------", "ERROR:      2", "WARN:       0",
                "INFO:       0", "", "First 2 violations:",
                "ERROR\tduplicate_label\ta\trdfs:label\ta",
                "ERROR\tduplicate_label\tb\trdfs:label\ta",
                "cp ../patterns/definitions.owl tmp/definitions.owl",
                "cp: cannot stat '../patterns/definitions.owl': No such file or directory",
                "make: *** [Makefile:412: tmp/definitions.owl] Error 1");

        assertNull(OdkTargets.whyItStopped(transcript,
                new File(ontologyDirectory, "egg-edit.owl"), null));
        assertEquals("tmp/definitions.owl", OdkTargets.stoppedAt(transcript));
    }

    /** A run reports on several files: the report that stopped it is the last. */
    @Test
    void ofSeveralReportsTheLastIsTheOneThatStoppedTheRun() {
        List<String> transcript = Arrays.asList(
                "robot --catalog catalog-v001.xml report -i tmp/merged-egg-edit.ofn -l true "
                        + "--fail-on ERROR --print 5 -o reports/egg-edit.owl-obo-report.tsv",
                "Violations: 1", "-----------------", "ERROR:      0", "WARN:       1",
                "INFO:       0", "", "First 1 violations:",
                "WARN\tmissing_definition\ta\tIAO:0000115\t",
                "robot --catalog catalog-v001.xml report -i egg.owl -l true --fail-on ERROR "
                        + "--print 5 -o reports/egg.owl-obo-report.tsv",
                "Violations: 1", "-----------------", "ERROR:      1", "WARN:       0",
                "INFO:       0", "", "First 1 violations:",
                "ERROR\tmissing_label\tb\trdfs:label\t",
                "make: *** [Makefile:230: reports/egg.owl-obo-report.tsv] Error 1");

        assertEquals("The quality report found 1 error: missing_label b. All of them are in "
                + "reports/egg.owl-obo-report.tsv; "
                + "OntoBoard > Check and test > Quality report... lists them one by one.",
                OdkTargets.whyItStopped(transcript, new File("egg-edit.owl"), null));
    }

    /** What a run printed and then went on after is not why it stopped later. */
    @Test
    void whatTheRunWentOnAfterIsNotWhyItStopped(@TempDir File ontologyDirectory) {
        List<String> transcript = Arrays.asList(
                "robot --catalog catalog-v001.xml report -i tmp/merged-egg-edit.ofn -l true "
                        + "--fail-on ERROR --print 5 -o reports/egg-edit.owl-obo-report.tsv",
                "Violations: 1", "-----------------", "ERROR:      1", "WARN:       0",
                "INFO:       0", "", "First 1 violations:",
                "ERROR\tduplicate_label\ta\trdfs:label\ta",
                "make: [Makefile:230: reports/egg-edit.owl-obo-report.tsv] Error 1 (ignored)",
                "robot --catalog catalog-v001.xml validate-profile --profile DL -i "
                        + "tmp/validate.ofn -o reports/validate_profile_owl2dl_egg.owl.txt",
                "OWL 2 DL Profile Report: Ontology and imports closure NOT in profile. The "
                        + "following violations are present:",
                "Use of undeclared class: <https://example.org/A> [SubClassOf("
                        + "<https://example.org/A> <https://example.org/B>)]",
                "robot --catalog catalog-v001.xml merge --input tmp/egg-preprocess.owl verify "
                        + "--queries ../sparql/q.rq -O reports",
                "FAIL Rule ../sparql/q.rq: 1 violation(s)", "term", "https://example.org/t1",
                "make: *** [egg.Makefile:14: question_test] Error 1");

        assertEquals(ONLY_THE_QUESTION, OdkTargets.whyItStopped(transcript,
                new File(ontologyDirectory, "egg-edit.owl"), null));
    }

    /** A project whose report stops at a warning is told that, with what was found. */
    @Test
    void aReportThatStopsAtAWarningSaysSo() {
        List<String> transcript = Arrays.asList(
                "robot --catalog catalog-v001.xml report -i tmp/merged-egg-edit.ofn -l true "
                        + "--profile profile.txt --fail-on WARN --print 5 -o "
                        + "reports/egg-edit.owl-obo-report.tsv",
                "Violations: 3", "-----------------", "ERROR:      0", "WARN:       2",
                "INFO:       1", "", "First 3 violations:",
                "WARN\tmissing_definition\ta\tIAO:0000115\t",
                "WARN\tmissing_definition\tb\tIAO:0000115\t",
                "INFO\tmissing_superclass\tc\trdfs:subClassOf\t",
                "make: *** [Makefile:230: reports/egg-edit.owl-obo-report.tsv] Error 1");

        assertEquals("The quality report found 2 findings of the level WARN or above, where "
                + "this project's report stops (--fail-on WARN): missing_definition a; "
                + "missing_definition b. All of them are in "
                + "reports/egg-edit.owl-obo-report.tsv; "
                + "OntoBoard > Check and test > Quality report... lists them one by one.",
                OdkTargets.whyItStopped(transcript, new File("egg-edit.owl"), null));
    }
}
