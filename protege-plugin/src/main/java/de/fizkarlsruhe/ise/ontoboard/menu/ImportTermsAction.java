package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.Catalog;
import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotException;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportModules;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportProducts;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkBuildSettings;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkYaml;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import de.fizkarlsruhe.ise.ontoboard.robot.TermList;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JOptionPane;
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

    /**
     * Fills the source and terms in before the dialog opens.
     *
     * <p>So that the pattern library can hand a chosen pattern to this action instead of
     * carrying its own copy of what follows. Those five steps - extract, write the module, write
     * the term list, add the import, add the catalog entry - have four separate guards in them
     * that exist because each one was once got wrong, and a second implementation would start
     * without any of them. The dialog still opens: the user picks BOT or STAR and what to do
     * with the module, exactly as if they had typed the source themselves.
     */
    void seedWith(String source, String terms) {
        this.seededSource = source == null ? "" : source;
        this.seededTerms = terms == null ? "" : terms;
    }

    private volatile String seededSource = "";
    private volatile String seededTerms = "";

    @Override
    protected boolean configure() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(OPTION_SOURCE, seededSource);
        values.put(OPTION_TERMS, seededTerms);
        values.put(OPTION_TERM_FILE, "");
        values.put(OPTION_METHOD, TermExtract.DEFAULT_METHOD.getLabel());
        values.put(OPTION_OUTCOME, Outcome.SAVE_AND_IMPORT.label);
        values.put(OPTION_MODULE_FILE, "");

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
                        .help("Where the module file goes. Leave it empty and it is named after "
                                + "the source ontology and put in an imports/ directory beside "
                                + "your own - imports/iao_import.owl for IAO - which is the ODK "
                                + "layout, so the project looks like every other OBO project and "
                                + "its Makefile knows where to find things. Fill it in only to "
                                + "put the module somewhere else. Ignored when you choose to copy "
                                + "the axioms in instead.")
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
        // Which release this module was cut from, recorded in the module itself. Without it a
        // module in src/ontology/imports/ says nothing about where it came from: nobody dares
        // regenerate it, because regenerating means guessing, and nobody can tell whether the
        // definitions in it are current or four years stale. The module becomes a fork of the
        // upstream ontology that nobody decided to make.
        extracted.getModule().getOWLOntologyManager().applyChanges(
                ImportProvenance.stamp(extracted.getModule(), source, Provenance.today()));

        result.note("Method: " + method.getLabel());
        result.note("Module IRI: " + moduleIri);
        ImportProvenance.Report provenance =
                ImportProvenance.check(extracted.getModule(), source.getOWLOntologyManager());
        if (provenance.getCutFrom() != null) {
            result.note("Cut from: " + provenance.getCutFrom());
        } else if (provenance.getSource() != null) {
            result.note("Cut from: " + provenance.getSource()
                    + " - which publishes no version IRI, so the extraction date is the only "
                    + "record of which release this is.");
        }
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

        if (BackgroundRun.abandoned()) {
            // The user stopped waiting. Everything below writes files or changes the ontology,
            // and doing that after somebody walked away is worse than not being cancellable.
            return result.failed("Stopped before anything was written.").build();
        }

        switch (outcome) {
            case ADD_AXIOMS:
                return copyIn(ontology, extracted, result);
            case SAVE_ONLY:
                return save(extracted, result, false, ontology, moduleIri, source,
                        terms.getIris(), sourceIri);
            case SAVE_AND_IMPORT:
            default:
                return save(extracted, result, true, ontology, moduleIri, source,
                        terms.getIris(), sourceIri);
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
        if (BackgroundRun.abandoned()) {
            return result.failed("Stopped before anything was changed.").build();
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
            boolean andImport, OWLOntology ontology, IRI moduleIri, OWLOntology source,
            java.util.List<IRI> requestedTerms, IRI sourceIri) {
        File target = moduleFileFor(ontology, source);
        if (target == null) {
            return result.failed("This ontology has not been saved, so there is nowhere to put "
                    + "the module. Save it first, or type a path to save the module at.").build();
        }
        // Named after the source, so two imports cannot land on one file. They used to share a
        // single default - imports/extracted_import.owl - so importing from IAO and then from
        // ChEBI overwrote the first module with the second while adding a catalog entry for each.
        // Both import IRIs then resolved to one file holding only the second module. In the
        // session it looked right, because the first was still in memory; reopening the project,
        // or checking it out, lost every term from the first import with nothing to say so.
        String clash = wouldOverwriteAnotherModule(target, moduleIri, ontology);
        if (clash != null) {
            return result.failed(clash).build();
        }
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

        // The term list, beside the module. An ODK project does not commit a module as a finished
        // artefact - it commits the list of terms it wants and rebuilds the module from it, which
        // is what makes `make refresh-imports` meaningful. Writing only the module leaves the next
        // person a binary blob they cannot reproduce, extend, or tell has gone stale against its
        // source. This plugin wrote only the module until 1.38.0.
        writeTermList(result, ontology, source, target, requestedTerms, sourceIri);

        // And the product in import_group.products, without which the two files above are
        // invisible to ODK. Its IMPORTS list is built from that key, so a module and term list
        // that no product declares are files `make refresh-imports` never touches - and OntoBoard
        // made exactly that state for itself, scaffolding `products: []` and then never adding to
        // it. 1.99.0 started reporting it; this stops causing it.
        declareProduct(result, ontology, source, sourceIri);

        if (isOutsideTheProject(ontology, target)) {
            result.warn("The module is outside the ontology's own directory, so it will not be "
                    + "committed with the project and the catalog entry pointing at it will not "
                    + "resolve for anybody else.");
        }

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
        OWLOntology stale = OntologySource.findAlreadyLoaded(manager, moduleIri);
        if (stale != null) {
            // The file was just rewritten, so anything already in the manager is by definition
            // the previous version. Skipping the load - which is what happened before - left
            // Protege holding the old module while reporting the new one: re-running an import
            // with an extra term wrote the term to disk, said so, and the term appeared nowhere
            // in the class hierarchy until Protege was restarted.
            manager.removeOntology(stale);
        }
        try {
            manager.loadOntologyFromOntologyDocument(target);
        } catch (Exception cannotLoad) {
            result.warn("The module was written but Protege could not load it back: "
                    + cannotLoad.getMessage());
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
            // Different drives on Windows: there is no relative path between them. A bare
            // "D:/shared/x.owl" is not a usable catalog value - resolved against the catalog's
            // own file: URI it yields a URI whose scheme is "D", which nothing can open, not even
            // on the machine that wrote it. A real file: URI at least works locally.
            return target.toURI().toString();
        }
    }

    /**
     * Where the module goes: what was typed, or the ODK location named after the source.
     *
     * <p>Named after the source is the whole point. A single fixed default meant every import
     * wrote to the same file, so the second one silently replaced the first.
     */
    /**
     * Writes {@code imports/<name>_terms.txt} beside the module.
     *
     * <p>A warning rather than a failure if it cannot be written: the module and the catalog entry
     * are already on disk and working, and losing those over a term list would be the wrong trade.
     * But it is a warning and not a note, because a module without its list is the state this is
     * here to prevent.
     */
    private void writeTermList(OperationResult.Builder result, OWLOntology ontology,
            OWLOntology source, File moduleFile, java.util.List<IRI> terms, IRI sourceIri) {
        File projectRoot = projectRootFor(ontology);
        if (projectRoot == null) {
            result.warn("No term list was written: this ontology has not been saved, so there is "
                    + "no project directory to put one in. Without imports/"
                    + TermExtract.shortNameOf(source) + "_terms.txt nobody can rebuild this "
                    + "module.");
            return;
        }
        try {
            File written = ImportModules.writeTerms(projectRoot, TermExtract.shortNameOf(source),
                    terms, sourceIri == null ? null : sourceIri.toString());
            result.wrote(written);
        } catch (IOException cannotWrite) {
            result.warn("Wrote the module but could not write its term list: "
                    + cannotWrite.getMessage() + ". Without it nobody can rebuild "
                    + moduleFile.getName() + ".");
        }
    }

    /**
     * Declares the import in {@code import_group.products}, so ODK's build knows it exists.
     *
     * <p>A warning rather than a failure whenever it cannot be done, on the same trade as the
     * term list: the module, the list and the catalog entry are already written and working, and
     * losing those over a YAML edit would be the wrong way round. But a warning and not a note,
     * because an undeclared import is one {@code make refresh-imports} silently skips.
     *
     * <p>Nothing is written when the product is already declared. Re-running this on an existing
     * import is ordinary - it is how a term list grows - and a second product with the same id
     * would give ODK two rules for one module.
     */
    private void declareProduct(OperationResult.Builder result, OWLOntology ontology,
            OWLOntology source, IRI sourceIri) {
        File projectRoot = projectRootFor(ontology);
        File editFile = fileOf(ontology);
        if (projectRoot == null || editFile == null) {
            return;
        }
        File ontologyDirectory = editFile.getAbsoluteFile().getParentFile();
        File yaml = OdkBuildSettings.yamlIn(ontologyDirectory);
        if (yaml == null) {
            // Not an ODK project, or not one with a configuration - nothing to declare into, and
            // nothing wrong with that. The module still works through the catalog.
            return;
        }

        String id = TermExtract.shortNameOf(source);
        if (ImportProducts.named(ImportProducts.declaredIn(ontologyDirectory), id) != null) {
            result.note(id + " is already declared in import_group.products, so the list was "
                    + "left alone.");
            return;
        }

        ImportProducts.Declaration declaration = ImportProducts.declarationFor(id,
                sourceIri == null ? null : sourceIri.toString(), method);
        try {
            String before = new String(java.nio.file.Files.readAllBytes(yaml.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            String after = OdkYaml.appendBlockTo(before, "import_group.products",
                    declaration.getFields());
            OdkBuildSettings.writeIfUnchanged(yaml, before, after);
            result.wrote(yaml);
            result.note("Declared " + id + " in import_group.products, so `make refresh-imports` "
                    + "rebuilds it: " + describe(declaration.getFields()));
            if (declaration.getCaveat() != null) {
                result.warn(declaration.getCaveat());
            }
        } catch (RuntimeException cannotEdit) {
            result.warn("Wrote the module and its term list, but could not add " + id + " to "
                    + "import_group.products: " + cannotEdit.getMessage() + ". Until it is "
                    + "declared there, ODK's IMPORTS list has no entry for it and "
                    + "`make refresh-imports` will not rebuild it.");
        } catch (IOException cannotEdit) {
            result.warn("Wrote the module and its term list, but could not add " + id + " to "
                    + "import_group.products: " + cannotEdit.getMessage() + ". Until it is "
                    + "declared there, ODK's IMPORTS list has no entry for it and "
                    + "`make refresh-imports` will not rebuild it.");
        }
    }

    /** The declaration on one line, so the result says what went into the file. */
    private static String describe(Map<String, String> fields) {
        StringBuilder said = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            said.append(said.length() == 0 ? "" : ", ")
                    .append(field.getKey()).append(": ").append(field.getValue());
        }
        return said.toString();
    }

    /** The ODK project the open ontology belongs to, or null when it has never been saved. */
    private File projectRootFor(OWLOntology ontology) {
        File ontologyFile = fileOf(ontology);
        return ontologyFile == null ? null : ReleaseAction.projectRootOf(ontologyFile);
    }

    private File moduleFileFor(OWLOntology ontology, OWLOntology source) {
        if (!isBlank(moduleFileText)) {
            return new File(moduleFileText.trim());
        }
        File ontologyFile = fileOf(ontology);
        if (ontologyFile == null || ontologyFile.getParentFile() == null) {
            return null;
        }
        return new File(new File(ontologyFile.getParentFile(), "imports"),
                TermExtract.shortNameOf(source) + "_import.owl");
    }

    /**
     * Why writing here would destroy another module, or null.
     *
     * <p>The catalog is the record of which file belongs to which module IRI. If it already maps
     * a <em>different</em> IRI to this file, writing would leave two import statements resolving
     * to one file that answers to only one of them - and the loss is invisible until the project
     * is reopened somewhere else.
     */
    private String wouldOverwriteAnotherModule(File target, IRI moduleIri, OWLOntology ontology) {
        File ontologyFile = fileOf(ontology);
        if (!target.isFile() || ontologyFile == null || ontologyFile.getParentFile() == null) {
            return null;
        }
        File catalog = new File(ontologyFile.getParentFile(), "catalog-v001.xml");
        if (!catalog.isFile()) {
            return null;
        }
        String catalogXml;
        try {
            catalogXml = new String(java.nio.file.Files.readAllBytes(catalog.toPath()), "UTF-8");
        } catch (IOException cannotRead) {
            return null;
        }
        String here = relativePath(ontologyFile.getParentFile(), target);
        String mine = Catalog.entryFor(catalogXml, moduleIri.toString());
        if (here.equals(mine)) {
            return null; // this module's own file; overwriting it is the point
        }
        for (String otherIri : Catalog.mappedIris(catalogXml)) {
            if (here.equals(Catalog.entryFor(catalogXml, otherIri))
                    && !otherIri.equals(moduleIri.toString())) {
                return target.getAbsolutePath() + " is already the module for " + otherIri
                        + ", according to catalog-v001.xml. Writing this module there would "
                        + "leave two imports resolving to one file, and whichever of them is not "
                        + "in that file would silently vanish the next time the project is "
                        + "opened. Choose a different file name.";
            }
        }
        return null;
    }

    /** Whether the module lies outside the tree that gets committed with the ontology. */
    private boolean isOutsideTheProject(OWLOntology ontology, File target) {
        File ontologyFile = fileOf(ontology);
        if (ontologyFile == null || ontologyFile.getParentFile() == null) {
            return false;
        }
        return relativePath(ontologyFile.getParentFile(), target).startsWith("..")
                || relativePath(ontologyFile.getParentFile(), target).contains("://");
    }

    /** The ontology's own file, or null when it has never been saved. */

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
