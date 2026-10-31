package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The address that reports a problem with a pattern, and the three things it has to agree with:
 * the pom it takes the repository from, the issue form it opens, and the web pages that link to
 * the same form.
 *
 * <p>Each of those is a file nothing compiles. A renamed form, a renamed field or a second copy of
 * the repository would each leave a button that opens the wrong page and a build that stays green
 * - which is how the old form's link to {@code ise-fizkarlsruhe.github.io/ontoboard} came to be a
 * 404 that nobody noticed.
 */
class PatternProblemReportTest {

    /** The repository root: the module is {@code protege-plugin/} and the forms sit beside it. */
    private static File repositoryRoot() {
        return new File(System.getProperty("user.dir")).getAbsoluteFile().getParentFile();
    }

    private static String read(File file) throws Exception {
        assertTrue(file.isFile(), "missing: " + file);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** The query string, decoded, so a test can say what a browser will hand GitHub. */
    private static Map<String, String> query(String url) throws Exception {
        Map<String, String> parameters = new HashMap<String, String>();
        for (String pair : new URI(url).getRawQuery().split("&")) {
            int equals = pair.indexOf('=');
            parameters.put(pair.substring(0, equals),
                    URLDecoder.decode(pair.substring(equals + 1), "UTF-8"));
        }
        return parameters;
    }

    /**
     * The repository is the pom's {@code <github.repo>}, carried through the filtered build
     * properties - not a literal in Java that can disagree with the one {@code update.properties}
     * is built from.
     */
    @Test
    void theRepositoryIsTheOneThePomNames() throws Exception {
        Matcher declared = Pattern.compile("<github\\.repo>\\s*([^<\\s]+)\\s*</github\\.repo>")
                .matcher(read(new File("pom.xml")));
        assertTrue(declared.find(), "pom.xml declares no <github.repo>");

        assertEquals(declared.group(1), PatternProblemReport.getRepository(),
                "ontoboard-build.properties did not carry the pom's <github.repo> - "
                        + "was it filtered?");
    }

    /**
     * The exact address for one shipped pattern. A name with brackets and spaces in it, so the
     * encoding is exercised by real data, and this string is the one
     * {@code tools/generate-pattern-pages.py} writes onto that pattern's page.
     */
    @Test
    void theAddressForAShippedPattern() {
        DesignPattern sga = PatternLibrary.find("spatial-graph-adapter-sga");
        assertNotNull(sga, "the pattern this test is about is no longer in the library");

        assertEquals("https://github.com/" + PatternProblemReport.getRepository()
                        + "/issues/new?template=pattern-problem.yml"
                        + "&title=%5Bpattern%20problem%5D%20Spatial%20Graph%20Adapter%20%28SGA%29"
                        + "%20Pattern%20%28spatial-graph-adapter-sga%29"
                        + "&pattern=spatial-graph-adapter-sga",
                PatternProblemReport.urlFor(sga));
        assertNull(PatternProblemReport.whyNot(sga));
    }

    /**
     * Whatever a pattern is called, the address is one a browser opens and the title survives the
     * round trip: an ampersand or a hash in a name must not end the title early, and a plus must
     * not come back as a space.
     */
    @Test
    void anyNameMakesAnAddressThatCarriesItIntact() throws Exception {
        String awkward = "Ünïcode & <angle> [square] 50% + #hash / slash? a=b 𝔸";
        String url = PatternProblemReport.urlFor("owner/name", "x-1", awkward);

        URI parsed = new URI(url);
        assertEquals("github.com", parsed.getHost());
        assertEquals("/owner/name/issues/new", parsed.getPath());
        Map<String, String> parameters = query(url);
        assertEquals(PatternProblemReport.FORM, parameters.get("template"));
        assertEquals("[pattern problem] " + awkward + " (x-1)", parameters.get("title"));
        assertEquals("x-1", parameters.get(PatternProblemReport.PATTERN_FIELD));
        assertEquals(3, parameters.size(), "nothing else in the address: " + parameters);
    }

    /**
     * RFC 3986's rule, byte for byte the same as Python's {@code quote(text, safe="")}: only the
     * unreserved characters pass, a space is {@code %20} rather than {@code +}, and the hex is
     * upper case. The expected strings were printed by Python 3.12's urllib for these inputs.
     */
    @Test
    void theEncodingIsTheOneTheWebPagesUse() {
        assertEquals("a%20b%2Bc%26d%3De%2Ff~g_h.i-j%C3%A9%2A",
                PatternProblemReport.encode("a b+c&d=e/f~g_h.i-jé*"));
        assertEquals("%F0%9D%94%B8", PatternProblemReport.encode("𝔸"));
        assertEquals("", PatternProblemReport.encode(""));
    }

    /** The name and the id, each once. */
    @Test
    void theTitleNamesThePatternOnce() {
        assertEquals("[pattern problem] Agent Role Pattern (agent-role)",
                PatternProblemReport.titleFor("agent-role", " Agent Role Pattern "));
        assertEquals("[pattern problem] x", PatternProblemReport.titleFor("x", "x"));
        assertEquals("[pattern problem] x", PatternProblemReport.titleFor("x", "  "));
        assertEquals("[pattern problem] x", PatternProblemReport.titleFor("x", null));
    }

    /**
     * One of the user's own patterns is not in the library, so there is nobody to report it to,
     * and the button that would says why rather than simply greying out.
     */
    @Test
    void aContributedPatternHasNowhereToBeReported() {
        DesignPattern mine = new DesignPattern("mine", "Mine", "yours", "me", "", "", "", "", "",
                "", new File("mine.ttl"));

        assertNull(PatternProblemReport.urlFor(mine));
        assertTrue(PatternProblemReport.whyNot(mine).contains("your own"),
                PatternProblemReport.whyNot(mine));
        assertNull(PatternProblemReport.urlFor(null));
        assertNotNull(PatternProblemReport.whyNot(null));
    }

    /**
     * The form the address names exists, has the field the pattern goes into, and starts its
     * title the same way - so a report opened from the dialog and one opened from the issue
     * chooser look alike in the issue list.
     */
    @Test
    void theFormItOpensExistsAndHasThePatternField() throws Exception {
        String form = read(new File(repositoryRoot(),
                ".github/ISSUE_TEMPLATE/" + PatternProblemReport.FORM));

        assertTrue(Pattern.compile("(?m)^\\s*id:\\s*" + PatternProblemReport.PATTERN_FIELD
                + "\\s*$").matcher(form).find(),
                "no field with id '" + PatternProblemReport.PATTERN_FIELD + "' in the form, so "
                        + "GitHub would drop the prefilled pattern without saying so");
        assertTrue(form.contains("title: \"" + PatternProblemReport.TITLE_PREFIX + "\""),
                "the form's default title is not '" + PatternProblemReport.TITLE_PREFIX + "'");
    }

    /**
     * The web pages build the same address: the same form, title and field, and the same
     * encoder. A guard on the Python source rather than a run of it, because the Maven build has
     * no Python - but a renamed form fails here instead of on a reader's click.
     */
    @Test
    void theWebPagesOpenTheSameForm() throws Exception {
        String generator = read(new File(repositoryRoot(), "tools/generate-pattern-pages.py"));

        assertTrue(generator.contains("\"" + PatternProblemReport.FORM + "\""),
                "the page generator does not name " + PatternProblemReport.FORM);
        assertTrue(generator.contains("\"" + PatternProblemReport.TITLE_PREFIX + "\""),
                "the page generator titles reports differently");
        assertTrue(generator.contains("\"&" + PatternProblemReport.PATTERN_FIELD + "=\""),
                "the page generator fills a different field");
        assertTrue(generator.contains("quote(") && generator.contains("safe=\"\""),
                "the page generator encodes differently from encode()");
    }
}
