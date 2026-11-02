package de.fizkarlsruhe.ise.ontoboard.pattern;

import de.fizkarlsruhe.ise.ontoboard.odk.ImportModules;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * The patterns from OntoBoard's library that an ontology imports or has merged in, each with its
 * classes.
 *
 * <p>Asked for: "I opened the pizza ontology, added all to the canvas, and couldn't see which parts
 * belong to which pattern." The board framed a pattern only at the moment it was imported through
 * the library, in that session - so an ontology imported in another session, by a script, or by a
 * colleague, came onto the board as one undivided diagram. The ontology itself says which imports
 * are patterns; this reads it.
 *
 * <p><b>How an import is recognised as a pattern.</b> By its term list's {@code # Pattern:} line,
 * which the library's import writes ({@link ImportModules#patternIn}); failing that, by the module
 * being named after a pattern in the library, {@code imports/<id>_import.owl}, which is what the
 * library's import has always produced. Either way only the pattern's own classes that the module
 * actually holds are reported, so a module that merely shares a pattern's name - a
 * {@code time_import.owl} cut from somebody else's ontology - holds none of them and is left out.
 */
public final class ImportedPatterns {

    private static final String MODULE_SUFFIX = "_import.owl";
    private static final String TERMS_SUFFIX = "_terms.txt";

    private ImportedPatterns() {
    }

    /** One imported pattern. */
    public static final class Found {
        private final DesignPattern pattern;
        private final List<String> classes;

        Found(DesignPattern pattern, List<String> classes) {
            this.pattern = pattern;
            this.classes = Collections.unmodifiableList(new ArrayList<String>(classes));
        }

        public DesignPattern getPattern() {
            return pattern;
        }

        /** The pattern's name, which is what a frame for it is labelled. */
        public String getLabel() {
            return pattern.getName();
        }

        /** The pattern's classes the import brought in, in the pattern's order. */
        public List<String> getClasses() {
            return classes;
        }
    }

    /**
     * The library patterns {@code ontology} uses, ordered by name: the ones it imports, and the
     * ones whose classes it holds in the pattern's own namespace.
     *
     * <p><b>Why the namespace as well as the imports.</b> A release merges its imports, so the
     * {@code pizza.owl} a reader opens first imports nothing and would show no pattern at all -
     * while every class it took from a pattern still carries that pattern's IRI. A namespace is
     * taken as a pattern's when it is named after one - {@code .../componency.owl#} is
     * Componency's - and the class is one of that pattern's terms. Not by which namespace most of
     * a pattern's terms are in: the index lists the terms a pattern takes from the patterns it
     * imports, and measured that way Agentrole lives in Objectrole's namespace and seven patterns
     * claim Classification's. OBO's namespace names no pattern, so a BFO class claims none of the
     * BFO-based ones.
     */
    public static List<Found> in(OWLOntology ontology) {
        List<Found> found = new ArrayList<Found>();
        if (ontology == null) {
            return found;
        }
        java.util.Map<String, List<String>> byPattern =
                new java.util.LinkedHashMap<String, List<String>>();
        java.util.Map<String, DesignPattern> patterns =
                new java.util.LinkedHashMap<String, DesignPattern>();
        Set<String> placed = new java.util.HashSet<String>();
        for (OWLOntology module : ontology.getImports()) {
            DesignPattern pattern = patternOf(module);
            if (pattern == null || patterns.containsKey(pattern.getId())) {
                continue;
            }
            List<String> classes = classesIn(pattern, module);
            if (!classes.isEmpty()) {
                patterns.put(pattern.getId(), pattern);
                byPattern.put(pattern.getId(), new ArrayList<String>(classes));
                placed.addAll(classes);
            }
        }

        for (OWLEntity entity : ontology.getClassesInSignature(Imports.INCLUDED)) {
            String iri = entity.getIRI().toString();
            DesignPattern pattern = patternNamed(namespaceOf(iri));
            if (pattern == null || placed.contains(iri) || !pattern.getTermIris().contains(iri)) {
                continue;
            }
            patterns.put(pattern.getId(), pattern);
            List<String> classes = byPattern.get(pattern.getId());
            if (classes == null) {
                classes = new ArrayList<String>();
                byPattern.put(pattern.getId(), classes);
            }
            classes.add(iri);
            placed.add(iri);
        }

        // Merged in by content: a pattern written in the ontology's own terms - as the patterns
        // harvested from published ontologies are - has no namespace of its own to be found by.
        reusedByContent(ontology, patterns, byPattern, placed);

        for (java.util.Map.Entry<String, List<String>> entry : byPattern.entrySet()) {
            DesignPattern pattern = patterns.get(entry.getKey());
            found.add(new Found(pattern, inPatternOrder(pattern, entry.getValue())));
        }
        Collections.sort(found, new Comparator<Found>() {
            @Override
            public int compare(Found one, Found other) {
                return one.getLabel().compareToIgnoreCase(other.getLabel());
            }
        });
        return found;
    }

