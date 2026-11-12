package de.fizkarlsruhe.ise.ontoboard.odk.make;

import de.fizkarlsruhe.ise.ontoboard.odk.make.MakeDatabase.Dep;
import de.fizkarlsruhe.ise.ontoboard.odk.make.MakeDatabase.FileEntry;
import de.fizkarlsruhe.ise.ontoboard.odk.make.MakeDatabase.PatternRule;
import de.fizkarlsruhe.ise.ontoboard.odk.make.MakeDatabase.Recipe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * One make: reads the makefile afresh, then decides as GNU make 4.3 does under {@code -n} -
 * {@code update_goal_chain}, {@code update_file}, {@code update_file_1}, {@code check_dep},
 * {@code remake_file}, {@code notice_finished_file} of {@code remake.c}, {@code pattern_search}
 * of {@code implicit.c}, {@code start_job_command} of {@code job.c} - serially, as with one job
 * slot. It prints instead of running; a recipe line that runs make is followed, as make
 * follows it even in a dry run, by a make of its own one level down.
 */
final class MakeRunner {

    /** What a run gives: the steps printed, and every name considered on the way. */
    static final class Result {
        final List<MakeFile.Step> steps;
        final SortedSet<String> considered;

        Result(List<MakeFile.Step> steps, SortedSet<String> considered) {
            this.steps = Collections.unmodifiableList(steps);
            this.considered = Collections.unmodifiableSortedSet(considered);
        }
    }

    private final MakeFile.Disk disk;
    private final MakeFile.Shell shell;
    private final Map<String, String> commandLine;
    private final int level;
    private final List<String> assumeNew;
    private final String makefile;
    private final boolean everyMake;
    /** Who runs the recipe lines; null for a dry run, which only says what they are. */
    private final MakeFile.Executor executor;

    private MakeDatabase db;
    private MakeExpander expander;
    private int pass;
    private boolean rebuilding;
    private int commandsStarted;
    private final List<MakeFile.Step> steps = new ArrayList<MakeFile.Step>();
    private final SortedSet<String> considered = new TreeSet<String>(MakeExpander.CODE_POINTS);

    /**
     * @param makefile the makefile to read; null for make's own choice among
     *                 {@code GNUmakefile}, {@code makefile} and {@code Makefile}
     */
    MakeRunner(MakeFile.Disk disk, MakeFile.Shell shell, Map<String, String> commandLine,
            int level, List<String> assumeNew, String makefile) {
        this(disk, shell, commandLine, level, assumeNew, makefile, false);
    }

    /**
     * @param everyMake follow every recipe line that runs make, as a real run does - also one
     *                  that only expands to it ({@code $(MAKE_FAST) test}), which a dry run
     *                  prints and leaves
     */
    MakeRunner(MakeFile.Disk disk, MakeFile.Shell shell, Map<String, String> commandLine,
            int level, List<String> assumeNew, String makefile, boolean everyMake) {
        this(disk, shell, commandLine, level, assumeNew, makefile, everyMake, null);
    }

    /**
     * @param executor runs the recipe lines: a real run, which decides as it goes and stops at
     *                 a line that fails ({@link Stopped}); null for a dry run
     */
    MakeRunner(MakeFile.Disk disk, MakeFile.Shell shell, Map<String, String> commandLine,
            int level, List<String> assumeNew, String makefile, boolean everyMake,
            MakeFile.Executor executor) {
        this.executor = executor;
        this.disk = disk;
        this.shell = shell;
        this.commandLine = new LinkedHashMap<String, String>(commandLine);
        this.level = level;
        this.assumeNew = new ArrayList<String>(assumeNew);
        this.makefile = makefile;
        this.everyMake = everyMake;
    }

