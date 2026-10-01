package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What this machine can actually do, asked once and answered in full.
 *
 * <p>Written because every answer this plugin gave about a missing tool was given at the moment
 * the user had already tried to do something, one sentence at a time, and only about the one tool
 * that happened to be checked first. A person who wanted to know whether their machine was set up
 * had no way to ask. This is the question made askable: OntoBoard &gt; Check requirements.
 *
 * <p>Two layers, deliberately. {@link Tool} is what is installed - a fact about the machine.
 * {@link Capability} is what the user can do - which is what they actually want to know, and is
 * never a single tool: building an ODK repository needs any one of a native ODK environment,
 * Docker, or Podman, and building a scaffolded project needs nothing at all.
 *
 * <p>Nothing here is cached. A probe costs a handful of process launches, the answer changes when
 * somebody starts Docker Desktop, and a cached "not installed" that outlives the install is worse
 * than the wait.
 */
public final class Toolchain {

    /** Whether a thing is there and working. */
    public enum State {
        /** Present and answering. */
        PRESENT,
        /** Installed, but not currently able to do the job - Docker with its daemon stopped. */
        NOT_WORKING,
        /** Not found at all. */
        ABSENT
    }

    /** One program, and what to do when it is not there. */
    public static final class Tool {
        private final String name;
        private final State state;
        private final String detail;
        private final String remedy;

        Tool(String name, State state, String detail, String remedy) {
            this.name = name;
            this.state = state;
            this.detail = detail;
            this.remedy = remedy;
        }

        public String getName() {
            return name;
        }

        public State getState() {
            return state;
        }

        /** What was found, or why not. Never null. */
        public String getDetail() {
            return detail == null ? "" : detail;
        }

        /** What the user should do about it, or empty when there is nothing to do. */
        public String getRemedy() {
            return remedy == null ? "" : remedy;
        }
    }

    /** One thing the user might want to do, and whether they can. */
    public static final class Capability {
        private final String name;
        private final boolean available;
        private final String how;

        Capability(String name, boolean available, String how) {
            this.name = name;
            this.available = available;
            this.how = how;
        }

        public String getName() {
            return name;
        }

        public boolean isAvailable() {
            return available;
        }

        /** How it will be done, or what is missing. */
        public String getHow() {
            return how;
        }
    }

    /** Everything the probe found. */
    public static final class Report {
        private final List<Tool> tools;
        private final List<Capability> capabilities;

        Report(List<Tool> tools, List<Capability> capabilities) {
            this.tools = tools;
            this.capabilities = capabilities;
        }

        public List<Tool> getTools() {
            return tools;
        }

        public List<Capability> getCapabilities() {
            return capabilities;
        }

        /** True when everything the user could want to do, they can do. */
        public boolean isEverythingAvailable() {
            for (Capability capability : capabilities) {
                if (!capability.isAvailable()) {
                    return false;
                }
            }
            return true;
        }
    }

    private Toolchain() {
    }

    /** True on Windows, where several remedies differ and one is unavailable. */
    static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Where a native ODK environment has been configured, or null.
     *
     * <p>{@code odk install /path/to/env} initialises a directory with the ODK tools in it and a
     * {@code bin/activate-odk-environment.sh} to put them on the PATH. There is no registry of
     * such directories and no fixed location, so the path is a preference the user sets once -
     * guessing at it would mean either searching the disk or being wrong.
     */
    public static File nativeEnvironment() {
        String configured = java.util.prefs.Preferences
                .userNodeForPackage(Toolchain.class).get(NATIVE_ENV_KEY, "");
        if (configured.trim().isEmpty()) {
            return null;
        }
        File directory = new File(configured.trim());
        return activationScript(directory) == null ? null : directory;
    }

    /** The preference key holding the native environment's directory. */
    public static final String NATIVE_ENV_KEY = "ontoboard.odk.nativeEnvironment";

    /** Remembers where the native ODK environment is, or forgets it when given null. */
    public static void setNativeEnvironment(File directory) {
        java.util.prefs.Preferences node =
                java.util.prefs.Preferences.userNodeForPackage(Toolchain.class);
        if (directory == null) {
            node.remove(NATIVE_ENV_KEY);
        } else {
            node.put(NATIVE_ENV_KEY, directory.getAbsolutePath());
        }
    }

    /**
     * The activation script inside a candidate directory, or null if this is not one.
     *
     * <p>Accepts the directory itself or its {@code bin}, because a user asked to point at "the
     * ODK environment" will reasonably pick either.
     */
    public static File activationScript(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        File inBin = new File(new File(directory, "bin"), ACTIVATE);
        if (inBin.isFile()) {
            return inBin;
        }
        File here = new File(directory, ACTIVATE);
        return here.isFile() ? here : null;
    }

