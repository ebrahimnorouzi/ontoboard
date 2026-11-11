package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes a run's {@code results.csv} (one row per case, model and repeat) and {@code summary.md}
 * (one table per task, one row per model). Sorted, with no timestamps, as {@code bench/Bench}
 * does: the same replies give the same bytes.
 */
final class ResultsWriter {

    /** One call's outcome. */
    static final class Row {
        final String caseId;
        final String task;
        final boolean control;
        final String model;
        final int repeat;
        final Score score;
        final long millis;
        final int inputTokens;
        final int outputTokens;
        final String error;

        Row(String caseId, String task, boolean control, String model, int repeat, Score score,
                long millis, int inputTokens, int outputTokens, String error) {
            this.caseId = caseId;
            this.task = task;
            this.control = control;
            this.model = model;
            this.repeat = repeat;
            this.score = score;
            this.millis = millis;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.error = error;
        }

        boolean passed() {
            return error == null && score.isPass();
        }
    }

    private ResultsWriter() {
    }

    static void write(List<Row> unsorted, File directory, String version) throws IOException {
        List<Row> rows = new ArrayList<Row>(unsorted);
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                int byCase = a.caseId.compareTo(b.caseId);
                if (byCase != 0) {
                    return byCase;
                }
                int byModel = a.model.compareTo(b.model);
                return byModel != 0 ? byModel : Integer.compare(a.repeat, b.repeat);
            }
        });
        Files.createDirectories(directory.toPath());
        Files.write(new File(directory, "results.csv").toPath(),
                csv(rows).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(directory, "summary.md").toPath(),
                summary(rows, version).getBytes(StandardCharsets.UTF_8));
    }

    static String csv(List<Row> rows) {
        StringBuilder out = new StringBuilder("case,task,model,repeat,pass,");
        out.append(String.join(",", Scorers.COLUMNS))
                .append(",millis,inputTokens,outputTokens,error\n");
        for (Row row : rows) {
            out.append(cell(row.caseId)).append(',').append(cell(row.task)).append(',')
                    .append(cell(row.model)).append(',').append(row.repeat).append(',')
                    .append(row.passed() ? 1 : 0);
            for (String column : Scorers.COLUMNS) {
                Double value = row.score.getValues().get(column);
                out.append(',').append(value == null ? "" : number(value));
            }
            out.append(',').append(row.millis).append(',').append(row.inputTokens).append(',')
                    .append(row.outputTokens).append(',')
                    .append(cell(row.error == null ? "" : row.error)).append('\n');
        }
        return out.toString();
    }

    static String summary(List<Row> rows, String version) {
        StringBuilder out = new StringBuilder("# Assistant benchmark\n\n");
        out.append("OntoBoard ").append(version).append(". One table per task, one row per"
                + " model. A call that failed counts as a failure. termRecall is the share of the"
                + " justification's terms the answer names; namesClash is 1 when it names both"
                + " sides of the clash; asked and askedRight say whether the model asked for the"
                + " held-back term; inventedIris counts IRIs that are not in the ontology."
                + " definition: validProposal is 1 when the answer has a proposal OntoBoard would"
                + " offer as a card for the right term; proposals is the number of different"
                + " cards the answer offers that a person could accept, of any kind; namesGenus"
                + " when the first definition's"
                + " value names an asserted parent; overlap is the F1 of its content words against"
                + " the held-back definition; it passes with one card, a valid definition that"
                + " names its genus, as the request asks for one block. text-to-axiom: proposals"
                + " is the number of different cards a person could accept; entails, entailed,"
                + " equivalent and safe"
                + " describe the first add-axiom; it passes with one card, a valid add-axiom that"
                + " leaves the ontology consistent and no class newly empty and that is equivalent"
                + " to the held-back axiom given the rest of the ontology. repair: the"
                + " model's remove-axiom, replace-axiom and add-axiom proposals are applied to a"
                + " copy and the reasoner runs"
                + " again, up to 3 rounds; solved, rounds, removedFault (what was removed is the"
                + " injected axiom, or an axiom of a justification the loop showed), collateral"
                // As the code counts them (1.149.0, review 1.148.0 bench-01 and bench-03; 1.150.0,
                // review 1.149.0 bench-04 and bench-06; 1.151.0, review 1.150.0 bench-01).
                + " (other axioms removed; for a problem the ontology already had, the removals"
                + " that were not needed, or, when it was not solved, the removals outside every"
                + " justification the loop showed) and validProposals (over all rounds, the share"
                + " of"
                + " the answers' blocks that changed the logic: removals and replacements"
                + " applied, and additions; annotation cards, blocks shown as Cannot be applied,"
                + " and a removal or an addition that changes nothing count against it); it"
                + " passes when solved without collateral."
                + " Tokens are as the servers reported them; -1 means not reported.\n");
        // Controls get a table of their own (review #5c): askedRight is 0 on every control by
        // construction, so averaging them in capped a perfect model's mean below 1.
        Map<String, List<Row>> byTask = new TreeMap<String, List<Row>>();
        for (Row row : rows) {
            String table = row.control ? row.task + " (controls)" : row.task;
            if (!byTask.containsKey(table)) {
                byTask.put(table, new ArrayList<Row>());
            }
            byTask.get(table).add(row);
        }
        for (Map.Entry<String, List<Row>> task : byTask.entrySet()) {
            Set<String> columns = new LinkedHashSet<String>();
            for (String column : Scorers.COLUMNS) {
                for (Row row : task.getValue()) {
                    if (row.score.getValues().containsKey(column)) {
                        columns.add(column);
                    }
                }
            }
            out.append("\n## ").append(task.getKey()).append("\n\n| model | cases | repeats |"
                    + " pass rate |");
            for (String column : columns) {
                out.append(" mean ").append(column).append(" |");
            }
            out.append(" median ms | tokens in | tokens out | errors |\n|---|---|---|---|");
            for (int i = 0; i < columns.size(); i++) {
                out.append("---|");
            }
            out.append("---|---|---|---|\n");
            Map<String, List<Row>> byModel = new TreeMap<String, List<Row>>();
            for (Row row : task.getValue()) {
                if (!byModel.containsKey(row.model)) {
                    byModel.put(row.model, new ArrayList<Row>());
                }
                byModel.get(row.model).add(row);
            }
            for (Map.Entry<String, List<Row>> model : byModel.entrySet()) {
                out.append(line(model.getKey(), model.getValue(), columns));
            }
        }
        return out.toString();
    }

    private static String line(String model, List<Row> rows, Set<String> columns) {
        Set<String> cases = new LinkedHashSet<String>();
        int repeats = 0;
        int passed = 0;
        int errors = 0;
        List<Long> millis = new ArrayList<Long>();
        long tokensIn = 0;
        long tokensOut = 0;
        boolean inKnown = false;
        boolean outKnown = false;
        Map<String, double[]> sums = new LinkedHashMap<String, double[]>();
        for (Row row : rows) {
            cases.add(row.caseId);
            repeats = Math.max(repeats, row.repeat);
            passed += row.passed() ? 1 : 0;
            errors += row.error == null ? 0 : 1;
            millis.add(row.millis);
            if (row.inputTokens >= 0) {
                tokensIn += row.inputTokens;
                inKnown = true;
            }
            if (row.outputTokens >= 0) {
                tokensOut += row.outputTokens;
                outKnown = true;
            }
            for (String column : columns) {
                Double value = row.score.getValues().get(column);
                if (value != null) {
                    double[] sum = sums.get(column);
                    if (sum == null) {
                        sum = new double[2];
                        sums.put(column, sum);
                    }
                    sum[0] += value;
                    sum[1]++;
                }
            }
        }
        Collections.sort(millis);
        StringBuilder out = new StringBuilder("| ").append(model).append(" | ")
                .append(cases.size()).append(" | ").append(repeats).append(" | ")
                .append(String.format(Locale.ROOT, "%d/%d (%.0f%%)", passed, rows.size(),
                        rows.isEmpty() ? 0.0 : 100.0 * passed / rows.size())).append(" |");
        for (String column : columns) {
            double[] sum = sums.get(column);
            out.append(' ').append(sum == null ? "" : String.format(Locale.ROOT, "%.2f",
                    sum[0] / sum[1])).append(" |");
        }
        out.append(' ').append(millis.isEmpty() ? 0 : millis.get(millis.size() / 2))
                .append(" | ").append(inKnown ? String.valueOf(tokensIn) : "-1")
                .append(" | ").append(outKnown ? String.valueOf(tokensOut) : "-1")
                .append(" | ").append(errors).append(" |\n");
        return out.toString();
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.3f", value);
    }

    private static String cell(String value) {
        String text = value == null ? "" : value;
        if (text.contains(",") || text.contains("\"") || text.contains("\n")
                || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
