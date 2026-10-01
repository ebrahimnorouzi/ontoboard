package de.fizkarlsruhe.ise.ontoboard.views;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Whether this plugin's views actually built, recorded by the views themselves.
 *
 * <p>Exists because the self-test lied, and lied in the one place it was least affordable.
 * {@code SelfTestAction} opened the OntoBoard tab and reported "opened, its views constructed,
 * and closed again" - a sentence, not a check. Protege's {@code View.createContent} catches
 * whatever {@code initialise()} throws and replaces the view with "An error occurred whilst
 * creating the view", so a canvas that died on construction looked from the outside exactly like
 * one that worked. 1.73.0 shipped with the canvas crashing on every open and a smoke receipt
 * recording PASS 10/10, because nothing between the throw and the receipt ever asked.
 *
 * <p>Nothing of Protege's can answer the question: the view component object exists either way,
 * and the only difference is inside a catch block in a class this plugin does not own. So the
 * view answers for itself, before Protege gets the chance to swallow anything.
 *
 * <p>Static, which is right for what this is: one JVM, one bundle, a diagnostic register read by
 * one caller immediately after the thing it describes. {@link #forget()} is called before opening
 * the tab so a run reports on that run.
 */
public final class ViewHealth {

    private static final Set<String> BUILT = new LinkedHashSet<String>();

    private static String failure;

    private ViewHealth() {
    }

    /** Called by a view when its construction finished without throwing. */
    public static synchronized void constructed(String viewName) {
        BUILT.add(viewName);
    }

    /**
     * Called by a view that is about to let a throwable escape its construction.
     *
     * <p>The first failure is kept rather than the last. A view that fails usually fails the
     * same way every time it is reopened, and the first one is the one with the cause in it.
     */
    public static synchronized void failed(String viewName, Throwable broke) {
        if (failure == null) {
            failure = viewName + " threw " + describe(broke);
        }
    }

    /** What failed, with the throwable and where it came from, or null if nothing has. */
    public static synchronized String whatFailed() {
        return failure;
    }

    /** The views that reported themselves built since the last {@link #forget()}. */
    public static synchronized Set<String> built() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(BUILT));
    }

    /** Clears both, so a self-test run reports on that run and not on the session. */
    public static synchronized void forget() {
        BUILT.clear();
        failure = null;
    }

    /**
     * The throwable, its message, and the first frame of ours.
     *
     * <p>The frame matters more than the message: this failure class is overwhelmingly a
     * NullPointerException whose {@code getMessage()} is null, and "NullPointerException: null"
     * in a receipt is a line nobody can act on. The first frame inside this plugin names the
     * method, which is enough to find it.
     */
    private static String describe(Throwable broke) {
        if (broke == null) {
            return "an unknown failure";
        }
        String message = broke.getMessage();
        StringBuilder text = new StringBuilder(broke.getClass().getSimpleName());
        if (message != null && !message.trim().isEmpty()) {
            text.append(": ").append(message.trim());
        }
        StackTraceElement[] frames = broke.getStackTrace();
        if (frames != null) {
            for (StackTraceElement frame : frames) {
                if (frame.getClassName().startsWith("de.fizkarlsruhe.ise.ontoboard")) {
                    text.append(" at ").append(frame.getClassName()).append('.')
                            .append(frame.getMethodName()).append(':').append(frame.getLineNumber());
                    break;
                }
            }
        }
        return text.toString();
    }
}
