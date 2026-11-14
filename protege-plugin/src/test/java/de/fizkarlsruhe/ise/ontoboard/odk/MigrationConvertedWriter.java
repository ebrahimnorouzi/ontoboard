package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import org.junit.jupiter.api.Test;

/**
 * Writes the 1.135.2 fixture as OntoBoard's conversion leaves it - the configuration turned
 * into ODK's with the imported modules declared, the rule for the project's questions in
 * its own makefile - for the recording of
 * what ODK's own update then makes of it
 * ({@code tools/odk-goldens/generate.py migration --converted <folder>}):
 *
 * <pre>
 * cd protege-plugin
 * mvn -o -Dtest=MigrationConvertedWriter -Dontoboard.writeConverted=&lt;new folder&gt; test
 * </pre>
 *
 * <p>Does nothing without that property, so that it writes nowhere in an ordinary run.
 */
class MigrationConvertedWriter {

    @Test
    void writesTheConvertedProjectWhenAsked() throws Exception {
        String into = System.getProperty("ontoboard.writeConverted");
        assumeTrue(into != null && !into.trim().isEmpty(),
                "Writes the converted project only with -Dontoboard.writeConverted=<folder>.");
        File target = new File(into.trim()).getAbsoluteFile();
        String[] there = target.list();
        assertTrue(there == null || there.length == 0, target + " already holds files; the "
                + "converted project is written into a new or empty folder.");

        OdkMigrationTest.copy(MigrationFixtureTest.fixture("odk/migration/eggboil-1.135.2"),
                target);
        OdkMigration.convert(target, OdkMigration.plan(target));

        assertTrue(OdkMigration.isOntoBoards1x(target), "the Makefile is ODK's update's to write");
        assertTrue(OdkQuestions.isKeptIn(target));
    }
}
