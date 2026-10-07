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

    // ---------- inside the structures ----------

    /** A real ODK import_group, shaped the way MWO's is. */
    private static final String NESTED = String.join("\n",
            "id: mwo",
            "release_artefacts:",
            "  - base",
            "  - full",
            "export_formats:",
            "  - owl",
            "  - ttl",
            "import_group:",
            "  annotation_properties:",
            "    - rdfs:label",
            "    - IAO:0000115",
            "  products:",
            "    - id: iao",
            "      module_type: custom",
            "    - id: nfdicore",
            "      mirror_from: https://example.org/nfdicore.owl",
            "      module_type: mirror",
            "robot_report:",
            "  use_labels: TRUE",
            "  fail_on: ERROR",
            "  report_on:",
            "    - edit",
            "") + "\n";

    private static OdkYaml.Entry at(String path) {
        for (OdkYaml.Entry entry : OdkYaml.entriesIn(NESTED)) {
            if (path.equals(entry.getPath())) {
                return entry;
            }
        }
        throw new AssertionError(path + " is not among " + OdkYaml.entriesIn(NESTED));
    }

    /**
     * Scalars inside a block and a list are reachable, and the structures still are not.
     *
     * <p>The reason the walk was ever shallow looked like a limit of the technique and was not:
     * editing splices a value's own span out of the text, and a scalar's span is its own
     * characters wherever it sits. A structure's span covers its contents, which is the thing
     * that cannot be replaced.
     */
    @Test
    void everyScalarIsReachableAtAnyDepth() {
        assertTrue(at("robot_report.fail_on").getEditable().isEditable());
        assertTrue(at("robot_report.use_labels").getEditable().isEditable());
        assertTrue(at("import_group.products[1].mirror_from").getEditable().isEditable());
        assertTrue(at("import_group.annotation_properties[0]").getEditable().isEditable());
        assertTrue(at("export_formats[1]").getEditable().isEditable());

        assertEquals(OdkYaml.Editable.MAPPING, at("import_group").getEditable());
        assertEquals(OdkYaml.Editable.SEQUENCE, at("robot_report.report_on").getEditable());
        assertEquals(OdkYaml.Editable.SEQUENCE, at("import_group.products").getEditable());
    }

    /** Editing a nested scalar changes one line, like a top-level one. */
    @Test
    void anestedEditChangesOneLine() {
        for (String path : new String[] {"robot_report.fail_on", "export_formats[0]",
            "import_group.products[1].module_type", "import_group.annotation_properties[1]"}) {
            String after = OdkYaml.withValue(NESTED, path, "CHANGED");
            assertEquals(1, differingLines(NESTED, after), "editing " + path);
            assertTrue(after.contains("CHANGED"), path);
        }
    }

    /**
     * The path addresses the right one of several identically named keys.
     *
     * <p>The trap this design had to avoid. {@code module_type} appears under every product and
     * {@code id} under each as well, so keying an edit by its leaf name would write one
     * product's value into another's.
     */
    @Test
    void aRepeatedLeafNameIsStillAddressedExactly() {
        String after = OdkYaml.withValue(NESTED, "import_group.products[0].module_type", "slme");

        assertTrue(after.contains("    - id: iao\n      module_type: slme"), after);
        assertTrue(after.contains("      module_type: mirror"),
                "the other product is untouched: " + after);
    }

    /** Depth is reported, so the dialog can show the nesting rather than four identical rows. */
    @Test
    void depthIsReported() {
        assertEquals(0, at("id").getDepth());
        assertEquals(1, at("robot_report.fail_on").getDepth());
        assertEquals(1, at("import_group.products").getDepth());
        assertEquals(2, at("import_group.products[0]").getDepth());
        assertEquals(3, at("import_group.products[0].id").getDepth());
    }

    /** A top-level key is a one-step path, so the old key-only calls keep working. */
    @Test
    void aTopLevelKeyIsItsOwnPath() {
        assertEquals("id", at("id").getPath());
        assertEquals("mwo", at("id").getValue());
        assertTrue(OdkYaml.withValue(NESTED, "id", "other").contains("id: other"));
    }

    /** A path naming nothing is refused, and says the path rather than a parse error. */
    @Test
    void aPathThatNamesNothingIsRefused() {
        for (String nowhere : new String[] {"nope", "robot_report.nope", "export_formats[9]",
            "id.deeper", "import_group.products[99].id", "export_formats[x]", ""}) {
            OdkYaml.UnreadableException refused = assertThrows(OdkYaml.UnreadableException.class,
                    () -> OdkYaml.withValue(NESTED, nowhere, "x"), "for " + nowhere);
            assertTrue(refused.getMessage().contains("no"), refused.getMessage());
        }
    }

    /** A structure is refused by path with the same reason it is refused at the top level. */
    @Test
    void aNestedStructureIsStillRefused() {
        assertTrue(assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.withValue(NESTED, "robot_report.report_on", "x"))
                .getMessage().contains("list"));
        assertTrue(assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.withValue(NESTED, "import_group", "x"))
                .getMessage().contains("nested block"));
    }

    /**
     * A duplicate inside a block is refused, not only one at the top level.
     *
     * <p>Two {@code products} inside {@code import_group} are as ambiguous as two at the top:
     * an edit would patch whichever the walk reached while ODK reads the other.
     */
    @Test
    void aDuplicateInsideABlockIsRefused() {
        String twice = String.join("\n", "id: x", "robot_report:", "  fail_on: ERROR",
                "  fail_on: WARN", "") + "\n";

        assertTrue(assertThrows(OdkYaml.UnreadableException.class,
                () -> OdkYaml.entriesIn(twice)).getMessage().contains("robot_report.fail_on"));
    }

    /**
     * A key that cannot be addressed unambiguously is shown and not offered.
     *
     * <p>A key containing a dot would make {@code a.b} mean two things. No ODK configuration has
     * one; guessing would mean an edit landing on a different key than the one clicked.
     */
    @Test
    void aKeyContainingAPathCharacterHasNoPath() {
        String awkward = String.join("\n", "id: x", "a.b: value", "") + "\n";

        for (OdkYaml.Entry entry : OdkYaml.entriesIn(awkward)) {
            if ("a.b".equals(entry.getKey())) {
                assertEquals("", entry.getPath(), "an ambiguous key gets no path");
                return;
            }
        }
        throw new AssertionError("the awkward key was not listed at all");
    }
}
