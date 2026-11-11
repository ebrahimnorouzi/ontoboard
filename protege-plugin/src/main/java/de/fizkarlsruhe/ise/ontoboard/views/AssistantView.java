package de.fizkarlsruhe.ise.ontoboard.views;

import de.fizkarlsruhe.ise.ontoboard.assistant.BuildInfo;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.MarkdownExport;
import de.fizkarlsruhe.ise.ontoboard.assistant.history.ConversationLog;
import de.fizkarlsruhe.ise.ontoboard.assistant.history.ConversationRecorder;
import de.fizkarlsruhe.ise.ontoboard.assistant.history.KeyRedactor;
import de.fizkarlsruhe.ise.ontoboard.assistant.ui.HistoryPanel;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ApiKeys;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextBuilder;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextItem;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.Problems;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.TermLine;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.TermLines;
import de.fizkarlsruhe.ise.ontoboard.assistant.host.AssistantPreferences;
import de.fizkarlsruhe.ise.ontoboard.assistant.host.AssistantSessions;
import de.fizkarlsruhe.ise.ontoboard.assistant.project.ProjectFolder;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.Role;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.RoleLibrary;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.RoleStore;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.AssistantSession;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.OntologyAccess;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.Questions;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.SessionKeys;
import de.fizkarlsruhe.ise.ontoboard.assistant.ui.AssistantPanel;
import de.fizkarlsruhe.ise.ontoboard.assistant.ui.SettingsPanel;
import de.fizkarlsruhe.ise.ontoboard.assistant.ui.WhatIsSentDialog;
import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.robot.Explanations;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import de.fizkarlsruhe.ise.ontoboard.robot.TermSearch;
import de.fizkarlsruhe.ise.ontoboard.sheet.TemplateDiscovery;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.protege.editor.owl.model.selection.OWLSelectionModelListener;
import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * The assistant column (spec D6): the right-hand column of the OntoBoard tab, and a view any tab
 * can place from Window &gt; Views.
 *
 * <p>The only assistant class in this package, and it must stay so: {@code ShippedLayout} counts
 * every component whose class is in {@code views} as one of the tab's views, so the column's
 * panels live in {@code assistant.ui}, where they cannot hide a view that went missing.
 *
 * <p>This class is the bridge and nothing more. It keeps one session per ontology - a
 * conversation is about one ontology, and switching to another shows that one's - and hands each
 * session the active connection with its key (typed this session, from the environment, or
 * remembered only if the person ticked the box), the role, and the project's local-only switch.
 * It answers the panel's questions about the selection and the reasoner. It changes the ontology
 * in one place only: a proposal the person accepted, applied through the model manager as one
 * change list, so one Edit > Undo takes it back.
 */
public class AssistantView extends AbstractOWLViewComponent {

    private static final long serialVersionUID = 1L;

    private final Map<OWLOntologyID, AssistantSession> sessions =
            new HashMap<OWLOntologyID, AssistantSession>();
    private ExecutorService background;
    /** Reasoner checks of proposed removals, apart from model calls (1.138.0, review I4). */
    private ExecutorService checker;
    private AssistantPreferences preferences;
    private AssistantPanel panel;
    private SettingsPanel settings;
    private HistoryPanel history;
    private JTabbedPane tabs;
    private JPanel cards;
    private CardLayout cardLayout;
    private AssistantSession shown;
    private TermSearch search;
    private OWLOntology searched;

