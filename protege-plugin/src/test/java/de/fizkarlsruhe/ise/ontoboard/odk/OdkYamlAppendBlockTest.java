package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/**
 * Adding a whole mapping item to a list - an {@code import_group.products} entry.
 *
 * <p>Every fixture here is a spelling taken from a real file, because the first implementation
 * passed the obvious case and broke four of the five real ones: it trusted snakeyaml's end mark
 * for the last item, which lands where the <em>next structure</em> begins, so a new product was
 * spliced in after NFDIcore's {@code components:} and after MWO's {@code remove_owl_nothing} and
 * the result was not YAML. Hence {@link #reparses} on every assertion - a result that is not
 * valid YAML is worse than a refusal.
 */
class OdkYamlAppendBlockTest {

    /** The product every test adds: what *Import terms…* knows after an extraction. */
    private static LinkedHashMap<String, String> product() {
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("id", "ro");
        fields.put("mirror_from", "http://purl.obolibrary.org/obo/ro.owl");
        fields.put("module_type", "custom");
        return fields;
    }

    /** The ids of {@code import_group.products} after the edit, in order. */
    private static String productsIn(String text) {
        StringBuilder ids = new StringBuilder();
        for (OdkYaml.Entry entry : OdkYaml.entriesIn(text)) {
            if (entry.getPath().matches("import_group\\.products\\[\\d+]\\.id")) {
                ids.append(ids.length() == 0 ? "" : ",").append(entry.getValue());
            }
        }
        return ids.toString();
    }

    /** That the result is still YAML at all - which the first implementation often was not. */
    private static String reparses(String text) {
        return productsIn(text);
    }

    // ---------- the case that matters: OntoBoard's own scaffold ----------

    /**
     * An empty flow list is seeded, which {@link OdkYaml#appendTo} refuses.
     *
     * <p>This is the whole reason the method exists. OdkScaffold writes {@code products: []} into
     * every project OntoBoard generates, so refusing an empty list refused every project this
     * plugin made - the only ones where somebody would be adding the first product.
     */
    @Test
    void anEmptyFlowListIsSeeded() {
        String after = OdkYaml.appendBlockTo(
                "id: pizza\nimport_group:\n  products: []\nrobot_report:\n  fail_on: ERROR\n",
                "import_group.products", product());

        assertEquals("ro", reparses(after));
        assertTrue(after.contains("  products:\n    - id: ro\n"), after);
        assertTrue(after.contains("      module_type: custom\n"), after);
        assertTrue(after.contains("robot_report:\n  fail_on: ERROR\n"),
                "what came after the list is untouched: " + after);
        assertTrue(after.startsWith("id: pizza\n"), after);
    }

    /** No `[]` left behind, which would be a second, empty list. */
    @Test
    void theFlowMarkersAreReplacedNotKept() {
        String after = OdkYaml.appendBlockTo("import_group:\n  products: []\n",
                "import_group.products", product());

        assertEquals(-1, after.indexOf("[]"), after);
        assertEquals(-1, after.indexOf("products: \n"), "no trailing space after the key: " + after);
    }

    /** A key with nothing under it is null, not an empty list, and is refused as before. */
    @Test
    void aKeyWithNothingUnderItIsNotAnEmptyList() {
        OdkYaml.UnreadableException refused = assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.appendBlockTo("import_group:\n  products:\n",
                        "import_group.products", product()));

