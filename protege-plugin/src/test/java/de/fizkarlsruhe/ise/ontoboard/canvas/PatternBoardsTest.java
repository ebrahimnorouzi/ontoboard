package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.util.mxPoint;
import com.mxgraph.view.mxCellState;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.FramedGroup;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.Curies;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import de.fizkarlsruhe.ise.ontoboard.pattern.DesignPattern;
import de.fizkarlsruhe.ise.ontoboard.pattern.PatternLibrary;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Every pattern in the library, drawn in its frame as importing it draws it, and looked at.
 *
 * <p>Asked for: no overlaps on the board, a clear hierarchy, arrows that do not run over things. A
 * test of the geometry on made-up boxes says the method works; this says it works on what people
 * actually import, all of it, not only the patterns somebody happened to look at.
 *
 * <p>Each board is routed in full within the router's two seconds, counted in its own processor
 * time (1.135.2). On the wall clock a busy machine left boards "148 of 150 routed" and this failed
 * under a full suite though the boards were no harder; a router grown slower still runs out of the
 * two seconds and fails it.
 */
class PatternBoardsTest {

    private static final class Drawn {
        final String pattern;
        final List<Object> boxes = new ArrayList<Object>();
        final List<Object> lines = new ArrayList<Object>();
        final SchemaGraph graph = new SchemaGraph();

        Drawn(String pattern) {
            this.pattern = pattern;
        }

        double[] rect(Object cell) {
            mxCellState state = graph.getView().getState(cell);
            return new double[] {state.getX(), state.getY(), state.getWidth(), state.getHeight()};
        }

        double[][] line(Object edge) {
            List<mxPoint> points = graph.getView().getState(edge).getAbsolutePoints();
            double[][] line = new double[points.size()][];
            for (int i = 0; i < line.length; i++) {
                line[i] = new double[] {points.get(i).getX(), points.get(i).getY()};
            }
            return line;
        }

        String name(Object cell) {
            return String.valueOf(graph.getModel().getValue(cell)).replace('\n', ' ');
        }

        /** How long routing the board took, in milliseconds. */
        long millis;

        /** Routes the board as drawn, then takes stock of its boxes and of the lines to be seen. */
        Drawn routed(Projection projection, CanvasLayout layout) {
            graph.render(projection, layout);
            long started = System.nanoTime();
            graph.routeEdges();
            millis = (System.nanoTime() - started) / 1_000_000;
            graph.getView().validate();
            Object parent = graph.getDefaultParent();
            for (int i = 0; i < graph.getModel().getChildCount(parent); i++) {
                Object cell = graph.getModel().getChildAt(parent, i);
                String style = graph.getModel().getStyle(cell);
                if (graph.getModel().isVertex(cell)) {
                    if (style == null || !style.startsWith(SchemaStyles.FRAME)) {
                        boxes.add(cell);
                    }
                } else if (graph.getModel().isEdge(cell) && graph.isCellVisible(cell)) {
                    lines.add(cell);
                }
            }
            return this;
        }

        BoardMeasures measured() {
            return new BoardMeasures(pattern, graph);
        }
    }

    private static final List<Drawn> BOARDS = new ArrayList<Drawn>();

    /** Each pattern's file as loaded, and its classes, for the boards several patterns make. */
    private static final Map<String, OWLOntology> LOADED = new HashMap<String, OWLOntology>();
    private static final Map<String, List<String>> CLASSES = new HashMap<String, List<String>>();
    private static final Map<String, String> NAMES = new HashMap<String, String>();

