package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Splitting a typed argument line the way a shell would.
 *
 * <p>The reason this is not {@code split(" ")}: every interesting path on this platform has a
 * space in it. {@code C:\Users\someone\My Documents\x.owl} split naively becomes three
 * arguments, and the script blames itself for the nonsense it was handed.
 */
class ScriptArgumentsTest {

    /** The ordinary case. */
    @Test
    void whitespaceSeparates() {
        assertEquals(Arrays.asList("--verbose", "out.owl"),
                ScriptArguments.parse("--verbose out.owl"));
        assertEquals(Arrays.asList("a", "b", "c"), ScriptArguments.parse("  a   b\tc  "));
    }

    /** Nothing typed is no arguments, not one empty one. */
    @Test
    void anEmptyLineIsNoArguments() {
        assertTrue(ScriptArguments.parse("").isEmpty());
        assertTrue(ScriptArguments.parse("   ").isEmpty());
        assertTrue(ScriptArguments.parse(null).isEmpty());
    }

    /** A quoted path with a space in it is one argument. The whole point. */
    @Test
    void quotesHoldAPathTogether() {
        assertEquals(Arrays.asList("C:\\Users\\me\\My Documents\\x.owl"),
                ScriptArguments.parse("\"C:\\Users\\me\\My Documents\\x.owl\""));
        assertEquals(Arrays.asList("one two", "three"),
                ScriptArguments.parse("'one two' three"));
    }

    /**
     * A backslash in a double-quoted string is literal unless it escapes a quote.
     *
     * <p>Deliberately not full POSIX. A Windows path is mostly backslashes, and treating each as
     * an escape would eat them: {@code "C:\Users\new"} would come out with a tab and a newline
     * in it.
     */
    @Test
    void windowsPathsSurviveDoubleQuotes() {
        assertEquals(Arrays.asList("C:\\Users\\new\\temp"),
                ScriptArguments.parse("\"C:\\Users\\new\\temp\""));
        assertEquals(Arrays.asList("say \"hi\""), ScriptArguments.parse("\"say \\\"hi\\\"\""));
        assertEquals(Arrays.asList("back\\slash"), ScriptArguments.parse("\"back\\\\slash\""));
    }

    /** Single quotes escape nothing, as in a shell. */
    @Test
    void singleQuotesAreLiteral() {
        assertEquals(Arrays.asList("a\\b"), ScriptArguments.parse("'a\\b'"));
        assertEquals(Arrays.asList("it\"s"), ScriptArguments.parse("'it\"s'"));
    }

    /** Quotes can start and stop inside one argument, as in a shell. */
    @Test
    void quotingCanBePartial() {
        assertEquals(Arrays.asList("--out=my file.owl"),
                ScriptArguments.parse("--out=\"my file.owl\""));
        assertEquals(Arrays.asList("abc"), ScriptArguments.parse("a\"b\"c"));
    }

    /** An explicitly empty argument is an argument. */
    @Test
    void anEmptyQuotedStringIsAnArgument() {
        assertEquals(Arrays.asList("a", "", "b"), ScriptArguments.parse("a \"\" b"));
    }

    /** A backslash outside quotes escapes whatever follows. */
    @Test
    void aBackslashEscapesOutsideQuotes() {
        assertEquals(Arrays.asList("a b"), ScriptArguments.parse("a\\ b"));
        assertEquals(Arrays.asList("\"quoted\""), ScriptArguments.parse("\\\"quoted\\\""));
    }

    /**
     * An unclosed quote is refused, not closed for the user.
     *
     * <p>The command shown before running is the basis on which it is approved. Guessing where a
     * quote ends would run something the user did not write, under an approval they gave to
     * something else.
     */
    @Test
    void anUnclosedQuoteIsRefused() {
        ScriptArguments.Malformed failure = assertThrows(ScriptArguments.Malformed.class,
                () -> ScriptArguments.parse("--out \"my file"));
        assertTrue(failure.getMessage().contains("unclosed double quote"), failure.getMessage());

        assertThrows(ScriptArguments.Malformed.class, () -> ScriptArguments.parse("'nope"));
    }

