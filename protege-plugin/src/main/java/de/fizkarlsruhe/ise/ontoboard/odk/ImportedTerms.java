package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Which terms an ontology takes from other ontologies, grouped by where each one comes from.
 *
 * <p>Asked for: "how can a user see which terms are imported from which ontologies, and add or
 * remove terms imported before, seeing the terms and their labels". The answer was spread over
 * four places - a module file, a term list of bare IRIs, the project's settings and the imports
 * dialog - and none of them said which terms somebody chose and which came along because a chosen
 * term needs them. This puts them in one list per source:
 *
 * <ul>
 *   <li><b>A module</b> (an import made from a term list) shows every term in it, each marked as
 *       <em>chosen</em> - on the list, so it can be removed - or <em>brought along</em>: a
 *       superclass or the like that the extraction keeps because a chosen term needs it, and which
 *       goes when the terms that need it go.
 *   <li><b>An ontology imported whole</b> shows its terms too, marked as such; there is no list to
 *       take one out of.
 * </ul>
 *
 * <p>Each term also says how many of your own axioms use it, which is the number to look at
 * before removing one. Pure OWL API; no Prot&eacute;g&eacute; types and no Swing.
 */
public final class ImportedTerms {

    private ImportedTerms() {
    }

    /** How a term came to be in the ontology. */
    public enum How {
        /** On the import's term list: somebody chose it, and it can be taken off again. */
        CHOSEN("chosen"),
        /** In the module because a chosen term needs it - a superclass, say. */
        BROUGHT_ALONG("brought along"),
        /** On the term list but not in the module: rebuilt since, or gone from the source. */
        NOT_IN_MODULE("chosen - not in the module yet"),
        /** From an ontology imported as a whole, with no term list. */
        WHOLE("imported whole");

        private final String words;

        How(String words) {
            this.words = words;
        }

        /** How it reads in a column. */
        public String getWords() {
            return words;
        }
    }

    /** One imported term. */
    public static final class Term {
        private final IRI iri;
        private final String label;
        private final String kind;
        private final How how;
        private final int usedByYou;
        private final List<String> neededBy;
        private final List<String> alsoIn;
        private final boolean saidDifferently;

        Term(IRI iri, String label, String kind, How how, int usedByYou, List<String> neededBy) {
            this(iri, label, kind, how, usedByYou, neededBy, Collections.<String>emptyList(),
                    false);
        }

        Term(IRI iri, String label, String kind, How how, int usedByYou, List<String> neededBy,
                List<String> alsoIn, boolean saidDifferently) {
            this.iri = iri;
            this.label = label;
            this.kind = kind;
            this.how = how;
            this.usedByYou = usedByYou;
            this.neededBy = neededBy;
            this.alsoIn = alsoIn;
            this.saidDifferently = saidDifferently;
        }

        /** The other imports this term also comes from, by name. */
        public List<String> getAlsoIn() {
            return alsoIn;
        }

        /**
         * Whether those other imports give it a different label or definition - two copies of
         * one term that disagree, which the quality report counts as two labels or two
         * definitions on it.
         */
        public boolean isSaidDifferently() {
            return saidDifferently;
        }

        public IRI getIri() {
            return iri;
        }

        /** Its label, or the end of its IRI when it has none. */
        public String getLabel() {
            return label;
        }

        /** "class", "object property" and so on. */
        public String getKind() {
            return kind;
        }

        public How getHow() {
            return how;
        }

        /** How many axioms of the importing ontology itself mention it, declarations aside. */
        public int getUsedByYou() {
            return usedByYou;
        }

        /** For a term brought along: the labels of the chosen terms that need it. */
        public List<String> getNeededBy() {
            return neededBy;
        }

        /** Whether it can be taken off a term list. */
        public boolean isRemovable() {
            return how == How.CHOSEN || how == How.NOT_IN_MODULE;
        }
    }

    /** One place terms come from: a module with its term list, or an ontology imported whole. */
    public static final class Source {
        private final String name;
        private final String from;
        private final ImportModules.Module module;
        private final OWLOntology ontology;
        private final TermExtract.Method method;
        private final String pattern;
        private final List<Term> terms;

        Source(String name, String from, ImportModules.Module module, OWLOntology ontology,
                TermExtract.Method method, String pattern, List<Term> terms) {
            this.name = name;
            this.from = from;
            this.module = module;
            this.ontology = ontology;
            this.method = method;
            this.pattern = pattern;
            this.terms = terms;
        }

