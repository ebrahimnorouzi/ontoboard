package de.fizkarlsruhe.ise.ontoboard.reason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.profiles.OWL2DLProfile;
import org.semanticweb.owlapi.profiles.OWL2ELProfile;
import org.semanticweb.owlapi.profiles.OWL2QLProfile;
import org.semanticweb.owlapi.profiles.OWL2RLProfile;
import org.semanticweb.owlapi.profiles.OWLProfile;
import org.semanticweb.owlapi.profiles.OWLProfileReport;
import org.semanticweb.owlapi.profiles.OWLProfileViolation;

/**
 * Which OWL 2 profile an ontology is in, and <em>which axiom</em> takes it out.
 *
 * <p>ROBOT's measure already answers the first half as a yes or no. The half that was missing is
 * attribution, and it is the half a person can act on: "this ontology is not EL" is not a finding,
 * it is a mood. "This axiom, on this term, is why" is a finding.
 *
 * <p>It matters here more than it would elsewhere, because this plugin contradicts itself without
 * it. The scaffold's generated Makefile classifies with ELK. This plugin's own release action
 * classifies with ELK by default. And ELK, as {@code Reasoners.Choice.ELK}'s own help text says,
 * quietly ignores axioms outside the EL profile - it does not warn, it does not fail, it simply
 * reasons over less than you wrote. Meanwhile the relation dialog offers readings that leave EL. So
 * the tool hands somebody a gesture that makes part of their ontology invisible to their own
 * release reasoner, and nothing anywhere says so.
 *
 * <p>Pure OWL API; no Protege types and no Swing.
 */
public final class ProfileCheck {

    /** The OWL 2 profiles, tightest first. */
    public enum Target {
        /**
         * EL: what OBO releases classify with, because it is the one that finishes on a
         * hundred-thousand-class ontology.
         */
        EL("EL", "The profile OBO release builds use, because ELK is the only reasoner that "
                + "finishes on a large ontology. Axioms outside it are ignored by ELK silently - "
                + "not rejected, not warned about, simply not reasoned over."),

        /** QL: query answering over large data. */
        QL("QL", "Aimed at answering queries over large amounts of instance data. Rarely the "
                + "target for a schema-level OBO ontology."),

        /** RL: rule-based implementations. */
        RL("RL", "Aimed at rule-based implementations working with instance data. Rarely the "
                + "target for a schema-level OBO ontology."),

        /**
         * DL: everything a complete OWL 2 reasoner can handle.
         */
        DL("DL", "The whole of OWL 2 that a complete reasoner can handle. Leaving DL is a "
                + "different kind of problem from leaving EL: it usually means something is "
                + "malformed - an undeclared entity, or a property used as two kinds at once - "
                + "rather than merely expressive.");

        private final String label;
        private final String help;

        Target(String label, String help) {
            this.label = label;
            this.help = help;
        }

        public String getLabel() {
            return label;
        }

        public String getHelp() {
            return help;
        }

        OWLProfile profile() {
            switch (this) {
                case QL:
                    return new OWL2QLProfile();
                case RL:
                    return new OWL2RLProfile();
                case DL:
                    return new OWL2DLProfile();
                case EL:
                default:
                    return new OWL2ELProfile();
            }
        }
    }

    /** One axiom that leaves the profile, and what a person can do about it. */
    public static final class Violation {
        private final OWLAxiom axiom;
        private final String message;
        private final Set<IRI> terms;

        Violation(OWLAxiom axiom, String message, Set<IRI> terms) {
            this.axiom = axiom;
            this.message = message;
            this.terms = Collections.unmodifiableSet(terms);
        }

        /** The axiom itself, so it can be found and changed. */
        public OWLAxiom getAxiom() {
            return axiom;
        }

        /** OWL API's own description of what is wrong. */
        public String getMessage() {
            return message;
        }

        /**
         * The terms the axiom is about.
         *
         * <p>The attribution that makes the finding actionable: an axiom string is something to
         * search for, a term is something to open.
         */
        public Set<IRI> getTerms() {
            return terms;
        }

        @Override
        public String toString() {
            return message;
        }
    }

