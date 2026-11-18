package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ------------------------------------------------------------------ ODK's module types

    /** Each kind of import declares ODK's module type for it, which ODK's own reading takes. */
    @Test
    void eachKindDeclaresOdksModuleType() {
        String[][] expected = {{"MODULE", "slme", "BOT"}, {"ONLY_THESE", "filter", null},
            {"WITH_RELATIONS", "minimal", null}, {"WHOLE", "mirror", null}};
        for (String[] kind : expected) {
            String after = ImportProducts.declare(seeded(), "uberon", null,
                    ImportProducts.Kind.valueOf(kind[0]));

            ImportProducts.Product uberon = ImportProducts.named(
                    ImportProducts.declaredIn(after), "uberon");
            OdkProjectModel.load(after);
            assertEquals(kind[1], uberon.getModuleType(), kind[0]);
            if (kind[2] != null) {
                assertEquals(kind[2], uberon.getSlmeMethod(), kind[0]);
            }
        }
    }

    /**
     * Each kind in plain words, with ODK's name for it for those who know ODK's - the words as
     * measured in ODK's image: asked for a, its parent b and d (what a is part of), filter kept
     * a, b and "a is a b"; minimal kept those and "a is part of d" with part of; neither kept
     * b's own parent. Filter and minimal are told apart by their own words: "is a" against
     * every relation (the 1.154.0 review's M5 - "links" and "relations" read alike).
     */
    @Test
    void eachKindSaysWhatTheModuleHolds() {
        assertEquals("The terms you chose and what they need",
                ImportProducts.Kind.MODULE.words());
        assertEquals("Only the terms you chose, with their \"is a\" links",
                ImportProducts.Kind.ONLY_THESE.words());
        assertEquals("The terms you chose, with every relation between them",
                ImportProducts.Kind.WITH_RELATIONS.words());
        assertEquals("The whole ontology", ImportProducts.Kind.WHOLE.words());
        assertEquals("filter", ImportProducts.Kind.ONLY_THESE.odkName());
        assertEquals("slme, BOT", ImportProducts.Kind.MODULE.odkName());
    }

    /** A source of this machine is kept as it is, whatever the kind: as 1.153.0 decided. */
    @Test
    void aFileOfThisMachineIsKeptAsItIsWhateverTheKind() {
        String after = ImportProducts.declare(seeded(), "uberon", "file:/C:/x/uberon.owl",
                ImportProducts.Kind.WHOLE);

        ImportProducts.Product uberon = ImportProducts.named(ImportProducts.declaredIn(after),
                "uberon");
        assertTrue(ImportProducts.isKeptAsItIs(uberon), after);
    }

    /**
     * ODK's update gives the module ODK's own rule for its type, and OntoBoard's own engine
     * can run the refresh of each, on ODK's oboimports seed.
     */
    @Test
    void odksUpdateGivesEachKindItsRuleAndTheEngineRunsItsRefresh(
            @org.junit.jupiter.api.io.TempDir java.io.File top) throws Exception {
        String[][] expected = {{"ONLY_THESE", "filter"}, {"WITH_RELATIONS", "minimal"},
            {"WHOLE", "mirror"}, {"MODULE", "slme"}};
        for (String[] kind : expected) {
            java.io.File root = new java.io.File(top, kind[0]);
            for (java.util.Map.Entry<String, byte[]> file : Goldens.seedTree("oboimports")
                    .entrySet()) {
                java.io.File target = new java.io.File(root, file.getKey());
                java.nio.file.Files.createDirectories(target.getParentFile().toPath());
                java.nio.file.Files.write(target.toPath(), file.getValue());
            }
            java.io.File config = new java.io.File(root, "src/ontology/obim-odk.yaml");
            java.io.File terms = new java.io.File(root, "src/ontology/imports/uberon_terms.txt");
            java.nio.file.Files.write(terms.toPath(), "UBERON:0000062\n"
                    .getBytes(StandardCharsets.UTF_8));
            java.nio.file.Files.write(config.toPath(), ImportProducts.declare(
                    new String(java.nio.file.Files.readAllBytes(config.toPath()),
                            StandardCharsets.UTF_8), "uberon", null,
                    ImportProducts.Kind.valueOf(kind[0])).getBytes(StandardCharsets.UTF_8));

            OdkUpdate.apply(root, OdkUpdate.plan(root));

            String makefile = new String(java.nio.file.Files.readAllBytes(
                    new java.io.File(root, "src/ontology/Makefile").toPath()),
                    StandardCharsets.UTF_8);
            assertTrue(java.util.regex.Pattern.compile("(?m)^IMPORTS =.*\\buberon\\b")
                    .matcher(makefile).find(), kind[0] + ": among ODK's imports");
            // slme with BOT is the group's own default: ODK's generic rule builds that one,
            // as it builds the seed's ro; every other type gets a rule of its own.
            assertEquals(!kind[1].equals("slme"),
                    makefile.contains("## Module for ontology: uberon (" + kind[1] + ")"),
                    kind[0] + ": ODK's rule for " + kind[1]);
            assertNull(OdkEngine.whyNot(new java.io.File(root, "src/ontology/obim-edit.owl"),
                    "refresh-uberon", java.util.Collections.<String, String>emptyMap()),
                    kind[0]);
        }
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
