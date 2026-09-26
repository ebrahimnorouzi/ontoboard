package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.model.mxCell;
import com.mxgraph.model.mxGraphModel;
import com.mxgraph.view.mxGraph;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.HashMap;
import java.util.Map;

/** Renders a {@link Projection}. Holds no ontology state of its own. */
public class SchemaGraph extends mxGraph {

    private static final double DEFAULT_W = 160;
    private static final double DEFAULT_H = 60;

    private final Map<String, Object> cellsById = new HashMap<>();

    public SchemaGraph() {
        SchemaStyles.install(this);
        setCellsResizable(true);
        setAllowDanglingEdges(false);
        setAllowLoops(false);
        setCellsDisconnectable(false);
        setEdgeLabelsMovable(false);
        setCellsMovable(true);
        // Both default to false in mxGraph. With the hierarchical layout's routing turned on
        // (CanvasLayouts) every edge carries absolute control points, so a dragged node otherwise
        // keeps the channel the layout gave it and its wire doubles back on itself. resetEdge nulls
        // those points and the router runs again.
        //
        // The trade-off, stated rather than hidden: a bend somebody added by hand is lost when
        // either end moves. That is already true across a reload, because CanvasLayout persists node
        // geometry and has never persisted an edge waypoint.
        setResetEdgesOnMove(true);
        setResetEdgesOnResize(true);
        // Label editing arrives in Task 3; enabling it now would let a user rename a
        // cell's visible text without touching the ontology, which would be a lie.
        setCellsEditable(false);
        setDropEnabled(false);
        setSplitEnabled(false);
    }

    public void render(Projection projection, CanvasLayout layout) {
        PrefixColours colours = new PrefixColours(layout.prefixColors);
        getModel().beginUpdate();
        try {
            removeCells(mxGraphModel.getChildren(getModel(), getDefaultParent()), true);
            cellsById.clear();
            tooltipsById.clear();
            notedIds.clear();

            // Node labels, so an edge's tooltip can name its two ends the way they are drawn
            // rather than by IRI. Built before the edges are inserted because an edge tooltip
            // needs both endpoints and the insertion order puts frames first.
            Map<String, String> labels = new HashMap<String, String>();
            for (CanvasNode node : projection.getNodes()) {
                labels.put(node.getId(), node.getLabel());
            }

            // Frames first, so they sit behind the nodes they group. mxGraph paints in insertion
            // order and a frame drawn afterwards would cover everything inside it.
            for (CanvasLayout.FrameLayout frame : layout.frames) {
                if (frame == null || frame.id == null) {
                    continue;
                }
                Object cell = insertVertex(getDefaultParent(), frame.id,
                        frame.label == null ? "" : frame.label,
                        frame.x, frame.y, frame.w <= 0 ? 320 : frame.w,
                        frame.h <= 0 ? 220 : frame.h,
                        SchemaStyles.FRAME + ";strokeColor="
                                + (frame.stroke == null ? "#2D6FBF" : frame.stroke));
                cellsById.put(frame.id, cell);
                tooltipsById.put(frame.id, CanvasTooltips.forFrame(frame.label));
            }

            double nextX = 40;
            for (CanvasNode node : projection.getNodes()) {
                CanvasLayout.NodeLayout stored = layout.nodes.get(node.getId());
                double x = stored != null ? stored.x : nextX;
                double y = stored != null ? stored.y : 40;
                double w = stored != null ? stored.w : DEFAULT_W;
                double h = stored != null ? stored.h : DEFAULT_H;
                if (stored == null) {
                    nextX += DEFAULT_W + 40;
                }
                Object cell = insertVertex(getDefaultParent(), node.getId(), node.getLabel(),
                        x, y, w, h, styleFor(node, colours));
                cellsById.put(node.getId(), cell);
                tooltipsById.put(node.getId(), CanvasTooltips.forNode(node));
                if (node.hasNote()) {
                    notedIds.add(node.getId());
                }
            }

            for (CanvasEdge edge : projection.getEdges()) {
                Object source = cellsById.get(edge.getSourceId());
                Object target = cellsById.get(edge.getTargetId());
                if (source == null || target == null) {
                    continue; // spec section 5.1: both endpoints must be on the canvas
                }
                Object cell = insertEdge(getDefaultParent(), edge.getId(), edge.getLabel(),
                        source, target, styleFor(edge));
                cellsById.put(edge.getId(), cell);
                tooltipsById.put(edge.getId(), CanvasTooltips.forEdge(edge, labels));
            }
            // Sticky notes last, so they are never hidden behind a node - the whole point of one
            // is that somebody reads it.
            for (CanvasLayout.NoteLayout note : layout.notes) {
                if (note == null || note.id == null) {
                    continue;
                }
                Object cell = insertVertex(getDefaultParent(), note.id,
                        note.text == null ? "" : note.text,
                        note.x, note.y, note.w <= 0 ? 180 : note.w, note.h <= 0 ? 120 : note.h,
                        SchemaStyles.STICKY_NOTE + ";fillColor="
                                + (note.color == null ? "#FFF3B0" : note.color));
                cellsById.put(note.id, cell);
                tooltipsById.put(note.id, CanvasTooltips.forNote(note.text));
            }
        } finally {
            getModel().endUpdate();
        }
    }

    /**
     * Whether this id belongs to a sticky note or a frame rather than to a term.
     *
     * <p>The canvas keys everything by id, and a note's id is a generated one rather than an IRI.
     * Anything that treats a cell as a term - selection, axiom removal, expanding neighbours -
     * has to be able to tell them apart, and asking the ontology would say "not found" for both a
     * note and a term that has been deleted.
     */
    public static boolean isAnnotationId(String id) {
        return id != null && (id.startsWith(NOTE_ID_PREFIX) || id.startsWith(FRAME_ID_PREFIX));
    }

