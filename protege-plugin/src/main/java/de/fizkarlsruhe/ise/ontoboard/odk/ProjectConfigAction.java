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

        DefaultTableModel model = modelFor(entries);
        JTable table = new JTable(model);
        table.setRowHeight(table.getRowHeight() + 6);
        table.getColumnModel().getColumn(0).setPreferredWidth(170);
        table.getColumnModel().getColumn(1).setPreferredWidth(360);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(new JLabel("<html>" + yaml.getName()
                + " &nbsp;<font color=\"#5A6470\">" + entries.size()
                + " keys. Lists and nested blocks are shown but not editable here - editing one "
                + "in place would delete what is inside it.</font></html>"), BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
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
        save(yaml, text, entries, model);
    }

    /** Applies whatever changed, or says plainly that nothing did. */
    private void save(File yaml, String original, List<OdkYaml.Entry> entries,
            DefaultTableModel model) {
        Map<String, String> changed = new LinkedHashMap<String, String>();
        for (int row = 0; row < model.getRowCount(); row++) {
            OdkYaml.Entry entry = entries.get(row);
            if (!entry.getEditable().isEditable()) {
                continue;
            }
            String now = String.valueOf(model.getValueAt(row, 1));
            if (!now.equals(entry.getValue())) {
                // By path, not by key. Two products both have an `id`, and `module_type`
                // appears four times - keying the edit by the leaf name would write one
                // product's value into another's.
                changed.put(entry.getPath(), now);
            }
        }
        if (changed.isEmpty()) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), "Nothing was changed.",
                    "Project configuration", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String updated = original;
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
        JOptionPane.showMessageDialog(getOWLWorkspace(),
                "Saved " + changed.size() + (changed.size() == 1 ? " change to " : " changes to ")
                        + yaml.getName() + ": " + String.join(", ", changed.keySet())
                        + ".\n\nODK reads this file when it regenerates the repository, so run "
                        + "the project's own update step for it to take effect.",
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
        String onDisk = new String(Files.readAllBytes(yaml.toPath()), StandardCharsets.UTF_8);
        if (!onDisk.equals(expected)) {
            throw new IOException(yaml.getName() + " has changed on disk since it was opened, so "
                    + "nothing has been written - saving would have discarded that change. Close "
                    + "this and open it again.");
        }
        File directory = yaml.getAbsoluteFile().getParentFile();
        File temporary = File.createTempFile(yaml.getName(), ".tmp", directory);
        try {
            Files.write(temporary.toPath(), updated.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary.toPath(), yaml.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                // Some Windows filesystems refuse an atomic replace. A plain replace is still
                // better than writing in place, because the content is already complete on disk.
                Files.move(temporary.toPath(), yaml.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
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
