package de.fizkarlsruhe.ise.ontoboard.views;

import java.io.IOException;
import java.io.InputStream;
import org.protege.editor.owl.ui.OWLWorkspaceViewsTab;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The OntoBoard tab: Prot&eacute;g&eacute;'s own views tab, the schema canvas, and one
 * behaviour - it notices when the shipped layout has changed and adopts it once.
 *
 * <p>Why that behaviour is needed, and the trap in it, are in {@link ShippedLayout}. It is
 * shared with {@link SheetsTab} because both tabs make the same decision about different
 * configs.
 *
 * <p>The sheet editor used to be here too, stacked under the canvas at 45% of the column
 * height. That was the only arrangement Prot&eacute;g&eacute; would actually build - two plugin
 * views in one tabbed node leaves the second one unconstructed - and it left a spreadsheet of
 * twenty-six columns with less than half a pane. It has its own tab from 1.117.0, which is the
 * only way either of them gets the room it needs.
 */
public class OntoBoardTab extends OWLWorkspaceViewsTab {

    private static final Logger LOGGER = LoggerFactory.getLogger(OntoBoardTab.class);

    static final String CONFIG = "/viewconfig-ontoboardtab.xml";

    @Override
    public void initialise() {
        super.initialise();
        ShippedLayout.adoptOnce(this, CONFIG, LOGGER);
    }

    /**
     * Visible to the tests, which pin that the digest is stable, changes with the layout, and
     * reads the whole file rather than its first buffer. Each of those failing is silent: an
     * unstable digest resets the tab on every launch, and one that never changes ships every
     * future layout to nobody.
     */
    static String revisionOf(InputStream in) throws IOException {
        return ShippedLayout.revisionOf(in);
    }
}
