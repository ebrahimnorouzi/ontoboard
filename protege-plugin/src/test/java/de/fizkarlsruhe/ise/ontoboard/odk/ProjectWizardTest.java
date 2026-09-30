package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The banner the new-project form carries when it rejects something.
 *
 * <p>The wizard itself is Swing and untestable headlessly; what is testable is the one decision
 * it makes about a rejection, which is how to render a validation message that quotes the user's
 * own input back at them.
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
        assertTrue(html.contains("width:520px"), "no width, no wrapping: " + html);
        assertTrue(html.contains("Not created."),
                "the banner says what happened before it says why: " + html);
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
