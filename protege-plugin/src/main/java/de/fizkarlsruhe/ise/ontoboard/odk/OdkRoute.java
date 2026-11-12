package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

/**
 * Whether ODK's container is used, and which program runs it.
 *
 * <p>OntoBoard runs an ODK project's targets in ODK's own container when one can be run, and by
 * itself when not. Two settings let a person decide instead: whether a container is used at all
 * ({@link Use}), and which program runs it - for a machine where Docker or Podman is installed
 * but not on the PATH Protégé was started with, or where one of the two must not be used.
 *
 * <p>Left alone, OntoBoard asks {@code docker} and {@code podman} from the PATH, then the places
 * they are usually installed to. A program is taken when {@code <program> info} answers: that
 * is the call that needs the engine behind it, not only the program.
 */
public final class OdkRoute {

    /** Whether ODK's targets run in a container. */
    public enum Use {
        /** In a container when one can be run, else by OntoBoard itself. */
        AUTOMATIC("Automatic"),
        /** Only in a container: without one, nothing runs and the reason is said. */
        ALWAYS_CONTAINER("Always in a container"),
        /** Never in a container, whatever is installed. */
        NEVER_CONTAINER("Never in a container");

        private final String label;

        Use(String label) {
            this.label = label;
        }

        /** The words the setting is shown with. */
        public String label() {
            return label;
        }
    }

    static final String USE_KEY = "ontoboard.odk.containerUse";
    static final String PROGRAM_KEY = "ontoboard.odk.containerProgram";

    private OdkRoute() {
    }

    // ------------------------------------------------------------------ the settings

    /**
     * A JVM property that says which settings are in force: unset, those the person at this
     * machine keeps; {@code none}, none at all - a test run sets that, so that what a test
     * finds never depends on what somebody chose for their own Protégé; {@code node:<path>},
     * those kept under another node of the user's preferences.
     */
    static final String SETTINGS_PROPERTY = "ontoboard.odk.settings";

    /** Where the settings are kept: beside the native environment's folder. */
    private static Preferences node() {
        String where = System.getProperty(SETTINGS_PROPERTY, "");
        return where.startsWith("node:") ? Preferences.userRoot().node(where.substring(5))
                : Preferences.userNodeForPackage(Toolchain.class);
    }

    private static boolean noSettings() {
        return "none".equals(System.getProperty(SETTINGS_PROPERTY));
    }

    public static Use use() {
        return noSettings() ? Use.AUTOMATIC : use(node());
    }

    static Use use(Preferences node) {
        String kept = node.get(USE_KEY, "");
        for (Use use : Use.values()) {
            if (use.name().equals(kept)) {
                return use;
            }
        }
        return Use.AUTOMATIC;
    }

    public static void setUse(Use use) {
        setUse(node(), use);
    }

    static void setUse(Preferences node, Use use) {
        if (use == null || use == Use.AUTOMATIC) {
            node.remove(USE_KEY);
        } else {
            node.put(USE_KEY, use.name());
        }
    }

    /** The program a person chose to run containers with, or null when they left it open. */
    public static String containerProgram() {
        return noSettings() ? null : containerProgram(node());
    }

    static String containerProgram(Preferences node) {
        String kept = node.get(PROGRAM_KEY, "").trim();
        return kept.isEmpty() ? null : kept;
    }

    public static void setContainerProgram(String pathOrNull) {
        setContainerProgram(node(), pathOrNull);
    }

    static void setContainerProgram(Preferences node, String pathOrNull) {
        if (pathOrNull == null || pathOrNull.trim().isEmpty()) {
            node.remove(PROGRAM_KEY);
        } else {
            node.put(PROGRAM_KEY, pathOrNull.trim());
        }
    }

    /** Whether, without a container, OntoBoard may run a target itself. */
    public static boolean mayRunWithoutContainer(Use use) {
        return use != Use.ALWAYS_CONTAINER;
    }

    // ------------------------------------------------------------------ the search

    /** The program to run containers with on this machine, by the settings; null for none. */
    public static String runtime(ProcessRunner.Runner runner) {
        if (noSettings()) {
            return runtime(Use.AUTOMATIC, null, new ArrayList<String>(), runner);
        }
        return runtime(use(), containerProgram(), usualPlaces(), runner);
    }

    /**
     * @param chosen      the program a person named, or null
     * @param usualPlaces where the programs are usually installed to, the first that answers wins
     */
    static String runtime(Use use, String chosen, List<String> usualPlaces,
            ProcessRunner.Runner runner) {
        if (use == Use.NEVER_CONTAINER) {
            return null;
        }
        if (chosen != null) {
            // The program they named, and no other: Podman is often chosen because Docker may
            // not be used on the machine.
            return answers(runner, chosen) ? chosen : null;
        }
        for (String name : new String[] {"docker", "podman"}) {
            if (answers(runner, name)) {
                return name;
            }
        }
        for (String place : usualPlaces) {
            if (new File(place).isFile() && answers(runner, place)) {
                return place;
            }
        }
        return null;
    }

