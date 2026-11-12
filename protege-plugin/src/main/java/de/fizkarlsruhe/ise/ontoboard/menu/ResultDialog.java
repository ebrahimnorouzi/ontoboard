package de.fizkarlsruhe.ise.ontoboard.menu;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.DefaultTableModel;

/**
 * Shows an {@link OperationResult}, and lets it be saved.
 *
 * <p>One dialog for every operation on the OntoBoard menu. The alternative - each operation
 * opening its own - produces a dozen slightly different dialogs, and reliably one that shows a
 * table while discarding the warnings that produced it.
 *
 * <p>Three things it insists on. The status is stated in words and colour, so a run that finished
 * with warnings does not read as a clean success. The log is always present as a tab, even when
 * empty, so nobody has to wonder whether there was one. And <b>Save</b> writes the whole thing -
 * summary, table, log, timing, files - because a saved report missing the warnings is the
 * half-truth this is meant to avoid.
 *
 * <p>Swing, so untested; every decision it renders is made in {@link OperationResult}, which is.
 */
public final class ResultDialog {

    private static final Color GOOD = new Color(0x2E, 0x7D, 0x32);
    private static final Color WARN = new Color(0xB0, 0x6C, 0x00);
    private static final Color BAD = new Color(0xC6, 0x28, 0x28);

    /** How many written files are named above the log; more are counted there and listed in it. */
    static final int FILES_NAMED = 3;

    /** What the log keeps of the panel's height: its tab and a handful of lines. */
    private static final int LOG_KEEPS = 120;

    /** The tallest the panel grows to keep the log that room; a laptop screen still holds it. */
    private static final int TALLEST = 560;

    /** The room a scroll bar beside the header takes, so that it does not cut the text. */
    private static final int SCROLL_BAR = 20;

    private ResultDialog() {
    }

    /** Shows {@code result}, blocking until dismissed. */
    public static void show(Component parent, OperationResult result) {
        JPanel content = buildContent(result);

        JButton save = new JButton("Save report...");
        save.setToolTipText("Write the summary, the table and the log to a file");
        save.addActionListener(a -> saveWithChooser(parent, result));

        JOptionPane pane = new JOptionPane(content, JOptionPane.PLAIN_MESSAGE,
                JOptionPane.DEFAULT_OPTION, null, new Object[] {save, "Close"}, "Close");
        pane.createDialog(parent, "OntoBoard: " + result.getOperation()).setVisible(true);
    }

