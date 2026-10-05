package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reading and editing an ODK project's YAML without rewriting it.
 *
 * <p>The design is a splice rather than a re-serialisation, and that was chosen by measurement:
 * loading a real {@code -odk.yaml} and dumping it back rewrites 28 of its 41 lines - 13 even
 * with indentation tuned - normalising a flow sequence, a three-space nested indent, CRLF to LF
 * and a missing final newline. A one-word change would arrive as a whole-file diff.
 *
 * <p>So the tests that matter are the ones about what is <em>not</em> touched, and about the
 * values that change meaning when written carelessly.
 */
class OdkYamlTest {

    /** The shape of a real ODK config: comments, nesting, a flow sequence, CRLF, no final newline. */
    private static final String REAL =
            "# Metadata\r\n"
            + "id: mwo\r\n"
            + "title: \"Materials Workflow Ontology\"\r\n"
            + "github_org: ISE-FIZKarlsruhe\r\n"
            + "repo: mwo\r\n"
            + "export_formats: [owl, ttl]\r\n"
            + "\r\n"
            + "# What goes in a release\r\n"
            + "release_artefacts:\r\n"
            + "  - base\r\n"
            + "  - full\r\n"
            + "import_group:\r\n"
            + "   products:\r\n"
            + "     - id: iao\r\n"
            + "       #mirror_from: http://example.org/iao.owl\r\n"
            + "robot_java_args: '-Xmx8G'\r\n"
            + "description: ";

    // ---------- reading ----------

    /** Every top-level key is listed, in file order, with its line. */
    @Test
    void theKeysAreListedInOrder() {
        List<OdkYaml.Entry> entries = OdkYaml.entriesIn(REAL);

        assertEquals("id", entries.get(0).getKey());
        assertEquals("mwo", entries.get(0).getValue());
        assertEquals(2, entries.get(0).getLine(), "1-based, so it can be shown beside the key");
        assertEquals("Materials Workflow Ontology", entries.get(1).getValue(),
                "the quotes are the file's, not the value's");
    }

    /** A structure is described rather than shown, and marked read-only. */
    @Test
    void structuresAreReadOnlyAndSayWhy() {
        for (OdkYaml.Entry entry : OdkYaml.entriesIn(REAL)) {
            if ("release_artefacts".equals(entry.getKey())) {
                assertEquals(OdkYaml.Editable.SEQUENCE, entry.getEditable());
                assertEquals("(2 items)", entry.getValue());
                assertTrue(entry.getEditable().getReason().contains("list"));
            }
            if ("import_group".equals(entry.getKey())) {
                assertEquals(OdkYaml.Editable.MAPPING, entry.getEditable());
            }
            if ("export_formats".equals(entry.getKey())) {
                assertEquals(OdkYaml.Editable.SEQUENCE, entry.getEditable(),
                        "a flow sequence is still a sequence");
            }
        }
    }