        assertTrue(refused.getMessage().contains("not a list"), refused.getMessage());
    }

    // ---------- every real indentation style, copied not computed ----------

    /** NFDIcore: a three-space key with four-space items under it. */
    @Test
    void nfdicoreKeepsItsFourSpaceItems() {
        String after = OdkYaml.appendBlockTo(String.join("\n",
                "import_group:",
                "   products:",
                "    - id: bfo",
                "      module_type: mirror",
                "",
                "components:",
                "  products:",
                "    - filename: x.owl",
                ""), "import_group.products", product());

        assertEquals("bfo,ro", reparses(after));
        assertTrue(after.contains("\n    - id: ro\n      mirror_from:"), after);
        assertTrue(after.contains("      module_type: mirror\n    - id: ro"),
                "it goes after the last product, not after components: " + after);
        assertTrue(after.contains("\n\ncomponents:\n"),
                "the blank line before the next key survives: " + after);
    }

    /** MWO: a two-space key with three-space items, which must not become four. */
    @Test
    void mwoKeepsItsThreeSpaceItems() {
        String after = OdkYaml.appendBlockTo(String.join("\n",
                "import_group:",
                "  products:",
                "   - id: iao",
                "     module_type: custom",
                "remove_owl_nothing: TRUE",
                ""), "import_group.products", product());

        assertEquals("iao,ro", reparses(after));
        assertTrue(after.contains("\n   - id: ro\n     mirror_from:"),
                "three spaces, copied from the item above: " + after);
        assertTrue(after.contains("\nremove_owl_nothing: TRUE\n"),
                "the key after the list is untouched and still at column 0: " + after);
    }

    /** ECTO: the last item is a bare `- id:` with no second key of its own. */
    @Test
    void aBareLastItemIsStillFound() {
        String after = OdkYaml.appendBlockTo(String.join("\n",
                "import_group:",
                "  products:",
                "    - id: chebi",
                "    - id: xco",
                "robot_java_args: '-Xmx8G'",
                ""), "import_group.products", product());

        assertEquals("chebi,xco,ro", reparses(after));
        assertTrue(after.contains("    - id: xco\n    - id: ro\n"), after);
    }

    /** The list ending at the last line of the file, with no blank line left behind. */
    @Test
    void aListAtTheEndOfTheFileGainsNoBlankLine() {
        String after = OdkYaml.appendBlockTo("import_group:\n  products:\n    - id: iao\n",
                "import_group.products", product());

        assertEquals("iao,ro", reparses(after));
        assertTrue(after.contains("    - id: iao\n    - id: ro\n"),
                "no blank line between them: " + after);
    }

    /** A trailing comment on the item above stays with that item. */
    @Test
    void aTrailingCommentStaysWhereItWas() {
        String after = OdkYaml.appendBlockTo(
                "import_group:\n  products:\n    - id: iao   # keep\n      module_type: custom\n",
                "import_group.products", product());

        assertEquals("iao,ro", reparses(after));
        assertTrue(after.contains("- id: iao   # keep"), after);
    }

    /**
     * A tab-indented list is refused, because YAML has no such thing.
     *
     * <p>Written as a test for the capability and corrected into a test for the refusal: the spec
     * forbids tabs in indentation outright, so snakeyaml rejects the file before this method sees
     * it. Nothing here should paper over that - a file the build cannot read is not a file to
     * edit - and the deeper-than-the-dash scan counts tabs only so that one in trailing
     * whitespace cannot miscount a line that is otherwise legal.
     */
    @Test
    void aTabIndentedListIsRefusedBecauseYamlHasNoSuchThing() {
        OdkYaml.UnreadableException refused = assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.appendBlockTo("import_group:\n  products:\n\t- id: iao\n",
                        "import_group.products", product()));

        assertTrue(refused.getMessage().contains("TAB"), refused.getMessage());
    }

    // ---------- the values ----------

    /** An IRI is not quoted, which is the thing most often added to one of these lists. */
    @Test
    void anIriIsNotQuoted() {
        String after = OdkYaml.appendBlockTo("import_group:\n  products:\n    - id: iao\n",
                "import_group.products", product());

        assertTrue(after.contains("mirror_from: http://purl.obolibrary.org/obo/ro.owl"), after);
        assertEquals(-1, after.indexOf("\"http"), "a quoted URL is not a URL: " + after);
    }

    /** A value YAML would read as a boolean is quoted, so `- id: no` is not false. */
    @Test
    void aValueThatWouldReadAsABooleanIsQuoted() {
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("id", "no");

        String after = OdkYaml.appendBlockTo("import_group:\n  products:\n    - id: iao\n",
                "import_group.products", fields);

        assertTrue(after.contains("- id: \"no\"") || after.contains("- id: 'no'"), after);
        assertEquals("iao,no", reparses(after), "and it reads back as the string: " + after);
    }

    /** The order the fields were given is the order they are written. */
    @Test
    void theFieldOrderIsKept() {
        String after = OdkYaml.appendBlockTo("import_group:\n  products:\n    - id: iao\n",
                "import_group.products", product());

        int id = after.lastIndexOf("id: ro");
        int mirror = after.indexOf("mirror_from:");
        int type = after.indexOf("module_type: custom");
        assertTrue(id < mirror && mirror < type, after);
    }

    /** One field is a whole item, which is what a product with no mirror_from is. */
    @Test
    void oneFieldIsEnough() {
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("id", "uo");

        String after = OdkYaml.appendBlockTo("import_group:\n  products: []\n",
                "import_group.products", fields);

        assertEquals("uo", reparses(after));
        assertTrue(after.endsWith("    - id: uo\n") || after.contains("    - id: uo"), after);
    }

    // ---------- refusals ----------

    /** No fields at all is refused rather than writing a dash with nothing after it. */
    @Test
    void anItemWithNoFieldsIsRefused() {
        assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.appendBlockTo("import_group:\n  products: []\n",
                        "import_group.products", new LinkedHashMap<String, String>()));
        assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.appendBlockTo("import_group:\n  products: []\n",
                        "import_group.products", null));
    }

    /** A path that is not there, and one that is not a list. */
    @Test
    void aMissingOrNonListPathIsRefused() {
        assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.appendBlockTo("id: x\n", "import_group.products", product()));
        assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.appendBlockTo("id: x\n", "id", product()));
    }

    /** Appending twice gives two items, not one overwritten - the dialog can be used again. */
    @Test
    void appendingTwiceGivesTwoItems() {
        LinkedHashMap<String, String> second = new LinkedHashMap<String, String>();
        second.put("id", "iao");
        second.put("module_type", "custom");

        String after = OdkYaml.appendBlockTo(
                OdkYaml.appendBlockTo("import_group:\n  products: []\n",
                        "import_group.products", product()),
                "import_group.products", second);

        assertEquals("ro,iao", reparses(after), after);
    }
}
