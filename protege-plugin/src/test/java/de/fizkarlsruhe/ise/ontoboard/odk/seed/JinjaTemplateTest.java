package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The interpreter renders what Jinja2 renders, for the part of Jinja2 that ODK's templates use.
 *
 * <p>Every expected string here is Jinja2 3.1.6's own output for that template and those values
 * ({@code probe_render.py}, run when the tests were written). The interpreter is strict about the
 * rest: a tag, filter, test or method ODK does not use is refused where it stands, so that a
 * later ODK cannot render differently here without a test saying so.
 */
class JinjaTemplateTest {

    private static final Object N = Py.NONE;

    // ------------------------------------------------------------------ None and Undefined

    /** Why a seeded Makefile has lines for subsets and plugins the config never mentions. */
    @Test
    void noneIsDefined() {
        assertEquals("y", render("{% if x is defined %}y{% endif %}", "x", N));
        assertEquals("d", render("{% if d.k is defined %}d{% else %}u{% endif %}",
                "d", dict("k", N)));
        assertEquals("y", render("{% if u is not defined %}y{% else %}n{% endif %}"));
    }

    @Test
    void nonePrintsNoneAndIsFalse() {
        assertEquals("None", render("{{ x }}", "x", N));
        assertEquals("f", render("{% if x %}t{% else %}f{% endif %}", "x", N));
        assertEquals("n", render("{% if x is none %}n{% else %}u{% endif %}", "x", N));
        assertEquals("y", render("{% if x is not none %}y{% else %}n{% endif %}", "x", "v"));
    }

    /** {@code project.contact.email}: an attribute of None or of a string prints nothing. */
    @Test
    void undefinedPrintsNothingAndIsNotNone() {
        assertEquals("u", render("{% if x.a is none %}n{% else %}u{% endif %}", "x", N));
        assertEquals("[]", render("[{{ c.email }}]", "c", "jane@example.org"));
        assertEquals("[]", render("[{{ c.email }}]", "c", N));
        assertEquals("[]", render("[{% for x in u %}a{% endfor %}]"));
    }

    @Test
    void anAttributeOfUndefinedSaysWhere() {
        JinjaException refused = assertThrows(JinjaException.class,
                () -> render("\n{{ a.b.c }}", "a", N));

        assertEquals(2, refused.getLine());
        assertTrue(refused.getMessage().contains("t.jinja2:2"), refused.getMessage());
    }

    @Test
    void whatPythonRefusesIsRefused() {
        assertThrows(JinjaException.class, () -> render("{{ x.upper() }}", "x", N));
        assertThrows(JinjaException.class, () -> render("{% for x in n %}a{% endfor %}", "n", N));
        assertThrows(JinjaException.class, () -> render("{% if 'a' in n %}y{% endif %}", "n", N));
        assertThrows(JinjaException.class, () -> render("{{ n|length }}", "n", N));
    }

    // ------------------------------------------------------------------ filters

    @Test
    void defaultReplacesOnlyUndefinedUnlessToldOtherwise() {
        assertEquals("[]", render("[{{ ''|default('X') }}]"));
        assertEquals("[None]", render("[{{ x|default('X') }}]", "x", N));
        assertEquals("[X]", render("[{{ x|default('X', true) }}]", "x", N));
        assertEquals("[X]", render("[{{ missing|default('X') }}]"));
        assertEquals("[None]", render("[{{ d.fail_on|default('None') }}]", "d", dict()));
        assertEquals("[None]", render("[{{ d.fail_on|default('None') }}]", "d", dict("fail_on", N)));
        assertEquals("[]", render("[{{ t|default(i.upper()) }}]", "t", "", "i", "x"));
    }

    @Test
    void defaultMayBeABooleanOrAList() {
        assertEquals("y", render("{% if d.e|default(true) %}y{% else %}n{% endif %}", "d", dict()));
        assertEquals("n", render("{% if d.e|default(true) %}y{% else %}n{% endif %}",
                "d", dict("e", false)));
        assertEquals("a b ", render("{% for x in d.c|default(['a', 'b']) %}{{ x }} {% endfor %}",
                "d", dict()));
        assertEquals("a,b,", render("{% for x in p|default(['a', 'b'], true) %}{{ x }},{% endfor %}",
                "p", new ArrayList<Object>()));
    }

