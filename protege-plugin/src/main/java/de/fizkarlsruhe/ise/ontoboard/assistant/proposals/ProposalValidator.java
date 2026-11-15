package de.fizkarlsruhe.ise.ontoboard.assistant.proposals;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.NameResolver;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.AssistantRendering;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Decides whether a proposal can be applied as the person will read it, before it becomes a
 * card. What fails is INVALID with a reason a person can act on, and is shown as text.
 *
 * <ul>
 *   <li>The subject resolves to exactly one term, by {@link NameResolver}: never the nearest
 *       match.</li>
 *   <li>The action and property are known, the value is not empty, and a label or synonym is one
 *       line.</li>
 *   <li>A term has one label and one definition per language: {@code add} for one it already has
 *       is refused (use {@code set}), and {@code set} for a synonym or example, which would
 *       replace them all, is refused (use {@code add}).</li>
 *   <li>A {@code remove-axiom} matches exactly one logical axiom of this ontology, compared as
 *       {@link AssistantRendering} writes it with whitespace normalised - the text the model was
 *       shown.</li>
 * </ul>
 *
 * <p>An instance keeps its name index and rendered axioms, so validating every proposal of an
 * answer reads the ontology once.
 */
public final class ProposalValidator {

    private final OWLOntology ontology;
    private NameResolver names;
    private Map<String, List<OWLAxiom>> own;
    private Map<String, List<OWLAxiom>> imported;
    private AxiomParser axioms;

    public ProposalValidator(OWLOntology ontology) {
        this.ontology = ontology;
    }

    public static Proposal validate(OWLOntology ontology, Proposal proposal) {
        return new ProposalValidator(ontology).validate(proposal);
    }

    public List<Proposal> validateAll(List<Proposal> proposals) {
        List<Proposal> checked = new ArrayList<Proposal>();
        for (Proposal proposal : proposals) {
            checked.add(validate(proposal));
        }
        return checked;
    }

    public Proposal validate(Proposal proposal) {
        if (proposal.getAction() == null) {
            // Every action: add-axiom and replace-axiom (1.139.0) were left out of the list
            // (1.149.0, the 1.148.0 bench reviewer's declined note).
            return proposal.invalid("OntoBoard does not know the action \""
                    + nonNull(proposal.getActionText()) + "\": it knows set, add, remove-axiom,"
                    + " add-axiom and replace-axiom.");
        }
        if (proposal.getAction() == Proposal.Action.REMOVE_AXIOM) {
            return axiom(proposal);
        }
        if (proposal.getAction() == Proposal.Action.ADD_AXIOM) {
            return addition(proposal);
        }
        if (proposal.getAction() == Proposal.Action.REPLACE_AXIOM) {
            return replacement(proposal);
        }
        return annotation(proposal);
    }

    /** An axiom to add: read against the ontology's names, and not one it already has. */
    private Proposal addition(Proposal proposal) {
        // Half of what was proposed vanished silently (review 1.140.0 M8).
        if (proposal.getWith() != null && !proposal.getWith().trim().isEmpty()) {
            return proposal.invalid("\"with:\" belongs to replace-axiom. To add two axioms, give"
                    + " each its own add-axiom block; for a definition, write one EquivalentTo"
                    + " axiom.");
        }
        AxiomParser.Result read = parser().parse(proposal.getAxiom());
        if (read.getAxiom() == null) {
            return proposal.invalid(read.getProblem());
        }
        OWLAxiom axiom = read.getAxiom();
        if (!axiom.isLogicalAxiom()) {
            return proposal.invalid("Only logic is added this way; a label, definition or"
                    + " comment is set or added as an annotation.");
        }
        if (alreadySays(axiom)) {
            return proposal.invalid("The ontology already says this.");
        }
        return proposal.axioms(null, axiom, QuantifierNotes.of(ontology, axiom))
                .shown(AssistantRendering.render(ontology, axiom), null);
    }

