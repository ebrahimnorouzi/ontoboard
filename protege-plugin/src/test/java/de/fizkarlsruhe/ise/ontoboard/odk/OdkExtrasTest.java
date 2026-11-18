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

    /**
     * A project site serves docs/X at https://user.github.io/repo/X, so an address deeper
     * in the repository is copied to the same path under docs/ (1.135.1 review). It said
     * docs/egg.owl for https://user.github.io/repo/ontology/egg.owl, where the address
     * would not answer.
     */
    @Test
    void onAProjectSiteADeeperAddressIsCopiedToItsOwnPathUnderDocs() {
        String page = OdkExtras.files(new OdkProjectConfig("egg", "Eggs", "Boiling eggs",
                "https://user.github.io/repo/ontology/egg.owl", LICENCE, null))
                .get("docs/release.md");

        assertTrue(page.contains("`egg.owl` to `docs/ontology/egg.owl`"), page);
        assertTrue(page.contains("`docs/ontology/egg/releases/1.0.0/egg.owl`"), page);
        assertFalse(page.contains("`docs/egg.owl`"), page);
    }

    /** A user site, https://user.github.io/x.owl, has no repository in its path. */
    @Test
    void onAUserSiteTheAddressIsCopiedToTheTopOfDocs() {
        String page = OdkExtras.files(new OdkProjectConfig("egg", "Eggs", "Boiling eggs",
                "https://user.github.io/egg.owl", LICENCE, null)).get("docs/release.md");

        assertTrue(page.contains("`egg.owl` to `docs/egg.owl`"), page);
        assertTrue(page.contains("`docs/egg/releases/1.0.0/egg.owl`"), page);
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

    // ------------------------------------------------------------------ F3, the workflow's token

    /** A page as a reader reads it: without Markdown's code marks, its lines joined. */
    private static String said(String page) {
        return page.replace("`", "").replace("\n", " ");
    }

    /**
     * F3: ODK's documentation workflow pushes the site to {@code gh-pages}, and a new GitHub
     * repository lets workflows only read, so its first run fails. The page that says how to
     * turn the site on says so first - with the command for this repository, its owner known.
     */
    @Test
    void theIndexPageSaysTheWorkflowMustBeAllowedToWriteFirst() {
        OdkProjectConfig owned = project("Test kitchen ontology",
                "https://example.org/eggboil/eggboil.owl");
        owned.setGithubOrg("kitchen-lab");

        String page = OdkExtras.files(owned).get("docs/index.md");

        assertTrue(said(page).contains(OdkExtras.workflowPermissions("kitchen-lab", "eggboil")),
                page);
        assertTrue(page.contains("`gh api -X PUT repos/kitchen-lab/eggboil/actions/permissions/"
                + "workflow -f default_workflow_permissions=write`"), page);
        assertTrue(page.indexOf("Workflow permissions") < page.indexOf("**Settings > Pages**"),
                "before Pages is turned on: " + page);
    }

    /**
     * The release page says it after the push - the push starts the workflow - and without an
     * owner both pages say {@code <owner>}, in code, where Markdown shows it as it is.
     */
    @Test
    void withoutAnOwnerThePagesSayOwnerAfterThePush() {
        Map<String, String> files = pages("https://example.org/eggboil/eggboil.owl");

        for (String path : new String[] {"docs/index.md", "docs/release.md"}) {
            String page = files.get(path);
            assertTrue(said(page).contains(OdkExtras.workflowPermissions(null, "eggboil")),
                    path + ":\n" + page);
            assertTrue(page.contains("`gh api -X PUT repos/<owner>/eggboil/"), path + ":\n" + page);
        }
        String release = files.get("docs/release.md");
        assertTrue(release.indexOf("git push --follow-tags") < release.indexOf(
                "Workflow permissions"), release);
    }

    /** Git must not turn ODK's files into CRLF on a Windows clone: its container runs them. */
    @Test
    void lineEndingsArePinnedForEveryTextFile() {
        String attributes = pages("https://example.org/eggboil/eggboil.owl").get(".gitattributes");

        assertTrue(attributes.contains("\n* text=auto eol=lf\n"), attributes);
    }
}
