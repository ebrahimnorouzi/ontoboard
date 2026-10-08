package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateSheet;
import de.fizkarlsruhe.ise.ontoboard.sheet.LabelIndex;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetAudit;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Checks a folder of spreadsheets as data, rather than as something to build.
 *
 * <p><i>Template…</i> answers "will this build, and if not where is the fault". This answers
 * "is this data any good", which turns out to be a different question with different answers.
 * The knowledge graph it was written against builds through ROBOT with <b>zero</b> problems and
 * still contains 687 cells holding a template placeholder, 49 references to a name that means
 * two different things, 145 cells with pasted whitespace in them and 144 with a curly quote
 * where a plain one was meant.
 *
 * <p>It takes a folder because the names are spread across the sheets. A reference in one sheet
 * usually points at a row in another - measured on that graph, every reference in its
 * {@code dataportal} sheet is a plain-text name whose target is defined five sheets upstream -
 * so checking one sheet alone would report almost every reference as unresolvable. Everything
 * in the folder, plus the ontology that is open, is indexed first and the sheets are checked
 * against that.
 *
 * <p>Read-only. Nothing is written and the ontology is not touched; each finding carries the
 * replacement to make where there is an obvious one, and whether to apply it is the reader's
 * decision.
 */
public class SheetCheckAction extends OntoBoardAction {

    private static final String OPTION_FOLDER = "folder";
    private static final String OPTION_KINDS = "kinds";

    /** Enough to see the shape of the problem; a table of ten thousand rows is not a report. */
    private static final int MOST_SHOWN = 300;

    private static final String EVERYTHING = "Everything";
    private static final String FIXABLE = "Only the ones with an obvious correction";
    private static final String REFERENCES = "Only names that point at nothing, or at two things";

    private File folder;
    private String kinds = EVERYTHING;

    @Override
    protected String operationName() {
        return "Check sheets";
    }

    @Override
    protected boolean needsAnOntology() {
        // The ontology is a source of names rather than the subject, and a folder of sheets can
        // be checked before any of it has been built - which is when it is most useful.
        return false;
    }

