package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What was saved while a run went on behind - Keep working - so that the release can say so
 * before it commits: the release may hold some of it or none, and its commit would take it as it
 * is now (the 1.155.0 review's I3).
 */
class SavedMeanwhileTest {

    @Test
    void whatWasSavedInTheProjectSinceTheStartIsNamedAsTheProjectNamesIt(@TempDir File dir) {
        File root = new File(dir, "egg");
        File edit = new File(root, "src/ontology/egg-edit.owl");
        File sheet = new File(root, "src/templates/terms.tsv");
        File elsewhere = new File(dir, "other/src/ontology/other-edit.owl");

        SavedMeanwhile.saved(edit, 1000L);
        SavedMeanwhile.saved(sheet, 2000L);
        SavedMeanwhile.saved(elsewhere, 2000L);

        assertEquals(Arrays.asList("src/ontology/egg-edit.owl", "src/templates/terms.tsv"),
                SavedMeanwhile.under(root, 1000L));
        assertEquals(Arrays.asList("src/templates/terms.tsv"), SavedMeanwhile.under(root, 1500L));
        assertTrue(SavedMeanwhile.under(root, 2500L).isEmpty());
    }

    /** Saved twice: the later time counts, and it is named once. */
    @Test
    void aFileSavedAgainIsNamedOnce(@TempDir File dir) {
        File root = new File(dir, "egg");
        File edit = new File(root, "src/ontology/egg-edit.owl");

        SavedMeanwhile.saved(edit, 1000L);
        SavedMeanwhile.saved(edit, 3000L);

        assertEquals(Arrays.asList("src/ontology/egg-edit.owl"), SavedMeanwhile.under(root, 2000L));
    }
}
