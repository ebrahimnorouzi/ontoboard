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
