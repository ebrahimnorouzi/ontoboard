package de.fizkarlsruhe.ise.ontoboard.sheet;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Every name in play, and what each one points at.
 *
 * <p>A knowledge-graph spreadsheet is mostly references: a row names another row's thing by its
 * label, and ROBOT resolves that label against whatever ontology it was given. Everything a
 * person wants from such a sheet - completing a name as they type it, telling them a name
 * points at nothing, telling them a name points at <em>two</em> things, jumping to where a
 * thing was defined - is a question about this index.
 *
 * <h2>Why it holds sheets as well as the ontology</h2>
 *
 * <p>Because that is where the names are. Measured on the MatWerk knowledge graph: of the
 * references in its {@code dataportal} sheet, every one is a plain-text label rather than an
 * IRI, and the thing each names is defined in another sheet five steps upstream rather than in
 * any ontology. An index built from the ontology alone would call all of them unresolvable.
 *
 * <h2>Ambiguity is the interesting answer, not an edge case</h2>
 *
 * <p>Also measured on that graph: 57 institute labels are declared in two different sheets, and
 * two of those pairs disagree about the IRI. <i>National Institute for Materials Science
 * (NIMS)</i> has two distinct IRIs in scope, and nine references name it. A further 23 labels
 * carry two IRIs inside one sheet alone. ROBOT resolves such a name to whichever IRI it
 * happens to pick, silently, and the author has no say. So {@link #lookup} returns every
 * candidate rather than a best guess, and {@link #ambiguous()} exists to be shown to somebody.
 *
 * <h2>Matching</h2>
 *
 * <p>Resolution is on the trimmed label, exactly, because that is what ROBOT does and an index
 * that resolved more generously than the builder would be lying. Suggestion is case- and
 * punctuation-insensitive, because that is what helps somebody typing. Those are different
 * questions and they get different methods.
 *
 * <p>No Swing and no Protege types: this is wanted by a dialog, by a view, and by tests.
 */
public final class LabelIndex {

    /** One place a name was found. */
    public static final class Entry {
        private final String label;
        private final IRI iri;
        private final String source;
        private final int row;

        Entry(String label, IRI iri, String source, int row) {
            this.label = label;
            this.iri = iri;
            this.source = source;
            this.row = row;
        }

        /** The label as written where it was found, untrimmed-case and all. */
        public String getLabel() {
            return label;
        }

        public IRI getIri() {
            return iri;
        }

        /** The ontology's name, or the sheet's. How a person is told where to look. */
        public String getSource() {
            return source;
        }

        /** The sheet row this came from as a spreadsheet counts it, or 0 for the ontology. */
        public int getRow() {
            return row;
        }

        /** Whether this came from a sheet rather than from the ontology. */
        public boolean isFromASheet() {
            return row > 0;
        }

        /** Where it is, in one phrase, for a message or a menu. */
        public String where() {
            return row > 0 ? source + " row " + row : source;
        }

        @Override
        public String toString() {
            return label + " -> " + iri + " (" + where() + ")";
        }
    }

    /** A label that names more than one thing. */
    public static final class Ambiguity {
        private final String label;
        private final List<Entry> entries;

        Ambiguity(String label, List<Entry> entries) {
            this.label = label;
            this.entries = Collections.unmodifiableList(entries);
        }

        public String getLabel() {
            return label;
        }

        /** Every place the label was found, in the order they were indexed. */
        public List<Entry> getEntries() {
            return entries;
        }

        /** The distinct IRIs it could mean. Always more than one. */
        public Set<IRI> getCandidates() {
            Set<IRI> found = new LinkedHashSet<IRI>();
            for (Entry entry : entries) {
                found.add(entry.getIri());
            }
            return found;
        }

        /** What to tell somebody, naming every candidate and where it came from. */
        public String describe() {
            StringBuilder text = new StringBuilder();
            text.append("\"").append(label).append("\" names ")
                    .append(getCandidates().size()).append(" different things: ");
            boolean first = true;
            for (Entry entry : entries) {
                if (!first) {
                    text.append("; ");
                }
                text.append(entry.getIri()).append(" in ").append(entry.where());
                first = false;
            }
            text.append(". A reference to it resolves to whichever one the builder picks, so "
                    + "write the IRI instead, or give the things different labels.");
            return text.toString();
        }

        @Override
        public String toString() {
            return label + " x" + getCandidates().size();
        }
    }

    private final Map<String, List<Entry>> byLabel = new LinkedHashMap<String, List<Entry>>();
    private final Map<String, List<Entry>> byLoose = new LinkedHashMap<String, List<Entry>>();
    private final Map<IRI, List<Entry>> byIri = new LinkedHashMap<IRI, List<Entry>>();

    private LabelIndex() {
    }

    /** An empty index, which is a legitimate state and must not be a null. */
    public static LabelIndex empty() {
        return new LabelIndex();
    }

    /**
     * Everything labelled in an ontology, its imports included.
     *
     * @param ontology may be null, in which case nothing is added
     * @param name what to call it when telling somebody where a name came from
     */
    public LabelIndex plus(OWLOntology ontology, String name) {
        if (ontology == null) {
            return this;
        }
        String source = name == null || name.trim().isEmpty() ? "this ontology" : name.trim();
        for (OWLAnnotationAssertionAxiom axiom
                : ontology.getAxioms(
                        org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION,
                        Imports.INCLUDED)) {
            if (!axiom.getProperty().isLabel()
                    || !(axiom.getSubject() instanceof IRI)
                    || !(axiom.getValue() instanceof OWLLiteral)) {
                continue;
            }
            String label = ((OWLLiteral) axiom.getValue()).getLiteral();
            add(label, (IRI) axiom.getSubject(), source, 0);
        }
        return this;
    }

    /**
     * Everything a sheet names, taken from its ID column and its label columns.
     *
     * <p>Read from the sheet rather than from what the sheet would produce, so an index can be
     * built while somebody is still typing - which is the only way completion can work.
     *
     * @param sheetName what to call it when telling somebody where a name came from
     * @param rows the whole table, both header rows included
     */
    public LabelIndex plus(String sheetName, List<List<String>> rows) {
        if (rows == null || rows.size() < 2) {
            return this;
        }
        String source = sheetName == null || sheetName.trim().isEmpty() ? "a sheet"
                : sheetName.trim();
        TemplateColumns columns = TemplateColumns.of(rows);
        TemplateColumns.Column id = columns.firstOf(TemplateColumns.Kind.ID);
        if (id == null) {
            return this;
        }
        List<TemplateColumns.Column> labels = new ArrayList<TemplateColumns.Column>();
        for (TemplateColumns.Column column : columns.getColumns()) {
            if (column.getKind() == TemplateColumns.Kind.LABEL
                    || column.getKind() == TemplateColumns.Kind.ANNOTATION_LANGUAGE
                    || isALabelAnnotation(column)) {
                labels.add(column);
            }
        }
        if (labels.isEmpty()) {
            return this;
        }
        for (int index = 2; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            String identifier = cell(row, id.getNumber());
            if (identifier.isEmpty()) {
                continue;
            }
            IRI iri;
            try {
                iri = IRI.create(identifier);
            } catch (RuntimeException notAnIri) {
                continue;
            }
            for (TemplateColumns.Column column : labels) {
                String label = cell(row, column.getNumber());
                if (!label.isEmpty()) {
                    add(label, iri, source, index + 1);
                }
            }
        }
        return this;
    }

    /**
     * Whether an annotation column carries a label.
     *
     * <p>Spelt out rather than compared to one string because the property can be written as a
     * CURIE or in full, and because the broken {@code rdfs:label@en} form - which this plugin
     * reports but which exists in real sheets - should still contribute its text to the index.
     * Somebody typing a name wants it completed whether or not the column that holds it is
     * written correctly.
     */
    private static boolean isALabelAnnotation(TemplateColumns.Column column) {
        if (column.getKind() != TemplateColumns.Kind.ANNOTATION
                && column.getKind() != TemplateColumns.Kind.ANNOTATION_TYPED) {
            return false;
        }
        String property = column.getProperty();
        int hash = property.lastIndexOf('#');
        int colon = property.lastIndexOf(':');
        String local = property.substring(Math.max(hash, colon) + 1);
        return "label".equals(local) || local.startsWith("label@");
    }

    private static String cell(List<String> row, int number) {
        int at = number - 1;
        if (row == null || at < 0 || at >= row.size() || row.get(at) == null) {
            return "";
        }
        return row.get(at).trim();
    }

    private void add(String label, IRI iri, String source, int row) {
        String exact = label.trim();
        if (exact.isEmpty()) {
            return;
        }
        Entry entry = new Entry(exact, iri, source, row);
        put(byLabel, exact, entry);
        put(byLoose, loosen(exact), entry);
        if (!byIri.containsKey(iri)) {
            byIri.put(iri, new ArrayList<Entry>());
        }
        byIri.get(iri).add(entry);
    }

    private static void put(Map<String, List<Entry>> into, String key, Entry entry) {
        if (!into.containsKey(key)) {
            into.put(key, new ArrayList<Entry>());
        }
        List<Entry> existing = into.get(key);
        for (Entry already : existing) {
            if (already.getIri().equals(entry.getIri())
                    && already.where().equals(entry.where())) {
                return;
            }
        }
        existing.add(entry);
    }

    /**
     * The form used for suggesting, as opposed to resolving.
     *
     * <p>Case and punctuation are dropped because somebody typing "nims" or "fraunhofer
     * gesellschaft" should be offered "National Institute for Materials Science (NIMS)" and
     * "Fraunhofer-Gesellschaft". Resolution does not use this, because ROBOT does not.
     */
    private static String loosen(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int at = 0; at < text.length(); at++) {
            char character = text.charAt(at);
            if (Character.isLetterOrDigit(character)) {
                out.append(Character.toLowerCase(character));
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- asking it things

    /** Every thing this exact label names. Empty when nothing does. */
    public List<Entry> lookup(String label) {
        if (label == null) {
            return Collections.emptyList();
        }
        List<Entry> found = byLabel.get(label.trim());
        return found == null ? Collections.<Entry>emptyList()
                : Collections.unmodifiableList(found);
    }

    /**
     * The one IRI this label names, or null when it names none or several.
     *
     * <p>Null for "several" on purpose: picking one would be the silent behaviour this class
     * exists to expose.
     */
    public IRI resolve(String label) {
        Set<IRI> candidates = new LinkedHashSet<IRI>();
        for (Entry entry : lookup(label)) {
            candidates.add(entry.getIri());
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    /** Whether this exact label names anything at all. */
    public boolean knows(String label) {
        return !lookup(label).isEmpty();
    }

    /** Everywhere this IRI was given a name. */
    public List<Entry> forIri(IRI iri) {
        List<Entry> found = byIri.get(iri);
        return found == null ? Collections.<Entry>emptyList()
                : Collections.unmodifiableList(found);
    }

    /** Every label that names more than one thing, worst first. */
    public List<Ambiguity> ambiguous() {
        List<Ambiguity> found = new ArrayList<Ambiguity>();
        for (Map.Entry<String, List<Entry>> each : byLabel.entrySet()) {
            Set<IRI> candidates = new LinkedHashSet<IRI>();
            for (Entry entry : each.getValue()) {
                candidates.add(entry.getIri());
            }
            if (candidates.size() > 1) {
                found.add(new Ambiguity(each.getKey(), each.getValue()));
            }
        }
        Collections.sort(found, new Comparator<Ambiguity>() {
            @Override
            public int compare(Ambiguity a, Ambiguity b) {
                int bySize = b.getCandidates().size() - a.getCandidates().size();
                return bySize != 0 ? bySize : a.getLabel().compareTo(b.getLabel());
            }
        });
        return found;
    }

    /**
     * Names to offer for what somebody has typed so far.
     *
     * <p>Ranked by how well they match rather than alphabetically: an exact match first, then a
     * name that starts with what was typed, then one that contains it. Within a rank the
     * shorter name comes first, because a short name that contains the text is more likely to
     * be the thing meant than a long one.
     *
     * @param typed what the person has typed; empty gives nothing rather than everything
     * @param limit how many to return at most
     */
    public List<Entry> suggest(String typed, int limit) {
        if (typed == null || typed.trim().isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        final String loose = loosen(typed);
        if (loose.isEmpty()) {
            return Collections.emptyList();
        }
        List<Entry> matches = new ArrayList<Entry>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Map.Entry<String, List<Entry>> each : byLoose.entrySet()) {
            if (each.getKey().indexOf(loose) < 0) {
                continue;
            }
            for (Entry entry : each.getValue()) {
                if (seen.add(entry.getLabel() + " " + entry.getIri())) {
                    matches.add(entry);
                }
            }
        }
        Collections.sort(matches, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int byRank = rank(a) - rank(b);
                if (byRank != 0) {
                    return byRank;
                }
                int byLength = a.getLabel().length() - b.getLabel().length();
                return byLength != 0 ? byLength : a.getLabel().compareTo(b.getLabel());
            }

            private int rank(Entry entry) {
                String candidate = loosen(entry.getLabel());
                if (candidate.equals(loose)) {
                    return 0;
                }
                return candidate.startsWith(loose) ? 1 : 2;
            }
        });
        return matches.size() <= limit ? matches : new ArrayList<Entry>(matches.subList(0, limit));
    }

    /** How many distinct names are indexed. */
    public int size() {
        return byLabel.size();
    }

    /** Every label, for a caller that wants to scan them. */
    public Collection<String> labels() {
        return Collections.unmodifiableCollection(byLabel.keySet());
    }
}
