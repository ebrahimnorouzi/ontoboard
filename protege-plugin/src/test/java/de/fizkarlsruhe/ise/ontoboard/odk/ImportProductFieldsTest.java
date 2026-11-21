package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every module type ODK has, and the fields of an import product ODK reads, as Import terms
 * declares them (1.155.0, asked by the user: "are all the methods considered ... each may have
 * some unique parameters and fields"). What is not given is not written: ODK's defaults stand.
 */
class ImportProductFieldsTest {

    private static final String CONFIG = "id: eggboil\nimport_group:\n  products:\n    - id: ro\n";

    @Test
    void everyModuleTypeOdkHasIsOffered() {
        List<String> types = new java.util.ArrayList<String>();
        for (ImportProducts.Kind kind : ImportProducts.Kind.values()) {
            types.add(kind.odkName());
        }

        assertTrue(types.containsAll(Arrays.asList("slme, BOT", "slme, TOP", "slme, STAR",
                "filter", "minimal", "mirror", "custom")), String.valueOf(types));
    }

    @Test
    void anSlmeModuleByTopIsDeclaredSo() {
        Map<String, String> fields = ImportProducts.declarationForImport("cob",
                "http://purl.obolibrary.org/obo/cob.owl", ImportProducts.Kind.SLME_TOP,
                ImportProducts.OdkFields.none()).getFields();

        assertEquals("slme", fields.get("module_type"));
        assertEquals("TOP", fields.get("module_type_slme"));
    }

    @Test
    void theFieldsGivenAreWrittenAndTheRestLeftToOdk() {
        ImportProducts.OdkFields given = ImportProducts.OdkFields.none()
                .withIndividuals("exclude")
                .withAnnotationProperties(Arrays.asList("rdfs:label", "IAO:0000115"))
                .withBaseIris(Arrays.asList("http://purl.obolibrary.org/obo/COB_"))
                .large(true).gzipped(true).usingTheBase(true).makingABase(true);

        String written = ImportProducts.withProduct(CONFIG,
                ImportProducts.declarationForImport("cob", "http://purl.obolibrary.org/obo/cob.owl",
                        ImportProducts.Kind.MODULE, given).getFields());

        assertTrue(written.contains("slme_individuals: exclude"), written);
        assertTrue(written.contains("annotation_properties: [\"rdfs:label\", \"IAO:0000115\"]"),
                written);
        assertTrue(written.contains("base_iris: [\"http://purl.obolibrary.org/obo/COB_\"]"),
                written);
        assertTrue(written.contains("is_large: true") && written.contains("use_gzipped: true")
                && written.contains("use_base: true") && written.contains("make_base: true"),
                written);
        List<ImportProducts.Product> read = ImportProducts.declaredIn(written);
        assertEquals("cob", read.get(1).getId());
        assertTrue(read.get(1).isLarge());
    }

    @Test
    void nothingGivenWritesNothingMore() {
        Map<String, String> fields = ImportProducts.declarationForImport("cob",
                "http://purl.obolibrary.org/obo/cob.owl", ImportProducts.Kind.MODULE,
                ImportProducts.OdkFields.none()).getFields();

        assertEquals(Arrays.asList("id", "module_type", "module_type_slme"),
                new java.util.ArrayList<String>(fields.keySet()));
        assertFalse(fields.containsKey("slme_individuals"));
    }

    /**
     * A custom module is kept as it is extracted here, until a rule of the project's own: with no
     * mirror, as ODK v1.6.1's custom rule otherwise needs the mirror first and then stops with
     * "This rule needs to be overwritten" - at the import itself, at every refresh and release
     * (the 1.155.0 review's I1).
     */
    @Test
    void aCustomModuleIsKeptAsItIs() {
        Map<String, String> fields = ImportProducts.declarationForImport("cob",
                "http://purl.obolibrary.org/obo/cob.owl", ImportProducts.Kind.CUSTOM,
                ImportProducts.OdkFields.none()).getFields();

        assertEquals("custom", fields.get("module_type"));
        assertFalse(fields.containsKey("module_type_slme"));
        assertEquals("no_mirror", fields.get("mirror_type"));
        ImportProducts.Product declared =
                ImportProducts.declaredIn(ImportProducts.withProduct(CONFIG,
                        new java.util.LinkedHashMap<String, String>(fields))).get(1);
        assertTrue(ImportProducts.isKeptAsItIs(declared), String.valueOf(fields));
    }
}
