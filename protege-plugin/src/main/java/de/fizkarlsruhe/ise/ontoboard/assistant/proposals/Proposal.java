package de.fizkarlsruhe.ise.ontoboard.assistant.proposals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;

/**
 * One change the model suggested, as the person sees it on a card: what it is, why, and what
 * became of it. Immutable; a change of status is a new value with the same {@link #getId()}.
 *
 * <p>What the model wrote is kept as written ({@link #getSubject()}, {@link #getActionText()},
 * {@link #getPropertyText()}); what OntoBoard made of it ({@link #getSubjectIri()},
 * {@link #getPropertyIri()}, {@link #getMatchedAxiom()}, {@link #getReplaced()}) is filled in by
 * {@link ProposalValidator}, so a card never shows a guess as if the model had said it.
 */
public final class Proposal {

    /** What the change does. */
    public enum Action {
        /** Replace a label, definition or comment in its language. */
        SET("set"),
        /** Add a synonym, an example or a comment. */
        ADD("add"),
        /** Remove one logical axiom, written exactly as the context showed it. */
        REMOVE_AXIOM("remove-axiom"),
        /** Add one logical axiom, in Manchester syntax with the ontology's names (1.139.0). */
        ADD_AXIOM("add-axiom"),
        /** Replace one axiom, as shown, by another: a repair that changes (1.139.0). */
        REPLACE_AXIOM("replace-axiom");

        /** Whether the action is about logic rather than an annotation. */
        public boolean isLogic() {
            return this == REMOVE_AXIOM || this == ADD_AXIOM || this == REPLACE_AXIOM;
        }

        private final String word;

        Action(String word) {
            this.word = word;
        }

        public String getWord() {
            return word;
        }

        /** {@code set}, {@code add}, {@code remove-axiom} (or {@code remove axiom}); else null. */
        static Action of(String text) {
            String word = text == null ? "" : text.trim().toLowerCase(Locale.ROOT)
                    .replace('_', '-').replace(' ', '-');
            for (Action action : values()) {
                if (action.word.equals(word)) {
                    return action;
                }
            }
            return null;
        }
    }

    /**
     * The annotations a proposal may change. The first IRI is the one written when the term and
     * the ontology use none of them; the others are what an ontology may already use instead.
     */
    public enum Property {
        LABEL("label", true, "http://www.w3.org/2000/01/rdf-schema#label",
                "http://www.w3.org/2004/02/skos/core#prefLabel"),
        DEFINITION("definition", false, "http://purl.obolibrary.org/obo/IAO_0000115",
                "http://www.w3.org/2004/02/skos/core#definition"),
        COMMENT("comment", false, "http://www.w3.org/2000/01/rdf-schema#comment"),
        SYNONYM("synonym", true, "http://www.geneontology.org/formats/oboInOwl#hasExactSynonym",
                "http://www.w3.org/2004/02/skos/core#altLabel"),
        EXAMPLE("example", false, "http://purl.obolibrary.org/obo/IAO_0000112",
                "http://www.w3.org/2004/02/skos/core#example");

        private final String word;
        private final boolean oneLine;
        private final List<IRI> iris;

        Property(String word, boolean oneLine, String... iris) {
            this.word = word;
            this.oneLine = oneLine;
            IRI[] all = new IRI[iris.length];
            for (int i = 0; i < iris.length; i++) {
                all[i] = IRI.create(iris[i]);
            }
            this.iris = Collections.unmodifiableList(Arrays.asList(all));
        }

        public String getWord() {
            return word;
        }

        /** A name: a label or a synonym is one line. */
        public boolean isOneLine() {
            return oneLine;
        }

        public List<IRI> getIris() {
            return iris;
        }

        /** The word, or a CURIE or IRI of one of its properties; else null. */
        static Property of(String text) {
            String word = text == null ? "" : text.trim();
            for (Property property : values()) {
                if (property.word.equalsIgnoreCase(word)) {
                    return property;
                }
                for (IRI iri : property.iris) {
                    if (iri.toString().equals(word) || curies(iri).contains(word)) {
                        return property;
                    }
                }
            }
            return null;
        }

