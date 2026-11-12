package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.obolibrary.robot.CommandLineHelper;

/**
 * Which words of a ROBOT command line name files or folders, and those made absolute.
 *
 * <p>An ODK recipe says {@code robot --catalog catalog-v001.xml merge --input tmp/x.owl ...}:
 * paths relative to {@code src/ontology}, where make runs. Inside Protégé ROBOT would read such a
 * path against the folder Protégé was started from, and Java cannot change its working folder.
 * So before a line runs, every value that names a file or a folder is made absolute against the
 * folder make runs in - and for that, every option of every ROBOT command is placed here: it
 * names one file or folder, several, pairs of files, a value that is no file, or takes no value.
 * The number of values an option takes is the live {@link Option}'s, never a guess.
 *
 * <p>The line is cut into commands as ROBOT's own {@code CommandManager} cuts it. The manager
 * parses the options before the first command, and then each command's, with commons-cli's
 * {@code DefaultParser} told to stop at the first word that is not an option: a command's
 * arguments are the words that parser takes - its options and their values - and the word it
 * stops at is the next command's name. A value is taken while the option wants more and the
 * word is no option of that command (so in {@code query --format --csv}, {@code --csv}, no option
 * of query, is the format), which is what {@link #isArgument} copies from commons-cli 1.4.
 *
 * <p>What cannot be placed is refused, naming it ({@link Unknown}): a command or an option
 * nobody here knows, and the forms of an option ODK's Makefile never writes ({@code --input=x},
 * {@code -ix}, a long name cut short, {@code --}). A file left relative would be read in
 * Protégé's own folder, silently.
 */
final class RobotPaths {

    /** Where an option's values stand. */
    enum Kind {
        /** Takes no value. */
        NONE,
        /** Takes values that are no files: words, IRIs, numbers. */
        VALUE,
        /** Names one file or folder. */
        FILE,
        /** Names several: every value is a file ({@code verify --queries a b c}). */
        FILES,
        /**
         * Takes pairs, a query file and an output file, both files ({@code query --query},
         * {@code --select}, {@code --construct}). No ROBOT option has a pair with one file part.
         */
        FILE_PAIRS
    }

