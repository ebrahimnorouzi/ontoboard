package de.fizkarlsruhe.ise.ontoboard.assistant.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;

/** A name the model asked for, found in the ontology exactly or not at all - never guessed. */
class NameResolverTest {

    private final OWLOntology pizza = MiniPizza.load();

    /**
     * 1.141.0: the terms a sentence names, so "Write as OWL…" can show the model that a word is a
     * property (the live run: hasReinforcement for 'reinforced with').
     */
    @Test
    void theTermsASentenceNamesInTheOrderTheyAppear() {
        java.util.List<org.semanticweb.owlapi.model.IRI> named = new NameResolver(pizza)
                .namedIn("Every vegetarian pizza hasTopping only cheese topping.");

        assertEquals(java.util.Arrays.asList(MiniPizza.iri("VegetarianPizza"),
                MiniPizza.iri("hasTopping"), MiniPizza.iri("CheeseTopping")), named);
    }

    @Test
    void aNameInsideALongerNameDoesNotCount() {
        assertEquals(java.util.Arrays.asList(MiniPizza.iri("VegetarianPizza"),
                MiniPizza.iri("Pizza")), new NameResolver(pizza)
                        .namedIn("A vegetarian pizza is a pizza."));
    }

    @Test
    void aSynonymDoesNotCount() {
        assertEquals(java.util.Collections.singletonList(MiniPizza.iri("Pizza")),
                new NameResolver(pizza).namedIn("Every veggie pizza is a Pizza."));
    }

