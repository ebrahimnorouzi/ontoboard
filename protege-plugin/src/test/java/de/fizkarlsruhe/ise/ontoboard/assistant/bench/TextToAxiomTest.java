package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.AssistantRendering;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.Questions;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Text to axiom (1.140.0; the maintainer: "text to axiom data from these ontologies"): an axiom
 * held back, a sentence made from it, and the model's axiom judged by the reasoner - equivalent,
 * stronger, weaker or unrelated - not by its spelling.
 */
class TextToAxiomTest {

    private final OWLOntology pizza = MiniPizza.load();
    private List<BenchCase> cases;
    private BenchCase margherita;

    @BeforeEach
    void generate() {
        cases = new CaseGenerator(pizza, "mini-pizza.ofn", "abc",
                Reasoners.Choice.HERMIT.newFactory(), "HermiT", 1).textToAxiom(5);
        for (BenchCase c : cases) {
            if (c.getSubjectIri().endsWith("#Margherita")) {
                margherita = c;
            }
        }
    }

    @Test
    void eachRestrictionBecomesACaseWithTheAxiomHeldBack() {
        assertEquals(2, cases.size(), "Margherita's some and vegetarian pizza's only");
        for (BenchCase c : cases) {
            assertEquals(BenchCase.TEXT_TO_AXIOM, c.getTask());
            OWLAxiom gold = BenchCases.goldAxiom(c);
            String shown = AssistantRendering.render(pizza, gold);
            assertFalse(c.getContext().get(0).getText().contains(shown),
                    c.getContext().get(0).getText());
        }
    }

    @Test
    void theSentenceSaysTheQuantifierInWordsNotInOwl() {
        assertEquals(Questions.sentenceToAxiom("Margherita",
                "Every Margherita hasTopping at least one cheese topping."),
                margherita.getMessage());
        BenchCase vegetarian = cases.get(0) == margherita ? cases.get(1) : cases.get(0);
        assertTrue(vegetarian.getMessage().contains(
                "Every vegetarian pizza hasTopping nothing but VegetarianTopping."),
                vegetarian.getMessage());
    }

    private Score score(String axiom) {
        String reply = "```ontoboard-proposal\naction: add-axiom\naxiom: " + axiom + "\n```";
        return Scorers.textToAxiom(margherita, reply, Collections.<String>emptySet(),
                CaseGenerator.without(pizza, BenchCases.goldAxiom(margherita)),
                Reasoners.Choice.HERMIT.newFactory());
    }

    /**
     * Review 1.141.0 M3, raised to Important: a safety check that ran out of time scored the gold
     * answer safe 0, pass false, with no error - on a busy machine, a right answer failed
     * silently. The check's error is now the score's.
     */
    @Test
    void aSafetyCheckThatRunsOutOfTimeIsTheRowsError() {
        long timeout = de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheckTiming
                .timeoutMillis();
        long grace = de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheckTiming
                .graceMillis();
        de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheckTiming.set(1, 0);
        Score score;
        try {
            score = score("Margherita SubClassOf hasTopping some 'cheese topping'");
        } finally {
            de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheckTiming.set(timeout,
                    grace);
        }

        assertFalse(score.isPass());
        assertTrue(score.getError() != null && score.getError().contains("out of time"),
                String.valueOf(score.getError()));
    }

    @Test
    void theHeldBackAxiomItselfIsEquivalent() {
        Score score = score("Margherita SubClassOf hasTopping some 'cheese topping'");

        assertEquals(1.0, score.getValues().get("equivalent"), 0.0);
        // One block, one axiom: the one-block rule leaves a single right answer passing
        // (1.146.0, review 1.145.0 bench-B).
        assertEquals(Double.valueOf(1.0), score.getValues().get("proposals"),
                score.getValues().toString());
        assertTrue(score.isPass());
    }

    private static String block(String axiom) {
        return "```ontoboard-proposal\naction: add-axiom\naxiom: " + axiom + "\n```\n";
    }

    private Score reply(String reply) {
        return Scorers.textToAxiom(margherita, reply, Collections.<String>emptySet(),
                CaseGenerator.without(pizza, BenchCases.goldAxiom(margherita)),
                Reasoners.Choice.HERMIT.newFactory());
    }

