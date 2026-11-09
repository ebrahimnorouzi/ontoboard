package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import java.io.File;
import java.io.IOException;
import org.semanticweb.owlapi.model.OWLOntology;

/** For tests in other packages: the benchmark's ontology loader, which is package-private. */
public final class AssistantBenchAccess {

    private AssistantBenchAccess() {
    }

    public static OWLOntology load(String path) throws IOException {
        return AssistantBench.load(new File(path));
    }
}
