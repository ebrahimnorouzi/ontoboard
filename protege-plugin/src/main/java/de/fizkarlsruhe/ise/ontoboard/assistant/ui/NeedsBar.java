package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.NameResolver;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * What the model asked for (spec D7), as one click: "Add hasTopping and cheese topping, and ask
 * again". Names not in the ontology and names two terms share are listed and not added - the
 * person decides what goes to the model, never the model.
 */
public final class NeedsBar extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Adds the found terms and asks the same question again. */
    public interface OnAdd {
        void addAndAsk(List<NameResolver.Resolution> found);
    }

    private final OnAdd onAdd;

    public NeedsBar(OnAdd onAdd) {
        this.onAdd = onAdd;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        setVisible(false);
    }

    public void setNeeds(List<NameResolver.Resolution> resolutions) {
        removeAll();
        final List<NameResolver.Resolution> found = new ArrayList<NameResolver.Resolution>();
        List<String> missing = new ArrayList<String>();
        List<String> ambiguous = new ArrayList<String>();
        for (NameResolver.Resolution resolution : resolutions) {
            if (resolution.isFound()) {
                found.add(resolution);
            } else if (resolution.isAmbiguous()) {
                ambiguous.add(resolution.getName() + " ("
                        + String.join(", ", resolution.getCandidates()) + ")");
            } else {
                missing.add(resolution.getName());
            }
        }
        if (!found.isEmpty()) {
            List<String> labels = new ArrayList<String>();
            for (NameResolver.Resolution resolution : found) {
                labels.add(resolution.getLabel());
            }
            JButton add = new JButton("Add " + joined(labels) + ", and ask again");
            add.setToolTipText("The model asked to see these terms. They are added to the context"
                    + " and the same question is sent again.");
            add.addActionListener(event -> {
                setVisible(false);
                onAdd.addAndAsk(found);
            });
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            row.setOpaque(false);
            row.add(add);
            add(row);
        }
        if (!missing.isEmpty()) {
            add(muted("not in this ontology: " + String.join(", ", missing)));
        }
        if (!ambiguous.isEmpty()) {
            add(muted("ambiguous: " + String.join("; ", ambiguous)));
        }
        setVisible(!resolutions.isEmpty());
        revalidate();
        repaint();
    }

    private static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.GRAY);
        return label;
    }

    /** "a", "a and b", "a, b and c". */
    static String joined(List<String> labels) {
        if (labels.size() <= 1) {
            return labels.isEmpty() ? "" : labels.get(0);
        }
        return String.join(", ", labels.subList(0, labels.size() - 1)) + " and "
                + labels.get(labels.size() - 1);
    }
}
