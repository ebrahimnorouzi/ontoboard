package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The pages OntoBoard adds to a new project say what is true of an ODK project.
 *
 * <p>They are the project's own documentation, published on its site, and the first thing a new
 * editor of it reads. In 1.135.x they described OntoBoard's own build; a project is ODK's now,
 * so they name ODK's targets, ODK's version IRIs and where ODK puts a release.
 */
class OdkExtrasTest {

    private static final String LICENCE = "https://creativecommons.org/licenses/by/4.0/";

    private static OdkProjectConfig project(String title, String iri) {
        return new OdkProjectConfig("eggboil", title, "Boiling eggs", iri, LICENCE, null);
    }

    private static Map<String, String> pages(String iri) {
        return OdkExtras.files(project("Test kitchen ontology", iri));
    }

    /** ODK's version IRI is {@code <uribase>/<id>/releases/<version>/<file>}. */
    @Test
    void theReleasePageNamesTheVersionIriOdkWrites() {
        String page = pages("https://example.org/eggboil/eggboil.owl").get("docs/release.md");

        assertTrue(page.contains("`https://example.org/eggboil/eggboil/releases/1.0.0/eggboil.owl`"),
                page);
        assertTrue(page.contains("`prepare_release`"), page);
        assertTrue(page.contains("`releases/1.0.0/`"), page);
        assertTrue(page.contains("tagged `v1.0.0`"), page);
        assertFalse(page.contains("GitHub Pages serves"), "only for an address on github.io");
    }

    /**
     * An ontology named under {@code github.io} is served from {@code docs/}: the page says
     * where under it each IRI is looked for, which is the path after the repository's name.
     */
    @Test
    void onGitHubPagesTheReleasePageSaysWhereTheIrisAreServedFrom() {
        String page = pages("https://jane.github.io/eggs/eggboil.owl").get("docs/release.md");

        assertTrue(page.contains("`https://jane.github.io/eggs/eggboil/releases/1.0.0/eggboil.owl`"),
                page);
        assertTrue(page.contains("`eggboil.owl` to `docs/eggboil.owl`"), page);
        assertTrue(page.contains("`docs/eggboil/releases/1.0.0/eggboil.owl`"), page);
    }

    @Test
    void theEditingPageNamesOdksTargetsAndThePlaceholderRanges() {
        String page = pages("https://example.org/eggboil/eggboil.owl").get("docs/editing.md");

        for (String said : new String[] {"`reason_test`", "`sparql_test`", "`robot_reports`",
            "`validate_idranges`", "`test`", "`test_fast`", "`sh run.sh make test`",
            "`ONTOLOGY-CREATOR`", "`ADDITIONAL EDITOR`", "ODK v1.6.1",
            // Whole, on one line: a path broken over two is one nobody finds by searching,
            // and MenuPathsInMessagesTest reads it as a menu item that is not there.
            "**OntoBoard > Project > ID ranges...**",
            "**OntoBoard > Build and release > Build...**",
            "`src/ontology/eggboil-odk.yaml`", "`src/ontology/profile.txt`"}) {
            assertTrue(page.contains(said), said + " is not on the page:\n" + page);
        }
    }

    /** {@code site_name: Eggs: boiled} is a YAML error, and the site then does not build. */
    @Test
    void aTitleWithAColonOrAQuoteIsStillAConfigMkdocsReads() {
        for (String title : new String[] {"Eggs: boiled", "The \"egg\" ontology", "# eggs",
            "Test kitchen ontology"}) {
            String config = OdkExtras.files(project(title,
                    "https://example.org/eggboil/eggboil.owl")).get("mkdocs.yaml");

            Object read = new Yaml().load(config);

            assertEquals(title, ((Map<?, ?>) read).get("site_name"), config);
            assertEquals("readthedocs", ((Map<?, ?>) read).get("theme"), config);
        }
    }

    /** A line break in a title would end the page's heading halfway. */
    @Test
    void aTitleIsOneLineOfEveryPage() {
        Map<String, String> files = OdkExtras.files(project("Eggs\nboiled",
                "https://example.org/eggboil/eggboil.owl"));

        assertTrue(files.get("docs/index.md").startsWith("# Eggs boiled\n\n"));
        assertTrue(files.get("docs/editing.md").startsWith("# Editing Eggs boiled\n\n"));
        assertTrue(files.get("docs/release.md").startsWith("# Releasing Eggs boiled\n\n"));
    }

    @Test
    void theIndexPageSaysWhereTheOntologyIs() {
        String page = pages("https://example.org/eggboil/eggboil.owl").get("docs/index.md");

        assertTrue(page.contains("| Ontology IRI | `https://example.org/eggboil/eggboil.owl` |"),
                page);
        assertTrue(page.contains("| Edit file | `src/ontology/eggboil-edit.owl` |"), page);
        assertTrue(page.contains("`releases/`"), page);
    }

    /** Git must not turn ODK's files into CRLF on a Windows clone: its container runs them. */
    @Test
    void lineEndingsArePinnedForEveryTextFile() {
        String attributes = pages("https://example.org/eggboil/eggboil.owl").get(".gitattributes");

        assertTrue(attributes.contains("\n* text=auto eol=lf\n"), attributes);
    }
}