    /** An axiom, as shown, and the one to put in its place. */
    private Proposal replacement(Proposal proposal) {
        Proposal old = axiom(proposal);
        if (old.getStatus() != Proposal.Status.OFFERED) {
            return old;
        }
        if (proposal.getWith() == null || proposal.getWith().trim().isEmpty()) {
            return proposal.invalid("It names no new axiom: the replacement goes after"
                    + " \"with:\".");
        }
        AxiomParser.Result read = parser().parse(proposal.getWith());
        if (read.getAxiom() == null) {
            return proposal.invalid("The new axiom: " + read.getProblem());
        }
        OWLAxiom added = read.getAxiom();
        if (!added.isLogicalAxiom()) {
            return proposal.invalid("The new axiom must be logic, not an annotation.");
        }
        if (added.equals(old.getMatchedAxiom().getAxiomWithoutAnnotations())) {
            return proposal.invalid("The new axiom is the old one: nothing would change.");
        }
        if (alreadySays(added)) {
            return proposal.invalid("The ontology already says the new axiom; to drop the old"
                    + " one, a remove-axiom is enough.");
        }
        return old.axioms(old.getMatchedAxiom(), added, QuantifierNotes.of(ontology, added))
                .shown(AssistantRendering.render(ontology, old.getMatchedAxiom()),
                        AssistantRendering.render(ontology, added));
    }

    /**
     * On Accept, for an axiom card (review 1.139.0 I2): the axioms it showed still fit - the one
     * it removes is still there, every term of the one it adds is still in the ontology, and the
     * ontology does not already say that one. Null when it fits, else why not. The text is not
     * read again: another card of the same answer may have renamed a term it names.
     */
    public static String stillFits(OWLOntology ontology, Proposal proposal) {
        OWLAxiom removed = proposal.getMatchedAxiom();
        if (removed != null && !ontology.containsAxiom(removed)) {
            return "The axiom is already gone from the ontology.";
        }
        OWLAxiom added = proposal.getNewAxiom();
        if (added == null) {
            return null;
        }
        for (org.semanticweb.owlapi.model.OWLEntity entity : added.getSignature()) {
            if (!entity.isBuiltIn() && !ontology.containsEntityInSignature(entity,
                    Imports.INCLUDED)) {
                return "A term it uses is no longer in the ontology: <" + entity.getIRI() + ">.";
            }
        }
        return new ProposalValidator(ontology).alreadySays(added)
                ? "The ontology already says this." : null;
    }

    /** In the ontology or its imports, with or without annotations. */
    private boolean alreadySays(OWLAxiom axiom) {
        for (OWLOntology part : ontology.getImportsClosure()) {
            if (part.containsAxiomIgnoreAnnotations(axiom)) {
                return true;
            }
        }
        return false;
    }

    private AxiomParser parser() {
        if (axioms == null) {
            axioms = new AxiomParser(ontology);
        }
        return axioms;
    }

