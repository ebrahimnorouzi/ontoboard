package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.CatalogMerge;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.GitignoreMerge;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkInstaller;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkProjectModel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two files ODK's update does not simply write: {@code .gitignore} and the XML catalog.
 *
 * <p>Both may hold lines of the project's own beside ODK's, so ODK's update merges: its own
 * part is replaced, the rest is kept ({@code update_gitignore} and {@code update_xml_catalog}
 * in odk.py). Here the same merges, held against what ODK's update wrote for the recorded
 * projects - byte for byte, since these are text files a commit shows the changes of.
 */
class OdkMergesTest {

    /** What ODK's template gives for a file of the case's project, before any merge. */
    private static String rendered(String name, String path) {
        Map<String, byte[]> before = UpdateCases.before(name);
        byte[] bytes = OdkInstaller.renderAll(OdkProjectModel.load(
                UpdateCases.text(before, "src/ontology/eggboil-odk.yaml"))).files().get(path);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ .gitignore

    @ParameterizedTest
    @ValueSource(strings = {"egg-unchanged", "egg-changed"})
    void theIgnoreFileIsWhatOdksUpdateWrites(String name) {
        String existing = UpdateCases.text(UpdateCases.before(name), ".gitignore");

        String merged = GitignoreMerge.merge(rendered(name, ".gitignore"), existing);

        assertEquals(UpdateCases.text(UpdateCases.after(name), ".gitignore"), merged);
    }

    private static final String ODKS = "# ODK-managed rules, do not modify\n.DS_Store\n\nbin/\n"
            + "# End of ODK-managed rules";

    @Test
    void withoutAnIgnoreFileItIsOdksWithALineBreakAtItsEnd() {
        assertEquals(ODKS + "\n", GitignoreMerge.merge(ODKS, null));
    }

    /**
     * ODK's update writes every piece between line breaks with a line break after it - the empty
     * piece after a last line break too. A template that ended in one would get an empty line.
     */
    @Test
    void aTemplateThatEndsInALineBreakGetsAnEmptyLineAsOdkWritesIt() {
        assertEquals("a\n\n", GitignoreMerge.merge("a\n", null));
    }

    /** Whatever stands outside ODK's block is the project's, above it or below. */
    @Test
    void whatIsOutsideOdksBlockIsKeptBelowIt() {
        String merged = GitignoreMerge.merge(ODKS, "above/\n# ODK-managed rules, do not modify\n"
                + "old-rule-of-odks\n# End of ODK-managed rules\nbelow/\n\n# a comment\n");

        assertEquals(ODKS + "\nabove/\nbelow/\n\n# a comment\n", merged);
    }

    /** ODK replaces its block whole: a line somebody put inside it is gone. That is ODK's rule. */
    @Test
    void aLinePutInsideOdksBlockIsNotKept() {
        String merged = GitignoreMerge.merge(ODKS, "# ODK-managed rules, do not modify\n"
                + "mine-inside/\n# End of ODK-managed rules\n");

        assertFalse(merged.contains("mine-inside/"), merged);
    }

    @Test
    void aRuleOfTheirsThatOdkHasTooIsNotWrittenTwice() {
        assertEquals(ODKS + "\nmine/\n", GitignoreMerge.merge(ODKS,
                "# ODK-managed rules, do not modify\n# End of ODK-managed rules\nbin/\nmine/\n"));
    }

    /** ODK reads the lines stripped: blanks around a rule go, and so do Windows line endings. */
    @Test
    void theirLinesAreWrittenWithoutBlanksAroundThemAndWithUnixLineEndings() {
        assertEquals(ODKS + "\nmine/\nother/\n", GitignoreMerge.merge(ODKS,
                "  mine/  \r\nother/\r\n"));
    }

    /**
     * "Stripped" is Python's: a no-break space counts as a blank there, which it does not for
     * Java's own trimming. A rule copied off a web page ends in one.
     */
    @Test
    void aNoBreakSpaceAroundARuleGoesToo() {
        String noBreakSpace = String.valueOf((char) 0xa0);

        assertEquals(ODKS + "\nmine/\n", GitignoreMerge.merge(ODKS,
                noBreakSpace + "mine/" + noBreakSpace + "\n"));
    }

    /** An old Mac's line ending ends a line as well, as it does where ODK's update runs. */
    @Test
    void aLoneCarriageReturnEndsALineToo() {
        assertEquals(ODKS + "\nmine/\nother/\n", GitignoreMerge.merge(ODKS, "mine/\rother/"));
    }

    // ------------------------------------------------------------------ the catalog

    @ParameterizedTest
    @ValueSource(strings = {"egg-unchanged", "egg-changed"})
    void theCatalogIsWhatOdksUpdateWrites(String name) {
        String existing = UpdateCases.text(UpdateCases.before(name),
                "src/ontology/catalog-v001.xml");

        String merged = CatalogMerge.merge(rendered(name, "src/ontology/catalog-v001.xml"),
                existing);

        assertEquals(UpdateCases.text(UpdateCases.after(name), "src/ontology/catalog-v001.xml"),
                merged);
    }

    private static final String TEMPLATE = "<?xml version=\"1.0\" encoding=\"UTF-8\" "
            + "standalone=\"no\"?>\n<catalog prefer=\"public\" "
            + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">\n"
            + "  <group id=\"odk-managed-catalog\" prefer=\"public\">\n"
            + "    <uri name=\"https://example.org/x/imports/a_import.owl\" "
            + "uri=\"imports/a_import.owl\"/>\n  </group>\n</catalog>";

    private static final String HEAD = "<?xml version='1.0' encoding='UTF-8'?>\n"
            + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\" "
            + "prefer=\"public\">\n  <group id=\"odk-managed-catalog\" prefer=\"public\">\n"
            + "    <uri name=\"https://example.org/x/imports/a_import.owl\" "
            + "uri=\"imports/a_import.owl\" />\n  </group>\n";

    @Test
    void withoutACatalogItIsOdksInTheWritingOdksUpdateHas() {
        assertEquals(HEAD + "</catalog>", CatalogMerge.merge(TEMPLATE, null));
    }

    /**
     * Protégé writes an entry of its own for every import somebody resolved by hand. Those
     * are the project's and stay, after ODK's group and in their order.
     */
    @Test
    void entriesOfTheirOwnStayAfterOdksGroup() {
        String merged = CatalogMerge.merge(TEMPLATE, "<?xml version=\"1.0\"?>\n"
                + "<catalog prefer=\"public\" xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:"
                + "catalog\">\n<uri id=\"User Entered Import Resolution\" "
                + "name=\"https://example.org/b.owl\" uri=\"b.owl\"/><uri name=\"c\" uri=\"c.owl\"/>"
                + "\n</catalog>\n");

        assertEquals(HEAD + "  <uri id=\"User Entered Import Resolution\" "
                + "name=\"https://example.org/b.owl\" uri=\"b.owl\" />\n"
                + "  <uri name=\"c\" uri=\"c.owl\" />\n</catalog>", merged);
    }

    /** What is in ODK's group already is not kept a second time outside it. */
    @Test
    void anEntryOdksGroupHasIsNotKeptASecondTime() {
        String merged = CatalogMerge.merge(TEMPLATE, "<catalog "
                + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                + "<uri name=\"https://example.org/x/imports/a_import.owl\" "
                + "uri=\"imports/a_import.owl\"/>"
                + "<uri name=\"https://example.org/x/imports/a_import.owl\" "
                + "uri=\"elsewhere/a_import.owl\"/></catalog>");

        assertEquals(HEAD + "  <uri name=\"https://example.org/x/imports/a_import.owl\" "
                + "uri=\"elsewhere/a_import.owl\" />\n</catalog>", merged,
                "the same name read from another file is another entry, and stays");
    }

    /** A character XML has to write another way stays what it was. */
    @Test
    void whatXmlEscapesIsEscapedAgain() {
        String merged = CatalogMerge.merge(TEMPLATE, "<catalog "
                + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                + "<uri name=\"https://example.org/q?a=1&amp;b=&lt;2&gt;\" "
                + "uri=\"a &quot;b&quot;.owl\"/></catalog>");

        assertTrue(merged.contains("<uri name=\"https://example.org/q?a=1&amp;b=&lt;2&gt;\" "
                + "uri=\"a &quot;b&quot;.owl\" />"), merged);
    }

    /** A tab or a line break inside a value is written as a number, the tab as ODK's has it. */
    @Test
    void aTabOrALineBreakInAValueIsWrittenAsItsNumber() {
        String merged = CatalogMerge.merge(TEMPLATE, "<catalog "
                + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                + "<uri name=\"c\" uri=\"a&#9;b&#10;c&#13;d.owl\"/></catalog>");

        assertTrue(merged.contains("<uri name=\"c\" uri=\"a&#09;b&#10;c&#13;d.owl\" />"), merged);
    }

    /**
     * A group of their own keeps its entries, one to a line and one step further in; an empty
     * {@code xml:base}, which Protégé writes and XML does not allow, is taken off it, any other
     * stays.
     */
    @Test
    void aGroupOfTheirOwnIsKeptWithoutAnEmptyBase() {
        String merged = CatalogMerge.merge(TEMPLATE, "<catalog "
                + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                + "<group id=\"Folder Repository\" prefer=\"public\" xml:base=\"\">"
                + "<uri name=\"c\" uri=\"c.owl\"/>"
                + "<uri name=\"https://example.org/x/imports/a_import.owl\" "
                + "uri=\"imports/a_import.owl\"/></group>"
                + "<group xml:base=\"other/\"><uri name=\"d\" uri=\"d.owl\"/></group>"
                + "</catalog>");

        assertEquals(HEAD + "  <group id=\"Folder Repository\" prefer=\"public\">\n"
                + "    <uri name=\"c\" uri=\"c.owl\" />\n  </group>\n"
                + "  <group xml:base=\"other/\">\n    <uri name=\"d\" uri=\"d.owl\" />\n"
                + "  </group>\n</catalog>", merged,
                "and what ODK's group has is not kept inside a group of theirs either");
    }

    /** ODK's group of the last update is not the project's: it goes, with whatever is in it. */
    @Test
    void odksGroupOfTheLastUpdateIsReplacedWhole() {
        String merged = CatalogMerge.merge(TEMPLATE, "<catalog "
                + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                + "<group id=\"odk-managed-catalog\" prefer=\"public\">"
                + "<uri name=\"https://example.org/x/imports/gone_import.owl\" "
                + "uri=\"imports/gone_import.owl\"/><uri name=\"put-here-by-hand\" uri=\"h.owl\"/>"
                + "</group></catalog>");

        assertEquals(HEAD + "</catalog>", merged);
    }

    /**
     * A catalog written without the catalog's namespace: ODK's update carries its entries over
     * as they are, into a file where they then have it.
     */
    @Test
    void anEntryWrittenWithoutTheNamespaceIsCarriedOver() {
        String merged = CatalogMerge.merge(TEMPLATE,
                "<catalog><uri name=\"c\" uri=\"c.owl\"/></catalog>");

        assertEquals(HEAD + "  <uri name=\"c\" uri=\"c.owl\" />\n</catalog>", merged);
    }

    /** Text between the entries means nothing in a catalog, but it is not lost either. */
    @Test
    void textOfTheirOwnBetweenEntriesStays() {
        String merged = CatalogMerge.merge(TEMPLATE, "<catalog "
                + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                + "<uri name=\"c\" uri=\"c.owl\"/> a &amp; b <uri name=\"d\" uri=\"d.owl\"/>"
                + "</catalog>");

        assertEquals(HEAD + "  <uri name=\"c\" uri=\"c.owl\" /> a &amp; b "
                + "<uri name=\"d\" uri=\"d.owl\" />\n</catalog>", merged);
    }

    /** A comment is not kept: ODK's update reads the catalog without them. That is ODK's rule. */
    @Test
    void aCommentIsNotKept() {
        String merged = CatalogMerge.merge(TEMPLATE, "<catalog "
                + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                + "<!-- mine --><uri name=\"c\" uri=\"c.owl\"/></catalog>");

        assertFalse(merged.contains("mine"), merged);
        assertTrue(merged.contains("<uri name=\"c\" uri=\"c.owl\" />"), merged);
    }

    /**
     * What the merge does not know how ODK would write is not guessed at: the update is then
     * ODK's own to do, in its container.
     */
    @Test
    void aCatalogThatIsNotOneIsRefusedWithAReason() {
        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class,
                () -> CatalogMerge.merge(TEMPLATE, "<catalog><uri name=\"c\""));
        assertTrue(broken.getMessage().contains("catalog-v001.xml"), broken.getMessage());

        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class,
                () -> CatalogMerge.merge(TEMPLATE, "<catalog "
                        + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\" "
                        + "xmlns:x=\"https://example.org/x\"><x:note>mine</x:note></catalog>"));
        assertTrue(foreign.getMessage().contains("https://example.org/x"),
                foreign.getMessage());