    @Test
    void aNameTwoTermsShareIsLeftOut() {
        org.semanticweb.owlapi.model.OWLDataFactory f = pizza.getOWLOntologyManager()
                .getOWLDataFactory();
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLAnnotationAssertionAxiom(
                f.getRDFSLabel(), MiniPizza.iri("Country"), f.getOWLLiteral("place")));
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLAnnotationAssertionAxiom(
                f.getRDFSLabel(), MiniPizza.iri("City"), f.getOWLLiteral("place")));

        assertEquals(java.util.Collections.singletonList(MiniPizza.iri("Naples")),
                new NameResolver(pizza).namedIn("Naples is a place."));
    }

    /**
     * Review 1.141.0 I4: in MWO, "license" names a class and dcterms:license; the annotation
     * property made the name shared, so the class never came as a chip.
     */
    @Test
    void anAnnotationPropertyDoesNotMakeAClassNameAmbiguous() {
        OWLDataFactory f = pizza.getOWLOntologyManager().getOWLDataFactory();
        org.semanticweb.owlapi.model.IRI license = MiniPizza.iri("License");
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLDeclarationAxiom(f.getOWLClass(
                license)));
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLDeclarationAxiom(
                f.getOWLAnnotationProperty(org.semanticweb.owlapi.model.IRI.create(
                        "http://purl.org/dc/terms/license"))));

        assertEquals(java.util.Arrays.asList(MiniPizza.iri("Pizza"), license),
                new NameResolver(pizza).namedIn("Every pizza has a license."));
    }

    /** An annotation property, or a labelled IRI that is no term (an ORCID), is never a chip. */
    @Test
    void onlyTermsAreNamed() {
        OWLDataFactory f = pizza.getOWLOntologyManager().getOWLDataFactory();
        org.semanticweb.owlapi.model.IRI status = org.semanticweb.owlapi.model.IRI.create(
                "http://purl.org/ontology/bibo/status");
        org.semanticweb.owlapi.model.IRI orcid = org.semanticweb.owlapi.model.IRI.create(
                "https://orcid.org/0000-0002-1825-0097");
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLDeclarationAxiom(
                f.getOWLAnnotationProperty(status)));
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLAnnotationAssertionAxiom(
                f.getRDFSLabel(), orcid, f.getOWLLiteral("Josiah Carberry")));

        assertEquals(java.util.Collections.emptyList(), new NameResolver(pizza)
                .namedIn("Its status was set by Josiah Carberry."));
    }

    @Test
    void byLabel() {
        NameResolver.Resolution found = new NameResolver(pizza).resolve("Vegetarian Pizza");

        assertTrue(found.isFound());
        assertEquals(MiniPizza.iri("VegetarianPizza"), found.getIri());
        assertEquals("vegetarian pizza", found.getLabel());
    }

    @Test
    void byLabelInAnotherLanguageAndBySynonym() {
        NameResolver resolver = new NameResolver(pizza);

        assertEquals(MiniPizza.iri("VegetarianPizza"),
                resolver.resolve("vegetarische Pizza").getIri());
        assertEquals(MiniPizza.iri("VegetarianPizza"), resolver.resolve("veggie pizza").getIri());
    }

    @Test
    void byLocalName() {
        assertEquals(MiniPizza.iri("hasTopping"), new NameResolver(pizza).resolve("hasTopping")
                .getIri());
    }

    @Test
    void byIri() {
        assertEquals(MiniPizza.iri("Italy"),
                new NameResolver(pizza).resolve(MiniPizza.NS + "Italy").getIri());
    }

    @Test
    void notFoundIsSaid() {
        NameResolver.Resolution missing = new NameResolver(pizza).resolve("calzone");

        assertFalse(missing.isFound());
        assertFalse(missing.isAmbiguous());
        assertNull(missing.getIri());
        assertEquals("calzone", missing.getName());
    }

    @Test
    void aFuzzyMatchIsNotAMatch() {
        assertFalse(new NameResolver(pizza).resolve("vegetarian").isFound());
    }

    /** Review focus 4: a label two terms share is reported, not guessed. */
    @Test
    void aLabelTwoTermsShareIsAmbiguousNotGuessed() {
        OWLDataFactory factory = pizza.getOWLOntologyManager().getOWLDataFactory();
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), MiniPizza.iri("MeatTopping"),
                factory.getOWLLiteral("cheese topping")));

        NameResolver.Resolution twice = new NameResolver(pizza).resolve("cheese topping");

        assertTrue(twice.isAmbiguous());
        assertFalse(twice.isFound());
        assertNull(twice.getIri());
        assertEquals(2, twice.getCandidates().size());
    }

    /**
     * NFDIcore (1.140.0): "event" is the label of one class and a synonym of "process". The term
     * whose label it is, is meant - as the column and what is sent name it.
     */
    @Test
    void aLabelBeatsAnotherTermsSynonym() {
        OWLDataFactory factory = pizza.getOWLOntologyManager().getOWLDataFactory();
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(org.semanticweb.owlapi.model.IRI.create(
                        "http://www.geneontology.org/formats/oboInOwl#hasExactSynonym")),
                MiniPizza.iri("PizzaTopping"), factory.getOWLLiteral("cheese topping")));

        NameResolver.Resolution found = new NameResolver(pizza).resolve("cheese topping");

        assertEquals(MiniPizza.iri("CheeseTopping"), found.getIri());
        assertEquals(1, new NameResolver(pizza).candidates("cheese topping").size());
    }

    /** Two synonyms alone are still ambiguous: no term is named by them more than the other. */
    @Test
    void twoSynonymsAloneAreAmbiguous() {
        OWLDataFactory factory = pizza.getOWLOntologyManager().getOWLDataFactory();
        org.semanticweb.owlapi.model.OWLAnnotationProperty synonym =
                factory.getOWLAnnotationProperty(org.semanticweb.owlapi.model.IRI.create(
                        "http://www.geneontology.org/formats/oboInOwl#hasExactSynonym"));
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                synonym, MiniPizza.iri("Country"), factory.getOWLLiteral("place")));
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                synonym, MiniPizza.iri("City"), factory.getOWLLiteral("place")));

        assertTrue(new NameResolver(pizza).resolve("place").isAmbiguous());
    }
}