    /** Reads the makefiles as make does before it decides anything; the goals are known first. */
    MakeDatabase load(List<String> goals) {
        db = new MakeDatabase(disk, shell);
        expander = new MakeExpander(db);
        MakeReader reader = new MakeReader(db, expander);
        MakeDatabase.Var make = new MakeDatabase.Var("MAKE");
        make.value = "make";
        make.recursive = false;
        make.origin = MakeFile.Origin.DEFAULT;
        db.variables.put(make.name, make);
        MakeDatabase.Var shellVariable = new MakeDatabase.Var("SHELL");
        shellVariable.value = "/bin/sh";
        shellVariable.recursive = false;
        shellVariable.origin = MakeFile.Origin.DEFAULT;
        db.variables.put(shellVariable.name, shellVariable);
        for (Map.Entry<String, String> variable : commandLine.entrySet()) {
            String name = variable.getKey();
            if (name.isEmpty() || !name.matches("[^\\s$=:#]+")) {
                throw new MakeFile.MakeException("'" + name + "' is no variable name for the"
                        + " command line");
            }
            reader.definition(name, "=", variable.getValue(), MakeFile.Origin.COMMAND_LINE, null);
        }
        for (String goal : goals) {
            db.enter(goal);
        }
        String name = makefile != null ? makefile : defaultMakefile();
        String text = disk.read(name);
        if (text == null) {
            throw MakeFile.MakeException.stop(prefix() + name + ": No such file or directory");
        }
        reader.readMakefile(name, text);
        snapDeps();
        for (String file : assumeNew) {
            FileEntry entry = db.enter(file);
            entry.lastMtime = MakeDatabase.NEW;
            entry.mtimeBeforeUpdate = MakeDatabase.NEW;
        }
        return db;
    }

    /** {@code make -n <goals>}: the makefiles first, to see whether they must be remade. */
    Result run(List<String> goals) {
        load(goals);
        List<String> wanted = new ArrayList<String>(goals);
        if (wanted.isEmpty()) {
            if (db.defaultGoal == null) {
                throw MakeFile.MakeException.stop(prefix() + "No targets.");
            }
            wanted.add(db.defaultGoal);
        }

        pass++;
        rebuilding = true;
        List<String> makefiles = new ArrayList<String>(db.makefiles);
        Collections.reverse(makefiles);
        for (String name : makefiles) {
            updateFile(db.enter(name));
        }

        pass++;
        rebuilding = false;
        for (String goal : wanted) {
            FileEntry file = db.enter(goal);
            int before = commandsStarted;
            updateFile(file);
            if (commandsStarted == before) {
                String nothing = file.phony || file.cmds == null
                        ? "Nothing to be done for '" + goal + "'."
                        : "'" + goal + "' is up to date.";
                if (executor != null) {
                    executor.say(prefix() + nothing);
                } else {
                    steps.add(message(nothing));
                }
            }
        }
        return new Result(steps, considered);
    }

    private String prefix() {
        return level == 0 ? "make: " : "make[" + level + "]: ";
    }

    private MakeFile.Step message(String text) {
        return new MakeFile.Step(MakeFile.Step.Kind.MESSAGE, text, null, null, 0, false, false,
                level, disk.folder(), Collections.<MakeFile.Step>emptyList());
    }

    /** Make's default makefile: the first of these that the folder has, spelled as it has it. */
    private String defaultMakefile() {
        List<String> names = disk.list(".");
        for (String candidate : new String[] {"GNUmakefile", "makefile", "Makefile"}) {
            if (names.contains(candidate)) {
                return candidate;
            }
        }
        throw MakeFile.MakeException.stop(prefix()
                + "*** No targets specified and no makefile found.");
    }

    /**
     * {@code snap_deps}: a {@code .PHONY} prerequisite is a target that never exists.
     * ({@code .PRECIOUS} only keeps make from deleting a file after an error or an interrupt,
     * which a dry run never does.)
     */
    private void snapDeps() {
        FileEntry phony = db.lookup(".PHONY");
        if (phony != null) {
            for (Dep dep : phony.deps) {
                dep.file.phony = true;
                dep.file.isTarget = true;
                dep.file.lastMtime = MakeDatabase.NONEXISTENT;
                dep.file.mtimeBeforeUpdate = MakeDatabase.NONEXISTENT;
            }
        }
    }