        private static List<String> curies(IRI iri) {
            String full = iri.toString();
            if (full.startsWith("http://purl.obolibrary.org/obo/")) {
                String local = full.substring("http://purl.obolibrary.org/obo/".length());
                return Arrays.asList("obo:" + local, local.replace('_', ':'));
            }
            if (full.startsWith("http://www.w3.org/2000/01/rdf-schema#")) {
                return Collections.singletonList("rdfs:" + iri.getShortForm());
            }
            if (full.startsWith("http://www.w3.org/2004/02/skos/core#")) {
                return Collections.singletonList("skos:" + iri.getShortForm());
            }
            if (full.startsWith("http://www.geneontology.org/formats/oboInOwl#")) {
                return Collections.singletonList("oboInOwl:" + iri.getShortForm());
            }
            return Collections.emptyList();
        }
    }

    /** What became of it. */
    public enum Status {
        /** Valid, waiting for the person. */
        OFFERED,
        /** Cannot be applied as written; {@link #getProblem()} says why. */
        INVALID,
        ACCEPTED,
        REJECTED,
        /** The person asked for a different version; the new answer brings new cards. */
        REVISED
    }

    private final int id;
    private final String actionText;
    private final Action action;
    private final String subject;
    private final String propertyText;
    private final Property property;
    private final String value;
    private final String language;
    private final String axiom;
    private final String with;
    private final OWLAxiom newAxiom;
    private final String reason;
    private final Status status;
    private final String problem;
    private final IRI subjectIri;
    private final IRI propertyIri;
    private final OWLAxiom matchedAxiom;
    private final List<String> replaced;
    private final List<String> notes;
    private final String shownAxiom;
    private final String shownWith;

    private Proposal(Builder b) {
        this.id = b.id;
        this.actionText = b.actionText;
        this.action = Action.of(b.actionText);
        this.subject = b.subject;
        this.propertyText = b.propertyText;
        this.property = Property.of(b.propertyText);
        this.value = b.value;
        this.language = b.language;
        this.axiom = b.axiom;
        this.with = b.with;
        this.newAxiom = b.newAxiom;
        this.reason = b.reason;
        this.status = b.status;
        this.problem = b.problem;
        this.subjectIri = b.subjectIri;
        this.propertyIri = b.propertyIri;
        this.matchedAxiom = b.matchedAxiom;
        this.notes = b.notes == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(b.notes);
        this.replaced = b.replaced == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(b.replaced);
        this.shownAxiom = b.shownAxiom;
        this.shownWith = b.shownWith;
    }

    public int getId() {
        return id;
    }

    /** The action as the model wrote it. */
    public String getActionText() {
        return actionText;
    }

    /** Null when the model wrote an action OntoBoard does not know. */
    public Action getAction() {
        return action;
    }

    /** The term as the model named it; null for a {@code remove-axiom} that names none. */
    public String getSubject() {
        return subject;
    }

    public String getPropertyText() {
        return propertyText;
    }

    /** Null when the model named a property OntoBoard does not know. */
    public Property getProperty() {
        return property;
    }

    /** As written; lines the model continued the value over are joined with "\n". */
    public String getValue() {
        return value;
    }

    /**
     * The value as it is written into the ontology: one paragraph, its lines joined by a space,
     * since a model breaking a definition over lines is wrapping, not writing two paragraphs.
     */
    public String getValueText() {
        return value == null ? "" : value.replaceAll("\\s*\\n\\s*", " ").trim();
    }

    /** A language tag; null for none. After validation, the one the change is written in. */
    public String getLanguage() {
        return language;
    }

    /** For {@code remove-axiom}: the axiom as the model copied it. */
    public String getAxiom() {
        return axiom;
    }

    /** For {@code replace-axiom}: the new axiom as the model wrote it. */
    public String getWith() {
        return with;
    }

    /** For {@code add-axiom} and {@code replace-axiom}, after validation: the axiom added. */
    public OWLAxiom getNewAxiom() {
        return newAxiom;
    }

    /**
     * What a card and the log show (review 1.139.0 I1): after validation, the axiom removed - or,
     * for {@code add-axiom}, added - as OntoBoard writes it, under the ontology's names; before,
     * or when invalid, the model's text.
     */
    public String getShownAxiom() {
        return shownAxiom != null ? shownAxiom : axiom;
    }

    /** For {@code replace-axiom}: the new axiom as it will be written; else the model's text. */
    public String getShownWith() {
        return shownWith != null ? shownWith : with;
    }