    /** {@code --annotate-inferred-axioms false}, not {@code False}. */
    @Test
    void lowerOfABooleanIsLowercase() {
        assertEquals("false", render("{{ f|default('false')|lower }}", "f", false));
        assertEquals("true", render("{{ f|default('false')|lower }}", "f", true));
        assertEquals("false", render("{{ m|default('false')|lower }}"));
    }

    @Test
    void upperAsAFilterAndAsAMethod() {
        assertEquals("EGGBOIL", render("{{ i | upper }}", "i", "eggboil"));
        assertEquals("EGGBOIL", render("{{ i.upper() }}", "i", "eggboil"));
        assertEquals("[NONE]", render("[{{ x|upper }}]", "x", N));
        assertEquals("[]", render("[{{ u|upper }}]"));
    }

    /** Python's upper() does not ask the machine which language it speaks; Java's does. */
    @Test
    void upperIsTheSameOnATurkishMachine() {
        Locale before = Locale.getDefault();
        Locale.setDefault(new Locale("tr", "TR"));
        try {
            assertEquals("EGGBOIL", render("{{ i | upper }}", "i", "eggboil"));
            assertEquals("EGGBOIL", render("{{ i.upper() }}", "i", "eggboil"));
            assertEquals("title", render("{{ i | lower }}", "i", "TITLE"));
        } finally {
            Locale.setDefault(before);
        }
    }

    /** A dot splits at dots: Java's own split would read it as "any character". */
    @Test
    void splitIsLiteral() {
        assertEquals("a", render("{{ 'a.b.owl'.split('.')|first }}"));
        assertEquals("parts", render("{{ f.split('.') | first }}", "f", "parts.owl"));
        assertEquals("parts", render("{{ f.split('.') | first }}", "f", "parts"));
    }

    @Test
    void startswithAndReplace() {
        assertEquals("c", render("{% if r.startswith('custom-') %}c{% else %}n{% endif %}",
                "r", "custom-x"));
        assertEquals("n", render("{% if r.startswith('custom-') %}c{% else %}n{% endif %}",
                "r", "base"));
        assertEquals("x", render("{{ r | replace('custom-','') }}", "r", "custom-x"));
        assertEquals("bbnbnb", render("{{ r|replace('a','b') }}", "r", "banana"));
    }

    @Test
    void lengthAndFirst() {
        assertEquals("3", render("{{ s|length }}", "s", "abc"));
        assertEquals("n", render("{% if s|length %}y{% else %}n{% endif %}", "s", ""));
        assertEquals("n", render("{% if u is defined and u|length %}y{% else %}n{% endif %}"));
        assertEquals("a", render("{{ xs|first }}", "xs", list("a", "b")));
    }

    // ------------------------------------------------------------------ tests and operators

    /** Identity, not truth: the text "true" and the number 1 are not True. */
    @Test
    void sameasTrueIsIdentity() {
        assertEquals("n", render("{% if t is sameas True %}y{% else %}n{% endif %}", "t", "true"));
        assertEquals("y", render("{% if t is sameas True %}y{% else %}n{% endif %}", "t", true));
        assertEquals("n", render("{% if t is sameas True %}y{% else %}n{% endif %}", "t", 1L));
    }

    @Test
    void iterableIsFalseForNoneAndTrueForWhatCanBeLooped() {
        assertEquals("n", render("{% if x is iterable %}i{% else %}n{% endif %}", "x", N));
        assertEquals("i", render("{% if x is iterable %}i{% else %}n{% endif %}", "x", list("a")));
        assertEquals("i", render("{% if x is iterable %}i{% else %}n{% endif %}", "x", "s"));
        assertEquals("i", render("{% if u is iterable %}i{% else %}n{% endif %}"));
    }

    @Test
    void isFalseOnlyForFalse() {
        assertEquals("f", render("{% if b is false %}f{% else %}t{% endif %}", "b", false));
        assertEquals("t", render("{% if b is false %}f{% else %}t{% endif %}", "b", N));
        assertEquals("t", render("{% if b is false %}f{% else %}t{% endif %}", "b", 0L));
        assertEquals("y", render("{% if g is defined and g is false and h %}y{% else %}n{% endif %}",
                "g", false, "h", true));
    }