    private Proposal annotation(Proposal proposal) {
        Proposal.Property property = proposal.getProperty();
        if (property == null) {
            return proposal.invalid("OntoBoard does not know the property \""
                    + nonNull(proposal.getPropertyText()) + "\": it knows label, definition,"
                    + " comment, synonym and example.");
        }
        if (proposal.getAction() == Proposal.Action.SET
                && (property == Proposal.Property.SYNONYM
                        || property == Proposal.Property.EXAMPLE)) {
            return proposal.invalid("\"set\" would replace every " + property.getWord()
                    + " of the term; a new " + property.getWord() + " is added with \"add\".");
        }
        if (proposal.getSubject() == null || proposal.getSubject().trim().isEmpty()) {
            return proposal.invalid("It names no subject, so OntoBoard cannot tell which term it"
                    + " is for.");
        }
        IRI subject = proposal.getSubjectIri();
        if (subject == null) {
            NameResolver.Resolution found = names().resolve(proposal.getSubject());
            if (found.isAmbiguous()) {
                return proposal.invalid("\"" + proposal.getSubject() + "\" is ambiguous: it"
                        + " names " + String.join(", ", found.getCandidates()) + ".");
            }
            if (!found.isFound()) {
                return proposal.invalid("\"" + proposal.getSubject() + "\" is not in this"
                        + " ontology.");
            }
            subject = found.getIri();
        }
        // On Accept the card's term is checked again by its IRI, not its name, which another
        // card of the same answer may have just changed (1.138.0 review R5).
        if (ontology.getEntitiesInSignature(subject, Imports.INCLUDED).isEmpty()) {
            return proposal.invalid("The term is no longer in this ontology.");
        }
        String value = proposal.getValueText();
        if (value.isEmpty()) {
            return proposal.invalid("It has no value.");
        }
        if (property.isOneLine() && proposal.getValue().indexOf('\n') >= 0) {
            return proposal.invalid("A " + property.getWord() + " must fit on one line.");
        }
        IRI propertyIri = propertyFor(property, subject);
        List<OWLAnnotationAssertionAxiom> existing = assertions(ontology, subject, propertyIri);
        List<OWLAnnotationAssertionAxiom> imported = new ArrayList<OWLAnnotationAssertionAxiom>();
        for (OWLOntology part : ontology.getImportsClosure()) {
            if (part != ontology) {
                imported.addAll(assertions(part, subject, propertyIri));
            }
        }
        String language = proposal.getLanguage() != null ? proposal.getLanguage()
                : languageOf(existing.isEmpty() ? imported : existing);
        List<String> same = new ArrayList<String>();
        for (OWLAnnotationAssertionAxiom axiom : inLanguage(existing, language)) {
            same.add(literal(axiom).getLiteral());
        }
        // Review I2: a value that lives in an import cannot be replaced from here, and a second
        // label or definition beside it is not what "set" promised.
        boolean replacing = proposal.getAction() == Proposal.Action.SET
                || property == Proposal.Property.LABEL
                || property == Proposal.Property.DEFINITION;
        // Imported values count in the language given exactly (an untagged one when none is):
        // a German definition beside an imported untagged one is a translation, added here
        // (1.138.0 review R6).
        List<OWLAnnotationAssertionAxiom> importedSame = new ArrayList<OWLAnnotationAssertionAxiom>();
        for (OWLAnnotationAssertionAxiom axiom : imported) {
            if (literal(axiom).getLang().equalsIgnoreCase(language == null ? "" : language)) {
                importedSame.add(axiom);
            }
        }
        if (same.isEmpty() && replacing && !importedSame.isEmpty()) {
            return proposal.invalid("The term's " + property.getWord() + " is in an imported"
                    + " ontology; change it there.");
        }
        if (same.contains(value)) {
            return proposal.invalid("The term already has this " + property.getWord() + ".");
        }
        if (proposal.getAction() == Proposal.Action.ADD && !same.isEmpty()
                && (property == Proposal.Property.LABEL
                        || property == Proposal.Property.DEFINITION)) {
            return proposal.invalid("The term already has a " + property.getWord()
                    + (language == null ? "" : " in " + language) + "; \"set\" replaces it.");
        }
        List<String> replaced = proposal.getAction() == Proposal.Action.SET ? same
                : Collections.<String>emptyList();
        return proposal.resolved(subject, propertyIri, language, null, replaced);
    }

    private Proposal axiom(Proposal proposal) {
        String text = key(proposal.getAxiom());
        if (text.isEmpty()) {
            return proposal.invalid("It names no axiom: the axiom must be copied exactly as the"
                    + " context shows it.");
        }
        List<OWLAxiom> matched = own().get(text);
        if (matched == null || matched.isEmpty()) {
            List<OWLAxiom> elsewhere = imported().get(text);
            if (elsewhere != null && !elsewhere.isEmpty()) {
                return proposal.invalid("The axiom is in an imported ontology, not in this one;"
                        + " it can only be removed there.");
            }
            return proposal.invalid("It matches no axiom of this ontology as the context shows"
                    + " it.");
        }
        if (matched.size() > 1) {
            return proposal.invalid("It matches " + matched.size() + " axioms that read alike;"
                    + " OntoBoard removes only an axiom it can name exactly.");
        }
        return proposal.resolved(null, null, null, matched.get(0),
                Collections.<String>emptyList())
                .shown(AssistantRendering.render(ontology, matched.get(0)), null);
    }

    /**
     * The property the term already uses for this (an ontology with skos:definition keeps it);
     * else the one the ontology uses; else OntoBoard's default, the first.
     */
    private IRI propertyFor(Proposal.Property property, IRI subject) {
        for (IRI iri : property.getIris()) {
            for (OWLOntology part : ontology.getImportsClosure()) {
                for (OWLAnnotationAssertionAxiom axiom : part.getAnnotationAssertionAxioms(subject)) {
                    if (axiom.getProperty().getIRI().equals(iri)) {
                        return iri;
                    }
                }
            }
        }
        for (IRI iri : property.getIris()) {
            if (ontology.containsAnnotationPropertyInSignature(iri, Imports.INCLUDED)) {
                return iri;
            }
        }
        return property.getIris().get(0);
    }

