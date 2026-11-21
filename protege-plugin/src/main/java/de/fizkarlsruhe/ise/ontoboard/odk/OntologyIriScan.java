package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An ontology file's ontology IRI, read from its head without parsing it (1.155.0): which file
 * a release tag holds under a name of its own - nfdicore's v1.0.0 holds nfdico.ttl - is found by
 * the IRI it declares, and reading every candidate whole with the OWL API would be what History
 * waits on.
 */
final class OntologyIriScan {

    /** How much of a file is read: an ontology's header comes first in every syntax read here. */
    private static final int HEAD = 512 * 1024;

    private static final Pattern[] DECLARATIONS = {
        // Turtle: <iri> a owl:Ontology / <iri> rdf:type owl:Ontology
        Pattern.compile("<([^>\\s]+)>\\s+(?:a|rdf:type)\\s+owl:Ontology"),
        // RDF/XML
        Pattern.compile("<owl:Ontology[^>]*?rdf:about=\"([^\"]+)\""),
        // OWL/XML
        Pattern.compile("<Ontology[^>]*?ontologyIRI=\"([^\"]+)\""),
        // Functional syntax
        Pattern.compile("Ontology\\(\\s*<([^>\\s]+)>"),
    };

    /** OBO: "ontology: egg" names http://purl.obolibrary.org/obo/egg.owl. */
    private static final Pattern OBO = Pattern.compile("(?m)^ontology:\\s*(\\S+)");

    private OntologyIriScan() {
    }

    /** The ontology IRI the text's head declares, or null when it declares none read here. */
    static String of(byte[] text) {
        if (text == null) {
            return null;
        }
        String head = new String(text, 0, Math.min(text.length, HEAD), StandardCharsets.UTF_8);
        int first = -1;
        String found = null;
        for (Pattern declaration : DECLARATIONS) {
            Matcher matcher = declaration.matcher(head);
            if (matcher.find() && (first < 0 || matcher.start() < first)) {
                first = matcher.start();
                found = matcher.group(1);
            }
        }
        if (found != null) {
            return found;
        }
        Matcher obo = OBO.matcher(head);
        return obo.find() ? "http://purl.obolibrary.org/obo/" + obo.group(1) + ".owl" : null;
    }

    /** The ontology IRI a file on disk declares, or null. */
    static String of(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] head = new byte[HEAD];
            int read = 0;
            for (int n; read < head.length && (n = in.read(head, read, head.length - read)) > 0;) {
                read += n;
            }
            return of(java.util.Arrays.copyOf(head, read));
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * Whether two ontology IRIs name the same ontology: alike but for http and https, and a
     * trailing slash or hash - nfdicore moved from http to https after its first release.
     */
    static boolean same(String one, String other) {
        return one != null && other != null && plain(one).equals(plain(other));
    }

    private static String plain(String iri) {
        String plain = iri.trim().replaceFirst("^(?i)https?://", "");
        while (plain.endsWith("/") || plain.endsWith("#")) {
            plain = plain.substring(0, plain.length() - 1);
        }
        return plain;
    }
}
