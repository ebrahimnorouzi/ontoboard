package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;

/**
 * The project OntoBoard 1.135.2 made, as it is recorded under
 * {@code src/test/resources/odk/migration/eggboil-1.135.2} (see the README beside it): a copy
 * for a test to work on. The code that wrote projects that way is gone; what it wrote is kept.
 */
public final class RecordedProject {

    private RecordedProject() {
    }

    /** Copies the recorded 1.135.2 project into {@code root}, and returns its edit file. */
    public static File of1352(File root) throws IOException {
        OdkMigrationTest.copy(MigrationFixtureTest.fixture("odk/migration/eggboil-1.135.2"), root);
        return new File(root, "src/ontology/eggboil-edit.owl");
    }
}