        /** The import's short name - {@code bfo} for {@code bfo_import.owl} - or the ontology's. */
        public String getName() {
            return name;
        }

        /** Where its terms are read from when it is rebuilt, or null when that is not recorded. */
        public String getFrom() {
            return from;
        }

        /** The module and its term list, or null for an ontology imported whole. */
        public ImportModules.Module getModule() {
            return module;
        }

        /** The import as Prot&eacute;g&eacute; has it open, or null when it is not open. */
        public OWLOntology getOntology() {
            return ontology;
        }

        /** How it is extracted - BOT unless the project says otherwise. */
        public TermExtract.Method getMethod() {
            return method == null ? TermExtract.DEFAULT_METHOD : method;
        }

        /** The library pattern it was made from, or null. */
        public String getPattern() {
            return pattern;
        }

        public List<Term> getTerms() {
            return terms;
        }

        /**
         * Whether terms can be added and removed here: a module with a term list and a source to
         * read from. A pattern's module changes when the pattern is imported again instead.
         */
        public boolean isEditable() {
            return module != null && module.isRebuildable() && from != null && pattern == null;
        }

        /** How many terms are on its term list. */
        public int countChosen() {
            int count = 0;
            for (Term term : terms) {
                count += term.isRemovable() ? 1 : 0;
            }
            return count;
        }
    }

