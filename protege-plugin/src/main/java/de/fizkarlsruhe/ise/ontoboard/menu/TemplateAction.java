package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.prov.ProvenanceSettings;
import de.fizkarlsruhe.ise.ontoboard.robot.TemplateSheet;
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
                                        Outcome.STARTER.getLabel())
                                .defaultValue(Outcome.CHECK.getLabel())
                                .required()
                                .help("Check it first. Every problem in the sheet is reported at "
                                        + "once, with its row and column - unlike ROBOT itself, "
                                        + "which stops at the first one.\n\nAdding the axioms "
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

        TemplateSheet.Result sheet = TemplateSheet.run(file.getName(),
                TemplateSheet.read(file), ontology, prefixesOf(ontology),
                IRI.create("http://www.ontoboard.org/template/" + file.getName()));

        result.note("Template: " + file.getAbsolutePath());
        result.note("Rows of terms: " + sheet.getDataRows());
        reportProblems(sheet, result);

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
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        for (OWLAxiom axiom : sheet.getOntology().getAxioms()) {
            if (!ontology.containsAxiom(axiom)) {
                changes.add(new AddAxiom(ontology, axiom));
            }
        }
        if (changes.isEmpty()) {
            return result.summary("Nothing to add - this ontology already has every axiom the "
                    + "template produces.").build();
        }
        if (BackgroundRun.abandoned()) {
            return result.failed("Stopped before anything was changed.").build();
        }

        // Who added these terms, on the same terms as everywhere else. A batch of forty terms
        // arriving anonymously is exactly the case where "who added this" gets asked later.
        ProvenanceSettings settings = ProvenanceSettings.load();
        if (settings.shouldStamp(ontology, false)) {
            changes.addAll(0, Provenance.declareProperties(ontology));
            for (OWLEntity entity : sheet.getOntology().getSignature()) {
                if (!ontology.containsEntityInSignature(entity.getIRI())) {
                    changes.addAll(Provenance.stampNew(ontology, entity.getIRI(),
                            settings.canonicalAgent(), Provenance.today()));
                }
            }
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
