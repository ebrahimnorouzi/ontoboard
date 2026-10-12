package de.fizkarlsruhe.ise.ontoboard.views;

import org.protege.editor.owl.ui.OWLWorkspaceViewsTab;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A tab of its own for the spreadsheets, because a spreadsheet needs the window.
 *
 * <p>From 1.112.0 the sheet editor sat under the canvas in the OntoBoard tab, at 45% of the
 * height of the right-hand column - so roughly a third of the window, for a grid that is
 * twenty-six columns wide on the knowledge graph it was built against. Three or four columns
 * were visible at a time and the toolbar wrapped. Tabbing the two views together would have
 * given each the whole column, and is the arrangement the six entity views opposite use, but
 * Prot&eacute;g&eacute; does not do it for plugin views: with both in one {@code CNode} it
 * instantiates the first and silently drops the second.
 *
 * <p>So a second workspace tab, which is the mechanism that does work: both views are built,
 * each has the whole window, and switching between them is one click in the same place a user
 * switches between Entities and Classes. {@code Knowledge graph &gt; Sheet editor} selects it
 * from the menu for anybody who has not noticed it.
 *
 * <p>Nothing but the layout differs from {@link OntoBoardTab}; the adopt-once behaviour they
 * share is in {@link ShippedLayout}, keyed per config so the two tabs cannot fight over one
 * preference.
 */
public class SheetsTab extends OWLWorkspaceViewsTab {

    private static final Logger LOGGER = LoggerFactory.getLogger(SheetsTab.class);

    static final String CONFIG = "/viewconfig-sheetstab.xml";

    /** The {@code label} in plugin.xml - what Window &gt; Tabs shows, and what the menu finds. */
    public static final String LABEL = "OntoBoard Sheets";

    @Override
    public void initialise() {
        super.initialise();
        ShippedLayout.adoptOnce(this, CONFIG, LOGGER);
    }
}
