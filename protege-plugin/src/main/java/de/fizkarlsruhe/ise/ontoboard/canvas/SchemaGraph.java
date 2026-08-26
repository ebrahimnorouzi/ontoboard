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
            }
        } finally {
            getModel().endUpdate();
        }
    }

    public Object getCellForId(String id) {
        return id == null ? null : cellsById.get(id);
    }

    public String getIdForCell(Object cell) {
        return cell instanceof mxCell ? ((mxCell) cell).getId() : null;
    }

    /**
     * Shows the full entity IRI on hover. Cell ids are the IRI (see {@link #render}, which
     * passes {@code node.getId()} / {@code edge.getId()} as the vertex/edge id), while the
     * visible label ({@link #convertValueToString}) is deliberately kept short. Falls back
     * to the default behaviour for anything without an id, e.g. {@code null}.
     */
    @Override
    public String getToolTipForCell(Object cell) {
        String id = getIdForCell(cell);
        return id != null ? id : super.getToolTipForCell(cell);
    }

    /**
     * Appends an inline {@code strokeColor} override to the named style, so every namespace
     * gets a distinguishable outline while the shape still says what kind of thing it is.
     * mxGraph reads {@code "styleName;key=value"} as style-plus-overrides.
     */
    private static String styleFor(CanvasNode node, PrefixColours colours) {
        return baseStyleFor(node) + ";strokeColor=" + colours.colourFor(node.getId());
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
            case OBJECT_PROPERTY:
            default:              return SchemaStyles.OBJECT_PROPERTY;
        }
    }
}
