package de.fizkarlsruhe.ise.ontoboard.odk.make;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads makefile text into a {@link MakeDatabase} as GNU make 4.3's {@code read.c} does
 * ({@code eval}, {@code readline}, {@code collapse_continuations}, {@code conditional_line},
 * {@code do_define}, {@code record_files}), for the constructs ODK's template has: assignments
 * with {@code = := ?= +=}, {@code export}, {@code define}/{@code endef}, {@code ifeq}/{@code ifneq}
 * with {@code else}/{@code endif}, {@code include}, explicit and pattern rules with order-only
 * prerequisites, {@code .PHONY} and {@code .PRECIOUS}. Anything else is refused where it is
 * written.
 */
final class MakeReader {

    private static final Set<String> SPECIAL_TARGETS = new HashSet<String>(Arrays.asList(
            ".SUFFIXES", ".DEFAULT", ".INTERMEDIATE", ".NOTINTERMEDIATE", ".SECONDARY",
            ".SECONDEXPANSION", ".DELETE_ON_ERROR", ".IGNORE", ".LOW_RESOLUTION_TIME", ".SILENT",
            ".EXPORT_ALL_VARIABLES", ".NOTPARALLEL", ".ONESHELL", ".POSIX", ".WAIT"));

    /** Variables that change how make itself reads or runs; ODK's template sets none of them. */
    private static final Set<String> UNASSIGNABLE = new HashSet<String>(Arrays.asList("VPATH",
            "GPATH", ".RECIPEPREFIX", "MAKEFLAGS", "MFLAGS", "MAKEFILES", "MAKEOVERRIDES",
            ".DEFAULT_GOAL", ".SHELLFLAGS", ".EXTRA_PREREQS", ".SECONDEXPANSION", "MAKE",
            "MAKE_COMMAND", "MAKELEVEL", "CURDIR", "MAKECMDGOALS", "SUFFIXES", ".LIBPATTERNS",
            "MAKEFILE_LIST", ".VARIABLES", ".FEATURES", ".INCLUDE_DIRS"));

    private final MakeDatabase db;
    private final MakeExpander expander;

    MakeReader(MakeDatabase db, MakeExpander expander) {
        this.db = db;
        this.expander = expander;
        expander.reader(this);
    }

    /** Reads a makefile, as {@code eval_makefile}: noted among the makefiles, a file of its own. */
    void readMakefile(String name, String text) {
        db.makefiles.add(name);
        db.enter(name);
        new Context(name).run(text, 1);
    }

    /** {@code $(eval)}: the text read as makefile lines, said to be where the call is written. */
    void evalText(String text, String file, int line) {
        new Context(file == null ? "(eval)" : file).run(text, line);
    }

    // ------------------------------------------------------------- one makefile's reading

    /** The state {@code eval} keeps for one makefile: conditionals, the rule being read. */
    private final class Context {
        private final String file;
        private final List<int[]> conditionals = new ArrayList<int[]>();
        private Waiting rule;
        private boolean noTargets;
        private boolean inIgnoredDefine;
        private String[] physical;
        private int next;
        private int first;

        Context(String file) {
            this.file = file;
        }

        void run(String text, int firstLine) {
            physical = text.replace("\r\n", "\n").split("\n", -1);
            if (physical.length > 0 && physical[physical.length - 1].isEmpty()) {
                physical = Arrays.copyOf(physical, physical.length - 1);
            }
            first = firstLine;
            next = 0;
            while (next < physical.length) {
                int lineNumber = first + next;
                String line = readLine();
                eval(line, lineNumber);
            }
            recordWaitingRule();
            if (!conditionals.isEmpty()) {
                throw MakeFile.MakeException.at(file, first + physical.length - 1,
                        "missing 'endif'");
            }
        }

        /** {@code readline}: a line and the lines it continues onto with an odd number of backslashes. */
        private String readLine() {
            StringBuilder line = new StringBuilder(physical[next++]);
            while (endsWithOddBackslashes(line) && next < physical.length) {
                line.append('\n').append(physical[next++]);
            }
            return line.toString();
        }