    // ------------------------------------------------------------- remake.c

    /** {@code file_mtime}: looked at once, through the disk. */
    private long mtime(FileEntry file) {
        if (file.lastMtime == MakeDatabase.UNKNOWN) {
            file.lastMtime = disk.exists(file.name)
                    ? MakeDatabase.ORDINARY_BASE + Math.max(0L, disk.modified(file.name))
                    : MakeDatabase.NONEXISTENT;
        }
        return file.lastMtime;
    }

    /** {@code update_file}: "Considering target file", or "Pruning file" if seen in this pass. */
    private void updateFile(FileEntry file) {
        considered.add(file.name);
        if (file.consideredPass == pass) {
            return;
        }
        file.consideredPass = pass;
        updateFile1(file);
    }

    private void updateFile1(FileEntry file) {
        if (file.updated) {
            return;
        }
        file.updating = true;
        long thisMtime = mtime(file);
        boolean noexist = thisMtime == MakeDatabase.NONEXISTENT;
        boolean mustMake = noexist;

        if (!file.phony && file.cmds == null && !file.triedImplicit) {
            patternSearch(file, 0);
            file.triedImplicit = true;
        }

        for (int i = 0; i < file.deps.size();) {
            Dep dep = file.deps.get(i);
            long before = mtime(dep.file);
            if (dep.file.updating) {
                // "Circular <- dependency dropped."
                file.deps.remove(i);
                continue;
            }
            dep.file.parent = file;
            boolean[] maybe = {mustMake};
            checkDep(dep.file, thisMtime, maybe);
            if (!dep.orderOnly) {
                mustMake = maybe[0];
            }
            long after = mtime(dep.file);
            dep.changed = after != before || before == MakeDatabase.NONEXISTENT;
            i++;
        }

        if (mustMake) {
            for (Dep dep : new ArrayList<Dep>(file.deps)) {
                if (dep.file.intermediate) {
                    long before = mtime(dep.file);
                    dep.file.parent = file;
                    updateFile(dep.file);
                    dep.changed = (file.phony && file.cmds != null) || mtime(dep.file) != before;
                }
            }
        }
        file.updating = false;

        boolean depsChanged = false;
        for (Dep dep : file.deps) {
            long depMtime = mtime(dep.file);
            if (!dep.orderOnly) {
                if (depMtime == MakeDatabase.NONEXISTENT && !dep.file.intermediate) {
                    mustMake = true;
                }
                depsChanged |= dep.changed;
            }
            dep.changed |= noexist || depMtime > thisMtime;
        }

        if (!noexist && file.isTarget && !depsChanged && file.cmds == null) {
            mustMake = false;
        }
        if (!mustMake) {
            noticeFinished(file, false);
            return;
        }
        remake(file);
        file.updated = true;
    }

    /** {@code check_dep}: a prerequisite brought up to date, or an intermediate one looked through. */
    private void checkDep(FileEntry file, long thisMtime, boolean[] mustMake) {
        file.updating = true;
        if (file.phony || !file.intermediate) {
            updateFile(file);
            long mtime = mtime(file);
            if (mtime == MakeDatabase.NONEXISTENT || mtime > thisMtime) {
                mustMake[0] = true;
            }
        } else {
            if (!file.phony && file.cmds == null && !file.triedImplicit) {
                patternSearch(file, 0);
                file.triedImplicit = true;
            }
            long mtime = mtime(file);
            if (mtime != MakeDatabase.NONEXISTENT && mtime > thisMtime) {
                mustMake[0] = true;
            } else {
                for (int i = 0; i < file.deps.size();) {
                    Dep dep = file.deps.get(i);
                    if (dep.file.updating) {
                        file.deps.remove(i);
                        continue;
                    }
                    dep.file.parent = file;
                    boolean[] maybe = {mustMake[0]};
                    checkDep(dep.file, thisMtime, maybe);
                    if (!dep.orderOnly) {
                        mustMake[0] = maybe[0];
                    }
                    i++;
                }
            }
        }
        file.updating = false;
    }

