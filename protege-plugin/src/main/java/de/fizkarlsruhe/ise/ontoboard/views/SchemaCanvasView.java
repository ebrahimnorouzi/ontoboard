package de.fizkarlsruhe.ise.ontoboard.views;

import com.mxgraph.swing.mxGraphComponent;
import de.fizkarlsruhe.ise.ontoboard.canvas.SchemaGraph;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.awt.BorderLayout;
import java.util.HashSet;
import java.util.Set;
import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;

public class SchemaCanvasView extends AbstractOWLViewComponent {

    private SchemaGraph graph;
    private CanvasLayout layout = new CanvasLayout();
    private OWLOntologyChangeListener changeListener;

    @Override
    protected void initialiseOWLView() {
        setLayout(new BorderLayout());

        graph = new SchemaGraph();
        add(new mxGraphComponent(graph), BorderLayout.CENTER);

        // Until Task 6 adds explicit membership, seed the canvas with up to 25 classes so
        // there is something to look at. Task 6 replaces this with the sidecar's set.
        seedInitialMembership();
        refresh();

        changeListener = changes -> refresh();
        getOWLModelManager().addOntologyChangeListener(changeListener);
    }

    @Override
    protected void disposeOWLView() {
        if (changeListener != null) {
            getOWLModelManager().removeOntologyChangeListener(changeListener);
        }
    }

    private void seedInitialMembership() {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        layout.ontologyIri = ontology.getOntologyID().getOntologyIRI()
                .transform(Object::toString).or("");
        int budget = 25;
        for (OWLClass cls : ontology.getClassesInSignature()) {
            if (budget-- <= 0) {
                break;
            }
            layout.onCanvas.add(cls.getIRI().toString());
        }
    }

    private void refresh() {
        Set<String> onCanvas = new HashSet<>(layout.onCanvas);
        Projection projection =
                OntologyProjection.project(getOWLModelManager().getActiveOntology(), onCanvas);
        graph.render(projection, layout);
    }
}
