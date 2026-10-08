package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writing the release in the formats the project asked for.
 *
 * <p>The defect was not an unread key, it was a disagreement OntoBoard created itself: its own
 * scaffold writes {@code export_formats} into every project it generates, and <i>Release…</i> then
 * wrote one RDF/XML file whatever the key said. NFDIcore, a real project, asks for {@code owl} and
 * {@code ttl}.
 */
class ExportFormatsTest {

    private static File projectWith(File root, String yaml) throws Exception {
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(ontology.mkdirs());
        Files.write(new File(ontology, "x-odk.yaml").toPath(),
                yaml.getBytes(StandardCharsets.UTF_8));
        return ontology;
    }

    /** What NFDIcore actually declares, copied from its own configuration. */
    @Test
    void aRealProjectGetsBothOfItsFormats(@TempDir File root) throws Exception {
        File ontology = projectWith(root, String.join("\n",
                "id: nfdicore",
                "release_artefacts:",
                "  - base",
                "  - full",
                "  - simple",
                "primary_release: full",
                "export_formats:",
                "  - owl",
                "  - ttl",
                ""));

        ExportFormats.Wanted wanted = ExportFormats.wantedBy(ontology);

        assertTrue(wanted.wasDeclared());
        assertEquals(2, wanted.getWritable().size(), wanted.getWritable().toString());
        assertEquals(ExportFormats.Format.OWL, wanted.getWritable().get(0));
        assertEquals(ExportFormats.Format.TTL, wanted.getWritable().get(1));
        assertTrue(wanted.getUnsupported().isEmpty());
    }

    /**
     * A project that says nothing gets what it always got.
     *
     * <p>The default is the behaviour before this existed, so reading the key cannot change what
     * an existing project's release looks like.
     */
    @Test
    void sayingNothingKeepsTheOldBehaviour(@TempDir File root) throws Exception {
        ExportFormats.Wanted wanted = ExportFormats.wantedBy(
                projectWith(root, "id: x\ntitle: No formats declared\n"));

        assertFalse(wanted.wasDeclared());
        assertEquals(1, wanted.getWritable().size());
        assertEquals(ExportFormats.Format.OWL, wanted.getWritable().get(0));
        assertEquals("owl", ExportFormats.DEFAULT);
    }

    /**
     * A format this cannot write is named, not dropped.
     *
     * <p>OBO Graphs JSON is produced by ROBOT's own converter, not by an OWL API document format,
     * and this writes releases through the OWL API. Skipping it silently would leave somebody
     * believing their release was complete.
     */
    @Test
    void aFormatThisCannotWriteIsReported(@TempDir File root) throws Exception {
        ExportFormats.Wanted wanted = ExportFormats.wantedBy(projectWith(root,
                "export_formats:\n  - owl\n  - json\n"));

        assertEquals(1, wanted.getWritable().size());
        assertEquals(1, wanted.getUnsupported().size());
        assertTrue(wanted.getUnsupported().get(0).startsWith("json"),
                wanted.getUnsupported().toString());
        assertTrue(wanted.getUnsupported().get(0).contains("ROBOT"),
                "it has to say who does produce it: " + wanted.getUnsupported());
    }

    /** And so is a word that is no ODK format at all. */
    @Test
    void anUnknownWordIsReported(@TempDir File root) throws Exception {
        ExportFormats.Wanted wanted = ExportFormats.wantedBy(projectWith(root,
                "export_formats:\n  - owl\n  - parquet\n"));

        assertEquals(1, wanted.getWritable().size());
        assertEquals(1, wanted.getUnsupported().size());
        assertTrue(wanted.getUnsupported().get(0).startsWith("parquet"));
    }

    /** A list of nothing writable still writes something. */
    @Test
    void aReleaseIsAlwaysWrittenInSomething(@TempDir File root) throws Exception {
        ExportFormats.Wanted wanted = ExportFormats.wantedBy(projectWith(root,
                "export_formats:\n  - json\n  - parquet\n"));

        assertEquals(1, wanted.getWritable().size());
        assertEquals(ExportFormats.Format.OWL, wanted.getWritable().get(0),
                "a release that writes no file at all is worse than one in the default format");
        assertEquals(2, wanted.getUnsupported().size());
    }

