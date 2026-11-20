package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.Catalog;
import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotException;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportModules;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportProducts;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportedTerms;
import de.fizkarlsruhe.ise.ontoboard.odk.ModuleRebuild;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkBuildSettings;
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
    private static final String OPTION_KIND = "kind";
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

    /**
     * In an ODK project: what the module holds - one of ODK's module types, declared, and
     * built by ODK's own rule for it. The extraction here, before ODK builds it, is BOT.
     */
    private volatile ImportProducts.Kind kind = ImportProducts.Kind.MODULE;
    private volatile Outcome outcome = Outcome.SAVE_AND_IMPORT;
    private volatile String moduleFileText = "";

    @Override
    protected String operationName() {
        return "Import terms";
    }

    /** The import of terms writes their module, list and catalog entry beside the open file. */
    @Override
    protected String worksBesideTheFile() {
        return "the import of these terms";
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

    /**
     * Seeds the dialog as {@link #seedWith} does, for a pattern from OntoBoard's library.
     *
     * <p>Which changes what is recorded, not what is extracted: the term list names the pattern's
     * own IRI and the pattern, and no product is declared - see
     * {@link ImportModules#writeTerms(File, String, java.util.List, String, String)}. If the
     * source is changed in the dialog, it is no longer the pattern that is being imported, and the
     * import is recorded like any other.
     */
    void seedWithPattern(String source, String terms, String pattern) {
        seedWith(source, terms);
        this.seededPattern = pattern;
    }

    /**
     * Seeds the dialog as {@link #seedWith} does, with the source already loaded - by Find terms,
     * which had to read the whole ontology to search its labels. Reading it a second time to
     * extract the module would download a large ontology twice.
     */
    void seedWithLoaded(String source, String terms, OWLOntology loaded) {
        seedWith(source, terms);
        this.seededOntology = loaded;
    }

    private volatile String seededSource = "";
    private volatile String seededTerms = "";
    private volatile String seededPattern = null;
    private volatile OWLOntology seededOntology = null;

    /** The library pattern being imported, or null when the import is not one. */
    private String patternBeingImported() {
        return seededPattern != null && seededSource.equals(sourceText) ? seededPattern : null;
    }

    /** What this run calls the module, decided once the source is loaded; null before. */
    private volatile String moduleName;

    /** The module's name: its files, its IRI, its product and what the messages call it. */
    private String moduleName(OWLOntology source) {
        return moduleName != null ? moduleName : TermExtract.shortNameOf(source);
    }

    /**
     * What a module of {@code source} is called in the project at {@code projectRoot}
     * (1.135.2): the name the project already has for it, or a recognisable one for a source new
     * to it - {@code pmdco} for PMDco, which was {@code co}. Find terms says it before adding.
     *
     * @param typed where the source was read from, as typed
     */
    static String moduleNameFor(File projectRoot, final OWLOntology source, String typed) {
        java.util.List<String> sources = new ArrayList<String>();
        if (typed != null) {
            sources.add(typed.trim());
            try {
                IRI asIri = OntologySource.toIri(typed);
                if (asIri != null) {
                    sources.add(asIri.toString());
                }
            } catch (RuntimeException gone) {
                // The file it was read from has gone since - moved, renamed - and Find terms names
                // the module before the import: by what was typed and the ontology's own IRIs
                // (1.135.2 review). It threw on the Swing thread, and the confirmation never came.
            }
        }
        if (source != null) {
            if (source.getOntologyID().getOntologyIRI().isPresent()) {
                sources.add(source.getOntologyID().getOntologyIRI().get().toString());
            }
            if (source.getOntologyID().getVersionIRI().isPresent()) {
                sources.add(source.getOntologyID().getVersionIRI().get().toString());
            }
        }
        // What the source declares tells its lists from another's, whatever address each recorded.
        return ImportModules.nameFor(projectRoot, TermExtract.shortNameOf(source),
                TermExtract.recognisableNameOf(source), sources, source == null ? null
                        : iri -> source.containsEntityInSignature(iri,
                                org.semanticweb.owlapi.model.parameters.Imports.INCLUDED));
    }

    /**
     * What a term list records as its source: the IRI the terms were taken from; for a pattern,
     * and for a file of this machine (F4), the ontology's own IRI - not a {@code file:} path,
     * which means nothing anywhere else, in a list that is committed. The module's provenance
     * records the same. Null for such an ontology without an IRI; a pattern's
     * {@code # Pattern:} line says where it came from.
     */
    static String recordedSource(OWLOntology source, IRI sourceIri, String pattern) {
        boolean ofThisMachine = sourceIri != null && "file".equalsIgnoreCase(sourceIri.getScheme());
        if (pattern == null && !ofThisMachine) {
            return sourceIri == null ? null : sourceIri.toString();
        }
        if (source == null) {
            return null;
        }
        com.google.common.base.Optional<IRI> own = source.getOntologyID().getOntologyIRI();
        return own.isPresent() ? own.get().toString() : null;
    }

    /**
     * Runs with what it was seeded with and the usual choices - BOT, saved and imported, in the
     * ODK place - without the dialog. Find terms' one-step "Add them", which names the files it
     * will write before it is pressed; its "Advanced..." opens the dialog instead.
     */
    void withoutAsking() {
        this.askNothing = true;
    }

    private volatile boolean askNothing = false;

    /**
     * Set with the form, in an ODK project: the person has changes that are not saved and
     * chose to import all the same, so the project's files follow the configuration later.
     */
    private volatile boolean bringInLineLater = false;

    @Override
    protected boolean configure() {
        return chosenWithTheForm() && savedWhereTheFilesFollow();
    }

    /**
     * In an ODK project an import ends with the project's files brought in line with its
     * configuration: ODK's update rewrites the edit file on disk, and Protege reads it
     * again from there. Changes that are not saved would be lost, so they are asked about
     * here, before anything runs - also where Find terms adds without the form.
     */
    private boolean savedWhereTheFilesFollow() {
        bringInLineLater = false;
        if (outcome != Outcome.SAVE_AND_IMPORT || !SaveFirst.isNeeded(getOWLModelManager())) {
            return true;
        }
        File editFile;
        try {
            editFile = fileOf(getOWLModelManager().getActiveOntology());
        } catch (RuntimeException notSaved) {
            editFile = null;
        }
        if (editFile == null
                || !de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease.isOdkProject(editFile)) {
            return true;
        }
        SaveFirst.Answer answer = SaveFirst.ask(getOWLWorkspace(), getOWLModelManager(),
                "The ontology has changes that are not saved yet. After the import the "
                        + "project's files are brought in line with its configuration - the "
                        + "Makefile, the catalog and the edit file's imports - and the edit "
                        + "file is read again from disk, so they would be lost.",
                "Save and import", "Import, and bring the files in line later",
                "Cannot import");
        if (answer == SaveFirst.Answer.CANCELLED) {
            return false;
        }
        bringInLineLater = answer == SaveFirst.Answer.NOT_SAVED;
        return true;
    }

    /** The form, or what Find terms settled without it. */
    private boolean chosenWithTheForm() {
        if (askNothing) {
            sourceText = seededSource;
            termsText = seededTerms;
            termFileText = "";
            method = TermExtract.DEFAULT_METHOD;
            kind = ImportProducts.Kind.MODULE;
            outcome = Outcome.SAVE_AND_IMPORT;
            moduleFileText = "";
            return true;
        }
        boolean odk = inAnOdkProject();
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(OPTION_SOURCE, seededSource);
        values.put(OPTION_TERMS, seededTerms);
        values.put(OPTION_TERM_FILE, "");
        values.put(OPTION_METHOD, TermExtract.DEFAULT_METHOD.getDisplayName());
        values.put(OPTION_KIND, kindLabel(ImportProducts.Kind.MODULE));
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
                    parametersFrom(values, odk));
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
        if (odk) {
            // ODK builds the module by its rule for the kind; the extraction here is BOT.
            kind = kindByLabel(values.get(OPTION_KIND));
            method = TermExtract.DEFAULT_METHOD;
        } else {
            method = methodByLabel(values.get(OPTION_METHOD));
        }
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

    /** A kind of module as the form offers it: in plain words, ODK's name in brackets. */
    static String kindLabel(ImportProducts.Kind kind) {
        return kind.words() + " (" + kind.odkName() + ")";
    }

    private static ImportProducts.Kind kindByLabel(String label) {
        for (ImportProducts.Kind kind : ImportProducts.Kind.values()) {
            if (kindLabel(kind).equals(label)) {
                return kind;
            }
        }
        return ImportProducts.Kind.MODULE;
    }

    /** Whether the active ontology is the edit file of an ODK project. */
    private boolean inAnOdkProject() {
        try {
            File editFile = fileOf(getOWLModelManager().getActiveOntology());
            return editFile != null
                    && de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease.isOdkProject(editFile);
        } catch (RuntimeException notSaved) {
            return false;
        }
    }

    /**
     * What the module holds, for an ODK project: ODK's module types, in plain words. ODK builds
     * each by its own rule, so a refresh or a release keeps the module what was chosen.
     */
    static Parameter kindParameter(String chosen) {
        List<String> kinds = new ArrayList<String>();
        for (ImportProducts.Kind available : ImportProducts.Kind.values()) {
            kinds.add(kindLabel(available));
        }
        return Parameter.of(OPTION_KIND, "The module holds", Parameter.Kind.CHOICE)
                .choices(kinds.toArray(new String[0]))
                .defaultValue(chosen)
                .required()
                .help("What of the source ontology comes with the terms. Each is one of ODK's own "
                        + "module types - its name is in brackets - and it is declared in the "
                        + "project's configuration, so ODK's Makefile builds the module by its own "
                        + "rule for it, now and at every refresh and release.\n\n"
                        + kindLabel(ImportProducts.Kind.MODULE) + ": each term with "
                        + "its parents up to the top, and what it is defined by - for a term "
                        + "that is part of a heart, the heart and 'part of'. ODK's default, and "
                        + "what an import almost always wants.\n\n"
                        + kindLabel(ImportProducts.Kind.ONLY_THESE) + ": nothing but the "
                        + "terms you chose, and which of them is a kind of which - 'a is a b' "
                        + "when you chose both. Their other parents are left out, and so is a "
                        + "link through any other relation.\n\n"
                        + kindLabel(ImportProducts.Kind.WITH_RELATIONS) + ": the same, and "
                        + "the links through other relations as well - 'a is part of d' when "
                        + "you chose both, with 'part of'. Their other parents are left out.\n\n"
                        + kindLabel(ImportProducts.Kind.WHOLE) + ": all of it, as the source "
                        + "has it - for a small ontology you use most of.\n\n"
                        + "The module is first extracted here, so that it works at once; ODK's "
                        + "own refresh of the import then builds it as chosen. An import "
                        + "already declared keeps the kind it has; "
                        + "OntoBoard > Project > Project configuration... changes it.")
                .build();
    }

    private List<Parameter> parametersFrom(Map<String, String> values, boolean odk) {
        List<String> methods = new ArrayList<String>();
        StringBuilder methodHelp = new StringBuilder(
                "How much of the source ontology comes with the terms. The names are misleading "
                        + "and the descriptions below were checked against what the operations "
                        + "actually return.\n");
        for (TermExtract.Method available : TermExtract.Method.values()) {
            methods.add(available.getDisplayName());
            methodHelp.append('\n').append(available.getLabel()).append(": ")
                    .append(available.getHelp()).append('\n');
        }
        Parameter how = odk ? kindParameter(values.get(OPTION_KIND))
                : Parameter.of(OPTION_METHOD, "Method", Parameter.Kind.CHOICE)
                        .choices(methods.toArray(new String[0]))
                        .defaultValue(values.get(OPTION_METHOD))
                        .required()
                        .help(methodHelp.toString())
                        .build();

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
                how,
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
        OWLOntology source = seededOntology != null && seededSource.equals(sourceText)
                ? seededOntology : OntologySource.findAlreadyLoaded(
                        getOWLModelManager().getOWLOntologyManager(), sourceIri);
        if (source != null && source == seededOntology) {
            result.note("Source: " + sourceIri + " (as loaded to search it)");
        } else if (source != null) {
            result.note("Source: " + sourceIri + " (already open)");
        } else {
            source = OntologySource.loadLeavingOutMissingImports(
                    OWLManager.createOWLOntologyManager(), sourceIri);
            result.note("Source: " + sourceIri);
        }
        String missingImports = OntologySource.describeMissingImports(
                OntologySource.missingImportsOf(source));
        if (missingImports != null) {
            result.warn("The source: " + missingImports + ".");
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

        // A pattern's module keeps the name the library gives it; any other is what the project
        // already calls it, or a name a reader recognises (1.135.2). Neither takes a name another
        // import has (1.135.2 review).
        moduleName = patternBeingImported() != null
                ? de.fizkarlsruhe.ise.ontoboard.odk.ImportModules.nameForPattern(
                        projectRootFor(ontology), TermExtract.shortNameOf(source),
                        patternBeingImported(), sourceText)
                : moduleNameFor(projectRootFor(ontology), source, sourceText);
        IRI moduleIri = odkModuleIri(ontology, moduleName);
        if (moduleIri == null) {
            moduleIri = TermExtract.moduleIriIn(
                    ontology.getOntologyID().getOntologyIRI().isPresent()
                            ? ontology.getOntologyID().getOntologyIRI().get() : null, source,
                    moduleName);
        }
        // A module already on disk keeps the IRI it declares: the one its import statement and
        // catalog entry already use. A newly made-up IRI would name neither.
        if (outcome != Outcome.ADD_AXIOMS) {
            IRI declared = ModuleRebuild.declaredIriOf(moduleFileFor(ontology, source));
            if (declared != null) {
                moduleIri = declared;
            }
        }
        java.util.List<IRI> extracting = withWhatWasImportedBefore(ontology, source,
                terms.getIris(), result);
        TermExtract.Result extracted =
                TermExtract.run(source, extracting, method, moduleIri);
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
                        extracting, sourceIri);
            case SAVE_AND_IMPORT:
            default:
                return save(extracted, result, true, ontology, moduleIri, source,
                        extracting, sourceIri);
        }
    }

    /**
     * The terms to extract: these, together with every term this source's import already has.
     *
     * <p>Importing more terms from an ontology that is already imported used to replace its
     * module and term list with the new terms alone, so everything imported from it before
     * disappeared - and with it every axiom of yours that used one of those terms lost its
     * label and its parents. Terms are now only added here; taking one out is what
     * OntoBoard &gt; Imported terms... is for.
     */
    private java.util.List<IRI> withWhatWasImportedBefore(OWLOntology ontology,
            OWLOntology source, java.util.List<IRI> requested, OperationResult.Builder result) {
        listStartsAfresh = false;
        File projectRoot = projectRootFor(ontology);
        if (outcome == Outcome.ADD_AXIOMS || projectRoot == null
                || patternBeingImported() != null) {
            return requested;
        }
        File list = ImportModules.termsFileFor(projectRoot, moduleName(source));
        File module = ImportModules.moduleFileFor(projectRoot, moduleName(source));
        File written = moduleFileFor(ontology, source);
        if (written != null && module.getAbsoluteFile().equals(written.getAbsoluteFile())
                && leftByAnImportTakenBack(ontology, list, module)) {
            listStartsAfresh = true;
            result.note(list.getName() + " was left by an import of "
                    + moduleName(source) + " that is not in force now - taken back"
                    + " with Edit > Undo, or removed - so it is written afresh.");
            return requested;
        }
        java.util.List<IRI> before;
        try {
            before = list.isFile() ? ImportModules.readTerms(list)
                    : java.util.Collections.<IRI>emptyList();
        } catch (IOException unreadable) {
            result.warn("Could not read " + list.getAbsolutePath() + " (" + unreadable.getMessage()
                    + "), so only the terms given here are in the module.");
            return requested;
        }
        if (before.isEmpty()) {
            return requested;
        }
        java.util.LinkedHashSet<IRI> all = new java.util.LinkedHashSet<IRI>(before);
        all.addAll(requested);
        int joining = all.size() - before.size();
        result.note(moduleName(source) + " was imported before, with " + before.size()
                + (before.size() == 1 ? " term. " : " terms. ")
                + (joining == 0 ? "These are all among them, so the module is rebuilt as it was."
                        : joining + (joining == 1 ? " joins" : " join") + " them; nothing "
                                + "imported before is taken out. To take terms out, use "
                                + "OntoBoard > Reuse > Imported terms..."));
        return new ArrayList<IRI>(all);
    }

    /**
     * Whether a term list was left by an import of its module that is not in force now: taken
     * back with Edit &gt; Undo, or removed (1.135.1, Windows study rehearsal). A tester imported
     * the wrong IAO term, undid it and imported report; the list still named the wrong term, so
     * the import said "iao is imported already", cut both, and wrote 641 axioms for 2 terms where
     * the guide says 622 for 1. Undo deletes nothing on disk, so that Redo still finds the module;
     * such a list is simply not "imported before".
     *
     * <p>Only a list OntoBoard wrote for that module, beside the module, which the ontology
     * neither has open nor imports.
     */
    static boolean leftByAnImportTakenBack(OWLOntology ontology, File list, File module) {
        if (ontology == null || list == null || module == null || !list.isFile()
                || !module.isFile()) {
            return false;
        }
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(list.toPath(),
                java.nio.charset.StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            if (first == null || !first.trim().startsWith("# Terms extracted into "
                    + module.getName())) {
                return false;
            }
        } catch (IOException unreadable) {
            return false;
        }
        if (ModuleRebuild.openFrom(ontology, module) != null) {
            return false;
        }
        for (org.semanticweb.owlapi.model.OWLImportsDeclaration imported
                : ontology.getImportsDeclarations()) {
            if (imported.getIRI().toString().endsWith("/" + module.getName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Set while an import writes its term list afresh, because the list on disk was left by an
     * import that is not in force now.
     */
    private volatile boolean listStartsAfresh;

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
        return result.summary("Copied " + counted(changes.size(), "axiom") + " for "
                + counted(extracted.getRequested() - extracted.getMissing().size(), "term")
                + " into this ontology.").build();
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
        if (patternBeingImported() != null) {
            // A pattern's term with no label gets one made from its name, marked as made here, so
            // the board reads and the project's label check passes - see NamesAsLabels.
            java.util.List<String> made = de.fizkarlsruhe.ise.ontoboard.pattern.NamesAsLabels
                    .labelTheUnlabelled(extracted.getModule(), ontology);
            if (!made.isEmpty()) {
                result.note(counted(made.size(), "term") + " of the pattern had no label; each"
                        + " got one made from its name, marked as made by OntoBoard: "
                        + String.join(", ", made.subList(0, Math.min(8, made.size())))
                        + (made.size() > 8 ? " and " + (made.size() - 8) + " more." : "."));
            }
        }
        try {
            writeModule(extracted.getModule(), target);
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
        //
        // A PATTERN is declared as kept as it is (custom, no mirror): it is not refreshed from
        // anywhere - importing it again is what changes it - and the only mirror_from there
        // was to give it was the library's working copy on this machine, a file: path that
        // resolved on no other.
        //
        // AND NOT AT ALL, in an ODK project, for a module that is saved and nothing else: a
        // declared product is an import there - ODK's update writes it into the edit file.
        File editFile = fileOf(ontology);
        boolean odk = editFile != null
                && de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease.isOdkProject(editFile);
        boolean declared = false;
        if (odk && !andImport && ImportProducts.named(ImportProducts.declaredIn(
                editFile.getAbsoluteFile().getParentFile()), moduleName(source)) == null) {
            result.note("No product was declared for it in the project's configuration: ODK's "
                    + "update writes a declared product into the edit file as an import, and "
                    + "this was to save the module and do nothing else.");
        } else if (odk || patternBeingImported() == null) {
            declared = declareProduct(result, ontology, source, sourceIri,
                    patternBeingImported() != null, odk);
        } else {
            // The older layout, as ever: its Makefile is OntoBoard's own, and nothing in
            // it is refreshed from a pattern.
            result.note("Not declared in import_group.products: a pattern from the library is "
                    + "changed by importing it again, so nothing needs to download it.");
        }

        if (isOutsideTheProject(ontology, target)) {
            result.warn("The module is outside the ontology's own directory, so it will not be "
                    + "committed with the project and the catalog entry pointing at it will not "
                    + "resolve for anybody else.");
        }

        if (!andImport) {
            // Nothing else - but a module this ontology imports already is open in Protege,
            // which holds what the file held before and would ask about the newer file.
            WhatARunWrote.Done again = protegeReadsAgain().readAgain(
                    java.util.Collections.singletonList(target));
            if (!again.read().isEmpty()) {
                result.note("This ontology imports that module already, so Protege has read "
                        + "it again from the file.");
            }
            for (String why : again.notRead()) {
                result.warn(why);
            }
            return result.summary("Wrote a module of " + counted(extracted.getAxiomCount(), "axiom")
                    + ". Nothing else was changed.").build();
        }

        if (odk) {
            return importedIntoAnOdkProject(extracted, result, ontology, moduleIri, source,
                    requestedTerms, target, editFile, declared);
        }
        result.note("Imported as " + wireUp(result, ontology, moduleIri, target, false));
        return result.summary(moduleSummary(extracted.getAxiomCount(),
                extracted.getRequested() - extracted.getMissing().size())).build();
    }

    /**
     * How an import ends in an ODK project: the project's files follow its configuration, and
     * ODK builds the module.
     *
     * <p>What a person does by hand there is three steps: the product into the configuration,
     * ODK's update ({@code update_repo}), ODK's refresh of that import. The first is done by
     * now. The second is {@link ConfigSync}: the Makefile learns the product, and the catalog
     * entry and the edit file's import are ODK's own - so where it ran, nothing is wired up
     * here by hand. The third is ODK's own {@code refresh-<id>}: the module in the project is
     * then what ODK's refresh builds from the term list, not what OntoBoard extracted, and
     * the next refresh or release changes it only where its source has changed.
     *
     * <p>Where a step cannot run, what was done before it stands, and the result says what
     * brings the rest: OntoBoard's own extraction is a module that works, wired up by hand
     * as in a project of the older layout.
     *
     * @param declared whether the product was written into the configuration just now
     */
    private OperationResult importedIntoAnOdkProject(TermExtract.Result extracted,
            OperationResult.Builder result, OWLOntology ontology, IRI moduleIri,
            OWLOntology source, java.util.List<IRI> requestedTerms, File target, File editFile,
            boolean declared) {
        ConfigSync.Done synced = null;
        // The IRI the import is under: the module's own, or the one the edit file imports its
        // file by already (Phase 7, nfdicore).
        IRI importedAs = moduleIri;
        if (declared) {
            boolean unsaved = bringInLineLater
                    || getOWLModelManager().getDirtyOntologies().contains(ontology);
            synced = ConfigSync.run(getOWLWorkspace(), getOWLModelManager(), editFile, unsaved
                    ? de.fizkarlsruhe.ise.ontoboard.odk.OdkSync.later("The ontology has changes "
                            + "that are not saved, and bringing the project's files in line "
                            + "rewrites the edit file and reads it again, so that was left for "
                            + "now")
                    : ConfigSync.wayFor(editFile));
            ConfigSync.tell(result, synced);
        }
        if (synced != null && synced.inLine()) {
            // ODK's update wrote the catalog entry and the import, and Protege read the edit
            // file again with them. What it has open of the module's own file - from an import
            // that was there before - is brought up to the file as well.
            for (String why : protegeReadsAgain().readAgain(
                    java.util.Collections.singletonList(target)).notRead()) {
                result.warn(why);
            }
        } else {
            // Nothing changed in the configuration, or the files could not follow it now.
            importedAs = wireUp(result, ontology, moduleIri, target, true);
        }
        java.util.List<IRI> found = new ArrayList<IRI>(requestedTerms);
        found.removeAll(extracted.getMissing());
        int built = builtByOdk(result, editFile, moduleName(source), found, target,
                synced == null || synced.inLine());
        int axioms = built < 0 ? extracted.getAxiomCount() : built;
        result.note("Imported as " + importedAs);
        return result.summary(moduleSummary(axioms,
                extracted.getRequested() - extracted.getMissing().size())).build();
    }

    /**
     * Has ODK's own target build the module of an import ODK builds, and reads it to see that
     * the terms asked for are in it.
     *
     * @param found  the terms asked for that the source has
     * @param inLine whether the project's files are what its configuration asks for: ODK's
     *               Makefile knows the import only then
     * @return the axioms of the module ODK built; -1 where ODK did not build it, and the
     *         module is still what was extracted here
     */
    private int builtByOdk(OperationResult.Builder result, File editFile, String id,
            java.util.List<IRI> found, File module, boolean inLine) {
        File ontologyDirectory = editFile.getAbsoluteFile().getParentFile();
        java.util.List<ImportProducts.Product> declaredNow =
                ImportProducts.declaredIn(ontologyDirectory);
        ImportProducts.Product product = ImportProducts.named(declaredNow, id);
        if (product == null || ImportProducts.isKeptAsItIs(product)) {
            // Nothing of ODK's builds this module: it is what was extracted here, and stays so.
            return -1;
        }
        String goal = "refresh-" + id;
        String instead = " The module is OntoBoard's own extraction of the terms for now; "
                + "OntoBoard > Reuse > Refresh imports... has ODK build it.";
        if (!inLine) {
            // Said with what was said of the files: ODK's Makefile does not know the import.
            result.note("The module is OntoBoard's own extraction of the terms for now; once "
                    + "the project's files are in line, "
                    + "OntoBoard > Reuse > Refresh imports... has ODK build it.");
            return -1;
        }
        if (BackgroundRun.abandoned()) {
            result.warn("ODK's own " + goal + " was not run - you stopped waiting." + instead);
            return -1;
        }
        OdkImportsRoute route = OdkImportsRoute.detect(editFile);
        if (route.way().refusal() != null) {
            result.warn("ODK's own " + goal + " was not run: "
                    + route.way().refusal().replace("\n\n", " ") + instead);
            return -1;
        }
        OdkImportsForm.Ran ran = OdkImportsForm.refresh(editFile,
                OdkImportsForm.asked(declaredNow, id, true, ontologyDirectory),
                java.util.Collections.<String>emptyList(),
                route.odk(editFile, new java.util.function.BooleanSupplier() {
                    @Override
                    public boolean getAsBoolean() {
                        return BackgroundRun.abandoned();
                    }
                }), protegeReadsAgain());
        for (String why : ran.readAgain().notRead()) {
            result.warn(why);
        }
        if (!ran.isOk()) {
            result.warn("ODK's own " + goal + " did not build the module, run "
                    + route.way().who() + ": " + ran.failure() + instead);
            java.util.List<String> said = ran.transcript();
            result.note("What it said, at the end:");
            for (String line : said.subList(Math.max(0, said.size() - 12), said.size())) {
                result.note("  " + line);
            }
            return -1;
        }
        if (route.imageNote() != null) {
            result.warn(route.imageNote());
        }
        result.note("Then ODK's own " + goal + " built the module, " + route.way().who()
                + ": it is what ODK's refresh builds from the term list.");
        try {
            OWLOntology built = OntologySource.loadLeavingOutMissingImports(
                    OWLManager.createOWLOntologyManager(), IRI.create(module.toURI()));
            for (IRI missing : notIn(built, found)) {
                result.warn(missing + " was asked for and is not in the module ODK built. ODK "
                        + "builds it from the source its product declares ("
                        + product.getSource() + "), with the method and the settings the "
                        + "configuration gives the import - which may not be the ontology the "
                        + "term was found in here.");
            }
            return built.getAxiomCount();
        } catch (RuntimeException unreadable) {
            result.warn("The module ODK built could not be read to check it for the terms "
                    + "asked for: " + said(unreadable) + ".");
            return -1;
        }
    }

    /**
     * Writes the module to its file.
     *
     * <p>Where Protege has an ontology open from that file - an import that is there
     * already, getting more terms - the file is written beside it first and then, in one
     * step on the dispatch thread, put in place and its new time noted with Protege.
     * Protege looks at the files it has open about half a second after its window comes
     * to the front, on that same thread, and asks about one that is newer than the time
     * it noted. Find terms closes its dialog and this then writes at once: the look fell
     * between the write and the note that used to follow the patch, and Protege asked
     * to reload a module OntoBoard was just bringing up to date (seen in a running
     * 5.6.9). On the dispatch thread the two cannot be told apart by it.
     */
    private void writeModule(OWLOntology module, final File target) throws Exception {
        final OWLOntology open = openFrom(target);
        if (open == null) {
            module.getOWLOntologyManager().saveOntology(module, IRI.create(target.toURI()));
            return;
        }
        final File beside = new File(target.getAbsoluteFile().getParentFile(),
                target.getName() + ".ontoboard-writing");
        try {
            module.getOWLOntologyManager().saveOntology(module, IRI.create(beside.toURI()));
            String failure = onDispatchThread(new Step() {
                @Override
                public void take() throws Exception {
                    java.nio.file.Files.move(beside.toPath(), target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    noteItsFileTime(open);
                }
            });
            if (failure != null) {
                throw new IOException(failure);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(beside.toPath());
        }
    }

    /** Of the terms asked for, those a module does not mention - in the order asked. */
    static java.util.List<IRI> notIn(OWLOntology module, java.util.List<IRI> asked) {
        java.util.List<IRI> missing = new ArrayList<IRI>();
        for (IRI term : asked) {
            if (!module.containsEntityInSignature(term)) {
                missing.add(term);
            }
        }
        return missing;
    }

    /** Whether a catalog serves this import from this place already. */
    static boolean serves(File catalog, IRI moduleIri, String path) {
        if (!catalog.isFile()) {
            return false;
        }
        try {
            String served = Catalog.entryFor(new String(java.nio.file.Files.readAllBytes(
                    catalog.toPath()), java.nio.charset.StandardCharsets.UTF_8),
                    moduleIri.toString());
            return served != null
                    && served.replace('\\', '/').equals(path.replace('\\', '/'));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Wires a module up as an import by OntoBoard's own hand: the catalog entry, the module
     * into Protege's manager, the import statement.
     *
     * @param unlessServed true to leave alone a catalog that serves the module from its place
     *                     already: in an ODK project the catalog is ODK's own, as its update
     *                     writes it, and writing it again here would put it out of line
     */
    private IRI wireUp(OperationResult.Builder result, OWLOntology ontology, IRI moduleIri,
            File target, boolean unlessServed) {
        // The catalog entry is not optional. Without it the import resolves only in this session,
        // because the module's IRI has never been published anywhere - and the person who made
        // the module never sees the failure.
        File ontologyFile = fileOf(ontology);
        // The name the edit file imports the module's file by, where that is another than the
        // module's own IRI (nfdicore's shape): the import and the catalog entry are wired under
        // it, and there is nothing new to wire (Phase 7, 2026-11-20).
        IRI importIri = moduleIri;
        if (ontologyFile != null && ontologyFile.getParentFile() != null) {
            File catalog = new File(ontologyFile.getParentFile(), "catalog-v001.xml");
            String path = relativePath(ontologyFile.getParentFile(), target);
            try {
                String already = catalog.isFile() ? importedAs(new String(
                        java.nio.file.Files.readAllBytes(catalog.toPath()), "UTF-8"), path,
                        importsOf(ontology)) : null;
                if (already != null) {
                    importIri = IRI.create(already);
                    result.note("Imported as " + already + ", as before: the module calls "
                            + "itself " + moduleIri + ".");
                }
                if (!(unlessServed && serves(catalog, importIri, path))) {
                    Catalog.addEntry(catalog, importIri.toString(), path);
                    result.wrote(catalog);
                }
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
        final OWLOntology open = OntologySource.findAlreadyLoaded(manager, moduleIri);
        OWLOntology loaded = null;
        try {
            loaded = intoManager(manager, moduleIri, target,
                    changes -> applyOnEventThread(changes));
        } catch (Exception cannotLoad) {
            result.warn("The module was written but Protege could not load it back: "
                    + cannotLoad.getMessage());
        }
        if (open != null && loaded == open) {
            final java.net.URI written = target.toURI();
            javax.swing.SwingUtilities.invokeLater(() -> {
                org.protege.editor.owl.model.OWLModelManager models = getOWLModelManager();
                models.setClean(open);
                // The file was written behind Protege's back, so its watcher still holds the time
                // it was loaded at, and the next time the window was activated it would offer to
                // reload a module that is already up to date. Protege's own reload tells it the
                // same way, after patching the ontology in place.
                if (models instanceof org.protege.editor.owl.model.IOListenerManager) {
                    ((org.protege.editor.owl.model.IOListenerManager) models)
                            .fireAfterLoadEvent(open.getOntologyID(), written);
                }
            });
        }
        org.semanticweb.owlapi.model.OWLImportsDeclaration declaration = getOWLModelManager()
                .getOWLDataFactory().getOWLImportsDeclaration(importIri);
        if (!ontology.getImportsDeclarations().contains(declaration)) {
            applyOnEventThread(java.util.Collections.<OWLOntologyChange>singletonList(
                    new AddImport(ontology, declaration)));
        } else if (loaded != null && loaded != open) {
            // Declared before, but the module was not open, so it has just been loaded - and the
            // import that is already there changes nothing, so nothing tells Protege to look at
            // its imports again. Asked directly, which is what an applied import change makes
            // Protege do.
            javax.swing.SwingUtilities.invokeLater(() -> getOWLModelManager()
                    .setActiveOntologiesStrategy(
                            getOWLModelManager().getActiveOntologiesStrategy()));
        }
        return importIri;
    }

    /**
     * Puts a module that was just written into the manager Protege reads from, and returns the
     * ontology the manager now has for it.
     *
     * <p><b>A module the manager already has is changed in place, never swapped.</b> It used to be
     * removed from the OWL API manager and loaded again, and Protege was never told: its model
     * manager keeps its own list of the ontologies in view, and the import statement added after
     * the load was already there, so nothing made it rebuild that list. It went on holding the
     * removed ontology, whose manager is gone, and every view that asked it anything threw
     * IllegalStateException until Protege was restarted - on painting, on selecting a term. Found
     * by importing the same pattern twice, and by importing a second term from one source. The
     * same kind of changes as Refresh imports makes, so the ontology everything else holds is the
     * one that now says what the file says.
     *
     * <p>What the file says, read back, rather than the module as it was extracted: written out,
     * a module declares every term it mentions, and those declarations are what reopening the
     * project will read.
     *
     * @param apply applies changes to an ontology the manager already has; for Protege's, through
     *     its model manager, so the views and undo hear about them
     * @throws Exception when the module just written cannot be read
     */
    static OWLOntology intoManager(OWLOntologyManager manager, IRI moduleIri, File written,
            java.util.function.Consumer<List<OWLOntologyChange>> apply) throws Exception {
        OWLOntology open = OntologySource.findAlreadyLoaded(manager, moduleIri);
        if (open == null) {
            return manager.loadOntologyFromOntologyDocument(written);
        }
        OWLOntologyManager reader = OWLManager.createOWLOntologyManager();
        reader.setOntologyLoaderConfiguration(reader.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(
                        org.semanticweb.owlapi.model.MissingImportHandlingStrategy.SILENT));
        final IRI nowhere = IRI.create(new File(written.getAbsoluteFile().getParentFile(),
                ".ontoboard-no-such-import").toURI());
        reader.getIRIMappers().add(iri -> nowhere);
        List<OWLOntologyChange> changes = ModuleRebuild.changesToMatch(open,
                OntologySource.loadFile(reader, written));
        if (!changes.isEmpty()) {
            apply.accept(changes);
        }
        IRI document = IRI.create(written.toURI());
        if (!document.equals(manager.getOntologyDocumentIRI(open))) {
            manager.setOntologyDocumentIRI(open, document);
        }
        return open;
    }

    /**
     * What an import that was saved and imported says it did: "Wrote a module of 604 axioms for
     * 1 term, and imported it." It said "1 terms", which is the first sentence a participant reads
     * after importing a single term.
     */
    static String moduleSummary(int axioms, int terms) {
        return "Wrote a module of " + counted(axioms, "axiom") + " for " + counted(terms, "term")
                + ", and imported it.";
    }

    /** {@code 1 term}, {@code 2 terms}. */
    static String counted(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
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
                    + moduleName(source) + "_terms.txt nobody can rebuild this "
                    + "module.");
            return;
        }
        try {
            String pattern = patternBeingImported();
            File existing = ImportModules.termsFileFor(projectRoot,
                    moduleName(source));
            if (existing.isFile() && pattern == null && !listStartsAfresh) {
                // Spliced, not rewritten, so its comments and its order stay as they were.
                String before = new String(java.nio.file.Files.readAllBytes(existing.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                Map<IRI, String> labels = new LinkedHashMap<IRI, String>();
                for (IRI term : terms) {
                    labels.put(term, labelIn(source, term));
                }
                String after = ImportedTerms.withTerms(before, labels);
                if (!after.equals(before)) {
                    java.nio.file.Files.write(existing.toPath(),
                            after.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    result.wrote(existing);
                }
                return;
            }
            Map<IRI, String> labels = new LinkedHashMap<IRI, String>();
            for (IRI term : terms) {
                labels.put(term, labelIn(source, term));
            }
            File written = ImportModules.writeTerms(projectRoot, moduleName(source),
                    terms, recordedSource(source, sourceIri, pattern), pattern, labels);
            result.wrote(written);
        } catch (IOException cannotWrite) {
            result.warn("Wrote the module but could not write its term list: "
                    + cannotWrite.getMessage() + ". Without it nobody can rebuild "
                    + moduleFile.getName() + ".");
        }
    }

    /** A term's label in the ontology it is taken from, or null. */
    private static String labelIn(OWLOntology source, IRI term) {
        for (org.semanticweb.owlapi.model.OWLEntity entity : source.getEntitiesInSignature(term,
                org.semanticweb.owlapi.model.parameters.Imports.INCLUDED)) {
            String label = de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels.forEntity(source,
                    entity);
            if (label != null && !label.trim().isEmpty() && !label.equals(term.toString())) {
                return label;
            }
        }
        return null;
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
    /**
     * What Import terms says of an import already declared: it keeps the kind it is declared
     * with (Task 18's ruling), so a kind chosen that is another is not taken - said, with where
     * an import's kind is changed (the 1.154.0 review's M4). The same kind needs no word.
     */
    static String alreadyDeclared(String id, ImportProducts.Product declared,
            ImportProducts.Kind chosen) {
        String left = id + " is already declared in import_group.products, so the list was "
                + "left alone.";
        ImportProducts.Kind has = declared == null ? null : ImportProducts.Kind.of(declared);
        if (chosen == null || declared == null || chosen == has) {
            return left;
        }
        return left + " It keeps the kind it is declared with ("
                + (has != null ? "\"" + has.words() + "\"" : declared.describeModuleType())
                + "): \"" + chosen.words() + "\", chosen here, is not taken. "
                + "OntoBoard > Project > Project configuration... changes an import's kind.";
    }

    private boolean declareProduct(OperationResult.Builder result, OWLOntology ontology,
            OWLOntology source, IRI sourceIri, boolean pattern, boolean odk) {
        File projectRoot = projectRootFor(ontology);
        File editFile = fileOf(ontology);
        if (projectRoot == null || editFile == null) {
            return false;
        }
        File ontologyDirectory = editFile.getAbsoluteFile().getParentFile();
        File yaml = OdkBuildSettings.yamlIn(ontologyDirectory);
        if (yaml == null) {
            // Not an ODK project, or not one with a configuration - nothing to declare into, and
            // nothing wrong with that. The module still works through the catalog.
            return false;
        }

        String id = moduleName(source);
        ImportProducts.Product declared = ImportProducts.named(
                ImportProducts.declaredIn(ontologyDirectory), id);
        if (declared != null) {
            result.note(alreadyDeclared(id, declared, odk && !pattern ? kind : null));
            return false;
        }

        java.util.LinkedHashMap<String, String> fields;
        String caveat;
        if (pattern) {
            fields = ImportProducts.frozen(id);
            caveat = null;
        } else {
            // An ODK project: what ODK cannot fetch or build is kept as it is. The older
            // layout: as 1.135.2 declared it, which its own Refresh imports reads.
            String from = sourceIri == null ? null : sourceIri.toString();
            ImportProducts.Declaration declaration = odk
                    ? ImportProducts.declarationForImport(id, from, kind)
                    : ImportProducts.declarationAsBefore(id, from, method);
            fields = declaration.getFields();
            caveat = declaration.getCaveat();
        }
        // The older layout's custom is a rule to write by hand, as it always said.
        boolean keptAsItIs = odk && "custom".equals(fields.get("module_type"));
        try {
            String before = new String(java.nio.file.Files.readAllBytes(yaml.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            // Also into a configuration without import_group - every project ODK's seed makes.
            String after = ImportProducts.withProduct(before, fields);
            OdkBuildSettings.writeIfUnchanged(yaml, before, after);
            result.wrote(yaml);
            result.note("Declared " + id + " in import_group.products" + (keptAsItIs
                    ? ", kept as it is: " : ", so `make refresh-imports` rebuilds it: ")
                    + describe(fields));
            if (odk) {
                // Why it is kept as it is: a remark, not a fault - nothing stops on it.
                result.note(caveat != null ? caveat : pattern ? "A pattern is not refreshed "
                        + "from anywhere: ODK's refresh leaves its module alone, and "
                        + "importing the pattern again is what changes it." : null);
            } else if (caveat != null) {
                // The older layout's: make does stop on it there.
                result.warn(caveat);
            }
            return true;
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
        return false;
    }

    /**
     * Where ODK's update puts the import of a product called {@code name} - its ONTBASE, then
     * {@code imports/<name>_import.owl} - for an ODK project whose Makefile says its ONTBASE
     * plainly; else null. A new module declares that IRI, so that it is the module the edit
     * file is made to import.
     */
    private IRI odkModuleIri(OWLOntology ontology, String name) {
        File editFile = fileOf(ontology);
        if (editFile == null || name == null
                || OdkBuildSettings.yamlIn(editFile.getAbsoluteFile().getParentFile()) == null
                || !de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease.isOdkProject(editFile)) {
            return null;
        }
        String ontbase = de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease.Facts.of(editFile)
                .ontbase();
        return ontbase == null ? null : IRI.create(ontbase + "/imports/" + name
                + "_import.owl");
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
                moduleName(source) + "_import.owl");
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
        return clashAt(catalogXml, here, moduleIri, ModuleRebuild.declaredIriOf(target),
                importsOf(ontology), target.getAbsolutePath());
    }

    /**
     * The IRI the edit file already imports the module's file by - the catalog's name for it -
     * or null when it imports it by none. nfdicore's modules call themselves by another IRI than
     * that (the shape of Task 14c), and an import of more terms into one is that module still.
     *
     * @param here     the module's file, relative to the catalog's folder
     * @param imported the IRIs the edit file imports
     */
    static String importedAs(String catalogXml, String here, java.util.Set<String> imported) {
        for (String mapped : Catalog.mappedIris(catalogXml)) {
            if (here.equals(Catalog.entryFor(catalogXml, mapped)) && imported.contains(mapped)) {
                return mapped;
            }
        }
        return null;
    }

    /**
     * Why writing the module to {@code here} would destroy another module, or null: the catalog
     * gives the file to another IRI. Not when that IRI is the one the edit file imports the
     * file by, and the file declares this module's IRI - it is this module, under the name it is
     * imported by (Phase 7, nfdicore, 2026-11-20: "two imports resolving to one file" refused
     * every term imported into one of its modules).
     *
     * @param declaredInFile the ontology IRI the file on disk declares, or null
     * @param imported       the IRIs the edit file imports
     * @param target         the file, as the refusal names it
     */
    static String clashAt(String catalogXml, String here, IRI moduleIri, IRI declaredInFile,
            java.util.Set<String> imported, String target) {
        String mine = Catalog.entryFor(catalogXml, moduleIri.toString());
        if (here.equals(mine)) {
            return null; // this module's own file; overwriting it is the point
        }
        for (String otherIri : Catalog.mappedIris(catalogXml)) {
            if (here.equals(Catalog.entryFor(catalogXml, otherIri))
                    && !otherIri.equals(moduleIri.toString())) {
                if (moduleIri.equals(declaredInFile) && imported.contains(otherIri)) {
                    return null;
                }
                return target + " is already the module for " + otherIri
                        + ", according to catalog-v001.xml. Writing this module there would "
                        + "leave two imports resolving to one file, and whichever of them is not "
                        + "in that file would silently vanish the next time the project is "
                        + "opened. Choose a different file name.";
            }
        }
        return null;
    }

    /** The IRIs an ontology imports. */
    private static java.util.Set<String> importsOf(OWLOntology ontology) {
        java.util.Set<String> imported = new java.util.HashSet<String>();
        for (org.semanticweb.owlapi.model.OWLImportsDeclaration declaration
                : ontology.getImportsDeclarations()) {
            imported.add(declaration.getIRI().toString());
        }
        return imported;
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
        String chosen = label == null ? "" : label.trim();
        for (TermExtract.Method available : TermExtract.Method.values()) {
            // Either spelling: the dialog offers the plain name, and a saved value or a
            // pattern seed may still carry ROBOT's.
            if (available.getLabel().equalsIgnoreCase(chosen.toUpperCase(Locale.ROOT))
                    || available.getDisplayName().equals(chosen)) {
                return available;
            }
        }
        return TermExtract.DEFAULT_METHOD;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
