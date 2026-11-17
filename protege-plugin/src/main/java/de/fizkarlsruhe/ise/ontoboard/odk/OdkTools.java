package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.assistant.BuildInfo;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import de.fizkarlsruhe.ise.ontoboard.tools.HttpsOpener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParserBuilder;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.ErrorHandler;
import org.apache.jena.riot.system.StreamRDFLib;

/**
 * The programs ODK's recipes name, as OntoBoard runs them itself: the shell's own commands, and
 * {@code robot}, {@code odk-info}, {@code dicer-cli} and {@code check-rdfxml} - the four that in
 * ODK's image are programs of their own. Each says, before anything of a target runs, whether it
 * can run a call ({@link RecipeShell.Tool#whyNot}); each says what it says the way the image's
 * program says it, where that is true here, and says what is true here where it is not.
 */
final class OdkTools {

    private OdkTools() {
    }

    /**
     * The ROBOT this bundle embeds, as {@code odk-info} says it.
     *
     * <p>Read from {@code ontoboard-build.properties}, which Maven fills in from the single
     * {@code robot.version} property in {@code pom.xml} - the same property that picks the
     * robot-core this bundle embeds. So what is said and what runs cannot drift apart,
     * because there is only one of them.
     */
    static final String ROBOT_VERSION = buildProperty("robot.version");

    /**
     * One value out of the filtered build properties.
     *
     * @throws IllegalStateException when the resource is missing or was never filtered - both
     *     mean the bundle was built wrongly
     */
    private static String buildProperty(String key) {
        java.io.InputStream in = OdkTools.class.getClassLoader()
                .getResourceAsStream("ontoboard-build.properties");
        if (in == null) {
            throw new IllegalStateException("ontoboard-build.properties is not on the classpath; "
                    + "the plugin was built without resource filtering.");
        }
        try {
            java.util.Properties properties = new java.util.Properties();
            properties.load(in);
            String value = properties.getProperty(key);
            if (value == null || value.trim().isEmpty() || value.contains("${")) {
                throw new IllegalStateException("ontoboard-build.properties has no usable " + key
                        + " (got '" + value + "'); Maven resource filtering did not run.");
            }
            return value.trim();
        } catch (java.io.IOException cannotRead) {
            throw new IllegalStateException("Could not read ontoboard-build.properties: "
                    + cannotRead.getMessage(), cannotRead);
        } finally {
            try {
                in.close();
            } catch (java.io.IOException ignored) {
                // Nothing to do about a failure to close a classpath resource.
            }
        }
    }

    /**
     * Every program ODK's recipes name that OntoBoard runs itself: the shell's own
     * ({@link RecipeShell#builtins()}), robot, odk-info, dicer-cli, check-rdfxml, curl - and
     * sed, for the substitutions projects' own rules make ({@link OdkSed}).
     */
    static Map<String, RecipeShell.Tool> forProject() {
        return forProject(null);
    }

    /**
     * The programs of a run the person can stop.
     *
     * @param stopped whether the person has stopped the run, for a program that takes its
     *                time over one line - a fetch; may be null
     */
    static Map<String, RecipeShell.Tool> forProject(BooleanSupplier stopped) {
        Map<String, RecipeShell.Tool> tools = new LinkedHashMap<String, RecipeShell.Tool>(
                RecipeShell.builtins());
        tools.put("robot", new Robot());
        tools.put("odk-info", new OdkInfo());
        tools.put("dicer-cli", new Dicer());
        tools.put("check-rdfxml", new CheckRdfXml());
        tools.put("curl", new OdkCurl(stopped));
        tools.put("sed", new OdkSed());
        return tools;
    }

    // ------------------------------------------------------------------ robot

