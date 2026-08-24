package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.Collections;
import java.util.List;

/** What the canvas should currently show. Immutable. */
public final class Projection {

    private final List<CanvasNode> nodes;
    private final List<CanvasEdge> edges;

    public Projection(List<CanvasNode> nodes, List<CanvasEdge> edges) {
        this.nodes = Collections.unmodifiableList(nodes);
        this.edges = Collections.unmodifiableList(edges);
    }

    public List<CanvasNode> getNodes() {
        return nodes;
    }

    public List<CanvasEdge> getEdges() {
        return edges;
    }
}
