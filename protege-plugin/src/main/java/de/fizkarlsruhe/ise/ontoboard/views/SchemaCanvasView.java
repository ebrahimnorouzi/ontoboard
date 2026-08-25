package de.fizkarlsruhe.ise.ontoboard.views;

import com.mxgraph.swing.mxGraphComponent;
import de.fizkarlsruhe.ise.ontoboard.axiom.AxiomRemoval;
import de.fizkarlsruhe.ise.ontoboard.axiom.EdgeAxioms;
import de.fizkarlsruhe.ise.ontoboard.axiom.EntityFactory;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectLoader;
import de.fizkarlsruhe.ise.ontoboard.axiom.RelationDialog;
import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import com.mxgraph.swing.mxGraphOutline;
import com.mxgraph.util.mxEvent;
import com.mxgraph.util.mxEventSource.mxIEventListener;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasExport;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasLayouts;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasMembership;
import de.fizkarlsruhe.ise.ontoboard.canvas.PalettePanel;
import de.fizkarlsruhe.ise.ontoboard.canvas.StartPanel;
import de.fizkarlsruhe.ise.ontoboard.canvas.SchemaGraph;
import de.fizkarlsruhe.ise.ontoboard.canvas.SelectionBridge;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayoutStore;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Point;
import java.awt.dnd.DropTarget;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import java.io.IOException;
import java.net.URI;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JToolBar;
import javax.swing.Timer;
import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.protege.editor.owl.model.selection.OWLSelectionModelListener;
import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;

public class SchemaCanvasView extends AbstractOWLViewComponent {

    private SchemaGraph graph;
    private mxGraphComponent graphComponent;
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

    @Override
    protected void initialiseOWLView() {
        setLayout(new BorderLayout());

        graph = new SchemaGraph();
        graphComponent = new mxGraphComponent(graph);
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
        add(new PalettePanel(), BorderLayout.WEST);
        graphComponent.getViewport().setOpaque(true);
        graphComponent.getViewport().setBackground(
                Color.decode(de.fizkarlsruhe.ise.ontoboard.canvas.SchemaStyles.CANVAS_BACKGROUND));
        graphComponent.setGridVisible(true);
        installPaletteDropTarget();
        installDoubleClickToCreate();

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
        };
        getOWLEditorKit().getOWLWorkspace().getOWLSelectionModel()
                .addListener(selectionListener);

        changeListener = changes -> refresh();
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

    /** Adds whatever is selected in Protege's hierarchy views to the canvas. */
    public void addSelectedEntityToCanvas() {
        OWLEntity selected = getOWLEditorKit().getOWLWorkspace()
                .getOWLSelectionModel().getSelectedEntity();
        if (selected != null && membership.add(selected.getIRI().toString())) {
            refresh();
        }
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

    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        JComboBox<CanvasLayouts.Algorithm> algorithms =
                new JComboBox<>(CanvasLayouts.Algorithm.values());
        JButton arrange = new JButton("Arrange");
        arrange.addActionListener(a -> CanvasLayouts.apply(graph,
                (CanvasLayouts.Algorithm) algorithms.getSelectedItem()));

        JButton exportPng = new JButton("Export PNG");
        exportPng.addActionListener(a -> exportTo("png"));

        JButton exportSvg = new JButton("Export SVG");
        exportSvg.addActionListener(a -> exportTo("svg"));

        bar.add(algorithms);
        bar.add(arrange);
        bar.addSeparator();
        bar.add(exportPng);
        bar.add(exportSvg);
        return bar;
    }

    private void exportTo(String extension) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("schema-diagram." + extension));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            if ("png".equals(extension)) {
                CanvasExport.writePng(graph, chooser.getSelectedFile());
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
        if (currentOntologyFile != null && CanvasLayoutStore.sidecarFor(currentOntologyFile).isFile()) {
            layout = CanvasLayoutStore.load(currentOntologyFile);
        } else {
            layout = new CanvasLayout();
            resetLayoutForOntology(getOWLModelManager().getActiveOntology(), layout);
        }
        membership = new CanvasMembership(layout);
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
        String name = JOptionPane.showInputDialog(this,
                "Name for the new " + what + ":", "New " + what,
                JOptionPane.PLAIN_MESSAGE);
        if (name == null) {
            return;
        }
        IRI iri;
        try {
            iri = EntityFactory.iriFor(ontology, name);
        } catch (IllegalArgumentException invalid) {
            JOptionPane.showMessageDialog(this, invalid.getMessage(),
                    "Cannot use that name", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<OWLOntologyChange> changes = EntityFactory.declare(ontology, iri, kind);
        if (!changes.isEmpty()) {
            getOWLModelManager().applyChanges(changes);
        }
        layout.nodes.put(iri.toString(), new CanvasLayout.NodeLayout(x, y));
        membership.add(iri.toString());
        refresh();
        saveLayoutTo(currentOntologyFile);
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
    private void installPaletteDropTarget() {
        final TransferHandler mxHandler = graphComponent.getTransferHandler();
        graphComponent.setTransferHandler(new TransferHandler() {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean canImport(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    return true;
                }
                return mxHandler != null && mxHandler.canImport(support);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    try {
                        Object payload = support.getTransferable()
                                .getTransferData(DataFlavor.stringFlavor);
                        EntityFactory.Kind kind =
                                PalettePanel.kindOf(String.valueOf(payload));
                        if (kind != null) {
                            Point at = support.getDropLocation().getDropPoint();
                            Point graphPoint = toGraphPoint(at);
                            createEntityAt(kind, graphPoint.x, graphPoint.y);
                            return true;
                        }
                    } catch (Exception ignored) {
                        // Fall through: something else was dragged in, let mxGraph try.
                    }
                }
                return mxHandler != null && mxHandler.importData(support);
            }
        });
    }

    /**
     * Double-clicking empty canvas creates a class there.
     *
     * <p>The fastest path to a new term, and the one every other diagram tool offers. Having
     * to pick an item from a palette and then drag it is a lot of ceremony for the most
     * common action in ontology sketching, so the palette is now the explicit route rather
     * than the only one.
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
            getOWLModelManager().getOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(project.getEditFile());
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
