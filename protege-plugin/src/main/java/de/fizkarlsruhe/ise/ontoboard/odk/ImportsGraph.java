package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * The {@code owl:imports} structure of a project, as a graph rather than a list.
 *
 * <p>OntoBoard already has this information in a table - <em>Project &rarr; Imports&hellip;</em> -
 * and that table says more per row than this does: what each import resolved to, which release
 * it was cut from. But it lists only the active ontology's <em>direct</em> imports, one row each,
 * and an ODK project's import structure is a shape. Five modules importing one mirror that
 * imports upstream is one picture and twelve rows, and the rows do not say that the mirror is
 * shared.
 *
 * <p>A pure model over the manager's closure: no Swing, no layout, no drawing. What can be wrong
 * here is which ontology imports which, whether a cycle was followed forever, and whether an
 * unresolved import is shown at all - and all three are testable without a display.
 */
public final class ImportsGraph {

    /** One ontology in the structure. */
    public static final class Node {
        private final IRI iri;
        private final boolean root;
        private final boolean resolved;
        private final int depth;

        Node(IRI iri, boolean root, boolean resolved, int depth) {
            this.iri = iri;
            this.root = root;
            this.resolved = resolved;
            this.depth = depth;
        }

        public IRI getIri() {
            return iri;
        }

        /** The ontology the graph was built from. */
        public boolean isRoot() {
            return root;
        }

        /**
         * Whether the manager has this ontology.
         *
         * <p>An unresolved import is a node here rather than an omission. Leaving it out would
         * draw a project as though it imported less than it declares, which is the opposite of
         * what somebody opening an imports graph is trying to find out.
         */
        public boolean isResolved() {
            return resolved;
        }

        /** How many imports away from the root, by the shortest path. */
        public int getDepth() {
            return depth;
        }

        /** The last meaningful segment of the IRI, for a label that fits in a box. */
        public String getShortName() {
            return shortNameOf(iri);
        }

        @Override
        public String toString() {
            return getShortName() + (resolved ? "" : " (unresolved)");
        }
    }

    /** One {@code owl:imports}, from importer to imported. */
    public static final class Edge {
        private final IRI from;
        private final IRI to;

        Edge(IRI from, IRI to) {
            this.from = from;
            this.to = to;
        }

        public IRI getFrom() {
            return from;
        }

