package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.Catalog;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportHealth;
import java.io.File;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Project &gt; Imports - what this ontology imports, and whether any of it actually arrived.
 *
 * <p>{@link ImportHealth} existed and ran on exactly one code path: opening a project from GitHub.
 * Every other way of opening an ontology - File &gt; Open, the recent list, a project opened
 * before this plugin was installed - got no check at all, and that is most of them.
 *
 * <p>It matters because an ontology whose imports did not resolve opens perfectly happily. The
 * hierarchy is there, the file is there, and the thousands of classes it was importing are not:
 * a term looks unused, a subclass axiom points at nothing, and a reasoner reports no
 * inconsistency because half the axioms are absent. Nothing in the window says so, which is why
 * it has to be askable.
 */
public class ImportsAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    @Override
    protected String operationName() {
        return "Imports";
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Import", "Resolved to");

        File catalog = catalogBeside(ontology);
        String catalogXml = readOrNull(catalog);
        if (catalog != null) {
            result.note("Catalog: " + catalog.getAbsolutePath()
                    + (catalogXml == null ? " (could not be read)" : ""));
        }

        List<ImportHealth.Missing> missing = ImportHealth.missingFrom(ontology,
                getOWLModelManager().getOWLOntologyManager(), catalog);
        java.util.Set<String> missingIris = new java.util.HashSet<String>();
        for (ImportHealth.Missing one : missing) {
            missingIris.add(one.getIri().toString());
        }

        int resolved = 0;
        for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
            String iri = declaration.getIRI().toString();
            if (missingIris.contains(iri)) {
                result.row(iri, "NOT RESOLVED");
                continue;
            }
            resolved++;
            String mapped = catalogXml == null ? null : Catalog.entryFor(catalogXml, iri);
            result.row(iri, mapped == null ? "fetched or already loaded" : mapped);
        }

        if (ontology.getImportsDeclarations().isEmpty()) {
            return result.summary("This ontology imports nothing.").build();
        }

        for (ImportHealth.Missing one : missing) {
            result.warn(one.explain());
        }
        int axioms = ontology.getAxiomCount(Imports.INCLUDED) - ontology.getAxiomCount();
        result.note("Axioms coming from imports: " + axioms);

        if (!missing.isEmpty()) {
            result.warn("Until those resolve, the ontology is missing everything they contain - "
                    + "terms will look unused and a reasoner will not find problems that are "
                    + "really there.");
            return result.summary(missing.size() + " of "
                    + ontology.getImportsDeclarations().size()
                    + " imports did not resolve.").build();
        }
        return result.summary("All " + resolved + " imports resolved, bringing in " + axioms
                + " axioms.").build();
    }

    /** {@code catalog-v001.xml} beside the ontology, or null. */
    private File catalogBeside(OWLOntology ontology) {
        File ontologyFile = fileOf(ontology);
        if (ontologyFile == null || ontologyFile.getParentFile() == null) {
            return null;
        }
        File catalog = new File(ontologyFile.getParentFile(), "catalog-v001.xml");
        return catalog.isFile() ? catalog : null;
    }

    private static String readOrNull(File catalog) {
        if (catalog == null) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(catalog.toPath()), Charset.forName("UTF-8"));
        } catch (java.io.IOException cannotRead) {
            return null;
        }
    }

    /** The ontology's own file, or null when it has never been saved. */
    private File fileOf(OWLOntology ontology) {
        if (ontology == null) {
            return null;
        }
        try {
            URI documentUri = getOWLModelManager().getOWLOntologyManager()
                    .getOntologyDocumentIRI(ontology).toURI();
            return "file".equalsIgnoreCase(documentUri.getScheme()) ? new File(documentUri) : null;
        } catch (RuntimeException notAFile) {
            return null;
        }
    }
}
