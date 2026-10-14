package de.fizkarlsruhe.ise.ontoboard.bench;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns;
import de.fizkarlsruhe.ise.ontoboard.robot.TemplateSheet;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetAudit;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetBook;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetFix;
import de.fizkarlsruhe.ise.ontoboard.sheet.Workbook;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every measurement this project claims, as a command somebody else can run.
 *
 * <p>Written because a paper that says "the audit finds 8,071 things in a production knowledge
 * graph" is asking to be taken on trust, and because the only way to get that number out of
 * this plugin was to start Protege, open a folder and read a status line. There was no
 * {@code main} anywhere in the source: the whole of it was reachable only through a window.
 *
 * <p>So this is the reproduction package, and it is deliberately boring. It takes an
 * {@code .xlsx} or a folder of {@code .tsv}, runs the shipped code - not a copy of it, not a
 * reimplementation for the benchmark - and writes tab-separated numbers to standard output. A
 * reviewer with the jar and the workbook can check any figure in one line. Nothing here touches
 * AWT, so it runs on a build machine.
 *
 * <pre>
 * java -cp ontoboard-1.119.0.jar de.fizkarlsruhe.ise.ontoboard.bench.Bench corpus    book.xlsx
 * java -cp ontoboard-1.119.0.jar de.fizkarlsruhe.ise.ontoboard.bench.Bench summary   book.xlsx
 * java -cp ontoboard-1.119.0.jar de.fizkarlsruhe.ise.ontoboard.bench.Bench audit     book.xlsx
 * java -cp ontoboard-1.119.0.jar de.fizkarlsruhe.ise.ontoboard.bench.Bench conflicts book.xlsx
 * java -cp ontoboard-1.119.0.jar de.fizkarlsruhe.ise.ontoboard.bench.Bench repair    book.xlsx
 * java -cp ontoboard-1.119.0.jar de.fizkarlsruhe.ise.ontoboard.bench.Bench roundtrip book.xlsx
 * </pre>
 *
 * <p>No timestamps and no random anything: run it twice on one input and the bytes match, so a
 * difference in the output is a difference in the software or the data and never in the clock.
 * Nothing is written to the input, ever - an {@code .xlsx} is expanded into a temporary folder
 * that is deleted on the way out, and {@code repair} changes only the copy in memory.
 */
public final class Bench {

    /**
     * What a reader of the output should see at the top of it.
     *
     * <p>From the filtered build properties, not a literal: a measurement labelled with the
     * wrong version is worse than one with no version, and this project already removed that
     * drift once for the embedded ROBOT.
     */
    private static final String VERSION = version();

    private static String version() {
        java.io.InputStream in = Bench.class.getClassLoader()
                .getResourceAsStream("ontoboard-build.properties");
        if (in == null) {
            return "unknown";
        }
        try {
            java.util.Properties properties = new java.util.Properties();
            properties.load(in);
            String said = properties.getProperty("ontoboard.version");
            return said == null || said.trim().isEmpty() || said.contains("${") ? "unknown"
                    : said.trim();
        } catch (java.io.IOException cannotRead) {
            return "unknown";
        } finally {
            try {
                in.close();
            } catch (java.io.IOException ignored) {
                // Closing a classpath resource cannot usefully fail.
            }
        }
    }

