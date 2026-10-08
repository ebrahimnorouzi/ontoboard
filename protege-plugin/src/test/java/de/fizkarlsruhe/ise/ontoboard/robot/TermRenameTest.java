package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.e2e.PizzaOntology;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLOntology;

/** ROBOT's rename, over the pizza. */
class TermRenameTest {

    private static Map<String, String> mapping(String from, String to) {
        Map<String, String> mappings = new LinkedHashMap<String, String>();
        mappings.put(from, to);
        return mappings;
    }

    /** The case this exists for: publishing a draft under a different namespace. */
    @Test
    void aPrefixRenameMovesEveryTerm() throws Exception {
        OWLOntology pizza = PizzaOntology.v1();

        TermRename.Plan plan = TermRename.plan(pizza, TermRename.Mode.PREFIX,
                mapping("http://example.org/pizza#", "http://purl.obolibrary.org/obo/PIZZA_"));

        assertFalse(plan.isEmpty(), "every term is under that prefix, so something must change");
        assertTrue(plan.getEntitiesAffected() > 5,
                "the pizza has more than five terms under that prefix: "
                        + plan.getEntitiesAffected());
        assertTrue(plan.getUnmatched().isEmpty(), plan.getUnmatched().toString());
    }

    /** One term, renamed by its whole IRI. */
    @Test
    void afullIriRenameMovesOneTerm() throws Exception {
        OWLOntology pizza = PizzaOntology.v1();

        TermRename.Plan plan = TermRename.plan(pizza, TermRename.Mode.FULL_IRI,
                mapping(PizzaOntology.NS + "Pizza", PizzaOntology.NS + "Flatbread"));

        assertFalse(plan.isEmpty());
        assertEquals(1, plan.getEntitiesAffected(),
                "renaming one IRI should move exactly one entity");
        assertTrue(plan.getUnmatched().isEmpty());
    }

    /**
     * A mapping that matches nothing is reported, not silently ignored.
     *
     * <p>It is almost always a typo in the IRI, and a silent no-op is how somebody concludes the
     * rename worked when it did nothing at all.
     */
    @Test
    void amappingThatMatchesNothingIsReported() throws Exception {
        TermRename.Plan plan = TermRename.plan(PizzaOntology.v1(), TermRename.Mode.FULL_IRI,
                mapping("http://example.org/nowhere#Nothing", PizzaOntology.NS + "Whatever"));

        assertTrue(plan.isEmpty(), "nothing matched, so nothing should change");
        assertEquals(1, plan.getUnmatched().size());
        assertEquals("http://example.org/nowhere#Nothing", plan.getUnmatched().get(0));
    }

    /** Both separators, because one comes from an editor and the other from a spreadsheet. */
    @Test
    void mappingsParseFromArrowsAndTabs() {
        Map<String, String> parsed = TermRename.parse(
                "http://a/x -> http://b/x\n"
                        + "# a comment\n"
                        + "\n"
                        + "http://a/y\thttp://b/y\n");

        assertEquals(2, parsed.size());
        assertEquals("http://b/x", parsed.get("http://a/x"));
        assertEquals("http://b/y", parsed.get("http://a/y"));
    }

    /** A line with no separator names itself, rather than being dropped. */
    @Test
    void amalformedLineIsRefusedAndNamesTheLine() {
        RobotException failed = assertThrows(RobotException.class,
                () -> TermRename.parse("http://a/x -> http://b/x\nthis line has no separator\n"));

        assertTrue(failed.getMessage().contains("Line 2"), failed.getMessage());
        assertTrue(failed.getMessage().contains("no separator"), failed.getMessage());
    }

    /**
     * A line copied out of a spreadsheet that has a third column.
     *
     * <p>The most ordinary thing a person can do with this box, and it used to corrupt the
     * ontology without saying anything. Both splits take a limit of two, so the third column
     * stayed attached to the second: the target IRI arrived as
     * {@code http://b/y<tab>renamed for clarity}, {@code plan} accepted it, reported one entity
     * affected and no unmatched mappings, and the changes were to declare a class with a tab in
     * its IRI and remove the real one.
     */
    @Test
    void athirdColumnIsRefusedRatherThanFoldedIntoTheNewIri() {
        RobotException failed = assertThrows(RobotException.class,
                () -> TermRename.parse("http://a/y\thttp://b/y\trenamed for clarity\n"));

        assertTrue(failed.getMessage().contains("Line 1"), failed.getMessage());
        assertTrue(failed.getMessage().contains("tab"), failed.getMessage());
        assertTrue(failed.getMessage().contains("third column"), failed.getMessage());
    }

    /** The same mistake written with an arrow, where the spare text has a space in front of it. */
    @Test
    void textAfterTheNewIriIsRefused() {
        RobotException failed = assertThrows(RobotException.class,
                () -> TermRename.parse("http://a/x -> http://b/x and also fix the label\n"));

        assertTrue(failed.getMessage().contains("a space"), failed.getMessage());
    }

    /** A space in the term being renamed is refused too, and names that side. */
    @Test
    void whitespaceOnTheLeftIsRefusedAndQuotesThatSide() {
        RobotException failed = assertThrows(RobotException.class,
                () -> TermRename.parse("http://a/old term\thttp://b/new\n"));

        assertTrue(failed.getMessage().contains("http://a/old term"), failed.getMessage());
    }

    /** And the legitimate forms still parse, including a prefix that is not a whole IRI. */
    @Test
    void aPrefixMappingIsStillAccepted() {
        Map<String, String> parsed = TermRename.parse(
                "http://example.org/ -> http://purl.obolibrary.org/obo/\n");

        assertEquals(1, parsed.size());
        assertEquals("http://purl.obolibrary.org/obo/", parsed.get("http://example.org/"));
    }

    @Test
    void emptyInputIsRefusedRatherThanReportedAsSuccess() throws Exception {
        OWLOntology pizza = PizzaOntology.v1();

        assertTrue(TermRename.parse("").isEmpty());
        assertTrue(TermRename.parse(null).isEmpty());
        assertThrows(RobotException.class,
                () -> TermRename.plan(pizza, TermRename.Mode.PREFIX,
                        new LinkedHashMap<String, String>()));
        assertThrows(IllegalArgumentException.class, () -> TermRename.plan(null,
                TermRename.Mode.PREFIX, mapping("a", "b")));
    }

    /**
     * The changes target the open ontology, so Protege can apply and undo them.
     *
     * <p>A change built against the working copy would apply to nothing while reporting success,
     * which is the failure mode this assertion exists to prevent.
     */
    @Test
    void theChangesTargetTheOntologyThatWasPassedIn() throws Exception {
        OWLOntology pizza = PizzaOntology.v1();

        TermRename.Plan plan = TermRename.plan(pizza, TermRename.Mode.PREFIX,
                mapping("http://example.org/pizza#", "http://example.org/pie#"));

        assertFalse(plan.isEmpty());
        for (org.semanticweb.owlapi.model.OWLOntologyChange change : plan.getChanges()) {
            assertEquals(pizza, change.getOntology(),
                    "a change aimed at a copy would apply to nothing and report success");
        }
    }

    /** Both modes describe themselves, because the dialog shows that text. */
    @Test
    void bothModesCarryTheirOwnHelp() {
        for (TermRename.Mode mode : TermRename.Mode.values()) {
            assertFalse(mode.getLabel().trim().isEmpty());
            assertFalse(mode.getHelp().trim().isEmpty());
        }
    }
}
