package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * The banner the new-project form carries when it rejects something.
 *
 * <p>The dialog is {@code JOptionPane}'s and needs a display; the form inside it does not, so it
 * is laid out here as the dialog lays it out, and measured. The rest is the one decision the
 * wizard makes about a rejection, which is how to render a validation message that quotes the
 * user's own input back at them.
 *
 * <p>Before 1.69.0 there was no banner and no test, because the dialog closed: the message went
 * to a modal of its own and everything typed was discarded. The form re-prompts now, so the
 * message has to live inside it.
 */
class ProjectWizardTest {

    /**
     * The user's input is shown, not interpreted.
     *
     * <p>Every rejection in {@code OdkProjectConfig.validate} quotes the offending value -
     * {@code Got: '...'} - and a {@code JLabel} whose text starts with {@code <html>} renders the
     * rest as markup. An ontology ID of {@code <b>} would therefore be shown as nothing at all,
     * hiding the very thing the message is about, and an unbalanced tag can swallow the sentence
     * after it.
     */
    @Test
    void theRejectedValueIsEscapedRatherThanRendered() {
        String html = ProjectWizard.problemHtml("Got: '<b>' and 5 > 3 & rising");

        assertTrue(html.contains("&lt;b&gt;"), "the angle brackets must survive as text: " + html);
        assertTrue(html.contains("5 &gt; 3"), "a bare > is still markup to Swing: " + html);
        assertTrue(html.contains("&amp; rising"), "an unescaped & starts an entity: " + html);
        assertFalse(html.contains("<b>'"), "the user's input must not reach the renderer: " + html);
    }

    /**
     * It wraps.
     *
     * <p>The base-IRI rule is sixty words, because it explains what a relative IRI does to a
     * colleague's copy of the ontology - which is the part that stops somebody re-entering the
     * same value. Without a width the label lays that out on one line and the dialog grows wider
     * than the screen, putting its own OK button out of reach.
     */
    @Test
    void theBannerIsGivenAWidthToWrapAt() {
        String html = ProjectWizard.problemHtml("a message");

        assertTrue(html.startsWith("<html>"), html);
        assertTrue(html.contains("width:" + ProjectWizard.TEXT_WIDTH_CSS + "px"),
                "no width, no wrapping: " + html);
        assertTrue(ProjectWizard.TEXT_WIDTH_CSS * 1.3 <= ProjectWizard.FORM_WIDTH,
                "Swing draws a CSS pixel 1.3 screen pixels wide, so " + ProjectWizard.TEXT_WIDTH_CSS
                        + "px is wider than the " + ProjectWizard.FORM_WIDTH + " px form");
        assertTrue(html.contains("Not created."),
                "the banner says what happened before it says why: " + html);
    }

    // ---------------------------------------------------------------- the form, laid out

    /** The egg study's ID, and the address a participant types after the one filled in. */
    private static final String EGG = "eggboil";

    private static final String EGG_TYPED_AFTER = "http://purl.obolibrary.org/obo/eggboil.owl"
            + "https://example.org/eggboil/eggboil.owl";

    /** What {@code show} hands {@link ProjectWizard#form}, made as it makes them. */
    private static final class Widgets {
        final JTextField id = new JTextField();
        final JTextField title = new JTextField("Test kitchen ontology");
        final JTextField description =
                new JTextField("Application ontology for boiling eggs in a test kitchen");
        final JTextField iri = new JTextField();
        final JTextField license = new JTextField("https://creativecommons.org/licenses/by/4.0/");
        final JTextField folder = new JTextField("C:\\Users\\participant\\Documents\\study");
        final JLabel problem = new JLabel();
        final JCheckBox adopt;
        final JPanel form;

        Widgets(String ontologyId, String address, boolean canAdopt) {
            id.setText(ontologyId);
            iri.setText(address);
            folder.setEditable(false);
            problem.setVisible(false);
            adopt = new JCheckBox(canAdopt
                    ? "Start from shop.owl - keep its address and everything in it" : "");
            adopt.setVisible(canAdopt);
            form = ProjectWizard.form(id, title, description, iri, license, folder,
                    new JButton("Choose..."), problem, adopt);
        }

