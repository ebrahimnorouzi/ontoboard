package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * ODK's own words for each key of a project's configuration, which Project configuration shows
 * beside the keys.
 *
 * <p>ODK v1.6.1's {@code project-schema.json} carries no descriptions: ODK's own documentation
 * is made from the docstring under each field of the dataclasses in its {@code odk.py}. Those
 * are bundled, flattened to the dotted keys a configuration file has
 * ({@code src/main/odk/v1.6.1/schema/descriptions.tsv}, made by {@code generate.py schema} from
 * ODK's source; ODK is BSD-3-Clause, NOTICE). A key of a list's item is said as the list's items
 * are: {@code import_group.products[2].module_type} has the words of
 * {@code import_group.products.module_type}.
 */
public final class OdkSchema {

    private static final String DESCRIPTIONS = "/odk/"
            + de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin.VERSION + "/schema/descriptions.tsv";

    /** Read once, on first use. */
    private static volatile Map<String, String> descriptions;

    private OdkSchema() {
    }

    /** ODK's description of a key of the configuration; null when ODK has none. */
    public static String help(String dottedKey) {
        if (dottedKey == null || dottedKey.trim().isEmpty()) {
            return null;
        }
        String words = descriptions().get(dottedKey.trim().replaceAll("\\[\\d+\\]", ""));
        // A field ODK gives no docstring is listed with nothing to say.
        return words == null || words.isEmpty() ? null : words;
    }

    /**
     * What Project configuration says under a key: ODK's words for it; for a key ODK has no
     * words for, those of the nearest key it sits under that has some - the entries of
     * {@code robot_report} are a dictionary ODK documents as a whole; else that ODK has none.
     */
    public static String line(String dottedKey) {
        String key = dottedKey == null ? "" : dottedKey.trim().replaceAll("\\[\\d+\\]", "");
        if (key.isEmpty()) {
            return "";
        }
        String own = help(key);
        if (own != null) {
            return key + ": " + own;
        }
        for (int dot = key.lastIndexOf('.'); dot > 0; dot = key.lastIndexOf('.', dot - 1)) {
            String outer = key.substring(0, dot);
            String words = help(outer);
            if (words != null) {
                return key + " sits under " + outer + ": " + words;
            }
        }
        return "ODK " + de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin.VERSION
                + " has no description of " + key + ".";
    }

    private static Map<String, String> descriptions() {
        Map<String, String> known = descriptions;
        if (known == null) {
            known = Collections.unmodifiableMap(read());
            descriptions = known;
        }
        return known;
    }

    private static Map<String, String> read() {
        Map<String, String> read = new HashMap<String, String>();
        InputStream in = OdkSchema.class.getResourceAsStream(DESCRIPTIONS);
        if (in == null) {
            return read;
        }
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(in,
                StandardCharsets.UTF_8))) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    read.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (IOException unreadable) {
            // A bundle without its descriptions: no help, and nothing else wrong.
        }
        return read;
    }
}
