package de.fizkarlsruhe.ise.ontoboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.obolibrary.robot.IOHelper;
import org.obolibrary.robot.ReasonOperation;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

class RobotCoreInteropTest {

    private static final File FIXTURE = new File("src/test/resources/fixture-tiny.ttl");

    /**
     * The host ships OWL API 4.5.9; robot-core declares 4.5.29. The dependencyManagement
     * block forces the downgrade. If a future bump lets 4.5.29 back in, the plugin would
     * compile here and then fail inside Protege - so pin the expectation.
     */
    @Test
    void owlApiIsPinnedToTheVersionTheHostShips() throws Exception {
        String owlApiJar = new File(OWLOntology.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getName();
        assertTrue(owlApiJar.contains("4.5.9"),
                "expected OWL API 4.5.9 to match Protege 5.5.0, but resolved " + owlApiJar);
    }

    /** Reasoning is the most OWL-API-intensive ROBOT operation, so exercise it directly. */
    @Test
    void robotCoreCanReasonAgainstTheHostsOwlApi() throws Exception {
        OWLOntology ontology = new IOHelper().loadOntology(FIXTURE);
        int before = ontology.getAxiomCount();

        OWLReasonerFactory factory = (OWLReasonerFactory)
                Class.forName("org.semanticweb.elk.owlapi.ElkReasonerFactory")
                        .getDeclaredConstructor().newInstance();
        ReasonOperation.reason(ontology, factory);

        assertTrue(ontology.getAxiomCount() > before,
                "ELK should have materialised at least one inferred axiom");
    }

    @Test
    void bothPathsReadTheSameOntology() throws Exception {
        OWLOntology viaRobot = new IOHelper().loadOntology(FIXTURE);
        OWLOntology viaOwlApi = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(FIXTURE);

        assertEquals(3, viaRobot.getClassesInSignature().size());
        assertEquals(viaOwlApi.getClassesInSignature().size(),
                     viaRobot.getClassesInSignature().size());
    }
}
