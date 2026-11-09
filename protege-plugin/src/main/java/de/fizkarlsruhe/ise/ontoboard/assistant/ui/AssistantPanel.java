package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.NameResolver;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Turn;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextItem;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.TermLine;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheck;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.AssistantSession;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.SessionListener;
import de.fizkarlsruhe.ise.ontoboard.canvas.WrapLayout;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import org.semanticweb.owlapi.model.IRI;

/**
 * The assistant column (spec D6, the B mockup; round 2, "The panel"), top to bottom: the selected
 * term, where questions go, and one-click requests; the term's lines, each with a ✦ menu; the
 * conversation as bubbles with proposal cards; what the model asked for; the context chips; what
 * is happening now; and the input.
 *
 * <p>Swing only: everything about Protégé comes through {@link Host}, so the panel is built and
 * tested without it. The ontology changes only when the person presses Accept on a card.
 */
public final class AssistantPanel extends JPanel implements SessionListener {

    private static final long serialVersionUID = 1L;

    /** The annotation lines a model may reword: what a {@code set} proposal can replace. */
    private static final List<String> REWORDABLE = Arrays.asList("label", "definition",
            "comment");

    /** What the panel needs from the editor. Called on the event thread. */
    public interface Host {
        List<TermLine> lines();

        /** The selected term's label, or a sentence saying nothing is selected. */
        String termTitle();

        boolean selectedIsUnsatisfiable();

        /** "Why is this unsatisfiable?" for the selected class. */
        void explainSelected();

        /** "Explain the reasoner problem…": inconsistency or an unsatisfiable class. */
        void explainProblem();

        /** Terms whose name contains what was typed, for adding to the context by name. */
        List<Candidate> complete(String prefix);

        IRI selectedIri();

        void openSettings();

        /** Where questions go, in words: "Claude · api.anthropic.com (cloud)". */
        String destination();

        String titleOf(ContextItem item);

        /** Estimated tokens the item adds. */
        int sizeOf(ContextItem item);

        void saveConversation();

        void showWhatIsSent(String draft);
    }

    /** A term offered when adding to the context by name: the person picks, nothing is guessed. */
    public static final class Candidate {
        private final IRI iri;
        private final String label;
        private final String kind;

        public Candidate(IRI iri, String label, String kind) {
            this.iri = iri;
            this.label = label;
            this.kind = kind;
        }

        public IRI getIri() {
            return iri;
        }

        @Override
        public String toString() {
            return label + (kind == null || kind.isEmpty() ? "" : " (" + kind + ")");
        }
    }

    /** The lines list, as wide as the column so that long axioms wrap instead of scrolling. */
    private static final class LinesPanel extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        LinesPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return visible.height;
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

    private final Host host;
    private AssistantSession session;

    private final JLabel title = new JLabel(" ");
    private final JTextArea info = new JTextArea(" ");
    private final JTextArea notice = new JTextArea();
    private final JButton suggestDefinition = new JButton("Suggest a definition");
    private final JButton writeAsOwl = new JButton("Write as OWL…");
    /** Asks the person for the sentence; a dialog, replaced in tests. */
    private java.util.function.BiFunction<String, String, String> sentenceAsker =
            (subject, start) -> (String) javax.swing.JOptionPane.showInputDialog(this,
                    "A sentence about " + subject
                            + ", for example \"Every … has at least one …\":", "Write as OWL",
                    javax.swing.JOptionPane.QUESTION_MESSAGE, null, null, start);
    /** A sentence refused before it was sent; the dialog starts from it next time. */
    private String unsentSentence = "";
    private final JButton whyUnsatisfiable = new JButton("Why is this unsatisfiable?");
    private final LinesPanel lines = new LinesPanel();
    private final List<JButton> sparks = new ArrayList<JButton>();
    private final List<JPopupMenu> menus = new ArrayList<JPopupMenu>();
    private final ConversationPanel conversation;
    private final NeedsBar needs;
    private final ChipsPanel chips;
    private final JTextArea input = new JTextArea(3, 20);
    private final JButton send = new JButton("Send");
    private final JButton stop = new JButton("Stop");
    private final StatusLine status = new StatusLine(stop);

