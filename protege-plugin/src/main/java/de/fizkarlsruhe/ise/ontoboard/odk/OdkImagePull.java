package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.menu.OperationResult;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * ODK's image in Check requirements: whether it is on this machine, under which name, and the
 * one command that fetches it.
 *
 * <p>Without Docker OntoBoard is complete: it runs ODK's targets itself. So the row is never a
 * fault - a missing image and a missing container program are each said to be fine - and it
 * adds no capability, so it never counts against "everything is available". What the image
 * gives is ODK's own container: the programs a project's continuous integration runs.
 *
 * <p>The pull is one command, {@code <runtime> pull obolibrary/odkfull:<pinned version>}
 * ({@link OdkPin}). It is offered only when a container program answers and the image is not
 * there, and run only by Check requirements after the person has said yes to {@link #question}
 * - which shows the command and the size. Nothing else starts it: OntoBoard never downloads an
 * image on its own ({@link OdkImage}).
 *
 * <p>No Swing: {@code RequirementsAction} asks and shows; this decides and words.
 */
public final class OdkImagePull {

    /** The row's name. */
    public static final String NAME = "ODK image " + OdkPin.VERSION;

    /** The button that offers the pull, as the window and the row name it. */
    public static final String BUTTON = "Pull ODK's image...";

    /** The pull's name, for its progress and its result. */
    public static final String OPERATION = "Pull ODK's image";

    /**
     * How long the pull may take before it is stopped: {@link OdkImage#SIZE} over a slow line
     * takes more than an hour.
     */
    public static final long TIMEOUT_MINUTES = 180;

    /** How many of the last lines the pull printed are kept with its result. */
    private static final int KEPT_LINES = 40;

    private static final String ITSELF = "OntoBoard runs ODK's targets itself";

    private final String runtime;
    private final String here;
    private final OdkRoute.Use use;

    private OdkImagePull(String runtime, String here, OdkRoute.Use use) {
        this.runtime = runtime;
        this.here = here;
        this.use = use == null ? OdkRoute.Use.AUTOMATIC : use;
    }

    /**
     * What Check requirements says about ODK's image.
     *
     * @param runtime the program that runs containers here, by the person's settings
     *                ({@link OdkRoute#runtime}); null when none answers or none is to be used
     * @param probe   what that program says about its images; not asked when there is none
     * @param use     the person's setting for ODK's targets
     */
    public static OdkImagePull of(String runtime, OdkImage.Probe probe, OdkRoute.Use use) {
        String here = runtime == null || probe == null ? null : OdkImage.pinned(probe);
        return new OdkImagePull(runtime, here, use);
    }

    /** The image the pull fetches: {@code obolibrary/odkfull:<pinned version>}. */
    public static String reference() {
        return OdkPin.IMAGE_REPOSITORY + ":" + OdkPin.VERSION;
    }

    /** The program that runs containers here; null when none answers or none is used. */
    public String runtime() {
        return runtime;
    }

    /** The pinned ODK's image, under the name it has on this machine; or null. */
    public String imageHere() {
        return here;
    }

    /** Whether the pull is offered: a container program answers and the image is not here. */
    public boolean offersThePull() {
        return runtime != null && here == null;
    }

    /** The row of Check requirements' list of what is installed. */
    public Toolchain.Tool row() {
        if (here != null) {
            return Toolchain.Tool.of(NAME, Toolchain.State.PRESENT, "on this machine as " + here
                    + (here.equals(reference()) ? "" : " (it is ODK " + OdkPin.VERSION + ")"), "");
        }
        if (runtime != null) {
            return Toolchain.Tool.of(NAME, Toolchain.State.ABSENT,
                    "not on this machine (" + OdkImage.SIZE + ")",
                    (use == OdkRoute.Use.ALWAYS_CONTAINER ? always() + ", so they wait for "
                            + "this image."
                            : "Nothing needs it: " + ITSELF + ". With it, they run in ODK's own "
                                    + "container.")
                            + " " + BUTTON + " below runs: " + commandLine());
        }
        if (use == OdkRoute.Use.NEVER_CONTAINER) {
            return Toolchain.Tool.of(NAME, Toolchain.State.ABSENT,
                    "not looked for: OntoBoard's setting for ODK's targets is \"" + use.label()
                            + "\"",
                    "Nothing needs it: " + ITSELF + ".");
        }
        return Toolchain.Tool.of(NAME, Toolchain.State.ABSENT,
                "not looked for: no program to run containers answers",
                use == OdkRoute.Use.ALWAYS_CONTAINER
                        ? always() + "; with \"" + OdkRoute.Use.AUTOMATIC.label() + "\", "
                                + "OntoBoard runs them itself where there is no container."
                        : "Nothing needs it: " + ITSELF + ".");
    }

    /**
     * The command the pull runs, and nothing else: {@code <runtime> pull <reference>}.
     *
     * @throws IllegalStateException when the pull is not offered
     */
    public List<String> command() {
        if (!offersThePull()) {
            throw new IllegalStateException(here != null ? "ODK's image is here already: " + here
                    : "No program to run containers answers.");
        }
        return Arrays.asList(runtime, "pull", reference());
    }

    /** The command as it is shown, ready to paste into a terminal. */
    public String commandLine() {
        List<String> command = command();
        String program = command.get(0);
        return (program.indexOf(' ') >= 0 ? "\"" + program + "\"" : program) + " "
                + command.get(1) + " " + command.get(2);
    }

    /** What the person is asked before anything is fetched: the command and the size. */
    public String question() {
        return "OntoBoard will run this one command:\n\n    " + commandLine() + "\n\n"
                + "It downloads ODK " + OdkPin.VERSION + "'s image, " + OdkImage.SIZE + ", and "
                + "can take a while; you can stop it while it runs.\n\n"
                + (use == OdkRoute.Use.ALWAYS_CONTAINER
                        ? always() + ": they run in this image once it is here."
                        // Two lines: a message dialog does not wrap what it shows.
                        : "Nothing needs it: " + ITSELF + ".\nWith the image, they run in "
                                + "ODK's own container - the programs a project's continuous "
                                + "integration runs.");
    }

    /** The button's help: what it runs, or why it is not offered. */
    public String buttonHelp() {
        if (offersThePull()) {
            return "Runs " + commandLine() + " (" + OdkImage.SIZE + "), after asking.";
        }
        if (here != null) {
            return "ODK's image is on this machine: " + here + ".";
        }
        return use == OdkRoute.Use.NEVER_CONTAINER
                ? "OntoBoard's setting for ODK's targets is \"" + use.label() + "\"."
                : "No program to run containers answers, so there is nothing to pull it with.";
    }

    /**
     * Runs the pull - {@link #command}, once - and says how it went. For Check requirements,
     * after the person said yes to {@link #question}; it runs off the event thread, where the
     * progress dialog's Stop ends the program ({@link ProcessRunner.Cancellation}).
     *
     * @throws IllegalStateException when the pull is not offered; nothing is run then
     */
    public OperationResult pull(ProcessRunner.Runner runner) {
        List<String> command = command();
        String shown = commandLine();
        OperationResult.Builder result = OperationResult.of(OPERATION).note("Command: " + shown);
        long started = System.currentTimeMillis();
        ProcessRunner.Outcome outcome;
        try {
            outcome = runner.run(null, command, TIMEOUT_MINUTES, null);
        } catch (IOException | RuntimeException cannot) {
            return result.failed(command.get(0) + " could not be started: " + cannot.getMessage()
                    + ". Nothing was downloaded. " + asBefore()).build();
        }
        List<String> output = outcome.getOutput();
        for (String line : output.subList(Math.max(0, output.size() - KEPT_LINES),
                output.size())) {
            result.note(line);
        }
        if (outcome.wasCancelled()) {
            return result.failed("You stopped the pull. " + command.get(0) + " may keep the "
                    + "layers it had finished, so that a later pull goes on from them. "
                    + asBefore()).build();
        }
        if (outcome.timedOut()) {
            return result.failed("The pull did not finish in " + TIMEOUT_MINUTES + " minutes and "
                    + "was stopped. " + asBefore()).build();
        }
        if (!outcome.isSuccess()) {
            String why = outcome.lastMeaningfulLine();
            return result.failed("The pull did not finish" + (why.isEmpty() ? "." : ": " + why
                    + (why.endsWith(".") ? "" : ".")) + " " + asBefore()).build();
        }
        long seconds = (System.currentTimeMillis() - started) / 1000;
        return result.summary("ODK's image is on this machine now: " + reference() + ", pulled "
                + "in " + seconds + "s. ODK's targets run in its container from now on, where "
                + "OntoBoard's setting allows one.").build();
    }

    /** What stays as it was after a pull that did not finish. */
    private String asBefore() {
        return use == OdkRoute.Use.ALWAYS_CONTAINER ? "Nothing else was changed."
                : "Nothing else was changed: " + ITSELF + ", as before.";
    }

    private String always() {
        return "OntoBoard's setting for ODK's targets is \""
                + OdkRoute.Use.ALWAYS_CONTAINER.label() + "\"";
    }
}