    /** A term in more library patterns than this is upper-level, not a sign of one pattern. */
    static final int COMMON = 4;

    /** How much of a pattern's distinctive vocabulary an ontology must use to be reusing it. */
    static final double SHARE = 0.6;

    private static java.util.Map<String, Integer> patternsPerTerm;

    /** For each term, how many library patterns use it. Counted once. */
    private static synchronized java.util.Map<String, Integer> patternsPerTerm() {
        if (patternsPerTerm == null) {
            java.util.Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
            for (DesignPattern pattern : PatternLibrary.all()) {
                for (String term : new java.util.HashSet<String>(pattern.getTermIris())) {
                    Integer was = counts.get(term);
                    counts.put(term, was == null ? 1 : was + 1);
                }
            }
            patternsPerTerm = counts;
        }
        return patternsPerTerm;
    }

    /**
     * Patterns the ontology reuses with their terms merged in: those whose distinctive terms -
     * the ones at most {@link #COMMON} library patterns use, so BFO's entity and continuant do
     * not count - the ontology mostly declares: at least two, and {@link #SHARE} of them. The
     * pattern that matches most is framed first, and a class is in one frame only.
     *
     * <p>Asked for: "if the ontology reuses the patterns, the board should show them" - and the
     * NFDIcore and MWO patterns in the library are written in those ontologies' own IRIs, so
     * opening NFDIcore or MWO showed none of the patterns taken from them.
     */
    private static void reusedByContent(OWLOntology ontology,
            java.util.Map<String, DesignPattern> patterns,
            java.util.Map<String, List<String>> byPattern, Set<String> placed) {
        Set<String> declared = new java.util.HashSet<String>();
        Set<String> classes = new java.util.HashSet<String>();
        for (OWLEntity entity : ontology.getSignature(Imports.INCLUDED)) {
            declared.add(entity.getIRI().toString());
            if (entity.isOWLClass()) {
                classes.add(entity.getIRI().toString());
            }
        }
        java.util.Map<String, Integer> common = patternsPerTerm();
        List<Object[]> matches = new ArrayList<Object[]>();
        for (DesignPattern pattern : PatternLibrary.all()) {
            if (patterns.containsKey(pattern.getId())) {
                continue;
            }
            int distinctive = 0;
            int present = 0;
            for (String term : new java.util.HashSet<String>(pattern.getTermIris())) {
                Integer shared = common.get(term);
                if (shared != null && shared > COMMON) {
                    continue;
                }
                distinctive++;
                present += declared.contains(term) ? 1 : 0;
            }
            if (present >= 2 && present >= SHARE * distinctive) {
                matches.add(new Object[] {pattern, present});
            }
        }
        Collections.sort(matches, (a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));
        for (Object[] match : matches) {
            DesignPattern pattern = (DesignPattern) match[0];
            List<String> mine = new ArrayList<String>();
            for (String term : pattern.getTermIris()) {
                Integer shared = common.get(term);
                if (classes.contains(term) && !placed.contains(term)
                        && (shared == null || shared <= COMMON) && !mine.contains(term)) {
                    mine.add(term);
                }
            }
            if (mine.size() < 2) {
                continue;
            }
            patterns.put(pattern.getId(), pattern);
            byPattern.put(pattern.getId(), mine);
            placed.addAll(mine);
        }
    }