    /** Prefixes that make a canvas annotation recognisable by its id alone. */
    public static final String NOTE_ID_PREFIX = "ontoboard-note-";
    public static final String FRAME_ID_PREFIX = "ontoboard-frame-";

    /** Cell id to the tooltip {@link #render} worked out for it. Cleared and rebuilt with the
     * cells, so it can never describe a cell that is no longer there. */
    private final Map<String, String> tooltipsById = new HashMap<String, String>();

    /**
     * Which drawn terms carry an editorial note, for {@link NoteBadgeLayer}.
     *
     * <p>Filled during {@code render} from the projection, like the tooltips, rather than read from the
     * ontology at paint time. Paint runs on every scroll and hover; a lookup per node per repaint would
     * put the OWL API on the paint path, and a badge that disagreed with the tooltip beside it would be
     * worse than either.
     */
    private final java.util.Set<String> notedIds = new java.util.LinkedHashSet<String>();

    /** The terms drawn with a note, in the order they were drawn. */
    public java.util.Set<String> getNotedIds() {
        return java.util.Collections.unmodifiableSet(notedIds);
    }

    public Object getCellForId(String id) {
        return id == null ? null : cellsById.get(id);
    }

    public String getIdForCell(Object cell) {
        return cell instanceof mxCell ? ((mxCell) cell).getId() : null;
    }

    /**
     * What the cell is, in words, from the tooltip {@link #render} built for it.
     *
     * <p>This used to return the cell's id. For a node that is its IRI, which is true but partial;
     * for everything else it was an internal string, so hovering an arrow gave
     * {@code rest|some|http://…#Pizza|http://…#hasTopping|http://…#PizzaTopping} and hovering a
     * sticky note gave {@code ontoboard-note-3f2a1b9c}. See {@link CanvasTooltips} for what they say
     * now.
     *
     * <p>Falls back to the id, which keeps a cell inserted by something other than {@code render}
     * from having no tooltip at all, and then to mxGraph's own behaviour for a cell with no id.
     */
    @Override
    public String getToolTipForCell(Object cell) {
        String id = getIdForCell(cell);
        if (id == null) {
            return super.getToolTipForCell(cell);
        }
        String tooltip = tooltipsById.get(id);
        return tooltip != null ? tooltip : id;
    }

    /**
     * The style a node would be drawn with, for a test.
     *
     * <p>Rendering needs a live mxGraph; the decision about which marker wins does not, and it is
     * the part that can be wrong in a way nobody notices.
     */
    static String styleForTesting(CanvasNode node) {
        return styleFor(node, new PrefixColours(new java.util.HashMap<String, String>()));
    }

    /**
     * Appends an inline {@code strokeColor} override to the named style, so every namespace
     * gets a distinguishable outline while the shape still says what kind of thing it is.
     * mxGraph reads {@code "styleName;key=value"} as style-plus-overrides.
     */
    private static String styleFor(CanvasNode node, PrefixColours colours) {
        // Border WEIGHT for a note, because every other channel is taken and says something
        // else: the shape says what kind of thing it is, the stroke colour says which namespace
        // it came from, and a dash would read as "inferred", which is what dashed edges mean two
        // lines down. Weight is the one free channel, and it reads as emphasis rather than as a
        // different kind of thing - which is right, since a note does not change what the term is.
        // An unsatisfiable class takes the namespace colour's channel. Which vocabulary a term
        // came from stops mattering the moment the reasoner says it can have no instances, and a
        // modelling error visible only to somebody who knows which shade of blue to look for is
        // not visible.
        String stroke = node.isUnsatisfiable()
                ? SchemaStyles.UNSATISFIABLE_STROKE : colours.colourFor(node.getId());
        return baseStyleFor(node) + ";strokeColor=" + stroke
                + (node.hasNote() || node.isUnsatisfiable()
                        ? ";strokeWidth=" + SchemaStyles.NOTED_STROKE_WIDTH : "")
                // Opacity for an imported term - the one channel the four above leave free, and the
                // only one that also survives a PNG or SVG export.
                + (node.isImported() ? ";opacity=" + SchemaStyles.IMPORTED_OPACITY : "");
    }

    private static String baseStyleFor(CanvasNode node) {
        switch (node.getKind()) {
            case INDIVIDUAL: return SchemaStyles.INDIVIDUAL;
            case DATATYPE:   return SchemaStyles.DATATYPE;
            case LITERAL:    return SchemaStyles.LITERAL;
            case OBJECT_PROPERTY: return SchemaStyles.OBJECT_PROPERTY_NODE;
            case DATA_PROPERTY:   return SchemaStyles.DATA_PROPERTY_NODE;
            case CLASS:
            default:         return SchemaStyles.CLASS;
        }
    }

    private static String styleFor(CanvasEdge edge) {
        switch (edge.getKind()) {
            case SUBCLASS:        return SchemaStyles.SUBCLASS;
            case DATA_PROPERTY:   return SchemaStyles.DATA_PROPERTY;
            case TYPE:            return SchemaStyles.TYPE;
            case SUB_PROPERTY:    return SchemaStyles.SUB_PROPERTY;
            case INFERRED_SUBCLASS: return SchemaStyles.INFERRED_SUBCLASS;
            case INFERRED_TYPE:   return SchemaStyles.INFERRED_TYPE;
            case OBJECT_PROPERTY:
            default:              return SchemaStyles.OBJECT_PROPERTY;
        }
    }
}