    /** {@code remake_file}. */
    private void remake(FileEntry file) {
        if (file.cmds == null) {
            if (!file.phony && !file.isTarget) {
                throw MakeFile.MakeException.stop(prefix() + (file.parent != null
                        ? "No rule to make target '" + file.name + "', needed by '"
                                + file.parent.name + "'."
                        : "No rule to make target '" + file.name + "'."));
            }
            noticeFinished(file, false);
            return;
        }
        file.cmds.chop();
        executeFileCommands(file);
    }

    /** {@code notice_finished_file}: under -n a remade file counts as new, unless only sub-makes ran. */
    private void noticeFinished(FileEntry file, boolean ran) {
        file.updated = true;
        if (file.mtimeBeforeUpdate == MakeDatabase.UNKNOWN) {
            file.mtimeBeforeUpdate = file.lastMtime;
        }
        if (ran && !file.phony) {
            int i = 0;
            if (file.cmds != null) {
                // A real run looks at the file again: its recipe ran, and may or may not have
                // made it. Only a dry run has to assume.
                if (executor == null) {
                    file.cmds.chop();
                    for (i = file.cmds.flags.length; i > 0; --i) {
                        if ((file.cmds.flags[i - 1] & Recipe.RECURSE) == 0) {
                            break;
                        }
                    }
                }
            } else if (file.isTarget) {
                i = 1;
            }
            file.lastMtime = i == 0 ? MakeDatabase.UNKNOWN : MakeDatabase.NEW;
        }
    }

    // ------------------------------------------------------------- commands.c, job.c

    /** {@code execute_file_commands} and {@code new_job}: every line expanded, then each started. */
    private void executeFileCommands(FileEntry file) {
        Recipe recipe = file.cmds;
        if (recipe.blank()) {
            noticeFinished(file, true);
            return;
        }
        if (rebuilding) {
            throw MakeFile.MakeException.at(recipe.lines.get(0).file(),
                    recipe.lines.get(0).number(), "the makefile '" + file.name + "' would be"
                            + " remade before make reads it again; this reader does not follow"
                            + " make's restart");
        }
        expander.automatic(fileVariables(file));
        List<String> lines = new ArrayList<String>();
        try {
            for (MakeFile.Line line : recipe.lines) {
                expander.at(line.file(), line.number());
                lines.add(expander.expand(MakeExpander.collapseInReferences(line.text())));
            }
        } finally {
            expander.automatic(null);
        }
        for (int i = 0; i < lines.size(); i++) {
            startJobCommand(file, i, lines.get(i));
        }
        noticeFinished(file, true);
    }

