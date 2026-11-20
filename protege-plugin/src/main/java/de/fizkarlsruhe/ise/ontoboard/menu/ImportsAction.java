package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.Catalog;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportGuard;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportHealth;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkSync;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance;
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

    /** Writes the project, or runs make or git in it: waits for a run kept behind. */
    @Override
    protected boolean writesTheProject() {
        return true;
    }

    @Override
    protected String operationName() {
        return "Imports";
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Import", "Resolved to", "Cut from");

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
        int moved = 0;
        int untraceable = 0;
        List<String> provenanceNotes = new java.util.ArrayList<String>();
        for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
            String iri = declaration.getIRI().toString();
            if (missingIris.contains(iri)) {
                result.row(iri, "NOT RESOLVED", "");
                continue;
            }
            resolved++;
            String mapped = catalogXml == null ? null : Catalog.entryFor(catalogXml, iri);

            // Which upstream release this module holds. The question nobody can answer about an
            // existing project, and the reason import modules go years without being regenerated:
            // regenerating one means guessing what it was cut from.
            ImportProvenance.Report provenance = ImportProvenance.check(
                    getOWLModelManager().getOWLOntologyManager()
                            .getImportedOntology(declaration),
                    getOWLModelManager().getOWLOntologyManager());
            result.row(iri, mapped == null ? "fetched or already loaded" : mapped,
                    describe(provenance));
            if (provenance.getFreshness() == ImportProvenance.Freshness.MOVED) {
                moved++;
            }
            if (provenance.getFreshness() == ImportProvenance.Freshness.UNRECORDED) {
                untraceable++;
            }
            if (provenance.isWorthWarningAbout()) {
                provenanceNotes.add(shortName(iri) + ": " + provenance.explain());
            }
        }

        if (ontology.getImportsDeclarations().isEmpty()) {
            return result.summary("This ontology imports nothing.").build();
        }

        for (ImportHealth.Missing one : missing) {
            result.warn(one.explain());
        }

        // An ODK project: the imports of the edit file its configuration does not declare,
        // which ODK's update would take out - each with the button that puts it right.
        File editFile = fileOf(ontology);
        File root = editFile == null ? null : projectRootOf(ontology);
        List<ImportGuard.Finding> undeclared = java.util.Collections.emptyList();
        if (root != null && OdkRelease.isOdkProject(editFile)) {
            try {
                undeclared = ImportFixes.undeclared(root, editFile, ontology);
            } catch (java.io.IOException | RuntimeException notLooked) {
                result.note("The imports the project's configuration does not declare could not "
                        + "be looked for: " + said(notLooked) + ".");
            }
        }
        for (ImportGuard.Finding finding : undeclared) {
            result.warn(ImportFixes.warningFor(finding), finding.isReadable()
                    ? declaring(editFile, root, finding.item().iri())
                    : takingOut(ontology, finding.item().iri()));
        }
        String notDeclared = undeclared.isEmpty() ? "" : " " + (undeclared.size() == 1
                ? "One is" : undeclared.size() + " are") + " not declared in the project's "
                + "configuration.";
        int axioms = ontology.getAxiomCount(Imports.INCLUDED) - ontology.getAxiomCount();
        result.note("Axioms coming from imports: " + axioms);

        for (String note : provenanceNotes) {
            result.warn(note);
        }

        // Files that call themselves by another name than the one they are imported by - put
        // in by OntoBoard when the ontology was opened, as ROBOT reads them (1.155.0: nfdicore's
        // and MWO's modules, which Protégé on its own leaves out).
        List<String> otherwise = de.fizkarlsruhe.ise.ontoboard.odk.ImportLinks
                .namedOtherwise(ontology, catalog);
        if (!otherwise.isEmpty()) {
            boolean one = otherwise.size() == 1;
            result.warn((one ? "One file calls itself" : otherwise.size()
                    + " files call themselves") + " by another name than the one "
                    + (one ? "it is" : "they are") + " imported by. Protégé on its own leaves "
                    + "such a file out of the ontology; OntoBoard puts it in when the ontology "
                    + "is opened, as ROBOT and ODK's make read it. "
                    + "OntoBoard > Reuse > Refresh imports... builds the modules again under the "
                    + "names they are imported by, as ODK names them.");
            for (String line : otherwise) {
                result.note(line);
            }
        }
        if (untraceable > 0) {
            result.note(untraceable + " import" + (untraceable == 1 ? " records" : "s record")
                    + " nothing about which upstream release it holds. Modules made with "
                    + "OntoBoard > Reuse > Find terms in another ontology... "
                    + "or Reuse > Import terms from a list... record it; an older one has to be "
                    + "re-extracted before anybody can say what is in it - "
                    + "OntoBoard > Reuse > Imported terms... does that with Rebuild from the source.");
        }

        if (!missing.isEmpty()) {
            result.warn("Until those resolve, the ontology is missing everything they contain - "
                    + "terms will look unused and a reasoner will not find problems that are "
                    + "really there.");
            return result.summary(missing.size() + " of "
                    + ontology.getImportsDeclarations().size()
                    + " imports did not resolve." + notDeclared).build();
        }
        return result.summary("All " + resolved + " imports resolved, bringing in " + axioms
                + " axioms."
                + (moved == 0 ? "" : " " + moved + " of them "
                        + (moved == 1 ? "was" : "were") + " cut from a release older than the "
                        + "copy you have open.") + notDeclared).build();
    }

    /**
     * "Declare it": the import declared as the update's own question declares it, and the
     * project's files brought in line as a save brings them - so its module takes its place.
     * Where nobody can bring them in line now, nothing is written, and why is said.
     */
    private OperationResult.Fix declaring(final File editFile, final File root,
            final String iri) {
        return new OperationResult.Fix() {
            @Override
            public String label() {
                return ImportFixes.DECLARE;
            }

            @Override
            public OperationResult apply() {
                String operation = "Declare an import";
                if (SaveFirst.isNeeded(getOWLModelManager())) {
                    return OperationResult.failed(operation, "Nothing was written: the "
                            + "ontology has changes that are not saved, and declaring " + iri
                            + " makes ODK's update, which rewrites the edit file and reads it "
                            + "again. Save, then Check imports again.");
                }
                OdkSync.Way way = ConfigSync.wayFor(editFile);
                if (way.how() == OdkSync.How.CONFIG_ONLY) {
                    return OperationResult.failed(operation, "Nothing was written: declaring "
                            + iri + " takes ODK's update, which puts its module in the import's "
                            + "place, and it cannot be made here now. " + way.whyNobody());
                }
                ProcessRunner.Runner real = ProcessRunner.real();
                return ImportFixes.declare(operation, root, iri,
                        ConfigSync.updateWay(way, real), real,
                        new ProtegeSession(getOWLWorkspace(), getOWLModelManager(), root));
            }
        };
    }

    /**
     * "Take it out": the import taken out of what is open, as a person does by hand - Edit
     * &gt; Undo has it back, and saving writes it to the edit file.
     */
    private OperationResult.Fix takingOut(final OWLOntology ontology, final String iri) {
        return new OperationResult.Fix() {
            @Override
            public String label() {
                return ImportFixes.TAKE_OUT;
            }

            @Override
            public OperationResult apply() {
                String operation = "Take an import out";
                try {
                    applyOnEventThread(ImportFixes.takingOut(ontology, iri));
                } catch (RuntimeException notTaken) {
                    return OperationResult.failed(operation, iri + " could not be taken out: "
                            + said(notTaken) + ".");
                }
                return OperationResult.of(operation).summary("Took the import of " + iri
                        + " out of the ontology. Save it to write that to the edit file - the "
                        + "save brings the project's files in line - or Edit > Undo to have it "
                        + "back.").build();
            }
        };
    }

    /** The freshness, short enough for a table cell. */
    private static String describe(ImportProvenance.Report provenance) {
        switch (provenance.getFreshness()) {
            case MOVED:
                return provenance.getCutFrom() + " - UPSTREAM HAS MOVED";
            case CURRENT:
                return provenance.getCutFrom() + " - current";
            case UNCHECKED:
                return String.valueOf(provenance.getCutFrom());
            case UNVERSIONED_UPSTREAM:
                return provenance.getSource() + (provenance.getExtractedOn().isEmpty() ? ""
                        : " on " + provenance.getExtractedOn());
            case UNRECORDED:
            default:
                return "not recorded";
        }
    }

    /** The last path segment, so a warning line does not begin with sixty characters of PURL. */
    private static String shortName(String iri) {
        // DisplayLabels handles a fragment and a path segment; an import IRI is usually
        // path-shaped but nothing guarantees it.
        String shortForm = de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels.shortNameOf(
                org.semanticweb.owlapi.model.IRI.create(iri));
        if (shortForm != null && !shortForm.isEmpty()) {
            return shortForm;
        }
        int slash = iri.lastIndexOf('/');
        return slash < 0 || slash == iri.length() - 1 ? iri : iri.substring(slash + 1);
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
}