    /**
     * The panel the dialog shows, separated from showing it.
     *
     * <p>Visible to the package so a test can paint it. This is what every operation in the
     * plugin puts on screen when it finishes, and until it could be rendered headlessly any
     * claim about how it looks meant launching Prot&eacute;g&eacute; and running something. See
     * {@code DialogDesignProofTest}, and {@code CanvasDesignProofTest} for the same argument
     * about the diagram.
     */
    static JPanel buildContent(OperationResult result) {
        JPanel content = new JPanel(new BorderLayout(0, 8));
        Component header = header(result);
        // The dialog cannot be resized, so what the header takes the log loses - and what is
        // pushed out of the panel cannot be reached. A long sentence above the log makes the
        // panel taller instead: a release's result showed no log at all (1.153.0, live run).
        // Past what a laptop screen holds, it is the sentence that scrolls, not the log that
        // goes.
        int usual = result.hasTable() ? 460 : 260;
        int room = TALLEST - 8 - LOG_KEEPS;
        int headerHeight = header.getPreferredSize().height;
        boolean scrolled = headerHeight > room;
        int width = (result.hasTable() ? 680 : 520) + (scrolled ? SCROLL_BAR : 0);
        if (scrolled) {
            JScrollPane pane = new JScrollPane(header,
                    JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            pane.setBorder(BorderFactory.createEmptyBorder());
            pane.setPreferredSize(new Dimension(width, room));
            content.add(pane, BorderLayout.NORTH);
        } else {
            content.add(header, BorderLayout.NORTH);
        }
        content.add(body(result), BorderLayout.CENTER);
        content.setPreferredSize(new Dimension(width, Math.min(TALLEST,
                Math.max(usual, Math.min(headerHeight, room) + 8 + LOG_KEEPS))));
        return content;
    }

    private static Component header(OperationResult result) {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JLabel status = new JLabel(statusText(result));
        status.setFont(status.getFont().deriveFont(Font.BOLD));
        status.setForeground(colourFor(result));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(status);

        // 390, not 460: Swing draws a CSS pixel 1.3 screen pixels wide, so 460 asked for 598 px in a
        // 520 px panel, and a tester found every failed build's message cut off at the right.
        JLabel summary = new JLabel("<html><body style='width:390px'>"
                + escape(result.getSummary()) + "</body></html>");
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        header.add(summary);

        List<File> wrote = result.getFiles();
        if (!wrote.isEmpty()) {
            // A release writes a dozen files. Named one a line they took the panel; the first
            // two are named here and all of them at the end of the log.
            boolean all = wrote.size() <= FILES_NAMED;
            StringBuilder written = new StringBuilder("<html><body style='width:390px'>Wrote: ");
            for (File file : all ? wrote : wrote.subList(0, FILES_NAMED - 1)) {
                written.append("<br>").append(escape(file.getAbsolutePath()));
            }
            if (!all) {
                written.append("<br>and ").append(wrote.size() - (FILES_NAMED - 1))
                        .append(" more - all are listed at the end of the log");
            }
            JLabel files = new JLabel(written.append("</body></html>").toString());
            files.setAlignmentX(Component.LEFT_ALIGNMENT);
            files.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
            header.add(files);
        }
        header.add(Box.createVerticalStrut(4));
        return header;
    }

    private static Component body(OperationResult result) {
        JTabbedPane tabs = new JTabbedPane();
        if (result.hasTable()) {
            tabs.addTab("Results (" + result.getRows().size() + ")", new JScrollPane(table(result)));
        }
        // Always a log tab, even when empty: an absent tab leaves the user wondering whether
        // there was a log they are not being shown.
        StringBuilder logged = new StringBuilder(result.getLog().isEmpty()
                ? "Nothing was logged." : String.join("\n", result.getLog()));
        if (result.getFiles().size() > FILES_NAMED) {
            logged.append("\n\nWrote:");
            for (File file : result.getFiles()) {
                logged.append('\n').append(file.getAbsolutePath());
            }
        }
        JTextArea log = new JTextArea(logged.toString());
        log.setCaretPosition(0);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tabs.addTab("Log (" + result.getLog().size() + ")", new JScrollPane(log));
        return tabs;
    }

    private static JTable table(OperationResult result) {
        List<String> columns = result.getColumns();
        DefaultTableModel model = new DefaultTableModel(
                columns.toArray(new Object[0]), 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int row, int column) {
                // A results table nobody can edit, because editing it would change nothing and
                // imply it would.
                return false;
            }
        };
        for (List<String> row : result.getRows()) {
            model.addRow(row.toArray(new Object[0]));
        }
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        return table;
    }

    private static void saveWithChooser(Component parent, OperationResult result) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(result.suggestedFileName()));
        chooser.setDialogTitle("Save the " + result.getOperation() + " report");
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            File written = result.saveTo(chooser.getSelectedFile());
            JOptionPane.showMessageDialog(parent, "Saved to\n" + written.getAbsolutePath(),
                    "Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException failed) {
            JOptionPane.showMessageDialog(parent,
                    "Could not write the report:\n" + failed.getMessage(),
                    "Not saved", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String statusText(OperationResult result) {
        switch (result.getStatus()) {
            case FAILED:
                return "Did not finish  (" + result.getMillis() + " ms)";
            case SUCCEEDED_WITH_WARNINGS:
                return "Finished, with warnings  (" + result.getMillis() + " ms)";
            case SUCCEEDED:
            default:
                return "Finished  (" + result.getMillis() + " ms)";
        }
    }

    private static Color colourFor(OperationResult result) {
        switch (result.getStatus()) {
            case FAILED:
                return BAD;
            case SUCCEEDED_WITH_WARNINGS:
                return WARN;
            case SUCCEEDED:
            default:
                return GOOD;
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
