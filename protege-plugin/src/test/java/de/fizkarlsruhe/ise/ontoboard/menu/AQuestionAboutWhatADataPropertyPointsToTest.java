package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.QuestionTests;
import org.junit.jupiter.api.Test;

/**
 * "Every kind of X has some P that is a Y" is not written for a data property (1.135.2 review).
 *
 * <p>A data property points to a value, never to a class: the test asks for a filler that is a
 * kind of Y, no datatype is one, and it stayed red for ever - its advice to relate each class to
 * another term with that property could not be followed either.
 */
class AQuestionAboutWhatADataPropertyPointsToTest {

    @Test
    void aDataPropertyIsRefusedForTheRuleThatNamesTheClassItPointsTo() {
        String refusal = QuestionTestAction.refusalFor(
                QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME_THAT_IS, true);

        assertNotNull(refusal);
        assertTrue(refusal.contains("data property") && refusal.contains("object property"),
                refusal);
    }

    @Test
    void anObjectPropertyIsNot() {
        assertNull(QuestionTestAction.refusalFor(
                QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME_THAT_IS, false));
    }

    @Test
    void norIsADataPropertyForARuleThatNamesNoClassItPointsTo() {
        assertNull(QuestionTestAction.refusalFor(QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME,
                true));
        assertNull(QuestionTestAction.refusalFor(QuestionTests.Template.EVERY_INDIVIDUAL_HAS,
                true));
        assertNull(QuestionTestAction.refusalFor(QuestionTests.Template.NO_MORE_THAN, true));
    }
}
