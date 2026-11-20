package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A project's choice of names written into its ODK configuration (1.155.0): the key, and ODK's
 * {@code namespaces} - its base release keeps only the terms they name, {@code <uribase>/<ID>}
 * when none are set, so a readable {@code <uribase>/<id>/BoiledEgg} would be dropped from it.
 * Every other line of the file stays as it was.
 */
class TermNamesTest {

    private static final String CONFIG = "id: eggboil\ntitle: \"Egg\"\nuribase: https://example.org\n"
            + "import_group:\n  products:\n    - id: ro\n";

    @Test
    void aProjectWithoutNamespacesGetsTheKeyAndBoth() {
        String chosen = TermNames.choose(CONFIG, true, "eggboil-odk.yaml");

        assertTrue(chosen.startsWith(CONFIG), chosen);
        assertTrue(chosen.contains("\nontoboard_term_names: readable\n"), chosen);
        assertTrue(chosen.contains("\nnamespaces:\n  - https://example.org/EGGBOIL_\n"
                + "  - https://example.org/eggboil/\n"), chosen);
        assertEquals("https://example.org/eggboil/",
                TermMinter.readableNamespaceOf(chosen, "eggboil-odk.yaml"));
    }

    @Test
    void aListOfNamespacesGetsTheReadableOneAtItsEnd() {
        String config = CONFIG + "namespaces:\n- https://example.org/EGGBOIL_\n";

        String chosen = TermNames.choose(config, true, "eggboil-odk.yaml");

        assertTrue(chosen.contains("namespaces:\n- https://example.org/EGGBOIL_\n"
                + "- https://example.org/eggboil/\n"), chosen);
        assertEquals(1, count(chosen, "namespaces:"));
    }

    @Test
    void aNamespaceListedAlreadyIsNotAddedAgain() {
        String config = CONFIG + "namespaces:\n  - https://example.org/EGGBOIL_\n"
                + "  - https://example.org/eggboil/\n";

        assertEquals(1, count(TermNames.choose(config, true, "eggboil-odk.yaml"),
                "https://example.org/eggboil/"));
    }

    /** A list in brackets is left to the person, with what to add - it cannot be spliced. */
    @Test
    void aListInBracketsIsRefusedInWords() {
        String config = CONFIG + "namespaces: [\"https://example.org/EGGBOIL_\"]\n";

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TermNames.choose(config, true, "eggboil-odk.yaml"));
        assertTrue(refused.getMessage().contains("https://example.org/eggboil/"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("eggboil-odk.yaml"), refused.getMessage());
    }

    /** Back to numbers: the readable terms made meanwhile stay at home in the base release. */
    @Test
    void numberedAgainKeepsTheNamespaces() {
        String readable = TermNames.choose(CONFIG, true, "eggboil-odk.yaml");

        String numbered = TermNames.choose(readable, false, "eggboil-odk.yaml");

        assertTrue(numbered.contains("\nontoboard_term_names: numbered\n"), numbered);
        assertEquals(1, count(numbered, "ontoboard_term_names"));
        assertTrue(numbered.contains("  - https://example.org/eggboil/\n"), numbered);
    }

    @Test
    void aFileWithWindowsLineEndsKeepsThem() {
        String chosen = TermNames.choose(CONFIG.replace("\n", "\r\n"), true, "eggboil-odk.yaml");

        assertEquals(-1, chosen.replace("\r\n", "").indexOf('\n'), chosen);
    }

    /** A new project's configuration says it from the start, and names both addresses. */
    @Test
    void aNewReadableProjectSaysSo() {
        OdkProjectConfig config = new OdkProjectConfig("eggboil", "Egg", "Eggs",
                "https://example.org/eggboil.owl",
                "https://creativecommons.org/licenses/by/4.0/", null);
        config.setReadableNames(true);

        String written = OdkConfigWriter.forNewProject(config);

        assertTrue(written.contains("\nontoboard_term_names: readable\n"), written);
        assertTrue(written.contains("namespaces:\n  - https://example.org/EGGBOIL_\n"
                + "  - https://example.org/eggboil/\n"), written);
        assertTrue(written.contains("  use_base_iris: TRUE\n"), written);
        assertEquals("https://example.org/eggboil/",
                TermMinter.readableNamespaceOf(written, "eggboil-odk.yaml"));
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            found++;
        }
        return found;
    }
}
