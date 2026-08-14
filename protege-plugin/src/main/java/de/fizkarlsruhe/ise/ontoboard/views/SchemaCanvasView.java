package de.fizkarlsruhe.ise.ontoboard.views;

import java.awt.BorderLayout;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;

public class SchemaCanvasView extends AbstractOWLViewComponent {

    @Override
    protected void initialiseOWLView() {
        setLayout(new BorderLayout());
        add(new JLabel("OntoBoard schema canvas", SwingConstants.CENTER), BorderLayout.CENTER);
    }

    @Override
    protected void disposeOWLView() {
        // nothing to release yet
    }
}