    /** {@code env['ODK_VERSION'] or "Unknown"}: or hands back one of its operands. */
    @Test
    void orAndAndGiveBackAnOperand() {
        assertEquals("latest", render("{{ env['ODK_VERSION'] or \"latest\" }}",
                "env", dict("ODK_VERSION", "")));
        assertEquals("v1.6.1", render("{{ env['ODK_VERSION'] or \"latest\" }}",
                "env", dict("ODK_VERSION", "v1.6.1")));
        assertEquals("Unknown", render("{{ env['ODK_VERSION'] or \"Unknown\" }}",
                "env", dict("ODK_VERSION", N)));
        assertEquals("\"Unknown\"", render("{% if env is defined %}{{ env['ODK_VERSION'] or "
                + "'Unknown' }}{% else %}\"Unknown\"{% endif %}"));
        assertEquals("[y]", render("[{{ a and b }}]", "a", "x", "b", "y"));
        assertEquals("[]", render("[{{ a and b }}]", "a", "", "b", "y"));
    }

    @Test
    void inIsSubstringOrMembershipAndNothingIsInUndefined() {
        assertEquals("o", render("{% if 'obo' in f %}o{% else %}n{% endif %}", "f", "obo"));
        assertEquals("o", render("{% if 'base' in xs %}o{% else %}n{% endif %}",
                "xs", list("full", "base")));
        assertEquals("n", render("{% if 'base' in d.xs %}o{% else %}n{% endif %}", "d", dict()));
        assertEquals("n", render("{% if 'x' not in xs %}n{% else %}i{% endif %}", "xs", list("a")));
        assertEquals("y", render("{% if d.c is not defined or 'iri-range' in d.c %}y{% else %}n"
                + "{% endif %}", "d", dict()));
    }

    @Test
    void equalityWithAndWithoutSpaces() {
        assertEquals("e", render("{% if x=='edit' %}e{% else %}o{% endif %}", "x", "edit"));
        assertEquals("e", render("{% if x ==\"edit\" %}e{% else %}o{% endif %}", "x", "edit"));
        assertEquals("True", render("{{ 'a' == 'a' }}"));
        assertEquals("y", render("{% if 'no_mirror' != m %}y{% else %}n{% endif %}", "m", N));
    }

    @Test
    void tildeJoinsWhatItsOperandsPrint() {
        assertEquals("$(ONT)-base", render("{{ \"$(ONT)-\" ~ r }}", "r", "base"));
        assertEquals("aNone", render("{{ 'a' ~ x }}", "x", N));
    }

    @Test
    void notIsTruthiness() {
        assertEquals("empty", render("{% if not xs %}empty{% else %}some{% endif %}",
                "xs", new ArrayList<Object>()));
    }

    // ------------------------------------------------------------------ statements

    @Test
    void elifTakesTheFirstBranchThatHolds() {
        assertEquals("other", render("{% if a == '1' %}one{% elif a == '2' %}two{% elif 'x' %}"
                + "other{% else %}never{% endif %}", "a", "3"));
        assertEquals("two", render("{% if a == '1' %}one{% elif a == '2' %}two{% else %}no"
                + "{% endif %}", "a", "2"));
    }

    @Test
    void loopLastSeparates() {
        assertEquals("a || b", render("{% for v in xs %}{{ v }}{% if not loop.last %} || {% endif %}"
                + "{% endfor %}", "xs", list("a", "b")));
        assertEquals("1x 1y 2x 2y ", render("{% for a in xs %}{% for b in ys %}{{ a }}{{ b }} "
                + "{% endfor %}{% endfor %}", "xs", list("1", "2"), "ys", list("x", "y")));
    }

    /** A value set inside a loop lives for one turn of it, as in Jinja2. */
    @Test
    void setInsideAForIsNewEachTime() {
        assertEquals("[a][]", render("{% for v in xs %}{% if v == 'a' %}{% set s='a' %}{% endif %}"
                + "[{{ s }}]{% endfor %}", "xs", list("a", "b")));
        assertEquals("$(SRC);o.owl;", render("{% for x in xs %}{% if x=='edit' %}{% set input = "
                + "'$(SRC)' %}{% else %}{% set input = x %}{% endif %}{{ input }};{% endfor %}",
                "xs", list("edit", "o.owl")));
        assertEquals("xxx", render("{% set a = 'x' %}{{ a }}{% for i in xs %}{{ a }}{% endfor %}",
                "xs", list("1", "2")));
    }