    /** {@code start_job_command} under -n: printed, and followed if it runs make. */
    private void startJobCommand(FileEntry file, int index, String expanded) {
        Recipe recipe = file.cmds;
        MakeFile.Line line = recipe.lines.get(index);
        int flags = recipe.flags[index];
        boolean noError = (flags & Recipe.NOERROR) != 0;
        int p = 0;
        while (p < expanded.length()) {
            char c = expanded.charAt(p);
            if (c == '@') {
                flags |= Recipe.SILENT;
            } else if (c == '+') {
                flags |= Recipe.RECURSE;
            } else if (c == '-') {
                noError = true;
            } else if (c != ' ' && c != '\t') {
                break;
            }
            p++;
        }
        recipe.flags[index] |= flags & Recipe.RECURSE;
        String command = withoutTabsAfterNewlines(expanded.substring(p));
        if (blank(command)) {
            return;
        }
        if (bareNewline(command)) {
            throw MakeFile.MakeException.at(line.file(), line.number(), "the recipe line of '"
                    + file.name + "' expands to several lines, which ODK's template never does");
        }
        commandsStarted++;
        boolean silent = (flags & Recipe.SILENT) != 0;
        boolean subMake = (flags & Recipe.RECURSE) != 0 || (everyMake && runsMake(command));
        if (executor != null) {
            // The real thing: said, run, and judged by its exit status.
            if (!silent) {
                executor.say(command);
            }
            int status = subMake ? (enterSubMake(command, line) ? 0 : 2)
                    : executor.run(new MakeFile.Step(MakeFile.Step.Kind.COMMAND, command,
                            file.name, line.file(), line.number(), silent, noError, level,
                            disk.folder(), Collections.<MakeFile.Step>emptyList()));
            if (status != 0) {
                String where = "[" + line.file() + ":" + line.number() + ": " + file.name
                        + "] Error " + status;
                if (!noError) {
                    executor.say(prefix() + "*** " + where);
                    throw new Stopped();
                }
                executor.say(prefix() + where + " (ignored)");
            }
            return;
        }
        if (!subMake) {
            steps.add(new MakeFile.Step(MakeFile.Step.Kind.COMMAND, command, file.name,
                    line.file(), line.number(), silent, noError, level, disk.folder(),
                    Collections.<MakeFile.Step>emptyList()));
            return;
        }
        List<MakeFile.Step> inner = followSubMake(command, line);
        steps.add(new MakeFile.Step(MakeFile.Step.Kind.SUB_MAKE, command, file.name, line.file(),
                line.number(), silent, noError, level, disk.folder(), inner));
    }

    /** {@code set_file_variables}: {@code $@ $< $^ $| $*} of the file whose recipe runs. */
    private Map<String, String> fileVariables(FileEntry file) {
        Map<String, String> automatic = new LinkedHashMap<String, String>();
        automatic.put("@", file.name);
        if (file.stem == null) {
            file.stem = "";
            for (String suffix : MakeDatabase.DEFAULT_SUFFIXES) {
                if (file.name.length() > suffix.length() && file.name.endsWith(suffix)) {
                    file.stem = file.name.substring(0, file.name.length() - suffix.length());
                    break;
                }
            }
        }
        automatic.put("*", file.stem);
        String less = "";
        for (Dep dep : file.deps) {
            if (!dep.orderOnly) {
                less = dep.name();
                break;
            }
        }
        automatic.put("<", less);
        Map<String, Dep> first = new LinkedHashMap<String, Dep>();
        for (Dep dep : file.deps) {
            Dep seen = first.get(dep.name());
            if (seen == null) {
                first.put(dep.name(), dep);
            } else if (seen.orderOnly != dep.orderOnly) {
                seen.orderOnly = false;
                dep.orderOnly = false;
            }
        }
        List<String> caret = new ArrayList<String>();
        List<String> bar = new ArrayList<String>();
        for (Dep dep : file.deps) {
            if (first.get(dep.name()) != dep) {
                continue;
            }
            (dep.orderOnly ? bar : caret).add(dep.name());
        }
        automatic.put("^", String.join(" ", caret));
        automatic.put("|", String.join(" ", bar));
        return automatic;
    }

