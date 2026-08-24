package de.fizkarlsruhe.ise.ontoboard.canvas;

import de.fizkarlsruhe.ise.ontoboard.axiom.EntityFactory;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;

/**
 * The drag source for creating entities: drag "Class" or "Individual" onto the canvas and one
 * is created where it lands.
 *
 * <p>Dragging was the missing half of canvas editing. Everything was previously reachable
 * only through a right-click menu, which is slow for the repetitive act of sketching a
 * schema, and gave the canvas the feel of a viewer with commands bolted on.
 *
 * <p>The transferable is a plain string naming an {@link EntityFactory.Kind}. Swing's own
 * drag-and-drop is used rather than mxGraph's transfer machinery because the drop must create
 * an <em>OWL entity</em> through {@code OWLModelManager}, not insert a free-floating cell -
 * a cell with no axiom behind it would vanish on the next refresh.
 */
public final class PalettePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Identifies a palette drag; the payload is the {@link EntityFactory.Kind} name. */
    public static final DataFlavor KIND_FLAVOR =
            new DataFlavor(String.class, "OntoBoard entity kind");

    private static final class Item {
        private final EntityFactory.Kind kind;
        private final String label;
        private final String hint;

        Item(EntityFactory.Kind kind, String label, String hint) {
            this.kind = kind;
            this.label = label;
            this.hint = hint;
        }
    }

    public PalettePanel() {
        super(new java.awt.BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        DefaultListModel<Item> model = new DefaultListModel<Item>();
        model.addElement(new Item(EntityFactory.Kind.CLASS, "Class",
                "Drag onto the canvas to create an owl:Class"));
        model.addElement(new Item(EntityFactory.Kind.INDIVIDUAL, "Individual",
                "Drag onto the canvas to create a named individual"));

        final JList<Item> list = new JList<Item>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setDragEnabled(true);
        list.setVisibleRowCount(model.getSize());
        list.setCellRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> source, Object value,
                    int index, boolean selected, boolean focused) {
                JLabel rendered = (JLabel) super.getListCellRendererComponent(
                        source, value, index, selected, focused);
                Item item = (Item) value;
                rendered.setText(item.label);
                rendered.setToolTipText(item.hint);
                rendered.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
                return rendered;
            }
        });

        list.setTransferHandler(new TransferHandler() {
            private static final long serialVersionUID = 1L;

            @Override
            public int getSourceActions(javax.swing.JComponent source) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(javax.swing.JComponent source) {
                Item selected = list.getSelectedValue();
                if (selected == null) {
                    return null;
                }
                final String payload = selected.kind.name();
                return new Transferable() {
                    @Override
                    public DataFlavor[] getTransferDataFlavors() {
                        return new DataFlavor[] {KIND_FLAVOR, DataFlavor.stringFlavor};
                    }

                    @Override
                    public boolean isDataFlavorSupported(DataFlavor flavor) {
                        return KIND_FLAVOR.equals(flavor)
                                || DataFlavor.stringFlavor.equals(flavor);
                    }

                    @Override
                    public Object getTransferData(DataFlavor flavor)
                            throws UnsupportedFlavorException, IOException {
                        if (!isDataFlavorSupported(flavor)) {
                            throw new UnsupportedFlavorException(flavor);
                        }
                        return payload;
                    }
                };
            }
        });

        JLabel heading = new JLabel("Drag onto the canvas");
        heading.setBorder(BorderFactory.createEmptyBorder(0, 4, 6, 4));
        heading.setForeground(new Color(0x55, 0x60, 0x6B));

        add(heading, java.awt.BorderLayout.NORTH);
        add(new JScrollPane(list), java.awt.BorderLayout.CENTER);
        setPreferredSize(new Dimension(150, 120));
    }

    /**
     * Reads a palette drop back into a kind.
     *
     * @return the kind, or {@code null} when the payload did not come from this palette -
     *     callers must ignore unrecognised drops rather than creating a default entity
     */
    public static EntityFactory.Kind kindOf(String payload) {
        if (payload == null) {
            return null;
        }
        for (EntityFactory.Kind kind : EntityFactory.Kind.values()) {
            if (kind.name().equals(payload.trim())) {
                return kind;
            }
        }
        return null;
    }
}
