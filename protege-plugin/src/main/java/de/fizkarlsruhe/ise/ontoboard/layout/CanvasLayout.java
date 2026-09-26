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
     * A copy that shares nothing with this one.
     *
     * <p>For {@link BoardHistory}, which holds the board as it was before each action. Every collection
     * and every nested object is rebuilt: the canvas mutates this DTO in place - positions are written
     * straight into {@code nodes}, a note's text into its {@code NoteLayout} - so a copy that shared any
     * of them would be a history whose entries all silently became the present.
     *
     * <p>Not {@code clone()} and not serialisation through Jackson. A hand-written copy has to be
     * updated when a field is added, which is a small cost with a loud failure mode - a test asserting
     * that no two copies share a reference - where a reflective copy would keep working and quietly
     * start sharing a new field.
     */
    public CanvasLayout copy() {
        CanvasLayout copy = new CanvasLayout();
        copy.version = version;
        copy.ontologyIri = ontologyIri;
        copy.onCanvas = new ArrayList<>(onCanvas);
        copy.nodes = new LinkedHashMap<>();
        for (Map.Entry<String, NodeLayout> entry : nodes.entrySet()) {
            copy.nodes.put(entry.getKey(), entry.getValue() == null
                    ? null : entry.getValue().copy());
        }
        copy.frames = new ArrayList<>();
        for (FrameLayout frame : frames) {
            copy.frames.add(frame == null ? null : frame.copy());
        }
        copy.notes = new ArrayList<>();
        for (NoteLayout note : notes) {
            copy.notes.add(note == null ? null : note.copy());
        }
        copy.prefixColors = new LinkedHashMap<>(prefixColors);
        return copy;
    }

    /**
     * Becomes a copy of {@code other}, in place.
     *
     * <p>In place because the canvas hands this instance to {@code CanvasMembership} and to the
     * projection, and both keep the reference. Replacing the field on the view would leave membership
     * editing the board nobody is drawing - a bug whose symptom is that undo appears to work and the
     * next action puts everything back.
     */
    public void copyFrom(CanvasLayout other) {
        if (other == null) {
            return;
        }
        CanvasLayout snapshot = other.copy();
        version = snapshot.version;
        ontologyIri = snapshot.ontologyIri;
        onCanvas.clear();
        onCanvas.addAll(snapshot.onCanvas);
        nodes.clear();
        nodes.putAll(snapshot.nodes);
        frames.clear();
        frames.addAll(snapshot.frames);
        notes.clear();
        notes.addAll(snapshot.notes);
        prefixColors.clear();
        prefixColors.putAll(snapshot.prefixColors);
    }

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

        NodeLayout copy() {
            NodeLayout copy = new NodeLayout(x, y);
            copy.w = w;
            copy.h = h;
            return copy;
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

        /** A copy that shares nothing, for the history and for Ctrl+D. */
        public FrameLayout copy() {
            FrameLayout copy = new FrameLayout();
            copy.id = id;
            copy.label = label;
            copy.x = x;
            copy.y = y;
            copy.w = w;
            copy.h = h;
            copy.fill = fill;
            copy.stroke = stroke;
            return copy;
        }
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

        /** A copy that shares nothing, for the history and for Ctrl+D. */
        public NoteLayout copy() {
            NoteLayout copy = new NoteLayout();
            copy.id = id;
            copy.text = text;
            copy.x = x;
            copy.y = y;
            copy.w = w;
            copy.h = h;
            copy.color = color;
            copy.fontSize = fontSize;
            return copy;
        }
    }
}