        private boolean ignoring() {
            for (int[] conditional : conditionals) {
                if (conditional[0] != 0) {
                    return true;
                }
            }
            return false;
        }

        private MakeFile.MakeException refuse(int line, String message) {
            return MakeFile.MakeException.at(file, line, message);
        }

        private void eval(String line, int number) {
            if (line.isEmpty()) {
                return;
            }
            if (line.charAt(0) == '\t') {
                if (noTargets) {
                    return;
                }
                if (rule != null) {
                    if (ignoring()) {
                        return;
                    }
                    String recipeLine = line.substring(1);
                    expander.scan(recipeLine, file, number);
                    rule.recipe.add(new MakeFile.Line(recipeLine, file, number));
                    return;
                }
            }
            String collapsed = removeComments(collapseContinuations(line));
            String p = skipSpace(collapsed, 0);

            Assignment assignment = parseVarAssignment(p, number);
            if (assignment != null) {
                if (ignoring()) {
                    if (assignment.define) {
                        inIgnoredDefine = true;
                    }
                    return;
                }
                if (assignment.override || assignment.privateVar || assignment.unexport
                        || assignment.undefine) {
                    throw refuse(number, "'" + assignment.modifier + "' is not part of what"
                            + " ODK's template uses");
                }
                recordWaitingRule();
                MakeDatabase.Var var;
                if (assignment.define) {
                    var = define(assignment.name, number);
                } else {
                    var = assign(assignment, collapsed.trim(), number);
                }
                if (assignment.export && var != null) {
                    var.exported = true;
                }
                return;
            }
            if (p.isEmpty()) {
                return;
            }
            int wordEnd = p.length();
            for (int i = 0; i < p.length(); i++) {
                if (Character.isWhitespace(p.charAt(i))) {
                    wordEnd = i;
                    break;
                }
            }
            String word = p.substring(0, wordEnd);
            String rest = skipSpace(p, wordEnd);
            if (inIgnoredDefine) {
                if (word.equals("endef") && rest.isEmpty()) {
                    inIgnoredDefine = false;
                }
                return;
            }
            if (conditional(word, rest, number)) {
                return;
            }
            if (ignoring()) {
                return;
            }
            if (word.equals("export")) {
                recordWaitingRule();
                if (rest.isEmpty()) {
                    throw refuse(number, "'export' of every variable is not part of what ODK's"
                            + " template uses");
                }
                expander.scan(rest, file, number);
                expander.at(file, number);
                for (String name : MakeExpander.words(expander.expand(rest))) {
                    MakeDatabase.Var var = db.variable(name);
                    if (var == null) {
                        var = new MakeDatabase.Var(name);
                        var.file = file;
                        var.line = number;
                        db.variables.put(name, var);
                    }
                    var.exported = true;
                }
                return;
            }
            if (word.equals("unexport") || word.equals("vpath") || word.equals("-include")
                    || word.equals("sinclude") || word.equals("load") || word.equals("-load")
                    || word.equals("override") || word.equals("private")
                    || word.equals("undefine") || word.equals("define")
                    || word.equals("endef")) {
                throw refuse(number, "'" + word + "' is not part of what ODK's template uses");
            }
            if (word.equals("include")) {
                recordWaitingRule();
                include(rest, number);
                return;
            }
            if (line.charAt(0) == '\t') {
                throw refuse(number, "recipe commences before first target");
            }
            recordWaitingRule();
            readRule(line, number);
        }

        // --------------------------------------------------------- assignments

        private MakeDatabase.Var assign(Assignment assignment, String written, int number) {
            expander.scan(assignment.name, file, number);
            expander.scan(assignment.value, file, number);
            expander.at(file, number);
            String name = expander.expand(assignment.name).trim();
            checkName(name, number);
            return definition(name, assignment.operator, assignment.value,
                    MakeFile.Origin.FILE, new MakeFile.Line(written, file, number));
        }

