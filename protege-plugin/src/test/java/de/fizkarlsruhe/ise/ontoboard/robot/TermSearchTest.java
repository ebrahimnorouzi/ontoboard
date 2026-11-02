package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class TermSearchTest {

    private static final String NS = "http://example.org/source#";

    /**
     * A term is found by what it is called, best match first.
     *
     * <p>Asked for: "the user doesn't know the terms - from the labels in the source ontology
     * they choose." Import terms wanted IRIs, found somewhere else first.
     */
    @Test
    void termsAreFoundByTheirLabelsBestFirst() throws Exception {
        TermSearch search = TermSearch.of(source());

        assertEquals("[temperature, temperature sensor, room temperature, heat]",
                labels(search.find("temperature", 10, false)),
                "exactly, then starting with it, then containing it, then a synonym");
        assertEquals("[heat]", labels(search.find("warmth", 10, false)), "by synonym");
        assertEquals("[temperature sensor]", labels(search.find("T0002", 10, false)),
                "and by the IRI, for whoever has one");
    }

    /** Obsolete terms stay out of the way unless asked for, and the limit is a limit. */
    @Test
    void obsoleteTermsOnlyWhenAskedAndNoMoreThanTheLimit() throws Exception {
        TermSearch search = TermSearch.of(source());

        assertTrue(labels(search.find("", 10, false)).indexOf("old heat") < 0);
        assertTrue(labels(search.find("", 10, true)).contains("old heat"));
        assertEquals(2, search.find("temperature", 2, false).size());
        assertEquals(4, search.count("temperature", false));
    }

    /** What a term is and means comes with it, so the right one can be told from the rest. */
    @Test
    void aTermSaysWhatItIsAndMeans() throws Exception {
        TermSearch.Term heat = TermSearch.of(source()).find("heat", 1, false).get(0);

        assertEquals("Class", heat.getKind());
        assertEquals("Energy transferred because of a temperature difference.",
                heat.getDefinition());
    }

    /**
     * Three terms called Person are not three identical rows.
     *
     * <p>Seen loading FOAF from its web address: FOAF's own Person, and schema.org's and another
     * vocabulary's Person, which FOAF only mentions in an axiom and says nothing else about. All
     * three showed as "Person | Class | Person".
     */
    @Test
    void aTermTheOntologyOnlyMentionsComesLastAndSaysWhereItIsFrom() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology vocabulary = manager.createOntology(IRI.create("http://example.org/v"));
        org.semanticweb.owlapi.model.OWLClass own = df.getOWLClass(
                IRI.create("http://example.org/v/Person"));
        org.semanticweb.owlapi.model.OWLClass elsewhere = df.getOWLClass(
                IRI.create("https://www.example.com/other#Person"));
        manager.addAxiom(vocabulary, df.getOWLDeclarationAxiom(own));
        manager.addAxiom(vocabulary, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                own.getIRI(), df.getOWLLiteral("Person")));
        manager.addAxiom(vocabulary, df.getOWLEquivalentClassesAxiom(own, elsewhere));
        manager.addAxiom(vocabulary, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create("http://example.org/v/Agent"))));

        List<TermSearch.Term> found = TermSearch.of(vocabulary).find("person", 10, false);

        assertEquals(2, found.size());
        assertEquals("http://example.org/v/Person", found.get(0).getIri(), "its own first");
        assertEquals("Person", found.get(0).getShortIri());
        assertTrue(found.get(0).isDescribed());
        assertEquals("example.com/other#Person", found.get(1).getShortIri(),
                "where the other is from, without the scheme");
        assertTrue(!found.get(1).isDescribed());
    }

    private static OWLOntology source() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology source = manager.createOntology(IRI.create("http://example.org/source"));
        String[][] terms = {{"T0001", "temperature"}, {"T0002", "temperature sensor"},
            {"T0003", "room temperature"}, {"T0004", "heat"}, {"T0005", "old heat"}};
        for (String[] term : terms) {
            IRI iri = IRI.create(NS + term[0]);
            manager.addAxiom(source, df.getOWLDeclarationAxiom(df.getOWLClass(iri)));
            manager.addAxiom(source, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), iri,
                    df.getOWLLiteral(term[1], "en")));
        }
        IRI heat = IRI.create(NS + "T0004");
        manager.addAxiom(source, df.getOWLAnnotationAssertionAxiom(df.getOWLAnnotationProperty(
                IRI.create("http://www.geneontology.org/formats/oboInOwl#hasExactSynonym")),
                heat, df.getOWLLiteral("warmth; temperature energy")));
        manager.addAxiom(source, df.getOWLAnnotationAssertionAxiom(df.getOWLAnnotationProperty(
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000115")), heat,
                df.getOWLLiteral("Energy transferred because of a temperature difference.")));
        manager.addAxiom(source, df.getOWLAnnotationAssertionAxiom(df.getOWLDeprecated(),
                IRI.create(NS + "T0005"), df.getOWLLiteral(true)));
        return source;
    }

    private static String labels(List<TermSearch.Term> found) {
        List<String> labels = new ArrayList<String>();
        for (TermSearch.Term term : found) {
            labels.add(term.getLabel());
        }
        return labels.toString();
    }
}
