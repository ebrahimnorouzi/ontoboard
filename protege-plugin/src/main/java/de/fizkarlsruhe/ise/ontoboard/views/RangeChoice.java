package de.fizkarlsruhe.ise.ontoboard.views;

import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JOptionPane;

/**
 * "Which range is yours?": the choice among the ranges ODK seeds a project with.
 *
 * <p>A range's name has an underscore at its foot - RANGECHECK_0000000 - and the border Swing
 * draws around the chosen value when it has the focus was drawn over it (1.153.0's minor 21:
 * the value is as high as its text, and the border's line lies on the text's last row). The
 * value has room under its text now, so the border lies below the underscore.
 */
public final class RangeChoice {

    /** What is added under the text, and over it so the text stays in the middle. */
    private static final int BELOW = 2;
    private static final int ABOVE = 1;

    private RangeChoice() {
    }

    /** The choices, the first chosen, each shown with room under its text. */
    static JComboBox<String> combo(String[] choices) {
        JComboBox<String> combo = new JComboBox<String>(choices);
        combo.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                Component shown = super.getListCellRendererComponent(list, value, index,
                        isSelected, cellHasFocus);
                if (shown instanceof JComponent) {
                    JComponent label = (JComponent) shown;
                    label.setBorder(BorderFactory.createCompoundBorder(label.getBorder(),
                            BorderFactory.createEmptyBorder(ABOVE, 0, BELOW, 0)));
                }
                return shown;
            }
        });
        return combo;
    }

    /**
     * Asks which range is the person's: the question above the choice, OK and Cancel.
     *
     * @return the choice, or null when nothing was chosen
     */
    public static String ask(Component parent, String question, String[] choices) {
        JComboBox<String> combo = combo(choices);
        int answer = JOptionPane.showConfirmDialog(parent, new Object[] {question, combo},
                "Which range is yours?", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        return answer == JOptionPane.OK_OPTION ? (String) combo.getSelectedItem() : null;
    }
}
