package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ROBOT's own report rules and queries, read as streams.
 *
 * <p>This exists because of one line in {@code robot-core}. {@code ReportOperation} finds its
 * queries with
 *
 * <pre>ReportOperation.class.getClassLoader().getResource("report_queries")</pre>
 *
 * and then compares {@code URL.getProtocol()} against {@code "file"} and {@code "jar"}. Inside an
 * OSGi bundle a resource URL is neither - Felix hands back {@code bundle:} - so it falls through to
 * {@code throw new IOException("Cannot access report query files")}. Every entry point that
 * <em>chooses</em> the queries goes through it: {@code getReport}, all six {@code report} overloads,
 * and all three {@code getTDBReport} overloads.
 *
 * <p><b>One correction, because this file previously overstated the case.</b> It said "every public
 * entry point", and that is false.
 * {@code getViolations(IOHelper, Dataset, String, String, Map)} is public and takes the SPARQL as a
 * {@code String} parameter, so it never reaches {@code getReportQueries}: {@code javap -c} shows its
 * body calling only {@code OptionsHelper}, {@code Dataset.begin}/{@code end},
 * {@code QueryOperation.execQuery} and {@code getViolationsFromResults}. An earlier audit in this
 * project asserted that no such method exists; it does, and saying otherwise here was wrong.
 *
 * <p>So why does {@link RuleRunner} still run the queries itself? Because what
 * {@code getViolations} adds is ROBOT's own row-to-{@code Violation} mapping, and that mapping is
 * the part already shown to be equivalent: it reads {@code ?entity}, {@code ?property} and
 * {@code ?value} through a private {@code getQueryResultOrNull}, which is what {@code RuleRunner}
 * does, and {@code RobotParityTest} demonstrates the two produce identical violation sets - 7/7 rows
 * on {@code fixture-tiny}, 21/21 on pizza v2, against real ROBOT in {@code obolibrary/odkfull}.
 * Against that, {@code getViolations} would add two new in-bundle risks for no measured gain: a
 * {@code new IOHelper()} on the report path, and a Jena {@code Dataset} that must support
 * {@code begin}/{@code end}, which the obvious {@code DatasetFactory.create(model)} does not. It is
 * the better call if a future rule binds variables this code does not handle - all 33 of ROBOT's
 * queries are {@code SELECT DISTINCT ?entity ?property ?value} today, and {@code OdkScaffoldTest}
 * pins that.
 *
 * <p>So ROBOT's quality report has never worked inside this plugin, on either supported Protege.
 * The failure is in {@code ~/.Protege/logs/protege.log} at 2026-08-28 under OntoBoard 1.15.0, and
 * it sat there for nine versions while {@code docs/limitations.md} blamed the OWL API and promised
 * Protege 5.6.x had "the full surface".
 *
 * <p><b>What is ours and what is ROBOT's.</b> The rules, the severities and the SPARQL are entirely
 * ROBOT's, read out of {@code robot-core}'s own jar at the version this bundle embeds - so the
 * plugin and a project's {@code make report} agree about what a violation is. Only the plumbing is
 * ours: opening a stream instead of enumerating a directory. Directory <em>enumeration</em> is the
 * only thing that fails under Felix; {@code getResourceAsStream} on a known path works perfectly,
 * which is what makes the fix small.
 *
 * <p>The cost, stated plainly: a {@code robot-core} upgrade that renames a rule or moves these
 * resources breaks this where the CLI would not. {@code OdkScaffoldTest} pins the rule set against
 * the bundled profile, so that breakage is a failing test rather than a silent gap. The proper fix
 * is upstream - {@code getResourceAsStream} in {@code ReportOperation} - and is not ours to ship.
 */
public final class ReportQueries {

    /** ROBOT's default profile: one {@code LEVEL<tab>rule} line per rule. */
    private static final String PROFILE_RESOURCE = "report_profile.txt";

    /** Where the per-rule SPARQL lives inside {@code robot-core}. */
    private static final String QUERY_DIRECTORY = "report_queries/";

    /** A profile separates level from rule with a tab, but tolerate any run of whitespace. */
    private static final String WHITESPACE = "\\s+";

