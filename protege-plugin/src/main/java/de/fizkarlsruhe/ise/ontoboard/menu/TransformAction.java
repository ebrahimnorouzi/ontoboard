package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * ROBOT &gt; Transform - relax, reduce, repair and merge, run so that Protege can undo them.
 *
 * <p>These four ROBOT operations rewrite an ontology in place. Calling them on Protege's live
 * ontology would work, in the sense that the axioms would change, and would then leave the user
 * with a class hierarchy showing the old axioms, no change events anywhere, and <b>no undo</b> - a
 * merge on a real OBO ontology adds tens of thousands of axioms, and "reload and lose everything
 * else you did" is not an acceptable way back. So the work happens on a throwaway copy and the
 * difference is applied through {@code OWLModelManager}, which gives correct events and one undo
 * step that reverses the whole thing.
 *
 * <p>The result lists every change, so a user can see what happened rather than being told a
 * number. That matters most for reduce, the one operation here that deletes axioms.
 */
public class TransformAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    /**
     * How many changes to list.
     *
     * <p>Merging the imports of a real OBO ontology produces tens of thousands of changes, and a
     * table that large is slow to build and useless to read. The result says when it has been cut
     * short rather than quietly showing a prefix.
     */
    private static final int MAX_LISTED = 1000;

    private static final String OPTION_OPERATION = "operation";
    private static final String OPTION_REASONER = "reasoner";
    private static final String OPTION_APPLY = "apply";

    private volatile RobotTransform.Kind kind = RobotTransform.Kind.RELAX;
    private volatile Reasoners.Choice reasoner = Reasoners.DEFAULT;
    private volatile boolean apply = true;

    @Override
    protected String operationName() {
        return "Transform";
    }

    @Override
    protected boolean configure() {
        List<String> operations = new ArrayList<String>();
        StringBuilder operationHelp = new StringBuilder(
                "Which ROBOT operation to run. Each rewrites the ontology, and each is undoable "
                        + "as a single step.\n");
        for (RobotTransform.Kind available : RobotTransform.Kind.values()) {
            operations.add(available.getLabel());
            operationHelp.append('\n').append(available.getLabel()).append(": ")
                    .append(available.getHelp()).append('\n');
        }

        List<Parameter> parameters = Arrays.asList(
                Parameter.of(OPTION_OPERATION, "Operation", Parameter.Kind.CHOICE)
                        .choices(operations.toArray(new String[0]))
                        .defaultValue(RobotTransform.Kind.RELAX.getLabel())
                        .required()
                        .help(operationHelp.toString())
                        .build(),
                Parameter.of(OPTION_REASONER, "Reasoner", Parameter.Kind.CHOICE)
                        .choices(Reasoners.labels().toArray(new String[0]))
                        .defaultValue(Reasoners.DEFAULT.getLabel())
                        .help(Reasoners.help()
                                + "\nOnly Reduce uses this; the other operations ignore it. The "
                                + "default is ELK because that is what an ODK build uses, so the "
                                + "result here matches what 'make' would produce.")
                        .build(),
                Parameter.of(OPTION_APPLY, "Apply the changes", Parameter.Kind.FLAG)
                        .defaultValue("true")
                        .help("With this on, the ontology is changed and the result lists what "
                                + "changed; Protege's Edit > Undo reverses the whole operation in "
                                + "one step. Turn it off to see exactly which axioms would be "
                                + "added and removed without touching anything - worth doing "
                                + "first for Reduce, which is the only operation here that "
                                + "deletes axioms.")
                        .build());

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Transform",
                "Runs a ROBOT operation on the ontology you have open. The work happens on a copy "
                        + "and the difference is applied through Protege, so everything here is "
                        + "undoable.",
                parameters);
        if (chosen == null) {
            return false;
        }
        kind = kindByLabel(chosen.get(OPTION_OPERATION));
        reasoner = Reasoners.byLabel(chosen.get(OPTION_REASONER));
        apply = "true".equalsIgnoreCase(chosen.get(OPTION_APPLY));
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OWLReasonerFactory reasonerFactory =
                kind.needsReasoner() ? reasoner.newFactory() : null;
        RobotTransform.Diff diff = RobotTransform.preview(ontology, kind, reasonerFactory);

        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Change", "Axiom");
        // Recorded so a saved result says what produced it; a list of changes with no record of
        // the operation and reasoner behind it cannot be compared with another run.
        result.note("Operation: " + kind.getLabel());
        if (kind.needsReasoner()) {
            result.note("Reasoner: " + reasoner.getLabel());
        }

        if (diff.isEmpty()) {
            return result.summary("Nothing to change. " + kind.getLabel()
                    + " found no axioms to rewrite in this ontology.").build();
        }

        for (String[] row : render(diff.getChanges())) {
            result.row(row);
        }
        if (diff.getChanges().size() > MAX_LISTED) {
            result.note("Listing the first " + MAX_LISTED + " of "
                    + diff.getChanges().size() + " changes.");
        }

        String counts = diff.getAdded() + " axioms added, " + diff.getRemoved() + " removed"
                + (diff.getImportsDropped() == 0 ? ""
                        : ", " + diff.getImportsDropped() + " import statements dropped");

        if (!apply) {
            result.note("Nothing was changed - 'Apply the changes' was off.");
            return result.summary(kind.getLabel() + " would make " + counts + ".").build();
        }

        if (BackgroundRun.abandoned()) {
            // ROBOT cannot be interrupted, so the work finished after the user walked away.
            // Applying it now would change their ontology minutes after they cancelled, over
            // whatever they went on to do instead.
            result.note("Nothing was changed - you stopped waiting before it finished.");
            return result.summary(kind.getLabel() + " would have made " + counts
                    + ", but was abandoned.").build();
        }
        try {
            applyOnEventThread(diff.getChanges());
        } catch (RuntimeException failure) {
            return result.failed("The changes were computed but could not be applied: "
                    + failure.getMessage()).build();
        }
        if (diff.getImportsDropped() > 0) {
            // Worth saying out loud: the edit file is now self-contained, which is a release
            // artefact rather than something to commit over the file the project is maintained in.
            result.warn("This ontology no longer imports anything. That is what a release build "
                    + "produces - save it under a new name rather than over your edit file.");
        }
        if (diff.getRemoved() > 0) {
            result.note("Edit > Undo reverses all of this in one step.");
        }
        return result.summary(kind.getLabel() + " applied: " + counts + ".").build();
    }

    /**
     * The changes as table rows, rendered the way the rest of Protege renders terms.
     *
     * <p>Through {@code getRendering} so that a user who has Protege set to show labels sees
     * labels here too; a list of numeric OBO IRIs would be unreadable for exactly the ontologies
     * this is most useful on. Done on the dispatch thread because the renderer is Protege's and
     * is not documented as safe to call from anywhere else.
     */
    private List<String[]> render(List<OWLOntologyChange> changes) {
        final List<OWLOntologyChange> listed =
                changes.size() > MAX_LISTED ? changes.subList(0, MAX_LISTED) : changes;
        final List<String[]> rows = new ArrayList<String[]>(listed.size());
        Runnable rendering = new Runnable() {
            @Override
            public void run() {
                for (OWLOntologyChange change : listed) {
                    rows.add(new String[] {describe(change), text(change)});
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            rendering.run();
            return rows;
        }
        try {
            SwingUtilities.invokeAndWait(rendering);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException thrown) {
            // Rendering is presentation only. A failure here must not lose the changes
            // themselves, so fall back to the OWL API's own rendering rather than giving up.
            rows.clear();
            for (OWLOntologyChange change : listed) {
                rows.add(new String[] {describe(change), axiomText(change)});
            }
        }
        return rows;
    }

    /** "Added", "Removed" or "Import removed" - what kind of change this is. */
    private static String describe(OWLOntologyChange change) {
        if (change instanceof RemoveImport) {
            return "Import removed";
        }
        return change instanceof AddAxiom ? "Added" : "Removed";
    }

    /** One change as text, using Protege's renderer. Must be called on the dispatch thread. */
    private String text(OWLOntologyChange change) {
        if (change instanceof RemoveImport) {
            return ((RemoveImport) change).getImportDeclaration().getIRI().toString();
        }
        return getOWLModelManager().getRendering(change.getAxiom());
    }

    /**
     * One change as plain text, without Protege.
     *
     * <p>{@code OWLOntologyChange.getAxiom()} <em>throws</em> on an import change - OWL API's
     * {@code ImportChange} unconditionally raises {@code UnsupportedOperationException} - and
     * Merge imports puts {@code RemoveImport} into every result it produces. The primary renderer
     * above knows that; the fallback did not, so the path taken when rendering fails threw a
     * second exception of its own, and the user lost the list of changes that had just been
     * applied to their ontology. The same shape as the profile-violation crash: a method declared
     * to return something that throws when there is nothing to return.
     */
    private static String axiomText(OWLOntologyChange change) {
        if (change instanceof RemoveImport) {
            return ((RemoveImport) change).getImportDeclaration().getIRI().toString();
        }
        return change.isAxiomChange() ? String.valueOf(change.getAxiom()) : String.valueOf(change);
    }

    /** The operation with this label. */
    private static RobotTransform.Kind kindByLabel(String label) {
        for (RobotTransform.Kind available : RobotTransform.Kind.values()) {
            if (available.getLabel().equalsIgnoreCase(label == null ? "" : label.trim())) {
                return available;
            }
        }
        throw new IllegalArgumentException("no ROBOT operation called '" + label + "'");
    }
}
