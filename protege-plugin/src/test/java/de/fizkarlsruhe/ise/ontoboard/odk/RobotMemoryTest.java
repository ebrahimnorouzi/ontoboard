package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A project's {@code robot_java_args} against the memory Protégé runs with: in ODK's container
 * ROBOT gets what the project asks for; run by OntoBoard itself it has Protégé's, and a run
 * that may need more says so before it starts, with the setting that gives it.
 */
class RobotMemoryTest {

    private static final long GIB = 1L << 30;

    @Test
    void theHeapAskedForIsXmxInBytes() {
        assertEquals(8 * GIB, RobotMemory.asked("-Xmx8G"));
        assertEquals(8 * GIB, RobotMemory.asked("-Xmx8g"));
        assertEquals(8 * GIB, RobotMemory.asked("-Xms1G -Xmx8192m"));
        assertEquals(8 * GIB, RobotMemory.asked("-Xmx8192M"));
        assertEquals(2 * GIB, RobotMemory.asked("-Xmx2097152k"));
        assertEquals(0, RobotMemory.asked(""));
        assertEquals(0, RobotMemory.asked(null));
        assertEquals(0, RobotMemory.asked("-Xms4G -Dfile.encoding=UTF-8"));
    }

    /** Java takes the last -Xmx of its command line. */
    @Test
    void theLastXmxIsTheOneJavaTakes() {
        assertEquals(4 * GIB, RobotMemory.asked("-Xmx8G -Xmx4G"));
    }

    @Test
    void onProtege56TheNoteNamesItsJvmConf() {
        String note = RobotMemory.note("-Xmx8G", 4 * GIB, "5.6.9", true);

        assertTrue(note.startsWith("Run by OntoBoard itself, ROBOT has Protégé's memory"), note);
        assertTrue(note.contains("conf/jvm.conf") && note.contains("max_heap_size=8G"), note);
    }

    @Test
    void onProtege550OnWindowsTheNoteNamesItsIni() {
        String note = RobotMemory.note("-Xmx8G", 4 * GIB, "5.5.0", true);

        assertTrue(note.contains("Protege.l4j.ini") && note.contains("-Xmx8G"), note);
    }

    @Test
    void onProtege550ElsewhereTheNoteNamesItsScript() {
        String note = RobotMemory.note("-Xmx8G", 4 * GIB, "5.5.0", false);

        assertTrue(note.contains("run.sh") && note.contains("-Xmx8G"), note);
    }

    @Test
    void enoughMemoryIsNoNote() {
        assertNull(RobotMemory.note("-Xmx2G", 4 * GIB, "5.6.9", true));
        assertNull(RobotMemory.note("", 4 * GIB, "5.6.9", true));
        assertNull(RobotMemory.note(null, 4 * GIB, "5.5.0", true));
    }

    /**
     * Here, the setting is the project's own, read from its ODK configuration, against the
     * memory of the Java this runs in.
     */
    @Test
    void hereTheProjectsOwnSettingIsAskedAbout(@org.junit.jupiter.api.io.TempDir java.io.File top)
            throws java.io.IOException {
        java.io.File ontology = new java.io.File(top, "src/ontology");
        java.nio.file.Files.createDirectories(ontology.toPath());
        java.io.File yaml = new java.io.File(ontology, "egg-odk.yaml");

        java.nio.file.Files.write(yaml.toPath(), "id: egg\nrobot_java_args: '-Xmx64T'\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String note = RobotMemory.noteHere(ontology);
        assertTrue(note != null && note.contains("64.0 GB") == false
                && note.contains("65536.0 GB") && note.contains("-Xmx64T"), String.valueOf(note));

        java.nio.file.Files.write(yaml.toPath(), "id: egg\nrobot_java_args: '-Xmx1M'\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNull(RobotMemory.noteHere(ontology));
    }

    /** Some collectors report a little less than -Xmx as the most there is: that is enough. */
    @Test
    void aHeapAlittleUnderItsXmxIsEnough() {
        assertNull(RobotMemory.note("-Xmx8G", 8 * GIB - 512L * 1024 * 1024, "5.6.9", true));
    }
}
