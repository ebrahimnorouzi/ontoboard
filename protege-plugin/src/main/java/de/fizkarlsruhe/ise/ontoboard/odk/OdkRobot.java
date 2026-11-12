package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.robot.OntologyDataset;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityReport;
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
import java.util.Map;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.obolibrary.robot.Command;
import org.obolibrary.robot.CommandLineHelper;
import org.obolibrary.robot.CommandManager;
import org.obolibrary.robot.CommandState;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

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
 * <p><b>{@code report} and {@code verify} run on OntoBoard's own engines</b>, behind ROBOT's
 * arguments. ROBOT's go through a part of the OWL API that Protégé 5.5's version does not have
 * (its RDF4J renderer), and they exit when they find something. Here they take the same
 * options and the same input, apply ROBOT's own rules and the queries given, write the files
 * ROBOT writes, and fail where ROBOT fails: at the level of {@code --fail-on}, or on a query
 * that returns rows. {@code query} and {@code expand}, which need the same part of the OWL API,
 * are not run here at all and say so.
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
 * caller gives absolute paths; for the options that name an input, an output or the catalog
 * this is checked.
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

    /** Options whose value is a file or folder, by their long names. */
    private static final List<String> PATHS = Arrays.asList("input", "output", "output-dir",
            "catalog", "queries");

    private static final List<String> LEVELS = Arrays.asList("INFO", "WARN", "ERROR");

    /** ROBOT's commands that need the OWL API's RDF4J renderer and have no stand-in here. */
    private static final List<String> NOT_HERE = Arrays.asList("query", "expand");

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
        for (String argument : arguments) {
            if (EXITS.contains(argument)) {
                throw new IllegalArgumentException("'" + argument + "' makes ROBOT print and "
                        + "exit, which inside Protege would end Protege. It is not run here.");
            }
        }
        synchronized (ONE_AT_A_TIME) {
            HostLog log = HostLog.find();
            if (log == null) {
                return new Result(false, Collections.<String>emptyList(), "ROBOT's command "
                        + "line cannot run here: it sets the level of logback's root logger on "
                        + "every command, and this program does not log through logback.");
            }
            String[] line = log.keeping(arguments);
            try {
                return run(line);
            } finally {
                log.restore();
            }
        }
    }

    private static Result run(String[] line) {
        // First without doing anything: ROBOT's own manager cuts the line into commands, and
        // what it would run is checked before any of it runs.
        Run plan = new Run(true);
        try {
            manager(plan).execute(null, line);
        } catch (Exception notACommandLine) {
            // The manager's own word on the line: a command it does not know, an option
            // without its value.
            return new Result(false, Collections.<String>emptyList(), describe(notACommandLine));
        } catch (LinkageError cannotRun) {
            return new Result(false, Collections.<String>emptyList(),
                    "ROBOT could not run here: " + cannotRun + ".");
        }
        if (plan.refusal != null) {
            throw new IllegalArgumentException(plan.refusal);
        }
        if (plan.failure != null) {
            return new Result(false, Collections.<String>emptyList(), plan.failure);
        }

        Run run = new Run(false);
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
     */
    private static final class HostLog {
        private final ch.qos.logback.classic.Logger root;
        private final ch.qos.logback.classic.Level level;

        private HostLog(ch.qos.logback.classic.Logger root) {
            this.root = root;
            this.level = root.getLevel();
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
            root.setLevel(level);
        }
    }

    private static CommandManager manager(Run run) {
        CommandManager manager = new CommandManager();
        for (String[] robot : ROBOTS) {
            try {
                Command command = (Command) Class.forName("org.obolibrary.robot." + robot[1])
                        .getDeclaredConstructor().newInstance();
                manager.addCommand(robot[0], new Wrapped(robot[0], command, run));
            } catch (ReflectiveOperationException notInThisRobot) {
                throw new IllegalStateException("ROBOT's " + robot[0] + " command is not in "
                        + "this build", notInThisRobot);
            }
        }
        for (String[] odk : ODKS) {
            Command command = plugin(odk[1]);
            if (command != null) {
                manager.addCommand(odk[0], new Wrapped(odk[0], command, run));
            }
        }
        return manager;
    }

    private static String describe(Throwable failed) {
        String said = failed.getMessage();
        return said == null || said.trim().isEmpty() ? failed.getClass().getSimpleName()
                : said.trim();
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

        Run(boolean dry) {
            this.dry = dry;
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
            if (NOT_HERE.contains(name)) {
                throw new IllegalStateException("ROBOT's '" + name + "' is not run inside "
                        + "Protege: it goes through a part of the OWL API that Protege's own "
                        + "version does not have. A recipe that needs it needs ODK's container.");
            }
            return real.execute(state, args);
        }

        /** The ontology the command works on: the chain's, or its own {@code --input}. */
        private CommandState input(CommandState state, CommandLine line) throws IOException {
            return CommandLineHelper.updateInputOntology(CommandLineHelper.getIOHelper(line),
                    state, line);
        }

        /**
         * {@code robot report}: ROBOT's rules, at the levels of the profile given, over the
         * ontology's own statements - written as ROBOT writes its table, and failing at the
         * level of {@code --fail-on} as ROBOT fails.
         */
        private CommandState report(CommandState state, CommandLine line) throws IOException {
            CommandState now = input(state, line);
            Map<String, String> options = new LinkedHashMap<String, String>();
            if (line.getOptionValue("profile") != null) {
                options.put(QualityReport.OPTION_PROFILE, line.getOptionValue("profile"));
            }
            if (line.getOptionValues("base-iri") != null) {
                options.put(QualityReport.OPTION_BASE_IRI,
                        String.join(" ", line.getOptionValues("base-iri")));
            }
            List<QualityFinding> findings = QualityReport.run(alone(now.getOntology()), options);

            char delimiter = delimiterOf(line);
            StringBuilder table = new StringBuilder("Level").append(delimiter)
                    .append("Rule Name").append(delimiter).append("Subject").append(delimiter)
                    .append("Property").append(delimiter).append("Value").append('\n');
            Map<String, Integer> levels = new LinkedHashMap<String, Integer>();
            for (String level : new String[] {"ERROR", "WARN", "INFO"}) {
                levels.put(level, 0);
            }
            for (QualityFinding finding : findings) {
                String level = finding.getSeverity().toString();
                levels.put(level, levels.containsKey(level) ? levels.get(level) + 1 : 1);
                table.append(level).append(delimiter).append(cell(finding.getRule(), delimiter))
                        .append(delimiter).append(cell(finding.getSubject(), delimiter))
                        .append(delimiter).append(cell(finding.getProperty(), delimiter))
                        .append(delimiter).append(cell(finding.getValue(), delimiter))
                        .append('\n');
            }
            File output = new File(line.getOptionValue("output"));
            Files.write(output.toPath(), table.toString().getBytes(StandardCharsets.UTF_8));

            // ROBOT's own summary, and with --print the first rows of the table.
            System.out.println("Violations: " + findings.size());
            System.out.println("-----------------");
            for (Map.Entry<String, Integer> level : levels.entrySet()) {
                System.out.println(level.getKey() + ":" + "      ".substring(level.getKey().length())
                        + level.getValue());
            }
            int print = 0;
            try {
                print = Integer.parseInt(line.getOptionValue("print", "0"));
            } catch (NumberFormatException notANumber) {
                print = 0;
            }
            String[] rows = table.toString().split("\n");
            for (int i = 0; i < rows.length && i <= print && print > 0; i++) {
                System.out.println(rows[i]);
            }

            String failOn = line.getOptionValue("fail-on", "error").toUpperCase(Locale.ROOT);
            Map<String, Integer> rules = new LinkedHashMap<String, Integer>();
            int count = 0;
            for (QualityFinding finding : findings) {
                String level = finding.getSeverity().toString();
                if (!failOn.equals("NONE") && LEVELS.indexOf(level) >= LEVELS.indexOf(failOn)) {
                    String rule = level + " " + finding.getRule();
                    rules.put(rule, rules.containsKey(rule) ? rules.get(rule) + 1 : 1);
                    count++;
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

        /** A cell of the table: quoted when it holds the delimiter, a quote or a line break. */
        private static String cell(String text, char delimiter) {
            String value = text == null ? "" : text;
            if (value.indexOf(delimiter) < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0
                    && value.indexOf('\r') < 0) {
                return value;
            }
            return '"' + value.replace("\"", "\"\"") + '"';
        }

        /**
         * {@code robot verify}: each query over the ontology's own statements. A query that
         * returns rows has violations; its rows go where ROBOT puts them - printed, and into
         * {@code <name>.csv} in the output folder - and the line fails.
         */
        private CommandState verify(CommandState state, CommandLine line) throws IOException {
            CommandState now = input(state, line);
            org.apache.jena.rdf.model.Model model =
                    OntologyDataset.modelOf(alone(now.getOntology()));
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
                                model);
                try {
                    org.apache.jena.query.ResultSetRewindable rows =
                            org.apache.jena.query.ResultSetFactory.copyResults(
                                    execution.execSelect());
                    if (rows.size() == 0) {
                        continue;
                    }
                    System.err.println("Rule " + query.getAbsolutePath() + ": " + rows.size()
                            + " violation(s)");
                    ByteArrayOutputStream csv = new ByteArrayOutputStream();
                    org.apache.jena.query.ResultSetFormatter.outputAsCSV(csv, rows);
                    System.err.print(new String(csv.toByteArray(), StandardCharsets.UTF_8));
                    Files.write(result.toPath(), csv.toByteArray());
                    failed.append(failed.length() == 0 ? "" : ", ").append(check).append(" (")
                            .append(rows.size()).append(rows.size() == 1 ? " row)" : " rows)");
                } finally {
                    execution.close();
                }
            }
            if (failed.length() > 0) {
                run.failure = "ROBOT's verify found violations: " + failed + ". The rows are in "
                        + folder.getAbsolutePath() + ".";
                throw new Judged();
            }
            return now;
        }

        /**
         * The ontology without its imports: what ROBOT's report and verify look at, unless told
         * otherwise. (OntoBoard's own Quality report looks at the imports too, which is why
         * its engine would otherwise merge them in.)
         */
        private static OWLOntology alone(OWLOntology ontology) throws IOException {
            if (ontology.getImportsDeclarations().isEmpty()) {
                return ontology;
            }
            try {
                OWLOntology copy = OWLManager.createOWLOntologyManager()
                        .createOntology(ontology.getOntologyID());
                copy.getOWLOntologyManager().addAxioms(copy, ontology.getAxioms());
                for (OWLAnnotation annotation : ontology.getAnnotations()) {
                    copy.getOWLOntologyManager().applyChange(
                            new AddOntologyAnnotation(copy, annotation));
                }
                return copy;
            } catch (OWLOntologyCreationException cannotCopy) {
                throw new IOException("The ontology could not be copied without its imports: "
                        + cannotCopy.getMessage(), cannotCopy);
            }
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
            for (String option : PATHS) {
                String[] values = real.getOptions().hasLongOption(option)
                        ? line.getOptionValues(option) : null;
                for (String value : values == null ? new String[0] : values) {
                    if (!new File(value).isAbsolute()) {
                        throw new Refused("'" + name + " --" + option + " " + value + "': a "
                                + "path given to ROBOT inside Protege has to be absolute. A "
                                + "relative one is read against the folder Protege was started "
                                + "from.");
                    }
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
                delimiterOf(line);
                String failOn = line.getOptionValue("fail-on", "error").toUpperCase(Locale.ROOT);
                if (!failOn.equals("NONE") && !LEVELS.contains(failOn)) {
                    throw new Refused("'report --fail-on " + line.getOptionValue("fail-on")
                            + "': ROBOT knows none, INFO, WARN and ERROR.");
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

        /** Tab or comma, by the report's format; other formats are not judged, so not run. */
        private char delimiterOf(CommandLine line) {
            String output = line.getOptionValue("output");
            String format = line.getOptionValue("format");
            if (format == null) {
                int dot = output.lastIndexOf('.');
                format = dot < 0 ? "tsv" : output.substring(dot + 1);
            }
            format = format.toLowerCase(Locale.ROOT);
            if (format.equals("tsv")) {
                return '\t';
            }
            if (format.equals("csv")) {
                return ',';
            }
            throw new Refused("'report' as " + format + " cannot be judged afterwards; inside "
                    + "Protege a report is written as .tsv or .csv, as ODK's Makefile does.");
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