    /**
     * ROBOT, inside this JVM ({@link OdkRobot}), with the line's files made absolute against the
     * folder make runs in ({@link RobotPaths}).
     *
     * <p>{@code whyNot} has no folder: it is asked of every line of a target before any runs.
     * It asks with the line made absolute against a folder of its own that is never read or
     * written - nothing {@link OdkRobot#whyNot} checks looks at the files, or depends on which
     * absolute folder they are in - and takes that folder out of what it answers.
     *
     * <p>What ROBOT printed is said line by line with the folder taken out, so that a transcript
     * reads as ODK's does ({@code PASS Rule ../sparql/...}); a failure is said as ROBOT says it,
     * on the error stream, and the status is 1.
     */
    static final class Robot implements RecipeShell.Tool {
        /** Stands for the folder make runs in while a line is only asked about. */
        private static final File ASKING = new File(System.getProperty("java.io.tmpdir"),
                "ontoboard-recipe-folder").getAbsoluteFile();
        /** A project's top and where make runs in it, for what a line writes, asked as text. */
        private static final File ASKING_TOP = new File(ASKING, "project");
        private static final File ASKING_IN = new File(ASKING_TOP, "src/ontology");

        @Override
        public String whyNot(List<String> arguments) {
            try {
                // A project's own rule may write inside the project only (1.154.0).
                String outside = RobotPaths.whyWrittenOutside(arguments, ASKING_IN, ASKING_TOP);
                if (outside != null) {
                    return outside;
                }
                String why = OdkRobot.whyNot(RobotPaths.absolute(arguments, ASKING), ASKING);
                return why == null ? null : asWritten(why, ASKING);
            } catch (RobotPaths.Unknown unknown) {
                return unknown.getMessage();
            }
        }

        @Override
        public int run(RecipeShell.Call call) {
            final File folder = call.folder();
            OdkRobot.Result result;
            // The files the line names, as they are before it runs: what is another
            // file afterwards, the line wrote.
            Map<File, String> before = new LinkedHashMap<File, String>();
            try {
                for (String named : RobotPaths.files(call.arguments(), folder)) {
                    before.put(new File(named), stampOf(new File(named)));
                }
                // An address to load from, through a move from http to https (IriRedirects).
                final List<String> line = RobotPaths.absolute(
                        IriRedirects.inputIrisAcrossToHttps(call.arguments()), folder);
                // With this Java's certificates; and where it does not know a server's
                // authority, once more with the operating system's as well.
                result = HttpsOpener.alsoWithOperatingSystemsTrust(
                        new HttpsOpener.Run<OdkRobot.Result>() {
                            @Override
                            public OdkRobot.Result run() {
                                return OdkRobot.run(line, folder);
                            }
                        }, new java.util.function.Predicate<OdkRobot.Result>() {
                            @Override
                            public boolean test(OdkRobot.Result ran) {
                                return failedOnACertificate(ran);
                            }
                        });
            } catch (IllegalArgumentException refused) {
                // RobotPaths' Unknown among them. Asked before, so only what changed since.
                call.complain(asWritten(String.valueOf(refused.getMessage()), folder));
                return 1;
            }
            for (String printed : result.output()) {
                call.say(asWritten(printed, folder));
            }
            if (!result.ok()) {
                call.complain(asWritten(result.failure(), folder));
                return 1;
            }
            // What ROBOT wrote says a string as ODK's OWL API says it, whatever this
            // process loaded before (SimpleStrings).
            for (Map.Entry<File, String> named : before.entrySet()) {
                if (!named.getValue().equals(stampOf(named.getKey()))) {
                    try {
                        SimpleStrings.simplify(named.getKey());
                    } catch (IOException | RuntimeException leftAsWritten) {
                        // The file is ROBOT's, whole and valid; only the form of its
                        // strings may differ from ODK's.
                        call.complain(asWritten(named.getKey().getPath(), folder)
                                + " was written; its strings were not brought to their "
                                + "simple form (" + leftAsWritten.getMessage() + ")");
                    }
                }
            }
            return 0;
        }

