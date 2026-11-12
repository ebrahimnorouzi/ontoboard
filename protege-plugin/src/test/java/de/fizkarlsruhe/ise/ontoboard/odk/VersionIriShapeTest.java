package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The shape of a release's version IRI, which the person releasing chooses.
 *
 * <p>ODK writes {@code <ONTBASE>/releases/<version>/<file>}. nfdicore and mwo publish
 * {@code <ONTBASE>/<version>/<file>}; OntoBoard 1.135.x wrote {@code <ONTBASE>/<version>}, one
 * IRI for every file of a release. A version IRI is a citation handle and cannot be taken back
 * once somebody has imported it, so a project keeps the shape it has - and the shape reaches
 * ODK's build the way nfdicore's own release script hands it over: as the make variable
 * {@code ANNOTATE_ONTOLOGY_VERSION}, on make's command line.
 */
class VersionIriShapeTest {

    private static final String EGG = "https://example.org/eggboil/eggboil";

    @Test
    void theShapesGiveTheseIris() {
        assertEquals("https://example.org/eggboil/eggboil/releases/1.0.0/eggboil.owl",
                VersionIriShape.of(VersionIriShape.Kind.ODK).iriFor(EGG, "1.0.0", "eggboil.owl"));
        assertEquals("https://example.org/eggboil/eggboil/1.0.0/eggboil.owl",
                VersionIriShape.of(VersionIriShape.Kind.VERSION_AND_FILE)
                        .iriFor(EGG, "1.0.0", "eggboil.owl"));
        assertEquals("https://example.org/eggboil/eggboil/1.0.0",
                VersionIriShape.of(VersionIriShape.Kind.VERSION_ONLY)
                        .iriFor(EGG, "1.0.0", "eggboil.owl"));
        assertEquals("https://example.org/eggboil/eggboil/v1.0.0/eggboil-full.owl",
                VersionIriShape.custom("{ontbase}/v{version}/{file}")
                        .iriFor(EGG, "1.0.0", "eggboil-full.owl"));
    }

    /** What the Makefile ODK seeds says by default is the ODK shape, to the letter. */
    @Test
    void odksShapeIsTheSeededMakefilesOwn() {
        String makefile = new String(Goldens.seedTree("eggboil").get("src/ontology/Makefile"),
                StandardCharsets.UTF_8);

        assertTrue(makefile.contains("ANNOTATE_ONTOLOGY_VERSION = annotate -V "
                + "$(ONTBASE)/releases/$(VERSION)/$@ --annotation owl:versionInfo $(VERSION)\n"));
        assertNull(VersionIriShape.of(VersionIriShape.Kind.ODK).annotateArgument(EGG, "1.0.0"),
                "nothing is handed to make: its own default is this shape");
    }

    /** The pizza example was released by 1.135.x: one IRI for the whole release. */
    @Test
    void pizzasReleaseIsVersionOnly() {
        VersionIriShape shape = VersionIriShape.detect(
                "https://ebrahimnorouzi.github.io/pizza-ontology/pizza/1.0.0",
                "https://ebrahimnorouzi.github.io/pizza-ontology/pizza", "1.0.0", "pizza.owl");

        assertEquals(VersionIriShape.Kind.VERSION_ONLY, shape.kind());
    }

    @Test
    void aReleasesShapeIsReadOffItsVersionIri() {
        assertEquals(VersionIriShape.Kind.ODK, VersionIriShape.detect(
                EGG + "/releases/2026-11-11/eggboil.owl", EGG, "2026-11-11", "eggboil.owl")
                .kind());
        assertEquals(VersionIriShape.Kind.VERSION_AND_FILE, VersionIriShape.detect(
                "https://nfdi.fiz-karlsruhe.de/ontology/3.0.5/nfdicore.owl",
                "https://nfdi.fiz-karlsruhe.de/ontology", "3.0.5", "nfdicore.owl").kind());
        assertNull(VersionIriShape.detect("https://example.org/somewhere/else", EGG, "1.0.0",
                "eggboil.owl"), "not one of the three: nothing is guessed");
        assertNull(VersionIriShape.detect(null, EGG, "1.0.0", "eggboil.owl"));
    }

