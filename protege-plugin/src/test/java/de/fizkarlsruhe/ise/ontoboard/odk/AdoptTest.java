package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project made from the ontology somebody already has. The guide used to say: make an empty
 * project, then merge your file into it by hand - and the IRI rule refused the IRI they had.
 */
class AdoptTest {

    @Test
    void anIdIsMadeFromTheAddressTheOntologyAlreadyHas() {
        assertEquals("shop_ontology", ProjectWizard.idFrom("https://example.org/Shop-Ontology.owl"));
        assertEquals("materials", ProjectWizard.idFrom("https://example.org/materials#"));
        assertEquals("o3d_print", ProjectWizard.idFrom("https://example.org/3d-print.ttl"));
    }

    @Test
    void anAdoptedAddressIsKeptThoughItDoesNotEndInTheId(@TempDir File folder) {
        OdkProjectConfig plain = new OdkProjectConfig("shop", "Shop", "", "https://example.org/Shop-Ontology.owl",
                "https://creativecommons.org/licenses/by/4.0/", folder);
        assertThrows(IllegalArgumentException.class, plain::validate,
                "a new project's IRI ends in its ID");

        OdkProjectConfig adopted = new OdkProjectConfig("shop", "Shop", "",
                "https://example.org/Shop-Ontology.owl",
                "https://creativecommons.org/licenses/by/4.0/", folder).adopting();
        adopted.validate();
        assertTrue(adopted.isAdopted());
        assertEquals("https://example.org/Shop-Ontology.owl", adopted.getBaseIri());
    }
}
