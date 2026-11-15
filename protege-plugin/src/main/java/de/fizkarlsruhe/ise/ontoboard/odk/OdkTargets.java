package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.reason.ProfileCheck;
import de.fizkarlsruhe.ise.ontoboard.robot.QuestionTests;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform;
import de.fizkarlsruhe.ise.ontoboard.robot.SparqlQuery;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * What a run of ODK's targets says to the person who started it, beyond make's own words.
 *
 * <p>ODK's Makefile stops at the first recipe line that fails, and make's last line is then
 * all there is: an IRI, or "Error 1". The build OntoBoard ran itself for its own projects, up to
 * 1.135, said more - which rule a check stands for, which terms break it and by what name the
 * person knows them, what to do about it on the board, that a test is about a class nobody has
 * any more, what makes a class unable to have members, what a fuller reasoner sees that ELK
 * does not. Each of those sentences was written after somebody was stuck without it. They are
 * kept here and said of ODK's own run: read off what make and ROBOT printed and what ROBOT
 * left under {@code reports/}, whoever ran the target - OntoBoard itself, ODK's container, or
 * ODK's tools on the machine.
 *
 * <p>Nothing here runs a target or changes a file.
 */
public final class OdkTargets {

    /** How many rows of one check are named. A check that lists hundreds is one mistake. */
    static final int ROWS_SHOWN = 10;

    /** How many of the report's findings a failure names before it points to the report. */
    static final int FINDINGS_NAMED = 3;

    /** How long HermiT is given for a second opinion before the build goes on without it. */
    static final long SECOND_OPINION_MILLIS = 10_000;

    /** Beyond this many logical axioms, HermiT is not asked in passing. */
    static final int SECOND_OPINION_AXIOMS = 20_000;

    private static final Pattern FAIL =
            Pattern.compile("FAIL Rule (\\S+): (\\d+) violation\\(s\\).*");
    private static final Pattern PASS = Pattern.compile("PASS Rule (\\S+): .*");
    /** make's own last word on a target: {@code make[1]: *** [Makefile:269: sparql_test] Error 1}. */
    private static final Pattern STOP = Pattern.compile(
            "make(?:\\[\\d+\\])?: \\*\\*\\* \\[(?:[^\\]]*?:\\d+: )?([^\\]]+)\\] Error \\d+.*");
    /** A recipe line that reasons; make says one that goes over several lines as one. */
    private static final Pattern REASONER = Pattern.compile(
            ".*\\breason\\b.*?(?:--reasoner|-r)\\s+(\\S+).*", Pattern.DOTALL);

    private OdkTargets() {
    }

    // ------------------------------------------------------------------ what make said

    /** A check or a question of the project that a run found rows for. */
    public static final class Failed {
        private final String path;
        private final File file;
        private final int violations;
        private final List<String> columns;
        private final List<List<String>> rows;

        Failed(String path, File file, int violations, List<String> columns,
                List<List<String>> rows) {
            this.path = path;
            this.file = file;
            this.violations = violations;
            this.columns = Collections.unmodifiableList(columns);
            this.rows = Collections.unmodifiableList(rows);
        }

        /** The file's name: {@code check_labels-violation.sparql}, {@code my_question.rq}. */
        public String name() {
            return file.getName();
        }

        /** The query, as make named it, under the project's {@code src/ontology}. */
        public File file() {
            return file;
        }

        /** How many rows the query returned: each one breaks the rule. */
        public int violations() {
            return violations;
        }

        /** The names of the query's columns. */
        public List<String> columns() {
            return columns;
        }

        /** The rows, as far as the run said them. */
        public List<List<String>> rows() {
            return rows;
        }

        @Override
        public String toString() {
            return path + ": " + violations;
        }
    }

