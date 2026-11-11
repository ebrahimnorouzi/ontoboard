package de.fizkarlsruhe.ise.ontoboard.assistant;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * OntoBoard's version, as the build wrote it into {@code ontoboard-build.properties}: what a
 * benchmark result and a saved conversation name, so neither can drift from the jar.
 */
public final class BuildInfo {

    private BuildInfo() {
    }

    /** "1.137.0", or "unknown" outside a built jar. */
    public static String version() {
        InputStream in = BuildInfo.class.getClassLoader()
                .getResourceAsStream("ontoboard-build.properties");
        if (in == null) {
            return "unknown";
        }
        try (InputStream properties = in) {
            Properties read = new Properties();
            read.load(properties);
            String said = read.getProperty("ontoboard.version");
            return said == null || said.contains("${") ? "unknown" : said.trim();
        } catch (IOException unreadable) {
            return "unknown";
        }
    }
}
