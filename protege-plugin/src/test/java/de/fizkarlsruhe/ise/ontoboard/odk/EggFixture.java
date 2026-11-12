package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * The project ODK's make ran in for the equivalence goldens, written to a folder: the eggboil
 * seed with the overlay's two files laid over it.
 */
final class EggFixture {

    /** What {@code make tmp/merged-eggboil-edit.ofn} left in tmp/, as the image wrote it. */
    static final String[] RECORDED_TMP = {"eggboil-preprocess.owl", "merged-eggboil-edit.ofn"};

    private EggFixture() {
    }

    /**
     * Writes the project under {@code top} and returns its {@code src/ontology}, where make runs.
     *
     * @param withRecordedTmp whether tmp/ gets the two files ODK's make wrote before its queries
     */
    static File write(File top, boolean withRecordedTmp) throws IOException {
        for (Map.Entry<String, byte[]> file : Goldens.eggProject().entrySet()) {
            File to = new File(top, file.getKey());
            Files.createDirectories(to.getParentFile().toPath());
            Files.write(to.toPath(), file.getValue());
        }
        File ontology = new File(top, "src/ontology");
        Files.createDirectories(new File(ontology, "tmp").toPath());
        if (withRecordedTmp) {
            for (String name : RECORDED_TMP) {
                Files.write(new File(ontology, "tmp/" + name).toPath(),
                        Goldens.equivalence("test_fast/files/src/ontology/tmp/" + name));
            }
        }
        return ontology;
    }
}
