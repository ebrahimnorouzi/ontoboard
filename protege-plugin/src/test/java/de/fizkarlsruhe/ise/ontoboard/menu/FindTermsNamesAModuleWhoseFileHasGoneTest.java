package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Find terms names the module it will write before it writes it, and the file it read the
 * ontology from may have gone meanwhile - moved, renamed, a clean-up of Downloads. The name is
 * still given (1.135.2 review): the check that the file exists threw on the Swing thread, and
 * the confirmation never came.
 */
class FindTermsNamesAModuleWhoseFileHasGoneTest {

    @TempDir
    File root;

    @Test
    void aModuleIsNamedThoughTheFileItWasReadFromHasGone() throws Exception {
        OWLOntology loaded = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("http://www.w3.org/2006/time"));
        String gone = new File(root, "Downloads/time.owl").getAbsolutePath();

        assertEquals("time", ImportTermsAction.moduleNameFor(root, loaded, gone));
    }
}
