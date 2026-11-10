package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A property whose domain names one class on the board stands beside that class in the Outline,
 * not in a section of its own.
 *
 * <p>Found by the maintainer on the pizza ontologies (1.135.1): "the properties that are not
 * connected, and they just stray in the canvas". Every property stood in the Object properties
 * frame - on pizza-bfo about 1,800 px below pizza - joined to nothing until it was clicked.
 */
class PropertyBesideItsDomainClassTest {

    private static BoardSections.Term term(String id, BoardSections.Kind kind, double w) {
        return new BoardSections.Term(id, kind, id, w, 40);
    }

    private static Map<String, String> beside(String... pairs) {
        Map<String, String> beside = new LinkedHashMap<String, String>();
        for (int at = 0; at < pairs.length; at += 2) {
            beside.put(pairs[at], pairs[at + 1]);
        }
        return beside;
    }

    /** Pizza under Thing, hasTopping beside Pizza: 80 px right of its box, its top level. */
    @Test
    void aPropertyStandsRightOfItsClassItsTopLevelWithTheClasssTop() {
        List<BoardSections.Term> terms = Arrays.asList(
                term("Thing", BoardSections.Kind.CLASS, 160),
                term("Pizza", BoardSections.Kind.CLASS, 200),
                term("hasTopping", BoardSections.Kind.OBJECT_PROPERTY, 200));
        List<BoardSections.Edge> edges = Arrays.asList(
                new BoardSections.Edge("Pizza", "Thing", BoardSections.Link.SUBCLASS));

        BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40,
                Double.NEGATIVE_INFINITY, 40, beside("hasTopping", "Pizza"));

