package de.fizkarlsruhe.ise.ontoboard.robot;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.obolibrary.robot.ExplainOperation;
import org.semanticweb.owl.explanation.api.Explanation;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * ROBOT's {@code explain} - which axioms make a class unsatisfiable, or the ontology inconsistent.
 *
 * <p>Protege has an explanation workbench, and it answers a different question. It explains one
 * entailment that you have already found and selected. This answers the question a curator actually
 * arrives with: <em>the reasoner has gone red, which axioms are responsible, and which single axiom
 * is behind the most of it.</em> With twelve unsatisfiable classes that is twelve separate
 * investigations in the workbench and one table here.
 *
 * <p><b>What is deliberately not used.</b> {@code ExplainOperation.renderExplanationAsMarkdown}
 * would format an explanation for us, but its bytecode constructs
 * {@code uk.ac.manchester.cs.owl.explanation.ProtegeExplanationOrderer} - a class from Protege's
 * explanation-workbench plugin. This bundle happens to embed a copy, and Protege also
 * <em>exports</em> that package, which is precisely the kind of two-copies-one-package situation
 * that resolves differently depending on what else the user has installed. The explanation itself is
 * a set of axioms; rendering it is a loop. So the rendering is ours and the reasoning is ROBOT's.
 *
 * <p>{@code renderAxiomImpactSummary} <em>is</em> ROBOT's, because its only dependency is the OWL
 * API's own Manchester renderer - checked with {@code javap}, not assumed.
 *
 * <p>Pure OWL API and robot-core; no Protege types and no Swing.
 */
public final class Explanations {

    /**
     * How many explanations to compute per unsatisfiable class.
     *
     * <p>One is rarely enough - an axiom can be the culprit in one justification and irrelevant in
     * another - and the count is what makes the impact summary meaningful. Beyond a handful the
     * cost grows sharply and the extra justifications repeat themselves.
     */
    public static final int DEFAULT_PER_CLASS = 3;

    private Explanations() {
    }

    /** One explanation: what it explains, and the axioms that force it. */
    public static final class Justification {
        private final String entailment;
        private final List<String> axioms;

        Justification(String entailment, List<String> axioms) {
            this.entailment = entailment;
            this.axioms = Collections.unmodifiableList(axioms);
        }

        public String getEntailment() {
            return entailment;
        }

        public List<String> getAxioms() {
            return axioms;
        }
    }

    /** Everything a run found, and ROBOT's own summary of which axiom does the most damage. */
    public static final class Result {
        private final List<Justification> justifications;
        private final String impactSummary;
        private final List<String> unsatisfiable;
        private final boolean consistent;

        Result(List<Justification> justifications, String impactSummary,
                List<String> unsatisfiable, boolean consistent) {
            this.justifications = Collections.unmodifiableList(justifications);
            this.impactSummary = impactSummary;
            this.unsatisfiable = Collections.unmodifiableList(unsatisfiable);
            this.consistent = consistent;
        }

        public List<Justification> getJustifications() {
            return justifications;
        }

        /** ROBOT's table of axioms ordered by how many justifications they appear in. */
        public String getImpactSummary() {
            return impactSummary;
        }

        public List<String> getUnsatisfiable() {
            return unsatisfiable;
        }

        public boolean isConsistent() {
            return consistent;
        }

        /** Nothing to explain is the good outcome, and must not read like a failure. */
        public boolean isClean() {
            return consistent && unsatisfiable.isEmpty();
        }
    }

    /**
     * Explains whatever is wrong: inconsistency first, then every unsatisfiable class.
     *
     * <p>Inconsistency first because it subsumes the rest - in an inconsistent ontology every class
     * is unsatisfiable, so listing them all would bury the one thing worth reading.
     *
     * @param perClass how many justifications to compute for each entailment
     * @throws RobotException if the reasoner or the explanation generator cannot run here
     */
    public static Result run(OWLOntology ontology, OWLReasonerFactory factory, int perClass) {
        if (ontology == null || factory == null) {
            throw new IllegalArgumentException("an ontology and a reasoner are both required");
        }
        int wanted = perClass < 1 ? 1 : perClass;

        OWLReasoner reasoner = null;
        try {
            reasoner = factory.createReasoner(ontology);
            boolean consistent = reasoner.isConsistent();

            if (!consistent) {
                Set<Explanation<OWLAxiom>> found =
                        ExplainOperation.explainInconsistent(ontology, factory, wanted);
                return new Result(describe(found, ontology), impact(found, ontology),
                        new ArrayList<String>(), false);
            }

            List<String> unsatisfiable = new ArrayList<String>();
            for (OWLClass bad : reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom()) {
                // forEntity, not shortNameOf: the justifications beside this list are rendered
                // with labels, so naming the same class ImpossiblePizza here and "impossible
                // pizza" three lines down made one class look like two. It is also the name
                // robot explain writes.
                unsatisfiable.add(DisplayLabels.forEntity(ontology, bad));
            }
            Collections.sort(unsatisfiable);
            if (unsatisfiable.isEmpty()) {
                return new Result(new ArrayList<Justification>(), "", unsatisfiable, true);
            }

            Set<Explanation<OWLAxiom>> found = ExplainOperation.explainUnsatisfiableClasses(
                    ontology, reasoner, factory, wanted);
            return new Result(describe(found, ontology), impact(found, ontology), unsatisfiable,
                    true);
        } catch (RuntimeException | LinkageError cannotExplain) {
            throw new RobotException("The explanation could not be computed: "
                    + describe(cannotExplain), cannotExplain);
        } finally {
            if (reasoner != null) {
                reasoner.dispose();
            }
        }
    }

