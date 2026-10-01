package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * The short, prefixed name for an IRI - {@code obo:BFO_0000023} rather than
 * {@code http://purl.obolibrary.org/obo/BFO_0000023}.
 *
 * <p>A canvas node showed only a label, which for an OBO ontology is the one thing an editor
 * cannot cite: labels change, identifiers do not, and a reviewer asking "which term is that?"
 * wants the identifier. Showing both is what Protege's own entity list does.
 *
 * <p>Prefixes come from the ontology itself first, because that is what the author chose and what
 * their file writes. The built-in table is a fallback for the handful every OBO project uses and
 * some serialisations omit - a Turtle file always declares its prefixes, an RDF/XML one often
 * declares them as XML namespaces that OWL API does surface, and a functional-syntax file may
 * carry none at all.
 */
public final class Curies {

    /**
     * The prefixes assumed when an ontology declares none of its own.
     *
     * <p>Deliberately short. A long table would turn an unrecognised IRI into a confidently wrong
     * abbreviation, and the cost of missing one is only that the full short name is shown.
     */
    private static final Map<String, String> WELL_KNOWN;

    static {
        Map<String, String> known = new LinkedHashMap<String, String>();
        known.put("obo", "http://purl.obolibrary.org/obo/");
        known.put("oboInOwl", "http://www.geneontology.org/formats/oboInOwl#");
        known.put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        known.put("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        known.put("owl", "http://www.w3.org/2002/07/owl#");
        known.put("xsd", "http://www.w3.org/2001/XMLSchema#");
        known.put("skos", "http://www.w3.org/2004/02/skos/core#");
        known.put("dc", "http://purl.org/dc/elements/1.1/");
        known.put("dcterms", "http://purl.org/dc/terms/");
        known.put("foaf", "http://xmlns.com/foaf/0.1/");
        known.put("prov", "http://www.w3.org/ns/prov#");
        WELL_KNOWN = Collections.unmodifiableMap(known);
    }

    private Curies() {
    }

    /** The prefix map this ontology declares, over the built-in defaults. */
    public static Map<String, String> prefixesOf(OWLOntology ontology) {
        Map<String, String> prefixes = new LinkedHashMap<String, String>(WELL_KNOWN);
        if (ontology == null) {
            return prefixes;
        }
        OWLDocumentFormat format = ontology.getOWLOntologyManager().getOntologyFormat(ontology);
        if (format instanceof PrefixDocumentFormat) {
            for (Map.Entry<String, String> declared
                    : ((PrefixDocumentFormat) format).getPrefixName2PrefixMap().entrySet()) {
                String name = declared.getKey();
                // OWL API hands back prefix names with their colon attached, and the default
                // prefix as ":" alone. A node reading ":0000042" says less than the short name
                // does, so the default prefix is left out.
                if (name == null || name.length() < 2 || !name.endsWith(":")) {
                    continue;
                }
                prefixes.put(name.substring(0, name.length() - 1), declared.getValue());
            }
        }
        return prefixes;
    }

    /**
     * {@code obo:BFO_0000023}, or null when no prefix matches.
     *
     * <p>The longest matching namespace wins. Without that, an ontology declaring both
     * {@code obo:} and a more specific {@code bfo: http://purl.obolibrary.org/obo/BFO_} would get
     * whichever the map happened to yield first, and the same term would be named two ways in one
     * diagram depending on iteration order.
     *
     * <p>A match whose remainder contains {@code /} or {@code #} is rejected: that is not a local
     * name, it is a deeper path, and {@code obo:uberon/releases/2024-01-01/uberon.owl} is not a
     * CURIE anybody wants to read.
     */
    public static String curieFor(Map<String, String> prefixes, String iri) {
        if (prefixes == null || iri == null || iri.isEmpty()) {
            return null;
        }
        String bestPrefix = null;
        String bestNamespace = null;
        for (Map.Entry<String, String> candidate : prefixes.entrySet()) {
            String namespace = candidate.getValue();
            if (namespace == null || namespace.isEmpty() || !iri.startsWith(namespace)) {
                continue;
            }
            String local = iri.substring(namespace.length());
            if (local.isEmpty() || local.indexOf('/') >= 0 || local.indexOf('#') >= 0) {
                continue;
            }
            if (bestNamespace == null || namespace.length() > bestNamespace.length()) {
                bestNamespace = namespace;
                bestPrefix = candidate.getKey();
            }
        }
        return bestNamespace == null ? null : bestPrefix + ":" + iri.substring(
                bestNamespace.length());
    }

    /** {@link #curieFor} against this ontology's own prefixes. */
    public static String curieFor(OWLOntology ontology, IRI iri) {
        return iri == null ? null : curieFor(prefixesOf(ontology), iri.toString());
    }

    /**
     * What a node shows: the identifier, then the label, when they differ.
     *
     * <p>Two lines rather than one. {@code obo:BFO_0000023 (role)} is the way it reads in prose,
     * but a node box is about 160px and that string is not: on one line every identified term
     * would either widen the diagram or be elided down to the part that is the same for all of
     * them. The identifier goes first because it is the fixed-width part, so a column of nodes
     * lines up.
     *
     * <p>Only one line when the label adds nothing - no label, or a label that is already the
     * local name, which is what an ontology without {@code rdfs:label} produces.
     */
    public static String displayLabel(String curie, String label) {
        String text = label == null ? "" : label.trim();
        if (curie == null || curie.isEmpty()) {
            return text;
        }
        if (text.isEmpty() || sameTerm(curie, text)) {
            return curie;
        }
        return curie + "\n" + text;
    }

    /** Whether the label is just the identifier's local part, so printing both would repeat it. */
    private static boolean sameTerm(String curie, String label) {
        int colon = curie.indexOf(':');
        String local = colon < 0 ? curie : curie.substring(colon + 1);
        return local.equals(label);
    }
}