        private void checkName(String name, int number) {
            if (name.isEmpty()) {
                throw refuse(number, "empty variable name");
            }
            for (char c : name.toCharArray()) {
                if (Character.isWhitespace(c)) {
                    throw refuse(number, "a variable name with a space, '" + name + "'");
                }
            }
            if (UNASSIGNABLE.contains(name)) {
                throw refuse(number, "setting " + name + " changes how make itself works, and"
                        + " ODK's template does not set it");
            }
        }

        /** {@code do_define}: the lines up to the matching {@code endef} are the value. */
        private MakeDatabase.Var define(String nameText, int number) {
            if (parseVariableDefinition(nameText) != null) {
                throw refuse(number, "'define' with an assignment operator is not part of what"
                        + " ODK's template uses");
            }
            expander.scan(nameText, file, number);
            expander.at(file, number);
            String name = expander.expand(nameText).trim();
            checkName(name, number);
            int levels = 1;
            StringBuilder body = new StringBuilder();
            boolean any = false;
            while (true) {
                if (next >= physical.length) {
                    throw refuse(number, "missing 'endef', unterminated 'define'");
                }
                int lineNumber = first + next;
                String line = collapseContinuations(readLine());
                if (line.isEmpty() || line.charAt(0) != '\t') {
                    String p = skipSpace(line, 0);
                    if (startsWithWord(p, "define")) {
                        levels++;
                    } else if (startsWithWord(p, "endef")) {
                        String after = removeComments(p.substring(5));
                        if (!after.trim().isEmpty()) {
                            throw refuse(lineNumber, "extraneous text after 'endef' directive");
                        }
                        if (--levels == 0) {
                            break;
                        }
                    }
                }
                if (any) {
                    body.append('\n');
                }
                body.append(line);
                any = true;
            }
            String value = body.toString();
            expander.scan(value, file, number);
            return definition(name, "=", value, MakeFile.Origin.FILE,
                    new MakeFile.Line("define " + nameText, file, number));
        }

        // --------------------------------------------------------- conditionals

        /** {@code conditional_line}: true if the line was a conditional directive. */
        private boolean conditional(String word, String rest, int number) {
            if (word.equals("ifdef") || word.equals("ifndef")) {
                throw refuse(number, "'" + word + "' is not part of what ODK's template uses");
            }
            if (word.equals("endif")) {
                if (!rest.isEmpty()) {
                    throw refuse(number, "extraneous text after 'endif' directive");
                }
                if (conditionals.isEmpty()) {
                    throw refuse(number, "extraneous 'endif'");
                }
                conditionals.remove(conditionals.size() - 1);
                return true;
            }
            if (word.equals("else")) {
                if (conditionals.isEmpty()) {
                    throw refuse(number, "extraneous 'else'");
                }
                int[] top = conditionals.get(conditionals.size() - 1);
                if (top[1] != 0) {
                    throw refuse(number, "only one 'else' per conditional");
                }
                if (!rest.isEmpty()) {
                    throw refuse(number, "'else' followed by another conditional is not part of"
                            + " what ODK's template uses");
                }
                top[0] = top[0] == 0 ? 2 : (top[0] == 1 ? 0 : top[0]);
                top[1] = 1;
                return true;
            }
            if (!word.equals("ifeq") && !word.equals("ifneq")) {
                return false;
            }
            boolean alreadyIgnoring = ignoring();
            int[] entry = new int[] {1, 0};
            conditionals.add(entry);
            if (alreadyIgnoring) {
                return true;
            }
            if (rest.isEmpty() || rest.charAt(0) != '(') {
                throw refuse(number, "'" + word + "' with quotes instead of (a,b) is not part of"
                        + " what ODK's template uses");
            }
            int p = 1;
            int count = 0;
            for (; p < rest.length(); p++) {
                char c = rest.charAt(p);
                if (c == '(') {
                    count++;
                } else if (c == ')') {
                    count--;
                } else if (c == ',' && count <= 0) {
                    break;
                }
            }
            if (p >= rest.length()) {
                throw refuse(number, "invalid syntax in conditional");
            }
            int s1End = p;
            while (s1End > 1 && (rest.charAt(s1End - 1) == ' ' || rest.charAt(s1End - 1) == '\t')) {
                s1End--;
            }
            String s1 = rest.substring(1, s1End);
            int s2Start = skipSpaceIndex(rest, p + 1);
            count = 0;
            int q;
            for (q = s2Start; q < rest.length(); q++) {
                char c = rest.charAt(q);
                if (c == '(') {
                    count++;
                } else if (c == ')') {
                    if (count <= 0) {
                        break;
                    }
                    count--;
                }
            }
            if (q >= rest.length()) {
                throw refuse(number, "invalid syntax in conditional");
            }
            String s2 = rest.substring(s2Start, q);
            if (!rest.substring(q + 1).trim().isEmpty()) {
                throw refuse(number, "extraneous text after '" + word + "' directive");
            }
            expander.scan(s1, file, number);
            expander.scan(s2, file, number);
            expander.at(file, number);
            String left = expander.expand(s1);
            String right = expander.expand(s2);
            entry[0] = left.equals(right) == word.equals("ifneq") ? 1 : 0;
            return true;
        }