        /**
         * Whether a line failed because this Java did not trust a server's certificate:
         * ROBOT loads an ontology from its address itself, through the OWL API, and
         * Protege 5.5.0's Java is older than several of today's authorities.
         */
        static boolean failedOnACertificate(OdkRobot.Result result) {
            // A line that went well has no failure sentence, so this alone answers.
            return HttpsOpener.saysCertificatePathFailure(result.failure());
        }

        /**
         * Whether a file is there, and its time and length: other when it was written. The
         * time as the file system has it - Java 8's own File gives whole seconds on Linux
         * and macOS, and a line can write a file twice within one.
         */
        private static String stampOf(File file) {
            if (!file.isFile()) {
                return "";
            }
            try {
                return Files.getLastModifiedTime(file.toPath()) + " " + file.length();
            } catch (IOException | RuntimeException notRead) {
                return file.lastModified() + " " + file.length();
            }
        }

        /** The text with the folder's absolute path, and the separator after it, taken out. */
        private static String asWritten(String text, File folder) {
            return text.replace(folder.getAbsolutePath() + File.separator, "");
        }
    }

    // ------------------------------------------------------------------ odk-info

    /**
     * ODK's {@code odk-info}: in the image it names the image and, with {@code --tools}, the
     * versions of ROBOT and seven other programs. Here it says what is true here, in the same
     * shape: what runs the recipes, and the ROBOT inside OntoBoard. Its arguments are read as
     * ODK's script reads them.
     */
    static final class OdkInfo implements RecipeShell.Tool {
        @Override
        public String whyNot(List<String> arguments) {
            return null;
        }

        @Override
        public int run(RecipeShell.Call call) {
            boolean tools = false;
            boolean python = false;
            for (String argument : call.arguments()) {
                if (argument.equals("--tools")) {
                    tools = true;
                } else if (argument.equals("--all-python")) {
                    python = true;
                } else if (argument.equals("--all")) {
                    tools = true;
                    python = true;
                } else {
                    // ODK's script: the usage line, and "exit" - the echo's status, 0.
                    call.say("Usage: odk-info [--tools|--all-python|--all]");
                    return 0;
                }
            }
            call.say("OntoBoard " + BuildInfo.version() + " running ODK " + OdkPin.VERSION
                    + "'s recipes without ODK's image");
            if (tools) {
                call.say("ROBOT version " + ROBOT_VERSION);
            }
            if (python) {
                call.say("Python packages: none - no Python runs ODK's recipes here.");
            }
            return 0;
        }
    }

    // ------------------------------------------------------------------ dicer-cli

    /**
     * ODK's check of the project's ID range policy: {@code dicer-cli policy --assume-manchester
     * --show-owlapi-error <file>}, which says nothing of a good file. Here the file is read with
     * {@link IdRanges}, as OntoBoard reads it everywhere else; that form of the call only.
     */
    static final class Dicer implements RecipeShell.Tool {
        private static final List<String> FORM = Arrays.asList("policy", "--assume-manchester",
                "--show-owlapi-error");

        @Override
        public String whyNot(List<String> arguments) {
            if (arguments.size() == FORM.size() + 1
                    && arguments.subList(0, FORM.size()).equals(FORM)) {
                return null;
            }
            return "'dicer-cli " + String.join(" ", arguments) + "' is not read here: only "
                    + "dicer-cli policy --assume-manchester --show-owlapi-error <file>, as ODK's "
                    + "recipe calls it to check the ID ranges file.";
        }

