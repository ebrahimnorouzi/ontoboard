package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.robot.SparqlQuery;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The SPARQL checks ODK's {@code make test} runs, and adding one to them.
 *
 * <p>ODK runs {@code src/sparql/<name>-violation.sparql} for each name under
 * {@code robot_report: custom_sparql_checks} in {@code src/ontology/<id>-odk.yaml}, with
 * {@code robot verify}: every row a query returns is a violation. Nothing else in
 * {@code src/sparql} is run - a {@code .rq} file there, which OntoBoard's older layout ran, is a
 * file ODK never reads. So a test OntoBoard writes into an ODK project is two things, the file
 * and the name in that list, and this is the list: reading it, and adding a name to it.
 *
 * <p><b>What ODK runs when there is no list</b> - measured in the goldens, not assumed. ODK reads
 * {@code robot_report} as a plain dict ({@code OdkProjectModel} says so, and ports it): a
 * configuration without the block gets every one of {@code ReportConfig}'s defaults, whose list
 * is ODK's four and {@code dc-properties}; one with the block gets exactly the block, and the
 * Makefile template's own default for a missing list, ODK's four, applies. A list replaces either.
 * So the first name added writes out what ODK ran before it, and then the name; and a block added
 * where there was none also says {@code use_base_iris: TRUE}, the one other default the template
 * reads differently once a block exists ({@code --base-iri} on the report).
 *
 * <p>The file is located through {@link OdkYaml}'s reading and spliced as text, never
 * re-serialised: every other byte, the line endings and the indentation stay as they are. The
 * result is read back before it is returned, and a change that does not read back as the list
 * plus the name, with every other value as it was, is refused rather than written.
 *
 * <p>Text and files only; no Swing, no OWL API.
 */
public final class OdkChecks {

    /** ODK's default checks, in ODK's order: what the Makefile runs for a block with no list. */
    public static final List<String> ODKS_OWN = Collections.unmodifiableList(Arrays.asList(
            "owldef-self-reference", "iri-range", "label-with-iri", "multiple-replaced_by"));

    /**
     * What ODK runs for a configuration with no {@code robot_report} block at all:
     * {@code ReportConfig}'s own default, ODK's four and {@code dc-properties}. The goldens of
     * components, nodefaults and oboimports run these five.
     */
    public static final List<String> WITHOUT_A_REPORT_BLOCK = Collections.unmodifiableList(
            Arrays.asList("owldef-self-reference", "iri-range", "label-with-iri",
                    "multiple-replaced_by", "dc-properties"));

    /**
     * Every check ODK ships a query for ({@code _dynamic_sparql.jinja2}), in its order. ODK
     * writes these files itself; a project's own checks are the others.
     */
    public static final List<String> ODK_SHIPS = Collections.unmodifiableList(Arrays.asList(
            "owldef-self-reference", "redundant-subClassOf", "taxon-range", "iri-range",
            "iri-range-advanced", "label-with-iri", "multiple-replaced_by", "term-tracker-uri",
            "illegal-date", "dc-properties"));

    /** Where the list is in the configuration, as an {@link OdkYaml} path. */
    public static final String LIST = "robot_report.custom_sparql_checks";

    /** What follows a check's name in the name of its file. */
    public static final String SUFFIX = "-violation.sparql";

    private static final String REPORT = "robot_report";
    private static final String KEY = "custom_sparql_checks";
    private static final int LONGEST = 80;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    /**
     * What a name already in a list may be, to be read as a file: one word to make, and a file
     * name that stays in src/sparql. Wider than {@link #NAME}, which is what OntoBoard writes -
     * a project's own list may use capitals.
     */
    private static final Pattern READABLE = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");

    private OdkChecks() {
    }

    /**
     * The checks ODK runs for this configuration: its list, or ODK's default when it has none.
     *
     * @throws OdkYaml.UnreadableException when the text is not a configuration OdkYaml reads
     */
    public static List<String> declaredIn(String configText) {
        return declared(OdkYaml.entriesIn(configText));
    }

