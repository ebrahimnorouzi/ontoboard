package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The lexer cuts a template where Jinja2 cuts it.
 *
 * <p>Whitespace is most of what makes a rendered file ODK's or not: a {@code -} inside a tag
 * eats every blank, newline and no-break space on that side, a comment disappears with or without
 * its surroundings, a {@code raw} block is text, and the template's last newline is dropped. The
 * expectations below are Jinja2 3.1.6's own answers - for all fourteen of ODK's templates through
 * the committed fixture ({@code tools/odk-goldens/lexer_fixture.py}), and for each rule alone as
 * that same Jinja2 lexed it when these tests were written.
 */
class JinjaLexerTest {

    static List<String> theFourteenTemplates() {
        List<String> paths = new ArrayList<String>();
        for (TemplateSet.Entry entry : TemplateSet.bundled().entries()) {
            if (entry.isTemplate()) {
                paths.add(entry.path());
            }
        }
        return paths;
    }

    @ParameterizedTest
    @MethodSource("theFourteenTemplates")
    void theTextChunksAreJinjasOwn(String template) {
        String source = new String(TemplateSet.bundled().entry(template).bytes(),
                StandardCharsets.UTF_8);
        List<String> jinjas = new ArrayList<String>();
        for (Goldens.Chunk chunk : Goldens.lexerChunks(template)) {
            jinjas.add(chunk.kind + "@" + chunk.line + " " + chunk.text);
        }

        assertEquals(jinjas, cut(template, source));
    }

    @Test
    void aMinusEatsWhitespaceAcrossNewlines() {
        assertEquals(Arrays.asList("data@1 a", "block@2 if True", "data@4 b", "block@4 endif",
                "data@4 \nc"), cut("a  \n  {%- if True -%}  \n\n b {%- endif %}\nc"));
    }

    @Test
    void aMinusOnAnExpressionEatsToo() {
        assertEquals(Arrays.asList("data@1 a", "variable@1 x", "data@1 b"), cut("a {{- x -}} b"));
    }

    @Test
    void aPlusKeepsWhatIsThere() {
        assertEquals(Arrays.asList("data@1 a \n", "block@2 if y", "data@2 \n b", "block@3 endif"),
                cut("a \n{%+ if y +%}\n b{% endif %}"));
    }

    @Test
    void aCommentWithMinusesJoinsItsNeighbours() {
        assertEquals(Arrays.asList("data@1 ab"), cut("a\n{#- c -#}\nb"));
    }

    @Test
    void aPlainCommentLeavesItsLineEmpty() {
        assertEquals(Arrays.asList("data@1 a\n\nb"), cut("a\n{# c #}\nb"));
    }

    @Test
    void oneTrailingNewlineIsDropped() {
        assertEquals(Arrays.asList("data@1 x"), cut("x\n"));
        assertEquals(Arrays.asList("data@1 x\n"), cut("x\n\n"));
        assertEquals(new ArrayList<String>(), cut("\n"));
        assertEquals(new ArrayList<String>(), cut(""));
    }

    /** ODK's own CONTRIBUTING template is CRLF; what it renders is not. */
    @Test
    void carriageReturnsBecomeNewlines() {
        assertEquals(Arrays.asList("data@1 a\nb\nc"), cut("a\r\nb\rc\n"));
    }

    /** {@code ${{ secrets.GITHUB_TOKEN }}} in a workflow is GitHub's syntax, not Jinja's. */
    @Test
    void aRawBlockIsText() {
        assertEquals(Arrays.asList("data@1 ${{ secrets.X }}"),
                cut("{% raw %}${{ secrets.X }}{% endraw %}"));
        assertEquals(Arrays.asList("data@1 a{{ x }}b"),
                cut("a \n{%- raw -%}\n {{ x }} \n{%- endraw -%}\n b"));
    }

    @Test
    void aQuotedDelimiterDoesNotEndATag() {
        assertEquals(Arrays.asList("variable@1 'a %} b'", "data@1 x"), cut("{{ 'a %} b' }}x"));
        assertEquals(Arrays.asList("block@1 if x == \"}}\"", "data@1 y", "block@1 endif"),
                cut("{% if x == \"}}\" %}y{% endif %}"));
    }

    @Test
    void bracesInsideAnExpressionAreBalancedFirst() {
        assertEquals(Arrays.asList("variable@1 {'a': 1}['a']", "data@1 z"),
                cut("{{ {'a': 1}['a'] }}z"));
    }

    /** run.sh's eight no-break spaces stay; beside a {@code -} they are whitespace like any. */
    @Test
    void aNoBreakSpaceIsTextAndIsWhitespaceToAMinus() {
        assertEquals(Arrays.asList("data@1 a b"), cut("a b"));
        assertEquals(Arrays.asList("data@1 x", "block@1 if y", "data@1 z", "block@1 endif"),
                cut("x {%- if y %}z{% endif %}"));
        assertEquals(Arrays.asList("block@1 if y", "data@1 z", "block@1 endif"),
                cut("{% if y -%} z{% endif %}"));
        assertEquals(Arrays.asList("block@1 if y", "data@1 z", "block@1 endif"),
                cut("{% if y -%}\f\u001cz{% endif %}"));
    }

    @Test
    void aBraceThatOpensNothingIsText() {
        assertEquals(Arrays.asList("data@1 a { b } c {"), cut("a { b } c {"));
    }

    @Test
    void everyTagKnowsItsLine() {
        assertEquals(Arrays.asList("data@1 l1\nl2 ", "variable@2 v", "data@2 \n", "block@3 if a",
                "data@3 \n\n", "variable@5 w", "block@5 endif"),
                cut("l1\nl2 {{ v }}\n{% if a %}\n\n{{ w }}{% endif %}"));
    }

    @Test
    void whatIsNotClosedIsRefusedWithItsLine() {
        assertEquals(2, refused("a\n{% if x").getLine());
        assertEquals(2, refused("a\n{# c").getLine());
        assertEquals(2, refused("a\n{% raw %} x").getLine());
        JinjaException unclosed = refused("a\n\n{{ 'x }}");
        assertEquals(3, unclosed.getLine());
        assertTrue(unclosed.getMessage().contains("t.jinja2:3"), unclosed.getMessage());
    }

    private static JinjaException refused(String source) {
        return assertThrows(JinjaException.class, () -> JinjaLexer.tokenize("t.jinja2", source));
    }

    private static List<String> cut(String source) {
        return cut("t.jinja2", source);
    }

    /** The token stream, one line each: {@code kind@line text}. */
    private static List<String> cut(String name, String source) {
        List<String> said = new ArrayList<String>();
        for (JinjaLexer.Token token : JinjaLexer.tokenize(name, source)) {
            said.add(token.kind.name().toLowerCase(java.util.Locale.ROOT) + "@" + token.line + " "
                    + token.text);
        }
        return said;
    }
}