    /**
     * Every source of imported terms for this ontology: its modules first, by name, then what it
     * imports whole.
     *
     * @param projectRoot the project around the ontology, or null when there is none - then only
     *     what it imports whole, and whatever modules its imports are, can be shown
     * @param ontologyDirectory the directory of the edit file, where the project's settings are
     */
    public static List<Source> of(OWLOntology ontology, File projectRoot,
            File ontologyDirectory) {
        List<Source> modules = new ArrayList<Source>();
        List<Source> whole = new ArrayList<Source>();
        if (ontology == null) {
            return modules;
        }
        List<ImportModules.Module> onDisk = projectRoot == null
                ? Collections.<ImportModules.Module>emptyList()
                : ImportModules.modulesIn(projectRoot);
        Map<String, ImportProducts.Row> rows = new HashMap<String, ImportProducts.Row>();
        if (projectRoot != null) {
            List<ImportProducts.Product> declared = ontologyDirectory == null
                    ? Collections.<ImportProducts.Product>emptyList()
                    : ImportProducts.declaredIn(ontologyDirectory);
            for (ImportProducts.Row row : ImportProducts.audit(declared, onDisk, projectRoot)) {
                rows.put(row.getName(), row);
            }
        }
        Set<ImportModules.Module> shown = new HashSet<ImportModules.Module>();
        for (OWLOntology part : ontology.getImportsClosure()) {
            // equals, not ==: the closure can hand back a different wrapper of the same ontology.
            if (part.equals(ontology)) {
                continue;
            }
            ImportModules.Module module = moduleOf(part, onDisk);
            if (module == null) {
                whole.add(wholeSource(ontology, part));
            } else {
                shown.add(module);
                modules.add(moduleSource(ontology, module, part, rows.get(module.getName())));
            }
        }
        // A module on disk that is not open - built, but not imported; or listed, but never
        // built - still has a term list to show and edit.
        for (ImportModules.Module module : onDisk) {
            if (!shown.contains(module) && module.isRebuildable()) {
                modules.add(moduleSource(ontology, module, null, rows.get(module.getName())));
            }
        }
        Collections.sort(modules, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        Collections.sort(whole, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        List<Source> all = new ArrayList<Source>(modules);
        all.addAll(whole);
        markSharedTerms(all);
        return all;
    }

    /**
     * Notes, on every term that more than one import brings, which others bring it and whether
     * they word it differently.
     *
     * <p>Common, and invisible before: a project built from library patterns had BFO's
     * {@code bearer of} from three imports and IAO's {@code information content entity} from
     * three, two of them with different definitions - which the quality report then counted as
     * two definitions on one term.
     */
    private static void markSharedTerms(List<Source> sources) {
        Map<IRI, List<Source>> bringing = new HashMap<IRI, List<Source>>();
        for (Source source : sources) {
            for (Term term : source.getTerms()) {
                if (term.getHow() == How.NOT_IN_MODULE) {
                    continue;
                }
                List<Source> by = bringing.get(term.getIri());
                if (by == null) {
                    by = new ArrayList<Source>();
                    bringing.put(term.getIri(), by);
                }
                by.add(source);
            }
        }
        for (Source source : sources) {
            List<Term> terms = source.getTerms();
            for (int at = 0; at < terms.size(); at++) {
                Term term = terms.get(at);
                List<Source> by = bringing.get(term.getIri());
                if (by == null || by.size() < 2) {
                    continue;
                }
                List<String> others = new ArrayList<String>();
                Set<String> wordings = new HashSet<String>();
                for (Source other : by) {
                    wordings.add(wordingIn(other.getOntology(), term.getIri()));
                    if (other != source) {
                        others.add(other.getName());
                    }
                }
                terms.set(at, new Term(term.getIri(), term.getLabel(), term.getKind(),
                        term.getHow(), term.getUsedByYou(), term.getNeededBy(), others,
                        wordings.size() > 1));
            }
        }
    }

    /** A term's labels and definitions as one ontology states them, for comparing copies. */
    private static String wordingIn(OWLOntology part, IRI iri) {
        if (part == null) {
            return "";
        }
        java.util.TreeSet<String> said = new java.util.TreeSet<String>();
        for (org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom axiom
                : part.getAnnotationAssertionAxioms(iri)) {
            String property = axiom.getProperty().getIRI().toString();
            if (axiom.getProperty().isLabel()
                    || property.equals("http://purl.obolibrary.org/obo/IAO_0000115")
                    || property.equals("http://www.w3.org/2004/02/skos/core#definition")) {
                said.add(property + "=" + (axiom.getValue().asLiteral().isPresent()
                        ? axiom.getValue().asLiteral().get().getLiteral().trim()
                        : axiom.getValue().toString()));
            }
        }
        return said.toString();
    }

    /** The module whose file this open ontology was read from, or null. */
    static ImportModules.Module moduleOf(OWLOntology part,
            Collection<ImportModules.Module> onDisk) {
        IRI document = part.getOWLOntologyManager().getOntologyDocumentIRI(part);
        if (document == null || !"file".equalsIgnoreCase(document.getScheme())) {
            return null;
        }
        File file;
        try {
            file = new File(document.toURI()).getAbsoluteFile();
        } catch (IllegalArgumentException notAFile) {
            return null;
        }
        for (ImportModules.Module module : onDisk) {
            if (module.getModuleFile().getAbsoluteFile().equals(file)) {
                return module;
            }
        }
        return null;
    }

    private static Source moduleSource(OWLOntology ontology, ImportModules.Module module,
            OWLOntology part, ImportProducts.Row row) {
        String listText = readOrEmpty(module.getTermsFile());
        Map<IRI, String> listed = new LinkedHashMap<IRI, String>();
        for (TermFile.Line line : TermFile.linesIn(listText)) {
            if (line.getKind() == TermFile.Kind.TERM && !listed.containsKey(line.getTerm())) {
                listed.put(line.getTerm(), line.getComment());
            }
        }
        String pattern = null;
        String from = row == null ? null : row.getSource();
        try {
            pattern = module.isRebuildable() ? ImportModules.patternIn(module.getTermsFile())
                    : null;
            if (from == null && module.isRebuildable()) {
                from = ImportModules.sourceIn(module.getTermsFile());
            }
        } catch (IOException unreadable) {
            // Shown without a source; the term list itself will say why when opened.
        }
        if (from == null && part != null) {
            IRI recorded = ImportProvenance.sourceOf(part);
            from = recorded == null ? null : recorded.toString();
        }

        List<Term> terms = new ArrayList<Term>();
        Set<IRI> inModule = new HashSet<IRI>();
        if (part != null) {
            Map<IRI, List<String>> neededBy = whatNeeds(part, listed.keySet(), ontology);
            for (OWLEntity entity : termsOf(part)) {
                IRI iri = entity.getIRI();
                inModule.add(iri);
                boolean chosen = listed.containsKey(iri);
                List<String> needs = neededBy.get(iri);
                terms.add(new Term(iri, labelOf(ontology, entity), kindOf(entity),
                        chosen ? How.CHOSEN : How.BROUGHT_ALONG, usedBy(ontology, entity),
                        chosen || needs == null ? Collections.<String>emptyList() : needs));
            }
        }
        for (Map.Entry<IRI, String> entry : listed.entrySet()) {
            if (!inModule.contains(entry.getKey())) {
                String comment = entry.getValue();
                terms.add(new Term(entry.getKey(), comment == null || comment.isEmpty()
                        ? shortFormOf(entry.getKey()) : comment, "", How.NOT_IN_MODULE,
                        usedBy(ontology, entry.getKey()), Collections.<String>emptyList()));
            }
        }
        sortByLabel(terms);
        return new Source(module.getName(), from, module, part,
                row == null ? null : row.getMethod(), pattern, terms);
    }

    private static Source wholeSource(OWLOntology ontology, OWLOntology part) {
        List<Term> terms = new ArrayList<Term>();
        for (OWLEntity entity : termsOf(part)) {
            terms.add(new Term(entity.getIRI(), labelOf(ontology, entity), kindOf(entity),
                    How.WHOLE, usedBy(ontology, entity), Collections.<String>emptyList()));
        }
        sortByLabel(terms);
        String name = nameOf(part);
        IRI iri = part.getOntologyID().getOntologyIRI().isPresent()
                ? part.getOntologyID().getOntologyIRI().get() : null;
        return new Source(name, iri == null ? null : iri.toString(), null, part, null, null,
                terms);
    }

    /** The terms an imported ontology brings: its own signature, OWL's built-ins left out. */
    static List<OWLEntity> termsOf(OWLOntology part) {
        List<OWLEntity> terms = new ArrayList<OWLEntity>();
        for (OWLEntity entity : part.getSignature(Imports.EXCLUDED)) {
            if (entity.isBuiltIn() || isStandardVocabulary(entity.getIRI())) {
                continue;
            }
            terms.add(entity);
        }
        return terms;
    }

    private static boolean isStandardVocabulary(IRI iri) {
        String text = iri.toString();
        return text.startsWith("http://www.w3.org/2002/07/owl#")
                || text.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")
                || text.startsWith("http://www.w3.org/2000/01/rdf-schema#")
                || text.startsWith("http://www.w3.org/2001/XMLSchema#");
    }

    /**
     * For each term of a module that is not on its list, the chosen terms that need it - by
     * subclass or sub-property, the way BOT keeps a term's ancestors.
     */
    static Map<IRI, List<String>> whatNeeds(OWLOntology part, Set<IRI> chosen,
            OWLOntology labels) {
        Map<IRI, Set<String>> needs = new HashMap<IRI, Set<String>>();
        for (OWLClass cls : part.getClassesInSignature(Imports.EXCLUDED)) {
            if (!chosen.contains(cls.getIRI())) {
                continue;
            }
            String label = labelOf(labels, cls);
            for (IRI ancestor : ancestorsOf(part, cls)) {
                if (!chosen.contains(ancestor)) {
                    add(needs, ancestor, label);
                }
            }
        }
        for (OWLObjectProperty property : part.getObjectPropertiesInSignature(Imports.EXCLUDED)) {
            if (!chosen.contains(property.getIRI())) {
                continue;
            }
            String label = labelOf(labels, property);
            Set<IRI> seen = new HashSet<IRI>();
            List<OWLObjectPropertyExpression> todo = superPropertiesOf(part, property);
            while (!todo.isEmpty()) {
                OWLObjectPropertyExpression next = todo.remove(todo.size() - 1);
                if (next.isAnonymous() || !seen.add(next.asOWLObjectProperty().getIRI())) {
                    continue;
                }
                if (!chosen.contains(next.asOWLObjectProperty().getIRI())) {
                    add(needs, next.asOWLObjectProperty().getIRI(), label);
                }
                todo.addAll(superPropertiesOf(part, next));
            }
        }
        Map<IRI, List<String>> sorted = new HashMap<IRI, List<String>>();
        for (Map.Entry<IRI, Set<String>> entry : needs.entrySet()) {
            List<String> names = new ArrayList<String>(entry.getValue());
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            sorted.put(entry.getKey(), names);
        }
        return sorted;
    }

    private static List<OWLObjectPropertyExpression> superPropertiesOf(OWLOntology part,
            OWLObjectPropertyExpression property) {
        List<OWLObjectPropertyExpression> supers = new ArrayList<OWLObjectPropertyExpression>();
        for (org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom axiom
                : part.getObjectSubPropertyAxiomsForSubProperty(property)) {
            supers.add(axiom.getSuperProperty());
        }
        return supers;
    }

    private static Set<IRI> ancestorsOf(OWLOntology part, OWLClass start) {
        Set<IRI> ancestors = new LinkedHashSet<IRI>();
        List<OWLClass> todo = new ArrayList<OWLClass>();
        todo.add(start);
        while (!todo.isEmpty()) {
            OWLClass next = todo.remove(todo.size() - 1);
            for (org.semanticweb.owlapi.model.OWLSubClassOfAxiom axiom
                    : part.getSubClassAxiomsForSubClass(next)) {
                OWLClassExpression parent = axiom.getSuperClass();
                if (!parent.isAnonymous() && !parent.isOWLThing()
                        && ancestors.add(parent.asOWLClass().getIRI())) {
                    todo.add(parent.asOWLClass());
                }
            }
        }
        return ancestors;
    }

    private static void add(Map<IRI, Set<String>> needs, IRI term, String chosenLabel) {
        Set<String> by = needs.get(term);
        if (by == null) {
            by = new LinkedHashSet<String>();
            needs.put(term, by);
        }
        by.add(chosenLabel);
    }

    /** How many of the importing ontology's own axioms mention it, declarations aside. */
    static int usedBy(OWLOntology ontology, OWLEntity entity) {
        int count = 0;
        for (OWLAxiom axiom : ontology.getReferencingAxioms(entity, Imports.EXCLUDED)) {
            if (!(axiom instanceof OWLDeclarationAxiom)) {
                count++;
            }
        }
        return count;
    }

    private static int usedBy(OWLOntology ontology, IRI iri) {
        int count = 0;
        for (OWLEntity entity : ontology.getEntitiesInSignature(iri, Imports.EXCLUDED)) {
            count += usedBy(ontology, entity);
        }
        return count;
    }

    private static String labelOf(OWLOntology ontology, OWLEntity entity) {
        String label = DisplayLabels.forEntity(ontology, entity);
        return label == null || label.trim().isEmpty() ? shortFormOf(entity.getIRI()) : label;
    }

    private static String kindOf(OWLEntity entity) {
        switch (entity.getEntityType().getName()) {
            case "Class":
                return "class";
            case "ObjectProperty":
                return "object property";
            case "DataProperty":
                return "data property";
            case "AnnotationProperty":
                return "annotation property";
            case "NamedIndividual":
                return "individual";
            case "Datatype":
                return "datatype";
            default:
                return entity.getEntityType().getName().toLowerCase(Locale.ROOT);
        }
    }

    private static String nameOf(OWLOntology part) {
        for (org.semanticweb.owlapi.model.OWLAnnotation annotation : part.getAnnotations()) {
            if (annotation.getProperty().isLabel()
                    && annotation.getValue().asLiteral().isPresent()) {
                return annotation.getValue().asLiteral().get().getLiteral();
            }
        }
        if (part.getOntologyID().getOntologyIRI().isPresent()) {
            return shortFormOf(part.getOntologyID().getOntologyIRI().get());
        }
        return "an ontology without a name";
    }

    static String shortFormOf(IRI iri) {
        String text = iri.toString();
        int cut = Math.max(text.lastIndexOf('#'), text.lastIndexOf('/'));
        String end = cut >= 0 && cut < text.length() - 1 ? text.substring(cut + 1) : text;
        return end.isEmpty() ? text : end;
    }

    private static void sortByLabel(List<Term> terms) {
        Collections.sort(terms, (a, b) -> {
            int byHow = Integer.compare(a.getHow() == How.BROUGHT_ALONG ? 1 : 0,
                    b.getHow() == How.BROUGHT_ALONG ? 1 : 0);
            return byHow != 0 ? byHow : a.getLabel().compareToIgnoreCase(b.getLabel());
        });
    }

    private static String readOrEmpty(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }

    /**
     * A term list's text with these terms added after its last term, each with its label as the
     * comment when the file writes them; terms already on it are left where they are.
     */
    public static String withTerms(String listText, Map<IRI, String> labels) {
        String text = listText == null ? "" : listText;
        Set<IRI> already = new HashSet<IRI>(TermFile.termsIn(text));
        for (Map.Entry<IRI, String> term : labels.entrySet()) {
            if (already.add(term.getKey())) {
                text = TermFile.with(text, term.getKey(), term.getValue());
            }
        }
        return text;
    }

    /** A term list's text without the lines naming these terms; every other line unchanged. */
    public static String withoutTerms(String listText, Set<IRI> terms) {
        String text = listText == null ? "" : listText;
        List<Integer> lines = new ArrayList<Integer>();
        for (TermFile.Line line : TermFile.linesIn(text)) {
            if (line.getKind() == TermFile.Kind.TERM && terms.contains(line.getTerm())) {
                lines.add(line.getNumber());
            }
        }
        // From the bottom up, so the numbers of the lines still to remove do not move.
        Collections.sort(lines, Collections.reverseOrder());
        for (int number : lines) {
            text = TermFile.without(text, number);
        }
        return text;
    }
}