        // Thing at 40,40; Pizza indented under it, 40 + 40 + 40 down; hasTopping 200 + 80 right.
        assertEquals("[80.0, 120.0]", Arrays.toString(plan.positions.get("Pizza")));
        assertEquals("[360.0, 120.0]", Arrays.toString(plan.positions.get("hasTopping")));
        assertEquals(new ArrayList<BoardSections.Section>(), plan.sections,
                "no Object properties section: its one property stands beside its class");
    }

    /**
     * The class's properties one under the other, 16 px apart, in the order the board gives
     * siblings: object properties, then data properties, each by label.
     */
    @Test
    void aClasssPropertiesStandOneUnderTheOtherInTheBoardsOrder() {
        List<BoardSections.Term> terms = Arrays.asList(
                term("Pizza", BoardSections.Kind.CLASS, 200),
                term("hasTopping", BoardSections.Kind.OBJECT_PROPERTY, 200),
                term("hasCalories", BoardSections.Kind.DATA_PROPERTY, 200),
                term("hasBase", BoardSections.Kind.OBJECT_PROPERTY, 160),
                term("aWeight", BoardSections.Kind.DATA_PROPERTY, 160));

        BoardSections.Plan plan = BoardSections.plan(terms,
                Collections.<BoardSections.Edge>emptyList(), 40, 40, Double.NEGATIVE_INFINITY, 40,
                beside("hasTopping", "Pizza", "hasCalories", "Pizza", "hasBase", "Pizza",
                        "aWeight", "Pizza"));

        Map<String, String> at = new LinkedHashMap<String, String>();
        for (String id : new String[] {"Pizza", "hasBase", "hasTopping", "aWeight",
            "hasCalories"}) {
            at.put(id, Arrays.toString(plan.positions.get(id)));
        }
        Map<String, String> expected = new LinkedHashMap<String, String>();
        expected.put("Pizza", "[40.0, 40.0]");
        expected.put("hasBase", "[320.0, 40.0]");
        expected.put("hasTopping", "[320.0, 96.0]");
        expected.put("aWeight", "[320.0, 152.0]");
        expected.put("hasCalories", "[320.0, 208.0]");
        assertEquals(expected, at);
    }

    /**
     * Two properties of one label beside one class - "has part" from two ontologies - stand in one
     * order whatever order the board lists them in: the order of the OWL API's hash sets, which an
     * unrelated axiom can change.
     */
    @Test
    void twoPropertiesOfOneLabelStandInOneOrderWhateverOrderTheyCome() {
        BoardSections.Term pizza = term("Pizza", BoardSections.Kind.CLASS, 200);
        BoardSections.Term one = new BoardSections.Term("obo:part",
                BoardSections.Kind.OBJECT_PROPERTY, "has part", 160, 40);
        BoardSections.Term other = new BoardSections.Term("ex:part",
                BoardSections.Kind.OBJECT_PROPERTY, "has part", 200, 40);
        Map<String, String> beside = beside("obo:part", "Pizza", "ex:part", "Pizza");

        BoardSections.Plan listed = BoardSections.plan(Arrays.asList(pizza, one, other),
                Collections.<BoardSections.Edge>emptyList(), 40, 40, Double.NEGATIVE_INFINITY, 40,
                beside);
        BoardSections.Plan reversed = BoardSections.plan(Arrays.asList(other, one, pizza),
                Collections.<BoardSections.Edge>emptyList(), 40, 40, Double.NEGATIVE_INFINITY, 40,
                beside);

        // By IRI where the labels are the same: ex: before obo:.
        assertEquals("[320.0, 40.0] [320.0, 96.0]",
                Arrays.toString(listed.positions.get("ex:part")) + " "
                        + Arrays.toString(listed.positions.get("obo:part")));
        assertEquals("[320.0, 40.0] [320.0, 96.0]",
                Arrays.toString(reversed.positions.get("ex:part")) + " "
                        + Arrays.toString(reversed.positions.get("obo:part")));
    }

    /** A data property's datatype under it, as in the Data properties section; the next below. */
    @Test
    void aDataPropertysDatatypeStandsUnderItBesideTheClass() {
        List<BoardSections.Term> terms = Arrays.asList(
                term("Price", BoardSections.Kind.CLASS, 160),
                term("hasValue", BoardSections.Kind.DATA_PROPERTY, 200),
                term("xsd:float", BoardSections.Kind.VALUE, 160),
                term("hasCurrency", BoardSections.Kind.DATA_PROPERTY, 200),
                term("hasZone", BoardSections.Kind.DATA_PROPERTY, 160));
        List<BoardSections.Edge> edges = Arrays.asList(
                new BoardSections.Edge("hasValue", "xsd:float", BoardSections.Link.OTHER));

        BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40,
                Double.NEGATIVE_INFINITY, 40, beside("hasValue", "Price", "hasCurrency", "Price",
                        "hasZone", "Price"));

        // hasCurrency at the class's top, hasValue 56 below, its datatype 40 in and 40 under it,
        // and hasZone 16 under the datatype.
        assertEquals("[280.0, 40.0]", Arrays.toString(plan.positions.get("hasCurrency")));
        assertEquals("[280.0, 96.0]", Arrays.toString(plan.positions.get("hasValue")));
        assertEquals("[320.0, 176.0]", Arrays.toString(plan.positions.get("xsd:float")));
        assertEquals("[280.0, 232.0]", Arrays.toString(plan.positions.get("hasZone")));
        assertTrue(plan.sections.isEmpty(), "no Data properties section: " + plan.sections.size());
    }

    /**
     * A class with six properties beside it and eight subclasses, beside a tree of its own: the
     * class and its properties are one block, so the subclasses stand below the properties and
     * nothing overlaps.
     */
    @Test
    void aClassWithManyPropertiesHasItsSubclassesBelowThemAndNothingOverlaps() {
        List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
        List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
        Map<String, String> beside = new HashMap<String, String>();
        terms.add(term("Pizza", BoardSections.Kind.CLASS, 200));
        for (int p = 0; p < 6; p++) {
            terms.add(term("has part " + p, BoardSections.Kind.OBJECT_PROPERTY, 240));
            beside.put("has part " + p, "Pizza");
        }
        for (int s = 0; s < 8; s++) {
            terms.add(term("pizza " + s, BoardSections.Kind.CLASS, 160));
            edges.add(new BoardSections.Edge("pizza " + s, "Pizza", BoardSections.Link.SUBCLASS));
        }
        terms.add(term("Base", BoardSections.Kind.CLASS, 160));
        for (int s = 0; s < 3; s++) {
            terms.add(term("base " + s, BoardSections.Kind.CLASS, 160));
            edges.add(new BoardSections.Edge("base " + s, "Base", BoardSections.Link.SUBCLASS));
        }

        BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40,
                Double.NEGATIVE_INFINITY, 40, beside);

        double[] pizza = plan.positions.get("Pizza");
        // Six properties of 40 px, 16 apart: 320 px from the class's top.
        double block = pizza[1] + 6 * 40 + 5 * 16;
        for (int s = 0; s < 8; s++) {
            assertTrue(plan.positions.get("pizza " + s)[1] >= block + HierarchyColumns.UNDER,
                    "pizza " + s + " below the properties: "
                            + plan.positions.get("pizza " + s)[1]);
        }
        for (int p = 0; p < 6; p++) {
            double[] at = plan.positions.get("has part " + p);
            assertEquals(pizza[0] + 200 + 80, at[0], 0.001, "has part " + p + " beside Pizza");
        }
        List<String> overlaps = new ArrayList<String>();
        List<BoardSections.Term> all = new ArrayList<BoardSections.Term>(terms);
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                double[] a = plan.positions.get(all.get(i).id);
                double[] b = plan.positions.get(all.get(j).id);
                if (a[0] < b[0] + all.get(j).w && b[0] < a[0] + all.get(i).w
                        && a[1] < b[1] + all.get(j).h && b[1] < a[1] + all.get(i).h) {
                    overlaps.add(all.get(i).id + " / " + all.get(j).id);
                }
            }
        }
        assertEquals(new ArrayList<String>(), overlaps);
    }

    /**
     * The sections hold only the properties no class has beside it: knows, in Object properties,
     * and no Data properties section, its one property standing beside Person.
     */
    @Test
    void onlyThePropertiesBesideNoClassStandInTheSections() {
        List<BoardSections.Term> terms = Arrays.asList(
                term("Person", BoardSections.Kind.CLASS, 160),
                term("hasPet", BoardSections.Kind.OBJECT_PROPERTY, 160),
                term("knows", BoardSections.Kind.OBJECT_PROPERTY, 160),
                term("age", BoardSections.Kind.DATA_PROPERTY, 160));

        BoardSections.Plan plan = BoardSections.plan(terms,
                Collections.<BoardSections.Edge>emptyList(), 40, 40, Double.NEGATIVE_INFINITY, 40,
                beside("hasPet", "Person", "age", "Person"));

        assertEquals(1, plan.sections.size(), "one section");
        BoardSections.Section objects = plan.sections.get(0);
        assertEquals("Object properties", objects.label);
        double[] knows = plan.positions.get("knows");
        assertTrue(knows[0] >= objects.x && knows[0] + 160 <= objects.x + objects.w
                && knows[1] >= objects.y && knows[1] + 40 <= objects.y + objects.h,
                "knows in its section");
        for (String besideIt : new String[] {"hasPet", "age"}) {
            double[] at = plan.positions.get(besideIt);
            assertFalse(at[0] + 160 > objects.x && at[0] < objects.x + objects.w
                    && at[1] + 40 > objects.y && at[1] < objects.y + objects.h,
                    besideIt + " is not in the section");
        }
    }
}