    /**
     * Review 1.145.0 bench-B: only the first add-axiom block was scored, so the right axiom
     * followed by a wrong alternative passed, and the same two blocks the other way round
     * failed. The request asks for one block, and the person would get two cards, one wrong.
     */
    @Test
    void aRightAxiomWithAWrongAlternativeDoesNotPassInEitherOrder() {
        String right = block("Margherita SubClassOf hasTopping some 'cheese topping'");
        String wrong = block("Margherita SubClassOf hasTopping only 'cheese topping'");

        Score rightFirst = reply(right + wrong);
        Score wrongFirst = reply(wrong + right);

        assertFalse(rightFirst.isPass(), rightFirst.getValues().toString());
        assertFalse(wrongFirst.isPass(), wrongFirst.getValues().toString());
        assertEquals(Double.valueOf(2.0), rightFirst.getValues().get("proposals"));
        assertEquals(Double.valueOf(2.0), wrongFirst.getValues().get("proposals"));
        // The other columns still describe the first block, so the row shows why it failed.
        assertEquals(1.0, rightFirst.getValues().get("equivalent"), 0.0);
        assertEquals(0.0, wrongFirst.getValues().get("entails"), 0.0);
    }

    /**
     * 1.146.0, ruling on the review of the bench-B fix: every card the answer offers counts, not
     * only add-axiom ones. A right axiom beside a card that removes another one, or one that adds
     * a comment, passed in either order - and the person would get both cards, where the request
     * asks for one block.
     */
    @Test
    void aRightAxiomBesideAnotherCardDoesNotPass() {
        String right = block("Margherita SubClassOf hasTopping some 'cheese topping'");
        String[] others = {
            "```ontoboard-proposal\naction: remove-axiom\naxiom: Margherita SubClassOf NamedPizza\n"
                    + "reason: tidy\n```\n",
            "```ontoboard-proposal\naction: add\nsubject: Margherita\nproperty: comment\n"
                    + "value: A classic.\nreason: helpful\n```\n"};
        for (String other : others) {
            Score rightFirst = reply(right + other);
            Score otherFirst = reply(other + right);

            assertFalse(rightFirst.isPass(), other + rightFirst.getValues());
            assertFalse(otherFirst.isPass(), other + otherFirst.getValues());
            assertEquals(Double.valueOf(2.0), rightFirst.getValues().get("proposals"), other);
            assertEquals(Double.valueOf(2.0), otherFirst.getValues().get("proposals"), other);
            // The columns still describe the add-axiom, so the row shows why it failed.
            assertEquals(1.0, otherFirst.getValues().get("equivalent"), 0.0);
        }
    }

    /** Review 1.145.0 bench-B, the checker's probe: an alternative that says more or less. */
    @Test
    void aStrongerOrWeakerAlternativeDoesNotPassInEitherOrder() {
        String right = block("Margherita SubClassOf hasTopping some 'cheese topping'");
        List<String> passed = new java.util.ArrayList<String>();
        for (String other : new String[] {
            "Margherita SubClassOf hasTopping some 'cheese topping' and hasTopping only"
                    + " VegetarianTopping",
            "Margherita SubClassOf hasTopping some VegetarianTopping"}) {
            if (reply(right + block(other)).isPass()) {
                passed.add("right, then " + other);
            }
            if (reply(block(other) + right).isPass()) {
                passed.add(other + ", then right");
            }
        }

        assertEquals(Collections.<String>emptyList(), passed);
    }

    /** A block repeated word for word is one answer, not a second proposal. */
    @Test
    void aBlockRepeatedWordForWordIsOneAnswer() {
        String right = block("Margherita SubClassOf hasTopping some 'cheese topping'");

        Score twice = reply(right + "The same again:\n" + right);

        assertEquals(Double.valueOf(1.0), twice.getValues().get("proposals"),
                twice.getValues().toString());
        assertTrue(twice.isPass(), twice.getValues().toString());
    }