    /**
     * The checks and questions a run found violations of, in the order make came to them.
     *
     * <p>ROBOT's verify says {@code FAIL Rule ../sparql/<file>: N violation(s)}, then the rows as
     * CSV under a line of column names, and leaves the same rows in
     * {@code reports/<file without its ending>.csv}. The rows are taken from what was said; where
     * nothing follows the line, from that file.
     *
     * @param ontologyDirectory the project's {@code src/ontology}, where make ran
     */
    public static List<Failed> failed(List<String> transcript, File ontologyDirectory) {
        List<Failed> found = new ArrayList<Failed>();
        for (int i = 0; i < transcript.size(); i++) {
            Matcher fail = FAIL.matcher(transcript.get(i).trim());
            if (!fail.matches()) {
                continue;
            }
            String path = fail.group(1);
            int violations = Integer.parseInt(fail.group(2));
            File file = new File(ontologyDirectory, path.replace('/', File.separatorChar));
            List<List<String>> said = new ArrayList<List<String>>();
            for (int next = i + 1; next < transcript.size()
                    && said.size() <= violations; next++) {
                String line = transcript.get(next);
                if (isMakesOrRobots(line)) {
                    break;
                }
                said.add(cells(line));
            }
            if (said.isEmpty()) {
                said = fromTheReport(file, ontologyDirectory);
            }
            List<String> columns = said.isEmpty() ? new ArrayList<String>() : said.get(0);
            List<List<String>> rows = said.isEmpty() ? new ArrayList<List<String>>()
                    : new ArrayList<List<String>>(said.subList(1, said.size()));
            found.add(new Failed(path, file, violations, columns, rows));
        }
        return found;
    }

    /** Whether a line is make's or ROBOT's own, and so no row of a check. */
    private static boolean isMakesOrRobots(String line) {
        String trimmed = line.trim();
        return trimmed.isEmpty() || trimmed.startsWith("PASS Rule ")
                || trimmed.startsWith("FAIL Rule ") || trimmed.startsWith("make")
                || trimmed.startsWith("robot ") || trimmed.startsWith("ROBOT")
                || trimmed.startsWith("ERROR") || trimmed.startsWith("Makefile:");
    }