    private Bench() {
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    /** Separated from {@code main} so a test can run a command without a process. */
    public static int run(String[] args, PrintStream out, PrintStream problems) {
        if (args == null || args.length == 0 || isHelp(args[0])) {
            usage(out);
            return args == null || args.length == 0 ? 2 : 0;
        }
        String command = args[0].trim().toLowerCase(java.util.Locale.ROOT);
        if ("version".equals(command)) {
            out.println(VERSION);
            return 0;
        }
        if (args.length < 2) {
            problems.println("bench: " + command + " needs a workbook or a folder.");
            usage(problems);
            return 2;
        }
        File input = new File(args[1]);
        if (!input.exists()) {
            problems.println("bench: no such file or folder: " + input.getAbsolutePath());
            return 2;
        }
        File temporary = null;
        try {
            File folder;
            if (input.isDirectory()) {
                folder = input;
            } else {
                temporary = Files.createTempDirectory("ontoboard-bench").toFile();
                Workbook.importInto(input, temporary);
                folder = temporary;
            }
            if ("corpus".equals(command)) {
                return corpus(input, folder, out);
            }
            if ("summary".equals(command)) {
                return summary(input, folder, out);
            }
            if ("audit".equals(command)) {
                return audit(input, folder, out, null);
            }
            if ("conflicts".equals(command)) {
                return audit(input, folder, out, SheetAudit.Kind.CONFLICTING_DEFINITION);
            }
            if ("repair".equals(command)) {
                return repair(input, folder, out);
            }
            if ("roundtrip".equals(command)) {
                if (input.isDirectory()) {
                    problems.println("bench: roundtrip compares a workbook against the files we "
                            + "write for ROBOT, so it needs the .xlsx, not a folder.");
                    return 2;
                }
                return roundtrip(input, folder, out);
            }
            problems.println("bench: no command called " + command + ".");
            usage(problems);
            return 2;
        } catch (IOException cannotRead) {
            problems.println("bench: " + cannotRead.getMessage());
            return 1;
        } catch (RuntimeException broken) {
            problems.println("bench: " + broken.getClass().getSimpleName() + ": "
                    + broken.getMessage());
            return 1;
        } finally {
            deleteTree(temporary);
        }
    }

    // ---------------------------------------------------------------- the commands

    /**
     * How big the corpus is, by the definitions a paper has to state.
     *
     * <p>Template tabs and other tabs counted apart, because a workbook people keep by hand has
     * both and quoting the total as "ROBOT templates" would be wrong. Non-empty cells rather
     * than cells, because a spreadsheet's extent is whatever somebody once clicked.
     */
    private static int corpus(File input, File folder, PrintStream out) {
        SheetBook book = SheetBook.open(folder);
        int templateTabs = 0;
        int templateRows = 0;
        int otherTabs = 0;
        int otherRows = 0;
        int templateCells = 0;
        int otherCells = 0;
        int identifiers = 0;
        int withLabel = 0;
        for (SheetBook.Sheet sheet : book.getSheets()) {
            TemplateColumns columns = sheet.getColumns();
            TemplateColumns.Column id = columns.firstOf(TemplateColumns.Kind.ID);
            TemplateColumns.Column label = columns.firstLabelColumn();
            boolean isTemplate = id != null || columns.firstOf(TemplateColumns.Kind.LABEL) != null;
            int rows = Math.max(0, sheet.getLastRow() - 2);
            int cells = 0;
            for (List<String> row : sheet.getRows()) {
                for (String cell : row) {
                    if (cell != null && !cell.trim().isEmpty()) {
                        cells++;
                    }
                }
            }
            if (isTemplate) {
                templateTabs++;
                templateRows += rows;
                templateCells += cells;
            } else {
                otherTabs++;
                otherRows += rows;
                otherCells += cells;
            }
            for (int row = SheetBook.FIRST_DATA_ROW; row <= sheet.getLastRow(); row++) {
                if (id != null && !book.cell(sheet.getName(), row, id.getNumber()).trim()
                        .isEmpty()) {
                    identifiers++;
                    if (label != null && !book.cell(sheet.getName(), row, label.getNumber())
                            .trim().isEmpty()) {
                        withLabel++;
                    }
                }
            }
        }
        header(out, "corpus", input);
        out.println("measure\tvalue");
        row(out, "tabs read", templateTabs + otherTabs);
        row(out, "robot template tabs", templateTabs);
        row(out, "other tabs", otherTabs);
        row(out, "data rows in template tabs", templateRows);
        row(out, "data rows in other tabs", otherRows);
        row(out, "non-empty cells in template tabs", templateCells);
        row(out, "non-empty cells in other tabs", otherCells);
        row(out, "identifiers", identifiers);
        row(out, "identifiers with a label", withLabel);
        row(out, "tabs that could not be opened", book.getUnreadable().size());
        for (SheetBook.Unreadable each : book.getUnreadable()) {
            out.println("could not open\t" + each.getFile().getName() + "\t" + each.getReason());
        }
        return 0;
    }

    private static int summary(File input, File folder, PrintStream out) {
        SheetBook book = SheetBook.open(folder);
        Map<SheetAudit.Kind, int[]> counts = countsOf(book.auditAll());
        header(out, "summary", input);
        out.println("kind\tfindings\tcarrying a replacement");
        int all = 0;
        int fixable = 0;
        for (SheetAudit.Kind kind : SheetAudit.Kind.values()) {
            int[] seen = counts.get(kind);
            if (seen == null) {
                continue;
            }
            out.println(kind.name() + "\t" + seen[0] + "\t" + seen[1]);
            all += seen[0];
            fixable += seen[1];
        }
        out.println("TOTAL\t" + all + "\t" + fixable);
        return 0;
    }

    private static int audit(File input, File folder, PrintStream out, SheetAudit.Kind only) {
        SheetBook book = SheetBook.open(folder);
        header(out, only == null ? "audit" : "conflicts", input);
        out.println("kind\tsheet\trow\tcolumn\theading\tcell\tsuggestion\tmessage");
        for (SheetAudit.Finding finding : book.auditAll()) {
            if (only != null && finding.getKind() != only) {
                continue;
            }
            out.println(finding.getKind().name() + "\t" + finding.getSheet() + "\t"
                    + finding.getRow() + "\t" + finding.getColumn() + "\t"
                    + flat(finding.getHeading()) + "\t" + flat(finding.getCell()) + "\t"
                    + flat(finding.getSuggestion()) + "\t" + flat(finding.getMessage()));
        }
        return 0;
    }

    /**
     * What applying every suggestion does, and whether doing it twice does anything more.
     *
     * <p>The second pass is the point. A repair that keeps finding work to do is a repair that
     * is fighting itself, and the number that matters to a reader is not "7,406 applied" but
     * "625 left, and a second pass changes nothing".
     */
    private static int repair(File input, File folder, PrintStream out) {
        SheetBook book = SheetBook.open(folder);
        Map<SheetAudit.Kind, int[]> before = countsOf(book.auditAll());
        SheetFix.Outcome first = SheetFix.applyAll(book, book.auditAll());
        Map<SheetAudit.Kind, int[]> after = countsOf(book.auditAll());
        String undo = book.canUndo() ? book.nextUndo() : "";
        SheetFix.Outcome second = SheetFix.applyAll(book, book.auditAll());
        Map<SheetAudit.Kind, int[]> settled = countsOf(book.auditAll());

        header(out, "repair", input);
        out.println("kind\tbefore\tafter\tafter a second pass");
        for (SheetAudit.Kind kind : SheetAudit.Kind.values()) {
            if (!before.containsKey(kind) && !after.containsKey(kind)) {
                continue;
            }
            out.println(kind.name() + "\t" + total(before, kind) + "\t" + total(after, kind)
                    + "\t" + total(settled, kind));
        }
        out.println("TOTAL\t" + sum(before) + "\t" + sum(after) + "\t" + sum(settled));
        row(out, "cells changed", first.getApplied());
        row(out, "passes before nothing more changed", first.getPasses());
        row(out, "left alone, changed by something other than this fix", first.getSkipped());
        row(out, "no replacement to apply", first.getNotFixable());
        row(out, "cells a second run would change", second.getApplied());
        out.println("one undo for the batch\t" + undo);
        return 0;
    }

    /**
     * Does a cell survive the trip out to ROBOT and back?
     *
     * <p>Reads the workbook, reads the files written for ROBOT with ROBOT's own reader, and
     * compares every cell. This is the measurement that found the quoting defect in 1.118.0 -
     * 20,795 cells differing and two tabs unreadable, with nothing reported anywhere - so it
     * ships rather than living in a scratch directory.
     */
    private static int roundtrip(File workbook, File folder, PrintStream out) throws IOException {
        int cells = 0;
        int differing = 0;
        int unreadable = 0;
        List<String> worst = new ArrayList<String>();
        for (Workbook.Tab tab : Workbook.read(workbook)) {
            if (tab.getRows().isEmpty()) {
                continue;
            }
            File file = new File(folder,
                    tab.getName().replaceAll("[^A-Za-z0-9_-]", "-") + ".tsv");
            if (!file.isFile()) {
                continue;
            }
            List<List<String>> back;
            try {
                back = TemplateSheet.read(file);
            } catch (RuntimeException cannotRead) {
                unreadable++;
                worst.add("unreadable\t" + tab.getName() + "\t"
                        + cannotRead.getClass().getSimpleName());
                continue;
            }
            List<List<String>> original = tab.getRows();
            for (int row = 0; row < original.size(); row++) {
                List<String> was = original.get(row);
                List<String> now = row < back.size() ? back.get(row) : null;
                for (int column = 0; column < was.size(); column++) {
                    String written = flat(was.get(column));
                    String read = now != null && column < now.size() ? now.get(column) : "";
                    cells++;
                    if (!written.equals(read)) {
                        differing++;
                        if (worst.size() < 20) {
                            worst.add("differs\t" + tab.getName() + "\trow " + (row + 1)
                                    + "\tcolumn " + (column + 1) + "\t" + written + "\t" + read);
                        }
                    }
                }
            }
        }
        header(out, "roundtrip", workbook);
        out.println("measure\tvalue");
        row(out, "cells compared", cells);
        row(out, "cells that changed", differing);
        row(out, "tabs ROBOT could not read", unreadable);
        for (String each : worst) {
            out.println(each);
        }
        return differing == 0 && unreadable == 0 ? 0 : 1;
    }

    // ---------------------------------------------------------------- plumbing

    private static Map<SheetAudit.Kind, int[]> countsOf(List<SheetAudit.Finding> findings) {
        Map<SheetAudit.Kind, int[]> counts = new LinkedHashMap<SheetAudit.Kind, int[]>();
        for (SheetAudit.Finding finding : findings) {
            int[] seen = counts.get(finding.getKind());
            if (seen == null) {
                seen = new int[2];
                counts.put(finding.getKind(), seen);
            }
            seen[0]++;
            if (finding.isFixable()) {
                seen[1]++;
            }
        }
        return counts;
    }

    private static int total(Map<SheetAudit.Kind, int[]> counts, SheetAudit.Kind kind) {
        int[] seen = counts.get(kind);
        return seen == null ? 0 : seen[0];
    }

    private static int sum(Map<SheetAudit.Kind, int[]> counts) {
        int all = 0;
        for (int[] each : counts.values()) {
            all += each[0];
        }
        return all;
    }

    private static void header(PrintStream out, String command, File input) {
        out.println("# ontoboard " + VERSION + " bench " + command + " " + input.getName());
    }

    private static void row(PrintStream out, String name, int value) {
        out.println(name + "\t" + value);
    }

    /** One cell on one line, so a tab-separated row stays a row. */
    private static String flat(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace("\r\n", " ").replace('\n', ' ')
                .replace('\r', ' ');
    }

    private static boolean isHelp(String first) {
        String one = first == null ? "" : first.trim();
        return one.isEmpty() || "-h".equals(one) || "--help".equals(one) || "help".equals(one);
    }

    private static void usage(PrintStream out) {
        out.println("ontoboard " + VERSION + " bench - the measurements, reproducibly");
        out.println();
        out.println("  bench corpus    <book.xlsx|folder>   how big it is");
        out.println("  bench summary   <book.xlsx|folder>   findings per kind");
        out.println("  bench audit     <book.xlsx|folder>   every finding, one per line");
        out.println("  bench conflicts <book.xlsx|folder>    where two sheets contradict");
        out.println("  bench repair    <book.xlsx|folder>   before, after, and a second pass");
        out.println("  bench roundtrip <book.xlsx>          every cell, through ROBOT's reader");
        out.println("  bench version");
        out.println();
        out.println("Tab-separated to standard output. The input is never written to.");
        out.println("roundtrip exits 1 if a single cell changed, so it can gate a build.");
    }

    private static void deleteTree(File file) {
        if (file == null) {
            return;
        }
        File[] inside = file.listFiles();
        if (inside != null) {
            for (File each : inside) {
                deleteTree(each);
            }
        }
        if (!file.delete() && file.exists()) {
            file.deleteOnExit();
        }
    }

    /** Only so the list of commands is in one place for a test to read. */
    public static List<String> commands() {
        return Arrays.asList("corpus", "summary", "audit", "conflicts", "repair", "roundtrip",
                "version");
    }
}
