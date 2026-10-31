package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class PinchZoomTest {

    /** Stands in for macOS's MagnificationListener: one method, given the event. */
    public interface Magnifies {
        void magnify(Object event);
    }

    /** Stands in for macOS's MagnificationEvent. */
    public static final class Pinch {
        private final double amount;

        Pinch(double amount) {
            this.amount = amount;
        }

        public double getMagnification() {
            return amount;
        }
    }

    /** Each pinch is passed on as the amount the trackpad reported, spreading and closing. */
    @Test
    void aPinchIsPassedOnAsItsMagnification() {
        final List<Double> heard = new ArrayList<Double>();
        Magnifies listener = (Magnifies) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Magnifies.class}, PinchZoom.handlerFor(heard::add));

        listener.magnify(new Pinch(0.04));
        listener.magnify(new Pinch(-0.02));

        assertEquals("[0.04, -0.02]", heard.toString());
        assertEquals(listener, listener, "and it behaves as an object");
        assertNotEquals(listener, new Object());
        assertEquals("pinch to zoom", listener.toString());
    }

    /**
     * On a Mac the listener installs - which is the part that could fail, since the plugin reaches
     * a JDK-internal package of Java 11 by reflection.
     */
    @Test
    void itInstallsOnAMac() {
        assumeTrue(System.getProperty("os.name", "").startsWith("Mac"), "a Mac's JDK only");
        assertNull(PinchZoom.install(new JPanel(), amount -> { }));
    }

    /** Anywhere else it says why, and nothing is installed. */
    @Test
    void elsewhereItSaysWhy() {
        assumeTrue(!System.getProperty("os.name", "").startsWith("Mac"), "not on a Mac");
        assertEquals("not a Mac", PinchZoom.install(new JPanel(), amount -> { }));
    }
}
