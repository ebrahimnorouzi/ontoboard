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
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;
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
 * <p>An ontology renamed - Protégé reads a module built again under another name - keeps its
 * imports: what the manager links to the old name is forgotten as the rename is made
 * ({@link ImportLinks#forgetRenamed}).
 *
 * <p>Nothing is read again and no ontology changes: nothing is left unsaved. It cannot throw -
 * Protégé is in the middle of opening an ontology.
 */
public final class ImportLinksOnOpen implements OWLModelManagerListener,
        OWLOntologyChangeListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportLinksOnOpen.class);

    private final OWLModelManager models;
    private final Consumer<String> tell;
    private final Consumer<Runnable> later;
    private final Consumer<Runnable> tellLater;
    private final Set<File> told = new HashSet<File>();
    /** What was linked since it was last told of, by edit file. */
    private final java.util.Map<File, List<ImportLinks.Unlinked>> waiting =
            new java.util.HashMap<File, List<ImportLinks.Unlinked>>();

    /** Telling at once, as each linking ends. */
    ImportLinksOnOpen(OWLModelManager models, Consumer<String> tell, Consumer<Runnable> later) {
        this(models, tell, later, Runnable::run);
    }

    /**
     * @param tell      shows the person what was put in
     * @param later     runs the linking once Protégé has done with the event - on its event
     *                  thread
     * @param tellLater runs the telling once the files a run changed have all been read again
     */
    ImportLinksOnOpen(OWLModelManager models, Consumer<String> tell, Consumer<Runnable> later,
            Consumer<Runnable> tellLater) {
        this.models = models;
        this.tell = tell;
        this.later = later;
        this.tellLater = tellLater;
    }

    /**
     * Runs the last work it was given once, a moment after it was last given one, on Swing's
     * event thread. Protégé reads the files a run changed one by one, some three seconds each:
     * told of after each, the notice named the first file and said "1 file" where nfdicore's
     * release had renamed eight (Phase 7, 2026-11-20). Given on the event thread, as Protégé's
     * events are.
     */
    static final class AfterTheBurst implements Consumer<Runnable> {
        private final javax.swing.Timer timer;
        private Runnable last;

        AfterTheBurst(int millis) {
            timer = new javax.swing.Timer(millis, event -> {
                Runnable work = last;
                last = null;
                if (work != null) {
                    work.run();
                }
            });
            timer.setRepeats(false);
        }

        @Override
        public void accept(Runnable work) {
            last = work;
            timer.restart();
        }

        /** Lets go of the work waiting: the window it was for is closing. */
        void stop() {
            timer.stop();
            last = null;
        }
    }

    /** Watches the editor kit's ontologies from now on; what stops it. Never throws. */
    static Runnable watch(final OWLEditorKit kit) {
        try {
            OWLModelManager models = kit.getModelManager();
            AfterTheBurst telling = new AfterTheBurst(5000);
            ImportLinksOnOpen glue = new ImportLinksOnOpen(models,
                    text -> notice(kit, text), javax.swing.SwingUtilities::invokeLater, telling);
            return watch(models, glue, telling::stop);
        } catch (Throwable notWatched) {
            LOGGER.warn("OntoBoard does not watch for imports Protégé leaves out", notWatched);
            return () -> { };
        }
    }

    /**
     * Puts the glue on Protégé's model manager, and gives what takes it off again and lets go of
     * what waits to be told - run when the window closes: Protégé names a listener still there
     * as an ERROR (the hand check of 1.155.0).
     */
    static Runnable watch(final OWLModelManager models, final ImportLinksOnOpen glue,
            final Runnable letGo) {
        models.addListener(glue);
        models.addOntologyChangeListener(glue);
        return () -> {
            models.removeListener(glue);
            models.removeOntologyChangeListener(glue);
            letGo.run();
        };
    }

    @Override
    public void handleChange(OWLModelManagerChangeEvent event) {
        if (event.isType(EventType.ONTOLOGY_LOADED) || event.isType(EventType.ONTOLOGY_RELOADED)) {
            later.accept(this::linkNow);
        }
    }

    /**
     * Each rename, as it is made: before Protégé's reload makes its record of what is active
     * again, the imports answered by the renamed ontology find it. Never throws - it runs
     * inside the change.
     */
    @Override
    public void ontologiesChanged(List<? extends OWLOntologyChange> changes) {
        try {
            int forgotten = ImportLinks.forgetRenamed(models.getOWLOntologyManager(), changes);
            if (forgotten > 0) {
                boolean one = forgotten == 1;
                LOGGER.info("OntoBoard: {} renamed; the imports {} answered find {} by the new "
                        + "name", one ? "an ontology was" : forgotten + " ontologies were",
                        one ? "it" : "they", one ? "it" : "them");
            }
        } catch (Throwable notForgotten) {
            LOGGER.warn("OntoBoard could not keep the imports of a renamed ontology",
                    notForgotten);
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
            LOGGER.info("OntoBoard: {}", ImportLinks.said(linked, file));
            // Told of together, once the run's files have all been read again.
            final File editFile = file.getAbsoluteFile();
            List<ImportLinks.Unlinked> linkedSince = waiting.get(editFile);
            if (linkedSince == null) {
                linkedSince = new java.util.ArrayList<ImportLinks.Unlinked>();
                waiting.put(editFile, linkedSince);
            }
            linkedSince.addAll(linked);
            tellLater.accept(this::tellOfWhatWaits);
        } catch (Throwable notLinked) {
            LOGGER.warn("OntoBoard could not put in the imports Protégé left out", notLinked);
        }
    }

    /**
     * Tells of everything that waits, each project's apart: the wait keeps only the last thing
     * given it, and a first project linked within it was never told of (the final review of
     * 1.155.0).
     */
    private void tellOfWhatWaits() {
        for (File editFile : new java.util.ArrayList<File>(waiting.keySet())) {
            tellOf(editFile);
        }
    }

    /** Tells of what was linked since it was last told of - once a session for each file. */
    private void tellOf(File editFile) {
        try {
            List<ImportLinks.Unlinked> linked = waiting.remove(editFile);
            if (linked == null || linked.isEmpty() || !told.add(editFile)) {
                return;
            }
            tell.accept(ImportLinks.said(linked, editFile));
        } catch (Throwable notTold) {
            LOGGER.warn("OntoBoard could not tell of the imports it put in", notTold);
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

    /** A long sentence as a dialog shows it, at a readable width. */
    public static javax.swing.JComponent wrapped(String text) {
        javax.swing.JTextArea message = new javax.swing.JTextArea(text);
        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setOpaque(false);
        message.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        message.setColumns(52);
        message.setFont(javax.swing.UIManager.getFont("Label.font"));
        message.setSize(message.getPreferredSize().width, Short.MAX_VALUE);
        return message;
    }

    /** A notice that does not stop anything: the window stays usable while it is open. */
    private static void notice(OWLEditorKit kit, String text) {
        javax.swing.JComponent message = wrapped(text);
        javax.swing.JDialog dialog = new javax.swing.JOptionPane(message,
                javax.swing.JOptionPane.INFORMATION_MESSAGE)
                .createDialog(kit.getWorkspace(), "Imports put in");
        dialog.setModal(false);
        dialog.setVisible(true);
    }
}
