package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The ODK configuration of a project OntoBoard creates: the wizard's answers, as the YAML that
 * ODK reads.
 *
 * <p>This is the one file of a new project that is OntoBoard's. Everything else is written by
 * ODK from it - by its container, or by its templates bundled here - so what a new project is,
 * is decided on these twenty lines:
 *
 * <ul>
 *   <li>{@code uribase} is the Base IRI without {@code /<id>.owl}, because ODK names the ontology
 *       {@code <uribase>/<id>.owl} and the terms {@code <uribase>/<ID>_0000001}.
 *   <li>{@code custom_profile: TRUE}, so ODK's seed writes {@code profile.txt} into the project
 *       and both ODK's report and OntoBoard's Quality report read that one file.
 *   <li>ODK's four SPARQL checks are named, and OntoBoard's label check after them, as an ODK
 *       check like any other ({@code src/sparql/check_labels-violation.sparql}).
 *   <li>Release artefacts and export formats are not named: ODK's defaults apply.
 *   <li>No {@code documentation:} block. ODK then writes no pages of its own, and the pages
 *       OntoBoard adds ({@link OdkExtras}) cannot collide with ODK's.
 * </ul>
 *
 * <p>The text is fixed to the byte, because ODK's Makefile records the configuration's hash:
 * the egg project's configuration is kept as a golden, and ODK's own seed of it is what the
 * parity tests compare against. OntoBoard's version is deliberately not in it - a project made
 * by a later OntoBoard is the same project.
 */
public final class OdkConfigWriter {

    private static final String WRITTEN_BY = ", written by OntoBoard for ODK ";

    /** Plain words YAML 1.1, which ODK's PyYAML speaks, reads as something other than text. */
    private static final Set<String> NOT_TEXT = new HashSet<String>(Arrays.asList(
            "y", "n", "yes", "no", "on", "off", "true", "false", "null", "~"));

    private OdkConfigWriter() {
    }

    /**
     * The configuration for a new project.
     *
     * @throws IllegalArgumentException when the Base IRI is not {@code <address>/<id>.owl} and
     *                                  the project does not start from an existing ontology -
     *                                  in words for the wizard's form
     */
    public static String forNewProject(OdkProjectConfig config) {
        String id = config.getOntologyId();
        return header(config.getTitle())
                + "id: " + plain(id) + "\n"
                + "title: " + quoted(config.getTitle()) + "\n"
                + "description: " + quoted(config.getDescription()) + "\n"
                + "uribase: " + plain(uriBase(config)) + "\n"
                + "license: " + plain(config.getLicense()) + "\n"
                + "repo: " + plain(id) + "\n"
                + "robot_report:\n"
                + "  use_labels: TRUE\n"
                + "  fail_on: ERROR\n"
                + "  custom_profile: TRUE\n"
                + "  report_on:\n"
                + "    - edit\n"
                + "  custom_sparql_checks:\n"
                + "    - owldef-self-reference\n"
                + "    - iri-range\n"
                + "    - label-with-iri\n"
                + "    - multiple-replaced_by\n"
                + "    - " + OdkExtras.LABEL_CHECK + "\n";
    }

    /**
     * The two comment lines a configuration OntoBoard writes for ODK opens with - a new
     * project's, and one of the older layout that is carried over ({@link OdkMigration}).
     */
    static String header(String title) {
        return "# ODK configuration for " + oneLine(title) + WRITTEN_BY + OdkPin.VERSION
                + ".\n"
                + "# After changing it, run OntoBoard > Project > Update project files... "
                + "(ODK: sh run.sh update_repo).\n";
    }

    /**
     * Whether a configuration was written by OntoBoard: its first line says so. It still does
     * after the file was edited, by hand or by OntoBoard, as long as that line was left.
     */
    public static boolean isOntoBoards(String configText) {
        String text = configText.startsWith("﻿") ? configText.substring(1) : configText;
        int end = text.indexOf('\n');
        String first = (end < 0 ? text : text.substring(0, end)).trim();
        return first.matches("# ODK configuration for .*" + WRITTEN_BY + "\\S+\\.");
    }

    /**
     * ODK's {@code uribase} for a project: its Base IRI without {@code /<id>.owl}.
     *
     * <p>A project that starts from an existing ontology keeps that ontology's IRI whatever its
     * shape - changing it would rename the ontology - and its uribase is then the address the
     * IRI lives at.
     */
    static String uriBase(OdkProjectConfig config) {
        String iri = config.getBaseIri();
        String id = config.getOntologyId();
        String file = "/" + id + ".owl";
        if (iri.endsWith(file) && iri.length() > file.length()) {
            return iri.substring(0, iri.length() - file.length());
        }
        if (config.isAdopted()) {
            int cut = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
            // "https://example.org": the last slash is the scheme's, and there is nothing above.
            return cut < 0 || iri.startsWith("://", cut - 2) ? iri : iri.substring(0, cut);
        }
        throw new IllegalArgumentException("The Base IRI must be an address followed by " + file
                + ", for example 'https://example.org/" + id + file + "'. Got: '" + iri
                + "'. ODK writes the ontology's IRI as <address>" + file + " into every file it "
                + "makes, and names the terms <address>/" + id.toUpperCase(Locale.ROOT)
                + "_0000001, so an IRI of another shape would not be the one the project uses.");
    }

    /**
     * ODK's {@code ONTBASE}: what the project's files other than the ontology itself are named
     * under - its releases, its import modules. {@code <uribase>/<id>}.
     */
    static String ontBase(OdkProjectConfig config) {
        return uriBase(config) + "/" + config.getOntologyId();
    }

    /** The IRI ODK gives the ontology in everything it builds: {@code <uribase>/<id>.owl}. */
    static String odkIri(OdkProjectConfig config) {
        return ontBase(config) + ".owl";
    }

    /** A value as a plain YAML scalar when it can be one, in double quotes when it cannot. */
    static String plain(String value) {
        boolean plain = value.matches("[A-Za-z][^\\s\\p{Cntrl}\u0085  ]*")
                && !value.endsWith(":") && !NOT_TEXT.contains(value.toLowerCase(Locale.ROOT));
        return plain ? value : quoted(value);
    }

    /** A value in YAML's double quotes: whatever it holds, it is read back as that text. */
    static String quoted(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c < 0x20 || c == 0x7F || c == 0x85 || c == 0x2028 || c == 0x2029
                    || c == 0xFEFF) {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** A title for a comment or a heading: a line break in it would start a line of its own. */
    static String oneLine(String title) {
        return title.replaceAll("[\\r\\n\u0085  ]+", " ");
    }
}
