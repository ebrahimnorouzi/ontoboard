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

    /**
     * The notes of this kind on an entity, with who wrote each one and when.
     *
     * <p>{@link #notesOn} still returns the words alone, because most callers want the words. This
     * is for the ones that have to decide whether to trust a note, which is a question about its
     * age and its author before it is a question about its text.
     */
    public static List<Note> attributedNotesOn(OWLOntology ontology, IRI entity, Kind kind) {
        List<Note> notes = new ArrayList<Note>();
        if (ontology == null || entity == null || kind == null) {
            return notes;
        }
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(entity)) {
            if (kind.getIri().equals(axiom.getProperty().getIRI())
                    && axiom.getValue() instanceof OWLLiteral) {
                notes.add(noteFrom(entity, kind, axiom));
            }
        }
        return notes;
    }

    /** One note read off its assertion, attribution and all. */
    private static Note noteFrom(IRI subject, Kind kind, OWLAnnotationAssertionAxiom axiom) {
        String author = "";
        String date = "";
        for (org.semanticweb.owlapi.model.OWLAnnotation annotation : axiom.getAnnotations()) {
            IRI property = annotation.getProperty().getIRI();
            if (Provenance.CONTRIBUTOR.equals(property)) {
                author = valueTextOf(annotation.getValue());
            } else if (Provenance.CREATED.equals(property)
                    || Provenance.MODIFIED.equals(property)) {
                date = valueTextOf(annotation.getValue());
            }
        }
        return new Note(subject, kind, ((OWLLiteral) axiom.getValue()).getLiteral(), author, date);
    }

    /** Whether anything is noted on this entity, of either kind. Drives the canvas marker. */
    public static boolean hasNote(OWLOntology ontology, IRI entity) {
        return !allNotesOn(ontology, entity).isEmpty();
    }

    /**
     * Every note on an entity, of either kind, editor notes first.
     *
     * <p>For the canvas, which draws a marker on a noted term and now quotes the note on hover. It
     * asks for the words rather than a flag, and it has no use for the distinction between the two
     * annotation properties - so this is the one place that flattens them, and it does so in a fixed
     * order rather than whichever the ontology happened to assert first.
     *
     * <p>{@link #hasNote} delegates here so the marker and the text cannot disagree: a term drawn as
     * noted always has something to show, and one drawn plain never does.
     */
    public static List<String> allNotesOn(OWLOntology ontology, IRI entity) {
        List<String> all = new ArrayList<String>(notesOn(ontology, entity, Kind.EDITOR));
        all.addAll(notesOn(ontology, entity, Kind.CURATOR));
        return all;
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
        return addNote(ontology, entity, kind, text, null, null);
    }

    /**
     * Changes that add a note, recording who wrote it and when.
     *
     * <p>The attribution is a gap this plugin created. Notes sync between collaborating editors and
     * carried no author or date, so a board's notes were undated anonymous text sitting beside
     * terms with full provenance. That is not a cosmetic difference. A note is advice, and advice
     * has a shelf life: "the definition needs work" written in 2019 by somebody who has since left
     * the project is a different thing from the same sentence written on Tuesday by the person
     * sitting next to you, and it is the difference between deleting the note and acting on it.
     *
     * <p>Written as annotations on the assertion rather than inside the text, so the note still
     * reads as a note to anything that consumes the ontology - a tool showing {@code IAO:0000116}
     * gets the words, not "needs work [0000-0002-..., 2019-04-02]".
     *
     * <p>The same two properties as term provenance, {@code dcterms:contributor} and
     * {@code dcterms:created}, because a project that has one convention should not acquire a
     * second one for notes. The caller declares them - see {@link Provenance#declareProperties} -
     * on the same condition that decides whether to attribute at all.
     *
     * @param agent an ORCID or name; blank writes the note unattributed, as before
     * @param isoDate {@code YYYY-MM-DD}; the caller owns the clock
     */
    public static List<OWLOntologyChange> addNote(OWLOntology ontology, IRI entity, Kind kind,
            String text, String agent, String isoDate) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        String trimmed = text == null ? "" : text.trim();
        if (ontology == null || entity == null || trimmed.isEmpty()) {
            return changes;
        }
        for (Note existing : attributedNotesOn(ontology, entity, kind)) {
            // Already said, word for word, by the same person. Adding it again would put two
            // identical annotations on the term, which no reader can tell apart and no diff
            // explains. Two people saying the same thing years apart is a different matter and is
            // allowed: with attribution those are two facts, not one repeated.
            if (existing.getText().equals(trimmed)
                    && existing.getAuthor().equals(canonical(agent))) {
                return changes;
            }
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                property(factory, kind), entity, factory.getOWLLiteral(trimmed),
                attribution(factory, agent, isoDate))));
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
        return replaceNote(ontology, entity, kind, oldText, newText, null, null);
    }

    /**
     * Changes that replace one note with another, attributing the replacement.
     *
     * <p>The new author replaces the old one rather than joining them. Editing somebody's note is
     * not contributing to it: the words are now yours, and leaving their name on text they did not
     * write would attribute an opinion to somebody who may disagree with it.
     */
    public static List<OWLOntologyChange> replaceNote(OWLOntology ontology, IRI entity, Kind kind,
            String oldText, String newText, String agent, String isoDate) {
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
                    property(factory, kind), entity, factory.getOWLLiteral(now),
                    attribution(factory, agent, isoDate))));
        }
        return changes;
    }

    /**
     * The annotations recording who wrote a note and when.
     *
     * <p>Empty for a blank agent, which is what an ontology that does not keep provenance gets -
     * the same rule as everywhere else, so leaving a note never starts a convention the project
     * did not choose.
     */
    private static java.util.Set<org.semanticweb.owlapi.model.OWLAnnotation> attribution(
            OWLDataFactory factory, String agent, String isoDate) {
        java.util.Set<org.semanticweb.owlapi.model.OWLAnnotation> annotations =
                new java.util.LinkedHashSet<org.semanticweb.owlapi.model.OWLAnnotation>();
        if (agent == null || agent.trim().isEmpty()) {
            return annotations;
        }
        annotations.addAll(Provenance.creationAnnotations(factory, agent, isoDate));
        return annotations;
    }

    /** An agent as it will be recorded, so a note written twice can be recognised as one. */
    private static String canonical(String agent) {
        if (agent == null || agent.trim().isEmpty()) {
            return "";
        }
        String orcid = Provenance.normaliseOrcid(agent);
        return orcid != null ? orcid : agent.trim();
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
            notes.add(noteFrom((IRI) axiom.getSubject(), kind, axiom));
        }
        return Collections.unmodifiableList(notes);
    }

    /** One note, and what it is on. */
    public static final class Note {
        private final IRI subject;
        private final Kind kind;
        private final String text;
        private final String author;
        private final String date;

        Note(IRI subject, Kind kind, String text, String author, String date) {
            this.subject = subject;
            this.kind = kind;
            this.text = text;
            this.author = author == null ? "" : author;
            this.date = date == null ? "" : date;
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

        /** Who wrote it - an ORCID IRI or a name - or empty when the note is unattributed. */
        public String getAuthor() {
            return author;
        }

        /** When it was written, {@code YYYY-MM-DD}, or empty. */
        public String getDate() {
            return date;
        }

        /**
         * Who and when, in one phrase, or empty when neither is recorded.
         *
         * <p>Empty rather than "unknown author": a note written before the project attributed
         * anything is not a note by an unknown person, it is a note from before the practice
         * existed, and saying "unknown" invites somebody to go looking for an answer that was
         * never recorded.
         */
        public String describeAttribution() {
            if (author.isEmpty() && date.isEmpty()) {
                return "";
            }
            if (author.isEmpty()) {
                return date;
            }
            return date.isEmpty() ? author : author + ", " + date;
        }

        @Override
        public String toString() {
            String attribution = describeAttribution();
            return kind.getLabel() + " on " + subject + ": " + text
                    + (attribution.isEmpty() ? "" : " (" + attribution + ")");
        }
    }

    private static OWLAnnotationProperty property(OWLDataFactory factory, Kind kind) {
        return factory.getOWLAnnotationProperty(kind.getIri());
    }
}
