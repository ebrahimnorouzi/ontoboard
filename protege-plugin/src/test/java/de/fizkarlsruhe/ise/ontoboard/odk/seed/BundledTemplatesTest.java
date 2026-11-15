package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * ODK's own templates, in the bundle exactly as the pinned ODK image holds them.
 *
 * <p>A project OntoBoard creates without Docker is rendered from these, and the promise is that
 * every file equals what {@code odk.py seed} writes. That starts with the templates being ODK's
 * bytes: one template edited "to make it render", or one checkout that turned line endings into
 * CRLF, and the promise is broken somewhere nobody is looking.
 */
class BundledTemplatesTest {

    /** The image's /tools/templates holds 28 files: ODK's 27, and ROBOT's report profile. */
    @Test
    void theIndexListsTheImagesTwentyEightFiles() {
        List<TemplateSet.Entry> all = TemplateSet.bundled().entries();

        assertEquals(28, all.size());
        int templates = 0;
        List<String> paths = new ArrayList<String>();
        for (TemplateSet.Entry entry : all) {
            paths.add(entry.path());
            if (entry.isTemplate()) {
                templates++;
            }
        }
        assertEquals(14, templates, "fourteen are Jinja2 templates, fourteen are copied as they are");
        assertTrue(paths.contains("src/ontology/profile.txt"),
                "the image adds ROBOT's standard profile, which ODK's repository does not hold");
        assertTrue(paths.contains("src/ontology/tmp/.gitkeep"), "an empty file is a file too");
        assertTrue(paths.contains("src/ontology/Makefile.jinja2"));
        assertEquals(paths, new ArrayList<String>(new TreeSet<String>(paths)),
                "listed in one fixed order, so two machines install in the same order");
    }

    /**
     * Each bundled file hashes to what the image said when it was copied out.
     *
     * <p>This is the test that catches a Windows checkout: with {@code core.autocrlf=true} and no
     * {@code -text} attribute every template comes back with CRLF, and all of them fail here.
     */
    @Test
    void everyBundledFileIsTheImagesByte() throws Exception {
        for (TemplateSet.Entry entry : TemplateSet.bundled().entries()) {
            assertEquals(entry.sha256(), sha256(entry.bytes()), entry.path());
        }
    }

    /**
     * ODK's own repository keeps this one template with CRLF, and the image has it so.
     *
     * <p>Kept as it is: the renderer normalises line endings as Jinja2 does, and the seeded
     * CONTRIBUTING.md is LF. "No carriage return anywhere" would be the wrong rule to check.
     */
    @Test
    void theOneTemplateOdkKeepsWithCrlfIsKeptSo() {
        byte[] contributing = TemplateSet.bundled().entry("CONTRIBUTING.md.jinja2").bytes();

        assertEquals(86, count(contributing, (byte) '\r'));
        for (TemplateSet.Entry entry : TemplateSet.bundled().entries()) {
            if (!entry.path().equals("CONTRIBUTING.md.jinja2")) {
                assertEquals(0, count(entry.bytes(), (byte) '\r'), entry.path());
            }
        }
    }

    /** The two files the image marks executable are the two a seeded project has executable. */
    @Test
    void theExecutableOnesAreTheImages() {
        List<String> executable = new ArrayList<String>();
        for (TemplateSet.Entry entry : TemplateSet.bundled().entries()) {
            if (entry.isExecutable()) {
                executable.add(entry.path());
            }
        }

        assertEquals(Arrays.asList("src/ontology/run.sh.jinja2", "src/scripts/run-command.sh"),
                executable);
    }

    /** The version is said once, and the bundled folder is that version's. */
    @Test
    void thePinIsOdkOneSixOne() {
        assertEquals("v1.6.1", OdkPin.VERSION);
        assertEquals("obolibrary/odkfull", OdkPin.IMAGE_REPOSITORY);
        assertEquals("obolibrary/odkfull:v1.6.1", OdkPin.IMAGE);
        assertEquals("odk/v1.6.1/", OdkPin.RESOURCES);
    }

    private static int count(byte[] bytes, byte wanted) {
        int found = 0;
        for (byte b : bytes) {
            if (b == wanted) {
                found++;
            }
        }
        return found;
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }
}
