package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * A test about a class that was deleted, written before tests asked for {@code ?term a owl:Class}:
 * the build says what it is about and what to do, rather than failing on a bare IRI for ever.
 */
class ChecksOnDeletedTermsTest {

    private static final String GONE = "http://purl.obolibrary.org/obo/demo.owl#Gone";

    @Test
    void aTermTheOntologyDoesNotHaveIsNamed() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology(
                IRI.create("http://purl.obolibrary.org/obo/demo.owl"));
        assertEquals(GONE, InProcessTargets.goneFrom(ontology,
                Collections.singletonList(Arrays.asList(GONE, ""))));
        assertNull(InProcessTargets.goneFrom(ontology,
                Collections.singletonList(Arrays.asList("a literal", ""))));
        assertNull(InProcessTargets.goneFrom(ontology, Collections.<List<String>>emptyList()));
    }

    @Test
    void theBuildSaysToDeleteATestAboutNothing(@TempDir File where) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("demo", "Test ontology",
                "Scaffolded by a test", "http://purl.obolibrary.org/obo/demo.owl",
                "https://creativecommons.org/licenses/by/4.0/", where);
        OdkScaffold.create(config);
        File edit = config.getEditFile();
        // As the test writer wrote it until 1.135.1, on a class since deleted.
        File test = new File(new File(InProcessTargets.projectRoot(edit), "src"),
                "sparql" + File.separator + "every_kind_of_gone_has_a_label.rq");
        Files.write(test.toPath(), ("# Every Gone, and every kind of it, has a label?\n"
                + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                + "SELECT DISTINCT ?term WHERE {\n"
                + "  { ?term rdfs:subClassOf* <" + GONE + "> . FILTER(isIRI(?term)) }\n"
                + "  FILTER NOT EXISTS { ?term rdfs:label ?label }\n"
                + "}\n").getBytes("UTF-8"));

        InProcessTargets.Outcome outcome = InProcessTargets.run(edit,
                OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                        IRI.create(edit.toURI())),
                de.fizkarlsruhe.ise.ontoboard.robot.Reasoners.Choice.ELK.newFactory(),
                "sparql_test");

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("is not in the ontology any more"),
                outcome.getFailure());
        assertTrue(outcome.getFailure().contains(
                "delete src/sparql/every_kind_of_gone_has_a_label.rq"), outcome.getFailure());
    }
}
