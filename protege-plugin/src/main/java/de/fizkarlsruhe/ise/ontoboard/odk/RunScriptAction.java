package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.menu.BackgroundRun;
import de.fizkarlsruhe.ise.ontoboard.menu.OperationResult;
import de.fizkarlsruhe.ise.ontoboard.menu.ResultDialog;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;

/**
 * OntoBoard &gt; Project &gt; Run a project script... - the scripts in {@code src/scripts}.
 *
 * <p>Asked for directly: "it should be possible that users have scripts and it should be run
 * everything inside the protege". Nothing in the plugin listed or ran them, and they are not
 * peripheral - a real ODK Makefile sets {@code SHELL = $(SCRIPTSDIR)/run-command.sh}, so every
 * recipe line of the build already goes through one.
 *
 * <p><b>The command is shown before it runs.</b> This is the only place in the plugin that runs
 * code somebody else wrote and OntoBoard has not inspected, so the consent is explicit: the
 * route, the exact argv and any warning are on screen, and nothing starts until the user agrees
 * to that particular command. The plugin already shows the command for a build; here it is not
 * a courtesy.
 */
public class RunScriptAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        File ontologyDirectory;
        try {
            ontologyDirectory = ontologyDirectory();
        } catch (RuntimeException notAProject) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), notAProject.getMessage(),
                    "No project", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        List<ProjectScripts.Script> scripts = ProjectScripts.in(ontologyDirectory);
        if (scripts.isEmpty()) {
            File looked = ProjectScripts.directoryFor(ontologyDirectory);
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "This project has no scripts.\n\nOntoBoard looked in "
                            + (looked == null ? "src/scripts" : looked.getAbsolutePath())
                            + ".\nAn ODK project keeps them there; a project OntoBoard "
                            + "scaffolded has none, because an empty directory advertises a "
                            + "capability that is not there.",
                    "No scripts", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        ProjectScripts.Script chosen = ask(scripts);
        if (chosen == null) {
            return;
        }

        String runtime = MakeRun.containerRuntime(ProcessRunner.real());
        // --version, the same probe Toolchain uses for make and sh. An interpreter that answers
        // it is on PATH; one that does not cannot run anything here, whatever the reason.
        boolean onPath = chosen.getInterpreter() != null
                && ProcessRunner.isAvailable(ProcessRunner.real(), chosen.getInterpreter(),
                        "--version");
        final ScriptRun.Plan plan = ScriptRun.planFor(chosen, ontologyDirectory, runtime,
                Toolchain.activationScript(Toolchain.nativeEnvironment()), onPath);

        if (plan.getRoute() == ScriptRun.Route.NOT_RUNNABLE) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), plan.getAdvice(),
                    "Cannot run " + chosen.getName(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!confirmed(chosen, plan)) {
            return;
        }

        final File workingDirectory = ontologyDirectory;
        BackgroundRun.execute(getOWLWorkspace(), "Run " + chosen.getName(),
                new java.util.concurrent.Callable<OperationResult>() {
                    @Override
                    public OperationResult call() {
                        return runIt(plan, workingDirectory);
                    }
                },
                new java.util.function.Consumer<OperationResult>() {
                    @Override
                    public void accept(OperationResult result) {
                        ResultDialog.show(getOWLWorkspace(), result);
                    }
                });
    }

    /** The chooser, listing each script with what would run it. */
    private ProjectScripts.Script ask(List<ProjectScripts.Script> scripts) {
        String[] labels = new String[scripts.size()];
        for (int at = 0; at < scripts.size(); at++) {
            ProjectScripts.Script script = scripts.get(at);
            labels[at] = script.getName()
                    + (script.isRunnable() ? "   (" + script.getInterpreter() + ")"
                            : "   - OntoBoard cannot tell what runs this");
        }
        JComboBox<String> choices = new JComboBox<String>(labels);
        if (JOptionPane.showConfirmDialog(getOWLWorkspace(), choices, "Run a project script",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return null;
        }
        return scripts.get(Math.max(0, choices.getSelectedIndex()));
    }

    /** Shows the exact command, and any warning, and waits for a yes. */
    private boolean confirmed(ProjectScripts.Script script, ScriptRun.Plan plan) {
        StringBuilder message = new StringBuilder("<html><b>")
                .append(script.getName()).append("</b> will run ")
                .append(describe(plan.getRoute())).append(".<br><br>")
                .append("<font face=\"monospaced\">")
                .append(plan.asCommandLine().replace("&", "&amp;").replace("<", "&lt;"))
                .append("</font>");
        String warning = ScriptRun.warningFor(script, plan.getRoute());
        if (warning != null) {
            message.append("<br><br><b>Before you do:</b> ").append(warning);
        }
        message.append("<br><br>OntoBoard has not inspected this script. It runs with your "
                + "permissions and can change the project.</html>");
        return JOptionPane.showConfirmDialog(getOWLWorkspace(), message.toString(),
                "Run " + script.getName() + "?", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private static String describe(ScriptRun.Route route) {
        switch (route) {
            case IN_CONTAINER:
                return "inside the project's ODK container";
            case NATIVE:
                return "in the native ODK environment";
            case HOST:
            default:
                return "directly on this machine";
        }
    }

    /** Runs it, and reports what it said. Off the event thread. */
    static OperationResult runIt(ScriptRun.Plan plan, File workingDirectory) {
        OperationResult.Builder result = OperationResult.of("Run a project script");
        result.note("Route: " + describe(plan.getRoute()));
        result.note("Command: " + plan.asCommandLine());
        try {
            ProcessRunner.Outcome outcome = ProcessRunner.real().run(workingDirectory,
                    plan.getCommand(), ScriptRun.TIMEOUT_MINUTES, null);
            for (String line : outcome.getOutput()) {
                result.note(line);
            }
            if (outcome.timedOut()) {
                return result.failed("The script did not finish within "
                        + ScriptRun.TIMEOUT_MINUTES + " minutes and was stopped.").build();
            }
            if (!outcome.isSuccess()) {
                return result.failed("Exit code " + outcome.getExitCode() + ". "
                        + outcome.lastMeaningfulLine()).build();
            }
            return result.summary("Finished with exit code 0.").build();
        } catch (java.io.IOException cannotRun) {
            return result.failed("Could not start it: " + cannotRun.getMessage()).build();
        }
    }

    /** The directory the open ontology is saved in, which is the project's src/ontology. */
    private File ontologyDirectory() {
        org.semanticweb.owlapi.model.OWLOntology ontology =
                getOWLModelManager().getActiveOntology();
        if (ontology == null) {
            throw new IllegalStateException("No ontology is open.");
        }
        org.semanticweb.owlapi.model.IRI document = getOWLModelManager()
                .getOWLOntologyManager().getOntologyDocumentIRI(ontology);
        java.net.URI uri = document == null ? null : document.toURI();
        if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("This ontology is not saved to a file, so OntoBoard "
                    + "cannot find a project around it.");
        }
        File parent = new File(uri).getAbsoluteFile().getParentFile();
        if (parent == null) {
            throw new IllegalStateException("This ontology has no directory.");
        }
        return parent;
    }
}
