package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.protege.editor.core.ui.workspace.WorkspaceTab;

/**
 * Closing the tab the self-test opened. Protege's {@code Disposable.dispose} declares Exception,
 * so a tab that cannot be closed has to reach the check's verdict some other way than a checked
 * exception nothing on the event thread catches.
 */
class SelfTestActionTest {

    /** A tab whose dispose throws what it is given. */
    private static WorkspaceTab tabThatThrows(final Exception thrown) {
        return new WorkspaceTab() {
            private static final long serialVersionUID = 1L;

            @Override
            public void initialise() {
            }

            @Override
            public void dispose() throws Exception {
                throw thrown;
            }
        };
    }

    @Test
    void aTabThatCannotBeClosedFailsTheCheckWithTheReason() {
        IOException cause = new IOException("the layout could not be saved");

        IllegalStateException reported = assertThrows(IllegalStateException.class,
                () -> SelfTestAction.close(tabThatThrows(cause)));

        assertEquals("closing it: java.io.IOException: the layout could not be saved",
                reported.getMessage());
        assertSame(cause, reported.getCause());
    }

    @Test
    void anUncheckedFailureIsPassedOnAsItIs() {
        IllegalArgumentException thrown = new IllegalArgumentException("no such view");

        assertSame(thrown, assertThrows(IllegalArgumentException.class,
                () -> SelfTestAction.close(tabThatThrows(thrown))));
    }
}
