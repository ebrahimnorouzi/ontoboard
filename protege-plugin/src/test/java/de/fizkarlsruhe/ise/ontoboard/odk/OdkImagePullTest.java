package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.menu.OperationResult;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ODK's image in Check requirements: which one is here, and the one command that fetches it.
 *
 * <p>Without Docker OntoBoard is complete - it runs ODK's targets itself - so the row is never
 * a failure, and a missing image or a missing container program is said to be fine. The pull is
 * one command, {@code <runtime> pull obolibrary/odkfull:v1.6.1}, run only when the person has
 * said yes to a question that shows it and the size. Here with a made-up machine; no real pull.
 */
class OdkImagePullTest {

    /** A machine's images: {@code reference=the ODK_VERSION in its environment}. */
    private static OdkImage.Probe images(String... referencesAndVersions) {
        final Map<String, String> images = new LinkedHashMap<String, String>();
        for (String entry : referencesAndVersions) {
            int equals = entry.indexOf('=');
            images.put(entry.substring(0, equals), entry.substring(equals + 1));
        }
        return new OdkImage.Probe() {
            @Override
            public boolean isPresent(String reference) {
                return images.containsKey(reference);
            }

            @Override
            public String odkVersionOf(String reference) {
                return images.get(reference);
            }

            @Override
            public List<String> localTags(String repository) {
                List<String> tags = new ArrayList<String>();
                for (String reference : images.keySet()) {
                    if (reference.startsWith(repository + ":")) {
                        tags.add(reference.substring(repository.length() + 1));
                    }
                }
                return tags;
            }
        };
    }

    /** A runner that answers every command with these lines and this exit code. */
    private static final class Answering implements ProcessRunner.Runner {
        final List<List<String>> asked = new ArrayList<List<String>>();
        final List<Long> minutes = new ArrayList<Long>();
        final ProcessRunner.Outcome outcome;
        final IOException cannot;

        Answering(ProcessRunner.Outcome outcome) {
            this.outcome = outcome;
            this.cannot = null;
        }

        Answering(IOException cannot) {
            this.outcome = null;
            this.cannot = cannot;
        }

