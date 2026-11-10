package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.search.EntitySearcher;

/**
 * A label made from a term's name, for a pattern's term that has none.
 *
 * <p>Fifty-eight of the library's patterns have terms without an rdfs:label - the two Food Recipe
 * Ingredient Substitution patterns and Contextualized Winston Part-Whole have none at all. Imported,
 * their boxes read {@code food_recipe} and {@code PO-Component-Type}, and the label check a project
 * starts with failed every build from then on, over terms the project did not write. Found by the
 * tester who prepared the cookbook scenario.
 *
 * <p>Only in the module a pattern's import writes, and marked: each label carries an annotation
 * saying OntoBoard made it from the name, and the import's result lists them. The pattern itself is
 * not changed.
 */
public final class NamesAsLabels {

    /** What the annotation on a made label says. */
    public static final String MADE = "Made by OntoBoard from the term's name: the pattern gives"
            + " it no label.";

    private NamesAsLabels() {
    }

    /**
     * Labels every class and property of {@code module} that has no rdfs:label, in it or in
     * {@code project} and its imports - a second label from here would be a duplicate there.
     *
     * @param project the ontology the module is for, or null
     * @return the labels made, in name order
     */
    public static List<String> labelTheUnlabelled(OWLOntology module, OWLOntology project) {
        OWLDataFactory factory = module.getOWLOntologyManager().getOWLDataFactory();
        List<OWLEntity> terms = new ArrayList<OWLEntity>();
        terms.addAll(module.getClassesInSignature(Imports.EXCLUDED));
        terms.addAll(module.getObjectPropertiesInSignature(Imports.EXCLUDED));
        terms.addAll(module.getDataPropertiesInSignature(Imports.EXCLUDED));
        Collections.sort(terms);
        List<String> made = new ArrayList<String>();
        OWLAnnotation note = factory.getOWLAnnotation(factory.getRDFSComment(),
                factory.getOWLLiteral(MADE, "en"));
        for (OWLEntity term : terms) {
            if (term.isBuiltIn() || isStandard(term.getIRI())
                    || !EntitySearcher.getAnnotations(term, module, factory.getRDFSLabel())
                            .isEmpty()
                    || (project != null && labelledIn(project, term, factory))) {
                continue;
            }
            String label = labelFrom(term.getIRI());
            if (label.isEmpty()) {
                continue;
            }
            module.getOWLOntologyManager().addAxiom(module,
                    factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(), term.getIRI(),
                            factory.getOWLLiteral(label, "en"), Collections.singleton(note)));
            made.add(label);
        }
        Collections.sort(made, String.CASE_INSENSITIVE_ORDER);
        return made;
    }

    /**
     * The words of an IRI's name: {@code food_recipe} is "food recipe", {@code hasHalfMove} "has
     * half move", {@code PO-Component-Type} "PO component type". Capitals that make an acronym stay.
     */
    public static String labelFrom(IRI iri) {
        String text = iri.toString();
        String name = text.substring(Math.max(text.lastIndexOf('#'), text.lastIndexOf('/')) + 1);
        try {
            name = java.net.URLDecoder.decode(name, "UTF-8");
        } catch (Exception notEncoded) {
            // As it stands.
        }
        String spaced = name.replace('_', ' ').replace('-', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("\\s+", " ").trim();
        StringBuilder words = new StringBuilder();
        for (String word : spaced.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            boolean acronym = word.length() > 1 && word.equals(word.toUpperCase(Locale.ROOT))
                    && word.matches(".*[A-Z].*");
            if (words.length() > 0) {
                words.append(' ');
            }
            words.append(acronym ? word : word.toLowerCase(Locale.ROOT));
        }
        return words.toString();
    }

    private static boolean labelledIn(OWLOntology project, OWLEntity term,
            OWLDataFactory factory) {
        for (OWLOntology part : project.getImportsClosure()) {
            if (!EntitySearcher.getAnnotations(term, part, factory.getRDFSLabel()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStandard(IRI iri) {
        String text = iri.toString();
        return text.startsWith("http://www.w3.org/");
    }
}