    /** The library pattern a module was cut from, or null when it is not one. */
    static DesignPattern patternOf(OWLOntology module) {
        File moduleFile = fileOf(module);
        if (moduleFile != null && moduleFile.getName().endsWith(MODULE_SUFFIX)) {
            String name = moduleFile.getName();
            File terms = new File(moduleFile.getParentFile(),
                    name.substring(0, name.length() - MODULE_SUFFIX.length()) + TERMS_SUFFIX);
            try {
                String id = ImportModules.patternIn(terms);
                if (id != null && PatternLibrary.find(id) != null) {
                    return PatternLibrary.find(id);
                }
            } catch (IOException unreadable) {
                // Fall through to the name, which is what every older import has to go by.
            }
        }
        com.google.common.base.Optional<IRI> iri = module.getOntologyID().getOntologyIRI();
        if (iri.isPresent()) {
            String text = iri.get().toString();
            if (text.endsWith(MODULE_SUFFIX)) {
                String id = text.substring(Math.max(text.lastIndexOf('/'), text.lastIndexOf('#'))
                        + 1, text.length() - MODULE_SUFFIX.length());
                return PatternLibrary.find(id);
            }
        }
        return null;
    }

    /** The library pattern a namespace is named after - {@code .../componency.owl#} - or null. */
    static DesignPattern patternNamed(String namespace) {
        String trimmed = namespace;
        while (trimmed.endsWith("#") || trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String segment = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        if (segment.endsWith(".owl")) {
            segment = segment.substring(0, segment.length() - ".owl".length());
        }
        return segment.isEmpty() ? null : PatternLibrary.find(segment);
    }

    /** An IRI's namespace: up to and including its last {@code #}, or else its last {@code /}. */
    static String namespaceOf(String iri) {
        int hash = iri.lastIndexOf('#');
        return iri.substring(0, (hash >= 0 ? hash : iri.lastIndexOf('/')) + 1);
    }

    /** {@code classes} in the order the pattern lists its terms, so a frame reads like it. */
    private static List<String> inPatternOrder(DesignPattern pattern, List<String> classes) {
        List<String> ordered = new ArrayList<String>();
        for (String term : pattern.getTermIris()) {
            if (classes.contains(term)) {
                ordered.add(term);
            }
        }
        for (String term : classes) {
            if (!ordered.contains(term)) {
                ordered.add(term);
            }
        }
        return ordered;
    }

    /** The pattern's classes that the module holds. */
    private static List<String> classesIn(DesignPattern pattern, OWLOntology module) {
        List<String> classes = new ArrayList<String>();
        Set<IRI> held = new LinkedHashSet<IRI>();
        for (OWLEntity entity : module.getClassesInSignature(Imports.EXCLUDED)) {
            held.add(entity.getIRI());
        }
        try {
            for (OWLEntity entity : PatternLibrary.contentsOf(pattern).getClasses()) {
                if (held.contains(entity.getIRI())) {
                    classes.add(entity.getIRI().toString());
                }
            }
        } catch (IOException unreadable) {
            return Collections.emptyList();
        }
        return classes;
    }

    private static File fileOf(OWLOntology module) {
        try {
            IRI document = module.getOWLOntologyManager().getOntologyDocumentIRI(module);
            return document != null && "file".equals(document.getScheme())
                    ? new File(document.toURI()) : null;
        } catch (RuntimeException notAFile) {
            return null;
        }
    }
}
