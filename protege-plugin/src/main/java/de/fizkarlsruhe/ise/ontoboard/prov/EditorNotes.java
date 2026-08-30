package de.fizkarlsruhe.ise.ontoboard.prov;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveAxiom;

/**
 * Editorial notes - the durable, per-term comment that belongs in the ontology.
 *
 * <p>The request was "write comments, and save everything in the ontology". A threaded discussion
 * does not belong in an ontology: it is unbounded, mutable, appears in every release and every
 * diff, and an importer gains nothing from it. But the thing underneath the request - <em>I want to
 * write down that this definition needs work, where the next editor will see it</em> - is exactly
 * what OBO has a property for, and every OBO ontology carries them.
 *
 * <p>{@code IAO:0000116 editor note} is the one people mean. {@code IAO:0000232 curator note} is
 * its sibling, for a project that distinguishes the two - editor notes about the modelling, curator
 * notes about the curation. {@code IAO:0000233 term tracker item} is the other half of the answer:
 * the note lives in the ontology and points at the issue where the argument happens.
 *
 * <p>The definition of {@code IAO:0000116} says a note "may not be included in the publication
 * version of the ontology, so it should contain nothing necessary for end users". That is
 * permission to strip them at release, not an instruction to - which is why {@link #strip} exists
 * and is off by default.
 *
 * <p>Pure OWL API; no Protege types and no Swing.
 */
public final class EditorNotes {

    /** {@code IAO:0000116 editor note}. */
    public static final IRI EDITOR_NOTE =
            IRI.create("http://purl.obolibrary.org/obo/IAO_0000116");

    /** {@code IAO:0000232 curator note}. */
    public static final IRI CURATOR_NOTE =
            IRI.create("http://purl.obolibrary.org/obo/IAO_0000232");

    /** {@code IAO:0000233 term tracker item} - where the discussion actually happens. */
    public static final IRI TERM_TRACKER_ITEM =
            IRI.create("http://purl.obolibrary.org/obo/IAO_0000233");

    /** Which of the two note properties. */
    public enum Kind {
        /**
         * {@code IAO:0000116} - about the modelling. What almost everyone means by "a note".
         */
        EDITOR("Editor note", EDITOR_NOTE,
                "A note to whoever edits this term next - that the definition needs work, that "
                        + "the parent is provisional, that it duplicates something. This is the "
                        + "one OBO ontologies use, and an importer can read it. It is not a "
                        + "discussion: for that, link an issue with a term tracker item."),

        /**
         * {@code IAO:0000232} - about the curation rather than the modelling.
         */
        CURATOR("Curator note", CURATOR_NOTE,
                "A note about the curation rather than the modelling - where a value came from, "
                        + "which source was followed, why an obvious-looking change was not made. "
                        + "Only worth using in a project that already distinguishes the two; "
                        + "otherwise everything goes in the editor note.");

        private final String label;
        private final IRI iri;
        private final String help;

        Kind(String label, IRI iri, String help) {
            this.label = label;
            this.iri = iri;
            this.help = help;
        }

        public String getLabel() {
            return label;
        }

        public IRI getIri() {
            return iri;
        }

        /** What distinguishes it from the other one, for the parameter dialog's "?". */
        public String getHelp() {
            return help;
        }
    }

    private EditorNotes() {
    }

