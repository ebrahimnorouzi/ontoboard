package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.MakeRun;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Project &gt; Build - run one of the project's own make targets, and keep the transcript.
 *
 * <p>{@code MakeTargets} could already read a Makefile and order its targets sensibly, tested
 * against a real 767-line ODK one, and nothing could run any of them. Wanting what a target does
 * meant leaving Protege for a terminal, which is most of the reason this plugin exists.
 *
 * <p>The transcript is the product, not a side effect. A build fails in the middle of several
 * hundred lines of ROBOT output and the useful part is usually well above the end, so a result
 * saying only "make failed (exit 2)" would send somebody to the terminal anyway. It is savable
 * from the result dialog, which is what makes it something to attach to an issue.
 */
public class BuildAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_TARGET = "target";

    /**
     * How much of the transcript to keep.
     *
     * <p>A full ODK release prints tens of thousands of lines, nearly all of it progress. Keeping
     * every one makes a dialog that takes seconds to open and cannot be scrolled usefully; the
     * result says when it has been cut rather than quietly showing a tail.
     */
    private static final int MAX_LINES = 4000;

    private volatile String target = "";

    @Override
    protected String operationName() {
        return "Build";
    }

    @Override
    protected boolean configure() {
        File editFile = fileOf(getOWLModelManager().getActiveOntology());
        String why = MakeRun.whyNotRunnable(editFile, ProcessRunner.real());
        if (why != null) {
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(), why,
                    "Cannot build", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        List<String> targets = MakeRun.targetsFor(editFile);
        if (targets.isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "The Makefile in " + MakeRun.workingDirectory(editFile).getAbsolutePath()
                            + " declares no targets this can run.",
                    "Nothing to build", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }

        List<Parameter> parameters = Arrays.asList(
                Parameter.of(OPTION_TARGET, "Target", Parameter.Kind.CHOICE)
                        .choices(targets.toArray(new String[0]))
                        .defaultValue(targets.get(0))
                        .required()
                        .help("Read from this project's own Makefile, most useful first. "
                                + "'all' and 'reason' regenerate the published file, 'report' "
                                + "runs the quality checks, 'prepare_release' produces a dated "
                                + "release. The build runs with make and robot on your PATH - "
                                + "this does not use Docker - and it writes files that Protege "
                                + "will not notice changing, so reopen the ontology afterwards "
                                + "if the target rewrote it.")
                        .build());

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Build",
                "Runs one of this project's make targets and keeps the whole transcript, which "
                        + "you can save from the result.",
                parameters);
        if (chosen == null) {
            return false;
        }
        target = chosen.get(OPTION_TARGET);
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        File editFile = fileOf(ontology);
        File directory = MakeRun.workingDirectory(editFile);
        OperationResult.Builder result = OperationResult.of(operationName());
        result.note("Target: " + target);
        result.note("Directory: " + directory.getAbsolutePath());

        ProcessRunner.Outcome outcome;
        long started = System.currentTimeMillis();
        try {
            outcome = ProcessRunner.real().run(directory, MakeRun.command(target),
                    MakeRun.TIMEOUT_MINUTES, null);
        } catch (IOException cannotRun) {
            return result.failed("Could not start make: " + cannotRun.getMessage()).build();
        }
        long seconds = (System.currentTimeMillis() - started) / 1000;

        int shown = 0;
        for (String line : outcome.getOutput()) {
            if (shown++ >= MAX_LINES) {
                break;
            }
            result.note(line);
        }
        if (outcome.getOutput().size() > MAX_LINES) {
            result.warn("Transcript cut after " + MAX_LINES + " of "
                    + outcome.getOutput().size() + " lines.");
        }
        result.note("Took " + seconds + "s, " + outcome.getOutput().size() + " lines of output.");

        String failure = MakeRun.explain(target, outcome);
        if (failure != null) {
            // The transcript is above; this is the sentence that says where to look in it.
            return result.failed(failure).build();
        }

        String reload = MakeRun.reloadHint(target, editFile);
        if (reload != null) {
            result.warn(reload);
        }
        return result.summary("make " + target + " succeeded in " + seconds + "s.").build();
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
}
