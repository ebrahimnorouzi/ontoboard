package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Find terms searches every name a term has, and says which one it found the term by (1.135.2).
 *
 * <p>Found building an application ontology on PMDco: PMDco's mould, {@code PMD_0000075}, is
 * labelled "mold"@en-us and "mould"@en. Find terms listed it as "mold" and searched only that, so
 * "mould" found nothing: the first English label was kept and every other label dropped. Nor was
 * OBO's alternative label, {@code IAO_0000118}, a synonym it searched - and PMDco and TTO give
 * them.
 */
class TermSearchFindsEveryNameTest {

    private static final String PMD = "https://w3id.org/pmd/co/";
    private static final String ALTERNATIVE = "http://purl.obolibrary.org/obo/IAO_0000118";
    private static final String ALT_LABEL = "http://www.w3.org/2004/02/skos/core#altLabel";

    private static OWLOntology pmdco() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology pmdco = manager.createOntology(IRI.create(PMD));
        name(pmdco, "PMD_0000075", "rdfs:label", "mold", "en-us");
        name(pmdco, "PMD_0000075", "rdfs:label", "mould", "en");
        name(pmdco, "PMD_0200002", "rdfs:label", "injection molding", "en");
        name(pmdco, "PMD_0200002", ALTERNATIVE, "injection moulding", "en");
        name(pmdco, "S_1", "rdfs:label", "specimen", "en");
        name(pmdco, "S_1", "rdfs:label", "Probe", "de");
        name(pmdco, "S_1", ALT_LABEL, "test piece", "en");
        for (String term : new String[] {"PMD_0000075", "PMD_0200002", "S_1"}) {
            manager.addAxiom(pmdco, df.getOWLDeclarationAxiom(
                    df.getOWLClass(IRI.create(PMD + term))));
        }
        return pmdco;
    }

    private static void name(OWLOntology ontology, String term, String property, String text,
            String language) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
                "rdfs:label".equals(property) ? df.getRDFSLabel()
                        : df.getOWLAnnotationProperty(IRI.create(property)),
                IRI.create(PMD + term), df.getOWLLiteral(text, language)));
    }

    private static String first(List<TermSearch.Term> found) {
        return found.isEmpty() ? "nothing" : found.get(0).getIri();
    }

    @Test
    void everyLabelOfATermFindsIt() throws Exception {
        TermSearch search = TermSearch.of(pmdco());

        assertEquals(PMD + "PMD_0000075", first(search.find("mould", 5, false)));
        assertEquals(PMD + "PMD_0000075", first(search.find("mold", 5, false)));
        assertEquals(PMD + "S_1", first(search.find("probe", 5, false)),
                "a label in another language");
    }

    /** As the board names it - English first, then a label with no language - every time. */
    @Test
    void theLabelShownIsTheOneTheBoardShows() throws Exception {
        for (int run = 0; run < 3; run++) {
            assertEquals("mould", TermSearch.of(pmdco()).find("mold", 1, false).get(0)
                    .getLabel());
        }
    }

    /**
     * With no English label and none without a language, as the board names it too: the smallest
     * of the others, whatever their language (1.135.2 review). Find terms took an en-us label
     * first, and the board the smallest - so it found "mold" and drew "Form".
     */
    @Test
    void aTermWithNoEnglishLabelIsNamedAsTheBoardNamesIt() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create(PMD));
        name(ontology, "M_1", "rdfs:label", "mold", "en-us");
        name(ontology, "M_1", "rdfs:label", "Form", "de");
        org.semanticweb.owlapi.model.OWLClass mould = df.getOWLClass(IRI.create(PMD + "M_1"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(mould));

        TermSearch.Term found = TermSearch.of(ontology).find("mold", 1, false).get(0);

        assertEquals(de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels.forEntity(ontology, mould),
                found.getLabel());
        assertEquals("Form", found.getLabel());
        assertEquals("mold", found.nameMatching("mold"), "and says which of its names it found");
    }

    /**
     * A term with no label is shown by its IRI's last part, and found first by it (1.135.2 review):
     * once every label was searched, a term without one fell behind every label that merely began
     * with the words, and off a long list.
     */
    @Test
    void aTermWithoutALabelIsFoundFirstByTheNameItIsShownUnder() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/people"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create("https://schema.org/Person"))));
        IRI personal = IRI.create("http://example.org/people#PD");
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(df.getOWLClass(personal)));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), personal,
                df.getOWLLiteral("personal data", "en")));

        assertEquals("https://schema.org/Person",
                first(TermSearch.of(ontology).find("person", 5, false)));
    }

    @Test
    void anAlternativeLabelIsASynonym() throws Exception {
        assertEquals(PMD + "PMD_0200002",
                first(TermSearch.of(pmdco()).find("injection moulding", 5, false)));
    }

    @Test
    void theNameATermWasFoundByIsSaid() throws Exception {
        TermSearch search = TermSearch.of(pmdco());

        TermSearch.Term mould = search.find("mold", 1, false).get(0);
        assertEquals("mold", mould.nameMatching("mold"), "another of its labels");
        assertEquals("", mould.nameMatching("mould"), "the label shown");

        TermSearch.Term specimen = search.find("test piece", 1, false).get(0);
        assertEquals("test piece", specimen.nameMatching("test piece"), "a synonym");
        assertEquals("", specimen.nameMatching("S_1"), "only its IRI");
        assertFalse(search.find("probe", 1, false).isEmpty());
        assertEquals("Probe", search.find("probe", 1, false).get(0).nameMatching("probe"));
    }
}
