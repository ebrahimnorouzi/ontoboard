package de.fizkarlsruhe.ise.ontoboard.views;

import com.mxgraph.swing.mxGraphOutline;
import com.mxgraph.util.mxEvent;
import com.mxgraph.util.mxEventSource.mxIEventListener;
import de.fizkarlsruhe.ise.ontoboard.axiom.AxiomRemoval;
import de.fizkarlsruhe.ise.ontoboard.axiom.EdgeAxioms;
import de.fizkarlsruhe.ise.ontoboard.axiom.EntityFactory;
import de.fizkarlsruhe.ise.ontoboard.axiom.RelationDialog;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasExport;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasLayouts;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasMembership;
import de.fizkarlsruhe.ise.ontoboard.canvas.CollaborativeGraphComponent;
import de.fizkarlsruhe.ise.ontoboard.canvas.LegendPanel;
import de.fizkarlsruhe.ise.ontoboard.canvas.PrefixColours;
import de.fizkarlsruhe.ise.ontoboard.canvas.SchemaGraph;
import de.fizkarlsruhe.ise.ontoboard.canvas.SelectionBridge;
import de.fizkarlsruhe.ise.ontoboard.canvas.StartPanel;
import de.fizkarlsruhe.ise.ontoboard.collab.CollabDialog;
import de.fizkarlsruhe.ise.ontoboard.collab.CollabSession;
import de.fizkarlsruhe.ise.ontoboard.collab.CollabSettings;
import de.fizkarlsruhe.ise.ontoboard.collab.CollabSettingsStore;
import de.fizkarlsruhe.ise.ontoboard.collab.OperationMapper;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayoutStore;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import de.fizkarlsruhe.ise.ontoboard.reason.InferredEdges;
import de.fizkarlsruhe.ise.ontoboard.odk.IdRanges;
import de.fizkarlsruhe.ise.ontoboard.odk.TermMinter;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectLoader;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.dnd.DropTarget;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToolBar;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.protege.editor.owl.model.selection.OWLSelectionModelListener;
import org.protege.editor.owl.ui.transfer.OWLObjectDataFlavor;
import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;

