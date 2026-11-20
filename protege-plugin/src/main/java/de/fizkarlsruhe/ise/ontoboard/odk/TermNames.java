package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A project's choice of names for its new terms, written into its ODK configuration (1.155.0):
 * {@code ontoboard_term_names: readable} or {@code numbered}, and - for readable names - ODK's
 * {@code namespaces}. ODK's base release keeps only the terms its namespaces name,
 * {@code <uribase>/<ID>} when none are set, so a readable {@code <uribase>/<id>/BoiledEgg} would
 * be dropped from it; its report, told to go by the project's own terms, would not see it either.
 *
 * <p>Spliced, as {@link OdkYaml} edits: every other line of the file stays as it was, its line
 * endings too. ODK reads the configuration with dacite, not strictly, and ignores the key it does
 * not know, as OntoBoard's own rendering of ODK's templates does.
 */
public final class TermNames {

    private TermNames() {
    }

    /**
     * The configuration with the choice written: the key set where it is, added where it is not;
     * for readable names, the project's readable address in ODK's {@code namespaces}, beside
     * {@code <uribase>/<ID>_} where the configuration names none. Back to numbered, the
     * namespaces are kept: the readable terms made meanwhile stay at home in the base release.
     *
     * @param fileName the configuration's name, {@code <id>-odk.yaml}: the id where the file
     *                 gives none, and what is said
     * @throws IllegalArgumentException when ODK's {@code namespaces} is a list in brackets, which
     *                                  is not spliced: with what to add to it by hand
     */
    public static String choose(String configText, boolean readable, String fileName) {
        String newline = configText.contains("\r\n") ? "\r\n" : "\n";
        String text = configText.replace("\r\n", "\n");
        Map<String, String> values = valuesOf(text);
        String value = readable ? TermMinter.READABLE_NAMES : TermMinter.NUMBERED_NAMES;
        text = values.containsKey(TermMinter.TERM_NAMES_KEY)
                ? OdkYaml.withValue(text, TermMinter.TERM_NAMES_KEY, value)
                : withLines(text, TermMinter.TERM_NAMES_KEY + ": " + value);
        if (readable) {
            String id = idOf(values, fileName);
            String readableNamespace = TermMinter.projectAddress(values, id) + "/";
            String numbered = uribaseOf(values) + "/" + id.toUpperCase(Locale.ROOT) + "_";
            if (!values.containsKey("namespaces")) {
                text = withLines(text, "namespaces:\n  - " + numbered + "\n  - "
                        + readableNamespace);
            } else if (!listed(values, readableNamespace)) {
                if (inBrackets(text)) {
                    throw new IllegalArgumentException("ODK's namespaces in " + fileName + " is a "
                            + "list in brackets, which OntoBoard does not rewrite. Add "
                            + readableNamespace + " to it - by hand, in a text editor - so that "
                            + "ODK's base release keeps the terms named there, then choose "
                            + "readable names again.");
                }
                text = OdkYaml.appendTo(text, "namespaces", readableNamespace);
            }
        }
        return text.replace("\n", newline);
    }

    /** Every value by its path: {@code uribase}, {@code namespaces[1]}. */
    private static Map<String, String> valuesOf(String text) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (OdkYaml.Entry entry : OdkYaml.entriesIn(text)) {
            values.put(entry.getPath(), entry.getValue());
        }
        return values;
    }

    private static boolean listed(Map<String, String> values, String namespace) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith("namespaces[") && entry.getValue() != null
                    && entry.getValue().trim().equals(namespace)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the top-level {@code namespaces} is written {@code [ ... ]}. */
    private static boolean inBrackets(String text) {
        for (String line : text.split("\n")) {
            if (line.startsWith("namespaces:")) {
                return line.substring("namespaces:".length()).trim().startsWith("[");
            }
        }
        return false;
    }

    /** {@code text} with these lines added at its end, a top-level key and what is under it. */
    private static String withLines(String text, String lines) {
        return (text.isEmpty() || text.endsWith("\n") ? text : text + "\n") + lines + "\n";
    }

    private static String idOf(Map<String, String> values, String fileName) {
        String id = values.get("id");
        if (id != null && !id.trim().isEmpty()) {
            return id.trim();
        }
        String name = fileName == null ? "" : fileName;
        return name.endsWith("-odk.yaml") ? name.substring(0, name.length() - "-odk.yaml".length())
                : name;
    }

    private static String uribaseOf(Map<String, String> values) {
        String uribase = values.get("uribase");
        String base = uribase == null || uribase.trim().isEmpty() ? "http://purl.obolibrary.org/obo"
                : uribase.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }
}
