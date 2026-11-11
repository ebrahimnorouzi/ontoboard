package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Shown;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Turn;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Reply;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheck;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The conversation (round 2, "The panel"): the person's messages on the right, the assistant's
 * on the left, each answer's proposals as cards under it, and why a turn ended early as a muted
 * line. The column follows the newest message while the person is at the bottom, and leaves
 * them where they are when they have scrolled up to read.
 */
final class ConversationPanel extends JPanel implements Scrollable {

    private static final long serialVersionUID = 1L;

    private final ProposalCard.Actions actions;
    private Bubble writing;
    private Turn writingTurn;

    ConversationPanel(ProposalCard.Actions actions) {
        super(new StackLayout(6));
        this.actions = actions;
        setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
        setOpaque(true);
        setBackground(Bubble.background());
        empty();
    }

    private void empty() {
        JTextArea hint = line("Ask about the selected term, or use ✦ on one of its lines. The"
                + " answer appears here; suggested changes come as cards to accept or reject.");
        add(hint);
    }

    /** Draws every turn again: after an answer, or when another ontology's session is shown. */
    void render(List<Turn> turns, Function<Proposal, ProposalCheck.Check> checks) {
        boolean follow = atBottom();
        removeAll();
        writing = null;
        writingTurn = null;
        if (turns.isEmpty()) {
            empty();
        }
        for (Turn turn : turns) {
            add(new Bubble(true, "You · " + time(turn.getStarted()), turn.getMessage()));
            String reply = turn.getReply();
            if (!reply.isEmpty() || !turn.isFinished()) {
                Bubble answer = new Bubble(false, meta(turn),
                        Shown.forReading(reply, !turn.isFinished()));
                add(answer);
                if (!turn.isFinished()) {
                    writing = answer;
                    writingTurn = turn;
                }
            }
            if (turn.getError() != null) {
                add(line(turn.getError()));
            }
            for (Proposal proposal : turn.getProposals()) {
                add(new ProposalCard(proposal, checks.apply(proposal), actions));
            }
        }
        changed(follow);
    }

    /** A new question, and the bubble its answer will be written into. */
    void startTurn(Turn turn) {
        boolean follow = true;
        if (getComponentCount() == 1 && !(getComponent(0) instanceof Bubble)
                && !(getComponent(0) instanceof ProposalCard)) {
            removeAll();
        }
        add(new Bubble(true, "You · " + time(turn.getStarted()), turn.getMessage()));
        writing = new Bubble(false, meta(turn), "…");
        writingTurn = turn;
        add(writing);
        changed(follow);
    }

    /** The answer so far, as it will read. */
    void appendDelta(Turn turn) {
        if (writing == null || writingTurn != turn) {
            return;
        }
        boolean follow = atBottom();
        String shown = Shown.forReading(turn.getReply(), true);
        writing.setText(shown.isEmpty() ? "…" : shown);
        changed(follow);
    }

    void proposalChanged(Proposal proposal) {
        for (ProposalCard card : cards()) {
            if (card.getProposal().getId() == proposal.getId()) {
                card.show(proposal);
            }
        }
        changed(false);
    }

    void proposalChecked(Proposal proposal, ProposalCheck.Check check) {
        for (ProposalCard card : cards()) {
            if (card.getProposal().getId() == proposal.getId()) {
                card.setCheck(check);
            }
        }
        changed(false);
    }

    private static JTextArea line(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        Font font = UIManager.getFont("Label.font");
        if (font != null) {
            area.setFont(font.deriveFont(Font.ITALIC));
        }
        area.setForeground(Bubble.muted());
        return area;
    }

    /** "qwen2.5:7b · 3.2 s · 1,204 → 96 tokens", or the model's name while it writes. */
    static String meta(Turn turn) {
        String model = turn.getModel();
        int space = model.indexOf(' ');
        String name = space >= 0 ? model.substring(space + 1) : model;
        if (name.isEmpty()) {
            name = turn.getConnectionName();
        }
        Reply info = turn.getReplyInfo();
        if (info == null) {
            return turn.isFinished() ? name : name + " · writing…";
        }
        StringBuilder out = new StringBuilder(name);
        if (info.getMillis() > 0) {
            out.append(String.format(Locale.ROOT, " · %.1f s", info.getMillis() / 1000.0));
        }
        if (info.getInputTokens() != Reply.UNKNOWN && info.getOutputTokens() != Reply.UNKNOWN) {
            out.append(String.format(Locale.ROOT, " · %,d → %,d tokens", info.getInputTokens(),
                    info.getOutputTokens()));
        }
        return out.toString();
    }

    private static String time(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(millis));
    }

    private boolean atBottom() {
        if (!(getParent() instanceof JViewport)) {
            return true;
        }
        Rectangle visible = ((JViewport) getParent()).getViewRect();
        return visible.y + visible.height >= getHeight() - 24;
    }

    private void changed(boolean follow) {
        revalidate();
        repaint();
        if (follow) {
            SwingUtilities.invokeLater(() -> scrollRectToVisible(
                    new Rectangle(0, Math.max(0, getHeight() - 1), 1, 1)));
        }
    }

    // ------------------------------------------------------------------ Scrollable

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 18;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(18, visible.height - 36);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    // ------------------------------------------------------------------ for tests

    int bubbleCount() {
        int count = 0;
        for (Component child : getComponents()) {
            if (child instanceof Bubble) {
                count++;
            }
        }
        return count;
    }

    List<ProposalCard> cards() {
        List<ProposalCard> found = new ArrayList<ProposalCard>();
        for (Component child : getComponents()) {
            if (child instanceof ProposalCard) {
                found.add((ProposalCard) child);
            }
        }
        return found;
    }

    /** Everything the conversation says, in order. */
    String text() {
        StringBuilder out = new StringBuilder();
        for (Component child : getComponents()) {
            if (child instanceof Bubble) {
                out.append(((Bubble) child).getText()).append('\n');
            } else if (child instanceof ProposalCard) {
                out.append(((ProposalCard) child).text());
            } else if (child instanceof JTextArea) {
                out.append(((JTextArea) child).getText()).append('\n');
            }
        }
        return out.toString();
    }
}
