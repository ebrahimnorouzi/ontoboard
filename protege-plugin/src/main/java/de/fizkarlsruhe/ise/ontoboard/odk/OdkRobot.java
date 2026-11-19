package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.obolibrary.robot.Command;
import org.obolibrary.robot.CommandLineHelper;
import org.obolibrary.robot.CommandManager;
import org.obolibrary.robot.CommandState;
import org.obolibrary.robot.IOHelper;
import org.obolibrary.robot.ReportOperation;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * ROBOT's command line, and ODK's ROBOT plugin, run inside this JVM.
 *
 * <p>An ODK recipe is a ROBOT command line - {@code $(ROBOT) merge --input ... reason ...
 * annotate ... --output ...}. Without Docker, OntoBoard runs such a line here, through ROBOT's
 * own command layer ({@code robot-command}), so that every option means what it means in ODK's
 * container: the same parser, the same defaults, the same chaining of one command's ontology
 * into the next.
 *
 * <p>ROBOT's command line was written for a process of its own, and here it runs in Protégé,
 * with somebody's work in it. Three things follow.
 *
 * <p><b>Nothing may end the JVM.</b> {@code --help}, {@code -h}, {@code --version} and the words
 * {@code help} and {@code version} make ROBOT print and exit: they are refused before anything
 * runs. And ROBOT's command manager answers <em>any</em> exception from a command by printing it
 * and exiting, so every command is wrapped: what goes wrong is kept, the rest of the line is
 * not run, and the {@link Result} says what failed - as the exit status would have.
 *
 * <p><b>{@code report}, {@code verify} and {@code query} run on OntoBoard's own engines</b>,
 * behind ROBOT's arguments. ROBOT's go through a part of the OWL API that Protégé 5.5's version
 * does not have (its RDF4J renderer), and the first two exit when they find something. Here they
 * take the same options and the same input, apply ROBOT's own rules and the queries given,
 * write the files ROBOT writes, and fail where ROBOT fails: at the level of {@code --fail-on},
 * or on a query that returns rows. {@code report} writes and prints its table as ROBOT does -
 * labels, CURIEs, the counts and the first rows ({@link RobotReport}); {@code verify} says
 * ROBOT's PASS and FAIL line per query; {@code query} runs as ODK's recipes use it and refuses
 * the rest by name ({@link RobotQuery}).
 * {@code expand}, which needs the same part of the OWL API, is not run here at all and says so.
 *
 * <p><b>Asked before anything runs.</b> {@link #whyNot} goes through a line as {@link #run} does
 * before running it - ROBOT's own manager and parsers, nothing loaded - and gives the reason run
 * would refuse it, or null.
 *
 * <p><b>The host's logging is left as it was</b>: see {@link HostLog}.
 *
 * <p>Protégé 5.5 runs on Java 8, and there every format is written but JSON, which obographs
 * writes and which needs Java 11: such a line is refused before it runs, saying so. (That any
 * file can be written on Java 8 at all takes one class of OntoBoard's in obographs' place:
 * {@code org.geneontology.obographs.owlapi.OboGraphJsonDocumentFormat}.) The startup
 * self-check runs a line in the host itself, on both Protégés.
 *
 * <p>Of ODK's plugin the commands ODK's own Makefile uses without further settings are here:
 * {@code odk:import}, {@code odk:normalize}, {@code odk:subset}. Its {@code check-align} and
 * {@code check}, which exit when they find something, are not: a recipe that needs one is not
 * run here, and says so.
 *
 * <p>ROBOT reads a path that does not start at a root against the JVM's working folder. The
 * caller gives absolute paths ({@link RobotPaths} makes a recipe's so); for every option whose
 * value names a file, by RobotPaths' table, this is checked.
 */
public final class OdkRobot {

    /** What a run came to. */
    public static final class Result {
        private final boolean ok;
        private final List<String> output;
        private final String failure;

        Result(boolean ok, List<String> output, String failure) {
            this.ok = ok;
            this.output = Collections.unmodifiableList(output);
            this.failure = failure;
        }

        /** Whether the line would have succeeded as a process: every command ran, none failed. */
        public boolean ok() {
            return ok;
        }

        /** What ROBOT printed while this ran, line by line, for the transcript. */
        public List<String> output() {
            return output;
        }

        /** What went wrong, in a sentence or two; null when {@link #ok()}. */
        public String failure() {
            return failure;
        }
    }

    /** ROBOT's own commands, by the names its command line gives them. */
    private static final String[][] ROBOTS = {
        {"annotate", "AnnotateCommand"}, {"collapse", "CollapseCommand"},
        {"convert", "ConvertCommand"}, {"diff", "DiffCommand"}, {"expand", "ExpandCommand"},
        {"explain", "ExplainCommand"}, {"export", "ExportCommand"},
        {"export-prefixes", "ExportPrefixesCommand"}, {"extract", "ExtractCommand"},
        {"filter", "FilterCommand"}, {"materialize", "MaterializeCommand"},
        {"measure", "MeasureCommand"}, {"merge", "MergeCommand"}, {"mirror", "MirrorCommand"},
        {"query", "QueryCommand"}, {"reason", "ReasonCommand"}, {"reduce", "ReduceCommand"},
        {"relax", "RelaxCommand"}, {"remove", "RemoveCommand"}, {"rename", "RenameCommand"},
        {"repair", "RepairCommand"}, {"report", "ReportCommand"},
        {"template", "TemplateCommand"}, {"unmerge", "UnmergeCommand"},
        {"validate-profile", "ValidateProfileCommand"}, {"verify", "VerifyCommand"},
    };

    /**
     * ODK's plugin commands, as ROBOT names the commands of the plugin file {@code odk.jar}.
     * Looked up by name, so that a bundle built without the plugin still runs ROBOT's own.
     */
    private static final String[][] ODKS = {
        {"odk:import", "ImportCommand"}, {"odk:normalize", "NormalizeCommand"},
        {"odk:subset", "SubsetCommand"},
    };

    private static final List<String> EXITS = Arrays.asList("--help", "-h", "--version", "help",
            "version");

    private static final List<String> LEVELS = Arrays.asList("INFO", "WARN", "ERROR");

    /** ROBOT's commands that need the OWL API's RDF4J renderer and have no stand-in here. */
    private static final List<String> NOT_HERE = Arrays.asList("expand");

    /** One chain at a time: the output of a run is taken from the JVM's own streams. */
    private static final Object ONE_AT_A_TIME = new Object();

    private OdkRobot() {
    }

    /**
     * The level the host's root logger is at - INFO, WARN... - or null when the host does not
     * log through logback. For the startup self-check, which holds that a run leaves it alone.
     */
    public static String hostLogLevel() {
        HostLog log = HostLog.find();
        return log == null || log.level == null ? null : log.level.toString();
    }

    /**
     * Whether the OWL API leaves an OBO file it read open where that matters: OWL API 4.5.9 -
     * Protégé 5.5.0's - keeps the file's stream until the collector closes it (measured: every
     * way of reading one; 4.5.29 closes it), and on Windows an open file cannot be renamed or
     * deleted, so git could not replace a project's OBO edit file meanwhile.
     */
    static boolean leavesOboOpen(String owlApiVersion, boolean windows) {
        Matcher version = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)").matcher(
                owlApiVersion == null ? "" : owlApiVersion);
        if (!windows || !version.find()) {
            return false;
        }
        int major = Integer.parseInt(version.group(1));
        int minor = Integer.parseInt(version.group(2));
        int patch = Integer.parseInt(version.group(3));
        return major < 4 || major == 4 && (minor < 5 || minor == 5 && patch < 29);
    }

    /** Whether a ROBOT line reads an OBO file: one of its inputs names one. */
    static boolean readsObo(List<String> arguments) {
        for (int i = 1; i < arguments.size(); i++) {
            String before = arguments.get(i - 1);
            if ((before.equals("-i") || before.equals("--input") || before.equals("--inputs"))
                    && arguments.get(i).toLowerCase(Locale.ROOT).endsWith(".obo")) {
                return true;
            }
        }
        return false;
    }

    /** The OWL API's version with the time of its build after it, in the OWL API's line. */
    private static final Pattern STAMPED_VERSION = Pattern.compile(
            "(Generated by the OWL API \\(version \\d+\\.\\d+\\.\\d+)\\.[^)\\s]+(?=\\))");

    /**
     * The OWL API's line at the end of a file ROBOT wrote, as ODK's image writes it. ROBOT's own
     * jar carries no version of the OWL API in it, which then says its version without the time
     * of its build - "4.5.29" - where the OWL API Protégé loads says
     * "4.5.29.2024-05-13T12:11:03Z" (1.155.0: the one line in which nfdicore's and MWO's release
     * files from OntoBoard's engine differed from their release in ODK's container). Only the
     * time goes; a file without the line, or with a version that has none, is left as it is.
     */
    static void owlApiLineAsInOdk(File written) throws IOException {
        long length = written.length();
        int tail = (int) Math.min(length, 512);
        if (tail == 0) {
            return;
        }
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(written, "rw")) {
            byte[] end = new byte[tail];
            file.seek(length - tail);
            file.readFully(end);
            // One character a byte, so what is not ASCII in the tail comes back as it was.
            String text = new String(end, StandardCharsets.ISO_8859_1);
            Matcher stamped = STAMPED_VERSION.matcher(text);
            if (!stamped.find()) {
                return;
            }
            String plain = text.substring(0, stamped.start()) + stamped.group(1)
                    + text.substring(stamped.end());
            file.setLength(length - tail);
            file.seek(length - tail);
            file.write(plain.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    /**
     * A file ROBOT wrote as JSON, with its lines ended as ODK's image ends them: obographs
     * writes through Jackson's pretty printer, which ends a line as the platform does - CRLF
     * on Windows. A JSON string cannot hold a line break of its own, so every CRLF is one.
     */
    static void lineEndsAsInOdk(File json) throws IOException {
        byte[] bytes = Files.readAllBytes(json.toPath());
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.contains("\r\n")) {
            Files.write(json.toPath(), text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Why ROBOT's JSON cannot be written on this Java, or null when it can.
     *
     * <p>JSON is written by obographs, which is compiled for Java 11. Protege 5.5 runs on Java
     * 8, where every other format is written and this one is not.
     *
     * @param javaSpecificationVersion the JVM's {@code java.specification.version}: "1.8", "11"
     */
    static String whyNoJson(String javaSpecificationVersion) {
        if (javaSpecificationVersion == null || !javaSpecificationVersion.startsWith("1.")) {
            return null;
        }
        return "the JSON form of an ontology is written by a library that needs Java 11 or "
                + "later, and this Protege runs on Java " + javaSpecificationVersion.substring(2)
                + ". Every other format is written here; for JSON use Protege 5.6, or ODK's "
                + "container.";
    }

    /** The live options of each command run here, by name; filled on first use. */
    private static Map<String, Options> commandOptions;

    private static synchronized Map<String, Options> commandOptions() {
        if (commandOptions == null) {
            Map<String, Options> options = new LinkedHashMap<String, Options>();
            for (String[] robot : ROBOTS) {
                options.put(robot[0], robot(robot).getOptions());
            }
            for (String[] odk : ODKS) {
                Command command = plugin(odk[1]);
                if (command != null) {
                    options.put(odk[0], command.getOptions());
                }
            }
            commandOptions = Collections.unmodifiableMap(options);
        }
        return commandOptions;
    }

    /** The commands a line can name here: ROBOT's own, and ODK's plugin's when it is there. */
    static List<String> commandNames() {
        return new ArrayList<String>(commandOptions().keySet());
    }

    /** A command's live options, as its parser reads them; null for no command run here. */
    static Options options(String command) {
        return commandOptions().get(command);
    }

    private static Command robot(String[] robot) {
        try {
            return (Command) Class.forName("org.obolibrary.robot." + robot[1])
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException notInThisRobot) {
            throw new IllegalStateException("ROBOT's " + robot[0] + " command is not in this "
                    + "build", notInThisRobot);
        }
    }

    /**
     * Why this command line would be refused, or null: asked before anything is run.
     *
     * <p>The same refusals {@link #run} makes, for the same reasons, found the way run finds them
     * before it runs anything: ROBOT's own manager cuts the line into commands and each command's
     * own parser reads its options, and nothing is loaded, run or written. What is refused: what
     * would end the JVM; a command ROBOT does not have, and {@code expand}; what ROBOT's parser
     * rejects; JSON on Java 8; a {@code report} or {@code verify} that could not be judged
     * afterwards; a path that is not absolute; and what {@code query} does not run here.
     */
    public static String whyNot(List<String> arguments) {
        return whyNot(arguments, null);
    }

    /**
     * Why this command line would be refused, or null, when it runs in a folder: see
     * {@link #run(List, File)}.
     */
    public static String whyNot(List<String> arguments, File folder) {
        String exits = exits(arguments);
        if (exits != null) {
            return exits;
        }
        synchronized (ONE_AT_A_TIME) {
            HostLog log = HostLog.find();
            if (log == null) {
                return NO_LOGBACK;
            }
            String[] line = log.keeping(arguments);
            try {
                log.asOnRobotsCommandLine(arguments);
                Run plan = plan(line, folder);
                return plan.refusal != null ? plan.refusal : plan.failure;
            } finally {
                log.restore();
            }
        }
    }

    private static final String NO_LOGBACK = "ROBOT's command line cannot run here: it sets the "
            + "level of logback's root logger on every command, and this program does not log "
            + "through logback.";

    /** Why the line would end the JVM, or null. */
    private static String exits(List<String> arguments) {
        for (String argument : arguments) {
            if (EXITS.contains(argument)) {
                return "'" + argument + "' makes ROBOT print and exit, which inside Protege "
                        + "would end Protege. It is not run here.";
            }
        }
        return null;
    }

    /** Whether ODK's ROBOT plugin is in this build, so that the {@code odk:} commands run. */
    public static boolean hasOdkPlugin() {
        return plugin(ODKS[0][1]) != null;
    }

    private static Command plugin(String className) {
        try {
            return (Command) Class.forName("org.incenp.obofoundry.odk." + className, true,
                    OdkRobot.class.getClassLoader()).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException notThere) {
            return null;
        } catch (LinkageError notThere) {
            return null;
        }
    }

    /**
     * Runs one ROBOT command line: what stands after {@code robot} in a terminal, or after
     * {@code $(ROBOT)} in ODK's Makefile, as separate arguments.
     *
     * @throws IllegalArgumentException when the line would end the JVM, names an input, an
     *                                  output or a catalog that is not an absolute path, or
     *                                  holds a {@code report} or {@code verify} that could not
     *                                  be judged afterwards; nothing has run then
     */
    public static Result run(List<String> arguments) {
        return run(arguments, null);
    }

    /**
     * Runs one ROBOT command line that runs in a folder, as a recipe's line runs in the folder
     * make runs in.
     *
     * <p>The line's own paths are absolute all the same ({@link RobotPaths} makes a recipe's
     * so). The folder is for the paths ROBOT finds inside a file it reads and takes against the
     * folder it runs in: a report profile's {@code file:../sparql/mine.rq}. Without a folder
     * such a path cannot be read, and the line is refused.
     *
     * @param folder the folder the line runs in, or null when there is none to name
     */
    public static Result run(List<String> arguments, File folder) {
        String exits = exits(arguments);
        if (exits != null) {
            throw new IllegalArgumentException(exits);
        }
        synchronized (ONE_AT_A_TIME) {
            HostLog log = HostLog.find();
            if (log == null) {
                return new Result(false, Collections.<String>emptyList(), NO_LOGBACK);
            }
            String[] line = log.keeping(arguments);
            try {
                log.asOnRobotsCommandLine(arguments);
                return run(line, folder);
            } finally {
                log.restore();
            }
        }
    }

    /**
     * The line, gone through without doing anything: ROBOT's own manager cuts it into commands,
     * and what each would run is checked. The run's {@code refusal} is what makes {@link #run}
     * throw, its {@code failure} what makes it fail before anything has run.
     */
    private static Run plan(String[] line, File folder) {
        Run plan = new Run(true, folder);
        try {
            manager(plan).execute(null, line);
        } catch (Exception notACommandLine) {
            // The manager's own word on the line: a command it does not know, an option
            // without its value.
            plan.failure = describe(notACommandLine);
        } catch (LinkageError cannotRun) {
            plan.failure = "ROBOT could not run here: " + cannotRun + ".";
        }
        return plan;
    }

    private static Result run(String[] line, File folder) {
        Run plan = plan(line, folder);
        if (plan.refusal != null) {
            throw new IllegalArgumentException(plan.refusal);
        }
        if (plan.failure != null) {
            return new Result(false, Collections.<String>emptyList(), plan.failure);
        }

        Run run = new Run(false, folder);
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        PrintStream out = System.out;
        PrintStream err = System.err;
        Thread here = Thread.currentThread();
        String failure = null;
        System.setOut(new PrintStream(new Capture(out, here, printed), true));
        System.setErr(new PrintStream(new Capture(err, here, printed), true));
        try {
            manager(run).execute(null, line);
            failure = run.failure != null ? run.failure : run.refusal;
        } catch (Exception failed) {
            failure = describe(failed);
        } catch (LinkageError failed) {
            // A class ROBOT needs that this Protege does not have: said, not thrown at
            // whoever pressed Build.
            failure = "ROBOT could not run here: " + failed + ".";
        } finally {
            System.out.flush();
            System.err.flush();
            System.setOut(out);
            System.setErr(err);
        }
        if (readsObo(Arrays.asList(line)) && leavesOboOpen(org.semanticweb.owlapi.util
                .VersionInfo.getVersionInfo().getVersion(), System.getProperty("os.name", "")
                        .toLowerCase(Locale.ROOT).contains("win"))) {
            // What the older OWL API left open of the OBO file it read is closed when its
            // stream is collected: now, not when the collector would have come by.
            System.gc();
            System.runFinalization();
        }
        List<String> lines = new ArrayList<String>();
        String text = new String(printed.toByteArray(), Charset.defaultCharset());
        if (!text.isEmpty()) {
            lines.addAll(Arrays.asList(text.replace("\r\n", "\n").split("\n")));
        }
        return new Result(failure == null, lines, failure);
    }

    /**
     * The host's logging, kept as it is across a run.
     *
     * <p>On every command ROBOT's command line takes the root logger slf4j gives it for
     * logback's and sets its level: ERROR, or what {@code -v}, {@code -vv} or {@code -vvv} ask
     * for. In a process of its own that is its to do. Here the root logger is Protege's, and
     * a build would leave Protege logging errors only until it is restarted.
     *
     * <p>So the line is given the flag that asks for the level the host has already - nothing
     * changes while it runs, and nothing of the host's own log is lost - and the level is set
     * back when it has run, whatever it was.
     *
     * <p>And for the length of a run, what the run's own thread logs is what ROBOT's command
     * line lets through: errors, unless the line asks for more ({@code -v} the warnings,
     * {@code -vv} what is said for information, {@code -vvv} everything). ROBOT logs
     * "Subcommand Timing: convert took 0.05 seconds" at WARN for every command, and a reasoner
     * says each of its stages at INFO; with the host logging from INFO up, to a console the
     * run's transcript is taken from, a build would carry a line per command and forty per
     * call of the reasoner, none of which ODK's own log has. Nor has it what the OWL API's OBO
     * writer logs as an error for every axiom OBO cannot say, which is held back too. What the
     * host's other threads log is the host's, and is not touched. What is held back is held
     * back from the host's log file as well, as it is from ROBOT's own output.
     */
    private static final class HostLog {
        private final ch.qos.logback.classic.Logger root;
        private final ch.qos.logback.classic.Level level;
        private OfTheRun held;

        private HostLog(ch.qos.logback.classic.Logger root) {
            this.root = root;
            this.level = root.getLevel();
        }

        /**
         * From here to {@link #restore}: the thread this is called on logs what ROBOT's own
         * command line would let through for these arguments.
         */
        void asOnRobotsCommandLine(List<String> arguments) {
            try {
                OfTheRun filter = new OfTheRun(Thread.currentThread(), saidFrom(arguments));
                filter.start();
                root.getLoggerContext().addTurboFilter(filter);
                held = filter;
            } catch (LinkageError noFilters) {
                // A logback without them: the run goes on, and its transcript carries what
                // the host logs meanwhile.
                held = null;
            }
        }

        /** The level from which ROBOT says things for a line: by its most talkative flag. */
        private static int saidFrom(List<String> arguments) {
            int from = ch.qos.logback.classic.Level.ERROR_INT;
            for (String argument : arguments) {
                if (argument.equals("-vvv") || argument.equals("--very-very-verbose")) {
                    return ch.qos.logback.classic.Level.DEBUG_INT;
                } else if (argument.equals("-vv") || argument.equals("--very-verbose")) {
                    from = ch.qos.logback.classic.Level.INFO_INT;
                } else if ((argument.equals("-v") || argument.equals("--verbose"))
                        && from > ch.qos.logback.classic.Level.WARN_INT) {
                    from = ch.qos.logback.classic.Level.WARN_INT;
                }
            }
            return from;
        }

        /** The host's root logger when it is logback's; null when ROBOT's cast would fail. */
        static HostLog find() {
            try {
                org.slf4j.Logger root = org.slf4j.LoggerFactory.getLogger(
                        org.slf4j.Logger.ROOT_LOGGER_NAME);
                return root instanceof ch.qos.logback.classic.Logger
                        ? new HostLog((ch.qos.logback.classic.Logger) root) : null;
            } catch (LinkageError noLogback) {
                return null;
            }
        }

        /** The line, led by the flag with which ROBOT sets the level the host already has. */
        String[] keeping(List<String> arguments) {
            List<String> line = new ArrayList<String>(arguments);
            if (level != null && level.toInt() <= ch.qos.logback.classic.Level.DEBUG_INT) {
                line.add(0, "-vvv");
            } else if (level != null && level.toInt() <= ch.qos.logback.classic.Level.INFO_INT) {
                line.add(0, "-vv");
            } else if (level != null && level.toInt() <= ch.qos.logback.classic.Level.WARN_INT) {
                line.add(0, "-v");
            }
            return line.toArray(new String[0]);
        }

        void restore() {
            if (held != null) {
                root.getLoggerContext().getTurboFilterList().remove(held);
                held = null;
            }
            root.setLevel(level);
        }
    }

    /**
     * Holds back what one thread logs below a level; says nothing of any other thread, so the
     * host's own logging goes on as it is.
     */
    private static final class OfTheRun extends ch.qos.logback.classic.turbo.TurboFilter {
        private final Thread thread;
        private final int from;

        OfTheRun(Thread thread, int from) {
            this.thread = thread;
            this.from = from;
        }

        @Override
        public ch.qos.logback.core.spi.FilterReply decide(org.slf4j.Marker marker,
                ch.qos.logback.classic.Logger logger, ch.qos.logback.classic.Level level,
                String format, Object[] params, Throwable thrown) {
            return Thread.currentThread() == thread && level != null
                    && (level.toInt() < from || isMaskedByTheOboWriter(logger, format))
                    ? ch.qos.logback.core.spi.FilterReply.DENY
                    : ch.qos.logback.core.spi.FilterReply.NEUTRAL;
        }

        /**
         * The OWL API's OBO writer says every axiom OBO has no words for as an error, with
         * the stack of where it stood - a "MASKING ERROR" in its own words, and no failure:
         * the file is written without the axiom. ROBOT in ODK's container converts the same
         * ontology without a word of it, so a run here says none either - a release's
         * transcript was cut after 4000 lines of these.
         */
        private static boolean isMaskedByTheOboWriter(ch.qos.logback.classic.Logger logger,
                String format) {
            return logger != null && format != null && format.startsWith("MASKING ERROR")
                    && "org.obolibrary.obo2owl.OWLAPIOwl2Obo".equals(logger.getName());
        }
    }

    private static CommandManager manager(Run run) {
        CommandManager manager = new CommandManager();
        for (String[] robot : ROBOTS) {
            manager.addCommand(robot[0], new Wrapped(robot[0], robot(robot), run));
        }
        for (String[] odk : ODKS) {
            Command command = plugin(odk[1]);
            if (command != null) {
                manager.addCommand(odk[0], new Wrapped(odk[0], command, run));
            }
        }
        return manager;
    }

    /**
     * What went wrong, in ROBOT's words: its message without the page of ROBOT's documentation
     * it starts with ("errors#UNKNOWN ARG ERROR ..."), as ROBOT's ExceptionHelper prints it.
     */
    private static String describe(Throwable failed) {
        String said = failed.getMessage();
        if (said == null || said.trim().isEmpty()) {
            return failed.getClass().getSimpleName();
        }
        return said.trim().replaceFirst("^[a-z][a-z-]*#", "");
    }

    // ------------------------------------------------------------------ one run

    /** Raised before anything has run: the caller's line is wrong. */
    private static final class Refused extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /** Ends a chain where ROBOT's exit would have ended the process. */
    private static final class Judged extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Run {
        /** Whether this pass only looks at what would be run. */
        final boolean dry;
        /** What a command failed with; the commands after it are not run. */
        String failure;
        /** Why the line is not run at all: said by the pass that only looks. */
        String refusal;

        /** The folder the line runs in, for paths inside the files it reads; or null. */
        final File folder;

        Run(boolean dry, File folder) {
            this.dry = dry;
            this.folder = folder;
        }
    }

    /**
     * One command of the manager's, as ROBOT's own with its exits taken out. The manager hands
     * each command exactly its own arguments, the global ones in front.
     */
    private static final class Wrapped implements Command {
        private final String name;
        private final Command real;
        private final Run run;

        Wrapped(String name, Command real, Run run) {
            this.name = name;
            this.real = real;
            this.run = run;
        }

        @Override
        public String getName() {
            return real.getName();
        }

        @Override
        public String getDescription() {
            return real.getDescription();
        }

        @Override
        public String getUsage() {
            return real.getUsage();
        }

        @Override
        public Options getOptions() {
            return real.getOptions();
        }

        @Override
        public void main(String[] args) {
            throw new UnsupportedOperationException("main() exits the JVM; use execute");
        }

        /**
         * Runs the command, and lets nothing out. ROBOT's manager answers an exception from a
         * command by printing it and ending the JVM ({@code executeCommand} calls
         * {@code handleException}), so what goes wrong is kept in the run instead, and the
         * commands after it do nothing: the line ended there, as the process would have.
         */
        @Override
        public CommandState execute(CommandState state, String[] args) {
            if (run.failure != null || run.refusal != null) {
                return state;
            }
            try {
                return executeOrThrow(state, args);
            } catch (Refused refused) {
                run.refusal = refused.getMessage();
            } catch (Judged judged) {
                // What failed is in the run already.
            } catch (Exception failed) {
                run.failure = describe(failed);
            }
            return state;
        }

        private CommandState executeOrThrow(CommandState state, String[] args) throws Exception {
            CommandLine line = parse(args);
            if (run.dry) {
                check(line);
                return state == null ? new CommandState() : state;
            }
            if (name.equals("report")) {
                return report(state, line);
            }
            if (name.equals("verify")) {
                return verify(state, line);
            }
            if (name.equals("query")) {
                CommandState after = query(state, line);
                if (!CommandLineHelper.getOptionalValues(line, "update").isEmpty()) {
                    // With --update, --output is the ontology the updates left.
                    asWrittenInOdk(line, false);
                }
                return after;
            }
            if (NOT_HERE.contains(name)) {
                throw new IllegalStateException(notHere(name));
            }
            CommandState after = real.execute(state, args);
            asWrittenInOdk(line, writesJson(line));
            return after;
        }

        /**
         * Each file the command wrote, as ODK's image writes it: the OWL API's line without the
         * time of its build, and JSON's lines ended in LF on every platform.
         */
        private void asWrittenInOdk(CommandLine line, boolean json) throws IOException {
            for (String output : real.getOptions().hasLongOption("output")
                    && line.getOptionValues("output") != null
                    ? line.getOptionValues("output") : new String[0]) {
                File written = new File(output);
                if (!written.isFile() || output.toLowerCase(Locale.ROOT).endsWith(".gz")) {
                    continue;
                }
                if (json) {
                    lineEndsAsInOdk(written);
                }
                owlApiLineAsInOdk(written);
            }
        }

        private static String notHere(String name) {
            return "ROBOT's '" + name + "' is not run inside Protege: it goes through a part of "
                    + "the OWL API that Protege's own version does not have";
        }

        /**
         * {@code robot query}: ROBOT's options and ROBOT's loading of the input (the catalog
         * used), the queries on OntoBoard's engine, each output written as ROBOT writes it.
         * With {@code --update}, as ROBOT's {@code QueryCommand.execute} does then: the
         * ontology the updates leave is written by {@code --output}, when there is one, and
         * handed to the next command of the line.
         */
        private CommandState query(CommandState state, CommandLine line) throws Exception {
            CommandState now = input(state, line);
            List<String> updates = CommandLineHelper.getOptionalValues(line, "update");
            if (!updates.isEmpty()) {
                OWLOntology updated = RobotQuery.update(now,
                        CommandLineHelper.getIOHelper(line), updates);
                CommandLineHelper.maybeSaveOutput(line, updated);
                now.setOntology(updated);
                return now;
            }
            RobotQuery.run(now.getOntology(), line,
                    CommandLineHelper.getBooleanValue(line, "use-graphs", false));
            return now;
        }

        /** The ontology the command works on: the chain's, or its own {@code --input}. */
        private CommandState input(CommandState state, CommandLine line) throws IOException {
            return CommandLineHelper.updateInputOntology(CommandLineHelper.getIOHelper(line),
                    state, line);
        }

        /**
         * {@code robot report}: ROBOT's rules, at the levels of the profile given, over the
         * ontology's own statements, on OntoBoard's engine; the rows read, written and printed
         * as ROBOT reads, writes and prints them ({@link RobotReport}), and the line failing at
         * the level of {@code --fail-on} as ROBOT fails.
         *
         * <p>In ROBOT's order ({@code ReportCommand.execute}, {@code ReportOperation.report}):
         * the options are ROBOT's defaults with what the line gives; the profile is read and
         * every rule's query found, ROBOT's own or a file of the project's, and the rules run -
         * failing, before anything is said, where ROBOT fails and in its words; the counts are
         * printed, then {@code --print} is read, the first rows printed, the file written;
         * {@code --fail-on} is read and judged last.
         */
        private CommandState report(CommandState state, CommandLine line) throws Exception {
            IOHelper io = CommandLineHelper.getIOHelper(line);
            CommandState now = CommandLineHelper.updateInputOntology(io, state, line);
            Map<String, String> options = ReportOperation.getDefaultOptions();
            for (String option : new ArrayList<String>(options.keySet())) {
                if (line.hasOption(option)) {
                    options.put(option, line.getOptionValue(option));
                }
            }
            for (String base : CommandLineHelper.getBaseNamespaces(line, io)) {
                io.addBaseNamespace(base);
            }
            // getReport: the profile, the rules' queries, then the ontology as ROBOT's dataset
            // holds it - its own statements, under its name and as the default graph.
            List<RobotReport.Rule> queries = RobotReport.rules(RobotReport.profile(
                    options.get("profile")), run.folder);
            org.apache.jena.query.Dataset dataset = RobotQuery.datasetOf(now.getOntology(),
                    false);
            List<de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding> rows;
            try {
                rows = RobotReport.rows(queries, dataset, io.getBaseNamespaces(), System.out);
            } finally {
                dataset.close();
            }
            RobotReport.Table table = RobotReport.table(rows,
                    new RobotReport.Cells(now.getOntology(), io,
                            RobotReport.isTrue(options.get("labels"))),
                    io.getBaseNamespaces());

            RobotReport.printCounts(table, System.out);
            int print = RobotReport.print(options.get("print"));
            File output = new File(line.getOptionValue("output"));
            String separator = RobotReport.separator(RobotReport.format(options.get("format"),
                    line.getOptionValue("output")));
            if (print > 0) {
                RobotReport.printFirst(table.rows(), print, separator, System.out);
            }
            // ROBOT's FileWriter writes in the JVM's charset, which in ODK's image is UTF-8.
            Files.write(output.toPath(), RobotReport.text(table.rows(), separator)
                    .getBytes(StandardCharsets.UTF_8));

            // --fail-on is read only now, after the file, as processReport reads it.
            String given = options.get("fail-on") == null ? "ERROR" : options.get("fail-on");
            if (!given.equalsIgnoreCase("none") && !given.equalsIgnoreCase("ERROR")
                    && !given.equalsIgnoreCase("WARN") && !given.equalsIgnoreCase("INFO")) {
                throw new IllegalArgumentException(String.format(
                        "report#FAIL ON ERROR '%s' is not a valid fail-on level.", given));
            }
            String failOn = given.toUpperCase(Locale.ROOT);
            Map<String, Integer> rules = new LinkedHashMap<String, Integer>();
            int count = 0;
            for (Map.Entry<String, Integer> rule : table.rules().entrySet()) {
                String level = rule.getKey().substring(0, rule.getKey().indexOf(' '));
                if (!failOn.equals("NONE") && LEVELS.indexOf(level) >= LEVELS.indexOf(failOn)) {
                    rules.put(rule.getKey(), rule.getValue());
                    count += rule.getValue();
                }
            }
            if (count > 0) {
                StringBuilder found = new StringBuilder();
                for (Map.Entry<String, Integer> rule : rules.entrySet()) {
                    found.append(found.length() == 0 ? "" : ", ").append(rule.getKey())
                            .append(rule.getValue() > 1 ? " x" + rule.getValue() : "");
                }
                run.failure = "ROBOT's report found " + count + (count == 1 ? " violation"
                        : " violations") + " at " + failOn + " or above (" + found + "). The "
                        + "report is " + output.getAbsolutePath() + ".";
                throw new Judged();
            }
            return now;
        }

        /**
         * {@code robot verify}: each query over the ontology's own statements, as ROBOT's
         * {@code QueryOperation.runVerify} runs it. ROBOT says a line per query, with its path as
         * it was given: {@code PASS Rule <path>: 0 violation(s)}, or {@code FAIL Rule <path>: N
         * violation(s)} and the rows on its error stream. A query that returns rows has
         * violations; its rows also go into {@code <name>.csv} in the output folder, and the
         * line fails.
         */
        private CommandState verify(CommandState state, CommandLine line) throws IOException {
            CommandState now = input(state, line);
            org.apache.jena.query.Dataset dataset = RobotQuery.datasetOf(now.getOntology(),
                    false);
            File folder = new File(line.getOptionValue("output-dir"));
            Files.createDirectories(folder.toPath());
            StringBuilder failed = new StringBuilder();
            for (String path : line.getOptionValues("queries")) {
                File query = new File(path);
                String file = query.getName();
                int dot = file.lastIndexOf('.');
                String check = dot < 0 ? file : file.substring(0, dot);
                File result = new File(folder, check + ".csv");
                // ROBOT writes a result when a query returns rows and never removes one: what
                // an earlier run left would be read as this run's.
                Files.deleteIfExists(result.toPath());
                org.apache.jena.query.QueryExecution execution =
                        org.apache.jena.query.QueryExecutionFactory.create(new String(
                                Files.readAllBytes(query.toPath()), StandardCharsets.UTF_8),
                                dataset);
                try {
                    org.apache.jena.query.ResultSetRewindable rows =
                            org.apache.jena.query.ResultSetFactory.copyResults(
                                    execution.execSelect());
                    if (rows.size() == 0) {
                        System.out.println("PASS Rule " + path + ": 0 violation(s)");
                        continue;
                    }
                    System.out.println("FAIL Rule " + path + ": " + rows.size()
                            + " violation(s)");
                    ByteArrayOutputStream csv = new ByteArrayOutputStream();
                    org.apache.jena.riot.ResultSetMgr.write(csv, rows,
                            org.apache.jena.riot.Lang.CSV);
                    System.err.print(new String(csv.toByteArray(), StandardCharsets.UTF_8));
                    Files.write(result.toPath(), csv.toByteArray());
                    failed.append(failed.length() == 0 ? "" : ", ").append(check).append(" (")
                            .append(rows.size()).append(rows.size() == 1 ? " row)" : " rows)");
                } finally {
                    execution.close();
                }
            }
            dataset.close();
            if (failed.length() > 0) {
                run.failure = "ROBOT's verify found violations: " + failed + ". The rows are in "
                        + folder.getAbsolutePath() + ".";
                throw new Judged();
            }
            return now;
        }

        private CommandLine parse(String[] args) {
            try {
                return new DefaultParser().parse(real.getOptions(), args);
            } catch (ParseException notRead) {
                throw new Refused("ROBOT cannot read the options of '" + name + "': "
                        + notRead.getMessage());
            }
        }

        /** Whether the command is asked for ROBOT's JSON: by its output's name, or by format. */
        private boolean writesJson(CommandLine line) {
            Options options = real.getOptions();
            String format = options.hasLongOption("format") ? line.getOptionValue("format")
                    : null;
            if (format != null) {
                return format.trim().equalsIgnoreCase("json");
            }
            String[] outputs = options.hasLongOption("output") ? line.getOptionValues("output")
                    : null;
            for (String output : outputs == null ? new String[0] : outputs) {
                String file = output.toLowerCase(Locale.ROOT);
                if (file.endsWith(".json") || file.endsWith(".json.gz")) {
                    return true;
                }
            }
            return false;
        }

        /** What must hold before the line is run at all. */
        private void check(CommandLine line) {
            if (NOT_HERE.contains(name)) {
                // A failure, as when it was found only while running: nothing has run.
                throw new IllegalStateException(notHere(name));
            }
            // Every value that names a file - by RobotPaths' table of every option - is absolute.
            for (Option option : line.getOptions()) {
                RobotPaths.Kind kind;
                try {
                    kind = RobotPaths.kind(name, option);
                } catch (RobotPaths.Unknown unknown) {
                    throw new Refused(unknown.getMessage());
                }
                if (kind != RobotPaths.Kind.FILE && kind != RobotPaths.Kind.FILES
                        && kind != RobotPaths.Kind.FILE_PAIRS) {
                    continue;
                }
                for (String value : option.getValues() == null ? new String[0]
                        : option.getValues()) {
                    String lower = value.toLowerCase(Locale.ROOT);
                    if (!new File(value).isAbsolute() && !lower.startsWith("http:")
                            && !lower.startsWith("https:") && !lower.startsWith("file:")) {
                        throw new Refused("'" + name + " --" + (option.getLongOpt() != null
                                ? option.getLongOpt() : option.getOpt()) + " " + value + "': a "
                                + "path given to ROBOT inside Protege has to be absolute. A "
                                + "relative one is read against the folder Protege was started "
                                + "from.");
                    }
                }
            }
            if (name.equals("query")) {
                String why = RobotQuery.whyNot(line);
                if (why != null) {
                    throw new Refused(why);
                }
            }
            if (!name.equals("report") && !name.equals("verify") && writesJson(line)) {
                String why = whyNoJson(System.getProperty("java.specification.version"));
                if (why != null) {
                    throw new Refused("'" + name + "': " + why);
                }
            }
            if (name.equals("report")) {
                if (line.getOptionValue("output") == null) {
                    throw new Refused("'report' without --output cannot be judged afterwards, "
                            + "and with --fail-on it would end Protege. Give it an output.");
                }
                String unported = RobotReport.whyNot(RobotReport.format(
                        line.getOptionValue("format"), line.getOptionValue("output")));
                if (unported != null) {
                    throw new Refused(unported);
                }
                // A rule of the project's own by a relative path: ROBOT's folder is not known.
                String unrun = RobotReport.whyNotRules(line.getOptionValue("profile"),
                        run.folder);
                if (unrun != null) {
                    throw new Refused(unrun);
                }
            }
            if (name.equals("verify")) {
                if (line.getOptionValue("output-dir") == null
                        || line.getOptionValues("queries") == null) {
                    throw new Refused("'verify' needs --queries and --output-dir here: without "
                            + "a folder for its results it cannot be judged afterwards.");
                }
            }
        }
    }

    /**
     * Standard output or error for the length of a run: everything goes on to where it went,
     * and what the running thread writes is kept as well. Other threads' output is theirs.
     */
    private static final class Capture extends OutputStream {
        private final PrintStream original;
        private final Thread owner;
        private final ByteArrayOutputStream kept;

        Capture(PrintStream original, Thread owner, ByteArrayOutputStream kept) {
            this.original = original;
            this.owner = owner;
            this.kept = kept;
        }

        @Override
        public void write(int b) {
            if (Thread.currentThread() == owner) {
                synchronized (kept) {
                    kept.write(b);
                }
            }
            original.write(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (Thread.currentThread() == owner) {
                synchronized (kept) {
                    kept.write(bytes, offset, length);
                }
            }
            original.write(bytes, offset, length);
        }

        @Override
        public void flush() {
            original.flush();
        }
    }
}