    private final OWLSelectionModelListener selectionListener = this::refresh;
    private final OWLModelManagerListener modelListener = event -> {
        if (event.isType(EventType.ACTIVE_ONTOLOGY_CHANGED)
                || event.isType(EventType.ONTOLOGY_LOADED)
                || event.isType(EventType.ONTOLOGY_RELOADED)) {
            SwingUtilities.invokeLater(this::showCurrent);
        } else if (event.isType(EventType.ONTOLOGY_SAVED)) {
            // A first save gives the ontology its project folder, and Save As may give it another:
            // the session's local-only switch and role, and Settings' role line, follow at once.
            // Settings said Protégé's preferences while Save wrote next to the ontology, and the
            // view noticed only at the next show or ontology change (1.146.0, review 1.145.0 ui-2).
            SwingUtilities.invokeLater(this::projectMayHaveChanged);
        }
    };
    /**
     * An edit only invalidates what was rendered (review #2): the chips are rendered again when
     * they are next needed, once, rather than on every selection change after it.
     */
    private final OWLOntologyChangeListener changeListener = changes -> {
        search = null;
        // A change to the logic leaves a problem chip to be asked about again before it is sent:
        // it told the model the ontology was inconsistent after the repair too (1.151.0, asked by
        // the person). A label or a comment cannot change what the reasoner finds.
        boolean logic = false;
        for (org.semanticweb.owlapi.model.OWLOntologyChange change : changes) {
            if (change.isImportChange()
                    || change.isAxiomChange() && change.getAxiom().isLogicalAxiom()) {
                logic = true;
                break;
            }
        }
        for (AssistantSession session : sessions.values()) {
            session.ontologyChanged(logic);
        }
        SwingUtilities.invokeLater(this::refresh);
    };

    /** A refresh skipped while the column was hidden, done when it is shown again. */
    private boolean stale;

    /**
     * Reports to {@link ViewHealth} either way, as the board does: Protégé catches whatever this
     * throws and shows an error label instead, so the host self-test asks the register rather
     * than the screen - and a view that does not report reads as one that never built.
     */
    @Override
    protected void initialiseOWLView() {
        try {
            build();
        } catch (RuntimeException | Error broke) {
            ViewHealth.failed(getClass().getSimpleName(), broke);
            throw broke;
        }
        ViewHealth.constructed(getClass().getSimpleName());
    }