    /** Duplicates are written once. */
    @Test
    void aFormatAskedForTwiceIsWrittenOnce(@TempDir File root) throws Exception {
        ExportFormats.Wanted wanted = ExportFormats.wantedBy(projectWith(root,
                "export_formats:\n  - ttl\n  - owl\n  - ttl\n"));

        assertEquals(2, wanted.getWritable().size(), wanted.getWritable().toString());
    }

    /** A configuration problem must never stop a release being written. */
    @Test
    void anUnreadableProjectFallsBackRatherThanThrowing(@TempDir File root) throws Exception {
        assertEquals(1, ExportFormats.wantedBy(null).getWritable().size());
        assertEquals(1, ExportFormats.wantedBy(new File(root, "nothing")).getWritable().size());

        File broken = projectWith(new File(root, "broken"), "this: is: not: yaml\n");
        assertEquals(1, ExportFormats.wantedBy(broken).getWritable().size());

        File notAList = projectWith(new File(root, "scalar"), "export_formats: owl\n");
        assertEquals(1, ExportFormats.wantedBy(notAList).getWritable().size());
    }

    // ---------- the writers and the file names ----------

    /** Every format this offers can actually be written, except the one documented as not. */
    @Test
    void everyOfferedFormatHasAWriterExceptTheDocumentedOne() {
        for (ExportFormats.Format format : ExportFormats.Format.values()) {
            if (format == ExportFormats.Format.JSON) {
                assertNull(format.newWriter(), "OBO Graphs is ROBOT's, and is reported as such");
                continue;
            }
            assertNotNull(format.newWriter(), format.getKey());
            assertFalse(format.getDescription().trim().isEmpty(), format.getKey());
        }
    }

    /** Every ODK key maps to exactly one format, and nothing else does. */
    @Test
    void theKeysAreTheOnesOdkWrites() {
        for (ExportFormats.Format format : ExportFormats.Format.values()) {
            assertEquals(format, ExportFormats.byKey(format.getKey()));
            assertEquals(format, ExportFormats.byKey(format.getKey().toUpperCase(
                    java.util.Locale.ROOT)), "ODK's key, whatever case it is written in");
        }
        assertNull(ExportFormats.byKey("rdf"));
        assertNull(ExportFormats.byKey(null));
        assertNull(ExportFormats.byKey(""));
    }

    /**
     * One file name per format, so two formats never write over each other.
     *
     * <p>The whole point of the extension: writing owl and ttl to the same path would leave one
     * file whose contents depend on which was written last.
     */
    @Test
    void eachFormatGetsItsOwnFileName(@TempDir File root) {
        File dated = new File(root, "nfdicore.owl");

        assertEquals("nfdicore.owl",
                ExportFormats.named(dated, ExportFormats.Format.OWL).getName());
        assertEquals("nfdicore.ttl",
                ExportFormats.named(dated, ExportFormats.Format.TTL).getName());
        assertEquals("nfdicore.ofn",
                ExportFormats.named(dated, ExportFormats.Format.OFN).getName());

        java.util.Set<String> names = new java.util.HashSet<String>();
        for (ExportFormats.Format format : ExportFormats.Format.values()) {
            assertTrue(names.add(ExportFormats.named(dated, format).getName()),
                    "two formats would write to the same file: " + format);
        }
    }

    /** A name with no extension still gets one. */
    @Test
    void aNameWithoutAnExtensionGetsOne(@TempDir File root) {
        assertEquals("release.ttl",
                ExportFormats.named(new File(root, "release"), ExportFormats.Format.TTL).getName());
    }

    /** And a dotted stem keeps everything before the last dot. */
    @Test
    void aDottedNameKeepsItsStem(@TempDir File root) {
        assertEquals("nfdicore-3.0.ttl", ExportFormats.named(
                new File(root, "nfdicore-3.0.owl"), ExportFormats.Format.TTL).getName());
    }

    /** The order the project wrote them in is the order they are written. */
    @Test
    void theProjectsOrderIsKept(@TempDir File root) throws Exception {
        List<ExportFormats.Format> wanted = ExportFormats.wantedBy(projectWith(root,
                "export_formats:\n  - ttl\n  - owx\n  - owl\n")).getWritable();

        assertEquals(ExportFormats.Format.TTL, wanted.get(0));
        assertEquals(ExportFormats.Format.OWX, wanted.get(1));
        assertEquals(ExportFormats.Format.OWL, wanted.get(2));
    }
}
