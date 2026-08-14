package de.fizkarlsruhe.ise.ontoboard.views;

import com.mxgraph.swing.mxGraphComponent;
import de.fizkarlsruhe.ise.ontoboard.canvas.SchemaGraph;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.awt.BorderLayout;
import java.util.HashSet;
import java.util.Set;
import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;

public class SchemaCanvasView extends AbstractOWLViewComponent {

    private SchemaGraph graph;
    private CanvasLayout layout = new CanvasLayout();
    private OWLOntologyChangeListener changeListener;
    private OWLModelManagerListener modelManagerListener;

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

        // Axiom edits and active-ontology switches are two separate Protege event
        // channels. Switching the active ontology fires no axiom change, so without this
        // listener the canvas silently keeps showing the ontology it initialised
        // against - see EventType. Only ACTIVE_ONTOLOGY_CHANGED is handled here; every
        // other EventType is ignored so ordinary edits still go through changeListener
        // alone and are not double-rendered.
        modelManagerListener = event -> {
            if (event.isType(EventType.ACTIVE_ONTOLOGY_CHANGED)) {
                switchToActiveOntology();
            }
        };
        getOWLModelManager().addListener(modelManagerListener);
    }

    @Override
    protected void disposeOWLView() {
        if (changeListener != null) {
            getOWLModelManager().removeOntologyChangeListener(changeListener);
        }
        if (modelManagerListener != null) {
            getOWLModelManager().removeListener(modelManagerListener);
        }
    }

    /**
     * Starts the view over for whichever ontology Protege just made active: swaps the
     * layout onto its IRI, drops membership that referred to entities in the previous
     * ontology, re-populates, then redraws. Task 6 repoints the re-population step at
     * loading that ontology's sidecar instead of {@link #seedInitialMembership()}.
     */
    private void switchToActiveOntology() {
        resetLayoutForOntology(getOWLModelManager().getActiveOntology(), layout);
        seedInitialMembership();
        refresh();
    }

    /**
     * The testable core of {@link #switchToActiveOntology()}: point {@code layout} at
     * {@code ontology} and discard membership that named entities in whatever ontology
     * was active before. Package-private so a unit test can drive it directly without a
     * live {@code OWLEditorKit}.
     */
    static void resetLayoutForOntology(OWLOntology ontology, CanvasLayout layout) {
        layout.ontologyIri = ontology.getOntologyID().getOntologyIRI()
                .transform(Object::toString).or("");
        layout.onCanvas.clear();
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