    /** {@link #run(OWLOntology, OWLReasonerFactory, int)} with the default depth. */
    public static Result run(OWLOntology ontology, OWLReasonerFactory factory) {
        return run(ontology, factory, DEFAULT_PER_CLASS);
    }

    // ===================================================================================== rendering

    /**
     * Each explanation as its entailment and the axioms that force it, in Manchester syntax.
     *
     * <p>Sorted, because a {@code Set} of explanations has no order and a table that reshuffles
     * between runs cannot be worked through one row at a time.
     */
    private static List<Justification> describe(Set<Explanation<OWLAxiom>> found,
            OWLOntology ontology) {
        ManchesterOWLSyntaxOWLObjectRendererImpl renderer = renderer(ontology);
        List<Justification> justifications = new ArrayList<Justification>();
        for (Explanation<OWLAxiom> explanation : found) {
            List<String> axioms = new ArrayList<String>();
            for (OWLAxiom axiom : explanation.getAxioms()) {
                axioms.add(tidy(renderer.render(axiom)));
            }
            Collections.sort(axioms);
            justifications.add(new Justification(
                    tidy(renderer.render(explanation.getEntailment())), axioms));
        }
        Collections.sort(justifications, new Comparator<Justification>() {
            @Override
            public int compare(Justification a, Justification b) {
                int byEntailment = a.getEntailment().compareToIgnoreCase(b.getEntailment());
                return byEntailment != 0
                        ? byEntailment
                        : Integer.compare(a.getAxioms().size(), b.getAxioms().size());
            }
        });
        return justifications;
    }

    /**
     * ROBOT's own summary of which axiom appears in the most justifications.
     *
     * <p>The counting is here because ROBOT's CLI does it in its command layer, which this plugin
     * does not use; the rendering is ROBOT's, and is safe in a bundle because its only dependency is
     * the OWL API's Manchester renderer.
     */
    private static String impact(Set<Explanation<OWLAxiom>> found, OWLOntology ontology) {
        Map<OWLAxiom, Integer> counts = new LinkedHashMap<OWLAxiom, Integer>();
        for (Explanation<OWLAxiom> explanation : found) {
            for (OWLAxiom axiom : explanation.getAxioms()) {
                Integer seen = counts.get(axiom);
                counts.put(axiom, seen == null ? 1 : seen + 1);
            }
        }
        if (counts.isEmpty()) {
            return "";
        }
        try {
            return ExplainOperation.renderAxiomImpactSummary(counts, ontology,
                    ontology.getOWLOntologyManager());
        } catch (RuntimeException | LinkageError cannotRender) {
            // The justifications are still useful without the summary, so this is not fatal.
            return "";
        }
    }

    /** A renderer that writes labels where an ontology has them, and short names otherwise. */
    private static ManchesterOWLSyntaxOWLObjectRendererImpl renderer(OWLOntology ontology) {
        ManchesterOWLSyntaxOWLObjectRendererImpl renderer =
                new ManchesterOWLSyntaxOWLObjectRendererImpl();
        renderer.setShortFormProvider(new org.semanticweb.owlapi.util.ShortFormProvider() {
            @Override
            public String getShortForm(org.semanticweb.owlapi.model.OWLEntity entity) {
                // forEntity already falls back to the short name when there is no usable label,
                // which is what makes an explanation readable in an ontology that has neither.
                return DisplayLabels.forEntity(ontology, entity);
            }

            @Override
            public void dispose() {
            }
        });
        return renderer;
    }

    /** Manchester output wraps at odd places; one line per axiom reads better in a table. */
    private static String tidy(String rendered) {
        if (rendered == null) {
            return "";
        }
        return rendered.replace('\n', ' ').replace('\r', ' ').replaceAll(" +", " ").trim();
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getName()
                : message;
    }

    /** Justifications grouped by the entailment they explain, for a caller that wants a tree. */
    public static Map<String, List<Justification>> byEntailment(Result result) {
        Map<String, List<Justification>> grouped = new TreeMap<String, List<Justification>>();
        for (Justification justification : result.getJustifications()) {
            List<Justification> forOne = grouped.get(justification.getEntailment());
            if (forOne == null) {
                forOne = new ArrayList<Justification>();
                grouped.put(justification.getEntailment(), forOne);
            }
            forOne.add(justification);
        }
        return grouped;
    }
}
