package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

/** Escape in the dialogs built from a plain JDialog: it presses their Close button. */
class DialogKeysTest {

    @Test
    void escapePressesClose() {
        JRootPane root = new JRootPane();
        JButton close = new JButton("Close");
        final AtomicInteger closed = new AtomicInteger();
        close.addActionListener(event -> closed.incrementAndGet());

        DialogKeys.escapePresses(root, close);

        Object key = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        assertEquals(DialogKeys.CLOSE, key, "Escape is bound for the whole window");
        Action action = root.getActionMap().get(key);
        assertNotNull(action);
        action.actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, "escape"));
        assertEquals(1, closed.get(), "what Close does, Escape does - stopping a load too");

        close.setEnabled(false);
        action.actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, "escape"));
        assertEquals(1, closed.get(), "a disabled Close is not pressed");
    }
}
