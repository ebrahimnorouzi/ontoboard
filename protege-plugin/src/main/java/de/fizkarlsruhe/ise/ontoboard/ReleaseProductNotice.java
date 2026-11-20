package de.fizkarlsruhe.ise.ontoboard;

import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseProducts;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.event.OWLModelManagerChangeEvent;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A release file of an ODK project opened in Protégé - {@code egg-full.owl}, {@code egg.owl} at
 * the project's top, a copy under {@code releases/<version>/} - is said for what it is when it is
 * opened, once for each file in a session, with the way to the edit file (1.155.0: the user
 * asked what happens when they open egg-full.owl or another of the release's files).
 */
public final class ReleaseProductNotice implements OWLModelManagerListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReleaseProductNotice.class);

    private final OWLEditorKit kit;
    private final Set<File> told = new HashSet<File>();

    private ReleaseProductNotice(OWLEditorKit kit) {
        this.kit = kit;
    }

    /** Watches the editor kit's ontologies from now on; what stops it. Never throws. */
    static Runnable watch(OWLEditorKit kit) {
        try {
            return watch(kit.getModelManager(), new ReleaseProductNotice(kit));
        } catch (Throwable notWatched) {
            LOGGER.warn("OntoBoard does not say when a release file is opened", notWatched);
            return () -> { };
        }
    }

    /**
     * Puts the notice on Protégé's model manager, and gives what takes it off again - run when
     * the window closes: Protégé names a listener still there as an ERROR (the hand check of
     * 1.155.0).
     */
    static Runnable watch(final OWLModelManager models, final OWLModelManagerListener notice) {
        models.addListener(notice);
        return () -> models.removeListener(notice);
    }

    /**
     * What to say when {@code document} is opened, or null: it is no release file of an ODK
     * project - the edit file is none - or it was told of already in this session.
     */
    static String decide(File document, Set<File> told) {
        ReleaseProducts.Product product = document == null ? null
                : ReleaseProducts.of(document);
        if (product == null || !told.add(canonical(document))) {
            return null;
        }
        if (product.where() == ReleaseProducts.Where.RELEASES) {
            return product.fileShown() + " is the " + product.version() + " release of the ODK "
                    + "project " + product.id() + ", kept as it was published: what is changed "
                    + "here would change a published release. To work on the ontology, open the "
                    + "edit file, " + product.editFileShown() + ".";
        }
        return product.fileShown() + " is a release file of the ODK project " + product.id()
                + ", made from " + product.editFileShown() + ": what is changed here is not "
                + "kept - the next release writes it again from the edit file. To work on the "
                + "ontology, open the edit file.";
    }

    private static File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException cannot) {
            return file.getAbsoluteFile();
        }
    }

    /** As History opens a release: Protégé's own way, in a window of its own. */
    public static void openInANewWindow(File file) throws Exception {
        org.protege.editor.core.ProtegeManager protege =
                org.protege.editor.core.ProtegeManager.getInstance();
        org.protege.editor.core.editorkit.EditorKitFactoryPlugin owl = null;
        for (org.protege.editor.core.editorkit.EditorKitFactoryPlugin plugin
                : protege.getEditorKitFactoryPlugins()) {
            if (owl == null || String.valueOf(plugin.getId()).contains("OWL")) {
                owl = plugin;
            }
        }
        if (owl == null || !protege.loadAndSetupEditorKitFromURI(owl, file.toURI())) {
            LOGGER.warn("OntoBoard: Protégé did not open {}", file);
        }
    }

    @Override
    public void handleChange(OWLModelManagerChangeEvent event) {
        if (!event.isType(EventType.ONTOLOGY_LOADED)) {
            return;
        }
        javax.swing.SwingUtilities.invokeLater(this::tellNow);
    }

    private void tellNow() {
        try {
            OWLModelManager models = kit.getModelManager();
            OWLOntology active = models.getActiveOntology();
            IRI document = active == null ? null
                    : models.getOWLOntologyManager().getOntologyDocumentIRI(active);
            File file = document == null || !"file".equalsIgnoreCase(document.getScheme())
                    ? null : new File(URI.create(document.toString()));
            String said = decide(file, told);
            if (said == null) {
                return;
            }
            LOGGER.info("OntoBoard: {}", said);
            Object[] choices = {"Open the edit file", "Keep this file"};
            int chosen = javax.swing.JOptionPane.showOptionDialog(kit.getWorkspace(),
                    ImportLinksOnOpen.wrapped(said),
                    "A release file", javax.swing.JOptionPane.DEFAULT_OPTION,
                    javax.swing.JOptionPane.INFORMATION_MESSAGE, null, choices, choices[0]);
            if (chosen == 0) {
                openInANewWindow(ReleaseProducts.of(file).editFile());
            }
        } catch (Throwable notTold) {
            LOGGER.warn("OntoBoard could not say what the opened file is", notTold);
        }
    }
}
