package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Python's rules for the values ODK's templates see.
 *
 * <p>ODK renders with Jinja2 over Python objects, and what its seed writes depends on rules that
 * Java does not share: {@code None} is a value that is defined and prints as "None", an attribute
 * of {@code None} is something else again - Undefined - which prints as nothing, booleans print
 * capitalised. Each test here is one such rule, checked against Jinja2 3.1.6 itself when this was
 * written ({@code jinja-report.md}, section 2), and several of them are visible in the bytes of a
 * seeded Makefile.
 */
class PyTest {

    @Test
    void noneIsFalsyAndPrintsNone() {
        assertFalse(Py.truthy(Py.NONE));
        assertEquals("None", Py.str(Py.NONE));
    }

    @Test
    void booleansPrintAsPythonDoes() {
        assertEquals("True", Py.str(Boolean.TRUE));
        assertEquals("False", Py.str(Boolean.FALSE));
    }

    /** {@code --retry 4}, never {@code --retry 4.0}. */
    @Test
    void integersPrintWithoutAFraction() {
        assertEquals("4", Py.str(4L));
        assertEquals("200", Py.str(200L));
    }

    @Test
    void aStringPrintsAsItself() {
        assertEquals("eggboil", Py.str("eggboil"));
    }

    /** {@code project.contact.email}: contact is None, or a string - either way, nothing. */
    @Test
    void anAttributeOfNoneOrOfAStringIsUndefined() {
        assertTrue(Py.attr(Py.NONE, "email") instanceof PyUndefined);
        assertTrue(Py.attr("jane@example.org", "email") instanceof PyUndefined);
        assertTrue(Py.attr(4L, "x") instanceof PyUndefined);
        assertTrue(Py.attr(Arrays.<Object>asList("a"), "x") instanceof PyUndefined);
    }

    @Test
    void undefinedPrintsEmptyIsFalsyAndIteratesEmpty() {
        Object undefined = Py.undefined("project.contact.email");

        assertEquals("", Py.str(undefined));
        assertFalse(Py.truthy(undefined));
        assertFalse(Py.iterate(undefined).iterator().hasNext());
    }

    /** Jinja2 raises here rather than printing nothing - a template bug must not pass unseen. */
    @Test
    void anAttributeOfUndefinedThrows() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Py.attr(Py.undefined("a.b"), "c"));

        assertTrue(thrown.getMessage().contains("a.b"), thrown.getMessage());
    }

    /** {@code project.robot_report.fail_on}: robot_report is a plain dict read with a dot. */
    @Test
    void aDictAnswersAttributeAccess() {
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("fail_on", "ERROR");

        assertEquals("ERROR", Py.attr(report, "fail_on"));
        assertTrue(Py.attr(report, "custom_sparql_checks") instanceof PyUndefined,
                "a key the dict does not have is Undefined, not None");
    }

    /** A dataclass field that holds None is there; a name the class never had is not. */
    @Test
    void anObjectsFieldMayHoldNoneAndAnUnknownNameIsUndefined() {
        PyObject project = new PyObject("OntologyProject");
        project.set("components", Py.NONE);
        project.set("id", "eggboil");

        assertSame(Py.NONE, Py.attr(project, "components"));
        assertEquals("eggboil", Py.attr(project, "id"));
        assertTrue(Py.attr(project, "config_hash") instanceof PyUndefined);
        assertEquals(Arrays.asList("components", "id"), new ArrayList<String>(project.fields()));
    }

    /** {@code env['ODK_VERSION']}. */
    @Test
    void aDictAnswersASubscript() {
        Map<String, Object> env = new LinkedHashMap<String, Object>();
        env.put("ODK_VERSION", "v1.6.1");

        assertEquals("v1.6.1", Py.item(env, "ODK_VERSION"));
        assertTrue(Py.item(env, "OTHER") instanceof PyUndefined);
    }

    /** {@code 'obo' in project.edit_format} is a substring test; on a list it is membership. */
    @Test
    void inIsMembershipInAListAndSubstringInAString() {
        assertTrue(Py.contains("obo", "obo"));
        assertTrue(Py.contains("my-obo-format", "obo"));
        assertFalse(Py.contains("owl", "obo"));
        assertTrue(Py.contains(Arrays.<Object>asList("full", "base"), "base"));
        assertFalse(Py.contains(Arrays.<Object>asList("full", "base"), "bas"));
        assertFalse(Py.contains(Py.undefined("x"), "base"), "nothing is in Undefined");
    }

    @Test
    void emptyStringEmptyListAndZeroAreFalsy() {
        assertFalse(Py.truthy(""));
        assertFalse(Py.truthy(Collections.emptyList()));
        assertFalse(Py.truthy(new LinkedHashMap<String, Object>()));
        assertFalse(Py.truthy(0L));
        assertFalse(Py.truthy(Boolean.FALSE));
        assertTrue(Py.truthy("false"), "a non-empty string is true, whatever it says");
        assertTrue(Py.truthy(Arrays.<Object>asList("")));
        assertTrue(Py.truthy(1L));
        assertTrue(Py.truthy(new PyObject("ImportGroup")), "an object is true");
    }

    @Test
    void equalityIsByValueAndNoneEqualsOnlyNone() {
        assertTrue(Py.eq("slme", "slme"));
        assertFalse(Py.eq("slme", "SLME"));
        assertTrue(Py.eq(4L, 4L));
        assertTrue(Py.eq(Py.NONE, Py.NONE));
        assertFalse(Py.eq(Py.NONE, ""));
        assertFalse(Py.eq(Py.undefined("x"), "custom"));
        assertFalse(Py.eq("true", Boolean.TRUE), "the string is not the boolean");
        assertTrue(Py.eq(Arrays.<Object>asList("a", "b"), Arrays.<Object>asList("a", "b")));
    }

    /** Never printed by ODK 1.6.1's templates, but if one ever is, it is Python's spelling. */
    @Test
    void aListPrintsAsPythonWritesIt() {
        List<Object> list = Arrays.<Object>asList("a", 1L, Boolean.TRUE, Py.NONE);

        assertEquals("['a', 1, True, None]", Py.str(list));
    }

    @Test
    void aListIteratesInOrderAndNoneDoesNotIterate() {
        List<Object> seen = new ArrayList<Object>();
        for (Object item : Py.iterate(Arrays.<Object>asList("ro", "iao"))) {
            seen.add(item);
        }

        assertEquals(Arrays.<Object>asList("ro", "iao"), seen);
        assertThrows(IllegalStateException.class, () -> Py.iterate(Py.NONE));
    }
}
