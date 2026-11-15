package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Stopping a run that is a container, on a machine that has one to stop.
 *
 * <p>Found by the review of 1.153.0 and then seen: with the client killed - which is all that
 * stopping a command did - the container was still up, building and writing into the project
 * for as long as ODK's target takes. Live: skipped unless a container runtime is running and
 * ODK's image is on the machine. It starts one container of that image, named for this test,
 * and takes it away again whatever happens.
 */
class ContainerStopLiveTest {

    @Test
    void stoppingARunTakesItsContainerAway() throws Exception {
        final ProcessRunner.Runner real = ProcessRunner.real();
        final String runtime = MakeRun.containerRuntime(real);
        assumeTrue(runtime != null, "no container runtime is running");
        final String image = OdkImage.pinned(OdkImage.probe(runtime, real));
        assumeTrue(image != null, "ODK v1.6.1's image is not on this machine");

        final String name = "ontoboard-stop-test-" + Long.toHexString(System.nanoTime());
        final ProcessRunner.Cancellation cancellation = new ProcessRunner.Cancellation();
        final AtomicReference<ProcessRunner.Outcome> outcome =
                new AtomicReference<ProcessRunner.Outcome>();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                ProcessRunner.cancelWith(cancellation);
                try {
                    outcome.set(real.run(null, Arrays.asList(runtime, "run", "--rm", "--name",
                            name, image, "sleep", "120"), 5, null));
                } catch (IOException notStarted) {
                    // The assertion on the outcome says so.
                } finally {
                    ProcessRunner.stopCancelling();
                }
            }
        }, "container-stop-test");
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(within(90, true, real, runtime, name), "the container started");

            cancellation.cancel();
            worker.join(30000);

            assertNotNull(outcome.get(), "the run came back");
            assertTrue(outcome.get().wasCancelled(), "and says who stopped it");
            assertTrue(within(30, false, real, runtime, name),
                    "a run that was stopped leaves no container running");
        } finally {
            real.run(null, Arrays.asList(runtime, "rm", "-f", name), 1, null);
        }
    }

    /** Whether the container is up (or is not) within so many seconds. */
    private static boolean within(int seconds, boolean up, ProcessRunner.Runner real,
            String runtime, String name) throws Exception {
        long until = System.nanoTime() + seconds * 1000000000L;
        while (System.nanoTime() < until) {
            boolean isUp = !String.join("", real.run(null, Arrays.asList(runtime, "ps", "-q",
                    "--filter", "name=" + name), 1, null).getOutput()).trim().isEmpty();
            if (isUp == up) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }
}