    /**
     * Review 1.140.0 I1: a sentence says one thing. An answer that says more entails it, and is
     * reported so, but is not the sentence.
     */
    @Test
    void aStrongerAxiomEntailsItButDoesNotPass() {
        Score score = score("Margherita SubClassOf hasTopping some 'cheese topping' and"
                + " hasTopping only VegetarianTopping");

        assertEquals(1.0, score.getValues().get("entails"), 0.0);
        assertEquals(0.0, score.getValues().get("entailed"), 0.0);
        assertFalse(score.isPass());
    }

    /**
     * Review 1.140.0 I1, measured on pizza-bfo: "only" passed where "some" was held back, because
     * the class already had some topping that could be the filler.
     */
    @Test
    void onlyThatTheOntologyMakesEntailSomeDoesNotPass() {
        org.semanticweb.owlapi.model.OWLDataFactory f = pizza.getOWLOntologyManager()
                .getOWLDataFactory();
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLSubClassOfAxiom(
                f.getOWLClass(MiniPizza.iri("Margherita")), f.getOWLObjectSomeValuesFrom(
                        f.getOWLObjectProperty(MiniPizza.iri("hasTopping")),
                        f.getOWLClass(MiniPizza.iri("VegetarianTopping")))));

        Score score = score("Margherita SubClassOf hasTopping only 'cheese topping'");

