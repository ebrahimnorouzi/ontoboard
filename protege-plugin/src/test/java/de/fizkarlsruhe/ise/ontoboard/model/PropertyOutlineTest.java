package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * What the board's properties panel shows (1.155.0): with no property a box on a UML-like board,
 * the place to see a property's hierarchy, its characteristics and its domain and range.
 */
class PropertyOutlineTest {

    private static final String U = "http://example.org/u#";
    private static final String I = "http://example.org/imported#";

    private static OWLOntology ontology() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.loadOntologyFromOntologyDocument(new StringDocumentSource(
                "Prefix(:=<" + I + ">)\nPrefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                + "Ontology(<http://example.org/imported>\n"
                + "Declaration(ObjectProperty(:relatedTo))\n"
                + "AnnotationAssertion(rdfs:label :relatedTo \"related to\")\n)"));
        return manager.loadOntologyFromOntologyDocument(new StringDocumentSource(
                "Prefix(:=<" + U + ">)\nPrefix(i:=<" + I + ">)\nPrefix(owl:=<http://www.w3.org/2002/07/owl#>)\n"
                + "Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
                + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                + "Ontology(<http://example.org/u>\nImport(<http://example.org/imported>)\n"
                + "Declaration(Class(:Person)) Declaration(Class(:Pizza)) Declaration(Class(:Topping))\n"
                + "Declaration(ObjectProperty(:likes)) Declaration(ObjectProperty(:hasPart))\n"
                + "Declaration(ObjectProperty(:hasTopping)) Declaration(ObjectProperty(:isToppingOf))\n"
                + "Declaration(DataProperty(:name)) Declaration(DataProperty(:age))\n"
                + "AnnotationAssertion(rdfs:label :hasTopping \"has topping\")\n"
                + "SubObjectPropertyOf(:likes i:relatedTo)\n"
                + "SubObjectPropertyOf(:hasTopping :hasPart)\n"
                + "SubObjectPropertyOf(:hasPart owl:topObjectProperty)\n"
                + "TransitiveObjectProperty(:hasPart)\n"
                + "InverseObjectProperties(:hasTopping :isToppingOf)\n"
                + "ObjectPropertyDomain(:hasTopping :Pizza) ObjectPropertyRange(:hasTopping :Topping)\n"
                + "ObjectPropertyDomain(:likes ObjectUnionOf(:Person :Pizza))\n"
                + "FunctionalDataProperty(:age)\n"
                + "DataPropertyDomain(:age :Person) DataPropertyRange(:age xsd:integer)\n"
                + "SubDataPropertyOf(:age owl:topDataProperty)\n"
                + ")"));
    }

    private static List<String> labels(List<PropertyOutline.Entry> entries) {
        List<String> labels = new ArrayList<String>();
        for (PropertyOutline.Entry entry : entries) {
            labels.add(entry.getLabel());
        }
        return labels;
    }

    private static PropertyOutline.Entry find(List<PropertyOutline.Entry> entries, String label) {
        for (PropertyOutline.Entry entry : entries) {
            if (entry.getLabel().equals(label)) {
                return entry;
            }
            PropertyOutline.Entry under = find(entry.getChildren(), label);
            if (under != null) {
                return under;
            }
        }
        return null;
    }

    @Test
    void thePropertiesStandInTheirHierarchy() throws Exception {
        PropertyOutline outline = PropertyOutline.of(ontology());

        assertEquals(Arrays.asList("hasPart", "isToppingOf", "related to"), labels(outline.getObjectProperties()),
                "the roots, by name; OWL's top property is no root of its own");
        assertEquals(Collections.singletonList("has topping"),
                labels(find(outline.getObjectProperties(), "hasPart").getChildren()));
        assertEquals(Collections.singletonList("likes"),
                labels(find(outline.getObjectProperties(), "related to").getChildren()),
                "under an imported property it is a sub-property of");
        assertEquals(Arrays.asList("age", "name"), labels(outline.getDataProperties()));
    }

    @Test
    void eachSaysItsCharacteristicsAndItsDomainAndRange() throws Exception {
        PropertyOutline outline = PropertyOutline.of(ontology());

        PropertyOutline.Entry hasTopping = find(outline.getObjectProperties(), "has topping");
        assertEquals(Collections.singletonList("inverse of isToppingOf"), hasTopping.getCharacteristics());
        assertEquals("Pizza → Topping", hasTopping.getDomainToRange());
        assertEquals(Collections.singletonList("transitive"),
                find(outline.getObjectProperties(), "hasPart").getCharacteristics());
        assertEquals("Person or Pizza → ?", find(outline.getObjectProperties(), "likes").getDomainToRange());
        PropertyOutline.Entry age = find(outline.getDataProperties(), "age");
        assertEquals(Collections.singletonList("functional"), age.getCharacteristics());
        assertEquals("Person → integer", age.getDomainToRange());
        assertEquals("", find(outline.getDataProperties(), "name").getDomainToRange(),
                "nothing said, nothing shown");
    }

    @Test
    void anImportedPropertyIsMarkedAsOne() throws Exception {
        PropertyOutline outline = PropertyOutline.of(ontology());

        assertFalse(find(outline.getObjectProperties(), "related to").isOwn());
        assertTrue(find(outline.getObjectProperties(), "likes").isOwn());
        assertEquals(I + "relatedTo", find(outline.getObjectProperties(), "related to").getIri());
    }

    /**
     * Two properties each the other's sub-property are both listed: each counted as placed under
     * the other, neither was a root, and both vanished from the panel (the final review of
     * 1.155.0).
     */
    @Test
    void twoPropertiesEachTheOthersSubPropertyAreBothListed() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource("Prefix(:=<" + U
                        + ">)\nOntology(<http://example.org/circle>\n"
                        + "Declaration(ObjectProperty(:partOf))\n"
                        + "Declaration(ObjectProperty(:componentOf))\n"
                        + "SubObjectPropertyOf(:partOf :componentOf)\n"
                        + "SubObjectPropertyOf(:componentOf :partOf)\n)"));

        List<String> listed = new ArrayList<String>();
        collect(PropertyOutline.of(ontology).getObjectProperties(), listed);

        assertTrue(listed.contains(U + "partOf"), listed.toString());
        assertTrue(listed.contains(U + "componentOf"), listed.toString());
    }

    private static void collect(List<PropertyOutline.Entry> entries, List<String> into) {
        for (PropertyOutline.Entry entry : entries) {
            into.add(entry.getIri());
            collect(entry.getChildren(), into);
        }
    }
}
