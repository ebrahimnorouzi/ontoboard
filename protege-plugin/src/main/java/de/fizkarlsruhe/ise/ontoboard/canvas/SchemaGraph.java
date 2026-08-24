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
    }

    public void render(Projection projection, CanvasLayout layout) {
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
                        x, y, w, h, styleFor(node));
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

    private static String styleFor(CanvasNode node) {
        switch (node.getKind()) {
            case INDIVIDUAL: return SchemaStyles.INDIVIDUAL;
            case DATATYPE:   return SchemaStyles.DATATYPE;
            case LITERAL:    return SchemaStyles.LITERAL;
            case CLASS:
            default:         return SchemaStyles.CLASS;
        }
    }

    private static String styleFor(CanvasEdge edge) {
        switch (edge.getKind()) {
            case SUBCLASS:        return SchemaStyles.SUBCLASS;
            case DATA_PROPERTY:   return SchemaStyles.DATA_PROPERTY;
            case TYPE:            return SchemaStyles.TYPE;
            case OBJECT_PROPERTY:
            default:              return SchemaStyles.OBJECT_PROPERTY;
        }
    }
}