    /** The rows ROBOT left for a check under {@code reports/}; empty when there is no file. */
    private static List<List<String>> fromTheReport(File check, File ontologyDirectory) {
        String name = check.getName();
        int dot = name.lastIndexOf('.');
        File report = new File(new File(ontologyDirectory, "reports"),
                (dot < 0 ? name : name.substring(0, dot)) + ".csv");
        List<List<String>> rows = new ArrayList<List<String>>();
        if (!report.isFile()) {
            return rows;
        }
        try {
            for (String line : Files.readAllLines(report.toPath(), StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) {
                    rows.add(cells(line));
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            rows.clear();
        }
        return rows;
    }

    /** The cells of one line of CSV: commas, and double quotes around a cell that has one. */
    static List<String> cells(String line) {
        List<String> cells = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    /** The checks and questions that passed, by file name, in the order make came to them. */
    public static List<String> passed(List<String> transcript) {
        List<String> passed = new ArrayList<String>();
        for (String line : transcript) {
            Matcher pass = PASS.matcher(line.trim());
            if (pass.matches()) {
                String path = pass.group(1);
                passed.add(path.substring(Math.max(path.lastIndexOf('/'),
                        path.lastIndexOf('\\')) + 1));
            }
        }
        return passed;
    }

    /**
     * Of the checks and questions a run names, the project's own: its questions, and each
     * check ODK does not ship a query for - the label check OntoBoard adds among them. ODK's
     * own pass in every project, and say nothing about what the person did.
     */
    public static List<String> ownAmong(List<String> names) {
        List<String> own = new ArrayList<String>();
        for (String name : names) {
            if (odksNameOf(name) == null) {
                own.add(name);
            }
        }
        return own;
    }

    /** The name of the check a file holds when it is one ODK ships a query for; else null. */
    private static String odksNameOf(String fileName) {
        if (fileName == null || !fileName.endsWith(OdkChecks.SUFFIX)) {
            return null;
        }
        String name = fileName.substring(0, fileName.length() - OdkChecks.SUFFIX.length());
        return OdkChecks.ODK_SHIPS.contains(name) ? name : null;
    }

    /**
     * The target make stopped at - the first such line, which is the deepest make's - or null
     * when make did not say.
     */
    public static String stoppedAt(List<String> transcript) {
        for (String line : transcript) {
            Matcher stop = STOP.matcher(line.trim());
            if (stop.matches()) {
                return stop.group(1);
            }
        }
        return null;
    }

    /** Whether the run reasoned with ELK, the reasoner ODK's Makefile names unless told otherwise. */
    public static boolean reasonedWithElk(List<String> transcript) {
        for (String line : transcript) {
            Matcher reasoner = REASONER.matcher(line);
            if (reasoner.matches() && reasoner.group(1).toLowerCase(Locale.ROOT).contains("elk")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ why it stopped

    /**
     * Why a run stopped, as a person can act on it: the class the reasoner found unable to have
     * members and what makes it so; each check or question that failed, with its rule, the terms
     * that break it and what to do; the report's findings that count as failures.
     *
     * @param editFile the project's edit file
     * @param ontology the ontology as Protege has it open, for the names of things; never
     *                 modified; may be null, and then terms are named as the run named them
     * @return the sentences; null when the transcript shows none of these
     */
    public static String whyItStopped(List<String> transcript, File editFile,
            OWLOntology ontology) {
        List<String> said = new ArrayList<String>();
        String found = whatTheReasonerFound(transcript, ontology);
        if (found != null) {
            said.add(found);
        }
        File ontologyDirectory = editFile == null ? null
                : editFile.getAbsoluteFile().getParentFile();
        for (Failed check : failed(transcript, ontologyDirectory)) {
            said.add(saidOf(check, ontology));
        }
        String missing = whatIsNotThere(transcript, ontologyDirectory);
        if (missing != null) {
            said.add(missing);
        }
        String report = whatTheReportFound(transcript);
        if (report != null) {
            said.add(report);
        }
        String profile = whatTheProfileCheckFound(transcript, ontology);
        if (profile != null) {
            said.add(profile);
        }
        return said.isEmpty() ? null : String.join(" ", said);
    }

    /**
     * Why a target's run failed, in the sentence Build and Release show above its log: what
     * the transcript says ({@link #whyItStopped}) and where make stopped; where it says
     * none of that, the sentence of whoever ran the target.
     *
     * @param runners  what the runner says of the failure: make's last word, as a rule
     * @param ontology as for {@link #whyItStopped}; may be null
     */
    public static String whyARunFailed(String target, List<String> transcript, String runners,
            File editFile, OWLOntology ontology) {
        String why = whyItStopped(transcript, editFile, ontology);
        String at = stoppedAt(transcript);
        return why == null ? runners : why + " '" + target + "' stopped"
                + (at == null ? "" : " at " + at) + "; the log has every line up to there.";
    }

    /** One failed check: its name and rule, the terms it lists, and what to do about them. */
    static String saidOf(Failed check, OWLOntology ontology) {
        // What the check's own file says first; one of ODK's says nothing, so it is said
        // for it.
        String rule = ruleOf(check.file());
        if (rule == null) {
            rule = OdkChecks.ruleOf(odksNameOf(check.name()));
        }
        int labelColumn = check.columns().indexOf("label");
        List<String> offenders = new ArrayList<String>();
        for (List<String> row : check.rows()) {
            if (offenders.size() >= ROWS_SHOWN) {
                break;
            }
            String label = labelColumn >= 0 && labelColumn < row.size()
                    ? row.get(labelColumn) : null;
            offenders.add(label != null && !label.trim().isEmpty() ? label
                    : nameOf(row, ontology));
        }
        String gone = ontology == null ? null : goneFrom(ontology, check.rows());
        boolean more = check.violations() > offenders.size();
        String terms = offenders.isEmpty() ? check.violations() + (check.violations() == 1
                ? " term" : " terms") : String.join(", ", offenders) + (more ? " and "
                        + (check.violations() - offenders.size()) + " more" : "");
        if (gone != null) {
            return check.name() + (rule == null ? "" : " (" + rule + ")") + ": " + terms + " - "
                    + gone + " is not in the ontology any more. If its class was deleted, the "
                    + "test is about nothing"
                    + (check.name().endsWith(OdkQuestions.SUFFIX) ? ": delete "
                            + SparqlQuery.CHECKS_DIRECTORY + "/" + check.name() + "." : ".");
        }
        return check.name() + (rule == null ? "" : " (" + rule + ")") + ": " + terms + ". "
                + adviceFor(check.file(), ontology);
    }

    /** A row's term by the name the person knows it by, else the row as the run said it. */
    private static String nameOf(List<String> row, OWLOntology ontology) {
        String first = row.isEmpty() ? "" : row.get(0);
        if (ontology != null && (first.startsWith("http://") || first.startsWith("https://"))) {
            for (OWLEntity entity : ontology.getEntitiesInSignature(IRI.create(first),
                    Imports.INCLUDED)) {
                String label = DisplayLabels.forEntity(ontology, entity);
                if (label != null && !label.trim().isEmpty()) {
                    return label;
                }
            }
        }
        return String.join(", ", row);
    }

    /**
     * What the reasoner found in the ontology, where the run stopped for that: the classes that
     * can have no members, by name, and what makes the first of them so - asked of the ontology
     * as it is open, with the reasoner the run named. Null when the run did not stop for it, or
     * the open ontology does not show it.
     */
    static String whatTheReasonerFound(List<String> transcript, OWLOntology ontology) {
        boolean said = false;
        Reasoners.Choice reasoner = Reasoners.Choice.ELK;
        for (String line : transcript) {
            String lower = line.toLowerCase(Locale.ROOT);
            said |= lower.contains("unsatisfiable") || lower.contains("inconsistent");
            Matcher named = REASONER.matcher(line);
            if (named.matches() && named.group(1).toLowerCase(Locale.ROOT).contains("hermit")) {
                reasoner = Reasoners.Choice.HERMIT;
            }
        }
        if (!said || ontology == null) {
            return null;
        }
        try {
            RobotTransform.preview(ontology, RobotTransform.Kind.REASON, reasoner.newFactory());
            return null;
        } catch (RuntimeException found) {
            return RobotTransform.isAboutTheModel(found) ? found.getMessage() : null;
        }
    }

    private static final Pattern VERIFY_LINE = Pattern.compile(
            "(?:\\S+=\\S*\\s+)*robot\\s(?:.*\\s)?verify\\s.*", Pattern.DOTALL);

    /**
     * A check the run was to ask whose file is not there: the configuration lists a name
     * and nothing is under it in {@code src/sparql} - deleted to be rid of it, or listed by
     * hand and never written. ROBOT's whole word on it is the file's path, among the lines
     * of the checks that passed. Read off the last line of ROBOT's the run came to, where
     * that asks checks: the files it names, looked for where make ran. Null when they are
     * all there.
     */
    static String whatIsNotThere(List<String> transcript, File ontologyDirectory) {
        int at = -1;
        for (int i = 0; i < transcript.size(); i++) {
            if (ROBOT_LINE.matcher(transcript.get(i).trim()).matches()) {
                at = i;
            }
        }
        if (ontologyDirectory == null || at < 0
                || !VERIFY_LINE.matcher(transcript.get(at).trim()).matches()) {
            return null;
        }
        // What ROBOT said of a check, it has read: only one it said nothing of can be
        // the one it did not find.
        List<String> spokenOf = new ArrayList<String>();
        for (String line : transcript.subList(at + 1, transcript.size())) {
            Matcher pass = PASS.matcher(line.trim());
            Matcher fail = FAIL.matcher(line.trim());
            if (pass.matches()) {
                spokenOf.add(pass.group(1));
            } else if (fail.matches()) {
                spokenOf.add(fail.group(1));
            }
        }
        List<String> missing = new ArrayList<String>();
        boolean queries = false;
        for (String word : transcript.get(at).trim().split("\\s+")) {
            if (word.equals("--queries") || word.equals("-q")) {
                queries = true;
            } else if (word.startsWith("-")) {
                queries = false;
            } else if (queries && !word.equals("\\") && !spokenOf.contains(word)
                    && !new File(ontologyDirectory,
                            word.replace('/', File.separatorChar)).isFile()) {
                missing.add(word);
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        String first = missing.get(0);
        String name = first.substring(first.lastIndexOf('/') + 1);
        String shown = first.startsWith("../sparql/")
                ? SparqlQuery.CHECKS_DIRECTORY + "/" + name : first;
        String asked = "The run asks the check " + shown + ", and that file is not there"
                + (missing.size() == 1 ? "" : " (nor " + (missing.size() == 2 ? "is 1"
                        : "are " + (missing.size() - 1)) + " more of the checks it asks)")
                + ".";
        if (odksNameOf(name) != null) {
            return asked + " It is one of ODK's own checks: "
                    + "OntoBoard > Project > Update project files... writes its file again.";
        }
        if (!name.endsWith(OdkChecks.SUFFIX)) {
            return asked;
        }
        return asked + " It is listed as "
                + name.substring(0, name.length() - OdkChecks.SUFFIX.length())
                + " under custom_sparql_checks in the project's <id>-odk.yaml: put the file "
                + "back, or take the name out of the list and update the project's files "
                + "(OntoBoard > Project > Update project files...).";
    }

    /** A recipe line that calls ROBOT, as make says it - variables set before it or not. */
    private static final Pattern ROBOT_LINE = Pattern.compile(
            "(?:\\S+=\\S*\\s+)*robot\\s.*", Pattern.DOTALL);
    private static final Pattern REPORT_LINE = Pattern.compile(
            "(?:\\S+=\\S*\\s+)*robot\\s(?:.*\\s)?report\\s.*", Pattern.DOTALL);
    private static final Pattern REPORT_OUTPUT = Pattern.compile(
            ".*\\s(?:-o|--output)\\s+(\\S+).*", Pattern.DOTALL);
    private static final Pattern REPORT_FAIL_ON = Pattern.compile(
            ".*\\s--fail-on\\s+(\\S+).*", Pattern.DOTALL);
    private static final Pattern REPORT_COUNT = Pattern.compile("(ERROR|WARN|INFO):\\s+(\\d+)");

    /** The levels of ROBOT's report, the gravest first. */
    private static final List<String> LEVELS = Collections.unmodifiableList(
            Arrays.asList("ERROR", "WARN", "INFO"));

    /**
     * The findings of ROBOT's report that stopped a run, named: the report prints how many
     * there are of each level and the first few, a line each of level, rule, subject,
     * property and value.
     *
     * <p>Null when the report was not what stopped the run. A report stops a run where its
     * findings reach the level its command names ({@code --fail-on}; ERROR when it names
     * none, and never with {@code none} - which is what ODK gives a project that says
     * nothing about its report). Such a report prints its errors all the same, and the run
     * goes on; said as the reason for what stopped it later, they would send somebody to
     * mend the wrong thing. So it is the last report of the run that is read, by the level
     * it was told, and only when no line of ROBOT's ran after it.
     */
    static String whatTheReportFound(List<String> transcript) {
        int at = -1;
        for (int i = 0; i < transcript.size(); i++) {
            if (REPORT_LINE.matcher(transcript.get(i).trim()).matches()) {
                at = i;
            }
        }
        if (at < 0) {
            return null;
        }
        String command = transcript.get(at).trim();
        Matcher output = REPORT_OUTPUT.matcher(command);
        String file = output.matches() ? output.group(1) : null;
        Matcher told = REPORT_FAIL_ON.matcher(command);
        String failOn = told.matches() ? told.group(1) : LEVELS.get(0);
        int stopsAt = LEVELS.indexOf(failOn.toUpperCase(Locale.ROOT));
        if (stopsAt < 0) {
            return null;
        }
        int[] counted = new int[LEVELS.size()];
        List<String> named = new ArrayList<String>();
        for (String line : transcript.subList(at + 1, transcript.size())) {
            if (ROBOT_LINE.matcher(line.trim()).matches()) {
                // The run went on to its next line: this report did not stop it.
                return null;
            }
            Matcher count = REPORT_COUNT.matcher(line.trim());
            if (count.matches()) {
                counted[LEVELS.indexOf(count.group(1))] = Integer.parseInt(count.group(2));
            }
            String[] cells = line.split("\t");
            int level = cells.length >= 3 ? LEVELS.indexOf(cells[0].trim()) : -1;
            if (level >= 0 && level <= stopsAt && named.size() < FINDINGS_NAMED) {
                named.add(cells[1].trim() + " " + cells[2].trim());
            }
        }
        int found = 0;
        for (int level = 0; level <= stopsAt; level++) {
            found += counted[level];
        }
        if (found == 0) {
            return null;
        }
        String what = stopsAt == 0 ? found + (found == 1 ? " error" : " errors")
                : found + (found == 1 ? " finding" : " findings") + " of the level "
                        + LEVELS.get(stopsAt) + " or above, where this project's report "
                        + "stops (--fail-on " + failOn + ")";
        return "The quality report found " + what
                + (named.isEmpty() ? "" : ": " + String.join("; ", named)
                        + (found > named.size() ? " and " + (found - named.size())
                                + " more" : ""))
                + ". " + (file == null ? "" : "All of them are in " + file + "; ")
                + "OntoBoard > Check and test > Quality report... lists them one by one.";
    }

    private static final Pattern PROFILE_REPORT = Pattern.compile(
            "OWL 2 (\\S+) Profile Report: .*NOT in profile.*");
    private static final Pattern PROFILE_OUTPUT = Pattern.compile(
            ".*\\bvalidate-profile\\b.*?\\s(?:-o|--output)\\s+(\\S+).*", Pattern.DOTALL);
    private static final Pattern IRI_IN_BRACKETS = Pattern.compile("<(https?://[^>\\s]+)>");

    /**
     * What takes the ontology out of the OWL 2 profile ODK's test asks of it, where the run
     * stopped for that: ROBOT says "NOT in profile" and then a line for each violation - the
     * kind, the expression, and in brackets the axiom it is in. How many there are, and the
     * first without its axiom, its terms by the names the person knows them by. Null when the
     * run printed no such report.
     */
    static String whatTheProfileCheckFound(List<String> transcript, OWLOntology ontology) {
        int at = -1;
        for (int i = 0; i < transcript.size(); i++) {
            if (PROFILE_REPORT.matcher(transcript.get(i).trim()).matches()) {
                at = i;
            }
        }
        if (at < 0) {
            return null;
        }
        Matcher report = PROFILE_REPORT.matcher(transcript.get(at).trim());
        String profile = report.matches() ? report.group(1) : "DL";
        String file = null;
        for (String line : transcript.subList(0, at)) {
            Matcher output = PROFILE_OUTPUT.matcher(line.trim());
            if (ROBOT_LINE.matcher(line.trim()).matches() && output.matches()) {
                file = output.group(1);
            }
        }
        List<String> violations = new ArrayList<String>();
        boolean makeHasSpoken = false;
        for (String line : transcript.subList(at + 1, transcript.size())) {
            String said = line.trim();
            if (ROBOT_LINE.matcher(said).matches()) {
                // The run went on to its next line: the profile did not stop it.
                return null;
            }
            makeHasSpoken |= said.startsWith("make");
            if (!makeHasSpoken && !said.isEmpty()) {
                violations.add(said);
            }
        }
        if (violations.isEmpty()) {
            return null;
        }
        String first = violations.get(0);
        int axiom = first.indexOf(" [");
        first = named(axiom < 0 ? first : first.substring(0, axiom), ontology);
        return "The ontology is not in OWL 2 " + profile + ", which ODK's test asks "
                + "of it - " + violations.size() + (violations.size() == 1 ? " violation: "
                        : " violations, the first: ") + first + ". "
                + (file == null ? "" : "The list is in " + file + ", and ")
                + "OntoBoard > Check and test > Profile... names the axioms that take the "
                + "ontology out of the profile.";
    }

    /** A line of the run with each address in it that the ontology names said by that name. */
    private static String named(String text, OWLOntology ontology) {
        if (ontology == null) {
            return text;
        }
        Matcher address = IRI_IN_BRACKETS.matcher(text);
        StringBuffer said = new StringBuffer();
        while (address.find()) {
            String name = null;
            for (OWLEntity entity : ontology.getEntitiesInSignature(
                    IRI.create(address.group(1)), Imports.INCLUDED)) {
                String label = DisplayLabels.forEntity(ontology, entity);
                if (label != null && !label.trim().isEmpty()) {
                    name = label;
                    break;
                }
            }
            address.appendReplacement(said, Matcher.quoteReplacement(
                    name == null ? address.group() : name));
        }
        address.appendTail(said);
        return said.toString();
    }

    // ------------------------------------------------------------------ what to do about a check

    /** The label check of a project made by 1.135 or before, and of one made since. */
    private static boolean isTheLabelCheck(File check) {
        return check != null && (check.getName().equals("check_labels.rq")
                || check.getName().equals(OdkExtras.LABEL_CHECK + OdkChecks.SUFFIX));
    }

    /**
     * What to do about a failing check, chosen by the check (1.135.1, Windows study rehearsal).
     *
     * <p>One sentence ended every failure: "fix the term (a missing label is added in Protege
     * under Annotations)". It was written when the label check was a project's only check; a test
     * written from a question is not answered by a label, and a guide's task sent participants
     * to add one nobody was missing. The template that wrote a test is read back from its query.
     */
    static String adviceFor(File check, OWLOntology ontology) {
        if (check != null && odksNameOf(check.getName()) != null) {
            // ODK's update writes every file it ships in src/sparql again: "edit the
            // check" would be advice that lasts until the next update.
            return "Each listed term breaks that rule: fix the term, or, if the rule does "
                    + "not apply to this project, take the check's name out of "
                    + "custom_sparql_checks in the project's <id>-odk.yaml and run "
                    + "OntoBoard > Project > Update project files... - the check's file "
                    + "is ODK's own and is written again by every update, so an edit to "
                    + "it does not last.";
        }
        String text = null;
        if (check != null) {
            try {
                text = new String(Files.readAllBytes(check.toPath()), StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                text = null;
            }
        }
        QuestionTests.Template template = QuestionTests.templateOf(text);
        if (isTheLabelCheck(check) || template == QuestionTests.Template.EVERY_ONE_HAS_A_LABEL) {
            return "Each listed term breaks the rule beside it: fix the term (a missing label is"
                    + " added in Protege under Annotations), or edit the check in "
                    + SparqlQuery.CHECKS_DIRECTORY + " if the rule does not apply to this project.";
        }
        if (template == null) {
            return "Each listed term breaks the rule beside it: change the model so it does not,"
                    + " or edit the check in " + SparqlQuery.CHECKS_DIRECTORY + " if the rule does"
                    + " not apply to this project.";
        }
        IRI cls = QuestionTests.classIn(text);
        IRI property = QuestionTests.propertyIn(text);
        IRI filler = QuestionTests.fillerIn(text);
        if (template == QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME_THAT_IS && cls != null
                && property != null && filler != null && ontology != null) {
            org.semanticweb.owlapi.model.OWLDataFactory df =
                    ontology.getOWLOntologyManager().getOWLDataFactory();
            return "The model does not answer this question yet. To make it answer, right-click "
                    + DisplayLabels.forEntity(ontology, df.getOWLClass(cls))
                    + " on the board, choose Relate to another term…, pick "
                    + DisplayLabels.forEntity(ontology, df.getOWLClass(filler))
                    + " and set Property to "
                    + DisplayLabels.forEntity(ontology, df.getOWLObjectProperty(property))
                    + "; then save and build again. A class above it that takes something"
                    + " broader does not answer it.";
        }
        if (template == QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME && cls != null
                && property != null && ontology != null) {
            org.semanticweb.owlapi.model.OWLDataFactory df =
                    ontology.getOWLOntologyManager().getOWLDataFactory();
            String about = DisplayLabels.forEntity(ontology, df.getOWLClass(cls));
            if (ontology.containsDataPropertyInSignature(property, Imports.INCLUDED)) {
                return "The model does not answer this question yet. To make it answer, add '"
                        + DisplayLabels.forEntity(ontology, df.getOWLDataProperty(property))
                        + " some' and the value's type (such as xsd:decimal) to " + about
                        + "'s SubClass Of, in Protege's Classes tab; then save and build again.";
            }
            return "The model does not answer this question yet. To make it answer, right-click "
                    + about + " on the board, choose Relate to another term…, pick the class"
                    + " it relates to and set Property to "
                    + DisplayLabels.forEntity(ontology, df.getOWLObjectProperty(property))
                    + "; then save and build again.";
        }
        return "The model does not answer this question yet: change it so that none of the"
                + " listed terms breaks the rule, then save and build again.";
    }

    /**
     * The first term a failing check lists that the ontology does not have at all, or null.
     *
     * <p>A test written from a question on a class that was then deleted listed the class's bare
     * IRI as breaking its rule - the zero-length step of {@code rdfs:subClassOf*} matches an IRI
     * whether or not anything says it exists - and so failed every build after, naming a term
     * that was nowhere to be found. Tests written since say {@code ?term a owl:Class} and pass
     * instead; this is for the ones written before, and for any hand-written check that does the
     * same.
     */
    static String goneFrom(OWLOntology ontology, List<List<String>> rows) {
        for (List<String> row : rows) {
            String first = row.isEmpty() ? "" : row.get(0);
            if (first.startsWith("http://") || first.startsWith("https://")) {
                if (!ontology.containsEntityInSignature(IRI.create(first), Imports.INCLUDED)) {
                    return first;
                }
            }
        }
        return null;
    }

    /**
     * What a check is for, from the comment it opens with - its first paragraph - or null.
     *
     * <p>A test written from a question opens with the question, and a hand-written check
     * usually says its rule the same way; reading it is what turns "my_check.rq failed" into a
     * sentence somebody can act on. A longer comment goes on after an empty comment line, and
     * is for whoever opens the file.
     */
    static String ruleOf(File query) {
        if (query == null) {
            return null;
        }
        StringBuilder rule = new StringBuilder();
        try {
            for (String line : Files.readAllLines(query.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("#")) {
                    break;
                }
                String text = trimmed.replaceFirst("^#+\\s*", "");
                if (text.isEmpty()) {
                    if (rule.length() > 0) {
                        break;
                    }
                    continue;
                }
                rule.append(rule.length() == 0 ? "" : " ").append(text);
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return rule.length() == 0 ? null : rule.toString();
    }

    // ------------------------------------------------------------------ a second opinion

    /**
     * What HermiT finds that ELK cannot see, or null: asked only after ELK reasoned, and only
     * when the ontology and its imports go beyond OWL 2 EL - inverse properties, universal
     * restrictions, cardinalities - which ELK ignores without a word.
     *
     * <p>Found by a tester: <i>quality of</i> picked for a value's relation made the class
     * unsatisfiable through the inverse <i>has quality</i>, whose range is a quality. HermiT said
     * so; ELK, which the build uses as ODK's does, did not, so the test passed and the class was
     * released. A warning, not a failure: the build keeps the verdict ODK's would give, and says
     * what a fuller reasoner adds. Not asked for a large ontology, and given ten seconds.
     */
    public static String secondOpinion(OWLOntology ontology, OWLReasonerFactory used) {
        if (ontology == null || used == null
                || !used.getClass().getName().toLowerCase(Locale.ROOT).contains("elk")) {
            return null;
        }
        // The count before the profile: it costs nothing, and an ontology too large to ask
        // HermiT about in passing is also the longest to look through for its profile.
        int axioms = 0;
        for (OWLOntology part : ontology.getImportsClosure()) {
            axioms += part.getLogicalAxiomCount();
        }
        if (axioms > SECOND_OPINION_AXIOMS
                || ProfileCheck.isIn(ontology, ProfileCheck.Target.EL)) {
            return null;
        }
        final org.semanticweb.owlapi.reasoner.OWLReasoner hermit;
        try {
            hermit = new org.semanticweb.HermiT.ReasonerFactory().createNonBufferingReasoner(
                    ontology, new org.semanticweb.owlapi.reasoner.SimpleConfiguration(
                            SECOND_OPINION_MILLIS));
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
        java.util.concurrent.ExecutorService asking =
                java.util.concurrent.Executors.newSingleThreadExecutor(work -> {
                    Thread thread = new Thread(work, "OntoBoard: HermiT's second opinion");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            java.util.concurrent.Future<List<String>> answer = asking.submit(() -> {
                List<String> found = new ArrayList<String>();
                if (!hermit.isConsistent()) {
                    found.add(null);
                    return found;
                }
                for (org.semanticweb.owlapi.model.OWLClass cls
                        : hermit.getUnsatisfiableClasses().getEntitiesMinusBottom()) {
                    found.add(DisplayLabels.forEntity(ontology, cls));
                }
                return found;
            });
            List<String> found = answer.get(SECOND_OPINION_MILLIS + 2_000,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (found.isEmpty()) {
                return null;
            }
            String what;
            // "Added to it" followed a list of classes (1.135.1 review); the ontology is one.
            String toWhat = " to it";
            if (found.contains(null)) {
                what = "that the ontology is inconsistent";
            } else {
                Collections.sort(found, String.CASE_INSENSITIVE_ORDER);
                String names = found.size() <= 5 ? String.join(", ", found)
                        : String.join(", ", found.subList(0, 5)) + " and " + (found.size() - 5)
                                + " more";
                what = "that " + names + " can have no members";
                toWhat = found.size() == 1 ? " to it" : " to one of them";
            }
            return "HermiT finds " + what + ". ELK, the reasoner this build uses as ODK's does,"
                    + " does not see inverse properties and the rest of OWL beyond its EL"
                    + " profile, so the build passes as ODK's would. A relation or parent added"
                    + toWhat + " is the usual cause:" + RobotTransform.TAKE_IT_OUT;
        } catch (Exception notAnswered) {
            hermit.interrupt();
            return null;
        } finally {
            asking.shutdownNow();
            hermit.dispose();
        }
    }
}
