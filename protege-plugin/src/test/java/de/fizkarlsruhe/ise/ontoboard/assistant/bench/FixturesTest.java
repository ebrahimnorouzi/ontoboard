package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * The benchmark's hand-written fixtures are what their comments say they are: a test that ran on
 * a fixture that had drifted would measure the wrong thing.
 */
class FixturesTest {

    private static final File FIXTURES = new File("../benchmarks/assistant/ontologies/fixtures");

    private static OWLReasoner reason(String name) throws IOException {
        OWLOntology ontology = AssistantBench.load(new File(FIXTURES, name));
        return Reasoners.Choice.HERMIT.newFactory().createReasoner(ontology);
    }

    @Test
    void consistentIsConsistentWithEveryClassSatisfiable() throws IOException {
        OWLReasoner reasoner = reason("consistent.ofn");
        try {
            assertTrue(reasoner.isConsistent());
            assertTrue(reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom().isEmpty());
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void unsatisfiableHasItsTwoUnsatisfiableClasses() throws IOException {
        OWLReasoner reasoner = reason("unsatisfiable.ofn");
        try {
            assertTrue(reasoner.isConsistent());
            Set<OWLClass> unsatisfiable =
                    reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom();
            assertEquals(2, unsatisfiable.size(), unsatisfiable.toString());
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void inconsistentIsInconsistent() throws IOException {
        OWLReasoner reasoner = reason("inconsistent.ofn");
        try {
            assertFalse(reasoner.isConsistent());
        } finally {
            reasoner.dispose();
        }
    }

    /** 1.144.0: the shapes beyond some and only, each once; nothing in it is broken. */
    @Test
    void restrictionsIsConsistentWithEveryClassSatisfiable() throws IOException {
        OWLReasoner reasoner = reason("restrictions.ofn");
        try {
            assertTrue(reasoner.isConsistent());
            assertTrue(reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom().isEmpty());
        } finally {
            reasoner.dispose();
        }
    }

    /** Only the decimal value clashes: the float one alone would be consistent. */
    @Test
    void theDatatypeClashIsInconsistent() throws IOException {
        OWLReasoner reasoner = reason("datatype-clash.ofn");
        try {
            assertFalse(reasoner.isConsistent());
        } finally {
            reasoner.dispose();
        }
    }
}
