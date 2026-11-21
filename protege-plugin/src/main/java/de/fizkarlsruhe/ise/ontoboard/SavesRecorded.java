package de.fizkarlsruhe.ise.ontoboard;

import de.fizkarlsruhe.ise.ontoboard.odk.SavedMeanwhile;
import java.io.File;
import java.net.URI;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.io.IOListener;
import org.protege.editor.owl.model.io.IOListenerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Protege's own saves, recorded with when they were made: a release that went on behind while
 * the person worked (Keep working) names what was saved meanwhile before it commits (the 1.155.0
 * review's I3).
 */
public final class SavesRecorded extends IOListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(SavesRecorded.class);

    /**
     * Puts the recorder on the window's model manager, and gives what takes it off again - run
     * when the window closes.
     */
    static Runnable watch(OWLEditorKit kit) {
        try {
            final OWLModelManager models = kit.getModelManager();
            final SavesRecorded recorder = new SavesRecorded();
            models.addIOListener(recorder);
            return () -> models.removeIOListener(recorder);
        } catch (Throwable notWatched) {
            LOGGER.warn("OntoBoard does not record what is saved while a release runs behind",
                    notWatched);
            return () -> { };
        }
    }

    @Override
    public void afterSave(IOListenerEvent event) {
        URI saved = event == null ? null : event.getPhysicalURI();
        if (saved != null && "file".equalsIgnoreCase(saved.getScheme())) {
            try {
                SavedMeanwhile.saved(new File(saved));
            } catch (IllegalArgumentException notAFile) {
                // A file: URI with a host or a query is no file of this machine.
            }
        }
    }

    @Override
    public void beforeSave(IOListenerEvent event) {
    }

    @Override
    public void beforeLoad(IOListenerEvent event) {
    }

    @Override
    public void afterLoad(IOListenerEvent event) {
    }
}
