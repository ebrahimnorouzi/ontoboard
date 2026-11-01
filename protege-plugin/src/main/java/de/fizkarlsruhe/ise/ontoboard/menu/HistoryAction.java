package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.Release;
import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseDiff;
import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseHistory;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * OntoBoard &gt; Project &gt; History... - every version the project kept, what each holds, what
 * changed from one to the next, and any of them opened in a window of its own.
 *
 * <p>Asked for: "go through the evolution of the ontology by opening the versions in Protege - all
 * the available versions, click one to open it in a new Protege window, and the statistics of what
 * was changed: what was added, deleted, obsoleted... useful and practical." Compare releases
 * already answered one pair at a time; this is the whole path in one table, with that comparison
 * underneath it for whichever row is chosen.
 *
 * <p>The versions are the ones Release keeps in {@code releases/<version>/}, so it needs nothing
 * but the project: no git, no network.
 */
public class HistoryAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    private static final Color BAR = new Color(0xCF, 0xE0, 0xF5);

    private final List<ReleaseHistory.Version> versions = new ArrayList<ReleaseHistory.Version>();
    private VersionsModel model;
    private JTable table;
    private JEditorPane details;
    private ChangesModel changes;
    private JComboBox<String> against;
    private JButton open;
    private JLabel status;
    private OWLOntology workingCopy;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        workingCopy = getOWLModelManager().getActiveOntology();
        java.net.URI physical = getOWLModelManager().getOntologyPhysicalURI(workingCopy);
        File editFile = physical != null && "file".equals(physical.getScheme())
                ? new File(physical) : null;
        if (editFile == null) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "This ontology has not been saved, so it has no project and no releases yet.",
                    "No history", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File projectRoot = ReleaseAction.projectRootOf(editFile);
        String id = Release.ontologyIdFrom(editFile);
        List<String> kept = CompareReleasesAction.releasesOf(editFile);
        show(projectRoot, id, kept, editFile);
    }

    private void show(final File projectRoot, final String id, final List<String> kept,
            final File editFile) {
        final JDialog dialog = new JDialog(
                javax.swing.SwingUtilities.getWindowAncestor(getOWLWorkspace()),
                "History of " + id, java.awt.Dialog.ModalityType.MODELESS);

        model = new VersionsModel();
        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(1).setCellRenderer(new TermsBar());
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(170);
        table.getColumnModel().getColumn(7).setPreferredWidth(380);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selected();
            }
        });

        details = new JEditorPane("text/html", "");
        details.setEditable(false);
        changes = new ChangesModel();
        JTable changeTable = new JTable(changes);
        changeTable.setAutoCreateRowSorter(true);
        changeTable.getColumnModel().getColumn(0).setPreferredWidth(110);
        changeTable.getColumnModel().getColumn(1).setPreferredWidth(200);

        JPanel lower = new JPanel(new BorderLayout(0, 6));
        JScrollPane summary = new JScrollPane(details);
        summary.setPreferredSize(new Dimension(800, 130));
        lower.add(summary, BorderLayout.NORTH);
        lower.add(new JScrollPane(changeTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table), lower);
        split.setResizeWeight(0.45);

        against = new JComboBox<String>();
        against.addActionListener(e -> compareChosen());
        open = new JButton("Open in a new window");
        open.setToolTipText("Opens the selected version in a Protege window of its own");
        open.setEnabled(false);
        open.addActionListener(e -> openSelected());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(open);
        buttons.add(new JLabel("   Compare with:"));
        buttons.add(against);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(buttons, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right.add(close);
        bottom.add(right, BorderLayout.EAST);

        status = new JLabel("Reading " + kept.size() + " release" + (kept.size() == 1 ? "" : "s")
                + "...");
        status.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JPanel content = new JPanel(new BorderLayout(0, 4));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(status, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setSize(1100, 720);
        dialog.setLocationRelativeTo(getOWLWorkspace());
        dialog.setVisible(true);

        new SwingWorker<List<ReleaseHistory.Version>, String>() {
            @Override
            protected List<ReleaseHistory.Version> doInBackground() throws Exception {
                return ReleaseHistory.of(projectRoot, id, kept, workingCopy, editFile,
                        (version, index, of) -> publish("Reading " + version + " (" + index
                                + " of " + of + ")..."));
            }

            @Override
            protected void process(List<String> said) {
                status.setText(said.get(said.size() - 1));
            }

            @Override
            protected void done() {
                try {
                    versions.clear();
                    versions.addAll(get());
                } catch (Exception failed) {
                    Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
                    status.setText("Could not read the history: " + cause.getMessage());
                    return;
                }
                model.fireTableDataChanged();
                against.removeAllItems();
                against.addItem(THE_ONE_BEFORE);
                for (ReleaseHistory.Version version : versions) {
                    against.addItem(version.getName());
                }
                int releases = versions.size() - 1;
                status.setText(releases <= 0
                        ? "No releases kept yet. Project > Release... keeps each one in "
                                + "releases/<version>/, and they will be listed here."
                        : releases + " release" + (releases == 1 ? "" : "s") + ", newest first, "
                                + "and the working copy above them. Choose one to see what "
                                + "changed in it.");
                if (!versions.isEmpty()) {
                    table.setRowSelectionInterval(0, 0);
                }
            }
        }.execute();
    }

    private static final String THE_ONE_BEFORE = "the version before";

    private ReleaseHistory.Version selectedVersion() {
        int row = table.getSelectedRow();
        return row < 0 || row >= versions.size() ? null : versions.get(row);
    }

    /** Shows what changed in the chosen version, against the one before it. */
    private void selected() {
        ReleaseHistory.Version version = selectedVersion();
        open.setEnabled(version != null && !version.isWorkingCopy());
        if (version == null) {
            return;
        }
        if (!THE_ONE_BEFORE.equals(against.getSelectedItem())) {
            against.setSelectedItem(THE_ONE_BEFORE);
            return;
        }
        int row = versions.indexOf(version);
        ReleaseHistory.Version before = row + 1 < versions.size() ? versions.get(row + 1) : null;
        showChange(before, version, version.getSincePrevious(), version.getPropertiesAdded(),
                version.getPropertiesRemoved(), version.getIndividualsAdded(),
                version.getIndividualsRemoved());
    }

    /** Compares the chosen version with another one picked from the list. */
    private void compareChosen() {
        final ReleaseHistory.Version version = selectedVersion();
        Object choice = against.getSelectedItem();
        if (version == null || choice == null) {
            return;
        }
        if (THE_ONE_BEFORE.equals(choice)) {
            selected();
            return;
        }
        ReleaseHistory.Version other = null;
        for (ReleaseHistory.Version candidate : versions) {
            if (candidate.getName().equals(choice)) {
                other = candidate;
            }
        }
        if (other == null || other == version) {
            details.setText(html("<p>Choose a different version to compare with.</p>"));
            changes.show(new ArrayList<String[]>());
            return;
        }
        // Earlier against later, whichever way round they were picked: the comparison is
        // asymmetric, and backwards it reports everything added as removed.
        final ReleaseHistory.Version earlier =
                versions.indexOf(other) > versions.indexOf(version) ? other : version;
        final ReleaseHistory.Version later = earlier == other ? version : other;
        status.setText("Comparing " + earlier.getName() + " with " + later.getName() + "...");
        new SwingWorker<ReleaseHistory.Version, Void>() {
            @Override
            protected ReleaseHistory.Version doInBackground() throws Exception {
                return ReleaseHistory.compare(earlier, later, workingCopy);
            }

            @Override
            protected void done() {
                try {
                    ReleaseHistory.Version compared = get();
                    showChange(earlier, later, compared.getSincePrevious(),
                            compared.getPropertiesAdded(), compared.getPropertiesRemoved(),
                            compared.getIndividualsAdded(), compared.getIndividualsRemoved());
                    status.setText("Compared " + earlier.getName() + " with "
                            + later.getName() + ".");
                } catch (Exception failed) {
                    status.setText("Could not compare them: " + failed.getMessage());
                }
            }
        }.execute();
    }

    private void showChange(ReleaseHistory.Version before, ReleaseHistory.Version after,
            ReleaseDiff diff, List<String> propertiesAdded, List<String> propertiesRemoved,
            List<String> individualsAdded, List<String> individualsRemoved) {
        ReleaseHistory.Counts counts = after.getCounts();
        StringBuilder text = new StringBuilder();
        text.append("<p><b>").append(escape(after.getName())).append("</b>")
                .append(after.getVersionInfo().isEmpty() || after.isWorkingCopy() ? ""
                        : " &middot; version label " + escape(after.getVersionInfo()))
                .append(" &middot; ").append(counts.getClasses()).append(" classes, ")
                .append(counts.getProperties()).append(" properties, ")
                .append(counts.getIndividuals()).append(" individuals, ")
                .append(counts.getLogicalAxioms()).append(" logical axioms")
                .append(counts.getObsolete() == 0 ? "" : ", " + counts.getObsolete() + " obsolete")
                .append("</p>");
        List<String[]> rows = new ArrayList<String[]>();
        if (before == null || diff == null) {
            text.append("<p>The first release: everything in it is new.</p>");
        } else {
            text.append("<p>Since <b>").append(escape(before.getName())).append("</b>: ");
            boolean any = false;
            for (ReleaseDiff.Change change : ReleaseDiff.Change.values()) {
                int count = diff.count(change);
                if (count > 0) {
                    text.append(any ? ", " : "").append(count).append(' ')
                            .append(change.getLabel().toLowerCase(java.util.Locale.ROOT));
                    any = true;
                }
            }
            text.append(any ? " (classes)" : "no class changed").append(". ");
            text.append(signed(propertiesAdded.size(), propertiesRemoved.size(), "properties"))
                    .append(signed(individualsAdded.size(), individualsRemoved.size(),
                            "individuals"));
            text.append("Logical axioms ")
                    .append(before.getCounts().getLogicalAxioms()).append(" &rarr; ")
                    .append(counts.getLogicalAxioms()).append(".</p>");
            if (diff.count(ReleaseDiff.Change.REMOVED) > 0) {
                text.append("<p style='color:#B06C00'><b>")
                        .append(diff.count(ReleaseDiff.Change.REMOVED))
                        .append(" class(es) were removed rather than made obsolete.</b> "
                                + "Anything that imported them now refers to nothing. Making a "
                                + "term obsolete keeps it, marked deprecated, and tells its "
                                + "users what to use instead.</p>");
            }
            for (ReleaseDiff.TermChange change : diff.getChanges()) {
                rows.add(new String[] {change.getChange().getLabel(),
                    ReleaseDiff.shortForm(change.getIri()), change.getBefore(),
                    change.getAfter()});
            }
            addRows(rows, "Property added", propertiesAdded);
            addRows(rows, "Property removed", propertiesRemoved);
            addRows(rows, "Individual added", individualsAdded);
            addRows(rows, "Individual removed", individualsRemoved);
        }
        details.setText(html(text.toString()));
        details.setCaretPosition(0);
        changes.show(rows);
    }

    private static String signed(int added, int removed, String what) {
        if (added == 0 && removed == 0) {
            return "";
        }
        return what.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + what.substring(1) + ": "
                + (added == 0 ? "" : "+" + added) + (added > 0 && removed > 0 ? " " : "")
                + (removed == 0 ? "" : "-" + removed) + ". ";
    }

    private static void addRows(List<String[]> rows, String change, List<String> iris) {
        for (String iri : iris) {
            rows.add(new String[] {change, ReleaseHistory.shortForm(iri), "", ""});
        }
    }

    /** Opens the chosen release in a Protege window of its own. */
    private void openSelected() {
        ReleaseHistory.Version version = selectedVersion();
        if (version == null || version.isWorkingCopy() || version.getFile() == null) {
            return;
        }
        try {
            org.protege.editor.core.ProtegeManager protege =
                    org.protege.editor.core.ProtegeManager.getInstance();
            org.protege.editor.core.editorkit.EditorKitFactoryPlugin owl = null;
            for (org.protege.editor.core.editorkit.EditorKitFactoryPlugin plugin
                    : protege.getEditorKitFactoryPlugins()) {
                if (owl == null || String.valueOf(plugin.getId()).contains("OWL")) {
                    owl = plugin;
                }
            }
            if (owl == null || !protege.loadAndSetupEditorKitFromURI(owl,
                    version.getFile().toURI())) {
                status.setText("Protege did not open " + version.getFile().getName() + ".");
                return;
            }
            status.setText("Opened " + version.getName() + " in a new window.");
        } catch (Exception cannotOpen) {
            status.setText("Could not open " + version.getName() + ": "
                    + cannotOpen.getMessage());
        }
    }

    private static String html(String body) {
        return "<html><body style='font-family:sans-serif;font-size:11px'>" + body
                + "</body></html>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** The versions, newest first. */
    private final class VersionsModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] columns = {"Version", "Terms", "Classes", "Properties",
            "Individuals", "Logical axioms", "Obsolete", "What changed since the one before"};

        @Override
        public int getRowCount() {
            return versions.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            ReleaseHistory.Version version = versions.get(row);
            ReleaseHistory.Counts counts = version.getCounts();
            switch (column) {
                case 0:
                    return version.getName();
                case 1:
                    return counts.getTerms();
                case 2:
                    return counts.getClasses();
                case 3:
                    return counts.getProperties();
                case 4:
                    return counts.getIndividuals();
                case 5:
                    return counts.getLogicalAxioms();
                case 6:
                    return counts.getObsolete();
                default:
                    return version.describeChange();
            }
        }
    }

    /** One row per change, for whichever comparison is showing. */
    private static final class ChangesModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] columns = {"Change", "Term", "Was", "Now"};
        private List<String[]> rows = new ArrayList<String[]>();

        void show(List<String[]> shown) {
            rows = shown;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            return rows.get(row)[column];
        }
    }

    /** The number of terms, with a bar behind it as long as the version is large. */
    private final class TermsBar extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        private double share;

        @Override
        public Component getTableCellRendererComponent(JTable owner, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(owner, value, isSelected, hasFocus, row, column);
            int most = 1;
            for (ReleaseHistory.Version version : versions) {
                most = Math.max(most, version.getCounts().getTerms());
            }
            share = value instanceof Integer ? (Integer) value / (double) most : 0;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(BAR);
            g.fillRect(2, 4, (int) Math.round((getWidth() - 4) * share), getHeight() - 8);
            super.paintComponent(g);
        }

        @Override
        public boolean isOpaque() {
            return false;
        }
    }
}
