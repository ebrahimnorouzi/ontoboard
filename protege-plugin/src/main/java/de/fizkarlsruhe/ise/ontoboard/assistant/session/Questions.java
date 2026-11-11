package de.fizkarlsruhe.ise.ontoboard.assistant.session;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

/**
 * The questions OntoBoard asks on the person's behalf when they press a button: the reasoner
 * problem, a definition. One place, because the benchmark asks exactly these: a score is only
 * what a user would get if the model was asked the same thing.
 */
public final class Questions {

    private static final String REPAIR = " Then propose the repair as an ontoboard-proposal block:"
            + " action: remove-axiom when an axiom of the justification is wrong, or action:"
            + " replace-axiom when it is right in spirit but wrong in a detail (a range, a"
            + " quantifier, a class), with the axiom copied exactly as the justification shows"
            + " it.";

    private Questions() {
    }

    public static String whyInconsistent() {
        return "Why is the ontology inconsistent? Explain the justification in plain language,"
                + " and say which axiom is most likely the mistake." + REPAIR;
    }

    public static String whyUnsatisfiable(String label) {
        return "Why is " + label + " unsatisfiable? Explain the justification in plain language,"
                + " and say which axiom is most likely the mistake." + REPAIR;
    }

    /**
     * "Suggest a definition": genus-differentia, the genus being the asserted parent, as a
     * proposal the person accepts.
     *
     * @param parents the labels of the term's asserted named parents; empty for none
     * @param hasDefinition whether it has one already, which is then to be improved, not replaced
     *     for its own sake
     */
    public static String suggestDefinition(String subject, List<String> parents,
            boolean hasDefinition) {
        StringBuilder out = new StringBuilder();
        out.append(hasDefinition ? "Suggest a better definition for " : "Write a definition for ")
                .append(subject).append(", as one ontoboard-proposal block (action: set,"
                        + " property: definition). Use the genus-differentia form, \"A <genus>"
                        + " that <differentia>\".");
        if (parents.isEmpty()) {
            out.append(" It has no asserted parent class: name the genus you assume in the"
                    + " reason, or say that this needs a domain expert.");
        } else {
            out.append(" The genus is its asserted parent: ").append(String.join(", ", parents))
                    .append('.');
        }
        if (hasDefinition) {
            out.append(" Keep what the current definition gets right, and say in the reason what"
                    + " you improved.");
        } else {
            out.append(" Say in the reason what sets it apart from its parent's other"
                    + " subclasses.");
        }
        out.append(" If the context does not tell you enough, say what you need instead of"
                + " guessing.");
        return out.toString();
    }

    /**
     * "Suggest a better wording" on a label, definition or comment line of the term.
     *
     * @param language the line's language tag, or null for none
     */
    public static String betterWording(String subject, String property, String text,
            String language) {
        String tag = language == null || language.isEmpty() ? "" : ", language: " + language;
        return "Suggest a better wording for this " + property + " of " + subject + ", as one"
                + " ontoboard-proposal block (action: set, property: " + property + tag + "):\n"
                + text + "\nKeep its meaning, and say in the reason what you improved. If it is"
                + " already good, say so and propose nothing.";
    }

    /**
     * "Write as OWL…": the person's sentence, as one axiom about the term. The hint names every
     * restriction a sentence can need: it named only "some" and "only", which a sentence with a
     * number or one named value cannot use (1.145.0, review 1.144.0 bench-2).
     */
    public static String sentenceToAxiom(String subject, String sentence) {
        return "Write this as OWL about " + subject + ", as one ontoboard-proposal block (action:"
                + " add-axiom), in Manchester syntax with the names the context uses - a name"
                + " with a space in single quotes:\n" + sentence + "\nUse \"some\" where"
                + " something must exist, \"only\" where it restricts what may be, \"min\","
                + " \"max\" or \"exactly\" with the number where the sentence gives one, and"
                + " \"value\" where it names one individual or value; say in the reason which"
                + " you chose and why. If a term it needs is not in the context, ask for it"
                + " instead of inventing it.";
    }

    /** ✦ "Suggest a better axiom": a replacement for one axiom of the term. */
    public static String betterAxiom(String subject, String axiom) {
        return "Suggest a better axiom for " + subject + " in place of this one, as one"
                + " ontoboard-proposal block (action: replace-axiom): the axiom copied exactly"
                + " after \"axiom:\" and the new one after \"with:\".\n" + axiom + "\nSay in the"
                + " reason what was wrong with it. If it is right as it is, say so and propose"
                + " nothing.";
    }

    /** The labels of a class's asserted named superclasses, sorted; empty for anything else. */
    /**
     * The terms a sentence for "Write as OWL…" names, other than its subject, in the order they
     * appear: sent with it as chips, so the model sees what each one is (1.141.0, measured: given
     * only the subject's block, qwen2.5:7b wrote hasReinforcement for 'reinforced with'). The
     * benchmark builds its text-to-axiom context from the same list.
     */
    public static List<IRI> sentenceTerms(OWLOntology ontology, IRI subject, String sentence) {
        List<IRI> terms = new ArrayList<IRI>(
                new de.fizkarlsruhe.ise.ontoboard.assistant.chat.NameResolver(ontology)
                        .namedIn(sentence));
        terms.remove(subject);
        return terms;
    }

    public static List<String> parentsOf(OWLOntology ontology, IRI iri) {
        List<String> parents = new ArrayList<String>();
        OWLClass cls = ontology.getOWLOntologyManager().getOWLDataFactory().getOWLClass(iri);
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (OWLSubClassOfAxiom axiom : part.getSubClassAxiomsForSubClass(cls)) {
                OWLClassExpression parent = axiom.getSuperClass();
                if (!parent.isAnonymous() && !parent.isOWLThing()) {
                    String label = DisplayLabels.forEntity(ontology, parent.asOWLClass());
                    if (!parents.contains(label)) {
                        parents.add(label);
                    }
                }
            }
        }
        Collections.sort(parents);
        return parents;
    }
}
