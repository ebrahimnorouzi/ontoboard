package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Is it working on systems where robot is not installed, ODK is not there and Docker is not
 * there?" - answered by measurement rather than by reading the code: scaffolding a project and
 * running its whole test target must start no program at all.
 *
 * <p>Two ways to watch, because Java took one away. Where a SecurityManager can still be installed
 * - Java 17 and earlier, or up to 23 with {@code -Djava.security.manager=allow} - one refuses
 * every exec, so even an attempt to run a program that is not installed fails this test. From
 * Java 24 none can be (JEP 486), and CI builds on Java 25: there the JVM's flight recorder records
 * every process the build starts, its {@code jdk.ProcessStart} event (Java 15 and later). The
 * recorder sees a start only when the program exists, so it is first shown one it must see - this
 * JVM's own {@code java -version} - and a recorder that misses it fails the test instead of
 * passing it for want of looking. CI's runners have make, docker, git and sh: what an accidental
 * exec would reach for.
 */
class BuildsWithNothingInstalledTest {

    @Test
    void scaffoldingAndBuildingStartNoProgram(@TempDir File where) throws Exception {
        final List<String> started = new ArrayList<String>();
        String outcome;
        SecurityManager previous = System.getSecurityManager();
        if (installed(refusing(started))) {
            try {
                outcome = SelfTestAction.buildsWithNothingInstalled(where);
            } finally {
                System.setSecurityManager(previous);
            }
        } else {
            ProcessStarts calibration = ProcessStarts.begin();
            assertNotNull(calibration, "Java " + System.getProperty("java.version") + " allows no "
                    + "SecurityManager and has no flight recorder: nothing here can see a program "
                    + "start, so this test cannot answer its question on this JVM");
            String probe = startJavaVersion();
            List<String> seen = calibration.end();
            assertTrue(mentions(seen, probe), "the flight recorder did not see " + probe
                    + " start, so it cannot vouch for what the build starts: " + seen);

            ProcessStarts recorder = ProcessStarts.begin();
            try {
                outcome = SelfTestAction.buildsWithNothingInstalled(where);
            } finally {
                started.addAll(recorder.end());
            }
        }

        assertTrue(started.isEmpty(), "programs were started: " + started);
        assertTrue(outcome.startsWith("ok:"), outcome);
        assertTrue(outcome.contains("robot "), "it says whether robot is on the PATH: " + outcome);
    }

    /** True when the manager is now in force; false on a Java that no longer allows one. */
    private static boolean installed(SecurityManager manager) {
        try {
            System.setSecurityManager(manager);
            return true;
        } catch (UnsupportedOperationException notOnThisJava) {
            return false;
        }
    }

    /** Refuses every exec and records the command; permits everything else. */
    private static SecurityManager refusing(final List<String> started) {
        return new SecurityManager() {
            @Override
            public void checkPermission(java.security.Permission permission) {
            }

            @Override
            public void checkPermission(java.security.Permission permission, Object context) {
            }

            @Override
            public void checkExec(String command) {
                // File.canExecute asks the same permission without starting anything - it is how
                // the self-test looks for robot on the PATH without running it.
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    if ("java.io.File".equals(frame.getClassName())
                            && "canExecute".equals(frame.getMethodName())) {
                        return;
                    }
                }
                started.add(command);
                throw new SecurityException("this build must not start " + command);
            }
        };
    }

    /** Starts this JVM's own java -version, waits for it, and returns the path it ran. */
    private static String startJavaVersion() throws Exception {
        String windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows")
                ? ".exe" : "";
        String java = new File(new File(System.getProperty("java.home"), "bin"), "java" + windows)
                .getPath();
        Process process = new ProcessBuilder(java, "-version").redirectErrorStream(true).start();
        InputStream output = process.getInputStream();
        byte[] buffer = new byte[4096];
        while (output.read(buffer) >= 0) {
            // drained, so the child cannot block on a full pipe
        }
        process.waitFor();
        return java;
    }

    private static boolean mentions(List<String> commands, String program) {
        for (String command : commands) {
            if (command.contains(program) && command.contains("-version")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The programs this JVM starts while a recording runs: the flight recorder's
     * {@code jdk.ProcessStart} events. Read through reflection, because the tests compile against
     * Java 8, which has no {@code jdk.jfr}.
     */
    private static final class ProcessStarts {

        private final Object recording;

        private ProcessStarts(Object recording) {
            this.recording = recording;
        }

        /** A recording of process starts, already running; null where there is no recorder. */
        static ProcessStarts begin() throws Exception {
            Class<?> type;
            try {
                type = Class.forName("jdk.jfr.Recording");
            } catch (ClassNotFoundException noFlightRecorder) {
                return null;
            }
            Object recording = type.getConstructor().newInstance();
            type.getMethod("enable", String.class).invoke(recording, "jdk.ProcessStart");
            type.getMethod("start").invoke(recording);
            return new ProcessStarts(recording);
        }

        /** Stops the recording and returns the command line of every program it saw start. */
        List<String> end() throws Exception {
            Class<?> type = Class.forName("jdk.jfr.Recording");
            type.getMethod("stop").invoke(recording);
            File dump = File.createTempFile("process-starts", ".jfr");
            try {
                type.getMethod("dump", Path.class).invoke(recording, dump.toPath());
                Class<?> file = Class.forName("jdk.jfr.consumer.RecordingFile");
                Class<?> event = Class.forName("jdk.jfr.consumer.RecordedEvent");
                Class<?> eventType = Class.forName("jdk.jfr.EventType");
                List<?> events = (List<?>) file.getMethod("readAllEvents", Path.class)
                        .invoke(null, dump.toPath());
                List<String> commands = new ArrayList<String>();
                for (Object each : events) {
                    Object kind = event.getMethod("getEventType").invoke(each);
                    if ("jdk.ProcessStart".equals(eventType.getMethod("getName").invoke(kind))) {
                        commands.add(String.valueOf(event.getMethod("getString", String.class)
                                .invoke(each, "command")));
                    }
                }
                return commands;
            } finally {
                type.getMethod("close").invoke(recording);
                if (!dump.delete()) {
                    dump.deleteOnExit();
                }
            }
        }
    }
}
