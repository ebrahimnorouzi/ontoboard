package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.NeedBlock;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalBlock;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheck;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalValidator;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * The automatic scores (spec D8). Deliberately simple and stated in the summary, because a number
 * nobody can recompute by eye is a number nobody should trust.
 *
 * <ul>
 *   <li><b>explain-problem</b>: {@code termRecall}, the share of the justification's terms the
 *       answer names; {@code namesClash}, 1 when it names both sides of the clash. Passes with the
 *       clash named and at least half the terms.
 *   <li><b>context-request</b>: {@code asked}, 1 when the answer has an {@code ontoboard-need}
 *       block; {@code askedRight}, 1 when the block names every held-back term. Passes when it
 *       asked for the right thing - or, for a control with nothing held back, did not ask.
 *   <li>Both: {@code inventedIris}, IRIs in the answer that are neither in the ontology nor an
 *       injected fault's.
 * </ul>
 *
 * <p>Names are compared with case, spaces and punctuation ignored, so "vegetarian topping",
 * "Vegetarian Topping" and "VegetarianTopping" are one name - a model may use the label or the
 * short name, and both are right.
 */
public final class Scorers {

    /**
     * Every score column, in the order the results file writes them. {@code proposals} came
     * last, so every score column before it keeps its place (1.146.0, review 1.145.0 bench-B).
     */
    public static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "termRecall", "namesClash", "asked", "askedRight", "validProposal", "namesGenus",
            "overlap", "solved", "rounds", "removedFault", "collateral", "validProposals",
            "entails", "entailed", "equivalent", "safe", "inventedIris", "proposals"));

    /** What a card would change, word for word: two blocks alike are one card. */
    private static List<String> card(Proposal proposal) {
        return Arrays.asList(String.valueOf(proposal.getAction()), spaced(proposal.getSubject()),
                String.valueOf(proposal.getProperty()), proposal.getLanguage(),
                spaced(proposal.getValueText()), spaced(proposal.getAxiom()),
                spaced(proposal.getWith()));
    }

    /**
     * A text as it reads: a block repeated with one more space was a second card, so a right
     * answer failed (1.147.0, review 1.146.0 bench-01); so was one with a no-break or a
     * zero-width space (1.148.0, review 1.147.0 bench-02). Every format character goes, as a
     * text shows none of them: a soft hyphen, a zero-width non-joiner or a left-to-right mark
     * still made a second card (1.149.0, the 1.148.0 bench reviewer's declined note).
     */
    private static String spaced(String text) {
        return text == null ? null : text.replaceAll("\\p{Cf}", "")
                .replaceAll("[\\s\\p{Z}]+", " ").trim();
    }

    /** Words that carry no content in a definition, left out of the overlap. */
    private static final Set<String> STOP = new java.util.HashSet<String>(Arrays.asList("a",
            "an", "the", "of", "with", "that", "which", "who", "is", "are", "be", "and", "or",
            "to", "in", "on", "for", "by", "as", "its", "it", "has", "have", "from", "at",
            "any", "some", "this", "these", "those"));

    /**
     * The definition task: {@code validProposal}, 1 when a proposal would become a card that sets
     * the right term's definition; {@code proposals}, how many different cards the answer
     * offers, of any kind; {@code namesGenus}, 1 when the first definition's value names an
     * asserted parent; {@code overlap}, the F1 of its content words against the held-back
     * definition's. Passes on one card, a valid definition of the term that names its genus:
     * one, because the request asks for one block (1.146.0, review 1.145.0 bench-B).
     *
     * @param without the ontology as the model saw it, the definition removed
     */
    public static Score definition(BenchCase c, String reply, Set<String> ontologyIris,
            org.semanticweb.owlapi.model.OWLOntology without) {
        String answer = reply == null ? "" : reply;
        Proposal best = null;
        // Every card the answer offers counts, not only the first definition (1.146.0, review
        // 1.145.0 bench-B): a right one followed by an alternative passed, and the same two the
        // other way round failed. A definition of another term, or a comment, is a card the
        // person must judge too, where the request asks for one block. Each is told apart by
        // what it would write, so a block repeated word for word is one; the first definition
        // of the term still fills the columns below.
        Set<Object> offered = new LinkedHashSet<Object>();
        for (Proposal proposal : new ProposalValidator(without).validateAll(
                ProposalBlock.parse(answer))) {
            if (proposal.getStatus() != Proposal.Status.OFFERED) {
                continue;
            }
            if (proposal.getProperty() == Proposal.Property.DEFINITION
                    && c.getSubjectIri().equals(String.valueOf(proposal.getSubjectIri()))) {
                if (best == null) {
                    best = proposal;
                }
                // With its action: a set and an add of the same text are two cards to judge
                // (1.148.0, review 1.147.0 bench-02).
                offered.add(Arrays.asList(String.valueOf(proposal.getAction()),
                        proposal.getLanguage(), spaced(proposal.getValueText())));
            } else {
                offered.add(card(proposal));
            }
        }
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        boolean genus = false;
        double overlap = 0.0;
        if (best != null) {
            String said = words(best.getValueText());
            for (String parent : c.getGenus()) {
                genus |= mentions(said, parent);
            }
            overlap = f1(content(best.getValueText()), content(c.getReference()));
        }
        values.put("validProposal", best == null ? 0.0 : 1.0);
        values.put("proposals", (double) offered.size());
        values.put("namesGenus", genus ? 1.0 : 0.0);
        values.put("overlap", overlap);
        values.put("inventedIris", (double) invented(answer, ontologyIris));
        return new Score(values, best != null && offered.size() == 1 && genus);
    }

    /**
     * The text-to-axiom task, judged by the reasoner on the ontology as the model saw it, not by
     * spelling: {@code validProposal}, 1 when the answer has an add-axiom proposal OntoBoard would
     * offer; {@code proposals}, how many different cards it offers, of any kind; and for the
     * first add-axiom, {@code entails}, 1 when the ontology with it entails the held-back axiom;
     * {@code entailed}, 1 when the ontology with the held-back axiom entails it;
     * {@code equivalent}, both; {@code safe}, 1 when it leaves the ontology consistent and no
     * class newly empty. Passes on one card, a valid, safe add-axiom equivalent to the held-back
     * axiom given the rest of the ontology (1.141.0): one, because the request asks for one block
     * (1.146.0, review 1.145.0 bench-B). A safety check that could not finish, for one
     * that ran out of time, is the score's error rather than a silent "unsafe" (1.143.0, review
     * 1.141.0 M3).
     *
     * @param without the ontology as the model saw it, the axiom held back
     */
    public static Score textToAxiom(BenchCase c, String reply, Set<String> ontologyIris,
            OWLOntology without, OWLReasonerFactory factory) {
        String answer = reply == null ? "" : reply;
        Proposal best = null;
        // Every card the answer offers counts, not only the first add-axiom (1.146.0, review
        // 1.145.0 bench-B): the right axiom followed by a wrong alternative passed, and the same
        // two the other way round failed, while the person would get both cards - and so did a
        // right axiom beside a card that removes another one. Add-axioms are told apart as OWL,
        // so a block repeated word for word is one; the first still fills the columns.
        Set<Object> offered = new LinkedHashSet<Object>();
        for (Proposal proposal : new ProposalValidator(without).validateAll(
                ProposalBlock.parse(answer))) {
            if (proposal.getStatus() != Proposal.Status.OFFERED) {
                continue;
            }
            if (proposal.getAction() == Proposal.Action.ADD_AXIOM
                    && proposal.getNewAxiom() != null) {
                if (best == null) {
                    best = proposal;
                }
                offered.add(proposal.getNewAxiom().getAxiomWithoutAnnotations());
            } else {
                offered.add(card(proposal));
            }
        }
        boolean entails = false;
        boolean entailed = false;
        boolean safe = false;
        String checkError = null;
        if (best != null) {
            OWLAxiom gold = BenchCases.goldAxiom(c);
            OWLAxiom given = best.getNewAxiom().getAxiomWithoutAnnotations();
            Set<OWLAxiom> withGiven = ProposalCheck.snapshot(without);
            withGiven.add(given);
            entails = CaseGenerator.entails(withGiven, gold, factory);
            Set<OWLAxiom> withGold = ProposalCheck.snapshot(without);
            withGold.add(gold);
            entailed = CaseGenerator.entails(withGold, given, factory);
            ProposalCheck.Check check = ProposalCheck.check(without, factory, best);
            safe = check.isSafe();
            checkError = check.getError() == null ? null
                    : "the safety check could not finish: " + check.getError();
        }
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        values.put("validProposal", best == null ? 0.0 : 1.0);
        values.put("proposals", (double) offered.size());
        values.put("entails", entails ? 1.0 : 0.0);
        values.put("entailed", entailed ? 1.0 : 0.0);
        values.put("equivalent", entails && entailed ? 1.0 : 0.0);
        values.put("safe", safe ? 1.0 : 0.0);
        // Asking for a term is what the request says to do when one is missing (review 1.140.0
        // I3): reported, though it is not a pass.
        values.put("asked", NeedBlock.parse(answer).isEmpty() ? 0.0 : 1.0);
        values.put("inventedIris", (double) invented(answer, ontologyIris));
        // A sentence says one thing: an answer that says more is not it, nor is an "only" that
        // the rest of the ontology makes entail "some" (review 1.140.0 I1), nor two axioms where
        // the request asks for one block (1.146.0, review 1.145.0 bench-B).
        return new Score(values, best != null && offered.size() == 1 && entails && entailed && safe,
                checkError);
    }

    /**
     * The repair task: {@code solved}; {@code rounds} used; {@code removedFault}, 1 when what was
     * removed is an injected axiom (or, for a problem the ontology already had, an axiom of its
     * justification); {@code collateral}, the other axioms removed (for a problem the ontology
     * already had, the removals that were not needed, or, unsolved, those outside its original
     * justification); {@code validProposals}, see {@link RepairLoop.Outcome#validProposals}.
     * Passes when solved without collateral (1.150.0, review 1.149.0 bench-04: this said "the
     * share of proposals that parsed and matched an axiom").
     */
    static Score repair(RepairLoop.Outcome outcome, Set<String> ontologyIris) {
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        values.put("solved", outcome.solved ? 1.0 : 0.0);
        values.put("rounds", (double) outcome.rounds);
        values.put("removedFault", outcome.removedFault ? 1.0 : 0.0);
        values.put("collateral", (double) outcome.collateral);
        values.put("validProposals", outcome.validProposals());
        values.put("inventedIris", (double) invented(String.join("\n", outcome.replies),
                ontologyIris));
        return new Score(values, outcome.error == null && outcome.solved
                && outcome.collateral == 0);
    }

    private static Set<String> content(String text) {
        Set<String> words = new LinkedHashSet<String>(Arrays.asList(words(text).split(" ")));
        words.removeAll(STOP);
        words.remove("");
        return words;
    }

    private static double f1(Set<String> given, Set<String> wanted) {
        if (given.isEmpty() || wanted.isEmpty()) {
            return 0.0;
        }
        Set<String> both = new LinkedHashSet<String>(given);
        both.retainAll(wanted);
        if (both.isEmpty()) {
            return 0.0;
        }
        double precision = (double) both.size() / given.size();
        double recall = (double) both.size() / wanted.size();
        return 2 * precision * recall / (precision + recall);
    }

    private static final Pattern IRI = Pattern.compile("https?://[^\\s<>\"'`)\\]]+");

    private Scorers() {
    }

    public static Score score(BenchCase c, String reply, Set<String> ontologyIris) {
        String answer = reply == null ? "" : reply;
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        boolean pass;
        if (BenchCase.EXPLAIN.equals(c.getTask())) {
            String said = words(answer);
            int named = 0;
            for (String term : c.getTerms()) {
                if (mentions(said, term)) {
                    named++;
                }
            }
            double recall = c.getTerms().isEmpty() ? 1.0 : (double) named / c.getTerms().size();
            boolean clash = !c.getClash().isEmpty();
            for (String side : c.getClash()) {
                clash &= mentions(said, side);
            }
            values.put("termRecall", recall);
            values.put("namesClash", clash ? 1.0 : 0.0);
            pass = clash && recall >= 0.5;
        } else {
            List<String> names = NeedBlock.parse(answer);
            boolean asked = !names.isEmpty();
            boolean right = !c.getNeed().isEmpty();
            for (int i = 0; i < c.getNeed().size(); i++) {
                String iri = i < c.getNeedIris().size() ? c.getNeedIris().get(i) : "";
                right &= askedFor(names, c.getNeed().get(i), iri);
            }
            values.put("asked", asked ? 1.0 : 0.0);
            values.put("askedRight", right ? 1.0 : 0.0);
            pass = c.isControl() ? !asked : right;
        }
        values.put("inventedIris", (double) invented(answer, ontologyIris));
        return new Score(values, pass);
    }

    private static boolean askedFor(List<String> names, String label, String iri) {
        String wantLabel = normal(label);
        String wantLocal = normal(localName(iri));
        for (String name : names) {
            String given = normal(name);
            if (!given.isEmpty() && (given.equals(wantLabel) || given.equals(wantLocal)
                    || name.trim().equals(iri))) {
                return true;
            }
        }
        return false;
    }

    private static int invented(String answer, Set<String> ontologyIris) {
        Set<String> found = new LinkedHashSet<String>();
        Matcher matcher = IRI.matcher(answer);
        while (matcher.find()) {
            String iri = matcher.group().replaceAll("[.,;:!?]+$", "");
            if (!ontologyIris.contains(iri) && !iri.startsWith(CaseGenerator.NS)) {
                found.add(iri);
            }
        }
        return found.size();
    }

    /** Whole words only (review #5a): "is it explained" must not name "site". */
    private static boolean mentions(String said, String term) {
        String wanted = words(term);
        return !wanted.isEmpty() && (" " + said + " ").contains(" " + wanted + " ");
    }

    /**
     * Lower case, with every run of non-letters and non-digits a single space and CamelCase
     * split, so "VegetarianTopping", "vegetarian-topping" and "Vegetarian topping" are the same
     * words - and words stay words.
     */
    static String words(String text) {
        if (text == null) {
            return "";
        }
        // Format characters go first, as spaced() drops them: here they split the word they
        // stood in, so a definition with a soft hyphen in its genus failed (1.150.0, review
        // 1.149.0 bench-02).
        String split = text.replaceAll("\\p{Cf}", "").replaceAll("(\\p{Ll})(\\p{Lu})", "$1 $2")
                .replaceAll("(\\p{Lu})(\\p{Lu}\\p{Ll})", "$1 $2");
        return split.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** A name compared as one token, for need-block names. */
    static String normal(String text) {
        return words(text).replace(" ", "");
    }

    private static String localName(String iri) {
        if (iri == null) {
            return "";
        }
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut < 0 ? iri : iri.substring(cut + 1);
    }
}
