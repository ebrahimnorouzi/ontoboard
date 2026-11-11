package de.fizkarlsruhe.ise.ontoboard.assistant.context;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.robot.Explanations;
import java.util.List;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * The reasoner problem a person arrives with, as a context item the model can explain: an
 * inconsistent ontology, or an unsatisfiable class, each with the justifications
 * {@link Explanations} computes. The model explains it, and may propose removing an axiom as a
 * card the reasoner checks first.
 *
 * <p>Both, because an inconsistent ontology classifies nothing: no class is marked
 * unsatisfiable, so an entry point that waited for one would never appear - and ontologies with
 * individuals, which is most knowledge graphs, are exactly where inconsistency happens.
 */
public final class Problems {

    static final String NO_JUSTIFICATION = "The reasoner found the problem but no justification"
            + " within the limit; this does not mean there is no reason.";

    private Problems() {
    }

    /** Inconsistency first, then every unsatisfiable class, as *Explain...* does. */
    public static Explanations.Result diagnose(final OWLOntology ontology,
            OWLReasonerFactory factory) {
        return Explanations.run(ontology, factory, Explanations.DEFAULT_PER_CLASS,
                object -> AssistantRendering.render(ontology, object));
    }

    /** The inconsistency chip's title. */
    public static final String INCONSISTENT = "Why the ontology is inconsistent";

    /**
     * The problem as the reasoner finds it now, asked first and explained only when it holds:
     * the inconsistency, when the ontology is inconsistent - every class is unsatisfiable then,
     * so a class's question gives way to it; else the class's problem, when {@code subject} names
     * one that is still unsatisfiable; else null. The assistant told the model the ontology was
     * inconsistent, at every question after the one that explained it, without asking the
     * reasoner whether it still was; and "Why is this unsatisfiable?" trusted Protégé's last
     * classification (1.151.0, asked by the person). The benchmark's repair loop asks the same
     * way between its rounds.
     *
     * @param subject the class a problem is about, or null for the inconsistency
     */
    public static ContextItem now(OWLOntology ontology, OWLReasonerFactory factory,
            org.semanticweb.owlapi.model.IRI subject, String reasonerName) {
        OWLClass cls = subject == null ? null
                : ontology.getOWLOntologyManager().getOWLDataFactory().getOWLClass(subject);
        boolean consistent;
        boolean satisfiable = true;
        org.semanticweb.owlapi.reasoner.OWLReasoner reasoner = factory.createReasoner(ontology);
        try {
            consistent = reasoner.isConsistent();
            if (consistent && cls != null) {
                satisfiable = reasoner.isSatisfiable(cls);
            }
        } finally {
            reasoner.dispose();
        }
        if (!consistent) {
            return inconsistency(ontology, diagnose(ontology, factory), reasonerName);
        }
        return satisfiable ? null : unsatisfiable(ontology, factory, cls, reasonerName);
    }

    public static ContextItem inconsistency(OWLOntology ontology, Explanations.Result result,
            String reasonerName) {
        return ContextItem.problem(INCONSISTENT, describe(reasonerName,
                "The ontology is inconsistent: as it stands, nothing can satisfy all of it.",
                result.getJustifications()));
    }

    /**
     * Why one class can have no instances. The reasoner must be the one that found it so:
     * {@link Explanations#forEntailment} warns that another can return nothing.
     */
    public static ContextItem unsatisfiable(OWLOntology ontology, OWLReasonerFactory factory,
            OWLClass cls, String reasonerName) {
        OWLDataFactory data = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLAxiom entailment = data.getOWLSubClassOfAxiom(cls, data.getOWLNothing());
        List<Explanations.Justification> found = Explanations.forEntailment(ontology, factory,
                entailment, Explanations.DEFAULT_PER_CLASS,
                object -> AssistantRendering.render(ontology, object));
        String label = DisplayLabels.forEntity(ontology, cls);
        return ContextItem.problem("Why " + label + " is unsatisfiable", describe(reasonerName,
                label + " is unsatisfiable: it can have no instances.", found), cls.getIRI());
    }

    static String describe(String reasonerName, String what,
            List<Explanations.Justification> justifications) {
        StringBuilder out = new StringBuilder("Reasoner: ").append(reasonerName).append(".\n")
                .append(what).append('\n');
        if (justifications.isEmpty()) {
            return out.append(NO_JUSTIFICATION).append('\n').toString();
        }
        int number = 0;
        for (Explanations.Justification justification : justifications) {
            out.append("Justification ").append(++number).append(" (the axioms that together"
                    + " force it):\n");
            for (String axiom : justification.getAxioms()) {
                out.append("  ").append(axiom).append('\n');
            }
            for (String note : datatypeNotes(justification.getAxioms())) {
                out.append(note).append('\n');
            }
        }
        return out.toString();
    }

    /** OWL 2's datatypes by value space: two in different groups share no value. */
    private static final String[][] VALUE_SPACES = {
        {"decimal", "integer", "nonNegativeInteger", "positiveInteger", "nonPositiveInteger",
            "negativeInteger", "long", "int", "short", "byte", "unsignedLong", "unsignedInt",
            "unsignedShort", "unsignedByte"},
        {"float"}, {"double"},
        {"string", "normalizedString", "token", "language", "Name", "NCName", "NMTOKEN"},
        {"boolean"}, {"dateTime", "dateTimeStamp"}, {"anyURI"}, {"hexBinary"}, {"base64Binary"}};

    private static final java.util.regex.Pattern RANGE =
            java.util.regex.Pattern.compile("Range:?\\s+xsd:(\\w+)\\s*$");
    private static final java.util.regex.Pattern VALUE =
            java.util.regex.Pattern.compile("\"[^\"]*\"\\^\\^xsd:(\\w+)");

    /**
     * The fact behind a datatype clash, stated (1.139.0): a justification that puts a value of
     * one datatype against a range whose value space is disjoint from it gets one line saying
     * so. The reasoner applied it; a model reading the justification may not know it - measured
     * on 1.137.0, where a local model said a float "should be able to accommodate" decimals.
     */
    static List<String> datatypeNotes(List<String> axioms) {
        java.util.Set<String> ranges = new java.util.LinkedHashSet<String>();
        java.util.Map<String, String> values = new java.util.LinkedHashMap<String, String>();
        for (String axiom : axioms) {
            java.util.regex.Matcher range = RANGE.matcher(axiom.trim());
            if (range.find()) {
                ranges.add(range.group(1));
            }
            java.util.regex.Matcher value = VALUE.matcher(axiom);
            while (value.find()) {
                values.put(value.group(1), value.group());
            }
        }
        List<String> notes = new java.util.ArrayList<String>();
        for (String range : ranges) {
            for (java.util.Map.Entry<String, String> value : values.entrySet()) {
                int a = space(range);
                int b = space(value.getKey());
                if (a >= 0 && b >= 0 && a != b) {
                    notes.add("Note (OWL 2): xsd:" + value.getKey() + " and xsd:" + range
                            + " have disjoint value spaces: " + value.getValue() + " is not an"
                            + " xsd:" + range + ". The reasoner applies this.");
                }
            }
        }
        return notes;
    }

    private static int space(String datatype) {
        for (int i = 0; i < VALUE_SPACES.length; i++) {
            if (java.util.Arrays.asList(VALUE_SPACES[i]).contains(datatype)) {
                return i;
            }
        }
        return -1;
    }
}