    private ProfileCheck() {
    }

    /** Whether the ontology is inside this profile. */
    public static boolean isIn(OWLOntology ontology, Target target) {
        return ontology != null && target != null
                && target.profile().checkOntology(ontology).isInProfile();
    }

    /**
     * Every axiom that takes the ontology out of the profile.
     *
     * <p>Deduplicated by axiom: OWL API reports one violation per offending construct, and a
     * single axiom with two problems would otherwise appear twice in a list somebody is working
     * through.
     */
    public static List<Violation> violations(OWLOntology ontology, Target target) {
        List<Violation> violations = new ArrayList<Violation>();
        if (ontology == null || target == null) {
            return violations;
        }
        OWLProfileReport report = target.profile().checkOntology(ontology);
        Set<OWLAxiom> seen = new LinkedHashSet<OWLAxiom>();
        for (OWLProfileViolation violation : report.getViolations()) {
            OWLAxiom axiom = violation.getAxiom();
            if (axiom != null && !seen.add(axiom)) {
                continue;
            }
            Set<IRI> terms = new LinkedHashSet<IRI>();
            if (axiom != null) {
                for (OWLEntity entity : axiom.getSignature()) {
                    terms.add(entity.getIRI());
                }
            }
            violations.add(new Violation(axiom, String.valueOf(violation), terms));
        }
        return violations;
    }

    /**
     * The tightest profile this ontology is in, or null when it is outside all of them.
     *
     * <p>Null means outside DL, which is worth distinguishing loudly: it is usually a malformed
     * ontology rather than an expressive one.
     */
    public static Target tightestProfile(OWLOntology ontology) {
        for (Target target : Target.values()) {
            if (isIn(ontology, target)) {
                return target;
            }
        }
        return null;
    }

    /**
     * The tightest profile an axiom on its own would still be in, or null.
     *
     * <p>Computed rather than looked up in a table of "these constructs are EL". A table is a
     * second copy of the OWL 2 specification, it is written from memory, and it goes stale in
     * silence - whereas asking the profile checker is asking the thing that decides.
     *
     * <p>The axiom's entities are declared first. An undeclared entity is a DL violation in its
     * own right, so without the declarations every axiom examined this way would come back
     * "outside DL" and the answer would be about the test harness rather than the axiom.
     */
    public static Target tightestProfileFor(OWLAxiom axiom) {
        if (axiom == null) {
            return null;
        }
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology alone = manager.createOntology(
                    IRI.create("http://www.ontoboard.org/profile-probe"));
            for (OWLEntity entity : axiom.getSignature()) {
                manager.addAxiom(alone, manager.getOWLDataFactory()
                        .getOWLDeclarationAxiom(entity));
            }
            manager.addAxiom(alone, axiom);
            return tightestProfile(alone);
        } catch (OWLOntologyCreationException cannotCheck) {
            return null;
        }
    }

    /**
     * Why writing this axiom would matter, given the profile the project targets, or null.
     *
     * <p>Said at the gesture, not at release time. By release the axiom is one of thousands and
     * whoever wrote it has forgotten which arrow it was; at the moment of writing it, they are
     * looking straight at it.
     */
    public static String warningFor(OWLAxiom axiom, Target target) {
        if (axiom == null || target == null) {
            return null;
        }
        Target actual = tightestProfileFor(axiom);
        if (actual == null) {
            return "This axiom is outside OWL 2 DL, which usually means something is malformed "
                    + "rather than merely expressive. A complete reasoner may refuse the whole "
                    + "ontology.";
        }
        if (actual.ordinal() <= target.ordinal()) {
            return null;
        }
        if (target == Target.EL) {
            return "This axiom is outside OWL 2 EL. OntoBoard's release action and the ODK build "
                    + "it scaffolds both classify with ELK, which ignores axioms it cannot "
                    + "express - silently, without an error or a warning. The axiom will be in "
                    + "the file and the reasoner will not see it. Write it if you mean it, but "
                    + "do not expect the classification to reflect it.";
        }
        return "This axiom is outside OWL 2 " + target.getLabel()
                + ", which this project targets. It is " + actual.getLabel() + ".";
    }
}
