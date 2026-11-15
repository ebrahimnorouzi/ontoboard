package de.fizkarlsruhe.ise.ontoboard.assistant.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.inference.AbstractProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.NoOpReasonerFactory;
import org.protege.editor.owl.model.inference.NoOpReasonerInfo;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * The reasoner "Explain the reasoner problem..." runs: the one chosen in Protege's Reasoner
 * menu, or HermiT when nothing is chosen there.
 *
 * <p>Seen in Protege 5.6.9 with the 1.153.0 candidate: with nothing chosen - a fresh install -
 * the column ran Protege's no-op reasoner and said "The reasoner finds no problem: the ontology
 * is consistent and every class is satisfiable." of an ontology with two unsatisfiable classes.
 * Protege names the reasoner it is running "Protégé Null Reasoner" until one is started, and the
 * column fell back to HermiT only for the name "none".
 */
class ProblemReasonerTest {

    private static final String HERMIT_INSTEAD = "HermiT (Protégé has no reasoner selected)";

    @Test
    void protegesNoReasonerIsNotRunButHermiT() {
        // As Protege sets its "None" up from its plugin.xml.
        NoOpReasonerInfo none = new NoOpReasonerInfo();
        none.setup(null, NoOpReasonerInfo.NULL_REASONER_ID, "None");

        Object[] run = ProblemReasoner.of(none);

        assertEquals(Reasoners.Choice.HERMIT.newFactory().getClass(), run[0].getClass());
        assertEquals(HERMIT_INSTEAD, run[1]);
    }

    @Test
    void aNoOpFactoryIsNotRunWhateverItIsCalled() {
        Object[] run = ProblemReasoner.of(chosen(new NoOpReasonerFactory(), "Some reasoner"));

        assertEquals(Reasoners.Choice.HERMIT.newFactory().getClass(), run[0].getClass());
        assertEquals(HERMIT_INSTEAD, run[1]);
    }

    @Test
    void aReasonerChosenButNotStartedIsRunUnderItsOwnName() {
        // Chosen in the menu and not started: Protege still runs its null reasoner, and the
        // column said "Running Protégé Null Reasoner..." over ELK's work.
        OWLReasonerFactory elk = Reasoners.Choice.ELK.newFactory();

        Object[] run = ProblemReasoner.of(chosen(elk, "ELK 0.6.0"));

        assertSame(elk, run[0]);
        assertEquals("ELK 0.6.0", run[1]);
    }

    @Test
    void aChosenReasonerWithoutANameIsCalledWhatItsFactoryCallsIt() {
        OWLReasonerFactory named = named("A reasoner by its factory's name");

        Object[] run = ProblemReasoner.of(chosen(named, " "));

        assertSame(named, run[0]);
        assertEquals("A reasoner by its factory's name", run[1]);
    }

    @Test
    void aReasonerNamedNowhereIsStillNamed() {
        // ELK's factory gives an empty name: "Running …" said nothing at all.
        OWLReasonerFactory elk = Reasoners.Choice.ELK.newFactory();

        Object[] run = ProblemReasoner.of(chosen(elk, null));

        assertSame(elk, run[0]);
        assertEquals("the reasoner chosen in Protégé", run[1]);
    }

    @Test
    void nothingAtAllIsHermiT() {
        Object[] run = ProblemReasoner.of(null);

        assertEquals(Reasoners.Choice.HERMIT.newFactory().getClass(), run[0].getClass());
        assertEquals(HERMIT_INSTEAD, run[1]);
    }

    /** A factory that calls itself {@code name} and is never asked for a reasoner. */
    private static OWLReasonerFactory named(final String name) {
        return (OWLReasonerFactory) java.lang.reflect.Proxy.newProxyInstance(
                OWLReasonerFactory.class.getClassLoader(), new Class<?>[] {OWLReasonerFactory.class},
                (proxy, method, arguments) -> {
                    if ("getReasonerName".equals(method.getName())) {
                        return name;
                    }
                    if ("toString".equals(method.getName())) {
                        return "factory " + name;
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == arguments[0];
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A reasoner as Protege's menu has it: its factory, under the name its plugin gives. */
    private static ProtegeOWLReasonerInfo chosen(final OWLReasonerFactory factory, String name) {
        AbstractProtegeOWLReasonerInfo info = new AbstractProtegeOWLReasonerInfo() {
            @Override
            public BufferingMode getRecommendedBuffering() {
                return BufferingMode.BUFFERING;
            }

            @Override
            public OWLReasonerFactory getReasonerFactory() {
                return factory;
            }
        };
        info.setup(null, "test.reasoner", name);
        return info;
    }
}
