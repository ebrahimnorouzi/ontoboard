package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.RenderedItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One benchmark case: what the model is told and asked, and what a good answer contains.
 *
 * <p>The context is stored as the plugin rendered it when the case was generated - so a run
 * needs no reasoner, takes the same input every time, and still sends exactly what the plugin
 * would have sent. The ontology is named with the SHA-256 of its file (line endings normalised),
 * so a case cannot silently be scored against a different version of it.
 */
public final class BenchCase {

    public static final String EXPLAIN = "explain-problem";
    public static final String CONTEXT = "context-request";
    /** "Suggest a definition", with the class's definition held back (round 2). */
    public static final String DEFINITION = "definition";
    /** A reasoner problem to repair by removal proposals, checked in a loop (round 2). */
    public static final String REPAIR = "repair";
    /** A sentence made from a held-back axiom, written as OWL and judged by the reasoner (1.140.0). */
    public static final String TEXT_TO_AXIOM = "text-to-axiom";

    /** The gold the round-2 tasks add; empty for the others. */
    public static final class Extra {
        public static final Extra NONE = new Extra("", "", "", Collections.<String>emptyList(),
                "", "");

        final String faultOntology;
        final String subject;
        final String subjectIri;
        final List<String> genus;
        final String reference;
        final String targetIri;
        final String goldAxiom;

        /**
         * @param faultOntology the injected axioms in OWL functional syntax, so a runner can add
         *     them again
         * @param subject the defined class's label
         * @param genus its asserted parents' labels
         * @param reference the held-back definition
         * @param targetIri for an unsatisfiable repair case, the class that must become
         *     satisfiable
         */
        public Extra(String faultOntology, String subject, String subjectIri, List<String> genus,
                String reference, String targetIri) {
            this(faultOntology, subject, subjectIri, genus, reference, targetIri, "");
        }

        /** @param goldAxiom for text-to-axiom: the held-back axiom in OWL functional syntax */
        public Extra(String faultOntology, String subject, String subjectIri, List<String> genus,
                String reference, String targetIri, String goldAxiom) {
            this.faultOntology = faultOntology == null ? "" : faultOntology;
            this.subject = subject == null ? "" : subject;
            this.subjectIri = subjectIri == null ? "" : subjectIri;
            this.genus = copy(genus);
            this.reference = reference == null ? "" : reference;
            this.targetIri = targetIri == null ? "" : targetIri;
            this.goldAxiom = goldAxiom == null ? "" : goldAxiom;
        }
    }

    private Extra extra = Extra.NONE;

    private final String id;
    private final String task;
    private final String ontology;
    private final String ontologySha256;
    private final String problem;
    private final List<String> fault;
    private final boolean control;
    private final String header;
    private final List<RenderedItem> context;
    private final String message;
    private final List<String> terms;
    private final List<String> clash;
    private final List<String> need;
    private final List<String> needIris;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public BenchCase(String id, String task, String ontology, String ontologySha256,
            String problem, List<String> fault, boolean control, String header,
            List<RenderedItem> context, String message, List<String> terms, List<String> clash,
            List<String> need, List<String> needIris) {
        this.id = id;
        this.task = task;
        this.ontology = ontology;
        this.ontologySha256 = ontologySha256;
        this.problem = problem;
        this.fault = copy(fault);
        this.control = control;
        this.header = header == null ? "" : header;
        this.context = Collections.unmodifiableList(new ArrayList<RenderedItem>(context));
        this.message = message;
        this.terms = copy(terms);
        this.clash = copy(clash);
        this.need = copy(need);
        this.needIris = copy(needIris);
    }

    /** The same case with the round-2 gold. */
    BenchCase with(Extra more) {
        BenchCase copy = new BenchCase(id, task, ontology, ontologySha256, problem, fault, control,
                header, context, message, terms, clash, need, needIris);
        copy.extra = more == null ? Extra.NONE : more;
        return copy;
    }

    Extra getExtra() {
        return extra;
    }

    /** The injected axioms in OWL functional syntax; empty when nothing was injected. */
    public String getFaultOntology() {
        return extra.faultOntology;
    }

    public String getSubject() {
        return extra.subject;
    }

    public String getSubjectIri() {
        return extra.subjectIri;
    }

    public List<String> getGenus() {
        return extra.genus;
    }

    public String getReference() {
        return extra.reference;
    }

    public String getTargetIri() {
        return extra.targetIri;
    }

    /** For text-to-axiom: the held-back axiom in OWL functional syntax; empty otherwise. */
    public String getGoldAxiom() {
        return extra.goldAxiom;
    }

    private static List<String> copy(List<String> values) {
        return Collections.unmodifiableList(values == null ? new ArrayList<String>()
                : new ArrayList<String>(values));
    }

    public String getId() {
        return id;
    }

    /** {@link #EXPLAIN} or {@link #CONTEXT}. */
    public String getTask() {
        return task;
    }

    /** The ontology file, relative to the cases file. */
    public String getOntology() {
        return ontology;
    }

    public String getOntologySha256() {
        return ontologySha256;
    }

    /** "unsatisfiable", "inconsistent", or null for a context-request case. */
    public String getProblem() {
        return problem;
    }

    /** The injected axioms, as the OWL API writes them, for a reader to check. */
    public List<String> getFault() {
        return fault;
    }

    /** A context-request case with nothing held back: asking is the wrong answer. */
    public boolean isControl() {
        return control;
    }

    public String getHeader() {
        return header;
    }

    public List<RenderedItem> getContext() {
        return context;
    }

    public String getMessage() {
        return message;
    }

    /** Labels of every named term in the justification. */
    public List<String> getTerms() {
        return terms;
    }

    /** The two terms whose clash causes the problem. */
    public List<String> getClash() {
        return clash;
    }

    /** What the model should ask for: labels, and the same terms' IRIs. */
    public List<String> getNeed() {
        return need;
    }

    public List<String> getNeedIris() {
        return needIris;
    }
}