    @Test
    void setMayUseAFilterOrATilde() {
        assertEquals("$(ONT)-base", render("{% set release = '$(ONT)-' ~ r %}{{ release }}",
                "r", "base"));
        assertEquals("$(ONT)-simple", render("{% if r.startswith('custom-') %}{% set release = r "
                + "| replace('custom-','') %}{% else %}{% set release = '$(ONT)-' ~ r %}{% endif %}"
                + "{{ release }}", "r", "simple"));
    }

    @Test
    void valuesPrintAsPythonPrintsThem() {
        assertEquals("--retry 4", render("--retry {{ n }}", "n", 4L));
        assertEquals("True", render("{{ b }}", "b", true));
        assertEquals("y", render("{%   if   x   %}y{%endif%}", "x", true));
    }

    // ------------------------------------------------------------------ strictness

    @ParameterizedTest
    @ValueSource(strings = {
        "{% macro m() %}{% endmacro %}",
        "{{ xs|join(',') }}",
        "{{ 'A'.lower() }}",
        "{% for v in xs %}{{ loop.index }}{% endfor %}",
        "{{ 1 + 1 }}",
        "{% include 'x' %}",
        "{% for a, b in xs %}{% endfor %}",
        "{% for v in xs if v %}{% endfor %}",
        "{{ x if y else z }}",
        "{{ x is odd }}",
        "{% set x %}text{% endset %}",
        "{{ xs[0] }}",
        "{{ (a) }}",
        "{% if x %}",
        "{% endif %}",
        "{% if x %}a{% endfor %}",
        "{{ x | }}",
        "{{ 'a\\nb' }}"
    })
    void whatOdkDoesNotUseIsRefusedWhereItStands(String template) {
        JinjaException refused = assertThrows(JinjaException.class,
                () -> JinjaTemplate.parse("t.jinja2", "line one\n" + template));

        assertEquals(2, refused.getLine(), refused.getMessage());
    }

    // ------------------------------------------------------------------ ODK's own templates

    /** Nothing in the templates ODK v1.6.1 ships is outside what the interpreter does. */
    @Test
    void everyTemplateOdkShipsIsRead() {
        int read = 0;
        for (TemplateSet.Entry entry : TemplateSet.bundled().entries()) {
            if (entry.isTemplate()) {
                JinjaTemplate.parse(entry.path(), new String(entry.bytes(), StandardCharsets.UTF_8));
                read++;
            }
        }

        assertEquals(14, read);
    }

    static List<String> seeds() {
        return Goldens.configNames();
    }

    /**
     * The whole of it at once: every file ODK writes from one template, for eight projects,
     * byte for byte - the Makefile among them, which uses nearly everything above.
     */
    @ParameterizedTest
    @MethodSource("seeds")
    void everyPlainTemplateRendersOdksBytes(String seed) {
        Map<String, byte[]> golden = Goldens.seedTree(seed);
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("project", OdkProjectModel.load(Goldens.config(seed)));
        context.put("env", dict("ODK_VERSION", OdkPin.VERSION));
        Map<String, byte[]> expected = new TreeMap<String, byte[]>();
        Map<String, byte[]> actual = new TreeMap<String, byte[]>();
        for (TemplateSet.Entry entry : TemplateSet.bundled().entries()) {
            if (!entry.isTemplate() || entry.path().startsWith("_dynamic")) {
                continue;
            }
            String path = entry.path().substring(0,
                    entry.path().length() - TemplateSet.SUFFIX.length());
            assertTrue(golden.containsKey(path), "ODK's seed of " + seed + " has no " + path);
            expected.put(path, golden.get(path));
            actual.put(path, JinjaTemplate.parse(entry.path(),
                    new String(entry.bytes(), StandardCharsets.UTF_8)).render(context)
                    .getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(10, expected.size());
        Goldens.assertTreeEquals(expected, actual);
    }

    // ------------------------------------------------------------------ helpers

    private static String render(String template, Object... namesAndValues) {
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            context.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return JinjaTemplate.parse("t.jinja2", template).render(context);
    }

    private static Map<String, Object> dict(Object... keysAndValues) {
        Map<String, Object> dict = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            dict.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return dict;
    }

    private static List<Object> list(Object... items) {
        return new ArrayList<Object>(Arrays.asList(items));
    }
}
