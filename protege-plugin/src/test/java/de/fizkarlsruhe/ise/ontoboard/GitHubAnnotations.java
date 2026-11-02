package de.fizkarlsruhe.ise.ontoboard;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Names each failing test in the summary of a GitHub Actions run.
 *
 * <p>A failure in CI is otherwise written only in the job's log, which GitHub shows to people
 * signed in: 1.133.0's release failed on Linux and on Windows, and to anyone else its run said
 * no more than "Process completed with exit code 1". A line {@code ::error title=...::...} on
 * standard output becomes an annotation on the run's page, which anyone can read and the API
 * serves without a token. Under GitHub Actions only; anywhere else this prints nothing.
 *
 * <p>A launcher listener rather than a Jupiter extension, so that a failure outside a test
 * method is named as well - a class that cannot be initialised, or a temporary directory that
 * Windows will not delete because a file in it is still open. Registered through
 * {@code META-INF/services}, which the launcher reads by itself.
 */
public class GitHubAnnotations implements TestExecutionListener {

    private static final boolean ON_GITHUB = "true".equals(System.getenv("GITHUB_ACTIONS"));

    @Override
    public void executionFinished(TestIdentifier test, TestExecutionResult result) {
        // FAILED only: ABORTED is a test that skipped itself through an assumption.
        if (!ON_GITHUB || result.getStatus() != TestExecutionResult.Status.FAILED) {
            return;
        }
        System.out.println(line(name(test.getSource().orElse(null), test.getDisplayName()),
                result.getThrowable().orElse(null)));
    }

    /** {@code SimpleClassName.method}, or the class alone for a failure outside any method. */
    static String name(TestSource source, String displayName) {
        if (source instanceof MethodSource) {
            MethodSource method = (MethodSource) source;
            return simple(method.getClassName()) + "." + method.getMethodName();
        }
        if (source instanceof ClassSource) {
            return simple(((ClassSource) source).getClassName());
        }
        return displayName;
    }

    /**
     * The workflow command: what was thrown, its message and the first frame in our own code.
     * GitHub reads a newline or a percent sign in the message as syntax unless escaped, and a
     * colon or a comma in the title as well.
     */
    static String line(String test, Throwable cause) {
        StringBuilder text = new StringBuilder();
        if (cause == null) {
            text.append("failed, with nothing thrown");
        } else {
            text.append(cause.getClass().getName());
            if (cause.getMessage() != null) {
                text.append(": ").append(cause.getMessage());
            }
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (frame.getClassName().startsWith("de.fizkarlsruhe.")) {
                    text.append("\n    at ").append(frame);
                    break;
                }
            }
        }
        String message = text.length() > 2000 ? text.substring(0, 2000) + "..." : text.toString();
        return "::error title=" + escape(test, true) + "::" + escape(message, false);
    }

    private static String escape(String value, boolean property) {
        String escaped = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
        return property ? escaped.replace(":", "%3A").replace(",", "%2C") : escaped;
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
