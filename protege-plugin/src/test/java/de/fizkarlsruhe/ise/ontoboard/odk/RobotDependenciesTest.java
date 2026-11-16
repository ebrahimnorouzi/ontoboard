package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.obolibrary.robot.IOHelper;
import org.obolibrary.robot.OBOWriteOption;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.OBODocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * The ROBOT inside OntoBoard runs in the oldest Protégé it supports, 5.5.0: what it brings is
 * Java 8, and what it calls of the OWL API is in that Protégé's, 4.5.9.
 *
 * <p>Both have stopped an upgrade. ODK v1.6.1's image has ROBOT 1.9.10. Its own classes are Java
 * 8, but relation-graph 2.3.3, which it asks for, brings ZIO 2.1.18, whose classes are Java 11.
 * And from 1.9.9 on, ROBOT writes OBO through the prefixes of the OWL API's OBO format
 * ({@code IOHelper.makeCleanOBODocument} hands {@code format.asPrefixOWLOntologyFormat()} to
 * {@code OWLAPIOwl2Obo.setPrefixManager}), which OWL API 4.5.9 does not have: there every
 * {@code .obo} fails with "OBODocumentFormat is not a PrefixDocumentFormat", and a release
 * writes one. So the ROBOT inside stays 1.9.8 (1.154.0).
 */
class RobotDependenciesTest {

    @ParameterizedTest
    @ValueSource(strings = {"zio/ZIO.class", "org/geneontology/whelk/Reasoner.class",
            "org/renci/relationgraph/RelationGraph.class",
            "org/obolibrary/robot/CommandLineHelper.class"})
    void whatRobotBringsLoadsOnJava8(String resource) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            byte[] head = new byte[8];
            assertEquals(8, in.read(head));
            assertTrue(((head[6] & 0xff) << 8 | (head[7] & 0xff)) <= 52,
                    resource + " is above Java 8");
        }
    }

    /**
     * As ODK's release writes its {@code .obo}: {@code convert -f obo --clean-obo "strict
     * drop-untranslatable-axioms"}, ODK's default {@code obo_format_options}. The tests run on
     * the OWL API of Protégé 5.5.0 (the pom's {@code owlapi.version}).
     */
    @Test
    void anOboFileIsWrittenOnTheOldestProtegesOwlApi(@TempDir File temp) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(
                IRI.create("http://purl.obolibrary.org/obo/x.owl"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(factory.getOWLClass(
                IRI.create("http://purl.obolibrary.org/obo/X_0000001"))));
        File obo = new File(temp, "x.obo");

        new IOHelper().saveOntology(ontology, new OBODocumentFormat(), IRI.create(obo),
                Collections.<String, String>emptyMap(), false,
                OBOWriteOption.fromString("strict drop-untranslatable-axioms"));

        assertTrue(obo.isFile(), "ROBOT wrote the OBO file");
    }
}
