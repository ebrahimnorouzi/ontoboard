package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * This projection without its SWRL rules: each rule's node, and the lines joining it to the
     * terms it uses and concludes - the Rules layer switched off.
     *
     * <p>A rule's node reads the whole rule, so a board with a dozen rules is mostly rules. The
     * node is found from its lines rather than from its label: an "if" line ends at it and a
     * "then" line starts at it.
     */
    public Projection withoutRules() {
        Set<String> rules = new HashSet<String>();
        for (CanvasEdge edge : edges) {
            if (edge.getKind() == CanvasEdge.Kind.RULE_PART) {
                rules.add("then".equals(edge.getLabel()) ? edge.getSourceId() : edge.getTargetId());
            }
        }
        if (rules.isEmpty()) {
            return this;
        }
        List<CanvasNode> keptNodes = new ArrayList<CanvasNode>();
        for (CanvasNode node : nodes) {
            if (!rules.contains(node.getId())) {
                keptNodes.add(node);
            }
        }
        List<CanvasEdge> keptEdges = new ArrayList<CanvasEdge>();
        for (CanvasEdge edge : edges) {
            if (edge.getKind() != CanvasEdge.Kind.RULE_PART
                    && !rules.contains(edge.getSourceId()) && !rules.contains(edge.getTargetId())) {
                keptEdges.add(edge);
            }
        }
        return new Projection(keptNodes, keptEdges);
    }
}
