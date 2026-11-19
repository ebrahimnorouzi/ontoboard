package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;

/**
 * A project's own release script - {@code src/ontology/release.sh} - read and run by OntoBoard
 * itself: no sh, no Docker.
 *
 * <p>The user, 2026-11-19: "i want this limit to be solved: a project released by its own
 * release.sh (nfdicore, MWO) is still released in ODK's container". Those scripts set a few
 * variables, call ODK's make through {@code run.sh} with them, copy a file, and say
 * {@code set -e}. This reads that much of sh - assignments, quotes, {@code $NAME} and
 * {@code ${NAME}}, comments, {@code set}, a call of make (alone, or through {@code run.sh}), and
 * any other line as a recipe line ({@link RecipeShell}), with the commands OntoBoard has.
 *
 * <p>Everything is read first ({@link #read}), and a line that cannot be run here is named
 * before anything runs ({@link Plan#whyNot}). Then each line is said as it starts, what it says
 * is passed on, and the first line that fails ends the run - also where sh, without
 * {@code set -e}, would go on: a release does not go on past a step that failed. The script's
 * own text is not changed by reading it; its CR line ends, as Git checks them out on Windows,
 * end lines as in sh on Linux.
 */
final class ProjectScript {

    /** What a line is. */
    enum Kind {
        /** A blank line or a comment. */
        NOTHING,
        /** {@code NAME=value}: the value, expanded, is the variable's from here on. */
        VARIABLE,
        /** {@code set -e} and its kin: OntoBoard stops at the first failure anyway. */
        OPTION,
        /** A call of make: run by OntoBoard's engine, with its variables and targets. */
        MAKE,
        /** Any other command, run as a recipe line. */
        SHELL
    }

    /** One line of the script, as it will run. */
    static final class Step {
        private final int line;
        private final String text;
        private final Kind kind;
        private final List<String> make;
        private final String shell;
        private final String why;

        Step(int line, String text, Kind kind, List<String> make, String shell, String why) {
            this.line = line;
            this.text = text;
            this.kind = kind;
            this.make = make == null ? null : Collections.unmodifiableList(make);
            this.shell = shell;
            this.why = why;
        }

        /** The line's number in the script, from 1. */
        int line() {
            return line;
        }

        Kind kind() {
            return kind;
        }

        /** For a call of make: {@code make}, its variables and its targets, expanded. */
        List<String> make() {
            return make;
        }

        /** Why this line cannot be run here, or null. */
        String why() {
            return why;
        }

        /** The line as it runs: a call of make with its words expanded, else as written. */
        String shown() {
            if (kind == Kind.MAKE) {
                return ReleaseRun.shown(make);
            }
            return kind == Kind.SHELL ? shell : text.trim();
        }
    }

    /** The script, read. */
    static final class Plan {
        private final List<Step> steps;
        private final Map<String, String> variables;
        private final String whyNot;

        Plan(List<Step> steps, Map<String, String> variables, String whyNot) {
            this.steps = Collections.unmodifiableList(steps);
            this.variables = Collections.unmodifiableMap(variables);
            this.whyNot = whyNot;
        }

        /** Every line that does something, in order: the blanks and comments are left out. */
        List<Step> steps() {
            return steps;
        }

        /** The variables as the script leaves them. */
        Map<String, String> variables() {
            return variables;
        }

        /** Why the script cannot be run here, naming the first line that cannot; null if it can. */
        String whyNot() {
            return whyNot;
        }
    }

    /** What runs a call of make: OntoBoard's engine, or a stand-in in a test. */
    interface Make {
        ProcessRunner.Outcome run(List<String> make) throws IOException;
    }