        // --------------------------------------------------------- include

        private void include(String rest, int number) {
            expander.scan(rest, file, number);
            expander.at(file, number);
            for (String name : MakeExpander.words(expander.expand(rest))) {
                if (name.indexOf('*') >= 0 || name.indexOf('?') >= 0 || name.indexOf('[') >= 0
                        || name.startsWith("~")) {
                    throw refuse(number, "a wildcard in 'include' is not part of what ODK's"
                            + " template uses");
                }
                String text = db.disk.read(name);
                if (text == null) {
                    throw refuse(number, name + ": No such file or directory");
                }
                readMakefile(name, text);
            }
        }

        // --------------------------------------------------------- rules

        private void readRule(String line, int number) {
            String text = line;
            int stop = findUnquoted(text, ";#");
            if (stop >= 0) {
                if (text.charAt(stop) == ';') {
                    throw refuse(number, "a recipe on the rule's own line (after ';') is not part"
                            + " of what ODK's template uses");
                }
                text = text.substring(0, stop);
            }
            text = collapseContinuations(text);
            int colon = findColon(text);
            if (colon < 0) {
                expander.scan(text, file, number);
                expander.at(file, number);
                if (expander.expand(text).trim().isEmpty()) {
                    return;
                }
                throw refuse(number, "missing separator");
            }
            String targetsText = text.substring(0, colon);
            String rest = text.substring(colon + 1);
            if (rest.startsWith(":")) {
                throw refuse(number, "a double-colon rule is not part of what ODK's template uses");
            }
            if (parseVarAssignment(skipSpace(rest, 0), number) != null) {
                throw refuse(number, "a target-specific variable is not part of what ODK's"
                        + " template uses");
            }
            if (text.indexOf('\\') >= 0) {
                throw refuse(number, "a backslash in a rule's names is not part of what ODK's"
                        + " template uses");
            }
            expander.scan(targetsText, file, number);
            expander.scan(rest, file, number);
            expander.at(file, number);
            String targetsExpanded = expander.expand(targetsText);
            if (targetsExpanded.indexOf(':') >= 0) {
                throw refuse(number, "a colon that comes from a variable in a rule's targets");
            }
            List<String> targets = MakeExpander.words(targetsExpanded);
            if (targets.isEmpty()) {
                noTargets = true;
                return;
            }
            String depsExpanded = expander.expand(rest);
            if (depsExpanded.indexOf(':') >= 0) {
                throw refuse(number, "a static pattern rule (a second ':') is not part of what"
                        + " ODK's template uses");
            }
            if (depsExpanded.indexOf(';') >= 0 || depsExpanded.indexOf('=') >= 0) {
                throw refuse(number, "a ';' or '=' among a rule's prerequisites");
            }
            List<String> normal = new ArrayList<String>();
            List<String> orderOnly = new ArrayList<String>();
            int bar = depsExpanded.indexOf('|');
            normal.addAll(MakeExpander.words(bar < 0 ? depsExpanded : depsExpanded.substring(0, bar)));
            if (bar >= 0) {
                String after = depsExpanded.substring(bar + 1);
                if (after.indexOf('|') >= 0) {
                    throw refuse(number, "a second '|' among a rule's prerequisites");
                }
                orderOnly.addAll(MakeExpander.words(after));
            }
            List<String> all = new ArrayList<String>(targets);
            all.addAll(normal);
            all.addAll(orderOnly);
            for (String name : all) {
                if (name.indexOf('*') >= 0 || name.indexOf('?') >= 0 || name.indexOf('[') >= 0
                        || name.startsWith("~")) {
                    throw refuse(number, "the name '" + name + "' would be globbed by make, which"
                            + " ODK's template never has it do");
                }
                if (name.indexOf('(') >= 0 && name.endsWith(")")) {
                    throw refuse(number, "an archive member '" + name + "' is not part of what"
                            + " ODK's template uses");
                }
            }
            boolean pattern = false;
            for (String target : targets) {
                if (target.indexOf('%') >= 0) {
                    pattern = true;
                }
            }
            if (pattern) {
                for (String target : targets) {
                    if (target.indexOf('%') < 0) {
                        throw refuse(number, "mixed implicit and normal rules");
                    }
                }
                if (targets.size() > 1) {
                    throw refuse(number, "a pattern rule with several targets is not part of what"
                            + " ODK's template uses");
                }
            }
            for (String target : targets) {
                if (SPECIAL_TARGETS.contains(target) || suffixRule(target)) {
                    throw refuse(number, "the special target " + target + " is not part of what"
                            + " ODK's template uses");
                }
            }
            noTargets = false;
            if (db.defaultGoal == null && !pattern) {
                for (String target : targets) {
                    if (target.startsWith(".") && target.indexOf('/') < 0) {
                        continue;
                    }
                    db.defaultGoal = target;
                    break;
                }
            }
            rule = new Waiting(targetsText.trim(), targets, normal, orderOnly, pattern, number);
        }

