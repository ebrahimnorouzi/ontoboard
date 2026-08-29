package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.Catalog;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotException;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import de.fizkarlsruhe.ise.ontoboard.robot.TermList;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * ROBOT &gt; Import terms - borrowing a piece of somebody else's ontology.
 *
 * <p>This is the thing an OBO project does constantly and Protege has no answer for. You need
 * {@code CHEBI:60027 polymer} to say what your material is. You do not want all of ChEBI. So you
 * extract a module holding that class and the axioms that give it meaning, and import that -
 * which is a ROBOT command, a term file, a saved module, an import statement and a catalog entry,
 * and getting any one of the five wrong produces a project that works for whoever made it and
 * fails for everyone else.
 *
 * <p>All five are done here, and the result says which. The catalog entry in particular: without
 * it the import resolves only in the session that created it, because the module's IRI has never
 * been published anywhere. That is the failure whose author never sees it.
 */
public class ImportTermsAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_SOURCE = "source";
    private static final String OPTION_TERMS = "terms";
    private static final String OPTION_TERM_FILE = "termFile";
    private static final String OPTION_METHOD = "method";
    private static final String OPTION_OUTCOME = "outcome";
    private static final String OPTION_MODULE_FILE = "moduleFile";

    /** How many changed axioms to list before the table stops being worth reading. */
    private static final int MAX_LISTED = 500;

    /** What to do with the module once it exists. */
    private enum Outcome {
        SAVE_AND_IMPORT("Save it and import it"),
        ADD_AXIOMS("Copy the axioms straight into this ontology"),
        SAVE_ONLY("Save it and do nothing else");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        static Outcome byLabel(String label) {
            for (Outcome outcome : values()) {
                if (outcome.label.equalsIgnoreCase(label == null ? "" : label.trim())) {
                    return outcome;
                }
            }
            return SAVE_AND_IMPORT;
        }
    }

    private volatile String sourceText = "";
    private volatile String termsText = "";
    private volatile String termFileText = "";
    private volatile TermExtract.Method method = TermExtract.DEFAULT_METHOD;
    private volatile Outcome outcome = Outcome.SAVE_AND_IMPORT;
    private volatile String moduleFileText = "";

    @Override
    protected String operationName() {
        return "Import terms";
    }

    @Override
    protected boolean configure() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(OPTION_SOURCE, "");
        values.put(OPTION_TERMS, "");
        values.put(OPTION_TERM_FILE, "");
        values.put(OPTION_METHOD, TermExtract.DEFAULT_METHOD.getLabel());
        values.put(OPTION_OUTCOME, Outcome.SAVE_AND_IMPORT.label);
        values.put(OPTION_MODULE_FILE, defaultModuleFile());

        // A loop, because two of the rules span fields - a source is needed, and terms have to
        // come from somewhere - and a per-field validator cannot express either. Re-showing with
        // what was typed rather than with the defaults is the difference between a correction and
        // starting again.
        while (true) {
            Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Import terms",
                    "Extracts a module from another ontology and brings it into this one - what "
                            + "'robot extract' does, including the import statement and the "
                            + "catalog entry that make it resolve for everyone else.",
                    parametersFrom(values));
            if (chosen == null) {
                return false;
            }
            values = chosen;
            List<String> problems = crossFieldProblems(chosen);
            if (problems.isEmpty()) {
                break;
            }
            StringBuilder message = new StringBuilder();
            for (String problem : problems) {
                message.append("• ").append(problem).append('\n');
            }
            JOptionPane.showMessageDialog(getOWLWorkspace(), message.toString().trim(),
                    problems.size() == 1 ? "One value needs changing"
                            : problems.size() + " values need changing",
                    JOptionPane.WARNING_MESSAGE);
        }

        sourceText = values.get(OPTION_SOURCE);
        termsText = values.get(OPTION_TERMS);
        termFileText = values.get(OPTION_TERM_FILE);
        method = methodByLabel(values.get(OPTION_METHOD));
        outcome = Outcome.byLabel(values.get(OPTION_OUTCOME));
        moduleFileText = values.get(OPTION_MODULE_FILE);
        return true;
    }

    /** The rules a per-field validator cannot express. */
    private static List<String> crossFieldProblems(Map<String, String> values) {
        List<String> problems = new ArrayList<String>();
        if (isBlank(values.get(OPTION_SOURCE))) {
            problems.add("Name the ontology to take terms from - a file, or a URL such as "
                    + "http://purl.obolibrary.org/obo/iao.owl");
        }
        if (isBlank(values.get(OPTION_TERMS)) && isBlank(values.get(OPTION_TERM_FILE))) {
            problems.add("Give some terms, either typed into the box or in a term file.");
        }
        if (Outcome.byLabel(values.get(OPTION_OUTCOME)) != Outcome.ADD_AXIOMS
                && isBlank(values.get(OPTION_MODULE_FILE))) {
            problems.add("Say where to save the module, or choose to copy the axioms in "
                    + "instead of saving a module.");
        }
        return problems;
    }

    private List<Parameter> parametersFrom(Map<String, String> values) {
        List<String> methods = new ArrayList<String>();
        StringBuilder methodHelp = new StringBuilder(
                "How much of the source ontology comes with the terms. The names are misleading "
                        + "and the descriptions below were checked against what the operations "
                        + "actually return.\n");
        for (TermExtract.Method available : TermExtract.Method.values()) {
            methods.add(available.getLabel());
            methodHelp.append('\n').append(available.getLabel()).append(": ")
                    .append(available.getHelp()).append('\n');
        }

        return Arrays.asList(
                Parameter.of(OPTION_SOURCE, "Take terms from", Parameter.Kind.FILE)
                        .defaultValue(values.get(OPTION_SOURCE))
                        .help("The ontology to borrow from, as a path to a file on disk or as a "
                                + "URL - an OBO PURL such as "
                                + "http://purl.obolibrary.org/obo/iao.owl works and is the usual "
                                + "case. If Protege already has that ontology open it is used "
                                + "as-is rather than downloaded again, which for a large ontology "
                                + "is the difference between instant and several minutes.")
                        .build(),
                Parameter.of(OPTION_TERMS, "Terms", Parameter.Kind.MULTILINE)
                        .defaultValue(values.get(OPTION_TERMS))
                        .help("One term per line, as a full IRI or a CURIE - IAO:0000109 and "
                                + "http://purl.obolibrary.org/obo/IAO_0000109 both work. The 277 "
                                + "OBO prefixes are built in and this ontology's own prefixes are "
                                + "added, so a project CURIE resolves too. Anything after a space "
                                + "and a # is treated as a comment, which is how term files are "
                                + "written. A line whose prefix is not recognised is reported "
                                + "rather than skipped.")
                        .build(),
                Parameter.of(OPTION_TERM_FILE, "or a term file", Parameter.Kind.FILE)
                        .defaultValue(values.get(OPTION_TERM_FILE))
                        .help("A file in the same one-per-line format, which is what an ODK "
                                + "project keeps under imports/ and passes to "
                                + "'robot extract --term-file'. Using the project's own file "
                                + "means this produces the same module its build does. If both "
                                + "this and the box above are filled in, the two are combined.")
                        .build(),
                Parameter.of(OPTION_METHOD, "Method", Parameter.Kind.CHOICE)
                        .choices(methods.toArray(new String[0]))
                        .defaultValue(values.get(OPTION_METHOD))
                        .required()
                        .help(methodHelp.toString())
                        .build(),
                Parameter.of(OPTION_OUTCOME, "Then", Parameter.Kind.CHOICE)
                        .choices(Outcome.SAVE_AND_IMPORT.label, Outcome.ADD_AXIOMS.label,
                                Outcome.SAVE_ONLY.label)
                        .defaultValue(values.get(OPTION_OUTCOME))
                        .required()
                        .help("Saving and importing is what an OBO project does: the module "
                                + "becomes a file of its own, this ontology imports it, and a "
                                + "catalog entry makes that import resolve without the network "
                                + "for everyone who checks the project out. Re-running the "
                                + "extraction later refreshes the file and nothing else changes."
                                + "\n\nCopying the axioms straight in merges them into this "
                                + "ontology as if you had written them. It is undoable in one "
                                + "step and there is nothing to keep up to date, but the borrowed "
                                + "terms are then indistinguishable from your own and the next "
                                + "person cannot tell where they came from."
                                + "\n\nSaving and doing nothing else leaves you the file to wire "
                                + "up by hand.")
                        .build(),
                Parameter.of(OPTION_MODULE_FILE, "Save the module as", Parameter.Kind.FILE)
                        .defaultValue(values.get(OPTION_MODULE_FILE))
                        .help("Where the module file goes. The default follows the ODK layout - "
                                + "an imports/ directory beside the ontology, named after the "
                                + "source - so the project looks like every other OBO project and "
                                + "its Makefile knows where to find things. Ignored when you "
                                + "choose to copy the axioms in instead.")
                        .build());
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName());

        IRI sourceIri = OntologySource.toIri(sourceText);
        // Protege's own manager first, so an ontology already open - very often the one being
        // edited imports it - is not downloaded a second time. Otherwise a throwaway manager, so
        // a 700MB ontology fetched to take four terms out of it does not stay in Protege's
        // ontology list and in memory for the rest of the session.
        OWLOntology source = OntologySource.findAlreadyLoaded(
                getOWLModelManager().getOWLOntologyManager(), sourceIri);
        if (source != null) {
            result.note("Source: " + sourceIri + " (already open)");
        } else {
            source = OntologySource.load(OWLManager.createOWLOntologyManager(), sourceIri);
            result.note("Source: " + sourceIri);
        }

        TermList terms = readTerms(ontology);
        result.note("Terms: " + terms.describe());
        for (TermList.Entry unresolved : terms.getUnresolved()) {
            result.warn("Line " + unresolved.getLine() + ": '" + unresolved.getText()
                    + "' - no prefix for that, so it was not imported.");
        }
        if (terms.isEmpty()) {
            return result.failed("None of the terms could be read as an IRI or a CURIE.").build();
        }

        IRI moduleIri = TermExtract.moduleIriIn(
                ontology.getOntologyID().getOntologyIRI().isPresent()
                        ? ontology.getOntologyID().getOntologyIRI().get() : null, source);
        TermExtract.Result extracted =
                TermExtract.run(source, terms.getIris(), method, moduleIri);
        result.note("Method: " + method.getLabel());
        result.note("Module IRI: " + moduleIri);
        String unpublishable = TermExtract.warningFor(moduleIri);
        if (unpublishable != null && outcome != Outcome.ADD_AXIOMS) {
            result.warn(unpublishable);
        }
        for (IRI missing : extracted.getMissing()) {
            result.warn(missing + " is not in that ontology - obsoleted, or from a different one.");
        }
        if (extracted.getAxiomCount() == 0) {
            return result.failed("The extraction produced nothing. "
                    + (method == TermExtract.Method.STAR
                            ? "STAR gives the smallest logically faithful module, which on a "
                                    + "plain hierarchy can be empty; BOT is what an ODK import "
                                    + "uses."
                            : "That usually means the terms are declared but have no axioms.")
                    ).build();
        }

        switch (outcome) {
            case ADD_AXIOMS:
                return copyIn(ontology, extracted, result);
            case SAVE_ONLY:
                return save(extracted, result, false, ontology, moduleIri);
            case SAVE_AND_IMPORT:
            default:
                return save(extracted, result, true, ontology, moduleIri);
        }
    }

    /** Both boxes, combined, with this ontology's prefixes so its own CURIEs resolve. */
    private TermList readTerms(OWLOntology ontology) {
        StringBuilder text = new StringBuilder();
        if (!isBlank(termsText)) {
            text.append(termsText).append('\n');
        }
        if (!isBlank(termFileText)) {
            File file = new File(termFileText.trim());
            if (!file.isFile()) {
                throw new RobotException("There is no term file at " + file.getAbsolutePath());
            }
            try {
                text.append(new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8"));
            } catch (IOException cannotRead) {
                throw new RobotException("Could not read " + file.getAbsolutePath() + ": "
                        + cannotRead.getMessage(), cannotRead);
            }
        }
        return TermList.parse(text.toString(), prefixesOf(ontology));
    }

    /** The editing ontology's prefixes, so a project's own CURIEs resolve in a term list. */
    private Map<String, String> prefixesOf(OWLOntology ontology) {
        try {
            org.semanticweb.owlapi.model.OWLDocumentFormat format =
                    getOWLModelManager().getOWLOntologyManager().getOntologyFormat(ontology);
            if (format != null && format.isPrefixOWLOntologyFormat()) {
                return format.asPrefixOWLOntologyFormat().getPrefixName2PrefixMap();
            }
        } catch (RuntimeException noPrefixes) {
            // A format without prefixes is not a problem; the OBO ones are still there.
        }
        return new LinkedHashMap<String, String>();
    }

    /** Merges the module's axioms into the ontology, through the model manager so undo works. */
    private OperationResult copyIn(OWLOntology ontology, TermExtract.Result extracted,
            OperationResult.Builder result) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        for (OWLAxiom axiom : extracted.getModule().getAxioms()) {
            if (!ontology.containsAxiom(axiom)) {
                changes.add(new AddAxiom(ontology, axiom));
            }
        }
        if (changes.isEmpty()) {
            return result.summary("Nothing to add - this ontology already has every axiom the "
                    + "module contains.").build();
        }
        applyOnEventThread(changes);

        result.columns("Axiom");
        int listed = 0;
        for (OWLOntologyChange change : changes) {
            if (listed++ >= MAX_LISTED) {
                break;
            }
            result.row(String.valueOf(change.getAxiom()));
        }
        if (changes.size() > MAX_LISTED) {
            result.note("Listing the first " + MAX_LISTED + " of " + changes.size() + ".");
        }
        result.note("Edit > Undo removes all of them in one step.");
        result.warn("These axioms are now indistinguishable from your own. If you want to keep "
                + "track of what was borrowed, re-run with 'Save it and import it'.");
        return result.summary("Copied " + changes.size() + " axioms for "
                + (extracted.getRequested() - extracted.getMissing().size()) + " terms into this "
                + "ontology.").build();
    }

    /** Writes the module, and optionally wires it up as an import. */
    private OperationResult save(TermExtract.Result extracted, OperationResult.Builder result,
            boolean andImport, OWLOntology ontology, IRI moduleIri) {
        File target = new File(moduleFileText.trim());
        if (target.getParentFile() != null && !target.getParentFile().isDirectory()
                && !target.getParentFile().mkdirs()) {
            return result.failed("Could not create " + target.getParentFile().getAbsolutePath())
                    .build();
        }
        try {
            extracted.getModule().getOWLOntologyManager()
                    .saveOntology(extracted.getModule(), IRI.create(target.toURI()));
        } catch (Exception cannotSave) {
            return result.failed("Could not write " + target.getAbsolutePath() + ": "
                    + cannotSave.getMessage()).build();
        }
        result.wrote(target);

        if (!andImport) {
            return result.summary("Wrote a module of " + extracted.getAxiomCount()
                    + " axioms. Nothing else was changed.").build();
        }

        // The catalog entry is not optional. Without it the import resolves only in this session,
        // because the module's IRI has never been published anywhere - and the person who made
        // the module never sees the failure.
        File ontologyFile = fileOf(ontology);
        if (ontologyFile != null && ontologyFile.getParentFile() != null) {
            File catalog = new File(ontologyFile.getParentFile(), "catalog-v001.xml");
            try {
                Catalog.addEntry(catalog, moduleIri.toString(),
                        relativePath(ontologyFile.getParentFile(), target));
                result.wrote(catalog);
            } catch (IOException cannotWrite) {
                result.warn("Wrote the module but could not update " + catalog.getAbsolutePath()
                        + ": " + cannotWrite.getMessage() + ". The import will resolve in this "
                        + "session and fail for anyone who opens the project fresh.");
            }
        } else {
            result.warn("This ontology has not been saved, so there is nowhere to put a catalog "
                    + "entry. The import will resolve now and fail when the project is reopened.");
        }

        // Into Protege's manager so the import resolves immediately, without a reload.
        OWLOntologyManager manager = getOWLModelManager().getOWLOntologyManager();
        if (OntologySource.findAlreadyLoaded(manager, moduleIri) == null) {
            try {
                manager.loadOntologyFromOntologyDocument(target);
            } catch (Exception cannotLoad) {
                result.warn("The module was written but Protege could not load it back: "
                        + cannotLoad.getMessage());
            }
        }
        applyOnEventThread(java.util.Collections.<OWLOntologyChange>singletonList(
                new AddImport(ontology, getOWLModelManager().getOWLDataFactory()
                        .getOWLImportsDeclaration(moduleIri))));

        result.note("Imported as " + moduleIri);
        return result.summary("Wrote a module of " + extracted.getAxiomCount() + " axioms for "
                + (extracted.getRequested() - extracted.getMissing().size()) + " terms, and "
                + "imported it.").build();
    }

    /**
     * {@code target} as a path relative to {@code base}, or absolute when it is somewhere else.
     *
     * <p>Relative matters: the catalog is committed and read out on another machine, where an
     * absolute path from this one resolves to nothing.
     */
    static String relativePath(File base, File target) {
        try {
            return base.toPath().toAbsolutePath().normalize()
                    .relativize(target.toPath().toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (IllegalArgumentException differentRoots) {
            // Different drives on Windows: there is no relative path, and an absolute one at
            // least works on this machine rather than silently resolving nowhere.
            return target.getAbsolutePath().replace('\\', '/');
        }
    }

    /** The ODK layout: an imports/ directory beside the ontology. */
    private String defaultModuleFile() {
        File ontologyFile = fileOf(getOWLModelManager().getActiveOntology());
        if (ontologyFile == null || ontologyFile.getParentFile() == null) {
            return "";
        }
        return new File(new File(ontologyFile.getParentFile(), "imports"),
                "extracted_import.owl").getAbsolutePath();
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

    /** Through the model manager, on the dispatch thread - see TransformAction for why both. */
    private void applyOnEventThread(final List<OWLOntologyChange> changes) {
        if (SwingUtilities.isEventDispatchThread()) {
            getOWLModelManager().applyChanges(changes);
            return;
        }
        final AtomicReference<RuntimeException> failure = new AtomicReference<RuntimeException>();
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    try {
                        getOWLModelManager().applyChanges(changes);
                    } catch (RuntimeException thrown) {
                        failure.set(thrown);
                    }
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while applying the changes");
        } catch (InvocationTargetException thrown) {
            throw new IllegalStateException(thrown.getCause() == null ? thrown.toString()
                    : String.valueOf(thrown.getCause().getMessage()));
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private static TermExtract.Method methodByLabel(String label) {
        for (TermExtract.Method available : TermExtract.Method.values()) {
            if (available.getLabel().equalsIgnoreCase(
                    label == null ? "" : label.trim().toUpperCase(Locale.ROOT))) {
                return available;
            }
        }
        return TermExtract.DEFAULT_METHOD;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
