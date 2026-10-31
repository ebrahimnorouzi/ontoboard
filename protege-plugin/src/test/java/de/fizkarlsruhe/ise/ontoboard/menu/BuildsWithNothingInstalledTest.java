package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Is it working on systems where robot is not installed, ODK is not there and Docker is not
 * there?" - answered by measurement rather than by reading the code: scaffolding a project and
 * running its whole test target happens under a SecurityManager that refuses to start any
 * program, so a single exec anywhere on the path fails this test.
 */
class BuildsWithNothingInstalledTest {

    @Test
    void scaffoldingAndBuildingStartNoProgram(@TempDir File where) {
        final List<String> started = new ArrayList<String>();
        SecurityManager previous = System.getSecurityManager();
        System.setSecurityManager(new SecurityManager() {
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
        });
        String outcome;
        try {
            outcome = SelfTestAction.buildsWithNothingInstalled(where);
        } finally {
            System.setSecurityManager(previous);
        }

        assertTrue(started.isEmpty(), "programs were started: " + started);
        assertTrue(outcome.startsWith("ok:"), outcome);
        assertTrue(outcome.contains("robot "), "it says whether robot is on the PATH: " + outcome);
    }
}
