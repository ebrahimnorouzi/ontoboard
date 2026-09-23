package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * What changed between two versions of an ontology, by term.
 *
 * <p>Every one of five independent expert panels asked for this, in its own vocabulary: at the
 * entailment level, as {@code robot diff}, as "what changed since I last looked, in sentences", by
 * term, and as <em>added, obsoleted, redefined, moved</em>. That is one question with four
 * audiences, and none of them is answered by a list of axiom strings - which is what
 * {@code robot diff} produces and why it is not enough on its own. An axiom diff of a release that
 * renamed one term runs to dozens of lines and never says the word "renamed".
 *
 * <p>The distinction this exists to draw is <b>obsoleted versus removed</b>. Obsoleting is correct
 * practice: the term stays, marked {@code owl:deprecated}, so everything that referenced it still
 * resolves. Removing it breaks every consumer that used it, silently, and is indistinguishable
 * from obsoleting in an axiom diff unless you already know what you are looking at. So they are
 * counted separately and removal is called out, because a release that drops a published term is
 * nearly always a mistake nobody noticed.
 *
 * <p>Pure OWL API - no Protege types, no Swing, no file access - so the comparison is testable.
 */
public final class ReleaseDiff {

    /** {@code IAO:0000115 definition}, the one OBO ontologies use. */
    public static final IRI DEFINITION =
            IRI.create("http://purl.obolibrary.org/obo/IAO_0000115");

    /** What happened to one term. */
    public enum Change {
        /** New in the later version. */
        ADDED("Added"),
        /** Marked {@code owl:deprecated} in the later version. The correct way to retire a term. */
        OBSOLETED("Obsoleted"),
        /**
         * Gone from the later version altogether.
         *
         * <p>Nearly always a mistake. Everything that referenced it now points at nothing, and no
         * consumer is told.
         */
        REMOVED("Removed"),
        /** Its {@code rdfs:label} changed. */
        RELABELLED("Relabelled"),
        /** Its {@code IAO:0000115} definition changed - the term may now mean something else. */
        REDEFINED("Redefined"),
        /** Its asserted parents changed, so it sits somewhere else in the hierarchy. */
        MOVED("Moved");

        private final String label;

