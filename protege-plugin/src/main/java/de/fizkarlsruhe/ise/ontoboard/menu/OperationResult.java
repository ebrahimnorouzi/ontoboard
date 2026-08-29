package de.fizkarlsruhe.ise.ontoboard.menu;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What an OntoBoard operation did, in a form that can be shown, saved and compared.
 *
 * <p>Every entry on the OntoBoard menu returns one of these rather than opening its own dialog.
 * That is deliberate: a dozen operations each inventing their own way of reporting success gives a
 * dozen slightly different dialogs, none of which can be saved, and an operation that fails
 * quietly because its author forgot the error case. One shape means one place to get the reporting
 * right.
 *
 * <p>It carries three things a user asks for after any operation: <em>did it work</em>, <em>what
 * did it produce</em>, and <em>what happened along the way</em>. The log matters as much as the
 * result. ROBOT operations in particular succeed with warnings, and a report that shows a table
 * while discarding the warnings that produced it is the kind of half-truth that costs somebody an
 * afternoon.
 *
 * <p>Immutable and free of Swing, so the shape of a result is testable even though the dialog that
 * renders it is not.
 */
public final class OperationResult {

    /** How it went. Distinguished because "no rows" and "it failed" are not the same news. */
    public enum Status {
        /** It ran and produced what was asked for. */
        SUCCEEDED,
        /** It ran, but something a user should know about happened. */
        SUCCEEDED_WITH_WARNINGS,
        /** It did not run, or ran and failed. The reason is in the summary. */
        FAILED
    }

    private final String operation;
    private final Status status;
    private final String summary;
    private final List<String> columns;
    private final List<List<String>> rows;
    private final List<String> log;
    private final List<File> files;
    private final long millis;
    private final Date when;

    private OperationResult(Builder builder) {
        this.operation = builder.operation;
        this.status = builder.status;
        this.summary = builder.summary;
        this.columns = Collections.unmodifiableList(new ArrayList<String>(builder.columns));
        this.rows = Collections.unmodifiableList(new ArrayList<List<String>>(builder.rows));
        this.log = Collections.unmodifiableList(new ArrayList<String>(builder.log));
        this.files = Collections.unmodifiableList(new ArrayList<File>(builder.files));
        this.millis = builder.millis;
        this.when = builder.when;
    }

    public static Builder of(String operation) {
        return new Builder(operation);
    }

    /** A failure, with the reason as the summary. */
    public static OperationResult failed(String operation, String reason) {
        return of(operation).failed(reason).build();
    }

    public String getOperation() {
        return operation;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isSuccess() {
        return status != Status.FAILED;
    }

    /** One line: what happened. Never blank - a result with nothing to say is a bug. */
    public String getSummary() {
        return summary;
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<List<String>> getRows() {
        return rows;
    }

    public List<String> getLog() {
        return log;
    }

    /** Files the operation wrote, for the dialog to offer. */
    public List<File> getFiles() {
        return files;
    }

    public long getMillis() {
        return millis;
    }

    public boolean hasTable() {
        return !columns.isEmpty();
    }

    /**
     * The whole result as text, for saving or for pasting into an issue.
     *
     * <p>Includes the log and the timing, because a result copied into a bug report without them
     * is missing exactly what makes it diagnosable.
     */
    public String toPlainText() {
        StringBuilder text = new StringBuilder();
        text.append("OntoBoard: ").append(operation).append('\n');
        text.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(when))
                .append('\n');
        text.append(status).append(" in ").append(millis).append(" ms\n");
        text.append(summary).append("\n");
        if (hasTable()) {
            text.append('\n').append(join(columns)).append('\n');
            for (List<String> row : rows) {
                text.append(join(row)).append('\n');
            }
        }
        if (!log.isEmpty()) {
            text.append("\n--- log ---\n");
            for (String line : log) {
                text.append(line).append('\n');
            }
        }
        if (!files.isEmpty()) {
            text.append("\n--- files written ---\n");
            for (File file : files) {
                text.append(file.getAbsolutePath()).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * Writes {@link #toPlainText()} to {@code target}.
     *
     * @return the file written, so a caller can tell the user where it went
     */
    public File saveTo(File target) throws IOException {
        Files.write(target.toPath(), toPlainText().getBytes(StandardCharsets.UTF_8));
        return target;
    }

    /** A filename that sorts by time and says what it was, for the save dialog to suggest. */
    public String suggestedFileName() {
        String slug = operation.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return "ontoboard-" + slug + "-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(when) + ".txt";
    }

    private static String join(List<String> cells) {
        StringBuilder line = new StringBuilder();
        for (String cell : cells) {
            if (line.length() > 0) {
                line.append('\t');
            }
            line.append(cell == null ? "" : cell.replace('\t', ' ').replace('\n', ' '));
        }
        return line.toString();
    }

    /** Builds a result; the terminal call is {@link #build()}. */
    public static final class Builder {
        private final String operation;
        private final List<String> columns = new ArrayList<String>();
        private final List<List<String>> rows = new ArrayList<List<String>>();
        private final List<String> log = new ArrayList<String>();
        private final List<File> files = new ArrayList<File>();
        private final long started = System.currentTimeMillis();
        private Status status = Status.SUCCEEDED;
        private String summary = "";
        private long millis;
        private Date when = new Date();

        private Builder(String operation) {
            if (operation == null || operation.trim().isEmpty()) {
                throw new IllegalArgumentException("an operation needs a name to report under");
            }
            this.operation = operation.trim();
        }

        public Builder summary(String text) {
            this.summary = text == null ? "" : text;
            return this;
        }

        public Builder columns(String... names) {
            columns.clear();
            Collections.addAll(columns, names);
            return this;
        }

        public Builder row(String... cells) {
            List<String> row = new ArrayList<String>();
            Collections.addAll(row, cells);
            rows.add(row);
            return this;
        }

        public Builder note(String line) {
            if (line != null && !line.trim().isEmpty()) {
                log.add(line.trim());
            }
            return this;
        }

        /** A note that also downgrades the status, so warnings cannot be logged and forgotten. */
        public Builder warn(String line) {
            note(line);
            if (status == Status.SUCCEEDED) {
                status = Status.SUCCEEDED_WITH_WARNINGS;
            }
            return this;
        }

        public Builder failed(String reason) {
            status = Status.FAILED;
            summary = reason == null || reason.trim().isEmpty()
                    ? "It failed, and gave no reason." : reason.trim();
            note(summary);
            return this;
        }

        public Builder wrote(File file) {
            if (file != null) {
                files.add(file);
            }
            return this;
        }

        /** For tests, so a result's rendering does not depend on when it ran. */
        Builder at(Date when, long millis) {
            this.when = when;
            this.millis = millis;
            return this;
        }

        public OperationResult build() {
            if (millis == 0) {
                millis = System.currentTimeMillis() - started;
            }
            if (summary.isEmpty()) {
                // A result with nothing to say tells the user nothing, so one is derived rather
                // than shown blank.
                summary = rows.isEmpty() ? "Finished, with nothing to report."
                        : rows.size() + " result" + (rows.size() == 1 ? "" : "s") + ".";
            }
            return new OperationResult(this);
        }
    }

    /** Column names to values for the first row, for a caller that wants one figure. */
    public Map<String, String> firstRow() {
        Map<String, String> named = new LinkedHashMap<String, String>();
        if (rows.isEmpty()) {
            return named;
        }
        List<String> row = rows.get(0);
        for (int i = 0; i < columns.size() && i < row.size(); i++) {
            named.put(columns.get(i), row.get(i));
        }
        return named;
    }
}
