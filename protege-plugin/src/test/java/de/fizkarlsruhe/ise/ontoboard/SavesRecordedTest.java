package de.fizkarlsruhe.ise.ontoboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.SavedMeanwhile;
import java.io.File;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.owl.model.io.IOListenerEvent;
import org.semanticweb.owlapi.model.OWLOntologyID;

/**
 * Protege's saves are recorded for a release kept behind, which names what was saved while it
 * ran before it commits (the 1.155.0 review's I3).
 */
class SavesRecordedTest {

    @Test
    void anOntologySavedInProtegeIsRecorded(@TempDir File dir) {
        File edit = new File(dir, "src/ontology/egg-edit.owl");
        long before = System.currentTimeMillis();

        new SavesRecorded().afterSave(new IOListenerEvent(new OWLOntologyID(), edit.toURI()));

        assertEquals(Arrays.asList("src/ontology/egg-edit.owl"),
                SavedMeanwhile.under(dir, before));
    }

    /** Loading is not saving; and what is not a file is not recorded. */
    @Test
    void onlyASaveToAFileIsRecorded(@TempDir File dir) throws Exception {
        long before = System.currentTimeMillis();
        SavesRecorded recorder = new SavesRecorded();

        recorder.afterLoad(new IOListenerEvent(new OWLOntologyID(),
                new File(dir, "loaded.owl").toURI()));
        recorder.afterSave(new IOListenerEvent(new OWLOntologyID(),
                new java.net.URI("http://example.org/egg.owl")));
        recorder.afterSave(null);

        assertTrue(SavedMeanwhile.under(dir, before).isEmpty());
    }
}
