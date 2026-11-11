package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ODK's own sample configurations, seeded here and by ODK, compared file by file.
 *
 * <p>The eight configs of {@link SeedParityTest} are OntoBoard's kind of project. These 79 are
 * everybody else's: components, DOSDP patterns, SSSOM mappings, translations, subsets, custom
 * artefacts, plugins, every mirror type - the branches of ODK's templates that an OntoBoard
 * project never takes but an existing ODK project, opened in OntoBoard, does. For each, ODK's
 * own seed is kept as a manifest of paths and SHA-256 hashes.
 */
class SeedCorpusTest {

    static List<String> theSeventyNineConfigs() {
        return Goldens.corpusNames();
    }

    @Test
    void thereAreSeventyNineAndOdkTookThemAll() {
        assertEquals(79, Goldens.corpusNames().size());
        for (String name : Goldens.corpusNames()) {
            assertFalse(Goldens.manifest(name).startsWith("!refused"), name);
        }
    }

    @ParameterizedTest
    @MethodSource("theSeventyNineConfigs")
    void odksOwnConfigsRenderToTheirManifests(String name) {
        assertEquals(Goldens.manifest(name),
                manifestOf(OdkInstaller.renderSeed(Goldens.corpusConfig(name)).files()));
    }

    /** {@code <sha256>  <path>} per file, sorted by path: as {@code generate.py} wrote ODK's. */
    static String manifestOf(Map<String, byte[]> files) {
        StringBuilder manifest = new StringBuilder();
        for (Map.Entry<String, byte[]> file : new TreeMap<String, byte[]>(files).entrySet()) {
            manifest.append(sha256(file.getValue())).append("  ").append(file.getKey())
                    .append('\n');
        }
        return manifest.toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
