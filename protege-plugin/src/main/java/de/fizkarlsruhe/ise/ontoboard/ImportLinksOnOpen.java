package de.fizkarlsruhe.ise.ontoboard;

import de.fizkarlsruhe.ise.ontoboard.odk.ImportLinks;
import java.io.File;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.event.OWLModelManagerChangeEvent;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * When Protégé opens an ontology, or reads one again, the imports it read and left out because
 * their files call themselves by another name are put in ({@link ImportLinks}) - as ROBOT and
 * ODK's make read them - and Protégé is asked to make its record of what is active again, so
 * every view, Measure and the reasoner see them. The person is told once for each file; the log
 * says it each time (1.155.0: nfdicore's eight modules).
 *
 * <p>Nothing is read again and no ontology changes: nothing is left unsaved. It cannot throw -
 * Protégé is in the middle of opening an ontology.
 */
public final class ImportLinksOnOpen implements OWLModelManagerListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportLinksOnOpen.class);

    private final OWLModelManager models;
    private final Consumer<String> tell;
    private final Consumer<Runnable> later;
    private final Set<File> told = new HashSet<File>();

    /**
     * @param tell  shows the person what was put in
     * @param later runs the linking once Protégé has done with the event - on its event thread
     */
    ImportLinksOnOpen(OWLModelManager models, Consumer<String> tell, Consumer<Runnable> later) {
        this.models = models;
        this.tell = tell;
        this.later = later;
    }

    /** Watches the editor kit's ontologies from now on. Never throws. */
    static void watch(final OWLEditorKit kit) {
        try {
            OWLModelManager models = kit.getModelManager();
            models.addListener(new ImportLinksOnOpen(models,
                    text -> notice(kit, text), javax.swing.SwingUtilities::invokeLater));
        } catch (Throwable notWatched) {
            LOGGER.warn("OntoBoard does not watch for imports Protégé leaves out", notWatched);
        }
    }

    @Override
    public void handleChange(OWLModelManagerChangeEvent event) {
        if (event.isType(EventType.ONTOLOGY_LOADED) || event.isType(EventType.ONTOLOGY_RELOADED)) {
            later.accept(this::linkNow);
        }
    }

    /** Puts in what the active ontology's catalog gives and Protégé left out. */
    void linkNow() {
        try {
            OWLOntology active = models.getActiveOntology();
            File file = active == null ? null : fileOf(models.getOWLOntologyManager(), active);
            if (file == null || file.getParentFile() == null) {
                return;
            }
            List<ImportLinks.Unlinked> linked = ImportLinks.link(active,
                    new File(file.getParentFile(), "catalog-v001.xml"));
            if (linked.isEmpty()) {
                return;
            }
            // The same strategy again: Protégé makes its record of the active ontologies, its
            // indices and its renderer anew, and says the active ontology changed.
            models.setActiveOntologiesStrategy(models.getActiveOntologiesStrategy());
            String said = ImportLinks.said(linked, file);
            LOGGER.info("OntoBoard: {}", said);
            if (told.add(file.getAbsoluteFile())) {
                tell.accept(said);
            }
        } catch (Throwable notLinked) {
            LOGGER.warn("OntoBoard could not put in the imports Protégé left out", notLinked);
        }
    }

    private static File fileOf(OWLOntologyManager manager, OWLOntology ontology) {
        IRI document = manager.getOntologyDocumentIRI(ontology);
        if (document == null || !"file".equalsIgnoreCase(document.getScheme())) {
            return null;
        }
        try {
            return new File(URI.create(document.toString()));
        } catch (IllegalArgumentException notAFile) {
            return null;
        }
    }

    /** A notice that does not stop anything: the window stays usable while it is open. */
    private static void notice(OWLEditorKit kit, String text) {
        javax.swing.JTextArea message = new javax.swing.JTextArea(text);
        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setOpaque(false);
        message.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        message.setColumns(52);
        message.setFont(javax.swing.UIManager.getFont("Label.font"));
        message.setSize(message.getPreferredSize().width, Short.MAX_VALUE);
        javax.swing.JDialog dialog = new javax.swing.JOptionPane(message,
                javax.swing.JOptionPane.INFORMATION_MESSAGE)
                .createDialog(kit.getWorkspace(), "Imports put in");
        dialog.setModal(false);
        dialog.setVisible(true);
    }
}
