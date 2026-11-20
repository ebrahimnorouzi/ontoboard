package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.views.OntoBoardTab;
import java.awt.Component;
import java.util.Collection;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
import org.protege.editor.core.ui.workspace.TabbedWorkspace;
import org.protege.editor.core.ui.workspace.WorkspaceTab;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLWorkspace;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * From a row of a result to its term: the term is made Protégé's selection, which the board
 * reveals - an imported one as Find shows it (1.155.0: the user saw "a label is missing" in the
 * quality report of their egg project and could not get from the row to the term).
 */
public final class TermLink {

    private TermLink() {
    }

    /**
     * The term with this IRI among the active ontology and what it imports: a class first, as a
     * report means a class where the IRI also names something else; null when none has it.
     */
    static OWLEntity find(OWLOntology active, String iri) {
        if (active == null || iri == null || iri.trim().isEmpty()) {
            return null;
        }
        OWLEntity any = null;
        for (OWLEntity entity : active.getEntitiesInSignature(IRI.create(iri.trim()),
                Imports.INCLUDED)) {
            if (entity.isOWLClass()) {
                return entity;
            }
            if (any == null) {
                any = entity;
            }
        }
        return any;
    }

    /**
     * Makes the term Protégé's selection and brings the board forward; whether it is in an
     * ontology open. On a tab without the board - Active ontology, which a new window opens with -
     * the selection alone changed nothing to be seen (the hand check of 1.155.0).
     */
    public static boolean open(OWLEditorKit kit, String iri) {
        final OWLWorkspace workspace = kit.getOWLWorkspace();
        return open(kit.getModelManager().getActiveOntology(), iri,
                entity -> workspace.getOWLSelectionModel().setSelectedEntity(entity),
                () -> bringForward(workspace));
    }

    /**
     * The term selected, then the board brought forward - in this order: a board shown for the
     * first time is built then, and opens on the term selected; one selected after would be
     * brought into view before the board had its size.
     */
    static boolean open(OWLOntology active, String iri, Consumer<OWLEntity> select,
            Runnable bringTheBoardForward) {
        OWLEntity entity = find(active, iri);
        if (entity == null) {
            return false;
        }
        select.accept(entity);
        bringTheBoardForward.run();
        return true;
    }

    /** The board's tab among a window's tabs, or null when the window has none. */
    static WorkspaceTab boardTab(Collection<? extends WorkspaceTab> tabs) {
        for (WorkspaceTab tab : tabs) {
            if (tab instanceof OntoBoardTab) {
                return tab;
            }
        }
        return null;
    }

    /** A board tab the person closed is not opened again for a row. */
    private static void bringForward(TabbedWorkspace workspace) {
        WorkspaceTab board = boardTab(workspace.getWorkspaceTabs());
        if (board != null && board != workspace.getSelectedTab()) {
            workspace.setSelectedTab(board);
        }
    }

    /**
     * What a click on a result's row calls, for a dialog over Protégé's workspace; null over
     * anything else, and the rows are then no links.
     */
    static Consumer<String> opener(final Component parent) {
        if (!(parent instanceof OWLWorkspace)) {
            return null;
        }
        final OWLEditorKit kit = ((OWLWorkspace) parent).getOWLEditorKit();
        return iri -> {
            if (!open(kit, iri)) {
                JOptionPane.showMessageDialog(parent, iri + " is in no ontology open, so there is "
                        + "nothing to show of it.", "Not open", JOptionPane.INFORMATION_MESSAGE);
            }
        };
    }
}
