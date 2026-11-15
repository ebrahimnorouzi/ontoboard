package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.NameResolver;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ChatRequest;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelClient;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Reply;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.StreamListener;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextItem;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.TermLine;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.TermLines;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.AssistantSession;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.OntologyAccess;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;

/** The assistant column (spec D6), built on the event thread against a scripted model. */
class AssistantPanelTest {

    private final OWLOntology pizza = MiniPizza.load();
    private final IRI selected = MiniPizza.iri("VegetarianPizza");

    @BeforeEach
    void needsADisplay() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing needs a display");
    }

    private final class TestHost implements AssistantPanel.Host {
        @Override
        public List<TermLine> lines() {
            return TermLines.of(pizza, selected);
        }

        @Override
        public String termTitle() {
            return "vegetarian pizza";
        }

        @Override
        public boolean selectedIsUnsatisfiable() {
            return false;
        }

        @Override
        public void explainSelected() {
        }

        @Override
        public void explainProblem() {
        }

        @Override
        public List<AssistantPanel.Candidate> complete(String prefix) {
            return Collections.emptyList();
        }

        @Override
        public IRI selectedIri() {
            return selected;
        }

        @Override
        public void openSettings() {
        }

        @Override
        public String destination() {
            return "Ollama · localhost (this machine)";
        }

        @Override
        public String titleOf(ContextItem item) {
            return item.getKind() == ContextItem.Kind.TERM ? item.getIri().getShortForm()
                    : item.getKind().toString();
        }

        @Override
        public int sizeOf(ContextItem item) {
            return 10;
        }

        @Override
        public void saveConversation() {
        }

        @Override
        public void showWhatIsSent(String draft) {
        }
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<T>();
        AtomicReference<Exception> failure = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (Exception thrown) {
                failure.set(thrown);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private AssistantSession session(List<String> deltas) {
        return session(deltas, Runnable::run);
    }

    /** @param worker where the model call runs; a queue keeps the session busy until run */
    private AssistantSession session(List<String> deltas, java.util.concurrent.Executor worker) {
        AssistantSession session = new AssistantSession(new OntologyAccess() {
            @Override
            public OWLOntology ontology() {
                return pizza;
            }

            @Override
            public IRI selected() {
                return selected;
            }

            @Override
            public File ontologyFile() {
                return null;
            }
        }, worker, Runnable::run);
        session.setClientFactory((connection, key) -> new ModelClient() {
            @Override
            public List<String> listModels() {
                return Collections.emptyList();
            }

            @Override
            public Reply chat(ChatRequest request, StreamListener listener) {
                StringBuilder text = new StringBuilder();
                for (String delta : deltas) {
                    listener.onText(delta);
                    text.append(delta);
                }
                return new Reply(text.toString(), 1, 1, "stop", 1);
            }

            @Override
            public void stop() {
            }
        });
        session.setConnection(Connection.of("Ollama", ConnectionKind.OLLAMA).withModel("m"), "");
        session.setProposalApplier(changes -> {
            applied.add(changes);
            pizza.getOWLOntologyManager().applyChanges(changes);
        });
        return session;
    }

    private final List<List<org.semanticweb.owlapi.model.OWLOntologyChange>> applied =
            new ArrayList<List<org.semanticweb.owlapi.model.OWLOntologyChange>>();

    @Test
    void aQuestionAndItsAnswerAreTwoBubbles() throws Exception {
        int bubbles = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            AssistantSession session = session(Arrays.asList("Every ", "pizza."));
            panel.showSession(session);
            session.send("What is it?");
            return panel.bubbleCount();
        });

        assertEquals(2, bubbles);
    }

    /** The maintainer's case: a definition offered as a card, applied by Accept. */
    @Test
    void aProposalIsACardWhoseAcceptAppliesIt() throws Exception {
        String reply = "I suggest:\n```ontoboard-proposal\naction: set\nsubject: vegetarian pizza\n"
                + "property: definition\nvalue: A pizza whose toppings are all vegetarian.\n```";

        String after = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            AssistantSession session = session(Arrays.asList(reply));
            panel.showSession(session);
            session.send("Write a definition.");
            List<ProposalCard> cards = panel.cards();
            assertEquals(1, cards.size());
            assertTrue(cards.get(0).text().contains("A pizza with no meat."),
                    "the old value is shown, struck through: " + cards.get(0).text());
            cards.get(0).acceptButton().doClick();
            return panel.cards().get(0).text();
        });

        assertEquals(1, applied.size());
        assertTrue(after.contains("Accepted"), after);
    }

    @Test
    void theAnswerIsShownWithoutItsProposalBlock() throws Exception {
        String reply = "I suggest:\n```ontoboard-proposal\naction: set\nsubject: vegetarian pizza\n"
                + "property: definition\nvalue: A pizza whose toppings are all vegetarian.\n```";

        String shown = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            AssistantSession session = session(Arrays.asList(reply));
            panel.showSession(session);
            session.send("Write a definition.");
            return panel.transcriptText();
        });

        assertTrue(shown.contains("I suggest:"), shown);
        assertFalse(shown.contains("ontoboard-proposal"), shown);
    }

    @Test
    void theStatusFollowsTheSession() throws Exception {
        String status = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            AssistantSession session = session(Arrays.asList("ok"));
            panel.showSession(session);
            session.send("q");
            return panel.statusText();
        });

        assertTrue(status.startsWith("Done in "), status);
    }

    @Test
    void suggestADefinitionIsOneClickFromTheHeader() throws Exception {
        String asked = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            AssistantSession session = session(Arrays.asList("ok"));
            panel.showSession(session);
            for (AbstractButton button : buttons(panel)) {
                if ("Suggest a definition".equals(button.getText())) {
                    button.doClick();
                }
            }
            return session.turns().isEmpty() ? "" : session.turns().get(0).getMessage();
        });

        assertTrue(asked.contains("definition for vegetarian pizza"), asked);
    }

    /**
     * 1.136.0's "What is sent" sat in a FlowLayout row that wrapped it onto a second line the row
     * gave no height to, so in the real column it was never painted (reported by the maintainer).
     */
    @Test
    void whatIsSentIsReachableInANarrowColumn() throws Exception {
        boolean visible = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            panel.showSession(session(Arrays.asList("x")));
            panel.setSize(280, 700);
            layOut(panel);
            for (AbstractButton button : buttons(panel)) {
                if ("What is sent".equals(button.getText()) && insideEveryAncestor(button, panel)) {
                    return true;
                }
            }
            return false;
        });

        assertTrue(visible, "no \"What is sent\" button is inside the area its row paints");
    }

    /**
     * Review (1.137.0): the footer's button row measured itself at its old width, so after the
     * column narrowed the second row of buttons was cut off until something else changed.
     */
    @Test
    void theButtonsWrapAfterTheColumnNarrows() throws Exception {
        List<String> clipped = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            panel.showSession(session(Arrays.asList("x")));
            panel.setSize(600, 700);
            layOut(panel);
            panel.setSize(200, 700);
            layOut(panel);
            List<String> cut = new ArrayList<String>();
            for (AbstractButton button : buttons(panel)) {
                String text = button.getText();
                if (Arrays.asList("Send", "What is sent", "Clear", "Save…").contains(text)
                        && !insideEveryAncestor(button, panel)) {
                    cut.add(text);
                }
            }
            return cut;
        });

        assertTrue(clipped.isEmpty(), "cut off: " + clipped);
    }

    private static void layOut(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layOut((Container) child);
            }
        }
    }

    private static List<AbstractButton> buttons(Container container) {
        List<AbstractButton> found = new ArrayList<AbstractButton>();
        for (Component child : container.getComponents()) {
            if (child instanceof AbstractButton) {
                found.add((AbstractButton) child);
            }
            if (child instanceof Container) {
                found.addAll(buttons((Container) child));
            }
        }
        return found;
    }

    /** Within its parent's height and width, and so on up to the panel. */
    private static boolean insideEveryAncestor(Component component, Container top) {
        Component at = component;
        while (at != top && at.getParent() != null) {
            Container parent = at.getParent();
            if (at.getWidth() == 0 || at.getHeight() == 0
                    || at.getX() + at.getWidth() > parent.getWidth() + 1
                    || at.getY() + at.getHeight() > parent.getHeight() + 1) {
                return false;
            }
            at = parent;
        }
        return true;
    }

    @Test
    void linesBecomeRowsWithASparkButton() throws Exception {
        int lines = TermLines.of(pizza, selected).size();

        int buttons = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            panel.showSession(session(Arrays.asList("x")));
            panel.refresh();
            return panel.sparkButtonCount();
        });

        assertEquals(lines, buttons);
    }

    @Test
    void explainThisAxiomOnlyOnSemantics() throws Exception {
        List<TermLine> lines = TermLines.of(pizza, selected);
        int annotation = -1;
        int semantics = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getGroup() == TermLine.Group.ANNOTATION && annotation < 0) {
                annotation = i;
            }
            if (lines.get(i).getGroup() == TermLine.Group.SEMANTICS && semantics < 0) {
                semantics = i;
            }
        }
        final int a = annotation;
        final int s = semantics;

        List<List<String>> menus = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            panel.showSession(session(Arrays.asList("x")));
            panel.refresh();
            return Arrays.asList(items(panel.menuForLine(a)), items(panel.menuForLine(s)));
        });

        // The first annotation line is the label: it can be reworded (round 2), not explained.
        assertEquals(Arrays.asList("Ask about this line", "Suggest a better wording"),
                menus.get(0));
        assertEquals(Arrays.asList("Ask about this line", "Explain this axiom",
                "Suggest a better axiom"), menus.get(1));
    }

    /** 1.139.0: an added axiom is a card with the axiom and, for "only" alone, the trap said. */
    @Test
    void anAddedAxiomIsACardWithItsAxiomAndANote() throws Exception {
        String reply = "```ontoboard-proposal\naction: add-axiom\n"
                + "axiom: 'vegetarian pizza' SubClassOf hasTopping only 'cheese topping'\n```";

        String card = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            AssistantSession session = session(Arrays.asList(reply));
            panel.showSession(session);
            session.send("Only cheese.");
            return panel.cards().get(0).text();
        });

        assertTrue(card.contains("Add an axiom"), card);
        assertTrue(card.contains("hasTopping only 'cheese topping'"), card);
        assertTrue(card.contains("satisfies it"), card);
    }

    /**
     * Review 1.139.0 I1: the card shows the axiom Accept writes, under the ontology's names - a
     * synonym the model used appears as the term it stands for.
     */
    @Test
    void anAxiomCardShowsTheAxiomAsItWillBeWritten() throws Exception {
        String reply = "```ontoboard-proposal\naction: add-axiom\n"
                + "axiom: 'veggie pizza' SubClassOf hasTopping some 'cheese topping'\n```";

        String card = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            AssistantSession session = session(Arrays.asList(reply));
            panel.showSession(session);
            session.send("Cheese.");
            return panel.cards().get(0).text();
        });

        assertTrue(card.contains("'vegetarian pizza' SubClassOf hasTopping some 'cheese topping'"),
                card);
        assertFalse(card.contains("veggie"), card);
    }

    /** 1.139.0 review M6: "Write as OWL…" while an answer was coming dropped the sentence. */
    @Test
    void writeAsOwlIsDisabledWhileBusy() throws Exception {
        List<Boolean> enabled = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            List<Runnable> queue = new ArrayList<Runnable>();
            AssistantSession session = session(Arrays.asList("ok"), queue::add);
            panel.showSession(session);
            AbstractButton write = null;
            for (AbstractButton button : buttons(panel)) {
                if ("Write as OWL…".equals(button.getText())) {
                    write = button;
                }
            }
            List<Boolean> seen = new ArrayList<Boolean>();
            seen.add(write.isEnabled());
            session.send("Busy now.");
            seen.add(write.isEnabled());
            queue.remove(0).run();
            seen.add(write.isEnabled());
            return seen;
        });

        assertEquals(Arrays.asList(true, false, true), enabled);
    }

    /** 1.142.0 (review 1.141.0 I1): a sentence that was not sent is not lost. */
    @Test
    void aSentenceThatWasNotSentIsOfferedAgain() throws Exception {
        List<String> starts = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            AssistantSession session = session(Arrays.asList("ok"));
            session.setConnection(null, "");
            panel.showSession(session);
            List<String> seen = new ArrayList<String>();
            panel.setSentenceAsker((subject, start) -> {
                seen.add(start);
                return "Every vegetarian pizza has a cheese topping.";
            });
            AbstractButton write = null;
            for (AbstractButton button : buttons(panel)) {
                if ("Write as OWL…".equals(button.getText())) {
                    write = button;
                }
            }
            write.doClick();
            write.doClick();
            return seen;
        });

        assertEquals(Arrays.asList("", "Every vegetarian pizza has a cheese topping."), starts);
    }

    /** 1.142.0 (review 1.141.0 I1): one way out of many chips, enabled only when there are any. */
    @Test
    void removeAllEmptiesTheChips() throws Exception {
        List<Object> seen = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            AssistantSession session = session(Arrays.asList("ok"));
            panel.showSession(session);
            AbstractButton removeAll = null;
            for (AbstractButton button : buttons(panel)) {
                if ("Remove all".equals(button.getText())) {
                    removeAll = button;
                }
            }
            List<Object> states = new ArrayList<Object>();
            states.add(removeAll != null);
            if (removeAll != null) {
                session.addContext(ContextItem.term(MiniPizza.iri("CheeseTopping"), false));
                states.add(removeAll.isEnabled());
                removeAll.doClick();
                states.add(session.context().size());
                states.add(removeAll.isEnabled());
            }
            return states;
        });

        assertEquals(Arrays.<Object>asList(true, true, 1, false), seen);
    }

    @Test
    void writeAsOwlSendsThePersonsSentence() throws Exception {
        String asked = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            AssistantSession session = session(Arrays.asList("ok"));
            panel.showSession(session);
            panel.setSentenceAsker((subject, start) ->
                    "Every vegetarian pizza has a cheese topping.");
            for (AbstractButton button : buttons(panel)) {
                if ("Write as OWL…".equals(button.getText())) {
                    button.doClick();
                }
            }
            return session.turns().isEmpty() ? "" : session.turns().get(0).getMessage();
        });

        assertTrue(asked.contains("Every vegetarian pizza has a cheese topping."), asked);
    }

    @Test
    void aBetterWordingIsAskedForAsAProposal() throws Exception {
        List<TermLine> lines = TermLines.of(pizza, selected);
        int definition = -1;
        for (int i = 0; i < lines.size(); i++) {
            if ("definition".equals(lines.get(i).getKey())) {
                definition = i;
            }
        }
        final int d = definition;

        String asked = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            AssistantSession session = session(Arrays.asList("ok"));
            panel.showSession(session);
            for (Component item : panel.menuForLine(d).getComponents()) {
                if (item instanceof JMenuItem
                        && "Suggest a better wording".equals(((JMenuItem) item).getText())) {
                    ((JMenuItem) item).doClick();
                }
            }
            return session.turns().get(0).getMessage();
        });

        assertTrue(asked.contains("A pizza with no meat."), asked);
        assertTrue(asked.contains("property: definition"), asked);
        assertTrue(asked.contains("ontoboard-proposal"), asked);
    }

    private static List<String> items(JPopupMenu menu) {
        List<String> texts = new ArrayList<String>();
        for (Component item : menu.getComponents()) {
            if (item instanceof JMenuItem) {
                texts.add(((JMenuItem) item).getText());
            }
        }
        return texts;
    }

    private static int conversationHeight(AssistantPanel panel, java.awt.Window frame,
            List<ContextItem> items) {
        panel.onContextChanged(items);
        frame.validate();
        frame.validate();
        javax.swing.JSplitPane split = find(panel, javax.swing.JSplitPane.class);
        return ((javax.swing.JScrollPane) split.getBottomComponent()).getViewport().getHeight();
    }

    private static <T> T find(Container root, Class<T> kind) {
        for (Component child : root.getComponents()) {
            if (kind.isInstance(child)) {
                return kind.cast(child);
            }
            if (child instanceof Container) {
                T inside = find((Container) child, kind);
                if (inside != null) {
                    return inside;
                }
            }
        }
        return null;
    }

    private List<ContextItem> chips(int count) {
        String[] names = {"CheeseTopping", "VegetarianTopping", "Pizza", "NamedPizza",
            "MeatTopping", "hasTopping", "Margherita", "Topping", "hasBase", "PizzaBase",
            "TomatoTopping"};
        List<ContextItem> items = new ArrayList<ContextItem>();
        items.add(ContextItem.term(selected, true));
        for (int i = 0; items.size() < count; i++) {
            items.add(ContextItem.term(MiniPizza.iri(names[i % names.length]), false));
        }
        return items;
    }

    /**
     * Review 1.144.0 settings-ui: every row of chips took its height from the conversation, where
     * answers and proposal cards appear - twelve chips in the assistant's column left it one line,
     * and with a larger font the chips slid under the header. After two rows they scroll.
     */
    @Test
    void manyChipsLeaveTheConversationItsRoom() throws Exception {
        int[] heights = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            javax.swing.JFrame frame = new javax.swing.JFrame();
            try {
                frame.setContentPane(panel);
                frame.setSize(384, 540);
                frame.addNotify();
                return new int[] {conversationHeight(panel, frame, chips(4)),
                    conversationHeight(panel, frame, chips(12))};
            } finally {
                frame.dispose();
            }
        });

        assertTrue(heights[1] >= heights[0], "4 chips leave " + heights[0] + " px, 12 leave "
                + heights[1] + " px");
    }

    private static void invalidateAll(Component component) {
        component.invalidate();
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                invalidateAll(child);
            }
        }
    }

    /** The column this wide, the window's borders aside, and laid out once, as one resize is. */
    private static void sized(java.awt.Window frame, int width) {
        java.awt.Insets insets = frame.getInsets();
        frame.setSize(width + insets.left + insets.right, 620 + insets.top + insets.bottom);
        frame.validate();
    }

    /** Everything measured again until nothing changes: a column laid out from scratch. */
    private static void settled(java.awt.Window frame) {
        for (int pass = 0; pass < 3; pass++) {
            invalidateAll(frame);
            frame.validate();
        }
    }

    /** The chips' height, the bottom of the lowest chip, and the chips' scroll bar's maximum. */
    private static int[] chipRows(AssistantPanel panel) {
        javax.swing.JScrollPane scroll = find(find(panel, ChipsPanel.class),
                javax.swing.JScrollPane.class);
        Component view = scroll.getViewport().getView();
        int bottom = 0;
        for (Component chip : ((Container) view).getComponents()) {
            bottom = Math.max(bottom, chip.getY() + chip.getHeight());
        }
        return new int[] {view.getHeight(), bottom, scroll.getVerticalScrollBar().getMaximum()};
    }

    /** Twelve chips in a column built at this width. */
    private int[] freshChipRows(int width) {
        AssistantPanel panel = new AssistantPanel(new TestHost());
        javax.swing.JFrame frame = new javax.swing.JFrame();
        try {
            frame.setContentPane(panel);
            frame.addNotify();
            panel.onContextChanged(chips(12));
            sized(frame, width);
            settled(frame);
            return chipRows(panel);
        } finally {
            frame.dispose();
        }
    }

    /**
     * Review 1.145.0 ui-1: after one resize of the column - Protégé maximised or restored - the
     * chips kept the height they had at the old width. Narrowed, the last chips and their × could
     * not be scrolled to; widened, the bar scrolled into blank space.
     */
    @Test
    void theChipsAreMeasuredAgainAfterOneResize() throws Exception {
        int[][] seen = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            javax.swing.JFrame frame = new javax.swing.JFrame();
            try {
                frame.setContentPane(panel);
                frame.addNotify();
                panel.onContextChanged(chips(12));
                sized(frame, 430);
                settled(frame);
                sized(frame, 280);
                int[] narrowed = chipRows(panel);
                sized(frame, 600);
                int[] widened = chipRows(panel);
                return new int[][] {narrowed, freshChipRows(280), widened, freshChipRows(600)};
            } finally {
                frame.dispose();
            }
        });

        assertTrue(seen[0][0] >= seen[0][1], "at 280 px the chips are " + seen[0][0]
                + " px tall and the last one ends at " + seen[0][1]);
        assertEquals(seen[1][0], seen[0][0], "the chips' height at 280 px, against a fresh layout");
        assertEquals(seen[3][2], seen[2][2],
                "the chips' scroll bar's maximum at 600 px, against a fresh layout");
    }

    /**
     * Review 1.145.0 ui-5, deferred to 1.147.0: a chip about as wide as the column - a long term
     * name, or the scroll bar beside three rows - had its × outside the row, where nobody can
     * reach it. Its name is shortened instead, and the × stays in reach.
     */
    @Test
    void aChipWiderThanTheRowKeepsItsRemoveButtonInReach() throws Exception {
        String name = "ATermNameSoLongThatItsChipIsWiderThanTheWholeAssistantColumnInProtege";
        int[] seen = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            javax.swing.JFrame frame = new javax.swing.JFrame();
            try {
                frame.setContentPane(panel);
                frame.setSize(300, 540);
                frame.addNotify();
                panel.onContextChanged(Arrays.asList(ContextItem.term(selected, true),
                        ContextItem.term(MiniPizza.iri(name), false)));
                frame.validate();
                frame.validate();
                AbstractButton remove = null;
                for (AbstractButton button : buttons(panel)) {
                    if ("\u00d7".equals(button.getText())) {
                        remove = button;
                    }
                }
                javax.swing.JViewport rows = (javax.swing.JViewport) SwingUtilities
                        .getAncestorOfClass(javax.swing.JViewport.class, remove);
                java.awt.Rectangle at = SwingUtilities.convertRectangle(remove.getParent(),
                        remove.getBounds(), rows);
                return new int[] {at.x + at.width, rows.getWidth(), at.width};
            } finally {
                frame.dispose();
            }
        });

        assertTrue(seen[2] > 0, "the x is laid out");
        assertTrue(seen[0] <= seen[1], "the x ends at " + seen[0] + " px in a row " + seen[1]
                + " px wide");
    }

    /** Each chip's × that is below the chips' scrollable area or beyond the row, by its name. */
    private static List<String> removeButtonsOutOfReach(AssistantPanel panel) {
        javax.swing.JViewport port = find(find(panel, ChipsPanel.class),
                javax.swing.JScrollPane.class).getViewport();
        Container rows = (Container) port.getView();
        List<String> out = new ArrayList<String>();
        for (AbstractButton button : buttons(rows)) {
            if (!"×".equals(button.getText())) {
                continue;
            }
            java.awt.Rectangle at = SwingUtilities.convertRectangle(button.getParent(),
                    button.getBounds(), rows);
            if (at.width == 0 || at.x < 0 || at.x + at.width > port.getWidth()
                    || at.y + at.height > rows.getHeight()) {
                out.add(((JLabel) button.getParent().getComponent(0)).getText() + " at y "
                        + at.y + ".." + (at.y + at.height) + ", rows " + rows.getHeight()
                        + " px tall");
            }
        }
        return out;
    }

    /**
     * Review 1.147.0 ui-2: widened in one step - Protégé maximised, the column dragged wider - a
     * long name shortened for the old width grows, and the short chip after it goes down a row.
     * The viewport sized the rows from the old names, so after that one validate the last chips
     * and their × are below the scrollable area. The review saw them stay there because its
     * probe validated again inside the same event; in Protégé the events the resize queued run
     * first. The rows' own resize event makes their viewport revalidate (JViewport's
     * ViewListener), and resizing the rows dropped the preferred size measured from the old
     * names, so the next validate measures the new ones and every × is in reach.
     */
    @Test
    void theLastChipsStayInReachAfterTheColumnWidensInOneStep() throws Exception {
        List<ContextItem> items = Arrays.asList(ContextItem.term(selected, true),
                ContextItem.term(MiniPizza.iri(
                        "ATermNameSoLongThatItsChipIsWiderThanTheWholeAssistantColumnInProtege"),
                        false),
                ContextItem.term(MiniPizza.iri("Pizza"), false),
                ContextItem.term(MiniPizza.iri("HeatTreatmentProcessThatChangesTheMicrostructure"
                        + "OfAMetallicAlloyByControlledHeatingAndCooling"), false),
                ContextItem.term(MiniPizza.iri("Food"), false),
                ContextItem.hierarchy());
        javax.swing.JFrame frame = onEdt(() -> new javax.swing.JFrame());
        try {
            AtomicReference<AssistantPanel> panel = new AtomicReference<AssistantPanel>();
            List<String> at280 = onEdt(() -> {
                panel.set(new AssistantPanel(new TestHost()));
                frame.setContentPane(panel.get());
                frame.addNotify();
                panel.get().onContextChanged(items);
                sized(frame, 280);
                settled(frame);
                List<String> before = removeButtonsOutOfReach(panel.get());
                sized(frame, 384);
                return before;
            });
            // invokeAndWait runs this after the events the layout at 384 px queued.
            List<String> at384 = onEdt(() -> {
                frame.validate();
                return removeButtonsOutOfReach(panel.get());
            });

            assertEquals(Collections.<String>emptyList(), at280, "out of reach at 280 px");
            assertEquals(Collections.<String>emptyList(), at384,
                    "out of reach at 384 px, after the work queued and one more validate");
        } finally {
            onEdt(() -> {
                frame.dispose();
                return null;
            });
        }
    }

    @Test
    void theSelectionChipHasNoRemoveButton() throws Exception {
        int removable = onEdt(() -> {
            ChipsPanel chips = new ChipsPanel(new TestHost(), new ChipsPanel.Actions() {
                @Override
                public void remove(ContextItem item) {
                }

                @Override
                public void add(ContextItem item) {
                }

                @Override
                public void removeAllChips() {
                }
            });
            chips.setItems(Arrays.asList(ContextItem.term(selected, true),
                    ContextItem.term(MiniPizza.iri("CheeseTopping"), false)));
            return chips.removeButtonCount();
        });

        assertEquals(1, removable);
    }

    @Test
    void streamedTextAppearsInTheTranscript() throws Exception {
        String text = onEdt(() -> {
            AssistantPanel panel = new AssistantPanel(new TestHost());
            AssistantSession session = session(Arrays.asList("Every ", "pizza."));
            panel.showSession(session);
            session.send("What is it?");
            return panel.transcriptText();
        });

        assertTrue(text.contains("What is it?"), text);
        assertTrue(text.contains("Every pizza."), text);
    }

    @Test
    void aNeedsBarListsFoundNotFoundAndAmbiguous() throws Exception {
        OWLDataFactory factory = pizza.getOWLOntologyManager().getOWLDataFactory();
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), MiniPizza.iri("Country"),
                factory.getOWLLiteral("place")));
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), MiniPizza.iri("City"), factory.getOWLLiteral("place")));
        NameResolver resolver = new NameResolver(pizza);
        List<NameResolver.Resolution> needs = Arrays.asList(resolver.resolve("cheese topping"),
                resolver.resolve("calzone"), resolver.resolve("place"));

        String shown = onEdt(() -> {
            NeedsBar bar = new NeedsBar(found -> { });
            bar.setNeeds(needs);
            return allText(bar);
        });

        assertTrue(shown.contains("Add cheese topping, and ask again"), shown);
        assertTrue(shown.contains("not in this ontology: calzone"), shown);
        assertTrue(shown.contains("ambiguous: place"), shown);
    }

    private static String allText(Container container) {
        StringBuilder out = new StringBuilder();
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel) {
                out.append(((JLabel) child).getText()).append('\n');
            } else if (child instanceof AbstractButton) {
                out.append(((AbstractButton) child).getText()).append('\n');
            }
            if (child instanceof Container) {
                out.append(allText((Container) child));
            }
        }
        return out.toString();
    }
}
