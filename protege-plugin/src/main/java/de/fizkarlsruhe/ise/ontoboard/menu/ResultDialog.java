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

    private ResultDialog() {
    }

    /** Shows {@code result}, blocking until dismissed. */
    public static void show(Component parent, OperationResult result) {
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(header(result), BorderLayout.NORTH);
        content.add(body(result), BorderLayout.CENTER);
        content.setPreferredSize(new Dimension(result.hasTable() ? 680 : 520,
                result.hasTable() ? 460 : 260));

        JButton save = new JButton("Save report...");
        save.setToolTipText("Write the summary, the table and the log to a file");
        save.addActionListener(a -> saveWithChooser(parent, result));

        JOptionPane pane = new JOptionPane(content, JOptionPane.PLAIN_MESSAGE,
                JOptionPane.DEFAULT_OPTION, null, new Object[] {save, "Close"}, "Close");
        pane.createDialog(parent, "OntoBoard: " + result.getOperation()).setVisible(true);
    }

    private static Component header(OperationResult result) {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JLabel status = new JLabel(statusText(result));
        status.setFont(status.getFont().deriveFont(Font.BOLD));
        status.setForeground(colourFor(result));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(status);

        JLabel summary = new JLabel("<html><body style='width:460px'>"
                + escape(result.getSummary()) + "</body></html>");
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        header.add(summary);

        if (!result.getFiles().isEmpty()) {
            StringBuilder written = new StringBuilder("<html><body style='width:460px'>Wrote: ");
            for (File file : result.getFiles()) {
                written.append("<br>").append(escape(file.getAbsolutePath()));
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
        JTextArea log = new JTextArea(result.getLog().isEmpty()
                ? "Nothing was logged." : String.join("\n", result.getLog()));
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
