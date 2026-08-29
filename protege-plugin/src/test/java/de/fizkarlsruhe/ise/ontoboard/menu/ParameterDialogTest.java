package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import org.junit.jupiter.api.Test;

/**
 * The one piece of the parameter dialog that is not Swing: turning help into something readable.
 *
 * <p>Swing renders help text as HTML, and HTML collapses newlines. The help behind the "?" for a
 * parameter offering five reasoners is five paragraphs, one per reasoner; run through a naive
 * escape it arrives as an unbroken wall of prose that nobody reads past the second line. Making
 * the help mandatory and then rendering it unreadably would be worse than not having it, because
 * it looks like the problem is solved.
 */
class ParameterDialogTest {

    @Test
    void aBlankLineBecomesAParagraphBreak() {
        assertEquals("First.<br><br>Second.",
                ParameterDialog.asHtml("First.\n\nSecond."));
    }

    @Test
    void aSingleNewlineBecomesALineBreak() {
        assertEquals("First.<br>Second.", ParameterDialog.asHtml("First.\nSecond."));
    }

    /** Windows line endings arrive in help copied out of a text file. */
    @Test
    void carriageReturnsDoNotSurviveIntoTheOutput() {
        assertEquals("First.<br><br>Second.",
                ParameterDialog.asHtml("First.\r\n\r\nSecond."));
    }

    @Test
    void severalBlankLinesAreStillOneBreak() {
        assertEquals("First.<br><br>Second.",
                ParameterDialog.asHtml("First.\n\n\n\n   \n\nSecond."));
    }

    @Test
    void leadingAndTrailingBlankLinesProduceNoStrayBreaks() {
        assertEquals("Only this.", ParameterDialog.asHtml("\n\n  Only this.  \n\n"));
    }

    @Test
    void emptyHelpProducesNothingRatherThanNull() {
        assertEquals("", ParameterDialog.asHtml(""));
        assertEquals("", ParameterDialog.asHtml("   \n \n "));
        assertEquals("", ParameterDialog.asHtml(null));
    }

    // ---------- the escaping still happens ----------

    /**
     * Help mentioning an IRI or a comparison contains characters that would otherwise be read as
     * markup, and a swallowed sentence is worse than an ugly one.
     */
    @Test
    void markupCharactersInTheHelpAreEscaped() {
        String html = ParameterDialog.asHtml("Use <owl:Class> & read rdfs:label > the IRI.");

        assertTrue(html.contains("&lt;owl:Class&gt;"), html);
        assertTrue(html.contains("&amp;"), html);
        assertTrue(html.contains("&gt; the IRI"), html);
    }

    @Test
    void theAmpersandIsEscapedBeforeTheAngleBracketsSoNothingIsDoubleEscaped() {
        assertEquals("&amp;lt;", ParameterDialog.asHtml("&lt;"));
    }

    // ---------- the help that actually ships ----------

    /**
     * The reasoner help is the longest in the plugin and the reason this exists. If it renders as
     * one paragraph, the five descriptions it carries are unreadable in practice.
     */
    @Test
    void theReasonerHelpArrivesAsSeparateParagraphs() {
        String html = ParameterDialog.asHtml(Reasoners.help());

        int breaks = html.split("<br><br>", -1).length - 1;
        assertTrue(breaks >= Reasoners.Choice.values().length,
                "expected a paragraph per reasoner, got " + breaks + " breaks in: " + html);
        assertFalse(html.contains("\n"), "a raw newline is invisible in HTML: " + html);
        for (Reasoners.Choice choice : Reasoners.Choice.values()) {
            assertTrue(html.contains(choice.getLabel()), choice + " missing from the rendering");
        }
    }
}
