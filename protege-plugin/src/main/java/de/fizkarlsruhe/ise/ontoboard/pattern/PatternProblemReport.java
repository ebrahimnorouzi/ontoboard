package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Where to report a problem with one of the library's patterns: the project's issue form for
 * exactly that, opened with the title and the pattern already filled in.
 *
 * <p>Asked for: "for creating issue, if the pattern is already available, then the user may only
 * have the title and description of the issue, but not asking for all the required field like
 * cqs". One form used to serve both a new pattern and a problem with a shipped one, and it
 * required a name, a description, competency questions, the ontology file, a source and a licence
 * - six things somebody who has found a wrong axiom in a shipped pattern does not have and should
 * not be asked for. The problem form, {@value #FORM}, requires a title and a description and
 * nothing else.
 *
 * <p><b>One address, two callers.</b> The library dialog's <i>Report a problem with this
 * pattern...</i> opens it, and {@code tools/generate-pattern-pages.py} writes the same address onto
 * every pattern's web page. {@code PatternProblemReportTest} pins the address for a real pattern
 * and checks that the generator names the same form, the same title and the same field, so the
 * page and the dialog cannot drift into sending a reader to two different places.
 *
 * <p><b>The repository comes from the pom</b>, through the filtered
 * {@code ontoboard-build.properties} - the {@code <github.repo>} property that already builds
 * {@code update.properties}' download address and the bundle's {@code Bundle-DocURL}. Not a
 * literal here: the old form's own link to the contributing page was typed out by hand, as
 * {@code ise-fizkarlsruhe.github.io/ontoboard}, and was a 404 (measured), while the site is
 * published at {@code ebrahimnorouzi.github.io/ontoboard}. An address written twice is an address
 * that can be written wrong once.
 */
public final class PatternProblemReport {

    /**
     * The form, by its file name under {@code .github/ISSUE_TEMPLATE/}.
     *
     * <p>GitHub's {@code ?template=} takes the file name, not the form's display name, so renaming
     * the file breaks every link to it without anything failing to build - which is why the test
     * checks that this file exists.
     */
    public static final String FORM = "pattern-problem.yml";

    /**
     * How every report's title starts, and the form's own default title.
     *
     * <p>A prefix rather than a label because a label only applies when it already exists in the
     * repository, and the title is what somebody scanning the issue list actually reads.
     */
    public static final String TITLE_PREFIX = "[pattern problem] ";

    /**
     * The form field the pattern's id is filled into: its {@code id:} in {@value #FORM}.
     *
     * <p>GitHub documents the field id as "the canonical identifier for the field in URL query
     * parameter prefills". The id goes in as well as the title because a title gets edited, and
     * the id is the one thing a maintainer needs exactly: nine of the library's entries are another
     * pattern under a near-identical name ({@code agentrole} is "Agentrole Pattern",
     * {@code agent-role} is "Agent Role Pattern" - measured in {@code patterns/index.tsv}).
     */
    public static final String PATTERN_FIELD = "pattern";

    /** {@code owner/name} on GitHub, or null when this build's resources were not filtered. */
    private static final String REPOSITORY = readRepository();

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private PatternProblemReport() {
    }

    /**
     * Where to report a problem with this pattern, or null when there is nowhere to.
     *
     * <p>Null for one of the user's own patterns: it is a file in their folder, not part of the
     * library, so its author is the person looking at it and an issue here would be one nobody
     * else can act on. Null too when this build did not record its repository - a build fault,
     * reported by {@link #whyNot} rather than guessed around.
     */
    public static String urlFor(DesignPattern pattern) {
        if (whyNot(pattern) != null) {
            return null;
        }
        return urlFor(REPOSITORY, pattern.getId(), pattern.getName());
    }

    /**
     * Why this pattern cannot be reported, in a sentence for a tooltip, or null when it can.
     *
     * <p>A disabled button with no reason is one people click at and give up on; the reason is
     * also the only place that explains why one of their own patterns has no such button.
     */
    public static String whyNot(DesignPattern pattern) {
        if (pattern == null) {
            return "Select a pattern first.";
        }
        if (pattern.isContributed()) {
            return "This is one of your own patterns, read from your folder. It is not in the "
                    + "library, so there is nobody else to report it to.";
        }
        if (REPOSITORY == null) {
            return "This build of OntoBoard does not record where its repository is, so there is "
                    + "no form to open. It was built without Maven resource filtering.";
        }
        return null;
    }

    /** {@code owner/name} on GitHub, as the pom declares it, or null when unfiltered. */
    public static String getRepository() {
        return REPOSITORY;
    }

    /**
     * The address for a pattern with this id and name, in the repository given.
     *
     * <p>No {@code labels=}: GitHub applies a label from the address only for somebody allowed to
     * triage, so for most reporters it would be dropped. The form carries its own label instead.
     */
    static String urlFor(String repository, String id, String name) {
        return "https://github.com/" + repository + "/issues/new?template=" + FORM
                + "&title=" + encode(titleFor(id, name))
                + "&" + PATTERN_FIELD + "=" + encode(id);
    }

    /**
     * The prefilled title: the pattern's name with its id after it, or the id alone.
     *
     * <p>Both, because each is the one the other lacks: the name is what a reader recognises,
     * the id is what is unique. Not both when they are the same text, which says nothing twice.
     */
    static String titleFor(String id, String name) {
        String shown = name == null ? "" : name.trim();
        return TITLE_PREFIX + (shown.isEmpty() || shown.equals(id) ? id : shown + " (" + id + ")");
    }

    /**
     * Percent-encodes every UTF-8 byte except RFC 3986's unreserved characters.
     *
     * <p>Not {@link java.net.URLEncoder}, which writes a space as {@code +} and leaves {@code *}
     * alone. GitHub reads either, but the page generator uses Python's
     * {@code urllib.parse.quote(text, safe="")}, which is exactly this rule, and the test pins both
     * to one string. Two encoders that merely both work drift apart without anybody noticing
     * which of them changed.
     */
    static String encode(String text) {
        StringBuilder out = new StringBuilder();
        for (byte raw : text.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = raw & 0xff;
            if ((unsigned >= 'A' && unsigned <= 'Z') || (unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= '0' && unsigned <= '9') || unsigned == '-'
                    || unsigned == '.' || unsigned == '_' || unsigned == '~') {
                out.append((char) unsigned);
            } else {
                out.append('%').append(HEX[unsigned >> 4]).append(HEX[unsigned & 0xf]);
            }
        }
        return out.toString();
    }

    /**
     * The pom's {@code <github.repo>}, as Maven filtered it into the build properties.
     *
     * <p>Lenient, as {@code Bench} is about the version and unlike {@code OdkScaffold} about
     * ROBOT's: a missing value only disables one button, and a library dialog that refused to open
     * over it would turn a missing link into a missing feature.
     */
    private static String readRepository() {
        InputStream in = PatternProblemReport.class.getClassLoader()
                .getResourceAsStream("ontoboard-build.properties");
        if (in == null) {
            return null;
        }
        try {
            Properties properties = new Properties();
            properties.load(in);
            String said = properties.getProperty("github.repo");
            return said == null || said.trim().isEmpty() || said.contains("${") ? null
                    : said.trim();
        } catch (IOException cannotRead) {
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Closing a classpath resource cannot usefully fail.
            }
        }
    }
}
