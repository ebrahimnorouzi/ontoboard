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
                                .required()
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
        TermRename.Plan plan = TermRename.plan(ontology, mode, mappings);

        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Change", "Axiom");
        result.note("Match: " + mode.getLabel());
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
}
