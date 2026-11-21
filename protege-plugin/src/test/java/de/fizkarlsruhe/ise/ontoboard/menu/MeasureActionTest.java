package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import org.junit.jupiter.api.Test;

/** Measure's own words: the name the ontology's default prefix goes by (1.155.0). */
class MeasureActionTest {

    /** As ODK names a project's edit file: nfdicore-edit.owl is nfdicore's. */
    @Test
    void theDefaultPrefixIsNamedAsTheFileIs() {
        assertEquals("nfdicore", MeasureAction.ownName(new File("nfdicore-edit.owl")));
        assertEquals("pizza", MeasureAction.ownName(new File("pizza.owl")));
        assertEquals("egg", MeasureAction.ownName(new File("C:/x/egg-edit.obo")));
        assertEquals("notes-edited", MeasureAction.ownName(new File("notes-edited.ttl")));
    }

    /**
     * A warning is a count above nought, a "true", or a breakdown that names something: ROBOT
     * says why nfdicore is not in OWL 2 DL as "UseOfUndeclaredAnnotationProperty 1", which was
     * left in the table and not raised (1.155.0).
     */
    @Test
    void aBreakdownThatNamesSomethingIsAWarning() {
        assertEquals(true, MeasureAction.isConcerning("Outside OWL 2 DL",
                "UseOfUndeclaredAnnotationProperty 1"));
        assertEquals(true, MeasureAction.isConcerning("Terms used and not declared", "2"));
        assertEquals(true, MeasureAction.isConcerning("A class among its own superclasses",
                "true"));
        assertEquals(false, MeasureAction.isConcerning("Terms used and not declared", "0"));
        assertEquals(false, MeasureAction.isConcerning("A class among its own superclasses",
                "false"));
        assertEquals(false, MeasureAction.isConcerning("Outside OWL 2 DL", ""));
    }
}
