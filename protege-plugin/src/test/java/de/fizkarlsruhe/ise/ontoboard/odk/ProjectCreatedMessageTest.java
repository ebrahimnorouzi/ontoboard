package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;

/**
 * "Project created" says what was made and how to check it, and sends nobody to a second guide.
 *
 * <p>It said "Next: OntoBoard &gt; Start here: the guide... walks you through the rest." A study
 * participant follows a guide of their own, whose T1 says only "A window says what was created.
 * Press OK again", and read that as another guide to follow (egg A, 1.135.1). The board the
 * message names is empty at that moment and offers the guide as its first button, for whoever
 * has none.
 */
class ProjectCreatedMessageTest {

    @Test
    void aNewProjectsMessageGivesNoSecondGuideToFollow() {
        String said = ProjectWizard.createdMessage(17, new File("work", "eggboil"),
                "eggboil-edit.owl");

        assertFalse(said.contains("Next:"), said);
        assertFalse(said.contains("Start here: the guide"), said);
        assertFalse(said.contains("walks you through"), said);
        assertTrue(said.startsWith("Created 17 files in\n"), said);
        assertTrue(said.contains("Editing eggboil-edit.owl - the file you work in."), said);
        assertTrue(said.contains("OntoBoard > Build and release > Build... and choose test"), said);
        assertTrue(said.endsWith("Nothing else needs installing."), said);
    }

    @Test
    void neitherDoesAnAdoptedOntologysMessage() {
        String said = ProjectWizard.adoptedMessage(17, new File("work", "shop"), "shop-edit.owl",
                "shop.owl");

        assertFalse(said.contains("Next:"), said);
        assertFalse(said.contains("Start here: the guide"), said);
        assertTrue(said.contains("Your ontology is now the project's edit file, shop-edit.owl"),
                said);
        assertTrue(said.contains("shop.owl is left as it was."), said);
        assertTrue(said.endsWith("Nothing else needs installing."), said);
    }

    /** Still three paragraphs, which is what {@link ProjectWizard#wrapped} lays out. */
    @Test
    void itStillWrapsAsBefore() {
        String html = ProjectWizard.wrapped(ProjectWizard.createdMessage(17,
                new File("work", "eggboil"), "eggboil-edit.owl"));

        assertTrue(html.contains("width:400px"), html);
        assertTrue(html.split("<br><br>").length == 3, html);
    }
}
