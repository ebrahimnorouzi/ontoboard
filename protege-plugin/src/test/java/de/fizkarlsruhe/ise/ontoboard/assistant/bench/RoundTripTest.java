package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.context.AssistantRendering;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.AxiomParser;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Every logical axiom the assistant shows parses back to itself, on the benchmark's real
 * ontologies (1.139.0): what a model copies into an add or replace proposal is the axiom it
 * was shown. An axiom kind the reader cannot handle shows up here by name.
 */
class RoundTripTest {

    @ParameterizedTest
    @ValueSource(strings = {"pizza.owl", "pizzabfo.owl", "mwo.owl", "nfdicore.owl",
        "fixtures/consistent.ofn",
        "fixtures/unsatisfiable.ofn", "fixtures/inconsistent.ofn",
        "fixtures/datatype-clash.ofn"})
    void everyAxiomShownParsesBack(String name) throws IOException {
        OWLOntology ontology = AssistantBench.load(
                new File("../benchmarks/assistant/ontologies", name));
        AxiomParser parser = new AxiomParser(ontology);
        List<String> failed = new ArrayList<String>();
        int total = 0;
        for (OWLAxiom axiom : ontology.getLogicalAxioms()) {
            // A SWRL rule is shown and can be removed by its text; single-axiom Manchester
            // syntax cannot write one, so it is never added or replaced (docs: Limits).
            if (axiom.getAxiomType() == org.semanticweb.owlapi.model.AxiomType.SWRL_RULE) {
                continue;
            }
            total++;
            OWLAxiom plain = axiom.getAxiomWithoutAnnotations();
            String shown = AssistantRendering.render(ontology, plain);
            AxiomParser.Result back = parser.parse(shown);
            if (!plain.equals(back.getAxiom())) {
                failed.add(shown + "  ->  " + (back.getAxiom() == null ? back.getProblem()
                        : back.getAxiom()));
            }
        }

        assertTrue(failed.isEmpty(), failed.size() + " of " + total + " in " + name
                + " do not parse back:\n" + String.join("\n",
                        failed.subList(0, Math.min(25, failed.size()))));
    }
}
