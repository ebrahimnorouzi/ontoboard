package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What New ODK project's form may be given: the id, the title, the address and the licence of
 * a project, checked before a file is written.
 *
 * <p>Each of these was first written against the layout OntoBoard made up to 1.135, in that
 * layout's own test, after something went wrong with a project somebody had made. The layout
 * went with 1.153.0; the form's rules did not, and a project is refused for the same reasons.
 */
class OdkProjectConfigTest {

    /** A real licence: ROBOT reports a missing ontology licence as an ERROR, and a
     * project's CI fails on errors, so a project without one fails its own build. */
    private static final String CC0 =
            "https://creativecommons.org/publicdomain/zero/1.0/";

    private static OdkProjectConfig config(File into) {
        return new OdkProjectConfig("mwo", "Materials Workflow Ontology",
                "Workflows for materials science", "http://purl.obolibrary.org/obo/mwo.owl",
                "https://creativecommons.org/licenses/by/4.0/", into);
    }

    /**
     * F2: the GitHub owner goes into ODK's links, github.com/&lt;owner&gt;/&lt;repo&gt;. It is
     * optional - blank is none - and a name GitHub gives a user or an organisation: letters,
     * digits and hyphens, a hyphen neither first nor last.
     */
    @Test
    void theGitHubOwnerIsOptionalAndAGitHubName(@TempDir Path dir) {
        OdkProjectConfig config = config(dir.toFile());
        assertEquals("", config.getGithubOrg());
        config.setGithubOrg("  kitchen-lab ");
        assertEquals("kitchen-lab", config.getGithubOrg());
        config.validate();
        config.setGithubOrg(" ");
        assertEquals("", config.getGithubOrg());
        config.validate();

        for (String bad : new String[] {"kitchen lab", "-lab", "lab-", "kitchen/lab",
                "https://github.com/kitchen-lab"}) {
            config.setGithubOrg(bad);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    config::validate, bad);
            assertTrue(refused.getMessage().contains("'" + bad + "'"), refused.getMessage());
        }
    }

    @Test
    void anOmittedIriFallsBackToTheOboConvention(@TempDir Path dir) {
        OdkProjectConfig config =
                new OdkProjectConfig("abc", "ABC", "", "", CC0, dir.toFile());
        assertEquals("http://purl.obolibrary.org/obo/abc.owl", config.getBaseIri());
    }

    @Test
    void trailingDelimitersInTheIriAreNormalisedAway(@TempDir Path dir) {
        assertEquals("http://x.org/abc", new OdkProjectConfig(
                "abc", "ABC", "", "http://x.org/abc#", CC0, dir.toFile()).getBaseIri());
        assertEquals("http://x.org/abc", new OdkProjectConfig(
                "abc", "ABC", "", "http://x.org/abc/", CC0, dir.toFile()).getBaseIri());
    }

    /** The id becomes file names, Makefile variables and IRIs; a bad one breaks the build. */
    @Test
    void invalidOntologyIdsAreRejectedWithAnActionableMessage(@TempDir Path dir) {
        for (String bad : new String[] {"", "  ", "MWO", "my ont", "9lives", "my-ont"}) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new OdkProjectConfig(bad, "T", "", "", CC0, dir.toFile()).validate(),
                    "should have rejected id '" + bad + "'");
            assertTrue(thrown.getMessage().length() > 20,
                    "message must tell the user what to do: " + thrown.getMessage());
        }
    }

    /**
     * The most damaging value this dialog can accept, and the one that looks most harmless.
     *
     * <p>Found in a real generated project: the user typed "o" for the base IRI, and Protege
     * resolved it against the file location on save. The ontology became
     * {@code file:/C:/Users/.../src/ontology/o} and every term
     * {@code file:/C:/Users/.../o#apple} - the author's own folder path baked into the identity
     * of every concept. Nothing failed. It would simply never have meant the same thing to
     * anyone else, and two people collaborating would mint different IRIs for one concept and
     * never converge.
     */
    @Test
    void aRelativeBaseIriIsRejectedBecauseItWouldBakeInALocalFilePath(@TempDir Path dir) {
        for (String relative : new String[] {"o", "mmo", "obo/mmo.owl", "/absolute/path",
            "./here", "ftp://x.org/a", "www.example.org/mmo"}) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new OdkProjectConfig("abc", "ABC", "", relative, CC0, dir.toFile())
                            .validate(),
                    "should have rejected base IRI '" + relative + "'");
            assertTrue(thrown.getMessage().contains("absolute http"),
                    thrown.getMessage());
            assertTrue(thrown.getMessage().contains(relative),
                    "the message must quote what was entered: " + thrown.getMessage());
        }
    }

    @Test
    void anAbsoluteBaseIriIsAccepted(@TempDir Path dir) {
        new OdkProjectConfig("abc", "ABC", "", "http://purl.obolibrary.org/obo/abc.owl", CC0,
                dir.toFile()).validate();
        new OdkProjectConfig("abc", "ABC", "", "https://w3id.org/abc", CC0, dir.toFile())
                .validate();
    }

    /** The fallback has to survive its own validation, or an omitted IRI would be unusable. */
    @Test
    void theFallbackIriPassesValidation(@TempDir Path dir) {
        new OdkProjectConfig("abc", "ABC", "", "", CC0, dir.toFile()).validate();
    }

    @Test
    void aMissingTitleIsRejected(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> new OdkProjectConfig("abc", "", "", "", CC0, dir.toFile()).validate());
    }

    /** Writing into an existing folder could clobber someone's work. */
    @Test
    void refusesToWriteIntoAnExistingProjectFolder(@TempDir Path dir) {
        OdkProjectConfig config = config(dir.toFile());
        SeededProject.create(config);

        assertThrows(IllegalArgumentException.class, () -> SeededProject.create(config(dir.toFile())));
    }

    /**
     * The ontology id and the IRI's own name must be the same word.
     *
     * <p>ODK names the built artefact after the id ({@code $(ONT).owl}) and the release version
     * IRI after the ontology IRI. Let them differ and one release is published as
     * {@code .../pizza/releases/<date>/mwo.owl} - a namespace naming one thing, a file naming
     * another - with nothing failing. Found by a test comparing the two generators directly
     * rather than asserting a literal.
     */
    @Test
    void aBaseIriThatDisagreesWithTheIdIsRejected(@TempDir Path dir) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new OdkProjectConfig("mwo", "MWO", "", "http://example.org/pizza", CC0,
                        dir.toFile()).validate());

        assertTrue(thrown.getMessage().contains("pizza"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("mwo"), thrown.getMessage());
        assertTrue(thrown.getMessage().length() > 80,
                "the message must say what to do about it: " + thrown.getMessage());
    }

    @Test
    void aBaseIriThatAgreesWithTheIdIsAccepted(@TempDir Path dir) {
        new OdkProjectConfig("mwo", "MWO", "", "http://purl.obolibrary.org/obo/mwo.owl", CC0,
                dir.toFile()).validate();
        new OdkProjectConfig("pizza", "Pizza", "", "http://example.org/pizza", CC0,
                dir.toFile()).validate();
    }

    /**
     * An address typed after the one filled in, not over it, is refused.
     *
     * <p>The field fills itself from the ID, so that is the likeliest slip on the form, and it
     * passed every rule: it starts with http and ends in the ID. A study rehearsal made such a
     * project, with every term it would mint named under the two addresses run together.
     */
    @Test
    void aSecondAddressInTheBaseIriIsRefused(@TempDir Path dir) {
        for (String garbled : new String[] {
            "http://purl.obolibrary.org/obo/abc.owlhttps://example.org/abc/abc.owl",
            "http://purl.obolibrary.org/obo/https://example.org/abc/abc.owlabc.owl"}) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new OdkProjectConfig("abc", "ABC", "", garbled, CC0, dir.toFile())
                            .validate(), garbled);
            assertTrue(thrown.getMessage().contains("second address"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("Replace the address"), thrown.getMessage());
        }
    }

    /** A space cannot be part of an IRI, and every term is named under this one. */
    @Test
    void aSpaceInTheBaseIriIsRefused(@TempDir Path dir) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new OdkProjectConfig("abc", "ABC", "", "https://example.org/my abc/abc.owl",
                        CC0, dir.toFile()).validate());
        assertTrue(thrown.getMessage().contains("space"), thrown.getMessage());
    }

    /**
     * A project must not fail its own CI on its first push.
     *
     * <p>ROBOT's default profile - which this scaffold writes out in full - treats
     * missing_ontology_license as an ERROR, and the generated report recipe passes
     * --fail-on ERROR. So a licenceless project went red immediately, over something the wizard
     * never asked about.
     */
    @Test
    void aProjectWithoutALicenceIsRejectedBecauseItsOwnCiWouldFail(@TempDir Path dir) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new OdkProjectConfig("abc", "ABC", "", "http://x.org/abc.owl", "",
                        dir.toFile()).validate());

        assertTrue(thrown.getMessage().contains("ERROR"),
                "the message must say why, or it reads as bureaucracy: " + thrown.getMessage());
    }

    /**
     * An SPDX identifier is not a licence IRI. It goes straight into rdf:resource, where a
     * relative value is resolved against the file's own location on save - so the ontology's
     * licence would name a folder on one machine. This project's own pizza test used "CC0-1.0".
     */
    @Test
    void anSpdxIdentifierIsNotAcceptedAsALicence(@TempDir Path dir) {
        for (String notAnIri : new String[] {"CC0-1.0", "CC-BY-4.0", "MIT", "Apache-2.0"}) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new OdkProjectConfig("abc", "ABC", "", "http://x.org/abc.owl", notAnIri,
                            dir.toFile()).validate(),
                    "should have rejected licence '" + notAnIri + "'");
            assertTrue(thrown.getMessage().contains(notAnIri), thrown.getMessage());
        }
    }
}
