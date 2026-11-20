package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;

/**
 * What a release refused for terms that are gone tells the person to do, by whose terms they are.
 * MWO's release after a Build was refused for 20 PMDco terms that upstream no longer brings with
 * MWO's module, and told "Bring them back and mark them owl:deprecated with a replacement" - which
 * MWO cannot do for PMDco's terms (Phase 7, probe host, 2026-11-20).
 */
class RemovedTermsAdviceTest {

    private static final String MWO = "http://purls.helmholtz-metadaten.de/mwo/MWO_";

    private static ReleaseDiff.TermChange gone(String iri, String label) {
        return new ReleaseDiff.TermChange(IRI.create(iri), EntityType.CLASS,
                ReleaseDiff.Change.REMOVED, label, "");
    }

    private static final ReleaseDiff.TermChange GRAIN_SIZE =
            gone("https://w3id.org/pmd/co/PMD_0000503", "ASTM grainsize");
    private static final ReleaseDiff.TermChange TEMPERATURE =
            gone("https://w3id.org/pmd/co/PMD_0000967", "temperature");
    private static final ReleaseDiff.TermChange OWN =
            gone("http://purls.helmholtz-metadaten.de/mwo/MWO_0001049",
                    "crystal plasticity simulation");

    @Test
    void termsAllFromImportsAreToBeNamedInTheirLists() {
        String said = OdkRelease.whyTermsAreMissed(Arrays.asList(GRAIN_SIZE, TEMPERATURE),
                "3.0.2", MWO);

        assertTrue(said.startsWith("2 terms that the 3.0.2 release published are gone from this "
                + "one, PMD_0000503 (ASTM grainsize) among them"), said);
        assertTrue(said.contains("none of them this project's own"), said);
        assertTrue(said.contains("_terms.txt"), said);
        assertFalse(said.contains("owl:deprecated"), "not ours to deprecate: " + said);
        assertTrue(said.contains("nothing was copied to releases/, committed or tagged"), said);
        assertTrue(said.contains(OdkRelease.REFUSE_LABEL), said);
    }

    @Test
    void theProjectsOwnAreToBeBroughtBackAndDeprecated() {
        String said = OdkRelease.whyTermsAreMissed(Arrays.asList(OWN), "3.0.2", MWO);

        assertTrue(said.contains("owl:deprecated"), said);
        assertFalse(said.contains("_terms.txt"), said);
    }

    @Test
    void ofBothKindsEachIsToldItsOwnWay() {
        String said = OdkRelease.whyTermsAreMissed(Arrays.asList(GRAIN_SIZE, OWN, TEMPERATURE),
                "3.0.2", MWO);

        assertTrue(said.contains("1 of them this project's own, 2 from its imports"), said);
        assertTrue(said.contains("owl:deprecated"), said);
        assertTrue(said.contains("_terms.txt"), said);
    }

    /** Without the project's prefix nothing tells whose a term is: both ways are said. */
    @Test
    void withoutThePrefixBothWaysAreSaid() {
        String said = OdkRelease.whyTermsAreMissed(Arrays.asList(GRAIN_SIZE), "3.0.2", null);

        assertTrue(said.contains("owl:deprecated"), said);
        assertTrue(said.contains("_terms.txt"), said);
    }

    @Test
    void theProjectsPrefixIsReadFromItsIdRanges(@TempDir File ontology) throws Exception {
        assertNull(OdkRelease.ownTermPrefix(ontology, "mwo"), "no ID ranges, no prefix");
        Files.write(new File(ontology, "mwo-idranges.owl").toPath(), ("Prefix: idprefix: "
                + "<http://purl.obolibrary.org/obo/IAO_0000599>\n"
                + "Prefix: idsfor: <http://purl.obolibrary.org/obo/IAO_0000598>\n"
                + "Prefix: iddigits: <http://purl.obolibrary.org/obo/IAO_0000596>\n\n"
                + "Ontology: <http://purls.helmholtz-metadaten.de/mwo/mwo-idranges.owl>\n\n"
                + "Annotations:\n    idsfor: \"MWO\",\n    idprefix: \"" + MWO + "\",\n"
                + "    iddigits: 7\n").getBytes(StandardCharsets.UTF_8));

        assertEquals(MWO, OdkRelease.ownTermPrefix(ontology, "mwo"));
    }
}
