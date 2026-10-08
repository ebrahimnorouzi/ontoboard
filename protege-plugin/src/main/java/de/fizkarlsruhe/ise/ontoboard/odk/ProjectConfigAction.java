package de.fizkarlsruhe.ise.ontoboard.odk;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;

/**
 * OntoBoard &gt; Project &gt; Project configuration... - the project's own
 * {@code &lt;id&gt;-odk.yaml}, shown and editable.
 *
 * <p>The largest gap in OntoBoard's ODK support, and the hinge the rest turn on. The wizard
 * wrote this file once and nothing ever showed it again; five scalars were read by a regex, and
 * on a real ODK repository even those were unreachable because the regenerator refuses such a
 * project before reading anything. So of the thirteen keys a real project declares, exactly one
 * - {@code title} - was ever read, and the documentation had to tell people to leave Protégé and
 * open a text editor.
 *
 * <p>Edits are spliced rather than re-serialised - see {@link OdkYaml} for why, and for what is
 * refused. This class is the part that touches the disk, so it carries the two guards that
 * matter there: the file is re-read and compared immediately before writing, and the write goes
 * through a temporary file in the same directory and an atomic move.
 */
public class ProjectConfigAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        File yaml;
        try {
            yaml = OdkProjectSettings.yamlIn(ontologyDirectory());
        } catch (RuntimeException notFound) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), notFound.getMessage(),
                    "No project configuration", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(yaml.toPath()), StandardCharsets.UTF_8);
        } catch (IOException cannotRead) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "Could not read " + yaml.getAbsolutePath() + ": " + cannotRead.getMessage(),
                    "Cannot read the configuration", JOptionPane.ERROR_MESSAGE);
            return;
        }

        List<OdkYaml.Entry> entries;
        try {
            entries = OdkYaml.entriesIn(text);
        } catch (OdkYaml.UnreadableException unreadable) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), unreadable.getMessage(),
                    "Cannot show this configuration", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // The file as the dialog currently believes it to be. Scalar edits live in the table
        // model until OK; a structural edit - adding or removing a list entry - has to change the
        // text immediately, because it renumbers every index after it and the table is addressed
        // by path. So the two are reconciled through this: a structural edit first flushes the
        // pending scalar edits into the working copy, then applies itself, then the table is
        // rebuilt from the result.
        final Session session = new Session(text, entries);

        final JTable table = new JTable(session.model);
        table.setRowHeight(table.getRowHeight() + 6);
        widths(table);
        session.table = table;

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(new JLabel("<html>" + yaml.getName()
                + " &nbsp;<font color=\"#5A6470\">every scalar is editable in place. Use the "
                + "buttons below to add an entry to a list - an import product, an export format "
                + "- or to remove the selected one.</font></html>"), BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);

        final JButton add = new JButton("Add to a list...");
        add.setToolTipText("Append an entry to one of the lists in this file");
        add.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                addToList(session);
            }
        });
        final JButton remove = new JButton("Remove entry");
        remove.setEnabled(false);
        remove.setToolTipText("Select a list entry in the table first");
        table.getSelectionModel().addListSelectionListener(
                new javax.swing.event.ListSelectionListener() {
                    @Override
                    public void valueChanged(javax.swing.event.ListSelectionEvent event) {
                        remove.setEnabled(session.selectedListItem(table.getSelectedRow()) != null);
                    }
                });
        remove.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                removeSelected(session, table.getSelectedRow());
            }
        });
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        buttons.add(add);
        buttons.add(remove);
        content.add(buttons, BorderLayout.SOUTH);
        content.setPreferredSize(new Dimension(720, 420));

        if (JOptionPane.showConfirmDialog(getOWLWorkspace(), content,
                "Project configuration", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        if (table.isEditing()) {
            // Otherwise the cell being typed into is not in the model yet, and the edit the user
            // just made is the one edit that does not get saved.
            table.getCellEditor().stopCellEditing();
        }
        save(yaml, text, session);
    }

    private static void widths(JTable table) {
        table.getColumnModel().getColumn(0).setPreferredWidth(170);
        table.getColumnModel().getColumn(1).setPreferredWidth(360);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
    }

    /**
     * What the dialog is editing: the text as it now stands, and the rows showing it.
     *
     * <p>Exists because a structural edit and a scalar edit cannot both be deferred to OK. A
     * scalar edit is addressed by path and can wait; adding or removing a list entry renumbers
     * every path after it, so deferring one would leave every pending scalar edit addressing the
     * wrong line. Applying structure immediately, into this working copy, keeps one truth.
     */
    private static final class Session {
        private String working;
        private List<OdkYaml.Entry> entries;
        private final DefaultTableModel model;
        private JTable table;
        private int structuralEdits;

        Session(String text, List<OdkYaml.Entry> entries) {
            this.working = text;
            this.entries = entries;
            this.model = modelFor(entries);
        }

        /** The entry at that row when it is an item of a list, or null. */
        OdkYaml.Entry selectedListItem(int row) {
            if (row < 0 || row >= entries.size()) {
                return null;
            }
            OdkYaml.Entry entry = entries.get(row);
            return entry.getPath().endsWith("]") ? entry : null;
        }

        /** Every list in the file, by path, so the Add dialog can offer them. */
        List<String> listPaths() {
            java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<String>();
            for (OdkYaml.Entry entry : entries) {
                String path = entry.getPath();
                int bracket = path.indexOf('[');
                if (bracket > 0) {
                    paths.add(path.substring(0, bracket));
                }
            }
            return new ArrayList<String>(paths);
        }

        /**
         * Folds the table's pending scalar edits into the working text.
         *
         * <p>Called before every structural change. Without it, typing a new title and then
         * adding an export format would lose the title: the table is rebuilt from the new text,
         * and whatever was only in the old model goes with it.
         */
        void flushScalarEdits() {
            if (table != null && table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
            for (int row = 0; row < model.getRowCount() && row < entries.size(); row++) {
                OdkYaml.Entry entry = entries.get(row);
                if (!entry.getEditable().isEditable()) {
                    continue;
                }
                String now = String.valueOf(model.getValueAt(row, 1));
                if (!now.equals(entry.getValue())) {
                    working = OdkYaml.withValue(working, entry.getPath(), now);
                }
            }
        }

        /** Re-reads the working text, so paths and indices are right again. */
        void reload() {
            entries = OdkYaml.entriesIn(working);
            DefaultTableModel rebuilt = modelFor(entries);
            model.setDataVector(rebuilt.getDataVector(), columnNames());
            if (table != null) {
                widths(table);
            }
        }
    }

    private static java.util.Vector<Object> columnNames() {
        java.util.Vector<Object> names = new java.util.Vector<Object>();
        names.add("Key");
        names.add("Value");
        names.add("Editable");
        return names;
    }

    /** Asks which list and what to add, then adds it to the working copy. */
    private void addToList(Session session) {
        List<String> lists = session.listPaths();
        if (lists.isEmpty()) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "This file has no lists with anything in them yet. A list needs one entry "
                            + "already in it, because the indentation of a new one is copied from "
                            + "the last rather than guessed - add the first in a text editor.",
                    "No list to add to", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object chosen = JOptionPane.showInputDialog(getOWLWorkspace(),
                "Which list?", "Add to a list", JOptionPane.PLAIN_MESSAGE, null,
                lists.toArray(), lists.get(0));
        if (chosen == null) {
            return;
        }
        String value = JOptionPane.showInputDialog(getOWLWorkspace(),
                "What to add to " + chosen + "?\n\nIt is appended as a new entry. A value that "
                        + "YAML would otherwise read as a number or a boolean is quoted for you.",
                "Add to " + chosen, JOptionPane.PLAIN_MESSAGE);
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        try {
            session.flushScalarEdits();
            session.working = OdkYaml.appendTo(session.working, String.valueOf(chosen), value);
            session.structuralEdits++;
            session.reload();
        } catch (OdkYaml.UnreadableException refused) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    refused.getMessage() + "\n\nNothing has been changed.",
                    "Cannot add that", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Removes the selected list entry from the working copy, after saying which one. */
    private void removeSelected(Session session, int row) {
        OdkYaml.Entry entry = session.selectedListItem(row);
        if (entry == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(getOWLWorkspace(),
                "Remove " + entry.getPath() + "?\n\n" + entry.getKey() + ": " + entry.getValue()
                        + "\n\nIf it is a block, everything inside it goes with it. Nothing is "
                        + "written until you press OK on the configuration dialog.",
                "Remove entry", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            session.flushScalarEdits();
            session.working = OdkYaml.removeFrom(session.working, entry.getPath());
            session.structuralEdits++;
            session.reload();
        } catch (OdkYaml.UnreadableException refused) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    refused.getMessage() + "\n\nNothing has been changed.",
                    "Cannot remove that", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * Applies whatever changed, or says plainly that nothing did.
     *
     * <p>Two kinds of change arrive together. The structural ones - entries added to or removed
     * from a list - are already in {@code session.working}, because they had to be: each one
     * renumbers the paths after it. The scalar ones are still sitting in the table, addressed by
     * paths that are correct <em>for that working copy</em>, which is why they are applied to it
     * rather than to the text the dialog opened with.
     */
    private void save(File yaml, String original, Session session) {
        Map<String, String> changed = new LinkedHashMap<String, String>();
        for (int row = 0; row < session.model.getRowCount() && row < session.entries.size();
                row++) {
            OdkYaml.Entry entry = session.entries.get(row);
            if (!entry.getEditable().isEditable()) {
                continue;
            }
            String now = String.valueOf(session.model.getValueAt(row, 1));
            if (!now.equals(entry.getValue())) {
                // By path, not by key. Two products both have an `id`, and `module_type`
                // appears four times - keying the edit by the leaf name would write one
                // product's value into another's.
                changed.put(entry.getPath(), now);
            }
        }
        if (changed.isEmpty() && session.structuralEdits == 0) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), "Nothing was changed.",
                    "Project configuration", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String updated = session.working;
        try {
            for (Map.Entry<String, String> edit : changed.entrySet()) {
                updated = OdkYaml.withValue(updated, edit.getKey(), edit.getValue());
            }
        } catch (OdkYaml.UnreadableException refused) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    refused.getMessage() + "\n\nNothing has been written.",
                    "Cannot make that change", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            writeIfUnchanged(yaml, original, updated);
        } catch (IOException cannotWrite) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), cannotWrite.getMessage(),
                    "Nothing was written", JOptionPane.ERROR_MESSAGE);
            return;
        }
        StringBuilder what = new StringBuilder("Saved ");
        if (!changed.isEmpty()) {
            what.append(changed.size()).append(changed.size() == 1 ? " value: " : " values: ")
                    .append(String.join(", ", changed.keySet()));
        }
        if (session.structuralEdits > 0) {
            what.append(changed.isEmpty() ? "" : ", and ").append(session.structuralEdits)
                    .append(session.structuralEdits == 1 ? " list change" : " list changes");
        }
        JOptionPane.showMessageDialog(getOWLWorkspace(),
                what.append(" to ").append(yaml.getName())
                        .append(".\n\nODK reads this file when it regenerates the repository, so "
                                + "run the project's own update step for it to take effect.")
                        .toString(),
                "Project configuration", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Writes, but only onto exactly the bytes that were read.
     *
     * <p>Two things go wrong here and neither is unlikely. The file may have changed under the
     * dialog - a text editor, a git pull, a {@code make update_repo} - and the replacement was
     * built from offsets into the old text, so writing it would discard that change wholesale.
     * And a crash or a full disk during a plain write leaves a truncated configuration, which
     * breaks every ODK target at once.
     *
     * <p>So: compare first and refuse, then write through a temporary file in the same directory
     * and move it into place. The same directory matters - an atomic move across filesystems is
     * not atomic, and the system temporary directory is frequently on another one.
     */
    static void writeIfUnchanged(File yaml, String expected, String updated) throws IOException {
        // One implementation, in OdkBuildSettings, because Import terms... needs the same
        // guarantee to add an import product and two atomic replaces is one that drifts.
        OdkBuildSettings.writeIfUnchanged(yaml, expected, updated);
    }

    /** Key, value and whether it can be edited - the third column explains a read-only row. */
    private static DefaultTableModel modelFor(final List<OdkYaml.Entry> entries) {
        List<Object[]> rows = new ArrayList<Object[]>();
        for (OdkYaml.Entry entry : entries) {
            // Indented by depth, so the nesting is visible: a bare `id` column would show four
            // identical rows for the four imported products.
            StringBuilder shown = new StringBuilder();
            for (int space = 0; space < entry.getDepth() * 4; space++) {
                shown.append(' ');
            }
            shown.append(entry.getKey());
            rows.add(new Object[] {shown.toString(), entry.getValue(),
                    entry.getEditable().isEditable() ? "line " + entry.getLine()
                            : entry.getEditable().name().toLowerCase(java.util.Locale.ROOT)});
        }
        return new DefaultTableModel(rows.toArray(new Object[0][]),
                new Object[] {"Key", "Value", ""}) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int row, int column) {
                // A value with no path cannot be addressed unambiguously - a key containing a
                // dot or a bracket - so it is shown and not offered for editing, rather than
                // risking an edit landing on a different key than the one clicked.
                return column == 1 && entries.get(row).getEditable().isEditable()
                        && !entries.get(row).getPath().isEmpty();
            }
        };
    }

    /**
     * The directory the open ontology is saved in, which is where its configuration lives.
     *
     * <p>The configuration sits beside the edit file in {@code src/ontology}, so this is the
     * directory to search - the same one {@code OdkProjectSettings} reads from, through the
     * same method, so the editor cannot end up showing a different file from the one the rest
     * of the plugin uses.
     */
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
                    + "cannot find a project configuration beside it.");
        }
        File parent = new File(uri).getAbsoluteFile().getParentFile();
        if (parent == null) {
            throw new IllegalStateException("This ontology has no directory to search.");
        }
        return parent;
    }
}
