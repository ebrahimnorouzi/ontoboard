package de.fizkarlsruhe.ise.ontoboard.assistant.roles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** A text resource shipped in the jar, read as UTF-8. */
final class Resources {

    private Resources() {
    }

    /** The text, or null when the jar does not have it. */
    static String text(String path) {
        InputStream in = Resources.class.getResourceAsStream(path);
        if (in == null) {
            return null;
        }
        try (InputStream body = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = body.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            // Normalised, so a resource checked out with CRLF reads the same as one with LF.
            return new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException unreadable) {
            return null;
        }
    }
}
