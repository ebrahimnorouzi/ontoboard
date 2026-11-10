package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * "Same as" only for a pattern with exactly its twin's terms.
 *
 * <p>Found by a tester preparing the cookbook scenario: <i>Collection Entity Pattern</i> was listed
 * as "same as collection", while it has the class Entity that <i>Collection Pattern</i> lacks.
 */
class PatternTwinNoteTest {

    @Test
    void aTwinWithOtherTermsIsAnotherVersion() {
        assertEquals("another version of collection",
                PatternLibrary.twinNote(PatternLibrary.find("collection-entity")));
        assertEquals("another version of place",
                PatternLibrary.twinNote(PatternLibrary.find("spatial-object")));
    }

    @Test
    void aTwinWithTheSameTermsIsTheSame() {
        assertEquals("same as collection",
                PatternLibrary.twinNote(PatternLibrary.find("collectionentity")));
        assertEquals("", PatternLibrary.twinNote(PatternLibrary.find("collection")));
    }

    @Test
    void theSummarySaysWhatDiffers() throws Exception {
        DesignPattern entity = PatternLibrary.find("collection-entity");
        String html = PatternSummary.asHtml(entity, PatternLibrary.contentsOf(entity));
        assertFalse(html.contains("under a second name"), html);
        assertTrue(html.contains("declares the same ontology IRI with other terms: this one has 4,"
                + " collection has 3"), html);
    }
}
