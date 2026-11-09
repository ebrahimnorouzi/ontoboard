package de.fizkarlsruhe.ise.ontoboard.assistant.host;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import org.semanticweb.owlapi.model.IRI;

/**
 * How the board's right-click menu reaches the assistant column of the same Protégé window:
 * each column registers under its editor kit, and "Add to assistant context" on the board asks
 * for the column registered under its own kit. Weak, so a closed window is forgotten.
 */
public final class AssistantSessions {

    private static final Map<Object, Consumer<IRI>> VIEWS =
            Collections.synchronizedMap(new WeakHashMap<Object, Consumer<IRI>>());

    private AssistantSessions() {
    }

    public static void register(Object editorKit, Consumer<IRI> adder) {
        if (editorKit != null && adder != null) {
            VIEWS.put(editorKit, adder);
        }
    }

    public static void unregister(Object editorKit) {
        if (editorKit != null) {
            VIEWS.remove(editorKit);
        }
    }

    /** Whether this window has an assistant column to add to, for enabling the menu item. */
    public static boolean canAdd(Object editorKit) {
        return editorKit != null && VIEWS.get(editorKit) != null;
    }

    /** Adds the term to that window's assistant context; false when it has no column. */
    public static boolean addTerm(Object editorKit, IRI iri) {
        Consumer<IRI> adder = editorKit == null ? null : VIEWS.get(editorKit);
        if (adder == null || iri == null) {
            return false;
        }
        adder.accept(iri);
        return true;
    }
}
