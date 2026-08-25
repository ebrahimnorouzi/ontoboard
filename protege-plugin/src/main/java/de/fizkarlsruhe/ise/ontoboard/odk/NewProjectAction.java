package de.fizkarlsruhe.ise.ontoboard.odk;

import java.awt.event.ActionEvent;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;

/**
 * Tools &gt; New ODK Project... - a thin menu entry over {@link ProjectWizard}.
 *
 * <p>The wizard itself is static and parameterised so the canvas start panel can offer the
 * same action without depending on Protege's action lifecycle.
 */
public class NewProjectAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        ProjectWizard.show(getOWLWorkspace(), getOWLModelManager());
    }
}
