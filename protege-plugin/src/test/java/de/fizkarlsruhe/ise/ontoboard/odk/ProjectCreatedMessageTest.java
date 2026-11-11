package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * Since a new project is ODK's seed, the message says who wrote it - ODK's container, or
     * ODK's templates inside OntoBoard - and whether git has it. In the first paragraph, under
     * the folder: the message keeps its three.
     */
    @Test
    void itSaysHowTheProjectWasMadeAndKeepsItsThreeParagraphs() {
        String how = "It is the project ODK v1.6.1 writes, made from ODK's templates inside "
                + "OntoBoard. It is a git repository, and these files are its first commit.";

        String said = ProjectWizard.createdMessage(51, new File("work", "eggboil"),
                "eggboil-edit.owl", how);
        String adopted = ProjectWizard.adoptedMessage(51, new File("work", "shop"),
                "shop-edit.owl", "shop.owl", how);

        assertTrue(said.startsWith("Created 51 files in\n"), said);
        assertTrue(said.contains("eggboil\n" + how + "\n\nEditing eggboil-edit.owl"), said);
        assertTrue(adopted.contains("shop\n" + how + "\n\nYour ontology is now"), adopted);
        assertEquals(3, ProjectWizard.wrapped(said).split("<br><br>").length);
        assertEquals(3, ProjectWizard.wrapped(adopted).split("<br><br>").length);
        assertEquals(ProjectWizard.createdMessage(51, new File("work", "eggboil"),
                "eggboil-edit.owl"), ProjectWizard.createdMessage(51,
                        new File("work", "eggboil"), "eggboil-edit.owl", " "));
    }

    /** A project that could not be written, or was stopped, says so and that nothing is left. */
    @Test
    void aProjectThatWasNotCreatedSaysWhy() {
        String said = ProjectWizard.notCreatedMessage("Stopped after 3s.");

        assertTrue(said.startsWith("The project was not created, and nothing is left"), said);
        assertTrue(said.endsWith("\n\nStopped after 3s."), said);
        assertTrue(ProjectWizard.notCreatedMessage(null).endsWith("No reason was given."));
    }
}
