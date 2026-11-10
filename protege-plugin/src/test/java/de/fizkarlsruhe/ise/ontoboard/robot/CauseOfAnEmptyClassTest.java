package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.obolibrary.robot.exceptions.IncoherentTBoxException;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * What a failed reason step names as the cause of a class that can have no members (1.135.1
 * review). The step tells people which line or arrow to take out, so a cause it names has to be
 * one that is wrong: naming a correct arrow, or one cause of two as if it were the only one, has a
 * study participant delete correct work.
 */
public class CauseOfAnEmptyClassTest {

    private static final String UP = "http://example.org/upper#";
    private static final String EGG = "http://example.org/egg#";

    /** What the step says when it names no cause, after "boiled egg can have no members.". */
    private static final String USUAL_FOR_ONE = " A relation or parent added to it is the usual"
            + " cause: right-click it on the board and choose Delete axiom from ontology… to take"
            + " it out; Edit > Undo takes back only the last change.";

    private OWLOntologyManager manager;
    private OWLDataFactory df;
    private OWLOntology upper;
    private OWLOntology own;
    private OWLClass process;
    private OWLClass object;
    private OWLObjectProperty output;
    private OWLObjectProperty input;

    /** An upper ontology: only a planned process has a specified output, and no object is one. */
    @BeforeEach
    void anEggOntologyImportingAnUpperOne() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        df = manager.getOWLDataFactory();
        upper = manager.createOntology(IRI.create("http://example.org/upper"));
        process = named(upper, UP + "process", "planned process");
        object = named(upper, UP + "object", "object");
        output = df.getOWLObjectProperty(IRI.create(UP + "output"));
        input = df.getOWLObjectProperty(IRI.create(UP + "input"));
        for (Object[] property : new Object[][] {{output, "has specified output"},
            {input, "has specified input"}}) {
            OWLObjectProperty p = (OWLObjectProperty) property[0];
            manager.addAxiom(upper, df.getOWLDeclarationAxiom(p));
            manager.addAxiom(upper, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    p.getIRI(), df.getOWLLiteral((String) property[1])));
        }
        manager.addAxiom(upper, df.getOWLObjectPropertyDomainAxiom(output, process));
        manager.addAxiom(upper, df.getOWLDisjointClassesAxiom(process, object));

        own = manager.createOntology(IRI.create("http://example.org/egg"));
        manager.applyChange(new AddImport(own, df.getOWLImportsDeclaration(
                IRI.create("http://example.org/upper"))));
    }

    private OWLClass named(OWLOntology in, String iri, String label) {
        OWLClass cls = df.getOWLClass(IRI.create(iri));
        manager.addAxiom(in, df.getOWLDeclarationAxiom(cls));
        manager.addAxiom(in, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), cls.getIRI(),
                df.getOWLLiteral(label)));
        return cls;
    }

    /** What the reason step says, run as the in-process build runs it: with ELK. */
    private String whyReasoningFails() {
        RobotException failed = assertThrows(RobotException.class, () -> RobotTransform.preview(
                own, RobotTransform.Kind.REASON, Reasoners.Choice.ELK.newFactory()));
        assertTrue(RobotTransform.isAboutTheModel(failed), failed.getMessage());
        return failed.getMessage();
    }

    /**
     * The tester's slip: an output relation drawn from the egg to the boiling. The egg's parent and
     * the arrow are both the project's own, and each is enough to take out.
     */
    private OWLClass wrongWayRound() {
        OWLClass boiling = named(own, EGG + "boiling", "egg boiling");
        OWLClass boiled = named(own, EGG + "boiled", "boiled egg");
        manager.addAxiom(own, df.getOWLSubClassOfAxiom(boiled, object));
        manager.addAxiom(own, df.getOWLSubClassOfAxiom(boiled,
                df.getOWLObjectSomeValuesFrom(output, boiling)));
        return boiled;
    }

    // ---------- an arrow to a class that is empty itself ----------

    /**
     * The guide's correct arrow is not named because its filler is empty (1.135.1 review). The
     * slip makes raw egg empty, and egg boiling, which has specified input some raw egg, is empty
     * because raw egg is. Taking that arrow out gives egg boiling members again, so it counted as
     * a cause; egg boiling comes first by name, and the build told people to delete it.
     */
    @Test
    void anArrowToAClassThatIsEmptyItselfIsNotNamedAsTheCause() {
        OWLClass boiling = named(own, EGG + "boiling", "egg boiling");
        OWLClass raw = named(own, EGG + "raw", "raw egg");
        manager.addAxiom(own, df.getOWLSubClassOfAxiom(boiling, process));
        manager.addAxiom(own, df.getOWLSubClassOfAxiom(raw, object));
        // Correct: egg boiling has specified input some raw egg.
        manager.addAxiom(own, df.getOWLSubClassOfAxiom(boiling,
                df.getOWLObjectSomeValuesFrom(input, raw)));
        // The slip: raw egg has specified output some egg boiling.
        manager.addAxiom(own, df.getOWLSubClassOfAxiom(raw,
                df.getOWLObjectSomeValuesFrom(output, boiling)));

        String said = whyReasoningFails();

        assertFalse(said.contains("has specified input"), "a correct arrow named: " + said);
        String start = "These classes can have no members: egg boiling, raw egg. What makes raw egg"
                + " so, together: ";
        String parent = "raw egg is a kind of object";
        String slip = "the arrow ∃ has specified output from raw egg to egg boiling";
        String end = ". Take out the one that is wrong: right-click it on the board and choose"
                + " Delete axiom from ontology…; Edit > Undo takes back only the last change.";
        assertTrue(said.equals(start + parent + "; " + slip + end)
                || said.equals(start + slip + "; " + parent + end), said);
    }

    /** The tester's own case, one class empty and one cause, still names that one. */
    @Test
    void theTestersWrongWayRoundIsStillTheOneToTakeOut() {
        OWLClass boiling = named(own, EGG + "boiling", "egg boiling");
        OWLClass boiled = named(own, EGG + "boiled", "boiled egg");
        // Its parent said by the import: only the arrow is the project's own.
        manager.addAxiom(upper, df.getOWLSubClassOfAxiom(boiled, object));
        manager.addAxiom(own, df.getOWLSubClassOfAxiom(boiled,
                df.getOWLObjectSomeValuesFrom(output, boiling)));

        assertEquals("boiled egg can have no members. What makes it so: the arrow ∃ has specified"
                + " output from boiled egg to egg boiling. Right-click that arrow on the board and"
                + " choose Delete axiom from ontology… to take it out; Edit > Undo takes back only"
                + " the last change.", whyReasoningFails());
    }

    // ---------- an answer cut short ----------

    /**
     * A search the deadline stopped is not the answer (1.135.1 review). The deadline was checked
     * before each attempt, so a search could stop after the first and name it alone: "What makes it
     * so: boiled egg is a kind of object. Right-click that line..." - a correct parent.
     */
    @Test
    void aSearchTheDeadlineCutShortNamesNoCause() {
        OWLClass boiled = wrongWayRound();
        Map<String, OWLClass> empty = new TreeMap<String, OWLClass>(String.CASE_INSENSITIVE_ORDER);
        empty.put("boiled egg", boiled);
        assertTrue(String.valueOf(RobotTransform.causeOf(own, own, empty,
                Reasoners.Choice.ELK.newFactory())).startsWith(" What makes it so, together: "),
                "given the time, both causes are named");

        long deadline = System.nanoTime() + 250_000_000L;
        String said = RobotTransform.causeOf(own, own, empty,
                slowUntil(Reasoners.Choice.ELK.newFactory(), deadline), deadline);

        assertNull(said, "one cause of two, as if it were the only one");
    }

    // ---------- a reasoner that fails while the cause is sought ----------

    /**
     * A failure while the cause is sought costs the cause, not the diagnosis (1.135.1 review). It
     * escaped from the reason step's own failure handling, and the build said "The reasoner could
     * not finish" about a class it had found empty. Every axiom tried is put back.
     */
    @Test
    void aReasonerFailingWhileTheCauseIsSoughtCostsOnlyTheCause() {
        OWLClass boiled = wrongWayRound();
        Set<OWLAxiom> before = new HashSet<OWLAxiom>(own.getAxioms());

        String said = RobotTransform.whyItFailed(own, own,
                new IncoherentTBoxException(Collections.singleton(boiled)),
                failing(Reasoners.Choice.ELK.newFactory(), "dispose", 2, Integer.MAX_VALUE));

        assertEquals("boiled egg can have no members." + USUAL_FOR_ONE, said);
        assertEquals(before, new HashSet<OWLAxiom>(own.getAxioms()), "every axiom tried is back");
    }

    /** The reasoner that names the empty classes failing as it is put away keeps their names. */
    @Test
    void aReasonerFailingAsItIsPutAwayStillLeavesTheClassNamed() {
        OWLClass boiled = wrongWayRound();

        String said = RobotTransform.whyItFailed(own, own,
                new IncoherentTBoxException(Collections.singleton(boiled)),
                failing(Reasoners.Choice.ELK.newFactory(), "dispose", 1, Integer.MAX_VALUE));

        assertEquals("boiled egg can have no members." + USUAL_FOR_ONE, said);
    }

    /**
     * An attempt that cannot be answered leaves the answer incomplete, so none is given: the other
     * cause alone was named as the one to take out.
     */
    @Test
    void anAttemptThatCannotBeAnsweredLeavesNoPartialAnswer() {
        OWLClass boiled = wrongWayRound();

        String said = RobotTransform.whyItFailed(own, own,
                new IncoherentTBoxException(Collections.singleton(boiled)),
                failing(Reasoners.Choice.ELK.newFactory(), "isSatisfiable", 2, 2));

        assertEquals("boiled egg can have no members." + USUAL_FOR_ONE, said);
    }

    /** Several classes named and no cause: the usual cause is added to one of them, not to "it". */
    @Test
    void severalEmptyClassesWithNoCauseNamedAreSpokenOfAsSeveral() {
        OWLClass boiled = named(own, EGG + "boiled", "boiled egg");
        OWLClass soft = named(own, EGG + "soft", "soft-boiled egg");
        // All said by the import, so nothing of the project's own is a cause.
        manager.addAxiom(upper, df.getOWLSubClassOfAxiom(boiled, object));
        manager.addAxiom(upper, df.getOWLSubClassOfAxiom(boiled,
                df.getOWLObjectSomeValuesFrom(output, df.getOWLThing())));
        manager.addAxiom(upper, df.getOWLSubClassOfAxiom(soft, boiled));

        assertEquals("These classes can have no members: boiled egg, soft-boiled egg. A relation"
                + " or parent added to one of them is the usual cause: right-click it on the board"
                + " and choose Delete axiom from ontology… to take it out; Edit > Undo takes back"
                + " only the last change.", whyReasoningFails());
    }

    // ---------- reasoners that misbehave on purpose ----------

    /**
     * A reasoner factory whose reasoners, counted from 1 as they are made, fail in {@code method}
     * from the {@code from}-th to the {@code to}-th, after the real call has been made.
     */
    public static OWLReasonerFactory failing(final OWLReasonerFactory real, final String method,
            final int from, final int to) {
        final AtomicInteger made = new AtomicInteger();
        return (OWLReasonerFactory) Proxy.newProxyInstance(
                OWLReasonerFactory.class.getClassLoader(), new Class<?>[] {OWLReasonerFactory.class},
                (factory, asked, args) -> {
                    Object answer = call(real, asked, args);
                    if (!(answer instanceof OWLReasoner)) {
                        return answer;
                    }
                    final int number = made.incrementAndGet();
                    final OWLReasoner reasoner = (OWLReasoner) answer;
                    return Proxy.newProxyInstance(OWLReasoner.class.getClassLoader(),
                            new Class<?>[] {OWLReasoner.class}, (self, called, with) -> {
                                Object result = call(reasoner, called, with);
                                if (called.getName().equals(method) && number >= from
                                        && number <= to) {
                                    throw new IllegalStateException(method + " failed on reasoner "
                                            + number);
                                }
                                return result;
                            });
                });
    }

    /** A reasoner factory that, asked for a reasoner before {@code deadline}, answers after it. */
    private static OWLReasonerFactory slowUntil(final OWLReasonerFactory real,
            final long deadline) {
        return (OWLReasonerFactory) Proxy.newProxyInstance(
                OWLReasonerFactory.class.getClassLoader(), new Class<?>[] {OWLReasonerFactory.class},
                (factory, asked, args) -> {
                    while (asked.getName().startsWith("create") && System.nanoTime() <= deadline) {
                        Thread.sleep(10);
                    }
                    return call(real, asked, args);
                });
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
    }
}
