package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.robot.TermRename;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * ROBOT &gt; Rename IRIs - move terms to new IRIs, or a whole namespace at once.
 *
 * <p>The job is migrating a namespace: an ontology drafted under {@code http://example.org/} that is
 * about to be published under {@code http://purl.obolibrary.org/obo/}. Protege renames one entity at
 * a time and cannot be told "everything under this prefix", so a few hundred terms is an afternoon
 * of clicking and a real chance of missing one.
 *
 * <p>Previewed before it is applied, and off by default. A bulk IRI change is the operation people
 * most want to look at before committing to, and the one they most want to undo - so the changes go
 * through Protege's model manager and Edit &gt; Undo reverses the whole rename in one step.
 */
public class RenameAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_MODE = "mode";
    private static final String OPTION_MAPPINGS = "mappings";
    private static final String OPTION_APPLY = "apply";

    /** Enough to see the shape of the change; a namespace move is thousands of axioms. */
    private static final int MAX_LISTED = 300;

    /**
     * The project's numbered terms no release has carried, named from their labels (1.155.0),
     * in a project that chose readable names; the mappings are worked out, not typed.
     */
    private static final String READABLE = "Readable names from labels";

    private volatile boolean readableNames;
    private volatile TermRename.Mode mode = TermRename.Mode.PREFIX;
    private volatile Map<String, String> mappings;
    private volatile boolean apply;

    @Override
    protected String operationName() {
        return "Rename IRIs";
    }

    @Override
    protected boolean runsInBackground() {
        return true;
    }

    @Override
    protected boolean configure() {
        List<String> labels = new ArrayList<String>();
        StringBuilder modeHelp = new StringBuilder();
        for (TermRename.Mode available : TermRename.Mode.values()) {
            labels.add(available.getLabel());
            modeHelp.append(available.getLabel()).append(" - ").append(available.getHelp())
                    .append("\n\n");
        }
        labels.add(READABLE);
        modeHelp.append(READABLE).append(" - in a project that names its new terms from what is "
                + "typed (OntoBoard > Project > ID ranges...), its numbered terms that no release "
                + "has carried get the name their label makes: 'boiled egg' a class BoiledEgg, "
                + "'has part' a property hasPart. A released term keeps its IRI. The mappings "
                + "are worked out, so the box below is not read.\n\n");

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Rename IRIs",
                "Changes term IRIs in bulk. Nothing is modified unless you turn on 'Apply the "
                        + "changes', and what is applied can be undone in one step.",
                Arrays.asList(
                        Parameter.of(OPTION_MODE, "Match", Parameter.Kind.CHOICE)
                                .choices(labels.toArray(new String[0]))
                                .defaultValue(TermRename.Mode.PREFIX.getLabel())
                                .required()
                                .help(modeHelp.toString().trim())
                                .build(),
                        Parameter.of(OPTION_MAPPINGS, "Mappings, one per line",
                                Parameter.Kind.MULTILINE)
                                .help("Write 'old -> new', or separate the two with a tab so a "
                                        + "column pair pastes straight out of a spreadsheet. "
                                        + "Blank lines and lines starting with # are ignored.\n\n"
                                        + "For example:\n"
                                        + "  http://example.org/pizza# -> "
                                        + "http://purl.obolibrary.org/obo/PIZZA_\n\n"
                                        + "A mapping that matches nothing is reported rather than "
                                        + "ignored - it is nearly always a typo in the IRI.")
                                .build(),
                        Parameter.of(OPTION_APPLY, "Apply the changes", Parameter.Kind.FLAG)
                                .defaultValue("false")
                                .help("Off by default. With it off you get the full list of what "
                                        + "would change and nothing is touched, which is how to "
                                        + "check a mapping list before trusting it.")
                                .build()));
        if (chosen == null) {
            return false;
        }

        for (TermRename.Mode available : TermRename.Mode.values()) {
            if (available.getLabel().equals(chosen.get(OPTION_MODE))) {
                mode = available;
            }
        }
        apply = "true".equalsIgnoreCase(chosen.get(OPTION_APPLY));
        readableNames = READABLE.equals(chosen.get(OPTION_MODE));
        if (readableNames) {
            mode = TermRename.Mode.FULL_IRI;
            mappings = new java.util.LinkedHashMap<String, String>();
            return true;
        }
        String typed = chosen.get(OPTION_MAPPINGS);
        if (typed == null || typed.trim().isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(), "Write at least one "
                    + "mapping, 'old -> new'.", "Rename IRIs",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        try {
            mappings = TermRename.parse(chosen.get(OPTION_MAPPINGS));
        } catch (de.fizkarlsruhe.ise.ontoboard.robot.RobotException badList) {
            // Reported here rather than carried into the background run: the user is still looking
            // at the box they typed it into.
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(), badList.getMessage(),
                    "Rename IRIs", javax.swing.JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Change", "Axiom");
        if (readableNames) {
            String refused = readableMappings(ontology, result);
            if (refused != null) {
                return result.failed(refused).build();
            }
            if (mappings.isEmpty()) {
                return result.summary("No numbered term to name: every one has been released, "
                        + "or is left for the reason above.").build();
            }
        }
        TermRename.Plan plan = TermRename.plan(ontology, mode, mappings);

        result.note("Match: " + (readableNames ? READABLE : mode.getLabel()));
        result.note("Mappings: " + mappings.size());

        for (String unmatched : plan.getUnmatched()) {
            result.warn("Nothing matched '" + unmatched + "', so that line renamed nothing. Check "
                    + "it for a typo - the IRI has to be exactly as it appears in the ontology.");
        }

        if (plan.isEmpty()) {
            return result.summary("Nothing to rename. None of the "
                    + mappings.size() + " mappings matched anything in this ontology.").build();
        }

        int listed = 0;
        for (org.semanticweb.owlapi.model.OWLOntologyChange change : plan.getChanges()) {
            if (listed >= MAX_LISTED) {
                result.note("Listing the first " + MAX_LISTED + " of "
                        + plan.getChanges().size() + " changes.");
                break;
            }
            result.row(change.isAddAxiom() ? "add" : "remove",
                    String.valueOf(change.getAxiom()));
            listed++;
        }

        String counts = plan.getEntitiesAffected() + " terms renamed across "
                + plan.getChanges().size() + " axiom changes";

        if (!apply) {
            result.note("Nothing was changed - 'Apply the changes' was off.");
            return result.summary("Would rename " + counts + ".").build();
        }
        if (BackgroundRun.abandoned()) {
            // ROBOT cannot be interrupted, so the work finished after the user walked away.
            // Renaming their terms now would change the ontology minutes after they cancelled.
            result.note("Nothing was changed - you stopped waiting before it finished.");
            return result.summary("Would have renamed " + counts + ", but was abandoned.").build();
        }
        try {
            applyOnEventThread(plan.getChanges());
        } catch (RuntimeException failure) {
            return result.failed("The rename was computed but could not be applied: "
                    + failure.getMessage()).build();
        }
        result.note("Edit > Undo reverses the whole rename in one step.");
        return result.summary("Renamed " + counts + ".").build();
    }

    /**
     * The renames for Readable names from labels, into {@link #mappings}, with what is left and
     * why said in the result; the reason nothing can be named, or null.
     */
    private String readableMappings(OWLOntology ontology, OperationResult.Builder result) {
        java.io.File editFile = fileOf(ontology);
        String namespace = de.fizkarlsruhe.ise.ontoboard.odk.TermMinter.readableNamespaceOf(
                editFile);
        if (namespace == null) {
            return "This project numbers its new terms. Choose readable names first - OntoBoard "
                    + "> Project > ID ranges..., Name new terms from what is typed - which also "
                    + "tells ODK's release where the readable terms are.";
        }
        java.io.File rangesFile = de.fizkarlsruhe.ise.ontoboard.odk.TermMinter.findRangesFile(
                editFile);
        de.fizkarlsruhe.ise.ontoboard.odk.IdRanges ranges = null;
        if (rangesFile != null) {
            try {
                ranges = de.fizkarlsruhe.ise.ontoboard.odk.IdRanges.parse(new String(
                        java.nio.file.Files.readAllBytes(rangesFile.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException | RuntimeException unreadable) {
                ranges = null;
            }
        }
        if (ranges == null || ranges.getIdPrefix().isEmpty()) {
            return "This project has no ID ranges to read its numbering from, so there are no "
                    + "numbered terms to name.";
        }
        // A release whose file is on GitHub only may have carried any of these terms: renamed,
        // its published IRIs would stop meaning anything (the 1.155.0 review's I2).
        java.util.List<String> unread =
                de.fizkarlsruhe.ise.ontoboard.odk.TermMinter.releasesNotRead(rangesFile);
        if (!unread.isEmpty()) {
            String notHere = (unread.size() == 1 ? "The release " : "The releases ")
                    + String.join(", ", unread) + (unread.size() == 1 ? " keeps its file"
                            : " keep their files") + " on GitHub only, so which terms "
                    + (unread.size() == 1 ? "it" : "they") + " carried is not known here. Fetch "
                    + (unread.size() == 1 ? "it" : "them") + " first - "
                    + "OntoBoard > Build and release > History..., "
                    + "Fetch the whole history from GitHub... - and name the terms then.";
            if (apply) {
                return "Nothing was renamed. " + notHere;
            }
            result.warn(notHere);
        }
        de.fizkarlsruhe.ise.ontoboard.odk.ReadableNames.Plan names =
                de.fizkarlsruhe.ise.ontoboard.odk.ReadableNames.plan(ontology,
                        ranges.getIdPrefix(), namespace,
                        de.fizkarlsruhe.ise.ontoboard.odk.TermMinter.releasedIdentifiers(
                                rangesFile, ranges));
        mappings = new java.util.LinkedHashMap<String, String>(names.mappings());
        for (java.util.Map.Entry<String, String> rename : mappings.entrySet()) {
            result.note(rename.getKey() + " -> " + rename.getValue());
        }
        for (String left : names.left()) {
            result.note("Left as it is: " + left);
        }
        result.note("Files outside the ontology that name these terms - sheets, term lists, "
                + "notes - are not changed.");
        return null;
    }
}
