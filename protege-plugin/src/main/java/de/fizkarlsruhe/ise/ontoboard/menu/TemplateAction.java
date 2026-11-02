package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.prov.ProvenanceSettings;
import de.fizkarlsruhe.ise.ontoboard.robot.TemplateSheet;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetBook;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

/**
 * ROBOT &gt; Template - a spreadsheet of terms, turned into axioms.
 *
 * <p>The single most used ROBOT feature in OBO projects, and the one that decides whether a domain
 * expert can contribute at all. Somebody who knows what a copolymer is, and has no intention of
 * learning Manchester syntax, fills in a spreadsheet; forty terms with definitions, parents and
 * sources arrive as axioms in one step. Until now that workflow lived entirely on the command line,
 * which is precisely where the person it is for will not go.
 */
public class TemplateAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_FILE = "file";
    private static final String OPTION_OUTCOME = "outcome";

    /** How many rows of a table are worth reading before it stops being a report. */
    private static final int MAX_LISTED = 500;

    /** What to do with the result. */
    private enum Outcome {
        CHECK("Check it, change nothing"),
        ADD("Add the axioms to this ontology"),
        FOLDER("Add every sheet in its folder"),
        STARTER("Write me a template to start from");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        String getLabel() {
            return label;
        }
    }

    private volatile File file;
    private volatile Outcome outcome = Outcome.CHECK;

    @Override
    protected String operationName() {
        return "Template";
    }

    @Override
    protected boolean configure() {
        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Template",
                "A ROBOT template is a table whose first row is your own column headings, whose "
                        + "second row says what each column means, and whose remaining rows are "
                        + "terms - one per row. TSV or CSV.",
                Arrays.asList(
                        Parameter.of(OPTION_FILE, "Template file", Parameter.Kind.FILE)
                                .required()
                                .help("The spreadsheet, saved as .tsv or .csv. Every spreadsheet "
                                        + "program exports both; which one you pick makes no "
                                        + "difference except that a definition containing a comma "
                                        + "is less trouble in a TSV.\n\nIf you have not written "
                                        + "one yet, name a file that does not exist and choose "
                                        + "'Write me a template to start from'.")
                                .build(),
                        Parameter.of(OPTION_OUTCOME, "Then", Parameter.Kind.CHOICE)
                                .choices(Outcome.CHECK.getLabel(), Outcome.ADD.getLabel(),
                                        Outcome.FOLDER.getLabel(), Outcome.STARTER.getLabel())
                                .defaultValue(Outcome.CHECK.getLabel())
                                .required()
                                .help("Check it first. Every problem in the sheet is reported at "
                                        + "once, with its row and column - unlike ROBOT itself, "
                                        + "which stops at the first one.\n\nA sheet may name "
                                        + "the rows of every other sheet in the same folder, by "
                                        + "label or by ID, in any order: they are all read "
                                        + "before this one is built. 'Add every sheet in its "
                                        + "folder' builds all of them that way at once.\n\n"
                                        + "Adding the axioms "
                                        + "puts them straight into the ontology you have open, "
                                        + "where Edit > Undo reverses the lot in one step. An ODK "
                                        + "project may prefer to keep template output in its own "
                                        + "component file, which the Makefile regenerates - in "
                                        + "that case run the template from the command line and "
                                        + "let the build own the file.")
                                .build()));
        if (chosen == null) {
            return false;
        }
        String path = chosen.get(OPTION_FILE);
        file = path == null || path.trim().isEmpty() ? null : new File(path.trim());
        for (Outcome candidate : Outcome.values()) {
            if (candidate.getLabel().equals(chosen.get(OPTION_OUTCOME))) {
                outcome = candidate;
            }
        }
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName());
        if (file == null) {
            return result.failed("No template file was given.").build();
        }
        if (outcome == Outcome.STARTER) {
            return writeStarter(ontology, result);
        }
        if (!file.isFile()) {
            return result.failed(file.getAbsolutePath() + " is not there. To start from a "
                    + "worked example, run this again with 'Write me a template to start "
                    + "from'.").build();
        }
        Map<String, List<List<String>>> sheets = sheetsBeside(file, result);
        warnAmbiguous(sheets, ontology, result);
        if (outcome == Outcome.FOLDER) {
            return addFolder(ontology, sheets, result);
        }

        // The other sheets in the folder are read first, so this one can name their rows - as
        // the sheet editor offers them and Check sheets... accepts them.
        TemplateSheet.Names names = TemplateSheet.namesOf(sheets, ontology, prefixesOf(ontology),
                file.getName());
        TemplateSheet.Result sheet = TemplateSheet.run(file.getName(),
                TemplateSheet.read(file), ontology, prefixesOf(ontology),
                IRI.create("http://www.ontoboard.org/template/" + file.getName()), names);

        result.note("Template: " + file.getAbsolutePath());
        noteNames(names, file.getName(), result);
        result.note("Rows of terms: " + sheet.getDataRows());
        reportProblems(sheet, result);
        for (TemplateSheet.Problem warning : sheet.getWarnings()) {
            result.warn(warning.where() + ": " + warning.getMessage());
        }

        if (sheet.isTemplateUnusable()) {
            return result.failed("The row saying what the columns mean could not be read, so "
                    + "nothing was generated. Every other row depends on it.").build();
        }
        result.note("Axioms generated: " + sheet.getAxiomCount());

        if (outcome == Outcome.CHECK) {
            return result.summary(describe(sheet) + " Nothing was changed.").build();
        }
        if (sheet.getAxiomCount() == 0) {
            return result.failed("The template produced no axioms, so there is nothing to add.")
                    .build();
        }
        return add(ontology, sheet, result);
    }

    /** Every problem, with its row and column, so the whole sheet can be fixed in one pass. */
    private void reportProblems(TemplateSheet.Result sheet, OperationResult.Builder result) {
        if (sheet.getProblems().isEmpty()) {
            return;
        }
        result.columns("Where", "Cell", "Problem");
        int listed = 0;
        for (TemplateSheet.Problem problem : sheet.getProblems()) {
            if (listed++ >= MAX_LISTED) {
                break;
            }
            result.row(problem.where(), problem.getCell(), problem.getMessage());
        }
        if (sheet.getProblems().size() > MAX_LISTED) {
            result.note("Listing the first " + MAX_LISTED + " of "
                    + sheet.getProblems().size() + ".");
        }
        if (!sheet.isTemplateUnusable()) {
            result.warn(sheet.getProblems().size() + " row"
                    + (sheet.getProblems().size() == 1 ? "" : "s")
                    + " could not be read in full. What those rows say beyond the failing cell - "
                    + "usually the identifier and the label - is still generated, so adding the "
                    + "axioms now would create those terms without the part that failed.");
        }
    }

    /** Merges the generated axioms in, through the model manager so undo works. */
    private OperationResult add(OWLOntology ontology, TemplateSheet.Result sheet,
            OperationResult.Builder result) {
        List<OWLOntologyChange> changes = changesFor(ontology,
                java.util.Collections.singletonList(sheet.getOntology()));
        if (changes.isEmpty()) {
            return result.summary("Nothing to add - this ontology already has every axiom the "
                    + "template produces.").build();
        }
        if (BackgroundRun.abandoned()) {
            return result.failed("Stopped before anything was changed.").build();
        }
        applyOnEventThread(changes);

        result.note("Edit > Undo removes all of them in one step.");
        return result.summary("Added " + changes.size() + " axioms from " + sheet.getDataRows()
                + " rows." + (sheet.getProblems().isEmpty() ? ""
                        : " " + sheet.getProblems().size() + " row"
                                + (sheet.getProblems().size() == 1 ? " was" : "s were")
                                + " only partly read - see the table.")).build();
    }

    /**
     * What adding these generated ontologies changes: their axioms the ontology lacks, and who
     * added the terms that are new.
     */
    private static List<OWLOntologyChange> changesFor(OWLOntology ontology,
            List<OWLOntology> generated) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        java.util.Set<OWLAxiom> seen = new java.util.HashSet<OWLAxiom>();
        java.util.Set<IRI> introduced = new java.util.LinkedHashSet<IRI>();
        for (OWLOntology each : generated) {
            if (each == null) {
                continue;
            }
            for (OWLAxiom axiom : each.getAxioms()) {
                if (!ontology.containsAxiom(axiom) && seen.add(axiom)) {
                    changes.add(new AddAxiom(ontology, axiom));
                }
            }
            for (OWLEntity entity : each.getSignature()) {
                if (!ontology.containsEntityInSignature(entity.getIRI())) {
                    introduced.add(entity.getIRI());
                }
            }
        }
        if (changes.isEmpty()) {
            return changes;
        }
        // Who added these terms, on the same terms as everywhere else. A batch of forty terms
        // arriving anonymously is exactly the case where "who added this" gets asked later.
        ProvenanceSettings settings = ProvenanceSettings.load();
        if (settings.shouldStamp(ontology, false)) {
            changes.addAll(0, Provenance.declareProperties(ontology));
            for (IRI term : introduced) {
                changes.addAll(Provenance.stampNew(ontology, term, settings.canonicalAgent(),
                        Provenance.today()));
            }
        }
        return changes;
    }

    /**
     * The sheets of the chosen sheet's folder by file name, as the sheet editor reads them - with
     * the chosen one always among them, and a word about every file that is not.
     *
     * <p>The sheet editor knows a sheet by its name without the extension, so of cities.csv and
     * cities.tsv it reads one; and it reads .tsv and .csv only, where Template... also takes .tab.
     * Either way the chosen sheet could be left out of its own folder's build without a word.
     */
    static Map<String, List<List<String>>> sheetsBeside(File chosen,
            OperationResult.Builder result) {
        File directory = chosen.getAbsoluteFile().getParentFile();
        SheetBook folder = SheetBook.open(directory);
        Map<String, List<List<String>>> sheets = new LinkedHashMap<String, List<List<String>>>();
        java.util.Set<String> accounted = new java.util.HashSet<String>();
        for (SheetBook.Sheet sheet : folder.getSheets()) {
            sheets.put(sheet.getFile().getName(), sheet.getRows());
            accounted.add(sheet.getFile().getName());
        }
        for (SheetBook.Unreadable unreadable : folder.getUnreadable()) {
            accounted.add(unreadable.getFile().getName());
            result.warn(unreadable.getFile().getName() + " was not read, so no sheet can name "
                    + "its rows: " + unreadable.getReason());
        }
        if (!sheets.containsKey(chosen.getName())) {
            String stem = stemOf(chosen.getName());
            for (java.util.Iterator<String> each = sheets.keySet().iterator(); each.hasNext();) {
                String other = each.next();
                if (stemOf(other).equalsIgnoreCase(stem)) {
                    each.remove();
                    result.warn(other + " was set aside: it has the chosen sheet's name with "
                            + "another extension.");
                }
            }
            List<List<String>> rows = new ArrayList<List<String>>();
            for (List<String> row : TemplateSheet.read(chosen)) {
                rows.add(new ArrayList<String>(row));
            }
            sheets.put(chosen.getName(), rows);
            accounted.add(chosen.getName());
        }
        File[] files = directory == null ? null : directory.listFiles();
        if (files != null) {
            for (File each : files) {
                String lower = each.getName().toLowerCase(java.util.Locale.ROOT);
                if (each.isFile() && (lower.endsWith(".tsv") || lower.endsWith(".csv"))
                        && !accounted.contains(each.getName())
                        && !sheets.containsKey(each.getName())) {
                    result.warn(each.getName() + " was not read: a file of the same name with "
                            + "another extension was read in its place.");
                }
            }
        }
        return sheets;
    }

    private static String stemOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * A name two sheets - or a sheet and the ontology - give to different things resolves to
     * whichever ROBOT happens to pick. Measured on a real workbook: one institute's label carried
     * two IRIs across sheets, and nine cells named it.
     */
    private static void warnAmbiguous(Map<String, List<List<String>>> sheets,
            OWLOntology ontology, OperationResult.Builder result) {
        de.fizkarlsruhe.ise.ontoboard.sheet.LabelIndex index =
                de.fizkarlsruhe.ise.ontoboard.sheet.LabelIndex.empty()
                        .plus(ontology, "the open ontology");
        for (Map.Entry<String, List<List<String>>> sheet : sheets.entrySet()) {
            index = index.plus(sheet.getKey(), sheet.getValue());
        }
        int ambiguous = index.ambiguous().size();
        if (ambiguous > 0) {
            result.warn(ambiguous + (ambiguous == 1 ? " name means" : " names mean") + " more "
                    + "than one thing across these sheets, and a cell naming one gets whichever "
                    + "ROBOT picks. Spreadsheets > Check sheets... lists them.");
        }
    }

    /** Which other sheets this one could name rows of, and which could not be read for it. */
    private static void noteNames(TemplateSheet.Names names, String self,
            OperationResult.Builder result) {
        List<String> others = new ArrayList<String>(names.getSheets());
        others.remove(self);
        if (!others.isEmpty()) {
            result.note("Rows it may name from the other sheets in its folder: "
                    + String.join(", ", others));
        }
        for (String unavailable : names.getUnavailable()) {
            result.warn(unavailable);
        }
    }

    /**
     * Every sheet in the chosen sheet's folder, built against the same names - so any sheet may
     * name a row of any other, in whatever order the files happen to sort - and added in one step.
     *
     * <p>The way a knowledge graph spread over sheets was built before was one sheet at a time, in
     * an order the author had to work out - cities before the pizzerias in them - and two sheets
     * that name each other could not be built at all.
     */
    private OperationResult addFolder(OWLOntology ontology,
            Map<String, List<List<String>>> sheets, OperationResult.Builder result) {
        File directory = file.getAbsoluteFile().getParentFile();
        result.note("Folder: " + directory.getAbsolutePath());
        for (String unavailable : TemplateSheet.namesOf(sheets, ontology, prefixesOf(ontology))
                .getUnavailable()) {
            result.warn(unavailable);
        }
        if (sheets.isEmpty()) {
            return result.failed("There are no sheets in " + directory.getAbsolutePath()
                    + " - a sheet is a .tsv or .csv with a heading row and a template row.")
                    .build();
        }
        Map<String, TemplateSheet.Result> built = TemplateSheet.runAll(sheets, ontology,
                prefixesOf(ontology), "http://www.ontoboard.org/template/");

        List<OWLOntology> generated = new ArrayList<OWLOntology>();
        List<String> unusable = new ArrayList<String>();
        int rows = 0;
        int problems = 0;
        for (Map.Entry<String, TemplateSheet.Result> sheet : built.entrySet()) {
            TemplateSheet.Result outcomeOfSheet = sheet.getValue();
            rows += outcomeOfSheet.getDataRows();
            if (outcomeOfSheet.isTemplateUnusable()) {
                unusable.add(sheet.getKey());
            } else {
                generated.add(outcomeOfSheet.getOntology());
            }
            for (TemplateSheet.Problem problem : outcomeOfSheet.getProblems()) {
                if (problems == 0) {
                    result.columns("Sheet", "Where", "Cell", "Problem");
                }
                if (problems++ < MAX_LISTED) {
                    result.row(sheet.getKey(), problem.where(), problem.getCell(),
                            problem.getMessage());
                }
            }
            for (TemplateSheet.Problem warning : outcomeOfSheet.getWarnings()) {
                result.warn(sheet.getKey() + ", " + warning.where() + ": "
                        + warning.getMessage());
            }
        }
        if (problems > MAX_LISTED) {
            result.note("Listing the first " + MAX_LISTED + " of " + problems + ".");
        }
        if (!unusable.isEmpty()) {
            result.warn("Nothing was built from " + String.join(", ", unusable) + ": the row "
                    + "saying what the columns mean could not be read.");
        }
        result.note(sheets.size() + " sheets, " + rows + " rows of terms; every sheet could "
                + "name the rows of every other.");

        int usable = 0;
        for (OWLOntology each : generated) {
            if (each != null && each.getAxiomCount() > 0) {
                usable++;
            }
        }
        if (usable == 0) {
            return result.failed("No sheet in the folder produced anything to add - see the "
                    + "problems listed.").build();
        }
        List<OWLOntologyChange> changes = changesFor(ontology, generated);
        if (changes.isEmpty()) {
            return result.summary("Nothing to add - this ontology already has every axiom the "
                    + "sheets produce.").build();
        }
        if (BackgroundRun.abandoned()) {
            return result.failed("Stopped before anything was changed.").build();
        }
        applyOnEventThread(changes);
        result.note("Edit > Undo removes all of them in one step.");
        return result.summary("Added " + changes.size() + " axioms from " + usable + " of "
                + sheets.size() + " sheets." + (problems == 0 ? ""
                        : " " + problems + " row" + (problems == 1 ? " was" : "s were")
                                + " only partly read - see the table.")).build();
    }

    /**
     * Writes a template to start from.
     *
     * <p>The blank page is the real barrier. Somebody told to "fill in a ROBOT template" and left
     * with an empty spreadsheet has to go and find the documentation, and most of them do not come
     * back.
     */
    private OperationResult writeStarter(OWLOntology ontology, OperationResult.Builder result) {
        // THE NAME HAS TO MATCH WHAT IS WRITTEN. What comes out is tab-separated text, and
        // asking for a spreadsheet is the obvious thing to do - somebody typed templates.xlsx
        // and got 562 bytes of TSV with an .xlsx name, which Excel then refused to open and
        // which ROBOT would not have read either. Writing one format under another format's
        // extension is worse than refusing, because the file looks fine until something tries
        // to use it.
        String lower = file.getName().toLowerCase();
        if (!lower.endsWith(".tsv") && !lower.endsWith(".csv")) {
            String suggested = file.getName();
            int dot = suggested.lastIndexOf('.');
            suggested = (dot > 0 ? suggested.substring(0, dot) : suggested) + ".tsv";
            return result.failed("A starter is tab-separated text, so it has to be named .tsv "
                    + "or .csv - \"" + file.getName() + "\" says it is something else, and "
                    + "writing TSV into it would give you a file nothing can open. Name it \""
                    + suggested + "\" instead. Every spreadsheet program opens a .tsv, and "
                    + "saves back to one.").build();
        }
        if (file.exists()) {
            return result.failed(file.getAbsolutePath() + " already exists. Overwriting it would "
                    + "destroy whatever is in it - name a file that does not exist.").build();
        }
        if (file.getParentFile() != null && !file.getParentFile().isDirectory()
                && !file.getParentFile().mkdirs()) {
            return result.failed("Could not create " + file.getParentFile().getAbsolutePath())
                    .build();
        }
        String[] ids = starterIdentifiersFor(ontology, fileOf(ontology));
        try {
            Files.write(file.toPath(),
                    TemplateSheet.starterWithIris(ids[0], ids[1], ids[2])
                            .getBytes(Charset.forName("UTF-8")));
        } catch (IOException cannotWrite) {
            return result.failed("Could not write " + file.getAbsolutePath() + ": "
                    + cannotWrite.getMessage()).build();
        }
        result.wrote(file);
        result.note("Open it in any spreadsheet program. Keep the first two rows: the first is "
                + "your own headings, the second says what each column means.");
        result.note("The example rows are there to be replaced - and to be run, so you can see "
                + "what comes out before you have written anything.");
        return result.summary("Wrote a template with the columns an OBO term needs: an "
                + "identifier, a label, a parent, a definition and where the definition came "
                + "from.").build();
    }

    /** The editing ontology's prefixes, so a project's own CURIEs resolve in a template. */
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

    /**
     * The project's own prefix, for the example identifiers in a starter template.
     *
     * <p>Taken from the ontology IRI rather than the prefix map, because the prefix map is full of
     * other people's namespaces and there is no way to tell which one is the project's - whereas
     * {@code .../obo/mwo.owl} says "mwo" unambiguously.
     */
    /**
     * Three identifiers for the starter's example rows, free and in the project's own shape.
     *
     * <p>Read from {@code -idranges.owl}, which is the file that decides what this project's
     * identifiers look like, and continued past the highest already in the ontology so the
     * rows cannot land on a published term. Where there is no id ranges file - an ontology
     * that is not an ODK project - the ontology's own IRI gives the namespace, and failing
     * that the rows fall back to {@code ex:}, which at least resolves because ROBOT knows it.
     *
     * <p>Static and package-visible so a test can check the three against a real project
     * without opening Prot&eacute;g&eacute;.
     */
    public static String[] starterIdentifiersFor(OWLOntology ontology, File ontologyFile) {
        String namespace = "";
        int digits = 7;
        long aboveEveryRange = 0;
        File rangesFile = de.fizkarlsruhe.ise.ontoboard.odk.TermMinter
                .findRangesFile(ontologyFile);
        if (rangesFile != null) {
            try {
                de.fizkarlsruhe.ise.ontoboard.odk.IdRanges ranges =
                        de.fizkarlsruhe.ise.ontoboard.odk.IdRanges.parse(
                                new String(Files.readAllBytes(rangesFile.toPath()),
                                        Charset.forName("UTF-8")));
                if (ranges != null && ranges.getIdPrefix() != null
                        && !ranges.getIdPrefix().trim().isEmpty()) {
                    namespace = ranges.getIdPrefix().trim();
                    digits = ranges.getIdDigits() > 0 ? ranges.getIdDigits() : 7;
                    for (de.fizkarlsruhe.ise.ontoboard.odk.IdRanges.Range range
                            : ranges.getRanges()) {
                        aboveEveryRange = Math.max(aboveEveryRange, range.getUpper());
                    }
                }
            } catch (IOException | RuntimeException | Error noRanges) {
                namespace = "";
            }
        }
        if (namespace.isEmpty()) {
            return new String[] {"ex:0000001", "ex:0000002", "ex:0000003"};
        }

        // ABOVE EVERY ALLOCATED RANGE, not merely above what this file happens to mention.
        //
        // The first version of this took the highest identifier in the ontology's signature
        // and added one, which is right when the ontology holds its own terms and dangerous
        // when it does not. nfdicore-edit.owl is fifty-three lines that import components:
        // zero NFDI_ identifiers in the file itself. So "highest in use" came back as nought
        // and the starter proposed NFDI_0000001 - a live published term, "obsolete NFDI
        // resource" - onto which it would have asserted rdfs:label "an example term" and
        // reported a clean success.
        //
        // An id ranges file declares the blocks allocated to each editor, so one past the
        // highest upper bound is outside all of them by construction, whatever any single
        // file happens to contain. The signature is still consulted, because a project can
        // have terms above its declared ranges, and the larger of the two wins.
        long next = Math.max(aboveEveryRange, highestUnder(ontology, namespace)) + 1;
        String[] ids = new String[3];
        for (int at = 0; at < 3; at++) {
            ids[at] = namespace + pad(next + at, digits);
        }
        return ids;
    }

    /** The highest number already used under this namespace, or zero. */
    private static long highestUnder(OWLOntology ontology, String namespace) {
        long highest = 0;
        if (ontology == null) {
            return highest;
        }
        for (org.semanticweb.owlapi.model.OWLEntity entity : ontology.getSignature(
                org.semanticweb.owlapi.model.parameters.Imports.INCLUDED)) {
            String iri = entity.getIRI().toString();
            if (!iri.startsWith(namespace)) {
                continue;
            }
            String rest = iri.substring(namespace.length());
            if (rest.isEmpty()) {
                continue;
            }
            boolean digitsOnly = true;
            for (int at = 0; at < rest.length(); at++) {
                if (!Character.isDigit(rest.charAt(at))) {
                    digitsOnly = false;
                    break;
                }
            }
            if (!digitsOnly) {
                continue;
            }
            try {
                highest = Math.max(highest, Long.parseLong(rest));
            } catch (NumberFormatException tooBig) {
                continue;
            }
        }
        return highest;
    }

    private static String pad(long number, int digits) {
        StringBuilder text = new StringBuilder(Long.toString(number));
        while (text.length() < digits) {
            text.insert(0, '0');
        }
        return text.toString();
    }

    private String projectPrefixOf(OWLOntology ontology) {
        if (ontology == null || !ontology.getOntologyID().getOntologyIRI().isPresent()) {
            return "ex";
        }
        String iri = ontology.getOntologyID().getOntologyIRI().get().toString();
        int slash = iri.lastIndexOf('/');
        String last = slash < 0 ? iri : iri.substring(slash + 1);
        int dot = last.indexOf('.');
        String name = dot < 0 ? last : last.substring(0, dot);
        return name.matches("[A-Za-z][A-Za-z0-9_-]*") ? name : "ex";
    }

    private String describe(TemplateSheet.Result sheet) {
        if (sheet.getProblems().isEmpty()) {
            return sheet.getDataRows() + " row" + (sheet.getDataRows() == 1 ? "" : "s")
                    + " read, producing " + sheet.getAxiomCount() + " axioms with nothing wrong.";
        }
        return sheet.getProblems().size() + " problem"
                + (sheet.getProblems().size() == 1 ? "" : "s") + " in " + sheet.getDataRows()
                + " rows.";
    }
}
