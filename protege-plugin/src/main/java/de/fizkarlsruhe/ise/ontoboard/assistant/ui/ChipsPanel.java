package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextItem;
import de.fizkarlsruhe.ise.ontoboard.canvas.WrapLayout;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import org.semanticweb.owlapi.model.IRI;

/**
 * The context row (spec D3): one chip per thing the next request tells the model about, each
 * with its estimated size, and the ways to add more - the selected term, a term by name, the
 * class hierarchy or the whole ontology, the last two with their size shown before they are
 * added. The chip that follows the selection has no ×: it changes when the selection does.
 */
public final class ChipsPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** What the row asks the session to do. */
    public interface Actions {
        void remove(ContextItem item);

        void add(ContextItem item);

        /** Every chip but the one that follows the selection. */
        void removeAllChips();
    }

    private final AssistantPanel.Host host;
    private final Actions actions;
    private final JPanel chips = new ChipRows();
    private final JTextField name = new JTextField(12);
    private final JButton removeAll = new JButton("Remove all");
    private int removeButtons;

    public ChipsPanel(AssistantPanel.Host host, Actions actions) {
        super(new BorderLayout(0, 2));
        this.host = host;
        this.actions = actions;
        chips.setOpaque(false);
        // Two rows of chips, then they scroll: each row took its height from the conversation,
        // where answers and proposal cards appear (1.145.0, review 1.144.0).
        JScrollPane rows = new JScrollPane(chips, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        rows.setBorder(BorderFactory.createEmptyBorder());
        rows.setOpaque(false);
        rows.getViewport().setOpaque(false);
        add(rows, BorderLayout.CENTER);

        JButton addSelected = new JButton("Add selected");
        addSelected.setToolTipText("Keep the selected term in the context when the selection"
                + " moves on.");
        addSelected.addActionListener(event -> {
            IRI selected = host.selectedIri();
            if (selected != null) {
                actions.add(ContextItem.term(selected, false));
            }
        });
        name.setToolTipText("Type part of a term's name, then pick it to add it to the context.");
        name.addActionListener(event -> suggest());
        JButton more = new JButton("More ▾");
        more.setToolTipText("Send more of the ontology. The size is shown before anything is"
                + " added.");
        more.addActionListener(event -> scopes(more));
        removeAll.setToolTipText("Remove every chip but the selected term.");
        removeAll.setEnabled(false);
        removeAll.addActionListener(event -> actions.removeAllChips());

        JPanel controls = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
        controls.setOpaque(false);
        controls.add(new JLabel("Context:"));
        controls.add(addSelected);
        controls.add(name);
        controls.add(more);
        controls.add(removeAll);
        add(controls, BorderLayout.SOUTH);
    }

    public void setItems(List<ContextItem> items) {
        chips.removeAll();
        removeButtons = 0;
        for (final ContextItem item : items) {
            JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
            chip.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1, true),
                    BorderFactory.createEmptyBorder(1, 4, 1, 2)));
            JLabel label = new JLabel(host.titleOf(item));
            // The whole name, too: a long one is shortened to keep the chip's x in reach.
            label.setToolTipText(host.titleOf(item) + ": " + (item.isFollowingSelection()
                    ? "the selected term and its neighbours. It follows the selection."
                    : "sent with every question until you remove it."));
            chip.add(label);
            JLabel size = new JLabel("~" + host.sizeOf(item));
            size.setForeground(Color.GRAY);
            size.setToolTipText("Estimated tokens: a quarter of the characters.");
            chip.add(size);
            if (!item.isFollowingSelection()) {
                JButton remove = new JButton("×");
                remove.setMargin(new Insets(0, 3, 0, 3));
                remove.setBorderPainted(false);
                remove.setContentAreaFilled(false);
                remove.setToolTipText("Remove from the context");
                remove.addActionListener(event -> actions.remove(item));
                chip.add(remove);
                removeButtons++;
            }
            chips.add(chip);
        }
        removeAll.setEnabled(removeButtons > 0);
        chips.revalidate();
        chips.repaint();
        revalidate();
    }

    /**
     * A new width drops the sizes the chips' viewport and rows measured at the old one. The
     * column asks for this panel's height before the scroll pane is laid out again, and Swing
     * answered from those sizes: after one resize - Protégé maximised or restored - the chips kept
     * the old width's height, so the last ones and their × could not be scrolled to, or the bar
     * scrolled into blank space (1.146.0, review 1.145.0 ui-1).
     */
    @Override
    public void setBounds(int x, int y, int width, int height) {
        if (width != getWidth()) {
            chips.invalidate();
        }
        super.setBounds(x, y, width, height);
    }

    /** How many chips can be removed, for tests. */
    int removeButtonCount() {
        return removeButtons;
    }

    private void suggest() {
        String typed = name.getText().trim();
        if (typed.isEmpty()) {
            return;
        }
        List<AssistantPanel.Candidate> candidates = host.complete(typed);
        JPopupMenu menu = new JPopupMenu();
        if (candidates.isEmpty()) {
            JMenuItem none = new JMenuItem("No term in this ontology matches \"" + typed + "\"");
            none.setEnabled(false);
            menu.add(none);
        }
        for (final AssistantPanel.Candidate candidate : candidates) {
            JMenuItem item = new JMenuItem(candidate.toString());
            item.addActionListener(event -> {
                actions.add(ContextItem.term(candidate.getIri(), false));
                name.setText("");
            });
            menu.add(item);
        }
        menu.show(name, 0, name.getHeight());
    }

    private void scopes(JButton anchor) {
        JPopupMenu menu = new JPopupMenu();
        final ContextItem hierarchy = ContextItem.hierarchy();
        final ContextItem whole = ContextItem.wholeOntology();
        JMenuItem tree = new JMenuItem("Class hierarchy, as labels (~" + host.sizeOf(hierarchy)
                + " tokens)");
        tree.addActionListener(event -> actions.add(hierarchy));
        JMenuItem all = new JMenuItem("Whole ontology (~" + host.sizeOf(whole) + " tokens)");
        all.addActionListener(event -> actions.add(whole));
        menu.add(tree);
        menu.add(all);
        menu.show(anchor, 0, anchor.getHeight());
    }

    /** The chips, wrapped at the row's width, and shown two rows high at most. */
    private static final class ChipRows extends JPanel implements Scrollable {

        private static final long serialVersionUID = 1L;
        private static final int ROWS_SHOWN = 2;
        private static final int VGAP = 2;

        private static final int HGAP = 4;
        /** The narrowest a shortened name gets: a letter or two and the dots. */
        private static final int NAME_AT_LEAST = 24;

        ChipRows() {
            super(new WrapLayout(FlowLayout.LEFT, HGAP, VGAP));
        }

        /**
         * A chip wider than the row - a long term name, or the scroll bar beside three rows -
         * had its x outside the row, where nobody can reach it: its name is shortened to fit
         * instead, and says itself in full on hover (1.147.0, review 1.145.0 ui-5).
         */
        @Override
        public void doLayout() {
            Insets insets = getInsets();
            int room = getWidth() - insets.left - insets.right - 2 * HGAP;
            for (Component chip : getComponents()) {
                if (!(chip instanceof JPanel) || ((JPanel) chip).getComponentCount() == 0
                        || !(((JPanel) chip).getComponent(0) instanceof JLabel)) {
                    continue;
                }
                JLabel name = (JLabel) ((JPanel) chip).getComponent(0);
                name.setPreferredSize(null);
                chip.invalidate();
                int over = chip.getPreferredSize().width - room;
                if (room > 0 && over > 0) {
                    Dimension full = name.getPreferredSize();
                    name.setPreferredSize(new Dimension(
                            Math.max(NAME_AT_LEAST, full.width - over), full.height));
                    chip.invalidate();
                }
            }
            super.doLayout();
        }

        private int row() {
            int tallest = 0;
            for (Component chip : getComponents()) {
                tallest = Math.max(tallest, chip.getPreferredSize().height);
            }
            return tallest + VGAP;
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            Dimension all = getPreferredSize();
            Insets insets = getInsets();
            int shown = ROWS_SHOWN * row() + VGAP + insets.top + insets.bottom;
            return new Dimension(all.width, Math.min(all.height, shown));
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? Math.max(1, row()) : 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
