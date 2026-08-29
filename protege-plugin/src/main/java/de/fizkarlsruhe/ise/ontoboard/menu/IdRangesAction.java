package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.IdRanges;
import de.fizkarlsruhe.ise.ontoboard.odk.TermMinter;
import java.io.File;
import java.net.URI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Project &gt; ID ranges - who may mint which identifiers in this project.
 *
 * <p>Read-only for now, and useful anyway: the commonest question is "why can I not create a term",
 * and the answer is almost always that no range is allocated to the name this editor is using. That
 * is a question this answers in one click and nothing else in Protege answers at all.
 */
public class IdRangesAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    @Override
    protected String operationName() {
        return "ID ranges";
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        File ontologyFile = fileOf(ontology);
        File rangesFile = TermMinter.findRangesFile(ontologyFile);
        if (rangesFile == null) {
            return OperationResult.of(operationName())
                    .summary("This project has no -idranges.owl, so new terms are named from "
                            + "what you type rather than from an allocated block. That is fine "
                            + "for one editor and unsafe for several.")
                    .note(ontologyFile == null
                            ? "The ontology has not been saved, so there is nowhere to look."
                            : "Looked beside " + ontologyFile.getAbsolutePath())
                    .build();
        }
        TermMinter minter = TermMinter.forOntologyFile(ontologyFile, System.getProperty(
                "user.name", ""));
        if (!minter.isNumeric()) {
            return OperationResult.of(operationName())
                    .failed("Found " + rangesFile.getName()
                            + " but could not read it as an ID ranges file.")
                    .build();
        }
        IdRanges ranges = minter.getRanges();
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Range", "Allocated to", "From", "To", "Identifiers")
                .summary(ranges.getRanges().size() + " range"
                        + (ranges.getRanges().size() == 1 ? "" : "s") + " under prefix "
                        + ranges.getIdPrefix() + ", padded to " + ranges.getIdDigits()
                        + " digits");
        for (IdRanges.Range range : ranges.getRanges()) {
            result.row(String.valueOf(range.getNumber()), range.getAllocatedTo(),
                    String.valueOf(range.getLower()), String.valueOf(range.getUpper()),
                    String.valueOf(range.getUpper() - range.getLower() + 1));
        }
        result.note("Read from " + rangesFile.getAbsolutePath());
        result.note(minter.describe());
        if (ranges.getRanges().isEmpty()) {
            result.warn("No ranges are allocated, so nobody can mint an identifier.");
        }
        return result.build();
    }

    /** The ontology's own file, or null when it has never been saved. */
    private File fileOf(OWLOntology ontology) {
        try {
            URI documentUri = getOWLModelManager().getOWLOntologyManager()
                    .getOntologyDocumentIRI(ontology).toURI();
            return "file".equalsIgnoreCase(documentUri.getScheme()) ? new File(documentUri) : null;
        } catch (RuntimeException notAFile) {
            return null;
        }
    }
}