        public IRI getTo() {
            return to;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Edge)) {
                return false;
            }
            Edge that = (Edge) other;
            return from.equals(that.from) && to.equals(that.to);
        }

        @Override
        public int hashCode() {
            return from.hashCode() * 31 + to.hashCode();
        }

        @Override
        public String toString() {
            return shortNameOf(from) + " -> " + shortNameOf(to);
        }
    }

    private final List<Node> nodes;
    private final List<Edge> edges;
    private final List<IRI> unresolved;

    private ImportsGraph(List<Node> nodes, List<Edge> edges, List<IRI> unresolved) {
        this.nodes = Collections.unmodifiableList(nodes);
        this.edges = Collections.unmodifiableList(edges);
        this.unresolved = Collections.unmodifiableList(unresolved);
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public List<Edge> getEdges() {
        return edges;
    }

    /** The imports nothing could be found for. */
    public List<IRI> getUnresolved() {
        return unresolved;
    }

    public boolean isEmpty() {
        return edges.isEmpty();
    }

    /**
     * How many ontologies import each one, so a shared module can be pointed out.
     *
     * <p>The thing the table cannot show. A mirror imported by five modules is the structural
     * fact worth knowing about an ODK project, and in a list of direct imports it is invisible.
     */
    public Map<IRI, Integer> importerCounts() {
        Map<IRI, Integer> counts = new LinkedHashMap<IRI, Integer>();
        for (Edge edge : edges) {
            Integer so_far = counts.get(edge.getTo());
            counts.put(edge.getTo(), so_far == null ? 1 : so_far + 1);
        }
        return Collections.unmodifiableMap(counts);
    }

    /** The ontologies more than one other ontology imports. */
    public List<IRI> shared() {
        List<IRI> shared = new ArrayList<IRI>();
        for (Map.Entry<IRI, Integer> entry : importerCounts().entrySet()) {
            if (entry.getValue() > 1) {
                shared.add(entry.getKey());
            }
        }
        return Collections.unmodifiableList(shared);
    }

    /**
     * Builds the graph by walking the imports closure breadth first.
     *
     * <p>Breadth first, so {@code depth} is the shortest path rather than whichever route the
     * walk happened to take. Visited ontologies are never expanded twice, which is also what
     * stops an import cycle from running forever - OWL permits one, and a project that has
     * grown an accidental cycle is exactly the project whose owner would open this.
     */
    public static ImportsGraph of(OWLOntology ontology, OWLOntologyManager manager) {
        List<Node> nodes = new ArrayList<Node>();
        Set<Edge> edges = new LinkedHashSet<Edge>();
        List<IRI> unresolved = new ArrayList<IRI>();
        if (ontology == null) {
            return new ImportsGraph(nodes, new ArrayList<Edge>(), unresolved);
        }
        IRI rootIri = iriOf(ontology);
        Set<IRI> seen = new LinkedHashSet<IRI>();
        Deque<OWLOntology> queue = new ArrayDeque<OWLOntology>();
        Map<IRI, Integer> depths = new LinkedHashMap<IRI, Integer>();

        queue.add(ontology);
        seen.add(rootIri);
        depths.put(rootIri, 0);
        nodes.add(new Node(rootIri, true, true, 0));

        while (!queue.isEmpty()) {
            OWLOntology current = queue.removeFirst();
            IRI currentIri = iriOf(current);
            int depth = depths.containsKey(currentIri) ? depths.get(currentIri) : 0;
            for (OWLImportsDeclaration declaration : current.getImportsDeclarations()) {
                IRI target = declaration.getIRI();
                edges.add(new Edge(currentIri, target));
                if (seen.contains(target)) {
                    continue;
                }
                seen.add(target);
                depths.put(target, depth + 1);
                OWLOntology imported = manager == null ? null
                        : manager.getImportedOntology(declaration);
                nodes.add(new Node(target, false, imported != null, depth + 1));
                if (imported == null) {
                    unresolved.add(target);
                } else {
                    queue.addLast(imported);
                }
            }
        }
        return new ImportsGraph(nodes, new ArrayList<Edge>(edges), unresolved);
    }

    /**
     * The graph as GraphViz DOT, for a layout engine somebody else already has.
     *
     * <p>The same reasoning as the canvas's DOT export: a picture is useful, and a picture
     * somebody can drop into their own pipeline is more useful still.
     */
    public String toDot() {
        Map<IRI, String> names = new LinkedHashMap<IRI, String>();
        StringBuilder dot = new StringBuilder("digraph imports {\n");
        dot.append("  rankdir=TB;\n");
        dot.append("  node [shape=box, style=rounded, fontname=\"Helvetica\"];\n");
        int at = 0;
        for (Node node : nodes) {
            String name = "n" + at++;
            names.put(node.getIri(), name);
            dot.append("  ").append(name).append(" [label=")
               .append(quote(node.getShortName()))
               .append(", tooltip=").append(quote(node.getIri().toString()));
            if (node.isRoot()) {
                dot.append(", style=\"rounded,filled\", fillcolor=\"#DCE9F7\"");
            } else if (!node.isResolved()) {
                dot.append(", color=\"#B00020\", fontcolor=\"#B00020\", style=\"rounded,dashed\"");
            }
            dot.append("];\n");
        }
        for (Edge edge : edges) {
            String from = names.get(edge.getFrom());
            String to = names.get(edge.getTo());
            if (from != null && to != null) {
                dot.append("  ").append(from).append(" -> ").append(to).append(";\n");
            }
        }
        return dot.append("}\n").toString();
    }

    /** Backslashes before quotes, or a label containing one would end the string early. */
    static String quote(String text) {
        return "\"" + (text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\""))
                + "\"";
    }

    /**
     * A readable name for an ontology IRI.
     *
     * <p>The last segment, minus a file extension, and skipping a trailing slash. An OBO import
     * is {@code .../obo/mwo/imports/chebi_import.owl}, and {@code chebi_import} is what somebody
     * is looking for in a box three centimetres wide.
     */
    static String shortNameOf(IRI iri) {
        if (iri == null) {
            return "(no IRI)";
        }
        String text = iri.toString();
        while (text.endsWith("/") || text.endsWith("#")) {
            text = text.substring(0, text.length() - 1);
        }
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('#'));
        String last = slash < 0 ? text : text.substring(slash + 1);
        for (String extension : new String[] {".owl", ".ttl", ".obo", ".rdf", ".omn", ".ofn"}) {
            if (last.toLowerCase(java.util.Locale.ROOT).endsWith(extension)) {
                last = last.substring(0, last.length() - extension.length());
                break;
            }
        }
        return last.isEmpty() ? text : last;
    }

    /** An ontology's IRI, or a stand-in for an anonymous one so it can still be a node. */
    private static IRI iriOf(OWLOntology ontology) {
        com.google.common.base.Optional<IRI> iri = ontology.getOntologyID().getOntologyIRI();
        return iri.isPresent() ? iri.get()
                : IRI.create("urn:ontoboard:anonymous:" + System.identityHashCode(ontology));
    }
}