        @Override
        public int run(RecipeShell.Call call) throws IOException {
            String name = call.arguments().get(FORM.size());
            File file = call.file(name);
            if (!file.isFile()) {
                call.complain(name + " is not there, so there is no ID range policy to check.");
                return 1;
            }
            IdRanges ranges;
            try {
                ranges = IdRanges.parse(new String(Files.readAllBytes(file.toPath()),
                        StandardCharsets.UTF_8));
            } catch (RuntimeException notAPolicy) {
                String why = String.valueOf(notAPolicy.getMessage()).trim();
                call.complain(name + " is not an ID range policy: " + why
                        + (why.endsWith(".") ? "" : "."));
                return 1;
            }
            List<IdRanges.Range> all = ranges.getRanges();
            for (int i = 0; i < all.size(); i++) {
                for (int j = i + 1; j < all.size(); j++) {
                    if (all.get(i).overlaps(all.get(j))) {
                        call.complain(name + " is not a usable ID range policy: ranges "
                                + all.get(i).getNumber() + " and " + all.get(j).getNumber()
                                + " overlap, so one identifier could be given out twice.");
                        return 1;
                    }
                }
            }
            return 0;
        }
    }

    // ------------------------------------------------------------------ check-rdfxml

    /**
     * ODK's {@code check-rdfxml [flags] FILE}: the same lines and exit statuses as ODK's script,
     * with one parser - Jena's RDF/XML parser, in this bundle already - where the image has
     * LightRDF, RDFLib and Jena's command line to switch on and off. The flags are read as the
     * script reads them; the one parse runs when any parser is switched on (LightRDF is by
     * default) and stands for all of them, so the status is 0 or 1.
     */
    static final class CheckRdfXml implements RecipeShell.Tool {
        @Override
        public String whyNot(List<String> arguments) {
            // ODK's script takes every word that is no flag as the file, the last one winning.
            return null;
        }

        @Override
        public int run(RecipeShell.Call call) {
            boolean lightrdf = true;
            boolean rdflib = false;
            boolean jena = false;
            String name = null;
            for (String argument : call.arguments()) {
                if (argument.equals("--lightrdf")) {
                    lightrdf = true;
                } else if (argument.equals("--no-lightrdf")) {
                    lightrdf = false;
                } else if (argument.equals("--rdflib")) {
                    rdflib = true;
                } else if (argument.equals("--no-rdflib")) {
                    rdflib = false;
                } else if (argument.equals("--jena")) {
                    jena = true;
                } else if (argument.equals("--no-jena")) {
                    jena = false;
                } else {
                    name = argument;
                }
            }
            if (name == null || name.isEmpty()) {
                call.say("Usage: check-rdfxml [[--no-]lightrdf] [[--no-]rdflib] [[--no-]jena] "
                        + "FILE");
                return 10;
            }
            File file = call.file(name);
            if (!file.isFile()) {
                call.say("check-rdfxml: " + name + ": file not found");
                return 11;
            }
            call.say("Checking RDF/XML file " + name + "...");
            if (!lightrdf && !rdflib && !jena) {
                return 0;
            }
            String complaint = parse(file);
            call.say("  OntoBoard: " + (complaint == null ? "OK" : complaint));
            return complaint == null ? 0 : 1;
        }

        /** What Jena's RDF/XML parser says of the file, in a line; null when it reads it. */
        static String parse(File file) {
            try {
                RDFParserBuilder.create().source(file.toPath()).lang(Lang.RDFXML)
                        .errorHandler(STRICT).parse(StreamRDFLib.sinkNull());
                return null;
            } catch (RuntimeException failed) {
                String said = failed.getMessage();
                return said == null || said.trim().isEmpty() ? failed.getClass().getSimpleName()
                        : said.replaceAll("\\s+", " ").trim();
            }
        }

        /** An error ends the parse; a warning does not - LightRDF does not stop on one either. */
        private static final ErrorHandler STRICT = new ErrorHandler() {
            @Override
            public void warning(String message, long line, long column) {
                // Not a failure.
            }

            @Override
            public void error(String message, long line, long column) {
                throw new RiotException(where(line, column) + message);
            }

            @Override
            public void fatal(String message, long line, long column) {
                throw new RiotException(where(line, column) + message);
            }

            private String where(long line, long column) {
                return line < 0 ? "" : "line " + line + (column < 0 ? "" : ", column " + column)
                        + ": ";
            }
        };
    }
}