        IllegalArgumentException marked = assertThrows(IllegalArgumentException.class,
                () -> CatalogMerge.merge(TEMPLATE, "<catalog "
                        + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\" "
                        + "xmlns:y=\"https://example.org/y\">"
                        + "<uri y:note=\"mine\" name=\"c\" uri=\"c.owl\"/></catalog>"));
        assertTrue(marked.getMessage().contains("https://example.org/y"), marked.getMessage());
        for (String said : new String[] {foreign.getMessage(), marked.getMessage()}) {
            assertTrue(said.contains("ODK's container"), said);
            assertFalse(said.contains("Start Docker"), "a container is one way, not an "
                    + "order: " + said);
        }
    }

    /**
     * A catalog comes with a repository somebody cloned, and a document type declaration can
     * name a file to fetch from anywhere. OntoBoard reads no catalog that has one - the rule
     * {@code Catalog} has for the same file - and says that this is why.
     */
    @Test
    void aCatalogWithADocumentTypeIsRefusedAndNothingIsFetched() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CatalogMerge.merge(TEMPLATE, "<?xml version=\"1.0\"?>\n"
                        + "<!DOCTYPE catalog SYSTEM \"http://127.0.0.1:9/catalog.dtd\">\n"
                        + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\"/>"));
        assertTrue(refused.getMessage().contains("<!DOCTYPE"), refused.getMessage());
        assertTrue(refused.getMessage().contains("catalog-v001.xml"), refused.getMessage());
        assertTrue(refused.getMessage().contains("ODK's container"), refused.getMessage());
        assertFalse(refused.getMessage().contains("Start Docker"), refused.getMessage());

        IllegalArgumentException entity = assertThrows(IllegalArgumentException.class,
                () -> CatalogMerge.merge(TEMPLATE, "<!DOCTYPE catalog [<!ENTITY e \"x\">]>\n"
                        + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                        + "<uri name=\"&e;\" uri=\"c.owl\"/></catalog>"));
        assertTrue(entity.getMessage().contains("<!DOCTYPE"), entity.getMessage());
    }
}
