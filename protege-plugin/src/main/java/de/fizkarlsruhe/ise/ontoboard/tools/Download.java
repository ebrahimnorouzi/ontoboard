package de.fizkarlsruhe.ise.ontoboard.tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Downloads a pinned release and keeps it only if it is the release.
 *
 * <p><b>Nothing half-written ever has the release's name.</b> The bytes go to
 * {@code <name>.part} and are renamed only once their length and SHA-256 match the pin. That
 * matters more than it looks: {@code WidocoInstall.candidates} takes any file with the release's
 * name for an installed Widoco, so a jar cut short by Cancel or a dropped connection, left under
 * that name, would be "found" next time and fail in a way nobody could trace back to the download.
 * Every way out other than success deletes the part file.
 *
 * <p>No transport here: {@link Opener} supplies the bytes, so everything this class decides is
 * tested without a network, and the HTTPS details live in {@link HttpsOpener}.
 */
public final class Download {

    private static final int CHUNK = 64 * 1024;

    private Download() {
    }

    /** Opens a URL for reading. */
    public interface Opener {
        Opened open(String url) throws IOException;
    }

    /** An opened download: its bytes, and their length if the server said. */
    public static final class Opened {
        private final InputStream body;
        private final long length;

        /** @param length the length the server announced, or -1 when it did not */
        public Opened(InputStream body, long length) {
            this.body = body;
            this.length = length;
        }

        public InputStream getBody() {
            return body;
        }

        public long getLength() {
            return length;
        }
    }

    /** Told how far a download has got, and asked whether to stop. */
    public interface Progress {
        void bytes(long done, long total);

        boolean isCancelled();
    }

    /** Why a download did not produce the release - or that it was stopped. */
    public static final class Failed extends Exception {
        private static final long serialVersionUID = 1L;
        private final boolean cancelled;

        Failed(String message, boolean cancelled, Throwable cause) {
            super(message, cause);
            this.cancelled = cancelled;
        }

        /** True when the user stopped it, which is not a failure to report as one. */
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /**
     * Puts the release in {@code folder} under its own name, or says why it could not.
     *
     * <p>A file already there with the right hash is used as it is: somebody who downloaded it by
     * hand, or ran this twice, does not wait for 39 MB again. One there with any other content is
     * replaced, but only once the new download has been checked.
     *
     * @return the release, verified
     */
    public static File fetch(ToolAsset asset, File folder, Opener opener, Progress progress)
            throws Failed {
        File target = new File(folder, asset.getName());
        if (target.isFile()) {
            try {
                if (asset.getSha256().equals(sha256(target))) {
                    progress.bytes(asset.getSize(), asset.getSize());
                    return target;
                }
            } catch (IOException unreadable) {
                // Then it is no use as it is: download it again.
            }
        }
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new Failed("Cannot create the folder " + folder.getAbsolutePath()
                    + ". Choose one you can write to.", false, null);
        }

        File part = new File(folder, asset.getName() + ".part");
        boolean kept = false;
        try {
            Opened opened = opener.open(asset.getUrl());
            if (progress.isCancelled()) {
                closeQuietly(opened.getBody());
                throw new Failed("The download was stopped.", true, null);
            }
            long total = opened.getLength() > 0 ? opened.getLength() : asset.getSize();
            MessageDigest digest = sha256Digest();
            long done = 0;
            OutputStream out;
            try {
                out = new FileOutputStream(part);
            } catch (IOException cannotWrite) {
                closeQuietly(opened.getBody());
                throw new Failed("Cannot write to " + folder.getAbsolutePath() + ": "
                        + cannotWrite.getMessage() + ". Choose a folder you can write to.",
                        false, cannotWrite);
            }
            try (InputStream in = opened.getBody(); OutputStream to = out) {
                byte[] buffer = new byte[CHUNK];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (progress.isCancelled()) {
                        throw new Failed("The download was stopped.", true, null);
                    }
                    to.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    done += read;
                    progress.bytes(done, total);
                }
            }
            if (done != asset.getSize()) {
                throw new Failed("The download of " + asset.getName() + " stopped at " + done
                        + " of " + asset.getSize() + " bytes. It has been deleted.", false, null);
            }
            String got = hex(digest.digest());
            if (!got.equals(asset.getSha256())) {
                throw new Failed("The file downloaded from " + asset.getUrl() + " is not the "
                        + "release OntoBoard expects: its SHA-256 is " + got + ", the release's is "
                        + asset.getSha256() + ". It has been deleted.", false, null);
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            kept = true;
            return target;
        } catch (IOException broken) {
            // Cancel closes the connection, so whatever was waiting on it fails: that is the
            // cancel arriving, not a network problem to report to somebody who pressed Cancel.
            if (progress.isCancelled()) {
                throw new Failed("The download was stopped.", true, broken);
            }
            throw new Failed("The download of " + asset.getName() + " failed: "
                    + broken.getMessage(), false, broken);
        } finally {
            if (!kept && part.exists() && !part.delete()) {
                part.deleteOnExit();
            }
        }
    }

    /** A file's SHA-256, lowercase hex. */
    public static String sha256(File file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16))
                    .append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Already failing for a better reason.
        }
    }
}