    /** The term's literal values of the property in this ontology: what a {@code set} can replace. */
    static List<OWLAnnotationAssertionAxiom> assertions(OWLOntology ontology, IRI subject,
            IRI property) {
        List<OWLAnnotationAssertionAxiom> found = new ArrayList<OWLAnnotationAssertionAxiom>();
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(subject)) {
            if (axiom.getProperty().getIRI().equals(property)
                    && axiom.getValue().asLiteral().isPresent()) {
                found.add(axiom);
            }
        }
        return found;
    }

    private static OWLLiteral literal(OWLAnnotationAssertionAxiom axiom) {
        return axiom.getValue().asLiteral().get();
    }

    /**
     * No language given: the one the term's values share; with several, English; with none, no
     * tag. "Set the label" then replaces the English label, not every label.
     */
    static String languageOf(List<OWLAnnotationAssertionAxiom> existing) {
        Set<String> languages = new LinkedHashSet<String>();
        for (OWLAnnotationAssertionAxiom axiom : existing) {
            languages.add(literal(axiom).getLang().toLowerCase(java.util.Locale.ROOT));
        }
        if (languages.size() == 1) {
            String only = languages.iterator().next();
            return only.isEmpty() ? null : only;
        }
        return languages.contains("en") ? "en" : null;
    }

    /**
     * What a {@code set} in this language replaces: the values in the language; when there are
     * none and a language was given, the untagged ones, which say the same thing in no language.
     */
    static List<OWLAnnotationAssertionAxiom> inLanguage(
            List<OWLAnnotationAssertionAxiom> existing, String language) {
        String wanted = language == null ? "" : language;
        List<OWLAnnotationAssertionAxiom> same = new ArrayList<OWLAnnotationAssertionAxiom>();
        for (OWLAnnotationAssertionAxiom axiom : existing) {
            if (literal(axiom).getLang().equalsIgnoreCase(wanted)) {
                same.add(axiom);
            }
        }
        if (same.isEmpty() && !wanted.isEmpty()) {
            for (OWLAnnotationAssertionAxiom axiom : existing) {
                if (literal(axiom).getLang().isEmpty()) {
                    same.add(axiom);
                }
            }
        }
        return same;
    }

    private NameResolver names() {
        if (names == null) {
            names = new NameResolver(ontology);
        }
        return names;
    }

    private Map<String, List<OWLAxiom>> own() {
        if (own == null) {
            own = index(Collections.singleton(ontology));
        }
        return own;
    }

    private Map<String, List<OWLAxiom>> imported() {
        if (imported == null) {
            Set<OWLOntology> others = new LinkedHashSet<OWLOntology>(ontology.getImportsClosure());
            others.remove(ontology);
            imported = index(others);
        }
        return imported;
    }

    /**
     * Each logical axiom under its rendering, with and without its annotations: a justification
     * shows an axiom without them, the ontology holds it with them.
     */
    private Map<String, List<OWLAxiom>> index(Set<OWLOntology> parts) {
        Map<String, List<OWLAxiom>> index = new HashMap<String, List<OWLAxiom>>();
        for (OWLOntology part : parts) {
            for (OWLAxiom axiom : part.getLogicalAxioms()) {
                Set<String> keys = new LinkedHashSet<String>();
                keys.add(key(AssistantRendering.render(ontology, axiom)));
                if (!axiom.getAnnotations().isEmpty()) {
                    keys.add(key(AssistantRendering.render(ontology,
                            axiom.getAxiomWithoutAnnotations())));
                }
                for (String key : keys) {
                    List<OWLAxiom> list = index.get(key);
                    if (list == null) {
                        list = new ArrayList<OWLAxiom>();
                        index.put(key, list);
                    }
                    if (!list.contains(axiom)) {
                        list.add(axiom);
                    }
                }
            }
        }
        return index;
    }

    static String normal(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    /**
     * How an axiom is looked up: whitespace normalised and single quotes left out, so an axiom
     * copied without the quotes around a name with a space still names it (1.140.0, measured:
     * qwen2.5:7b wrote "has value Range: xsd:float" three rounds running). Two axioms that read
     * alike this way are refused as reading alike, never guessed between.
     */
    static String key(String text) {
        return normal(text).replace("'", "");
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }
}
