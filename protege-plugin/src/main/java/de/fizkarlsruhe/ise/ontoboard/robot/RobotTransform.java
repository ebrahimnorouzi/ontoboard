package de.fizkarlsruhe.ise.ontoboard.robot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.obolibrary.robot.IOHelper;
import org.obolibrary.robot.MergeOperation;
import org.obolibrary.robot.ReduceOperation;
import org.obolibrary.robot.RelaxOperation;
import org.obolibrary.robot.RepairOperation;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

/**
 * ROBOT's ontology-modifying operations, made safe to run inside Protege.
 *
 * <p>Every one of these mutates the ontology <em>in place</em>: {@code RelaxOperation.relax} takes
 * an {@code OWLOntology} and returns void. Calling them on Protege's live ontology would rewrite
 * it behind the model manager's back - no change events, so the class hierarchy and every open view
 * would still be showing the old axioms, and <b>no undo</b>. A user who ran relax on a large
 * ontology and disliked the result would have no way back short of reloading and losing everything
 * else they had done.
 *
 * <p>So each operation runs on a throwaway copy and the difference is returned as
 * {@link OWLOntologyChange} objects for the caller to apply through {@code OWLModelManager}. That
 * costs one copy of the axiom set and buys correct events, correct views, and one undo that
 * reverses the whole operation.
 *
 * <p>Pure OWL API and robot-core; no Protege types and no Swing, so the diffing - the part that
 * would silently corrupt an ontology if it were wrong - is unit-testable.
 */
public final class RobotTransform {

    /** The operations offered, and what each one actually does to an ontology. */
    public enum Kind {
        /**
         * {@code robot relax}: rewrites equivalence axioms into subclass axioms.
         *
         * <p>An OBO ontology defines a term with an equivalent-class expression, which most
         * consumers cannot use. Relax adds the weaker subclass form alongside, so a simple tool
         * still sees the hierarchy. Nothing is lost - it only adds.
         */
        RELAX("Relax",
                "Rewrites equivalent-class definitions into the weaker subclass form, adding "
                        + "them alongside the originals. OBO release pipelines do this so that "
                        + "consumers which cannot handle equivalence still see the hierarchy. It "
                        + "only adds axioms; nothing is removed.",
                false),

        /**
         * {@code robot reduce}: removes subclass axioms a reasoner can already infer.
         *
         * <p>The only one here that removes axioms, which is why the result is worth reviewing
         * before it is applied.
         */
        REDUCE("Reduce",
                "Removes redundant subclass axioms - the ones a reasoner can already derive from "
                        + "the others. It makes the asserted hierarchy smaller without changing "
                        + "what the ontology entails. This is the only operation here that "
                        + "removes axioms, so read the result before applying it.",
                true),

        /**
         * {@code robot repair}: fixes annotation assertions that point at the wrong thing.
         */
        REPAIR("Repair",
                "Fixes annotation assertions whose subject is a term that has been merged into "
                        + "another, following the replacement so the annotation lands on the term "
                        + "that survived. Useful after importing from an ontology that has "
                        + "obsoleted terms.",
                false),

        /**
         * {@code robot merge}: brings every imported axiom into the ontology itself.
         *
         * <p>A large and irreversible-feeling change even though undo covers it, so it is
         * described as such.
         */
        MERGE_IMPORTS("Merge imports",
                "Copies every axiom from the imported ontologies into this one and drops the "
                        + "import statements, producing a single self-contained file. This is "
                        + "what a release build does. It can add tens of thousands of axioms, so "
                        + "the result is usually saved as a release artefact rather than kept in "
                        + "the edit file.",
                false);

        private final String label;
        private final String help;
        private final boolean needsReasoner;

        Kind(String label, String help, boolean needsReasoner) {
            this.label = label;
            this.help = help;
            this.needsReasoner = needsReasoner;
        }

        public String getLabel() {
            return label;
        }

        /** A sentence for the parameter dialog's "?", explaining what it does to the ontology. */
        public String getHelp() {
            return help;
        }

        public boolean needsReasoner() {
            return needsReasoner;
        }
    }

    /** What an operation would do, without having done it. */
    public static final class Diff {
        private final List<OWLOntologyChange> changes;
        private final int added;
        private final int removed;
        private final int importsDropped;

        Diff(List<OWLOntologyChange> changes, int added, int removed, int importsDropped) {
            this.changes = Collections.unmodifiableList(changes);
            this.added = added;
            this.removed = removed;
            this.importsDropped = importsDropped;
        }

        /** Apply through {@code OWLModelManager}; never straight into the ontology. */
        public List<OWLOntologyChange> getChanges() {
            return changes;
        }

        public int getAdded() {
            return added;
        }

        public int getRemoved() {
            return removed;
        }

        /**
         * How many import statements would be dropped.
         *
         * <p>Counted apart from the axioms because it is a different kind of change with a
         * different consequence: an ontology that no longer imports anything is self-contained,
         * and saving the edit file in that state loses the import structure the project is
         * maintained in.
         */
        public int getImportsDropped() {
            return importsDropped;
        }

