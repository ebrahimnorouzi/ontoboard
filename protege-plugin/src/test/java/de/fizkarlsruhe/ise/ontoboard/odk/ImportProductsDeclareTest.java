package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkProjectModel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * One more import product in a project's configuration - also in one that imports nothing yet
 * and so has no {@code import_group} at all, which is every project ODK's seed has just made.
 */
class ImportProductsDeclareTest {

    private static final String CONFIG = "src/ontology/eggboil-odk.yaml";

    /** The configuration of a project as ODK's seed leaves it: no imports, no group. */
    private static String seeded() {
        return new String(Goldens.seedTree("eggboil").get(CONFIG), StandardCharsets.UTF_8);
    }

    /** The recorded project's: a group with two products. */
    private static String withTwo() {
        return UpdateCases.text(UpdateCases.before("egg-changed"), CONFIG);
    }

    private static LinkedHashMap<String, String> spoons() {
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("id", "spoons");
        fields.put("mirror_from", "https://example.org/spoons.owl");
        fields.put("module_type", "slme");
        fields.put("module_type_slme", "BOT");
        return fields;
    }

    private static List<String> idsIn(String yaml) {
        // ODK's own reading has to take it too.
        OdkProjectModel.load(yaml);
        List<String> ids = new ArrayList<String>();
        for (ImportProducts.Product product : ImportProducts.declaredIn(yaml)) {
            ids.add(product.getId());
        }
        return ids;
    }

    @Test
    void aProductIsAddedToTheListThatIsThere() {
        String before = withTwo();

        String after = ImportProducts.withProduct(before, spoons());

        assertEquals(Arrays.asList("ro", "utensils", "spoons"), idsIn(after));
        ImportProducts.Product added = ImportProducts.named(ImportProducts.declaredIn(after),
                "spoons");
        assertEquals("https://example.org/spoons.owl", added.getMirrorFrom());
        assertEquals("slme", added.getModuleType());
        assertEquals("BOT", added.getSlmeMethod());
        // Nothing else is touched: take the four lines out again and it is the file it was.
        assertEquals(before, after.replace("    - id: spoons\n"
                + "      mirror_from: https://example.org/spoons.owl\n"
                + "      module_type: slme\n"
                + "      module_type_slme: BOT\n", ""));
    }

    /** ODK's seed writes no import_group for a project that imports nothing. */
    @Test
    void aConfigurationThatImportsNothingGetsTheGroup() {
        String before = seeded();
        assertFalse(before.contains("import_group"), "the seed's configuration has no group");

        String after = ImportProducts.withProduct(before, spoons());

        assertEquals(Arrays.asList("spoons"), idsIn(after));
        assertEquals(before + "import_group:\n"
                + "  products:\n"
                + "    - id: spoons\n"
                + "      mirror_from: https://example.org/spoons.owl\n"
                + "      module_type: slme\n"
                + "      module_type_slme: BOT\n", after);
    }

    @Test
    void aFileThatDoesNotEndWithALineBreakGetsOneFirst() {
        String before = "id: demo\ntitle: Demo";

        String after = ImportProducts.withProduct(before, spoons());

        assertTrue(after.startsWith("id: demo\ntitle: Demo\nimport_group:\n"), after);
        assertEquals(Arrays.asList("spoons"), ImportProductsDeclareTest.plainIdsIn(after));
    }

    /** A group that says something else and has no list gets one, where the group ends. */
    @Test
    void aGroupWithoutAListGetsOneAsItsLastLines() {
        String before = "id: demo\n"
                + "import_group:\n"
                + "  release_imports: TRUE\n"
                + "\n"
                + "robot_java_args: -Xmx8G\n";

        String after = ImportProducts.withProduct(before, spoons());

        assertEquals("id: demo\n"
                + "import_group:\n"
                + "  release_imports: TRUE\n"
                + "  products:\n"
                + "    - id: spoons\n"
                + "      mirror_from: https://example.org/spoons.owl\n"
                + "      module_type: slme\n"
                + "      module_type_slme: BOT\n"
                + "\n"
                + "robot_java_args: -Xmx8G\n", after);
        assertEquals(Arrays.asList("spoons"), plainIdsIn(after));
    }

    /** The list goes at the indent the group's own keys have. */
    @Test
    void aGroupIndentedByFourGetsItsListAtFour() {
        String before = "id: demo\n"
                + "import_group:\n"
                + "    release_imports: TRUE\n";

        String after = ImportProducts.withProduct(before, spoons());

        assertEquals("id: demo\n"
                + "import_group:\n"
                + "    release_imports: TRUE\n"
                + "    products:\n"
                + "      - id: spoons\n"
                + "        mirror_from: https://example.org/spoons.owl\n"
                + "        module_type: slme\n"
                + "        module_type_slme: BOT\n", after);
        assertEquals(Arrays.asList("spoons"), plainIdsIn(after));
    }

    @Test
    void anEmptyListIsFilled() {
        String after = ImportProducts.withProduct("id: demo\nimport_group:\n  products: []\n",
                spoons());

        assertEquals(Arrays.asList("spoons"), plainIdsIn(after));
    }

    @Test
    void windowsLineEndingsAreKept() {
        for (String before : new String[] {seeded(), withTwo(),
            "id: demo\nimport_group:\n  release_imports: TRUE\n"}) {
            String after = ImportProducts.withProduct(before.replace("\n", "\r\n"), spoons());

            assertFalse(after.replace("\r\n", "").contains("\n"), "a line break that is not "
                    + "the file's own in: " + after.replace("\r", "<CR>"));
            assertEquals(ImportProducts.withProduct(before, spoons()),
                    after.replace("\r\n", "\n"));
        }
    }

    /** What is not understood is not guessed at: the group is left as it is, and that is said. */
    @Test
    void aGroupOnOneLineIsRefused() {
        OdkYaml.UnreadableException refused = assertThrows(OdkYaml.UnreadableException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        ImportProducts.withProduct("id: demo\nimport_group: {}\n", spoons());
                    }
                });

        assertTrue(refused.getMessage().contains("import_group"), refused.getMessage());
    }

    @Test
    void aProductsKeyThatHoldsNoListIsRefused() {
        assertThrows(OdkYaml.UnreadableException.class, new Executable() {
            @Override
            public void execute() {
                ImportProducts.withProduct("id: demo\nimport_group:\n  products:\n", spoons());
            }
        });
    }

    /** A value YAML would read as something else is written so that it stays the text it is. */
    @Test
    void anIdYamlWouldReadAsSomethingElseIsQuoted() {
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("id", "no");

        String after = ImportProducts.withProduct("id: demo\n", fields);

        assertEquals(Arrays.asList("no"), plainIdsIn(after));
    }

    /** ODK's words for a module it keeps as it is and fetches nothing for. */
    @Test
    void aFrozenProductIsCustomWithoutAMirror() {
        LinkedHashMap<String, String> fields = ImportProducts.frozen("spoons");

        assertEquals("[id, module_type, mirror_type]", fields.keySet().toString());
        ImportProducts.Product read = ImportProducts.named(ImportProducts.declaredIn(
                ImportProducts.withProduct("id: demo\n", fields)), "spoons");
        assertEquals("custom", read.getModuleType());
        assertFalse(read.isMirrored());
    }

    /** The ids as OntoBoard reads them, for a configuration too small for ODK's own reading. */
    private static List<String> plainIdsIn(String yaml) {
        List<String> ids = new ArrayList<String>();
        for (ImportProducts.Product product : ImportProducts.declaredIn(yaml)) {
            ids.add(product.getId());
        }
        return ids;
    }
}
