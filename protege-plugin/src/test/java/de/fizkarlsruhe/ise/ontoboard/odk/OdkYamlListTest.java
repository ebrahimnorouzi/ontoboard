package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Adding and removing a list entry in the ODK YAML.
 *
 * <p>The documented roadmap's number one since 1.89.0: every scalar has been editable at any depth
 * for seven releases, and the thing a user most often wants to do - add an import to
 * {@code import_group.products}, add a format to {@code export_formats} - still meant opening a
 * text editor.
 *
 * <p>These pin the two things that make a list edit safe rather than merely possible: the file is
 * spliced so everything around the change survives byte for byte, and a new value is quoted when
 * YAML would otherwise read it as something other than a string.
 */
class OdkYamlListTest {

    private static final String FILE = String.join("\n",
            "# The ODK configuration. Comments matter.",
            "id: mwo",
            "title: Materials Workflow Ontology",
            "export_formats:",
            "  - owl",
            "  - obo          # kept for the legacy consumers",
            "import_group:",
            "  products:",
            "    - id: bfo",
            "      mirror_from: http://purl.obolibrary.org/obo/bfo/2020/notime/bfo.owl",
            "    - id: ro",
            "robot_report:",
            "  fail_on: ERROR",
            "");

    // ---------- adding ----------

    /** A new item goes on the end, and nothing else in the file moves. */
    @Test
    void anItemIsAppendedAndNothingElseChanges() {
        String after = OdkYaml.appendTo(FILE, "export_formats", "json");

        assertTrue(after.contains("  - owl\n"), after);
        assertTrue(after.contains("  - json"), after);
        assertTrue(after.indexOf("- json") > after.indexOf("- obo"), "it goes last");
        // Everything else survives, including the comments the splice exists to protect.
        assertTrue(after.startsWith("# The ODK configuration. Comments matter.\n"));
        assertTrue(after.contains("# kept for the legacy consumers"));
        assertTrue(after.contains("  fail_on: ERROR"));
    }

    /**
     * The trailing comment stays with the item it was written against.
     *
     * <p>The new entry goes after the whole of the last item's line, not after the value: putting
     * it between the value and its comment would move somebody's note onto a different entry.
     */
    @Test
    void aTrailingCommentIsNotStrandedOnTheNewItem() {
        String after = OdkYaml.appendTo(FILE, "export_formats", "json");

        assertTrue(after.contains("- obo          # kept for the legacy consumers\n  - json"),
                after);
    }

    /**
     * Indentation is copied from the list's last item, never computed.
     *
     * <p>ODK files in the wild indent sequences both ways and both are valid YAML. Guessing would
     * reformat the file on the first edit, which is what splice-not-rewrite exists to prevent.
     */
    @Test
    void theIndentIsCopiedFromTheFileRatherThanGuessed() {
        String flush = String.join("\n", "export_formats:", "- owl", "- obo", "");

        String after = OdkYaml.appendTo(flush, "export_formats", "json");

        assertTrue(after.contains("\n- json"), after);
        assertFalse(after.contains("\n  - json"), "the file does not indent, so nor does the edit");
    }

    /** It reaches a nested list too, which is where the products live. */
    @Test
    void aNestedListTakesAnItem() {
        String after = OdkYaml.appendTo(FILE, "import_group.products", "placeholder");

        assertTrue(after.contains("    - placeholder"), after);
        assertTrue(after.indexOf("- placeholder") > after.indexOf("- id: ro"));
        assertTrue(after.contains("robot_report:"), "what follows the list is untouched");
    }

    /** The result still parses, and has one more entry than it did. */
    @Test
    void theFileStillParsesAfterwards() {
        String after = OdkYaml.appendTo(FILE, "export_formats", "json");

        List<OdkYaml.Entry> before = OdkYaml.entriesIn(FILE);
        List<OdkYaml.Entry> now = OdkYaml.entriesIn(after);
        assertEquals(before.size() + 1, now.size());
        boolean found = false;
        for (OdkYaml.Entry entry : now) {
            if ("json".equals(entry.getValue())) {
                found = true;
            }
        }
        assertTrue(found, "the new value is readable back out");
    }

    // ---------- the quoting that stops a string becoming something else ----------

    /**
     * A value YAML would read as a boolean is quoted.
     *
     * <p>{@code - yes} in an ODK YAML is the boolean true, not the string "yes". A list of export
     * formats containing a boolean is a build that fails somewhere else entirely.
     */
    @Test
    void aValueThatWouldParseAsSomethingElseIsQuoted() {
        for (String risky : new String[] {"yes", "no", "true", "FALSE", "on", "off", "null", "~"}) {
            assertEquals("\"" + risky + "\"", OdkYaml.scalarFor(risky), risky);
        }
        for (String number : new String[] {"1", "1.5", "-3", "1e6", "007"}) {
            assertEquals("\"" + number + "\"", OdkYaml.scalarFor(number), number);
        }
    }

