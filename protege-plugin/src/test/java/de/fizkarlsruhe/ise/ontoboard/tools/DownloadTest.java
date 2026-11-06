package de.fizkarlsruhe.ise.ontoboard.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A download is kept only when it is the release that was pinned, and nothing else is left behind.
 *
 * <p>"Nothing left behind" is the point of most of these. {@code WidocoInstall.candidates} takes
 * any file with the release's name for an installed Widoco, so a half-written jar under that name
 * would be found next time and fail in a way nobody could connect to a cancelled download.
 */
class DownloadTest {

    /** Several 64 KB chunks, so a download can be stopped part of the way. */
    private static final byte[] RELEASE = bytes(200_000);

    private static byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        for (int at = 0; at < length; at++) {
            bytes[at] = (byte) (at * 31 + 7);
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    private static ToolAsset pinnedTo(byte[] bytes) throws Exception {
        return new ToolAsset("tool.jar", "https://example.org/tool.jar", bytes.length,
                sha256(bytes), "https://example.org/", false);
    }

    private static Download.Opener serving(final byte[] bytes) {
        return new Download.Opener() {
            @Override
            public Download.Opened open(String url) {
                return new Download.Opened(new ByteArrayInputStream(bytes), bytes.length);
            }
        };
    }

    /** Remembers the last progress report; cancels once it has seen {@code cancelAfter} reports. */
    private static final class Watching implements Download.Progress {
        private final int cancelAfter;
        int reports;
        long lastDone = -1;
        long lastTotal = -1;

        Watching(int cancelAfter) {
            this.cancelAfter = cancelAfter;
        }

        @Override
        public void bytes(long done, long total) {
            reports++;
            lastDone = done;
            lastTotal = total;
        }

        @Override
        public boolean isCancelled() {
            return cancelAfter > 0 && reports >= cancelAfter;
        }
    }

    private static Watching never() {
        return new Watching(0);
    }

    private static void assertNothingLeft(File folder) {
        assertFalse(new File(folder, "tool.jar").exists(), "the release name must stay free");
        assertFalse(new File(folder, "tool.jar.part").exists(), "no part file may be left");
    }

    @Test
    void downloadsVerifiesAndKeepsTheReleaseName(@TempDir File folder) throws Exception {
        File got = Download.fetch(pinnedTo(RELEASE), folder, serving(RELEASE), never());

        assertEquals(new File(folder, "tool.jar"), got);
        assertArrayEquals(RELEASE, Files.readAllBytes(got.toPath()));
        assertFalse(new File(folder, "tool.jar.part").exists());
    }

    @Test
    void aWrongHashLeavesNothingBehind(@TempDir File folder) throws Exception {
        byte[] tampered = Arrays.copyOf(RELEASE, RELEASE.length);
        tampered[100_000] ^= 1;
        ToolAsset asset = pinnedTo(RELEASE);

        Download.Failed failed = assertThrows(Download.Failed.class,
                () -> Download.fetch(asset, folder, serving(tampered), never()));

        assertFalse(failed.isCancelled());
        assertTrue(failed.getMessage().contains(asset.getSha256()), failed.getMessage());
        assertTrue(failed.getMessage().contains(sha256(tampered)), failed.getMessage());
        assertNothingLeft(folder);
    }

    @Test
    void aCancelledDownloadLeavesNothingBehind(@TempDir File folder) throws Exception {
        Download.Failed failed = assertThrows(Download.Failed.class,
                () -> Download.fetch(pinnedTo(RELEASE), folder, serving(RELEASE),
                        new Watching(1)));

        assertTrue(failed.isCancelled(), failed.getMessage());
        assertNothingLeft(folder);
    }

    @Test
    void aBrokenConnectionLeavesNothingBehind(@TempDir File folder) throws Exception {
        Download.Opener breaksHalfway = new Download.Opener() {
            @Override
            public Download.Opened open(String url) {
                // 70,000 bytes of the release, then the connection drops.
                final ByteArrayInputStream half = new ByteArrayInputStream(RELEASE, 0, 70_000);
                return new Download.Opened(new InputStream() {
                    @Override
                    public int read() throws IOException {
                        byte[] one = new byte[1];
                        return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
                    }

                    @Override
                    public int read(byte[] into, int from, int length) throws IOException {
                        if (half.available() == 0) {
                            throw new IOException("connection reset");
                        }
                        return half.read(into, from, length);
                    }
                }, RELEASE.length);
            }
        };

        Download.Failed failed = assertThrows(Download.Failed.class,
                () -> Download.fetch(pinnedTo(RELEASE), folder, breaksHalfway, never()));

        assertFalse(failed.isCancelled());
        assertTrue(failed.getMessage().contains("connection reset"), failed.getMessage());
        assertNothingLeft(folder);
    }

    /**
     * Cancel closes the connection, so whatever was waiting on it fails - and that is the cancel
     * arriving, not a network problem to report to somebody who has just pressed Cancel.
     */
    @Test
    void aCancelDuringTheConnectionIsNotReportedAsAFailure(@TempDir File folder) throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
        Download.Opener closedWhileConnecting = new Download.Opener() {
            @Override
            public Download.Opened open(String url) throws IOException {
                cancelled.set(true);
                throw new java.net.SocketException("Socket closed");
            }
        };

        Download.Failed failed = assertThrows(Download.Failed.class,
                () -> Download.fetch(pinnedTo(RELEASE), folder, closedWhileConnecting,
                        cancelledWhen(cancelled)));

        assertTrue(failed.isCancelled(), failed.getMessage());
        assertNothingLeft(folder);
    }

