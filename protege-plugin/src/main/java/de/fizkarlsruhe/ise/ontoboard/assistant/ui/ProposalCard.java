package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheck;
import de.fizkarlsruhe.ise.ontoboard.canvas.WrapLayout;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * One proposal, as the person decides on it: what would change (the old value struck through,
 * the new one under it), why, what the reasoner says about a removal, and Accept, Reject or
 * Revise…. A card that cannot be applied says why and offers Revise…; decided cards stay in the
 * conversation, marked.
 */
final class ProposalCard extends JPanel implements StackLayout.Measured {

    private static final long serialVersionUID = 1L;
    private static final Color GOOD = new Color(0x2e, 0x7d, 0x32);
    private static final Color WARN = new Color(0x9a, 0x4f, 0x00);

    /** What the buttons do; the panel passes them to the session. */
    interface Actions {
        void accept(Proposal proposal);

        void reject(Proposal proposal);

        /** @return whether the request was sent */
        boolean revise(Proposal proposal, String request);
    }

    private final Actions actions;
    private Proposal proposal;
    private ProposalCheck.Check check;

    private final JLabel title = new JLabel();
    private final JLabel badge = new JLabel();
    private final JTextPane change = new JTextPane();
    private final JTextArea reason = area();
    private final JTextArea notes = area();
    private final JTextArea verdict = area();
    private final JTextArea problem = area();
    private final JButton accept = new JButton("Accept");
    private final JButton reject = new JButton("Reject");
    private final JButton revise = new JButton("Revise…");
    private final JPanel buttons = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 1));
    private final JPanel reviseRow = new JPanel(new BorderLayout(4, 0));
    private final JTextField reviseText = new JTextField();
    private int measuredWidth = -1;
    private int measuredHeight;

    ProposalCard(Proposal proposal, ProposalCheck.Check check, Actions actions) {
        super(new StackLayout(3));
        this.actions = actions;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 10));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        change.setEditable(false);
        change.setOpaque(false);
        change.setBorder(BorderFactory.createEmptyBorder());
        reason.setFont(reason.getFont().deriveFont(Font.ITALIC));
        reason.setForeground(Bubble.muted());
        problem.setForeground(WARN);

        accept.setToolTipText("Apply it to the ontology: one change, undone with Edit > Undo.");
        accept.addActionListener(event -> actions.accept(this.proposal));
        reject.setToolTipText("Decline it. Nothing changes.");
        reject.addActionListener(event -> actions.reject(this.proposal));
        revise.setToolTipText("Ask for a different version, in your words.");
        revise.addActionListener(event -> {
            reviseRow.setVisible(true);
            reviseText.requestFocusInWindow();
            changed();
        });
        buttons.setOpaque(false);
        buttons.add(accept);
        buttons.add(reject);
        buttons.add(revise);

        JButton send = new JButton("Send");
        JButton cancel = new JButton("Cancel");
        send.addActionListener(event -> sendRevision());
        reviseText.addActionListener(event -> sendRevision());
        reviseText.setToolTipText("What should be different? Enter sends it.");
        cancel.addActionListener(event -> {
            reviseRow.setVisible(false);
            changed();
        });
        JPanel reviseButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        reviseButtons.setOpaque(false);
        reviseButtons.add(send);
        reviseButtons.add(cancel);
        reviseRow.setOpaque(false);
        reviseRow.add(reviseText, BorderLayout.CENTER);
        reviseRow.add(reviseButtons, BorderLayout.EAST);
        reviseRow.setVisible(false);

        JPanel head = new JPanel(new BorderLayout(6, 0));
        head.setOpaque(false);
        head.add(title, BorderLayout.CENTER);
        head.add(badge, BorderLayout.EAST);
        add(head);
        add(change);
        add(reason);
        add(notes);
        add(verdict);
        add(problem);
        add(buttons);
        add(reviseRow);
        this.check = check;
        show(proposal);
    }

    private static JTextArea area() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        Font font = UIManager.getFont("Label.font");
        if (font != null) {
            area.setFont(font);
        }
        return area;
    }

    Proposal getProposal() {
        return proposal;
    }

    /** Shows the proposal in its current status. */
    void show(Proposal next) {
        this.proposal = next;
        title.setText(next.describe());
        Proposal.Status status = next.getStatus();
        badge.setText(status == Proposal.Status.ACCEPTED ? "Accepted ✓"
                : status == Proposal.Status.REJECTED ? "Rejected"
                        : status == Proposal.Status.REVISED ? "Revision asked"
                                : status == Proposal.Status.INVALID ? "Cannot be applied" : "");
        badge.setForeground(status == Proposal.Status.ACCEPTED ? GOOD
                : status == Proposal.Status.INVALID ? WARN : Bubble.muted());
        writeChange(next);
        String why = next.getReason() == null ? "" : next.getReason().trim();
        reason.setText(why.isEmpty() ? "" : "Why: " + why);
        reason.setVisible(!why.isEmpty());
        notes.setText(String.join("\n", next.getNotes()));
        notes.setForeground(WARN);
        notes.setVisible(!next.getNotes().isEmpty() && status != Proposal.Status.INVALID);
        problem.setText(next.getProblem() == null ? "" : next.getProblem());
        problem.setVisible(status == Proposal.Status.INVALID);
        boolean open = status == Proposal.Status.OFFERED;
        accept.setVisible(open);
        reject.setVisible(open || status == Proposal.Status.INVALID);
        reject.setText(open ? "Reject" : "Dismiss");
        revise.setVisible(open || status == Proposal.Status.INVALID);
        buttons.setVisible(accept.isVisible() || reject.isVisible() || revise.isVisible());
        if (!revise.isVisible()) {
            reviseRow.setVisible(false);
        }
        showCheck();
        changed();
    }

    void setCheck(ProposalCheck.Check next) {
        this.check = next;
        showCheck();
        changed();
    }

    private void showCheck() {
        boolean logic = proposal.getAction() != null && proposal.getAction().isLogic()
                && proposal.getStatus() != Proposal.Status.INVALID;
        verdict.setVisible(logic);
        if (!logic) {
            return;
        }
        if (check == null) {
            verdict.setText("Checking with the reasoner, on a copy…");
            verdict.setForeground(Bubble.muted());
        } else {
            verdict.setText(check.describe());
            // Green when it solves the problem, or when it changes a sound ontology and breaks
            // nothing; amber otherwise.
            boolean soundBefore = check.isConsistentBefore()
                    && check.getUnsatisfiableBefore().isEmpty();
            verdict.setForeground(check.isSolved() || (soundBefore && check.isSafe()) ? GOOD
                    : WARN);
        }
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }

    private void writeChange(Proposal next) {
        StyledDocument document = change.getStyledDocument();
        SimpleAttributeSet old = new SimpleAttributeSet();
        StyleConstants.setStrikeThrough(old, true);
        StyleConstants.setForeground(old, Bubble.muted());
        SimpleAttributeSet added = new SimpleAttributeSet();
        String language = next.getLanguage() == null ? "" : "  @" + next.getLanguage();
        try {
            document.remove(0, document.getLength());
            // As Accept writes them, under the ontology's names (review 1.139.0 I1).
            if (next.getAction() == Proposal.Action.REMOVE_AXIOM) {
                document.insertString(0, nonNull(next.getShownAxiom()), old);
            } else if (next.getAction() == Proposal.Action.ADD_AXIOM) {
                document.insertString(0, nonNull(next.getShownAxiom()), added);
            } else if (next.getAction() == Proposal.Action.REPLACE_AXIOM) {
                document.insertString(0, nonNull(next.getShownAxiom()) + "\n", old);
                document.insertString(document.getLength(), nonNull(next.getShownWith()),
                        added);
            } else {
                for (String was : next.getReplaced()) {
                    document.insertString(document.getLength(), was + "\n", old);
                }
                document.insertString(document.getLength(), next.getValueText() + language,
                        added);
            }
        } catch (BadLocationException impossible) {
            throw new IllegalStateException(impossible);
        }
        change.setVisible(document.getLength() > 0);
    }

    private void sendRevision() {
        String typed = reviseText.getText().trim();
        if (typed.isEmpty()) {
            return;
        }
        // Kept when not sent - while an answer is still coming - so nothing typed is lost.
        if (actions.revise(proposal, typed)) {
            reviseText.setText("");
            reviseRow.setVisible(false);
            reviseText.setToolTipText("What should be different? Enter sends it.");
        } else {
            // Said on the card, not only in a tooltip (1.138.0 R10); the session's own notice
            // says why when it refused for another reason.
            problem.setText("Not sent - your text is kept. If an answer is still coming, send"
                    + " it again when it has finished.");
            problem.setVisible(true);
        }
        changed();
    }

    private void changed() {
        measuredWidth = -1;
        revalidate();
        repaint();
    }

    @Override
    public int heightAt(int width) {
        if (width == measuredWidth) {
            return measuredHeight;
        }
        setSize(width, Math.max(getHeight(), 1));
        measuredHeight = getLayout().preferredLayoutSize(this).height;
        measuredWidth = width;
        return measuredHeight;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color accent = proposal.getStatus() == Proposal.Status.ACCEPTED ? GOOD
                    : proposal.getStatus() == Proposal.Status.INVALID ? WARN
                            : UIManager.getColor("List.selectionBackground") == null
                                    ? new Color(0x2f, 0x6f, 0xd0)
                                    : UIManager.getColor("List.selectionBackground");
            g.setColor(Bubble.blend(accent, Bubble.background(), 0.08));
            g.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 12, 12);
            g.setColor(Bubble.blend(accent, Bubble.background(), 0.55));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 12, 12);
            g.fillRect(1, 8, 3, getHeight() - 16);
        } finally {
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ for tests

    JButton acceptButton() {
        return accept;
    }

    JButton reviseButton() {
        return revise;
    }

    JTextField reviseField() {
        return reviseText;
    }

    /** Everything the card says, in order. */
    String text() {
        StringBuilder out = new StringBuilder();
        for (JComponent part : new JComponent[] {title, badge}) {
            out.append(((JLabel) part).getText()).append('\n');
        }
        out.append(change.getText()).append('\n');
        for (JTextArea area : new JTextArea[] {reason, notes, verdict, problem}) {
            if (area.isVisible()) {
                out.append(area.getText()).append('\n');
            }
        }
        return out.toString();
    }
}
