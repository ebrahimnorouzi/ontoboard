package de.fizkarlsruhe.ise.ontoboard.odk;

import java.awt.event.ActionEvent;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;

/**
 * OntoBoard &gt; Project &gt; Open existing ODK project... - a thin menu entry over
 * {@link ProjectOpener}.
 *
 * <p>The work is static and parameterised so the canvas start panel offers the same action
 * without depending on Protege's action lifecycle, exactly as {@link NewProjectAction} does
 * over {@link ProjectWizard}.
 */
public class OpenProjectAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        ProjectOpener.open(getOWLWorkspace(), getOWLEditorKit());
    }
}