    private static String withoutTabsAfterNewlines(String command) {
        StringBuilder out = new StringBuilder(command.length());
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            out.append(c);
            if (c == '\n' && i + 1 < command.length() && command.charAt(i + 1) == '\t') {
                i++;
            }
        }
        return out.toString();
    }

    private static boolean blank(String command) {
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static boolean bareNewline(String command) {
        for (int i = 0; i < command.length(); i++) {
            if (command.charAt(i) == '\n') {
                int backslashes = 0;
                for (int b = i - 1; b >= 0 && command.charAt(b) == '\\'; b--) {
                    backslashes++;
                }
                if (backslashes % 2 == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------- sub-makes

    /** Whether a command, as expanded, is make itself with its arguments. */
    private boolean runsMake(String command) {
        String make = db.variable("MAKE").value;
        return command.equals(make) || command.startsWith(make + " ")
                || command.startsWith(make + "\t");
    }

    /**
     * A recipe line make runs even under -n: it must be {@code make} with variables, goals and
     * {@code --assume-new}, as ODK's recipes are. Followed by a make one level down, with this
     * make's command-line variables passed on and the line's own beating them.
     */
    private List<MakeFile.Step> followSubMake(String command, MakeFile.Line line) {
        List<String> goals = new ArrayList<String>();
        MakeRunner child = subMake(command, line, goals);
        Result result = child.run(goals);
        considered.addAll(result.considered);
        return result.steps;
    }

    /**
     * The same line in a real run: the make one level down runs its goals now, on the disk as
     * the lines before it left it.
     *
     * @return whether it made them; false when it stopped, which it has said
     */
    private boolean enterSubMake(String command, MakeFile.Line line) {
        List<String> goals = new ArrayList<String>();
        MakeRunner child = subMake(command, line, goals);
        executor.say(child.prefix() + "Entering directory '" + disk.folder() + "'");
        boolean made = true;
        try {
            child.run(goals);
        } catch (Stopped stopped) {
            made = false;
        } catch (MakeFile.MakeException stop) {
            if (!stop.isMakesOwn()) {
                throw stop;
            }
            executor.say(asMakeStops(stop.getMessage()));
            made = false;
        }
        executor.say(child.prefix() + "Leaving directory '" + disk.folder() + "'");
        considered.addAll(child.considered);
        return made;
    }

    /**
     * A stop of make's own as make words it on its last line:
     * {@code make: *** No rule to make target 'x', needed by 'y'.  Stop.}
     */
    static String asMakeStops(String message) {
        int colon = message.indexOf(": ");
        String who = colon < 0 ? "" : message.substring(0, colon + 2);
        String what = colon < 0 ? message : message.substring(colon + 2);
        return who + (what.startsWith("*** ") ? "" : "*** ") + what + "  Stop.";
    }

    /** A real run ended at a recipe line that failed; make has said which. */
    static final class Stopped extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stopped() {
            super(null, null, false, false);
        }
    }

    /** The make one level down for a recipe line that runs make; its goals are added to goals. */
    private MakeRunner subMake(String command, MakeFile.Line line, List<String> goals) {
        List<String> words = shellWords(command, line);
        String make = db.variable("MAKE").value;
        if (words.isEmpty() || !words.get(0).equals(make)) {
            throw MakeFile.MakeException.at(line.file(), line.number(), "make runs '" + command
                    + "' even under -n, and it is not a sub-make this reader can follow");
        }
        Map<String, String> variables = new LinkedHashMap<String, String>(commandLine);
        List<String> newFiles = new ArrayList<String>();
        for (int k = 1; k < words.size(); k++) {
            String word = words.get(k);
            if (word.startsWith("--assume-new=")) {
                newFiles.add(word.substring("--assume-new=".length()));
            } else if (word.equals("-W") && k + 1 < words.size()) {
                newFiles.add(words.get(++k));
            } else if (word.startsWith("-")) {
                throw MakeFile.MakeException.at(line.file(), line.number(), "the sub-make option '"
                        + word + "' is not one ODK's template uses");
            } else {
                String[] definition = MakeReader.parseVariableDefinition(word);
                if (definition == null) {
                    goals.add(word);
                } else if (definition[1].equals("=") && !definition[0].contains("$")) {
                    variables.remove(definition[0]);
                    variables.put(definition[0], definition[2]);
                } else {
                    throw MakeFile.MakeException.at(line.file(), line.number(), "the sub-make"
                            + " argument '" + word + "' is not of the form NAME=value");
                }
            }
        }
        return new MakeRunner(disk, shell, variables, level + 1, newFiles, null, everyMake,
                executor);
    }

    /**
     * The words the shell would make of a simple command: quotes and backslashes as sh has
     * them, a backslash-newline removed. Anything that would make it more than one plain
     * command - {@code ; & | < > ( ) $ `}, a glob, a leading {@code ~} or {@code #} - is refused.
     */
    static List<String> shellWords(String command, MakeFile.Line line) {
        List<String> words = new ArrayList<String>();
        StringBuilder word = null;
        int length = command.length();
        for (int i = 0; i < length; i++) {
            char c = command.charAt(i);
            if (c == '\\' && i + 1 < length && command.charAt(i + 1) == '\n') {
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\n') {
                if (word != null) {
                    words.add(word.toString());
                    word = null;
                }
                continue;
            }
            if (word == null) {
                word = new StringBuilder();
                if (c == '~' || c == '#') {
                    throw notSimple(command, line);
                }
            }
            if (c == '\'') {
                int close = command.indexOf('\'', i + 1);
                if (close < 0) {
                    throw notSimple(command, line);
                }
                word.append(command, i + 1, close);
                i = close;
            } else if (c == '"') {
                int j = i + 1;
                while (true) {
                    if (j >= length) {
                        throw notSimple(command, line);
                    }
                    char d = command.charAt(j);
                    if (d == '"') {
                        break;
                    }
                    if (d == '$' || d == '`') {
                        throw notSimple(command, line);
                    }
                    if (d == '\\' && j + 1 < length) {
                        char e = command.charAt(j + 1);
                        if (e == '\n') {
                            j += 2;
                            continue;
                        }
                        if (e == '$' || e == '`' || e == '"' || e == '\\') {
                            word.append(e);
                            j += 2;
                            continue;
                        }
                    }
                    word.append(d);
                    j++;
                }
                i = j;
            } else if (c == '\\') {
                if (i + 1 >= length) {
                    throw notSimple(command, line);
                }
                word.append(command.charAt(++i));
            } else if (";&|<>()$`*?[".indexOf(c) >= 0) {
                throw notSimple(command, line);
            } else {
                word.append(c);
            }
        }
        if (word != null) {
            words.add(word.toString());
        }
        return words;
    }

    private static MakeFile.MakeException notSimple(String command, MakeFile.Line line) {
        return MakeFile.MakeException.at(line.file(), line.number(), "make runs '" + command
                + "' even under -n, and it is more than one plain make command");
    }

    // ------------------------------------------------------------- implicit.c

    /** A pattern rule that matches, as {@code struct tryrule}. */
    private static final class Try {
        final PatternRule rule;
        final MakeDatabase.Match match;
        final int order;
        boolean rejected;

        Try(PatternRule rule, MakeDatabase.Match match, int order) {
            this.rule = rule;
            this.match = match;
            this.order = order;
        }
    }

    /** A prerequisite a pattern rule would have, as {@code struct patdeps}. */
    private static final class Pat {
        final String name;
        final boolean orderOnly;
        FileEntry intermediate;

        Pat(String name, boolean orderOnly) {
            this.name = name;
            this.orderOnly = orderOnly;
        }
    }

    /**
     * {@code pattern_search}: the pattern rule with the shortest stem whose prerequisites exist,
     * are mentioned in the makefile, or - on a second try - can be made by another pattern rule
     * as intermediate files. Its prerequisites go before the file's own; it gives the file its
     * recipe and its stem.
     */
    private boolean patternSearch(FileEntry file, int recursions) {
        String name = file.name;
        List<Try> tries = new ArrayList<Try>();
        boolean specificRuleMatched = false;
        for (PatternRule rule : db.patternRules) {
            if (!rule.deps.isEmpty() && rule.cmds == null) {
                continue;
            }
            if (rule.inUse) {
                continue;
            }
            if (recursions > 0 && rule.target.equals("%")) {
                continue;
            }
            MakeDatabase.Match match = MakeDatabase.match(rule.target, name);
            if (match == null) {
                continue;
            }
            if (!rule.target.equals("%")) {
                specificRuleMatched = true;
            }
            if (rule.deps.isEmpty() && rule.cmds == null) {
                continue;
            }
            tries.add(new Try(rule, match, tries.size()));
        }
        if (tries.isEmpty()) {
            return false;
        }
        Collections.sort(tries, new Comparator<Try>() {
            @Override
            public int compare(Try a, Try b) {
                int byLength = Integer.compare(a.match.length(), b.match.length());
                return byLength != 0 ? byLength : Integer.compare(a.order, b.order);
            }
        });
        if (specificRuleMatched) {
            for (Try t : tries) {
                if (t.rule.target.equals("%")) {
                    t.rejected = true;
                }
            }
        }

        Try found = null;
        List<Pat> deplist = null;
        for (int intermediateOk = 0; intermediateOk < 2 && found == null; intermediateOk++) {
            for (Try t : tries) {
                if (t.rejected) {
                    continue;
                }
                PatternRule rule = t.rule;
                if (rule.deps.isEmpty()) {
                    found = t;
                    deplist = new ArrayList<Pat>();
                    break;
                }
                rule.inUse = true;
                List<Pat> pats = new ArrayList<Pat>();
                boolean failed = false;
                try {
                    for (Dep dep : rule.deps) {
                        String pattern = dep.name;
                        int percent = pattern.indexOf('%');
                        String depName = percent < 0 ? pattern : t.match.dir
                                + pattern.substring(0, percent) + t.match.stem
                                + pattern.substring(percent + 1);
                        if (db.impossible.contains(depName)) {
                            t.rejected = true;
                            failed = true;
                            break;
                        }
                        Pat pat = new Pat(depName, dep.orderOnly);
                        if (mentionedFor(file, depName) || db.lookup(depName) != null
                                || disk.exists(depName)) {
                            pats.add(pat);
                            continue;
                        }
                        if (intermediateOk == 1) {
                            FileEntry candidate = new FileEntry(depName);
                            if (patternSearch(candidate, recursions + 1)) {
                                pat.intermediate = candidate;
                                pats.add(pat);
                                continue;
                            }
                            db.impossible.add(depName);
                        }
                        failed = true;
                        break;
                    }
                } finally {
                    rule.inUse = false;
                }
                if (!failed) {
                    found = t;
                    deplist = pats;
                    break;
                }
            }
        }
        if (found == null) {
            return false;
        }

        for (int k = deplist.size() - 1; k >= 0; k--) {
            Pat pat = deplist.get(k);
            if (pat.intermediate != null) {
                FileEntry made = pat.intermediate;
                FileEntry entry = db.enter(made.name);
                entry.deps = made.deps;
                entry.cmds = made.cmds;
                entry.stem = made.stem;
                entry.isTarget = true;
                entry.intermediate = true;
                entry.triedImplicit = true;
                for (Dep dep : entry.deps) {
                    dep.file = db.enter(dep.name);
                    dep.name = null;
                }
            }
            Dep dep;
            if (recursions > 0) {
                dep = new Dep(pat.name, null, pat.orderOnly);
            } else {
                dep = new Dep(null, db.enter(pat.name), pat.orderOnly);
            }
            file.deps.add(0, dep);
        }
        file.stem = found.match.fullStem();
        file.cmds = found.rule.cmds;
        file.isTarget = true;
        return true;
    }

    /** Whether the name is already among the file's own (explicit) prerequisites. */
    private static boolean mentionedFor(FileEntry file, String name) {
        for (Dep dep : file.deps) {
            if (dep.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