    /** The same proposal, with the axioms as they will be written. */
    Proposal shown(String axiomShown, String withShown) {
        Builder b = toBuilder();
        b.shownAxiom = axiomShown;
        b.shownWith = withShown;
        return b.build();
    }

    public String getReason() {
        return reason;
    }

    public Status getStatus() {
        return status;
    }

    /** Why it is INVALID; null otherwise. */
    public String getProblem() {
        return problem;
    }

    /** The term the subject resolved to, after validation. */
    public IRI getSubjectIri() {
        return subjectIri;
    }

    /** The annotation property the change is written with, after validation. */
    public IRI getPropertyIri() {
        return propertyIri;
    }

    /** For {@code remove-axiom}, after validation: the one axiom it matched. */
    public OWLAxiom getMatchedAxiom() {
        return matchedAxiom;
    }

    /** For {@code set}, after validation: the values it replaces, for striking through. */
    /** For logic, after validation: what the card should say beside it (the quantifier trap). */
    public List<String> getNotes() {
        return notes;
    }

    public List<String> getReplaced() {
        return replaced;
    }

    public Proposal withStatus(Status next) {
        Builder b = toBuilder();
        b.status = next;
        return b.build();
    }

    /** INVALID, with a reason a person can act on. */
    public Proposal invalid(String why) {
        Builder b = toBuilder();
        b.status = Status.INVALID;
        b.problem = why;
        return b.build();
    }

    Proposal resolved(IRI subjectIri, IRI propertyIri, String language, OWLAxiom matched,
            List<String> replaced) {
        Builder b = toBuilder();
        b.subjectIri = subjectIri;
        b.propertyIri = propertyIri;
        b.language = language;
        b.matchedAxiom = matched;
        b.replaced = replaced;
        b.notes = notes;
        b.status = Status.OFFERED;
        b.problem = null;
        return b.build();
    }

    /** Valid logic: the axiom removed (null for an add) and the axiom added (null for a removal). */
    Proposal axioms(OWLAxiom removed, OWLAxiom added, List<String> sayAlso) {
        Builder b = toBuilder();
        b.matchedAxiom = removed;
        b.newAxiom = added;
        b.notes = sayAlso;
        b.status = Status.OFFERED;
        b.problem = null;
        return b.build();
    }

    /** "set definition of Concept", "remove-axiom": what a card's title says. */
    public String describe() {
        if (action == Action.REMOVE_AXIOM) {
            return "Remove an axiom";
        }
        if (action == Action.ADD_AXIOM) {
            return "Add an axiom";
        }
        if (action == Action.REPLACE_AXIOM) {
            return "Replace an axiom";
        }
        String verb = action == Action.ADD ? "Add a " : action == Action.SET ? "Set the " : "";
        String what = property == null ? propertyText : property.getWord();
        return verb + (what == null ? "" : what) + (subject == null ? "" : " of " + subject)
                + (language == null ? "" : " (" + language + ")");
    }

    @Override
    public String toString() {
        return describe() + " [" + status + "]";
    }

    private Builder toBuilder() {
        Builder b = new Builder(id);
        b.actionText = actionText;
        b.subject = subject;
        b.propertyText = propertyText;
        b.value = value;
        b.language = language;
        b.axiom = axiom;
        b.with = with;
        b.newAxiom = newAxiom;
        b.reason = reason;
        b.status = status;
        b.problem = problem;
        b.subjectIri = subjectIri;
        b.propertyIri = propertyIri;
        b.matchedAxiom = matchedAxiom;
        b.replaced = replaced;
        b.notes = notes;
        b.shownAxiom = shownAxiom;
        b.shownWith = shownWith;
        return b;
    }

    /** For {@link ProposalBlock} and tests. */
    static final class Builder {
        private final int id;
        String actionText;
        String subject;
        String propertyText;
        String value;
        String language;
        String axiom;
        String with;
        OWLAxiom newAxiom;
        String reason;
        Status status = Status.OFFERED;
        String problem;
        IRI subjectIri;
        IRI propertyIri;
        OWLAxiom matchedAxiom;
        List<String> replaced;
        List<String> notes;
        String shownAxiom;
        String shownWith;

        Builder(int id) {
            this.id = id;
        }

        Proposal build() {
            return new Proposal(this);
        }
    }
}
