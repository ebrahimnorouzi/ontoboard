package de.fizkarlsruhe.ise.ontoboard.canvas;

import de.fizkarlsruhe.ise.ontoboard.model.PropertyOutline;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/**
 * The board's properties (1.155.0): the ontology's object and data properties in their hierarchy,
 * each with its characteristics and its domain and range, opened from the toolbar. On a board
 * drawn UML-like no property is a box of its own; this is where one is seen among the others. A
 * click selects the property in Prot&eacute;g&eacute;, and the board shows its arrows.
 *
 * <p>The tree never takes the keyboard: Delete, Escape, Ctrl+Z and the zoom keys are bound on the
 * board's graph component for when it or a child has focus, and a panel floating beside it that
 * took focus would silence every one of them - see {@code SchemaCanvasView.floatingButton}.
 */
public final class PropertiesPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** The width the panel asks for beside the board. */
    public static final int WIDTH = 300;

    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("Properties")));
    private final Consumer<String> onSelect;

    public PropertiesPanel(Consumer<String> onSelect, Runnable onClose) {
        super(new BorderLayout());
        this.onSelect = onSelect;
        setOpaque(true);
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createLineBorder(new Color(0xD8, 0xDD, 0xE3)));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        JLabel title = new JLabel("Properties");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setToolTipText("This ontology's properties and its imports', in their hierarchy. "
                + "A click selects one in Protégé; bold is the ontology's own.");
        header.add(title, BorderLayout.WEST);
        JButton close = new JButton("×");
        close.setToolTipText("Close the properties");
        close.setFocusable(false);
        close.setRequestFocusEnabled(false);
        close.setMargin(new java.awt.Insets(0, 6, 0, 6));
        close.addActionListener(a -> onClose.run());
        header.add(close, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFocusable(false);
        tree.setRequestFocusEnabled(false);
        // Each entry's own height: a name, and a line under it when there is something to say.
        tree.setRowHeight(0);
        tree.setCellRenderer(new Renderer());
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeSelectionListener(event -> {
            TreePath path = event.getNewLeadSelectionPath();
            Object node = path == null ? null : path.getLastPathComponent();
            if (node instanceof DefaultMutableTreeNode
                    && ((DefaultMutableTreeNode) node).getUserObject() instanceof PropertyOutline.Entry) {
                this.onSelect.accept(((PropertyOutline.Entry)
                        ((DefaultMutableTreeNode) node).getUserObject()).getIri());
            }
        });
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setFocusable(false);
        add(scroll, BorderLayout.CENTER);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(WIDTH, Math.max(160, super.getPreferredSize().height));
    }

    /** Shows this outline: both folders, opened down to the roots' sub-properties. */
    public void show(PropertyOutline outline) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Properties");
        root.add(folder("Object properties", outline.getObjectProperties()));
        root.add(folder("Data properties", outline.getDataProperties()));
        tree.setModel(new DefaultTreeModel(root));
        for (int folder = 0; folder < root.getChildCount(); folder++) {
            DefaultMutableTreeNode each = (DefaultMutableTreeNode) root.getChildAt(folder);
            tree.expandPath(new TreePath(each.getPath()));
            for (int i = 0; i < each.getChildCount(); i++) {
                tree.expandPath(new TreePath(((DefaultMutableTreeNode) each.getChildAt(i)).getPath()));
            }
        }
    }

    private static DefaultMutableTreeNode folder(String name, List<PropertyOutline.Entry> entries) {
        DefaultMutableTreeNode folder = new DefaultMutableTreeNode(name + " (" + count(entries) + ")");
        for (PropertyOutline.Entry entry : entries) {
            folder.add(node(entry));
        }
        return folder;
    }

    private static DefaultMutableTreeNode node(PropertyOutline.Entry entry) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(entry);
        for (PropertyOutline.Entry child : entry.getChildren()) {
            node.add(node(child));
        }
        return node;
    }

    /** How many properties, each once, however often the hierarchy places it. */
    private static int count(List<PropertyOutline.Entry> entries) {
        java.util.Set<String> seen = new java.util.HashSet<String>();
        List<PropertyOutline.Entry> waiting = new ArrayList<PropertyOutline.Entry>(entries);
        while (!waiting.isEmpty()) {
            PropertyOutline.Entry entry = waiting.remove(waiting.size() - 1);
            seen.add(entry.getIri());
            waiting.addAll(entry.getChildren());
        }
        return seen.size();
    }

    /** The tree, for a test. */
    JTree tree() {
        return tree;
    }

    /** An entry as the tree shows it: its name, bold for the ontology's own, and what it is. */
    static String textOf(PropertyOutline.Entry entry) {
        StringBuilder text = new StringBuilder("<html>");
        String name = escape(entry.getLabel());
        text.append(entry.isOwn() ? "<b>" + name + "</b>" : "<font color=\"#5A6470\">" + name + "</font>");
        List<String> about = new ArrayList<String>(entry.getCharacteristics());
        if (!entry.getDomainToRange().isEmpty()) {
            about.add(entry.getDomainToRange());
        }
        if (!about.isEmpty()) {
            text.append("<br><font color=\"#5A6470\" size=\"-2\">")
                    .append(escape(String.join(" · ", about))).append("</font>");
        }
        return text.append("</html>").toString();
    }

    /**
     * An entry on hover, whole: its name, each characteristic, its domain and range, and its IRI -
     * in Protégé's column a long line runs past the panel's edge.
     */
    static String hoverOf(PropertyOutline.Entry entry) {
        StringBuilder text = new StringBuilder("<html><b>").append(escape(entry.getLabel()))
                .append("</b>");
        for (String characteristic : entry.getCharacteristics()) {
            text.append("<br>").append(escape(characteristic));
        }
        if (!entry.getDomainToRange().isEmpty()) {
            text.append("<br>").append(escape(entry.getDomainToRange()));
        }
        return text.append("<br><font size=\"-2\">").append(escape(entry.getIri()))
                .append(entry.isOwn() ? "" : " - imported").append("</font></html>").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Draws an entry as {@link #textOf} writes it, and its IRI on hover. */
    private static final class Renderer extends DefaultTreeCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row, boolean focused) {
            Object user = value instanceof DefaultMutableTreeNode
                    ? ((DefaultMutableTreeNode) value).getUserObject() : value;
            super.getTreeCellRendererComponent(tree, user instanceof PropertyOutline.Entry
                    ? textOf((PropertyOutline.Entry) user) : String.valueOf(user), selected, expanded,
                    leaf, row, focused);
            setIcon(null);
            setToolTipText(user instanceof PropertyOutline.Entry
                    ? hoverOf((PropertyOutline.Entry) user) : null);
            return this;
        }
    }
}