    private static final String ACTIVATE = "activate-odk-environment.sh";

    /** Probes the machine. Costs a handful of process launches. */
    public static Report probe(ProcessRunner.Runner runner) {
        List<Tool> tools = new ArrayList<Tool>();

        File nativeEnv = nativeEnvironment();
        tools.add(nativeEnv != null
                ? new Tool("Native ODK environment", State.PRESENT, nativeEnv.getAbsolutePath(), "")
                : new Tool("Native ODK environment", State.ABSENT,
                        "not configured",
                        isWindows()
                                ? "Not available on Windows - ODK supports native environments on "
                                        + "Linux and macOS only. Use Docker or Podman here."
                                : "Install the odk-core Python package "
                                        + "('python -m pip install odk-core'), run "
                                        + "'odk install /path/to/env', then point OntoBoard at "
                                        + "that directory with the Choose button."));

        boolean dockerThere = ProcessRunner.isAvailable(runner, "docker", "--version");
        boolean dockerWorks = dockerThere && ProcessRunner.isAvailable(runner, "docker", "info");
        tools.add(new Tool("Docker",
                dockerWorks ? State.PRESENT : dockerThere ? State.NOT_WORKING : State.ABSENT,
                dockerWorks ? "installed and running"
                        : dockerThere ? "installed, but the engine is not responding"
                                : "not found",
                dockerWorks ? ""
                        : dockerThere
                                ? "Start Docker Desktop and wait until it reports that it is "
                                        + "running."
                                : "https://www.docker.com/products/docker-desktop/"));

        boolean podmanThere = ProcessRunner.isAvailable(runner, "podman", "--version");
        boolean podmanWorks = podmanThere && ProcessRunner.isAvailable(runner, "podman", "info");
        tools.add(new Tool("Podman",
                podmanWorks ? State.PRESENT : podmanThere ? State.NOT_WORKING : State.ABSENT,
                podmanWorks ? "installed and running"
                        : podmanThere ? "installed, but not responding"
                                : "not found",
                podmanWorks || dockerWorks ? ""
                        : "An alternative to Docker Desktop, with the same command line: "
                                + "https://podman.io/"));

        boolean make = ProcessRunner.isAvailable(runner, "make", "--version");
        tools.add(new Tool("GNU make", make ? State.PRESENT : State.ABSENT,
                make ? "on the PATH" : "not found",
                make ? ""
                        : isWindows()
                                ? "'choco install make' or 'scoop install make'. Only needed for "
                                        + "a project OntoBoard did not scaffold."
                                : "Install it with your package manager - 'apt install make' or "
                                        + "'brew install make'."));

        boolean shell = ProcessRunner.isAvailable(runner, "sh", "--version");
        tools.add(new Tool("POSIX shell", shell ? State.PRESENT : State.ABSENT,
                shell ? "on the PATH" : "not found",
                shell || !isWindows() ? ""
                        : "make needs one to run recipes that use rm, mkdir, cp and date. "
                                + "Installing Git for Windows provides it."));

        boolean odk = ProcessRunner.isAvailable(runner, "odk", "--version");
        tools.add(new Tool("odk (ODK Core)", odk ? State.PRESENT : State.ABSENT,
                odk ? "on the PATH" : "not found",
                odk ? ""
                        : "'python -m pip install odk-core'. This gives the odk command for "
                                + "seeding and updating repositories; it does not by itself "
                                + "provide the build tools."));

        tools.add(javaTool());

        return new Report(tools, capabilities(nativeEnv != null, dockerWorks, podmanWorks, make));
    }

    /** The JRE this plugin is running in, which is always present by construction. */
    private static Tool javaTool() {
        String version = System.getProperty("java.version");
        return new Tool("Java", State.PRESENT,
                version == null ? "running" : version + ", running this plugin", "");
    }