    /** A block scalar is refused: its span is the indicator and every indented line under it. */
    @Test
    void aBlockScalarIsReadOnly() {
        String text = "id: x\nnotes: |\n  first line\n  second line\n";

        OdkYaml.Entry notes = OdkYaml.entriesIn(text).get(1);

        assertEquals(OdkYaml.Editable.BLOCK, notes.getEditable());
        assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.withValue(text, "notes", "replaced"));
    }

    /** A duplicate key is refused outright, because YAML keeps both and ODK reads one. */
    @Test
    void aDuplicateKeyIsRefused() {
        OdkYaml.UnreadableException refused = assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.entriesIn("id: one\ntitle: a\nid: two\n"));

        assertTrue(refused.getMessage().contains("more than once"), refused.getMessage());
        assertTrue(refused.getMessage().contains("text editor"), refused.getMessage());
    }

    /** Something that is not a mapping, or not YAML at all, is refused rather than guessed at. */
    @Test
    void whatIsNotAConfigurationIsRefused() {
        assertThrows(OdkYaml.UnreadableException.class, () -> OdkYaml.entriesIn(""));
        assertThrows(OdkYaml.UnreadableException.class, () -> OdkYaml.entriesIn(null));
        assertThrows(OdkYaml.UnreadableException.class, () -> OdkYaml.entriesIn("- just\n- a list\n"));
        assertThrows(OdkYaml.UnreadableException.class, () -> OdkYaml.entriesIn("a: [unclosed\n"));
    }

    // ---------- the splice ----------

    /**
     * Changing one value changes one line and nothing else.
     *
     * <p>The whole reason for the design. Every comment, the blank line, the flow sequence, the
     * three-space nested indent, the CRLF endings and the absent final newline survive because
     * they are never re-serialised.
     */
    @Test
    void anEditChangesOneLineAndLeavesTheFileAlone() {
        String after = OdkYaml.withValue(REAL, "repo", "materials-workflow");

        assertEquals(countOf(REAL, "\r\n"), countOf(after, "\r\n"), "CRLF must survive");
        assertFalse(after.endsWith("\n"), "the missing final newline must survive");
        assertTrue(after.contains("# Metadata"), "comments must survive");
        assertTrue(after.contains("#mirror_from:"), "a commented-out nested line must survive");
        assertTrue(after.contains("[owl, ttl]"), "a flow sequence must not be expanded");
        assertTrue(after.contains("   products:"), "the three-space indent must survive");
        assertEquals(1, differingLines(REAL, after), "exactly one line may change");
        assertTrue(after.contains("repo: materials-workflow"), after);
    }

    /** An empty value gets a space, or the result is "description:NewText". */
    @Test
    void anEmptyValueGainsItsSpace() {
        String after = OdkYaml.withValue(REAL, "description", "An ontology");

        assertTrue(after.endsWith("description: An ontology"), "...actual tail: "
                + after.substring(Math.max(0, after.length() - 40)));
    }

    /** A key the file does not have is refused rather than appended by surprise. */
    @Test
    void anAbsentKeyIsRefused() {
        assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.withValue(REAL, "nonesuch", "x"));
    }

    // ---------- values that change meaning if written bare ----------

    /** Text that YAML would read as something else is quoted. */
    @Test
    void dangerousValuesAreQuoted() {
        for (String value : new String[] {"true", "no", "null", "~", "12:30", "has # hash",
                " leading", "trailing ", ""}) {
            String after = OdkYaml.withValue(REAL, "repo", value);
            List<OdkYaml.Entry> entries = OdkYaml.entriesIn(after);
            String readBack = null;
            for (OdkYaml.Entry entry : entries) {
                if ("repo".equals(entry.getKey())) {
                    readBack = entry.getValue();
                }
            }
            assertEquals(value, readBack, "writing " + quote(value) + " changed what it means");
        }
    }

    /** An ordinary word is not quoted, so the file does not drift towards noise. */
    @Test
    void ordinaryValuesAreLeftBare() {
        assertTrue(OdkYaml.withValue(REAL, "repo", "mwo-core").contains("repo: mwo-core"));
    }

    /** A value already quoted in the file stays quoted. */
    @Test
    void anAlreadyQuotedValueKeepsItsQuotes() {
        String after = OdkYaml.withValue(REAL, "title", "Something Else");

        assertTrue(after.contains("title: \"Something Else\""), after);
    }

    /**
     * An index from the parser is a codepoint index, not a char index.
     *
     * <p>Treating one as the other splices at the wrong offset in any file containing a
     * character outside the basic plane, corrupting bytes a long way from the edit.
     */
    @Test
    void aNonBmpCharacterDoesNotShiftTheSplice() {
        String withEmoji = "title: \"Ontology 🍕 project\"\nrepo: mwo\n";

        String after = OdkYaml.withValue(withEmoji, "repo", "changed");

        assertTrue(after.contains("Ontology 🍕 project"), "the earlier line is intact");
        assertTrue(after.contains("repo: changed"), after);
        assertEquals(1, differingLines(withEmoji, after));
    }

    /** Round-tripping with no change is the identity, which is the cheapest possible check. */
    @Test
    void writingBackTheSameValueChangesNothing() {
        assertEquals(REAL, OdkYaml.withValue(REAL, "repo", "mwo"));
        assertEquals(REAL, OdkYaml.withValue(REAL, "title", "Materials Workflow Ontology"));
    }

    private static String quote(String value) {
        return "'" + value + "'";
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static int differingLines(String before, String after) {
        String[] was = before.split("\r?\n", -1);
        String[] now = after.split("\r?\n", -1);
        int differing = Math.abs(was.length - now.length);
        for (int at = 0; at < Math.min(was.length, now.length); at++) {
            if (!was[at].equals(now[at])) {
                differing++;
            }
        }
        return differing;
    }
}