    @Override
    protected boolean configure() {
        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Check sheets",
                "Looks for the mistakes that do not stop a template from building: a name that "
                        + "means two different things, two rows for one thing, a reference that "
                        + "points at nothing, text a template left behind, and whitespace or "
                        + "curly quotes that came in with a paste.\n\nEvery .tsv and .csv in the "
                        + "folder is read, and the names in all of them - and in the ontology "
                        + "you have open - are indexed together, because a reference in one "
                        + "sheet usually names a row in another.",
                Arrays.asList(
                        Parameter.of(OPTION_FOLDER, "Folder of sheets",
                                        Parameter.Kind.DIRECTORY)
                                .required()
                                .help("Where the sheets live. In an ODK project that is usually "
                                        + "src/templates.")
                                .build(),
                        Parameter.of(OPTION_KINDS, "Report", Parameter.Kind.CHOICE)
                                .choices(EVERYTHING, FIXABLE, REFERENCES)
                                .defaultValue(EVERYTHING)
                                .required()
                                .help("Everything is the honest default and can be long.\n\n"
                                        + "The second narrows it to findings that carry a "
                                        + "replacement, which is the list to work through "
                                        + "first.\n\nThe third narrows it to names: one that "
                                        + "points at nothing, and one that points at two things "
                                        + "and so resolves to whichever the builder picks.")
                                .build()));
        if (chosen == null) {
            return false;
        }
        String path = chosen.get(OPTION_FOLDER);
        folder = path == null || path.trim().isEmpty() ? null : new File(path.trim());
        kinds = chosen.get(OPTION_KINDS);
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName());
        if (folder == null || !folder.isDirectory()) {
            return result.failed("That is not a folder.").build();
        }

        Map<String, List<List<String>>> sheets = read(folder, result);
        if (sheets.isEmpty()) {
            return result.summary("No .tsv or .csv files in " + folder.getAbsolutePath()
                    + ", so there was nothing to check.").build();
        }

        LabelIndex index = LabelIndex.empty().plus(ontology, "the open ontology");
        for (Map.Entry<String, List<List<String>>> each : sheets.entrySet()) {
            index = index.plus(each.getKey(), each.getValue());
        }

        List<SheetAudit.Finding> findings = new ArrayList<SheetAudit.Finding>();
        for (Map.Entry<String, List<List<String>>> each : sheets.entrySet()) {
            findings.addAll(SheetAudit.of(each.getKey(), each.getValue(), index));
        }

        result.note("Sheets: " + sheets.size());
        result.note("Names indexed: " + index.size()
                + (ontology == null ? "" : ", including the open ontology's"));

        List<LabelIndex.Ambiguity> ambiguous = index.ambiguous();
        if (!ambiguous.isEmpty()) {
            result.warn(ambiguous.size() + (ambiguous.size() == 1 ? " name means" : " names mean")
                    + " more than one thing across these sheets. A reference to such a name "
                    + "resolves to whichever one the builder happens to pick, so the sheet "
                    + "builds and the graph is wrong. The worst is " + describe(ambiguous.get(0)));
        }

        List<SheetAudit.Finding> shown = select(findings);
        if (shown.isEmpty()) {
            return result.summary(findings.isEmpty()
                    ? "Nothing to report in " + sheets.size() + " sheets."
                    : findings.size() + " findings, none of them in the category you asked for.")
                    .build();
        }

        result.columns("Where", "What", "Cell", "Change it to", "Why");
        int listed = 0;
        for (SheetAudit.Finding finding : shown) {
            if (listed++ >= MOST_SHOWN) {
                break;
            }
            result.row(finding.where(), readable(finding.getKind()),
                    shorten(finding.getCell()),
                    finding.isFixable() ? shorten(finding.getSuggestion()) : "",
                    finding.getMessage());
        }
        if (shown.size() > MOST_SHOWN) {
            result.warn("Showing the first " + MOST_SHOWN + " of " + shown.size()
                    + ". The rest are the same kinds of thing; narrow the report, or fix these "
                    + "and run it again.");
        }

        int fixable = 0;
        for (SheetAudit.Finding finding : findings) {
            if (finding.isFixable()) {
                fixable++;
            }
        }
        return result.summary(findings.size() + " findings across " + sheets.size()
                + " sheets, " + fixable + " of them with an obvious correction. Nothing was "
                + "changed.").build();
    }

    /** Every sheet in the folder, by name, skipping what cannot be read. */
    private Map<String, List<List<String>>> read(File directory, OperationResult.Builder result) {
        Map<String, List<List<String>>> sheets =
                new LinkedHashMap<String, List<List<String>>>();
        File[] files = directory.listFiles();
        if (files == null) {
            return sheets;
        }
        List<File> ordered = new ArrayList<File>(Arrays.asList(files));
        Collections.sort(ordered, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File file : ordered) {
            String name = file.getName().toLowerCase();
            if (!file.isFile() || !(name.endsWith(".tsv") || name.endsWith(".csv"))) {
                continue;
            }
            try {
                List<List<String>> rows = TemplateSheet.read(file);
                if (rows.size() >= 2) {
                    sheets.put(stem(file.getName()), rows);
                }
            } catch (RuntimeException cannotRead) {
                result.warn("Skipped " + file.getName() + ": " + cannotRead.getMessage());
            }
        }
        return sheets;
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private List<SheetAudit.Finding> select(List<SheetAudit.Finding> findings) {
        if (EVERYTHING.equals(kinds)) {
            return findings;
        }
        List<SheetAudit.Finding> kept = new ArrayList<SheetAudit.Finding>();
        for (SheetAudit.Finding finding : findings) {
            boolean wanted = FIXABLE.equals(kinds)
                    ? finding.isFixable()
                    : finding.getKind() == SheetAudit.Kind.UNRESOLVED_REFERENCE
                            || finding.getKind() == SheetAudit.Kind.AMBIGUOUS_NAME
                            || finding.getKind() == SheetAudit.Kind.QUOTED_REFERENCE;
            if (wanted) {
                kept.add(finding);
            }
        }
        return kept;
    }

    /** The enum name is for code. This is for somebody reading a table. */
    private static String readable(SheetAudit.Kind kind) {
        switch (kind) {
            case AMBIGUOUS_NAME:
                return "means two things";
            case DUPLICATE_ID:
                return "same identifier twice";
            case NEAR_DUPLICATE:
                return "almost the same name";
            case UNRESOLVED_REFERENCE:
                return "points at nothing";
            case WHITESPACE:
                return "stray whitespace";
            case TYPOGRAPHIC_CHARACTER:
                return "curly quote or long dash";
            case MISSING_ID:
                return "no identifier";
            case PLACEHOLDER:
                return "placeholder text";
            case QUOTED_REFERENCE:
                return "quoted name";
            case UNUSED_COLUMN:
                return "column is ignored";
            default:
                return kind.name();
        }
    }

    private static String shorten(String text) {
        String flat = text.replace('\n', ' ').replace('\t', ' ').trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "...";
    }

    private static String describe(LabelIndex.Ambiguity ambiguity) {
        return "\"" + ambiguity.getLabel() + "\", which names "
                + ambiguity.getCandidates().size() + " of them.";
    }
}
