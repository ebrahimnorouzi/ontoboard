package de.fizkarlsruhe.ise.ontoboard.robot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

/**
 * The reasoners ROBOT ships with, named the way ROBOT names them.
 *
 * <p>Every ROBOT operation that reasons takes {@code --reasoner ELK} or similar, and an OBO
 * project's Makefile picks one deliberately: ELK because it is the only one that finishes on a
 * hundred-thousand-class ontology, HermiT because ELK silently ignores the axioms it cannot
 * express. Offering "the reasoner" as a single fixed choice would make the plugin disagree with
 * the project's own build, and offering the list without saying what distinguishes them would make
 * the choice a guess.
 *
 * <p>The names here are ROBOT's, so a user who has read the ODK documentation recognises them and
 * a result that records "Reasoner: ELK" can be compared with what {@code make} produced.
 *
 * <p>Each factory is constructed on demand rather than held in a field, so choosing ELK does not
 * drag HermiT, JFact and Whelk into the classloader as well.
 */
public final class Reasoners {

    /** A reasoner, as ROBOT spells it, with the sentence that makes the choice a decision. */
    public enum Choice {
        /**
         * ELK: OWL EL only, and the reason OBO releases finish at all.
         */
        ELK("ELK",
                "Fast and used by nearly every OBO release build, including the ODK's. It "
                        + "supports the OWL EL profile only and quietly ignores axioms outside it "
                        + "- on an ontology using cardinality or negation it will not report an "
                        + "error, it will simply not reason over those axioms. This is the right "
                        + "choice for a large OBO-style ontology and the wrong one for an "
                        + "expressive small one."),

        /**
         * HermiT: complete for OWL 2 DL, and slow in proportion.
         */
        HERMIT("HermiT",
                "Complete for the whole of OWL 2 DL, so it sees everything ELK skips - "
                        + "cardinality restrictions, negation, disjointness. The cost is time: on "
                        + "an ontology of tens of thousands of classes it can take minutes or "
                        + "never finish. Use it when correctness over expressive axioms matters "
                        + "more than speed."),

        /**
         * JFact: a second complete reasoner, kept because the two disagree in practice.
         */
        JFACT("JFact",
                "Another complete OWL 2 DL reasoner, a Java port of FaCT++. Worth reaching for "
                        + "when HermiT is unusably slow on a particular ontology or when you want "
                        + "a second opinion - two complete reasoners disagreeing means one of "
                        + "them has a bug, and knowing that is useful."),

        /**
         * Whelk: EL again, but tolerant of what it cannot handle.
         */
        WHELK("Whelk",
                "An OWL EL reasoner like ELK but more forgiving of ontologies that mix in axioms "
                        + "outside the profile, where ELK can refuse to start. Used by some GO "
                        + "pipelines. Comparable speed to ELK."),

        /**
         * Structural: not really reasoning, and honest about it.
         */
        STRUCTURAL("Structural",
                "Not a reasoner in the usual sense - it reports only what is asserted directly, "
                        + "following told subclass axioms and inferring nothing. It is instant and "
                        + "always terminates, which makes it useful for checking that an "
                        + "operation works before spending twenty minutes on a real reasoner. Do "
                        + "not use its results as if they were entailments.");

        private final String label;
        private final String help;

        Choice(String label, String help) {
            this.label = label;
            this.help = help;
        }

        /** As ROBOT spells it, so a result can be compared with what {@code make} reported. */
        public String getLabel() {
            return label;
        }

        /** What distinguishes this reasoner from the others, for the parameter dialog's "?". */
        public String getHelp() {
            return help;
        }

        /**
         * A new factory.
         *
         * <p>Constructed here rather than stored, so selecting one reasoner does not load the
         * classes of the other four.
         *
         * @throws LinkageError if the reasoner is not on the classpath, which the caller reports
         *     as an unavailable reasoner rather than as a crash
         */
        public OWLReasonerFactory newFactory() {
            switch (this) {
                case ELK:
                    return new org.semanticweb.elk.owlapi.ElkReasonerFactory();
                case HERMIT:
                    return new org.semanticweb.HermiT.ReasonerFactory();
                case JFACT:
                    return new uk.ac.manchester.cs.jfact.JFactFactory();
                case WHELK:
                    return new org.geneontology.whelk.owlapi.WhelkOWLReasonerFactory();
                case STRUCTURAL:
                default:
                    return new StructuralReasonerFactory();
            }
        }
    }

    /** The default everywhere, because it is the ODK's default and finishes on real ontologies. */
    public static final Choice DEFAULT = Choice.ELK;

    private Reasoners() {
    }

    /** Every label, in the order they are offered. */
    public static List<String> labels() {
        List<String> labels = new ArrayList<String>();
        for (Choice choice : Choice.values()) {
            labels.add(choice.getLabel());
        }
        return labels;
    }

    /**
     * The choice with this label, case-insensitively.
     *
     * <p>Case-insensitive because ROBOT accepts {@code elk} and {@code ELK} alike, and a user
     * copying a reasoner name out of a Makefile should not have to match its capitalisation.
     *
     * @throws IllegalArgumentException naming what was asked for and what is available, because
     *     "no such reasoner" without the list leaves a user guessing
     */
    public static Choice byLabel(String label) {
        String wanted = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
        for (Choice choice : Choice.values()) {
            if (choice.getLabel().toLowerCase(Locale.ROOT).equals(wanted)) {
                return choice;
            }
        }
        throw new IllegalArgumentException(
                "no reasoner called '" + label + "'; ROBOT offers " + labels());
    }

    /** One paragraph covering all of them, for the "?" on a reasoner parameter. */
    public static String help() {
        StringBuilder text = new StringBuilder(
                "Which reasoner computes the entailments. They differ in what they can express "
                        + "and how long they take.\n");
        for (Choice choice : Choice.values()) {
            text.append('\n').append(choice.getLabel()).append(": ").append(choice.getHelp())
                    .append('\n');
        }
        return text.toString();
    }
}
