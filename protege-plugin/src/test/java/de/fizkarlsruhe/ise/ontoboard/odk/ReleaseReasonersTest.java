package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * The reasoners a release may reason with - the user's choice (2026-11-19: "for release the user
 * define the reasoner") - and which one the Release form offers first.
 */
class ReleaseReasonersTest {

    private static File project(File top, String yaml) throws Exception {
        File ontology = new File(top, "src/ontology");
        assertTrue(ontology.mkdirs());
        File edit = new File(ontology, "egg-edit.owl");
        Files.write(edit.toPath(), "Ontology(<http://purl.obolibrary.org/obo/egg.owl>)\n"
                .getBytes(StandardCharsets.UTF_8));
        if (yaml != null) {
            Files.write(new File(ontology, "egg-odk.yaml").toPath(),
                    yaml.getBytes(StandardCharsets.UTF_8));
        }
        return edit;
    }

    @Test
    void namesAreRobots() {
        // As ROBOT's reason --reasoner takes them, which is what REASONER= hands ODK.
        assertEquals(Arrays.asList("ELK", "HermiT", "JFact", "Whelk"), ReleaseReasoners.names());
    }

    @Test
    void aProjectThatSaysHermiTIsHermiT(@TempDir File top) throws Exception {
        File edit = project(top, "id: egg\ntitle: \"egg\"\nreasoner: HermiT\n");

        assertEquals("HermiT", ReleaseReasoners.ofProject(edit));
        assertEquals("HermiT", ReleaseReasoners.preselected(edit));
    }

    @Test
    void aProjectThatSaysElkPreselectsElk(@TempDir File top) throws Exception {
        File edit = project(top, "id: egg\nreasoner: ELK\n");

        assertEquals("ELK", ReleaseReasoners.preselected(edit));
    }

    @Test
    void odksDefaultIsNotAChoice(@TempDir File top) throws Exception {
        // ODK writes REASONER= ELK into every Makefile whether or not anybody chose it; only the
        // configuration says what a project chose. Nothing chosen: HermiT is offered first, so a
        // contradiction only a DL reasoner sees does not pass by default (the egg project).
        File edit = project(top, "id: egg\ntitle: \"egg\"\n");

        assertNull(ReleaseReasoners.ofProject(edit));
        assertEquals("HermiT", ReleaseReasoners.preselected(edit));
    }

    @Test
    void aFileInNoProjectPreselectsHermiT(@TempDir File top) throws Exception {
        File alone = new File(top, "pizza.owl");
        Files.write(alone.toPath(), "Ontology()\n".getBytes(StandardCharsets.UTF_8));

        assertNull(ReleaseReasoners.ofProject(alone));
        assertEquals("HermiT", ReleaseReasoners.preselected(alone));
        assertEquals("HermiT", ReleaseReasoners.preselected(null));
    }

    @Test
    void aReasonerRobotDoesNotKnowIsKeptOutOfTheChoice(@TempDir File top) throws Exception {
        // Written by hand into the configuration: ODK would hand it to ROBOT, which would stop.
        // The form does not offer it; it offers what the project would fail without.
        File edit = project(top, "id: egg\nreasoner: Pellet\n");

        assertEquals("Pellet", ReleaseReasoners.ofProject(edit));
        assertEquals("HermiT", ReleaseReasoners.preselected(edit));
    }

    @Test
    void namesAreMatchedWithoutCase() {
        assertEquals("HermiT", ReleaseReasoners.canonical("hermit"));
        assertEquals("ELK", ReleaseReasoners.canonical(" elk "));
        assertNull(ReleaseReasoners.canonical("Pellet"));
        assertNull(ReleaseReasoners.canonical(null));
    }

    @Test
    void factoryForEach() throws Exception {
        OWLOntology empty = OWLManager.createOWLOntologyManager().createOntology();
        for (String name : ReleaseReasoners.names()) {
            OWLReasonerFactory factory = ReleaseReasoners.factory(name);
            assertNotNull(factory, name);
            OWLReasoner reasoner = factory.createReasoner(empty);
            try {
                assertTrue(reasoner.isConsistent(), name + " reasons");
            } finally {
                reasoner.dispose();
            }
        }
        assertThrows(IllegalArgumentException.class, () -> ReleaseReasoners.factory("Pellet"));
    }

    @Test
    void elkSaysWhatItMisses() {
        String elk = ReleaseReasoners.whatItMisses("ELK");
        assertNotNull(elk);
        assertTrue(elk.contains("inverse properties"), elk);
        assertTrue(elk.contains("OWL 2 EL"), elk);
        assertNull(ReleaseReasoners.whatItMisses("HermiT"));
        assertNull(ReleaseReasoners.whatItMisses("JFact"));
        // Whelk is an EL reasoner too.
        assertNotNull(ReleaseReasoners.whatItMisses("Whelk"));
        assertFalse(ReleaseReasoners.isComplete("ELK"));
        assertTrue(ReleaseReasoners.isComplete("HermiT"));
    }
}
