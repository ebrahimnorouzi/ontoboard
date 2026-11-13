package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * One task of an ODK project run both ways - ODK's own program in ODK's Docker image, and
 * OntoBoard's own code without a container - on fresh copies of the same project, the two
 * results held against each other, each way timed; and the table of all of it.
 *
 * <p>Test support for {@code SideBySideLiveTest}: the routes, the project and the comparison are
 * the caller's; this runs them in a fixed order, keeps the times and writes
 * {@code results.csv} and {@code report.md}. A route that refuses a task says why
 * ({@link Refused}): that is a result, written in the row, not a failure. A route that fails is a
 * difference, said in the row, so the table is written whatever happened.
 */
final class SideBySide {

    /** How much of a difference the Markdown table shows; the CSV keeps all of it. */
    private static final int SHOWN = 240;

    private SideBySide() {
    }

    /** A route's refusal to run a task, with its reason. */
    static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        Refused(String reason) {
            super(reason);
        }
    }

    /** One way of running a task on a fresh copy of the project; returns what is compared. */
    interface Route<T> {
        T run(File project) throws Exception;
    }

    /** The first difference between the two results, in plain words, or null if they agree. */
    interface Comparison<T> {
        String firstDifference(T odks, T ontoBoards) throws Exception;
    }

    /** A comparison written as assertions, as the existing tests write theirs. */
    interface Check<T> {
        void check(T odks, T ontoBoards) throws Exception;
    }

    /** Writes the project, as the task starts from it, into a folder that is not there yet. */
    interface Fixture {
        void writeTo(File folder) throws Exception;
    }

    /** The first assertion that fails is the difference, in its own words. */
    static <T> Comparison<T> byAssertions(final Check<T> check) {
        return new Comparison<T>() {
            @Override
            public String firstDifference(T odks, T ontoBoards) throws Exception {
                try {
                    check.check(odks, ontoBoards);
                    return null;
                } catch (AssertionError differs) {
                    return differs.getMessage() == null ? differs.toString()
                            : differs.getMessage();
                }
            }
        };
    }

    /** A route's times in milliseconds: the first run apart, then the counted ones. */
    static final class Times {
        private final long first;
        private final List<Long> counted;

        Times(long first, List<Long> counted) {
            this.first = first;
            this.counted = new ArrayList<Long>(counted);
            Collections.sort(this.counted);
        }

        /** The run before the counted ones: the JVM, the disk's cache and Docker still cold. */
        long getFirst() {
            return first;
        }

        /** Of the counted runs the one in the middle - of an even number, the mean of two. */
        Long getMedian() {
            int size = counted.size();
            if (size == 0) {
                return null;
            }
            return size % 2 == 1 ? counted.get(size / 2)
                    : (counted.get(size / 2 - 1) + counted.get(size / 2)) / 2;
        }

        Long getLowest() {
            return counted.isEmpty() ? null : counted.get(0);
        }

        Long getHighest() {
            return counted.isEmpty() ? null : counted.get(counted.size() - 1);
        }

        /** {@code 5000}, or {@code 5000 first; median 1200, lowest 1000, highest 1400}. */
        String describe() {
            return counted.isEmpty() ? String.valueOf(first)
                    : first + " first; median " + getMedian() + ", lowest " + getLowest()
                            + ", highest " + getHighest();
        }
    }

    /** One task, both ways. */
    static final class Row {
        private final String task;
        private final String project;
        private final Times odk;
        private final Times ontoBoard;
        private final int runs;
        private final boolean refused;
        private final String differs;
        private final String note;

        Row(String task, String project, Times odk, Times ontoBoard, int runs, boolean refused,
                String differs, String note) {
            this.task = task;
            this.project = project;
            this.odk = odk;
            this.ontoBoard = ontoBoard;
            this.runs = runs;
            this.refused = refused;
            this.differs = differs;
            this.note = note;
        }

        String getTask() {
            return task;
        }

        String getProject() {
            return project;
        }

        /** ODK's route in Docker. */
        Times getOdk() {
            return odk;
        }

        /** OntoBoard's route, or null when it refused the task. */
        Times getOntoBoard() {
            return ontoBoard;
        }

        /** How many runs each way were counted, after the first. */
        int getRuns() {
            return runs;
        }

        /** Both ran, and every run's two results were the same. */
        boolean agrees() {
            return !refused && differs.isEmpty();
        }

        boolean isRefused() {
            return refused;
        }

        /** The first difference in plain words, or "". */
        String getDiffers() {
            return differs;
        }

        /** "refused: <why>", or "". */
        String getNote() {
            return note;
        }
    }

    /** Where the times were taken. */
    static final class Machine {
        final String os;
        final String java;
        final String docker;
        final String image;
        final String imageId;
        final String owlApi;
        final String date;
        final String robotHere;
        final String robotInImage;

        Machine(String os, String java, String docker, String image, String imageId,
                String owlApi, String date, String robotHere, String robotInImage) {
            this.os = os;
            this.java = java;
            this.docker = docker;
            this.image = image;
            this.imageId = imageId;
            this.owlApi = owlApi;
            this.date = date;
            this.robotHere = robotHere;
            this.robotInImage = robotInImage;
        }
    }

    /** The next {@code run}, timed by the wall clock. */
    static <T> Row run(String task, String project, int runs, File scratch, Fixture fixture,
            Route<T> odk, Route<T> ontoBoard, Comparison<T> comparison) throws Exception {
        return run(task, project, runs, scratch, fixture, odk, ontoBoard, comparison,
                new LongSupplier() {
                    @Override
                    public long getAsLong() {
                        return System.currentTimeMillis();
                    }
                });
    }

    /**
     * Runs a task both ways, {@code runs + 1} times each: one run that is not counted, then the
     * counted ones. Each run of each route is on a fresh copy of the project, in a folder of its
     * own under {@code scratch} ({@code odk-0}, {@code ontoboard-0}, {@code odk-1}, ...); ODK's
     * route runs first. A route's time is the clock read just before and just after the call.
     * The first difference of the earliest run that has one is the row's. Once OntoBoard's
     * route refuses, it is not asked again.
     */
    static <T> Row run(String task, String project, int runs, File scratch, Fixture fixture,
            Route<T> odk, Route<T> ontoBoard, Comparison<T> comparison, LongSupplier clock)
            throws Exception {
        long odkFirst = 0;
        long ourFirst = 0;
        List<Long> odkCounted = new ArrayList<Long>();
        List<Long> ourCounted = new ArrayList<Long>();
        boolean refused = false;
        String note = "";
        String differs = "";
        for (int at = 0; at <= runs; at++) {
            File odkCopy = new File(scratch, "odk-" + at);
            fixture.writeTo(odkCopy);
            T odks = null;
            String odkFailed = null;
            long started = clock.getAsLong();
            try {
                odks = odk.run(odkCopy);
            } catch (Exception | AssertionError failed) {
                odkFailed = describe(failed);
            }
            long took = clock.getAsLong() - started;
            if (at == 0) {
                odkFirst = took;
            } else {
                odkCounted.add(took);
            }

            T ours = null;
            String ourFailed = null;
            if (!refused) {
                File ourCopy = new File(scratch, "ontoboard-" + at);
                fixture.writeTo(ourCopy);
                started = clock.getAsLong();
                try {
                    ours = ontoBoard.run(ourCopy);
                } catch (Refused why) {
                    refused = true;
                    note = "refused: " + why.getMessage();
                } catch (Exception | AssertionError failed) {
                    ourFailed = describe(failed);
                }
                took = clock.getAsLong() - started;
                if (at == 0) {
                    ourFirst = took;
                } else {
                    ourCounted.add(took);
                }
            }

            if (differs.isEmpty()) {
                if (odkFailed != null) {
                    differs = "ODK's route failed: " + odkFailed;
                } else if (refused) {
                    continue;
                } else if (ourFailed != null) {
                    differs = "OntoBoard's route failed: " + ourFailed;
                } else {
                    String found;
                    try {
                        found = comparison.firstDifference(odks, ours);
                    } catch (Exception failed) {
                        found = "the comparison failed: " + describe(failed);
                    }
                    differs = found == null ? "" : found;
                }
            }
        }
        return new Row(task, project, new Times(odkFirst, odkCounted),
                refused ? null : new Times(ourFirst, ourCounted), runs, refused, differs, note);
    }

    private static String describe(Throwable failed) {
        return failed.getClass().getSimpleName() + ": " + failed.getMessage();
    }

    // ------------------------------------------------------------------ the table

    private static final String CSV_HEADER = "task,project,odk_first_ms,odk_median_ms,"
            + "odk_lowest_ms,odk_highest_ms,ontoboard_first_ms,ontoboard_median_ms,"
            + "ontoboard_lowest_ms,ontoboard_highest_ms,runs,agrees,refused,differs,note";

    /** One line a row, after a header; a cell is quoted as RFC 4180 has it. Lines end in LF. */
    static String csv(List<Row> rows) {
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        for (Row row : rows) {
            List<String> cells = new ArrayList<String>();
            cells.add(row.getTask());
            cells.add(row.getProject());
            times(cells, row.getOdk());
            times(cells, row.getOntoBoard());
            cells.add(String.valueOf(row.getRuns()));
            cells.add(String.valueOf(row.agrees()));
            cells.add(String.valueOf(row.isRefused()));
            cells.add(row.getDiffers());
            cells.add(row.getNote());
            for (int at = 0; at < cells.size(); at++) {
                csv.append(at == 0 ? "" : ",").append(quoted(cells.get(at)));
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    private static void times(List<String> cells, Times times) {
        cells.add(times == null ? "" : String.valueOf(times.getFirst()));
        cells.add(times == null || times.getMedian() == null ? "" : times.getMedian().toString());
        cells.add(times == null || times.getLowest() == null ? "" : times.getLowest().toString());
        cells.add(times == null || times.getHighest() == null ? ""
                : times.getHighest().toString());
    }

    private static String quoted(String cell) {
        if (cell.contains(",") || cell.contains("\"") || cell.contains("\n")
                || cell.contains("\r")) {
            return "\"" + cell.replace("\"", "\"\"") + "\"";
        }
        return cell;
    }

    /** The rows as a Markdown table. A refused task shows "-" for its time and its agreement. */
    static String table(List<Row> rows) {
        StringBuilder table = new StringBuilder("| Task | Project | ODK in Docker, ms "
                + "| OntoBoard, ms | Counted runs | Agrees | First difference | Note |\n"
                + "|---|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            String differs = row.getDiffers().length() <= SHOWN ? row.getDiffers()
                    : row.getDiffers().substring(0, SHOWN) + " ...";
            table.append("| ").append(cell(row.getTask()))
                    .append(" | ").append(cell(row.getProject()))
                    .append(" | ").append(row.getOdk().describe())
                    .append(" | ").append(row.getOntoBoard() == null ? "-"
                            : row.getOntoBoard().describe())
                    .append(" | ").append(row.getRuns())
                    .append(" | ").append(row.isRefused() ? "-" : row.agrees() ? "yes" : "no")
                    .append(" | ").append(cell(differs))
                    .append(" | ").append(cell(row.getNote()))
                    .append(" |\n");
        }
        return table.toString();
    }

    private static String cell(String text) {
        return text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
                .replace("|", "\\|");
    }

    /**
     * The whole report: what was run, the table, what each number holds, the machine, and what
     * the table does not show - the refusals the run met, then sentences that hold for every run.
     */
    static String report(List<Row> rows, Machine machine) {
        int runs = 0;
        List<String> projects = new ArrayList<String>();
        for (Row row : rows) {
            runs = Math.max(runs, row.getRuns());
            if (!projects.contains(row.getProject())) {
                projects.add(row.getProject());
            }
        }
        StringBuilder report = new StringBuilder("# ODK in Docker and OntoBoard, side by side\n\n")
                .append("Each task of an ODK project, run both ways on fresh copies of the same ")
                .append("project: ODK's own program in ODK's Docker image, and OntoBoard's own ")
                .append("code without a container. ")
                .append(runs == 0 ? "Each task ran once each way."
                        : "Each task ran " + (runs + 1) + " times each way: one run that is not "
                                + "counted, then " + runs + " counted.")
                .append("\n\n")
                .append(table(rows))
                .append("\nNot in the table yet: Refresh imports - OntoBoard's side of it is not ")
                .append("written.\n\n")
                .append("## What the numbers are\n\n")
                .append("- ODK in Docker: the wall-clock time of one `docker run`, from starting ")
                .append("it to its exit - the container's start, the project mounted into it, ")
                .append("ODK's program, the container's end.\n")
                .append("- OntoBoard: the wall-clock time of one call, from start to return, in ")
                .append("the one JVM that runs the tests - as inside Protege: no JVM is started ")
                .append("for it, no container.\n")
                .append("- Neither number includes writing the fresh copy of the project, or ")
                .append("comparing the two results.\n")
                .append("- With counted runs, the first run of each task is apart (\"first\"); of ")
                .append("the counted ones the median, the lowest and the highest are given.\n\n")
                .append("## The machine\n\n")
                .append("- Operating system: ").append(machine.os).append('\n')
                .append("- Java: ").append(machine.java).append('\n')
                .append("- Docker's server: ").append(machine.docker).append('\n')
                .append("- The image: ").append(machine.image).append(" (").append(machine.imageId)
                .append(")\n")
                .append("- The OWL API on the class path: ").append(machine.owlApi).append('\n')
                .append("- The date: ").append(machine.date).append("\n\n")
                .append("## What this does not show\n\n");
        boolean anyRefused = false;
        for (Row row : rows) {
            if (row.isRefused()) {
                anyRefused = true;
                String why = row.getNote().substring("refused: ".length()).trim();
                report.append("- ").append(row.getTask())
                        .append(": OntoBoard's route refused it - ")
                        .append(why.endsWith(".") ? why : why + ".").append('\n');
            }
        }
        if (!anyRefused) {
            report.append("- OntoBoard's route refused no task of these.\n");
        }
        report.append("- The times are from one machine on one day, with whatever else that ")
                .append("machine was doing then: they are not a benchmark.\n")
                .append("- ODK's number includes starting a container for each call; ")
                .append("OntoBoard's does not.\n")
                .append("- The project is a small fixture (").append(String.join(", ", projects))
                .append("): how the two compare on a large ontology is not measured here.\n")
                .append("- ROBOT is ").append(machine.robotHere).append(" inside OntoBoard and ")
                .append(machine.robotInImage).append(" in the image.\n");
        return report.toString();
    }

    /** {@code results.csv} and {@code report.md} in {@code folder}, as UTF-8. */
    static void write(File folder, List<Row> rows, Machine machine) throws IOException {
        Files.createDirectories(folder.toPath());
        Files.write(new File(folder, "results.csv").toPath(),
                csv(rows).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(folder, "report.md").toPath(),
                report(rows, machine).getBytes(StandardCharsets.UTF_8));
    }
}
