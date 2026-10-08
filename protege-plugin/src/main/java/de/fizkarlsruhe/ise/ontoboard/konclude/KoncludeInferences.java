package de.fizkarlsruhe.ise.ontoboard.konclude;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Turning Konclude's output file into the axioms that are actually new.
 *
 * <p>Konclude writes an OWL 2 XML {@code <Ontology>} holding everything it concluded - which
 * includes a great deal that was already in the ontology it was given. Handing that straight to a
 * user as "the inferences" would be three kinds of wrong at once, so three things are subtracted,
 * each for a reason that was measured rather than assumed.
 *
 * <ol>
 *   <li><b>Axioms the ontology already asserts.</b> Konclude re-emits asserted subsumptions
 *       alongside inferred ones. On a small test ontology its output held 76 {@code SubClassOf}
 *       axioms, most of which were simply read back. Presenting those as inferences would make the
 *       reasoner look as if it had discovered the ontology you wrote.
 *   <li><b>{@code SubClassOf(X, owl:Thing)}.</b> True of everything and informative about nothing.
 *       This is exactly what ROBOT drops under {@code --exclude-tautologies structural}, and this
 *       project has already shipped the version without it once: the dialog showed seven axioms
 *       where {@code make reason} wrote two, and the difference was five tautologies.
 *   <li><b>Declarations.</b> Belt and braces behind
 *       {@code +Konclude.CLI.Output.WriteDeclarations=false} on the command line. Declaring an
 *       entity that already exists is not an inference.
 * </ol>
 *
 * <p>The result is deliberately a plain list of axioms rather than a prepared set of ontology
 * changes: what to do with them - show, save, apply - is the caller's decision, and the caller is
 * the only part that knows which ontology they are going into.
 */
public final class KoncludeInferences {

    private KoncludeInferences() {
    }

    /** What came back, and what was taken out of it. */
    public static final class Result {
        private final List<OWLAxiom> inferred;
        private final int total;
        private final int alreadyAsserted;
        private final int tautologies;
        private final int declarations;

        Result(List<OWLAxiom> inferred, int total, int alreadyAsserted, int tautologies,
                int declarations) {
            this.inferred = Collections.unmodifiableList(inferred);
            this.total = total;
            this.alreadyAsserted = alreadyAsserted;
            this.tautologies = tautologies;
            this.declarations = declarations;
        }

        /** The axioms that are genuinely new to the ontology. */
        public List<OWLAxiom> getInferred() {
            return inferred;
        }

        /** How many axioms Konclude wrote, before anything was subtracted. */
        public int getTotal() {
            return total;
        }

        public int getAlreadyAsserted() {
            return alreadyAsserted;
        }

        public int getTautologies() {
            return tautologies;
        }

        public int getDeclarations() {
            return declarations;
        }

        /**
         * What was dropped and why, for the result to say rather than silently show a smaller
         * number than Konclude reported.
         */
        public String describeDropped() {
            if (alreadyAsserted + tautologies + declarations == 0) {
                return "";
            }
            List<String> parts = new ArrayList<String>();
            if (alreadyAsserted > 0) {
                parts.add(alreadyAsserted + " already asserted in this ontology");
            }
            if (tautologies > 0) {
                parts.add(tautologies + " of the form 'subclass of owl:Thing'");
            }
            if (declarations > 0) {
                parts.add(declarations + " declarations");
            }
            StringBuilder text = new StringBuilder("Konclude wrote " + total + " axioms; ");
            for (int at = 0; at < parts.size(); at++) {
                text.append(at == 0 ? "" : at == parts.size() - 1 ? " and " : ", ");
                text.append(parts.get(at));
            }
            return text.append(" were left out.").toString();
        }
    }

    /**
     * Reads Konclude's output and subtracts what is not news.
     *
     * <p>Loaded with a manager of its own. Konclude's {@code <Ontology>} declares no ontology IRI,
     * so it loads anonymously - which is harmless here because axioms are compared by their own
     * structure and entity IRIs, not by which ontology they came from.
     *
     * @param target the ontology the inferences are about, read with its imports closure
     * @throws IOException if the file is missing or will not parse, which the caller reports as a
     *     failed run rather than as zero inferences
     */
    public static Result read(File output, OWLOntology target) throws IOException {
        if (output == null || !output.isFile()) {
            throw new IOException("Konclude wrote no output file.");
        }
        OWLOntology concluded;
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            concluded = manager.loadOntologyFromOntologyDocument(output);
        } catch (Exception cannotRead) {
            throw new IOException("Could not read what Konclude wrote: " + cannotRead.getMessage(),
                    cannotRead);
        }
        Set<OWLAxiom> asserted = target == null
                ? Collections.<OWLAxiom>emptySet() : target.getAxioms(Imports.INCLUDED);

        List<OWLAxiom> fresh = new ArrayList<OWLAxiom>();
        int total = 0;
        int already = 0;
        int tautologies = 0;
        int declarations = 0;
        for (OWLAxiom axiom : concluded.getAxioms()) {
            total++;
            if (axiom.isOfType(AxiomType.DECLARATION)) {
                declarations++;
                continue;
            }
            if (isTopTautology(axiom)) {
                tautologies++;
                continue;
            }
            // Compared without annotations: Konclude does not carry them, and an asserted axiom
            // that differs from the inferred one only by an editor note is still asserted.
            if (asserted.contains(axiom) || asserted.contains(axiom.getAxiomWithoutAnnotations())) {
                already++;
                continue;
            }
            fresh.add(axiom);
        }
        Collections.sort(fresh);
        return new Result(fresh, total, already, tautologies, declarations);
    }

    /** {@code SubClassOf(X, owl:Thing)}: true of everything, informative about nothing. */
    static boolean isTopTautology(OWLAxiom axiom) {
        if (!(axiom instanceof OWLSubClassOfAxiom)) {
            return false;
        }
        OWLSubClassOfAxiom subclass = (OWLSubClassOfAxiom) axiom;
        return !subclass.getSuperClass().isAnonymous()
                && subclass.getSuperClass().asOWLClass().isOWLThing();
    }
}