    @Test
    void aCancelDuringTheTransferIsNotReportedAsAFailure(@TempDir File folder) throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
        Download.Opener closedWhileReading = new Download.Opener() {
            @Override
            public Download.Opened open(String url) {
                return new Download.Opened(new InputStream() {
                    @Override
                    public int read() throws IOException {
                        cancelled.set(true);
                        throw new java.net.SocketException("Socket closed");
                    }
                }, RELEASE.length);
            }
        };

        Download.Failed failed = assertThrows(Download.Failed.class,
                () -> Download.fetch(pinnedTo(RELEASE), folder, closedWhileReading,
                        cancelledWhen(cancelled)));

        assertTrue(failed.isCancelled(), failed.getMessage());
        assertNothingLeft(folder);
    }

    private static Download.Progress cancelledWhen(
            final java.util.concurrent.atomic.AtomicBoolean cancelled) {
        return new Download.Progress() {
            @Override
            public void bytes(long done, long total) {
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    @Test
    void aGoodFileAlreadyThereIsNotDownloadedAgain(@TempDir File folder) throws Exception {
        File there = new File(folder, "tool.jar");
        Files.write(there.toPath(), RELEASE);
        Download.Opener mustNotBeCalled = new Download.Opener() {
            @Override
            public Download.Opened open(String url) {
                return fail("the release was already there, so nothing should be downloaded");
            }
        };

        File got = Download.fetch(pinnedTo(RELEASE), folder, mustNotBeCalled, never());

        assertEquals(there, got);
        assertArrayEquals(RELEASE, Files.readAllBytes(got.toPath()));
    }

    @Test
    void aBadFileAlreadyThereIsReplaced(@TempDir File folder) throws Exception {
        File there = new File(folder, "tool.jar");
        Files.write(there.toPath(), Arrays.copyOf(RELEASE, 1_000));

        File got = Download.fetch(pinnedTo(RELEASE), folder, serving(RELEASE), never());

        assertEquals(there, got);
        assertArrayEquals(RELEASE, Files.readAllBytes(got.toPath()));
    }

    @Test
    void progressReportsTheTotal(@TempDir File folder) throws Exception {
        Watching progress = never();

        Download.fetch(pinnedTo(RELEASE), folder, serving(RELEASE), progress);

        assertTrue(progress.reports > 1, "a 200 KB file arrives in more than one chunk");
        assertEquals(RELEASE.length, progress.lastDone);
        assertEquals(RELEASE.length, progress.lastTotal);
    }

    @Test
    void aFolderThatCannotBeWrittenIsNamed(@TempDir File dir) throws Exception {
        File notAFolder = new File(dir, "a file, not a folder");
        Files.write(notAFolder.toPath(), new byte[] {1});

        Download.Failed failed = assertThrows(Download.Failed.class,
                () -> Download.fetch(pinnedTo(RELEASE), notAFolder, serving(RELEASE), never()));

        assertTrue(failed.getMessage().contains(notAFolder.getAbsolutePath()),
                failed.getMessage());
    }
}
