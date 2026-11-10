package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A property beside its class inside the pattern's frame the class is in, and the frame holds it.
 *
 * <p>Found by the maintainer on the pizza ontologies (1.135.1): every property stood in a section
 * below the frames, joined to nothing until it was clicked. The class with its properties is one
 * block for the frame's hierarchy, so the frame grows to hold them.
 */
class PropertyBesideItsClassInAFrameTest {

    private static Map<String, double[]> sizes(String... ids) {
        Map<String, double[]> sizes = new HashMap<String, double[]>();
        for (String id : ids) {
            sizes.put(id, new double[] {160, 40});
        }
        return sizes;
    }

    private static Map<String, String> names(String... ids) {
        Map<String, String> names = new HashMap<String, String>();
        for (String id : ids) {
            names.put(id, id);
        }
        return names;
    }

    private static String where(FramedGroup.Placement placement, String id) {
        CanvasLayout.NodeLayout node = placement.getNodes().get(id);
        return node == null ? "not placed"
                : node.x + "," + node.y + " " + node.w + "x" + node.h;
    }

    private static String frame(FramedGroup.Placement placement) {
        CanvasLayout.FrameLayout frame = placement.getFrame();
        return frame.x + "," + frame.y + " " + frame.w + "x" + frame.h;
    }

    /** A hierarchy: B and C under A, has part beside B; the frame as wide as B's block. */
    @Test
    void inAHierarchyThePropertyStandsBesideItsClassInsideTheFrame() {
        Map<String, List<PropertiesBeside.Item>> beside =
                new LinkedHashMap<String, List<PropertiesBeside.Item>>();
        beside.put("B", Collections.singletonList(new PropertiesBeside.Item("has part",
                BoardSections.Kind.OBJECT_PROPERTY, "has part", 200, 40)));
        Map<String, String> parents = new HashMap<String, String>();
        parents.put("B", "A");
        parents.put("C", "A");

        FramedGroup.Placement placement = FramedGroup.plan("Pattern", Arrays.asList("A", "B", "C"),
                new CanvasLayout(), sizes("A", "B", "C"), parents, names("A", "B", "C"), beside);

        // A at the frame's inside, 60 + 40 and 60 + 40 + 40; B and C under it, 40 in, B first.
        assertEquals("100.0,140.0 160.0x40.0", where(placement, "A"));
        assertEquals("140.0,220.0 160.0x40.0", where(placement, "B"));
        assertEquals("140.0,280.0 160.0x40.0", where(placement, "C"));
        assertEquals("380.0,220.0 200.0x40.0", where(placement, "has part"));
        // B's block is 160 + 80 + 200 wide, 40 in: the frame 480 wide inside, 40 round it.
        assertEquals("60.0,60.0 560.0x300.0", frame(placement));
    }

    /** No hierarchy, a grid: A's column as wide as A with its two properties beside it. */
    @Test
    void inAGridThePropertiesStandBesideTheirClassAndTheColumnMakesRoom() {
        Map<String, List<PropertiesBeside.Item>> beside =
                new LinkedHashMap<String, List<PropertiesBeside.Item>>();
        beside.put("A", Arrays.asList(
                new PropertiesBeside.Item("q", BoardSections.Kind.OBJECT_PROPERTY, "q", 160, 40),
                new PropertiesBeside.Item("p", BoardSections.Kind.OBJECT_PROPERTY, "p", 200, 40)));

        FramedGroup.Placement placement = FramedGroup.plan("Pattern", Arrays.asList("A", "B"),
                new CanvasLayout(), sizes("A", "B"), Collections.<String, String>emptyMap(),
                names("A", "B"), beside);

        assertEquals("100.0,140.0 160.0x40.0", where(placement, "A"));
        assertEquals("340.0,140.0 200.0x40.0", where(placement, "p"));
        assertEquals("340.0,196.0 160.0x40.0", where(placement, "q"));
        // A's column 160 + 80 + 200 wide, then 40, then B.
        assertEquals("580.0,140.0 160.0x40.0", where(placement, "B"));
        assertEquals("60.0,60.0 720.0x220.0", frame(placement));
    }

    /** A class already on the board stays where it is, and what stands beside it stays with it. */
    @Test
    void aClassAlreadyOnTheBoardBringsNoPropertyIntoTheFrame() {
        Map<String, List<PropertiesBeside.Item>> beside =
                new LinkedHashMap<String, List<PropertiesBeside.Item>>();
        beside.put("A", Collections.singletonList(new PropertiesBeside.Item("p",
                BoardSections.Kind.OBJECT_PROPERTY, "p", 200, 40)));
        beside.put("B", Collections.singletonList(new PropertiesBeside.Item("q",
                BoardSections.Kind.OBJECT_PROPERTY, "q", 200, 40)));
        CanvasLayout board = new CanvasLayout();
        board.nodes.put("A", new CanvasLayout.NodeLayout(1000, 1000));

        FramedGroup.Placement placement = FramedGroup.plan("Pattern", Arrays.asList("A", "B"),
                board, sizes("A", "B"), Collections.<String, String>emptyMap(), names("A", "B"),
                beside);

        assertFalse(placement.getNodes().containsKey("p"), "p stays with A, outside the frame");
        // The frame 60 right of A, B at its inside, q 160 + 80 right of B.
        assertEquals("1500.0,1080.0 200.0x40.0", where(placement, "q"));
    }
}
