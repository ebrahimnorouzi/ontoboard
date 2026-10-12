package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.views.SheetsTab;
import org.protege.editor.core.ui.workspace.TabbedWorkspace;
import org.protege.editor.core.ui.workspace.WorkspaceTab;
import org.protege.editor.core.ui.workspace.WorkspaceTabPlugin;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Knowledge graph &gt; Sheet editor - brings the spreadsheet tab to the front, opening it if it
 * is not there.
 *
 * <p>A tab is only discoverable to somebody who looks along the tab strip or into
 * {@code Window &gt; Tabs}, and the first thing a user asked of this feature was how to open the
 * sheets inside Prot&eacute;g&eacute; at all. The menu is where the rest of this plugin's work
 * is, so the way in belongs there too.
 *
 * <p>No dialog on success. {@code run} returns null, which the base class treats as "nothing to
 * report" - clicking a menu item called <i>Sheet editor</i> and getting the sheet editor needs no
 * confirmation. Only a failure to find the tab produces a result, and it names what the
 * workspace did offer, because the alternative is a menu item that appears to do nothing.
 */
public class SheetEditorAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    /** The {@code WorkspaceTab} extension id in plugin.xml. */
    private static final String TAB_ID = "SheetsTab";

    @Override
    protected String operationName() {
        return "Sheet editor";
    }

    /** It opens a tab; there is nothing to do to an ontology, and none need be open. */
    @Override
    protected boolean needsAnOntology() {
        return false;
    }

    /** On the dispatch thread: this builds Swing components. */
    @Override
    protected boolean runsInBackground() {
        return false;
    }

    /**
     * It asks nothing, so its label carries no ellipsis.
     *
     * <p>The inference everywhere else - that an action staying on the dispatch thread is there
     * to open a dialog - is sound for every other entry in this menu and wrong for this one.
     * <i>Sheet editor</i> shows the sheet editor, which is the whole of it.
     */
    @Override
    protected boolean asksBeforeActing() {
        return false;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        TabbedWorkspace workspace;
        try {
            workspace = getOWLEditorKit().getOWLWorkspace();
        } catch (RuntimeException | Error noWorkspace) {
            return OperationResult.failed(operationName(),
                    "There is no workspace to open a tab in: " + noWorkspace);
        }

        // Already open: select it and stop. Adding a second copy of a tab somebody already has
        // would be a surprising answer to "show me the sheet editor".
        for (WorkspaceTab open : workspace.getWorkspaceTabs()) {
            if (open instanceof SheetsTab) {
                workspace.setSelectedTab(open);
                return null;
            }
        }

        // By id or by label. Protege composes a plugin's id from the bundle and the extension, so
        // the bare extension id from plugin.xml is not necessarily what getId() returns - the
        // self-test learned that the hard way and blamed the plugin for the lookup's mistake. The
        // label is what the user sees in Window > Tabs.
        StringBuilder offered = new StringBuilder();
        for (WorkspaceTabPlugin candidate : workspace.getOrderedPlugins()) {
            String id = candidate.getId();
            if (offered.length() > 0) {
                offered.append("; ");
            }
            offered.append(id).append('=').append(candidate.getLabel());
            if (TAB_ID.equals(id) || SheetsTab.LABEL.equals(candidate.getLabel())
                    || (id != null && id.endsWith(TAB_ID))) {
                WorkspaceTab added = workspace.addTabForPlugin(candidate);
                if (added == null) {
                    return OperationResult.failed(operationName(),
                            "Protege would not create the \"" + SheetsTab.LABEL + "\" tab.");
                }
                workspace.setSelectedTab(added);
                return null;
            }
        }
        return OperationResult.failed(operationName(),
                "No workspace tab is registered as " + TAB_ID + " or \"" + SheetsTab.LABEL
                        + "\". Protege offers: " + offered);
    }
}
