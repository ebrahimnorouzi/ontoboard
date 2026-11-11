package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.TermSearch;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Find terms offers the ontology a project's library patterns are cut from, and says which name it
 * found a term by (1.135.2).
 *
 * <p>Found building an application ontology on PMDco: its patterns were imported, and Find terms
 * listed BFO, RO, IAO, PROV-O, OWL-Time and SKOS - not PMDco, whose terms the project wanted next.
 * The user typed https://w3id.org/pmd/co/. Nor TTO, PMD's ontology of the tensile test.
 */
class FindTermsOffersWhatPatternsAreCutFromTest {

    @Test
    void aProjectWithAPmdcoPatternIsOfferedPmdcoAndTto() {
        Map<String, String> sources = new LinkedHashMap<String, String>();

        FindTermsAction.offerWhatPatternsAreCutFrom(sources,
                Arrays.asList("pmdco-measurement-and-setpoint"));

        assertTrue(sources.containsKey("https://w3id.org/pmd/co/"), sources.toString());
        assertTrue(sources.containsKey("https://w3id.org/pmd/tto/"), sources.toString());
    }

    @Test
    void aProjectWithoutOneIsNot() {
        Map<String, String> sources = new LinkedHashMap<String, String>();

        FindTermsAction.offerWhatPatternsAreCutFrom(sources, Arrays.asList("agentrole", null));

        assertTrue(sources.isEmpty(), sources.toString());
    }

    @Test
    void theLabelColumnSaysWhichNameFoundTheTerm() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology pmdco = manager.createOntology(IRI.create("https://w3id.org/pmd/co/"));
        IRI mould = IRI.create("https://w3id.org/pmd/co/PMD_0000075");
        manager.addAxiom(pmdco, df.getOWLDeclarationAxiom(df.getOWLClass(mould)));
        manager.addAxiom(pmdco, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), mould,
                df.getOWLLiteral("mold", "en-us")));
        manager.addAxiom(pmdco, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), mould,
                df.getOWLLiteral("mould", "en")));
        TermSearch.Term found = TermSearch.of(pmdco).find("mold", 1, false).get(0);

        assertEquals("mould - found as \"mold\"", FindTermsAction.shownLabel(found, "mold", false));
        assertEquals("mould", FindTermsAction.shownLabel(found, "mould", false));
    }
}