        assertEquals(1.0, score.getValues().get("entails"), 0.0);
        assertEquals(0.0, score.getValues().get("equivalent"), 0.0);
        assertFalse(score.isPass());
    }

    /** Review 1.140.0 I2: a facet HermiT cannot handle ended the whole run. */
    @Test
    void anAnswerTheReasonerCannotHandleIsScoredNotThrown() {
        org.semanticweb.owlapi.model.OWLDataFactory f = pizza.getOWLOntologyManager()
                .getOWLDataFactory();
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLDeclarationAxiom(
                f.getOWLDataProperty(MiniPizza.iri("hasPrice"))));

        Score score = score("Margherita SubClassOf hasPrice some xsd:string[>= 5]");

        assertEquals(1.0, score.getValues().get("validProposal"), 0.0);
        assertEquals(0.0, score.getValues().get("entails"), 0.0);
        assertFalse(score.isPass());
    }

    /** Review 1.140.0 I3: asking for a term is what the request says to do when one is missing. */
    @Test
    void anAnswerThatAsksIsReportedAsAsked() {
        Score score = Scorers.textToAxiom(margherita, "I need this first.\n```ontoboard-need\n"
                + "hasTopping\n```", Collections.<String>emptySet(),
                CaseGenerator.without(pizza, BenchCases.goldAxiom(margherita)),
                Reasoners.Choice.HERMIT.newFactory());

        assertEquals(1.0, score.getValues().get("asked"), 0.0);
        assertFalse(score.isPass());
    }

    /** "∀ alone": only where some was meant says nothing about there being a topping. */
    @Test
    void onlyWhereSomeWasMeantFails() {
        Score score = score("Margherita SubClassOf hasTopping only 'cheese topping'");

        assertEquals(1.0, score.getValues().get("validProposal"), 0.0);
        assertEquals(0.0, score.getValues().get("entails"), 0.0);
        assertFalse(score.isPass());
    }

    @Test
    void anInventedNameIsNotAValidProposal() {
        Score score = score("Margherita SubClassOf hasTopping some mozzarella");

        assertEquals(0.0, score.getValues().get("validProposal"), 0.0);
        // Only what OntoBoard would offer as a card is counted (1.146.0, review 1.145.0 bench-B).
        assertEquals(Double.valueOf(0.0), score.getValues().get("proposals"),
                score.getValues().toString());
        assertFalse(score.isPass());
    }

    /** An unsatisfiable class entails everything; that is no pass. */
    @Test
    void anAxiomThatEmptiesTheClassIsNotSafe() {
        Score score = score("Margherita SubClassOf hasTopping some MeatTopping and"
                + " hasTopping only VegetarianTopping and hasTopping some 'cheese topping'");

        assertEquals(0.0, score.getValues().get("safe"), 0.0);
        assertFalse(score.isPass());
    }

    /**
     * NFDIcore and MWO say what a class is in EquivalentTo definitions, and their SubClassOf
     * restrictions repeat parts of them (measured: 11 of 12 entailed by a definition). A
     * definition says both directions, and only an answer that says both passes.
     */
    @Test
    void aDefinitionBecomesACaseThatNeedsBothDirections() {
        OWLOntology defined = MiniPizza.load();
        org.semanticweb.owlapi.model.OWLDataFactory df = defined.getOWLOntologyManager()
                .getOWLDataFactory();
        org.semanticweb.owlapi.model.OWLClass cheesy = df.getOWLClass(MiniPizza.iri("CheesyPizza"));
        defined.getOWLOntologyManager().addAxiom(defined, df.getOWLDeclarationAxiom(cheesy));
        defined.getOWLOntologyManager().addAxiom(defined, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), cheesy.getIRI(), df.getOWLLiteral("cheesy pizza", "en")));
        defined.getOWLOntologyManager().addAxiom(defined, df.getOWLEquivalentClassesAxiom(cheesy,
                df.getOWLObjectIntersectionOf(df.getOWLClass(MiniPizza.iri("Pizza")),
                        df.getOWLObjectSomeValuesFrom(df.getOWLObjectProperty(
                                MiniPizza.iri("hasTopping")), df.getOWLClass(
                                        MiniPizza.iri("CheeseTopping"))))));
        BenchCase c = null;
        for (BenchCase each : new CaseGenerator(defined, "defined.ofn", "abc",
                Reasoners.Choice.HERMIT.newFactory(), "HermiT", 1).textToAxiom(5)) {
            if (each.getSubjectIri().endsWith("#CheesyPizza")) {
                c = each;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(c, "no case for the definition");
        assertTrue(c.getMessage().contains("Every cheesy pizza is a Pizza that hasTopping at"
                + " least one cheese topping, and every Pizza that hasTopping at least one cheese"
                + " topping is a cheesy pizza."), c.getMessage());
        OWLOntology without = CaseGenerator.without(defined, BenchCases.goldAxiom(c));

        Score both = Scorers.textToAxiom(c, "```ontoboard-proposal\naction: add-axiom\naxiom:"
                + " 'cheesy pizza' EquivalentTo Pizza and (hasTopping some 'cheese topping')\n```",
                Collections.<String>emptySet(), without, Reasoners.Choice.HERMIT.newFactory());
        Score oneWay = Scorers.textToAxiom(c, "```ontoboard-proposal\naction: add-axiom\naxiom:"
                + " 'cheesy pizza' SubClassOf Pizza and (hasTopping some 'cheese topping')\n```",
                Collections.<String>emptySet(), without, Reasoners.Choice.HERMIT.newFactory());

        assertEquals(1.0, both.getValues().get("equivalent"), 0.0);
        assertTrue(both.isPass());
        assertEquals(0.0, oneWay.getValues().get("entails"), 0.0);
        assertEquals(1.0, oneWay.getValues().get("entailed"), 0.0);
        assertFalse(oneWay.isPass());
    }

    /** The fixtures' classes live beside the ontology IRI (…/materials# next to …/consistent). */
    @Test
    void aClassBesideTheOntologyIriIsItsOwn() throws java.io.IOException {
        OWLOntology fixture = AssistantBench.load(new java.io.File(
                "../benchmarks/assistant/ontologies/fixtures/consistent.ofn"));

        List<BenchCase> made = new CaseGenerator(fixture, "consistent.ofn", "abc",
                Reasoners.Choice.HERMIT.newFactory(), "HermiT", 1).textToAxiom(5);

        assertEquals(1, made.size());
        assertTrue(made.get(0).getMessage().contains(
                "Every composite has constituent at least one material."), made.get(0).getMessage());
    }

    /**
     * 1.141.0: a case sends what "Write as OWL…" sends - the subject's block and the blocks of the
     * terms the sentence names - and none of them shows the held-back axiom.
     */
    @Test
    void theCaseSendsTheTermsTheSentenceNames() throws java.io.IOException {
        // The consistent fixture since 1.142.0: the unsatisfiable one's only candidate empties
        // its class, so it gives no case (anAxiomWhoseReturnEmptiesAClassGivesNoCase).
        OWLOntology fixture = AssistantBench.load(new java.io.File(
                "../benchmarks/assistant/ontologies/fixtures/consistent.ofn"));

        BenchCase c = new CaseGenerator(fixture, "consistent.ofn", "abc",
                Reasoners.Choice.HERMIT.newFactory(), "HermiT", 1).textToAxiom(5).get(0);

        List<String> titles = new java.util.ArrayList<String>();
        for (de.fizkarlsruhe.ise.ontoboard.assistant.chat.RenderedItem item : c.getContext()) {
            titles.add(item.getTitle());
            assertFalse(item.getText().contains(AssistantRendering.render(fixture,
                    BenchCases.goldAxiom(c))), item.getText());
        }
        assertEquals(java.util.Arrays.asList("composite", "has constituent", "material"), titles);
    }

    /**
     * 1.142.0 (L1): a shipped case can be passed. Its own held-back axiom, written as the context
     * writes names and sent as the answer, passes - otherwise a right answer scores as a fail,
     * as unsatisfiable-text-to-axiom-01 did, whose axiom is the one that empties its class.
     */
    @Test
    void everyShippedCaseIsPassedByItsGoldAxiom() throws java.io.IOException {
        java.io.File folder = new java.io.File("../benchmarks/assistant/cases");
        java.io.File[] files = folder.listFiles((dir, name) -> name.endsWith(".jsonl"));
        java.util.Arrays.sort(files);
        java.util.Map<String, OWLOntology> loaded = new java.util.HashMap<String, OWLOntology>();
        List<String> failed = new java.util.ArrayList<String>();
        int checked = 0;
        for (java.io.File file : files) {
            for (BenchCase c : BenchCases.read(file)) {
                if (!BenchCase.TEXT_TO_AXIOM.equals(c.getTask())) {
                    continue;
                }
                java.io.File source = new java.io.File(folder, c.getOntology());
                OWLOntology ontology = loaded.get(source.getPath());
                if (ontology == null) {
                    ontology = AssistantBench.load(source);
                    loaded.put(source.getPath(), ontology);
                }
                OWLAxiom gold = BenchCases.goldAxiom(c);
                OWLOntology without = CaseGenerator.without(ontology, gold);
                String reply = "```ontoboard-proposal\naction: add-axiom\naxiom: "
                        + AssistantRendering.render(without, gold) + "\n```";
                Score score = Scorers.textToAxiom(c, reply, Collections.<String>emptySet(),
                        without, Reasoners.Choice.HERMIT.newFactory());
                checked++;
                if (!score.isPass()) {
                    failed.add(c.getId() + " " + score.getValues());
                }
            }
        }
        assertTrue(checked > 0, "no text-to-axiom case found under " + folder.getPath());
        assertEquals(Collections.<String>emptyList(), failed);
    }

    /**
     * 1.142.0 (L1): an axiom whose return makes a class unsatisfiable is never held back - not
     * even its own axiom would pass "safe". In the fixture, fibre-reinforced aluminium is a metal,
     * and anything reinforced with something is a composite, disjoint from metal.
     */
    @Test
    void anAxiomWhoseReturnEmptiesAClassGivesNoCase() throws java.io.IOException {
        OWLOntology fixture = AssistantBench.load(new java.io.File(
                "../benchmarks/assistant/ontologies/fixtures/unsatisfiable.ofn"));
        String materials = "http://example.org/ontoboard-fixtures/materials#";
        org.semanticweb.owlapi.model.OWLDataFactory df = fixture.getOWLOntologyManager()
                .getOWLDataFactory();
        OWLAxiom empties = df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(materials + "FibreReinforcedAluminium")),
                df.getOWLObjectSomeValuesFrom(
                        df.getOWLObjectProperty(IRI.create(materials + "reinforcedWith")),
                        df.getOWLClass(IRI.create(materials + "Material"))));

        List<BenchCase> made = new CaseGenerator(fixture, "unsatisfiable.ofn", "abc",
                Reasoners.Choice.HERMIT.newFactory(), "HermiT", 1).textToAxiom(5);

        for (BenchCase c : made) {
            assertFalse(empties.equals(BenchCases.goldAxiom(c)), c.getId());
        }
    }

    private static OWLOntology restrictions() throws java.io.IOException {
        return AssistantBench.load(new java.io.File(
                "../benchmarks/assistant/ontologies/fixtures/restrictions.ofn"));
    }

    private static java.util.Map<String, BenchCase> bySubject(List<BenchCase> made) {
        java.util.Map<String, BenchCase> found = new java.util.TreeMap<String, BenchCase>();
        for (BenchCase c : made) {
            found.put(c.getSubject(), c);
        }
        return found;
    }

    /** 1.144.0: min, max, exactly, a value, a datatype and a typed value, said in words. */
    @Test
    void eachShapeIsSaidInWords() throws java.io.IOException {
        List<BenchCase> made = new CaseGenerator(restrictions(), "restrictions.ofn", "abc",
                Reasoners.Choice.HERMIT.newFactory(), "HermiT", 1).textToAxiom(10);

        java.util.Map<String, String> said = new java.util.TreeMap<String, String>();
        for (java.util.Map.Entry<String, BenchCase> each : bySubject(made).entrySet()) {
            said.put(each.getKey(), each.getValue().getReference());
        }
        java.util.Map<String, String> expected = new java.util.TreeMap<String, String>();
        expected.put("high-entropy alloy",
                "Every high-entropy alloy has constituent element at least 5 element.");
        expected.put("pure metal", "Every pure metal has constituent element at most 1 element.");
        expected.put("brass", "Every brass has constituent element exactly 2 element.");
        expected.put("steel", "Every steel has main element iron.");
        expected.put("test specimen",
                "Every test specimen has density at least one decimal value.");
        expected.put("reference steel",
                "Every reference steel has density the decimal value 7.85.");
        expected.put("binary alloy", "Every binary alloy is an alloy that has constituent element"
                + " exactly 2 element, and every alloy that has constituent element exactly 2"
                + " element is a binary alloy.");
        assertEquals(expected, said);
    }

    private static OWLOntology parse(String functionalSyntax) throws Exception {
        return org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(
                        new org.semanticweb.owlapi.io.StringDocumentSource(functionalSyntax));
    }

    /**
     * 1.144.0: the new shapes come after the cases of some and only, which are chosen as before -
     * otherwise a min restriction in MWO reshuffles MWO's cases, and live runs of different
     * versions measure different cases.
     */
    @Test
    void theNewShapesDoNotChangeWhichSomeAndOnlyCasesAreChosen() throws Exception {
        String head = "Prefix(:=<http://example.org/t/x#>)\n"
                + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                + "Ontology(<http://example.org/t/o>\n"
                + "Declaration(ObjectProperty(:hasPart))\n"
                + "AnnotationAssertion(rdfs:label :hasPart \"has part\")\n";
        StringBuilder some = new StringBuilder(head);
        for (String name : new String[] {"Anode", "Bolt", "Cable", "Disc", "Frame", "Gear"}) {
            some.append("Declaration(Class(:").append(name).append("))\n")
                    .append("AnnotationAssertion(rdfs:label :").append(name).append(" \"")
                    .append(name.toLowerCase(java.util.Locale.ROOT)).append("\")\n");
        }
        String base = some + "SubClassOf(:Anode ObjectSomeValuesFrom(:hasPart :Bolt))\n"
                + "SubClassOf(:Cable ObjectSomeValuesFrom(:hasPart :Disc))\n"
                + "SubClassOf(:Frame ObjectSomeValuesFrom(:hasPart :Gear))\n";
        OWLOntology before = parse(base + ")");
        OWLOntology after = parse(base
                + "SubClassOf(:Gear ObjectMinCardinality(2 :hasPart :Disc))\n)");

        List<BenchCase> old = new CaseGenerator(before, "t.ofn", "abc",
                Reasoners.Choice.HERMIT.newFactory(), "HermiT", 1).textToAxiom(10);
        List<BenchCase> now = new CaseGenerator(after, "t.ofn", "abc",
                Reasoners.Choice.HERMIT.newFactory(), "HermiT", 1).textToAxiom(10);

        assertEquals(3, old.size());
        assertEquals(4, now.size());
        for (int i = 0; i < old.size(); i++) {
            assertEquals(old.get(i).getReference(), now.get(i).getReference(), "case " + i);
        }
        assertEquals("Every gear has part at least 2 disc.", now.get(3).getReference());
    }

    /** "At least one" is some, and min 1 means the same: it is said the same way. */
    @Test
    void minOneIsSaidAsSome() throws Exception {
        OWLOntology small = org.semanticweb.owlapi.apibinding.OWLManager
                .createOWLOntologyManager().loadOntologyFromOntologyDocument(
                        new org.semanticweb.owlapi.io.StringDocumentSource(
                                "Prefix(:=<http://example.org/t/x#>)\n"
                                + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                                + "Ontology(<http://example.org/t/o>\n"
                                + "Declaration(Class(:Anode)) Declaration(Class(:Fin))\n"
                                + "Declaration(ObjectProperty(:hasPart))\n"
                                + "AnnotationAssertion(rdfs:label :Anode \"anode\")\n"
                                + "AnnotationAssertion(rdfs:label :Fin \"fin\")\n"
                                + "AnnotationAssertion(rdfs:label :hasPart \"has part\")\n"
                                + "SubClassOf(:Anode ObjectMinCardinality(1 :hasPart :Fin)))"));

        List<BenchCase> made = new CaseGenerator(small, "small.ofn", "abc",
                Reasoners.Choice.HERMIT.newFactory(), "HermiT", 1).textToAxiom(5);

        assertEquals(1, made.size());
        assertEquals("Every anode has part at least one fin.", made.get(0).getReference());
    }

    private static Score scoreOn(OWLOntology ontology, BenchCase c, String axiom) {
        String reply = "```ontoboard-proposal\naction: add-axiom\naxiom: " + axiom + "\n```";
        return Scorers.textToAxiom(c, reply, Collections.<String>emptySet(),
                CaseGenerator.without(ontology, BenchCases.goldAxiom(c)),
                Reasoners.Choice.HERMIT.newFactory());
    }

    @Test
    void aSomeOrSmallerMinWhereAtLeastFiveWasSaidDoesNotPass() throws java.io.IOException {
        OWLOntology fixture = restrictions();
        BenchCase alloy = bySubject(new CaseGenerator(fixture, "restrictions.ofn", "abc",
                Reasoners.Choice.HERMIT.newFactory(), "HermiT", 1).textToAxiom(10))
                        .get("high-entropy alloy");

        Score some = scoreOn(fixture, alloy,
                "'high-entropy alloy' SubClassOf 'has constituent element' some element");
        Score four = scoreOn(fixture, alloy,
                "'high-entropy alloy' SubClassOf 'has constituent element' min 4 element");
        Score five = scoreOn(fixture, alloy,
                "'high-entropy alloy' SubClassOf 'has constituent element' min 5 element");

        assertFalse(some.isPass(), some.getValues().toString());
        assertFalse(four.isPass(), four.getValues().toString());
        assertTrue(five.isPass(), five.getValues().toString());
    }

    private java.util.Map<String, BenchCase> restrictionCases(OWLOntology fixture) {
        return bySubject(new CaseGenerator(fixture, "restrictions.ofn", "abc",
                Reasoners.Choice.HERMIT.newFactory(), "HermiT", 1).textToAxiom(10));
    }

    /**
     * Review 1.144.0 bench-1: with "min 2" on alloy, "max 2" for brass and binary alloy meant
     * "exactly 2" given the rest, so the classic mistake passed.
     */
    @Test
    void aMaxAnswerWhereExactlyWasSaidDoesNotPass() throws java.io.IOException {
        OWLOntology fixture = restrictions();
        java.util.Map<String, BenchCase> made = restrictionCases(fixture);

        Score brass = scoreOn(fixture, made.get("brass"),
                "brass SubClassOf 'has constituent element' max 2 element");
        Score binary = scoreOn(fixture, made.get("binary alloy"),
                "'binary alloy' EquivalentTo alloy and ('has constituent element' max 2 element)");

        assertFalse(brass.isPass(), brass.getValues().toString());
        assertFalse(binary.isPass(), binary.getValues().toString());
    }

    /** Review 1.144.0 bench-1: with a range on the property, leaving out the filler passed. */
    @Test
    void anAnswerWithoutTheClassOrDatatypeDoesNotPass() throws java.io.IOException {
        OWLOntology fixture = restrictions();
        java.util.Map<String, BenchCase> made = restrictionCases(fixture);

        Score noClass = scoreOn(fixture, made.get("pure metal"),
                "'pure metal' SubClassOf 'has constituent element' max 1");
        Score noDatatype = scoreOn(fixture, made.get("test specimen"),
                "'test specimen' SubClassOf 'has density' some rdfs:Literal");

        assertFalse(noClass.isPass(), noClass.getValues().toString());
        assertFalse(noDatatype.isPass(), noDatatype.getValues().toString());
    }

    /**
     * Review 1.144.0 bench-2: the request said only "some" and "only", which none of the min,
     * max, exactly and value cases can use. Every restriction a case's axiom needs is named.
     */
    @Test
    void theRequestNamesTheRestrictionEachShapeNeeds() throws java.io.IOException {
        OWLOntology fixture = restrictions();
        java.util.List<String> missing = new java.util.ArrayList<String>();
        for (BenchCase c : restrictionCases(fixture).values()) {
            String gold = AssistantRendering.render(fixture, BenchCases.goldAxiom(c));
            for (String keyword : new String[] {"some", "only", "min", "max", "exactly",
                "value"}) {
                if (java.util.regex.Pattern.compile("\\b" + keyword + "\\b").matcher(gold).find()
                        && !c.getMessage().contains("\"" + keyword + "\"")) {
                    missing.add(c.getId() + " needs " + keyword);
                }
            }
        }
        assertEquals(Collections.<String>emptyList(), missing);
    }

    /**
     * The bench sends the message stored in a case; "Write as OWL..." builds it anew. They must
     * stay the same text, or the bench measures a request nobody sends.
     */
    @Test
    void theShippedMessagesAreWhatWriteAsOwlSends() throws java.io.IOException {
        java.io.File folder = new java.io.File("../benchmarks/assistant/cases");
        java.util.List<String> drifted = new java.util.ArrayList<String>();
        for (java.io.File file : folder.listFiles((dir, name) -> name.endsWith(".jsonl"))) {
            for (BenchCase c : BenchCases.read(file)) {
                if (BenchCase.TEXT_TO_AXIOM.equals(c.getTask()) && !c.getMessage().equals(
                        Questions.sentenceToAxiom(c.getSubject(), c.getReference()))) {
                    drifted.add(c.getId());
                }
            }
        }
        assertEquals(Collections.<String>emptyList(), drifted);
    }

    @Test
    void aFloatValueWhereADecimalWasSaidDoesNotPass() throws java.io.IOException {
        OWLOntology fixture = restrictions();
        BenchCase steel = bySubject(new CaseGenerator(fixture, "restrictions.ofn", "abc",
                Reasoners.Choice.HERMIT.newFactory(), "HermiT", 1).textToAxiom(10))
                        .get("reference steel");

        Score asFloat = scoreOn(fixture, steel,
                "'reference steel' SubClassOf 'has density' value \"7.85\"^^xsd:float");
        Score asDecimal = scoreOn(fixture, steel,
                "'reference steel' SubClassOf 'has density' value 7.85");

        assertFalse(asFloat.isPass(), asFloat.getValues().toString());
        assertTrue(asDecimal.isPass(), asDecimal.getValues().toString());
    }

    @Test
    void theGoldSurvivesTheCaseFile() throws java.io.IOException {
        java.io.File file = java.io.File.createTempFile("t2a", ".jsonl");
        file.deleteOnExit();
        BenchCases.write(cases, file);

        BenchCase back = BenchCases.read(file).get(cases.indexOf(margherita));

        assertEquals(BenchCases.goldAxiom(margherita), BenchCases.goldAxiom(back));
        assertEquals(IRI.create(margherita.getSubjectIri()), IRI.create(back.getSubjectIri()));
    }
}