    /**
     * What the user can do, derived from what is installed.
     *
     * <p>Separated from the tool list because it is the half that answers the question. "Podman:
     * not found" means nothing to somebody who has Docker; "Build an ODK repository: yes, through
     * Docker" means something to everybody.
     */
    static List<Capability> capabilities(boolean nativeEnv, boolean docker, boolean podman,
            boolean make) {
        List<Capability> list = new ArrayList<Capability>();

        // Everything the plugin does itself. Stated because users ask, and because the honest
        // answer is the selling point: robot-core is embedded, so none of it needs anything.
        list.add(new Capability("Edit, draw, and run every ROBOT operation in the menu", true,
                "Built in - ROBOT runs inside Protege, with nothing to install"));

        list.add(new Capability("Create a new ODK project", true,
                "Built in - the scaffold is written directly, with nothing to install"));

        list.add(new Capability("Build a project OntoBoard scaffolded", true,
                "Built in - the generated targets run inside Protege, with nothing to install"));

        if (nativeEnv) {
            list.add(new Capability("Build an existing ODK repository", true,
                    "Through the native ODK environment, with no container"));
        } else if (docker) {
            list.add(new Capability("Build an existing ODK repository", true,
                    "Through Docker, in the project's own ODK image"));
        } else if (podman) {
            list.add(new Capability("Build an existing ODK repository", true,
                    "Through Podman, in the project's own ODK image"));
        } else {
            list.add(new Capability("Build an existing ODK repository", false,
                    isWindows()
                            ? "Needs Docker or Podman. ODK's own tools are Linux and macOS only, "
                                    + "so on Windows a container is the only route - that is "
                                    + "ODK's requirement, not OntoBoard's."
                            : "Needs a native ODK environment, Docker, or Podman. The native "
                                    + "environment is the only one of the three with no "
                                    + "container: see the odk-core row above."));
        }

        if (!nativeEnv && !docker && !podman && make) {
            list.add(new Capability("Run a plain Makefile that is not an ODK one", true,
                    "With make from your PATH"));
        }

        return list;
    }

    /**
     * A one-line summary for a status bar or a menu tooltip.
     *
     * <p>Counts capabilities rather than tools, for the same reason the two are separated: nobody
     * needs all six programs, and a count of what is missing would read as alarming when the
     * machine is in fact fully able.
     */
    public static String summary(Report report) {
        int blocked = 0;
        for (Capability capability : report.getCapabilities()) {
            if (!capability.isAvailable()) {
                blocked++;
            }
        }
        if (blocked == 0) {
            return "Everything OntoBoard can do is available on this machine.";
        }
        return blocked == 1
                ? "One thing is unavailable on this machine; the rest works."
                : blocked + " things are unavailable on this machine; the rest works.";
    }

    /** The whole report as text, for pasting into an issue. */
    public static String asText(Report report) {
        StringBuilder text = new StringBuilder("OntoBoard requirements\n\n");
        text.append("What you can do\n");
        for (Capability capability : report.getCapabilities()) {
            text.append(capability.isAvailable() ? "  [yes] " : "  [no ] ")
                    .append(capability.getName()).append(" - ").append(capability.getHow())
                    .append('\n');
        }
        text.append("\nWhat is installed\n");
        for (Tool tool : report.getTools()) {
            text.append("  ").append(symbolFor(tool.getState())).append(' ')
                    .append(tool.getName()).append(" - ").append(tool.getDetail());
            if (!tool.getRemedy().isEmpty()) {
                text.append("\n        ").append(tool.getRemedy());
            }
            text.append('\n');
        }
        text.append("\nos.name=").append(System.getProperty("os.name"))
                .append(" java.version=").append(System.getProperty("java.version")).append('\n');
        return text.toString();
    }

    private static String symbolFor(State state) {
        if (state == State.PRESENT) {
            return "[ok  ]";
        }
        return state == State.NOT_WORKING ? "[idle]" : "[none]";
    }

    /**
     * The command that runs a target through a native ODK environment.
     *
     * <p>The environment is activated by sourcing a shell script, which a Java process cannot do
     * to itself - so the shell does both in one invocation. {@code .} rather than {@code source}
     * because the script is POSIX and {@code source} is a bashism that {@code dash}, which is
     * {@code /bin/sh} on Debian and Ubuntu, does not have.
     */
    public static List<String> nativeCommand(File activationScript, String target) {
        if (activationScript == null) {
            throw new IllegalArgumentException("no activation script");
        }
        if (target == null || target.trim().isEmpty()) {
            throw new IllegalArgumentException("no target to make");
        }
        return Arrays.asList("sh", "-c",
                ". " + quote(activationScript.getAbsolutePath()) + " && make "
                        + target.trim());
    }

    /** Single-quoted for {@code sh}, with embedded quotes closed and reopened. */
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Reads a tool's version line, for the detail column. Empty when it cannot be read. */
    static String firstLine(ProcessRunner.Runner runner, String tool, String flag) {
        try {
            ProcessRunner.Outcome outcome =
                    runner.run(null, Arrays.asList(tool, flag), 1, null);
            List<String> output = outcome.getOutput();
            return output.isEmpty() ? "" : output.get(0).trim();
        } catch (IOException notThere) {
            return "";
        } catch (RuntimeException notThere) {
            return "";
        }
    }
}
