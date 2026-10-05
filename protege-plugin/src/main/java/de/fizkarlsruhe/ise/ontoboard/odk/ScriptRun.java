package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How one of the project's own scripts would be run, and where.
 *
 * <p>The same division of labour as {@link MakeRun}: this decides the route and composes the
 * command, and something else executes it. Keeping the decision separate from the execution is
 * what makes it testable without launching anything, which for a class whose job is to run
 * arbitrary code is not a small point.
 *
 * <p><b>The container is the normal route, not the fallback.</b> Measured on a real ODK project,
 * all three of its scripts need something that exists only inside {@code obolibrary/odkfull} -
 * {@code /usr/bin/time}, {@code /tools/odk.py}, {@code amm}. Offering to run them on the host
 * would offer a failure.
 */
public final class ScriptRun {

    /**
     * Shorter than a build's 45 minutes.
     *
     * <p>A build is a known quantity that legitimately takes an hour. A script is arbitrary code
     * somebody has just chosen to run, and until the process can actually be cancelled - it
     * cannot be today, see {@link de.fizkarlsruhe.ise.ontoboard.menu.BackgroundRun} - the
     * timeout is the only thing that ends a runaway. Fifteen minutes is long enough for every
     * script in the project this was measured against and short enough not to hold a thread for
     * an afternoon.
     */
    public static final long TIMEOUT_MINUTES = 15;

    /** Where a script would run. */
    public enum Route {
        /** Inside the project's own ODK image. The normal answer for a real ODK project. */
        IN_CONTAINER,

        /** In a native ODK environment the user has pointed OntoBoard at. */
        NATIVE,

        /** Directly, with the interpreter on this machine's PATH. */
        HOST,

        /** Nowhere, yet. */
        NOT_RUNNABLE
    }

    /** A decision: the route, the command, and what to say when there is no route. */
    public static final class Plan {
        private final Route route;
        private final List<String> command;
        private final String advice;

        Plan(Route route, List<String> command, String advice) {
            this.route = route;
            this.command = command == null ? null
                    : java.util.Collections.unmodifiableList(new ArrayList<String>(command));
            this.advice = advice;
        }

        public Route getRoute() {
            return route;
        }

        /** The command to run, or null when there is no route. */
        public List<String> getCommand() {
            return command;
        }

        /** What the user needs in order to run this, when they cannot. */
        public String getAdvice() {
            return advice;
        }

        /** The command as one line, which is what the user is shown before it runs. */
        public String asCommandLine() {
            if (command == null) {
                return "";
            }
            StringBuilder line = new StringBuilder();
            for (String part : command) {
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(part.contains(" ") ? "\"" + part + "\"" : part);
            }
            return line.toString();
        }
    }

    private ScriptRun() {
    }

    /**
     * How to run {@code script}, given what this machine has.
     *
     * <p>Container first, because that is where a real ODK project's scripts can actually run;
     * then a native ODK environment; then the host, which is the right answer only for a script
     * a project wrote for itself rather than one ODK supplied.
     *
     * <p>Everything this depends on is a parameter. It would be shorter to read the container
     * runtime and the native environment in here, but the native environment is a user
     * preference, and a decision that reads one silently cannot be tested without the machine
     * it is tested on leaking into the answer.
     *
     * @param ontologyDirectory the project's {@code src/ontology}
     * @param runtime the container runtime, or null when none answers
     * @param activationScript the native ODK environment's activation script, or null
     */
    public static Plan planFor(ProjectScripts.Script script, File ontologyDirectory,
            String runtime, File activationScript, boolean interpreterOnPath) {
        if (script == null || !script.isRunnable()) {
            return new Plan(Route.NOT_RUNNABLE, null,
                    script == null ? "No script was chosen." : script.getWhyNotRunnable());
        }
        String inContainer = ProjectScripts.containerPathOf(script.getFile(), ontologyDirectory);

        if (runtime != null && !runtime.trim().isEmpty() && inContainer != null) {
            return new Plan(Route.IN_CONTAINER,
                    MakeRun.containerCommandFor(runtime, ontologyDirectory,
                            MakeRun.imageFor(ontologyDirectory),
                            Arrays.asList(script.getInterpreter(), inContainer)),
                    null);
        }

        if (activationScript != null) {
            return new Plan(Route.NATIVE,
                    Toolchain.nativeCommandFor(activationScript, script.getInterpreter() + " "
                            + Toolchain.quote(script.getFile().getAbsolutePath())),
                    null);
        }

        if (interpreterOnPath) {
            return new Plan(Route.HOST,
                    Arrays.asList(script.getInterpreter(), script.getFile().getAbsolutePath()),
                    null);
        }

        return new Plan(Route.NOT_RUNNABLE, null,
                "Nothing here can run " + script.getName() + ". It needs "
                        + script.getInterpreter() + ", and this machine has no container "
                        + "runtime, no native ODK environment, and no " + script.getInterpreter()
                        + " on PATH. A real ODK project's scripts usually need the ODK image "
                        + "itself - start Docker or Podman, or point OntoBoard at a native ODK "
                        + "environment under Project > Check requirements.");
    }

    /**
     * What to warn about before running, or null.
     *
     * <p>Only the line endings so far, and only because the failure they cause names the wrong
     * thing: a shell in a Linux container reading a script whose first line ends {@code \r}
     * reports "cannot execute: required file not found", which reads as a missing interpreter.
     * ODK's own documentation warns about it, and every script in the project this was measured
     * against has CRLF endings.
     */
    public static String warningFor(ProjectScripts.Script script, Route route) {
        if (script == null || !script.hasWindowsLineEndings()) {
            return null;
        }
        if (route != Route.IN_CONTAINER && route != Route.NATIVE) {
            return null;
        }
        return script.getName() + " has Windows line endings. A shell inside the container will "
                + "report \"cannot execute: required file not found\", which names the "
                + "interpreter rather than the real cause. Convert it to Unix endings first - "
                + "git's core.autocrlf is the usual reason it looks like this in a checkout.";
    }
}