    /** Whether the configuration has a list of its own, rather than ODK's default. */
    public static boolean isListed(String configText) {
        return at(OdkYaml.entriesIn(configText), LIST) != null;
    }

    /**
     * The configuration with the check in its list; unchanged when it is there already.
     *
     * @throws IllegalArgumentException when {@link #whyNotAName} refuses the name, with that
     *     sentence
     * @throws OdkYaml.UnreadableException when the file cannot be changed safely, with the reason
     */
    public static String add(String configText, String checkName) {
        String why = whyNotAName(checkName);
        if (why != null) {
            throw new IllegalArgumentException(why);
        }
        // A file whose every line break is CRLF is edited as LF and turned back, so the new lines
        // break as the file's do. A file that mixes the two is edited as it is.
        boolean crlf = configText != null && isCrlf(configText);
        String text = crlf ? configText.replace("\r\n", "\n") : configText;
        List<OdkYaml.Entry> entries = OdkYaml.entriesIn(text);
        List<String> before = declared(entries);
        if (before.contains(checkName)) {
            return configText;
        }
        List<String> expected = new ArrayList<String>(before);
        expected.add(checkName);

        OdkYaml.Entry report = at(entries, REPORT);
        OdkYaml.Entry list = at(entries, LIST);
        String after;
        if (report == null) {
            after = withReportBlock(text, entries, expected);
        } else if (report.getEditable() != OdkYaml.Editable.MAPPING) {
            throw new OdkYaml.UnreadableException("robot_report in this file is not a block of "
                    + "settings, so there is no list of checks to add " + checkName + " to. Make "
                    + "it one in a text editor, with " + checkName + " under "
                    + "custom_sparql_checks.");
        } else if (list == null) {
            after = withList(text, entries, report, expected, checkName);
        } else if (list.getEditable() != OdkYaml.Editable.SEQUENCE) {
            throw new OdkYaml.UnreadableException("robot_report: custom_sparql_checks in this "
                    + "file has no list under it, and ODK cannot read it that way. Give it a list "
                    + "in a text editor, with " + checkName + " in it.");
        } else if (itemsOf(entries) == 0) {
            after = seeded(text, entries, list, checkName);
        } else if (!lineAt(text, list.getLine()).trim().startsWith("-")) {
            after = inBrackets(text, list, checkName);
        } else {
            after = OdkYaml.appendTo(text, LIST, checkName);
        }
        confirm(text, after, expected, report == null, checkName);
        return crlf ? after.replace("\n", "\r\n") : after;
    }

    /** Why a name cannot be a check's name, or null: it becomes a file name and a make word. */
    public static String whyNotAName(String checkName) {
        if (checkName == null || checkName.trim().isEmpty()) {
            return "A check needs a name: it becomes the file src/sparql/<name>-violation.sparql "
                    + "and a word in ODK's Makefile.";
        }
        if (checkName.length() > LONGEST) {
            return "A check's name is at most " + LONGEST + " characters long, and this one has "
                    + checkName.length() + ".";
        }
        if (!NAME.matcher(checkName).matches()) {
            return "\"" + checkName + "\" cannot be a check's name. A name is lower-case letters, "
                    + "digits, '_' and '-', and starts with a letter or a digit, because it "
                    + "becomes the file src/sparql/<name>-violation.sparql and a word in ODK's "
                    + "Makefile.";
        }
        return null;
    }

    /**
     * {@code src/sparql/<name>-violation.sparql}.
     *
     * @throws IllegalArgumentException for a name that as a file name would leave
     *     {@code src/sparql}, or that make would read as more than one word
     */
    public static File fileFor(File projectRoot, String checkName) {
        if (checkName == null || !READABLE.matcher(checkName).matches()) {
            throw new IllegalArgumentException("\"" + checkName + "\" cannot be a check's name: "
                    + "as a file name it would leave " + SparqlQuery.CHECKS_DIRECTORY
                    + ", or be more than one word to make.");
        }
        return new File(new File(projectRoot, SparqlQuery.CHECKS_DIRECTORY), checkName + SUFFIX);
    }

