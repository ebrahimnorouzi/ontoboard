package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * A project's questions, and the rule that asks them.
 *
 * <p>A question is a {@code .rq} file in {@code src/sparql}: a query whose every row is
 * something that breaks what the ontology is to hold - "every kind of dataset has some part".
 * OntoBoard 1.135.x asked them of the ontology <em>with its imports merged</em>, and that is
 * what such a question means: its terms and the restrictions that answer it are as often in
 * an import as in the edit file.
 *
 * <p>ODK's own checks ({@code robot_report: custom_sparql_checks}, run by {@code sparql_test})
 * read the edit file with its imports taken out. A question asked that way changes its answer
 * without a word: the recorded 1.135.2 project's "every dataset has part" passes with its
 * import and names two terms without it - seen in ODK's own container. So questions are not
 * ODK checks. They have a rule of their own, which hangs on ODK's {@code test}:
 *
 * <pre>
 * question_test: $(EDIT_PREPROCESSED) | $(REPORTDIR)
 *         $(ROBOT) merge --input $(EDIT_PREPROCESSED) verify --queries $(QUESTIONS) -O $(REPORTDIR)
 * test: question_test
 * </pre>
 *
 * <p>It lives in the project's own {@code <id>.Makefile}, which is where ODK means a project's
 * additions to be: ODK's Makefile includes that file last, ODK's update never writes it, and
 * make runs what it holds - in ODK's container, with ODK's tools on the machine, in continuous
 * integration. OntoBoard's own engine, which otherwise runs ODK's recipes and nothing of a
 * project's own, runs this rule too, as long as it is the rule as OntoBoard writes it
 * ({@link #alone}, used by {@link FallbackEligibility}).
 *
 * <p>The rule is written when a project gets its first question and when a project of the
 * older layout is carried over; a new project, which has none, is ODK's seed to the byte.
 *
 * <p>Text and files only; no Swing, no OWL API.
 */
public final class OdkQuestions {

    /** How the rule's first line begins; the whole line is in {@link #rule}. */
    private static final String FIRST = "# --- OntoBoard: questions ";

    private static final String LAST = "# --- OntoBoard: end of questions ---";

    /** What a question's file name ends in. */
    public static final String SUFFIX = ".rq";

    /** The target the rule adds to ODK's {@code test}. */
    public static final String TARGET = "question_test";

    private static final String RULE = FIRST
            + "(change nothing down to the line that ends them) ---\n"
            + "# Every .rq file in src/sparql is a question this ontology has to answer: a query\n"
            + "# whose every row is something that breaks it. `make test` asks them of the\n"
            + "# ontology WITH its imports and fails on any row. ODK's own checks (sparql_test,\n"
            + "# the *-violation.sparql files the configuration lists) read the edit file without\n"
            + "# its imports, where a question about an imported term has no answer.\n"
            + "# OntoBoard wrote these lines - Check and test > Write a test from a question...\n"
            + "# adds a question - and runs them itself where there is no container, but only\n"
            + "# as they are written here.\n"
            + "QUESTIONS = $(wildcard ../sparql/*.rq)\n"
            + "\n"
            + ".PHONY: question_test\n"
            + "question_test:\n"
            + "ifneq ($(strip $(QUESTIONS)),)\n"
            + "question_test: $(EDIT_PREPROCESSED) | $(REPORTDIR)\n"
            + "\t$(ROBOT) merge --input $(EDIT_PREPROCESSED) verify --queries $(QUESTIONS) "
            + "-O $(REPORTDIR)\n"
            + "endif\n"
            + "\n"
            + "test: question_test\n"
            + LAST + "\n";

    private OdkQuestions() {
    }

    // ------------------------------------------------------------------ the rule, as text

    /** The rule as OntoBoard writes it: its lines, each ending in a line feed. */
    public static String rule() {
        return RULE;
    }

    /** Whether a project's own makefile holds the rule exactly as OntoBoard writes it. */
    public static boolean isIn(String ownMakefile) {
        return linesIn(ownMakefile) != null;
    }

    /**
     * Whether the file holds a rule of OntoBoard's that is no longer as it was written: its
     * first line is there, and what follows is not the rule.
     */
    public static boolean isChangedIn(String ownMakefile) {
        return ownMakefile != null && firstLineIn(linesOf(ownMakefile)) >= 0
                && linesIn(ownMakefile) == null;
    }

    /**
     * Where the rule is in the file, when it is there exactly as written: the numbers of its
     * first and its last line, counted from 1. Null when it is not there, or was changed.
     */
    public static int[] linesIn(String ownMakefile) {
        if (ownMakefile == null) {
            return null;
        }
        List<String> lines = linesOf(ownMakefile);
        List<String> rule = linesOf(RULE);
        // The rule's text ends in a line feed, so its last "line" is the empty rest.
        rule = rule.subList(0, rule.size() - 1);
        // Every line that begins one: a whole rule may stand after one that was cut off.
        for (int first = 0; first + rule.size() <= lines.size(); first++) {
            if (lines.get(first).startsWith(FIRST)
                    && lines.subList(first, first + rule.size()).equals(rule)) {
                return new int[] {first + 1, first + rule.size()};
            }
        }
        return null;
    }

    /**
     * The file with the rule in it: added at its end where there is none; put right at its
     * place where somebody changed it; as it is where the rule is there as written. What else
     * the file holds stays, and so do its line endings.
     */
    public static String with(String ownMakefile) {
        String text = ownMakefile == null ? "" : ownMakefile;
        if (isIn(text)) {
            return text;
        }
        boolean windows = text.contains("\r\n") && !text.replace("\r\n", "").contains("\n");
        String unix = windows ? text.replace("\r\n", "\n") : text;
        List<String> lines = linesOf(unix);
        int first = firstLineIn(lines);
        int last = first < 0 ? -1 : lines.subList(first, lines.size()).indexOf(LAST);
        String now;
        if (first >= 0 && last >= 0) {
            // Changed between its two marker lines: those lines are written again.
            List<String> all = new ArrayList<String>(lines.subList(0, first));
            List<String> rule = linesOf(RULE);
            all.addAll(rule.subList(0, rule.size() - 1));
            all.addAll(lines.subList(first + last + 1, lines.size()));
            now = String.join("\n", all);
        } else {
            // None, or one cut off before its last line: that is left, and a whole one added.
            String before = unix.replaceAll("\n+$", "");
            now = (before.isEmpty() ? "" : before + "\n\n") + RULE;
        }
        return windows ? now.replace("\n", "\r\n") : now;
    }

    /** The lines of a text, without their line endings; the rest after the last one included. */
    private static List<String> linesOf(String text) {
        return new ArrayList<String>(Arrays.asList(text.replace("\r\n", "\n").split("\n", -1)));
    }

    /** The index of the rule's first line among these; -1 when it is not there. */
    private static int firstLineIn(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(FIRST)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ the files

    /** The project's questions: the {@code .rq} files of {@code src/sparql}, by name. */
    public static List<File> filesIn(File projectRoot) {
        List<File> files = new ArrayList<File>();
        File folder = new File(projectRoot.getAbsoluteFile(), "src/sparql");
        String[] names = folder.list();
        for (String name : names == null ? new TreeSet<String>()
                : new TreeSet<String>(Arrays.asList(names))) {
            File file = new File(folder, name);
            if (name.endsWith(SUFFIX) && file.isFile()) {
                files.add(file);
            }
        }
        return files;
    }

    /** The project's own makefile, {@code src/ontology/<id>.Makefile}; null when no id is known. */
    public static File ownMakefileOf(File projectRoot) {
        File ontology = new File(projectRoot.getAbsoluteFile(), "src/ontology");
        File config = OdkBuildSettings.yamlIn(ontology);
        if (config == null) {
            return null;
        }
        String name = config.getName();
        return new File(ontology, name.substring(0, name.length() - "-odk.yaml".length())
                + ".Makefile");
    }

    /**
     * Whether the project's own makefile holds a rule of OntoBoard's that somebody changed
     * ({@link #isChangedIn(String)}); false also where the file cannot be read.
     */
    public static boolean isChangedIn(File projectRoot) {
        File own = ownMakefileOf(projectRoot);
        try {
            return own != null && own.isFile() && isChangedIn(new String(
                    Files.readAllBytes(own.toPath()), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** Whether the project's own makefile holds the rule as OntoBoard writes it. */
    public static boolean isKeptIn(File projectRoot) {
        File own = ownMakefileOf(projectRoot);
        try {
            return own != null && own.isFile() && isIn(new String(
                    Files.readAllBytes(own.toPath()), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Sees to it that the project's own makefile holds the rule: writes it where there is
     * none or a changed one, and makes the file where the project has none (ODK's Makefile
     * includes it).
     *
     * @return the file, when it was written; null when the rule was there already
     * @throws IOException when the file cannot be read or written, or the project has no
     *                     single configuration to tell its id by
     */
    public static File ensure(File projectRoot) throws IOException {
        File own = ownMakefileOf(projectRoot);
        if (own == null) {
            throw new IOException("src/ontology holds no ODK configuration (or more than one), "
                    + "so the project's own makefile cannot be told.");
        }
        String before = own.isFile() ? new String(Files.readAllBytes(own.toPath()),
                StandardCharsets.UTF_8) : "";
        String after = with(before);
        if (own.isFile() && after.equals(before)) {
            return null;
        }
        Files.write(own.toPath(), after.getBytes(StandardCharsets.UTF_8));
        return own;
    }
}
