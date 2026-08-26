package de.fizkarlsruhe.ise.ontoboard.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canvas presentation state. Deliberately contains no OWL semantics: everything here is
 * about how the diagram looks, never about what the ontology means.
 *
 * <p>Public mutable fields are intentional. This is a Jackson DTO edited in place by the
 * canvas; getters would add noise without adding safety.
 */
public class CanvasLayout {

    public static final int CURRENT_VERSION = 1;

    public int version = CURRENT_VERSION;
    public String ontologyIri;
    public List<String> onCanvas = new ArrayList<>();
    public Map<String, NodeLayout> nodes = new LinkedHashMap<>();
    public List<FrameLayout> frames = new ArrayList<>();
    public List<NoteLayout> notes = new ArrayList<>();
    public Map<String, String> prefixColors = new LinkedHashMap<>();

    /**
     * True when this layout describes {@code candidateOntologyIri}.
     *
     * <p>A sidecar sits beside the ontology <em>file</em> but names the ontology <em>IRI</em>, and
     * those come apart: a repository cloned to another machine, or an ontology whose IRI was
     * corrected, leaves a sidecar keyed to identities that no longer exist. Loading it anyway gives
     * an empty canvas - every stored IRI matches nothing - which is then saved back over the file,
     * destroying the original arrangement without a word.
     *
     * <p>A layout with no recorded IRI is accepted: sidecars written before this was recorded are
     * not worth discarding.
     */
    public boolean belongsTo(String candidateOntologyIri) {
        if (ontologyIri == null || ontologyIri.trim().isEmpty()) {
            return true;
        }
        return ontologyIri.equals(candidateOntologyIri);
    }

    /**
     * Drops canvas members and positions for entities the ontology no longer declares.
     *
     * <p>A stale entry renders nothing, so it is invisible, and it survives every save - one real
     * project was found carrying a position for a class that did not exist anywhere in its
     * ontology. Pruning on load keeps the file honest about what it describes.
     *
     * @param declaredIris every entity IRI the ontology currently declares
     * @return the IRIs removed, so the caller can say what it cleaned up rather than doing it
     *     silently
     */
    public List<String> pruneMissing(java.util.Set<String> declaredIris) {
        List<String> removed = new ArrayList<>();
        for (String iri : new ArrayList<>(onCanvas)) {
            if (!declaredIris.contains(iri)) {
                onCanvas.remove(iri);
                removed.add(iri);
            }
        }
        // Positions are pruned against the same set rather than against onCanvas, so a position
        // orphaned without a membership entry is cleaned up too.
        for (String iri : new ArrayList<>(nodes.keySet())) {
            if (!declaredIris.contains(iri)) {
                nodes.remove(iri);
                if (!removed.contains(iri)) {
                    removed.add(iri);
                }
            }
        }
        return removed;
    }

    public static class NodeLayout {
        public double x;
        public double y;
        public double w = 160;
        public double h = 60;

        public NodeLayout() {
        }

        public NodeLayout(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    public static class FrameLayout {
        public String id;
        public String label;
        public double x;
        public double y;
        public double w;
        public double h;
        public String fill = "#EEF3FA";
        public String stroke = "#4A90D9";
    }

    public static class NoteLayout {
        public String id;
        public String text;
        public double x;
        public double y;
        public double w = 180;
        public double h = 120;
        public String color = "#FFF3B0";
        public int fontSize = 12;
    }
}