    /** A line sh reads that is not read here; the message says what. */
    static final class NotRead extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotRead(String message) {
            super(message);
        }
    }

    private static final Pattern ASSIGNMENT =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=(.*)", Pattern.DOTALL);

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private ProjectScript() {
    }

    /**
     * Reads a script.
     *
     * @param tools the commands a line other than make may name: those of OntoBoard's engine
     */
    static Plan read(String text, Map<String, RecipeShell.Tool> tools) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Map<String, String> variables = new LinkedHashMap<String, String>();
        List<Step> steps = new ArrayList<Step>();
        String why = null;
        for (int at = 0; at < lines.length; at++) {
            Step step;
            try {
                step = readLine(at + 1, lines[at], variables, tools);
            } catch (NotRead | RecipeShell.NotRead notRead) {
                step = new Step(at + 1, lines[at], Kind.NOTHING, null, null,
                        notRead.getMessage());
            }
            if (step.why() != null && why == null) {
                why = "Line " + step.line() + " of the script cannot be run by OntoBoard "
                        + "itself - " + step.why() + ": " + lines[at].trim()
                        + ". Nothing was run.";
            }
            if (step.kind() != Kind.NOTHING || step.why() != null) {
                steps.add(step);
            }
        }
        return new Plan(steps, variables, why);
    }

    private static Step readLine(int number, String line, Map<String, String> variables,
            Map<String, RecipeShell.Tool> tools) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return new Step(number, line, Kind.NOTHING, null, null, null);
        }
        Matcher assignment = ASSIGNMENT.matcher(trimmed);
        if (assignment.matches() && !hasOperators(assignment.group(2))) {
            List<String> value = words(assignment.group(2), variables);
            if (value.size() > 1) {
                throw new NotRead("a variable set for the one command after it, which "
                        + "OntoBoard does not read");
            }
            variables.put(assignment.group(1), value.isEmpty() ? "" : value.get(0));
            return new Step(number, line, Kind.VARIABLE, null, null, null);
        }
        List<String> words = words(line, variables);
        if (words.isEmpty()) {
            return new Step(number, line, Kind.NOTHING, null, null, null);
        }
        String first = words.get(0);
        if (first.equals("set")) {
            for (String option : words.subList(1, words.size())) {
                if (!option.matches("[-+][euxo]+") && !option.equals("pipefail")) {
                    throw new NotRead("'set " + option + "' is not read here (set -e, -u, -x "
                            + "and -o pipefail are)");
                }
            }
            return new Step(number, line, Kind.OPTION, null, null, null);
        }
        int make = makeAt(words);
        boolean runSh = isRunSh(words);
        if (make < 0 && runSh) {
            throw new NotRead("run.sh runs a command in ODK's container; OntoBoard runs make's "
                    + "targets for it, and '" + String.join(" ", words.subList(
                            first.equals("sh") || first.equals("bash") ? 2 : 1, words.size()))
                    + "' is no call of make");
        }
        if (make >= 0) {
            if (hasOperators(line)) {
                throw new NotRead("a call of make joined with other commands; OntoBoard reads "
                        + "one on a line of its own");
            }
            List<String> command = new ArrayList<String>();
            command.add("make");
            command.addAll(words.subList(make, words.size()));
            return new Step(number, line, Kind.MAKE, command, null, null);
        }
        String shell = expandedForShell(line, variables);
        String why = RecipeShell.parse(shell).whyNot(tools);
        if (why != null) {
            throw new NotRead(why);
        }
        return new Step(number, line, Kind.SHELL, null, shell, null);
    }

    /** Where make's own words start - after {@code make}, {@code sh run.sh make}, ... - or -1. */
    private static int makeAt(List<String> words) {
        String first = words.get(0);
        if (first.equals("make")) {
            return 1;
        }
        if ((first.equals("sh") || first.equals("bash")) && words.size() > 2
                && isRunShName(words.get(1)) && words.get(2).equals("make")) {
            return 3;
        }
        if (isRunShName(first) && words.size() > 1 && words.get(1).equals("make")) {
            return 2;
        }
        return -1;
    }

    private static boolean isRunSh(List<String> words) {
        String first = words.get(0);
        return isRunShName(first) || (first.equals("sh") || first.equals("bash"))
                && words.size() > 1 && isRunShName(words.get(1));
    }

    private static boolean isRunShName(String word) {
        return word.equals("run.sh") || word.equals("./run.sh");
    }

    // ------------------------------------------------------------------ sh's words

    /** Whether the text has one of sh's operators outside quotes. */
    static boolean hasOperators(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"') {
                    i++;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '\\') {
                i++;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) {
                return false;
            } else if (";&|<>()".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** The words of a line, as sh splits and expands them; a comment ends the line. */
    static List<String> words(String line, Map<String, String> variables) {
        List<String> words = new ArrayList<String>();
        StringBuilder word = null;
        int at = 0;
        int end = line.length();
        while (at < end) {
            char c = line.charAt(at);
            if (word == null && c == '#') {
                break;
            }
            if (c == ' ' || c == '\t') {
                if (word != null) {
                    words.add(word.toString());
                    word = null;
                }
                at++;
                continue;
            }
            if (word == null) {
                word = new StringBuilder();
            }
            if (c == '\'') {
                int close = line.indexOf('\'', at + 1);
                if (close < 0) {
                    throw new NotRead("a quote (') that is not closed");
                }
                word.append(line, at + 1, close);
                at = close + 1;
            } else if (c == '"') {
                at = doubleQuoted(line, at + 1, variables, word);
            } else if (c == '\\') {
                if (at + 1 < end) {
                    word.append(line.charAt(at + 1));
                }
                at += 2;
            } else if (c == '$') {
                at = dollar(line, at, variables, word);
            } else if (c == '`') {
                throw new NotRead("a command in backquotes");
            } else {
                word.append(c);
                at++;
            }
        }
        if (word != null) {
            words.add(word.toString());
        }
        return words;
    }

    private static int doubleQuoted(String line, int at, Map<String, String> variables,
            StringBuilder word) {
        while (at < line.length()) {
            char c = line.charAt(at);
            if (c == '"') {
                return at + 1;
            }
            if (c == '\\' && at + 1 < line.length()
                    && "$`\"\\".indexOf(line.charAt(at + 1)) >= 0) {
                word.append(line.charAt(at + 1));
                at += 2;
            } else if (c == '$') {
                at = dollar(line, at, variables, word);
            } else if (c == '`') {
                throw new NotRead("a command in backquotes");
            } else {
                word.append(c);
                at++;
            }
        }
        throw new NotRead("a quote (\") that is not closed");
    }

    /** {@code $NAME} or {@code ${NAME}} at {@code at}: its value; where the text goes on. */
    private static int dollar(String line, int at, Map<String, String> variables,
            StringBuilder into) {
        int next = at + 1;
        if (next < line.length() && line.charAt(next) == '(') {
            throw new NotRead("a command in $(...)");
        }
        String name;
        int after;
        if (next < line.length() && line.charAt(next) == '{') {
            int close = line.indexOf('}', next);
            if (close < 0) {
                throw new NotRead("a ${ that is not closed");
            }
            name = line.substring(next + 1, close);
            if (!NAME.matcher(name).matches()) {
                throw new NotRead("${" + name + "}, which is more than a variable's name");
            }
            after = close + 1;
        } else {
            Matcher found = NAME.matcher(line);
            if (!found.find(next) || found.start() != next) {
                // $@, $1, a $ at the end: sh's own parameters, which a release script given no
                // arguments has empty. A lone $ is itself.
                if (next < line.length() && "@*#?0123456789".indexOf(line.charAt(next)) >= 0) {
                    return next + 1;
                }
                into.append('$');
                return next;
            }
            name = found.group();
            after = found.end();
        }
        into.append(valueOf(name, variables));
        return after;
    }

    private static String valueOf(String name, Map<String, String> variables) {
        if (variables.containsKey(name)) {
            return variables.get(name);
        }
        String environment = System.getenv(name);
        if (environment != null) {
            return environment;
        }
        throw new NotRead("$" + name + ", which neither the script nor the environment sets");
    }

    /**
     * The line with each variable replaced by its value, quoted so that the recipe shell reads
     * it as one word where sh would: the recipe shell reads no variables itself.
     */
    private static String expandedForShell(String line, Map<String, String> variables) {
        StringBuilder out = new StringBuilder();
        char quote = 0;
        for (int at = 0; at < line.length(); at++) {
            char c = line.charAt(at);
            if (quote == '\'') {
                out.append(c);
                if (c == '\'') {
                    quote = 0;
                }
            } else if (c == '\\' && at + 1 < line.length()) {
                out.append(c).append(line.charAt(++at));
            } else if (c == '\'' && quote == 0) {
                quote = '\'';
                out.append(c);
            } else if (c == '"') {
                quote = quote == '"' ? 0 : '"';
                out.append(c);
            } else if (c == '$') {
                StringBuilder value = new StringBuilder();
                int next = dollar(line, at, variables, value);
                if (quote == '"') {
                    out.append(value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace("$", "\\$").replace("`", "\\`"));
                } else {
                    out.append('\'').append(value.toString().replace("'", "'\\''")).append('\'');
                }
                at = next - 1;
            } else {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    // ------------------------------------------------------------------ the run

    /**
     * Runs the script's lines in order, each said as it starts, and stops at the first that
     * fails. Nothing runs when {@link Plan#whyNot} names a line.
     *
     * @param ontology the folder the script runs in: the project's {@code src/ontology}
     * @param stopped  asked before each line: whether the person has stopped the run; may be
     *                 null
     */
    static ProcessRunner.Outcome run(Plan plan, java.io.File ontology, Make make,
            Map<String, RecipeShell.Tool> tools, BooleanSupplier stopped) throws IOException {
        List<String> said = new ArrayList<String>();
        if (plan.whyNot() != null) {
            said.add(plan.whyNot());
            return new ProcessRunner.Outcome(1, said, false);
        }
        for (Step step : plan.steps()) {
            if (step.kind() == Kind.VARIABLE || step.kind() == Kind.OPTION) {
                said.add("line " + step.line() + ": " + step.shown());
                continue;
            }
            if (stopped != null && stopped.getAsBoolean()) {
                said.add("Stopped before line " + step.line() + ".");
                return new ProcessRunner.Outcome(130, said, false, true);
            }
            said.add("line " + step.line() + ": " + step.shown());
            int status;
            if (step.kind() == Kind.MAKE) {
                ProcessRunner.Outcome made = make.run(step.make());
                said.addAll(made.getOutput());
                if (made.wasCancelled()) {
                    said.add("Stopped in line " + step.line() + ".");
                    return new ProcessRunner.Outcome(130, said, false, true);
                }
                status = made.isSuccess() ? 0 : Math.max(1, made.getExitCode());
            } else {
                try {
                    status = RecipeShell.parse(step.shell).run(tools, ontology, said);
                } catch (RuntimeException broke) {
                    said.add(broke.getMessage() == null ? broke.toString() : broke.getMessage());
                    status = 1;
                }
            }
            if (status != 0) {
                said.add("line " + step.line() + " failed (status " + status + "): "
                        + step.shown() + ". The lines after it did not run.");
                return new ProcessRunner.Outcome(status, said, false);
            }
        }
        said.add("The script ran to its end.");
        return new ProcessRunner.Outcome(0, said, false);
    }
}