    /** A trailing backslash escapes nothing and is refused rather than dropped. */
    @Test
    void aTrailingBackslashIsRefused() {
        assertTrue(assertThrows(ScriptArguments.Malformed.class,
                () -> ScriptArguments.parse("a b\\")).getMessage().contains("backslash"));
    }

    /**
     * A shell metacharacter is just a character.
     *
     * <p>It has to be, and this is the test that says so: the arguments become separate elements
     * of a ProcessBuilder command list, never a string a shell re-splits, so a semicolon cannot
     * start a second command.
     */
    @Test
    void shellMetacharactersAreNotSpecial() {
        assertEquals(Arrays.asList("a;rm", "-rf", "/"), ScriptArguments.parse("a;rm -rf /"));
        assertEquals(Arrays.asList("$(whoami)"), ScriptArguments.parse("'$(whoami)'"));
        assertEquals(Arrays.asList("a&&b"), ScriptArguments.parse("a&&b"));
    }

    // ---------- what the user is shown ----------

    /** The count and the brackets are what tell somebody their quoting worked. */
    @Test
    void theDescriptionShowsEachArgument() {
        assertEquals("no arguments", ScriptArguments.describe(null));
        assertEquals("no arguments", ScriptArguments.describe(Arrays.<String>asList()));
        assertEquals("1 argument: [a b]", ScriptArguments.describe(Arrays.asList("a b")));
        assertEquals("2 arguments: [a] [b]", ScriptArguments.describe(Arrays.asList("a", "b")));
    }

    // ---------- and into the command ----------

    /** Arguments reach the container command as separate elements. */
    @Test
    void theContainerCommandCarriesThem(@org.junit.jupiter.api.io.TempDir java.io.File root)
            throws Exception {
        java.io.File ontology = new java.io.File(new java.io.File(root, "src"), "ontology");
        java.io.File scripts = new java.io.File(new java.io.File(root, "src"), "scripts");
        assertTrue(ontology.mkdirs() && scripts.mkdirs());
        java.nio.file.Files.write(new java.io.File(scripts, "x.sh").toPath(),
                "#!/bin/sh\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ProjectScripts.Script script = ProjectScripts.in(ontology).get(0);

        ScriptRun.Plan plan = ScriptRun.planFor(script, ontology, "docker", null, false,
                Arrays.asList("--out", "my file.owl"));

        List<String> command = plan.getCommand();
        assertEquals("my file.owl", command.get(command.size() - 1),
                "the space must not have split it: " + command);
        assertEquals("--out", command.get(command.size() - 2));
        assertTrue(plan.asCommandLine().contains("\"my file.owl\""),
                "quoted only for display: " + plan.asCommandLine());
    }

    /** No arguments leaves the command exactly as it was. */
    @Test
    void noArgumentsChangesNothing(@org.junit.jupiter.api.io.TempDir java.io.File root)
            throws Exception {
        java.io.File ontology = new java.io.File(new java.io.File(root, "src"), "ontology");
        java.io.File scripts = new java.io.File(new java.io.File(root, "src"), "scripts");
        assertTrue(ontology.mkdirs() && scripts.mkdirs());
        java.nio.file.Files.write(new java.io.File(scripts, "x.sh").toPath(),
                "#!/bin/sh\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ProjectScripts.Script script = ProjectScripts.in(ontology).get(0);

        assertEquals(ScriptRun.planFor(script, ontology, "docker", null, false).getCommand(),
                ScriptRun.planFor(script, ontology, "docker", null, false,
                        Arrays.<String>asList()).getCommand());
        assertEquals(ScriptRun.planFor(script, ontology, "docker", null, false).getCommand(),
                ScriptRun.planFor(script, ontology, "docker", null, false, null).getCommand());
    }
}