        Change(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** One term, and what happened to it. */
    public static final class TermChange {
        private final IRI iri;
        private final Change change;
        private final String before;
        private final String after;

        TermChange(IRI iri, Change change, String before, String after) {
            this.iri = iri;
            this.change = change;
            this.before = before == null ? "" : before;
            this.after = after == null ? "" : after;
        }

        public IRI getIri() {
            return iri;
        }

        public Change getChange() {
            return change;
        }

        /** What it was, where that makes sense - the old label, definition or parents. */
        public String getBefore() {
            return before;
        }

        /** What it is now. */
        public String getAfter() {
            return after;
        }

        @Override
        public String toString() {
            return change.getLabel() + ": " + iri
                    + (before.isEmpty() && after.isEmpty() ? ""
                            : "  [" + before + " -> " + after + "]");
        }
    }

    private final List<TermChange> changes;
    private final int axiomsBefore;
    private final int axiomsAfter;

    private ReleaseDiff(List<TermChange> changes, int axiomsBefore, int axiomsAfter) {
        this.changes = Collections.unmodifiableList(changes);
        this.axiomsBefore = axiomsBefore;
        this.axiomsAfter = axiomsAfter;
    }

    /**
     * Compares two versions.
     *
     * @param before the earlier one - a previous release, usually
     * @param after the later one - the edit file, or the release about to be cut
     */
    public static ReleaseDiff between(OWLOntology before, OWLOntology after) {
        if (before == null || after == null) {
            throw new IllegalArgumentException("two ontologies are needed to compare them");
        }
        List<TermChange> changes = new ArrayList<TermChange>();

        Set<IRI> beforeTerms = classIrisOf(before);
        Set<IRI> afterTerms = classIrisOf(after);

        for (IRI iri : afterTerms) {
            if (!beforeTerms.contains(iri)) {
                changes.add(new TermChange(iri, Change.ADDED, "", labelOf(after, iri)));
            }
        }
        for (IRI iri : beforeTerms) {
            if (!afterTerms.contains(iri)) {
                // Gone entirely, not merely deprecated. Everything that referenced it now points
                // at nothing.
                changes.add(new TermChange(iri, Change.REMOVED, labelOf(before, iri), ""));
            }
        }

        for (IRI iri : afterTerms) {
            if (!beforeTerms.contains(iri)) {
                continue;
            }
            if (!isObsolete(before, iri) && isObsolete(after, iri)) {
                changes.add(new TermChange(iri, Change.OBSOLETED, labelOf(before, iri),
                        replacedBy(after, iri)));
                // Nothing else about a retirement is news. Obsoleting a term the OBO way always
                // prefixes its label with "obsolete " and always takes it out of the hierarchy,
                // so reporting the relabel and the move as well turned one decision into three
                // rows - and a curator reading a release note cannot tell that the three are the
                // same event. Found by running a release history end to end rather than by
                // testing the diff on its own.
                continue;
            }
            String labelBefore = labelOf(before, iri);
            String labelAfter = labelOf(after, iri);
            if (!labelBefore.equals(labelAfter)) {
                changes.add(new TermChange(iri, Change.RELABELLED, labelBefore, labelAfter));
            }
            String definitionBefore = definitionOf(before, iri);
            String definitionAfter = definitionOf(after, iri);
            if (!definitionBefore.equals(definitionAfter)) {
                changes.add(new TermChange(iri, Change.REDEFINED, definitionBefore,
                        definitionAfter));
            }
            Set<String> parentsBefore = parentsOf(before, iri);
            Set<String> parentsAfter = parentsOf(after, iri);
            if (!parentsBefore.equals(parentsAfter)) {
                changes.add(new TermChange(iri, Change.MOVED, join(parentsBefore),
                        join(parentsAfter)));
            }
        }
        return new ReleaseDiff(changes, before.getAxiomCount(), after.getAxiomCount());
    }

    /** Everything that changed, in the order found. */
    public List<TermChange> getChanges() {
        return changes;
    }

    /** Just the ones of this kind. */
    public List<TermChange> of(Change change) {
        List<TermChange> matching = new ArrayList<TermChange>();
        for (TermChange one : changes) {
            if (one.getChange() == change) {
                matching.add(one);
            }
        }
        return matching;
    }

    public int count(Change change) {
        return of(change).size();
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public int getAxiomsBefore() {
        return axiomsBefore;
    }

    public int getAxiomsAfter() {
        return axiomsAfter;
    }

    /**
     * The terms a consumer would lose, if any.
     *
     * <p>Kept separate from everything else because it is the one outcome that cannot be undone
     * for somebody downstream: a release that drops a published term breaks every ontology that
     * imported it, and nothing tells them.
     */
    public List<TermChange> removals() {
        return of(Change.REMOVED);
    }

    /** A sentence a person reads, rather than a count of axioms. */
    public String summary() {
        if (changes.isEmpty()) {
            return "Nothing changed: the two versions have the same terms, labels, definitions "
                    + "and parents.";
        }
        StringBuilder text = new StringBuilder();
        for (Change change : Change.values()) {
            int count = count(change);
            if (count == 0) {
                continue;
            }
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(count).append(' ').append(change.getLabel().toLowerCase());
        }
        return text + " (" + axiomsBefore + " axioms to " + axiomsAfter + ").";
    }

    /**
     * Release notes, in Markdown, generated rather than remembered.
     *
     * <p>Written beside the release and not into the ontology: a changelog inside the file grows
     * without bound, appears in every subsequent diff, and an importer gains nothing from it.
     *
     * @param version what to call this release - the date, usually
     */
    public String asReleaseNotes(String version, OWLOntology after) {
        StringBuilder notes = new StringBuilder("# ").append(version).append("\n\n");
        notes.append(summary()).append("\n");

        if (!removals().isEmpty()) {
            notes.append("\n## Removed - check this was meant\n\n");
            notes.append("These terms are gone rather than obsoleted, so anything that imported "
                    + "them now references nothing.\n\n");
            for (TermChange change : removals()) {
                notes.append("- `").append(change.getIri()).append('`');
                if (!change.getBefore().isEmpty()) {
                    notes.append(" — ").append(change.getBefore());
                }
                notes.append('\n');
            }
        }
        for (Change kind : new Change[] {Change.ADDED, Change.OBSOLETED, Change.REDEFINED,
                Change.RELABELLED, Change.MOVED}) {
            List<TermChange> matching = of(kind);
            if (matching.isEmpty()) {
                continue;
            }
            notes.append("\n## ").append(kind.getLabel()).append("\n\n");
            for (TermChange change : matching) {
                notes.append("- `").append(shortForm(change.getIri())).append('`');
                String label = labelOf(after, change.getIri());
                if (!label.isEmpty()) {
                    notes.append(' ').append(label);
                }
                if (kind == Change.RELABELLED || kind == Change.MOVED) {
                    notes.append(" — was ").append(
                            change.getBefore().isEmpty() ? "(none)" : change.getBefore());
                }
                if (kind == Change.OBSOLETED && !change.getAfter().isEmpty()) {
                    notes.append(" — replaced by `").append(change.getAfter()).append('`');
                }
                notes.append('\n');
            }
        }
        return notes.toString();
    }

    // ---------------------------------------------------------------- reading an ontology

    private static Set<IRI> classIrisOf(OWLOntology ontology) {
        Set<IRI> iris = new LinkedHashSet<IRI>();
        for (OWLClass owlClass : ontology.getClassesInSignature()) {
            if (!owlClass.isOWLThing() && !owlClass.isOWLNothing()) {
                iris.add(owlClass.getIRI());
            }
        }
        return iris;
    }

    private static boolean isObsolete(OWLOntology ontology, IRI iri) {
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(iri)) {
            if (OWLRDFVocabulary.OWL_DEPRECATED.getIRI().equals(axiom.getProperty().getIRI())
                    && axiom.getValue() instanceof OWLLiteral
                    && ((OWLLiteral) axiom.getValue()).parseBoolean()) {
                return true;
            }
        }
        return false;
    }

    /** {@code IAO:0100001 term replaced by}, so a reader knows where the term went. */
    private static String replacedBy(OWLOntology ontology, IRI iri) {
        IRI property = IRI.create("http://purl.obolibrary.org/obo/IAO_0100001");
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(iri)) {
            if (property.equals(axiom.getProperty().getIRI())) {
                return axiom.getValue() instanceof OWLLiteral
                        ? ((OWLLiteral) axiom.getValue()).getLiteral()
                        : axiom.getValue().toString();
            }
        }
        return "";
    }