    /**
     * The declared checks whose file is there, in the list's order - what ODK's test runs.
     *
     * @throws OdkYaml.UnreadableException when the configuration cannot be read
     */
    public static List<File> filesIn(File projectRoot, String configText) {
        List<File> found = new ArrayList<File>();
        if (projectRoot == null) {
            return found;
        }
        for (String name : new LinkedHashSet<String>(declaredIn(configText))) {
            if (READABLE.matcher(name).matches()) {
                File file = fileFor(projectRoot, name);
                if (file.isFile()) {
                    found.add(file);
                }
            }
        }
        return found;
    }

    /**
     * The declared checks whose file is not there, in the list's order. ODK's test stops on one:
     * it hands ROBOT a query file that does not exist.
     */
    public static List<String> missingIn(File projectRoot, String configText) {
        List<String> missing = new ArrayList<String>();
        for (String name : new LinkedHashSet<String>(declaredIn(configText))) {
            if (!READABLE.matcher(name).matches() || projectRoot == null
                    || !fileFor(projectRoot, name).isFile()) {
                missing.add(name);
            }
        }
        return missing;
    }

    /**
     * The files in {@code src/sparql} that are named as ODK's checks are -
     * {@code *-violation.sparql} - and are not declared, so ODK's test does not run them; by
     * name. A {@code .rq} file is not one of them: it is a question of the project's, which
     * has a rule of its own ({@link OdkQuestions}).
     */
    public static List<File> undeclaredIn(File projectRoot, String configText) {
        List<File> undeclared = new ArrayList<File>();
        File[] children = projectRoot == null ? null
                : new File(projectRoot, SparqlQuery.CHECKS_DIRECTORY).listFiles();
        if (children == null) {
            return undeclared;
        }
        Set<String> declared = new HashSet<String>();
        for (File file : filesIn(projectRoot, configText)) {
            declared.add(file.getName());
        }
        for (File child : children) {
            String name = child.getName();
            if (child.isFile() && !declared.contains(name) && name.endsWith(SUFFIX)) {
                undeclared.add(child);
            }
        }
        Collections.sort(undeclared, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return undeclared;
    }

    // ------------------------------------------------------------------ reading

    private static List<String> declared(List<OdkYaml.Entry> entries) {
        OdkYaml.Entry report = at(entries, REPORT);
        if (report == null) {
            return WITHOUT_A_REPORT_BLOCK;
        }
        OdkYaml.Entry list = at(entries, LIST);
        if (report.getEditable() != OdkYaml.Editable.MAPPING || list == null) {
            return ODKS_OWN;
        }
        List<String> names = new ArrayList<String>();
        if (list.getEditable() != OdkYaml.Editable.SEQUENCE) {
            // A key with no list under it: ODK cannot render its Makefile from that at all.
            return Collections.unmodifiableList(names);
        }
        for (OdkYaml.Entry entry : entries) {
            if (isItem(entry.getPath()) && entry.getEditable() == OdkYaml.Editable.YES) {
                names.add(entry.getValue());
            }
        }
        return Collections.unmodifiableList(names);
    }

    /** {@code robot_report.custom_sparql_checks[3]}, and not anything inside an item. */
    private static boolean isItem(String path) {
        return path.startsWith(LIST + "[") && path.indexOf(']') == path.length() - 1;
    }

    private static int itemsOf(List<OdkYaml.Entry> entries) {
        int items = 0;
        for (OdkYaml.Entry entry : entries) {
            if (isItem(entry.getPath())) {
                items++;
            }
        }
        return items;
    }

    private static OdkYaml.Entry at(List<OdkYaml.Entry> entries, String path) {
        for (OdkYaml.Entry entry : entries) {
            if (path.equals(entry.getPath())) {
                return entry;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ writing

    /** No robot_report: the block, at the end of the file, with what ODK ran without it. */
    private static String withReportBlock(String text, List<OdkYaml.Entry> entries,
            List<String> expected) {
        String step = mappingStep(text, entries);
        StringBuilder block = new StringBuilder(REPORT).append(':');
        block.append('\n').append(step).append("use_base_iris: TRUE");
        block.append('\n').append(step).append(KEY).append(':');
        appendItems(block, step + listStep(text, entries), expected);
        // A file that ends without a line break keeps ending without one.
        return text.endsWith("\n") ? text + block + "\n" : text + "\n" + block;
    }

    /** robot_report without the key: the key and its list, after the block's last line. */
    private static String withList(String text, List<OdkYaml.Entry> entries,
            OdkYaml.Entry report, List<String> expected, String checkName) {
        // A block mapping starts at its first key, so the line the block's value starts on is
        // that key's, and its indent is the block's.
        String indent = indentOf(lineAt(text, report.getLine()));
        if (indent.isEmpty()) {
            throw new OdkYaml.UnreadableException("robot_report in this file is written on one "
                    + "line, and OntoBoard adds a list only to a block written over several. Add "
                    + "custom_sparql_checks to it in a text editor, with ODK's own checks and "
                    + checkName + ".");
        }
        int last = report.getLine();
        for (OdkYaml.Entry entry : entries) {
            if (entry.getPath().startsWith(REPORT + ".")) {
                last = Math.max(last, entry.getLine());
            }
        }
        // Lines deeper than the block's keys go on the value that starts above them.
        int lines = lineCount(text);
        while (last < lines) {
            String next = lineAt(text, last + 1);
            String trimmed = next.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")
                    || indentOf(next).length() <= indent.length()) {
                break;
            }
            last++;
        }
        StringBuilder added = new StringBuilder();
        added.append('\n').append(indent).append(KEY).append(':');
        appendItems(added, indent + listStep(text, entries), expected);
        int at = endOfLine(text, last);
        return text.substring(0, at) + added + text.substring(at);
    }

    /**
     * {@code custom_sparql_checks: []} becomes a list of one, written as a block, two spaces in
     * unless the file indents its lists otherwise - as {@link OdkYaml#appendBlockTo} seeds an
     * empty {@code products: []}.
     */
    private static String seeded(String text, List<OdkYaml.Entry> entries, OdkYaml.Entry list,
            String checkName) {
        int line = list.getLine();
        String current = lineAt(text, line);
        int open = current.indexOf('[');
        int close = open < 0 ? -1 : current.indexOf(']', open);
        if (!current.trim().startsWith(KEY) || close < 0
                || !current.substring(open + 1, close).trim().isEmpty()) {
            throw new OdkYaml.UnreadableException("robot_report: custom_sparql_checks in this "
                    + "file is an empty list written in a way OntoBoard does not change. Add "
                    + checkName + " to it in a text editor.");
        }
        String replaced = current.substring(0, open).replaceAll("[ \t]+$", "")
                + current.substring(close + 1)
                + "\n" + indentOf(current) + listStep(text, entries) + "- "
                + OdkYaml.scalarFor(checkName);
        return text.substring(0, startOfLine(text, line)) + replaced
                + text.substring(endOfLine(text, line));
    }

    /** {@code custom_sparql_checks: [a, b]}: the name goes inside the brackets. */
    private static String inBrackets(String text, OdkYaml.Entry list, String checkName) {
        int line = list.getLine();
        String current = lineAt(text, line);
        int open = current.indexOf('[');
        int close = open < 0 ? -1 : current.indexOf(']', open);
        if (close < 0) {
            throw new OdkYaml.UnreadableException("robot_report: custom_sparql_checks in this "
                    + "file is a list in brackets over more than one line, which OntoBoard does "
                    + "not change. Add " + checkName + " to it in a text editor.");
        }
        String replaced = current.substring(0, close).replaceAll("[ \t]+$", "") + ", "
                + OdkYaml.scalarFor(checkName) + current.substring(close);
        return text.substring(0, startOfLine(text, line)) + replaced
                + text.substring(endOfLine(text, line));
    }

    private static void appendItems(StringBuilder into, String indent, List<String> names) {
        for (String name : names) {
            into.append('\n').append(indent).append("- ").append(OdkYaml.scalarFor(name));
        }
    }

    /**
     * Reads the result back: the list must be what it was and the name, and every other value
     * what it was. Anything else is refused, and nothing is written.
     */
    private static void confirm(String before, String after, List<String> expected,
            boolean blockAdded, String checkName) {
        List<OdkYaml.Entry> now;
        try {
            now = OdkYaml.entriesIn(after);
        } catch (OdkYaml.UnreadableException broken) {
            now = null;
        }
        if (now == null || !declared(now).equals(expected)
                || !others(OdkYaml.entriesIn(before), blockAdded).equals(
                        others(now, blockAdded))) {
            throw new OdkYaml.UnreadableException("OntoBoard could not add " + checkName
                    + " to robot_report: custom_sparql_checks in this file without changing "
                    + "something else in it, so the file has not been changed. Add the name to "
                    + "that list in a text editor.");
        }
    }

    /** Every value but the list, robot_report's own summary and a new block's use_base_iris. */
    private static List<String> others(List<OdkYaml.Entry> entries, boolean blockAdded) {
        List<String> values = new ArrayList<String>();
        for (OdkYaml.Entry entry : entries) {
            String path = entry.getPath();
            if (path.equals(REPORT) || path.equals(LIST) || path.startsWith(LIST + "[")
                    || (blockAdded && path.equals(REPORT + ".use_base_iris"))) {
                continue;
            }
            values.add(path + "=" + entry.getValue());
        }
        return values;
    }

    /**
     * How far in the file puts a list's items from their key: copied from the first list written
     * as a block, two spaces when there is none.
     */
    private static String listStep(String text, List<OdkYaml.Entry> entries) {
        for (OdkYaml.Entry entry : entries) {
            if (!entry.getPath().endsWith("[0]")) {
                continue;
            }
            String item = lineAt(text, entry.getLine());
            String itemIndent = indentOf(item);
            if (!item.substring(itemIndent.length()).startsWith("-")) {
                continue;
            }
            for (int up = entry.getLine() - 1; up >= 1; up--) {
                String above = lineAt(text, up);
                String trimmed = above.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String keyIndent = indentOf(above);
                if (!trimmed.startsWith("-") && trimmed.endsWith(":")
                        && itemIndent.startsWith(keyIndent)) {
                    return itemIndent.substring(keyIndent.length());
                }
                break;
            }
        }
        return "  ";
    }

    /**
     * How far in the file puts a block's keys: copied from the first key one block in that has
     * its value on its own line, two spaces when there is none.
     */
    private static String mappingStep(String text, List<OdkYaml.Entry> entries) {
        for (OdkYaml.Entry entry : entries) {
            if (entry.getDepth() != 1 || entry.getPath().isEmpty()
                    || entry.getPath().indexOf('[') >= 0) {
                continue;
            }
            String line = lineAt(text, entry.getLine());
            String indent = indentOf(line);
            if (!indent.isEmpty()
                    && line.substring(indent.length()).startsWith(entry.getKey() + ":")) {
                return indent;
            }
        }
        return "  ";
    }

    private static boolean isCrlf(String text) {
        if (text.indexOf("\r\n") < 0) {
            return false;
        }
        String without = text.replace("\r\n", "");
        return without.indexOf('\n') < 0 && without.indexOf('\r') < 0;
    }

    /** Line {@code number}, 1-based, without its line break. */
    private static String lineAt(String text, int number) {
        int start = startOfLine(text, number);
        return text.substring(start, endOfLine(text, number));
    }

    private static int startOfLine(String text, int number) {
        int at = 0;
        for (int line = 1; line < number; line++) {
            int next = text.indexOf('\n', at);
            if (next < 0) {
                return text.length();
            }
            at = next + 1;
        }
        return at;
    }

    private static int endOfLine(String text, int number) {
        int start = startOfLine(text, number);
        int end = text.indexOf('\n', start);
        return end < 0 ? text.length() : end;
    }

    private static int lineCount(String text) {
        int lines = 1;
        for (int at = text.indexOf('\n'); at >= 0; at = text.indexOf('\n', at + 1)) {
            lines++;
        }
        return lines;
    }

    private static String indentOf(String line) {
        int at = 0;
        while (at < line.length() && (line.charAt(at) == ' ' || line.charAt(at) == '\t')) {
            at++;
        }
        return line.substring(0, at);
    }
}
