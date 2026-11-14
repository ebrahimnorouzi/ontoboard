package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.awt.event.ActionEvent;
import java.io.File;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;

/**
 * OntoBoard &gt; Check requirements... - what this machine can do, and what to install.
 *
 * <p>Asked for directly: "there should be one option to check the requirements and ticked if it's
 * working properly and ask users to install or do/get required information to run everything
 * smoothly". Before this, every answer about a missing tool arrived after the user had already
 * tried to do something, one tool at a time, and only about whichever was checked first.
 *
 * <p>Probing launches several processes and took about 2.5 seconds when measured, so it runs off
 * the event thread. A requirements screen that freezes the application while it decides whether
 * the application works would be an unfortunate first impression.
 */
public class RequirementsAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        de.fizkarlsruhe.ise.ontoboard.menu.BackgroundRun.execute(getOWLWorkspace(),
                "Check requirements",
                new java.util.concurrent.Callable<
                        de.fizkarlsruhe.ise.ontoboard.menu.OperationResult>() {
                    @Override
                    public de.fizkarlsruhe.ise.ontoboard.menu.OperationResult call() {
                        // The assistant's model is checked over the network, so it is added
                        // here rather than in probe(), which runs programs (1.136.0).
                        probed = Toolchain.probe(ProcessRunner.real()).plus(
                                de.fizkarlsruhe.ise.ontoboard.assistant.host.AssistantRequirement
                                        .probe());
                        return null;
                    }
                },
                new java.util.function.Consumer<
                        de.fizkarlsruhe.ise.ontoboard.menu.OperationResult>() {
                    @Override
                    public void accept(de.fizkarlsruhe.ise.ontoboard.menu.OperationResult ignored) {
                        show();
                    }
                });
    }

    /** Filled on the worker thread, read on the event thread after it finishes. */
    private volatile Toolchain.Report probed;

    private void show() {
        Toolchain.Report report = probed;
        if (report == null) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "The requirements check did not finish.", "Check requirements",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        JButton choose = new JButton("Set native ODK environment...");
        choose.setToolTipText("The directory 'odk install' created. Linux and macOS only.");
        choose.addActionListener(a -> chooseNativeEnvironment());

        JButton container = new JButton("Container settings...");
        container.setToolTipText("Whether ODK's targets run in a container, and which program "
                + "runs it.");
        container.addActionListener(a -> chooseContainerSettings());

        JButton copy = new JButton("Copy as text");
        copy.setToolTipText("For pasting into an issue report.");
        copy.addActionListener(a -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(Toolchain.asText(report)), null);
        });

        // Enabled only where a container program answers and ODK's image is not here; it asks
        // before anything is fetched (OdkImagePull decides and words, this only shows).
        final OdkImagePull image = report.getImage();
        final JButton pull = new JButton(OdkImagePull.BUTTON);
        pull.setEnabled(image != null && image.offersThePull());
        pull.setToolTipText(image == null ? null : image.buttonHelp());
        pull.addActionListener(a -> askThenPull(image, pull));

        JOptionPane.showOptionDialog(getOWLWorkspace(), new RequirementsPanel(report),
                "OntoBoard requirements", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null,
                new Object[] {container, choose, pull, copy, "Close"}, "Close");
    }

    /**
     * Asks whether to pull ODK's image - the command and the size shown - and on a yes runs
     * that one command in the background, where the progress dialog's Stop ends it. The
     * outcome is shown, then Check requirements runs again, so the image's row says what is
     * there now. Cancel is the answer Enter gives: a download of this size is never started by
     * a key pressed out of habit.
     */
    private void askThenPull(final OdkImagePull image, JButton pull) {
        if (image == null || !image.offersThePull()) {
            return;
        }
        java.awt.Window requirements = javax.swing.SwingUtilities.getWindowAncestor(pull);
        Object[] answers = {"Pull", "Cancel"};
        int answer = JOptionPane.showOptionDialog(
                requirements == null ? getOWLWorkspace() : requirements, image.question(),
                OdkImagePull.OPERATION, JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, answers, answers[1]);
        if (answer != 0) {
            return;
        }
        if (requirements != null) {
            // Shown again, as it is then, when the pull is over.
            requirements.dispose();
        }
        de.fizkarlsruhe.ise.ontoboard.menu.BackgroundRun.execute(getOWLWorkspace(),
                OdkImagePull.OPERATION,
                new java.util.concurrent.Callable<
                        de.fizkarlsruhe.ise.ontoboard.menu.OperationResult>() {
                    @Override
                    public de.fizkarlsruhe.ise.ontoboard.menu.OperationResult call() {
                        return image.pull(ProcessRunner.real());
                    }
                },
                new java.util.function.Consumer<
                        de.fizkarlsruhe.ise.ontoboard.menu.OperationResult>() {
                    @Override
                    public void accept(de.fizkarlsruhe.ise.ontoboard.menu.OperationResult done) {
                        de.fizkarlsruhe.ise.ontoboard.menu.ResultDialog.show(getOWLWorkspace(),
                                done);
                        actionPerformed(null);
                    }
                });
    }

    /**
     * The two things a person can settle about ODK's container ({@link OdkRoute}): whether one
     * is used, and which program runs it. Asked again until what is chosen can be kept - a
     * program that is named has to be a file - or the person gives up.
     */
    private void chooseContainerSettings() {
        OdkRoutePanel panel = new OdkRoutePanel(OdkRoute.use(), OdkRoute.containerProgram(), null);
        while (true) {
            int answer = JOptionPane.showConfirmDialog(getOWLWorkspace(), panel,
                    "Container for ODK's targets", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
            String why = panel.whyNot();
            if (why == null) {
                break;
            }
            JOptionPane.showMessageDialog(getOWLWorkspace(), why, "Container for ODK's targets",
                    JOptionPane.WARNING_MESSAGE);
        }
        OdkRoute.setUse(panel.use());
        OdkRoute.setContainerProgram(panel.program());
        JOptionPane.showMessageDialog(getOWLWorkspace(),
                "Kept: " + panel.use().label() + (panel.program() == null ? ""
                        : ", with " + panel.program()) + ".\n\nRe-run Check requirements to "
                        + "see what answers now.",
                "Container for ODK's targets", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Points OntoBoard at a native ODK environment.
     *
     * <p>{@code odk install /path/to/env} initialises a directory and leaves a
     * {@code bin/activate-odk-environment.sh} in it. There is no registry of such directories and
     * no conventional location, so this is a question rather than a search - and the answer is
     * checked before it is kept, because a path that turns out not to be one would otherwise fail
     * later, during a build, as something that looks like a build failure.
     */
    private void chooseNativeEnvironment() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select the native ODK environment directory");
        File existing = Toolchain.nativeEnvironment();
        if (existing != null) {
            chooser.setCurrentDirectory(existing);
        }
        if (chooser.showOpenDialog(getOWLWorkspace()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File chosen = chooser.getSelectedFile();
        if (Toolchain.activationScript(chosen) == null) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "There is no bin/activate-odk-environment.sh in "
                            + chosen.getAbsolutePath()
                            + ".\n\nThat script is what 'odk install' leaves behind, and what "
                            + "OntoBoard sources to put the ODK tools on the PATH. Choose the "
                            + "directory you gave to 'odk install'.",
                    "Not a native ODK environment", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Toolchain.setNativeEnvironment(chosen);
        JOptionPane.showMessageDialog(getOWLWorkspace(),
                "Native ODK environment set to " + chosen.getAbsolutePath()
                        + ".\n\nODK builds will now run there instead of in a container. "
                        + "Re-run Check requirements to see the change.",
                "Native ODK environment", JOptionPane.INFORMATION_MESSAGE);
    }
}