        public boolean isEmpty() {
            return changes.isEmpty();
        }

        @Override
        public String toString() {
            return "+" + added + " / -" + removed
                    + (importsDropped == 0 ? "" : " / -" + importsDropped + " imports");
        }
    }

    private RobotTransform() {
    }

    /**
     * What {@code kind} would change, as changes to apply.
     *
     * @param reasonerFactory needed by {@link Kind#REDUCE}; null falls back to OWL API's
     *     structural reasoner, which computes told subsumptions and is enough to find the
     *     obviously redundant axioms
     * @throws QualityReport.QualityReportException if ROBOT cannot run here, with the same
     *     contract as the report so a caller handles one failure shape
     */
    public static Diff preview(OWLOntology ontology, Kind kind,
            OWLReasonerFactory reasonerFactory) {
        if (ontology == null) {
            throw new IllegalArgumentException("no ontology to transform");
        }
        Set<OWLAxiom> before = new HashSet<OWLAxiom>(ontology.getAxioms());
        OWLOntology copy;
        try {
            copy = copyOf(ontology, kind == Kind.MERGE_IMPORTS);
        } catch (OWLOntologyCreationException cannotCopy) {
            throw new QualityReport.QualityReportException(
                    "Could not copy the ontology to preview the change: "
                            + cannotCopy.getMessage(), cannotCopy);
        }
        try {
            run(copy, kind, reasonerFactory);
        } catch (LinkageError incompatible) {
            throw new QualityReport.QualityReportException(
                    kind.getLabel() + " could not run against this Protege's OWL API. Protege 5.6 "
                            + "or newer is expected to work.", incompatible);
        } catch (RuntimeException failed) {
            throw new QualityReport.QualityReportException(
                    kind.getLabel() + " failed: " + failed.getMessage(), failed);
        } catch (Exception failed) {
            throw new QualityReport.QualityReportException(
                    kind.getLabel() + " failed: " + failed.getMessage(), failed);
        }

        Set<OWLAxiom> after = new HashSet<OWLAxiom>(copy.getAxioms());
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        int added = 0;
        int removed = 0;
        for (OWLAxiom axiom : after) {
            if (!before.contains(axiom)) {
                changes.add(new AddAxiom(ontology, axiom));
                added++;
            }
        }
        for (OWLAxiom axiom : before) {
            if (!after.contains(axiom)) {
                changes.add(new RemoveAxiom(ontology, axiom));
                removed++;
            }
        }

        // An import declaration is not an axiom, so the diff above cannot see it. Merge is
        // defined as producing a single self-contained ontology: leaving the imports in place
        // after copying their axioms in would give a file that both contains the imported
        // axioms and re-imports them, which is not what "merge" means anywhere else and not
        // what the description of the operation promises.
        int importsDropped = 0;
        if (kind == Kind.MERGE_IMPORTS) {
            for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
                changes.add(new RemoveImport(ontology, declaration));
                importsDropped++;
            }
        }
        return new Diff(changes, added, removed, importsDropped);
    }

    private static void run(OWLOntology copy, Kind kind, OWLReasonerFactory reasonerFactory)
            throws Exception {
        switch (kind) {
            case RELAX:
                RelaxOperation.relax(copy);
                return;
            case REDUCE:
                ReduceOperation.reduce(copy, reasonerFactory == null
                        ? new StructuralReasonerFactory() : reasonerFactory);
                return;
            case REPAIR:
                RepairOperation.repair(copy, new IOHelper());
                return;
            case MERGE_IMPORTS:
            default:
                // merge returns the merged ontology rather than mutating, so the axioms are moved
                // across explicitly to keep one diffing path for every operation.
                OWLOntology merged = MergeOperation.merge(copy);
                Set<OWLAxiom> mergedAxioms = new HashSet<OWLAxiom>(merged.getAxioms());
                copy.getOWLOntologyManager().addAxioms(copy, mergedAxioms);
        }
    }

    /**
     * A throwaway ontology with the same axioms.
     *
     * <p>Built in a fresh manager rather than through {@code copyOntology}, so nothing that
     * happens to the copy can reach Protege's own manager - the entire point of the exercise.
     *
     * @param withImports whether to bring the imports closure across, which only merge needs
     */
    private static OWLOntology copyOf(OWLOntology ontology, boolean withImports)
            throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        Set<OWLAxiom> axioms = withImports
                ? new HashSet<OWLAxiom>(ontology.getAxioms(
                        org.semanticweb.owlapi.model.parameters.Imports.INCLUDED))
                : new HashSet<OWLAxiom>(ontology.getAxioms());
        return manager.createOntology(axioms,
                ontology.getOntologyID().getOntologyIRI().isPresent()
                        ? ontology.getOntologyID().getOntologyIRI().get()
                        : org.semanticweb.owlapi.model.IRI.create(
                                "http://www.ontoboard.org/transform-preview"));
    }
}
