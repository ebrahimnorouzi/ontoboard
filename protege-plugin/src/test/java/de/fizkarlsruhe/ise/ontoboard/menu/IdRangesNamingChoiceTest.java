package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * ID ranges... asks nothing in a project without a ranges file - and takes no choice of an
 * earlier run with it: that choice, readable or numbered names, would be written into this
 * project's configuration unasked (the 1.155.0 review's M7).
 */
class IdRangesNamingChoiceTest {

    @Test
    void noRangesFileForgetsAnEarlierNamingChoice() throws Exception {
        IdRangesAction action = new IdRangesAction() {
            @Override
            File rangesFile() {
                return null;
            }
        };
        Field naming = IdRangesAction.class.getDeclaredField("naming");
        naming.setAccessible(true);
        naming.set(action, Boolean.TRUE);

        assertTrue(action.configure());

        assertNull(naming.get(action), "the earlier run's choice would be applied again");
    }
}