    /** And so is anything that would change the structure. */
    @Test
    void aValueCarryingStructureIsQuoted() {
        for (String risky : new String[] {"a: b", "x # y", "[1]", "{a}", "*anchor", "&ref",
            "!tag", "%directive", "@at", "`tick`"}) {
            assertTrue(OdkYaml.scalarFor(risky).startsWith("\""),
                    risky + " became " + OdkYaml.scalarFor(risky));
        }
    }

    /** An ordinary word is left alone, because quoting everything would reformat the file. */
    @Test
    void anOrdinaryValueIsNotQuoted() {
        for (String plain : new String[] {"owl", "obo", "json", "ro", "my-import",
            "http://purl.obolibrary.org/obo/ro.owl"}) {
            assertEquals(plain, OdkYaml.scalarFor(plain), plain);
        }
    }

    /** An empty value is written as an empty string rather than as nothing at all. */
    @Test
    void anEmptyValueIsAnEmptyString() {
        assertEquals("''", OdkYaml.scalarFor(""));
        assertEquals("''", OdkYaml.scalarFor("   "));
        assertEquals("''", OdkYaml.scalarFor(null));
    }

    // ---------- removing ----------

    /** An item goes with its whole line, and its neighbours stay. */
    @Test
    void anItemIsRemovedWithItsLine() {
        String after = OdkYaml.removeFrom(FILE, "export_formats[0]");

        assertFalse(after.contains("- owl"), after);
        assertTrue(after.contains("- obo"), "its neighbour survives");
        assertTrue(after.contains("# The ODK configuration"), "and so does everything else");
        assertTrue(after.contains("  fail_on: ERROR"));
    }

    /** Removing one with a trailing comment takes the comment too. */
    @Test
    void aCommentGoesWithTheItemItAnnotates() {
        String after = OdkYaml.removeFrom(FILE, "export_formats[1]");

        assertFalse(after.contains("# kept for the legacy consumers"), after);
        assertTrue(after.contains("- owl"));
    }

    /**
     * A mapping item goes as one unit.
     *
     * <p>An {@code import_group} product is a mapping spread over several lines. Taking only the
     * first would leave {@code mirror_from:} orphaned under the item before it - a file that still
     * parses and says something different.
     */
    @Test
    void aMultiLineItemGoesWhole() {
        String after = OdkYaml.removeFrom(FILE, "import_group.products[0]");

        assertFalse(after.contains("- id: bfo"), after);
        assertFalse(after.contains("mirror_from:"), "its continuation line goes with it");
        assertTrue(after.contains("- id: ro"), "the next product stays");
        assertTrue(OdkYaml.entriesIn(after).size() < OdkYaml.entriesIn(FILE).size());
    }

    /** Removing then adding gets back to something equivalent. */
    @Test
    void whatIsRemovedCanBeAddedBack() {
        String without = OdkYaml.removeFrom(FILE, "export_formats[1]");
        String again = OdkYaml.appendTo(without, "export_formats", "obo");

        List<OdkYaml.Entry> entries = OdkYaml.entriesIn(again);
        int formats = 0;
        for (OdkYaml.Entry entry : entries) {
            if (entry.getPath().startsWith("export_formats[")) {
                formats++;
            }
        }
        assertEquals(2, formats);
    }

    // ---------- refusing rather than guessing ----------

    /** A path that is not a list is refused, not silently treated as one. */
    @Test
    void somethingThatIsNotAListIsRefused() {
        OdkYaml.UnreadableException refused = assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.appendTo(FILE, "id", "x"));
        assertTrue(refused.getMessage().contains("not a list"), refused.getMessage());

        assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.appendTo(FILE, "no_such_key", "x"));
    }

    /**
     * An empty list is refused, with the reason.
     *
     * <p>There is no existing item to copy an indent from, and an empty sequence is written both
     * as {@code key: []} and as a key with nothing under it - two different edits. Refusing and
     * saying so is honest; guessing would reformat the file.
     */
    @Test
    void anEmptyListIsRefusedWithTheReason() {
        String empty = String.join("\n", "id: x", "export_formats: []", "");

        OdkYaml.UnreadableException refused = assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.appendTo(empty, "export_formats", "owl"));

        assertTrue(refused.getMessage().contains("empty"), refused.getMessage());
        assertTrue(refused.getMessage().contains("text editor"),
                "it has to say what to do instead: " + refused.getMessage());
    }

    /** Removing something that is not an item of a list is refused. */
    @Test
    void removingSomethingThatIsNotAListItemIsRefused() {
        assertThrows(OdkYaml.UnreadableException.class, () -> OdkYaml.removeFrom(FILE, "id"));
        assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.removeFrom(FILE, "export_formats[9]"));
    }
}