    public AssistantPanel(Host host) {
        super(new BorderLayout(0, 4));
        this.host = host;
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        conversation = new ConversationPanel(new ProposalCard.Actions() {
            @Override
            public void accept(Proposal proposal) {
                if (session != null) {
                    session.accept(proposal);
                }
            }

            @Override
            public void reject(Proposal proposal) {
                if (session != null) {
                    session.reject(proposal);
                }
            }

            @Override
            public boolean revise(Proposal proposal, String request) {
                return session != null && session.revise(proposal, request);
            }
        });
        needs = new NeedsBar(found -> {
            if (session != null) {
                session.addAndAskAgain(found);
            }
        });
        chips = new ChipsPanel(host, new ChipsPanel.Actions() {
            @Override
            public void remove(ContextItem item) {
                if (session != null) {
                    session.removeContext(item);
                }
            }

            @Override
            public void add(ContextItem item) {
                if (session != null) {
                    session.addContext(item);
                }
            }

            @Override
            public void removeAllChips() {
                if (session != null) {
                    session.removeAllContext();
                }
            }
        });
        add(header(), BorderLayout.NORTH);
        JScrollPane linesScroll = new JScrollPane(lines);
        linesScroll.setBorder(BorderFactory.createEmptyBorder());
        JScrollPane conversationScroll = new JScrollPane(conversation);
        conversationScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0,
                Bubble.blend(Color.GRAY, Bubble.background(), 0.35)));
        conversationScroll.getVerticalScrollBar().setUnitIncrement(18);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, linesScroll,
                conversationScroll);
        split.setResizeWeight(0.3);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        updateButtons();
    }

    private JComponent header() {
        // Stacked at the column's width: the destination line wraps instead of running off the
        // edge, and a long term name ends in "…".
        JPanel header = new JPanel(new StackLayout(2));
        header.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize() + 2f));
        info.setEditable(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        info.setOpaque(false);
        info.setBorder(BorderFactory.createEmptyBorder());
        info.setForeground(Bubble.muted());
        info.setFont(title.getFont().deriveFont(Font.PLAIN, title.getFont().getSize() - 3f));
        suggestDefinition.setToolTipText("Asks for a definition in genus-differentia form, from"
                + " the term's parent, as a card to accept or reject.");
        suggestDefinition.addActionListener(event -> {
            if (session != null) {
                session.suggestDefinition();
            }
        });
        writeAsOwl.setToolTipText("Write a sentence about the term; the assistant answers with"
                + " the OWL as a card, checked by the reasoner before you accept it.");
        writeAsOwl.addActionListener(event -> {
            if (session != null) {
                String sentence = sentenceAsker.apply(host.termTitle(), unsentSentence);
                if (sentence != null && !sentence.trim().isEmpty()) {
                    int before = session.turns().size();
                    session.writeAsOwl(sentence);
                    unsentSentence = session.turns().size() == before ? sentence : "";
                }
            }
        });
        JButton problem = new JButton("Explain the reasoner problem…");
        problem.setToolTipText("Runs the reasoner Protégé uses: an inconsistency, or a class that"
                + " can have no instances, is explained from its justification. A repair comes as"
                + " a card, checked by the reasoner before you accept it.");
        problem.addActionListener(event -> host.explainProblem());
        whyUnsatisfiable.addActionListener(event -> host.explainSelected());
        notice.setEditable(false);
        notice.setLineWrap(true);
        notice.setWrapStyleWord(true);
        notice.setOpaque(false);
        notice.setFont(info.getFont());
        notice.setForeground(new Color(0x9a, 0x4f, 0x00));
        notice.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        notice.setAlignmentX(LEFT_ALIGNMENT);
        notice.setVisible(false);

        header.add(title);
        header.add(info);
        header.add(left(suggestDefinition, writeAsOwl, problem, whyUnsatisfiable));
        header.add(notice);
        return header;
    }

    private JComponent footer() {
        // Width first, then height, as the header: a BoxLayout asked the button row for its
        // height at its old width, and after the column narrowed the second row was cut off.
        JPanel footer = new JPanel(new StackLayout(2));
        needs.setAlignmentX(LEFT_ALIGNMENT);
        chips.setAlignmentX(LEFT_ALIGNMENT);
        status.setAlignmentX(LEFT_ALIGNMENT);
        footer.add(needs);
        footer.add(chips);
        footer.add(status);

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setToolTipText("Ask about the selected term or the ontology. Ctrl+Enter sends.");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
                InputEvent.CTRL_DOWN_MASK), "send");
        input.getActionMap().put("send", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                sendInput();
            }
        });
        send.setToolTipText("Send the question (Ctrl+Enter).");
        send.setFont(send.getFont().deriveFont(Font.BOLD));
        send.addActionListener(event -> sendInput());
        stop.setToolTipText("Stop the answer; what has arrived stays.");
        stop.addActionListener(event -> {
            if (session != null) {
                session.stop();
            }
        });
        stop.setVisible(false);
        JButton clear = new JButton("Clear");
        clear.setToolTipText("Forget this conversation.");
        clear.addActionListener(event -> {
            if (session != null) {
                session.clear();
                conversation.render(session.turns(), session::checkOf);
                needs.setNeeds(Collections.<NameResolver.Resolution>emptyList());
                status.setStatus(" ", false);
            }
        });
        JButton save = new JButton("Save…");
        save.setToolTipText("Save the conversation as Markdown.");
        save.addActionListener(event -> host.saveConversation());
        // Beside Send, where a person looks before sending: in 1.136.0 it sat at the end of the
        // header's info line, which a FlowLayout wrapped out of sight in a narrow column.
        JButton whatIsSent = new JButton("What is sent");
        whatIsSent.setToolTipText("The exact text of the next request, before it is sent.");
        whatIsSent.addActionListener(event -> host.showWhatIsSent(input.getText()));
        JScrollPane inputScroll = new JScrollPane(input);
        inputScroll.setAlignmentX(LEFT_ALIGNMENT);
        footer.add(inputScroll);
        footer.add(left(send, whatIsSent, clear, save));
        return footer;
    }

    /** A row that wraps onto as many lines as the column needs, and is as tall as they are. */
    private static JComponent left(JComponent... parts) {
        JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 1));
        for (JComponent part : parts) {
            row.add(part);
        }
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    /** Listens to this session from now on, and shows its conversation. */
    public void showSession(AssistantSession next) {
        if (session != null) {
            session.removeListener(this);
        }
        session = next;
        if (session != null) {
            session.addListener(this);
            conversation.render(session.turns(), session::checkOf);
        }
        needs.setNeeds(Collections.<NameResolver.Resolution>emptyList());
        status.setStatus(" ", false);
        refresh();
    }

    /** The selected term, its lines, the context and the size: everything that follows it. */
    public void refresh() {
        title.setText(host.termTitle());
        refreshInfo();
        whyUnsatisfiable.setVisible(host.selectedIsUnsatisfiable());
        List<TermLine> termLines = host.lines();
        boolean hasDefinition = false;
        for (TermLine line : termLines) {
            hasDefinition |= "definition".equals(line.getKey());
        }
        suggestDefinition.setText(hasDefinition ? "Suggest a definition" : "Write a definition");
        suggestDefinition.setVisible(host.selectedIri() != null);
        writeAsOwl.setVisible(host.selectedIri() != null);
        rebuildLines(termLines);
        if (session != null) {
            chips.setItems(session.context());
        }
        updateButtons();
    }

    private void refreshInfo() {
        String size = "";
        if (session != null) {
            try {
                size = " · ~" + session.preview(input.getText()).getEstimatedTokens()
                        + " tokens";
            } catch (RuntimeException unrenderable) {
                size = "";
            }
        }
        String role = session == null ? "" : session.getRoleName() + " · ";
        info.setText(role + host.destination() + size);
        info.setToolTipText(info.getText());
    }

    private void rebuildLines(List<TermLine> termLines) {
        lines.removeAll();
        sparks.clear();
        menus.clear();
        TermLine.Group group = null;
        for (final TermLine line : termLines) {
            if (line.getGroup() != group) {
                group = line.getGroup();
                JLabel heading = new JLabel(group == TermLine.Group.ANNOTATION ? "Annotations"
                        : "Semantics");
                heading.setForeground(Bubble.muted());
                heading.setFont(heading.getFont().deriveFont(Font.BOLD,
                        heading.getFont().getSize() - 1f));
                heading.setBorder(BorderFactory.createEmptyBorder(4, 2, 1, 2));
                heading.setAlignmentX(LEFT_ALIGNMENT);
                lines.add(heading);
            }
            lines.add(row(line));
        }
        if (sparks.isEmpty()) {
            JLabel nothing = new JLabel(host.selectedIri() == null
                    ? "Select a term to see its lines here." : "This term says nothing yet.");
            nothing.setForeground(Bubble.muted());
            lines.add(nothing);
        }
        lines.add(Box.createVerticalGlue());
        lines.revalidate();
        lines.repaint();
    }

    private JComponent row(final TermLine line) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        JLabel key = new JLabel(line.getKey());
        key.setForeground(Bubble.muted());
        key.setVerticalAlignment(JLabel.TOP);
        key.setPreferredSize(new Dimension(86, key.getPreferredSize().height));
        JTextArea text = new JTextArea(line.getText());
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setBorder(BorderFactory.createEmptyBorder());
        text.setFont(key.getFont());

        final JPopupMenu menu = new JPopupMenu();
        JMenuItem ask = new JMenuItem("Ask about this line");
        ask.addActionListener(event -> {
            if (session != null) {
                session.askAboutLine(line);
            }
        });
        menu.add(ask);
        if (line.getGroup() == TermLine.Group.ANNOTATION
                && REWORDABLE.contains(line.getKey())) {
            JMenuItem wording = new JMenuItem("Suggest a better wording");
            wording.addActionListener(event -> {
                if (session != null) {
                    session.suggestWording(line);
                }
            });
            menu.add(wording);
        }
        if (line.getGroup() == TermLine.Group.SEMANTICS) {
            JMenuItem explain = new JMenuItem("Explain this axiom");
            explain.addActionListener(event -> {
                if (session != null) {
                    session.explainLine(line);
                }
            });
            menu.add(explain);
            JMenuItem better = new JMenuItem("Suggest a better axiom");
            better.addActionListener(event -> {
                if (session != null) {
                    session.suggestBetterAxiom(line);
                }
            });
            menu.add(better);
        }
        final JButton spark = new JButton(new SparkIcon());
        spark.setMargin(new java.awt.Insets(1, 3, 1, 3));
        spark.setToolTipText("Ask the assistant about this line");
        spark.addActionListener(event -> menu.show(spark, 0, spark.getHeight()));
        sparks.add(spark);
        menus.add(menu);

        JPanel east = new JPanel(new BorderLayout());
        east.add(spark, BorderLayout.NORTH);
        row.add(key, BorderLayout.WEST);
        row.add(text, BorderLayout.CENTER);
        row.add(east, BorderLayout.EAST);
        row.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        return row;
    }

    private void sendInput() {
        String text = input.getText().trim();
        if (session == null || text.isEmpty()) {
            return;
        }
        showNotice(null);
        int before = session.turns().size();
        session.send(text);
        if (session.turns().size() > before) {
            input.setText("");
        }
    }

    private void updateButtons() {
        boolean busy = session != null && session.isBusy();
        send.setEnabled(session != null && !busy);
        // Also while proposals are being checked: Stop skips the checks not yet started.
        stop.setVisible(busy || status.isWorking());
        suggestDefinition.setEnabled(session != null && !busy);
        // A sentence typed while an answer is coming was dropped (review 1.139.0 M6).
        writeAsOwl.setEnabled(session != null && !busy);
    }

    private void showNotice(String message) {
        boolean shown = message != null && !message.trim().isEmpty();
        notice.setText(shown ? message : "");
        notice.setVisible(shown);
        revalidate();
    }

    /** For tests: the sentence "Write as OWL…" would ask for. */
    void setSentenceAsker(java.util.function.BiFunction<String, String, String> asker) {
        this.sentenceAsker = asker;
    }

    /** For tests: how many lines have a ✦ button. */
    int sparkButtonCount() {
        return sparks.size();
    }

    /** For tests: the ✦ menu of one line. */
    JPopupMenu menuForLine(int index) {
        return menus.get(index);
    }

    /** For tests: the conversation as shown. */
    String transcriptText() {
        return conversation.text();
    }

    /** For tests. */
    int bubbleCount() {
        return conversation.bubbleCount();
    }

    /** For tests. */
    List<ProposalCard> cards() {
        return conversation.cards();
    }

    /** For tests. */
    String statusText() {
        return status.getText();
    }

    @Override
    public void onTurnStarted(Turn turn) {
        conversation.startTurn(turn);
        needs.setNeeds(Collections.<NameResolver.Resolution>emptyList());
        showNotice(null);
        updateButtons();
    }

    @Override
    public void onText(Turn turn, String delta) {
        conversation.appendDelta(turn);
    }

    @Override
    public void onTurnFinished(Turn turn) {
        if (session != null) {
            conversation.render(session.turns(), session::checkOf);
        }
        updateButtons();
        refreshInfo();
    }

    @Override
    public void onProposalChanged(Proposal proposal) {
        conversation.proposalChanged(proposal);
    }

    @Override
    public void onProposalChecked(Proposal proposal, ProposalCheck.Check check) {
        conversation.proposalChecked(proposal, check);
    }

    @Override
    public void onStatus(String text, boolean working) {
        status.setStatus(text, working);
        updateButtons();
    }

    @Override
    public void onContextChanged(List<ContextItem> context) {
        chips.setItems(context);
        refreshInfo();
    }

    @Override
    public void onNeeds(List<NameResolver.Resolution> resolutions) {
        needs.setNeeds(resolutions);
    }

    @Override
    public void onNotice(String message) {
        showNotice(message);
    }
}
