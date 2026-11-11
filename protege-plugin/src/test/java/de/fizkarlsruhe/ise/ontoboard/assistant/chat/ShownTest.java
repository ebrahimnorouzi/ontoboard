package de.fizkarlsruhe.ise.ontoboard.assistant.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** An answer as the bubble shows it: blocks become cards and buttons, markdown becomes text. */
class ShownTest {

    @Test
    void proposalAndNeedBlocksAreNotShownAsText() {
        String reply = "I suggest this.\n```ontoboard-proposal\naction: add\nsubject: x\n"
                + "property: example\nvalue: y\n```\nAnd I need:\n```ontoboard-need\nhasTopping\n```";

        String shown = Shown.forReading(reply, false);

        assertTrue(shown.startsWith("I suggest this."), shown);
        assertFalse(shown.contains("subject: x"), shown);
        assertFalse(shown.contains("hasTopping"), shown);
    }

    @Test
    void aProposalBeingWrittenIsSaidNotSpelledOut() {
        String streaming = "Here it is:\n```ontoboard-proposal\naction: set\nsubj";

        assertEquals("Here it is:\n\n✎ Writing a proposal…", Shown.forReading(streaming, true));
        assertTrue(Shown.forReading(streaming, false).contains("action: set"),
                "a finished answer cut off inside a block shows what came");
    }

    @Test
    void markdownBecomesPlainText() {
        String reply = "### Why\n**Naples** has a `decimal` price.\n- first\n* second\n"
                + "```owl\nhasPrice Range: xsd:float\n```";

        assertEquals("Why\nNaples has a decimal price.\n• first\n• second\n"
                + "hasPrice Range: xsd:float", Shown.forReading(reply, false));
    }

    @Test
    void singleStarsForItalicsAreDroppedButArithmeticStays() {
        assertEquals("both a VegetarianTopping and a MeatTopping; 2 * 3 = 6",
                Shown.forReading("both a *VegetarianTopping* and a *MeatTopping*; 2 * 3 = 6",
                        false));
    }

    /** Review (1.137.0): raw proposal lines with no card and no word about why. */
    @Test
    void aFinishedAnswerCutInsideAProposalSaysSo() {
        String cut = "Here it is:\n```ontoboard-proposal\naction: set\nsubject: x\nproperty: lab";

        String shown = Shown.forReading(cut, false);

        assertTrue(shown.contains("action: set"), shown);
        assertTrue(shown.endsWith("(The answer ended inside a proposal, so it cannot be offered"
                + " as a card.)"), shown);
    }

    @Test
    void anAnswerThatIsOnlyAProposalSaysWhereItIs() {
        String reply = "```ontoboard-proposal\naction: add\nsubject: x\nproperty: example\n"
                + "value: y\n```";

        assertEquals("(The proposal is below.)", Shown.forReading(reply, false));
    }
}