    /**
     * nfdicore's release.sh:
     * {@code ANNOTATE_ONTOLOGY_VERSION="annotate -V $ONTBASE/$VERSION/\$@ --annotation
     * owl:versionInfo $VERSION"}. The file's place is make's {@code $@}: ODK's Makefile uses
     * the variable in the recipe of every product, each with its own name.
     */
    @Test
    void theAnnotateArgumentIsWhatNfdicoresScriptPasses() {
        assertEquals("annotate -V https://nfdi.fiz-karlsruhe.de/ontology/3.0.5/$@ "
                + "--annotation owl:versionInfo 3.0.5",
                VersionIriShape.of(VersionIriShape.Kind.VERSION_AND_FILE)
                        .annotateArgument("https://nfdi.fiz-karlsruhe.de/ontology", "3.0.5"));
        assertEquals("annotate -V " + EGG + "/1.0.0 --annotation owl:versionInfo 1.0.0",
                VersionIriShape.of(VersionIriShape.Kind.VERSION_ONLY)
                        .annotateArgument(EGG, "1.0.0"));
        assertEquals("annotate -V " + EGG + "/v1.0.0/$@ --annotation owl:versionInfo 1.0.0",
                VersionIriShape.custom("{ontbase}/v{version}/{file}")
                        .annotateArgument(EGG, "1.0.0"));
    }

    @Test
    void aPatternOfTheirOwnIsShownAsTyped() {
        // Blanks and a line break around it are a paste's, and go; inside it they are refused.
        assertEquals("{ontbase}/v{version}/{file}",
                VersionIriShape.custom(" {ontbase}/v{version}/{file} \n").pattern());
        assertEquals(VersionIriShape.Kind.CUSTOM,
                VersionIriShape.custom("{ontbase}/v{version}/{file}").kind());
        assertEquals("{ontbase}/releases/{version}/{file}",
                VersionIriShape.of(VersionIriShape.Kind.ODK).pattern());
        // One of the three, typed out, is that one.
        assertEquals(VersionIriShape.Kind.VERSION_AND_FILE,
                VersionIriShape.custom("{ontbase}/{version}/{file}").kind());
    }

    /**
     * The pattern ends up in a command line that a shell inside ODK's container reads, and in
     * an IRI. What cannot be part of an IRI, or would mean something to make or to that shell,
     * is refused before anything is run - with what may be written instead.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "{ontbase}/{file}", "{ontbase}/{version}/{file}; rm -rf /work",
        "{ontbase}/$(shell id)/{version}", "{ontbase}/{version} {file}", "{ontbase}/`id`/{version}",
        "{ontbase}/{release}/{version}", "{version}", "ftp://example.org/{version}",
        "{ontbase}/{version}/\"{file}\"", "{ontbase}/{version}\n/{file}", "{ontbase}/{version}|x",
        "{ontbase}/{version}/{file}&x=1", "{ontbase}/{version}\\{file}", "{ontbase}/{version}/'{file}"})
    void aPatternThatIsNotAnIriOrNamesNoVersionIsRefused(String pattern) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> VersionIriShape.custom(pattern));

        assertTrue(refused.getMessage().contains("{version}"), refused.getMessage());
    }

    /** The same care for what fills the pattern: a version or a base with a blank in it. */
    @Test
    void whatFillsThePatternIsCheckedToo() {
        VersionIriShape shape = VersionIriShape.of(VersionIriShape.Kind.VERSION_AND_FILE);

        assertThrows(IllegalArgumentException.class, () -> shape.annotateArgument(EGG, "1.0 beta"));
        assertThrows(IllegalArgumentException.class, () -> shape.annotateArgument(EGG, "$(X)"));
        assertThrows(IllegalArgumentException.class,
                () -> shape.annotateArgument("https://example.org/my eggs", "1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> shape.iriFor(EGG, "", "eggboil.owl"));
    }

    /** A pattern that starts with its own address does not need the project's. */
    @Test
    void anAddressOfItsOwnIsAPattern() {
        assertEquals("https://w3id.org/eggs/1.0.0/eggboil.owl",
                VersionIriShape.custom("https://w3id.org/eggs/{version}/{file}")
                        .iriFor(EGG, "1.0.0", "eggboil.owl"));
    }
}
