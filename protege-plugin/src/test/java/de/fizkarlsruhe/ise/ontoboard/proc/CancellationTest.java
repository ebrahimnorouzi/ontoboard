package de.fizkarlsruhe.ise.ontoboard.proc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Stopping a running command.
 *
 * <p>Until this existed the progress dialog's button abandoned the <em>result</em> and left the
 * work running: pressing it on a forty-minute ODK build dismissed the dialog and left the
 * container going to completion. The dialog said so, which made it honest rather than
 * acceptable.
 *
 * <p>These start a real child process, because the thing being tested is whether a real process
 * dies. A stub runner would only prove the flag is passed around.
 */
class CancellationTest {

    /** Something that runs long enough to be stopped, on whatever this is. */
    private static List<String> aLongCommand() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win")
                ? Arrays.asList("cmd", "/c", "ping", "-n", "60", "127.0.0.1")
                : Arrays.asList("sleep", "60");
    }

    /** Cancelling a running command kills it, and the outcome says who stopped it. */
    @Test
    void cancellingKillsTheProcess() throws Exception {
        final ProcessRunner.Cancellation cancellation = new ProcessRunner.Cancellation();
        final AtomicReference<ProcessRunner.Outcome> outcome =
                new AtomicReference<ProcessRunner.Outcome>();
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                ProcessRunner.cancelWith(cancellation);
                try {
                    started.countDown();
                    outcome.set(ProcessRunner.real().run(null, aLongCommand(), 5, null));
                } catch (Exception thrown) {
                    failure.set(thrown);
                } finally {
                    ProcessRunner.stopCancelling();
                    done.countDown();
                }
            }
        }, "cancellation-test");
        worker.setDaemon(true);

        long begun = System.currentTimeMillis();
        worker.start();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        // Long enough for the process to exist, far short of its own minute.
        Thread.sleep(600);
        cancellation.cancel();

        assertTrue(done.await(20, TimeUnit.SECONDS), "the run did not return after cancelling");
        assertNotNull(outcome.get(), "threw instead of returning: " + failure.get());
        assertTrue(outcome.get().wasCancelled(),
                "a killed process must not look like a failed one: " + outcome.get());
        assertFalse(outcome.get().timedOut(), "it was stopped, not timed out");
        assertTrue(System.currentTimeMillis() - begun < 30000,
                "it waited for the command instead of killing it");
    }

    /**
     * Cancelling before the command starts still stops it.
     *
     * <p>The gap between pressing "run" and a container actually launching is seconds, and a
     * press in that window would otherwise cancel nothing while the dialog went away.
     */
    @Test
    void cancellingBeforeItStartsStillStopsIt() throws Exception {
        ProcessRunner.Cancellation cancellation = new ProcessRunner.Cancellation();
        cancellation.cancel();

        ProcessRunner.cancelWith(cancellation);
        try {
            long begun = System.currentTimeMillis();
            ProcessRunner.Outcome outcome = ProcessRunner.real().run(null, aLongCommand(), 5,
                    null);

            assertTrue(outcome.wasCancelled(), outcome.toString());
            assertTrue(System.currentTimeMillis() - begun < 30000, "it ran the full command");
        } finally {
            ProcessRunner.stopCancelling();
        }
    }

    /** With nothing watching, a command runs normally. */
    @Test
    void withoutACancellationNothingChanges() throws Exception {
        ProcessRunner.stopCancelling();

        ProcessRunner.Outcome outcome = ProcessRunner.real().run(null, echo(), 2, null);

        assertTrue(outcome.isSuccess(), outcome.toString());
        assertFalse(outcome.wasCancelled());
    }

    /**
     * A finished command releases its handle.
     *
     * <p>Otherwise a later press would call destroyForcibly on a dead process - harmless in
     * itself, but the next operation on the thread would report itself cancelled when it was
     * not.
     */
    @Test
    void aFinishedCommandIsNoLongerCancellable() throws Exception {
        ProcessRunner.Cancellation cancellation = new ProcessRunner.Cancellation();
        ProcessRunner.cancelWith(cancellation);
        try {
            ProcessRunner.Outcome first = ProcessRunner.real().run(null, echo(), 2, null);
            assertTrue(first.isSuccess());
            assertFalse(first.wasCancelled());

            // The handle is clear, so this is a no-op rather than killing something unrelated.
            cancellation.cancel();
            assertTrue(cancellation.isCancelled());
        } finally {
            ProcessRunner.stopCancelling();
        }
    }

    /** The default outcome constructor still reports not-cancelled, for every existing caller. */
    @Test
    void theOlderConstructorIsUnchanged() {
        ProcessRunner.Outcome outcome =
                new ProcessRunner.Outcome(0, Arrays.asList("hello"), false);

        assertFalse(outcome.wasCancelled());
        assertTrue(outcome.isSuccess());
    }

    private static List<String> echo() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win")
                ? Arrays.asList("cmd", "/c", "echo", "hello")
                : Arrays.asList("echo", "hello");
    }
}
