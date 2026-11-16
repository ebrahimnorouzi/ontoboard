package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkProjectModel;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.PyObject;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The ODK configuration OntoBoard writes for a new project.
 *
 * <p>It is the one file of a new project that is OntoBoard's and not ODK's, and everything ODK
 * then writes follows from it - the Makefile records its hash. So it is fixed to the byte: the
 * egg project's is kept as a golden, and ODK's own seed of that golden is what
 * {@code SeedParityTest} compares against.
 */
class OdkConfigWriterTest {

    private static OdkProjectConfig egg() {
        return new OdkProjectConfig("eggboil", "Test kitchen ontology",
                "Application ontology for boiling eggs in a test kitchen",
                "https://example.org/eggboil/eggboil.owl",
                "https://creativecommons.org/licenses/by/4.0/", null);
    }

    private static OdkProjectConfig project(String id, String title, String iri) {
        return new OdkProjectConfig(id, title, "", iri,
                "https://creativecommons.org/licenses/by/4.0/", null);
    }

    @Test
    void theEggAnswersGiveThisConfig() {
        assertEquals(Goldens.config("eggboil"), OdkConfigWriter.forNewProject(egg()));
    }

    /**
     * F2: the GitHub owner is written as ODK's own {@code github_org}, before {@code repo} as in
     * ODK's samples, so that ODK's README, badge and pages link to the repository. Without one
     * nothing is written: the egg's configuration above stays the golden one.
     */
    @Test
    void anOwnerIsWrittenAsOdksGithubOrg() {
        OdkProjectConfig config = egg();
        config.setGithubOrg("kitchen-lab");

        String yaml = OdkConfigWriter.forNewProject(config);

        assertTrue(yaml.contains("\nlicense: https://creativecommons.org/licenses/by/4.0/\n"
                + "github_org: kitchen-lab\nrepo: eggboil\n"), yaml);
        assertEquals("kitchen-lab", OdkProjectModel.load(yaml).get("github_org"));
    }

    /** ODK names the ontology {@code <uribase>/<id>.owl}; the wizard asks for that whole IRI. */
    @Test
    void theUribaseIsTheBaseIriWithoutTheFile() {
        assertEquals("https://example.org/eggboil", OdkConfigWriter.uriBase(egg()));
        assertEquals("http://purl.obolibrary.org/obo", OdkConfigWriter.uriBase(
                project("mwo", "MWO", "http://purl.obolibrary.org/obo/mwo.owl")));
        // A # or / typed after it is dropped when the answers are read.
        assertEquals("https://example.org", OdkConfigWriter.uriBase(
                project("eggboil", "Egg", "https://example.org/eggboil.owl#")));
    }

    /**
     * 1.135.x took any IRI that ended in the ID. ODK does not: it writes {@code <uribase>/<id>.owl}
     * into the edit file, the releases and the catalog, whatever was meant. An IRI of another
     * shape would be a project whose files name two different ontologies.
     */
    @ParameterizedTest
    @ValueSource(strings = {"https://example.org/eggboil", "https://example.org/eggboil/eggboil",
        "https://example.org/eggboil/eggboil.ttl", "https://example.org/x/EGGBOIL.owl",
        "https://example.org/myeggboil.owl"})
    void aBaseIriOdkCannotKeepIsRefusedSayingWhatItMustBe(String iri) {
        OdkProjectConfig config = project("eggboil", "Egg", iri);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> OdkConfigWriter.forNewProject(config));

