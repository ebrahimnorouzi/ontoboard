package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.konclude.KoncludeInstall;
import de.fizkarlsruhe.ise.ontoboard.tools.Download;
import de.fizkarlsruhe.ise.ontoboard.tools.HttpsOpener;
import de.fizkarlsruhe.ise.ontoboard.tools.ToolAsset;
import de.fizkarlsruhe.ise.ontoboard.tools.ToolSetup;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * What OntoBoard shows when Widoco or Konclude is not on this machine: get it from here, or by
 * hand.
 *
 * <p>Before the operation's own form, because that form's first field is a path to a file the
 * user does not have - a dead end with a help button. Here there are two ways on: <b>Download...</b>
 * (a folder of your choosing; the release OntoBoard was built against, checked against its
 * SHA-256, Konclude unpacked with its libraries beside it), or <b>Copy link</b> / <b>Open release
 * page</b> to fetch it with a browser - which trusts whatever the system trusts, the way round
 * an old Java that cannot check GitHub's certificate - and then <b>Choose file...</b>.
 *
 * <p>Swing, so untested: what it says is {@link ToolSetup}'s, what it keeps is decided by
 * {@link Download}, how it connects is {@link HttpsOpener}'s, and how Konclude is unpacked is
 * {@link KoncludeInstall#install}'s - all four tested without a screen.
 */
final class GetToolDialog {

    private static final String DOWNLOAD = "Download...";
    private static final String COPY = "Copy link";
    private static final String OPEN = "Open release page";
    private static final String CHOOSE = "Choose file...";
    private static final String CANCEL = "Cancel";

    private GetToolDialog() {
    }

    /**
     * Offers to get the tool.
     *
     * @param headline replaces the first line of the explanation - for a tool that is there but
     *     will not start - or null for "not installed"
     * @return the jar or binary to use, verified when it was downloaded, or null when the user
     *     cancelled
     */
    static File offer(Component parent, ToolSetup.Tool tool, ToolAsset asset, String headline) {
        List<String> lines = ToolSetup.explanation(tool, asset,
                System.getProperty("os.name", ""), System.getProperty("os.arch", ""),
                System.getProperty("java.specification.version", ""),
                KoncludeInstall.vcRuntimePresent());
        if (headline != null && !headline.trim().isEmpty()) {
            lines.set(0, headline.trim());
        }
        String title = (tool == ToolSetup.Tool.WIDOCO ? "Widoco" : "Konclude") + " is needed";
        Object[] options = {DOWNLOAD, COPY, OPEN, CHOOSE, CANCEL};
        String note = "";
        while (true) {
            int answer = JOptionPane.showOptionDialog(parent, text(lines, note), title,
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options,
                    options[0]);
            note = "";
            if (answer < 0 || answer >= options.length || CANCEL.equals(options[answer])) {
                return null;
            }
            Object pressed = options[answer];
            if (COPY.equals(pressed)) {
                note = copy(asset.getUrl());
                continue;
            }
            if (OPEN.equals(pressed)) {
                note = open(asset.getReleasePage());
                continue;
            }
            File got = DOWNLOAD.equals(pressed) ? download(parent, asset)
                    : choose(parent, tool, asset);
            if (got != null) {
                return got;
            }
        }
    }

    // ------------------------------------------------------------------- the four ways on

    private static File download(Component parent, final ToolAsset asset) {
        JFileChooser chooser = new JFileChooser(KoncludeInstall.downloads());
        chooser.setDialogTitle("Where should " + asset.getName() + " go?");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showDialog(parent, "Save here") != JFileChooser.APPROVE_OPTION
                || chooser.getSelectedFile() == null) {
            return null;
        }
        final File folder = chooser.getSelectedFile();
        final HttpsOpener opener = HttpsOpener.real();
        // Cancel closes the connection too, so a server that has gone quiet does not keep the
        // window - and the rest of Protege behind it - waiting out a 60-second timeout.
        return inBackground(parent, "Downloading " + asset.getName(), opener::stop, reporter -> {
            File got = Download.fetch(asset, folder, opener, reporter);
            if (!asset.isArchive()) {
                return got;
            }
            reporter.status("Unpacking " + asset.getName() + "...");
            return KoncludeInstall.install(got, folder);
        });
    }

    private static File choose(Component parent, ToolSetup.Tool tool, ToolAsset asset) {
        JFileChooser chooser = new JFileChooser(KoncludeInstall.downloads());
        chooser.setDialogTitle(tool == ToolSetup.Tool.WIDOCO ? "Choose the Widoco jar"
                : "Choose the Konclude zip, or the program inside it");
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION
                || chooser.getSelectedFile() == null) {
            return null;
        }
        File chosen = chooser.getSelectedFile();
        ToolSetup.Choice choice;
        try {
            choice = ToolSetup.interpret(tool, asset, chosen);
        } catch (IOException unreadable) {
            warn(parent, "Could not read " + chosen.getAbsolutePath() + ": "
                    + unreadable.getMessage());
            return null;
        }
        switch (choice.getKind()) {
            case USE:
                return choice.getFile();
            case UNPACK:
                final File zip = choice.getFile();
                return inBackground(parent, "Unpacking " + zip.getName(), null, reporter -> {
                    reporter.status("Unpacking " + zip.getName() + "...");
                    return KoncludeInstall.install(zip, zip.getParentFile());
                });
            default:
                warn(parent, choice.getReason());
                return null;
        }
    }

    private static String copy(String url) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(url), null);
            return "Copied: " + url;
        } catch (RuntimeException unavailable) {
            // HeadlessException, or the clipboard busy with another program.
            return "Could not copy the link. Here it is: " + url;
        }
    }

    private static String open(String page) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(page));
                return "Opened " + page + " in your browser.";
            }
        } catch (Exception cannotOpen) {
            // Some Linux desktops have no Desktop support; say where the page is instead.
        }
        return "Could not open a browser from here. The release page is " + page;
    }

    // --------------------------------------------------------------- work off the event thread

    /** Told what is happening, beside how far the download has got. */
    private interface Reporter extends Download.Progress {
        void status(String text);
    }

    private interface Work {
        File run(Reporter reporter) throws Exception;
    }

    /**
     * Runs the work on a background thread behind a modal progress window, and reports a failure
     * in one sentence with the way round it. A cancelled download is not a failure: it returns
     * null quietly, back to the choice of ways on.
     *
     * @param onCancel what Cancel does besides asking the work to stop - closing its connection -
     *     or null when the work cannot be cancelled
     */
    private static File inBackground(Component parent, String title, final Runnable onCancel,
            final Work work) {
        final boolean cancellable = onCancel != null;
        Window owner = parent instanceof Window ? (Window) parent
                : parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        final JProgressBar bar = new JProgressBar(0, 1000);
        bar.setIndeterminate(true);
        final JLabel status = new JLabel("Connecting...");
        final AtomicBoolean stopped = new AtomicBoolean();
        final Runnable cancel = () -> {
            stopped.set(true);
            status.setText("Stopping...");
            onCancel.run();
        };
        JButton stop = new JButton("Cancel");
        stop.setEnabled(cancellable);
        stop.addActionListener(event -> cancel.run());
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (cancellable) {
                    cancel.run();
                }
            }
        });
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(status, BorderLayout.NORTH);
        content.add(bar, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.add(stop);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setSize(Math.max(440, dialog.getWidth()), dialog.getHeight());
        dialog.setLocationRelativeTo(parent);

        SwingWorker<File, Object[]> worker = new SwingWorker<File, Object[]>() {
            @Override
            protected File doInBackground() throws Exception {
                return work.run(new Reporter() {
                    @Override
                    public void bytes(long done, long total) {
                        publish(new Object[] {done, total});
                    }

                    @Override
                    public boolean isCancelled() {
                        return stopped.get();
                    }

                    @Override
                    public void status(String text) {
                        publish(new Object[] {text});
                    }
                });
            }

            @Override
            protected void process(List<Object[]> chunks) {
                Object[] last = chunks.get(chunks.size() - 1);
                if (last.length == 1) {
                    bar.setIndeterminate(true);
                    status.setText((String) last[0]);
                    return;
                }
                if (stopped.get()) {
                    return;
                }
                long done = (Long) last[0];
                long total = (Long) last[1];
                if (total > 0) {
                    bar.setIndeterminate(false);
                    bar.setValue((int) Math.min(1000, done * 1000 / total));
                }
                status.setText(megabytes(done) + " of " + megabytes(total));
            }

            @Override
            protected void done() {
                dialog.dispose();
            }
        };
        worker.execute();
        // Modal: this returns once done() has disposed the window, whichever way it ended.
        dialog.setVisible(true);
        try {
            return worker.get();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Download.Failed && ((Download.Failed) cause).isCancelled()) {
                return null;
            }
            warn(parent, ToolSetup.whyItFailed(cause, System.getProperty("java.version", "?")));
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ------------------------------------------------------------------------------- display

    /** Selectable, so a link can be copied by hand even where the clipboard is not reachable. */
    private static JTextArea text(List<String> lines, String note) {
        StringBuilder all = new StringBuilder(String.join("\n\n", lines));
        if (note != null && !note.isEmpty()) {
            all.append("\n\n").append(note);
        }
        JTextArea area = new JTextArea(all.toString());
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setColumns(56);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        if (UIManager.getFont("Label.font") != null) {
            area.setFont(UIManager.getFont("Label.font"));
        }
        // A wrapping text area reports its preferred height only once it knows its width.
        area.setSize(area.getPreferredSize().width, Short.MAX_VALUE);
        return area;
    }

    private static void warn(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, text(java.util.Collections.singletonList(message),
                ""), "Could not get it", JOptionPane.WARNING_MESSAGE);
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
