package de.fizkarlsruhe.ise.ontoboard.odk.make;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Makefile read as GNU make 4.3 reads it: its variables and rules, after its conditionals -
 * and what make would do with it for a goal, decided as make decides it.
 *
 * <p>This is the part of make that ODK's Makefile uses, and no more: the features its template
 * ({@code src/main/odk/v1.6.1/templates/src/ontology/Makefile.jinja2}) has. Anything else is
 * refused with the file and line when the Makefile is read, never guessed at. Nothing is run:
 * the disk is seen only through a {@link Disk}, {@code $(shell ...)} only through a
 * {@link Shell}, and a dry run ends at "these are the commands, in this order".
 *
 * <p>Checked against make itself: {@code MakeDryRunParityTest} and {@code MakeClosureParityTest}
 * compare every seeded project's {@code make -n} and {@code make -n -d}, recorded in the ODK
 * image, with {@link #dryRun} and {@link #considered}.
 */
public final class MakeFile {

    /** What make would find on disk: the reader never touches the real disk except through this. */
    public interface Disk {
        /** Relative to the Makefile's folder, or absolute, with {@code /}; a file or a folder. */
        boolean exists(String path);

        /** Milliseconds; meaningful only when {@link #exists}. */
        long modified(String path);

        /** The names in a folder, for {@code $(wildcard)}; empty when there is no such folder. */
        List<String> list(String folder);

        /** An included makefile's text, UTF-8; null when there is none. */
        String read(String path);

        /**
         * The absolute folder make runs in, as it prints it in {@code make[1]: Entering
         * directory '...'}.
         */
        String folder();
    }

    /** {@code $(shell ...)}: the command as make expanded it, to what it would print. */
    public interface Shell {
        /** The command's standard output; throws {@link MakeException} for one it cannot answer. */
        String run(String command);
    }

    /** A line of a makefile: its text, the makefile it is in, and its line number there. */
    public static final class Line {
        private final String text;
        private final String file;
        private final int number;

        Line(String text, String file, int number) {
            this.text = text;
            this.file = file;
            this.number = number;
        }

        /** As written, a recipe line without its leading tab; continuations kept. */
        public String text() {
            return text;
        }

        public String file() {
            return file;
        }

        /** 1-based, of the line's first physical line. */
        public int number() {
            return number;
        }

        @Override
        public String toString() {
            return file + ":" + number + ": " + text;
        }
    }

    /** One line a dry run prints: a command, a sub-make with what it does, or a message. */
    public static final class Step {
        /** What the step is. */
        public enum Kind {
            /** A recipe line make would run. */
            COMMAND,
            /** A recipe line that runs make itself; make runs it even in a dry run. */
            SUB_MAKE,
            /** A line make says, e.g. {@code make[1]: Nothing to be done for 'all_imports'.} */
            MESSAGE
        }

        private final Kind kind;
        private final String command;
        private final String target;
        private final String file;
        private final int line;
        private final boolean silent;
        private final boolean ignoreErrors;
        private final int level;
        private final String folder;
        private final List<Step> steps;

        Step(Kind kind, String command, String target, String file, int line, boolean silent,
                boolean ignoreErrors, int level, String folder, List<Step> steps) {
            this.kind = kind;
            this.command = command;
            this.target = target;
            this.file = file;
            this.line = line;
            this.silent = silent;
            this.ignoreErrors = ignoreErrors;
            this.level = level;
            this.folder = folder;
            this.steps = Collections.unmodifiableList(new ArrayList<Step>(steps));
        }

        public Kind kind() {
            return kind;
        }

        /**
         * The command after expansion, as make prints it and hands it to the shell: without its
         * {@code @ - +} prefixes, a continued line keeping its backslash and line break and losing
         * the one tab after it. For a {@link Kind#MESSAGE}, the message without make's prefix.
         */
        public String command() {
            return command;
        }

        /** The target whose recipe the line is from; null for a message. */
        public String target() {
            return target;
        }

        /** The makefile of the recipe line ({@code Makefile} or an included one). */
        public String file() {
            return file;
        }

        public int line() {
            return line;
        }

        /** Written with {@code @}: make runs it without echoing it. */
        public boolean silent() {
            return silent;
        }

        /** Written with {@code -}: make goes on when it fails. */
        public boolean ignoreErrors() {
            return ignoreErrors;
        }

        /** The make level the step belongs to: 0 for the make that was asked. */
        public int level() {
            return level;
        }

        /** For a sub-make: what that make does, in order. Empty otherwise. */
        public List<Step> steps() {
            return steps;
        }

        /** The lines make prints for this step, as {@link MakeFile#dryRun} has them. */
        void print(List<String> into) {
            switch (kind) {
                case MESSAGE:
                    into.add(prefix(level) + command);
                    break;
                case SUB_MAKE:
                    into.add(command);
                    into.add(prefix(level + 1) + "Entering directory '" + folder + "'");
                    for (Step step : steps) {
                        step.print(into);
                    }
                    into.add(prefix(level + 1) + "Leaving directory '" + folder + "'");
                    break;
                default:
                    into.add(command);
            }
        }

        private static String prefix(int level) {
            return level == 0 ? "make: " : "make[" + level + "]: ";
        }

        @Override
        public String toString() {
            return kind + " " + command;
        }
    }

    /** Where a variable's value came from, in make's order of strength. */
    public enum Origin {
        /** Set by make itself ({@code MAKE}, {@code SHELL}). */
        DEFAULT,
        /** Set in a makefile. */
        FILE,
        /** Given on the command line: it beats every assignment in a makefile. */
        COMMAND_LINE
    }

    /** A variable after the makefiles were read. */
    public static final class Variable {
        private final String name;
        private final String value;
        private final boolean recursive;
        private final Origin origin;
        private final String file;
        private final int line;
        private final boolean exported;
        private final List<Line> assignments;

        Variable(String name, String value, boolean recursive, Origin origin, String file,
                int line, boolean exported, List<Line> assignments) {
            this.name = name;
            this.value = value;
            this.recursive = recursive;
            this.origin = origin;
            this.file = file;
            this.line = line;
            this.exported = exported;
            this.assignments = Collections.unmodifiableList(new ArrayList<Line>(assignments));
        }

        public String name() {
            return name;
        }

        /** As make keeps it: as written for a recursive variable, expanded for a simple one. */
        public String value() {
            return value;
        }

        /** {@code =} (expanded where used) rather than {@code :=} (expanded where assigned). */
        public boolean recursive() {
            return recursive;
        }

        public Origin origin() {
            return origin;
        }

        /** The makefile whose assignment gave the value it has; null if not a makefile's. */
        public String file() {
            return file;
        }

        public int line() {
            return line;
        }

        /** {@code export}ed: a recipe's shell sees it in its environment. */
        public boolean exported() {
            return exported;
        }

        /**
         * Every assignment to it that make read, in order - also those that did not take effect,
         * because the command line beat them or {@code ?=} found it set.
         */
        public List<Line> assignments() {
            return assignments;
        }

        @Override
        public String toString() {
            return name + (recursive ? " = " : " := ") + value;
        }
    }

    /** A rule as a makefile has it: explicit, or a pattern rule ({@code %}). */
    public static final class Rule {
        private final String written;
        private final List<String> targets;
        private final List<String> prerequisites;
        private final List<String> orderOnly;
        private final List<Line> recipe;
        private final boolean pattern;
        private final String file;
        private final int line;

        Rule(String written, List<String> targets, List<String> prerequisites,
                List<String> orderOnly, List<Line> recipe, boolean pattern, String file,
                int line) {
            this.written = written;
            this.targets = Collections.unmodifiableList(new ArrayList<String>(targets));
            this.prerequisites = Collections.unmodifiableList(new ArrayList<String>(prerequisites));
            this.orderOnly = Collections.unmodifiableList(new ArrayList<String>(orderOnly));
            this.recipe = Collections.unmodifiableList(new ArrayList<Line>(recipe));
            this.pattern = pattern;
            this.file = file;
            this.line = line;
        }

        /** The targets as written, before expansion: {@code $(REPORTDIR)/%-obo-report.tsv}. */
        public String written() {
            return written;
        }

        /** The targets after expansion; a pattern rule's keep their {@code %}. */
        public List<String> targets() {
            return targets;
        }

        /** The normal prerequisites after expansion, in order. */
        public List<String> prerequisites() {
            return prerequisites;
        }

        /** The order-only prerequisites (after {@code |}) after expansion, in order. */
        public List<String> orderOnly() {
            return orderOnly;
        }

        /** The recipe lines as written, each with its makefile and line. */
        public List<Line> recipe() {
            return recipe;
        }

        public boolean isPattern() {
            return pattern;
        }

        /** {@code Makefile}, or the included makefile the rule is in. */
        public String file() {
            return file;
        }

        public int line() {
            return line;
        }

        /**
         * Whether this rule is one for the file: one of its targets, or - for a pattern rule - a
         * target pattern that matches the name as make matches it (a pattern without a slash
         * matches a name in any folder).
         */
        public boolean matches(String name) {
            for (String target : targets) {
                if (pattern ? MakeDatabase.stemOf(target, name) != null : target.equals(name)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return file + ":" + line + ": " + targets + ": " + prerequisites
                    + (orderOnly.isEmpty() ? "" : " | " + orderOnly);
        }
    }

    /** Thrown where make stops - "No rule to make target 'x', needed by 'y'." and the like. */
    public static final class MakeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final boolean makesOwn;

        public MakeException(String message) {
            this(message, false);
        }

        private MakeException(String message, boolean makesOwn) {
            super(message);
            this.makesOwn = makesOwn;
        }

        /** A stop of make's own, worded as make words it: no rule for a target, no makefile. */
        static MakeException stop(String message) {
            return new MakeException(message, true);
        }

        /**
         * Whether make itself would stop here - "No rule to make target" - and not only this
         * reader, which also refuses what it does not read. make would stop wherever it runs.
         */
        public boolean isMakesOwn() {
            return makesOwn;
        }

        /** A message with the makefile and line it is about, as make gives them. */
        static MakeException at(String file, int line, String message) {
            return new MakeException(file == null ? message : file + ":" + line + ": " + message);
        }
    }

    private final String makefilePath;
    private final Disk disk;
    private final Map<String, String> commandLine;
    private final Shell shell;
    private final MakeDatabase database;

    private MakeFile(String makefilePath, Disk disk, Map<String, String> commandLine,
            Shell shell) {
        this.makefilePath = makefilePath;
        this.disk = disk;
        this.commandLine = commandLine;
        this.shell = shell;
        this.database = new MakeRunner(disk, shell, commandLine, 0,
                Collections.<String>emptyList(), makefilePath).load(
                        Collections.<String>emptyList());
    }

    /**
     * Reads a makefile, and every makefile it includes, as make would with these variables on
     * its command line. {@code $(shell)} answers as {@link #odkShell} with the system clock.
     *
     * @throws MakeException where make would stop reading, or where the makefile uses
     *                       something of make that ODK's template does not
     */
    public static MakeFile read(String makefilePath, Disk disk, Map<String, String> commandLine) {
        return read(makefilePath, disk, commandLine, odkShell(Clock.systemDefaultZone()));
    }

    /** As {@link #read(String, Disk, Map)}, with the given shell. */
    public static MakeFile read(String makefilePath, Disk disk, Map<String, String> commandLine,
            Shell shell) {
        return new MakeFile(makefilePath, disk, new LinkedHashMap<String, String>(commandLine),
                shell);
    }

    /**
     * Every target and file make considers to decide how to make the goal: the names of
     * {@code make -n -d}'s "Considering target file" and "Pruning file" lines - its makefiles
     * first among them, and those of every sub-make a dry run follows. Sorted by code point.
     */
    public SortedSet<String> considered(String goal) {
        return run(goal).considered;
    }

    /** The lines {@code make -n <goal>} prints, in order (sub-makes followed, as make follows them). */
    public List<String> dryRun(String goal) {
        List<String> printed = new ArrayList<String>();
        for (Step step : plan(goal)) {
            step.print(printed);
        }
        return printed;
    }

    /**
     * The same as objects, for whoever runs them later: each command after expansion, the
     * target whose recipe it is from, the makefile and line of that recipe line, whether it was
     * written with {@code @} or {@code -}, and for a sub-make line the steps inside it.
     * {@link #dryRun} is the printing of this.
     */
    public List<Step> plan(String goal) {
        return run(goal).steps;
    }

    /**
     * What a real run of the goal would do, where {@link #plan} says what a dry run prints.
     *
     * <p>One difference: every recipe line that runs make is followed, also a line that only
     * expands to it. ODK's {@code test_fast} is such a line - {@code $(MAKE_FAST) test} - and
     * {@code make -n test_fast} prints it and stops, while {@code make test_fast} runs the
     * tests. A followed line is a {@link Step.Kind#SUB_MAKE} with that make's steps.
     *
     * <p>Like a dry run, this decides on the disk as it is now. A real run decides each
     * sub-make when it gets there, so whoever runs the steps asks again at each sub-make.
     *
     * @throws MakeException also for a line that runs make in a way this reader cannot follow
     */
    public List<Step> runPlan(String goal) {
        return new MakeRunner(disk, shell, commandLine, 0, Collections.<String>emptyList(),
                makefilePath, true).run(Collections.singletonList(goal)).steps;
    }

    /** Whoever runs the recipe lines of a real run, in the shell's place. */
    public interface Executor {
        /** One recipe line, as make hands it to the shell; its exit status, 0 for success. */
        int run(Step step);

        /**
         * A line make itself prints: a recipe line before it is run (unless it is written with
         * {@code @}), a directory entered or left, an error.
         */
        void say(String line);
    }

    /**
     * Runs the goal as {@code make <goal>} does: decides as it goes, on the disk as each
     * recipe leaves it, hands every recipe line to the executor in make's order, and stops at
     * the first line that fails and is not written with {@code -}.
     *
     * <p>Where {@link #runPlan} says what a run would do, seen from before it, this is the
     * run. They differ where a recipe does not make its target, or removes what a later
     * target needs - ODK's refresh of the imports cleans and then builds.
     *
     * @return whether the goal was made; false when make stopped, which it has then said
     * @throws MakeException where this reader cannot follow the makefile - not for a stop of
     *                       make's own, which is said and returned as false
     */
    public boolean run(String goal, Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("a run needs somebody to run its recipe lines");
        }
        try {
            new MakeRunner(disk, shell, commandLine, 0, Collections.<String>emptyList(),
                    makefilePath, true, executor).run(Collections.singletonList(goal));
            return true;
        } catch (MakeRunner.Stopped stopped) {
            return false;
        } catch (MakeException stop) {
            if (!stop.isMakesOwn()) {
                throw stop;
            }
            executor.say(MakeRunner.asMakeStops(stop.getMessage()));
            return false;
        }
    }

    /** Every rule of the makefiles, in the order make read them. */
    public List<Rule> rules() {
        return Collections.unmodifiableList(database.rules);
    }

    /** Every variable after reading, by name: value, flavour, origin, makefile and line. */
    public Map<String, Variable> variables() {
        Map<String, Variable> variables = new TreeMap<String, Variable>();
        for (MakeDatabase.Var var : database.variables.values()) {
            variables.put(var.name, var.view());
        }
        return Collections.unmodifiableMap(variables);
    }

    /**
     * Every variable that expanding this text reads, directly or through the values of
     * recursive variables - not a {@code foreach} loop's own variable, not {@code $@} and the
     * other automatic variables.
     */
    public Set<String> variablesReadBy(String text) {
        return new MakeExpander(database).variablesReadBy(text);
    }

    private MakeRunner.Result run(String goal) {
        return new MakeRunner(disk, shell, commandLine, 0, Collections.<String>emptyList(),
                makefilePath).run(Collections.singletonList(goal));
    }

    // ------------------------------------------------------------- ready-made helpers

    /** A {@link Disk} over a real folder: the Makefile's folder; nothing is ever written. */
    public static Disk onDisk(final File folder) {
        return new Disk() {
            private File resolve(String path) {
                File file = new File(path);
                return path.startsWith("/") || file.isAbsolute() ? file : new File(folder, path);
            }

            @Override
            public boolean exists(String path) {
                return resolve(path).exists();
            }

            @Override
            public long modified(String path) {
                return resolve(path).lastModified();
            }

            @Override
            public List<String> list(String path) {
                String[] names = resolve(path).list();
                if (names == null) {
                    return new ArrayList<String>();
                }
                List<String> sorted = new ArrayList<String>(Arrays.asList(names));
                Collections.sort(sorted);
                return sorted;
            }

            @Override
            public String read(String path) {
                File file = resolve(path);
                if (!file.isFile()) {
                    return null;
                }
                try {
                    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                } catch (IOException unreadable) {
                    throw new MakeException(path + ": " + unreadable.getMessage());
                }
            }

            @Override
            public String folder() {
                return folder.getAbsolutePath().replace('\\', '/');
            }
        };
    }

    /** {@code echo <words> | tr a-z A-Z}, the words free of anything the shell would interpret. */
    private static final Pattern ECHO_TR =
            Pattern.compile("echo ([^'\"`$\\\\;&|<>()*?\\[#~\n]*) \\| tr a-z A-Z");

    /**
     * The shell for the forms ODK's template has, and no other: {@code date +%Y-%m-%d},
     * {@code date +'%d:%m:%Y %H:%M'} (by the clock), and {@code echo <words> | tr a-z A-Z}. Any
     * other command is refused, naming it - no process is ever started.
     */
    public static Shell odkShell(final Clock clock) {
        return new Shell() {
            @Override
            public String run(String command) {
                String trimmed = command.trim();
                ZonedDateTime now = ZonedDateTime.now(clock);
                if (trimmed.equals("date +%Y-%m-%d")) {
                    return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "\n";
                }
                if (trimmed.equals("date +'%d:%m:%Y %H:%M'")) {
                    return now.format(DateTimeFormatter.ofPattern("dd:MM:yyyy HH:mm")) + "\n";
                }
                Matcher echo = ECHO_TR.matcher(trimmed);
                if (echo.matches()) {
                    StringBuilder upper = new StringBuilder();
                    for (String word : echo.group(1).trim().split("[ \t]+")) {
                        if (upper.length() > 0) {
                            upper.append(' ');
                        }
                        for (char c : word.toCharArray()) {
                            upper.append(c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c);
                        }
                    }
                    return upper + "\n";
                }
                throw new MakeException("$(shell " + command + "): this reader runs no command;"
                        + " it knows only the date and echo|tr forms of ODK's template");
            }
        };
    }
}
