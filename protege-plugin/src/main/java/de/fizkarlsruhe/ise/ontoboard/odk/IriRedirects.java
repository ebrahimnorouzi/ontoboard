package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * An address ROBOT loads an ontology from ({@code -I}, {@code --input-iri}), taken through a
 * redirect from http to https before ROBOT is given it.
 *
 * <p>w3.org answers {@code http://www.w3.org/ns/dcat3} with "301 Moved Permanently" to the same
 * address in https - ODK's mirror of DCAT is loaded from it. Java follows a redirect within a
 * protocol and not from http to https, and the OWL API of Protégé 5.5.0 (4.5.9) leaves that to
 * Java: ROBOT read W3C's page about the move as the ontology and stopped on its markup. ODK's
 * OWL API (4.5.29) follows it. So the moves are followed here as far as the first one to https,
 * and ROBOT gets that address; one within https is Java's to follow, as before. Anything else -
 * an address that answers, or none at all - is handed over as written, and ROBOT says what it
 * says of it (nfdicore's refresh-dcat, found by the live run of 1.154.0).
 */
final class IriRedirects {

    private static final int HOPS = 5;
    /** Long enough for a server that answers; a probe that is not answered is given up soon. */
    private static final int TIMEOUT_MILLIS = 5000;
    /** What the OWL API asks for when it loads from an address. */
    private static final String ACCEPT = "application/rdf+xml, application/xml; q=0.7, "
            + "text/xml; q=0.6, text/plain; q=0.1, */*; q=0.09";

    private IriRedirects() {
    }

    /** The https address an http one moves to, or the address as written. */
    static String acrossToHttps(String iri) {
        if (!startsWith(iri, "http:")) {
            return iri;
        }
        String at = iri;
        for (int hop = 0; hop < HOPS; hop++) {
            String next = movedTo(at);
            if (next == null) {
                return iri;
            }
            if (!startsWith(next, "http:")) {
                return startsWith(next, "https:") ? next : iri;
            }
            // A move within http: on to where it leads, for one to https after it.
            at = next;
        }
        return iri;
    }

    /**
     * Whether an OWL API of this version follows a move from http to https itself: 4.5.29 -
     * ODK's, Protégé 5.6.9's - and later do; 4.5.9, Protégé 5.5.0's, does not. A version that
     * cannot be read is taken for one that does not, and its addresses are probed.
     */
    static boolean followsMovesItself(String owlApiVersion) {
        java.util.regex.Matcher number = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)")
                .matcher(owlApiVersion == null ? "" : owlApiVersion.trim());
        if (!number.find()) {
            return false;
        }
        int major = Integer.parseInt(number.group(1));
        int minor = Integer.parseInt(number.group(2));
        int patch = Integer.parseInt(number.group(3));
        return major > 4 || major == 4 && (minor > 5 || minor == 5 && patch >= 29);
    }

    /**
     * A ROBOT line with the value of each {@code -I} and {@code --input-iri} through those moves,
     * where the OWL API does not follow them itself: where it does, nothing is probed - offline,
     * or behind a firewall that answers nothing, each probe waited for nothing (the 1.154.0
     * review's M6).
     *
     * @param across where an address leads, for one the OWL API does not follow itself
     */
    static List<String> inputIrisAcrossToHttps(List<String> arguments, boolean followsItself,
            java.util.function.UnaryOperator<String> across) {
        List<String> line = new ArrayList<String>(arguments);
        if (followsItself) {
            return line;
        }
        for (int i = 0; i + 1 < line.size(); i++) {
            if (line.get(i).equals("-I") || line.get(i).equals("--input-iri")) {
                line.set(i + 1, across.apply(line.get(i + 1)));
            }
        }
        return line;
    }

    /** A ROBOT line with the value of each {@code -I} and {@code --input-iri} through those moves. */
    static List<String> inputIrisAcrossToHttps(List<String> arguments) {
        return inputIrisAcrossToHttps(arguments, followsMovesItself(owlApiVersion()),
                IriRedirects::acrossToHttps);
    }

    /** The version of the OWL API this runs with; empty where it cannot be told. */
    private static String owlApiVersion() {
        try {
            String version = org.semanticweb.owlapi.util.VersionInfo.getVersionInfo().getVersion();
            return version == null ? "" : version;
        } catch (RuntimeException | LinkageError unknown) {
            return "";
        }
    }

    /** Where the address says it has moved to; null when it answers otherwise, or not at all. */
    private static String movedTo(String address) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("HEAD");
                connection.setRequestProperty("Accept", ACCEPT);
                connection.setConnectTimeout(TIMEOUT_MILLIS);
                connection.setReadTimeout(TIMEOUT_MILLIS);
                int status = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                return status >= 300 && status < 400 && location != null
                        ? new URL(new URL(address), location).toString() : null;
            } finally {
                connection.disconnect();
            }
        } catch (IOException | RuntimeException unanswered) {
            return null;
        }
    }

    private static boolean startsWith(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
