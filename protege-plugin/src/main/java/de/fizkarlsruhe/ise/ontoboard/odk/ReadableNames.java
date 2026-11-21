package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.axiom.EntityFactory;
import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.search.EntitySearcher;

/**
 * Readable names from labels (1.155.0): the IRIs a project that chose readable names gives its
 * numbered terms that no release has carried - "it's annoying to change the IRIs always", the
 * user said. The name is the one {@link TermMinter} gives a new term typed as the label. A term
 * a release carried keeps its IRI, because somebody may use it; a term without a label, or whose
 * name another term has or would get, is left and said.
 */
public final class ReadableNames {

    /** The renames, and what is left with why. */
    public static final class Plan {
        private final Map<String, String> mappings;
        private final List<String> left;

        Plan(Map<String, String> mappings, List<String> left) {
            this.mappings = Collections.unmodifiableMap(mappings);
            this.left = Collections.unmodifiableList(left);
        }

        /** Old IRI to new, in the order of the old. */
        public Map<String, String> mappings() {
            return mappings;
        }

        /** A sentence for each term left as it is. */
        public List<String> left() {
            return left;
        }
    }

    private ReadableNames() {
    }

    /**
     * @param numberedPrefix the ranges' prefix, {@code <uribase>/<ID>_}
     * @param namespace      where readable names go, ending in a slash
     * @param released       the identifiers releases carried, whole
     */
    public static Plan plan(OWLOntology ontology, String numberedPrefix, String namespace,
            Set<String> released) {
        Map<String, OWLEntity> numbered = new TreeMap<String, OWLEntity>();
        for (OWLEntity entity : ontology.getSignature(Imports.EXCLUDED)) {
            String iri = entity.getIRI().toString();
            if (iri.startsWith(numberedPrefix)
                    && iri.substring(numberedPrefix.length()).matches("[0-9]+")
                    && !entity.isOWLDatatype()) {
                numbered.put(iri, entity);
            }
        }
        List<String> left = new ArrayList<String>();
        Map<String, String> wanted = new LinkedHashMap<String, String>();
        Map<String, List<String>> byName = new LinkedHashMap<String, List<String>>();
        Map<String, String> labels = new LinkedHashMap<String, String>();
        for (Map.Entry<String, OWLEntity> term : numbered.entrySet()) {
            String iri = term.getKey();
            String shortName = iri.substring(Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'))
                    + 1);
            String label = DisplayLabels.chooseFrom(EntitySearcher.getAnnotations(
                    term.getValue(), ontology,
                    ontology.getOWLOntologyManager().getOWLDataFactory().getRDFSLabel()));
            if (released.contains(iri)) {
                left.add((label == null ? shortName : label + " (" + shortName + ")")
                        + " keeps its IRI: a release carried it, and somebody may use it.");
                continue;
            }
            if (label == null || label.trim().isEmpty()) {
                left.add(shortName + " has no label to make a name from.");
                continue;
            }
            String name;
            try {
                name = namespace + TermMinter.readableName(label, kindOf(term.getValue()));
            } catch (IllegalArgumentException noName) {
                left.add(label + " (" + shortName + "): " + noName.getMessage());
                continue;
            }
            if (!ontology.getEntitiesInSignature(IRI.create(name), Imports.INCLUDED).isEmpty()) {
                left.add(label + " (" + shortName + ") would be " + name
                        + ", which another term is named already.");
                continue;
            }
            wanted.put(iri, name);
            labels.put(iri, label + " (" + shortName + ")");
            List<String> sharing = byName.get(name);
            if (sharing == null) {
                sharing = new ArrayList<String>();
                byName.put(name, sharing);
            }
            sharing.add(iri);
        }
        Map<String, String> mappings = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> rename : wanted.entrySet()) {
            List<String> sharing = byName.get(rename.getValue());
            if (sharing.size() > 1) {
                left.add(labels.get(rename.getKey()) + " would be "
                        + rename.getValue() + ", as " + (sharing.size() - 1)
                        + " other term" + (sharing.size() == 2 ? "" : "s")
                        + " would: give one of them another label first.");
                continue;
            }
            mappings.put(rename.getKey(), rename.getValue());
        }
        return new Plan(mappings, left);
    }

    /** How a term's name begins: a class in upper case, anything else in lower case. */
    private static EntityFactory.Kind kindOf(OWLEntity entity) {
        if (entity.isOWLClass()) {
            return EntityFactory.Kind.CLASS;
        }
        return entity.isOWLNamedIndividual() ? EntityFactory.Kind.INDIVIDUAL
                : EntityFactory.Kind.OBJECT_PROPERTY;
    }
}
