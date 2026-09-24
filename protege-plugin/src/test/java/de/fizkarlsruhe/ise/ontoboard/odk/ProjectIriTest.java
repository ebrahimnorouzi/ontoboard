package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;

/**
 * The namespace a project owns.
 *
 * <p>Three places worked this out separately and each got a different wrong answer, so one release
 * could be identified three ways depending on whether it was built from the menu, from the
 * generated Makefile, or read from the javadoc. A version IRI is a permanent citation handle -
 * baked into the artefact, and not retractable once anybody has imported it.
 */
class ProjectIriTest {

    /** A real licence: ROBOT reports a missing ontology licence as an ERROR, and
     * the generated CI fails on errors, so a project without one fails its own build. */
    private static final String CC0 =
            "https://creativecommons.org/publicdomain/zero/1.0/";

    /** Strip the extension, never a path segment: the segment is the project's identity. */
    @Test
    void theStemIsTheIriWithoutItsExtension() {
        assertEquals("http://purl.obolibrary.org/obo/mwo",
                ProjectIri.stemOf("http://purl.obolibrary.org/obo/mwo.owl"));
        assertEquals("http://example.org/pizza",
                ProjectIri.stemOf("http://example.org/pizza"));
        assertEquals("https://w3id.org/mwo/mwo",
                ProjectIri.stemOf("https://w3id.org/mwo/mwo.owl"));
    }

    @Test
    void trailingDelimitersAreNotPartOfTheStem() {
        assertEquals("http://example.org/pizza", ProjectIri.stemOf("http://example.org/pizza/"));
        assertEquals("http://example.org/pizza", ProjectIri.stemOf("http://example.org/pizza#"));
    }

    @Test
    void theNameIsTheLastSegmentOfTheStem() {
        assertEquals("mwo", ProjectIri.nameOf("http://purl.obolibrary.org/obo/mwo.owl"));
        assertEquals("pizza", ProjectIri.nameOf("http://example.org/pizza"));
    }

    /** Real OBO releases go.owl as .../obo/go/releases/<date>/go.owl. */
    @Test
    void theReleaseIriFollowsTheOboPattern() {
        assertEquals("http://purl.obolibrary.org/obo/go/releases/2026-08-30/go.owl",
                ProjectIri.releaseIri("http://purl.obolibrary.org/obo/go.owl", "2026-08-30"));
    }

    @Test
    void nothingToWorkFromIsNotAnAnswer() {
        assertNull(ProjectIri.stemOf((String) null));
        assertNull(ProjectIri.stemOf((IRI) null));
        assertNull(ProjectIri.stemOf("   "));
        assertNull(ProjectIri.releaseIri(null, "2026-08-30"));
    }

    // ---------- the invariant that actually matters ----------

    /**
     * The menu and the generated Makefile must mint the same version IRI, or one release has two
     * permanent identifiers depending on how somebody happened to build it.
     *
     * <p>They did not. For the wizard's own default base IRI the Makefile emitted
     * {@code .../obo/mwo.owl/releases/<date>/mwo.owl} - no stripping at all - while
     * {@code Release.versionIri} emitted {@code .../obo/releases/<date>/mwo.owl}, and the javadoc
     * documented a third form that neither produced. This test compares the two generators
     * directly rather than asserting a literal, so they cannot drift apart again.
     */
    @Test
    void theScaffoldedMakefileAndTheReleaseActionAgree(@TempDir Path dir) throws Exception {
        // The id and the IRI name always match - OdkProjectConfig.validate now refuses anything
        // else, because ODK names the artefact after the id and the version IRI after the IRI.
        for (String baseIri : new String[] {
            "http://purl.obolibrary.org/obo/mwo.owl",
            "http://example.org/mwo",
            "https://w3id.org/mwo/mwo.owl"}) {

            Path into = Files.createTempDirectory(dir, "proj");
            OdkProjectConfig config = new OdkProjectConfig("mwo", "MWO", "", baseIri, CC0,
                    into.toFile());
            OdkScaffold.create(config);
            String makefile = new String(Files.readAllBytes(
                    new File(config.getProjectRoot(), "src/ontology/Makefile").toPath()),
                    Charset.forName("UTF-8"));

            // What the Makefile would expand for a given date, with make's own variables filled in.
            String fromMake = null;
            for (String line : makefile.split("\r?\n")) {
                if (line.contains("--version-iri")) {
                    fromMake = line.substring(line.indexOf('"') + 1, line.lastIndexOf('"'))
                            .replace("$(TODAY)", "2026-08-30")
                            .replace("$(ONT)", "mwo");
                }
            }
            String fromMenu = Release.versionIri(
                    IRI.create(config.getBaseIri()), "2026-08-30").toString();

            assertEquals(fromMake, fromMenu,
                    "the Makefile and the Release action disagree for base IRI " + baseIri);
            assertTrue(fromMenu.contains("/releases/2026-08-30/"), fromMenu);
            assertTrue(fromMenu.endsWith(".owl"), fromMenu);
        }
    }
}