        @Override
        public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
            asked.add(new ArrayList<String>(command));
            minutes.add(timeoutMinutes);
            if (cannot != null) {
                throw cannot;
            }
            return outcome;
        }
    }

    private static final String PINNED = "obolibrary/odkfull:" + OdkPin.VERSION;

    // ------------------------------------------------------------------ the row

    @Test
    void theImageOnThisMachineIsNamedUnderTheNameItHas() {
        OdkImagePull here = OdkImagePull.of("docker",
                images("obolibrary/odkfull:latest=" + OdkPin.VERSION), OdkRoute.Use.AUTOMATIC);

        Toolchain.Tool row = here.row();
        assertEquals("ODK image " + OdkPin.VERSION, row.getName());
        assertEquals(Toolchain.State.PRESENT, row.getState());
        assertTrue(row.getDetail().contains("obolibrary/odkfull:latest"), row.getDetail());
        assertEquals("obolibrary/odkfull:latest", here.imageHere());
        assertFalse(here.offersThePull(), "it is here: nothing to fetch");
    }

    @Test
    void aMissingImageIsNoFailureAndItsPullIsOffered() {
        OdkImagePull missing = OdkImagePull.of("docker", images("obolibrary/odkfull:v1.5.4=v1.5.4"),
                OdkRoute.Use.AUTOMATIC);

        Toolchain.Tool row = missing.row();
        assertEquals("not on this machine (" + OdkImage.SIZE + ")", row.getDetail());
        assertEquals("about 3.4 GB", OdkImage.SIZE);
        assertNotEquals(Toolchain.State.NOT_WORKING, row.getState(), "never shown as a fault");
        assertTrue(row.getRemedy().contains("OntoBoard runs ODK's targets itself"),
                row.getRemedy());
        assertTrue(row.getRemedy().contains(OdkImagePull.BUTTON), row.getRemedy());
        assertTrue(missing.offersThePull());
        assertNull(missing.imageHere());
        assertEquals(Arrays.asList("docker", "pull", PINNED), missing.command());
        assertEquals("docker pull " + PINNED, missing.commandLine());
    }

    /** The question before a pull shows the size and the one command it will run. */
    @Test
    void theQuestionShowsTheSizeAndTheCommand() {
        OdkImagePull missing = OdkImagePull.of("podman", images(), OdkRoute.Use.AUTOMATIC);

        String question = missing.question();
        assertTrue(question.contains(OdkImage.SIZE), question);
        assertTrue(question.contains("podman pull " + PINNED), question);
        assertTrue(question.contains("OntoBoard runs ODK's targets itself"), question);
        assertTrue(missing.buttonHelp().contains("podman pull " + PINNED), missing.buttonHelp());
    }

    @Test
    void withNoContainerProgramNothingIsMissing() {
        for (OdkRoute.Use use : new OdkRoute.Use[] {OdkRoute.Use.AUTOMATIC,
                OdkRoute.Use.NEVER_CONTAINER}) {
            OdkImagePull none = OdkImagePull.of(null, null, use);

            Toolchain.Tool row = none.row();
            assertEquals(Toolchain.State.ABSENT, row.getState(), use.label());
            assertTrue(row.getDetail().startsWith("not looked for"), row.getDetail());
            assertTrue(row.getRemedy().contains("OntoBoard runs ODK's targets itself"),
                    use.label() + ": " + row.getRemedy());
            assertFalse(none.offersThePull());
            assertThrows(IllegalStateException.class, none::command);
            assertFalse(none.buttonHelp().isEmpty(), "the button says why it is not offered");
        }
        assertTrue(OdkImagePull.of(null, null, OdkRoute.Use.NEVER_CONTAINER).row().getDetail()
                .contains(OdkRoute.Use.NEVER_CONTAINER.label()));
    }

    /**
     * Who settled "Always in a container" has said that ODK's targets wait for one; the row
     * says that of the setting, and is no failure either.
     */
    @Test
    void alwaysInAContainerIsSaidAsTheSettingsDoing() {
        OdkImagePull missing = OdkImagePull.of("docker", images(),
                OdkRoute.Use.ALWAYS_CONTAINER);

        Toolchain.Tool row = missing.row();
        assertNotEquals(Toolchain.State.NOT_WORKING, row.getState());
        assertTrue(row.getRemedy().contains(OdkRoute.Use.ALWAYS_CONTAINER.label()),
                row.getRemedy());
        assertFalse(row.getRemedy().contains("OntoBoard runs ODK's targets itself"),
                "not true under this setting: " + row.getRemedy());
        assertTrue(missing.offersThePull());
    }

    /** A program somebody chose by its path is shown so that it can be pasted. */
    @Test
    void aProgramWithABlankInItsPathIsQuotedWhereItIsShown() {
        String path = "C:\\Program Files\\RedHat\\Podman\\podman.exe";
        OdkImagePull missing = OdkImagePull.of(path, images(), OdkRoute.Use.AUTOMATIC);

        assertEquals(Arrays.asList(path, "pull", PINNED), missing.command());
        assertEquals("\"" + path + "\" pull " + PINNED, missing.commandLine());
    }

    // ------------------------------------------------------------------ the pull

    @Test
    void thePullRunsThatOneCommandAndSaysItIsHereNow() {
        Answering runner = new Answering(new ProcessRunner.Outcome(0, Arrays.asList(
                "v1.6.1: Pulling from obolibrary/odkfull", "Digest: sha256:0123",
                "Status: Downloaded newer image for " + PINNED), false));

        OperationResult result = OdkImagePull.of("docker", images(), OdkRoute.Use.AUTOMATIC)
                .pull(runner);

        assertEquals(Collections.singletonList(Arrays.asList("docker", "pull", PINNED)),
                runner.asked, "one command, and only that");
        assertTrue(runner.minutes.get(0) >= 60, "a download of " + OdkImage.SIZE
                + " is given time: " + runner.minutes);
        assertTrue(result.isSuccess(), result.getSummary());
        assertTrue(result.getSummary().contains(PINNED), result.getSummary());
        assertTrue(result.getLog().toString().contains("Status: Downloaded newer image"),
                "what it printed is kept: " + result.getLog());
    }

    @Test
    void aPullThatFailsSaysWhyAndThatNothingElseChanged() {
        Answering runner = new Answering(new ProcessRunner.Outcome(1, Arrays.asList(
                "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": "
                        + "dial tcp: lookup registry-1.docker.io: no such host"), false));

        OperationResult result = OdkImagePull.of("docker", images(), OdkRoute.Use.AUTOMATIC)
                .pull(runner);

        assertFalse(result.isSuccess());
        assertTrue(result.getSummary().contains("no such host"), result.getSummary());
        assertTrue(result.getSummary().contains("OntoBoard runs ODK's targets itself"),
                result.getSummary());
    }

    @Test
    void aStoppedATimedOutAndAnUnstartablePullAreEachSaidAsWhatTheyAre() {
        OdkImagePull missing = OdkImagePull.of("docker", images(), OdkRoute.Use.AUTOMATIC);

        OperationResult stopped = missing.pull(new Answering(new ProcessRunner.Outcome(-1,
                Collections.singletonList("Downloading"), false, true)));
        OperationResult late = missing.pull(new Answering(new ProcessRunner.Outcome(-1,
                Collections.singletonList("Downloading"), true)));
        OperationResult unstartable = missing.pull(new Answering(
                new IOException("Cannot run program \"docker\"")));

        assertFalse(stopped.isSuccess());
        assertTrue(stopped.getSummary().startsWith("You stopped the pull"), stopped.getSummary());
        assertFalse(late.isSuccess());
        assertTrue(late.getSummary().contains(OdkImagePull.TIMEOUT_MINUTES + " minutes"),
                late.getSummary());
        assertFalse(unstartable.isSuccess());
        assertTrue(unstartable.getSummary().contains("could not be started"),
                unstartable.getSummary());
    }

    /** Asked when the image is here, or when no program answers, it runs nothing at all. */
    @Test
    void thereIsNothingToPullWhenItIsNotOffered() {
        Answering runner = new Answering(new ProcessRunner.Outcome(0,
                Collections.<String>emptyList(), false));

        assertThrows(IllegalStateException.class, () -> OdkImagePull.of("docker",
                images(PINNED + "=" + OdkPin.VERSION), OdkRoute.Use.AUTOMATIC).pull(runner));
        assertThrows(IllegalStateException.class,
                () -> OdkImagePull.of(null, null, OdkRoute.Use.AUTOMATIC).pull(runner));
        assertTrue(runner.asked.isEmpty(), runner.asked.toString());
    }
}