        /** {@code record_waiting_files}: the rule read so far goes into the database. */
        private void recordWaitingRule() {
            Waiting waiting = rule;
            rule = null;
            if (waiting == null) {
                return;
            }
            MakeDatabase.Recipe cmds = waiting.recipe.isEmpty() ? null
                    : new MakeDatabase.Recipe(waiting.recipe);
            boolean special = waiting.targets.contains(".PHONY")
                    || waiting.targets.contains(".PRECIOUS");
            if (special && cmds != null) {
                throw refuse(waiting.recipe.get(0).number(), "a recipe for .PHONY or .PRECIOUS");
            }
            db.rules.add(new MakeFile.Rule(waiting.written, waiting.targets, waiting.normal,
                    waiting.orderOnly, waiting.recipe, waiting.pattern, file, waiting.line));
            if (waiting.pattern) {
                List<MakeDatabase.Dep> deps = new ArrayList<MakeDatabase.Dep>();
                for (String name : waiting.normal) {
                    deps.add(new MakeDatabase.Dep(name, null, false));
                }
                for (String name : waiting.orderOnly) {
                    deps.add(new MakeDatabase.Dep(name, null, true));
                }
                newPatternRule(new MakeDatabase.PatternRule(waiting.targets.get(0), deps, cmds,
                        file, waiting.line));
                return;
            }
            // enter_prereqs: an explicit rule's prerequisites are files make knows of.
            List<MakeDatabase.Dep> deps = new ArrayList<MakeDatabase.Dep>();
            for (String name : waiting.normal) {
                deps.add(new MakeDatabase.Dep(null, db.enter(name), false));
            }
            for (String name : waiting.orderOnly) {
                deps.add(new MakeDatabase.Dep(null, db.enter(name), true));
            }
            for (String target : waiting.targets) {
                MakeDatabase.FileEntry entry = db.enter(target);
                if (cmds != null) {
                    // A later recipe for the same target replaces the earlier (make warns).
                    entry.cmds = cmds;
                }
                entry.isTarget = true;
                List<MakeDatabase.Dep> mine = new ArrayList<MakeDatabase.Dep>();
                for (MakeDatabase.Dep dep : deps) {
                    mine.add(dep.copy());
                }
                if (entry.deps.isEmpty()) {
                    entry.deps = mine;
                } else if (cmds != null) {
                    mine.addAll(entry.deps);
                    entry.deps = mine;
                } else {
                    entry.deps.addAll(mine);
                }
            }
        }

