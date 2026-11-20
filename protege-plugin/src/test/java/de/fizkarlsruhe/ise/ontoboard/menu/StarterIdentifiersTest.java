package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.robot.TemplateSheet;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * The identifiers of a new sheet's example rows continue after those of the sheets already
 * there (1.155.0): the user's nfdicore had two sheets made with New sheet... holding the same
 * three, NFDI_0030000 to NFDI_0030002 - only the ranges and the ontology were looked at.
 */
class StarterIdentifiersTest {

    @Test
    void aSecondNewSheetContinuesAfterTheFirst(@TempDir File root) throws Exception {
        File ontologyFolder = new File(root, "src/ontology");
        assertTrue(ontologyFolder.mkdirs());
        Files.write(new File(ontologyFolder, "eggboil-idranges.owl").toPath(),
                Goldens.seedTree("eggboil").get("src/ontology/eggboil-idranges.owl"));
        File edit = new File(ontologyFolder, "eggboil-edit.owl");
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology();
        File sheets = new File(root, "src/templates");
        assertTrue(sheets.mkdirs());

        String[] first = TemplateAction.starterIdentifiersFor(ontology, edit, sheets);
        Files.write(new File(sheets, "terms.tsv").toPath(), TemplateSheet
                .starterWithIris(first[0], first[1], first[2]).getBytes(StandardCharsets.UTF_8));
        String[] second = TemplateAction.starterIdentifiersFor(ontology, edit, sheets);

        assertTrue(Collections.disjoint(Arrays.asList(first), Arrays.asList(second)),
                Arrays.toString(first) + " " + Arrays.toString(second));
        assertEquals(numberOf(first[2]) + 1, numberOf(second[0]));
    }

    private static long numberOf(String iri) {
        return Long.parseLong(iri.substring(iri.lastIndexOf('_') + 1));
    }
}