    /**
     * Whether a program can actually run something, which is not the same as being installed.
     *
     * <p>{@code docker --version} answers from the CLI alone and says nothing about the engine.
     * On the machine this was written for, the CLI reported 29.5.2 while the daemon was down
     * and every command failed with "failed to connect to the docker API at
     * npipe:////./pipe/dockerDesktopLinuxEngine". {@code docker info} is the one that needs
     * the daemon, so it is the one asked.
     */
    private static boolean answers(ProcessRunner.Runner runner, String program) {
        return ProcessRunner.isAvailable(runner, program, "info");
    }

    /** Whether a program is Podman: by its own name, not by a folder it stands in. */
    public static boolean isPodman(String runtime) {
        if (runtime == null) {
            return false;
        }
        String name = runtime.substring(Math.max(runtime.lastIndexOf('/'),
                runtime.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        return name.equals("podman") || name.startsWith("podman.");
    }

    /** Why no container is used on this machine, by the settings; null when one is. */
    public static String whyNoContainer(ProcessRunner.Runner runner) {
        if (noSettings()) {
            return whyNoContainer(Use.AUTOMATIC, null, new ArrayList<String>(), runner);
        }
        return whyNoContainer(use(), containerProgram(), usualPlaces(), runner);
    }

    static String whyNoContainer(Use use, String chosen, List<String> usualPlaces,
            ProcessRunner.Runner runner) {
        if (use == Use.NEVER_CONTAINER) {
            return "No container is used: OntoBoard's setting for ODK's targets is \""
                    + use.label() + "\".";
        }
        if (runtime(use, chosen, usualPlaces, runner) != null) {
            return null;
        }
        if (chosen != null) {
            return "The program chosen in OntoBoard's settings to run containers with, " + chosen
                    + ", does not answer (\"" + chosen + " info\" fails). Start it, choose "
                    + "another, or clear the choice so that OntoBoard looks for Docker and "
                    + "Podman itself.";
        }
        return "Neither Docker nor Podman answers on this machine: OntoBoard asked both from "
                + "the PATH" + (usualPlaces.isEmpty() ? "" : " and in the places they are "
                + "usually installed to") + ". If one is installed, start it, or name its "
                + "program in OntoBoard's settings.";
    }

    /**
     * Why no container is used on this machine, as the start of a sentence that goes on ("...,
     * so OntoBoard runs ODK's own recipes itself"); null when one is.
     */
    public static String noContainerBecause(ProcessRunner.Runner runner) {
        if (noSettings()) {
            return noContainerBecause(Use.AUTOMATIC, null, new ArrayList<String>(), runner);
        }
        return noContainerBecause(use(), containerProgram(), usualPlaces(), runner);
    }

    static String noContainerBecause(Use use, String chosen, List<String> usualPlaces,
            ProcessRunner.Runner runner) {
        if (use == Use.NEVER_CONTAINER) {
            return "A container is switched off in OntoBoard's settings";
        }
        if (runtime(use, chosen, usualPlaces, runner) != null) {
            return null;
        }
        if (chosen != null) {
            return "The program chosen in OntoBoard's settings to run containers with, " + chosen
                    + ", does not answer";
        }
        return "Neither Docker nor Podman answers on this machine";
    }

    /** Where Docker and Podman are usually installed to on this kind of machine. */
    static List<String> usualPlaces() {
        List<String> places = new ArrayList<String>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String programs = System.getenv("ProgramFiles");
            if (programs != null && !programs.isEmpty()) {
                places.add(programs + "\\Docker\\Docker\\resources\\bin\\docker.exe");
                places.add(programs + "\\RedHat\\Podman\\podman.exe");
            }
        } else if (os.contains("mac")) {
            places.add("/usr/local/bin/docker");
            places.add("/opt/homebrew/bin/docker");
            places.add("/Applications/Docker.app/Contents/Resources/bin/docker");
            places.add("/opt/podman/bin/podman");
            places.add("/opt/homebrew/bin/podman");
            places.add("/usr/local/bin/podman");
        } else {
            places.add("/usr/bin/docker");
            places.add("/usr/local/bin/docker");
            places.add("/snap/bin/docker");
            places.add("/usr/bin/podman");
            places.add("/usr/local/bin/podman");
        }
        return places;
    }
}