        List<JTextField> six() {
            return Arrays.asList(id, title, description, iri, license, folder);
        }

        /** OK pressed: the validator's own words, shown as the wizard shows them. */
        void refused() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new OdkProjectConfig(id.getText(), title.getText(),
                            description.getText(), iri.getText(), license.getText(), null)
                            .validate());
            assertTrue(refusal.getMessage().contains("second address"), refusal.getMessage());
            ProjectWizard.refuse(problem, refusal.getMessage());
        }
    }

    /**
     * The form as the dialog shows it. Each pass of the wizard's loop makes a new
     * {@code JOptionPane} around the same panel and packs it, so the panel gets exactly its
     * preferred size, and keeps it: that dialog cannot be resized. Painted once, as the dialog
     * is, because an HTML label lays its text out again at the width it is painted at and then
     * asks to be laid out again - inside the same window.
     *
     * <p>So whatever grows in that first paint has to come from somewhere. Without a width of its
     * own the hint grew from two lines to six, and the option pane, which shrinks nothing below
     * its minimum, pushed OK and Cancel below the bottom of the window - checked here, for every
     * layout.
     */
    private static Dimension shown(JPanel form) {
        JOptionPane pane = new JOptionPane(form, JOptionPane.PLAIN_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION);
        Dimension packed = pane.getPreferredSize();
        pane.setSize(packed);
        pane.addNotify();
        pane.validate();
        BufferedImage image = new BufferedImage(packed.width, packed.height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        pane.paint(graphics);
        graphics.dispose();
        invalidateAll(pane);
        pane.validate();
        assertOkAndCancelAreInside(pane, pane, form);
        return form.getSize();
    }

    private static void assertOkAndCancelAreInside(JOptionPane pane, Container in, JPanel form) {
        for (Component child : in.getComponents()) {
            if (child instanceof JButton) {
                Rectangle at = SwingUtilities.convertRectangle(in, child.getBounds(), pane);
                int below = at.y + at.height - pane.getHeight();
                assertTrue(below <= 0, "'" + ((JButton) child).getText() + "' is " + below
                        + " px below the bottom of the dialog, which was packed before the form's"
                        + " first paint and cannot grow");
            } else if (child instanceof Container && child != form) {
                assertOkAndCancelAreInside(pane, (Container) child, form);
            }
        }
    }

    private static void invalidateAll(Component component) {
        component.invalidate();
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                invalidateAll(child);
            }
        }
    }

    /**
     * A refusal makes the dialog taller, and every field keeps its height.
     *
     * <p>Found rehearsing the egg study on Windows. The address typed after the one filled in is
     * refused - "The Base IRI holds a second address" - but the form was a fixed 560x260, the
     * dialog for the next attempt was packed to it again, and the banner's lines came out of the
     * six fields: 8 px high of the 22 they need, their text cut. The way back was a click into an
     * 8 px line. With the box that starts from the open ontology too, which is one more row.
     */
    @Test
    void aRefusalGrowsTheFormAndEveryFieldKeepsItsHeight() {
        for (boolean canAdopt : new boolean[] {false, true}) {
            Widgets widgets = new Widgets(EGG, EGG_TYPED_AFTER, canAdopt);
            int before = shown(widgets.form).height;
            assertEveryFieldWhole(widgets, "on the first attempt");

            widgets.refused();
            int after = shown(widgets.form).height;

            assertEveryFieldWhole(widgets, "after the refusal");
            assertTrue(after > before, "the banner's lines have to be added to the form, which "
                    + "stayed " + before + " px high");
        }
    }

    private static void assertEveryFieldWhole(Widgets widgets, String when) {
        for (JTextField field : widgets.six()) {
            assertTrue(field.getHeight() >= field.getPreferredSize().height,
                    when + " a field is " + field.getHeight() + " px high of the "
                            + field.getPreferredSize().height + " it needs, with the adopt box "
                            + (widgets.adopt.isVisible() ? "shown" : "hidden") + ": '"
                            + field.getText() + "'");
        }
    }

    /**
     * The refusal is shown whole: no line cut off at the right, none at the bottom.
     *
     * <p>The banner asked for 520 CSS pixels, which Swing draws 676 px wide, in a 560 px form:
     * "Replace the add ... in rather than typing after it". And a longer ID makes the rejected
     * address one word longer than a line - {@code shop_ontology}, the hint's own example, gives
     * 102 characters - and a word wider than the width sets the width.
     */
    @Test
    void theRefusalIsShownWhole() {
        for (String id : new String[] {EGG, "shop_ontology"}) {
            String typedAfter = "http://purl.obolibrary.org/obo/" + id + ".owl"
                    + "https://example.org/" + id + "/" + id + ".owl";
            Widgets widgets = new Widgets(id, typedAfter, false);
            shown(widgets.form);

            widgets.refused();
            shown(widgets.form);

            Dimension wanted = widgets.problem.getPreferredSize();
            assertTrue(widgets.problem.getWidth() >= wanted.width, "for " + id + " the banner "
                    + "wants " + wanted.width + " px and has " + widgets.problem.getWidth()
                    + ": the end of each line is cut off");
            assertTrue(widgets.problem.getHeight() >= wanted.height, "for " + id + " the banner "
                    + "wants " + wanted.height + " px and has " + widgets.problem.getHeight()
                    + ": its last lines are cut off");
        }
    }

    /**
     * "Project created" wraps at a width of its own, keeps its paragraphs, and shows a folder's name
     * as it is.
     *
     * <p>Unwrapped it measured 1355 px wide, wider than a 1280 px screen - 1920x1080 at 150% - so
     * its OK button would have been off the edge.
     */
    @Test
    void theCreatedMessageWraps() {
        String html = ProjectWizard.wrapped("Created 23 files in\n/home/me/R&D <new>"
                + "\n\nNext: OntoBoard > Start here: the guide...");

        assertTrue(html.startsWith("<html>"), html);
        assertTrue(html.contains("width:400px"), "no width, no wrapping: " + html);
        assertTrue(html.contains("in<br>/home/me/R&amp;D &lt;new&gt;<br><br>Next:"), html);
        assertFalse(html.contains("\n"), html);
    }

    /**
     * A path longer than a line is broken after a separator: a word wider than the width sets
     * the width, and the dialog came out 1165 px wide for a project in a deep folder.
     */
    @Test
    void aLongPathIsBrokenAfterASeparator() {
        String path = "/Users/someone/Documents/projects/ontologies/2026/november/a-long-folder/"
                + "another-long-folder/hardtest";
        String html = ProjectWizard.wrapped("Created 17 files in\n" + path);

        for (String line : html.replace("<html><body style='width:400px'>", "")
                .replace("</body></html>", "").split("<br>")) {
            assertTrue(line.length() <= ProjectWizard.LONGEST_WORD + "Created 17 files in".length(),
                    line);
        }
        assertTrue(html.replace("<br>", "").contains(path), "nothing of the path is lost: " + html);
    }

    /**
     * A rejection with nothing to say still says something.
     *
     * <p>{@code IllegalArgumentException.getMessage()} is null for a throw with no message, and
     * three of the exceptions this form can catch come from {@code OdkScaffold} rather than from
     * validation. A banner reading "Not created." followed by the word "null" is worse than the
     * old modal it replaced.
     */
    @Test
    void anEmptyMessageDoesNotProduceABlankBanner() {
        for (String nothing : new String[] {null, "", "   "}) {
            String html = ProjectWizard.problemHtml(nothing);

            assertFalse(html.contains("null"), "rendered a null message: " + html);
            assertTrue(html.contains("rejected"),
                    "an empty message still needs a sentence: " + html);
        }
    }
}
