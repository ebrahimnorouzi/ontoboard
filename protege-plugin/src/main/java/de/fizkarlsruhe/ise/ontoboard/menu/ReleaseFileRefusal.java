package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.ImportLinksOnOpen;
import de.fizkarlsruhe.ise.ontoboard.ReleaseProductNotice;
import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseProducts;
import java.awt.Component;
import java.io.File;
import java.net.URI;
import javax.swing.JOptionPane;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Work refused in an ODK project's release file (1.155.0). The user opened the egg project's root
 * egg.owl - a file ODK's release writes, and the next release writes again - imported two
 * patterns into it, their modules landing at the project's root, and released it as an ontology
 * of its own into the project's releases/. An action that writes beside the open file asks here
 * first, before it asks or writes anything else: in a release file it is refused, the reason
 * says where to work instead, and the edit file is offered.
 */
final class ReleaseFileRefusal {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReleaseFileRefusal.class);

    /** What Release... does: release the open file - refused in a sentence of its own. */
    static final String RELEASING = "releasing it";

    private ReleaseFileRefusal() {
    }

    /** Why this work is not done in this document; null when it is no release file. */
    static String of(File document, String work) {
        if (document == null || work == null) {
            return null;
        }
        return RELEASING.equals(work) ? ReleaseProducts.releaseRefusal(document)
                : ReleaseProducts.refusal(document, work);
    }

    /** The file an ontology was read from, or null when it was read from no file. */
    static File documentOf(OWLModelManager models, OWLOntology ontology) {
        if (models == null || ontology == null) {
            return null;
        }
        IRI document = models.getOWLOntologyManager().getOntologyDocumentIRI(ontology);
        try {
            return document == null || !"file".equalsIgnoreCase(document.getScheme()) ? null
                    : new File(URI.create(document.toString()));
        } catch (IllegalArgumentException notAFile) {
            return null;
        }
    }

    /**
     * Says why the work is refused in this document and offers the edit file; whether it was
     * refused. Nothing is written either way.
     */
    static boolean said(Component parent, File document, String work) {
        String refused = of(document, work);
        if (refused == null) {
            return false;
        }
        LOGGER.info("OntoBoard: {}", refused);
        Object[] choices = {"Open the edit file", "Close"};
        int chosen = JOptionPane.showOptionDialog(parent, ImportLinksOnOpen.wrapped(refused),
                "A release file", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, choices, choices[0]);
        if (chosen == 0) {
            try {
                ReleaseProductNotice.openInANewWindow(ReleaseProducts.of(document).editFile());
            } catch (Exception notOpened) {
                LOGGER.warn("OntoBoard: the edit file did not open", notOpened);
            }
        }
        return true;
    }
}
