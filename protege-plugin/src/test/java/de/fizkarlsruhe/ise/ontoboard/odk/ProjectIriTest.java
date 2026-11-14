package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Makefile OntoBoard then wrote, or read from the javadoc. A version IRI is a permanent citation handle -
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
     * The menu and the project's Makefile must mint the same version IRI, or one release has
     * two permanent identifiers depending on how somebody happened to build it.
     *
     * <p>They did not, when the Makefile was OntoBoard's own: for the wizard's default base
     * IRI it emitted {@code .../obo/mwo.owl/releases/<date>/mwo.owl} - no stripping at all -
     * while {@code Release.versionIri} emitted {@code .../obo/releases/<date>/mwo.owl}, and the
     * javadoc documented a third form that neither produced. The Makefile is ODK's now, and
     * the comparison is with that: the dated IRI a release made here, of an ontology in no
     * project, is the one ODK's Makefile gives a project of the same address. The two
     * generators are compared directly rather than against a literal, so they cannot drift
     * apart again.
     */
    @Test
    void odksMakefileAndTheReleaseActionAgree(@TempDir Path dir) throws Exception {
        // The id and the IRI name always match - OdkProjectConfig.validate refuses anything
        // else, because ODK names the artefact after the id and the version IRI after the IRI.
        for (String baseIri : new String[] {
            "http://purl.obolibrary.org/obo/mwo.owl",
            "http://example.org/mwo.owl",
            "https://w3id.org/mwo/mwo.owl"}) {

            Path into = Files.createTempDirectory(dir, "proj");
            OdkProjectConfig config = new OdkProjectConfig("mwo", "MWO", "", baseIri, CC0,
                    into.toFile());
            SeededProject.create(config);
            String makefile = new String(Files.readAllBytes(
                    new File(config.getProjectRoot(), "src/ontology/Makefile").toPath()),
                    Charset.forName("UTF-8"));

            // What ODK's Makefile expands for a given date and the main product, with
            // make's own variables filled in.
            String ontBase = null;
            String annotate = null;
            for (String line : makefile.split("\r?\n")) {
                if (line.startsWith("ONTBASE=")) {
                    ontBase = line.substring(line.indexOf('=') + 1).trim();
                }
                if (line.startsWith("ANNOTATE_ONTOLOGY_VERSION =")) {
                    annotate = line.substring(line.indexOf('=') + 1).trim();
                }
            }
            assertNotNull(ontBase, "ODK's Makefile no longer defines ONTBASE");
            assertNotNull(annotate, "ODK's Makefile no longer annotates the version this way");
            assertTrue(annotate.startsWith("annotate -V $(ONTBASE)/releases/$(VERSION)/$@ "),
                    annotate);
            String fromMake = annotate.substring("annotate -V ".length(),
                            annotate.indexOf(" --annotation"))
                    .replace("$(ONTBASE)", ontBase)
                    .replace("$(VERSION)", "2026-08-30")
                    .replace("$@", "mwo.owl");
            String fromMenu = Release.versionIri(
                    IRI.create(config.getBaseIri()), "2026-08-30").toString();

            assertEquals(fromMake, fromMenu,
                    "ODK's Makefile and the Release action disagree for base IRI " + baseIri);
            assertTrue(fromMenu.contains("/releases/2026-08-30/"), fromMenu);
            assertTrue(fromMenu.endsWith(".owl"), fromMenu);
        }
    }
}
