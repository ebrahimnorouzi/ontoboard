package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.swing.handler.mxGraphHandler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Constructing the canvas component, which is what 1.73.0 broke.
 *
 * <p>The failure was a textbook one and the comment next door already warned about it:
 * {@code mxGraphHandler}'s constructor calls {@code setVisible(false)} at line 277, that
 * dispatches to {@link GuideGraphHandler}'s override, and a subclass field initialiser has not
 * run yet - it runs after {@code super(...)} returns. So the override read a null list and threw,
 * the component's constructor threw, {@code initialiseOWLView} threw, and Protege replaced the
 * whole canvas with "An error occurred whilst creating the view".
 *
 * <p>What made it ship is the more useful part. 1,423 unit tests passed, both hosts smoked PASS
 * with the tab self-test reporting "opened, its views constructed", and none of that touched this
 * constructor: the suite built graphs and projections and never a component, and the self-test's
 * sentence was a claim rather than a check. One line of test here closes the first half of that;
 * {@code ViewHealth} closes the second.
 *
 * <p><b>These need a display, and the first version of this file claimed they did not.</b> The
 * comment here read "Swing objects are merely constructed, never realised, so this runs
 * headless". That was wrong: {@code mxGraphComponent}'s constructor reaches far enough into AWT
 * to throw {@code HeadlessException}, which passed unnoticed on a developer machine and failed
 * the first CI run that tried to publish a release. Written the same way the bug it guards was
 * written - from what seemed obvious rather than from a run.
 *
 * <p>So they are skipped where there is no display. That is a real loss: CI is exactly where a
 * regression by someone else would be caught. It is acceptable only because the host smoke
 * covers the same ground from the other side - {@code ViewHealth} makes the self-test report
 * whether {@code SchemaCanvasView} built, and building it constructs this component - so the
 * question "can the canvas be constructed" is answered on every release by a real Protege on
 * two hosts, and answered here whenever a developer runs the suite.
 */
class GuideGraphHandlerTest {

    /**
     * No display, no component. Skipped rather than failed: the absence of a screen is not a
     * defect in the plugin, and a red suite on a headless machine would train people to ignore it.
     */
    @BeforeEach
    void requireADisplay() {
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(),
                "constructing an mxGraphComponent needs a display; the host smoke covers this "
                        + "case on a real Protege instead");
    }

    /** The whole bug, in one line. */
    @Test
    void theCanvasComponentCanBeConstructed() {
        assertDoesNotThrow(() -> new CollaborativeGraphComponent(new SchemaGraph()),
                "mxGraphComponent's constructor calls setVisible on the graph handler before a "
                        + "subclass field initialiser has run, and 1.73.0 shipped a handler that "
                        + "dereferenced a field at that moment");
    }

    /** The handler that got installed is ours, or the guides are wired to nothing. */
    @Test
    void theInstalledHandlerIsTheOneThatDrawsGuides() {
        CollaborativeGraphComponent component = new CollaborativeGraphComponent(new SchemaGraph());

        mxGraphHandler handler = component.getGraphHandler();

        assertInstanceOf(GuideGraphHandler.class, handler,
                "createGraphHandler has to be the one Protege ends up with");
    }

    /**
     * Guides are readable before any drag, and before the field holding them exists.
     *
     * <p>Not a null check for its own sake: the component's paint asks for them on every repaint,
     * including the first, which can happen before anything has been dragged.
     */
    @Test
    void thereAreNoGuidesBeforeADrag() {
        GuideGraphHandler handler =
                (GuideGraphHandler) new CollaborativeGraphComponent(new SchemaGraph())
                        .getGraphHandler();

        assertNotNull(handler.getGuides(), "never null, because the paint path reads it");
        assertTrue(handler.getGuides().isEmpty());
    }

    /** The gesture mode is readable from the start, for the same reason. */
    @Test
    void theGestureModeHasAValueFromTheStart() {
        CollaborativeGraphComponent component = new CollaborativeGraphComponent(new SchemaGraph());

        assertNotNull(component.getGestureMode());
        assertTrue(CanvasGesture.leftDragPans(component.getGestureMode(), false, false, false),
                "a fresh canvas pans on a plain drag");
    }
}