    /**
     * The notes of this kind on an entity, in the order the ontology gives them.
     *
     * <p>A list, not one value: a term can carry several notes written by different people at
     * different times, and OBO ontologies do. Collapsing them to "the note" would lose all but one
     * the moment somebody edited it.
     */
    public static List<String> notesOn(OWLOntology ontology, IRI entity, Kind kind) {
        List<String> notes = new ArrayList<String>();
        if (ontology == null || entity == null || kind == null) {
            return notes;
        }
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(entity)) {
            if (kind.getIri().equals(axiom.getProperty().getIRI())
                    && axiom.getValue() instanceof OWLLiteral) {
                notes.add(((OWLLiteral) axiom.getValue()).getLiteral());
            }
        }
        return notes;
    }

    /** Whether anything is noted on this entity, of either kind. Drives the canvas marker. */
    public static boolean hasNote(OWLOntology ontology, IRI entity) {
        return !notesOn(ontology, entity, Kind.EDITOR).isEmpty()
                || !notesOn(ontology, entity, Kind.CURATOR).isEmpty();
    }

    /**
     * Changes that add a note, leaving any existing ones alone.
     *
     * <p>Adding rather than replacing is the default because notes accumulate: two editors each
     * leave one, and a tool that silently replaced the first would destroy somebody else's words
     * with no trace in the diff beyond a line that changed.
     *
     * @return no changes at all for blank text, or when that exact note is already there
     */
    public static List<OWLOntologyChange> addNote(OWLOntology ontology, IRI entity, Kind kind,
            String text) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        String trimmed = text == null ? "" : text.trim();
        if (ontology == null || entity == null || trimmed.isEmpty()) {
            return changes;
        }
        if (notesOn(ontology, entity, kind).contains(trimmed)) {
            // Already said, word for word. Adding it again would put two identical annotations on
            // the term, which no reader can tell apart and no diff explains.
            return changes;
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                property(factory, kind), entity, factory.getOWLLiteral(trimmed))));
        return changes;
    }

    /**
     * Changes that replace one note with another.
     *
     * <p>Matched by its exact old text rather than by position, because between reading a note and
     * saving an edit somebody else's note may have arrived - and replacing "whichever one was
     * first" would then overwrite theirs.
     *
     * @param newText the replacement; blank removes the note
     * @return no changes when {@code oldText} is not there any more, so an edit racing another
     *     editor's deletion does nothing rather than resurrecting it
     */
    public static List<OWLOntologyChange> replaceNote(OWLOntology ontology, IRI entity, Kind kind,
            String oldText, String newText) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        String was = oldText == null ? "" : oldText.trim();
        String now = newText == null ? "" : newText.trim();
        if (ontology == null || entity == null || was.equals(now)) {
            return changes;
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        boolean found = false;
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(entity)) {
            if (kind.getIri().equals(axiom.getProperty().getIRI())
                    && axiom.getValue() instanceof OWLLiteral
                    && was.equals(((OWLLiteral) axiom.getValue()).getLiteral())) {
                changes.add(new RemoveAxiom(ontology, axiom));
                found = true;
                break;
            }
        }
        if (!found && !was.isEmpty()) {
            // The note being edited is gone - deleted by somebody else, or already changed.
            // Writing the new text anyway would resurrect a note its author had removed.
            return new ArrayList<OWLOntologyChange>();
        }
        if (!now.isEmpty()) {
            changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                    property(factory, kind), entity, factory.getOWLLiteral(now))));
        }
        return changes;
    }

    /** Changes that remove one exact note. */
    public static List<OWLOntologyChange> removeNote(OWLOntology ontology, IRI entity, Kind kind,
            String text) {
        return replaceNote(ontology, entity, kind, text, "");
    }

    /**
     * The issue trackers linked from a term, in the order the ontology gives them.
     *
     * <p>{@code IAO:0000233} is the OBO answer to "where is the discussion about this term". The
     * note in the ontology says what is wrong; the tracker item points at the argument about it.
     * That split is why a threaded conversation does not have to live in a released file: an
     * importer gets the note and a link, and neither grows without bound in every diff.
     */
    public static List<String> trackerItemsOn(OWLOntology ontology, IRI entity) {
        List<String> items = new ArrayList<String>();
        if (ontology == null || entity == null) {
            return items;
        }
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(entity)) {
            if (!TERM_TRACKER_ITEM.equals(axiom.getProperty().getIRI())) {
                continue;
            }
            if (axiom.getValue() instanceof IRI) {
                items.add(axiom.getValue().toString());
            } else if (axiom.getValue() instanceof OWLLiteral) {
                // Some projects write it as a string. Read both; write only the IRI form.
                items.add(((OWLLiteral) axiom.getValue()).getLiteral());
            }
        }
        return items;
    }

    /**
     * Changes that link a term to its issue.
     *
     * <p>Written as an IRI rather than a string, which is what OBO does and what makes the link
     * resolvable by anything that reads the ontology rather than only by a person looking at it.
     *
     * @return no changes for a blank url, or when that exact link is already there
     */
    public static List<OWLOntologyChange> addTrackerItem(OWLOntology ontology, IRI entity,
            String url) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        String trimmed = url == null ? "" : url.trim();
        if (ontology == null || entity == null || trimmed.isEmpty()
                || trackerItemsOn(ontology, entity).contains(trimmed)) {
            return changes;
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(TERM_TRACKER_ITEM), entity,
                IRI.create(trimmed))));
        return changes;
    }

    /** Changes that unlink one exact issue from a term. */
    public static List<OWLOntologyChange> removeTrackerItem(OWLOntology ontology, IRI entity,
            String url) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        String trimmed = url == null ? "" : url.trim();
        if (ontology == null || entity == null || trimmed.isEmpty()) {
            return changes;
        }
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(entity)) {
            if (TERM_TRACKER_ITEM.equals(axiom.getProperty().getIRI())
                    && trimmed.equals(valueTextOf(axiom.getValue()))) {
                changes.add(new RemoveAxiom(ontology, axiom));
            }
        }
        return changes;
    }

    /**
     * Why this is not usable as a tracker item, or null.
     *
     * <p>An {@code IAO:0000233} whose value is not resolvable is worse than none: it looks like a
     * link, so nobody looks for the discussion anywhere else, and it goes nowhere.
     */
    public static String rejectTrackerItem(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isEmpty()) {
            return "Give the address of the issue.";
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "A term tracker item has to be a resolvable address - an issue URL such as "
                    + "https://github.com/owner/repo/issues/12. A bare number or a note about "
                    + "where to look would read as a link and go nowhere.";
        }
        if (trimmed.contains(" ")) {
            return "That address contains a space, so it is not a URL.";
        }
        return null;
    }

    /** An annotation value as text, whether it is an IRI or a literal. */
    private static String valueTextOf(org.semanticweb.owlapi.model.OWLAnnotationValue value) {
        if (value instanceof OWLLiteral) {
            return ((OWLLiteral) value).getLiteral();
        }
        return value == null ? "" : value.toString();
    }

    /**
     * Changes that take every editorial note out of the ontology.
     *
     * <p>For a release. {@code IAO:0000116}'s own definition says a note "may not be included in
     * the publication version of the ontology" - permission, not instruction, which is why nothing
     * calls this unless a user asks.
     */
    public static List<OWLOntologyChange> strip(OWLOntology ontology) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        if (ontology == null) {
            return changes;
        }
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAxioms(
                org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION)) {
            IRI property = axiom.getProperty().getIRI();
            if (EDITOR_NOTE.equals(property) || CURATOR_NOTE.equals(property)) {
                changes.add(new RemoveAxiom(ontology, axiom));
            }
        }
        return changes;
    }

    /**
     * Changes that declare the note properties, if they are not declared already.
     *
     * <p>An undeclared annotation property is a ROBOT report violation, and an ontology that uses
     * one without declaring it is relying on an import having done so - which is exactly the thing
     * that stops being true when somebody drops the import.
     */
    public static List<OWLOntologyChange> declareProperties(OWLOntology ontology) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        if (ontology == null) {
            return changes;
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        for (IRI iri : new IRI[] {EDITOR_NOTE, CURATOR_NOTE, TERM_TRACKER_ITEM}) {
            OWLAnnotationProperty declared = factory.getOWLAnnotationProperty(iri);
            if (!ontology.isDeclared(declared)) {
                changes.add(new AddAxiom(ontology, factory.getOWLDeclarationAxiom(declared)));
            }
        }
        return changes;
    }

    /**
     * Every entity carrying a note, with the note, for a review of what is outstanding.
     *
     * <p>The reason to have notes at all is to come back to them, and an ontology of ten thousand
     * terms hides them completely - there is no view in Protege that lists them.
     */
    public static List<Note> allIn(OWLOntology ontology) {
        List<Note> notes = new ArrayList<Note>();
        if (ontology == null) {
            return notes;
        }
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAxioms(
                org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION)) {
            IRI property = axiom.getProperty().getIRI();
            Kind kind = EDITOR_NOTE.equals(property) ? Kind.EDITOR
                    : CURATOR_NOTE.equals(property) ? Kind.CURATOR : null;
            if (kind == null || !(axiom.getValue() instanceof OWLLiteral)
                    || !(axiom.getSubject() instanceof IRI)) {
                continue;
            }
            notes.add(new Note((IRI) axiom.getSubject(), kind,
                    ((OWLLiteral) axiom.getValue()).getLiteral()));
        }
        return Collections.unmodifiableList(notes);
    }

    /** One note, and what it is on. */
    public static final class Note {
        private final IRI subject;
        private final Kind kind;
        private final String text;

        Note(IRI subject, Kind kind, String text) {
            this.subject = subject;
            this.kind = kind;
            this.text = text;
        }

        public IRI getSubject() {
            return subject;
        }

        public Kind getKind() {
            return kind;
        }

        public String getText() {
            return text;
        }

        @Override
        public String toString() {
            return kind.getLabel() + " on " + subject + ": " + text;
        }
    }

    private static OWLAnnotationProperty property(OWLDataFactory factory, Kind kind) {
        return factory.getOWLAnnotationProperty(kind.getIri());
    }
}