        assertTrue(refused.getMessage().contains("/eggboil.owl"), refused.getMessage());
        assertTrue(refused.getMessage().contains(config.getBaseIri()), refused.getMessage());
    }

    /**
     * An ontology somebody already has keeps its address, whatever its shape: changing it would
     * rename the ontology. The uribase is then where that address lives.
     */
    @Test
    void anAdoptedOntologyKeepsItsAddressAndTheUribaseIsWhereItLives() {
        assertEquals("https://example.org", OdkConfigWriter.uriBase(project("shop_ontology",
                "Shop", "https://example.org/Shop-Ontology.owl").adopting()));
        assertEquals("https://w3id.org/shop", OdkConfigWriter.uriBase(project("core", "Shop",
                "https://w3id.org/shop/core").adopting()));
        assertEquals("https://example.org", OdkConfigWriter.uriBase(project("example", "Bare",
                "https://example.org").adopting()));
        // And when it is ODK's shape already, it is read as any other.
        assertEquals("https://example.org/shop", OdkConfigWriter.uriBase(project("shop", "Shop",
                "https://example.org/shop/shop.owl").adopting()));
    }

    /**
     * What ODK names under a project's base - its modules, its releases - is under an adopted
     * ontology's own address: where that address does not end in the id, its last part is
     * ODK's {@code uribase_suffix}. The file ODK releases is named after the id all the same.
     */
    @Test
    void whatOdkNamesForAnAdoptedOntologyIsUnderItsOwnAddress() {
        OdkProjectConfig odd = project("shopcore", "Shop",
                "https://example.org/vocab/ontology").adopting();
        assertEquals("ontology", OdkConfigWriter.uriBaseSuffix(odd));
        assertEquals("https://example.org/vocab/ontology", OdkConfigWriter.ontBase(odd));
        assertEquals("https://example.org/vocab/shopcore.owl", OdkConfigWriter.odkIri(odd));
        String config = OdkConfigWriter.forNewProject(odd);
        assertTrue(config.contains("uribase: https://example.org/vocab\n"
                + "uribase_suffix: ontology\nlicense: "), config);
        PyObject read = OdkProjectModel.load(config);
        assertEquals("ontology", de.fizkarlsruhe.ise.ontoboard.odk.seed.Py.str(
                read.get("uribase_suffix")), "ODK reads it");
        assertEquals("https://example.org/vocab/ontology", OdkUpdate.ontbase(read));

        // The .owl of a file's name is no part of the address it stands for.
        OdkProjectConfig file = project("shop_ontology", "Shop",
                "https://example.org/Shop-Ontology.owl").adopting();
        assertEquals("Shop-Ontology", OdkConfigWriter.uriBaseSuffix(file));
        assertEquals("https://example.org/Shop-Ontology", OdkConfigWriter.ontBase(file));
        assertEquals("https://example.org/shop_ontology.owl", OdkConfigWriter.odkIri(file));

        // ODK's own shape, with or without .owl, needs none - and has none.
        OdkProjectConfig odks = project("shop", "Shop", "https://example.org/shop/shop.owl")
                .adopting();
        assertEquals(null, OdkConfigWriter.uriBaseSuffix(odks));
        assertEquals("https://example.org/shop/shop", OdkConfigWriter.ontBase(odks));
        assertFalse(OdkConfigWriter.forNewProject(odks).contains("uribase_suffix"));
        assertEquals(null, OdkConfigWriter.uriBaseSuffix(project("shop", "Shop",
                "https://example.org/shop").adopting()));
        // A separator at the end is no part of the address.
        assertEquals("shop", OdkConfigWriter.uriBaseSuffix(project("shopx", "Shop",
                "https://example.org/shop/").adopting()));
        // Nothing to name it by: a bare host, a part after a hash (ODK joins with a slash),
        // or a part make would not read as a word.
        for (String address : new String[] {"https://example.org",
            "https://example.org/shops#core", "https://example.org/my%20shop",
            "https://example.org/shop$(X)"}) {
            assertEquals(null, OdkConfigWriter.uriBaseSuffix(project("shopx", "Shop", address)
                    .adopting()), address);
        }
        // A project that starts from nothing has ODK's shape by rule.
        assertEquals(null, OdkConfigWriter.uriBaseSuffix(egg()));
        assertEquals("https://example.org/eggboil/eggboil", OdkConfigWriter.ontBase(egg()));
        assertEquals("https://example.org/eggboil/eggboil.owl", OdkConfigWriter.odkIri(egg()));
    }

    /**
     * ODK takes a project's own terms to begin with {@code <uribase>/<ID>_}: its base release
     * keeps what begins so, its quality report looks at it, its lists of terms hold it. The
     * terms of an ontology somebody already has begin with that ontology's own address. The
     * configuration names both - ODK's {@code namespaces} - and has the report go by them;
     * without that the base release of an adopted ontology held none of its terms (1.153.0,
     * live run: 0 classes).
     */
    @Test
    void anAdoptedOntologysOwnTermsAreWhereItsAddressIs() {
        String config = OdkConfigWriter.forNewProject(project("shop", "Shop",
                "https://example.org/shop.owl").adopting());

        assertTrue(config.contains("repo: shop\n"
                + "namespaces:\n"
                + "  - https://example.org/shop\n"
                + "  - https://example.org/SHOP_\n"
                + "robot_report:\n"), config);
        assertTrue(config.contains("  custom_profile: TRUE\n  use_base_iris: TRUE\n"), config);
        PyObject read = OdkProjectModel.load(config);
        java.util.List<String> named = new java.util.ArrayList<String>();
        for (Object namespace : de.fizkarlsruhe.ise.ontoboard.odk.seed.Py.iterate(
                read.get("namespaces"))) {
            named.add(de.fizkarlsruhe.ise.ontoboard.odk.seed.Py.str(namespace));
        }
        assertEquals(java.util.Arrays.asList("https://example.org/shop",
                "https://example.org/SHOP_"), named, "ODK reads them");

        // With an address of another shape: that address, and where ODK names new terms.
        String odd = OdkConfigWriter.forNewProject(project("shopcore", "Shop",
                "https://example.org/vocab/ontology").adopting());
        assertTrue(odd.contains("namespaces:\n"
                + "  - https://example.org/vocab/ontology\n"
                + "  - https://example.org/vocab/SHOPCORE_\n"), odd);

        // An address make would not read as one word goes into no Makefile.
        String unsafe = OdkConfigWriter.forNewProject(project("shopx", "Shop",
                "https://example.org/shop$(X)").adopting());
        assertFalse(unsafe.contains("namespaces"), unsafe);
        assertFalse(unsafe.contains("use_base_iris"), unsafe);

        // A project that starts from nothing names its terms as ODK does: nothing to say.
        assertFalse(OdkConfigWriter.forNewProject(egg()).contains("namespaces"));
        assertFalse(OdkConfigWriter.forNewProject(egg()).contains("use_base_iris"));
    }

    /** ODK reads the file with PyYAML; what was typed must be what it reads. */
    @ParameterizedTest
    @ValueSource(strings = {"Say \"cheese\"", "Back\\slash C:\\ontologies", "Colon: and # hash",
        "  padded  ", "\u00c4pfel & \u00d6l - 100 %", "'single' quotes", "{braces} [brackets]",
        "yes", "~", "a\ttab"})
    void aTitleIsReadBackByOdkAsItWasTyped(String title) {
        OdkProjectConfig config = project("eggboil", title,
                "https://example.org/eggboil/eggboil.owl");

        PyObject project = OdkProjectModel.load(OdkConfigWriter.forNewProject(config));

        assertEquals(config.getTitle(), project.get("title"));
        assertEquals(config.getDescription(), project.get("description"));
        assertEquals("eggboil", project.get("id"));
        assertEquals("https://example.org/eggboil", project.get("uribase"));
    }

    /** A title is one line of a comment and one value; a line break in it stays inside both. */
    @Test
    void aLineBreakInATitleDoesNotStartANewLineOfTheConfig() {
        OdkProjectConfig config = project("eggboil", "Egg\nid: hijacked",
                "https://example.org/eggboil/eggboil.owl");

        String text = OdkConfigWriter.forNewProject(config);
        PyObject project = OdkProjectModel.load(text);

        assertEquals("eggboil", project.get("id"));
        assertEquals("Egg\nid: hijacked", project.get("title"));
        assertEquals(1, text.split("\nid: ", -1).length - 1, text);
    }

    /** An address with a blank or a hash in it is not a plain YAML value; it is quoted then. */
    @Test
    void anAddressThatIsNotAPlainValueIsQuoted() {
        OdkProjectConfig config = new OdkProjectConfig("eggboil", "Egg", "",
                "https://example.org/my eggs/eggboil.owl",
                "https://example.org/licence #1", null);

        String text = OdkConfigWriter.forNewProject(config);
        PyObject project = OdkProjectModel.load(text);

        assertEquals("https://example.org/my eggs", project.get("uribase"));
        assertEquals("https://example.org/licence #1", project.get("license"));
    }

    /** What ODK makes of it: its own defaults, a profile of the project's own, five checks. */
    @Test
    void odkReadsItAsMeant() {
        PyObject project = OdkProjectModel.load(OdkConfigWriter.forNewProject(egg()));
        Map<?, ?> report = (Map<?, ?>) project.get("robot_report");

        assertEquals("eggboil", project.get("repo"));
        assertEquals(Boolean.TRUE, report.get("custom_profile"));
        assertEquals(Boolean.TRUE, report.get("use_labels"));
        assertEquals("ERROR", report.get("fail_on"));
        assertEquals("[owldef-self-reference, iri-range, label-with-iri, multiple-replaced_by, "
                + "check_labels]", String.valueOf(report.get("custom_sparql_checks")));
        assertEquals("[full, base]", String.valueOf(project.get("release_artefacts")),
                "left to ODK's default");
    }

    @Test
    void itsFirstLineSaysWhoWroteIt() {
        String written = OdkConfigWriter.forNewProject(egg());

        assertTrue(written.startsWith("# ODK configuration for Test kitchen ontology, written by "
                + "OntoBoard for ODK v1.6.1.\n"), written);
        assertTrue(OdkConfigWriter.isOntoBoards(written));
        assertTrue(OdkConfigWriter.isOntoBoards(written.replace("\n", "\r\n")),
                "saved on Windows");
        assertTrue(OdkConfigWriter.isOntoBoards(written + "import_group:\n  products: []\n"),
                "edited later, by hand or by OntoBoard");
        assertFalse(OdkConfigWriter.isOntoBoards(Goldens.corpusConfig(Goldens.corpusNames()
                .get(0))), "one of ODK's own");
        assertFalse(OdkConfigWriter.isOntoBoards("id: x\n# ODK configuration for X, written by "
                + "OntoBoard for ODK v1.6.1.\n"), "the first line, not any line");
        assertFalse(OdkConfigWriter.isOntoBoards(""));
    }
}