public class SchemaCanvasView extends AbstractOWLViewComponent {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SchemaCanvasView.class);

    private SchemaGraph graph;
    private CollaborativeGraphComponent graphComponent;
    private JPanel centre;
    private CardLayout cards;
    private CanvasLayout layout = new CanvasLayout();
    private CanvasMembership membership;
    /**
     * The file whose sidecar {@link #layout} currently reflects. Recorded whenever
     * {@link #layout} is (re)loaded, and deliberately NOT recomputed from
     * {@code getOWLModelManager().getActiveOntology()} at save time - see
     * {@link #switchToActiveOntology()} for why that distinction matters.
     */
    private File currentOntologyFile;
    private OWLOntologyChangeListener changeListener;
    private OWLModelManagerListener modelManagerListener;
    private SelectionBridge selectionBridge;
    private OWLSelectionModelListener selectionListener;
    /**
     * Debounces the sidecar save that follows a drag. {@code mxEvent.CELLS_MOVED} fires
     * repeatedly while the mouse moves, and writing the sidecar on every one of those events
     * would be wasteful, so {@link #positionSaveTimer} is restarted on each event and only
     * the last one in a burst - after ~800ms of quiet, mirroring the retired web app's
     * debounce - actually reaches disk. {@code capturePositions()} itself still runs
     * synchronously on every event since copying live geometry into {@link #layout} is
     * cheap; only the disk write is deferred.
     */
    private Timer positionSaveTimer;
    private mxIEventListener cellsMovedListener;
    /**
     * Enabled only while Protege has an entity selected. The entity tabs sit in the same
     * OntoBoard tab as the canvas, so the intended gesture is select-then-add; a button that
     * is always enabled and silently does nothing on an empty selection reads as broken.
     */
    private JButton addSelectedButton;
    /**
     * Whether the reasoner's conclusions are drawn alongside the asserted axioms. Off by default:
     * reasoning costs time on a large ontology, and a diagram should show what the ontology says
     * until someone asks what it means.
     */
    private boolean showInferences;
    private javax.swing.JToggleButton inferencesButton;
    /**
     * The live session, or null when working through git. Created on demand from the
     * Collaborate dialog rather than at startup, because most sessions are single-user and
     * opening a socket nobody asked for is the wrong default.
     */
    private CollabSession collab;
    private JButton collaborateButton;
    private javax.swing.JLabel collabStatus;
    /**
     * When the cursor was last published. Presence is sent on mouse movement, which fires far
     * faster than anyone needs to see, so it is throttled - and the client's own heartbeat
     * keeps the cursor alive in between.
     */
    private long lastCursorSentAt;

    @Override
    protected void initialiseOWLView() {
        setLayout(new BorderLayout());

        graph = new SchemaGraph();
        graphComponent = new CollaborativeGraphComponent(graph);
        graphComponent.setConnectable(false); // Task 4 turns this on with real axiom writing
        graphComponent.setToolTips(true);
        graphComponent.setPanning(true);
        graphComponent.getPanningHandler().setEnabled(true);
        // Card deck so an empty board shows guidance instead of a blank grid.
        cards = new CardLayout();
        centre = new JPanel(cards);
        centre.add(graphComponent, "canvas");
        centre.add(new StartPanel(this::runNewProjectWizard, this::openExistingProject,
                this::addSelectedEntityToCanvas), "start");
        add(centre, BorderLayout.CENTER);

        mxGraphOutline outline = new mxGraphOutline(graphComponent);
        outline.setPreferredSize(new Dimension(180, 140));
        add(outline, BorderLayout.EAST);
        add(buildToolBar(), BorderLayout.NORTH);
        graphComponent.getViewport().setOpaque(true);
        graphComponent.getViewport().setBackground(
                Color.decode(de.fizkarlsruhe.ise.ontoboard.canvas.SchemaStyles.CANVAS_BACKGROUND));
        graphComponent.setGridVisible(true);
        installEntityDropTarget();
        installDoubleClickToCreate();
        installCursorSharing();
        installSelection();
        installWheelZoom();
        installKeyboardShortcuts();

        loadLayoutForActiveOntology();
        installContextMenu();
        refresh();

        selectionBridge = new SelectionBridge(graph, this::pushSelectionToProtege);
        selectionBridge.install();

        positionSaveTimer = new Timer(800, event -> saveLayoutTo(currentOntologyFile));
        positionSaveTimer.setRepeats(false);
        cellsMovedListener = (sender, event) -> {
            capturePositions();
            positionSaveTimer.restart();
        };
        graph.addListener(mxEvent.CELLS_MOVED, cellsMovedListener);

        selectionListener = () -> {
            OWLEntity selected = getOWLEditorKit().getOWLWorkspace()
                    .getOWLSelectionModel().getSelectedEntity();
            selectionBridge.selectOnCanvas(selected == null ? null : selected.getIRI().toString());
            describeSelectionOnButton(selected);
        };
        getOWLEditorKit().getOWLWorkspace().getOWLSelectionModel()
                .addListener(selectionListener);

        changeListener = changes -> {
            // Publishing from here rather than from the canvas is deliberate: this listener sees
            // edits made anywhere in Protege - the class hierarchy, the Manchester syntax
            // editor - so those travel too. CollabSession refuses while it is applying a remote
            // operation, which is what stops the two ends amplifying each other.
            if (collab != null) {
                collab.publishLocalChanges(changes, canvasHints());
            }
            refresh();
        };
        getOWLModelManager().addOntologyChangeListener(changeListener);

        // Axiom edits and active-ontology switches are two separate Protege event
        // channels. Switching the active ontology fires no axiom change, so without this
        // listener the canvas silently keeps showing the ontology it initialised
        // against - see EventType. Only ACTIVE_ONTOLOGY_CHANGED is handled here; every
        // other EventType is ignored so ordinary edits still go through changeListener
        // alone and are not double-rendered.
        modelManagerListener = event -> {
            if (event.isType(EventType.ACTIVE_ONTOLOGY_CHANGED)) {
                switchToActiveOntology();
            }
        };
        getOWLModelManager().addListener(modelManagerListener);
    }

    @Override
    protected void disposeOWLView() {
        if (changeListener != null) {
            getOWLModelManager().removeOntologyChangeListener(changeListener);
        }
        if (modelManagerListener != null) {
            getOWLModelManager().removeListener(modelManagerListener);
        }
        if (selectionBridge != null) {
            selectionBridge.uninstall();
        }
        if (selectionListener != null) {
            getOWLEditorKit().getOWLWorkspace().getOWLSelectionModel()
                    .removeListener(selectionListener);
        }
        if (collab != null) {
            // Before the timer work below: stopping the socket first means no remote operation
            // can arrive while the view is being torn down.
            collab.stop();
            collab = null;
        }
        if (cellsMovedListener != null) {
            graph.removeListener(cellsMovedListener);
        }
        if (positionSaveTimer != null) {
            // Stop the debounce timer so it cannot fire after this view is gone, then do
            // the save it would have done - a drag immediately before closing must not be
            // lost just because the 800ms quiet period never elapsed.
            positionSaveTimer.stop();
        }
        capturePositions();
        saveLayoutTo(currentOntologyFile);
    }

    /**
     * Adds whatever is selected in Protege's entity tabs to the canvas.
     *
     * <p>Both dead ends are answered rather than ignored. With nothing selected the user is told
     * where to select something, since the tabs that drive this are in the same OntoBoard tab
     * and easy to overlook. An entity already on the board is selected there instead, because a
     * board holding fifty nodes gives no clue whether the one you asked for is among them, and
     * doing nothing at all is indistinguishable from a failure.
     */
    public void addSelectedEntityToCanvas() {
        OWLEntity selected = getOWLEditorKit().getOWLWorkspace()
                .getOWLSelectionModel().getSelectedEntity();
        if (selected == null) {
            JOptionPane.showMessageDialog(this,
                    "Select a class, property or individual in the tabs on the left first.",
                    "Nothing selected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String iri = selected.getIRI().toString();
        if (membership.add(iri)) {
            refresh();
        }
        if (selectionBridge != null) {
            selectionBridge.selectOnCanvas(iri);
        }
    }

    /**
     * Puts the selected entity's name on the Add button, so the button says what it will add
     * rather than leaving the user to check the tree and the board for agreement.
     */
    private void describeSelectionOnButton(OWLEntity selected) {
        if (addSelectedButton == null) {
            return;
        }
        addSelectedButton.setEnabled(selected != null);
        if (selected == null) {
            addSelectedButton.setText("Add selected");
            addSelectedButton.setToolTipText(
                    "Select a class, property or individual in the tabs on the left");
            return;
        }
        String label =
                DisplayLabels.forEntity(getOWLModelManager().getActiveOntology(), selected);
        addSelectedButton.setText("Add " + label);
        addSelectedButton.setToolTipText("Add " + selected.getIRI() + " to the canvas");
    }

    /**
     * Pushes a canvas selection outward so Protege's editors follow it. {@code iri} is
     * {@code null} when {@link SelectionBridge#resyncAfterRender} determines the
     * previously-selected node fell off the canvas during a refresh; passing {@code null}
     * through to {@code setSelectedEntity} clears Protege's selection so it does not keep
     * pointing at an entity the canvas no longer highlights.
     */
    private void pushSelectionToProtege(String iri) {
        if (iri == null) {
            getOWLEditorKit().getOWLWorkspace().getOWLSelectionModel().setSelectedEntity(null);
            return;
        }
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        for (OWLEntity entity : ontology.getEntitiesInSignature(IRI.create(iri))) {
            getOWLEditorKit().getOWLWorkspace().getOWLSelectionModel().setSelectedEntity(entity);
            return;
        }
    }

    /** Shows the key to the diagram, built from the same styles the canvas draws with. */
    private void showLegend() {
        JOptionPane.showMessageDialog(this, new LegendPanel(), "What the diagram means",
                JOptionPane.PLAIN_MESSAGE);
    }

    // ------------------------------------------------------------------ inferences

    /**
     * Adds the reasoner's conclusions to the projection, when the user has asked for them.
     *
     * <p>Failures are reported once, through the status line, and then the toggle is turned back
     * off - a button that stays pressed while showing nothing is a worse lie than an error. The
     * asserted diagram is always still drawn, because losing the whole canvas because a reasoner
     * was not started would be an absurd punishment for asking a question.
     */
    private Projection withInferences(Projection asserted) {
        if (!showInferences) {
            return asserted;
        }
        try {
            List<CanvasEdge> inferred = InferredEdges.subClassEdges(
                    getOWLModelManager().getReasoner(), membership.asSet(),
                    asserted.getEdges(),
                    getOWLModelManager().getOWLDataFactory());
            if (inferred.isEmpty()) {
                setStatus("Nothing further was inferred: the ontology already states what it "
                        + "entails for the entities on this board.");
                return asserted;
            }
            List<CanvasEdge> combined = new ArrayList<CanvasEdge>(asserted.getEdges());
            combined.addAll(inferred);
            setStatus(inferred.size() + " inferred edge" + (inferred.size() == 1 ? "" : "s")
                    + " shown, dotted and grey.");
            return new Projection(asserted.getNodes(), combined);
        } catch (InferredEdges.NotAvailable unavailable) {
            showInferences = false;
            if (inferencesButton != null) {
                inferencesButton.setSelected(false);
            }
            setStatus(unavailable.getMessage());
            JOptionPane.showMessageDialog(this, unavailable.getMessage(),
                    "No inferences available", JOptionPane.INFORMATION_MESSAGE);
            return asserted;
        }
    }

    /** Puts a line in the toolbar's status label, reusing the collaboration one. */
    private void setStatus(String text) {
        if (collabStatus != null) {
            collabStatus.setText(text);
            collabStatus.setToolTipText(text);
        }
    }

    // ------------------------------------------------------------------ interaction

    /**
     * Rubberband selection with a modifier held, and multiple selection generally.
     *
     * <p>A plain left-drag pans (see {@code CollaborativeGraphComponent.isPanningEvent}), so the
     * rubberband is bound to Ctrl or Shift rather than fighting it for the same gesture. Ctrl-click
     * to add one node at a time works without any code here - mxGraph does it - but only once the
     * graph allows more than one cell to be selected at a time, which is what
     * {@code setMultigraph} does not do and {@code mxGraphSelectionModel} needs told.
     */
    private void installSelection() {
        graph.getSelectionModel().setSingleSelection(false);
        new com.mxgraph.swing.handler.mxRubberband(graphComponent) {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                // Without this guard the rubberband starts on every empty-space press and the
                // pan never happens, because both handlers see the same event.
                if (event.isControlDown() || event.isShiftDown()) {
                    super.mousePressed(event);
                }
            }
        };
    }

    /**
     * Zooms on the mouse wheel.
     *
     * <p>mxGraph zooms on Ctrl+wheel and leaves a plain wheel to scroll. On a diagram the
     * expectation is the opposite way round, so wheel scrolling is switched off on the scroll pane
     * and the wheel is bound to zoom. Scrolling is still reachable by dragging the canvas, which is
     * now the primary gesture anyway.
     */
    private void installWheelZoom() {
        graphComponent.setWheelScrollingEnabled(false);
        graphComponent.addMouseWheelListener(new java.awt.event.MouseWheelListener() {
            @Override
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent event) {
                if (event.getWheelRotation() < 0) {
                    graphComponent.zoomIn();
                } else {
                    graphComponent.zoomOut();
                }
                event.consume();
            }
        });
    }

    /**
     * Delete removes the selection from the canvas, and Escape clears it.
     *
     * <p>Delete removes from the <em>board</em>, not from the ontology. Retracting an axiom is a
     * separate, confirmed action, and a keystroke that silently deleted classes from someone's
     * ontology - with a collaborator watching them vanish - is not a keystroke worth having. The
     * status line says which of the two happened, because "delete" reasonably means either.
     */
    private void installKeyboardShortcuts() {
        javax.swing.InputMap keys =
                graphComponent.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        keys.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0),
                "ontoboard.removeFromCanvas");
        keys.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_BACK_SPACE, 0),
                "ontoboard.removeFromCanvas");
        graphComponent.getActionMap().put("ontoboard.removeFromCanvas",
                new javax.swing.AbstractAction() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent event) {
                        removeSelectionFromCanvas();
                    }
                });
    }

    /**
     * Takes every selected node off the board, leaving the ontology untouched.
     *
     * @return how many were removed
     */
    private int removeSelectionFromCanvas() {
        Object[] selected = graph.getSelectionCells();
        if (selected == null || selected.length == 0) {
            return 0;
        }
        int removed = 0;
        for (Object cell : selected) {
            String iri = graph.getIdForCell(cell);
            if (iri != null && membership.remove(iri)) {
                removed++;
            }
        }
        if (removed > 0) {
            refresh();
            saveLayoutTo(currentOntologyFile);
        }
        return removed;
    }

    /**
     * Puts every entity the ontology declares onto the board.
     *
     * <p>The canvas is opt-in because Protege routinely opens ontologies with a hundred thousand
     * classes, and rendering all of them hangs it. But for an ontology of a few hundred terms,
     * choosing them one at a time is absurd - so this exists, with a confirmation whose threshold
     * is about when the layout stops being readable rather than when it stops being possible.
     */
    private void addEverythingToCanvas() {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        List<String> candidates = new ArrayList<String>();
        for (OWLEntity entity : ontology.getSignature()) {
            if (entity.isOWLClass() || entity.isOWLNamedIndividual()) {
                candidates.add(entity.getIRI().toString());
            }
        }
        if (candidates.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "This ontology declares no classes or individuals yet.",
                    "Nothing to add", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (candidates.size() > 300) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "This ontology has " + candidates.size() + " classes and individuals.\n\n"
                            + "A diagram that large is slow to arrange and hard to read. Add them "
                            + "all anyway?",
                    "That is a lot of nodes", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
        }
        int added = 0;
        for (String iri : candidates) {
            if (membership.add(iri)) {
                added++;
            }
        }
        if (added > 0) {
            // Cleared so autoArrangeIfUnpositioned lays the whole board out rather than leaving
            // the new arrivals stacked on the origin, which is what "should not overlap" means.
            layout.nodes.clear();
            refresh();
            CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);
            capturePositions();
            saveLayoutTo(currentOntologyFile);
        }
    }

    // ------------------------------------------------------------------ collaboration

    /**
     * Opens the Collaborate dialog, or leaves a session that is already running.
     *
     * <p>One button for both because they are the same question - am I sharing this or not - and
     * a separate Disconnect that only appears sometimes is harder to find than a label that
     * changes.
     */
    private void toggleCollaboration() {
        if (collab != null) {
            collab.stop();
            collab = null;
            graphComponent.setPeerCursors(null);
            graphComponent.getGraphControl().repaint();
            collaborateButton.setText("Collaborate...");
            collabStatus.setText("Working through git");
            return;
        }
        CollabSettings settings = CollabDialog.show(this);
        if (settings == null) {
            return;
        }
        if (!settings.isLive()) {
            // A deliberate choice, not a failure: the dialog says so too.
            collabStatus.setText("Working through git");
            JOptionPane.showMessageDialog(this, settings.explainWhyNotLive(),
                    "Working through git", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        collab = new CollabSession(settings, new CanvasCollabHost(),
                javax.swing.SwingUtilities::invokeLater);
        graphComponent.setPeerCursors(collab.getCursors());
        collaborateButton.setText("Disconnect");
        collabStatus.setText("Connecting...");
        collab.start();
    }

    /**
     * Publishes the pointer position while it moves over the canvas.
     *
     * <p>Throttled because mouse-moved fires far faster than anyone can perceive, and every frame
     * would be a WebSocket write. The client re-sends the last position on its own heartbeat, so
     * a position dropped here is never the last one anyone sees.
     */
    private void installCursorSharing() {
        graphComponent.getGraphControl().addMouseMotionListener(
                new java.awt.event.MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(java.awt.event.MouseEvent event) {
                        shareCursor(event);
                    }

                    @Override
                    public void mouseDragged(java.awt.event.MouseEvent event) {
                        shareCursor(event);
                    }
                });
    }

    private void shareCursor(java.awt.event.MouseEvent event) {
        if (collab == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCursorSentAt < 80) {
            return;
        }
        lastCursorSentAt = now;
        double scale = graph.getView().getScale();
        if (scale <= 0) {
            scale = 1;
        }
        // The event is on the graph control, which is the scrolled content, so only the zoom has
        // to be undone - the mirror of PeerCursorLayer, and of toGraphPoint minus its scroll term.
        collab.publishCursor(event.getX() / scale, event.getY() / scale,
                selectionBridge == null ? null : selectionBridge.currentCanvasSelection());
    }

    /**
     * Canvas geometry for the operations being published, so a peer draws a new node where it
     * actually is rather than at the origin.
     */
    private OperationMapper.CanvasHints canvasHints() {
        return iri -> {
            Object cell = graph.getCellForId(iri);
            if (!(cell instanceof com.mxgraph.model.mxCell)) {
                return null;
            }
            com.mxgraph.model.mxGeometry geometry =
                    ((com.mxgraph.model.mxCell) cell).getGeometry();
            if (geometry == null) {
                return null;
            }
            // Coloured by the node's OWN namespace, not the ontology's: distinguishing imported
            // vocabulary at a glance is the whole point of the colouring, and publishing every
            // node in the ontology's colour would throw that away on the receiving canvas.
            return new OperationMapper.NodeHint(geometry.getX(), geometry.getY(),
                    geometry.getWidth(), geometry.getHeight(),
                    new PrefixColours(layout.prefixColors).colourFor(iri));
        };
    }

    /** What the session needs from Protege and from this view. */
    private final class CanvasCollabHost implements CollabSession.Host {

        @Override
        public OWLOntology activeOntology() {
            return getOWLModelManager().getActiveOntology();
        }

        @Override
        public void applyChanges(List<OWLOntologyChange> changes) {
            // Through the model manager, never straight into the ontology: that is what keeps
            // Protege's undo and its other views correct for a change that came from someone else.
            getOWLModelManager().applyChanges(changes);
        }

        @Override
        public void onStatus(String status, boolean connected) {
            collabStatus.setText(status);
            collabStatus.setToolTipText(status);
            collaborateButton.setText(connected ? "Disconnect" : "Collaborate...");
        }

        @Override
        public void onPeersChanged() {
            graphComponent.getGraphControl().repaint();
        }

        @Override
        public void onUnshareable(int count, String exampleReason) {
            // A running count in the status line rather than a dialog per change: Protege can
            // produce a dozen unshareable axioms from one action, and a dozen modal dialogs
            // would be worse than the problem. The tooltip carries the detail.
            collabStatus.setText(count + " change" + (count == 1 ? "" : "s") + " not shared");
            collabStatus.setToolTipText("The most recent was " + exampleReason);
        }
    }

    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        JComboBox<CanvasLayouts.Algorithm> algorithms =
                new JComboBox<>(CanvasLayouts.Algorithm.values());
        JButton arrange = new JButton("Arrange");
        arrange.addActionListener(a -> CanvasLayouts.apply(graph,
                (CanvasLayouts.Algorithm) algorithms.getSelectedItem()));

        collaborateButton = new JButton("Collaborate...");
        collaborateButton.addActionListener(a -> toggleCollaboration());
        collabStatus = new javax.swing.JLabel(" ");
        collabStatus.setFont(collabStatus.getFont().deriveFont(
                java.awt.Font.PLAIN, collabStatus.getFont().getSize() - 1f));

        addSelectedButton = new JButton("Add selected");
        addSelectedButton.addActionListener(a -> addSelectedEntityToCanvas());
        describeSelectionOnButton(getOWLEditorKit().getOWLWorkspace()
                .getOWLSelectionModel().getSelectedEntity());

        JButton export = new JButton("Export...");
        export.addActionListener(a -> exportWithOptions());

        inferencesButton = new javax.swing.JToggleButton("Inferences");
        inferencesButton.setToolTipText("Also draw what the running reasoner concludes, dotted");
        inferencesButton.addActionListener(a -> {
            showInferences = inferencesButton.isSelected();
            refresh();
        });

        JButton legend = new JButton("Legend");
        legend.setToolTipText("What the shapes and lines mean");
        legend.addActionListener(a -> showLegend());

        JButton addAll = new JButton("Add all");
        addAll.setToolTipText("Put every class and individual in the ontology on the board");
        addAll.addActionListener(a -> addEverythingToCanvas());

        bar.add(addSelectedButton);
        bar.addSeparator();
        bar.add(algorithms);
        bar.add(arrange);
        bar.addSeparator();
        bar.add(addAll);
        bar.add(inferencesButton);
        bar.add(legend);
        bar.addSeparator();
        bar.add(export);
        bar.addSeparator();
        bar.add(collaborateButton);
        bar.add(collabStatus);
        return bar;
    }

    /**
     * One export button, then the choices.
     *
     * <p>Two buttons for two formats does not scale past two formats, and it put the least
     * interesting decision - the file type - in the toolbar while the one that matters, how big,
     * was not offered at all.
     */
    private void exportWithOptions() {
        JComboBox<String> format = new JComboBox<String>(new String[] {
            "PNG - a picture, for slides and papers",
            "SVG - vector, scales without blurring"});
        JComboBox<String> resolution = new JComboBox<String>(new String[] {
            "Screen size (1x)", "Double (2x)", "Triple (3x) - for print"});

        javax.swing.JPanel form = new javax.swing.JPanel(new java.awt.GridLayout(0, 2, 6, 6));
        form.add(new javax.swing.JLabel("Format"));
        form.add(format);
        form.add(new javax.swing.JLabel("Resolution"));
        form.add(resolution);
        // SVG is resolution-independent, so a scale for it would mean nothing.
        format.addActionListener(a -> resolution.setEnabled(format.getSelectedIndex() == 0));

        if (JOptionPane.showConfirmDialog(this, form, "Export diagram",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        boolean png = format.getSelectedIndex() == 0;
        exportTo(png ? "png" : "svg", 1.0 + resolution.getSelectedIndex());
    }

    private void exportTo(String extension) {
        exportTo(extension, 1.0);
    }

    private void exportTo(String extension, double scale) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("schema-diagram." + extension));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            if ("png".equals(extension)) {
                CanvasExport.writePng(graph, chooser.getSelectedFile(), scale);
            } else {
                CanvasExport.writeSvg(graph, chooser.getSelectedFile());
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(),
                    "OntoBoard", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void installContextMenu() {
        graphComponent.getGraphControl().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                Object cell = graphComponent.getCellAt(event.getX(), event.getY());
                String iri = graph.getIdForCell(cell);
                JPopupMenu menu = new JPopupMenu();

                JMenuItem addSelected = new JMenuItem("Add selected entity to canvas");
                addSelected.addActionListener(a -> addSelectedEntityToCanvas());
                menu.add(addSelected);

                if (iri != null && membership.contains(iri)) {
                    JMenuItem expand = new JMenuItem("Expand neighbours (1 hop)");
                    expand.addActionListener(a -> {
                        membership.expandOneHop(getOWLModelManager().getActiveOntology(), iri);
                        refresh();
                    });
                    menu.add(expand);

                    JMenuItem remove = new JMenuItem("Remove from canvas (keeps axioms)");
                    remove.addActionListener(a -> {
                        membership.remove(iri);
                        refresh();
                    });
                    menu.add(remove);
                }
                if (cell != null && graph.getModel().isEdge(cell)) {
                    final String edgeId = graph.getIdForCell(cell);
                    JMenuItem deleteAxiom = new JMenuItem("Delete axiom from ontology...");
                    deleteAxiom.addActionListener(a -> deleteAxiomFor(edgeId));
                    menu.add(deleteAxiom);
                }

                menu.addSeparator();

                final int clickX = event.getX();
                final int clickY = event.getY();

                JMenuItem newClass = new JMenuItem("New class here...");
                newClass.addActionListener(a -> createEntityAt(
                        EntityFactory.Kind.CLASS, clickX, clickY));
                menu.add(newClass);

                JMenuItem newIndividual = new JMenuItem("New individual here...");
                newIndividual.addActionListener(a -> createEntityAt(
                        EntityFactory.Kind.INDIVIDUAL, clickX, clickY));
                menu.add(newIndividual);

                if (iri != null && membership.contains(iri)) {
                    JMenuItem relate = new JMenuItem("Create relation from this node...");
                    relate.addActionListener(a -> createRelationFrom(iri));
                    menu.add(relate);
                }

                menu.show(graphComponent.getGraphControl(), event.getX(), event.getY());
            }
        });
    }

    /**
     * Starts the view over for whichever ontology Protege just made active. By the time
     * {@code ACTIVE_ONTOLOGY_CHANGED} fires, {@code getOWLModelManager().getActiveOntology()}
     * already returns the INCOMING ontology, not the one this view is currently showing.
     * That makes ordering critical:
     *
     * <ol>
     *   <li>Capture live node positions from the still-on-screen outgoing diagram into
     *       {@link #layout}.
     *   <li>Save that {@link #layout} to {@link #currentOntologyFile} - the file recorded
     *       when it was loaded, i.e. the OUTGOING ontology's sidecar. Recomputing the file
     *       here via {@code getActiveOntology()} would resolve to the incoming ontology's
     *       document instead, and this save would silently overwrite the incoming
     *       ontology's sidecar with the outgoing diagram - the exact corruption this
     *       method exists to prevent.
     *   <li>Only then load (or create) the layout for the now-active incoming ontology,
     *       replacing {@link #layout}, {@link #currentOntologyFile} and {@link #membership}
     *       wholesale.
     *   <li>Redraw.
     * </ol>
     */
    private void switchToActiveOntology() {
        capturePositions();
        saveLayoutTo(currentOntologyFile);

        loadLayoutForActiveOntology();
        refresh();
    }

    /**
     * Produces a clean, correctly-IRI-stamped layout for an ontology that has no
     * persisted diagram yet. Kept as the single source of truth for "what does an empty
     * layout for ontology X look like" - the clearing it performs is a no-op here (a
     * freshly constructed {@link CanvasLayout} is already empty), but it stamps
     * {@code ontologyIri} the same way a loaded sidecar would have, and it is never
     * called on a layout that came from an existing sidecar, so it can never discard
     * persisted membership or positions. Package-private so a unit test can drive it
     * directly without a live {@code OWLEditorKit}.
     */
    static void resetLayoutForOntology(OWLOntology ontology, CanvasLayout layout) {
        layout.ontologyIri = ontology.getOntologyID().getOntologyIRI()
                .transform(Object::toString).or("");
        layout.onCanvas.clear();
        layout.nodes.clear();
        layout.frames.clear();
        layout.notes.clear();
        layout.prefixColors.clear();
    }

    /**
     * Re-renders the canvas. {@link SchemaGraph#render} clears and recreates every cell,
     * which silently drops any live selection - so whatever was selected beforehand is
     * captured and handed to {@link SelectionBridge#resyncAfterRender} afterwards, which
     * restores it if it is still on the canvas, or clears it (and reports the loss outward)
     * if it is not. {@code selectionBridge} is null the first time this runs, during
     * {@link #initialiseOWLView()}, before there is anything to preserve.
     */
    private void refresh() {
        String selectedBeforeRender =
                selectionBridge != null ? selectionBridge.currentCanvasSelection() : null;

        Projection projection = OntologyProjection
                .project(getOWLModelManager().getActiveOntology(), membership.asSet());
        projection = withInferences(projection);
        graph.render(projection, layout);
        autoArrangeIfUnpositioned();
        showAppropriateCard();

        if (selectionBridge != null) {
            selectionBridge.resyncAfterRender(selectedBeforeRender);
        }
    }

    /** Copies live cell geometry back into the layout so it survives the next save. */
    private void capturePositions() {
        for (String iri : membership.asSet()) {
            Object cell = graph.getCellForId(iri);
            if (cell instanceof com.mxgraph.model.mxCell) {
                com.mxgraph.model.mxGeometry geometry =
                        ((com.mxgraph.model.mxCell) cell).getGeometry();
                if (geometry != null) {
                    CanvasLayout.NodeLayout node = layout.nodes
                            .computeIfAbsent(iri, k -> new CanvasLayout.NodeLayout());
                    node.x = geometry.getX();
                    node.y = geometry.getY();
                    node.w = geometry.getWidth();
                    node.h = geometry.getHeight();
                }
            }
        }
    }

    private File activeOntologyFile() {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        URI documentUri = getOWLModelManager().getOWLOntologyManager()
                .getOntologyDocumentIRI(ontology).toURI();
        return "file".equalsIgnoreCase(documentUri.getScheme()) ? new File(documentUri) : null;
    }

    /**
     * Loads the sidecar for whichever ontology is currently active into {@link #layout},
     * records its file as {@link #currentOntologyFile}, and rebuilds {@link #membership}
     * on top of it. Must only be called once the active ontology already reflects the
     * one to load for - see {@link #switchToActiveOntology()}.
     */
    private void loadLayoutForActiveOntology() {
        currentOntologyFile = activeOntologyFile();
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        layout = null;

        if (currentOntologyFile != null
                && CanvasLayoutStore.sidecarFor(currentOntologyFile).isFile()) {
            CanvasLayout stored = CanvasLayoutStore.load(currentOntologyFile);
            if (stored.belongsTo(ontologyIriOf(ontology))) {
                layout = stored;
                pruneStaleMembers(ontology, layout);
            } else {
                // A sidecar for a different ontology, which happens after a clone to another
                // machine or a corrected ontology IRI. Adopting it would render an empty canvas -
                // every stored IRI matches nothing - and then save that emptiness back over
                // someone's arrangement. Starting fresh loses the diagram either way, but does
                // not destroy the file that still holds it.
                LOGGER.warn("OntoBoard: {} describes '{}' but the open ontology is '{}'. Starting "
                        + "with an empty board rather than overwriting it.",
                        CanvasLayoutStore.sidecarFor(currentOntologyFile).getName(),
                        stored.ontologyIri, ontologyIriOf(ontology));
                currentOntologyFile = null; // so nothing is written back over it
            }
        }
        if (layout == null) {
            layout = new CanvasLayout();
            resetLayoutForOntology(ontology, layout);
        }
        membership = new CanvasMembership(layout);
    }

    /**
     * Removes sidecar entries for entities the ontology no longer declares.
     *
     * <p>Deleting an entity in Protege leaves its position behind, and a stale entry renders
     * nothing - so it is invisible and survives every save. Logged rather than done quietly,
     * because a board that silently loses members would be worse than one that says so.
     */
    private void pruneStaleMembers(OWLOntology ontology, CanvasLayout candidate) {
        Set<String> declared = new HashSet<String>();
        for (OWLEntity entity : ontology.getSignature()) {
            declared.add(entity.getIRI().toString());
        }
        List<String> removed = candidate.pruneMissing(declared);
        if (!removed.isEmpty()) {
            LOGGER.info("OntoBoard: dropped {} canvas entr{} for entities no longer in the "
                    + "ontology: {}", removed.size(), removed.size() == 1 ? "y" : "ies", removed);
        }
    }

    private static String ontologyIriOf(OWLOntology ontology) {
        com.google.common.base.Optional<IRI> iri = ontology.getOntologyID().getOntologyIRI();
        return iri.isPresent() ? iri.get().toString() : null;
    }

    private void saveLayoutTo(File file) {
        if (file != null) {
            CanvasLayoutStore.save(file, layout);
        }
    }

    /**
     * Creates a class or individual and places it where the user clicked.
     *
     * <p>The position is written into {@link #layout} <em>before</em> refreshing, because
     * render() reads geometry from the layout - without this the new node would appear at
     * the default position rather than under the cursor.
     */
    private void createEntityAt(EntityFactory.Kind kind, int x, int y) {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        String what = kind == EntityFactory.Kind.INDIVIDUAL ? "individual" : "class";
        // The policy comes from the project, not from a setting: an ODK project mints numeric
        // identifiers from this editor's allocated range, anything else names the term from what
        // is typed. describe() goes in the prompt because the two produce very different IRIs and
        // a user expecting #Person who gets MWO_0001000 will think something has broken.
        TermMinter minter = TermMinter.forOntologyFile(currentOntologyFile, editorName());
        String name = JOptionPane.showInputDialog(this,
                "Name for the new " + what + ":\n\n" + minter.describe(),
                "New " + what, JOptionPane.PLAIN_MESSAGE);
        if (name == null) {
            return;
        }
        IRI iri;
        try {
            iri = minter.mintFor(ontology, name);
        } catch (IllegalArgumentException invalid) {
            JOptionPane.showMessageDialog(this, invalid.getMessage(),
                    "Cannot use that name", JOptionPane.WARNING_MESSAGE);
            return;
        } catch (IdRanges.NoRangeException cannotMint) {
            // Deliberately not falling back to a name-derived IRI. That would put the term
            // outside the project's own identifier scheme without saying so, and the whole point
            // of the ranges is that nobody mints outside their block.
            JOptionPane.showMessageDialog(this, cannotMint.getMessage(),
                    "Cannot mint an identifier", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<OWLOntologyChange> changes = minter.declare(ontology, iri, kind, name);
        if (!changes.isEmpty()) {
            getOWLModelManager().applyChanges(changes);
        }
        layout.nodes.put(iri.toString(), new CanvasLayout.NodeLayout(x, y));
        membership.add(iri.toString());
        refresh();
        saveLayoutTo(currentOntologyFile);
    }

    /**
     * Who is minting, for matching against the ID ranges.
     *
     * <p>The collaboration display name when one is configured, since that is the name a team has
     * already agreed on and the one their ranges are likely allocated to; otherwise the OS account,
     * which is what the scaffold allocates the first range to.
     */
    private String editorName() {
        try {
            String configured = CollabSettingsStore.load().getDisplayName();
            if (configured != null && !configured.trim().isEmpty()) {
                return configured.trim();
            }
        } catch (RuntimeException noPreferences) {
            LOGGER.debug("OntoBoard: no collaboration settings; using the OS account name",
                    noPreferences);
        }
        String account = System.getProperty("user.name", "");
        return account.trim().isEmpty() ? "FirstEditor" : account.trim();
    }

    /**
     * Asks for a target, a property and how the edge should be read, then writes the chosen
     * OWL axiom. Cancelling at any point writes nothing at all.
     */
    private void createRelationFrom(String sourceIri) {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        OWLDataFactory factory = getOWLModelManager().getOWLDataFactory();

        List<String> targets = new ArrayList<String>();
        for (String onCanvas : membership.asSet()) {
            if (!onCanvas.equals(sourceIri)
                    && ontology.containsClassInSignature(IRI.create(onCanvas))) {
                targets.add(onCanvas);
            }
        }
        if (targets.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Add another class to the canvas first - a relation needs a target.",
                    "Nothing to relate to", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Collections.sort(targets);

        String[] labels = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            labels[i] = DisplayLabels.forEntity(ontology,
                    factory.getOWLClass(IRI.create(targets.get(i))));
        }
        String sourceLabel = DisplayLabels.forEntity(ontology,
                factory.getOWLClass(IRI.create(sourceIri)));

        Object chosen = JOptionPane.showInputDialog(this,
                "Relate " + sourceLabel + " to:", "Choose target",
                JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
        if (chosen == null) {
            return;
        }
        String targetIri = targets.get(indexOf(labels, chosen.toString()));

        RelationDialog.Choice choice =
                RelationDialog.ask(this, ontology, sourceLabel, chosen.toString());
        if (choice == null) {
            return;
        }

        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        OWLObjectProperty property = choice.getProperty();
        if (property == null) {
            IRI propertyIri;
            try {
                propertyIri = EntityFactory.iriFor(ontology, choice.getNewPropertyName());
            } catch (IllegalArgumentException invalid) {
                JOptionPane.showMessageDialog(this, invalid.getMessage(),
                        "Cannot use that name", JOptionPane.WARNING_MESSAGE);
                return;
            }
            changes.addAll(EntityFactory.declare(ontology, propertyIri,
                    EntityFactory.Kind.OBJECT_PROPERTY));
            property = factory.getOWLObjectProperty(propertyIri);
        }

        changes.add(new AddAxiom(ontology, EdgeAxioms.build(factory, choice.getCandidate(),
                factory.getOWLClass(IRI.create(sourceIri)), property,
                factory.getOWLClass(IRI.create(targetIri)))));

        // Applying fires the ontology-change listener, which refreshes the canvas. The edge
        // therefore appears only because the axiom exists - if the change were rejected,
        // no edge would be drawn.
        getOWLModelManager().applyChanges(changes);
    }

    private static int indexOf(String[] values, String needle) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(needle)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Retracts the axiom an edge stands for, after confirmation.
     *
     * <p>Deliberately worded and separated from "Remove from canvas (keeps axioms)": one
     * changes the ontology and one changes only the view, and confusing them would lose a
     * user's work. Legacy rdfs:domain/rdfs:range edges get an extra warning because those
     * axioms are global - every other arrow drawn with the same property depends on them.
     */
    private void deleteAxiomFor(String edgeId) {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        List<OWLOntologyChange> removals;
        try {
            removals = AxiomRemoval.removalsFor(ontology, edgeId);
        } catch (AxiomRemoval.UnknownEdgeException unknown) {
            JOptionPane.showMessageDialog(this, unknown.getMessage(),
                    "Cannot delete this edge", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (removals.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "That axiom is not asserted in this ontology - it may be inferred, or "
                            + "it may live in an imported ontology.",
                    "Nothing to delete", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("Permanently remove ")
                .append(removals.size() == 1 ? "this axiom" : removals.size() + " axioms")
                .append(" from the ontology?\n\n");
        for (OWLOntologyChange change : removals) {
            message.append("    ").append(change.getAxiom()).append('\n');
        }
        if (AxiomRemoval.isGlobalDomainRange(edgeId)) {
            message.append("\nThese are GLOBAL rdfs:domain / rdfs:range axioms. Every other "
                    + "arrow drawn with this property depends on them and will disappear too.");
        }
        message.append("\n\nTo hide the arrow without changing the ontology, use "
                + "\"Remove from canvas\" on a node instead.");

        int answer = JOptionPane.showConfirmDialog(this, message.toString(),
                "Delete axiom", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) {
            getOWLModelManager().applyChanges(removals);
        }
    }

    /**
     * Accepts entity drops from the palette.
     *
     * <p>This must be a {@link TransferHandler} on the component, not a raw
     * {@code DropTarget} on the graph control. mxGraphComponent installs its own transfer
     * handler (see {@code createTransferHandler}), which owns the component's drop plumbing -
     * a competing DropTarget is simply never called, which is exactly how the first attempt
     * failed: the palette dragged, and nothing landed.
     *
     * <p>Anything this handler does not recognise is delegated back to mxGraph's handler, so
     * the component's own drag behaviour keeps working.
     */
    private void installEntityDropTarget() {
        final TransferHandler mxHandler = graphComponent.getTransferHandler();
        graphComponent.setTransferHandler(new TransferHandler() {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean canImport(TransferSupport support) {
                if (support.isDataFlavorSupported(
                        OWLObjectDataFlavor.OWL_OBJECT_DATA_FLAVOR)) {
                    return true;
                }
                return mxHandler != null && mxHandler.canImport(support);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (support.isDataFlavorSupported(
                        OWLObjectDataFlavor.OWL_OBJECT_DATA_FLAVOR)) {
                    try {
                        Object payload = support.getTransferable().getTransferData(
                                OWLObjectDataFlavor.OWL_OBJECT_DATA_FLAVOR);
                        Point graphPoint =
                                toGraphPoint(support.getDropLocation().getDropPoint());
                        if (dropEntities(payload, graphPoint)) {
                            return true;
                        }
                    } catch (Exception notOurs) {
                        // Something else was dragged in. Fall through to mxGraph rather than
                        // consuming the drop, or its own cell moves would stop working.
                        LOGGER.debug("OntoBoard: not an OWL entity drop", notOurs);
                    }
                }
                return mxHandler != null && mxHandler.importData(support);
            }
        });
        // The trees are the drag source, so a drop has to be accepted anywhere the canvas is
        // visible - including the empty area, which is not a cell and so is not something
        // mxGraph's own handler would claim.
        graphComponent.getGraphControl().setTransferHandler(
                graphComponent.getTransferHandler());
    }

    /**
     * Puts entities dragged in from Protege's trees onto the canvas at {@code at}.
     *
     * <p>Adds existing terms rather than creating new ones, which is the difference between this
     * and double-clicking. Several can arrive at once, because Protege's trees allow a multiple
     * selection, and they are laid out in a small grid from the drop point so a drag of twenty
     * classes does not stack them all on one spot.
     *
     * @return true when at least one entity was recognised, so the drop is consumed
     */
    private boolean dropEntities(Object payload, Point at) {
        if (!(payload instanceof List)) {
            return false;
        }
        int added = 0;
        int column = 0;
        int row = 0;
        for (Object dragged : (List<?>) payload) {
            if (!(dragged instanceof OWLEntity)) {
                // Protege can carry class expressions and axioms in the same flavour. Those have
                // no node, so they are skipped rather than turned into something invented.
                continue;
            }
            String iri = ((OWLEntity) dragged).getIRI().toString();
            if (!membership.add(iri)) {
                continue;
            }
            CanvasLayout.NodeLayout position = new CanvasLayout.NodeLayout();
            position.x = at.x + column * 190;
            position.y = at.y + row * 90;
            position.w = 160;
            position.h = 60;
            layout.nodes.put(iri, position);
            added++;
            if (++column == 3) {
                column = 0;
                row++;
            }
        }
        if (added == 0) {
            return false;
        }
        refresh();
        saveLayoutTo(currentOntologyFile);
        return true;
    }

    /**
     * Double-clicking empty canvas creates a class there.
     *
     * <p>The fastest path to a new term, and the one every other diagram tool offers.
     *
     * <p>This replaced a palette whose two items had to be dragged onto the canvas. Choosing
     * "Class" before dragging is a mode, and modes are ceremony for the most common action in
     * ontology sketching - especially when the canvas already had to give up a column of width to
     * hold the palette. Existing terms now arrive by being dragged from Protege's own trees, which
     * is a different gesture for a different thing: this creates, that adds.
     */
    private void installDoubleClickToCreate() {
        graphComponent.getGraphControl().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2 || event.isPopupTrigger()) {
                    return;
                }
                if (graphComponent.getCellAt(event.getX(), event.getY()) != null) {
                    // Double-clicking a node is not a request for a new one.
                    return;
                }
                com.mxgraph.util.mxPoint at = graphComponent.getPointForEvent(event);
                createEntityAt(EntityFactory.Kind.CLASS, (int) at.getX(), (int) at.getY());
            }
        });
    }

    /**
     * Converts a point in the component's coordinates to graph coordinates.
     *
     * <p>A drop point arrives relative to the visible component, so it has to be shifted by
     * the scroll position and divided by the zoom - otherwise a node dropped on a scrolled or
     * zoomed canvas appears somewhere else entirely.
     */
    private Point toGraphPoint(Point componentPoint) {
        Point scrolled = new Point(componentPoint);
        if (graphComponent.getViewport() != null) {
            Point offset = graphComponent.getViewport().getViewPosition();
            scrolled.translate(offset.x, offset.y);
        }
        double scale = graph.getView().getScale();
        if (scale <= 0) {
            scale = 1;
        }
        return new Point((int) (scrolled.x / scale), (int) (scrolled.y / scale));
    }

    /**
     * Arranges the diagram when nothing has a stored position yet.
     *
     * <p>Without this, a freshly populated canvas stacks everything near the origin and
     * looks broken. Only applied when the layout carries no geometry, so a user's own
     * arrangement is never silently rearranged.
     */
    private void autoArrangeIfUnpositioned() {
        if (!layout.nodes.isEmpty()) {
            return;
        }
        if (membership.size() < 2) {
            return;
        }
        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);
        capturePositions();
    }

    /** Guidance while the board is empty, the board once it is not. */
    private void showAppropriateCard() {
        if (cards == null) {
            return;
        }
        cards.show(centre, membership.size() == 0 ? "start" : "canvas");
    }

    /** The same wizard the Tools menu offers, reachable from the empty board. */
    private void runNewProjectWizard() {
        de.fizkarlsruhe.ise.ontoboard.odk.ProjectWizard.show(this, getOWLModelManager());
    }

    /**
     * Opens an existing ODK repository.
     *
     * <p>Users think in terms of "my ontology repo", not "the file at
     * src/ontology/foo-edit.owl", and picking the generated release file by mistake means
     * their edits get overwritten by the next build. So this takes a folder and works out
     * what to open, refusing rather than guessing when it cannot tell.
     */
    private void openExistingProject() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select an ODK project folder");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        OdkProjectLoader.Detected project;
        try {
            project = OdkProjectLoader.detect(chooser.getSelectedFile());
        } catch (RuntimeException notAProject) {
            JOptionPane.showMessageDialog(this, notAProject.getMessage(),
                    "Not an ODK project", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            // handleLoadFrom, not loadOntologyFromOntologyDocument. The latter loads the
            // ontology into the OWLOntologyManager and stops there: Protege's
            // OWLModelManager never hears about it, so it does not become the active
            // ontology, does not appear in the ontology list, and the class hierarchy
            // carries on showing whatever was open before. The dialog said "Project
            // opened" and nothing appeared, which is exactly what was reported.
            // handleLoadFrom is the call Protege's own File > Open makes.
            if (!getOWLEditorKit().handleLoadFrom(project.getEditFile().toURI())) {
                JOptionPane.showMessageDialog(this,
                        "Protege declined to open "
                                + project.getEditFile().getName()
                                + ". It may already be open in another window.",
                        "Not opened", JOptionPane.WARNING_MESSAGE);
                return;
            }
            JOptionPane.showMessageDialog(this,
                    "Opened " + project.getTitle() + "\n\n"
                            + project.getEditFile().getAbsolutePath()
                            + "\n\nAdd entities from the class hierarchy, or double-click "
                            + "the board to create one.",
                    "Project opened", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception couldNotLoad) {
            JOptionPane.showMessageDialog(this,
                    "Found " + project.getEditFile().getName()
                            + " but Protege could not load it:\n" + couldNotLoad.getMessage(),
                    "Could not open", JOptionPane.ERROR_MESSAGE);
        }
    }
}