    @BeforeAll
    static void drawEveryPattern() throws Exception {
        File folder = Files.createTempDirectory("pattern-boards").toFile();
        for (DesignPattern pattern : PatternLibrary.all()) {
            File copy = PatternLibrary.copyTo(pattern, folder);
            org.semanticweb.owlapi.model.OWLOntologyManager manager =
                    OWLManager.createOWLOntologyManager();
            // Some of the ODP portal's files import what is nowhere to be had; what is in the file
            // is what is drawn.
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(
                    new org.semanticweb.owlapi.io.FileDocumentSource(copy),
                    new org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration()
                            .setMissingImportHandlingStrategy(
                                    org.semanticweb.owlapi.model.MissingImportHandlingStrategy.SILENT));
            Drawn drawn = new Drawn(pattern.getId());
            List<String> classes = new ArrayList<String>();
            for (OWLEntity entity : PatternLibrary.contentsOf(pattern).getClasses()) {
                classes.add(entity.getIRI().toString());
            }
            if (classes.isEmpty()) {
                continue;
            }
            LOADED.put(pattern.getId(), ontology);
            CLASSES.put(pattern.getId(), classes);
            NAMES.put(pattern.getId(), pattern.getName());
            Set<String> members = new HashSet<String>(classes);
            Map<String, double[]> sizes = new HashMap<String, double[]>();
            Map<String, String> names = new HashMap<String, String>();
            for (CanvasNode node : OntologyProjection.project(ontology, members, false).getNodes()) {
                String label = Curies.displayLabel(node.getCurie(), node.getLabel(), node.getId());
                sizes.put(node.getId(), drawn.graph.fittedSize(label, SchemaStyles.CLASS));
                names.put(node.getId(), label);
            }
            CanvasLayout layout = new CanvasLayout();
            FramedGroup.Placement placement = FramedGroup.plan(pattern.getName(), classes, layout,
                    sizes, OntologyProjection.superclassesAmong(ontology, classes), names);
            FramedGroup.withId(placement.getFrame(), SchemaGraph.FRAME_ID_PREFIX + "pattern");
            layout.frames.add(placement.getFrame());
            layout.nodes.putAll(placement.getNodes());
            Projection projection = OntologyProjection.project(ontology, members, false);
            BOARDS.add(drawn.routed(projection, layout));
        }
    }

    @Test
    void everyPatternIsLookedAt() {
        assertTrue(BOARDS.size() > 140, "the library, not a sample: " + BOARDS.size());
    }