    private void build() {
        setLayout(new BorderLayout());
        background = Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, "OntoBoard assistant");
            thread.setDaemon(true);
            return thread;
        });
        checker = Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, "OntoBoard assistant checks");
            thread.setDaemon(true);
            return thread;
        });
        preferences = AssistantPreferences.load();
        panel = new AssistantPanel(new Bridge());
        settings = new SettingsPanel(preferences, this::projectFolder, this::settingsChanged);

        cardLayout = new CardLayout();
        cards = new JPanel(cardLayout);
        cards.add(setUpFirst(), "setup");
        cards.add(panel, "chat");
        tabs = new JTabbedPane();
        tabs.addTab("Assistant", cards);
        tabs.addTab("Settings", settings);
        history = new HistoryPanel(this::projectFolder);
        tabs.addTab("History", history);
        tabs.addChangeListener(event -> {
            if (tabs.getSelectedComponent() == history) {
                history.reload();
            }
        });
        add(tabs, BorderLayout.CENTER);

        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                    && isShowing() && stale) {
                refresh();
            }
        });
        getOWLWorkspace().getOWLSelectionModel().addListener(selectionListener);
        getOWLModelManager().addListener(modelListener);
        getOWLModelManager().addOntologyChangeListener(changeListener);
        AssistantSessions.register(getOWLEditorKit(),
                iri -> SwingUtilities.invokeLater(() -> {
                    AssistantSession session = current();
                    if (session != null) {
                        session.addContext(ContextItem.term(iri, false));
                        tabs.setSelectedIndex(0);
                    }
                }));
        showCurrent();
    }

    @Override
    protected void disposeOWLView() {
        getOWLWorkspace().getOWLSelectionModel().removeListener(selectionListener);
        getOWLModelManager().removeListener(modelListener);
        getOWLModelManager().removeOntologyChangeListener(changeListener);
        AssistantSessions.unregister(getOWLEditorKit());
        for (AssistantSession session : sessions.values()) {
            session.stop();
        }
        sessions.clear();
        if (background != null) {
            background.shutdownNow();
        }
        if (checker != null) {
            checker.shutdownNow();
        }
    }

    private JPanel setUpFirst() {
        JPanel empty = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 40));
        JLabel words = new JLabel("<html><div style='width:220px;text-align:center'>"
                + "The assistant needs a model: a key for a cloud model, or Ollama running on"
                + " this machine.</div></html>");
        JButton open = new JButton("Set up a model…");
        open.addActionListener(event -> tabs.setSelectedIndex(1));
        empty.add(words);
        empty.add(open);
        return empty;
    }

    /**
     * The active ontology's session, made the first time it is needed; null while there is no
     * active ontology. Protégé can build a tab's views before any ontology is active - measured in
     * the host self-test, where every hierarchy view threw on that and this one did too, until
     * this returned null instead.
     */
    private AssistantSession current() {
        final OWLOntology ontology = getOWLModelManager().getActiveOntology();
        if (ontology == null) {
            return null;
        }
        AssistantSession session = sessions.get(ontology.getOntologyID());
        if (session == null) {
            session = new AssistantSession(new OntologyAccess() {
                @Override
                public OWLOntology ontology() {
                    return ontology;
                }

                @Override
                public IRI selected() {
                    return selectedIri();
                }

                @Override
                public File ontologyFile() {
                    return fileOf(ontology);
                }
            }, background, SwingUtilities::invokeLater);
            session.setConfirmer((host, tokens, subject) -> JOptionPane.showConfirmDialog(this,
                    "This sends about " + tokens + " tokens about " + subject + " to " + host
                            + ".\n\n\"What is sent\" shows the exact text. You are asked once"
                            + " for each server in this session.",
                    "Send to a cloud model?", JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION);
            // Through the model manager: one Edit > Undo, and every view hears of it.
            session.setProposalApplier(changes -> getOWLModelManager().applyChanges(changes));
            session.setCheckExecutor(checker);
            final AssistantSession recorded = session;
            ConversationRecorder.attach(session, () -> ProjectFolder.of(fileOf(ontology)),
                    folder -> ProjectFolder.ConversationSharing.of(
                            preferences.conversationSharing(folder.key())),
                    turn -> header(ontology, recorded, turn), this::knownKeys,
                    notice -> panel.onNotice(notice), problem -> panel.onNotice(problem));
            sessions.put(ontology.getOntologyID(), session);
            configure(session, ontology);
        }
        // The reasoner a removal is checked with is the one Protégé runs now, chosen in its
        // Reasoner menu; HermiT only when none is chosen.
        Object[] reasoner = runningReasoner();
        session.setReasoner((OWLReasonerFactory) reasoner[0], (String) reasoner[1]);
        return session;
    }

    /** The active connection with its key, the role and the project's switch. */
    private void configure(AssistantSession session, OWLOntology ontology) {
        Connection active = preferences.active();
        String key = active == null ? "" : ApiKeys.resolve(active,
                SessionKeys.get(active.getName()), System.getenv(),
                preferences.rememberedKey(active.getName())).getKey();
        session.setConnection(active, key);
        configureProject(session, ontology);
    }

    /**
     * The parts that follow the ontology's project folder: the role, the local-only switch, the
     * rules for saved conversations and provenance. Not the connection: a save re-applied it, and
     * an answer in progress stopped when another view had changed the active connection (1.147.0,
     * review 1.146.0 ui-4).
     */
    private void configureProject(AssistantSession session, OWLOntology ontology) {
        ProjectFolder project = ProjectFolder.of(fileOf(ontology));
        RoleStore store = project != null ? project.roles(this::knownKeys) : preferences.roles();
        try {
            RoleLibrary library = new RoleLibrary(store);
            Role role = library.get(preferences.roleId());
            if (role == null) {
                role = library.get("ontology-expert");
            }
            session.setRole(role.getName(), RoleLibrary.render(role, preferences.field()));
        } catch (IOException unreadable) {
            session.setRole("Ontology expert", RoleLibrary.shipped("ontology-expert"));
        }
        session.setLocalModelsOnly(project != null && project.isLocalModelsOnly());
        if (project != null && project.isSavingConversations()) {
            // When a project is opened or Settings change, not only before the next save: a pull
            // can have removed the block or put a line after it (1.144.0, the git probe).
            try {
                project.ensureConversationRules();
            } catch (IOException notNow) {
                // The recorder says so, and saves nothing, at the next answer.
            }
        }
        session.setProvenance(preferences.isProvenance());
    }

    /** A saved conversation's header: when, which OntoBoard, which ontology, model and role. */
    private ConversationLog.Header header(OWLOntology ontology, AssistantSession session,
            de.fizkarlsruhe.ise.ontoboard.assistant.chat.Turn turn) {
        Connection connection = session.getConnection();
        String iri = ontology.getOntologyID().getOntologyIRI().isPresent()
                ? ontology.getOntologyID().getOntologyIRI().get().toString() : null;
        String version = ontology.getOntologyID().getVersionIRI().isPresent()
                ? ontology.getOntologyID().getVersionIRI().get().toString() : null;
        return new ConversationLog.Header(turn.getStarted(), BuildInfo.version(), iri, version,
                connection == null ? "" : connection.getName(),
                connection == null ? "" : connection.getKind().getId(),
                connection == null ? "" : connection.getHost(),
                connection == null ? "" : connection.getModel(), session.getRoleName());
    }

    /**
     * Every key OntoBoard knows now or knew before in this session, so none of them is ever
     * written into the project - not after it was replaced in Settings either (1.145.0, review
     * 1.144.0 privacy-01).
     */
    private java.util.Collection<String> knownKeys() {
        return KeyRedactor.everKnown(preferences.connections(), System.getenv(),
                preferences::rememberedKey);
    }

    private void showCurrent() {
        if (panel == null) {
            return;
        }
        shown = current();
        if (shown != null) {
            // The project folder, and so the roles and the local-only switch, follow the
            // ontology: read here and when Settings change, not at every selection (review #2).
            configure(shown, getOWLModelManager().getActiveOntology());
        }
        panel.showSession(shown);
        settings.reload();
        if (tabs.getSelectedComponent() == history) {
            history.reload();
        }
        refreshShown();
    }

    /**
     * After the ontology was saved, perhaps to its first or another folder: the project's parts
     * of the session, Settings, and the column's line, which named the old role and size until
     * the next refresh (1.147.0, review 1.146.0 ui-3).
     */
    private void projectMayHaveChanged() {
        if (panel == null) {
            return;
        }
        if (shown != null) {
            configureProject(shown, getOWLModelManager().getActiveOntology());
        }
        settings.projectMayHaveChanged();
        refreshShown();
    }

    /** After a selection or an edit. Skipped while the column is hidden (review #2). */
    private void refresh() {
        if (panel == null) {
            return;
        }
        if (!isShowing()) {
            stale = true;
            return;
        }
        if (shown == null && getOWLModelManager().getActiveOntology() != null) {
            // An ontology became active after the view was built.
            showCurrent();
            return;
        }
        refreshShown();
    }

    private void refreshShown() {
        stale = false;
        cardLayout.show(cards, preferences.active() == null ? "setup" : "chat");
        panel.refresh();
    }

    /**
     * Settings changed. The shown session takes the new connection, role and switches; an
     * answer in flight is stopped only when its connection changed (setConnection decides), so
     * ticking "Save conversations" or provenance never cuts an answer off (1.138.0).
     */
    private void settingsChanged() {
        if (shown != null) {
            configure(shown, getOWLModelManager().getActiveOntology());
        }
        refreshShown();
    }

    private ProjectFolder projectFolder() {
        return ProjectFolder.of(fileOf(getOWLModelManager().getActiveOntology()));
    }

    private File fileOf(OWLOntology ontology) {
        if (ontology == null) {
            return null;
        }
        try {
            IRI document = ontology.getOWLOntologyManager().getOntologyDocumentIRI(ontology);
            return document == null ? null : TemplateDiscovery.fileOf(document.toURI());
        } catch (RuntimeException notSaved) {
            return null;
        }
    }

    private IRI selectedIri() {
        OWLEntity selected = getOWLWorkspace().getOWLSelectionModel().getSelectedEntity();
        return selected == null ? null : selected.getIRI();
    }

    /** The reasoner Protégé is running, or HermiT, named, when Protégé has none chosen. */
    private Object[] runningReasoner() {
        try {
            ProtegeOWLReasonerInfo info =
                    getOWLModelManager().getOWLReasonerManager().getCurrentReasonerFactory();
            String name = getOWLModelManager().getOWLReasonerManager().getCurrentReasonerName();
            OWLReasonerFactory factory = info == null ? null : info.getReasonerFactory();
            if (factory != null && name != null && !"none".equalsIgnoreCase(name.trim())) {
                return new Object[] {factory, name};
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // Fall through to HermiT.
        }
        return new Object[] {Reasoners.Choice.HERMIT.newFactory(),
            "HermiT (Protégé has no reasoner selected)"};
    }

    /** Runs the reasoner off the event thread, then adds the problem and asks about it. */
    private void explainProblem() {
        final OWLOntology ontology = getOWLModelManager().getActiveOntology();
        final AssistantSession session = current();
        if (ontology == null || session == null) {
            panel.onNotice("Open an ontology first.");
            return;
        }
        final Object[] reasoner = runningReasoner();
        panel.onNotice("Running " + reasoner[1] + "…");
        new SwingWorker<Explanations.Result, Void>() {
            @Override
            protected Explanations.Result doInBackground() {
                return Problems.diagnose(ontology, (OWLReasonerFactory) reasoner[0]);
            }

            @Override
            protected void done() {
                Explanations.Result result;
                try {
                    result = get();
                } catch (Exception failed) {
                    panel.onNotice("The reasoner could not explain it: " + cause(failed));
                    return;
                }
                panel.onNotice(" ");
                if (result.isConsistent()) {
                    // A chip the reasoner no longer bears out goes now, not only at the next
                    // question (1.151.0, asked by the person).
                    for (ContextItem item : session.context()) {
                        if (item.getKind() == ContextItem.Kind.PROBLEM
                                && (item.getSubject() == null
                                        || result.getUnsatisfiable().isEmpty())) {
                            session.removeContext(item);
                        }
                    }
                }
                if (!result.isConsistent()) {
                    session.addContext(Problems.inconsistency(ontology, result,
                            (String) reasoner[1]));
                    session.send(Questions.whyInconsistent());
                } else if (result.getUnsatisfiable().isEmpty()) {
                    panel.onNotice("The reasoner finds no problem: the ontology is consistent and"
                            + " every class is satisfiable.");
                } else {
                    List<String> labels = result.getUnsatisfiable();
                    Object chosen = JOptionPane.showInputDialog(AssistantView.this,
                            "These classes can have no instances. Which one should the"
                                    + " assistant explain?", "Explain the reasoner problem",
                            JOptionPane.QUESTION_MESSAGE, null, labels.toArray(), labels.get(0));
                    OWLClass cls = chosen == null ? null : classLabelled(ontology,
                            chosen.toString());
                    if (cls != null) {
                        explainClass(ontology, reasoner, cls, session);
                    }
                }
            }
        }.execute();
    }

    private void explainClass(final OWLOntology ontology, final Object[] reasoner,
            final OWLClass cls, final AssistantSession session) {
        final String label = DisplayLabels.forEntity(ontology, cls);
        panel.onNotice("Explaining why " + label + " is unsatisfiable…");
        new SwingWorker<ContextItem, Void>() {
            @Override
            protected ContextItem doInBackground() {
                // Asked first: "Why is this unsatisfiable?" shows from Protégé's last
                // classification, and the question was sent whatever the reasoner found now
                // (1.151.0, asked by the person).
                return Problems.now(ontology, (OWLReasonerFactory) reasoner[0], cls.getIRI(),
                        (String) reasoner[1]);
            }

            @Override
            protected void done() {
                ContextItem found;
                try {
                    found = get();
                } catch (Exception failed) {
                    panel.onNotice("The reasoner could not explain it: " + cause(failed));
                    return;
                }
                if (found == null) {
                    panel.onNotice(reasoner[1] + " finds " + label + " satisfiable now: there is"
                            + " nothing to explain.");
                    return;
                }
                panel.onNotice(" ");
                session.addContext(found);
                // Inconsistent now: every class is unsatisfiable, and the inconsistency is what
                // there is to explain.
                session.send(found.getSubject() == null ? Questions.whyInconsistent()
                        : Questions.whyUnsatisfiable(label));
            }
        }.execute();
    }

    private static String cause(Exception failed) {
        Throwable cause = failed.getCause() == null ? failed : failed.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static OWLClass classLabelled(OWLOntology ontology, String label) {
        for (OWLClass cls : ontology.getClassesInSignature(Imports.INCLUDED)) {
            if (label.equals(DisplayLabels.forEntity(ontology, cls))) {
                return cls;
            }
        }
        return null;
    }

    /** What the panel asks of Protégé. */
    private final class Bridge implements AssistantPanel.Host {

        private OWLOntology ontology() {
            return getOWLModelManager().getActiveOntology();
        }

        @Override
        public List<TermLine> lines() {
            IRI selected = selectedIri();
            return selected == null || ontology() == null ? Collections.<TermLine>emptyList()
                    : TermLines.of(ontology(), selected);
        }

        @Override
        public String termTitle() {
            if (ontology() == null) {
                return "No ontology is open";
            }
            IRI selected = selectedIri();
            return selected == null ? "Nothing selected"
                    : ContextBuilder.titleOf(ontology(), ContextItem.term(selected, true));
        }

        @Override
        public boolean selectedIsUnsatisfiable() {
            OWLEntity selected = getOWLWorkspace().getOWLSelectionModel().getSelectedEntity();
            if (!(selected instanceof OWLClass)) {
                return false;
            }
            try {
                // Only a classified reasoner is asked: asking one that is not would start a
                // classification on the event thread.
                if (getOWLModelManager().getOWLReasonerManager().getReasonerStatus()
                        != ReasonerStatus.INITIALIZED) {
                    return false;
                }
                OWLReasoner reasoner = getOWLModelManager().getReasoner();
                return reasoner != null && reasoner.getUnsatisfiableClasses()
                        .contains((OWLClass) selected);
            } catch (RuntimeException | LinkageError unavailable) {
                return false;
            }
        }

        @Override
        public void explainSelected() {
            OWLEntity selected = getOWLWorkspace().getOWLSelectionModel().getSelectedEntity();
            AssistantSession session = current();
            if (selected instanceof OWLClass && session != null) {
                explainClass(ontology(), runningReasoner(), (OWLClass) selected, session);
            }
        }

        @Override
        public void explainProblem() {
            AssistantView.this.explainProblem();
        }

        @Override
        public List<AssistantPanel.Candidate> complete(String prefix) {
            OWLOntology ontology = ontology();
            if (ontology == null) {
                return Collections.emptyList();
            }
            if (search == null || searched != ontology) {
                search = TermSearch.of(ontology);
                searched = ontology;
            }
            List<AssistantPanel.Candidate> candidates = new ArrayList<AssistantPanel.Candidate>();
            for (TermSearch.Term term : search.find(prefix, 12, false)) {
                String label = term.getLabel() == null || term.getLabel().isEmpty()
                        ? term.getShortIri() : term.getLabel();
                candidates.add(new AssistantPanel.Candidate(IRI.create(term.getIri()), label,
                        term.getKind()));
            }
            return candidates;
        }

        @Override
        public IRI selectedIri() {
            return AssistantView.this.selectedIri();
        }

        @Override
        public void openSettings() {
            tabs.setSelectedIndex(1);
        }

        @Override
        public String destination() {
            Connection connection = shown == null ? null : shown.getConnection();
            return connection == null ? "No model set up"
                    : connection.getName() + " · " + connection.getHost() + " ("
                            + connection.describeWhere() + ")";
        }

        @Override
        public String titleOf(ContextItem item) {
            return ontology() == null ? item.getTitle() : ContextBuilder.titleOf(ontology(), item);
        }

        @Override
        public int sizeOf(ContextItem item) {
            // From the session's cache: rendered once per version of the ontology (review #2).
            return shown == null ? 0 : shown.sizeOf(item);
        }

        @Override
        public void saveConversation() {
            if (shown == null || shown.turns().isEmpty()) {
                panel.onNotice("There is no conversation to save yet.");
                return;
            }
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File("assistant-conversation.md"));
            if (chooser.showSaveDialog(AssistantView.this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            String title = ContextBuilder.header(ontology()).replaceFirst("^Ontology: ", "");
            // Wherever it is saved, a key is not: a file chosen here may be in a repository too.
            String markdown = KeyRedactor.redact(MarkdownExport.of(title, shown.getConnection(),
                    shown.getRoleName(), shown.turns()), knownKeys());
            try {
                Files.write(chooser.getSelectedFile().toPath(),
                        markdown.getBytes(StandardCharsets.UTF_8));
            } catch (IOException cannotWrite) {
                panel.onNotice("Could not save: " + cannotWrite.getMessage());
            }
        }

        @Override
        public void showWhatIsSent(String draft) {
            if (shown == null || shown.getConnection() == null) {
                panel.onNotice("Set up a model in the Settings tab first.");
                return;
            }
            String message = draft == null || draft.trim().isEmpty()
                    ? "(your next message)" : draft.trim();
            WhatIsSentDialog.show(AssistantView.this,
                    shown.preview(message).describe(shown.getConnection()));
        }
    }
}