    /** Thrown for a command or an option this class cannot place; the message names it. */
    static final class Unknown extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        Unknown(String message) {
            super(message);
        }
    }

    private RobotPaths() {
    }

    // ------------------------------------------------------------------ the table

    /** The options every command has, and the options before the first command. */
    private static final Map<String, Kind> COMMON = table(
            "help", Kind.NONE, "version", Kind.NONE, "verbose", Kind.NONE,
            "very-verbose", Kind.NONE, "very-very-verbose", Kind.NONE,
            "catalog", Kind.FILE, "prefix", Kind.VALUE, "prefixes", Kind.FILE,
            "noprefixes", Kind.NONE, "add-prefix", Kind.VALUE, "add-prefixes", Kind.FILE,
            "xml-entities", Kind.NONE, "strict", Kind.NONE,
            // Every command that reads or writes an ontology has these three.
            "input", Kind.FILE, "input-iri", Kind.VALUE, "output", Kind.FILE);

    /** Each command's own options, by long name (by short name for one without a long name). */
    private static final Map<String, Map<String, Kind>> COMMANDS =
            new HashMap<String, Map<String, Kind>>();

    static {
        COMMANDS.put("annotate", table("version-iri", Kind.VALUE, "axiom-annotation", Kind.VALUE,
                "remove-annotations", Kind.NONE, "annotation-file", Kind.FILE,
                "ontology-iri", Kind.VALUE, "annotate-derived-from", Kind.VALUE,
                "annotate-defined-by", Kind.VALUE, "interpolate", Kind.VALUE,
                "annotation", Kind.VALUE, "link-annotation", Kind.VALUE,
                "language-annotation", Kind.VALUE, "typed-annotation", Kind.VALUE));
        // A "-terms" option of ROBOT's is a file of terms: CommandLineHelper.getTerms reads it.
        COMMANDS.put("collapse", table("threshold", Kind.VALUE, "precious", Kind.VALUE,
                "precious-terms", Kind.FILE));
        COMMANDS.put("convert", table("format", Kind.VALUE, "check", Kind.VALUE,
                "clean-obo", Kind.VALUE));
        COMMANDS.put("diff", table("left", Kind.FILE, "left-iri", Kind.VALUE,
                "left-catalog", Kind.FILE, "right", Kind.FILE, "right-iri", Kind.VALUE,
                "right-catalog", Kind.FILE, "labels", Kind.VALUE, "format", Kind.VALUE));
        COMMANDS.put("expand", table("create-new-ontology", Kind.VALUE,
                "annotate-expansion-axioms", Kind.VALUE, "expand-term", Kind.VALUE,
                "expand-term-file", Kind.FILE, "no-expand-term", Kind.VALUE,
                "no-expand-term-file", Kind.FILE));
        COMMANDS.put("explain", table("reasoner", Kind.VALUE, "axiom", Kind.VALUE,
                "max", Kind.VALUE, "unsatisfiable", Kind.VALUE, "mode", Kind.VALUE,
                "explanation", Kind.FILE, "format", Kind.VALUE));
        COMMANDS.put("export", table("export", Kind.FILE, "header", Kind.VALUE,
                "sort", Kind.VALUE, "include", Kind.VALUE, "format", Kind.VALUE,
                "split", Kind.VALUE, "entity-format", Kind.VALUE, "entity-select", Kind.VALUE,
                "standalone", Kind.VALUE));
        COMMANDS.put("export-prefixes", table());
        COMMANDS.put("extract", table("output-iri", Kind.VALUE, "method", Kind.VALUE,
                "term", Kind.VALUE, "term-file", Kind.FILE, "upper-term", Kind.VALUE,
                "upper-terms", Kind.FILE, "lower-term", Kind.VALUE, "lower-terms", Kind.FILE,
                "branch-from-term", Kind.VALUE, "branch-from-terms", Kind.FILE,
                "copy-ontology-annotations", Kind.VALUE, "force", Kind.VALUE,
                "annotate-with-source", Kind.VALUE, "sources", Kind.FILE,
                "individuals", Kind.VALUE, "imports", Kind.VALUE, "intermediates", Kind.VALUE));
        Map<String, Kind> selecting = table("ontology-iri", Kind.VALUE, "base-iri", Kind.VALUE,
                "term", Kind.VALUE, "term-file", Kind.FILE, "exclude-term", Kind.VALUE,
                "exclude-terms", Kind.FILE, "include-term", Kind.VALUE,
                "include-terms", Kind.FILE, "select", Kind.VALUE, "axioms", Kind.VALUE,
                "trim", Kind.VALUE, "signature", Kind.VALUE, "preserve-structure", Kind.VALUE,
                "drop-axiom-annotations", Kind.VALUE, "allow-punning", Kind.VALUE);
        COMMANDS.put("filter", selecting);
        COMMANDS.put("remove", selecting);
        COMMANDS.put("materialize", table("reasoner", Kind.VALUE,
                "remove-redundant-subclass-axioms", Kind.VALUE, "create-new-ontology", Kind.VALUE,
                "annotate-inferred-axioms", Kind.VALUE, "term", Kind.VALUE,
                "term-file", Kind.FILE));
        COMMANDS.put("measure", table("reasoner", Kind.VALUE, "format", Kind.VALUE,
                "metrics", Kind.VALUE));
        // --inputs is a wildcard pattern of files ('imports/*.owl'): ROBOT splits it into a
        // folder and a name pattern (CommandLineHelper.getFilesByPattern), so an absolute
        // folder in front of it keeps its meaning.
        COMMANDS.put("merge", table("inputs", Kind.FILE, "collapse-import-closure", Kind.VALUE,
                "include-annotations", Kind.VALUE, "annotate-derived-from", Kind.VALUE,
                "annotate-defined-by", Kind.VALUE));
        COMMANDS.put("unmerge", table("inputs", Kind.FILE));
        COMMANDS.put("mirror", table("directory", Kind.FILE));
        COMMANDS.put("query", table("format", Kind.VALUE, "output-dir", Kind.FILE,
                "use-graphs", Kind.VALUE, "update", Kind.FILE, "temporary-file", Kind.VALUE,
                "tdb", Kind.VALUE, "create-tdb", Kind.VALUE, "keep-tdb-mappings", Kind.VALUE,
                "tdb-directory", Kind.FILE, "select", Kind.FILE_PAIRS,
                "construct", Kind.FILE_PAIRS, "query", Kind.FILE_PAIRS, "queries", Kind.FILES));
        COMMANDS.put("reason", table("reasoner", Kind.VALUE, "dump-unsatisfiable", Kind.FILE,
                "remove-redundant-subclass-axioms", Kind.VALUE,
                "preserve-annotated-axioms", Kind.VALUE, "create-new-ontology", Kind.VALUE,
                "create-new-ontology-with-annotations", Kind.VALUE,
                "annotate-inferred-axioms", Kind.VALUE, "exclude-duplicate-axioms", Kind.VALUE,
                "exclude-external-entities", Kind.VALUE, "exclude-tautologies", Kind.VALUE,
                "exclude-owl-thing", Kind.VALUE, "equivalent-classes-allowed", Kind.VALUE,
                "axiom-generators", Kind.VALUE, "include-indirect", Kind.VALUE));
        COMMANDS.put("reduce", table("reasoner", Kind.VALUE,
                "preserve-annotated-axioms", Kind.VALUE, "named-classes-only", Kind.VALUE,
                "include-subproperties", Kind.VALUE));
        COMMANDS.put("relax", table("enforce-obo-format", Kind.VALUE,
                "exclude-named-classes", Kind.VALUE, "include-subclass-of", Kind.VALUE));
        COMMANDS.put("rename", table("mappings", Kind.FILE, "prefix-mappings", Kind.FILE,
                "allow-duplicates", Kind.VALUE, "allow-missing-entities", Kind.VALUE,
                "mapping", Kind.VALUE));
        COMMANDS.put("repair", table("output-iri", Kind.VALUE,
                "merge-axiom-annotations", Kind.VALUE, "invalid-references", Kind.VALUE,
                "annotation-property", Kind.VALUE, "annotation-properties-file", Kind.FILE));
        // report --profile is a file of rules and levels; validate-profile --profile is the name
        // of an OWL profile (DL, EL, ...). Two commands, two tables.
        COMMANDS.put("report", table("profile", Kind.FILE, "base-iri", Kind.VALUE,
                "format", Kind.VALUE, "fail-on", Kind.VALUE, "labels", Kind.VALUE,
                "print", Kind.VALUE, "tdb", Kind.VALUE, "keep-tdb-mappings", Kind.VALUE,
                "tdb-directory", Kind.FILE, "limit", Kind.VALUE, "standalone", Kind.VALUE));
        COMMANDS.put("validate-profile", table("profile", Kind.VALUE));
        COMMANDS.put("template", table("version-iri", Kind.VALUE, "ontology-iri", Kind.VALUE,
                "template", Kind.FILE, "ancestors", Kind.NONE, "merge-before", Kind.NONE,
                "merge-after", Kind.NONE, "collapse-import-closure", Kind.VALUE,
                "include-annotations", Kind.VALUE, "force", Kind.VALUE, "errors", Kind.FILE,
                "external-template", Kind.FILE));
        COMMANDS.put("verify", table("fail-on-violation", Kind.VALUE, "output-dir", Kind.FILE,
                "queries", Kind.FILES));
        // ODK's plugin. odk:subset --query is a DL query, not a file.
        COMMANDS.put("odk:import", table("add", Kind.VALUE, "remove", Kind.VALUE,
                "exclusive", Kind.VALUE));
        COMMANDS.put("odk:normalize", table("subset-decls", Kind.VALUE,
                "synonym-decls", Kind.VALUE, "base-iri", Kind.VALUE, "merge-axioms", Kind.VALUE,
                "add-source", Kind.VALUE, "all", Kind.NONE));
        COMMANDS.put("odk:subset", table("reasoner", Kind.VALUE,
                "collapse-imports-closure", Kind.VALUE, "query", Kind.VALUE,
                "ancestors", Kind.VALUE, "term", Kind.VALUE, "term-file", Kind.FILE,
                "subset", Kind.VALUE, "fill-gaps", Kind.VALUE, "no-dangling", Kind.VALUE,
                "follow-property", Kind.VALUE, "follow-in", Kind.VALUE,
                "not-follow-in", Kind.VALUE, "write-to", Kind.FILE, "ontology-iri", Kind.VALUE));
    }

    private static Map<String, Kind> table(Object... pairs) {
        Map<String, Kind> table = new HashMap<String, Kind>();
        for (int i = 0; i < pairs.length; i += 2) {
            table.put((String) pairs[i], (Kind) pairs[i + 1]);
        }
        return Collections.unmodifiableMap(table);
    }

    private static Options global;

    /** The options ROBOT reads before the first command: the ones every command shares. */
    static synchronized Options globalOptions() {
        if (global == null) {
            global = CommandLineHelper.getCommonOptions();
        }
        return global;
    }

    /**
     * Where an option of a command stands.
     *
     * @param command the command, or null for the options before the first command
     * @throws Unknown for a command or an option the table does not place
     */
    static Kind kind(String command, Option option) {
        String name = option.getLongOpt() != null ? option.getLongOpt() : option.getOpt();
        Map<String, Kind> own = command == null ? Collections.<String, Kind>emptyMap()
                : COMMANDS.get(command);
        if (own == null) {
            throw new Unknown("'" + command + "' is no ROBOT command OntoBoard knows, so which "
                    + "words of the line name files cannot be told; the line is not run.");
        }
        Kind kind = own.containsKey(name) ? own.get(name) : COMMON.get(name);
        if (kind == null) {
            throw new Unknown("'" + (command == null ? "robot" : command) + " "
                    + (option.getLongOpt() != null ? "--" : "-") + name + "' is an option of "
                    + "ROBOT's that OntoBoard has not placed: whether its value is a file is not "
                    + "known here, so the line is not run.");
        }
        return kind;
    }

    // ------------------------------------------------------------------ a line

    /**
     * The command line with every argument that names a file or a folder made absolute against
     * {@code folder}, and every other argument as it was.
     *
     * <p>A value is made absolute as {@code folder.getAbsolutePath() + File.separator + value},
     * not normalised, so that {@code ../sparql/x.sparql} stays visible at its end - what ROBOT
     * prints can then be given back with that prefix taken out. A value that is absolute
     * already, or an IRI ({@code http:}, {@code https:}, {@code file:}), stays as it is.
     *
     * @param arguments a whole ROBOT command line without the word {@code robot}
     * @throws Unknown for what cannot be placed; the message names it
     */
    static List<String> absolute(List<String> arguments, File folder) {
        String prefix = folder.getAbsolutePath() + File.separator;
        List<String> line = new ArrayList<String>(arguments);
        String command = null;
        Options options = globalOptions();
        int at = 0;
        while (at < arguments.size()) {
            String word = arguments.get(at);
            Option option = optionAt(word, command, options);
            at++;
            if (option == null) {
                // Where ROBOT's parser stops, the next command starts.
                options = OdkRobot.options(word);
                if (options == null) {
                    throw new Unknown("'" + word + "' is no ROBOT command OntoBoard knows, so "
                            + "which words of the line name files cannot be told; the line is "
                            + "not run.");
                }
                command = word;
                continue;
            }
            Kind kind = kind(command, option);
            boolean files = kind == Kind.FILE || kind == Kind.FILES || kind == Kind.FILE_PAIRS;
            int taken = 0;
            while (at < arguments.size() && wantsMore(option, taken)
                    && isArgument(arguments.get(at), options)) {
                if (files) {
                    line.set(at, absolute(arguments.get(at), prefix));
                }
                taken++;
                at++;
            }
        }
        return line;
    }

    private static String absolute(String value, String prefix) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.isEmpty() || lower.startsWith("http:") || lower.startsWith("https:")
                || lower.startsWith("file:") || isAbsolute(value)) {
            return value;
        }
        return prefix + value;
    }

    /** A path that starts at a root here, or on the Unix of ODK's image, or at a drive. */
    static boolean isAbsolute(String path) {
        return new File(path).isAbsolute() || path.startsWith("/") || path.startsWith("\\")
                || path.matches("[A-Za-z]:[\\\\/].*");
    }

    /**
     * The option a word is, or null for a word that is no option: the next command's name.
     *
     * @throws Unknown for an option no one here knows, or one written in a form not read here
     */
    private static Option optionAt(String word, String command, Options options) {
        String of = command == null ? "the options before ROBOT's first command"
                : "ROBOT's " + command;
        if (word.equals("--")) {
            throw new Unknown("'--' in a ROBOT line is not read by OntoBoard's engine; the line "
                    + "is not run.");
        }
        if (!word.startsWith("-") || word.equals("-")) {
            return null;
        }
        if (word.indexOf('=') > 0) {
            throw new Unknown("'" + word + "': an option with its value after '=' is not read "
                    + "by OntoBoard's engine; ODK's Makefile writes the value as the next word.");
        }
        String name = word.startsWith("--") ? word.substring(2) : word.substring(1);
        if (word.startsWith("--")) {
            if (options.hasLongOption(name)) {
                return options.getOption(name);
            }
        } else if (options.hasShortOption(name)) {
            return options.getOption(name);
        } else if (name.length() > 1 && options.hasLongOption(name)) {
            return options.getOption(name);
        }
        if (name.length() > 1 && !options.getMatchingOptions(name).isEmpty()) {
            throw new Unknown("'" + word + "' is the beginning of an option of " + of + ": "
                    + "OntoBoard's engine reads options by their whole names, as ODK's Makefile "
                    + "writes them.");
        }
        if (!word.startsWith("--") && name.length() > 1 && isOption(word, options)) {
            throw new Unknown("'" + word + "' is an option with its value, or several options, "
                    + "in one word: OntoBoard's engine does not read that; ODK's Makefile writes "
                    + "each option and each value as a word of its own.");
        }
        throw new Unknown("'" + (command == null ? "robot" : command) + " " + word + "': no "
                + "option of that name is known for " + of + ", so whether it names a file "
                + "cannot be told; the line is not run.");
    }

    /** Whether the option takes another value after {@code taken}: commons-cli's acceptsArg. */
    private static boolean wantsMore(Option option, int taken) {
        if (!option.hasArg()) {
            return false;
        }
        return option.getArgs() == Option.UNLIMITED_VALUES || taken < option.getArgs();
    }

    // ------------------------------------------------------------------ commons-cli 1.4

    /** DefaultParser.isArgument: a word that is no option, or a negative number. */
    static boolean isArgument(String word, Options options) {
        return !isOption(word, options) || isNegativeNumber(word);
    }

    private static boolean isNegativeNumber(String word) {
        try {
            Double.parseDouble(word);
            return true;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    private static boolean isOption(String word, Options options) {
        return isLongOption(word, options) || isShortOption(word, options);
    }

    private static boolean isShortOption(String word, Options options) {
        if (!word.startsWith("-") || word.length() == 1) {
            return false;
        }
        int equals = word.indexOf('=');
        String name = equals == -1 ? word.substring(1) : word.substring(1, equals);
        if (options.hasShortOption(name)) {
            return true;
        }
        return name.length() > 0 && options.hasShortOption(String.valueOf(name.charAt(0)));
    }

    private static boolean isLongOption(String word, Options options) {
        if (!word.startsWith("-") || word.length() == 1) {
            return false;
        }
        int equals = word.indexOf('=');
        String name = equals == -1 ? word : word.substring(0, equals);
        if (!options.getMatchingOptions(name).isEmpty()) {
            return true;
        }
        return longPrefix(word, options) != null && !word.startsWith("--");
    }

    private static String longPrefix(String word, Options options) {
        String name = word.startsWith("--") ? word.substring(2)
                : word.startsWith("-") ? word.substring(1) : word;
        for (int end = name.length() - 2; end > 1; end--) {
            if (options.hasLongOption(name.substring(0, end))) {
                return name.substring(0, end);
            }
        }
        return null;
    }
}