        /** {@code new_pattern_rule}: an identical rule (targets and prerequisites) is replaced. */
        private void newPatternRule(MakeDatabase.PatternRule rule) {
            for (int i = 0; i < db.patternRules.size(); i++) {
                MakeDatabase.PatternRule old = db.patternRules.get(i);
                if (old.target.equals(rule.target) && sameNames(old.deps, rule.deps)) {
                    db.patternRules.remove(i);
                    break;
                }
            }
            db.patternRules.add(rule);
        }
    }

    /** A rule line read, waiting for its recipe lines. */
    private static final class Waiting {
        final String written;
        final List<String> targets;
        final List<String> normal;
        final List<String> orderOnly;
        final boolean pattern;
        final int line;
        final List<MakeFile.Line> recipe = new ArrayList<MakeFile.Line>();

        Waiting(String written, List<String> targets, List<String> normal,
                List<String> orderOnly, boolean pattern, int line) {
            this.written = written;
            this.targets = targets;
            this.normal = normal;
            this.orderOnly = orderOnly;
            this.pattern = pattern;
            this.line = line;
        }
    }

    private static boolean sameNames(List<MakeDatabase.Dep> a, List<MakeDatabase.Dep> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).name().equals(b.get(i).name())) {
                return false;
            }
        }
        return true;
    }

    /** {@code .c.o} or {@code .c}: an old-fashioned suffix rule of make's default suffixes. */
    private static boolean suffixRule(String target) {
        if (!target.startsWith(".")) {
            return false;
        }
        if (MakeDatabase.DEFAULT_SUFFIXES.contains(target)) {
            return true;
        }
        for (String suffix : MakeDatabase.DEFAULT_SUFFIXES) {
            if (target.startsWith(suffix)
                    && MakeDatabase.DEFAULT_SUFFIXES.contains(target.substring(suffix.length()))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- variables

    /**
     * {@code do_variable_definition}, for a makefile's assignment or a command line's: the
     * operator's rules, then make's order of strength - a weaker origin does not replace a
     * stronger one.
     */
    MakeDatabase.Var definition(String name, String operator, String value,
            MakeFile.Origin origin, MakeFile.Line written) {
        MakeDatabase.Var existing = db.variable(name);
        if (existing != null && written != null) {
            existing.assignments.add(written);
        }
        String stored;
        boolean recursive;
        if (operator.equals("=")) {
            stored = value;
            recursive = true;
        } else if (operator.equals(":=")) {
            stored = expander.expand(value);
            recursive = false;
        } else if (operator.equals("?=")) {
            if (existing != null) {
                return existing;
            }
            stored = value;
            recursive = true;
        } else if (operator.equals("+=")) {
            if (existing == null) {
                stored = value;
                recursive = true;
            } else {
                String added = existing.recursive ? value : expander.expand(value);
                stored = existing.value.isEmpty() ? added : existing.value + " " + added;
                recursive = existing.recursive;
            }
        } else {
            throw MakeFile.MakeException.at(written == null ? null : written.file(),
                    written == null ? 0 : written.number(), "the assignment '" + operator
                            + "' is not part of what ODK's template uses");
        }
        if (existing != null && existing.origin.compareTo(origin) > 0) {
            return existing;
        }
        MakeDatabase.Var var = existing;
        if (var == null) {
            var = new MakeDatabase.Var(name);
            if (written != null) {
                var.assignments.add(written);
            }
            db.variables.put(name, var);
        }
        var.value = stored;
        var.recursive = recursive;
        var.origin = origin;
        var.file = written == null ? null : written.file();
        var.line = written == null ? 0 : written.number();
        return var;
    }

    /** What {@code parse_var_assignment} found: modifiers, and the definition or a define. */
    private static final class Assignment {
        boolean export;
        boolean unexport;
        boolean override;
        boolean privateVar;
        boolean define;
        boolean undefine;
        String modifier;
        String name;
        String operator;
        String value;
    }

    /** {@code parse_var_assignment}: an assignment, possibly after modifiers; null if none. */
    private Assignment parseVarAssignment(String line, int number) {
        Assignment found = new Assignment();
        String p = skipSpace(line, 0);
        if (p.isEmpty()) {
            return null;
        }
        while (true) {
            String[] definition = parseVariableDefinition(p);
            if (definition != null) {
                found.name = definition[0];
                found.operator = definition[1];
                found.value = definition[2];
                return found;
            }
            int end = 0;
            while (end < p.length() && !Character.isWhitespace(p.charAt(end))) {
                end++;
            }
            String word = p.substring(0, end);
            String rest = skipSpace(p, end);
            if (word.equals("export")) {
                found.export = true;
            } else if (word.equals("unexport")) {
                found.unexport = true;
            } else if (word.equals("override")) {
                found.override = true;
            } else if (word.equals("private")) {
                found.privateVar = true;
            } else if (word.equals("define")) {
                found.define = true;
                found.name = rest;
                if (found.modifier == null) {
                    found.modifier = word;
                }
                return found;
            } else if (word.equals("undefine")) {
                found.undefine = true;
                found.name = rest;
                found.modifier = word;
                return found;
            } else {
                return null;
            }
            if (found.modifier == null && !word.equals("export")) {
                found.modifier = word;
            }
            p = rest;
            if (p.isEmpty()) {
                return null;
            }
        }
    }

    /**
     * {@code parse_variable_definition}: name (unexpanded), operator, value (leading whitespace
     * gone, trailing kept) - or null if the text is not an assignment.
     */
    static String[] parseVariableDefinition(String text) {
        int p = 0;
        int length = text.length();
        boolean whitespace = false;
        int nameEnd = -1;
        while (true) {
            if (p >= length) {
                return null;
            }
            char c = text.charAt(p++);
            if (c == '#') {
                return null;
            }
            if (c == '$') {
                if (p >= length) {
                    return null;
                }
                char open = text.charAt(p++);
                char close;
                if (open == '(') {
                    close = ')';
                } else if (open == '{') {
                    close = '}';
                } else {
                    continue;
                }
                int count = 1;
                for (; p < length; p++) {
                    char ch = text.charAt(p);
                    if (ch == close && --count == 0) {
                        p++;
                        break;
                    }
                    if (ch == open) {
                        count++;
                    }
                }
                continue;
            }
            if (c == ' ' || c == '\t') {
                whitespace = true;
                nameEnd = p - 1;
                while (p < length && Character.isWhitespace(text.charAt(p))) {
                    p++;
                }
                continue;
            }
            if (c == '=') {
                if (nameEnd < 0) {
                    nameEnd = p - 1;
                }
                return new String[] {text.substring(0, nameEnd), "=", skipSpace(text, p)};
            }
            if (p < length && text.charAt(p) == '=') {
                String operator;
                if (c == ':') {
                    operator = ":=";
                } else if (c == '+') {
                    operator = "+=";
                } else if (c == '?') {
                    operator = "?=";
                } else if (c == '!') {
                    operator = "!=";
                } else {
                    if (whitespace) {
                        return null;
                    }
                    continue;
                }
                if (nameEnd < 0) {
                    nameEnd = p - 1;
                }
                return new String[] {text.substring(0, nameEnd), operator, skipSpace(text, p + 1)};
            }
            if (c == ':') {
                if (p + 1 < length && text.charAt(p) == ':' && text.charAt(p + 1) == '=') {
                    if (nameEnd < 0) {
                        nameEnd = p - 1;
                    }
                    return new String[] {text.substring(0, nameEnd), "::=",
                        skipSpace(text, p + 2)};
                }
                return null;
            }
            if (whitespace) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------- text

    /**
     * {@code collapse_continuations}: outside recipes a backslash-newline, the whitespace before
     * it and the whitespace starting the next line become one space; an even run of backslashes
     * before a newline keeps half of them and the newline.
     */
    static String collapseContinuations(String line) {
        if (line.indexOf('\n') < 0) {
            return line;
        }
        StringBuilder out = new StringBuilder();
        int in = 0;
        int length = line.length();
        while (true) {
            int q = line.indexOf('\n', in);
            if (q < 0) {
                out.append(line, in, length);
                return out.toString();
            }
            int backslashes = 0;
            for (int b = q - 1; b >= in && line.charAt(b) == '\\'; b--) {
                backslashes++;
            }
            out.append(line, in, q - backslashes);
            for (int k = 0; k < backslashes / 2; k++) {
                out.append('\\');
            }
            in = q + 1;
            if (backslashes % 2 == 1) {
                while (in < length && (line.charAt(in) == ' ' || line.charAt(in) == '\t')) {
                    in++;
                }
                while (out.length() > 0 && (out.charAt(out.length() - 1) == ' '
                        || out.charAt(out.length() - 1) == '\t')) {
                    out.setLength(out.length() - 1);
                }
                out.append(' ');
            } else {
                out.append('\n');
            }
        }
    }

    /** {@code remove_comments}: from the first {@code #} not escaped with a backslash. */
    static String removeComments(String line) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '#') {
                int backslashes = 0;
                for (int b = out.length() - 1; b >= 0 && out.charAt(b) == '\\'; b--) {
                    backslashes++;
                }
                if (backslashes % 2 == 0) {
                    out.setLength(out.length() - backslashes / 2);
                    return out.toString();
                }
                out.setLength(out.length() - (backslashes + 1) / 2);
                out.append('#');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** The first of these characters outside variable references, or -1. */
    private static int findUnquoted(String text, String stops) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '$' && i + 1 < text.length()) {
                char n = text.charAt(i + 1);
                if (n == '$') {
                    i++;
                    continue;
                }
                if (n == '(' || n == '{') {
                    depth++;
                    i++;
                    continue;
                }
            }
            if (depth > 0 && (c == ')' || c == '}')) {
                depth--;
                continue;
            }
            if (depth == 0 && stops.indexOf(c) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static int findColon(String text) {
        return findUnquoted(text, ":");
    }

    private static boolean endsWithOddBackslashes(CharSequence line) {
        int count = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            count++;
        }
        return count % 2 == 1;
    }

    private static boolean startsWithWord(String text, String word) {
        return text.startsWith(word)
                && (text.length() == word.length() || text.charAt(word.length()) == ' '
                        || text.charAt(word.length()) == '\t');
    }

    static String skipSpace(String text, int from) {
        return text.substring(skipSpaceIndex(text, from));
    }

    private static int skipSpaceIndex(String text, int from) {
        int p = from;
        while (p < text.length() && Character.isWhitespace(text.charAt(p))) {
            p++;
        }
        return p;
    }
}