    private ReportQueries() {
    }

    /**
     * Every rule ROBOT ships, with the severity ROBOT gives it.
     *
     * <p>Read from the profile rather than from a list written here. A hardcoded list is a second
     * copy of ROBOT's own policy, and it goes stale in exactly the way nobody notices: silently, as
     * a check that stops running.
     *
     * @return rule name to {@code ERROR}/{@code WARN}/{@code INFO}, in the profile's own order
     * @throws RobotException when the profile cannot be read, which means the bundle is broken
     */
    public static Map<String, String> defaultSeverities() {
        InputStream in = ReportQueries.class.getClassLoader()
                .getResourceAsStream(PROFILE_RESOURCE);
        if (in == null) {
            throw new RobotException("Could not read ROBOT's own report profile ("
                    + PROFILE_RESOURCE + ") from the bundle. The plugin's copy of robot-core is "
                    + "incomplete.");
        }
        try {
            return Collections.unmodifiableMap(parse(in));
        } catch (IOException cannotRead) {
            throw new RobotException("Could not read " + PROFILE_RESOURCE + ": "
                    + cannotRead.getMessage(), cannotRead);
        } finally {
            close(in);
        }
    }

    /**
     * The rules and severities in a project's own {@code profile.txt}.
     *
     * <p>A project's profile wins over ROBOT's defaults, because the plugin and that project's CI
     * must agree about what counts as a violation - the whole reason
     * {@code QualityReport.optionsFor} finds the file. Note that ROBOT does <em>not</em> merge a
     * given profile with its defaults: it runs exactly what the file lists, so a rule deleted from
     * that file is a check that stops running. The scaffold writes all 32 for that reason.
     *
     * @return the file's rules, or an empty map when there is no readable file - never ROBOT's
     *     defaults, so the caller decides what an unreadable project profile means
     */
    public static Map<String, String> severitiesIn(File profile) {
        if (profile == null || !profile.isFile()) {
            return Collections.emptyMap();
        }
        try {
            InputStream in = new FileInputStream(profile);
            try {
                return Collections.unmodifiableMap(parse(in));
            } finally {
                close(in);
            }
        } catch (IOException cannotRead) {
            // An unreadable project profile falls back to ROBOT's defaults in the caller, which is
            // a better answer than no report at all.
            return Collections.emptyMap();
        }
    }

    /**
     * The SPARQL for one rule, or null when ROBOT ships no query for it.
     *
     * <p>Null rather than an exception: the profile and the query directory do not correspond
     * exactly - robot-core 1.9.8 carries 33 {@code .rq} files for 32 profile entries - and a rule
     * named in a project's own profile.txt need not be one ROBOT knows. A missing query is worth
     * reporting to the user as a skipped check, not worth abandoning the other 31.
     */
    public static String queryFor(String rule) {
        if (rule == null || rule.trim().isEmpty()) {
            return null;
        }
        InputStream in = ReportQueries.class.getClassLoader()
                .getResourceAsStream(QUERY_DIRECTORY + rule.trim() + ".rq");
        if (in == null) {
            return null;
        }
        try {
            StringBuilder text = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                text.append(line).append('\n');
            }
            return text.toString();
        } catch (IOException cannotRead) {
            throw new RobotException("Could not read the query for rule '" + rule + "': "
                    + cannotRead.getMessage(), cannotRead);
        } finally {
            close(in);
        }
    }

    /**
     * One {@code LEVEL<tab>rule} line at a time, in the file's own order.
     *
     * <p>ROBOT's default profile and a project's profile.txt are the same format, so they get the
     * same parser. Blank lines and {@code #} comments are skipped; a line with fewer than two
     * fields is ignored rather than fatal, because a profile is a file a human edits.
     */
    private static Map<String, String> parse(InputStream in) throws IOException {
        Map<String, String> severities = new LinkedHashMap<String, String>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split(WHITESPACE);
            if (parts.length >= 2) {
                severities.put(parts[1], parts[0].toUpperCase(Locale.ROOT));
            }
        }
        return severities;
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Closing a classpath resource cannot usefully fail, and failing to close one is not
            // worth losing the report over.
        }
    }
}
