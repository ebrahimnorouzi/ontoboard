package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two release conventions, and the IRI each one publishes.
 *
 * <p>The numbers here are not invented. MatWerk's published {@code mwo.owl} carries
 * {@code owl:versionIRI http://purls.helmholtz-metadaten.de/mwo/3.0.2}, cut by a
 * {@code release.sh} that sets {@code VERSION=3.0.2} and {@code PRIOR_VERSION=3.0.1}; the ODK
 * Makefile it overrides defaults to {@code VERSION = $(TODAY)} and
 * {@code $(ONTBASE)/releases/$(VERSION)/$@}. Both shapes are asserted against those exact
 * strings, because a version IRI is a permanent citation handle and "close enough" is a
 * different ontology.
 */
class OntologyVersionTest {

    private static final String MWO = "http://purls.helmholtz-metadaten.de/mwo";
    private static final String OBO = "http://purl.obolibrary.org/obo/mwo.owl";

    // ---------- which convention is this ----------

    @Test
    void aDateIsRecognisedAsTheDatedConvention() {
        assertEquals(OntologyVersion.Scheme.DATE,
                OntologyVersion.of("2026-10-12").getScheme());
    }

    @Test
    void aThreeFieldNumberIsRecognisedAsASemanticVersion() {
        assertEquals(OntologyVersion.Scheme.SEMANTIC, OntologyVersion.of("3.0.2").getScheme());
    }

    @Test
    void twoFieldsAreEnoughBecausePeopleWriteThemThatWay() {
        assertEquals(OntologyVersion.Scheme.SEMANTIC, OntologyVersion.of("3.1").getScheme());
        assertEquals("3.1.1", OntologyVersion.of("3.1").nextPatch().getText());
    }

    /**
     * The git tag and the ontology version differ by one character in ODK's own Makefile
     * ({@code GHVERSION=v$(VERSION)}), so somebody pasting the tag means the version. Normalised
     * away rather than kept: a versionIRI ending {@code /v3.0.2} beside a published history of
     * {@code /3.0.2} is a break nobody intended.
     */
    @Test
    void theGitTagsLeadingVIsNormalisedAway() {
        assertEquals("3.0.2", OntologyVersion.of("v3.0.2").getText());
        assertEquals(MWO + "/3.0.2", OntologyVersion.of("v3.0.2").versionIriUnder(MWO));
    }

    @Test
    void aVersionThatIsNeitherShapeIsRefusedAndTheMessageNamesBoth() {
        for (String bad : new String[] {"today", "30/08/2026", "2026-8-3", "", null, "v3",
            "3.0.2.1", "release-1"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> OntologyVersion.of(bad), "accepted " + bad);
            assertTrue(refused.getMessage().contains("YYYY-MM-DD"), refused.getMessage());
            assertTrue(refused.getMessage().contains("3.0.2"), refused.getMessage());
        }
    }

    @Test
    void askingForOneSchemeAndGettingTheOtherIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> OntologyVersion.dated("3.0.2"));
        assertThrows(IllegalArgumentException.class,
                () -> OntologyVersion.semantic("2026-10-12"));
    }

    // ---------- the IRI each one publishes ----------

    /** The exact IRI MatWerk's published mwo.owl carries. */
    @Test
    void aSemanticReleaseIsPublishedAsTheBaseIriAndTheVersion() {
        assertEquals("http://purls.helmholtz-metadaten.de/mwo/3.0.2",
                OntologyVersion.semantic("3.0.2").versionIriUnder(MWO));
    }

    /** OBO's pattern, unchanged: this is what every release before 1.117.0 produced. */
    @Test
    void aDatedReleaseKeepsTheOboReleasesPattern() {
        assertEquals("http://purl.obolibrary.org/obo/mwo/releases/2026-10-12/mwo.owl",
                OntologyVersion.dated("2026-10-12").versionIriUnder(OBO));
    }

    /**
     * An ontology IRI ending in .owl keeps its name out of the semantic IRI, because the stem is
     * the namespace the project owns. Without this, mwo.owl 3.0.2 would publish as
     * {@code .../mwo.owl/3.0.2} - which is a path under a document rather than a version of it.
     */
    @Test
    void theOwlExtensionIsNotPartOfASemanticVersionIri() {
        assertEquals("http://purl.obolibrary.org/obo/mwo/3.0.2",
                OntologyVersion.semantic("3.0.2").versionIriUnder(OBO));
    }

    @Test
    void anIriWithNothingToBuildFromYieldsNoVersionIri() {
        assertNull(OntologyVersion.semantic("3.0.2").versionIriUnder(null));
        assertNull(OntologyVersion.dated("2026-10-12").versionIriUnder(null));
    }

    // ---------- ordering ----------

    /**
     * The trap the whole class exists for. Sorted as text, 3.9.0 comes after 3.10.0, so a
     * project on its tenth minor release would compare every release against the wrong
     * predecessor and report everything added since 3.9.0 as new again.
     */
    @Test
    void tenComesAfterNineRatherThanBeforeIt() {
        assertTrue(OntologyVersion.of("3.10.0").compareTo(OntologyVersion.of("3.9.0")) > 0,
                "3.10.0 must sort after 3.9.0; as text it does not");
        assertTrue("3.10.0".compareTo("3.9.0") < 0, "text ordering really is the wrong answer");
    }

    @Test
    void theNewestOfAMixedListIsFoundByNumber() {
        List<OntologyVersion> versions = new ArrayList<OntologyVersion>();
        for (String each : Arrays.asList("3.9.0", "3.10.1", "3.2.0", "4.0.0", "3.10.0")) {
            versions.add(OntologyVersion.of(each));
        }
        assertEquals("4.0.0", OntologyVersion.newestFirst(versions).get(0).getText());
    }

    @Test
    void aPreReleaseSortsBelowTheSameVersionWithoutOne() {
        assertTrue(OntologyVersion.of("3.1.0-rc1").compareTo(OntologyVersion.of("3.1.0")) < 0);
        assertTrue(OntologyVersion.of("3.1.0-rc1").isPreRelease());
        assertFalse(OntologyVersion.of("3.1.0").isPreRelease());
    }

    @Test
    void datesStillSortAsDates() {
        assertTrue(OntologyVersion.of("2026-10-01").compareTo(OntologyVersion.of("2026-09-30"))
                > 0);
    }

    @Test
    void twoWaysOfWritingTheSameVersionAreTheSameVersion() {
        assertEquals(OntologyVersion.of("3.0.2"), OntologyVersion.of("v3.0.2"));
        assertEquals(OntologyVersion.of("3.0.2").hashCode(),
                OntologyVersion.of("v3.0.2").hashCode());
    }

    // ---------- what to release next ----------

    @Test
    void theIncrementsAreTheThreeSemanticOnes() {
        OntologyVersion current = OntologyVersion.of("3.0.2");

        assertEquals("3.0.3", current.nextPatch().getText());
        assertEquals("3.1.0", current.nextMinor().getText());
        assertEquals("4.0.0", current.nextMajor().getText());
    }

    /** A patch is the only increment safe to propose: it claims nothing moved. */
    @Test
    void theProposalForASemanticProjectIsThePatchAfterIt() {
        assertEquals("3.0.3", OntologyVersion.of("3.0.2").suggestNext("2026-10-12").getText());
    }

    /** There is nothing to decide about a dated release; it is whatever day it is. */
    @Test
    void theProposalForADatedProjectIsToday() {
        assertEquals("2026-10-12",
                OntologyVersion.of("2026-01-01").suggestNext("2026-10-12").getText());
    }

    @Test
    void incrementingADateIsRefusedRatherThanInvented() {
        assertThrows(IllegalStateException.class,
                () -> OntologyVersion.of("2026-10-12").nextMinor());
    }

    // ---------- reading the scheme off the artefact ----------

    @Test
    void theVersionInfoIsBelievedFirstBecauseItHoldsThePlainVersion() {
        assertEquals("3.0.2", OntologyVersion.declaredBy("3.0.2", null).getText());
        assertEquals(OntologyVersion.Scheme.SEMANTIC,
                OntologyVersion.declaredBy("3.0.2", null).getScheme());
    }

    /** MatWerk's shape, which is the case this release exists for. */
    @Test
    void aSemanticVersionIriIsRecognisedWithoutAnyVersionInfo() {
        OntologyVersion found =
                OntologyVersion.declaredBy(null, "http://purls.helmholtz-metadaten.de/mwo/3.0.2");

        assertEquals("3.0.2", found.getText());
        assertEquals(OntologyVersion.Scheme.SEMANTIC, found.getScheme());
    }

    /** OBO's shape, where the version is the segment before the file name. */
    @Test
    void aDatedVersionIriIsRecognisedThroughTheFileNameAfterIt() {
        OntologyVersion found = OntologyVersion.declaredBy(null,
                "http://purl.obolibrary.org/obo/mwo/releases/2026-08-30/mwo.owl");

        assertEquals("2026-08-30", found.getText());
        assertEquals(OntologyVersion.Scheme.DATE, found.getScheme());
    }

    @Test
    void anOntologyThatDeclaresNothingDeclaresNothing() {
        assertNull(OntologyVersion.declaredBy(null, null));
        assertNull(OntologyVersion.declaredBy("", "http://purl.obolibrary.org/obo/mwo.owl"));
        assertNull(OntologyVersion.declaredBy("the second one", null));
    }

    // ---------- the dialog ----------

    @Test
    void everySchemeHasALabelAndAnExampleAndTheLabelsRoundTrip() {
        assertEquals(OntologyVersion.Scheme.values().length,
                OntologyVersion.Scheme.labels().size());
        for (OntologyVersion.Scheme scheme : OntologyVersion.Scheme.values()) {
            assertFalse(scheme.getLabel().trim().isEmpty());
            assertEquals(scheme, OntologyVersion.Scheme.byLabel(scheme.getLabel()));
            assertEquals(scheme, OntologyVersion.of(scheme.getExample()).getScheme(),
                    scheme + " offers an example that is not one of its own versions");
        }
    }

    /** An unknown label falls back to what ODK itself does, which is to date the release. */
    @Test
    void anUnknownLabelFallsBackToTheOdkDefault() {
        assertEquals(OntologyVersion.Scheme.DATE, OntologyVersion.Scheme.byLabel("something"));
        assertEquals(OntologyVersion.Scheme.DATE, OntologyVersion.Scheme.byLabel(null));
    }

    @Test
    void theDescriptionSaysWhichConventionItIs() {
        assertTrue(OntologyVersion.of("3.0.2").describe().contains("semantic"));
        assertTrue(OntologyVersion.of("2026-10-12").describe().contains("dated"));
    }

    @Test
    void isWrittenAnswersForBothSchemesWithoutThrowing() {
        assertTrue(OntologyVersion.isWritten("3.0.2", OntologyVersion.Scheme.SEMANTIC));
        assertFalse(OntologyVersion.isWritten("3.0.2", OntologyVersion.Scheme.DATE));
        assertFalse(OntologyVersion.isWritten("nonsense", OntologyVersion.Scheme.DATE));
        assertFalse(OntologyVersion.isWritten(null, OntologyVersion.Scheme.SEMANTIC));
    }
}
