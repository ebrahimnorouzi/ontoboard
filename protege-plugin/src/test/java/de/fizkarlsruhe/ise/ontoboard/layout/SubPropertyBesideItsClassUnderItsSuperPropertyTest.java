package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Two properties beside one class, one a sub-property of the other: the super-property stands
 * above, as in a property's section, whatever their labels say.
 *
 * <p>Found on pizza-bfo arranged by the beside build of 1.135.1: "inheres in" and "specifically
 * depends on" both stand beside specifically dependent continuant, and in label order "inheres in"
 * came first - the sub-property above its super-property, the is-a line between them pointing
 * down, the one is-a line on the board whose parent stood below its child.
 */
class SubPropertyBesideItsClassUnderItsSuperPropertyTest {

    private static final BoardSections.Term SDC = new BoardSections.Term("sdc",
            BoardSections.Kind.CLASS, "specifically dependent continuant", 200, 40);

    private static BoardSections.Term property(String id, String label) {
        return new BoardSections.Term(id, BoardSections.Kind.OBJECT_PROPERTY, label, 200, 40);
    }

    private static BoardSections.Edge sub(String child, String parent) {
        return new BoardSections.Edge(child, parent, BoardSections.Link.SUB_PROPERTY);
    }

    private static Map<String, String> allBesideSdc(String... properties) {
        Map<String, String> beside = new LinkedHashMap<String, String>();
        for (String property : properties) {
            beside.put(property, "sdc");
        }
        return beside;
    }

    private static String column(BoardSections.Plan plan, String... ids) {
        StringBuilder at = new StringBuilder();
        for (String id : ids) {
            at.append(id).append(' ').append(Arrays.toString(plan.positions.get(id))).append('\n');
        }
        return at.toString();
    }

    /** specifically depends on level with the class's top, inheres in 40 + 16 under it. */
    @Test
    void theSuperPropertyStandsAboveItsSubPropertyInTheOutline() {
        List<BoardSections.Term> terms = Arrays.asList(SDC,
                property("inheresIn", "inheres in"),
                property("dependsOn", "specifically depends on"));

        BoardSections.Plan plan = BoardSections.plan(terms,
                Collections.singletonList(sub("inheresIn", "dependsOn")), 40, 40,
                Double.NEGATIVE_INFINITY, 40, allBesideSdc("inheresIn", "dependsOn"));

        assertEquals("sdc [40.0, 40.0]\ndependsOn [320.0, 40.0]\ninheresIn [320.0, 96.0]\n",
                column(plan, "sdc", "dependsOn", "inheresIn"));
    }

    /**
     * The rest keep the board's order of siblings, and a sub-property of two comes after both,
     * whichever order the lines come in: bearer of, has quality, specifically depends on, and
     * inheres in - under both of its super-properties - last.
     */
    @Test
    void aSubPropertyOfTwoComesAfterBothAndTheRestStandInLabelOrder() {
        List<BoardSections.Term> terms = Arrays.asList(SDC,
                property("inheresIn", "inheres in"),
                property("dependsOn", "specifically depends on"),
                property("hasQuality", "has quality"),
                property("bearerOf", "bearer of"));
        Map<String, String> beside = allBesideSdc("inheresIn", "dependsOn", "hasQuality",
                "bearerOf");
        String expected = "bearerOf [320.0, 40.0]\nhasQuality [320.0, 96.0]\n"
                + "dependsOn [320.0, 152.0]\ninheresIn [320.0, 208.0]\n";

        BoardSections.Plan one = BoardSections.plan(terms,
                Arrays.asList(sub("inheresIn", "dependsOn"), sub("inheresIn", "bearerOf")), 40,
                40, Double.NEGATIVE_INFINITY, 40, beside);
        BoardSections.Plan other = BoardSections.plan(terms,
                Arrays.asList(sub("inheresIn", "bearerOf"), sub("inheresIn", "dependsOn")), 40,
                40, Double.NEGATIVE_INFINITY, 40, beside);

        assertEquals(expected, column(one, "bearerOf", "hasQuality", "dependsOn", "inheresIn"));
        assertEquals(expected, column(other, "bearerOf", "hasQuality", "dependsOn", "inheresIn"));
    }

    /** In a pattern's frame the same: what stands beside a class is worked out once. */
    @Test
    void theSuperPropertyStandsAboveItsSubPropertyInAPatternsFrame() {
        List<BoardSections.Term> terms = Arrays.asList(SDC,
                property("inheresIn", "inheres in"),
                property("dependsOn", "specifically depends on"));
        Map<String, List<PropertiesBeside.Item>> beside = BoardSections.itemsBeside(terms,
                Collections.singletonList(sub("inheresIn", "dependsOn")),
                allBesideSdc("inheresIn", "dependsOn"));
        Map<String, double[]> sizes = new HashMap<String, double[]>();
        sizes.put("sdc", new double[] {200, 40});
        Map<String, String> names = new HashMap<String, String>();
        names.put("sdc", "specifically dependent continuant");

        FramedGroup.Placement placement = FramedGroup.plan("Pattern",
                Collections.singletonList("sdc"), new CanvasLayout(), sizes,
                new HashMap<String, String>(), names, beside);

        CanvasLayout.NodeLayout sdc = placement.getNodes().get("sdc");
        CanvasLayout.NodeLayout dependsOn = placement.getNodes().get("dependsOn");
        CanvasLayout.NodeLayout inheresIn = placement.getNodes().get("inheresIn");
        assertEquals("dependsOn +280,+0; inheresIn +280,+56",
                "dependsOn +" + (int) (dependsOn.x - sdc.x) + ",+" + (int) (dependsOn.y - sdc.y)
                        + "; inheresIn +" + (int) (inheresIn.x - sdc.x) + ",+"
                        + (int) (inheresIn.y - sdc.y));
    }
}