    private static String labelOf(OWLOntology ontology, IRI iri) {
        return annotationText(ontology, iri, OWLRDFVocabulary.RDFS_LABEL.getIRI());
    }

    private static String definitionOf(OWLOntology ontology, IRI iri) {
        return annotationText(ontology, iri, DEFINITION);
    }

    /**
     * One annotation's text, chosen deterministically.
     *
     * <p>Sorted rather than first-found: a term with two labels would otherwise appear to change
     * between two runs over identical files, purely because a hash set iterated differently.
     */
    private static String annotationText(OWLOntology ontology, IRI iri, IRI property) {
        List<String> values = new ArrayList<String>();
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(iri)) {
            if (property.equals(axiom.getProperty().getIRI())
                    && axiom.getValue() instanceof OWLLiteral) {
                values.add(((OWLLiteral) axiom.getValue()).getLiteral());
            }
        }
        Collections.sort(values);
        return values.isEmpty() ? "" : values.get(0);
    }

    /** Asserted named parents only; an anonymous superclass is a restriction, not a place. */
    private static Set<String> parentsOf(OWLOntology ontology, IRI iri) {
        Set<String> parents = new HashSet<String>();
        OWLClass subject = ontology.getOWLOntologyManager().getOWLDataFactory()
                .getOWLClass(iri);
        for (OWLSubClassOfAxiom axiom : ontology.getSubClassAxiomsForSubClass(subject)) {
            if (!axiom.getSuperClass().isAnonymous()) {
                parents.add(shortForm(axiom.getSuperClass().asOWLClass().getIRI()));
            }
        }
        return parents;
    }

    private static String join(Set<String> values) {
        List<String> sorted = new ArrayList<String>(values);
        Collections.sort(sorted);
        StringBuilder text = new StringBuilder();
        for (String value : sorted) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(value);
        }
        return text.toString();
    }

    /** The readable end of an IRI, for notes a person reads. */
    public static String shortForm(IRI iri) {
        String text = iri.toString();
        int hash = text.lastIndexOf('#');
        int slash = text.lastIndexOf('/');
        int cut = Math.max(hash, slash);
        return cut >= 0 && cut < text.length() - 1 ? text.substring(cut + 1) : text;
    }

    /** Counts by kind, for a caller that wants the shape without the detail. */
    public Map<Change, Integer> counts() {
        Map<Change, Integer> counts = new LinkedHashMap<Change, Integer>();
        for (Change change : Change.values()) {
            counts.put(change, count(change));
        }
        return counts;
    }
}
