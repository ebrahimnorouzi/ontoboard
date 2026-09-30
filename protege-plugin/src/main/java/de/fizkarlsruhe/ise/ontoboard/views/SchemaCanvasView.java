package de.fizkarlsruhe.ise.ontoboard.views;

import com.mxgraph.swing.mxGraphOutline;
import com.mxgraph.util.mxEvent;
import com.mxgraph.util.mxEventSource.mxIEventListener;
import de.fizkarlsruhe.ise.ontoboard.axiom.AxiomRemoval;
import de.fizkarlsruhe.ise.ontoboard.axiom.DrawnEdge;
import de.fizkarlsruhe.ise.ontoboard.axiom.HierarchyAxioms;
import de.fizkarlsruhe.ise.ontoboard.axiom.EdgeAxioms;
import de.fizkarlsruhe.ise.ontoboard.axiom.EntityFactory;
import de.fizkarlsruhe.ise.ontoboard.axiom.RelationDialog;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasExport;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasIcons;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasLayouts;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasMembership;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasSearch;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasZoom;
import de.fizkarlsruhe.ise.ontoboard.canvas.CollaborativeGraphComponent;
import de.fizkarlsruhe.ise.ontoboard.canvas.LegendPanel;
import de.fizkarlsruhe.ise.ontoboard.canvas.PrefixColours;
import de.fizkarlsruhe.ise.ontoboard.canvas.SchemaGraph;
import de.fizkarlsruhe.ise.ontoboard.canvas.SelectionBridge;
import de.fizkarlsruhe.ise.ontoboard.canvas.StartPanel;
import de.fizkarlsruhe.ise.ontoboard.collab.BoardId;
import de.fizkarlsruhe.ise.ontoboard.collab.CollabDialog;
import de.fizkarlsruhe.ise.ontoboard.collab.CollabSession;
import de.fizkarlsruhe.ise.ontoboard.collab.CollabSettings;
import de.fizkarlsruhe.ise.ontoboard.collab.CollabSettingsStore;
import de.fizkarlsruhe.ise.ontoboard.collab.OperationMapper;
import de.fizkarlsruhe.ise.ontoboard.collab.PeerGeometry;
import de.fizkarlsruhe.ise.ontoboard.layout.BoardHistory;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayoutStore;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes;
import de.fizkarlsruhe.ise.ontoboard.prov.EditWatcher;
import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.prov.ProvenanceSettings;
import de.fizkarlsruhe.ise.ontoboard.reason.InferredEdges;
import de.fizkarlsruhe.ise.ontoboard.reason.ProfileCheck;
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
import org.semanticweb.owlapi.model.OWLAxiom;
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

    /**
     * Where the board's arrangement may be written, or null when it may not.
     *
     * <p>Separate from {@link #currentOntologyFile} because that field answers a different
     * question - which project the open ontology belongs to - and the two were one field. Refusing
     * to write a sidecar therefore also switched off ID-range minting and provenance for the rest
     * of the session: {@code TermMinter.forOntologyFile(null, ...)} falls back to naming from the
     * typed text, so "New class here..." in an ODK project silently minted
     * {@code ontologyIri#TypedName} instead of an identifier from the editor's own block - outside
     * the project's scheme, permanently, which is what the ID ranges exist to prevent.
     *
     * <p>Nulling a field to protect a file must not change what the identifiers mean.
     */
    private File layoutFile;
    private OWLOntologyChangeListener changeListener;
    private OWLModelManagerListener modelManagerListener;
    /** The last peer-mismatch warning shown, so it is not repeated on every cursor move. */
    private String lastPeerOntologyWarning;
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
     * The Find box, and what it last found.
     *
     * <p>The projection is kept because search asks a question only the drawn board can answer -
     * "which of these is on screen" - and rebuilding it per keystroke would re-walk the ontology on
     * every letter typed. {@code ontologyTerms} is the wider index, built on the first search that
     * finds nothing on the board and dropped on the next refresh, because the answer to "is this term
     * in the ontology at all" changes whenever the ontology does.
     */
    private javax.swing.JTextField searchField;
    private javax.swing.JLabel searchCount;
    private Projection rendered;
    private List<CanvasNode> searchMatches = Collections.emptyList();
    private int searchCursor;
    private List<CanvasNode> ontologyTerms;

    /** The zoom readout in the status bar. A button, because clicking it returns to 100%. */
    private JButton zoomReadout;

    /** Kept so the algorithm menu can be popped underneath it. */
    private JButton arrangeButton;

    /** The zoom controls, floating over the bottom-right of the board. */
    private JPanel zoomCluster;

    /** The overview, floating above the zoom controls. */
    private JPanel minimapPanel;

    /** The outline inside it, kept so the open/closed state can be restored. */
    private mxGraphOutline minimapOutline;

    /**
     * What each expansion added, so it can be taken back.
     *
     * <p>Held for the session and not written to the sidecar. Collapse is an answer to "I have just
     * expanded this and it was too much", which is a question asked seconds later and never after
     * reopening the project - and a sidecar that recorded it would be promising an undo that survives
     * a restart, which is A5 in the canvas plan and is a different piece of work.
     */
    private final java.util.Map<String, List<String>> expansions =
            new java.util.LinkedHashMap<String, List<String>>();

    /**
     * Undo for the board, which had none.
     *
     * <p>Not persisted, and cleared when the ontology changes. An undo stack from another ontology
     * would offer to restore a board of identifiers this one does not declare, which the sidecar loader
     * would then prune to nothing - so "put it back" would empty the board.
     */
    private final BoardHistory history = new BoardHistory();

    /**
     * True while this class is moving cells itself, so the move listener does not record a step.
     *
     * <p>Arrange already remembers the board before it runs; without this it would push a second,
     * identical step from the CELLS_MOVED the layout produces, and one Ctrl+Z would look like it had
     * done nothing.
     */
    private boolean movingProgrammatically;
    /**
     * The live session, or null when working through git. Created on demand from the
     * Collaborate dialog rather than at startup, because most sessions are single-user and
     * opening a socket nobody asked for is the wrong default.
     */
    private CollabSession collab;
    private JButton collaborateButton;
    private javax.swing.JLabel collabStatus;

    /**
     * What the board just did, kept apart from what the session is doing.
     *
     * <p>One label served both until 1.67.0, and they are not the same kind of message. A board
     * confirmation is transient - "Arranged the board", "Undid: adding 7 terms" - while a session
     * notice is persistent state that needs acting on: "3 changes not shared", "Disconnected - you
     * switched ontology". Whichever wrote last won, permanently. So arranging the board erased the
     * only warning that a colleague would never see your edit, and nothing brought it back.
     */
    private javax.swing.JLabel boardStatus;
    /**
     * When the cursor was last published. Presence is sent on mouse movement, which fires far
     * faster than anyone needs to see, so it is throttled - and the client's own heartbeat
     * keeps the cursor alive in between.
     */
    private long lastCursorSentAt;

    /**
     * The last cursor position published, so presence can be re-announced without one.
     *
     * <p>Presence used to be sent only from mouse motion, so a selection made with the keyboard, from
     * the Find box, or by clicking once and not moving never reached anybody. Re-announcing needs a
     * position, and the last one the peer saw is a better answer than the origin - which is where the
     * canvas is not, for anybody who has scrolled.
     */
    private Point lastCursorPoint;

    @Override
    protected void initialiseOWLView() {
        setLayout(new BorderLayout());

        graph = new SchemaGraph();
        graphComponent = new CollaborativeGraphComponent(graph);
        // Turned on in installDragToConnect below, which also fixes the library default that would
        // have made a press on a node start a connection rather than move it.
        graphComponent.setToolTips(true);
        graphComponent.setPanning(true);
        graphComponent.getPanningHandler().setEnabled(true);
        // Card deck so an empty board shows guidance instead of a blank grid.
        zoomCluster = buildZoomCluster();
        minimapPanel = buildMinimap();

        cards = new CardLayout();
        centre = new JPanel(cards);
        centre.add(buildBoardLayers(), "canvas");
        centre.add(new StartPanel(this::runNewProjectWizard, this::openExistingProject,
                this::addSelectedEntityToCanvas, this::addEverythingToCanvas), "start");
        add(centre, BorderLayout.CENTER);
        // Before the toolbar, because the status bar owns collabStatus and the toolbar used to.
        add(buildStatusBar(), BorderLayout.SOUTH);

        // The outline used to be pinned EAST at a fixed 180px, present even while the start card
        // was showing - where it is a blank grey rectangle beside a "nothing here yet" message. It
        // floats over the board now, above the zoom cluster, and collapses.
        graphComponent.setPageBackgroundColor(
                java.awt.Color.decode(de.fizkarlsruhe.ise.ontoboard.canvas.SchemaStyles
                        .CANVAS_BACKGROUND));
        add(buildToolBar(), BorderLayout.NORTH);
        graphComponent.getViewport().setOpaque(true);
        graphComponent.getViewport().setBackground(
                Color.decode(de.fizkarlsruhe.ise.ontoboard.canvas.SchemaStyles.CANVAS_BACKGROUND));
        // A border, and its colour taken from the theme. The canvas stays light on purpose - it is
        // the surface every exported PNG is composed on, and a diagram whose colours depend on the
        // author's IDE theme is not reproducible - but on a dark look and feel a borderless white
        // slab reads as a rendering fault rather than as a choice.
        java.awt.Color themePanel = javax.swing.UIManager.getColor("Panel.background");
        boolean darkTheme = themePanel != null && (0.2126 * themePanel.getRed()
                + 0.7152 * themePanel.getGreen() + 0.0722 * themePanel.getBlue()) / 255.0 < 0.4;
        graphComponent.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 1, 1, 1,
                darkTheme ? new java.awt.Color(0x3A, 0x40, 0x4A)
                        : new java.awt.Color(0xD8, 0xDD, 0xE3)));
        graphComponent.setGridVisible(true);
        // Dots rather than lines, and pale enough to be texture: #D4D8DF is about 1.5:1 on the
        // canvas, present to align against and never competing with a 1.6px node stroke. The pitch
        // and the snap step are SchemaGraph's setGridSize - the component owns how the grid looks,
        // mxGraph owns what it does.
        graphComponent.setGridStyle(com.mxgraph.swing.mxGraphComponent.GRID_STYLE_DOT);
        graphComponent.setGridColor(java.awt.Color.decode("#D4D8DF"));
        // Enter commits an in-place edit and Escape abandons it, which is what every other text
        // field in Protege does. Without them the only way out of an edit is to click elsewhere.
        graphComponent.setEnterStopsCellEditing(true);
        graphComponent.setEscapeEnabled(true);
        installEntityDropTarget();
        installDoubleClickToCreate();
        installCursorSharing();
        installSelection();
        installDragToConnect();
        installUndo();
        installBoardShortcuts();
        installWheelZoom();
        installZoomReadout();
        installKeyboardShortcuts();

        loadLayoutForActiveOntology();
        installContextMenu();
        refresh();

        selectionBridge = new SelectionBridge(graph, this::pushSelectionToProtege);
        selectionBridge.install();

        // Once, ever, per user. The pan gesture inverted in 1.68.0 - a plain drag selects a region
        // now and panning moved to space, the middle button and the right - and an inversion nobody
        // is told about is indistinguishable from a fault. It goes to the board channel, so it
        // cannot overwrite a collaboration warning.
        //
        // It can still collide with a board message, and one in particular: loadLayoutForActiveOntology
        // above says "the saved arrangement could not be read" on the same label, and that sentence is
        // the last one this hint may be allowed to bury. So it yields, and does not mark itself seen -
        // a hint that is worth showing once is worth showing next time instead.
        java.util.prefs.Preferences prefs =
                java.util.prefs.Preferences.userNodeForPackage(SchemaCanvasView.class);
        if (firstRunHintFits(prefs.getBoolean("ontoboard.hintsSeen", false),
                boardStatus == null ? null : boardStatus.getText())) {
            setStatus("Drag to select, space-drag to pan, wheel to zoom, double-click for a new "
                    + "class. Press ? on the board for every shortcut.");
            prefs.putBoolean("ontoboard.hintsSeen", true);
        }
        // And the overview starts open only on a board big enough to need one.
        setMinimapOpen(prefs.getBoolean("ontoboard.minimap.open", membership.size() >= 25),
                minimapPanel);

        positionSaveTimer = new Timer(800, event -> saveLayoutTo(layoutFile));
        positionSaveTimer.setRepeats(false);
        cellsMovedListener = (sender, event) -> {
            // One step per completed drag. The guard here used to be "only if the save timer is not
            // running", on the stated grounds that mxGraph fires CELLS_MOVED repeatedly during a
            // drag. It does not: mxGraphHandler sets livePreview = false and imagePreview = true in
            // 4.2.2, so a drag shows a ghost bitmap and the model is touched exactly once, on
            // release. What that guard actually did was swallow the step for any second drag started
            // within 800ms of the first - move one node, immediately move another, press Ctrl+Z, and
            // both went back with no way to take back only the second.
            if (!movingProgrammatically) {
                rememberBoard("moving things on the board");
            }
            capturePositions();
            positionSaveTimer.restart();
        };
        graph.addListener(mxEvent.CELLS_MOVED, cellsMovedListener);
        installFrameDragging();

        // Without this an in-place edit is lost at the next refresh, which any edit anywhere in
        // Protege triggers: mxGraph writes the new text into the cell, and render rebuilds every
        // cell from the layout, which still holds the old words.
        graph.addListener(mxEvent.LABEL_CHANGED, (sender, event) -> {
            Object cell = event.getProperty("cell");
            String id = graph.getIdForCell(cell);
            if (id == null || !SchemaGraph.isAnnotationId(id)) {
                return;
            }
            Object value = graph.getModel().getValue(cell);
            String text = value == null ? "" : value.toString().trim();
            if (text.isEmpty()) {
                // An emptied note is almost always a mis-keyed edit rather than a request to blank
                // it, and a note with nothing in it is indistinguishable from a rendering fault.
                refresh();
                setStatus("A sticky note needs some words. Nothing was changed.");
                return;
            }
            rememberBoard(id.startsWith(SchemaGraph.NOTE_ID_PREFIX)
                    ? "editing a sticky note" : "renaming a frame");
            for (CanvasLayout.NoteLayout note : layout.notes) {
                if (id.equals(note.id)) {
                    note.text = text;
                }
            }
            for (CanvasLayout.FrameLayout frame : layout.frames) {
                if (id.equals(frame.id)) {
                    frame.label = text;
                }
            }
            saveLayoutTo(layoutFile);
        });

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
            stampEditsMadeElsewhere(changes);
            refresh();
        };
        getOWLModelManager().addOntologyChangeListener(changeListener);

        // Axiom edits, active-ontology switches and classification are three separate Protege
        // event channels, and the canvas has to hear all three. Switching the active ontology
        // fires no axiom change; classifying fires no axiom change either. See
        // shouldRefreshFor for which of them matter and why.
        modelManagerListener = event -> {
            if (event.isType(EventType.ACTIVE_ONTOLOGY_CHANGED)) {
                switchToActiveOntology();
            } else if (shouldRefreshFor(event.getType(), showInferences)) {
                refresh();
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
        saveLayoutTo(layoutFile);
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
        rememberBoard("adding " + getOWLModelManager().getRendering(selected));
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
    /**
     * A label cut to fit, ending in an ellipsis.
     *
     * <p>Package-private and pure so the rule can be tested at the boundary rather than eyeballed on
     * one example. U+2026 is present in Tahoma, Segoe UI and the logical Dialog family, which are the
     * three this plugin can actually end up drawing with.
     */
    static String elide(String label, int limit) {
        if (label == null) {
            return "";
        }
        String trimmed = label.trim();
        if (trimmed.length() <= limit) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(1, limit - 1)).trim() + "\u2026";
    }

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
        // Elided rather than allowed to grow: the button's width is pinned in buildToolBar, so a
        // long label would otherwise be clipped mid-word by the layout instead of ending in a
        // character that says there is more. The tooltip carries the whole IRI either way.
        addSelectedButton.setText("Add " + elide(label, 14));
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
        // Out to collaborators as well as to Protege. The canvas selection is the most useful thing
        // presence can carry - "she is looking at Margherita" - and it was the one thing the presence
        // message only ever carried by accident, when a mouse movement happened to follow.
        announcePresence();
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

    /**
     * Shows the key to the diagram, built from the same styles the canvas draws with.
     *
     * <p>Given this board's namespace colours, not just the stylesheet. The canvas outlines every
     * node in a colour derived from its namespace and the key used to say nothing about it, so a
     * board with two vocabularies showed purple outlines beside a legend whose only purple swatch
     * was "Individual" - and the obvious reading was wrong.
     */
    private void showLegend() {
        JOptionPane.showMessageDialog(this, new LegendPanel(layout.prefixColors),
                "What the diagram means", JOptionPane.PLAIN_MESSAGE);
    }

    /**
     * Whether a model-manager event means the diagram is now out of date.
     *
     * <p>Classification is its own event channel. Starting a reasoner, or re-running one after an
     * edit, changes nothing about the axioms, so neither the ontology-change listener nor the
     * active-ontology listener hears anything - and the canvas went on showing the inferences it
     * had computed before, or none at all. The symptom was precisely backwards from useful: turn
     * inferences on with no reasoner started, get told to start one, start one, and the canvas
     * still showed nothing until you toggled the button off and on again or happened to make an
     * edit.
     *
     * <p>Only when inferences are actually being shown, because a classification changes nothing a
     * user can see on an asserted-only diagram, and re-rendering a large board for no visible
     * difference is a stutter with no purpose.
     *
     * <p>Static and package-visible so the policy can be tested; everything around it is Swing.
     *
     * @param showingInferences whether the reasoner's conclusions are currently on the diagram
     */
    static boolean shouldRefreshFor(EventType type, boolean showingInferences) {
        if (type == null) {
            return false;
        }
        switch (type) {
            case ONTOLOGY_CLASSIFIED:
                // The conclusions have just changed. This is the event the canvas most needed and
                // was not listening for.
                return showingInferences;
            case REASONER_CHANGED:
                // A different reasoner reaches different conclusions - ELK and HermiT genuinely
                // disagree on an ontology that uses anything outside OWL EL - so what is drawn is
                // no longer what the selected reasoner says.
                return showingInferences;
            case ONTOLOGY_RELOADED:
                // Reverted from disk. Every axiom may have changed and no axiom-change event is
                // fired for it.
                return true;
            default:
                // Everything else - visibility, renderer changes, saves, loads of ontologies that
                // are not the active one - either cannot alter this diagram or already arrives
                // through the ontology-change listener, and refreshing twice for one edit makes
                // a large board stutter.
                return false;
        }
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
            List<CanvasEdge> inferred = new ArrayList<CanvasEdge>(InferredEdges.subClassEdges(
                    getOWLModelManager().getReasoner(), membership.asSet(),
                    asserted.getEdges(),
                    getOWLModelManager().getOWLDataFactory()));
            // Types as well as subsumptions. Drawing only subsumptions meant a board of
            // individuals showed nothing when inferences were switched on, which reads as "the
            // reasoner found nothing" rather than "this tool does not look".
            inferred.addAll(InferredEdges.typeEdges(
                    getOWLModelManager().getReasoner(), membership.asSet(),
                    asserted.getEdges(),
                    getOWLModelManager().getOWLDataFactory()));
            if (inferred.isEmpty()) {
                // Still worth re-rendering: no new edges does not mean no unsatisfiable classes,
                // and those are the more important of the two things a reasoner has to say.
                List<CanvasNode> onlyMarked = markUnsatisfiable(asserted.getNodes());
                setStatus("Nothing further was inferred: the ontology already states what it "
                        + "entails for the entities on this board." + unsatisfiableNote(onlyMarked));
                return new Projection(onlyMarked, asserted.getEdges());
            }
            List<CanvasEdge> combined = new ArrayList<CanvasEdge>(asserted.getEdges());
            combined.addAll(inferred);
            List<CanvasNode> nodes = markUnsatisfiable(asserted.getNodes());
            setStatus(inferred.size() + " inferred edge" + (inferred.size() == 1 ? "" : "s")
                    + " shown, dotted and grey." + unsatisfiableNote(nodes));
            return new Projection(nodes, combined);
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

    /**
     * The same nodes, with the ones the reasoner says can have no instances marked.
     *
     * <p>{@code InferredEdges.unsatisfiableClasses} was computed on every refresh and thrown away
     * - the single most useful thing a reasoner has to say, calculated and discarded. An
     * unsatisfiable class is a modelling error, not a shape, and on a diagram that draws it like
     * everything else it is invisible.
     */
    private List<CanvasNode> markUnsatisfiable(List<CanvasNode> nodes) {
        java.util.Set<String> unsatisfiable = InferredEdges.unsatisfiableClasses(
                getOWLModelManager().getReasoner(), membership.asSet());
        if (unsatisfiable.isEmpty()) {
            return nodes;
        }
        List<CanvasNode> marked = new ArrayList<CanvasNode>(nodes.size());
        for (CanvasNode node : nodes) {
            marked.add(unsatisfiable.contains(node.getId()) ? node.asUnsatisfiable() : node);
        }
        return marked;
    }

    /** A sentence about unsatisfiable classes, or nothing when there are none. */
    private static String unsatisfiableNote(List<CanvasNode> nodes) {
        int count = 0;
        for (CanvasNode node : nodes) {
            if (node.isUnsatisfiable()) {
                count++;
            }
        }
        if (count == 0) {
            return "";
        }
        return "  " + count + " class" + (count == 1 ? "" : "es")
                + " on this board cannot have instances - shown in red.";
    }

    /**
     * Says once when the people on this board are editing a different ontology.
     *
     * <p>Once, not on every presence update: peers arrive with every cursor movement, and a
     * dialog per update would be unusable. The warning is repeated only when the set of
     * disagreeing peers changes, which is when there is genuinely something new to say.
     *
     * <p>A dialog rather than the status line, unlike the other collaboration messages, because
     * this one means every edit either side makes is landing in the wrong file - it is not a
     * condition to notice eventually.
     */
    private void warnAboutPeersEditingSomethingElse() {
        if (collab == null) {
            return;
        }
        String warning = collab.peerOntologyWarning();
        if (warning == null) {
            lastPeerOntologyWarning = null;
            return;
        }
        setStatus(warning);
        if (warning.equals(lastPeerOntologyWarning)) {
            return;
        }
        lastPeerOntologyWarning = warning;
        JOptionPane.showMessageDialog(this, warning, "Different ontologies on one board",
                JOptionPane.WARNING_MESSAGE);
    }

    // ------------------------------------------------------------------ notes and frames

    /**
     * A sticky note on the diagram.
     *
     * <p>Deliberately not in the ontology, and the menu says so. A note at x=340, y=90 reading
     * "check this with Bob" is meaningless without the diagram it is stuck to, and putting it in
     * the ontology would mean every consumer of every release downloads somebody's reminder to
     * themselves. The durable, per-term kind that does belong in the ontology is
     * {@code IAO:0000116}, which OntoBoard > Notes writes.
     *
     * <p>Kept in the sidecar, which is committed - so a note does reach collaborators through
     * git, and reaches them live once the sidecar is synced.
     */
    private void createStickyNote(java.awt.Point at) {
        String text = JOptionPane.showInputDialog(this,
                "What should the note say?\n\nThis stays on the diagram - it is not written "
                        + "into the ontology and will not appear in a release.",
                "Sticky note", JOptionPane.PLAIN_MESSAGE);
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        rememberBoard("adding a sticky note");
        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = SchemaGraph.NOTE_ID_PREFIX + nextAnnotationSuffix();
        note.text = text.trim();
        note.x = at == null ? 60 : at.getX();
        note.y = at == null ? 60 : at.getY();
        layout.notes.add(note);
        refresh();
        saveLayoutTo(layoutFile);
    }

    /** A labelled region grouping what is inside it. Also diagram-only. */
    private void createFrame(java.awt.Point at) {
        String label = JOptionPane.showInputDialog(this,
                "What is this group called?\n\nA frame is a region on the diagram. Dragging it "
                        + "takes whatever is inside it along; resizing it does not. If it is really "
                        + "a module, make it one - an import, or IAO:0000113 in branch - rather "
                        + "than a rectangle.",
                "Frame", JOptionPane.PLAIN_MESSAGE);
        if (label == null || label.trim().isEmpty()) {
            return;
        }
        rememberBoard("adding a frame");
        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = SchemaGraph.FRAME_ID_PREFIX + nextAnnotationSuffix();
        frame.label = label.trim();
        frame.x = at == null ? 40 : at.getX();
        frame.y = at == null ? 40 : at.getY();
        frame.w = 340;
        frame.h = 240;
        layout.frames.add(frame);
        refresh();
        saveLayoutTo(layoutFile);
    }

    /**
     * Reads and writes a term's editorial note, on the canvas.
     *
     * <p>{@code IAO:0000116}, the same annotation the Notes menu writes - so this is not a second
     * kind of note but the same one, reachable where the user is already pointing. A sticky note
     * lives in the sidecar and never reaches a release; this is an axiom and does.
     *
     * <p>Applied through {@code OWLModelManager} rather than straight into the ontology, which buys
     * two things: Protege's undo covers it, and the view's change listener publishes it to a live
     * session - so a note written on the canvas reaches a colleague as an {@code updateAnnotation}.
     *
     * <p>Pre-filled with the existing note and empty-means-delete, because the alternative is a
     * separate menu item for removing one and a curator who has to know which to reach for.
     */
    private void editEditorialNote(String iri) {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        if (ontology == null) {
            return;
        }
        IRI entity = IRI.create(iri);
        List<String> existing =
                EditorNotes.notesOn(ontology, entity, EditorNotes.Kind.EDITOR);
        String was = existing.isEmpty() ? "" : existing.get(0);

        javax.swing.JTextArea area = new javax.swing.JTextArea(was, 7, 44);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        String label = graph.getCellForId(iri) == null ? shortNameOf(iri)
                : DisplayLabels.shortNameOf(entity);
        int answer = JOptionPane.showConfirmDialog(this,
                new Object[] {
                    "An editor note on " + label + ". This goes into the ontology and travels with "
                            + "it - clear the box to remove the note.",
                    new javax.swing.JScrollPane(area) },
                was.isEmpty() ? "Add an editorial note" : "Edit the editorial note",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        String now = area.getText() == null ? "" : area.getText().trim();
        List<OWLOntologyChange> changes;
        String said;
        switch (noteEditFor(was, now)) {
            case UNCHANGED:
                setStatus("The note on " + label + " is unchanged.");
                return;
            case REMOVE:
                changes = EditorNotes.removeNote(ontology, entity, EditorNotes.Kind.EDITOR, was);
                said = "Removed the editorial note from " + label + ".";
                break;
            case ADD:
                changes = EditorNotes.addNote(ontology, entity, EditorNotes.Kind.EDITOR, now);
                said = "Added an editorial note to " + label + ". It is in the ontology, so it "
                        + "will appear in a release.";
                break;
            case REPLACE:
            default:
                changes = EditorNotes.replaceNote(ontology, entity, EditorNotes.Kind.EDITOR,
                        was, now);
                said = "Changed the editorial note on " + label + ".";
                break;
        }
        if (changes.isEmpty()) {
            setStatus("Nothing to change in the note on " + label + ".");
            return;
        }
        getOWLModelManager().applyChanges(changes);
        setStatus(said);
    }

    /** What editing a note box amounts to. */
    enum NoteEdit {
        UNCHANGED, ADD, REPLACE, REMOVE
    }

    /**
     * Which of the four things a note box's contents mean, given what was there before.
     *
     * <p>Separated from the dialog because this is the part that can be wrong and the dialog is the
     * part that cannot be tested: a JOptionPane needs a display. Getting it wrong is quiet -
     * replacing when it should add throws away nobody's note but writes a second annotation, and
     * treating whitespace as text leaves an empty note in a release.
     *
     * <p>Both sides are trimmed, so a note whose only change is trailing whitespace is unchanged.
     * That is deliberate: an editor note is prose and an invisible edit to it is not an edit, while
     * the alternative rewrites the axiom - and in a live session republishes it to every peer - for
     * a space nobody typed on purpose.
     */
    static NoteEdit noteEditFor(String was, String now) {
        String before = was == null ? "" : was.trim();
        String after = now == null ? "" : now.trim();
        if (before.equals(after)) {
            return NoteEdit.UNCHANGED;
        }
        if (after.isEmpty()) {
            return NoteEdit.REMOVE;
        }
        return before.isEmpty() ? NoteEdit.ADD : NoteEdit.REPLACE;
    }

    /** The bit after the last # or /, for a label when the ontology offers none. */
    private static String shortNameOf(String iri) {
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut < 0 ? iri : iri.substring(cut + 1);
    }

    /** Changes the text of a note or the label of a frame. */
    private void editAnnotation(String id) {
        for (CanvasLayout.NoteLayout note : layout.notes) {
            if (id.equals(note.id)) {
                String text = JOptionPane.showInputDialog(this, "Note", note.text);
                if (text != null && !text.trim().isEmpty()) {
                    rememberBoard("editing a sticky note");
                    note.text = text.trim();
                    refresh();
                    saveLayoutTo(layoutFile);
                }
                return;
            }
        }
        for (CanvasLayout.FrameLayout frame : layout.frames) {
            if (id.equals(frame.id)) {
                String label = JOptionPane.showInputDialog(this, "Frame name", frame.label);
                if (label != null && !label.trim().isEmpty()) {
                    rememberBoard("renaming a frame");
                    frame.label = label.trim();
                    refresh();
                    saveLayoutTo(layoutFile);
                }
                return;
            }
        }
    }

    /**
     * Removes a note or a frame.
     *
     * <p>No confirmation, unlike deleting a term: nothing in the ontology changes, the sidecar is
     * in git, and a prompt for every sticky note would be the kind of friction that stops people
     * using them. Since 1.62.0 Ctrl+Z on the canvas brings it back, which is the answer a prompt was
     * standing in for.
     */
    private void deleteAnnotation(String id) {
        rememberBoard("deleting a note or frame");
        boolean removed = false;
        for (java.util.Iterator<CanvasLayout.NoteLayout> notes = layout.notes.iterator();
                notes.hasNext();) {
            if (id.equals(notes.next().id)) {
                notes.remove();
                removed = true;
            }
        }
        for (java.util.Iterator<CanvasLayout.FrameLayout> frames = layout.frames.iterator();
                frames.hasNext();) {
            if (id.equals(frames.next().id)) {
                frames.remove();
                removed = true;
            }
        }
        if (removed) {
            refresh();
            saveLayoutTo(layoutFile);
        }
    }

    /**
     * A suffix that does not collide with one made on another machine.
     *
     * <p>The sidecar is committed and merged, so two people adding a note between pulls would
     * otherwise both create note-1 and git would resolve it by keeping one of them.
     */
    private String nextAnnotationSuffix() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /** Puts a line in the toolbar's status label, reusing the collaboration one. */
    private void setStatus(String text) {
        say(boardStatus, text, text);
    }

    /**
     * The collaboration channel: connected, retrying, refused, or how much is unshared.
     *
     * <p>Separate from {@link #setStatus} so that a board message cannot overwrite it. The dot is the
     * part that reads at a glance - green connected, amber something needs attention, grey not in a
     * session - and the words are for when it does not.
     */
    private void setSessionStatus(String text, java.awt.Color light) {
        say(collabStatus, text, text);
        if (collabStatus != null) {
            collabStatus.setIcon(new CanvasIcons.Dot(light));
        }
    }

    /** Green: in a session and up to date. */
    private static final java.awt.Color LIGHT_CONNECTED = new java.awt.Color(0x16, 0xA3, 0x4A);

    /** Amber: in a session, and something the user has to know about. */
    private static final java.awt.Color LIGHT_ATTENTION = new java.awt.Color(0xD9, 0x77, 0x06);

    /** Grey: not in a session. Working through git, which is the ordinary state. */
    private static final java.awt.Color LIGHT_OFFLINE = new java.awt.Color(0x9C, 0xA3, 0xAF);



    /**
     * One keystroke on the canvas, without an anonymous action per binding.
     *
     * <p>Bound on the graph component's ancestor map, like every other shortcut here, so none of
     * them fires while the focus is in Prot&eacute;g&eacute;'s class hierarchy or an annotation
     * field.
     */
    private void bindOnCanvas(String name, int keyCode, int modifiers, final Runnable action) {
        graphComponent.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(javax.swing.KeyStroke.getKeyStroke(keyCode, modifiers), name);
        graphComponent.getActionMap().put(name, new javax.swing.AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                action.run();
            }
        });
    }

    /**
     * The zoom and selection keys every board has.
     *
     * <p>Ctrl+0, Ctrl+1 and Ctrl+2 are the three a person reaches for without looking: actual size,
     * fit everything, frame what I have selected. Plus and minus go through the same clamped step the
     * wheel uses rather than {@code zoomIn()}, which has no ceiling.
     *
     * <p>Ctrl+A selects vertices only, deliberately. Delete on a selection containing edges produces
     * the "an arrow is an axiom, so use the right-click menu" message once per edge, which reads as a
     * half-broken shortcut rather than as the refusal it is.
     */
    private void installBoardShortcuts() {
        for (int mask : new int[] { java.awt.event.InputEvent.CTRL_DOWN_MASK,
                java.awt.event.InputEvent.META_DOWN_MASK }) {
            bindOnCanvas("ontoboard.zoomActual", java.awt.event.KeyEvent.VK_0, mask, () -> {
                graphComponent.zoomActual();
                updateZoomReadout();
                setStatus("Actual size.");
            });
            bindOnCanvas("ontoboard.fitBoard", java.awt.event.KeyEvent.VK_1, mask,
                    this::fitToWindow);
            bindOnCanvas("ontoboard.fitSelection", java.awt.event.KeyEvent.VK_2, mask,
                    this::fitToSelection);
            bindOnCanvas("ontoboard.zoomIn", java.awt.event.KeyEvent.VK_EQUALS, mask,
                    () -> zoomAt(true, null));
            bindOnCanvas("ontoboard.zoomInPad", java.awt.event.KeyEvent.VK_ADD, mask,
                    () -> zoomAt(true, null));
            bindOnCanvas("ontoboard.zoomOut", java.awt.event.KeyEvent.VK_MINUS, mask,
                    () -> zoomAt(false, null));
            bindOnCanvas("ontoboard.zoomOutPad", java.awt.event.KeyEvent.VK_SUBTRACT, mask,
                    () -> zoomAt(false, null));
            bindOnCanvas("ontoboard.selectAll", java.awt.event.KeyEvent.VK_A, mask, () -> {
                graph.selectCells(true, false);
                setStatus(graph.getSelectionCount() + " selected. Delete takes them off the board; "
                        + "the ontology is unchanged.");
            });
            bindOnCanvas("ontoboard.duplicate", java.awt.event.KeyEvent.VK_D, mask,
                    this::duplicateSelectedAnnotations);
        }
    }

    /**
     * Ctrl+D copies the selected sticky notes and frames.
     *
     * <p>Notes and frames only. A node is a term and a term appears once - that is the invariant the
     * whole canvas rests on, and the same one a Ctrl+drag quietly broke until 1.66.0 - so duplicating
     * one is not a gesture this board offers. Saying so is better than doing nothing, because doing
     * nothing is indistinguishable from a shortcut that is not bound.
     */
    private void duplicateSelectedAnnotations() {
        Object[] selected = graph.getSelectionCells();
        if (selected == null || selected.length == 0) {
            setStatus("Nothing selected. Ctrl+D copies sticky notes and frames.");
            return;
        }

        List<CanvasLayout.NoteLayout> newNotes = new ArrayList<CanvasLayout.NoteLayout>();
        List<CanvasLayout.FrameLayout> newFrames = new ArrayList<CanvasLayout.FrameLayout>();
        for (Object cell : selected) {
            String id = graph.getIdForCell(cell);
            if (id == null) {
                continue;
            }
            for (CanvasLayout.NoteLayout note : layout.notes) {
                if (id.equals(note.id)) {
                    CanvasLayout.NoteLayout copy = note.copy();
                    copy.id = SchemaGraph.NOTE_ID_PREFIX + nextAnnotationSuffix();
                    copy.x += 20;
                    copy.y += 20;
                    newNotes.add(copy);
                }
            }
            for (CanvasLayout.FrameLayout frame : layout.frames) {
                if (id.equals(frame.id)) {
                    CanvasLayout.FrameLayout copy = frame.copy();
                    copy.id = SchemaGraph.FRAME_ID_PREFIX + nextAnnotationSuffix();
                    copy.x += 20;
                    copy.y += 20;
                    newFrames.add(copy);
                }
            }
        }
        if (newNotes.isEmpty() && newFrames.isEmpty()) {
            setStatus("A node is a term, and a term appears once. Ctrl+D copies sticky notes and "
                    + "frames.");
            return;
        }

        int copied = newNotes.size() + newFrames.size();
        rememberBoard(copied == 1 ? "copying a note or frame" : "copying " + copied + " of them");
        layout.notes.addAll(newNotes);
        layout.frames.addAll(newFrames);
        refresh();
        saveLayoutTo(layoutFile);
        setStatus("Copied " + copied + (copied == 1 ? " item" : " items")
                + ", offset so you can see both. The ontology is unchanged.");
    }

    /**
     * Recolours a sticky note or a frame.
     *
     * <p>{@code NoteLayout.color} has existed, been persisted in the sidecar and been read by
     * {@code SchemaGraph.render} since sticky notes were added - and nothing in the plugin has ever
     * written it, so every note on every board has been the same yellow. The model, the persistence
     * and the renderer were all already there; only the menu was missing.
     */
    private void recolourAnnotation(String id, String hex) {
        for (CanvasLayout.NoteLayout note : layout.notes) {
            if (id.equals(note.id)) {
                rememberBoard("recolouring a sticky note");
                note.color = hex;
                refresh();
                saveLayoutTo(layoutFile);
                return;
            }
        }
        for (CanvasLayout.FrameLayout frame : layout.frames) {
            if (id.equals(frame.id)) {
                rememberBoard("recolouring a frame");
                frame.stroke = hex;
                refresh();
                saveLayoutTo(layoutFile);
                return;
            }
        }
    }

    /** The colours a note can be, as {name, hex}. All light enough for the note's dark ink. */
    private static final String[][] NOTE_COLOURS = {
        {"Yellow", "#FFF3B0"}, {"Green", "#D8F0D5"}, {"Blue", "#D6E8FB"},
        {"Pink", "#FBD9E6"}, {"Orange", "#FFE2C4"}, {"Violet", "#E5DCF6"},
    };

    /** The colours a frame's outline can be, drawn from the palette the nodes use. */
    private static final String[][] FRAME_COLOURS = {
        {"Blue", "#2D6FBF"}, {"Green", "#2F7A4C"}, {"Amber", "#B35C00"}, {"Slate", "#3B4652"},
    };

    /** A small filled square, so a colour menu shows its colours. */
    private static final class SwatchIcon implements javax.swing.Icon {

        private final java.awt.Color colour;

        SwatchIcon(java.awt.Color colour) {
            this.colour = colour;
        }

        @Override
        public int getIconWidth() {
            return 14;
        }

        @Override
        public int getIconHeight() {
            return 14;
        }

        @Override
        public void paintIcon(java.awt.Component host, java.awt.Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(colour);
                g.fillRoundRect(x, y + 1, 13, 12, 4, 4);
                g.setColor(java.awt.Color.decode("#9AA3AF"));
                g.drawRoundRect(x, y + 1, 13, 12, 4, 4);
            } finally {
                g.dispose();
            }
        }
    }

    /** The colour submenu for a note or a frame, or null when the id is neither. */
    private javax.swing.JMenu colourMenuFor(final String id) {
        boolean isNote = id.startsWith(SchemaGraph.NOTE_ID_PREFIX);
        String[][] palette = isNote ? NOTE_COLOURS : FRAME_COLOURS;
        javax.swing.JMenu menu = new javax.swing.JMenu(isNote ? "Note colour" : "Frame colour");
        for (final String[] swatch : palette) {
            JMenuItem item = new JMenuItem(swatch[0],
                    new SwatchIcon(java.awt.Color.decode(swatch[1])));
            item.addActionListener(a -> recolourAnnotation(id, swatch[1]));
            menu.add(item);
        }
        return menu;
    }

    /**
     * Dragging a frame takes whatever is inside it along.
     *
     * <p>The one behaviour that makes a frame a frame rather than a rectangle. {@code SchemaGraph}
     * inserts frames, terms, edges and notes all under the default parent, so a frame is a
     * <em>sibling</em> of the nodes it visually encloses and {@code moveCells} moves only the frame:
     * drag the "Toppings" frame and it arrives somewhere else, still empty.
     *
     * <p>Real parenting is the wrong fix and was rejected deliberately. Making nodes children of the
     * frame cell makes their geometry relative to it, and {@code captureInto} reads {@code getX()}
     * as an absolute board coordinate straight into the sidecar - so every saved board would shift
     * by the frame origin the first time it was loaded. {@code constrainChildren} and
     * {@code extendParents}, both on by default, would also start resizing frames behind the user.
     *
     * <p>So: after a move, work out which cells were inside the frame's <em>old</em> box and move
     * those by the same delta. {@code moveCells} fires this once at the end, after the geometry is
     * already final, which is why the old box is reconstructed by subtracting the delta rather than
     * read.
     *
     * <p>Containment is by centre point, not by whole bounds: a node overlapping the frame edge by a
     * few pixels is one the user considers inside. Resizing a frame deliberately does not move
     * anything - a frame is a reading aid, and a resize that dragged terms around would make it a
     * container.
     */
    private void installFrameDragging() {
        graph.addListener(mxEvent.MOVE_CELLS, (sender, event) -> {
            if (movingProgrammatically) {
                return;
            }
            Object[] moved = (Object[]) event.getProperty("cells");
            Object dxValue = event.getProperty("dx");
            Object dyValue = event.getProperty("dy");
            if (moved == null || !(dxValue instanceof Number) || !(dyValue instanceof Number)) {
                return;
            }
            double dx = ((Number) dxValue).doubleValue();
            double dy = ((Number) dyValue).doubleValue();
            if (dx == 0 && dy == 0) {
                return;
            }

            java.util.Set<Object> alreadyMoving = new java.util.HashSet<Object>(
                    java.util.Arrays.asList(moved));
            List<Object> passengers = new ArrayList<Object>();
            for (Object cell : moved) {
                String id = graph.getIdForCell(cell);
                if (id == null || !id.startsWith(SchemaGraph.FRAME_ID_PREFIX)) {
                    continue;
                }
                com.mxgraph.model.mxGeometry frame = graph.getModel().getGeometry(cell);
                if (frame == null) {
                    continue;
                }
                java.awt.geom.Rectangle2D.Double before = new java.awt.geom.Rectangle2D.Double(
                        frame.getX() - dx, frame.getY() - dy, frame.getWidth(), frame.getHeight());
                for (Object other : graph.getChildVertices(graph.getDefaultParent())) {
                    if (other == cell || alreadyMoving.contains(other)) {
                        continue;
                    }
                    com.mxgraph.model.mxGeometry box = graph.getModel().getGeometry(other);
                    if (box == null) {
                        continue;
                    }
                    if (before.contains(box.getX() + box.getWidth() / 2,
                            box.getY() + box.getHeight() / 2)) {
                        passengers.add(other);
                        alreadyMoving.add(other);
                    }
                }
            }
            if (passengers.isEmpty()) {
                return;
            }

            movingProgrammatically = true;
            try {
                graph.moveCells(passengers.toArray(), dx, dy);
            } finally {
                movingProgrammatically = false;
            }
            capturePositions();
            positionSaveTimer.restart();
        });
    }

    // ------------------------------------------------------------------ what floats over the board

    /**
     * The board, with the zoom cluster and the overview floating on top of it.
     *
     * <p>The canvas used to be framed on all four sides by fixed chrome - toolbar above, a 180px
     * outline panel pinned right, status bar below - and the outline was a permanent tax. It was
     * there while the start card was showing, where it is a blank grey rectangle beside a "nothing
     * here yet" message; and on a board large enough to need an overview it fails at the one job
     * it has, because a 3694x613 strip scaled into a 140px box paints as a grey smear.
     *
     * <p>Hand-laid rather than given a layout manager, and {@code doLayout} is overridden rather than
     * hung off a {@code ComponentListener}, so the first layout is placed correctly instead of
     * appearing in the top-left corner and then moving.
     */
    private javax.swing.JLayeredPane buildBoardLayers() {
        javax.swing.JLayeredPane layers = new javax.swing.JLayeredPane() {
            private static final long serialVersionUID = 1L;

            @Override
            public void doLayout() {
                graphComponent.setBounds(0, 0, getWidth(), getHeight());
                int margin = 12;
                Dimension zoom = zoomCluster.getPreferredSize();
                Dimension map = minimapPanel.getPreferredSize();
                int right = getWidth() - margin;
                int bottom = getHeight() - margin;
                zoomCluster.setBounds(right - zoom.width, bottom - zoom.height,
                        zoom.width, zoom.height);
                minimapPanel.setBounds(right - map.width, bottom - zoom.height - 8 - map.height,
                        map.width, map.height);
            }

            @Override
            public Dimension getPreferredSize() {
                return graphComponent.getPreferredSize();
            }
        };
        layers.add(graphComponent, javax.swing.JLayeredPane.DEFAULT_LAYER);
        layers.add(minimapPanel, javax.swing.JLayeredPane.PALETTE_LAYER);
        layers.add(zoomCluster, javax.swing.JLayeredPane.PALETTE_LAYER);
        return layers;
    }

    /**
     * A button that floats over the board and never takes the keyboard.
     *
     * <p>This is the single most likely way to break this canvas. Delete, Escape, Ctrl+Z, Ctrl+F,
     * Ctrl+A and the five zoom keys are all bound on {@code graphComponent}'s
     * {@code WHEN_ANCESTOR_OF_FOCUSED_COMPONENT} input map - so the moment focus moves to a sibling
     * of the graph component, every one of them stops firing: silently, with no error, and nothing
     * on screen to say why. A floating button is exactly such a sibling.
     *
     * <p>Hence both flags and the explicit hand-back at the end of every action.
     */
    private JButton floatingButton(javax.swing.Icon icon, String text, String tooltip,
            final Runnable action) {
        JButton button = icon == null ? new JButton(text) : new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setRequestFocusEnabled(false);
        button.setMargin(new java.awt.Insets(2, 6, 2, 6));
        if (action != null) {
            button.addActionListener(a -> {
                action.run();
                graphComponent.requestFocusInWindow();
            });
        }
        return button;
    }

    /** Zoom out, the readout, zoom in, fit, and the key to everything else. */
    private JPanel buildZoomCluster() {
        JPanel cluster = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 2, 3));
        cluster.setOpaque(true);
        cluster.setBackground(java.awt.Color.WHITE);
        cluster.setBorder(javax.swing.BorderFactory.createLineBorder(
                new java.awt.Color(0xD8, 0xDD, 0xE3)));

        // U+2212, the minus sign, which is present in Tahoma, Segoe UI and the logical Dialog
        // family - the three this plugin can end up drawing with.
        cluster.add(floatingButton(null, "−", "Zoom out (Ctrl+-)", () -> zoomAt(false, null)));

        zoomReadout = new JButton(CanvasZoom.readout(1.0));
        zoomReadout.setToolTipText("How far the board is zoomed. Click, or Ctrl+0, for 100%.");
        zoomReadout.setFocusable(false);
        zoomReadout.setRequestFocusEnabled(false);
        zoomReadout.addActionListener(a -> {
            graphComponent.zoomActual();
            updateZoomReadout();
            graphComponent.requestFocusInWindow();
        });
        // Wide enough for "400%", so the cluster does not resize as the number changes.
        zoomReadout.setPreferredSize(new Dimension(64, zoomReadout.getPreferredSize().height));
        cluster.add(zoomReadout);

        cluster.add(floatingButton(null, "+", "Zoom in (Ctrl++)", () -> zoomAt(true, null)));
        cluster.add(floatingButton(null, "Fit", "Zoom so the whole board is visible (Ctrl+1). "
                + "Ctrl+2 frames the selection.", this::fitToWindow));
        cluster.add(floatingButton(null, "?", "Every keyboard shortcut and mouse gesture",
                this::showShortcuts));
        return cluster;
    }

    /** The overview, with a header that collapses it. */
    private JPanel buildMinimap() {
        minimapOutline = new mxGraphOutline(graphComponent);
        // The constructor sets antialiasing off, which at a 4% scale is the difference between
        // shapes and grit.
        minimapOutline.setAntiAlias(true);
        minimapOutline.setPreferredSize(new Dimension(200, 140));

        final JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(true);
        panel.setBackground(java.awt.Color.WHITE);
        panel.setBorder(javax.swing.BorderFactory.createLineBorder(
                new java.awt.Color(0xD8, 0xDD, 0xE3)));
        panel.setPreferredSize(new Dimension(200, 168));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 8, 2, 2));
        javax.swing.JLabel title = new javax.swing.JLabel("Overview");
        title.setForeground(new java.awt.Color(0x5A, 0x64, 0x70));
        title.setFont(title.getFont().deriveFont(java.awt.Font.PLAIN, 11f));
        header.add(title, BorderLayout.WEST);
        header.add(floatingButton(new CanvasIcons.Caret(), null, "Show or hide the overview",
                () -> setMinimapOpen(!minimapOutline.isVisible(), panel)), BorderLayout.EAST);

        panel.add(header, BorderLayout.NORTH);
        panel.add(minimapOutline, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Opens or closes the overview and remembers which.
     *
     * <p>In {@code Preferences} rather than the JSON sidecar, deliberately: whether a panel is open
     * is a property of this person's screen and not of the diagram, and putting it in the sidecar
     * would mean collapsing a panel dirties a file that is committed to somebody's repository.
     */
    private void setMinimapOpen(boolean open, JPanel panel) {
        minimapOutline.setVisible(open);
        panel.setPreferredSize(open ? new Dimension(200, 168) : new Dimension(200, 24));
        panel.revalidate();
        if (panel.getParent() != null) {
            panel.getParent().doLayout();
            panel.getParent().repaint();
        }
        java.util.prefs.Preferences.userNodeForPackage(SchemaCanvasView.class)
                .putBoolean("ontoboard.minimap.open", open);
    }

    // ------------------------------------------------------------------ undo for the board

    /**
     * Remembers the board before an action changes it.
     *
     * <p>Every board-owned mutation calls this first. What counts as board-owned is what the sidecar
     * holds: which terms are shown, where they are, and the sticky notes and frames. Axioms are not,
     * and Ctrl+Z here never touches them - Prot&eacute;g&eacute;'s own undo owns that half, and mixing
     * the two would make one keystroke mean "move that node back" or "retract that axiom" depending on
     * what happened to be last.
     *
     * @param action phrased to complete "Undid: ..." - for instance "adding 7 terms"
     */
    private void rememberBoard(String action) {
        history.record(action, layout);
    }

    /**
     * Ctrl+Z and Ctrl+Shift+Z, on the board only.
     *
     * <p>Bound on the graph component rather than globally, so it cannot fight Prot&eacute;g&eacute;'s
     * undo while the focus is in a class hierarchy or an annotation field. Inside the canvas it is the
     * board's undo, which is the state the canvas is responsible for and the only state that had none.
     */
    private void installUndo() {
        javax.swing.InputMap keys = graphComponent.getInputMap(
                javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        for (int mask : new int[] { java.awt.event.InputEvent.CTRL_DOWN_MASK,
                java.awt.event.InputEvent.META_DOWN_MASK }) {
            keys.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, mask),
                    "ontoboard.undoBoard");
            keys.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z,
                    mask | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "ontoboard.redoBoard");
            // Ctrl+Y as well, which is what a Windows user reaches for first.
            keys.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y, mask),
                    "ontoboard.redoBoard");
        }
        graphComponent.getActionMap().put("ontoboard.undoBoard",
                new javax.swing.AbstractAction() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent event) {
                        undoBoardChange();
                    }
                });
        graphComponent.getActionMap().put("ontoboard.redoBoard",
                new javax.swing.AbstractAction() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent event) {
                        redoBoardChange();
                    }
                });
    }

    /** Steps the board back one action, and says what it did and what it did not. */
    private void undoBoardChange() {
        BoardHistory.Step step = history.undo(layout);
        if (step == null) {
            setStatus("Nothing on the board to undo. Axioms are undone with Protege's own Edit > "
                    + "Undo.");
            return;
        }
        restoreBoard(step.getBoard());
        // The second sentence is the one that matters. A user who has removed a term from the board
        // and deleted a class will otherwise read one keystroke as having reversed both.
        setStatus("Undid: " + step.getAction() + ". The ontology is unchanged - use Protege's "
                + "Edit > Undo for axioms.");
    }

    /** Puts back what the last undo took away. */
    private void redoBoardChange() {
        BoardHistory.Step step = history.redo(layout);
        if (step == null) {
            setStatus("Nothing to redo on the board.");
            return;
        }
        restoreBoard(step.getBoard());
        setStatus("Redid: " + step.getAction() + ". The ontology is unchanged.");
    }

    /**
     * Puts a remembered board back on screen and in the sidecar.
     *
     * <p>{@code copyFrom} rather than assigning the field, because {@code CanvasMembership} and the
     * position capture hold this exact instance. Replacing it would leave membership editing a board
     * nobody draws, which looks like an undo that works and is then undone by the next action.
     */
    private void restoreBoard(CanvasLayout remembered) {
        layout.copyFrom(remembered);
        // An expansion's record refers to terms that may have just left the board, and a collapse
        // offered after an undo would remove terms the user has not expanded.
        expansions.clear();
        refresh();
        saveLayoutTo(layoutFile);
    }

    // ------------------------------------------------------------------ draw an edge, get an axiom

    /**
     * Lets an edge be drawn on the board, and turns the gesture into the question it asked.
     *
     * <p>`docs/feature-parity.md` claimed the plugin could author edges from the canvas while
     * {@code setConnectable(false)} sat in this file, so there was no connection handle to find and the
     * capability was two levels into a context menu. People looked for the handle - it is the gesture
     * every diagram tool has - and concluded the feature was missing.
     *
     * <p>One line here is load-bearing and its default is wrong for this canvas.
     * {@code mxConstants.CONNECT_HANDLE_ENABLED} is {@code false} in JGraphX 4.2.2 - checked, not
     * assumed - and with the handle disabled {@code mxConnectionHandler.isHighlighting()} returns true,
     * which makes a press anywhere in a node's hotspot start a connection instead of moving the node.
     * That would have traded the canvas's most-used gesture for its newest one. With the handle enabled
     * a small square appears on hover, dragging from it draws an edge, and dragging the node itself
     * still moves it.
     *
     * <p>{@code setCreateTarget(false)} because a drag ending on empty canvas must not invent a term.
     * Which class to create, called what, minted from which range, is the {@code New class here...}
     * conversation, not something to infer from where a mouse was let go.
     */
    private void installDragToConnect() {
        graphComponent.setConnectable(true);
        com.mxgraph.swing.handler.mxConnectionHandler handler =
                graphComponent.getConnectionHandler();
        handler.setHandleEnabled(true);
        handler.setCreateTarget(false);
        handler.addListener(mxEvent.CONNECT, (sender, event) -> {
            Object drawn = event.getProperty("cell");
            Object mouse = event.getProperty("event");
            Point where = mouse instanceof MouseEvent
                    ? new Point(((MouseEvent) mouse).getX(), ((MouseEvent) mouse).getY()) : null;
            // Off the handler's own event dispatch: it is still inside the model update that
            // inserted this edge, and the first thing we do is take that edge back out.
            javax.swing.SwingUtilities.invokeLater(() -> edgeWasDrawn(drawn, where));
        });
    }

    /**
     * Asks what a freshly drawn edge means, and removes it either way.
     *
     * <p>The edge mxGraph just inserted is deleted before anything else. Every line on this board is a
     * projection of an axiom - that is the property the whole canvas rests on - and an edge that is
     * only a drawing would be the one line on screen that means nothing, indistinguishable from the
     * ones that do. If an axiom is written, the refresh that follows draws the edge again from the
     * ontology; if the user cancels, there is nothing left behind.
     */
    private void edgeWasDrawn(Object drawn, Point where) {
        if (drawn == null) {
            return;
        }
        String sourceIri = graph.getIdForCell(graph.getModel().getTerminal(drawn, true));
        String targetIri = graph.getIdForCell(graph.getModel().getTerminal(drawn, false));
        graph.getModel().remove(drawn);

        // The rules live in DrawnEdge, where they can be tested. They were four ifs in this method
        // and every one of them exists to stop the same thing - a line on the board with no axiom
        // behind it - which makes them worth stating in one place.
        DrawnEdge.Verdict verdict =
                DrawnEdge.verdictFor(sourceIri, targetIri, membership.asSet());
        if (verdict != DrawnEdge.Verdict.OFFER) {
            if (verdict.getMessage() != null) {
                setStatus(verdict.getMessage());
            }
            return;
        }
        offerAxiomsFor(sourceIri, targetIri, where);
    }

    /**
     * The picker: what the two ends could mean, at the point the edge was dropped.
     *
     * <p>A popup at the cursor rather than a modal, because the answer is one click and a dialog in the
     * middle of the screen for a one-click answer covers the two terms being talked about. The options
     * are built from what the ends are, so the menu cannot offer something that would only produce an
     * error - {@link HierarchyAxioms#applicableTo} decides which single hierarchy link is legal, and a
     * restriction is offered only between two classes.
     *
     * <p>Nothing has been written to the ontology at this point and nothing is drawn, so dismissing the
     * menu is a complete undo. The status line says so, because a vanished edge and a rejected edge
     * look identical.
     */
    private void offerAxiomsFor(String sourceIri, String targetIri, Point where) {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        OWLEntity source = entityOnCanvas(ontology, sourceIri);
        OWLEntity target = entityOnCanvas(ontology, targetIri);
        if (source == null || target == null) {
            setStatus("One of those terms is not in this ontology, so nothing can be asserted "
                    + "between them.");
            return;
        }

        JPopupMenu menu = new JPopupMenu();
        String sourceName = nameOnTheBoard(sourceIri);
        String targetName = nameOnTheBoard(targetIri);
        javax.swing.JLabel heading = new javax.swing.JLabel(
                "  " + sourceName + "  \u2192  " + targetName + "  ");
        heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD,
                heading.getFont().getSize() - 1f));
        menu.add(heading);
        menu.addSeparator();

        List<DrawnEdge.Option> options = DrawnEdge.optionsFor(ontology, source, target);
        if (options.isEmpty()) {
            // Nothing legal between these two ends. Saying why is the useful answer, and
            // HierarchyAxioms already words it - for instance an individual dragged to a property.
            setStatus(HierarchyAxioms.whyNot(source, target));
            return;
        }
        for (DrawnEdge.Option option : options) {
            if (option == DrawnEdge.Option.HIERARCHY) {
                HierarchyAxioms.Kind kind = HierarchyAxioms.applicableTo(source, target).get(0);
                JMenuItem hierarchy = new JMenuItem(kind.getDisplayName());
                hierarchy.setToolTipText(kind.getExplanation());
                hierarchy.addActionListener(a -> linkHierarchy(source, target));
                menu.add(hierarchy);
            } else {
                JMenuItem relation = new JMenuItem("Related by an object property...");
                relation.setToolTipText("Choose the property and how strong the reading is - some, "
                        + "only, exactly one, and the rest");
                relation.addActionListener(a -> relateWithObjectProperty(sourceIri, targetIri));
                menu.add(relation);
            }
        }

        menu.addSeparator();
        JMenuItem cancel = new JMenuItem("Cancel - write nothing");
        cancel.addActionListener(a -> setStatus("No axiom written, and the edge was not kept."));
        menu.add(cancel);

        setStatus("Drew " + sourceName + " \u2192 " + targetName
                + " - choose what it asserts, or dismiss to write nothing.");
        Point at = where != null ? where : new Point(40, 40);
        menu.show(graphComponent.getGraphControl(), at.x, at.y);
    }

    // ------------------------------------------------------------------ expand and collapse

    /**
     * Puts a term's neighbours on the board, around it, and says how many arrived.
     *
     * <p>All four of the things this now does were missing, and they were the same omission:
     * {@code expandOneHop} returned a count that the caller threw away. So the new terms had no
     * geometry and {@code SchemaGraph.render} laid them in a row along the top of the board - nowhere
     * near the term they neighbour, overlapping whatever was already up there; nothing was saved, so
     * the next refresh moved them again; nothing was said, so an expansion that found nothing looked
     * exactly like one that worked; and nothing remembered what had been added, so there was no way
     * back.
     *
     * <p>The ring is {@link CanvasLayouts#ringOffsets}. Positions are written into the layout
     * <em>before</em> the refresh, because render reads the layout - placing them afterwards would
     * draw them in the wrong place first and move them a frame later.
     */
    private void expandNeighbours(String iri) {
        rememberBoard("expanding " + nameOnTheBoard(iri));
        List<String> added = membership.expandOneHop(getOWLModelManager().getActiveOntology(), iri);
        if (added.isEmpty()) {
            setStatus(nameOnTheBoard(iri) + " has no neighbours that are not already on the board.");
            return;
        }

        placeAround(iri, added);
        expansions.put(iri, new ArrayList<String>(added));
        refresh();
        capturePositions();
        saveLayoutTo(layoutFile);
        setStatus("Expanded " + nameOnTheBoard(iri) + " - " + added.size()
                + (added.size() == 1 ? " neighbour added" : " neighbours added")
                + ". Right-click it to collapse again.");
    }

    /**
     * Takes back exactly what one expansion added.
     *
     * <p>Deliberately not a general undo - that is A5 in the canvas plan and is not done. This removes
     * the terms that this expansion put on the board, which is the promise the menu item makes, and
     * nothing else: a term the user has since moved, annotated or drawn an axiom on is still one this
     * expansion added, and leaving it behind would make "collapse" mean something different every time.
     *
     * <p>Only the board is touched. Anything written to the ontology in between - an axiom drawn
     * between two of these neighbours - stays in the ontology, which is the same rule
     * {@code Remove from canvas} follows.
     */
    private void collapseExpansion(String iri) {
        rememberBoard("collapsing " + nameOnTheBoard(iri));
        List<String> added = expansions.remove(iri);
        if (added == null || added.isEmpty()) {
            setStatus("Nothing to collapse on " + nameOnTheBoard(iri) + ".");
            return;
        }
        int removed = 0;
        for (String neighbour : added) {
            if (membership.remove(neighbour)) {
                removed++;
                // An expansion rooted at a term that is leaving cannot be collapsed later.
                expansions.remove(neighbour);
            }
        }
        refresh();
        capturePositions();
        saveLayoutTo(layoutFile);
        setStatus("Collapsed " + nameOnTheBoard(iri) + " - " + removed
                + (removed == 1 ? " term removed from the board" : " terms removed from the board")
                + ". The ontology is unchanged.");
    }

    /** Of a remembered expansion, the terms that are still drawn. Never null. */
    private List<String> stillOnTheBoard(List<String> remembered) {
        List<String> present = new ArrayList<String>();
        if (remembered != null) {
            for (String iri : remembered) {
                if (membership.contains(iri)) {
                    present.add(iri);
                }
            }
        }
        return present;
    }

    /** Writes ring positions for newly added neighbours into the layout. */
    private void placeAround(String iri, List<String> neighbours) {
        double[] source = boundsOf(iri);
        double centreX = source[0] + source[2] / 2;
        double centreY = source[1] + source[3] / 2;

        List<double[]> offsets = CanvasLayouts.ringOffsets(neighbours.size());
        for (int i = 0; i < neighbours.size() && i < offsets.size(); i++) {
            CanvasLayout.NodeLayout position = new CanvasLayout.NodeLayout();
            position.w = 160;
            position.h = 60;
            // The offset is to the neighbour's centre, so half a node back to its corner.
            position.x = centreX + offsets.get(i)[0] - position.w / 2;
            position.y = centreY + offsets.get(i)[1] - position.h / 2;
            layout.nodes.put(neighbours.get(i), position);
        }
    }

    /** Where a term is, as {@code {x, y, w, h}}, from the live cell or the layout, or the origin. */
    private double[] boundsOf(String iri) {
        Object cell = graph.getCellForId(iri);
        if (cell instanceof com.mxgraph.model.mxCell) {
            com.mxgraph.model.mxGeometry geometry = ((com.mxgraph.model.mxCell) cell).getGeometry();
            if (geometry != null) {
                return new double[] { geometry.getX(), geometry.getY(),
                        geometry.getWidth(), geometry.getHeight() };
            }
        }
        CanvasLayout.NodeLayout stored = layout.nodes.get(iri);
        if (stored != null) {
            return new double[] { stored.x, stored.y,
                    stored.w > 0 ? stored.w : 160, stored.h > 0 ? stored.h : 60 };
        }
        return new double[] { 40, 40, 160, 60 };
    }

    /**
     * What a term is called, for a sentence in the status bar.
     *
     * <p>From the drawn projection, so it is the label the user is looking at. A message that named a
     * term by an IRI the board never shows would be describing something else as far as the reader is
     * concerned.
     */
    private String nameOnTheBoard(String iri) {
        for (CanvasNode node : termsOnTheBoard()) {
            if (node.getId().equals(iri)) {
                return CanvasSearch.nameOf(node);
            }
        }
        return CanvasSearch.localNameOf(iri);
    }

    // ------------------------------------------------------------------ find and zoom

    /**
     * Runs the current query and reports what it found.
     *
     * <p>Centring while typing is the behaviour worth having - you see the term arrive rather than
     * pressing Enter and hoping - but not on the first character, where the best match for "m" is
     * arbitrary and the canvas lurches to it. From two characters on, the view follows the query.
     *
     * @param centreTheBest whether to move the view, which stepping and re-running after an add do
     *     themselves
     */
    private void runSearch(boolean centreTheBest) {
        String query = searchField == null ? "" : searchField.getText();
        searchMatches = CanvasSearch.matches(query, termsOnTheBoard());
        searchCursor = 0;

        if (query.trim().isEmpty()) {
            say(searchCount, " ", null);
            return;
        }
        if (!searchMatches.isEmpty()) {
            say(searchCount, searchMatches.size() == 1 ? "1 match"
                    : searchMatches.size() + " matches", null);
            if (centreTheBest && query.trim().length() >= 2) {
                goToCurrentMatch();
            }
            return;
        }
        reportNothingFoundOnTheBoard(query);
    }

    /**
     * What to say when the board has no match, which is two different situations.
     *
     * <p>"No match" alone is the unhelpful answer, because the two reasons it can be true lead to
     * opposite next actions: the term does not exist and needs creating, or it exists and simply has
     * not been added to the board. Distinguishing them is the whole reason this looks past the board
     * at all.
     */
    private void reportNothingFoundOnTheBoard(String query) {
        List<CanvasNode> inTheOntology = CanvasSearch.matches(query, termsInTheOntology(), 1);
        if (inTheOntology.isEmpty()) {
            say(searchCount, "no match", "Nothing in this ontology matches that either.");
            setStatus("No term matching \"" + query.trim() + "\" - not on the board, and not in "
                    + "this ontology.");
            return;
        }
        String name = CanvasSearch.nameOf(inTheOntology.get(0));
        say(searchCount, "not on board",
                name + " is in the ontology. Ctrl+Enter puts it on the board.");
        setStatus(name + " is in this ontology but not on the board - Ctrl+Enter adds it.");
    }

    /** Enter and Shift+Enter: the next match, wrapping. */
    private void stepThroughMatches(int delta) {
        if (searchMatches.isEmpty()) {
            // Enter on a query that matched nothing on the board does the obvious thing instead of
            // nothing at all: if the term exists, put it there.
            addBestMatchFromTheOntology();
            return;
        }
        searchCursor = (searchCursor + delta + searchMatches.size()) % searchMatches.size();
        goToCurrentMatch();
    }

    /**
     * Centres the current match, selects it, and names it.
     *
     * <p>Selecting rather than only scrolling is what makes this more than a camera move: the
     * selection goes out through {@link SelectionBridge} to Prot&eacute;g&eacute;'s own selection, so
     * finding a term on the board also brings up its annotations and its axioms in the panels beside
     * it - and out to collaborators, who see what their colleague is looking at.
     */
    private void goToCurrentMatch() {
        if (searchMatches.isEmpty()) {
            return;
        }
        CanvasNode match = searchMatches.get(searchCursor);
        Object cell = graph.getCellForId(match.getId());
        if (cell == null) {
            // The board was re-rendered between the search and the jump - an edit elsewhere in
            // Protege, or a collaborator removing the term.
            setStatus(CanvasSearch.nameOf(match) + " is no longer on the board.");
            runSearch(false);
            return;
        }
        graph.setSelectionCell(cell);
        graphComponent.scrollCellToVisible(cell, true);
        String position = searchMatches.size() == 1
                ? "" : "(" + (searchCursor + 1) + " of " + searchMatches.size() + ") ";
        setStatus(position + CanvasSearch.nameOf(match) + " - " + match.getId());
    }

    /**
     * Ctrl+Enter: puts the best matching term in the ontology onto the board.
     *
     * <p>Placed at the centre of what the user is currently looking at, not at the origin. A node
     * that arrives off-screen after an explicit request to add it is indistinguishable from nothing
     * happening, which is the bug the drop path was fixed for and would be a new one here.
     */
    private void addBestMatchFromTheOntology() {
        String query = searchField == null ? "" : searchField.getText();
        if (query.trim().isEmpty()) {
            return;
        }
        List<CanvasNode> found = CanvasSearch.matches(query, termsInTheOntology(), 1);
        if (found.isEmpty()) {
            setStatus("No term in this ontology matches \"" + query.trim() + "\".");
            return;
        }
        CanvasNode term = found.get(0);
        if (!membership.add(term.getId())) {
            // Already there - so this is a search that should have matched, and the useful response
            // is to go to it rather than to report a no-op.
            runSearch(true);
            return;
        }

        rememberBoard("adding " + CanvasSearch.nameOf(term));
        Point where = centreOfTheVisibleCanvas();
        CanvasLayout.NodeLayout position = new CanvasLayout.NodeLayout();
        position.x = where.x;
        position.y = where.y;
        position.w = 160;
        position.h = 60;
        layout.nodes.put(term.getId(), position);

        refresh();
        saveLayoutTo(layoutFile);
        setStatus("Added " + CanvasSearch.nameOf(term) + " to the board.");
        // Re-run so the count, the selection and the view all describe the board as it now is.
        runSearch(true);
    }

    /** The middle of the visible canvas, in graph coordinates. */
    private Point centreOfTheVisibleCanvas() {
        java.awt.Rectangle visible = graphComponent.getViewport().getViewRect();
        if (visible.width <= 0 || visible.height <= 0) {
            return new Point(40, 40);
        }
        // Half a default node up and left, so the node is centred rather than starting at the centre.
        Point centre = graphPointFromControl(visible.x + visible.width / 2,
                visible.y + visible.height / 2);
        return new Point(Math.max(0, centre.x - 80), Math.max(0, centre.y - 30));
    }

    /** Escape in the Find box: clear it and give the keyboard back to the canvas. */
    private void clearSearch() {
        if (searchField != null) {
            searchField.setText("");
        }
        searchMatches = Collections.emptyList();
        searchCursor = 0;
        say(searchCount, " ", null);
        graphComponent.requestFocusInWindow();
    }

    /** The terms drawn on the board, which is what Find searches first. */
    private List<CanvasNode> termsOnTheBoard() {
        return rendered == null ? Collections.<CanvasNode>emptyList() : rendered.getNodes();
    }

    /**
     * Every term in the ontology, built on demand and kept until the next refresh.
     *
     * <p>Only reached when the board has no match, so the common case - searching for something that
     * is on screen - never walks the ontology. {@link #refresh} drops it, and refresh runs on every
     * ontology change, so this cannot answer with a term that has since been deleted.
     */
    private List<CanvasNode> termsInTheOntology() {
        if (ontologyTerms == null) {
            // Imports included, so "not on the board" is answered about the ontology the user is
            // working in rather than about the edit file alone - and so Ctrl+Enter can draw an
            // imported term, which it now can.
            ontologyTerms = OntologyProjection.everyTermWorthShowing(
                    getOWLModelManager().getActiveOntology(),
                    org.semanticweb.owlapi.model.parameters.Imports.INCLUDED);
        }
        return ontologyTerms;
    }

    /** Text and tooltip together, because a label whose text is cut off needs the tooltip. */
    private static void say(javax.swing.JLabel label, String text, String tooltip) {
        if (label != null) {
            label.setText(text);
            label.setToolTipText(tooltip);
        }
    }

    /**
     * Zooms so the whole board is visible.
     *
     * <p>The one thing to get right here is a unit: {@code mxGraphView.getGraphBounds()} reports the
     * board in <em>scaled</em> pixels, so dividing by the current scale is what makes Fit mean the
     * same thing from 40% as from 100%. Without it the arithmetic is right at 100% and wrong
     * everywhere else, which is a bug that survives every manual test that starts from 100%.
     */
    private void fitToWindow() {
        fitTo(graph.getView().getGraphBounds(), false, "the board");
    }

    /**
     * Frames whatever is selected, magnifying if it is small.
     *
     * <p>The one case where zooming past 1:1 is right: framing a single node in a large window is
     * the whole point of asking for it, where fitting the board past 1:1 would be a zoom level
     * nobody requested.
     */
    private void fitToSelection() {
        Object[] selected = graph.getSelectionCells();
        if (selected == null || selected.length == 0) {
            setStatus("Select something first - Ctrl+2 frames whatever is selected.");
            return;
        }
        fitTo(graph.getView().getBoundingBox(selected), true,
                selected.length == 1 ? "the selection" : selected.length + " selected");
    }

    /**
     * Zooms and scrolls so {@code bounds} fills the window.
     *
     * <p>The one thing to get right here is a unit: {@code mxGraphView} reports bounds in
     * <em>scaled</em> pixels, so dividing by the current scale is what makes this mean the same
     * thing from 40% as from 100%. Without it the arithmetic is right at 100% and wrong everywhere
     * else, which is a bug that survives every manual test that starts from 100%.
     */
    private void fitTo(com.mxgraph.util.mxRectangle bounds, boolean allowMagnify, String what) {
        java.awt.Rectangle window = graphComponent.getViewport().getViewRect();
        double scale = graph.getView().getScale();
        double zoom = scale <= 0 ? 1 : scale;
        if (bounds == null || bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            setStatus("Nothing on the board to fit.");
            return;
        }

        double fitted = allowMagnify
                ? CanvasZoom.scaleToFill(bounds.getWidth() / zoom, bounds.getHeight() / zoom,
                        window.getWidth(), window.getHeight())
                : CanvasZoom.scaleToFit(bounds.getWidth() / zoom, bounds.getHeight() / zoom,
                        window.getWidth(), window.getHeight());
        graphComponent.zoomTo(fitted, false);

        // Then bring the content itself into view: fitting the scale without scrolling leaves a board
        // that starts at x=2000 exactly as invisible as it was, only smaller.
        double ratio = fitted / zoom;
        graphComponent.getGraphControl().scrollRectToVisible(new java.awt.Rectangle(
                (int) (bounds.getX() * ratio), (int) (bounds.getY() * ratio),
                (int) (bounds.getWidth() * ratio), (int) (bounds.getHeight() * ratio)));
        updateZoomReadout();
        setStatus("Fitted " + what + " to the window at " + CanvasZoom.readout(fitted) + ".");
    }

    /**
     * Keeps the readout honest however the zoom changed.
     *
     * <p>Bound to the view rather than to the wheel handler and the two buttons, so a zoom from
     * anywhere - the outline panel, a future keyboard shortcut, mxGraph itself - updates it. A readout
     * that is right only when you zoom the way its author expected is worse than none.
     */
    private void installZoomReadout() {
        mxIEventListener onScale = (sender, event) -> updateZoomReadout();
        graph.getView().addListener(mxEvent.SCALE, onScale);
        graph.getView().addListener(mxEvent.SCALE_AND_TRANSLATE, onScale);
        updateZoomReadout();
    }

    /** Puts the current scale in the status bar. */
    private void updateZoomReadout() {
        if (zoomReadout != null) {
            zoomReadout.setText(CanvasZoom.readout(graph.getView().getScale()));
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
                // A plain drag on empty board selects a region, since 1.68.0. The explicit
                // null-cell test is required rather than belt-and-braces: mxRubberband.mousePressed
                // in 4.2.2 checks isConsumed, isEnabled, isRubberbandTrigger and isPopupTrigger and
                // never asks what is under the cursor - isRubberbandTrigger is literally "return
                // true". It works today only because mxGraphHandler is registered first by the
                // mxGraphComponent constructor and consumes the event on a cell hit, which is a
                // registration order to depend on deliberately or not at all.
                if (graphComponent.getCellAt(event.getX(), event.getY()) == null
                        && !((CollaborativeGraphComponent) graphComponent).isSpaceHeld()) {
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
                zoomAt(event.getWheelRotation() < 0, event);
                event.consume();
            }
        });
    }

    /**
     * Zooms one step, keeping the point under the cursor under the cursor.
     *
     * <p>Two faults this replaces. {@code mxGraphComponent.centerZoom} defaults to true and was never
     * set, so the board zoomed towards the middle of the viewport: on a 3694px board the node you
     * were pointing at slid off the screen at every click, which is the opposite of what pointing at
     * it means. And {@code mxGraphComponent.zoom} is guarded only by {@code newScale > 0.04} with no
     * upper bound, so the wheel ran to 800% while {@link CanvasZoom#MAX_SCALE} documented a ceiling
     * of 400% that nothing enforced.
     *
     * <p>The arithmetic is exact rather than approximate: {@code zoomTo} calls
     * {@code scaleAndTranslate(newScale, 0, 0)} whenever page view is off - it is, and nothing here
     * turns it on - so the view translate is permanently (0,0) and a graph point's pixel is exactly
     * its coordinate times the scale.
     *
     * @param event where the cursor is, or null to zoom about the centre of the view
     */
    private void zoomAt(boolean in, java.awt.event.MouseEvent event) {
        double scale = graph.getView().getScale();
        double target = CanvasZoom.clamp(in
                ? scale * graphComponent.getZoomFactor()
                : scale / graphComponent.getZoomFactor());
        if (target == scale) {
            return;
        }
        final javax.swing.JViewport port = graphComponent.getViewport();
        final java.awt.Point inPort = event == null
                ? new java.awt.Point(port.getWidth() / 2, port.getHeight() / 2)
                : javax.swing.SwingUtilities.convertPoint(
                        (java.awt.Component) event.getSource(), event.getPoint(), port);
        final java.awt.Point origin = port.getViewPosition();
        final double ratio = target / scale;

        graphComponent.zoomTo(target, false);
        // After the component has finished: zoomTo defers its own scrollbar maintenance to an
        // invokeLater, so a scroll position set synchronously here would simply be overwritten.
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                java.awt.Dimension size = graphComponent.getGraphControl().getPreferredSize();
                int x = (int) Math.round((origin.x + inPort.x) * ratio) - inPort.x;
                int y = (int) Math.round((origin.y + inPort.y) * ratio) - inPort.y;
                x = Math.max(0, Math.min(x, Math.max(0, size.width - port.getWidth())));
                y = Math.max(0, Math.min(y, Math.max(0, size.height - port.getHeight())));
                port.setViewPosition(new java.awt.Point(x, y));
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
                        reportRemoval(removeSelectionFromCanvas());
                    }
                });

        // Escape clears the selection. Documented in the testing guide and simply absent, so the
        // only way out of a rubber-band selection was to click empty canvas and hope not to hit a
        // node - with Delete one keystroke away from removing whatever was still selected.
        // Ctrl+F from the canvas, because the point of a find box is not having to reach for the
        // mouse. Both masks rather than Toolkit.getMenuShortcutKeyMask(), which is deprecated on the
        // newer of the two Java versions this plugin is built for.
        for (int mask : new int[] { java.awt.event.InputEvent.CTRL_DOWN_MASK,
                java.awt.event.InputEvent.META_DOWN_MASK }) {
            keys.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F, mask),
                    "ontoboard.find");
        }
        graphComponent.getActionMap().put("ontoboard.find",
                new javax.swing.AbstractAction() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent event) {
                        if (searchField != null) {
                            searchField.requestFocusInWindow();
                            searchField.selectAll();
                        }
                    }
                });

        // Space held pans, which is the gesture every canvas application uses and the one this
        // canvas took away from a plain drag. Bound on press and on release, with the cursor saying
        // which mode the board is in - without that the only feedback is that dragging does
        // something different, which is how a deliberate inversion reads as a fault.
        keys.put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_SPACE, 0, false), "ontoboard.panOn");
        keys.put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_SPACE, 0, true), "ontoboard.panOff");
        graphComponent.getActionMap().put("ontoboard.panOn", holdSpace(true));
        graphComponent.getActionMap().put("ontoboard.panOff", holdSpace(false));

        keys.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                "ontoboard.clearSelection");
        graphComponent.getActionMap().put("ontoboard.clearSelection",
                new javax.swing.AbstractAction() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent event) {
                        graph.clearSelection();
                    }
                });
    }

    /**
     * Whether the first-run hint may take the board's status line.
     *
     * <p>A pure function because the interesting case is not the hint: {@code boardStatus} starts as a
     * single space, which is not the same as empty, and {@code loadLayoutForActiveOntology} runs first
     * and may already have written "the saved arrangement could not be read" there. That sentence
     * carries a file the user has to go and look at, and it must outrank a hint about the mouse.
     *
     * <p>Yielding is safe because the caller only marks the hint seen when it shows: a hint worth
     * showing once is worth showing next session instead.
     */
    static boolean firstRunHintFits(boolean alreadySeen, String boardMessage) {
        return !alreadySeen && (boardMessage == null || boardMessage.trim().isEmpty());
    }

    /** The space-bar action, in both directions, with the cursor to match. */
    private javax.swing.Action holdSpace(final boolean held) {
        return new javax.swing.AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                ((CollaborativeGraphComponent) graphComponent).setSpaceHeld(held);
                graphComponent.getGraphControl().setCursor(java.awt.Cursor.getPredefinedCursor(
                        held ? java.awt.Cursor.MOVE_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
            }
        };
    }

    /** The shortcut list, which is what makes the inverted pan gesture defensible. */
    private void showShortcuts() {
        JOptionPane.showMessageDialog(this, new de.fizkarlsruhe.ise.ontoboard.canvas.ShortcutsPanel(),
                "Canvas shortcuts", JOptionPane.PLAIN_MESSAGE);
    }

    /**
     * Says what Delete did, in one sentence, and whether the ontology was touched.
     *
     * <p>That last clause carries more weight here than in most software. "Removed from the board"
     * and "deleted from the ontology" look identical on a canvas - the node disappears either way -
     * and the current interface distinguished them nowhere. A curator who believes they have retired
     * a term and has only hidden it will find out at the next release; one who believes the reverse
     * will spend an afternoon looking for a class that is still there.
     *
     * <p>Delete discarded the count this reports. Pressing it on a sticky note, a frame or an edge
     * did nothing at all and said nothing either, so the reasonable conclusion was that the keyboard
     * shortcut was broken.
     */
    private void reportRemoval(Removal removal) {
        if (removal.nothingSelected()) {
            setStatus("Nothing is selected. Click a node, or drag a box around several.");
            return;
        }
        StringBuilder said = new StringBuilder();
        if (removal.terms > 0) {
            said.append(removal.terms).append(removal.terms == 1 ? " term" : " terms")
                    .append(" taken off the board. The ontology is unchanged - they are still "
                            + "there, and Add all or the entity trees will bring them back.");
        }
        if (removal.annotations > 0) {
            if (said.length() > 0) {
                said.append(' ');
            }
            said.append(removal.annotations)
                    .append(removal.annotations == 1 ? " note or frame deleted."
                            : " notes and frames deleted.");
        }
        if (removal.skipped > 0) {
            if (said.length() > 0) {
                said.append(' ');
            }
            // Edges are the common case, and the distinction is the point: an edge is an axiom, so
            // removing one is a change to the ontology and belongs behind the confirmation the
            // context menu already has.
            said.append(removal.skipped)
                    .append(removal.skipped == 1 ? " selected item was left alone"
                            : " selected items were left alone")
                    .append(" - an arrow is an axiom, so use the right-click menu to remove one.");
        }
        setStatus(said.toString());
    }

    /** What one Delete did, so it can be reported rather than counted and dropped. */
    static final class Removal {
        private final int terms;
        private final int annotations;
        private final int skipped;

        Removal(int terms, int annotations, int skipped) {
            this.terms = terms;
            this.annotations = annotations;
            this.skipped = skipped;
        }

        boolean nothingSelected() {
            return terms == 0 && annotations == 0 && skipped == 0;
        }

        int getTerms() {
            return terms;
        }

        int getAnnotations() {
            return annotations;
        }

        int getSkipped() {
            return skipped;
        }
    }

    /**
     * Takes the selection off the board, leaving the ontology untouched.
     *
     * <p>Three outcomes, counted separately because they mean different things to the user. A term
     * comes off the board and stays in the ontology. A sticky note or a frame is deleted outright -
     * it exists nowhere else, so there is nothing to come off. Anything else, an edge in practice, is
     * left alone: an edge is an axiom, and removing one is a change to the ontology that belongs
     * behind the confirmation the context menu already has.
     *
     * <p>Notes and frames used to fall into a silent gap. {@code membership.remove} returns false for
     * an id that is not a term, so pressing Delete on a note did nothing and said nothing, and the
     * shortcut looked broken.
     */
    private Removal removeSelectionFromCanvas() {
        Object[] selected = graph.getSelectionCells();
        if (selected == null || selected.length == 0) {
            return new Removal(0, 0, 0);
        }
        // Recorded after the empty check, so pressing Delete on nothing does not push a step that
        // undoes nothing and hides the one before it.
        rememberBoard(selected.length == 1 ? "removing a term from the board"
                : "removing " + selected.length + " things from the board");
        int terms = 0;
        int annotations = 0;
        int skipped = 0;
        for (Object cell : selected) {
            String id = graph.getIdForCell(cell);
            if (id == null) {
                skipped++;
            } else if (SchemaGraph.isAnnotationId(id)) {
                // deleteAnnotation refreshes and saves for itself, which is why this loop does not
                // count it towards the redraw below.
                deleteAnnotation(id);
                annotations++;
            } else if (membership.remove(id)) {
                terms++;
            } else {
                skipped++;
            }
        }
        if (terms > 0) {
            refresh();
            saveLayoutTo(layoutFile);
        }
        return new Removal(terms, annotations, skipped);
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
        List<String> candidates = new ArrayList<String>(
                OntologyProjection.everythingWorthShowing(ontology));
        if (candidates.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "This ontology declares no classes, individuals or properties yet.",
                    "Nothing to add", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (candidates.size() > 300) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "This ontology has " + candidates.size() + " terms.\n\n"
                            + "A diagram that large is slow to arrange and hard to read. Add them "
                            + "all anyway?",
                    "That is a lot of nodes", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
        }
        rememberBoard("adding every term");
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
            saveLayoutTo(layoutFile);
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
            setSessionStatus("Working through git", LIGHT_OFFLINE);
            return;
        }
        OWLOntology open = getOWLModelManager().getActiveOntology();
        String ontologyIri = open != null && open.getOntologyID().getOntologyIRI().isPresent()
                ? open.getOntologyID().getOntologyIRI().get().toString() : null;
        CollabSettings settings = CollabDialog.show(this, ontologyIri);
        if (settings == null) {
            return;
        }
        if (!settings.isLive()) {
            // A deliberate choice, not a failure: the dialog says so too.
            setSessionStatus("Working through git", LIGHT_OFFLINE);
            JOptionPane.showMessageDialog(this, settings.explainWhyNotLive(),
                    "Working through git", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // Before connecting, not after. A board that is not this ontology's is legitimate when
        // it is meant - one board across a pair of related files - but when it is not, the
        // consequence is somebody else's classes arriving in this file, and nothing later in the
        // session would say so.
        String mismatch = BoardId.mismatchWarning(settings.getBoard(), ontologyIri);
        if (mismatch != null && JOptionPane.showConfirmDialog(this,
                mismatch + "\n\nConnect anyway?",
                "This board is for another ontology",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.YES_OPTION) {
            return;
        }
        collab = new CollabSession(settings.forOntology(ontologyIri), new CanvasCollabHost(),
                javax.swing.SwingUtilities::invokeLater);
        graphComponent.setPeerCursors(collab.getCursors());
        collaborateButton.setText("Disconnect");
        setSessionStatus("Connecting...", LIGHT_ATTENTION);
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
        // The event is on the graph control, which is the scrolled content, so only the zoom and
        // the view translate have to be undone - the mirror of PeerCursorLayer. Through the shared
        // converter rather than inline: this path had the arithmetic right and the drop path did not,
        // and one function is what stops them differing again. It also picks up the translate term,
        // which the inline version omitted - zero on a default view, not in general.
        Point inGraph = graphPointFromControl(event.getX(), event.getY());
        lastCursorPoint = inGraph;
        collab.publishCursor(inGraph.x, inGraph.y,
                selectionBridge == null ? null : selectionBridge.currentCanvasSelection());
    }

    /**
     * Says where this editor is looking, without waiting for the mouse to move.
     *
     * <p>Two absences this closes, both of which made a live session look emptier than it was.
     * Selecting a term published nothing until the mouse happened to move afterwards - so clicking a
     * node and reading it, or finding it with Ctrl+F, told nobody; and a peer who joined and then read
     * the diagram without moving the mouse was invisible, indistinguishable from nobody having joined.
     *
     * <p>The position is the last one the peers saw, or the selected node's centre, or the middle of
     * the visible canvas - in that order. The origin is the one answer never given: for anybody who has
     * scrolled, it is somewhere the canvas is not.
     */
    private void announcePresence() {
        if (collab == null) {
            return;
        }
        String selection = selectionBridge == null ? null : selectionBridge.currentCanvasSelection();
        Point where = presencePoint(selection);
        lastCursorPoint = where;
        collab.publishCursor(where.x, where.y, selection);
    }

    /** Where to say this editor is, when the mouse has not said. */
    private Point presencePoint(String selection) {
        if (selection != null) {
            // The selected node's centre beats a stale cursor: it is what the user is actually
            // looking at, and it is where a colleague following them wants to be taken.
            double[] box = boundsOf(selection);
            return new Point((int) (box[0] + box[2] / 2), (int) (box[1] + box[3] / 2));
        }
        if (lastCursorPoint != null) {
            return lastCursorPoint;
        }
        return centreOfTheVisibleCanvas();
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
            setSessionStatus(status, connected ? LIGHT_CONNECTED : LIGHT_ATTENTION);
            // The label says what the button will DO, which depends on whether a session exists - not
            // on whether its socket happens to be up this second. Taking it from `connected` meant
            // that during an automatic reconnect, and after a refusal, the button read
            // "Collaborate..." while a live session object was still held: clicking it disconnected
            // instead of opening the dialog, and the only way to find that out was to try.
            collaborateButton.setText(collab != null ? "Disconnect" : "Collaborate...");
        }

        @Override
        public void onPeersChanged() {
            graphComponent.getGraphControl().repaint();
            warnAboutPeersEditingSomethingElse();
        }

        @Override
        public void onUnshareable(int count, String exampleReason) {
            // A running count in the status line rather than a dialog per change: Protege can
            // produce a dozen unshareable axioms from one action, and a dozen modal dialogs
            // would be worse than the problem. The tooltip carries the detail.
            setSessionStatus(count + " change" + (count == 1 ? "" : "s") + " not shared",
                    LIGHT_ATTENTION);
            collabStatus.setToolTipText("The most recent was " + exampleReason);
        }

        @Override
        public void onPeerGeometry(String iri, java.util.Map<String, Object> data) {
            // The rule is PeerGeometry's: take a colleague's position only for a term this board has
            // no position for, and treat the origin as "no hint" rather than as a coordinate.
            if (PeerGeometry.adoptInto(layout, iri, data)) {
                saveLayoutTo(layoutFile);
            }
        }

        @Override
        public void onSessionEnded(String reason) {
            // A refusal is final. Holding the session afterwards left the button and the state
            // disagreeing; dropping it here is what makes the label above correct.
            if (collab != null) {
                collab.stop();
                collab = null;
            }
            graphComponent.setPeerCursors(null);
            graphComponent.getGraphControl().repaint();
            collaborateButton.setText("Collaborate...");
            setSessionStatus(reason + " - not connected.", LIGHT_ATTENTION);
        }

        @Override
        public void onJoined() {
            announcePresence();
        }
    }

    /**
     * The row of controls above the board.
     *
     * <p>It did not fit. Measured with the real components under Java 11 the bar wanted 1261px and
     * began clipping at 1030; the OntoBoard tab in a 1440px Prot&eacute;g&eacute; window gives it 857,
     * at which {@code Collaborate...} is laid out at x=812 - past the right edge, unpainted and
     * unclickable. {@code JToolBar} uses a {@code BoxLayout}, which lays overflowing children out
     * beyond the container rather than wrapping them, and there is no overflow or chevron to switch
     * on. Nothing said so: the button was simply not there.
     *
     * <p>Three changes bring it to 777px. The layout combo goes - 201px to choose between four
     * entries nobody reopens - and Arrange becomes a button that pops them. Legend, Export and
     * Collaborate move into an overflow menu, because none of them is a thing you do twice a minute.
     * And the two widths that were free to grow are pinned: the Find box, whose slack reached 407px
     * on a wide bar, and the Add button.
     *
     * <p>Add selected, Add all, Find and Inferences stay visible. Inferences is a mode with a visible
     * consequence on the diagram, and a mode you can forget you are in must not be hidden in a menu.
     */
    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        addSelectedButton = new JButton("Add selected", new CanvasIcons.Plus());
        addSelectedButton.addActionListener(a -> addSelectedEntityToCanvas());
        // Pinned, all three ways, because describeSelectionOnButton rewrites this label on every
        // selection change: "Add selected" is 108px and "Add Cheesey vegetable topping" is 211, so
        // clicking through the class tree shifted everything to its right by up to 103px - including
        // the Find field, which slid out from under the pointer mid-type. searchCount has carried a
        // fixed width for exactly this reason since 1.58.0.
        Dimension addSize = new Dimension(150, addSelectedButton.getPreferredSize().height);
        addSelectedButton.setPreferredSize(addSize);
        addSelectedButton.setMinimumSize(addSize);
        addSelectedButton.setMaximumSize(addSize);
        describeSelectionOnButton(getOWLEditorKit().getOWLWorkspace()
                .getOWLSelectionModel().getSelectedEntity());

        JButton addAll = new JButton("Add all", new CanvasIcons.PlusStack());
        addAll.setToolTipText("Put every class, individual and property in the ontology on "
                + "the board");
        addAll.addActionListener(a -> addEverythingToCanvas());

        arrangeButton = new JButton("Arrange", new CanvasIcons.Tree());
        arrangeButton.setToolTipText("Lay the board out - superclasses above their subclasses");
        arrangeButton.addActionListener(a -> {
            JPopupMenu algorithms = new JPopupMenu();
            for (final CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
                JMenuItem item = new JMenuItem(algorithm.getDisplayName());
                item.addActionListener(b -> arrangeWith(algorithm));
                algorithms.add(item);
            }
            algorithms.show(arrangeButton, 0, arrangeButton.getHeight());
        });

        inferencesButton = new javax.swing.JToggleButton("Inferences", new CanvasIcons.Dashed());
        inferencesButton.setToolTipText("Also draw what the running reasoner concludes, dotted");
        inferencesButton.addActionListener(a -> {
            showInferences = inferencesButton.isSelected();
            refresh();
        });

        collaborateButton = new JButton("Collaborate...");
        collaborateButton.addActionListener(a -> toggleCollaboration());

        bar.add(addSelectedButton);
        bar.add(addAll);
        bar.addSeparator();
        bar.add(buildFindBox());
        bar.addSeparator();
        bar.add(arrangeButton);
        bar.add(inferencesButton);
        bar.add(javax.swing.Box.createHorizontalGlue());
        bar.add(buildOverflowButton());
        return bar;
    }

    /**
     * Everything that does not need to be one click away.
     *
     * <p>Collaborate is in here rather than on the bar because it is pressed once a session, and
     * because its state is already reported continuously in the status bar - which is where somebody
     * looks to find out whether they are sharing, not at a button.
     */
    private JButton buildOverflowButton() {
        final JButton more = new JButton(new CanvasIcons.Kebab());
        more.setToolTipText("More");
        more.addActionListener(a -> {
            JPopupMenu menu = new JPopupMenu();

            JMenuItem legend = new JMenuItem("Legend...", new CanvasIcons.Key());
            legend.setToolTipText("What the shapes and lines mean");
            legend.addActionListener(b -> showLegend());
            menu.add(legend);

            JMenuItem export = new JMenuItem("Export image...", new CanvasIcons.Download());
            export.addActionListener(b -> exportWithOptions());
            menu.add(export);
            menu.addSeparator();

            JMenuItem collaborate = new JMenuItem(collaborateButton.getText());
            collaborate.addActionListener(b -> toggleCollaboration());
            menu.add(collaborate);
            menu.addSeparator();

            final javax.swing.JCheckBoxMenuItem snap =
                    new javax.swing.JCheckBoxMenuItem("Snap to grid", graph.isGridEnabled());
            snap.setToolTipText("A 20px grid. Alt while dragging ignores it for one move.");
            snap.addActionListener(b -> {
                graph.setGridEnabled(snap.isSelected());
                graphComponent.setGridVisible(snap.isSelected());
                graphComponent.getGraphControl().repaint();
            });
            menu.add(snap);
            menu.addSeparator();

            JMenuItem shortcuts = new JMenuItem("Keyboard shortcuts\u2026");
            shortcuts.addActionListener(b -> showShortcuts());
            menu.add(shortcuts);

            menu.show(more, 0, more.getHeight());
        });
        return more;
    }

    /**
     * Find a term on the board.
     *
     * <p>The gap this closes is the largest one in the interface, and it is a gap rather than a bug:
     * on a board with a hundred terms - what <em>Add all</em> produces on the pizza ontology, and
     * small for the ontologies this plugin is for - there was no way to locate {@code Margherita}
     * except to drag the canvas until it appeared, or to leave the canvas for the class hierarchy,
     * find it there, and come back. People did the second, which made the canvas a thing to look at
     * rather than to work in.
     *
     * <p>Four keys, because a search box that only searches is half of one. <b>Enter</b> steps to the
     * next match and <b>Shift+Enter</b> back, so a term whose name is a prefix of four others is two
     * keystrokes away rather than a longer query. <b>Ctrl+Enter</b> adds the best match that is in the
     * ontology but not yet on the board - the case where the honest answer to a search is "it exists,
     * you just have not drawn it", and where sending somebody back to the class hierarchy to drag it
     * across is exactly the round trip this box exists to remove. <b>Escape</b> clears the box and
     * returns the keyboard to the canvas.
     *
     * <p>Matching is {@link CanvasSearch}, tested separately, because the ranking is the part that
     * can be quietly wrong: with a plain substring match, typing {@code marg} on the pizza ontology
     * centres whichever of {@code Margherita} and {@code VegetarianMargheritaBase} the ontology
     * happens to list first.
     */
    private JPanel buildFindBox() {
        JPanel box = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        box.setOpaque(false);

        javax.swing.JLabel caption = new javax.swing.JLabel("Find");
        searchField = new javax.swing.JTextField(13);
        // A floor and a ceiling. JToolBar's BoxLayout pours all of a wide bar's slack into the one
        // growable child, which took the field to 407px on a 1400px bar and left it at its minimum
        // on a narrow one.
        searchField.setMinimumSize(new Dimension(140, searchField.getPreferredSize().height));
        searchField.setToolTipText("<html><b>Find a term on the board</b> by label or IRI."
                + "<br>Enter: next match &nbsp; Shift+Enter: previous"
                + "<br>Ctrl+Enter: add a match that is in the ontology but not on the board"
                + "<br>Escape: clear &nbsp; Ctrl+F: come back here</html>");
        caption.setLabelFor(searchField);

        searchCount = new javax.swing.JLabel(" ");
        searchCount.setFont(searchCount.getFont().deriveFont(
                java.awt.Font.PLAIN, searchCount.getFont().getSize() - 1f));
        // Fixed width so the toolbar does not reflow on every keystroke, which reads as the whole
        // row twitching while you type.
        searchCount.setPreferredSize(new Dimension(92, searchField.getPreferredSize().height));

        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                runSearch(true);
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                runSearch(true);
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                runSearch(true);
            }
        });

        bindInField("ontoboard.find.next", javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_ENTER, 0), () -> stepThroughMatches(1));
        bindInField("ontoboard.find.previous", javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_ENTER,
                java.awt.event.InputEvent.SHIFT_DOWN_MASK), () -> stepThroughMatches(-1));
        bindInField("ontoboard.find.add", javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_ENTER,
                java.awt.event.InputEvent.CTRL_DOWN_MASK), this::addBestMatchFromTheOntology);
        bindInField("ontoboard.find.addMeta", javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_ENTER,
                java.awt.event.InputEvent.META_DOWN_MASK), this::addBestMatchFromTheOntology);
        bindInField("ontoboard.find.clear", javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_ESCAPE, 0), this::clearSearch);

        box.add(caption);
        box.add(searchField);
        box.add(searchCount);
        box.setMaximumSize(new Dimension(320, Short.MAX_VALUE));
        return box;
    }

    /** One keystroke inside the Find box, without five anonymous actions in the builder. */
    private void bindInField(String name, javax.swing.KeyStroke key, final Runnable action) {
        searchField.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).put(key, name);
        searchField.getActionMap().put(name, new javax.swing.AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                action.run();
            }
        });
    }

    /**
     * A status bar along the bottom, with the zoom controls in it.
     *
     * <p>The status text used to live in the toolbar, which put a line of prose that grows and
     * shrinks - "3 changes not shared", "Disconnected - you switched ontology" - in the middle of a
     * row of buttons, pushing them sideways as it changed and squeezing them out of the panel
     * entirely on a narrow one. A status line belongs under the thing it describes.
     *
     * <p>Zoom sits here rather than in the toolbar for the same reason every drawing tool puts it
     * here: it is a property of the view, not an action on the ontology, and the bottom-right corner
     * is where people look for it.
     */
    private JPanel buildStatusBar() {
        collabStatus = new javax.swing.JLabel("Working through git", new CanvasIcons.Dot(
                LIGHT_OFFLINE), javax.swing.SwingConstants.LEADING);
        collabStatus.setFont(collabStatus.getFont().deriveFont(
                java.awt.Font.PLAIN, collabStatus.getFont().getSize() - 1f));
        collabStatus.setIconTextGap(6);

        boardStatus = new javax.swing.JLabel(" ");
        boardStatus.setFont(boardStatus.getFont().deriveFont(
                java.awt.Font.PLAIN, boardStatus.getFont().getSize() - 1f));

        JPanel statusBar = new JPanel(new BorderLayout(8, 0));
        Color rule = javax.swing.UIManager.getColor("controlShadow");
        statusBar.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0,
                        rule == null ? Color.GRAY : rule),
                javax.swing.BorderFactory.createEmptyBorder(2, 8, 2, 4)));
        // The session on the left at a fixed width, the board's own line in the middle taking
        // whatever is left. Fixed, so a long session message does not push the board's line about.
        collabStatus.setPreferredSize(new Dimension(220, collabStatus.getPreferredSize().height));
        statusBar.add(collabStatus, BorderLayout.WEST);
        statusBar.add(boardStatus, BorderLayout.CENTER);
        // The zoom controls moved onto the board itself in 1.68.0, where a drawing tool puts them
        // and where they are next to what they act on. Keeping a second copy here would also have
        // meant two buttons claiming to be the readout, with only whichever was built last actually
        // wired to the scale event.
        return statusBar;
    }

    /**
     * One export button, then the choices.
     *
     * <p>Two buttons for two formats does not scale past two formats, and it put the least
     * interesting decision - the file type - in the toolbar while the one that matters, how big,
     * was not offered at all.
     */
    /**
     * Arranges the board and keeps the result.
     *
     * <p>The capture and the save are the point. Without them an arrangement lived only in the live
     * graph, so the next {@code refresh()} - any edit anywhere in Protege - redrew every node from
     * the stored positions and the arrangement was gone. Somebody who arranged thirty classes into
     * a readable tree and then added one subclass watched the tree collapse.
     *
     * <p>Both other callers of {@code CanvasLayouts.apply} already did this; only the toolbar button
     * did not, which is the kind of difference that survives because each path looks right alone.
     */
    private void arrangeWith(CanvasLayouts.Algorithm algorithm) {
        if (algorithm == null) {
            return;
        }
        // The action undo exists for. Arrange replaces every position at once, so a misdirected click
        // discarded an arrangement somebody had spent an afternoon on, and the only way back was to do
        // it again by hand.
        rememberBoard("arranging the board");
        movingProgrammatically = true;
        try {
            CanvasLayouts.apply(graph, algorithm);
        } finally {
            movingProgrammatically = false;
        }
        capturePositions();
        saveLayoutTo(layoutFile);
        // Fit afterwards, because even a well-shaped tree is bigger than the viewport: a corrected
        // 31-term hierarchy is 3694px wide, so on a 1200px panel the user saw six terms of thirty-one
        // and no evidence that Arrange had done anything at all.
        fitToWindow();
        setStatus("Arranged the board: " + algorithm.getDisplayName()
                + " - fitted to the window at " + CanvasZoom.readout(graph.getView().getScale())
                + ". Notes and frames kept their place among the terms.");
    }

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

            /** Where the button went down, to tell a click from the end of a pan. */
            private java.awt.Point pressedAt;

            @Override
            public void mousePressed(MouseEvent event) {
                pressedAt = event.getPoint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                // A right-drag pans this canvas, by design - isPanningEvent defers to the library
                // for any non-left button. But on Windows the popup trigger IS the button-3
                // release, so the documented pan gesture ended with an eleven-item menu opening
                // wherever the user happened to drag to, every single time. The tolerance is the
                // same threshold mxGraphComponent.isSignificant uses to tell a click from a drag.
                if (pressedAt != null && pressedAt.distance(event.getPoint())
                        > graphComponent.getTolerance()) {
                    return;
                }
                Object cell = graphComponent.getCellAt(event.getX(), event.getY());
                String iri = graph.getIdForCell(cell);
                JPopupMenu menu = new JPopupMenu();

                boolean onAnnotation = iri != null && SchemaGraph.isAnnotationId(iri);
                boolean onTerm = iri != null && !onAnnotation && membership.contains(iri);
                boolean onEdge = cell != null && graph.getModel().isEdge(cell);

                // Where the user actually right-clicked, in graph coordinates, worked out
                // once while the event is still in hand.
                //
                // Four call sites below used to get this wrong in two different ways. The note and
                // frame items asked `getGraphControl().getMousePosition()` from inside their action
                // listeners - by which time the pointer is over the menu, not the canvas, so it
                // returns null and both fell back to (60, 60). Every note a curator placed landed
                // in the top-left corner, stacked on the ones before it, however far away they were
                // looking. "New class here..." captured the raw event coordinates, which are
                // graph-control pixels: correct at 100% zoom and wrong by the scale factor at any
                // other, so after a wheel-zoom a term appeared nowhere near the click.
                //
                // getPointForEvent is JGraphX's own converter and handles scale and translation. The
                // double-click path has always used it, which is why that one gesture was right.
                // Using the library's arithmetic rather than repeating it here is also why there is
                // no unit test for the conversion: there is no longer any arithmetic of ours to test.
                com.mxgraph.util.mxPoint graphPoint = graphComponent.getPointForEvent(event);
                final java.awt.Point where =
                        new java.awt.Point((int) graphPoint.getX(), (int) graphPoint.getY());

                // ---- 1. what is under the cursor -------------------------------------------
                if (onAnnotation) {
                    JMenuItem edit = new JMenuItem("Edit this note or frame\u2026");
                    edit.addActionListener(a -> editAnnotation(iri));
                    menu.add(edit);
                    menu.add(colourMenuFor(iri));
                }
                if (onTerm) {
                    JMenuItem expand = new JMenuItem("Expand neighbours (1 hop)");
                    expand.setToolTipText("Put everything directly related to this term on the "
                            + "board, in a ring around it");
                    expand.addActionListener(a -> expandNeighbours(iri));
                    menu.add(expand);

                    // Offered only when there is something to take back, counted over what is still
                    // on the board: the user may have removed some of those terms by hand since, and
                    // a menu item promising to remove four when three remain is a menu item that
                    // reports the wrong number after doing the right thing.
                    List<String> lastExpansion = stillOnTheBoard(expansions.get(iri));
                    if (!lastExpansion.isEmpty()) {
                        JMenuItem collapse = new JMenuItem(
                                "Collapse (" + lastExpansion.size() + " added)");
                        collapse.setToolTipText("Take the terms that expansion added off the board. "
                                + "The ontology is not touched.");
                        collapse.addActionListener(a -> collapseExpansion(iri));
                        menu.add(collapse);
                    }

                    // The note a domain expert leaves is the one that belongs in the ontology, and
                    // reading it used to mean leaving the canvas: the heavy border said a note
                    // existed and nothing on the board would say what it was. The main menu's
                    // Notes > Note on the selected term... writes the same IAO:0000116, so this is
                    // the same capability where the user is already looking.
                    JMenuItem note = new JMenuItem(
                            EditorNotes.notesOn(getOWLModelManager().getActiveOntology(),
                                    IRI.create(iri), EditorNotes.Kind.EDITOR).isEmpty()
                                    ? "Editorial note\u2026" : "Editorial note (has one)\u2026");
                    note.setToolTipText("An IAO:0000116 editor note. Unlike a sticky note this is "
                            + "in the ontology and travels with it.");
                    note.addActionListener(a -> editEditorialNote(iri));
                    menu.add(note);
                }

                // ---- 2. what it can be joined to -------------------------------------------
                if (onTerm) {
                    menu.addSeparator();
                    JMenuItem hierarchy = new JMenuItem("Set parent or type\u2026");
                    hierarchy.setToolTipText("Assert rdfs:subClassOf, rdf:type or "
                            + "rdfs:subPropertyOf between this term and another on the board");
                    hierarchy.addActionListener(a -> createHierarchyLinkFrom(iri));
                    menu.add(hierarchy);

                    JMenuItem relate = new JMenuItem("Relate to another term\u2026");
                    relate.setToolTipText("An object property restriction. Dragging from this "
                            + "node's handle to another does the same thing.");
                    relate.addActionListener(a -> createRelationFrom(iri));
                    menu.add(relate);
                }

                // ---- 3. what can be made here ----------------------------------------------
                if (menu.getComponentCount() > 0) {
                    menu.addSeparator();
                }
                JMenuItem newClass = new JMenuItem("New class here\u2026");
                newClass.addActionListener(a -> createEntityAt(
                        EntityFactory.Kind.CLASS, where.x, where.y));
                menu.add(newClass);

                JMenuItem newIndividual = new JMenuItem("New individual here\u2026");
                newIndividual.addActionListener(a -> createEntityAt(
                        EntityFactory.Kind.INDIVIDUAL, where.x, where.y));
                menu.add(newIndividual);

                JMenuItem addNote = new JMenuItem("Sticky note here\u2026");
                addNote.setToolTipText("A note on the diagram. It is not in the ontology and "
                        + "never appears in a release - see OntoBoard > Notes for one that does.");
                addNote.addActionListener(a -> createStickyNote(where));
                menu.add(addNote);

                JMenuItem addFrame = new JMenuItem("Frame here\u2026");
                addFrame.setToolTipText("A labelled region to group what is inside it. Also only "
                        + "on the diagram.");
                addFrame.addActionListener(a -> createFrame(where));
                menu.add(addFrame);

                // ---- 4. what it takes away, last -------------------------------------------
                if (onTerm || onAnnotation || onEdge) {
                    menu.addSeparator();
                }
                if (onTerm) {
                    JMenuItem remove = new JMenuItem("Remove from board");
                    remove.setToolTipText("The axioms stay in the ontology.");
                    remove.setAccelerator(javax.swing.KeyStroke.getKeyStroke(
                            java.awt.event.KeyEvent.VK_DELETE, 0));
                    remove.addActionListener(a -> {
                        membership.remove(iri);
                        refresh();
                    });
                    menu.add(remove);
                }
                if (onEdge) {
                    final String edgeId = graph.getIdForCell(cell);
                    if (isInferred(edgeId)) {
                        // An inferred edge has no axiom behind it, so there is nothing to delete.
                        // Offering the item anyway produced "Cannot work out which axiom edge
                        // 'inf|...' stands for", which reads as a plugin defect rather than as the
                        // plain fact that the reasoner worked this out and the ontology does not
                        // say it. A disabled item explains; a missing one leaves the user
                        // right-clicking again to check they had not misread the menu.
                        JMenuItem inferred = new JMenuItem("Inferred - no axiom to delete");
                        inferred.setEnabled(false);
                        inferred.setToolTipText("The reasoner worked this out; the ontology does "
                                + "not assert it. Nothing to remove.");
                        menu.add(inferred);
                    } else {
                        JMenuItem deleteAxiom = new JMenuItem("Delete axiom from ontology\u2026");
                        deleteAxiom.addActionListener(a -> deleteAxiomFor(edgeId));
                        menu.add(deleteAxiom);
                    }
                }
                if (onAnnotation) {
                    JMenuItem delete = new JMenuItem("Delete this note or frame");
                    delete.addActionListener(a -> deleteAnnotation(iri));
                    menu.add(delete);
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
        saveLayoutTo(layoutFile);

        stopCollaborationIfTheOntologyChanged();
        loadLayoutForActiveOntology();
        // The history described the previous ontology's board. Restoring it here would put back
        // identifiers this ontology does not declare, which the sidecar loader prunes to nothing - so
        // "undo" would empty the board rather than restore it.
        history.clear();
        expansions.clear();
        refresh();
    }

    /**
     * Ends a live session when the user switches to a different ontology.
     *
     * <p>A board belongs to an ontology, and after a switch the canvas is showing something the
     * session is not about: peer cursors are positions in the other ontology's diagram, and the
     * edits being made here are not the ones the board carries. {@link CollabSession} now refuses to
     * publish or apply across that boundary, so nothing is corrupted by staying connected - but a
     * connected toolbar over an ontology that is not being shared says the opposite of the truth,
     * which is its own kind of wrong.
     *
     * <p>So it disconnects and says why, rather than leaving a half-state to be discovered. Coming
     * back to the original ontology does not reconnect on its own; that would be a surprising amount
     * of initiative for something holding a credential.
     */
    private void stopCollaborationIfTheOntologyChanged() {
        if (collab == null) {
            return;
        }
        OWLOntology sessionOntology = collab.getSubject();
        OWLOntology nowActive = getOWLModelManager().getActiveOntology();
        if (sessionOntology == null || sessionOntology.equals(nowActive)) {
            return;
        }
        collab.stop();
        collab = null;
        graphComponent.setPeerCursors(null);
        lastPeerOntologyWarning = null;
        collaborateButton.setText("Collaborate...");
        String message = "Disconnected: the session was for "
                + ontologyIriOf(sessionOntology)
                + ", and you have switched to another ontology. Collaborate... to start a session "
                + "for this one.";
        setSessionStatus("Disconnected - you switched ontology", LIGHT_ATTENTION);
        collabStatus.setToolTipText(message);
        LOGGER.info("OntoBoard: {}", message);
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
        // What search matches against, and what it can no longer assume about the ontology.
        rendered = projection;
        ontologyTerms = null;
        autoArrangeIfUnpositioned();
        showAppropriateCard();

        if (selectionBridge != null) {
            selectionBridge.resyncAfterRender(selectedBeforeRender);
        }
    }

    /** Copies live cell geometry back into the layout so it survives the next save. */
    private void capturePositions() {
        captureInto(layout, membership.asSet(), new Bounds() {
            @Override
            public double[] of(String id) {
                Object cell = graph.getCellForId(id);
                if (!(cell instanceof com.mxgraph.model.mxCell)) {
                    return null;
                }
                com.mxgraph.model.mxGeometry geometry =
                        ((com.mxgraph.model.mxCell) cell).getGeometry();
                return geometry == null ? null : new double[] {
                        geometry.getX(), geometry.getY(),
                        geometry.getWidth(), geometry.getHeight() };
            }
        });
    }

    /** Where a cell is now, as {@code {x, y, w, h}}, or null when it is not on the board. */
    interface Bounds {
        double[] of(String id);
    }

    /**
     * Copies live geometry back into the layout - terms, sticky notes and frames alike.
     *
     * <p>Notes and frames were missing, and their absence was invisible: the {@code CELLS_MOVED}
     * listener ran, this captured nothing about them, and an unchanged sidecar was saved. The next
     * refresh - which any edit anywhere in Protege triggers - redrew both from the position they
     * had before the user moved them. Dragging a note beside the class it comments on and then
     * editing anything put it back in the corner; a frame resized to enclose a group snapped back.
     *
     * <p>Separated from the view and given a {@link Bounds} lookup so a test can drive it without a
     * live {@code OWLEditorKit} - which is why the gap survived, there was nothing a unit test
     * could call. Package-private for the same reason {@link #resetLayoutForOntology} is.
     */
    static void captureInto(CanvasLayout layout, java.util.Set<String> terms, Bounds bounds) {
        for (String iri : terms) {
            double[] box = bounds.of(iri);
            if (box != null) {
                CanvasLayout.NodeLayout node =
                        layout.nodes.computeIfAbsent(iri, k -> new CanvasLayout.NodeLayout());
                node.x = box[0];
                node.y = box[1];
                node.w = box[2];
                node.h = box[3];
            }
        }
        for (CanvasLayout.NoteLayout note : layout.notes) {
            double[] box = bounds.of(note.id);
            if (box != null) {
                note.x = box[0];
                note.y = box[1];
            }
        }
        for (CanvasLayout.FrameLayout frame : layout.frames) {
            double[] box = bounds.of(frame.id);
            if (box != null) {
                frame.x = box[0];
                frame.y = box[1];
                frame.w = box[2];
                frame.h = box[3];
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
        layoutFile = currentOntologyFile;
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        layout = null;

        if (currentOntologyFile != null
                && CanvasLayoutStore.sidecarFor(currentOntologyFile).isFile()) {
            // A sidecar this plugin cannot read must cost the arrangement, never the view.
            // CanvasLayoutStore.load throws by design - UncheckedIOException on unparseable JSON,
            // UnsupportedLayoutVersionException on a file written by a newer OntoBoard - and
            // nothing caught either. This runs from initialiseOWLView, so a half-written sidecar
            // (an interrupted save, a merge conflict, a colleague on a newer version) stopped the
            // OntoBoard tab from opening at all, every time, with no way back except finding and
            // deleting a file whose name the user has no reason to know.
            CanvasLayout stored;
            try {
                stored = CanvasLayoutStore.load(currentOntologyFile);
            } catch (RuntimeException unreadable) {
                LOGGER.warn("OntoBoard: cannot read {}; starting with an empty board and leaving "
                        + "the file alone", CanvasLayoutStore.sidecarFor(currentOntologyFile),
                        unreadable);
                setStatus("The saved arrangement could not be read, so the board starts empty. "
                        + "The file has been left alone rather than overwritten, and your "
                        + "ontology is untouched. " + unreadable.getMessage());
                stored = null;
                // The line that makes the rest of this true. Without it the guard was worse than
                // the crash it replaced: currentOntologyFile still pointed at the ontology, so
                // the 800ms debounce timer wrote an empty board over the sidecar after the first
                // node drag - destroying a file that, in the case this guard exists for, is
                // perfectly good data written by a newer OntoBoard. The sibling branch below got
                // this right and said why; this one omitted exactly that line.
                //
                // layoutFile only. The first version of this guard cleared currentOntologyFile,
                // which the ID ranges and the provenance settings also read - so refusing to write
                // a sidecar quietly switched off minting from the editor's own block, and "New
                // class here..." started naming terms after the typed text, outside the project's
                // scheme, permanently.
                layoutFile = null;
            }
            if (stored == null) {
                layout = null;
            } else if (stored.belongsTo(ontologyIriOf(ontology))) {
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
                // So nothing is written back over it. Same distinction as above: the arrangement
                // is not saved, and the project is still the project.
                layoutFile = null;
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
        // Imports included: an imported term on the board is not stale. Excluding them meant a
        // board holding bfo:continuant had that entry deleted from the sidecar on the next load,
        // which is the second half of why dragging an imported term appeared to do nothing.
        //
        // Through OntologyProjection rather than ontology.getSignature(INCLUDED), which in OWL API
        // 4.5.29 corrupts the same ontology's cached EXCLUDED signature - see termIdentifiers.
        Set<String> declared = OntologyProjection.termIdentifiers(ontology,
                org.semanticweb.owlapi.model.parameters.Imports.INCLUDED);
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

    /**
     * Saves the arrangement, or says once that it cannot.
     *
     * <p>Caught here so no caller has to. The callers are a debounce {@link Timer} and nine mouse
     * and menu handlers, none of which has anything above it to catch an exception - so an
     * ontology opened from somewhere readable but not writable (a read-only checkout, a mounted
     * share, a protected directory) turned every node drag into an uncaught
     * {@code UncheckedIOException} on the event thread, 800ms after the user let go of the mouse.
     *
     * <p>Reported once per session. A failing drag reports on every mouse release otherwise, and
     * a dialog per drag is worse than the silence it replaced.
     */
    private void saveLayoutTo(File file) {
        if (file == null) {
            return;
        }
        try {
            CanvasLayoutStore.save(file, layout);
        } catch (RuntimeException cannotWrite) {
            if (reportedSaveFailure.compareAndSet(false, true)) {
                LOGGER.warn("OntoBoard: cannot write {}",
                        CanvasLayoutStore.sidecarFor(file), cannotWrite);
                setStatus("This board's arrangement cannot be saved: " + cannotWrite.getMessage()
                        + " The ontology itself is unaffected.");
            }
        }
    }

    /** So a board that cannot be saved says so once rather than on every drag. */
    private final java.util.concurrent.atomic.AtomicBoolean reportedSaveFailure =
            new java.util.concurrent.atomic.AtomicBoolean();

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
        changes.addAll(provenanceFor(ontology, iri, minter.isNumeric()));
        if (!changes.isEmpty()) {
            getOWLModelManager().applyChanges(changes);
        }
        layout.nodes.put(iri.toString(), new CanvasLayout.NodeLayout(x, y));
        membership.add(iri.toString());
        refresh();
        saveLayoutTo(layoutFile);
    }

    /**
     * Provenance for a term this plugin has just changed, or nothing.
     *
     * <p>{@code dcterms:date} answers "when did this term last change", which is the question a
     * curator asks before trusting a definition. {@link Provenance#stampModified} was written and
     * tested and never called, so every term in every ontology edited here carried a creation
     * date and no modification date at all - and the absence reads as "never touched since it was
     * made", which was false for any term anybody had worked on.
     *
     * <p>Stamped where this plugin makes the edit rather than from the change listener. The
     * listener sees every change including the ones arriving from a collaborator, and stamping
     * those would record the local user as having modified a term somebody else changed - and
     * would then publish that stamp back, which is a loop.
     *
     * @param iri the term that was edited, not the axiom that did it
     */
    private List<OWLOntologyChange> modificationProvenanceFor(OWLOntology ontology, IRI iri) {
        ProvenanceSettings settings = ProvenanceSettings.load();
        if (!settings.shouldStamp(ontology, isOdkProject())) {
            return new ArrayList<OWLOntologyChange>();
        }
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>(
                Provenance.declareProperties(ontology));
        changes.addAll(Provenance.stampModified(ontology, iri, settings.canonicalAgent(),
                today()));
        return changes;
    }

    /**
     * Provenance for edits made in Protege's own editors rather than on the canvas.
     *
     * <p>A gap this plugin created. Until this existed, provenance was stamped only where the
     * canvas made the edit - so a term dragged here recorded who and when, and the same term
     * re-parented in the class hierarchy, or given a restriction in the Manchester syntax editor,
     * recorded nothing. Which is most editing. The result was worse than no provenance at all: a
     * reader sees dates on some terms and none on others and concludes the undated ones were never
     * touched, when what actually happened is that somebody used a different view.
     *
     * <p>Three things make this safe to do from the change listener, which an earlier comment here
     * rightly said it was not:
     *
     * <ul>
     *   <li><b>Remote changes are skipped.</b> The listener sees a collaborator's edits arriving,
     *       and stamping those would record the local user as having modified somebody else's
     *       work - then publish that back, which is a loop.
     *   <li><b>A stamp is not an edit.</b> {@link EditWatcher} ignores changes that only write
     *       provenance, which is what terminates the recursion of stamping causing a stamp.
     *   <li><b>It is applied afterwards, not during.</b> Protege is in the middle of broadcasting
     *       this change to every listener; applying more changes inside that broadcast is asking
     *       for trouble. The cost is that Edit &gt; Undo takes two steps - the stamp, then the
     *       edit - which is the honest price of recording something Protege itself does not.
     * </ul>
     */
    private void stampEditsMadeElsewhere(List<? extends OWLOntologyChange> changes) {
        if (collab != null && collab.isApplyingRemote()) {
            return;
        }
        final OWLOntology ontology = getOWLModelManager().getActiveOntology();
        ProvenanceSettings settings = ProvenanceSettings.load();
        if (ontology == null || !settings.shouldStamp(ontology, isOdkProject())
                || !EditWatcher.isWorthStamping(ontology, changes)) {
            return;
        }
        final List<OWLOntologyChange> snapshot =
                new ArrayList<OWLOntologyChange>(changes);
        final String agent = settings.canonicalAgent();
        javax.swing.SwingUtilities.invokeLater(() -> {
            // Recomputed against the ontology as it is now rather than as it was, so an edit
            // undone or a term deleted in the meantime is not stamped back into existence.
            List<OWLOntologyChange> stamps =
                    EditWatcher.stampsFor(ontology, snapshot, agent, today());
            if (!stamps.isEmpty()) {
                getOWLModelManager().applyChanges(stamps);
            }
        });
    }

    /**
     * Whether the open ontology is an ODK project, for the provenance default.
     *
     * <p>A project this plugin scaffolded gets provenance by default; somebody else's ontology
     * does not, because introducing a convention its maintainers never chose would show up as
     * unexplained churn in their next diff.
     */
    private boolean isOdkProject() {
        return currentOntologyFile != null
                && TermMinter.findRangesFile(currentOntologyFile) != null;
    }

    /**
     * Provenance for a term just created, or nothing.
     *
     * <p>Whether to stamp is the ontology's decision more than the user's - see
     * {@link ProvenanceSettings}. An ontology that has never recorded provenance does not start
     * because somebody opened it here, since that would write a convention its maintainers never
     * chose into every term and show up as unexplained churn in their next diff.
     *
     * <p>The annotation properties are declared alongside, because {@code ROBOT report} flags an
     * undeclared annotation property - so stamping without declaring would make a project fail its
     * own quality check.
     */
    private List<OWLOntologyChange> provenanceFor(OWLOntology ontology, IRI iri,
            boolean isOdkProject) {
        ProvenanceSettings settings = ProvenanceSettings.load();
        if (!settings.shouldStamp(ontology, isOdkProject)) {
            return new ArrayList<OWLOntologyChange>();
        }
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>(
                Provenance.declareProperties(ontology));
        changes.addAll(Provenance.stampNew(ontology, iri, settings.canonicalAgent(), today()));
        return changes;
    }

    /**
     * Today, as {@code YYYY-MM-DD}.
     *
     * <p>Java 8 has {@code LocalDate}, but this bundle targets the Java 8 <em>API surface</em> on a
     * JRE that Protege supplies, and SimpleDateFormat is available everywhere without question.
     * The format is fixed to ISO regardless of locale, since a date in the ontology must not depend
     * on the machine that wrote it.
     */
    private static String today() {
        return Provenance.today();
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

        // Options carry their IRI. Round-tripping through the rendered label and looking the
        // string back up meant two board entries rendering to the same text both resolved to the
        // first one's IRI - so the axiom was written against a class the user had not picked, and
        // the canvas then drew the arrow there because the axiom really did say so. Same text is
        // easy to get: DisplayLabels falls back to the IRI's short name, so a#Pizza and b#Pizza
        // both render "Pizza", which is precisely the case somebody aligning two ontologies has
        // on the board.
        Target[] labels = new Target[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            labels[i] = new Target(targets.get(i), DisplayLabels.forEntity(ontology,
                    factory.getOWLClass(IRI.create(targets.get(i)))));
        }
        String sourceLabel = DisplayLabels.forEntity(ontology,
                factory.getOWLClass(IRI.create(sourceIri)));

        Object chosen = JOptionPane.showInputDialog(this,
                "Relate " + sourceLabel + " to:", "Choose target",
                JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
        if (chosen == null) {
            return;
        }
        relateWithObjectProperty(sourceIri, ((Target) chosen).iri);
    }

    /**
     * Writes one object property restriction between two terms already chosen.
     *
     * <p>Split out of {@link #createRelationFrom} so that drawing an edge on the canvas and picking a
     * target from a list end at the same code. The alternative was a second copy of the property
     * picker, the minting of a new property, the EL profile warning and the provenance stamp - four
     * things that are subtle once and would be wrong in the copy.
     */
    private void relateWithObjectProperty(String sourceIri, String targetIri) {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        OWLDataFactory factory = getOWLModelManager().getOWLDataFactory();

        // A restriction is asserted about a class and points at a class. Reached from the canvas
        // gesture this can be untrue - somebody drags from an individual - so it is checked here
        // rather than only by the target list that the menu path filters.
        if (!ontology.containsClassInSignature(IRI.create(sourceIri))
                || !ontology.containsClassInSignature(IRI.create(targetIri))) {
            JOptionPane.showMessageDialog(this,
                    "An object property restriction goes from a class to a class.\n\n"
                            + "For an individual, use its type; for a property, its parent "
                            + "property - both are on the node menu.",
                    "Not two classes", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String sourceLabel = DisplayLabels.forEntity(ontology,
                factory.getOWLClass(IRI.create(sourceIri)));
        String targetLabel = DisplayLabels.forEntity(ontology,
                factory.getOWLClass(IRI.create(targetIri)));

        RelationDialog.Choice choice =
                RelationDialog.ask(this, ontology, sourceLabel, targetLabel);
        if (choice == null) {
            return;
        }

        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        OWLObjectProperty property = choice.getProperty();
        if (property == null) {
            // Through the same minter that createEntityAt uses. This called EntityFactory.iriFor
            // directly, so a new property got ontologyIri#TypedName with no label - the exact
            // fallback createEntityAt refuses in writing, on the grounds that "the whole point of
            // the ranges is that nobody mints outside their block". A project can therefore have
            // its classes inside its scheme and its properties outside it, which is worse than
            // either policy applied consistently.
            TermMinter minter = TermMinter.forOntologyFile(currentOntologyFile, editorName());
            IRI propertyIri;
            try {
                propertyIri = minter.mintFor(ontology, choice.getNewPropertyName());
            } catch (IllegalArgumentException invalid) {
                JOptionPane.showMessageDialog(this, invalid.getMessage(),
                        "Cannot use that name", JOptionPane.WARNING_MESSAGE);
                return;
            } catch (IdRanges.NoRangeException cannotMint) {
                JOptionPane.showMessageDialog(this, cannotMint.getMessage(),
                        "Cannot mint an identifier", JOptionPane.WARNING_MESSAGE);
                return;
            }
            // declare, not EntityFactory.declare: in numeric mode the typed text becomes the
            // rdfs:label, which is the only thing that makes MWO_0001000 readable afterwards.
            changes.addAll(minter.declare(ontology, propertyIri,
                    EntityFactory.Kind.OBJECT_PROPERTY, choice.getNewPropertyName()));
            changes.addAll(provenanceFor(ontology, propertyIri, minter.isNumeric()));
            property = factory.getOWLObjectProperty(propertyIri);
        }

        OWLAxiom relation = EdgeAxioms.build(factory, choice.getCandidate(),
                factory.getOWLClass(IRI.create(sourceIri)), property,
                factory.getOWLClass(IRI.create(targetIri)));

        // Asked here, at the gesture, and not at release time. By release the axiom is one of
        // thousands and whoever wrote it has long forgotten which arrow it was; right now they
        // are looking straight at it. The dialog offers readings that leave OWL 2 EL, and both
        // this plugin's release action and the ODK build it scaffolds classify with ELK - which
        // ignores what it cannot express without saying so.
        String outsideProfile = ProfileCheck.warningFor(relation, ProfileCheck.Target.EL);
        if (outsideProfile != null && JOptionPane.showConfirmDialog(this,
                outsideProfile + "\n\nWrite it anyway?", "Outside the EL profile",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.YES_OPTION) {
            return;
        }
        changes.add(new AddAxiom(ontology, relation));
        // The restriction is asserted about the source class, so the source is what changed.
        changes.addAll(modificationProvenanceFor(ontology, IRI.create(sourceIri)));

        // Applying fires the ontology-change listener, which refreshes the canvas. The edge
        // therefore appears only because the axiom exists - if the change were rejected,
        // no edge would be drawn.
        getOWLModelManager().applyChanges(changes);
    }

    /**
     * Asserts the parent, type or sub-property link between this term and another on the board.
     *
     * <p>The canvas drew all three of these edges and could create none of them: the legend
     * advertised {@code rdfs:subClassOf}, {@code rdf:type} and {@code rdfs:subPropertyOf}, the
     * projection rendered them from the ontology, and the only authoring path - the relation
     * dialog - offered six property restrictions and no way to say "this is a kind of that". To
     * add a parent a user had to leave the canvas for Protege's class hierarchy.
     *
     * <p>Which of the three is offered is decided from what the two ends are rather than asked,
     * because only one is ever legal for a given pair - see {@link HierarchyAxioms#applicableTo}.
     * Offering a choice would be offering two ways to get an error, and letting somebody pick
     * "subclass of" between an individual and a class is exactly the confusion this diagram
     * exists to dispel.
     */
    private void createHierarchyLinkFrom(String sourceIri) {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        OWLDataFactory factory = getOWLModelManager().getOWLDataFactory();
        OWLEntity source = entityOnCanvas(ontology, sourceIri);
        if (source == null) {
            return;
        }

        // Only terms this one could legally be linked to, so the list cannot contain a choice
        // that produces an error message.
        List<String> targets = new ArrayList<String>();
        for (String onCanvas : membership.asSet()) {
            OWLEntity candidate = entityOnCanvas(ontology, onCanvas);
            if (candidate != null && !HierarchyAxioms.applicableTo(source, candidate).isEmpty()) {
                targets.add(onCanvas);
            }
        }
        if (targets.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Nothing on the board can be a parent or a type for "
                            + getOWLModelManager().getRendering(source) + ".\n\n"
                            + "A class takes a class as its parent, an individual takes a class "
                            + "as its type, and a property takes a property of the same kind. "
                            + "Add one to the board first.",
                    "Nothing to link to", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Collections.sort(targets);

        // Identity, not rendered text - the same reason as in createRelationFrom. Two board
        // entries that render alike both resolved to the first one's IRI, so the axiom was
        // written against a term the user had not chosen.
        Target[] labels = new Target[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            labels[i] = new Target(targets.get(i), getOWLModelManager().getRendering(
                    entityOnCanvas(ontology, targets.get(i))));
        }
        HierarchyAxioms.Kind kind = HierarchyAxioms.applicableTo(source,
                entityOnCanvas(ontology, targets.get(0))).get(0);

        Object chosen = JOptionPane.showInputDialog(this,
                getOWLModelManager().getRendering(source) + " " + kind.getDlNotation()
                        + " ...\n\n" + kind.getExplanation() + "\n",
                kind.getDisplayName(), JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
        if (chosen == null) {
            return;
        }
        linkHierarchy(source, entityOnCanvas(ontology, ((Target) chosen).iri));
    }

    /**
     * Asserts the one hierarchy link that is legal between these two terms.
     *
     * <p>Split out of {@link #createHierarchyLinkFrom} so the canvas gesture and the target list write
     * the same axiom, stamp the same term and refuse the same pairs.
     */
    private void linkHierarchy(OWLEntity source, OWLEntity target) {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        OWLDataFactory factory = getOWLModelManager().getOWLDataFactory();
        if (source == null || target == null) {
            return;
        }

        // Re-read for the chosen target: a list can hold more than one kind of term, and the
        // kind used for the prompt came from the first of them.
        List<HierarchyAxioms.Kind> applicable = HierarchyAxioms.applicableTo(source, target);
        if (applicable.isEmpty()) {
            JOptionPane.showMessageDialog(this, HierarchyAxioms.whyNot(source, target),
                    "Cannot link those", JOptionPane.WARNING_MESSAGE);
            return;
        }

        OWLAxiom axiom = HierarchyAxioms.build(factory, applicable.get(0), source, target);
        if (ontology.containsAxiom(axiom)) {
            JOptionPane.showMessageDialog(this, "That link is already asserted.",
                    "Nothing to add", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        changes.add(new AddAxiom(ontology, axiom));
        // The SOURCE term is the one that changed - it gained a parent, a type or a super
        // property. The target is untouched by this axiom and stamping it would claim an edit
        // nobody made.
        changes.addAll(modificationProvenanceFor(ontology, source.getIRI()));
        // Applying fires the ontology-change listener, which refreshes the canvas and publishes
        // to the shared session. The edge appears only because the axiom exists.
        getOWLModelManager().applyChanges(changes);
    }


    /** Whether this edge is the reasoner's conclusion rather than an axiom in the ontology. */
    private static boolean isInferred(String edgeId) {
        return edgeId != null
                && (edgeId.startsWith(InferredEdges.SUBCLASS_ID_PREFIX)
                        || edgeId.startsWith(InferredEdges.TYPE_ID_PREFIX));
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
                        Point graphPoint = graphPointOfDrop(support);
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
        rememberBoard("dropping terms on the board");
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
            // 200 and 100 rather than 190 and 90, so a dropped cluster lands on the 20px grid
            // instead of one pixel off every second column.
            position.x = at.x + column * 200;
            position.y = at.y + row * 100;
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
        saveLayoutTo(layoutFile);
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
                Object cell = graphComponent.getCellAt(event.getX(), event.getY());
                if (cell != null) {
                    // Double-clicking a node is not a request for a new one. On a term it is the
                    // ordinary board gesture for "show me what this connects to"; on a note or a
                    // frame mxGraph starts an in-place edit, which isCellEditable allows for
                    // exactly those two.
                    String onIt = graph.getIdForCell(cell);
                    if (onIt != null && !SchemaGraph.isAnnotationId(onIt)
                            && membership.contains(onIt)) {
                        expandNeighbours(onIt);
                    }
                    return;
                }
                com.mxgraph.util.mxPoint at = graphComponent.getPointForEvent(event);
                createEntityAt(EntityFactory.Kind.CLASS, (int) at.getX(), (int) at.getY());
            }
        });
    }

    /**
     * The entity behind an IRI on the board, or null.
     *
     * <p>An IRI can name more than one kind of entity in the same ontology - OWL 2 punning - and
     * the board holds one node per IRI. Classes first because that is what a board is mostly made
     * of, and because a punned IRI drawn as a class should link as one.
     */
    private OWLEntity entityOnCanvas(OWLOntology ontology, String iri) {
        IRI subject = IRI.create(iri);
        OWLDataFactory factory = getOWLModelManager().getOWLDataFactory();
        if (ontology.containsClassInSignature(subject)) {
            return factory.getOWLClass(subject);
        }
        if (ontology.containsIndividualInSignature(subject)) {
            return factory.getOWLNamedIndividual(subject);
        }
        if (ontology.containsObjectPropertyInSignature(subject)) {
            return factory.getOWLObjectProperty(subject);
        }
        if (ontology.containsDataPropertyInSignature(subject)) {
            return factory.getOWLDataProperty(subject);
        }
        return null;
    }

    /**
     * A term offered in a chooser, carrying what it is as well as what it looks like.
     *
     * <p>{@code JOptionPane} renders options with {@code toString()}, so the dialog looks exactly
     * as it did - but identity no longer depends on two terms rendering differently.
     */
    private static final class Target {
        private final String iri;
        private final String label;

        Target(String iri, String label) {
            this.iri = iri;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Where a drop landed, in graph coordinates.
     *
     * <p>The scroll offset used to be added here, and that was the bug. The same
     * {@code TransferHandler} instance is installed on two components - on {@code graphComponent}
     * and again on {@code graphComponent.getGraphControl()} - and a drop point is relative to
     * whichever one received it. The graph control is the full-size canvas <em>inside</em> the
     * viewport, so its coordinates already account for scrolling; adding the offset counted it
     * twice. On a board scrolled down 500 pixels, a class dropped in the middle of the visible area
     * was recorded 500 units below it - off-screen, so the drop looked like it had done nothing.
     *
     * <p>Worth noting where the right answer already was: {@link #shareCursor} converts a graph
     * control event with a comment saying only the zoom had to be undone - naming this very method as
     * the one that wrongly added a scroll term. The right arithmetic was written down eight lines
     * from the wrong one, and the comment flagged the difference without resolving it. Both now
     * call the same function.
     */
    private Point graphPointOfDrop(javax.swing.TransferHandler.TransferSupport support) {
        Point dropped = support.getDropLocation().getDropPoint();
        java.awt.Component onto = support.getComponent();
        Point onControl = onto == null || onto == graphComponent.getGraphControl()
                ? dropped
                : javax.swing.SwingUtilities.convertPoint(onto, dropped,
                        graphComponent.getGraphControl());
        return graphPointFromControl(onControl.x, onControl.y);
    }

    /** {@link #graphPointFromControl(int, int, double, double, double)} for this graph's view. */
    private Point graphPointFromControl(int controlX, int controlY) {
        com.mxgraph.util.mxPoint translate = graph.getView().getTranslate();
        return graphPointFromControl(controlX, controlY, graph.getView().getScale(),
                translate == null ? 0 : translate.getX(),
                translate == null ? 0 : translate.getY());
    }

    /**
     * The arithmetic {@code mxGraphComponent.getPointForEvent} performs, over a point already in the
     * graph control's coordinates.
     *
     * <p>One function for every gesture that turns a position on screen into a position in the
     * diagram. There were three different answers before: the double-click path called the library's
     * own converter and was right, the cursor-sharing path divided by the zoom and was right, and the
     * drop path added the scroll offset as well and was wrong. Three spellings of one calculation is
     * how one of them stays wrong.
     *
     * <p>Static and package-private so it can be tested without a live component, which the inline
     * versions could not be. There is deliberately no scroll term, and the test asserting its absence
     * is what will keep it from coming back.
     */
    static Point graphPointFromControl(int controlX, int controlY, double scale,
            double translateX, double translateY) {
        double zoom = scale <= 0 ? 1 : scale;
        return new Point((int) (controlX / zoom - translateX),
                (int) (controlY / zoom - translateY));
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
     * <p>The work moved to {@link de.fizkarlsruhe.ise.ontoboard.odk.ProjectOpener} in 1.69.0 so
     * that OntoBoard &gt; Project could offer it too. While it lived here it was reachable only
     * from the start card, which is to say only while the board was empty.
     */
    private void openExistingProject() {
        de.fizkarlsruhe.ise.ontoboard.odk.ProjectOpener.open(this, getOWLEditorKit());
    }
}
