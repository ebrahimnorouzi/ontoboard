package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ODK's own words for each key of a project's configuration, as Project configuration shows
 * them beside the keys: the docstrings of ODK v1.6.1's odk.py, which ODK's own documentation is
 * made from (src/main/odk/v1.6.1/schema/descriptions.tsv, generate.py schema).
 */
class OdkSchemaTest {

    @Test
    void aKeyHasOdksOwnWords() {
        assertEquals("Java args to pass to ROBOT at runtime, such as -Xmx6G",
                OdkSchema.help("robot_java_args"));
        assertEquals("Module type. Supported: slme, minimal, custom",
                OdkSchema.help("import_group.module_type"));
    }

    /** An item of a list is said as the list's items are: the index says nothing. */
    @Test
    void anItemOfAListHasTheWordsOfTheListsItems() {
        assertEquals(OdkSchema.help("import_group.products.module_type"),
                OdkSchema.help("import_group.products[2].module_type"));
        assertEquals("ontology project identifier / shorthand; e.g. go, obi, envo",
                OdkSchema.help("import_group.products[0].id"));
    }

    @Test
    void aKeyOdkDoesNotHaveHasNone() {
        assertNull(OdkSchema.help("no_such_key"));
        assertNull(OdkSchema.help(""));
        assertNull(OdkSchema.help(null));
    }

    /**
     * What the form says under a key: ODK's words for it; for a key ODK has no words for, those
     * of the nearest key it sits under that has some - robot_report's entries are a dictionary
     * ODK documents as a whole; else that ODK has none.
     */
    @Test
    void theLineUnderAKeyIsOdksWordsOrTheBlockItSitsIn() {
        assertEquals("robot_java_args: Java args to pass to ROBOT at runtime, such as -Xmx6G",
                OdkSchema.line("robot_java_args"));
        assertEquals("robot_report.fail_on sits under robot_report: "
                + OdkSchema.help("robot_report"), OdkSchema.line("robot_report.fail_on"));
        assertEquals("robot_report.report_on sits under robot_report: "
                + OdkSchema.help("robot_report"), OdkSchema.line("robot_report.report_on[1]"));
        assertEquals("ODK v1.6.1 has no description of pattern_group.",
                OdkSchema.line("pattern_group"));
    }

    /**
     * Every key Project configuration shows of ODK's own configurations - its sample configs
     * and the seeds of OntoBoard's tests - has a line; ODK's own words where ODK has them
     * (measured: 58 keys of the samples have none - uPheno's own configuration, keys of older
     * ODKs, fields without a docstring, robot_report's entries).
     */
    @Test
    void everyKeyTheFormShowsHasALine() throws Exception {
        List<String> texts = new ArrayList<String>();
        for (String name : Goldens.corpusNames()) {
            texts.add(Goldens.corpusConfig(name));
        }
        File[] seeds = new File(Goldens.root(), "configs").listFiles();
        for (File seed : seeds == null ? new File[0] : seeds) {
            texts.add(new String(Files.readAllBytes(seed.toPath()), StandardCharsets.UTF_8));
        }
        int keys = 0;
        int odks = 0;
        for (String text : texts) {
            List<OdkYaml.Entry> entries;
            try {
                entries = OdkYaml.entriesIn(text);
            } catch (OdkYaml.UnreadableException refused) {
                continue;
            }
            for (OdkYaml.Entry entry : entries) {
                keys++;
                String line = OdkSchema.line(entry.getPath());
                assertTrue(line != null && !line.isEmpty(), entry.getPath());
                odks += OdkSchema.help(entry.getPath()) != null ? 1 : 0;
            }
        }
        assertTrue(odks * 10 >= keys * 8, odks + " of " + keys + " keys with ODK's own words");
    }

    /**
     * Every key of OntoBoard's own seeds - the configurations OntoBoard writes - is said in
     * ODK's own words: its own, or those of the block it sits in (robot_report's entries).
     */
    @Test
    void everyKeyOfAConfigurationOntoBoardWritesHasOdksWords() throws Exception {
        List<String> texts = new ArrayList<String>();
        File[] seeds = new File(Goldens.root(), "configs").listFiles();
        for (File seed : seeds == null ? new File[0] : seeds) {
            texts.add(new String(Files.readAllBytes(seed.toPath()), StandardCharsets.UTF_8));
        }
        assertTrue(texts.size() >= 9, "the configurations: " + texts.size());
        List<String> without = new ArrayList<String>();
        int keys = 0;
        for (String text : texts) {
            List<OdkYaml.Entry> entries;
            try {
                entries = OdkYaml.entriesIn(text);
            } catch (OdkYaml.UnreadableException refused) {
                // The form shows none of such a file - one of ODK's samples declares a key twice.
                continue;
            }
            for (OdkYaml.Entry entry : entries) {
                keys++;
                String line = OdkSchema.line(entry.getPath());
                if ((line == null || line.contains("has no description"))
                        && !without.contains(entry.getPath())) {
                    without.add(entry.getPath());
                }
            }
        }
        assertEquals(new ArrayList<String>(), without, "of " + keys + " keys");
    }
}
