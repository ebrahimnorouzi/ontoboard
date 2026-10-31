package de.fizkarlsruhe.ise.ontoboard.views;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.DoubleConsumer;
import javax.swing.JComponent;

/**
 * Pinch to zoom on a Mac trackpad.
 *
 * <p>Reported: "pinching fingers together does not zoom in and out in the canvas on the Mac." Java
 * does not turn a pinch into anything a Swing component hears - not a mouse wheel event, which is
 * what a two-finger swipe becomes - but into a magnification gesture that only macOS's own
 * {@code com.apple.eawt.event.GestureUtilities} reports, to listeners registered with it.
 *
 * <p><b>Reached by reflection</b>, because the plugin is compiled against the Java 8 API, which
 * has no {@code com.apple} classes in it, and runs on Windows and Linux where they do not exist.
 * There the listener is simply not installed. The classes are looked up through the bootstrap
 * loader, which is where the JDK keeps them, rather than through the plugin's OSGi loader, which
 * does not see them. On Java 8 they are public API; on Java 11, which Protege 5.6 ships, the
 * package is reachable by reflection under the JDK's default {@code --illegal-access=permit}.
 */
final class PinchZoom {

    private static final String UTILITIES = "com.apple.eawt.event.GestureUtilities";
    private static final String LISTENER = "com.apple.eawt.event.GestureListener";
    private static final String MAGNIFICATION = "com.apple.eawt.event.MagnificationListener";

    private PinchZoom() {
    }

    /**
     * Calls {@code onPinch} with each magnification the trackpad reports over {@code target}: a
     * small positive amount while the fingers spread, negative while they close.
     *
     * @return null when it is installed, otherwise why not - "not a Mac", or what failed
     */
    static String install(JComponent target, DoubleConsumer onPinch) {
        try {
            Class<?> utilities = Class.forName(UTILITIES, true, null);
            Class<?> listenerType = Class.forName(LISTENER, true, null);
            Class<?> magnificationType = Class.forName(MAGNIFICATION, true, null);
            Object listener = Proxy.newProxyInstance(null, new Class<?>[] {magnificationType},
                    handlerFor(onPinch));
            utilities.getMethod("addGestureListenerTo", JComponent.class, listenerType)
                    .invoke(null, target, listener);
            return null;
        } catch (ClassNotFoundException notAMac) {
            return "not a Mac";
        } catch (Exception | LinkageError cannot) {
            return cannot.toString();
        }
    }

    /**
     * What the listener does with a call: {@code magnify(event)} passes the event's magnification
     * on, and the three {@code Object} methods answer as an ordinary object would.
     */
    static InvocationHandler handlerFor(final DoubleConsumer onPinch) {
        return new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments)
                    throws Exception {
                if (method.getDeclaringClass() == Object.class) {
                    if ("equals".equals(method.getName())) {
                        return proxy == arguments[0];
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    return "pinch to zoom";
                }
                if ("magnify".equals(method.getName()) && arguments != null
                        && arguments.length == 1 && arguments[0] != null) {
                    Object amount = arguments[0].getClass().getMethod("getMagnification")
                            .invoke(arguments[0]);
                    if (amount instanceof Number) {
                        onPinch.accept(((Number) amount).doubleValue());
                    }
                }
                return null;
            }
        };
    }
}
