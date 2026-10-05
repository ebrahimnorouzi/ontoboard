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

        Choice choice = ask(scripts);
        if (choice == null) {
            return;
        }
        ProjectScripts.Script chosen = choice.script;

        String runtime = MakeRun.containerRuntime(ProcessRunner.real());
        // --version, the same probe Toolchain uses for make and sh. An interpreter that answers
        // it is on PATH; one that does not cannot run anything here, whatever the reason.
        boolean onPath = chosen.getInterpreter() != null
                && ProcessRunner.isAvailable(ProcessRunner.real(), chosen.getInterpreter(),
                        "--version");
        final ScriptRun.Plan plan = ScriptRun.planFor(chosen, ontologyDirectory, runtime,
                Toolchain.activationScript(Toolchain.nativeEnvironment()), onPath,
                choice.arguments);

        if (plan.getRoute() == ScriptRun.Route.NOT_RUNNABLE) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), plan.getAdvice(),
                    "Cannot run " + chosen.getName(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!confirmed(chosen, plan, choice.arguments)) {
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

    /** What the chooser produced: a script and the arguments typed for it. */
    private static final class Choice {
        private final ProjectScripts.Script script;
        private final List<String> arguments;

        Choice(ProjectScripts.Script script, List<String> arguments) {
            this.script = script;
            this.arguments = arguments;
        }
    }

    /**
     * The chooser: which script, and what to pass it.
     *
     * <p>The argument line is parsed the way a shell splits words, and a line that cannot be
     * split - an unclosed quote - re-opens the dialog rather than being guessed at. The command
     * shown in the next step is the basis on which it is approved, so it has to be the command
     * the user actually wrote.
     */
    private Choice ask(List<ProjectScripts.Script> scripts) {
        String[] labels = new String[scripts.size()];
        for (int at = 0; at < scripts.size(); at++) {
            ProjectScripts.Script script = scripts.get(at);
            labels[at] = script.getName()
                    + (script.isRunnable() ? "   (" + script.getInterpreter() + ")"
                            : "   - OntoBoard cannot tell what runs this");
        }
        JComboBox<String> choices = new JComboBox<String>(labels);
        javax.swing.JTextField arguments = new javax.swing.JTextField(28);
        arguments.setToolTipText("Passed to the script as arguments. Quote anything with a "
                + "space in it.");

        javax.swing.JPanel form = new javax.swing.JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        form.add(new javax.swing.JLabel("Script"));
        form.add(choices);
        form.add(new javax.swing.JLabel("Arguments (optional)"));
        form.add(arguments);

        while (true) {
            if (JOptionPane.showConfirmDialog(getOWLWorkspace(), form, "Run a project script",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                    != JOptionPane.OK_OPTION) {
                return null;
            }
            try {
                return new Choice(scripts.get(Math.max(0, choices.getSelectedIndex())),
                        ScriptArguments.parse(arguments.getText()));
            } catch (ScriptArguments.Malformed cannotSplit) {
                JOptionPane.showMessageDialog(getOWLWorkspace(), cannotSplit.getMessage(),
                        "Check the arguments", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    /** Shows the exact command, and any warning, and waits for a yes. */
    private boolean confirmed(ProjectScripts.Script script, ScriptRun.Plan plan,
            List<String> arguments) {
        StringBuilder message = new StringBuilder("<html><b>")
                .append(script.getName()).append("</b> will run ")
                .append(describe(plan.getRoute())).append(".<br><br>")
                .append("<font face=\"monospaced\">")
                .append(plan.asCommandLine().replace("&", "&amp;").replace("<", "&lt;"))
                .append("</font>");
        if (!arguments.isEmpty()) {
            // Spelled out, because quoting is where a typed argument line goes wrong and the
            // count is the fact that says whether it did.
            message.append("<br><br>").append(escape(ScriptArguments.describe(arguments)));
        }
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

    /** For putting data into the HTML of a dialog. */
    private static String escape(String text) {
        return text == null ? ""
                : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
            if (outcome.wasCancelled()) {
                return result.failed("You stopped it. The script may have changed the project "
                        + "before it was killed.").build();
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
