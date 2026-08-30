package de.fizkarlsruhe.ise.ontoboard.proc;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Running an external command without hanging Protege.
 *
 * <p>Extracted from the git integration after a review found the naive version could never time
 * out. The original drained the process's output to end-of-stream and only then called
 * {@code waitFor(timeout)} - so a process that never exits never closes its pipe, the read blocks
 * for ever, and the timeout below it is unreachable. The bound has to cover the read as well as
 * the wait, or it is not a bound. Both {@code git} and {@code make} are launched through here so
 * there is one copy of that lesson rather than two.
 *
 * <p>Nothing can answer a prompt from a background thread inside Protege, so the environment is
 * set to make the common tools fail rather than wait: a private repository that pops the Git
 * Credential Manager, or a {@code make} recipe that asks for confirmation, would otherwise block
 * behind the main window until somebody noticed.
 *
 * <p>No Protege types and no Swing.
 */
public final class ProcessRunner {

    /** What a command did. */
    public static final class Outcome {
        private final int exitCode;
        private final List<String> output;
        private final boolean timedOut;

        public Outcome(int exitCode, List<String> output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = Collections.unmodifiableList(
                    new ArrayList<String>(output == null ? Collections.<String>emptyList()
                            : output));
            this.timedOut = timedOut;
        }

        public int getExitCode() {
            return exitCode;
        }

        /** Every line the command printed, in order, stdout and stderr interleaved as it wrote them. */
        public List<String> getOutput() {
            return output;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }

        /** True when the command was still running when its time ran out and was killed. */
        public boolean timedOut() {
            return timedOut;
        }

        /** The last line with anything on it - where a build tool puts the reason it failed. */
        public String lastMeaningfulLine() {
            for (int i = output.size() - 1; i >= 0; i--) {
                String line = output.get(i).trim();
                if (!line.isEmpty()) {
                    return line;
                }
            }
            return "";
        }

        @Override
        public String toString() {
            return "exit " + exitCode + (timedOut ? " (timed out)" : "") + ", "
                    + output.size() + " lines";
        }
    }

    /** Told about each line as it arrives, so a long build is not a frozen dialog. */
    public interface Sink {
        void line(String text);
    }

    /** How a command gets run. Replaced in tests, so the decisions are checkable without one. */
    public interface Runner {
        Outcome run(File workingDirectory, List<String> command, long timeoutMinutes, Sink sink)
                throws IOException;
    }

    private ProcessRunner() {
    }

    /** Runs commands for real. */
    public static Runner real() {
        return new Runner() {
            @Override
            public Outcome run(File workingDirectory, List<String> command, long timeoutMinutes,
                    final Sink sink) throws IOException {
                ProcessBuilder builder = new ProcessBuilder(command);
                if (workingDirectory != null) {
                    builder.directory(workingDirectory);
                }
                // Interleaved rather than separated: a failure message split across two streams is
                // reassembled in the wrong order as often as not, and what a user needs is the
                // transcript they would have seen in a terminal.
                builder.redirectErrorStream(true);
                quieten(builder.environment());

                Process process = builder.start();
                try {
                    // The command reads nothing from us, and an open pipe is one more thing for it
                    // to wait on.
                    process.getOutputStream().close();
                } catch (IOException alreadyClosed) {
                    // Going to be waited on regardless.
                }

                final List<String> collected = Collections.synchronizedList(
                        new ArrayList<String>());
                final Process running = process;
                Thread drain = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(running.getInputStream(), "UTF-8"));
                            try {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    collected.add(line);
                                    if (sink != null) {
                                        sink.line(line);
                                    }
                                }
                            } finally {
                                reader.close();
                            }
                        } catch (IOException stopped) {
                            // The process was destroyed under us, which is how a timeout ends.
                        }
                    }
                }, "ontoboard-process-output");
                drain.setDaemon(true);
                drain.start();

                boolean finished;
                try {
                    finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    return new Outcome(-1, snapshot(collected), false);
                }
                if (!finished) {
                    // destroyForcibly closes the pipe, which unblocks the reader at end of stream.
                    process.destroyForcibly();
                    join(drain);
                    return new Outcome(-1, snapshot(collected), true);
                }
                join(drain);
                return new Outcome(process.exitValue(), snapshot(collected), false);
            }
        };
    }

    /**
     * Environment that makes a tool fail rather than wait for input nobody can give it.
     *
     * <p>Package-visible so a test can check the settings are there. Getting this wrong does not
     * fail visibly - it hangs, once, on somebody else's machine, with a credential window behind
     * the main one.
     */
    static void quieten(Map<String, String> environment) {
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GCM_INTERACTIVE", "never");
        environment.put("GIT_ASKPASS", "");
        environment.put("SSH_ASKPASS", "");
        // make and the tools it calls: never page output, never colour it for a terminal that is
        // not there.
        environment.put("PAGER", "cat");
        environment.put("GIT_PAGER", "cat");
        environment.put("TERM", "dumb");
        environment.put("NO_COLOR", "1");
    }

    private static List<String> snapshot(List<String> collected) {
        synchronized (collected) {
            return new ArrayList<String>(collected);
        }
    }

    /**
     * Waits for the last of the output, but not for ever.
     *
     * <p>The process has exited, so the pipe is at end of stream and this returns at once in
     * practice. The bound is there because "in practice" is exactly what the original code
     * assumed about the read itself.
     */
    private static void join(Thread drain) {
        try {
            drain.join(2000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Whether a command can be launched at all - {@code <tool> --version} succeeding. */
    public static boolean isAvailable(Runner runner, String tool, String versionFlag) {
        try {
            return runner.run(null, Arrays.asList(tool, versionFlag), 1, null).isSuccess();
        } catch (IOException notThere) {
            return false;
        } catch (RuntimeException notThere) {
            return false;
        }
    }
}