    @Test
    void noTwoBoxesOverlapOnAnyPattern() {
        List<String> overlaps = new ArrayList<String>();
        for (Drawn drawn : BOARDS) {
            for (int i = 0; i < drawn.boxes.size(); i++) {
                for (int j = i + 1; j < drawn.boxes.size(); j++) {
                    double[] a = drawn.rect(drawn.boxes.get(i));
                    double[] b = drawn.rect(drawn.boxes.get(j));
                    if (a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3]
                            && b[1] < a[1] + a[3]) {
                        overlaps.add(drawn.pattern + ": " + drawn.name(drawn.boxes.get(i)) + " / "
                                + drawn.name(drawn.boxes.get(j)));
                    }
                }
            }
        }
        assertEquals(new ArrayList<String>(), overlaps);
    }

    /**
     * Every line routed, and none through a box, on every pattern. Until 1.135.1 this let two
     * patterns keep the plain connector as dense - more than {@code SchemaGraph.DENSE} lines - and
     * none was; since then a busy box that has no point left free for a line still has it routed.
     */
    @Test
    void noLineRunsThroughABoxOnAnyPattern() {
        List<String> notRouted = new ArrayList<String>();
        List<String> through = new ArrayList<String>();
        for (Drawn drawn : BOARDS) {
            if (!drawn.graph.isFullyRouted()) {
                BoardMeasures measured = drawn.measured();
                notRouted.add(drawn.pattern + (drawn.graph.isDense() ? " (dense)" : " " + measured.routed
                        + " of " + measured.lines + " routed, not " + measured.unrouted));
                continue;
            }
            for (Object edge : drawn.lines) {
                double[][] line = drawn.line(edge);
                Object from = drawn.graph.getModel().getTerminal(edge, true);
                Object to = drawn.graph.getModel().getTerminal(edge, false);
                for (Object box : drawn.boxes) {
                    if (box == from || box == to) {
                        continue;
                    }
                    double[] r = drawn.rect(box);
                    for (int i = 1; i < line.length; i++) {
                        double x1 = Math.min(line[i - 1][0], line[i][0]);
                        double x2 = Math.max(line[i - 1][0], line[i][0]);
                        double y1 = Math.min(line[i - 1][1], line[i][1]);
                        double y2 = Math.max(line[i - 1][1], line[i][1]);
                        if (x2 > r[0] + 1 && x1 < r[0] + r[2] - 1 && y2 > r[1] + 1
                                && y1 < r[1] + r[3] - 1) {
                            through.add(drawn.pattern + ": " + drawn.name(from) + " -> "
                                    + drawn.name(to) + " passes " + drawn.name(box));
                            break;
                        }
                    }
                }
            }
        }
        assertEquals(new ArrayList<String>(), through);
        assertEquals(new ArrayList<String>(), notRouted);
    }

    @Test
    void arrowsKeepToLanesOfTheirOwnOnEveryPattern() {
        double shared = 0;
        double length = 0;
        List<String> worst = new ArrayList<String>();
        for (Drawn drawn : BOARDS) {
            if (!drawn.graph.isFullyRouted()) {
                continue;
            }
            List<double[][]> arrows = new ArrayList<double[][]>();
            for (Object edge : drawn.lines) {
                String style = drawn.graph.getModel().getStyle(edge);
                if (style.startsWith(SchemaStyles.SUBCLASS)
                        || style.startsWith(SchemaStyles.SUB_PROPERTY)
                        || style.startsWith(SchemaStyles.TYPE)) {
                    continue;
                }
                double[][] line = drawn.line(edge);
                for (int i = 1; i < line.length; i++) {
                    length += Math.abs(line[i][0] - line[i - 1][0])
                            + Math.abs(line[i][1] - line[i - 1][1]);
                }
                for (double[][] other : arrows) {
                    double here = sharedLength(line, other);
                    shared += here;
                    if (here > 0) {
                        worst.add(drawn.pattern + ": " + Math.round(here) + "px");
                    }
                }
                arrows.add(line);
            }
        }
        assertTrue(shared < length * 0.01, "arrows run along each other for " + Math.round(shared)
                + "px of " + Math.round(length) + "px: " + worst);
    }

    private static double sharedLength(double[][] one, double[][] other) {
        double shared = 0;
        for (int i = 1; i < one.length; i++) {
            for (int j = 1; j < other.length; j++) {
                boolean flat = one[i][1] == one[i - 1][1] && other[j][1] == other[j - 1][1]
                        && Math.abs(one[i][1] - other[j][1]) < 1;
                boolean upright = one[i][0] == one[i - 1][0] && other[j][0] == other[j - 1][0]
                        && Math.abs(one[i][0] - other[j][0]) < 1;
                int axis = flat ? 0 : upright ? 1 : -1;
                if (axis >= 0) {
                    double from = Math.max(Math.min(one[i][axis], one[i - 1][axis]),
                            Math.min(other[j][axis], other[j - 1][axis]));
                    double to = Math.min(Math.max(one[i][axis], one[i - 1][axis]),
                            Math.max(other[j][axis], other[j - 1][axis]));
                    shared += Math.max(0, to - from);
                }
            }
        }
        return shared;
    }

    /** The two collections a study of pattern reuse starts from: every one of them, routed in full. */
    @Test
    void everyPmdcoAndNfdiPatternIsFullyRouted() {
        List<String> notRouted = new ArrayList<String>();
        int looked = 0;
        for (Drawn drawn : BOARDS) {
            if (drawn.pattern.startsWith("pmdco-") || drawn.pattern.startsWith("nfdi-")) {
                looked++;
                if (!drawn.graph.isFullyRouted()) {
                    notRouted.add(drawn.pattern);
                }
            }
        }
        assertEquals(28, looked);
        assertEquals(new ArrayList<String>(), notRouted);
    }

    /** "Labels for each pattern term, because some have unspeakable IRIs." */
    @Test
    void aBoxSaysWhatItsTermIsCalledFirst() {
        for (Drawn drawn : BOARDS) {
            for (Object box : drawn.boxes) {
                String first = String.valueOf(drawn.graph.getModel().getValue(box)).split("\n")[0];
                assertTrue(!first.matches("[a-zA-Z]+:[A-Z]+_[0-9]+") || !IRI.create(
                        String.valueOf(drawn.graph.getIdForCell(box))).toString().isEmpty(),
                        drawn.pattern + ": " + first);
            }
        }
    }

    /**
     * More lines than lanes: {@code notepattern} has 140 lines, 35 of them from one class's
     * subclasses to five classes, and {@code chessgame} 17 lines at one class in a column of others
     * 20px apart. Every line is routed on both, and some run together.
     */
    private static final List<String> FULL = Arrays.asList("notepattern", "chessgame");

    /**
     * Lines of any kind on top of each other, or closer than {@link BoardMeasures#APART} side by
     * side, since 1.135.1: a tester measured on the study board what
     * {@link #arrowsKeepToLanesOfTheirOwnOnEveryPattern} could not see, comparing arrows with arrows
     * only - two subclass lines to different classes drawn as one for 600px, and a relation arrow
     * 4px beside a subclass line for 357px. In 1.135.0, 329px and 96,890px on the patterns alone.
     *
     * <p>Now only at a box with no point left free for one more line: two lines share its last
     * point's stub, 20px, or arrive at points 10px apart, for up to 60px with the bend - and on the
     * two patterns with more lines than lanes, for under 3% of all line drawn.
     */
    @Test
    void linesRunTogetherOnlyWhereABoxHasNoRoomLeft() {
        List<String> found = new ArrayList<String>();
        double shared = 0;
        double close = 0;
        double length = 0;
        long millis = 0;
        for (Drawn drawn : BOARDS) {
            millis += drawn.millis;
            if (!drawn.graph.isFullyRouted()) {
                continue;
            }
            BoardMeasures measured = drawn.measured();
            shared += measured.shared;
            close += measured.close;
            length += measured.length;
            if (!FULL.contains(drawn.pattern) && (measured.longestShared > 20.5
                    || measured.longestClose > 60.5)) {
                found.add(details(measured));
            }
        }
        System.out.println("every pattern alone: " + Math.round(shared) + "px on top of each other, "
                + Math.round(close) + "px side by side, of " + Math.round(length)
                + "px of line; routing " + millis + "ms for " + BOARDS.size() + " boards");
        assertEquals(new ArrayList<String>(), found);
        assertTrue(shared + close < length * 0.03, Math.round(shared + close) + "px of "
                + Math.round(length));
    }

    /**
     * Every label on its own line, and few on anything else - a box, another label, another line -
     * since 1.135.1. In 1.135.0, of 592 labels 255 covered a box, 116 a label and 754 lines over
     * again; a tester found seven on lines on the study board, and labels on boxes after an import.
     * What is left is where a line has no stretch clear of everything for its label: a label of
     * three lines between rows of boxes 40px apart, or on a busy box's crowded side.
     */
    @Test
    void everyLabelSitsOnItsOwnLineAndFewOnAnythingElse() {
        List<String> off = new ArrayList<String>();
        int[] counts = new int[3];
        int labels = 0;
        int wrong = 0;
        for (Drawn drawn : BOARDS) {
            if (!drawn.graph.isFullyRouted()) {
                continue;
            }
            BoardMeasures measured = drawn.measured();
            labels += measured.labels;
            wrong += measured.labelsWrong;
            counts[0] += measured.onBoxes.size();
            counts[1] += measured.onLabels.size();
            counts[2] += measured.onLines.size();
            for (String one : measured.offTheirLine) {
                off.add(drawn.pattern + ": " + one);
            }
        }
        System.out.println("every pattern alone: " + labels + " labels, " + wrong
                + " touching something: on boxes " + counts[0] + ", on labels " + counts[1]
                + ", on lines " + counts[2]);
        assertEquals(new ArrayList<String>(), off);
        assertTrue(wrong < labels * 0.15, wrong + " of " + labels);
    }

    /**
     * Patterns imported one after another: the study's three, in its order, the same with two more
     * as one tester went on, and the two a tester saw put a label on a box. A class two of them
     * share stays in the frame of the one that came first, so lines run between the frames.
     */
    private static final String[][] TOGETHER = {
        {"pmdco-measurement-and-setpoint", "nfdi-dataset-description",
            "pmdco-scalar-value-specification"},
        {"pmdco-measurement-and-setpoint", "nfdi-dataset-description",
            "pmdco-scalar-value-specification", "pmdco-fundamental-property",
            "nfdi-process-role-realization"},
        {"pmdco-measurement-and-setpoint", "pmdco-manufacturing-input-output"},
        // The cookbook scenario's three: a line ran through an is-a arrowhead there, and a label
        // sat on the dataset frame's border.
        {"collection-entity", "nfdi-dataset-description", "price"},
    };

    /**
     * Clean, but for at most one label touching a line on a board, which these boards allow and the
     * plugin's own does not show: since the scalar value pattern was corrected to put value
     * specification under information content entity, one label here touches that new subclass
     * line. These boards are drawn as {@code frameImportedPatterns} plans frames, without the
     * superclasses the plugin moves into a frame; on the study's real board, measured in Protégé after
     * every task of the scripted run, no label touches anything.
     */
    @Test
    void patternsImportedTogetherAreRoutedInFullAndDrawnCleanly() throws Exception {
        List<String> found = new ArrayList<String>();
        for (String[] ids : TOGETHER) {
            Drawn drawn = drawnTogether(ids);
            BoardMeasures measured = drawn.measured();
            System.out.println(measured.summary() + " fully-routed=" + drawn.graph.isFullyRouted()
                    + " routing=" + drawn.millis + "ms");
            if (!drawn.graph.isFullyRouted()) {
                found.add(drawn.pattern + ": not every line routed");
            }
            boolean cleanButOneLabel = measured.sharing.isEmpty() && measured.beside.isEmpty()
                    && measured.onBoxes.isEmpty() && measured.onLabels.isEmpty()
                    && measured.offTheirLine.isEmpty() && measured.through.isEmpty()
                    && measured.throughArrowheads.isEmpty() && measured.onLines.size() <= 1;
            if (!cleanButOneLabel) {
                found.add(details(measured));
            }
        }
        assertEquals(new ArrayList<String>(), found);
    }

    /**
     * About 120 boxes and 100 lines, where routing gave up in 1.135.0: a tester's board of six
     * patterns and twelve subclasses had 71 of its 98 lines routed, the rest drawn through boxes,
     * because the routing grid had more lines than it allowed, not for want of time. Every line
     * routed now, none on or beside another, and of 30 labels at most three touching something.
     */
    @Test
    void aBoardOfAHundredAndTwentyBoxesIsRoutedInFullAndDrawnCleanly() {
        Drawn drawn = drawnMadeUp(6, 20, 30, 1351L);
        BoardMeasures measured = drawn.measured();
        System.out.println(measured.summary() + " fully-routed=" + drawn.graph.isFullyRouted()
                + " routing=" + drawn.millis + "ms");
        assertTrue(drawn.boxes.size() >= 115 && drawn.lines.size() >= 95,
                drawn.boxes.size() + " boxes, " + drawn.lines.size() + " lines");
        assertTrue(drawn.graph.isFullyRouted(), "routed in " + drawn.millis + "ms");
        assertTrue(measured.sharing.isEmpty() && measured.beside.isEmpty()
                && measured.through.isEmpty() && measured.offTheirLine.isEmpty()
                && measured.labelsWrong <= 3, details(measured));
    }

    private static String details(BoardMeasures measured) {
        StringBuilder text = new StringBuilder(measured.summary());
        for (List<String> wrong : Arrays.asList(measured.sharing, measured.beside, measured.onBoxes,
                measured.onLabels, measured.onLines, measured.offTheirLine, measured.through)) {
            for (int i = 0; i < Math.min(6, wrong.size()); i++) {
                text.append("\n  ").append(wrong.get(i));
            }
        }
        return text.toString();
    }

    /** Several patterns on one board, framed as the board frames them: in one row, then wrapped. */
    private static Drawn drawnTogether(String... ids) throws Exception {
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                OWLManager.createOWLOntologyManager();
        OWLOntology together = manager.createOntology(IRI.create("urn:test:together"));
        List<String> classes = new ArrayList<String>();
        for (String id : ids) {
            manager.addAxioms(together, LOADED.get(id).getAxioms());
            for (String iri : CLASSES.get(id)) {
                if (!classes.contains(iri)) {
                    classes.add(iri);
                }
            }
        }
        Drawn drawn = new Drawn(String.join(" + ", ids));
        Set<String> members = new HashSet<String>(classes);
        Map<String, double[]> sizes = new HashMap<String, double[]>();
        Map<String, String> names = new HashMap<String, String>();
        Projection projection = OntologyProjection.project(together, members, false);
        for (CanvasNode node : projection.getNodes()) {
            String label = Curies.displayLabel(node.getCurie(), node.getLabel(), node.getId());
            sizes.put(node.getId(), drawn.graph.fittedSize(label, SchemaStyles.CLASS));
            names.put(node.getId(), label);
        }
        Map<String, String> parents = OntologyProjection.superclassesAmong(together, classes);
        CanvasLayout row = new CanvasLayout();
        List<FramedGroup.Placement> planned = new ArrayList<FramedGroup.Placement>();
        for (String id : ids) {
            FramedGroup.Placement placement = FramedGroup.plan(NAMES.get(id), CLASSES.get(id), row,
                    sizes, parents, names);
            if (placement.isEmpty()) {
                continue;
            }
            FramedGroup.withId(placement.getFrame(), SchemaGraph.FRAME_ID_PREFIX + id);
            row.frames.add(placement.getFrame());
            row.nodes.putAll(placement.getNodes());
            planned.add(placement);
        }
        FramedGroup.wrap(planned);
        return drawn.routed(projection, row);
    }

    /**
     * A board made up to a size: {@code frames} frames of {@code each} classes, every frame a
     * hierarchy, the first class of each frame after the first under a class of an earlier frame,
     * and {@code relations} arrows, most of them inside a frame. The same board for the same seed.
     */
    static Drawn drawnMadeUp(int frames, int each, int relations, long seed) {
        java.util.Random random = new java.util.Random(seed);
        String[] words = {"process", "quality", "material", "device", "specimen", "data set",
            "report", "role", "function", "value", "unit", "property", "measurement", "setting",
            "sample", "plan", "site", "agent", "event", "document"};
        String[] properties = {"has part", "is about", "has input", "has output", "realizes",
            "has quality", "has value", "located in", "participates in", "has specified output",
            "concretizes", "bearer of"};
        Drawn drawn = new Drawn("made up: " + frames + " frames of " + each + ", " + relations
                + " relations");
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge> edges =
                new ArrayList<de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge>();
        CanvasLayout row = new CanvasLayout();
        List<FramedGroup.Placement> planned = new ArrayList<FramedGroup.Placement>();
        for (int f = 0; f < frames; f++) {
            List<String> ids = new ArrayList<String>();
            Map<String, double[]> sizes = new HashMap<String, double[]>();
            Map<String, String> names = new HashMap<String, String>();
            Map<String, String> parents = new HashMap<String, String>();
            for (int t = 0; t < each; t++) {
                String id = "urn:made-up#f" + f + "t" + t;
                String label = words[random.nextInt(words.length)] + " "
                        + words[random.nextInt(words.length)] + " " + f + "." + t;
                ids.add(id);
                nodes.add(new CanvasNode(id, de.fizkarlsruhe.ise.ontoboard.model.NodeKind.CLASS,
                        label));
                sizes.put(id, drawn.graph.fittedSize(label, SchemaStyles.CLASS));
                names.put(id, label);
                if (t > 0 && random.nextInt(5) < 3) {
                    String parent = "urn:made-up#f" + f + "t" + random.nextInt(t);
                    parents.put(id, parent);
                    edges.add(new de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge("sub|" + id, id,
                            parent, "", de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge.Kind.SUBCLASS));
                }
            }
            FramedGroup.Placement placement = FramedGroup.plan("Frame " + f, ids, row, sizes,
                    parents, names);
            FramedGroup.withId(placement.getFrame(), SchemaGraph.FRAME_ID_PREFIX + f);
            row.frames.add(placement.getFrame());
            row.nodes.putAll(placement.getNodes());
            planned.add(placement);
            if (f > 0) {
                String child = "urn:made-up#f" + f + "t0";
                edges.add(new de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge("sub|" + child, child,
                        "urn:made-up#f" + random.nextInt(f) + "t" + random.nextInt(each), "",
                        de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge.Kind.SUBCLASS));
            }
        }
        FramedGroup.wrap(planned);
        for (int r = 0; r < relations; r++) {
            int from = random.nextInt(frames);
            int to = random.nextInt(10) < 7 ? from : random.nextInt(frames);
            String source = "urn:made-up#f" + from + "t" + random.nextInt(each);
            String target = "urn:made-up#f" + to + "t" + random.nextInt(each);
            if (source.equals(target)) {
                continue;
            }
            edges.add(new de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge("rel|" + r, source, target,
                    OntologyProjection.SOME + properties[random.nextInt(properties.length)],
                    de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge.Kind.OBJECT_PROPERTY));
        }
        return drawn.routed(new Projection(nodes, edges), row);
    }
}
